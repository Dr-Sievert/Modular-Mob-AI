"""Join second-pass sincerity labels into the merged dialogue file, by id.

    python text/merge_sincerity.py                       text/data/sincerity/*.jsonl into text/data/dialogue.jsonl
    python text/merge_sincerity.py --out other.jsonl     a different merged file

Each sincerity file holds `{"id": ..., "sincerity": ...}` lines, possibly with stray fences or prose to
skip. A record that already carries a sincerity label keeps it (the inline first-pass label wins).
Prints how many records now have the field. Stdlib only.
"""

from __future__ import annotations

import argparse
import glob
import json
import os
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))
SINCERITY = ("SINCERE", "SARCASTIC", "JOKING")


def read_labels(paths: list[str]) -> tuple[dict[str, str], Counter]:
    labels, rejects = {}, Counter()
    for path in paths:
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("```"):
                    continue
                try:
                    obj = json.loads(line)
                except json.JSONDecodeError:
                    rejects["unparseable"] += 1
                    continue
                rid, val = obj.get("id"), obj.get("sincerity")
                if not isinstance(rid, str) or val not in SINCERITY:
                    rejects["bad id or value"] += 1
                    continue
                labels[rid.strip()] = val
    return labels, rejects


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("inputs", nargs="*", default=[os.path.join(HERE, "data", "sincerity", "*.jsonl")])
    ap.add_argument("--out", default=os.path.join(HERE, "data", "dialogue.jsonl"))
    args = ap.parse_args()

    paths = sorted(p for pattern in args.inputs for p in glob.glob(pattern))
    labels, rejects = read_labels(paths)

    records = []
    with open(args.out, encoding="utf-8") as f:
        for line in f:
            if line.strip():
                records.append(json.loads(line))

    added = 0
    for rec in records:
        if rec.get("sincerity") is None and rec.get("id") in labels:
            rec["sincerity"] = labels[rec["id"]]
            added += 1

    with open(args.out, "w", encoding="utf-8") as f:
        for rec in records:
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")

    have = sum(1 for r in records if r.get("sincerity"))
    dist = Counter(r["sincerity"] for r in records if r.get("sincerity"))
    print(f"{len(paths)} files, {len(labels)} labels read, {added} added; "
          f"{have}/{len(records)} records now carry sincerity {dict(dist)}")
    if rejects:
        print(f"skipped: {dict(rejects)}")


if __name__ == "__main__":
    main()
