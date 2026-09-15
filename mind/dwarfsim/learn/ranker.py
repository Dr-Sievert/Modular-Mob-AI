"""The learned chooser: a ranker over bank lines, trained on the bank itself.

:func:`dwarfsim.replybank.score` is a weighted tag match somebody tuned by hand. It is a good
default and it is also, obviously, a linear model over a one-hot encoding of two tag sets. This
module trains the small MLP that replaces it, against exactly the interface
:mod:`dwarfsim.replybank` calls -- the same seam, and the same shape, as the arbitrator's
``score(observation, candidate_features)``.

**Where the training data comes from.** The bank itself, and nothing else. Every line was written
for a set of tags: that set is the construction it is the right answer to, so the line is its own
positive. The negatives are the lines with the *same act* and a different state key -- which is
the discrimination that actually matters, because the act is already decided by the planner by
the time the chooser is asked anything. A ranker that can tell the ``hostile`` MOCK from the
``cold`` MOCK is a ranker that has learned the axis the bank was written along.

    python -m dwarfsim.learn.ranker --epochs 40 --out runs/learn/ranker
    python -m dwarfsim.learn.ranker --bank text/data/replies.jsonl --out runs/learn/ranker

**The layout.** ``observation`` is the construction's ten tags, one-hot, and ``candidate`` is one
line's ten tags plus how many slots it carries. Both are fixed and written into the exported
json, the way ``dwarfsim.schema`` writes the sim's own.

**Using it.** Pass the loaded :class:`RankerScorer` to ``replies.reply(..., ranker=...)`` or
``replybank.choose(..., ranker=...)``. Nothing switches to it on its own: the weighted match stays
the default until a held-out number says otherwise, and the repetition penalty stays outside the
model either way, because it is about this conversation rather than about this line.

This does **not** touch ``shared/models``. It writes its own pair, in the same two-file format
``dwarfsim.learn.scorer`` uses, and the port takes it on when there is something worth porting.
"""

import argparse
import json
import os
import random

import numpy as np

from ..replybank import HEARS_FIELDS, STATE_FIELDS, Bank, WEIGHTS, load
from ..speechplan import (ABOUT, ACTS, CONDITIONS, HEAT, INTENTS, MOODS, NEWS, SINCERITY,
                          SUSPICION, TOPICS, TRUST)

#: Bumped whenever the layout below or the forward pass changes.
EXPORT_SCHEMA_ID = "dwarfsim-ranker-v1"

#: The order every one-hot block is written in. Appended to, never reordered.
TAG_VALUES = {
    "intent": INTENTS, "about": ABOUT, "news": NEWS, "topic": TOPICS,
    "sincerity": SINCERITY, "heat": HEAT,
    "mood": MOODS, "trust": TRUST, "condition": CONDITIONS, "suspicion": SUSPICION,
}
TAG_ORDER = HEARS_FIELDS + STATE_FIELDS

#: Where each block starts, and how long the whole vector is.
OFFSETS = {}
_at = 0
for _field in TAG_ORDER:
    OFFSETS[_field] = _at
    _at += len(TAG_VALUES[_field])
TAG_SIZE = _at

#: The construction half: its ten tags and nothing else.
OBS_SIZE = TAG_SIZE
#: The candidate half: the line's ten tags, its act one-hot, and how many slots it carries.
CAND_ACT = TAG_SIZE
CAND_SLOTS = CAND_ACT + len(ACTS)
CAND_SIZE = CAND_SLOTS + 1

HIDDEN = (48, 48)

#: The same six arrays, in the same order, as ``dwarfsim.learn.scorer``.
ARRAY_ORDER = [
    ("fc1.weight", "first hidden layer, (hidden1, OBS_SIZE + CAND_SIZE)"),
    ("fc1.bias", "(hidden1,)"),
    ("fc2.weight", "second hidden layer, (hidden2, hidden1)"),
    ("fc2.bias", "(hidden2,)"),
    ("out.weight", "the score, (1, hidden2)"),
    ("out.bias", "(1,)"),
]

FORWARD = [
    "x = construction_tags ++ line_tags ++ line_act ++ [slot count / 3]",
    "h = relu(x @ fc1.weight.T + fc1.bias)",
    "h = relu(h @ fc2.weight.T + fc2.bias)",
    "score = (h @ out.weight.T + out.bias)[0]",
]


def _one_hot(vec, field, value):
    values = TAG_VALUES[field]
    if value in values:
        vec[OFFSETS[field] + values.index(value)] = 1.0


