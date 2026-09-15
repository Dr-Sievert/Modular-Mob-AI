#!/usr/bin/env python3
"""Step 1: download public dialogue corpora and extract raw utterances.

Writes one JSONL file per source to text/data/raw/<name>.jsonl, each line::

    {"text": "...", "source": "<name>", "meta": {...}}

Any labels the source already carries (DailyDialog acts/emotions,
EmpatheticDialogues emotion) are kept verbatim inside ``meta`` so the later
stages can use them as weak signal.

Stdlib only (urllib/zipfile/tarfile/csv/json). Single process, streaming
readers, small memory footprint. Downloaded archives are cached under
text/data/raw/_cache/ so re-running is cheap.

Usage::

    python text/fetch_corpora.py                 # all sources
    python text/fetch_corpora.py --only dailydialog empathetic
    python text/fetch_corpora.py --force         # ignore the archive cache
"""

from __future__ import annotations

import argparse
import csv
import io
import json
import os
import re
import sys
import tarfile
import time
import urllib.error
import urllib.request
import zipfile
from datetime import date

HERE = os.path.dirname(os.path.abspath(__file__))
RAW_DIR = os.path.join(HERE, "data", "raw")
CACHE_DIR = os.path.join(RAW_DIR, "_cache")

UA = {"User-Agent": "Mozilla/5.0 (compatible; dwarfsim-corpus-fetch/1.0)"}
TIMEOUT = 120
ATTEMPTS = 2

# Cap for the one source that is far too large to take whole (342 MB of raw
# Minecraft server chat): we stream a prefix and stop.
MINECRAFT_BYTE_CAP = 12 * 1024 * 1024


# --------------------------------------------------------------------------
# download helpers
# --------------------------------------------------------------------------

def _download(url: str, dest: str, force: bool = False, byte_cap: int | None = None) -> int:
    """Download `url` to `dest` with ATTEMPTS tries. Returns bytes on disk."""
    if os.path.exists(dest) and not force and os.path.getsize(dest) > 0:
        size = os.path.getsize(dest)
        print(f"    cached: {os.path.basename(dest)} ({_human(size)})")
        return size

    last_err = None
    for attempt in range(1, ATTEMPTS + 1):
        try:
            req = urllib.request.Request(url, headers=UA)
            with urllib.request.urlopen(req, timeout=TIMEOUT) as resp, open(dest + ".part", "wb") as fh:
                total = 0
                while True:
                    block = resp.read(1 << 18)
                    if not block:
                        break
                    if byte_cap is not None and total + len(block) > byte_cap:
                        fh.write(block[: byte_cap - total])
                        total = byte_cap
                        break
                    fh.write(block)
                    total += len(block)
            os.replace(dest + ".part", dest)
            print(f"    downloaded: {os.path.basename(dest)} ({_human(total)})"
                  + (" [truncated]" if byte_cap is not None and total >= byte_cap else ""))
            return total
        except Exception as exc:  # noqa: BLE001 - report and retry
            last_err = exc
            print(f"    attempt {attempt}/{ATTEMPTS} failed: {type(exc).__name__}: {exc}")
            if attempt < ATTEMPTS:
                time.sleep(2)
    raise RuntimeError(f"download failed after {ATTEMPTS} attempts: {url} ({last_err})")


def _human(n: int) -> str:
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return f"{n:.1f} {unit}" if unit != "B" else f"{n} B"
        n /= 1024.0
    return str(n)


class Writer:
    """Buffered JSONL writer that counts records."""

    def __init__(self, path: str, source: str):
        self.path = path
        self.source = source
        self.n = 0
        self._fh = open(path, "w", encoding="utf-8", newline="\n")

    def write(self, text: str, meta: dict) -> None:
        text = (text or "").strip()
        if not text:
            return
        self._fh.write(json.dumps(
            {"text": text, "source": self.source, "meta": meta},
            ensure_ascii=False,
        ) + "\n")
        self.n += 1

    def close(self) -> int:
        self._fh.close()
        return self.n


# --------------------------------------------------------------------------
# source 1: DailyDialog
# --------------------------------------------------------------------------

# The original http://yanran.li/files/ijcnlp_dailydialog.zip is dead (serves an
# HTML placeholder). The canonical HF repo li2017dailydialog/daily_dialog is a
# loading-script-only repo with no raw data files, so we use the roskoN mirror,
# which carries the untouched original .txt files inside per-split zips.
DD_BASE = "https://huggingface.co/datasets/roskoN/dailydialog/resolve/main/{split}.zip"

