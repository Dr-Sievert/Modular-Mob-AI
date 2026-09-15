#!/usr/bin/env python3
"""Step 2: filter, normalise and stratify the raw corpora into a labeling pool.

Reads every text/data/raw/*.jsonl produced by fetch_corpora.py and writes:

  - text/data/corpus.jsonl       {"id": "c000123", "text": ..., "source": ...}
  - text/data/corpus_stats.md    per-source counts, length histogram, drop reasons

Deterministic: the only randomness is a seeded `random.Random`, and the input is
read in a fixed file order, so the same inputs always give the same corpus.

Usage::

    python text/build_corpus.py
    python text/build_corpus.py --target 20000 --seed 1234 --max-share 0.6
"""

from __future__ import annotations

import argparse
import json
import os
import random
import re
import sys
import unicodedata
from collections import Counter, defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
RAW_DIR = os.path.join(HERE, "data", "raw")
OUT_PATH = os.path.join(HERE, "data", "corpus.jsonl")
STATS_PATH = os.path.join(HERE, "data", "corpus_stats.md")

MIN_WORDS = 1
MAX_WORDS = 25
SHORT_WORDS = 6          # <= this many words: keep unconditionally
DEFAULT_TARGET = 20000
DEFAULT_MAX_SHARE = 0.60
DEFAULT_SEED = 20240613

# Minecraft server chat is by far the closest register to what the classifier
# hears in game (very short, typo-heavy, domain vocabulary), so it gets a
# guaranteed slice of the pool instead of a plain even split. Everything else
# is split evenly over what is left, still under --max-share.
PREFERRED_SHARE = {"minecraft_chat": 0.30}


# --------------------------------------------------------------------------
# normalisation
# --------------------------------------------------------------------------

QUOTE_MAP = {
    "‘": "'", "’": "'", "‚": "'", "‛": "'", "′": "'",
    "“": '"', "”": '"', "„": '"', "‟": '"', "″": '"',
    "–": "-", "—": "-", "―": "-", "−": "-",
    "…": "...", " ": " ", "​": "", "﻿": "",
    "`": "'", "´": "'",
}

# "BIANCA:", "JOHN (V.O.):", "Dr. Smith:", "<player>", "[Admin] ", leading dashes.
SPEAKER_RE = re.compile(
    r"^\s*(?:"
    r"[-–—>*•]+\s*"                       # dash / bullet / quote marker
    r"|<[^>\n]{1,32}>\s*"                                 # <nickname>
    r"|\[[^\]\n]{1,32}\]\s*"                              # [Rank]
    r"|\(?[A-Z][A-Za-z.'\- ]{0,24}\)?\s*(?:\([^)\n]{0,24}\))?\s*:\s+"  # NAME: / Name (V.O.):
    r")"
)
URL_RE = re.compile(r"(https?://|www\.|\b\S+\.(?:com|net|org|io|gg|ru|de|co\.uk)\b)", re.I)
EMAIL_RE = re.compile(r"\b[\w.+-]+@[\w-]+\.[\w.]+\b")
MC_COMMAND_RE = re.compile(r"^\s*[/!.](?:[a-z]\w*)")      # /tp, !help, .home
HAS_LETTER_RE = re.compile(r"[A-Za-z]")
REPEAT_RE = re.compile(r"(.)\1{5,}")                       # aaaaaaa, !!!!!!!!
# detokenise DailyDialog / Cornell style " , " and " n't "
SPACE_BEFORE_PUNCT_RE = re.compile(r"\s+([,.!?;:%])")
SPACE_IN_CONTRACTION_RE = re.compile(r"\s+(n't|'s|'re|'ve|'ll|'d|'m)\b", re.I)
MULTI_PUNCT_RE = re.compile(r"([!?.,])\1{2,}")

# Anything outside Basic Latin + Latin-1/Extended-A, after the quote fixes above,
# means another script (or emoji) -> drop the line.
LATIN_OK_RE = re.compile(r"^[\x20-\x7e¡-ɏ’]*$")


