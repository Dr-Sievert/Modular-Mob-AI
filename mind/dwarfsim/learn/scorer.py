"""The exported scorer, in pure numpy, and the file format it is exported to.

Two files, the same spirit as ``text/classifier/export.py``:

    imitator.npz    float32 arrays, ARRAY_ORDER below and nothing else
    imitator.json   the schema id, the dims, the layout, the CLI that made it, the metrics

Layout conventions, repeated inside the json so a reader never has to guess:

* the input is ``observation ++ candidate_features``, ``OBS_SIZE + CAND_SIZE`` floats, in that
  order, both laid out by :mod:`dwarfsim.schema`;
* a Linear is stored the way torch stores it, ``weight`` shaped ``(out, in)``, so one layer is
  ``y = x @ weight.T + bias``;
* the forward pass is ``relu``, ``relu``, then a bare linear to one float. No normalisation, no
  batchnorm, no embeddings: six arrays and two matrix products, which is what makes it a
  ``.mbw``-shaped thing the Java port can load.

The scoring interface is the arbitrator's, unchanged::

    scorer.score(observation, candidate_features) -> float

plus :meth:`LearnedScorer.score_all`, which is the same thing for every candidate of one decision
at once -- the observation half of the first layer is computed once and reused.
"""

import json
import os

import numpy as np

from ..schema import CAND_SIZE, OBS_SIZE, SCHEMA_ID

#: Bumped whenever the arrays or the forward pass below change.
EXPORT_SCHEMA_ID = "dwarfsim-imitator-v1"

#: The fixed order of imitator.npz. npz is a dict, but this is the order a Java loader walks.
ARRAY_ORDER = [
    ("fc1.weight", "first hidden layer, (hidden1, OBS_SIZE + CAND_SIZE)"),
    ("fc1.bias", "(hidden1,)"),
    ("fc2.weight", "second hidden layer, (hidden2, hidden1)"),
    ("fc2.bias", "(hidden2,)"),
    ("out.weight", "the score, (1, hidden2)"),
    ("out.bias", "(1,)"),
]

FORWARD = [
    "x = observation ++ candidate_features",
    "h = relu(x @ fc1.weight.T + fc1.bias)",
    "h = relu(h @ fc2.weight.T + fc2.bias)",
    "score = (h @ out.weight.T + out.bias)[0]",
]


def resolve(path):
    """``runs/learn/imitator``, ``runs/learn/imitator.npz``, ``runs/learn/imitator.json`` or the
    directory holding them all name the same pair. Returns ``(npz path, json path)``."""
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


class LearnedScorer:
    """A trained scorer, loaded from the two exported files. No torch, no dependencies but numpy.

    Drop-in for :func:`dwarfsim.arbitrator.score`: pass one to ``World(scorer=...)`` or
    ``run --scorer`` and the weight table stops being consulted.
    """

    __slots__ = ("w1o", "w1c", "b1", "w2", "b2", "w3", "b3", "meta")

    def __init__(self, arrays, meta=None):
        w1 = np.asarray(arrays["fc1.weight"], dtype=np.float32)
        if w1.shape[1] != OBS_SIZE + CAND_SIZE:
            raise ValueError("fc1 expects %d inputs, this model wants %d -- stale weights?"
                             % (OBS_SIZE + CAND_SIZE, w1.shape[1]))
        # The observation half and the candidate half of the first layer, split once so a whole
        # decision shares one observation product.
        self.w1o = np.ascontiguousarray(w1[:, :OBS_SIZE].T)     # (OBS_SIZE, hidden1)
        self.w1c = np.ascontiguousarray(w1[:, OBS_SIZE:].T)     # (CAND_SIZE, hidden1)
        self.b1 = np.asarray(arrays["fc1.bias"], dtype=np.float32)
        self.w2 = np.ascontiguousarray(np.asarray(arrays["fc2.weight"], dtype=np.float32).T)
        self.b2 = np.asarray(arrays["fc2.bias"], dtype=np.float32)
        self.w3 = np.ascontiguousarray(np.asarray(arrays["out.weight"], dtype=np.float32).T)
        self.b3 = np.asarray(arrays["out.bias"], dtype=np.float32)
        self.meta = meta or {}

    # -- loading -----------------------------------------------------------

    @classmethod
    def load(cls, path):
        """Load from a prefix (``runs/learn/imitator``) or the directory holding the pair."""
        npz_path, json_path = resolve(path)
        with np.load(npz_path) as fh:
            arrays = {name: fh[name] for name, _ in ARRAY_ORDER}
        meta = {}
        if os.path.exists(json_path):
            with open(json_path, encoding="utf-8") as fh:
                meta = json.load(fh)
            got = meta.get("dwarfsim_schema_id")
            if got not in (None, SCHEMA_ID):
                raise ValueError("model was trained against %s, this sim is %s" % (got, SCHEMA_ID))
        return cls(arrays, meta)

    # -- scoring -----------------------------------------------------------

    def score_all(self, observation, features):
        """Every candidate of one decision at once. Returns a ``(n,)`` float32 array."""
        cand = np.asarray(features, dtype=np.float32).reshape(-1, CAND_SIZE)
        obs = np.asarray(observation, dtype=np.float32).reshape(OBS_SIZE)
        h = cand @ self.w1c
        h += obs @ self.w1o + self.b1
        np.maximum(h, 0.0, out=h)
        h = h @ self.w2 + self.b2
        np.maximum(h, 0.0, out=h)
        return (h @ self.w3 + self.b3).reshape(-1)

    def score(self, observation, features):
        """``score(observation, candidate_features) -> float``, the arbitrator's own signature."""
        return float(self.score_all(observation, features)[0])

    def __repr__(self):
        return "<LearnedScorer %d-%d-%d-1>" % (
            self.w1o.shape[0] + self.w1c.shape[0], self.b1.shape[0], self.b2.shape[0])


# ---------------------------------------------------------------------------
# Writing the pair
# ---------------------------------------------------------------------------


def export(arrays, out_prefix, meta):
    """Write ``<prefix>.npz`` and ``<prefix>.json``. Returns the metadata actually written."""
    directory = os.path.dirname(os.path.abspath(out_prefix))
    if directory:
        os.makedirs(directory, exist_ok=True)
    ordered = {}
    for name, _ in ARRAY_ORDER:
        if name not in arrays:
            raise KeyError("missing weight array %r" % name)
        ordered[name] = np.asarray(arrays[name], dtype=np.float32)
    extra = set(arrays) - {n for n, _ in ARRAY_ORDER}
    if extra:
        raise KeyError("weights missing from ARRAY_ORDER: %s" % sorted(extra))
    npz_path = out_prefix + ".npz"
    with open(npz_path, "wb") as fh:
        np.savez(fh, **ordered)
    meta = dict(meta)
    meta.setdefault("schema_id", EXPORT_SCHEMA_ID)
    meta["dwarfsim_schema_id"] = SCHEMA_ID
    meta["input"] = {
        "layout": "observation ++ candidate_features",
        "obs_size": OBS_SIZE,
        "cand_size": CAND_SIZE,
        "size": OBS_SIZE + CAND_SIZE,
        "schema": "dwarfsim.schema -- OBS_* and CAND_* offsets",
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
