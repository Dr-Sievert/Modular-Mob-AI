"""Tests for the frozen models in ``models/``: the manifest describes them, and parity holds.

    python -m pytest -q tests/test_freeze.py

The one that matters is ``test_check_parity_script``: it runs ``tools/check_parity.py`` exactly as
a person or CI would, and that script re-runs both ``parity.jsonl`` files through the numpy
implementations. A featurizer edit that changes a hash, a side column or a forward pass, and a
``models/`` directory holding weights its parity numbers were not written from, both fail here
rather than in Java six months later.

The rest are cheap structural checks: the manifest's shapes and hashes are the artefacts' own, and
the parity records carry the widths the layout promises.
"""

import json
import os
import subprocess
import sys

import numpy as np
import pytest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

from dwarfsim.schema import CAND_SIZE, OBS_SIZE  # noqa: E402
from text.classifier.features import SIDE_DIM  # noqa: E402

MODELS = os.path.join(ROOT, "models")


@pytest.fixture(scope="module")
def manifest():
    path = os.path.join(MODELS, "MANIFEST.json")
    if not os.path.exists(path):
        pytest.skip("models/MANIFEST.json is not built; run python -m tools.freeze")
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


@pytest.fixture(scope="module")
def by_name(manifest):
    return {m["name"]: m for m in manifest["models"]}


def read_jsonl(path):
    with open(path, encoding="utf-8") as fh:
        return [json.loads(line) for line in fh if line.strip()]


def test_check_parity_script(manifest):
    """The real check: both models re-run against their frozen answer sheets."""
    proc = subprocess.run([sys.executable, "-m", "tools.check_parity"], cwd=ROOT,
                          capture_output=True, text=True)
    assert proc.returncode == 0, proc.stdout + proc.stderr
    assert "%d of %d models match" % (len(manifest["models"]), len(manifest["models"])) \
        in proc.stdout
    for model in manifest["models"]:
        assert model["name"] in proc.stdout


def test_check_parity_catches_drift(tmp_path, by_name):
    """A single nudged score fails the check, so a passing run means something."""
    import shutil

    copy = tmp_path / "models"
    shutil.copytree(MODELS, copy)
    path = copy / "decisions" / "parity.jsonl"
    records = read_jsonl(str(path))
    records[0]["score"] = float(records[0]["score"]) + 1e-3
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        for rec in records:
            fh.write(json.dumps(rec) + "\n")

    proc = subprocess.run([sys.executable, "-m", "tools.check_parity", "--models", str(copy)],
                          cwd=ROOT, capture_output=True, text=True)
    assert proc.returncode == 1
    assert "drifted" in proc.stdout


def test_manifest_matches_the_artefacts(manifest):
    for model in manifest["models"]:
        directory = os.path.join(ROOT, model["directory"].replace("/", os.sep))
        assert os.path.isdir(directory)
        for name in ("layout.json", "README.md", model["parity"]["file"],
                     model["weights"]["file"]):
            assert os.path.exists(os.path.join(directory, name)), name
        with np.load(os.path.join(directory, model["weights"]["file"])) as npz:
            names = list(npz.files)
            assert names == [a["name"] for a in model["weights"]["arrays"]], model["name"]
            for array in model["weights"]["arrays"]:
                assert list(npz[array["name"]].shape) == array["shape"]
                assert npz[array["name"]].dtype == np.float32


def test_manifest_carries_the_provenance(manifest):
    """A frozen model says what it was trained on, when, and how well it did."""
    for model in manifest["models"]:
        assert model["schema_id"]
        assert len(model["layout_sha256"]) == 64
        assert model["training_data"]["date"]
        assert model["training_data"]["sha256"], model["name"]
        assert model["metrics"]


def test_interpreter_parity_records(by_name):
    model = by_name["interpreter"]
    records = read_jsonl(os.path.join(ROOT, model["directory"], model["parity"]["file"]))
    assert len(records) == model["parity"]["records"] == 200
    assert [r["i"] for r in records] == list(range(len(records)))
    sources = {r["source"].split(":")[0] for r in records}
    assert {"edge", "hardcase", "generated", "corpus"} <= sources
    assert {r["source"] for r in records if r["source"].startswith("edge")} == {
        "edge:empty", "edge:one_word", "edge:forty_words", "edge:all_caps",
        "edge:punctuation_only", "edge:unicode_quotes", "edge:name_first", "edge:name_last"}
    assert any(r["prev_intent"] for r in records)
    heads = {"intent": 13, "topic": 12, "addressed": 4, "sincerity": 3}
    for rec in records:
        assert rec["buckets"], "the bag is never empty, not even for %r" % rec["text"]
        assert len(rec["side"]) == SIDE_DIM
        assert len(rec["floats"]) == 3
        for head, width in heads.items():
            assert len(rec["logits"][head]) == width


def test_decisions_parity_records(by_name):
    model = by_name["decisions"]
    records = read_jsonl(os.path.join(ROOT, model["directory"], model["parity"]["file"]))
    assert len(records) == model["parity"]["records"] == 200
    for rec in records:
        assert len(rec["obs"]) == OBS_SIZE
        assert len(rec["cand"]) == CAND_SIZE
        assert isinstance(rec["score"], float)
    # Round-robin over the skills: no skill may take more than a handful of the 200.
    counts = {}
    for rec in records:
        counts[rec["skill"]] = counts.get(rec["skill"], 0) + 1
    assert len(counts) >= 15
    assert max(counts.values()) <= 15