DD_ACTS = {1: "inform", 2: "question", 3: "directive", 4: "commissive"}
DD_EMOTIONS = {
    0: "none", 1: "anger", 2: "disgust", 3: "fear",
    4: "happiness", 5: "sadness", 6: "surprise",
}


def fetch_dailydialog(force: bool) -> dict:
    out = Writer(os.path.join(RAW_DIR, "dailydialog.jsonl"), "dailydialog")
    total_bytes = 0
    for split in ("train", "validation", "test"):
        url = DD_BASE.format(split=split)
        dest = os.path.join(CACHE_DIR, f"dailydialog_{split}.zip")
        total_bytes += _download(url, dest, force)
        with zipfile.ZipFile(dest) as zf:
            names = zf.namelist()
            text_name = _pick(names, "dialogues_", exclude=("act", "emotion"))
            act_name = _pick(names, "dialogues_act")
            emo_name = _pick(names, "dialogues_emotion")
            texts = zf.read(text_name).decode("utf-8", "replace").splitlines()
            acts = zf.read(act_name).decode("utf-8", "replace").splitlines() if act_name else []
            emos = zf.read(emo_name).decode("utf-8", "replace").splitlines() if emo_name else []

        for d_i, line in enumerate(texts):
            utts = [u.strip() for u in line.split("__eou__") if u.strip()]
            a = acts[d_i].split() if d_i < len(acts) else []
            e = emos[d_i].split() if d_i < len(emos) else []
            for t_i, utt in enumerate(utts):
                meta = {"split": split, "dialogue_id": f"{split}-{d_i}", "turn": t_i}
                if t_i < len(a):
                    meta["act"] = DD_ACTS.get(int(a[t_i]), a[t_i])
                    meta["act_id"] = int(a[t_i])
                if t_i < len(e):
                    meta["emotion"] = DD_EMOTIONS.get(int(e[t_i]), e[t_i])
                    meta["emotion_id"] = int(e[t_i])
                out.write(utt, meta)
    return {"utterances": out.close(), "bytes": total_bytes, "path": out.path}


def _pick(names, prefix, exclude=()):
    for n in names:
        base = os.path.basename(n)
        if not base.endswith(".txt"):
            continue
        if not base.startswith(prefix):
            continue
        if any(x in base for x in exclude):
            continue
        return n
    return None


# --------------------------------------------------------------------------
# source 2: EmpatheticDialogues
# --------------------------------------------------------------------------

ED_URL = "https://dl.fbaipublicfiles.com/parlai/empatheticdialogues/empatheticdialogues.tar.gz"


def fetch_empathetic(force: bool) -> dict:
    dest = os.path.join(CACHE_DIR, "empatheticdialogues.tar.gz")
    nbytes = _download(ED_URL, dest, force)
    out = Writer(os.path.join(RAW_DIR, "empatheticdialogues.jsonl"), "empatheticdialogues")

    with tarfile.open(dest, "r:gz") as tf:
        for member in tf.getmembers():
            base = os.path.basename(member.name)
            if not member.isfile() or not base.endswith(".csv"):
                continue
            split = base[: -len(".csv")]
            fh = tf.extractfile(member)
            if fh is None:
                continue
            # The csv has unquoted stray commas in `tags`; restkey soaks them up.
            reader = csv.DictReader(
                io.TextIOWrapper(fh, encoding="utf-8", errors="replace"),
                restkey="_extra",
            )
            for row in reader:
                utt = (row.get("utterance") or "").replace("_comma_", ",")
                if not utt.strip():
                    continue
                out.write(utt, {
                    "split": split,
                    "emotion": row.get("context"),
                    "conv_id": row.get("conv_id"),
                    "utterance_idx": row.get("utterance_idx"),
                })
    return {"utterances": out.close(), "bytes": nbytes, "path": out.path}


# --------------------------------------------------------------------------
# source 3: Cornell Movie-Dialogs (via ConvoKit)
# --------------------------------------------------------------------------

CORNELL_URL = "https://zissou.infosci.cornell.edu/convokit/datasets/movie-corpus/movie-corpus.zip"