def construction_vector(construction):
    """The ``observation`` half: what the planner asked for, as ``OBS_SIZE`` floats."""
    vec = np.zeros(OBS_SIZE, dtype=np.float32)
    for field in HEARS_FIELDS:
        _one_hot(vec, field, construction.hears.get(field))
    for field in STATE_FIELDS:
        _one_hot(vec, field, construction.state.get(field))
    return vec


def tags_vector(hears, state):
    vec = np.zeros(TAG_SIZE, dtype=np.float32)
    for field in HEARS_FIELDS:
        _one_hot(vec, field, hears.get(field))
    for field in STATE_FIELDS:
        _one_hot(vec, field, state.get(field))
    return vec


def line_vector(line):
    """The ``candidate`` half: one bank line, as ``CAND_SIZE`` floats."""
    vec = np.zeros(CAND_SIZE, dtype=np.float32)
    vec[:TAG_SIZE] = tags_vector(line.hears, line.state)
    if line.act in ACTS:
        vec[CAND_ACT + ACTS.index(line.act)] = 1.0
    vec[CAND_SLOTS] = min(1.0, len(line.slots) / 3.0)
    return vec


# ---------------------------------------------------------------------------
# The exported model, in numpy. No torch to run it, the same as everything else here.
# ---------------------------------------------------------------------------


class RankerScorer:
    """A trained ranker. ``score_all(observation, candidates) -> (n,)``, the bank's own seam."""

    __slots__ = ("w1o", "w1c", "b1", "w2", "b2", "w3", "b3", "meta")

    def __init__(self, arrays, meta=None):
        w1 = np.asarray(arrays["fc1.weight"], dtype=np.float32)
        if w1.shape[1] != OBS_SIZE + CAND_SIZE:
            raise ValueError("fc1 expects %d inputs, this model wants %d -- stale weights?"
                             % (OBS_SIZE + CAND_SIZE, w1.shape[1]))
        self.w1o = np.ascontiguousarray(w1[:, :OBS_SIZE].T)
        self.w1c = np.ascontiguousarray(w1[:, OBS_SIZE:].T)
        self.b1 = np.asarray(arrays["fc1.bias"], dtype=np.float32)
        self.w2 = np.ascontiguousarray(np.asarray(arrays["fc2.weight"], dtype=np.float32).T)
        self.b2 = np.asarray(arrays["fc2.bias"], dtype=np.float32)
        self.w3 = np.ascontiguousarray(np.asarray(arrays["out.weight"], dtype=np.float32).T)
        self.b3 = np.asarray(arrays["out.bias"], dtype=np.float32)
        self.meta = meta or {}

    @classmethod
    def load(cls, path):
        npz_path, json_path = resolve(path)
        with np.load(npz_path) as fh:
            arrays = {name: fh[name] for name, _ in ARRAY_ORDER}
        meta = {}
        if os.path.exists(json_path):
            with open(json_path, encoding="utf-8") as fh:
                meta = json.load(fh)
            got = meta.get("schema_id")
            if got not in (None, EXPORT_SCHEMA_ID):
                raise ValueError("ranker was exported as %s, this build reads %s"
                                 % (got, EXPORT_SCHEMA_ID))
        return cls(arrays, meta)

    def score_all(self, observation, features):
        cand = np.asarray(features, dtype=np.float32).reshape(-1, CAND_SIZE)
        obs = np.asarray(observation, dtype=np.float32).reshape(OBS_SIZE)
        h = cand @ self.w1c
        h += obs @ self.w1o + self.b1
        np.maximum(h, 0.0, out=h)
        h = h @ self.w2 + self.b2
        np.maximum(h, 0.0, out=h)
        return (h @ self.w3 + self.b3).reshape(-1)

    def score(self, observation, features):
        return float(self.score_all(observation, features)[0])

    def __repr__(self):
        return "<RankerScorer %d-%d-%d-1>" % (OBS_SIZE + CAND_SIZE, self.b1.shape[0],
                                              self.b2.shape[0])


def resolve(path):
    """``runs/learn/ranker``, ``...npz``, ``...json`` or the directory: the same pair."""
    path = os.path.normpath(path)
    if os.path.isdir(path):
        found = sorted(f for f in os.listdir(path) if f.endswith(".npz"))
        if not found:
            raise FileNotFoundError("no .npz in %s" % path)
        prefix = os.path.join(path, found[0][:-4])
    elif path.endswith(".npz") or path.endswith(".json"):
        prefix = os.path.splitext(path)[0]
    else:
        prefix = path
    return prefix + ".npz", prefix + ".json"


