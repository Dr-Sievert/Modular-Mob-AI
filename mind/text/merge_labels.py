#!/usr/bin/env python3
"""Step 4: validate labeled JSONL and merge it into text/data/dialogue.jsonl.

Accepts the raw reply of a chat AI: stray code fences, commentary lines, blank
lines and a trailing summary are skipped rather than treated as errors.

Two kinds of input object are understood, so both data sources land in one file:

  * corpus lines  - carry an `id`; the canonical `text` is re-joined from
    `text/data/corpus.jsonl` and whatever `text` the model echoed is ignored.
  * generated lines - carry a `text` and no `id` (output of
    `text/PROMPT_generate_dialogue.md`).

Every object is validated against SCHEMA.md: the intent/topic/addressed enums,
the numeric ranges, and `names` being a subset of the name pool whose entries
actually occur in the text. Output is deduped by text against itself and against
whatever is already in dialogue.jsonl.

Usage::

    python text/merge_labels.py text/data/labeled/chunk_000.jsonl
    python text/merge_labels.py text/data/labeled/*.jsonl
    python text/merge_labels.py gen/*.jsonl --replace       # rebuild from scratch
    python text/merge_labels.py answers.jsonl --repair      # clamp/fix instead of reject
    python text/merge_labels.py answers.jsonl --dry-run
"""

from __future__ import annotations

import argparse
import glob
import json
import os
import re
import sys
from collections import Counter, defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
CORPUS = os.path.join(HERE, "data", "corpus.jsonl")
OUT_PATH = os.path.join(HERE, "data", "dialogue.jsonl")

INTENTS = ["GREET", "FAREWELL", "SMALLTALK", "QUESTION", "REQUEST", "COMMAND",
           "OFFER", "PRAISE", "APOLOGY", "INSULT", "THREAT", "WARNING", "ACCUSE"]
TOPICS = ["FORGE", "MINE", "TREASURE", "FOOD", "DRINK", "HOME", "WEAPON",
          "WORK", "CLAN", "MONSTER", "TRADE", "NONE"]
ADDRESSED = ["LISTENER", "THIRD", "GROUP", "NONE"]
# Optional on a record: labeled in a second pass for early chunks, inline for later ones.
SINCERITY = ["SINCERE", "SARCASTIC", "JOKING"]
NAME_POOL =["Alvis", "Borin", "Brokk", "Brynja", "Dagna", "Dvalin", "Eitri", "Frida",
             "Grimhild", "Gudrun", "Halvar", "Hrolf", "Ingrid", "Kolbrun", "Orm", "Ragna",
             "Runa", "Sindri", "Skadi", "Steinar", "Torvi", "Tova", "Vidar", "Yngvar"]
NAME_SET = set(NAME_POOL)

MIN_WORDS, MAX_WORDS = 1, 25
FIELD_ORDER = ["text", "intent", "topic", "addressed",
               "aggression", "valence", "urgency", "names"]
FENCE_RE = re.compile(r"^\s*(```|~~~)")


class Rejected(Exception):
    def __init__(self, reason: str):
        super().__init__(reason)
        self.reason = reason


# --------------------------------------------------------------------------
# parsing
# --------------------------------------------------------------------------

def iter_objects(path: str, stats: Counter):
    """Yield (lineno, obj) for every JSON object in a possibly messy file."""
    with open(path, "r", encoding="utf-8-sig", errors="replace") as fh:
        for lineno, line in enumerate(fh, 1):
            line = line.strip().rstrip(",")
            if not line:
                continue
            if FENCE_RE.match(line):
                stats["skipped: code fence"] += 1
                continue
            if line in ("[", "]"):
                stats["skipped: json array bracket"] += 1
                continue
            obj = _try_json(line)
            if obj is None:
                stats["skipped: not JSON (commentary?)"] += 1
                continue
            if isinstance(obj, list):  # the whole answer as one JSON array
                for sub in obj:
                    if isinstance(sub, dict):
                        yield lineno, sub
                continue
            if not isinstance(obj, dict):
                stats["skipped: not a JSON object"] += 1
                continue
            yield lineno, obj


def _try_json(line: str):
    try:
        return json.loads(line)
    except json.JSONDecodeError:
        pass
    # a line such as `1. {"id": ...}  <- note` still has a usable object in it
    start, end = line.find("{"), line.rfind("}")
    if start != -1 and end > start:
        try:
            return json.loads(line[start:end + 1])
        except json.JSONDecodeError:
            return None
    return None


# --------------------------------------------------------------------------
# validation
# --------------------------------------------------------------------------