def fetch_cornell(force: bool) -> dict:
    dest = os.path.join(CACHE_DIR, "movie-corpus.zip")
    nbytes = _download(CORNELL_URL, dest, force)
    out = Writer(os.path.join(RAW_DIR, "cornell_movie.jsonl"), "cornell_movie")

    with zipfile.ZipFile(dest) as zf:
        name = next((n for n in zf.namelist() if os.path.basename(n) == "utterances.jsonl"), None)
        if name is None:
            raise RuntimeError("utterances.jsonl not found inside movie-corpus.zip")
        # Stream: the file is ~300 MB uncompressed, never read it whole.
        with zf.open(name) as raw:
            for line in io.TextIOWrapper(raw, encoding="utf-8", errors="replace"):
                line = line.strip()
                if not line:
                    continue
                try:
                    obj = json.loads(line)
                except json.JSONDecodeError:
                    continue
                out.write(obj.get("text", ""), {
                    "utt_id": obj.get("id"),
                    "conversation_id": obj.get("conversation_id"),
                    "movie_id": (obj.get("meta") or {}).get("movie_idx"),
                })
    return {"utterances": out.close(), "bytes": nbytes, "path": out.path}


# --------------------------------------------------------------------------
# source 4 (optional bonus): Minecraft server chat
# --------------------------------------------------------------------------

MC_URL = "https://huggingface.co/datasets/declip/Minecraft-Server-Chat/resolve/main/clean.json"
_MC_OBJ = re.compile(r'\{\s*"username":\s*"(?:[^"\\]|\\.)*",\s*"content":\s*((?:"(?:[^"\\]|\\.)*"))', re.S)


def fetch_minecraft(force: bool) -> dict:
    """The full file is 342 MB, well over the size budget, so we stream only a
    prefix and parse the complete records inside it."""
    dest = os.path.join(CACHE_DIR, "minecraft_chat_prefix.json")
    nbytes = _download(MC_URL, dest, force, byte_cap=MINECRAFT_BYTE_CAP)
    out = Writer(os.path.join(RAW_DIR, "minecraft_chat.jsonl"), "minecraft_chat")

    with open(dest, "r", encoding="utf-8", errors="replace") as fh:
        blob = fh.read()
    # Usernames are deliberately dropped: we only want the chat text.
    for i, m in enumerate(_MC_OBJ.finditer(blob)):
        try:
            content = json.loads(m.group(1))
        except json.JSONDecodeError:
            continue
        out.write(content, {"idx": i})
    return {"utterances": out.close(), "bytes": nbytes, "path": out.path,
            "truncated": True}


# --------------------------------------------------------------------------
# registry + SOURCES.md
# --------------------------------------------------------------------------

SOURCES = [
    {
        "name": "dailydialog",
        "title": "DailyDialog (via the roskoN/dailydialog mirror on Hugging Face)",
        "url": DD_BASE.format(split="{train,validation,test}"),
        "homepage": "https://huggingface.co/datasets/roskoN/dailydialog",
        "license": "CC BY-NC-SA 4.0 as stated by the original authors (Li et al., IJCNLP 2017); "
                   "non-commercial research use.",
        "notes": "13,118 dialogues with per-utterance dialogue-act (inform/question/directive/"
                 "commissive) and emotion labels, both kept in `meta`. The original "
                 "http://yanran.li/files/ijcnlp_dailydialog.zip is dead and the canonical HF repo "
                 "li2017dailydialog/daily_dialog ships only a loading script, so this mirror of the "
                 "untouched original .txt files is used.",
        "fn": fetch_dailydialog,
    },
    {
        "name": "empatheticdialogues",
        "title": "EmpatheticDialogues (Facebook AI / ParlAI)",
        "url": ED_URL,
        "homepage": "https://github.com/facebookresearch/EmpatheticDialogues",
        "license": "CC BY-NC 4.0 per the facebookresearch/EmpatheticDialogues repository; "
                   "non-commercial research use.",
        "notes": "~25k crowdsourced conversations grounded in one of 32 emotion labels; the "
                 "emotion (`context` column) is kept in `meta`. `_comma_` placeholders in the CSV "
                 "are restored to real commas.",
        "fn": fetch_empathetic,
    },
    {
        "name": "cornell_movie",
        "title": "Cornell Movie-Dialogs Corpus (ConvoKit movie-corpus release)",
        "url": CORNELL_URL,
        "homepage": "https://convokit.cornell.edu/documentation/movie.html",
        "license": "Free for research use with citation of Danescu-Niculescu-Mizil & Lee (2011); "
                   "the underlying movie scripts remain the property of their authors.",
        "notes": "~305k utterances from ~617 films. Fictional, dramatic and skewed towards "
                 "conflict, which is useful for THREAT/INSULT/ACCUSE coverage. Streamed out of the "
                 "zip line by line. The original cs.cornell.edu zip fails TLS verification from "
                 "this machine, hence the ConvoKit mirror.",
        "fn": fetch_cornell,
    },
    {
        "name": "minecraft_chat",
        "title": "Minecraft Server Chat (declip/Minecraft-Server-Chat)",
        "url": MC_URL,
        "homepage": "https://huggingface.co/datasets/declip/Minecraft-Server-Chat",
        "license": "CC0-1.0 (public domain dedication) as stated on the dataset card.",
        "notes": "Real public Minecraft server chat: exactly the register the classifier will see "
                 "in game (very short, typo-heavy, domain vocabulary). The full file is 342 MB, so "
                 "only the first 12 MB are streamed and the complete records in that prefix are "
                 "parsed. Usernames are dropped; only `content` is kept. Caveat: unmoderated "
                 "player chat, so it contains profanity and noise - the filters in "
                 "build_corpus.py remove some but not all of it.",
        "fn": fetch_minecraft,
    },
]