def export(arrays, out_prefix, meta):
    """Write ``<prefix>.npz`` and ``<prefix>.json``, the shape ``tools/freeze.py`` reads."""
    directory = os.path.dirname(os.path.abspath(out_prefix))
    if directory:
        os.makedirs(directory, exist_ok=True)
    ordered = {}
    for name, _ in ARRAY_ORDER:
        if name not in arrays:
            raise KeyError("missing weight array %r" % name)
        ordered[name] = np.asarray(arrays[name], dtype=np.float32)
    npz_path = out_prefix + ".npz"
    with open(npz_path, "wb") as fh:
        np.savez(fh, **ordered)
    meta = dict(meta)
    meta.setdefault("schema_id", EXPORT_SCHEMA_ID)
    meta["input"] = {
        "layout": "construction_tags ++ line_tags ++ line_act ++ slot_count",
        "obs_size": OBS_SIZE,
        "cand_size": CAND_SIZE,
        "size": OBS_SIZE + CAND_SIZE,
        "tag_order": list(TAG_ORDER),
        "tag_offsets": dict(OFFSETS),
        "tag_values": {k: list(v) for k, v in TAG_VALUES.items()},
        "acts": list(ACTS),
    }
    meta["forward"] = FORWARD
    meta["weights"] = {
        "file": os.path.basename(npz_path),
        "dtype": "float32",
        "linear_layout": "torch: weight is (out, in), y = x @ weight.T + bias",
        "bytes": os.path.getsize(npz_path),
        "arrays": [{"name": n, "role": role} for n, role in ARRAY_ORDER],
        "shapes": {n: list(ordered[n].shape) for n, _ in ARRAY_ORDER},
    }
    with open(out_prefix + ".json", "w", encoding="utf-8", newline="\n") as fh:
        json.dump(meta, fh, indent=2)
        fh.write("\n")
    return meta


# ---------------------------------------------------------------------------
# Training pairs, out of the bank itself
# ---------------------------------------------------------------------------


def pairs(bank, negatives=5, seed=1):
    """``[(observation, [candidate vectors], right answer index)]``, one per line in the bank.

    The line's own tags are the construction it answers; the line itself is the positive; the
    negatives are other lines with the same act and a different state key, because those are
    the ones a chooser has to tell apart. A line whose act has no such sibling is skipped -- it
    would be a ranking problem with one candidate, which teaches nothing.
    """
    rng = random.Random(seed)
    out = []
    for line in bank.lines:
        obs = tags_vector(line.hears, line.state)
        siblings = [other for other in bank.for_act(line.act)
                    if other.lid != line.lid and other.state_key() != line.state_key()]
        if not siblings:
            continue
        rng.shuffle(siblings)
        chosen = siblings[:negatives]
        cands = [line] + chosen
        order = list(range(len(cands)))
        rng.shuffle(order)
        vectors = [line_vector(cands[i]) for i in order]
        out.append((obs, vectors, order.index(0)))
    return out


def split(rows, holdout=0.2, seed=1):
    rng = random.Random(seed)
    rows = list(rows)
    rng.shuffle(rows)
    cut = max(1, int(len(rows) * holdout))
    return rows[cut:], rows[:cut]


def baseline_accuracy(rows, bank):
    """What the hand-weighted match gets on the same rows, so a number has something to beat.

    The weighted match with no dialogue state and no slot filter is exactly
    ``sum(WEIGHTS[field])`` over the agreeing tags, which is what the vectors above encode, so
    this is computed here rather than by building fake constructions.
    """
    weights = np.zeros(TAG_SIZE, dtype=np.float32)
    for field in TAG_ORDER:
        lo = OFFSETS[field]
        weights[lo:lo + len(TAG_VALUES[field])] = WEIGHTS[field]
    right = 0
    for obs, cands, answer in rows:
        scores = [float(((obs * weights) * c[:TAG_SIZE]).sum()) for c in cands]
        if int(np.argmax(scores)) == answer:
            right += 1
    return right / max(1, len(rows))


def accuracy(scorer, rows):
    right = 0
    for obs, cands, answer in rows:
        if int(np.argmax(scorer.score_all(obs, cands))) == answer:
            right += 1
    return right / max(1, len(rows))


# ---------------------------------------------------------------------------
# Training. Torch only here, and one thread, always.
# ---------------------------------------------------------------------------


