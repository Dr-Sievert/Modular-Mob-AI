#!/usr/bin/env python3
"""Step 5 (optional): label the chunks with the Anthropic Message Batches API.

Only useful if you have an Anthropic API key. This is the automated version of
the manual flow in `text/LABELING_PROMPT.md`: same prompt, same chunk files, same
output location, so the two can be mixed freely.

Requires the official SDK, which is NOT installed by this pipeline::

    pip install anthropic
    set ANTHROPIC_API_KEY=sk-ant-...        # PowerShell: $env:ANTHROPIC_API_KEY="sk-ant-..."

The Batches API is asynchronous and costs 50% of the standard price. Most
batches finish within an hour (24 hours maximum).

Usage::

    python text/label_with_api.py submit                  # cost estimate only
    python text/label_with_api.py submit --yes            # actually submit
    python text/label_with_api.py submit --chunks 0-9 --model claude-sonnet-5 --yes
    python text/label_with_api.py status
    python text/label_with_api.py fetch                   # write results + merge

Idempotent and resumable: `submit` skips chunks that already have a labeled file
or sit in an unfetched batch, `fetch` skips batches it has already collected, and
`merge_labels.py` dedupes anyway.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
from datetime import datetime, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
CHUNK_DIR = os.path.join(HERE, "chunks")
PROMPT_PATH = os.path.join(HERE, "LABELING_PROMPT.md")
STATE_PATH = os.path.join(HERE, "data", "batches.json")
LABELED_DIR = os.path.join(HERE, "data", "labeled")

DEFAULT_MODEL = "claude-opus-5"
# Exactly these ids, no date suffixes.
MODELS = ["claude-opus-5", "claude-sonnet-5", "claude-haiku-4-5"]
# USD per million tokens, standard (non-batch) price.
PRICES = {
    "claude-opus-5": (5.0, 25.0),
    "claude-sonnet-5": (2.0, 10.0),
    "claude-haiku-4-5": (1.0, 5.0),
}
MAX_TOKENS = 16000
TOKENS_PER_INPUT_LINE = 15
TOKENS_PER_OUTPUT_LINE = 45
BATCH_DISCOUNT = 0.5


# --------------------------------------------------------------------------
# helpers
# --------------------------------------------------------------------------

def load_client():
    try:
        import anthropic
    except ImportError:
        print("The `anthropic` SDK is not installed. Install it with:\n"
              "    pip install anthropic\n"
              "and set ANTHROPIC_API_KEY in the environment.", file=sys.stderr)
        raise SystemExit(2)
    if not os.environ.get("ANTHROPIC_API_KEY"):
        print("ANTHROPIC_API_KEY is not set in the environment.", file=sys.stderr)
        raise SystemExit(2)
    return anthropic.Anthropic()          # reads ANTHROPIC_API_KEY from the environment


def system_prompt() -> str:
    """The pasteable half of LABELING_PROMPT.md: everything after the first `---`."""
    with open(PROMPT_PATH, "r", encoding="utf-8") as fh:
        body = fh.read()
    parts = re.split(r"(?m)^---\s*$", body, maxsplit=1)
    return (parts[1] if len(parts) > 1 else body).strip()


def all_chunks() -> list[tuple[str, str]]:
    """[(custom_id, path)] for every chunk file, sorted."""
    if not os.path.isdir(CHUNK_DIR):
        return []
    out = []
    for name in sorted(os.listdir(CHUNK_DIR)):
        if name.startswith("chunk_") and name.endswith(".txt"):
            out.append((name[: -len(".txt")], os.path.join(CHUNK_DIR, name)))
    return out


def parse_range(spec: str | None, available: list[str]) -> list[str]:
    """`--chunks 0-9,12,15-17` -> ['chunk_000', ...], filtered to what exists."""
    if not spec:
        return list(available)
    wanted = set()
    for part in spec.split(","):
        part = part.strip()
        if not part:
            continue
        if "-" in part.lstrip("chunk_"):
            lo, hi = part.replace("chunk_", "").split("-", 1)
            wanted.update(range(int(lo), int(hi) + 1))
        else:
            wanted.add(int(part.replace("chunk_", "")))
    names = {f"chunk_{i:03d}" for i in wanted}
    missing = names - set(available)
    if missing:
        print(f"warning: no chunk file for {', '.join(sorted(missing))}", file=sys.stderr)
    return [c for c in available if c in names]


def load_state() -> dict:
    if os.path.exists(STATE_PATH):
        with open(STATE_PATH, "r", encoding="utf-8") as fh:
            try:
                return json.load(fh)
            except json.JSONDecodeError:
                pass
    return {"batches": []}


def save_state(state: dict) -> None:
    os.makedirs(os.path.dirname(STATE_PATH), exist_ok=True)
    with open(STATE_PATH, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(state, fh, indent=2)
        fh.write("\n")


def n_lines(path: str) -> int:
    with open(path, "r", encoding="utf-8") as fh:
        return sum(1 for line in fh if line.strip())


def already_labeled(custom_id: str) -> bool:
    path = os.path.join(LABELED_DIR, custom_id + ".jsonl")
    return os.path.exists(path) and os.path.getsize(path) > 0


def pending_chunks(state: dict) -> set[str]:
    out = set()
    for batch in state["batches"]:
        if not batch.get("fetched"):
            out.update(batch.get("chunks", []))
    return out


def estimate_cost(model: str, chunk_paths: list[str], prompt_chars: int) -> dict:
    lines = sum(n_lines(p) for p in chunk_paths)
    prompt_tokens = prompt_chars // 4          # ~4 chars per token, good enough here
    in_tokens = lines * TOKENS_PER_INPUT_LINE + prompt_tokens * len(chunk_paths)
    out_tokens = lines * TOKENS_PER_OUTPUT_LINE
    in_price, out_price = PRICES[model]
    cost = (in_tokens / 1e6 * in_price + out_tokens / 1e6 * out_price) * BATCH_DISCOUNT
    return {"lines": lines, "requests": len(chunk_paths),
            "input_tokens": in_tokens, "output_tokens": out_tokens, "usd": cost}


def print_estimate(model: str, est: dict) -> None:
    in_price, out_price = PRICES[model]
    print(f"  model            {model}  "
          f"(${in_price:.2f} in / ${out_price:.2f} out per Mtok, halved for batch)")
    print(f"  requests         {est['requests']} (one per chunk)")
    print(f"  lines to label   {est['lines']:,}")
    print(f"  input tokens     ~{est['input_tokens']:,}"
          f"  ({TOKENS_PER_INPUT_LINE}/line + the prompt on every request)")
    print(f"  output tokens    ~{est['output_tokens']:,}  ({TOKENS_PER_OUTPUT_LINE}/line)")
    print(f"  ESTIMATED COST   ~${est['usd']:.2f}")
    print("  (an estimate, not a quote; the API bills actual tokens)")


# --------------------------------------------------------------------------
# subcommands
# --------------------------------------------------------------------------

def cmd_submit(args) -> int:
    chunks = all_chunks()
    if not chunks:
        print(f"no chunk files in {CHUNK_DIR}; run make_chunks.py first", file=sys.stderr)
        return 1
    by_id = dict(chunks)
    selected = parse_range(args.chunks, [c for c, _ in chunks])

    state = load_state()
    pending = pending_chunks(state)
    todo, skipped_done, skipped_pending = [], [], []
    for cid in selected:
        if not args.force and already_labeled(cid):
            skipped_done.append(cid)
        elif not args.force and cid in pending:
            skipped_pending.append(cid)
        else:
            todo.append(cid)

    if skipped_done:
        print(f"already labeled, skipping {len(skipped_done)} chunk(s) "
              f"(use --force to redo)")
    if skipped_pending:
        print(f"already in an unfetched batch, skipping {len(skipped_pending)} chunk(s)")
    if not todo:
        print("nothing to submit.")
        return 0

    prompt = system_prompt()
    est = estimate_cost(args.model, [by_id[c] for c in todo], len(prompt))
    lines_per_chunk = max(n_lines(by_id[c]) for c in todo)
    print("\nbatch plan")
    print_estimate(args.model, est)
    need = lines_per_chunk * TOKENS_PER_OUTPUT_LINE
    if need > MAX_TOKENS:
        print(f"\nWARNING: the largest chunk has {lines_per_chunk} lines, about {need:,} "
              f"output tokens, over max_tokens={MAX_TOKENS:,}. Re-chunk smaller:\n"
              f"    python text/make_chunks.py --size {int(MAX_TOKENS / TOKENS_PER_OUTPUT_LINE * 0.8)}")

    if not args.yes:
        print("\nNothing submitted. Re-run with --yes to submit this batch.")
        return 0

    import anthropic  # noqa: F401  (import error surfaced by load_client)
    from anthropic.types.message_create_params import MessageCreateParamsNonStreaming
    from anthropic.types.messages.batch_create_params import Request

    client = load_client()
    requests = []
    for cid in todo:
        with open(by_id[cid], "r", encoding="utf-8") as fh:
            chunk_text = fh.read()
        requests.append(Request(
            custom_id=cid,
            params=MessageCreateParamsNonStreaming(
                model=args.model,
                max_tokens=MAX_TOKENS,
                system=prompt,
                messages=[{"role": "user", "content": chunk_text}],
            ),
        ))

    batch = client.messages.batches.create(requests=requests)
    state["batches"].append({
        "id": batch.id,
        "model": args.model,
        "chunks": todo,
        "created_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "estimated_usd": round(est["usd"], 2),
        "fetched": False,
    })
    save_state(state)
    print(f"\nsubmitted batch {batch.id} ({len(todo)} requests, status "
          f"{batch.processing_status})")
    print(f"recorded in {STATE_PATH}")
    print("\nnext:\n    python text/label_with_api.py status\n"
          "    python text/label_with_api.py fetch      # once it has ended")
    return 0


def cmd_status(args) -> int:
    state = load_state()
    if not state["batches"]:
        print(f"no batches recorded in {STATE_PATH}")
        return 0
    client = load_client()
    for entry in state["batches"]:
        try:
            batch = client.messages.batches.retrieve(entry["id"])
        except Exception as exc:  # noqa: BLE001
            print(f"{entry['id']}  ERROR: {type(exc).__name__}: {exc}")
            continue
        counts = batch.request_counts
        entry["processing_status"] = batch.processing_status
        print(f"{batch.id}")
        print(f"  model      {entry.get('model')}")
        print(f"  status     {batch.processing_status}"
              f"{'  (fetched)' if entry.get('fetched') else ''}")
        print(f"  chunks     {len(entry.get('chunks', []))}")
        print(f"  requests   processing {counts.processing}, succeeded {counts.succeeded}, "
              f"errored {counts.errored}, canceled {counts.canceled}, expired {counts.expired}")
    save_state(state)

    if args.wait:
        print("\nwaiting for all batches to end (Ctrl-C to stop)...")
        while True:
            pend = [e for e in state["batches"] if not e.get("fetched")]
            if not pend:
                break
            statuses = [client.messages.batches.retrieve(e["id"]).processing_status
                        for e in pend]
            if all(s == "ended" for s in statuses):
                print("all batches ended.")
                break
            time.sleep(60)
    return 0


def cmd_fetch(args) -> int:
    state = load_state()
    if not state["batches"]:
        print(f"no batches recorded in {STATE_PATH}")
        return 0
    client = load_client()
    os.makedirs(LABELED_DIR, exist_ok=True)

    written = []
    for entry in state["batches"]:
        if entry.get("fetched") and not args.force:
            continue
        batch = client.messages.batches.retrieve(entry["id"])
        if batch.processing_status != "ended":
            print(f"{entry['id']}: still {batch.processing_status}, skipping")
            continue

        ok = err = 0
        # Results arrive in any order; key by custom_id, never by position.
        for result in client.messages.batches.results(entry["id"]):
            cid = result.custom_id
            kind = result.result.type
            if kind == "succeeded":
                msg = result.result.message
                text = next((b.text for b in msg.content if b.type == "text"), "")
                path = os.path.join(LABELED_DIR, f"{cid}.jsonl")
                with open(path, "w", encoding="utf-8", newline="\n") as fh:
                    fh.write(text.strip() + "\n")
                written.append(path)
                ok += 1
                if msg.stop_reason == "max_tokens":
                    print(f"  {cid}: hit max_tokens, the tail of the chunk is missing; "
                          f"re-run that chunk with a smaller --size")
                elif msg.stop_reason == "refusal":
                    print(f"  {cid}: refused by safety classifiers, no labels written")
            else:
                err += 1
                detail = getattr(getattr(result.result, "error", None), "type", kind)
                print(f"  {cid}: {kind} ({detail}) - resubmit this chunk")
        entry["fetched"] = True
        entry["succeeded"] = ok
        entry["failed"] = err
        print(f"{entry['id']}: {ok} succeeded, {err} failed")
    save_state(state)

    if not written:
        print("nothing new fetched.")
        return 0
    print(f"\nwrote {len(written)} file(s) to {LABELED_DIR}")

    if args.no_merge:
        print("--no-merge: not merging. Run:\n"
              "    python text/merge_labels.py text/data/labeled/*.jsonl")
        return 0
    print("\nmerging...\n")
    import merge_labels
    return merge_labels.main([os.path.join(LABELED_DIR, "*.jsonl")])


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("submit", help="build one batch from the chunks and submit it")
    p.add_argument("--model", default=DEFAULT_MODEL, choices=MODELS)
    p.add_argument("--chunks", help="subset, e.g. 0-9 or 0-4,7,12-14 (default: all)")
    p.add_argument("--yes", action="store_true", help="actually submit (costs money)")
    p.add_argument("--force", action="store_true",
                   help="resubmit chunks that are already labeled or pending")
    p.set_defaults(func=cmd_submit)

    p = sub.add_parser("status", help="poll the recorded batches")
    p.add_argument("--wait", action="store_true", help="poll every 60s until all have ended")
    p.set_defaults(func=cmd_status)

    p = sub.add_parser("fetch", help="write results to data/labeled/ and merge them")
    p.add_argument("--force", action="store_true", help="re-fetch batches already collected")
    p.add_argument("--no-merge", action="store_true", help="skip the merge step")
    p.set_defaults(func=cmd_fetch)

    args = ap.parse_args(argv)
    sys.path.insert(0, HERE)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