def normalise(text: str) -> str:
    if not text:
        return ""
    text = unicodedata.normalize("NFKC", text)
    for bad, good in QUOTE_MAP.items():
        text = text.replace(bad, good)
    text = SPEAKER_RE.sub("", text, count=1)
    text = text.replace("\t", " ").replace("\n", " ")
    text = SPACE_BEFORE_PUNCT_RE.sub(r"\1", text)
    text = SPACE_IN_CONTRACTION_RE.sub(r"\1", text)
    text = MULTI_PUNCT_RE.sub(r"\1\1", text)
    text = re.sub(r"\s+", " ", text).strip()
    text = text.strip("\"'*_ ").strip()
    return text


# --------------------------------------------------------------------------
# usefulness heuristics for the longer lines
# --------------------------------------------------------------------------

SECOND_PERSON_RE = re.compile(
    r"\b(you|your|yours|you're|youre|yourself|ye|u|ur|we|us|our|let's|lets)\b", re.I)

IMPERATIVE_VERBS = (
    "go come get give take bring put stop wait move look listen watch help "
    "leave stay run hide follow open close drop pick hold keep tell show hand "
    "fetch make build dig mine forge fix carry stand sit shut quit back off "
    "hurry check find send call bring pass return trade buy sell pay share "
    "guard defend attack kill dont don't do be let stop"
).split()
IMPERATIVE_RE = re.compile(r"^(?:please\s+|now\s+|just\s+|oi\s+|hey\s+)?(" +
                           "|".join(re.escape(v) for v in sorted(set(IMPERATIVE_VERBS))) +
                           r")\b", re.I)

EMOTION_WORDS = (
    "angry mad furious hate hated hates sorry apolog thank thanks thankful "
    "love loved great awful terrible horrible stupid idiot fool liar cheat "
    "thief steal stole stolen fight fought afraid scared fear scary danger "
    "dangerous careful warn warning watch help please happy glad sad upset "
    "worried worry proud shame disgust annoy annoyed hurt kill killed dead "
    "die died lie lying trust betray fault blame blamed damn bloody curse "
    "wonderful amazing excellent best worst never always promise swear"
).split()
EMOTION_RE = re.compile(r"\b(" + "|".join(EMOTION_WORDS) + r")", re.I)


def is_useful(text: str, n_words: int) -> bool:
    """Cheap bias toward lines a dialogue-act classifier can learn from."""
    if n_words <= SHORT_WORDS:
        return True
    if "?" in text or "!" in text:
        return True
    if SECOND_PERSON_RE.search(text):
        return True
    if IMPERATIVE_RE.search(text):
        return True
    if EMOTION_RE.search(text):
        return True
    return False


# --------------------------------------------------------------------------
# pipeline
# --------------------------------------------------------------------------

def word_count(text: str) -> int:
    return len(text.split())


