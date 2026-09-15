"""Agreement between two labelings of the same lines, joined by id.

    python text/qa_agreement.py A.jsonl B.jsonl

Reports exact agreement on the categorical fields, mean absolute error on the
continuous ones, and the confusion pairs that disagree most often. Lines that
fail to parse, or that only one side has, are counted and skipped. Stdlib only.
"""

from __future__ import annotations

import json
import sys
from collections import Counter

CATEGORICAL = ("intent", "topic", "addressed")
CONTINUOUS = ("aggression", "valence", "urgency")


def load(path: str) -> tuple[dict[str, dict], int]:
    rows, bad = {}, 0
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("```"):
                continue
            try:
                obj = json.loads(line)
            except json.JSONDecodeError:
                bad += 1
                continue
            key = obj.get("id") or obj.get("text", "").strip().lower()
            if key:
                rows[key] = obj
    return rows, bad


def main(a_path: str, b_path: str) -> None:
    a, a_bad = load(a_path)
    b, b_bad = load(b_path)
    common = sorted(set(a) & set(b))
    print(f"A: {len(a)} rows ({a_bad} unparseable)   B: {len(b)} rows ({b_bad} unparseable)   joined: {len(common)}")
    if not common:
        return

    for field in CATEGORICAL:
        agree = sum(1 for k in common if a[k].get(field) == b[k].get(field))
        confusions = Counter(
            (a[k].get(field), b[k].get(field)) for k in common if a[k].get(field) != b[k].get(field)
        )
        print(f"\n{field:10s} agreement {agree}/{len(common)} = {agree / len(common):.1%}")
        for (x, y), n in confusions.most_common(6):
            print(f"    A={x!s:10s} B={y!s:10s} x{n}")

    print()
    for field in CONTINUOUS:
        pairs = [(a[k].get(field), b[k].get(field)) for k in common]
        pairs = [(x, y) for x, y in pairs if isinstance(x, (int, float)) and isinstance(y, (int, float))]
        if not pairs:
            continue
        mae = sum(abs(x - y) for x, y in pairs) / len(pairs)
        within = sum(1 for x, y in pairs if abs(x - y) <= 0.25) / len(pairs)
        print(f"{field:10s} MAE {mae:.3f}   within 0.25: {within:.1%}")

    print("\nSample disagreements on intent:")
    shown = 0
    for k in common:
        if a[k].get("intent") != b[k].get("intent"):
            text = a[k].get("text") or b[k].get("text") or k
            print(f"  {a[k].get('intent'):9s} vs {b[k].get('intent'):9s} | {text[:70]}")
            shown += 1
            if shown >= 12:
                break


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
