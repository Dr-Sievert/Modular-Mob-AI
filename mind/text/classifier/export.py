"""Export the trained torch model to two plain files the Java port can read.

    weights.npz   float32 arrays, ARRAY_ORDER below and nothing else
    model.json    dims, bucket count, hash description, label lists in order,
                  tokenizer rules, the metrics, the training set size and hash

Layout conventions, repeated in model.json so a reader never has to guess:

* a Linear is stored the way torch stores it, `weight` shaped (out, in), so the
  forward pass is `y = x @ weight.T + bias`;
* `emb` is (buckets, dim) and a bag is the plain sum of its rows;
* `fc1.weight` is (hidden, dim + side): its first `dim` columns multiply the
  post-ReLU embedding sum and the remaining `side` columns multiply the dense
  side vector, in the exact order of `model.json`'s `side_features.order`;
* `head_floats` rows are `labels.FLOAT_FIELDS` = aggression, valence, urgency,
  with activations `labels.FLOAT_ACTIVATIONS` = sigmoid, tanh, sigmoid.
"""

from __future__ import annotations

import datetime as _dt
import json
import os

import numpy as np

from . import features
from .labels import (ADDRESSED, FLOAT_ACTIVATIONS, FLOAT_FIELDS, INTENTS, NAME_POOL,
                     OPTIONAL_ENUM_FIELDS, SINCERITY, TOPICS)

# v2 added the two position-tagged word features and the dense side vector, so
# fc1's input grew from `dim` to `dim + side`. A v1 reader cannot load a v2 file.
SCHEMA_ID = "dialogue-clf-v2"

# The fixed order of weights.npz. npz is a dict, but the order is what the Java
# loader walks, and it is asserted in tests/test_classifier.py.
ARRAY_ORDER = [
    ("emb.weight", "embedding bag rows, (buckets, dim), summed over a text's buckets"),
    ("fc1.weight", "hidden layer, (hidden, dim + side): the first `dim` columns "
                   "take the embedding sum, the rest take the side vector"),
    ("fc1.bias", "hidden layer bias, (hidden,)"),
    ("head_intent.weight", "intent logits, (13, hidden)"),
    ("head_intent.bias", "(13,)"),
    ("head_topic.weight", "topic logits, (12, hidden)"),
    ("head_topic.bias", "(12,)"),
    ("head_addressed.weight", "addressed logits, (4, hidden)"),
    ("head_addressed.bias", "(4,)"),
    ("head_sincerity.weight", "sincerity logits, (3, hidden)"),
    ("head_sincerity.bias", "(3,)"),
    ("head_floats.weight", "aggression/valence/urgency, (3, hidden)"),
    ("head_floats.bias", "(3,)"),
]

FORWARD = [
    "h = relu(sum of emb[b] for b in buckets(text))          # dim floats",
    "s = side_features(raw text, prev_intent)                # side floats, see side_features",
    "h = relu(concat(h, s) @ fc1.weight.T + fc1.bias)",
    "intent    = argmax(h @ head_intent.weight.T + head_intent.bias)",
    "topic     = argmax(h @ head_topic.weight.T + head_topic.bias)",
    "addressed = argmax(h @ head_addressed.weight.T + head_addressed.bias)",
    "sincerity = argmax(h @ head_sincerity.weight.T + head_sincerity.bias)",
    "floats    = [sigmoid, tanh, sigmoid] of (h @ head_floats.weight.T + head_floats.bias)",
    "names     = exact matches of the name pool in the raw text, not predicted",
]


def weights_dict(model) -> dict:
    state = model.state_dict()
    out = {}
    for name, _ in ARRAY_ORDER:
        if name not in state:
            raise KeyError(f"model has no parameter {name!r}")
        out[name] = state[name].detach().cpu().numpy().astype(np.float32)
    extra = set(state) - {n for n, _ in ARRAY_ORDER}
    if extra:
        raise KeyError(f"model has parameters missing from ARRAY_ORDER: {sorted(extra)}")
    return out


def model_json(model, metrics: dict | None = None) -> dict:
    return {
        "schema_id": SCHEMA_ID,
        "created": _dt.datetime.now().replace(microsecond=0).isoformat(),
        "dims": {
            "buckets": int(model.buckets),
            "embedding": int(model.dim),
            "side": int(model.side),
            "fc1_in": int(model.dim) + int(model.side),
            "hidden": int(model.hidden),
            "heads": {"intent": len(INTENTS), "topic": len(TOPICS),
                      "addressed": len(ADDRESSED), "sincerity": len(SINCERITY),
                      "floats": len(FLOAT_FIELDS)},
        },
        "hash": {
            "name": "FNV-1a, 32-bit",
            "offset_basis": "0x811c9dc5",
            "prime": "0x01000193",
            "input": "the UTF-8 bytes of the feature string",
            "step": "h ^= byte; h = (h * prime) & 0xffffffff",
            "fold": "bucket = h & (buckets - 1), buckets is a power of two",
            "known_vectors": {"": "0x811c9dc5", "a": "0xe40c292c",
                              "foobar": "0xbf9cf968"},
        },
        "labels": {
            "intent": INTENTS,
            "topic": TOPICS,
            "addressed": ADDRESSED,
            "sincerity": SINCERITY,
            "floats": FLOAT_FIELDS,
            "float_activations": FLOAT_ACTIVATIONS,
            "names": NAME_POOL,
        },
        "optional_labels": list(OPTIONAL_ENUM_FIELDS),
        "tokenizer": features.tokenizer_rules(),
        "side_features": features.side_feature_rules(),
        "forward": FORWARD,
        "weights": {
            "file": "weights.npz",
            "dtype": "float32",
            "linear_layout": "torch: weight is (out, in), y = x @ weight.T + bias",
            "arrays": [{"name": n, "role": role} for n, role in ARRAY_ORDER],
        },
        "data": (metrics or {}).get("data", {}),
        "metrics": _slim_metrics(metrics),
    }


def _slim_metrics(metrics: dict | None) -> dict:
    """The metrics worth carrying inside the model file (no per-epoch history)."""
    if not metrics:
        return {}
    return {k: metrics[k] for k in ("loss", "test", "hardcases", "config", "inference")
            if k in metrics}


def export_model(model, out_dir: str, metrics: dict | None = None) -> dict:
    os.makedirs(out_dir, exist_ok=True)
    arrays = weights_dict(model)
    npz_path = os.path.join(out_dir, "weights.npz")
    with open(npz_path, "wb") as fh:
        np.savez(fh, **arrays)
    meta = model_json(model, metrics)
    meta["weights"]["bytes"] = os.path.getsize(npz_path)
    meta["weights"]["shapes"] = {n: list(arrays[n].shape) for n, _ in ARRAY_ORDER}
    _write_json(os.path.join(out_dir, "model.json"), meta)
    return meta


def stamp_metrics(out_dir: str, metrics: dict) -> None:
    """Refresh model.json's metrics block after the inference numbers are known."""
    path = os.path.join(out_dir, "model.json")
    with open(path, encoding="utf-8") as fh:
        meta = json.load(fh)
    meta["data"] = metrics.get("data", meta.get("data", {}))
    meta["metrics"] = _slim_metrics(metrics)
    _write_json(path, meta)


def _write_json(path: str, obj: dict) -> None:
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(obj, fh, indent=2)
        fh.write("\n")