def write_sources_md(results: dict) -> str:
    path = os.path.join(RAW_DIR, "SOURCES.md")
    lines = [
        "# Raw corpus sources",
        "",
        f"Generated by `text/fetch_corpora.py` on {date.today().isoformat()}.",
        "",
        "Every file below is `text/data/raw/<name>.jsonl`, one JSON object per line:",
        '`{"text": ..., "source": ..., "meta": {...}}`. Labels that the source already carries are',
        "preserved in `meta`.",
        "",
        "| source | utterances | download | status |",
        "| --- | ---: | ---: | --- |",
    ]
    for src in SOURCES:
        r = results.get(src["name"], {})
        if r.get("ok"):
            lines.append(f"| `{src['name']}` | {r['utterances']:,} | {_human(r['bytes'])} | ok |")
        else:
            lines.append(f"| `{src['name']}` | - | - | FAILED: {r.get('error', 'not attempted')} |")
    lines.append("")

    for src in SOURCES:
        r = results.get(src["name"], {})
        lines += [
            f"## {src['title']}",
            "",
            f"- **file**: `text/data/raw/{src['name']}.jsonl`",
            f"- **download URL**: {src['url']}",
            f"- **source page**: {src['homepage']}",
            f"- **license / terms (as stated on the source page)**: {src['license']}",
        ]
        if r.get("ok"):
            lines.append(f"- **downloaded size**: {_human(r['bytes'])}"
                         + (" (truncated prefix, see notes)" if r.get("truncated") else ""))
            lines.append(f"- **utterances extracted**: {r['utterances']:,}")
        else:
            lines.append(f"- **status**: FAILED - {r.get('error', 'not attempted')}")
        lines += [f"- **notes**: {src['notes']}", ""]

    lines += [
        "## Terms",
        "",
        "DailyDialog and EmpatheticDialogues are non-commercial research licences and the Cornell",
        "corpus asks for citation; the derived `text/data/corpus.jsonl` and any labels built on top",
        "of it inherit those terms. Only the `minecraft_chat` slice is CC0. If this data is ever",
        "shipped inside a product, re-check each licence or regenerate the affected slices from the",
        "synthetic generation prompt instead.",
        "",
    ]
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(lines))
    return path


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--only", nargs="+", metavar="NAME",
                    choices=[s["name"] for s in SOURCES],
                    help="fetch only these sources")
    ap.add_argument("--force", action="store_true", help="re-download even if cached")
    args = ap.parse_args(argv)

    os.makedirs(CACHE_DIR, exist_ok=True)
    wanted = args.only or [s["name"] for s in SOURCES]

    results = {}
    for src in SOURCES:
        if src["name"] not in wanted:
            continue
        print(f"[{src['name']}] {src['title']}")
        t0 = time.time()
        try:
            info = src["fn"](args.force)
            info["ok"] = True
            results[src["name"]] = info
            print(f"    -> {info['utterances']:,} utterances in {time.time() - t0:.1f}s\n")
        except Exception as exc:  # noqa: BLE001 - a dead mirror must not kill the run
            results[src["name"]] = {"ok": False, "error": f"{type(exc).__name__}: {exc}"}
            print(f"    -> SKIPPED: {type(exc).__name__}: {exc}\n", file=sys.stderr)

    path = write_sources_md(results)
    print(f"wrote {path}")

    ok = [n for n, r in results.items() if r.get("ok")]
    bad = [n for n, r in results.items() if not r.get("ok")]
    print(f"\nsucceeded: {', '.join(ok) if ok else '(none)'}")
    if bad:
        print(f"failed:    {', '.join(bad)}")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
