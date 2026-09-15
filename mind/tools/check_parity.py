"""Re-run both frozen parity files against the numpy implementations. Fails on any drift.

    python -m tools.check_parity
    python -m tools.check_parity --tolerance 1e-6 --models ../shared/models

This is the Python half of the contract in `docs/port.md`: the same file the Java port has to
reproduce is first proved to still describe *this* side. It catches the two things that go wrong
quietly -- a featurizer edit that changes the hash or the side vector, and a `shared/models/`
directory that no longer holds the weights its parity numbers were written from. The frozen models
sit in `shared/` beside `mind/` and `combat/`, since both halves of the repository read them, and
every directory the manifest names is relative to `shared/`.

Three checks per model:

* **artefacts** -- every file the manifest names exists, and the weight file's sha256 and the
  layout file's sha256 are the ones the manifest recorded, so a stale copy cannot pass by
  accident;
* **inputs** -- the interpreter's bucket lists exactly, and its side vectors within tolerance;
* **outputs** -- every head's logits, the three floats, and the decision model's scores, within
  tolerance (default 1e-5 absolute).

Exit code 0 when everything matches, 1 on the first model that drifts (both are still reported).
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys

import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# shared/models, beside mind/ and combat/: written by tools/freeze.py, read by both halves.
SHARED_MODELS = os.path.abspath(os.path.join(ROOT, os.pardir, "shared", "models"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from text.classifier import features as clf_features        # noqa: E402
from text.classifier.infer import Classifier                # noqa: E402

DEFAULT_TOLERANCE = 1e-5


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for block in iter(lambda: fh.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def read_jsonl(path: str) -> list:
    with open(path, encoding="utf-8") as fh:
        return [json.loads(line) for line in fh if line.strip()]


class Result:
    """What one model's check found: the worst difference of each kind, and every complaint."""

    def __init__(self, name: str):
        self.name = name
        self.worst = {}
        self.problems = []
        self.records = 0

    def note(self, kind: str, diff: float) -> None:
        self.worst[kind] = max(self.worst.get(kind, 0.0), float(diff))

    def fail(self, message: str) -> None:
        self.problems.append(message)

    @property
    def ok(self) -> bool:
        return not self.problems

    def report(self, tolerance: float) -> str:
        worst = ", ".join("%s %.2e" % (k, v) for k, v in sorted(self.worst.items()))
        head = "%-12s %3d records, worst: %s" % (self.name, self.records, worst or "nothing to compare")
        if self.ok:
            return head + "  OK (tolerance %g)" % tolerance
        return head + "\n" + "\n".join("    FAIL %s" % p for p in self.problems)


def check_artefacts(result: Result, model: dict, root: str) -> None:
    directory = os.path.join(root, model["directory"].replace("/", os.sep))
    weights = os.path.join(directory, model["weights"]["file"])
    layout = os.path.join(directory, "layout.json")
    for path in (weights, layout, os.path.join(directory, model["parity"]["file"])):
        if not os.path.exists(path):
            result.fail("missing %s" % os.path.relpath(path, root))
    if not result.ok:
        return
    got = sha256_file(weights)
    if got != model["weights"]["sha256"]:
        result.fail("%s sha256 is %s, the manifest says %s -- re-run tools/freeze.py"
                    % (model["weights"]["file"], got[:16], model["weights"]["sha256"][:16]))
    with open(layout, "rb") as fh:
        layout_sha = hashlib.sha256(fh.read()).hexdigest()
    if layout_sha != model["layout_sha256"]:
        result.fail("layout.json sha256 is %s, the manifest says %s -- the schema id moved"
                    % (layout_sha[:16], model["layout_sha256"][:16]))

    # The .mbw the mod loads is the same weights in the mod's own format, so it goes stale the
    # moment the .npz moves and is worth the same two lines. A manifest written before
    # tools/mbw.py existed simply names none, and this is skipped.
    weight_file = model["weights"].get("weight_file")
    if not weight_file:
        return
    mbw_path = os.path.join(directory, weight_file["file"])
    if not os.path.exists(mbw_path):
        result.fail("missing %s -- run python -m tools.mbw" % os.path.relpath(mbw_path, root))
        return
    got_mbw = sha256_file(mbw_path)
    if got_mbw != weight_file["sha256"]:
        result.fail("%s sha256 is %s, the manifest says %s -- re-run tools/mbw.py"
                    % (weight_file["file"], got_mbw[:16], weight_file["sha256"][:16]))
    if weight_file["schema_id"] != layout_sha[:8]:
        result.fail("%s carries schema %s but layout.json hashes to %s -- the weight file is stale"
                    % (weight_file["file"], weight_file["schema_id"], layout_sha[:8]))


