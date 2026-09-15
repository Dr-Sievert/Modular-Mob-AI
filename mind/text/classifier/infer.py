"""Pure-numpy inference: what the Java port has to reproduce.

    from text.classifier.infer import Classifier
    clf = Classifier.load("text/models/clf")
    clf.predict("Get away from my forge, Brokk!")
    clf.predict("get out", prev_intent="INSULT")   # what was said to us last

No torch import anywhere in this file. It reads `weights.npz` and `model.json`
and nothing else, so it doubles as the reference implementation for the port:
five array lookups, one concatenation, two small matrix products, four softmaxes.

`prev_intent` is the intent of the line this one answers; leave it out and the
side vector one-hots `unknown`, which is what every training row carries today.
The other thirteen columns have therefore never had a gradient: supplying one
shifts the output, but the shift is an untrained column, not a learned reaction.

    python -m text.classifier.infer --model text/models/clf --bench
"""

from __future__ import annotations

import argparse
import json
import os
import random
import time

import numpy as np

from .features import SIDE_DIM, buckets, side_features
from .labels import FLOAT_FIELDS, names_in_text

# The predicted dict follows the SCHEMA.md field order.
FIELD_ORDER = ["text", "intent", "topic", "addressed",
               "aggression", "valence", "urgency", "names", "sincerity"]


def softmax(x: np.ndarray) -> np.ndarray:
    e = np.exp(x - x.max())
    return e / e.sum()


def sigmoid(x):
    return 1.0 / (1.0 + np.exp(-x))


class Classifier:
    """The exported model, running on numpy alone."""

    def __init__(self, meta: dict, arrays: dict):
        self.meta = meta
        self.labels = meta["labels"]
        self.name_pool = list(meta["labels"]["names"])
        self.buckets = int(meta["dims"]["buckets"])
        self.side_dim = int(meta["dims"].get("side", 0))
        a = {k: np.asarray(v, dtype=np.float32) for k, v in arrays.items()}
        self.emb = a["emb.weight"]
        self.fc1_w, self.fc1_b = a["fc1.weight"], a["fc1.bias"]
        if self.side_dim != SIDE_DIM or self.fc1_w.shape[1] != self.emb.shape[1] + SIDE_DIM:
            raise ValueError(
                "model was exported with side=%d, fc1 in=%d; this build expects "
                "side=%d, fc1 in=%d -- retrain or check out the matching revision"
                % (self.side_dim, self.fc1_w.shape[1], SIDE_DIM,
                   self.emb.shape[1] + SIDE_DIM))
        self.heads = {
            "intent": (a["head_intent.weight"], a["head_intent.bias"]),
            "topic": (a["head_topic.weight"], a["head_topic.bias"]),
            "addressed": (a["head_addressed.weight"], a["head_addressed.bias"]),
            "sincerity": (a["head_sincerity.weight"], a["head_sincerity.bias"]),
        }
        self.floats_w, self.floats_b = a["head_floats.weight"], a["head_floats.bias"]
        data = meta.get("data") or {}
        # 0 means the sincerity head never saw a labelled row: it will still
        # answer, but the answer carries no information yet.
        self.sincerity_labeled_rows = int(data.get("sincerity_labeled_rows", 0))

    # -- loading -----------------------------------------------------------

    @classmethod
    def load(cls, model_dir: str) -> "Classifier":
        with open(os.path.join(model_dir, "model.json"), encoding="utf-8") as fh:
            meta = json.load(fh)
        with np.load(os.path.join(model_dir, "weights.npz")) as npz:
            arrays = {k: npz[k] for k in npz.files}
        return cls(meta, arrays)

    # -- forward -----------------------------------------------------------

    def hidden(self, text: str, prev_intent: str | None = None) -> np.ndarray:
        idx = np.asarray(buckets(text), dtype=np.int64)
        bag = self.emb[idx].sum(axis=0, dtype=np.float32)
        h = np.maximum(bag, 0.0, dtype=np.float32)
        side = np.asarray(side_features(text, prev_intent), dtype=np.float32)
        h = np.concatenate((h, side))
        h = np.maximum(h @ self.fc1_w.T + self.fc1_b, 0.0, dtype=np.float32)
        return h

    def scores(self, text: str, prev_intent: str | None = None) -> dict:
        """Raw head outputs: a probability vector per softmax head, the 3 floats."""
        h = self.hidden(text, prev_intent)
        out = {name: softmax(w @ h + b) for name, (w, b) in self.heads.items()}
        raw = self.floats_w @ h + self.floats_b
        out["floats"] = np.array([sigmoid(raw[0]), np.tanh(raw[1]), sigmoid(raw[2])],
                                 dtype=np.float32)
        return out

    def predict(self, text: str, with_scores: bool = False,
                prev_intent: str | None = None) -> dict:
        s = self.scores(text, prev_intent)
        rec = {
            "text": text,
            "intent": self.labels["intent"][int(s["intent"].argmax())],
            "topic": self.labels["topic"][int(s["topic"].argmax())],
            "addressed": self.labels["addressed"][int(s["addressed"].argmax())],
        }
        for i, field in enumerate(FLOAT_FIELDS):
            rec[field] = round(float(s["floats"][i]), 3)
        rec["names"] = names_in_text(text, self.name_pool)
        rec["sincerity"] = self.labels["sincerity"][int(s["sincerity"].argmax())]
        if with_scores:
            rec["scores"] = {k: {lab: round(float(p), 4)
                                 for lab, p in zip(self.labels[k], s[k])}
                             for k in ("intent", "topic", "addressed", "sincerity")}
        return {k: rec[k] for k in FIELD_ORDER if k in rec} | (
            {"scores": rec["scores"]} if with_scores else {})

    def predict_batch(self, texts, prev_intent: str | None = None) -> list[dict]:
        return [self.predict(t, prev_intent=prev_intent) for t in texts]

    # -- benchmark ---------------------------------------------------------

    def benchmark(self, texts, n: int = 2000, seed: int = 0) -> dict:
        """Mean and p99 microseconds per line, one line at a time (the game's path)."""
        rng = random.Random(seed)
        pool = list(texts) or ["hello"]
        lines = [pool[rng.randrange(len(pool))] for _ in range(n)]
        for t in lines[:50]:                       # warm up caches
            self.predict(t)
        times = np.empty(n, dtype=np.float64)
        for i, t in enumerate(lines):
            t0 = time.perf_counter()
            self.predict(t)
            times[i] = (time.perf_counter() - t0) * 1e6
        return {"n": n, "mean": float(times.mean()),
                "p50": float(np.percentile(times, 50)),
                "p99": float(np.percentile(times, 99)),
                "max": float(times.max())}