def load_and_filter(path: str, drops: Counter) -> list[str]:
    """Return the accepted, normalised, per-file-deduped texts of one raw file."""
    kept: list[str] = []
    seen_local: set[str] = set()
    with open(path, "r", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            try:
                obj = json.loads(line)
            except json.JSONDecodeError:
                drops["bad json"] += 1
                continue
            raw = obj.get("text", "")
            text = normalise(raw)
            if not text:
                drops["empty after normalise"] += 1
                continue
            if URL_RE.search(text) or EMAIL_RE.search(text):
                drops["url/email"] += 1
                continue
            if MC_COMMAND_RE.match(text):
                drops["chat command"] += 1
                continue
            if not LATIN_OK_RE.match(text):
                drops["non-latin script"] += 1
                continue
            if not HAS_LETTER_RE.search(text):
                drops["no letters"] += 1
                continue
            if REPEAT_RE.search(text):
                drops["repeated-char spam"] += 1
                continue
            n = word_count(text)
            if n < MIN_WORDS or n > MAX_WORDS:
                drops["length out of 1-25 words"] += 1
                continue
            if not is_useful(text, n):
                drops["long and uninformative"] += 1
                continue
            key = text.lower()
            if key in seen_local:
                drops["duplicate (within source)"] += 1
                continue
            seen_local.add(key)
            kept.append(text)
    return kept


def allocate(avail: dict[str, int], target: int, max_share: float) -> dict[str, int]:
    """Preferred shares first, then an even split of the rest.

    Every source stays at or below `max_share` of the target; leftover quota
    from a source that ran out of eligible lines is redistributed.
    """
    names = sorted(avail)
    cap = max(1, int(target * max_share))
    quota = {n: 0 for n in names}
    remaining = target

    # 1. hand out the guaranteed shares (never above the global cap)
    for n in names:
        frac = PREFERRED_SHARE.get(n)
        if not frac:
            continue
        want = min(int(target * min(frac, max_share)), avail[n], remaining)
        quota[n] = max(0, want)
        remaining -= quota[n]

    # 2. even split of what is left over the remaining sources
    open_names = [n for n in names if avail[n] > quota[n] and n not in PREFERRED_SHARE]
    if not open_names:  # only preferred sources exist; let them absorb the rest
        open_names = [n for n in names if avail[n] > quota[n]]
    while remaining > 0 and open_names:
        share = max(1, remaining // len(open_names))
        progressed = False
        for n in list(open_names):
            room = min(avail[n] - quota[n], cap - quota[n], share, remaining)
            if room <= 0:
                open_names.remove(n)
                continue
            quota[n] += room
            remaining -= room
            progressed = True
            if quota[n] >= avail[n] or quota[n] >= cap:
                open_names.remove(n)
        if not progressed:
            break
    return quota


def histogram(counts: Counter) -> list[tuple[str, int]]:
    buckets = [("1-3", 1, 3), ("4-6", 4, 6), ("7-10", 7, 10),
               ("11-15", 11, 15), ("16-20", 16, 20), ("21-25", 21, 25)]
    return [(label, sum(c for n, c in counts.items() if lo <= n <= hi))
            for label, lo, hi in buckets]


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--target", type=int, default=DEFAULT_TARGET)
    ap.add_argument("--max-share", type=float, default=DEFAULT_MAX_SHARE)
    ap.add_argument("--seed", type=int, default=DEFAULT_SEED)
    args = ap.parse_args(argv)

    raw_files = sorted(f for f in os.listdir(RAW_DIR)
                       if f.endswith(".jsonl") and not f.startswith("_"))
    if not raw_files:
        print(f"no raw jsonl files in {RAW_DIR}; run fetch_corpora.py first", file=sys.stderr)
        return 1

    rng = random.Random(args.seed)
    pools: dict[str, list[str]] = {}
    drops: dict[str, Counter] = {}
    raw_totals: dict[str, int] = {}

    for fname in raw_files:
        source = fname[: -len(".jsonl")]
        path = os.path.join(RAW_DIR, fname)
        d = Counter()
        kept = load_and_filter(path, d)
        raw_totals[source] = sum(d.values()) + len(kept)
        pools[source] = kept
        drops[source] = d
        print(f"{source:24s} raw {raw_totals[source]:>7,}  eligible {len(kept):>7,}")

    # global cross-source dedupe, in a fixed source order
    seen: set[str] = set()
    for source in sorted(pools):
        deduped = []
        for t in pools[source]:
            k = t.lower()
            if k in seen:
                drops[source]["duplicate (cross-source)"] += 1
                continue
            seen.add(k)
            deduped.append(t)
        pools[source] = deduped

    avail = {s: len(p) for s, p in pools.items()}
    quota = allocate(avail, args.target, args.max_share)

    selected: list[tuple[str, str]] = []
    for source in sorted(pools):
        k = min(quota[source], len(pools[source]))
        if k <= 0:
            continue
        picks = rng.sample(pools[source], k)
        selected.extend((t, source) for t in picks)

    # Deterministic interleave so chunks are mixed across sources rather than
    # being 300 lines of one corpus at a time.
    rng.shuffle(selected)

    os.makedirs(os.path.dirname(OUT_PATH), exist_ok=True)
    with open(OUT_PATH, "w", encoding="utf-8", newline="\n") as fh:
        for i, (text, source) in enumerate(selected):
            fh.write(json.dumps({"id": f"c{i:06d}", "text": text, "source": source},
                                ensure_ascii=False) + "\n")

    # ---- stats -----------------------------------------------------------
    per_source = Counter(s for _, s in selected)
    len_by_source: dict[str, Counter] = defaultdict(Counter)
    all_lens = Counter()
    for text, source in selected:
        n = word_count(text)
        len_by_source[source][n] += 1
        all_lens[n] += 1

    total = len(selected)
    lines = [
        "# Corpus stats",
        "",
        f"Generated by `text/build_corpus.py` (seed {args.seed}, target {args.target:,}, "
        f"max share {args.max_share:.0%}).",
        f"Output: `text/data/corpus.jsonl` - **{total:,} lines**.",
        "",
        "## Per source",
        "",
        "| source | raw utterances | eligible after filters | sampled | share |",
        "| --- | ---: | ---: | ---: | ---: |",
    ]
    for source in sorted(pools):
        lines.append(f"| `{source}` | {raw_totals[source]:,} | {avail[source]:,} | "
                     f"{per_source.get(source, 0):,} | "
                     f"{(per_source.get(source, 0) / total if total else 0):.1%} |")
    lines.append(f"| **total** | {sum(raw_totals.values()):,} | {sum(avail.values()):,} | "
                 f"{total:,} | 100% |")
    lines += ["", "## Length histogram (words)", "",
              "| bucket | lines | share | " +
              " | ".join(f"`{s}`" for s in sorted(pools)) + " |",
              "| --- | ---: | ---: | " + " | ".join("---:" for _ in pools) + " |"]
    for label, count in histogram(all_lens):
        per = " | ".join(str(sum(c for n, c in len_by_source[s].items()
                                 if _in_bucket(n, label)))
                         for s in sorted(pools))
        bar = "#" * max(0, round(40 * count / total)) if total else ""
        lines.append(f"| {label} `{bar}` | {count:,} | {count / total:.1%} | {per} |")
    mean_len = sum(n * c for n, c in all_lens.items()) / total if total else 0
    lines += ["", f"Mean length {mean_len:.1f} words; "
                  f"{sum(c for n, c in all_lens.items() if n <= SHORT_WORDS) / total:.0%} "
                  f"of lines are <= {SHORT_WORDS} words.", ""]

    lines += ["## Why lines were dropped", "",
              "| source | reason | count |", "| --- | --- | ---: |"]
    for source in sorted(drops):
        for reason, count in drops[source].most_common():
            lines.append(f"| `{source}` | {reason} | {count:,} |")
    lines += ["",
              "Filters: 1-25 words, speaker prefixes stripped, whitespace/quotes normalised, no "
              "URLs or emails, Latin script only, case-insensitive dedupe within and across "
              f"sources. Lines longer than {SHORT_WORDS} words are kept only if they contain a "
              "question mark, an exclamation mark, a second-person pronoun, a leading imperative "
              "verb, or an emotion word.",
              ""]

    with open(STATS_PATH, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(lines))

    print(f"\nwrote {OUT_PATH} ({total:,} lines)")
    print(f"wrote {STATS_PATH}")
    return 0


def _in_bucket(n: int, label: str) -> bool:
    lo, hi = label.split("-")
    return int(lo) <= n <= int(hi)


if __name__ == "__main__":
    raise SystemExit(main())