def check_interpreter(model: dict, root: str, tolerance: float) -> Result:
    result = Result(model["name"])
    check_artefacts(result, model, root)
    if not result.ok:
        return result
    directory = os.path.join(root, model["directory"].replace("/", os.sep))
    clf = Classifier.load(directory)
    records = read_jsonl(os.path.join(directory, model["parity"]["file"]))
    result.records = len(records)
    if len(records) != model["parity"]["records"]:
        result.fail("parity.jsonl holds %d records, the manifest says %d"
                    % (len(records), model["parity"]["records"]))

    for rec in records:
        text, prev = rec["text"], rec["prev_intent"]
        got_buckets = [int(b) for b in clf_features.buckets(text)]
        if got_buckets != [int(b) for b in rec["buckets"]]:
            result.fail("record %d (%s): buckets differ -- %d against %d, first mismatch at %s"
                        % (rec["i"], rec["source"], len(got_buckets), len(rec["buckets"]),
                           _first_difference(got_buckets, rec["buckets"])))
            continue
        side = np.asarray(clf_features.side_features(text, prev), dtype=np.float32)
        result.note("side", np.abs(side - np.asarray(rec["side"], dtype=np.float32)).max())

        h = clf.hidden(text, prev)
        for head, (w, b) in clf.heads.items():
            got = np.asarray(w @ h + b, dtype=np.float32)
            want = np.asarray(rec["logits"][head], dtype=np.float32)
            if got.shape != want.shape:
                result.fail("record %d: %s head is %s, the file holds %s"
                            % (rec["i"], head, got.shape, want.shape))
                continue
            result.note("logits", np.abs(got - want).max())
        raw = clf.floats_w @ h + clf.floats_b
        got = np.array([1.0 / (1.0 + np.exp(-raw[0])), np.tanh(raw[1]),
                        1.0 / (1.0 + np.exp(-raw[2]))], dtype=np.float32)
        result.note("floats", np.abs(got - np.asarray(rec["floats"], dtype=np.float32)).max())

    _judge(result, tolerance)
    return result


def _first_difference(got, want):
    for i, (a, b) in enumerate(zip(got, want)):
        if a != b:
            return "index %d, %d against %d" % (i, a, b)
    return "the end of the shorter list"


def check_decisions(model: dict, root: str, tolerance: float) -> Result:
    from dwarfsim.learn.scorer import LearnedScorer

    result = Result(model["name"])
    check_artefacts(result, model, root)
    if not result.ok:
        return result
    directory = os.path.join(root, model["directory"].replace("/", os.sep))
    # `live=False`: this is the frozen model replayed against its own answer sheet, so its own
    # recorded layout is the truth. A model written against an older `dwarfsim` schema is exactly
    # what this check exists to keep honest; refusing to load it would prove nothing.
    scorer = LearnedScorer.load(directory, live=False)
    records = read_jsonl(os.path.join(directory, model["parity"]["file"]))
    result.records = len(records)
    if len(records) != model["parity"]["records"]:
        result.fail("parity.jsonl holds %d records, the manifest says %d"
                    % (len(records), model["parity"]["records"]))

    for rec in records:
        obs = np.asarray(rec["obs"], dtype=np.float32)
        cand = np.asarray(rec["cand"], dtype=np.float32)
        got = np.float32(scorer.score(obs, cand))
        result.note("score", abs(float(got) - float(np.float32(rec["score"]))))

    _judge(result, tolerance)
    return result


def _judge(result: Result, tolerance: float) -> None:
    for kind, diff in sorted(result.worst.items()):
        if diff > tolerance:
            result.fail("%s drifted by %.3e, past the %g tolerance" % (kind, diff, tolerance))


CHECKS = {"interpreter": check_interpreter, "decisions": check_decisions}


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--models", default=SHARED_MODELS)
    ap.add_argument("--tolerance", type=float, default=DEFAULT_TOLERANCE)
    args = ap.parse_args(argv)

    manifest_path = os.path.join(args.models, "MANIFEST.json")
    if not os.path.exists(manifest_path):
        print("no %s -- run python -m tools.freeze first" % manifest_path)
        return 1
    with open(manifest_path, encoding="utf-8") as fh:
        manifest = json.load(fh)
    root = os.path.dirname(os.path.abspath(args.models))

    failed = 0
    for model in manifest["models"]:
        check = CHECKS.get(model["name"])
        if check is None:
            print("%-12s no check for this model" % model["name"])
            failed += 1
            continue
        result = check(model, root, args.tolerance)
        print(result.report(args.tolerance))
        failed += 0 if result.ok else 1
    print("%d of %d models match" % (len(manifest["models"]) - failed, len(manifest["models"])))
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