def check_parity(torch_model, clf: Classifier, texts, n: int = 500,
                 seed: int = 0) -> tuple[float, int]:
    """Max abs difference between the torch model and this numpy one over n lines."""
    import torch                                   # local: infer.py itself stays numpy

    rng = random.Random(seed)
    pool = list(texts)
    sample = [pool[rng.randrange(len(pool))] for _ in range(min(n, len(pool)))] \
        if pool else []
    if not sample:
        return 0.0, 0
    torch_model.eval()
    worst = 0.0
    with torch.no_grad():
        for text in sample:
            idx = np.asarray(buckets(text), dtype=np.int64)
            side = np.asarray([side_features(text)], dtype=np.float32)
            out = torch_model(torch.from_numpy(idx), torch.zeros(1, dtype=torch.long),
                              torch.from_numpy(side))
            mine = clf.scores(text)
            for head in ("intent", "topic", "addressed", "sincerity"):
                theirs = torch.softmax(out[head][0], dim=0).numpy()
                worst = max(worst, float(np.abs(theirs - mine[head]).max()))
            theirs = out["floats"][0].numpy()
            worst = max(worst, float(np.abs(theirs - mine["floats"]).max()))
    return worst, len(sample)


def main(argv=None) -> int:
    here = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--model", default=os.path.join(here, "models", "clf"))
    ap.add_argument("--data", default=os.path.join(here, "data", "dialogue_partial.jsonl"),
                    help="lines to benchmark with")
    ap.add_argument("--bench", action="store_true")
    ap.add_argument("--lines", type=int, default=2000)
    ap.add_argument("--prev-intent", default=None,
                    help="intent of the line these answer (default: unknown)")
    ap.add_argument("text", nargs="*")
    args = ap.parse_args(argv)

    clf = Classifier.load(args.model)
    for text in args.text:
        print(json.dumps(clf.predict(text, prev_intent=args.prev_intent),
                         ensure_ascii=False))
    if args.bench:
        texts = []
        if os.path.exists(args.data):
            with open(args.data, encoding="utf-8") as fh:
                for line in fh:
                    line = line.strip()
                    if line:
                        texts.append(json.loads(line)["text"])
        lat = clf.benchmark(texts, n=args.lines)
        print(f"numpy inference over {lat['n']:,} lines: mean {lat['mean']:.1f} us, "
              f"p50 {lat['p50']:.1f} us, p99 {lat['p99']:.1f} us, max {lat['max']:.1f} us")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