def train(rows, epochs=40, lr=0.01, hidden=HIDDEN, seed=1, log=None):
    """Listwise cross-entropy over each row's candidates. Returns the six float32 arrays."""
    import torch
    from torch import nn

    torch.set_num_threads(1)
    torch.manual_seed(seed)

    net = nn.Sequential(
        nn.Linear(OBS_SIZE + CAND_SIZE, hidden[0]), nn.ReLU(),
        nn.Linear(hidden[0], hidden[1]), nn.ReLU(),
        nn.Linear(hidden[1], 1),
    )
    opt = torch.optim.Adam(net.parameters(), lr=lr)
    batches = []
    for obs, cands, answer in rows:
        x = np.concatenate([np.tile(obs, (len(cands), 1)), np.asarray(cands)], axis=1)
        batches.append((torch.from_numpy(x.astype("float32")), answer))

    order = list(range(len(batches)))
    rng = random.Random(seed)
    for epoch in range(epochs):
        rng.shuffle(order)
        total = 0.0
        for i in order:
            x, answer = batches[i]
            scores = net(x).squeeze(-1).unsqueeze(0)
            loss = nn.functional.cross_entropy(scores, torch.tensor([answer]))
            opt.zero_grad()
            loss.backward()
            opt.step()
            total += float(loss)
        if log is not None:
            log(epoch, total / max(1, len(batches)))

    state = net.state_dict()
    return {
        "fc1.weight": state["0.weight"].detach().numpy().astype("float32"),
        "fc1.bias": state["0.bias"].detach().numpy().astype("float32"),
        "fc2.weight": state["2.weight"].detach().numpy().astype("float32"),
        "fc2.bias": state["2.bias"].detach().numpy().astype("float32"),
        "out.weight": state["4.weight"].detach().numpy().astype("float32"),
        "out.bias": state["4.bias"].detach().numpy().astype("float32"),
    }


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--bank", default=None, help="a replies jsonl (default: the loaded bank)")
    ap.add_argument("--out", default=os.path.join("runs", "learn", "ranker"))
    ap.add_argument("--epochs", type=int, default=40)
    ap.add_argument("--lr", type=float, default=0.01)
    ap.add_argument("--negatives", type=int, default=5)
    ap.add_argument("--holdout", type=float, default=0.2)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args(argv)

    bank = Bank.read(args.bank) if args.bank else load()
    rows = pairs(bank, negatives=args.negatives, seed=args.seed)
    if not rows:
        print("the bank has no act with two different state keys: nothing to rank")
        return 1
    train_rows, test_rows = split(rows, holdout=args.holdout, seed=args.seed)
    print("bank %s: %d lines, %d ranking problems (%d train, %d held out)"
          % (bank.source, len(bank), len(rows), len(train_rows), len(test_rows)))

    def log(epoch, loss):
        if not args.quiet and (epoch % 5 == 0 or epoch == args.epochs - 1):
            print("  epoch %3d  loss %.4f" % (epoch, loss))

    arrays = train(train_rows, epochs=args.epochs, lr=args.lr, seed=args.seed, log=log)
    scorer = RankerScorer(arrays)
    train_acc = accuracy(scorer, train_rows)
    test_acc = accuracy(scorer, test_rows)
    base = baseline_accuracy(test_rows, bank)
    print("held-out accuracy: %.1f%%  (train %.1f%%)" % (100 * test_acc, 100 * train_acc))
    print("the hand-weighted match on the same rows: %.1f%%" % (100 * base))
    print("%s" % ("the ranker is ahead" if test_acc > base else
                  "the weighted match is still the one to beat -- it stays the default"))

    meta = export(arrays, args.out, {
        "bank": bank.source,
        "lines": len(bank),
        "problems": {"total": len(rows), "train": len(train_rows), "held_out": len(test_rows)},
        "negatives_per_problem": args.negatives,
        "metrics": {"held_out_accuracy": round(test_acc, 4),
                    "train_accuracy": round(train_acc, 4),
                    "weighted_match_accuracy": round(base, 4)},
        "cli": {"epochs": args.epochs, "lr": args.lr, "seed": args.seed,
                "holdout": args.holdout},
        "note": ("not wired in by default: replybank.score stays the chooser until a held-out "
                 "number says otherwise. Pass a loaded RankerScorer as replies.reply(ranker=...)"),
    })
    print("wrote %s.npz and %s.json (%d bytes of weights)"
          % (args.out, args.out, meta["weights"]["bytes"]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
