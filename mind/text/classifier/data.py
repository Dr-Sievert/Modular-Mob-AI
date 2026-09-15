"""Loading, the train/test split, batching and class weights.

Split rules (`split_rows`):

* every source, the synthetic "generated" lines included, is split 85/15
  stratified by `source`, so each corpus keeps its share in both halves, with a
  fixed seed that is independent of the training seed. The generated lines are
  the only ones that carry the game's vocabulary, so they must be trained on;
  the per-source accuracy table is what says how the model does on them.
"""

from __future__ import annotations

import hashlib
import json
import os
import random
import re
from collections import Counter, defaultdict

import numpy as np

from .features import SIDE_DIM, buckets, side_features
from .labels import ADDRESSED, FLOAT_FIELDS, INTENTS, MASKED, SINCERITY, TOPICS

HERE = os.path.dirname(os.path.abspath(__file__))
TEXT_DIR = os.path.dirname(HERE)
# NOT data/dialogue.jsonl: a file at that path makes every labeler's merge
# dry-run dedupe against it. The partial merge lives beside it under its own name.
DEFAULT_DATA = os.path.join(TEXT_DIR, "data", "dialogue_partial.jsonl")
HARDCASES = os.path.join(TEXT_DIR, "data", "hardcases.jsonl")

GENERATED_SOURCE = "generated"
TEST_FRACTION = 0.15
SPLIT_SEED = 20250913          # fixed: the split must not move when --seed does


def load_jsonl(path: str) -> list[dict]:
    rows = []
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    return rows


def file_sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for block in iter(lambda: fh.read(1 << 16), b""):
            h.update(block)
    return h.hexdigest()


def norm_text(text: str) -> str:
    """Same normalisation merge_labels.py dedupes with."""
    return re.sub(r"\s+", " ", text).strip().casefold()


def featurize(rows: list[dict]) -> tuple[list[np.ndarray], np.ndarray]:
    """`(hashed bucket lists, side matrix (n, SIDE_DIM) float32)`.

    `prev_intent` is read off the row when it is there. No corpus row has it
    today, so every side vector one-hots `unknown`; the game fills it in.
    """
    bags = [np.asarray(buckets(r["text"]), dtype=np.int64) for r in rows]
    sides = np.zeros((len(rows), SIDE_DIM), dtype=np.float32)
    for i, r in enumerate(rows):
        sides[i] = side_features(r["text"], r.get("prev_intent"))
    return bags, sides


def sincerity_index(row: dict) -> int:
    """Index into SINCERITY, or MASKED when the record predates the field."""
    v = row.get("sincerity")
    return SINCERITY.index(v) if v in SINCERITY else MASKED


def targets_of(rows: list[dict]) -> dict:
    return {
        "intent": np.array([INTENTS.index(r["intent"]) for r in rows], dtype=np.int64),
        "topic": np.array([TOPICS.index(r["topic"]) for r in rows], dtype=np.int64),
        "addressed": np.array([ADDRESSED.index(r["addressed"]) for r in rows], dtype=np.int64),
        "sincerity": np.array([sincerity_index(r) for r in rows], dtype=np.int64),
        "floats": np.array([[float(r[f]) for f in FLOAT_FIELDS] for r in rows],
                           dtype=np.float32),
    }


def split_rows(rows: list[dict], test_frac: float = TEST_FRACTION,
               seed: int = SPLIT_SEED) -> tuple[list[dict], list[dict]]:
    by_source: dict[str, list[dict]] = defaultdict(list)
    for r in rows:
        by_source[r.get("source", "unknown")].append(r)

    rng = random.Random(seed)
    train: list[dict] = []
    test: list[dict] = []
    for source in sorted(by_source):
        group = list(by_source[source])
        rng.shuffle(group)
        n_test = int(round(len(group) * test_frac))
        test.extend(group[:n_test])
        train.extend(group[n_test:])
    rng.shuffle(train)
    rng.shuffle(test)
    return train, test


def drop_hardcases(rows: list[dict], hardcase_rows: list[dict]) -> tuple[list[dict], int]:
    """Hard cases are an evaluation set only; make sure none leaked into training."""
    banned = {norm_text(r["text"]) for r in hardcase_rows}
    kept = [r for r in rows if norm_text(r["text"]) not in banned]
    return kept, len(rows) - len(kept)


def intent_class_weights(rows: list[dict], power: float = 0.5) -> np.ndarray:
    """Inverse-frequency weights, softened by `power` and normalised to mean 1.

    power 1.0 is the textbook balanced weighting, which over-rewards THREAT
    (25 lines) at the expense of everything else; 0.5 lifts the rare intents
    without letting a handful of lines steer the model.
    """
    counts = Counter(r["intent"] for r in rows)
    n, k = len(rows), len(INTENTS)
    w = np.array([(n / (k * max(counts.get(c, 0), 1))) ** power for c in INTENTS],
                 dtype=np.float32)
    w[[i for i, c in enumerate(INTENTS) if counts.get(c, 0) == 0]] = 0.0
    nonzero = w[w > 0]
    if len(nonzero):
        w = w / nonzero.mean()
    return w


def batch_indices(feats: list[np.ndarray], sides: np.ndarray,
                  rows_idx: np.ndarray) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Flat bucket indices + per-row offsets (the EmbeddingBag calling convention)
    + the matching rows of the side matrix."""
    parts = [feats[i] for i in rows_idx]
    lengths = np.array([len(p) for p in parts], dtype=np.int64)
    offsets = np.zeros(len(parts), dtype=np.int64)
    if len(parts) > 1:
        np.cumsum(lengths[:-1], out=offsets[1:])
    idx = np.concatenate(parts) if parts else np.zeros(0, dtype=np.int64)
    return idx, offsets, sides[rows_idx]