def _num(obj, field, lo, hi, repair):
    if field not in obj:
        raise Rejected(f"missing field `{field}`")
    v = obj[field]
    if isinstance(v, bool) or not isinstance(v, (int, float)):
        try:
            v = float(str(v).strip())
        except (TypeError, ValueError):
            raise Rejected(f"`{field}` is not a number ({v!r})")
    v = float(v)
    if not (lo <= v <= hi):
        if not repair:
            raise Rejected(f"`{field}` out of range [{lo}, {hi}] ({v})")
        v = max(lo, min(hi, v))
    return round(v, 3)


def _enum(obj, field, allowed):
    if field not in obj:
        raise Rejected(f"missing field `{field}`")
    v = obj[field]
    if not isinstance(v, str):
        raise Rejected(f"`{field}` is not a string ({v!r})")
    v = v.strip().upper()
    if v not in allowed:
        raise Rejected(f"`{field}` not in enum ({obj[field]!r})")
    return v


def name_in_text(name: str, text: str) -> bool:
    return re.search(r"(?<![A-Za-z])" + re.escape(name) + r"(?![A-Za-z])", text) is not None


def validate(obj: dict, corpus: dict[str, dict], repair: bool) -> dict:
    """Return a clean record or raise Rejected."""
    rid = obj.get("id")
    source = None
    if rid is not None:
        rid = str(rid).strip()
        row = corpus.get(rid)
        if row is None:
            raise Rejected(f"unknown id `{rid}` (not in corpus.jsonl)")
        text = row["text"]          # canonical text always wins
        source = row["source"]
    else:
        text = obj.get("text")
        if not isinstance(text, str) or not text.strip():
            raise Rejected("no `id` and no usable `text`")
        text = re.sub(r"\s+", " ", text).strip()
        source = obj.get("source") or "generated"
        n = len(text.split())
        if not (MIN_WORDS <= n <= MAX_WORDS):
            raise Rejected(f"text length {n} words, outside {MIN_WORDS}-{MAX_WORDS}")

    rec = {
        "text": text,
        "intent": _enum(obj, "intent", INTENTS),
        "topic": _enum(obj, "topic", TOPICS),
        "addressed": _enum(obj, "addressed", ADDRESSED),
        "aggression": _num(obj, "aggression", 0.0, 1.0, repair),
        "valence": _num(obj, "valence", -1.0, 1.0, repair),
        "urgency": _num(obj, "urgency", 0.0, 1.0, repair),
    }

    if obj.get("sincerity") is not None:
        rec["sincerity"] = _enum(obj, "sincerity", SINCERITY)

    names = obj.get("names", [])
    if names is None:
        names = []
    if isinstance(names, str):
        names = [n for n in re.split(r"[,\s]+", names) if n]
    if not isinstance(names, list) or any(not isinstance(n, str) for n in names):
        raise Rejected("`names` is not a list of strings")
    clean = []
    for n in names:
        n = n.strip()
        if not n:
            continue
        if n not in NAME_SET:
            if repair:
                continue
            raise Rejected(f"name `{n}` is not in the name pool")
        if not name_in_text(n, text):
            if repair:
                continue
            raise Rejected(f"name `{n}` does not appear in the text")
        if n not in clean:
            clean.append(n)
    rec["names"] = clean

    if rid:
        rec["id"] = rid
    if source:
        rec["source"] = source
    return rec


# --------------------------------------------------------------------------
# merge
# --------------------------------------------------------------------------

def dedupe_key(text: str) -> str:
    return re.sub(r"\s+", " ", text).strip().casefold()


