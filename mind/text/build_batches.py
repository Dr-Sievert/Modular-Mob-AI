"""Draw a large unlabeled batch from the raw pool for labeling in chat sessions.

    python text/build_batches.py --lines 100000 --files 10 --seed 7

Reuses build_corpus's filters and per-source allocation. New lines get ids that continue after the
last id in text/data/corpus.jsonl and are appended to it, so merge_labels.py validates their labels
the same way. Writes text/batches/batch_NN.tsv, one `id<TAB>text` per line, equal sizes, each file a
mix of every source. Deterministic under the seed. Stdlib only.
"""

from __future__ import annotations

import argparse
import json
import os
import random
import sys
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from build_corpus import RAW_DIR, allocate, load_and_filter  # noqa: E402

CORPUS = os.path.join(HERE, "data", "corpus.jsonl")
BATCHES = os.path.join(HERE, "batches")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--lines", type=int, default=100000)
    ap.add_argument("--files", type=int, default=10)
    ap.add_argument("--max-share", type=float, default=0.60)
    ap.add_argument("--seed", type=int, default=7)
    args = ap.parse_args()

    existing = []
    with open(CORPUS, encoding="utf-8") as f:
        for line in f:
            if line.strip():
                existing.append(json.loads(line))
    seen = {r["text"].lower() for r in existing}
    next_id = max(int(r["id"][1:]) for r in existing) + 1
    print(f"corpus has {len(existing):,} lines; new ids start at c{next_id:06d}")

    raw_files = sorted(f for f in os.listdir(RAW_DIR) if f.endswith(".jsonl") and not f.startswith("_"))
    if not raw_files:
        print(f"no raw jsonl in {RAW_DIR}; run fetch_corpora.py first", file=sys.stderr)
        return 1

    pools: dict[str, list[str]] = {}
    for fname in raw_files:
        source = fname[: -len(".jsonl")]
        kept = load_and_filter(os.path.join(RAW_DIR, fname), Counter())
        fresh = []
        for t in kept:
            k = t.lower()
            if k in seen:
                continue
            seen.add(k)
            fresh.append(t)
        pools[source] = fresh
        print(f"{source:24s} eligible and unused {len(fresh):>8,}")

    rng = random.Random(args.seed)
    quota = allocate({s: len(p) for s, p in pools.items()}, args.lines, args.max_share)
    picked: list[tuple[str, str]] = []
    for source in sorted(pools):
        k = min(quota.get(source, 0), len(pools[source]))
        picked.extend((t, source) for t in rng.sample(pools[source], k))
        print(f"{source:24s} sampled {k:>8,}")
    rng.shuffle(picked)

    rows = [{"id": f"c{next_id + i:06d}", "text": t, "source": s} for i, (t, s) in enumerate(picked)]
    with open(CORPUS, "a", encoding="utf-8", newline="\n") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")

    os.makedirs(BATCHES, exist_ok=True)
    per = -(-len(rows) // args.files)
    for n in range(args.files):
        part = rows[n * per:(n + 1) * per]
        if not part:
            break
        path = os.path.join(BATCHES, f"batch_{n + 1:02d}.tsv")
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            for r in part:
                f.write(f"{r['id']}\t{r['text']}\n")
        print(f"{os.path.relpath(path, HERE)}: {len(part):,} lines, {part[0]['id']} to {part[-1]['id']}")

    print(f"appended {len(rows):,} lines to corpus.jsonl; total now {len(existing) + len(rows):,}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
