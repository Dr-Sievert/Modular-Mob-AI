"""Which corpus chunks are labeled, half by half.

    python text/label_status.py            summary and the list of missing halves
    python text/label_status.py --next 8   the next 8 missing halves as (chunk, half, line range)

A chunk is labeled in two halves, `chunk_NNNa.jsonl` (lines 1-150) and `chunk_NNNb.jsonl`
(lines 151-end), each written by one labeling agent. A half counts as done when its file has
exactly as many parseable lines as its slice of the chunk. Stdlib only.
"""

from __future__ import annotations

import argparse
import json
import os

CHUNKS = os.path.join(os.path.dirname(__file__), "chunks")
LABELED = os.path.join(os.path.dirname(__file__), "data", "labeled")
HALF = 150


def chunk_lines(name: str) -> int:
    with open(os.path.join(CHUNKS, name), encoding="utf-8") as f:
        return sum(1 for line in f if line.strip())


def labeled_lines(path: str) -> int:
    if not os.path.exists(path):
        return -1
    n = 0
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("```"):
                continue
            try:
                json.loads(line)
                n += 1
            except json.JSONDecodeError:
                pass
    return n


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--next", type=int, default=0)
    args = ap.parse_args()

    chunks = sorted(f for f in os.listdir(CHUNKS) if f.startswith("chunk_") and f.endswith(".txt"))
    done, partial, missing = [], [], []
    for name in chunks:
        stem = name[:-4]
        total = chunk_lines(name)
        halves = [("a", 1, min(HALF, total))]
        if total > HALF:
            halves.append(("b", HALF + 1, total))
        for half, lo, hi in halves:
            want = hi - lo + 1
            got = labeled_lines(os.path.join(LABELED, f"{stem}{half}.jsonl"))
            entry = (stem, half, lo, hi, got, want)
            if got == want:
                done.append(entry)
            elif got < 0:
                missing.append(entry)
            else:
                partial.append(entry)

    if args.next:
        for stem, half, lo, hi, _, _ in missing[: args.next]:
            print(f"{stem} {half} {lo}-{hi}")
        return

    print(f"halves done {len(done)}, partial {len(partial)}, missing {len(missing)}")
    for stem, half, lo, hi, got, want in partial:
        print(f"  partial {stem}{half}: {got}/{want}")
    print("missing: " + " ".join(f"{s}{h}" for s, h, *_ in missing))


if __name__ == "__main__":
    main()