def load_corpus(path: str) -> dict[str, dict]:
    corpus = {}
    if not os.path.exists(path):
        return corpus
    with open(path, "r", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if line:
                row = json.loads(line)
                corpus[row["id"]] = row
    return corpus


def load_existing(path: str) -> list[dict]:
    rows = []
    if not os.path.exists(path):
        return rows
    with open(path, "r", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if line:
                try:
                    rows.append(json.loads(line))
                except json.JSONDecodeError:
                    continue
    return rows


def ordered(rec: dict) -> dict:
    out = {k: rec[k] for k in FIELD_ORDER if k in rec}
    for k in rec:
        if k not in out:
            out[k] = rec[k]
    return out


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("inputs", nargs="+", help="labeled JSONL files (globs allowed)")
    ap.add_argument("--out", default=OUT_PATH)
    ap.add_argument("--corpus", default=CORPUS)
    ap.add_argument("--replace", action="store_true",
                    help="overwrite dialogue.jsonl instead of adding to it")
    ap.add_argument("--repair", action="store_true",
                    help="clamp out-of-range numbers and drop bad names instead of rejecting")
    ap.add_argument("--dry-run", action="store_true", help="validate and report, write nothing")
    ap.add_argument("--examples", type=int, default=3,
                    help="rejected examples to print per reason")
    args = ap.parse_args(argv)

    paths = []
    for pattern in args.inputs:
        hits = sorted(glob.glob(pattern))
        if not hits and os.path.exists(pattern):
            hits = [pattern]
        if not hits:
            print(f"warning: no files match {pattern}", file=sys.stderr)
        paths.extend(hits)
    if not paths:
        print("no input files", file=sys.stderr)
        return 1

    corpus = load_corpus(args.corpus)
    if not corpus:
        print(f"warning: {args.corpus} is missing or empty; only id-less "
              f"(generated) lines can be merged", file=sys.stderr)

    kept: list[dict] = []
    seen: dict[str, str] = {}          # dedupe key -> where it came from
    n_existing = 0
    if not args.replace:
        for rec in load_existing(args.out):
            text = rec.get("text", "")
            key = dedupe_key(text)
            if not text or key in seen:
                continue
            seen[key] = "dialogue.jsonl"
            kept.append(rec)
        n_existing = len(kept)

    skips = Counter()
    rejects = Counter()
    reject_examples = defaultdict(list)
    per_file = {}
    n_new = 0

    for path in paths:
        before = len(kept)
        file_rejects = 0
        for lineno, obj in iter_objects(path, skips):
            try:
                rec = validate(obj, corpus, args.repair)
            except Rejected as exc:
                rejects[exc.reason if len(exc.reason) < 60 else exc.reason[:57] + "..."] += 1
                file_rejects += 1
                if len(reject_examples[exc.reason]) < args.examples:
                    reject_examples[exc.reason].append(
                        f"{os.path.basename(path)}:{lineno}: {json.dumps(obj, ensure_ascii=False)[:120]}")
                continue
            key = dedupe_key(rec["text"])
            if key in seen:
                rejects[f"duplicate text (already from {seen[key]})"] += 1
                file_rejects += 1
                continue
            seen[key] = os.path.basename(path)
            kept.append(rec)
            n_new += 1
        per_file[path] = (len(kept) - before, file_rejects)

    # ---- report ----------------------------------------------------------
    print("=" * 68)
    print("merge_labels report")
    print("=" * 68)
    print(f"input files:        {len(paths)}")
    for path, (ok, bad) in per_file.items():
        print(f"  {os.path.basename(path):32s} accepted {ok:>6,}   rejected {bad:>6,}")
    print()
    print(f"already in dialogue.jsonl: {n_existing:,}")
    print(f"newly accepted:            {n_new:,}")
    print(f"total after merge:         {len(kept):,}")
    print(f"rejected:                  {sum(rejects.values()):,}")
    print(f"non-JSON lines skipped:    {sum(skips.values()):,}")

    if skips:
        print("\nskipped lines:")
        for reason, count in skips.most_common():
            print(f"  {count:>6,}  {reason}")

    if rejects:
        print("\nrejections by reason:")
        for reason, count in rejects.most_common():
            print(f"  {count:>6,}  {reason}")
        if args.examples:
            print("\nexamples:")
            for reason, examples in list(reject_examples.items())[:12]:
                print(f"  [{reason}]")
                for ex in examples:
                    print(f"    {ex}")

    if kept:
        print("\nlabel distribution (%d records):" % len(kept))
        for field, allowed in (("intent", INTENTS), ("topic", TOPICS),
                               ("addressed", ADDRESSED)):
            counts = Counter(r.get(field) for r in kept)
            print(f"\n  {field}")
            width = max(len(v) for v in allowed)
            for v in allowed:
                c = counts.get(v, 0)
                pct = c / len(kept)
                print(f"    {v:<{width}}  {c:>6,}  {pct:>6.1%}  {'#' * round(pct * 40)}")
        print("\n  numeric fields")
        for field in ("aggression", "valence", "urgency"):
            vals = [float(r.get(field, 0.0)) for r in kept]
            mean = sum(vals) / len(vals)
            print(f"    {field:<11} mean {mean:+.2f}   min {min(vals):+.2f}   max {max(vals):+.2f}")
        n_named = sum(1 for r in kept if r.get("names"))
        print(f"\n  lines with names: {n_named:,} ({n_named / len(kept):.1%})")
        by_source = Counter(r.get("source", "unknown") for r in kept)
        print("\n  by source")
        for s, c in by_source.most_common():
            print(f"    {s:<22} {c:>6,}  {c / len(kept):>6.1%}")

    if args.dry_run:
        print("\n--dry-run: nothing written")
        return 0

    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    tmp = args.out + ".tmp"
    with open(tmp, "w", encoding="utf-8", newline="\n") as fh:
        for rec in kept:
            fh.write(json.dumps(ordered(rec), ensure_ascii=False) + "\n")
    os.replace(tmp, args.out)
    print(f"\nwrote {args.out} ({len(kept):,} lines)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
