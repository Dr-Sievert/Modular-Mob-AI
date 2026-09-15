#!/usr/bin/env python3
"""Validate the Cursor reply batches and merge them into text/data/replies.jsonl.

The same tool as `merge_labels.py`, for the other half of the data: `merge_labels.py` builds what
the classifier is trained on, this builds what the dwarves speak from. Written against
`text/CURSOR_REPLIES_PROMPT.md`, whose enums are the contract.

    python text/merge_replies.py text/batches/replies_*.jsonl
    python text/merge_replies.py text/batches/replies_01.jsonl --dry-run
    python text/merge_replies.py text/batches/replies_*.jsonl --replace

Accepts the raw reply of a chat AI: code fences, commentary lines, blank lines and a trailing
summary are skipped rather than treated as errors, exactly as `merge_labels.py` does.

**What is rejected, and why each rule is here**

* a bad enum anywhere in `hears`, `state` or `act` -- the bank is indexed on these words, and a
  line tagged `trust: friendly` is a line no dwarf will ever reach;
* an unknown `{slot}` -- `dwarfsim/realize.py` has one resolver per slot and no others, so a
  line asking for `{weather}` can never be filled and would be dropped silently at run time;
* a slot used in `text` but not declared in `slots`, or declared and never used -- the declared
  list is what the merge report and the docs count, and a bank whose two halves disagree is a
  bank nobody can audit;
* a real name from the pool in `text` -- names come from `{speaker}`, `{dwarf}` and `{third}`,
  filled from the actual settlement. A line that says "Brokk" is a line that will be said to
  somebody who is not Brokk;
* a line outside 1 to 25 words, or empty;
* a `SILENCE` line whose text is anything but `...`;
* a duplicate of a line already accepted -- same words *and* same tags (see :func:`dedupe_key`).

Nothing is repaired. A reply is a written thing and a half-right one is worse than one fewer.
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
sys.path.insert(0, os.path.dirname(HERE))

from dwarfsim import realize                                    # noqa: E402
from dwarfsim.replybank import (BadLine, HEARS_FIELDS, STATE_FIELDS,  # noqa: E402
                                MAX_WORDS, MIN_WORDS, parse_line)
from dwarfsim.schema import NAME_POOL                           # noqa: E402
from dwarfsim.speechplan import ACTS, TRUST                     # noqa: E402

OUT_PATH = os.path.join(HERE, "data", "replies.jsonl")
BATCH_GLOB = os.path.join(HERE, "batches", "replies_*.jsonl")

FIELD_ORDER = ["hears", "state", "act", "slots", "text"]
FENCE_RE = re.compile(r"^\s*(```|~~~)")
NAME_RE = re.compile(r"(?<![A-Za-z])(" + "|".join(re.escape(n) for n in NAME_POOL)
                     + r")(?![A-Za-z])")


# --------------------------------------------------------------------------
# parsing a possibly messy file
# --------------------------------------------------------------------------

def iter_objects(path: str, stats: Counter):
    """Yield ``(lineno, obj)`` for every JSON object in the file, skipping the noise."""
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
            if isinstance(obj, list):
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

def validate(obj: dict) -> dict:
    """Return a clean record, or raise :class:`BadLine`.

    The enum, slot and length rules are :func:`dwarfsim.replybank.parse_line`'s, called here so
    the merge tool and the loader can never drift apart. The name rule is this tool's own,
    because a bank file is allowed to be read by a loader that does not know the name pool.
    """
    line = parse_line(obj)
    hit = NAME_RE.search(line.text)
    if hit:
        raise BadLine("the name `%s` is in the text: use {speaker}, {dwarf} or {third}"
                      % hit.group(1))
    if line.act == "SILENCE" and line.text != "...":
        raise BadLine("a SILENCE line's text must be exactly `...`")
    return {
        "hears": {f: line.hears[f] for f in HEARS_FIELDS},
        "state": {f: line.state[f] for f in STATE_FIELDS},
        "act": line.act,
        "slots": list(line.slots),
        "text": line.text,
    }


def dedupe_key(rec: dict) -> tuple:
    """What makes two bank lines the same line: the words **and** the tags they were written for.

    Not the words alone. Every SILENCE line is the same three dots, and the whole point of them
    is that one is filed under grieving and another under hostile; deduping on text alone kept
    exactly one of them and quietly emptied the act.
    """
    text = re.sub(r"\s+", " ", rec.get("text", "")).strip().casefold()
    hears = rec.get("hears") or {}
    state = rec.get("state") or {}
    return (rec.get("act"), text,
            tuple(hears.get(f) for f in HEARS_FIELDS),
            tuple(state.get(f) for f in STATE_FIELDS))


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


# --------------------------------------------------------------------------


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("inputs", nargs="*", default=[BATCH_GLOB],
                    help="reply batch files (globs allowed); defaults to text/batches/replies_*")
    ap.add_argument("--out", default=OUT_PATH)
    ap.add_argument("--replace", action="store_true",
                    help="overwrite replies.jsonl instead of adding to it")
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
            print("warning: no files match %s" % pattern, file=sys.stderr)
        paths.extend(hits)
    if not paths:
        print("no input files", file=sys.stderr)
        return 1

    kept: list[dict] = []
    seen: dict[tuple, str] = {}
    n_existing = 0
    if not args.replace:
        for rec in load_existing(args.out):
            text = rec.get("text", "")
            key = dedupe_key(rec)
            if not text or key in seen:
                continue
            seen[key] = "replies.jsonl"
            kept.append(rec)
        n_existing = len(kept)

    skips: Counter = Counter()
    rejects: Counter = Counter()
    reject_examples = defaultdict(list)
    per_file = {}
    n_new = 0

    for path in paths:
        before = len(kept)
        file_rejects = 0
        for lineno, obj in iter_objects(path, skips):
            try:
                rec = validate(obj)
            except BadLine as exc:
                reason = str(exc)
                rejects[reason if len(reason) < 60 else reason[:57] + "..."] += 1
                file_rejects += 1
                if len(reject_examples[reason]) < args.examples:
                    reject_examples[reason].append(
                        "%s:%d: %s" % (os.path.basename(path), lineno,
                                       json.dumps(obj, ensure_ascii=False)[:120]))
                continue
            key = dedupe_key(rec)
            if key in seen:
                rejects["duplicate line and tags (already from %s)" % seen[key]] += 1
                file_rejects += 1
                continue
            seen[key] = os.path.basename(path)
            kept.append(rec)
            n_new += 1
        per_file[path] = (len(kept) - before, file_rejects)

    # ---- report ----------------------------------------------------------
    print("=" * 68)
    print("merge_replies report")
    print("=" * 68)
    print("input files:        %d" % len(paths))
    for path, (ok, bad) in per_file.items():
        print("  %-32s accepted %6d   rejected %6d" % (os.path.basename(path), ok, bad))
    print()
    print("already in replies.jsonl:  %6d" % n_existing)
    print("newly accepted:            %6d" % n_new)
    print("total after merge:         %6d" % len(kept))
    print("rejected:                  %6d" % sum(rejects.values()))
    print("non-JSON lines skipped:    %6d" % sum(skips.values()))

    if skips:
        print("\nskipped lines:")
        for reason, count in skips.most_common():
            print("  %6d  %s" % (count, reason))

    if rejects:
        print("\nrejections by reason:")
        for reason, count in rejects.most_common():
            print("  %6d  %s" % (count, reason))
        if args.examples:
            print("\nexamples:")
            for reason, examples in list(reject_examples.items())[:12]:
                print("  [%s]" % reason)
                for ex in examples:
                    print("    " + ex)

    if kept:
        print("\nbank shape (%d lines):" % len(kept))
        counts = Counter(r["act"] for r in kept)
        width = max(len(a) for a in ACTS)
        print("\n  act")
        for act in ACTS:
            c = counts.get(act, 0)
            flag = "  <- nothing written for this act" if c == 0 else ""
            print("    %-*s %6d%s" % (width, act, c, flag))
        print("\n  trust")
        tcounts = Counter(r["state"]["trust"] for r in kept)
        for level in TRUST:
            c = tcounts.get(level, 0)
            print("    %-9s %6d  %5.1f%%  %s" % (level, c, 100.0 * c / len(kept),
                                                 "#" * round(40.0 * c / len(kept))))
        for field in ("mood", "condition", "suspicion"):
            print("\n  %s" % field)
            fc = Counter(r["state"][field] for r in kept)
            for value, c in fc.most_common():
                print("    %-12s %6d  %5.1f%%" % (value, c, 100.0 * c / len(kept)))
        with_slots = sum(1 for r in kept if r["slots"])
        print("\n  lines with at least one slot: %d (%.1f%%)"
              % (with_slots, 100.0 * with_slots / len(kept)))
        slot_counts = Counter(s for r in kept for s in r["slots"])
        print("  slots used:")
        for slot in realize.SLOT_NAMES:
            print("    {%-8s} %6d" % (slot, slot_counts.get(slot, 0)))
        empty = [a for a in ACTS if counts.get(a, 0) == 0]
        if empty:
            print("\n  %d acts have no line at all: %s" % (len(empty), ", ".join(empty)))

    if args.dry_run:
        print("\n--dry-run: nothing written")
        return 0

    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    tmp = args.out + ".tmp"
    with open(tmp, "w", encoding="utf-8", newline="\n") as fh:
        for rec in kept:
            fh.write(json.dumps(ordered(rec), ensure_ascii=False) + "\n")
    os.replace(tmp, args.out)
    print("\nwrote %s (%d lines, %d to %d words)" % (args.out, len(kept), MIN_WORDS, MAX_WORDS))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
