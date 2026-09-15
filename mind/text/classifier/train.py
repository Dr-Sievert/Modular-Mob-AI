"""Train the tiny dialogue classifier.

    python -m text.classifier.train --data text/data/dialogue_partial.jsonl \
        --out text/models/clf --epochs 20 --seed 0 --hardcases

CPU only, one thread, one process, no DataLoader: the machine is shared with a
live training run. The whole thing is a few million parameters of embedding and
a 32x64 MLP, so a full run is well under a minute.

Writes into `--out`: model.pt (torch), weights.npz + model.json (the port),
metrics.json and report.md.
"""

from __future__ import annotations

import argparse
import json
import os
import random
import sys
import time
from collections import Counter

import numpy as np
import torch

if __package__ in (None, ""):                      # allow `python train.py` too
    sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
    __package__ = "text.classifier"

from .data import (DEFAULT_DATA, HARDCASES, SPLIT_SEED, TEST_FRACTION, batch_indices,
                   drop_hardcases, featurize, file_sha256, intent_class_weights,
                   load_jsonl, split_rows, targets_of)
from .labels import (ADDRESSED, ENUM_FIELDS, FLOAT_FIELDS, INTENTS, MASKED, SINCERITY,
                     TOPICS)
from .model import DialogueClassifier, loss_fn

ENUM_ORDER = ["intent", "topic", "addressed", "sincerity"]


# ---------------------------------------------------------------------------
# metrics (no sklearn: this repo is stdlib + torch + numpy)
# ---------------------------------------------------------------------------

def accuracy(true: np.ndarray, pred: np.ndarray) -> float:
    return float((true == pred).mean()) if len(true) else float("nan")


def macro_f1(true: np.ndarray, pred: np.ndarray, n_classes: int) -> float:
    """Macro F1 over the classes that actually occur in the gold labels."""
    present = sorted(set(true.tolist()))
    if not present:
        return float("nan")
    scores = []
    for c in present:
        tp = int(((true == c) & (pred == c)).sum())
        fp = int(((true != c) & (pred == c)).sum())
        fn = int(((true == c) & (pred != c)).sum())
        prec = tp / (tp + fp) if tp + fp else 0.0
        rec = tp / (tp + fn) if tp + fn else 0.0
        scores.append(2 * prec * rec / (prec + rec) if prec + rec else 0.0)
    return float(np.mean(scores))


def confused_pairs(true: np.ndarray, pred: np.ndarray, names: list[str], top: int = 6):
    counts = Counter((names[t], names[p]) for t, p in zip(true.tolist(), pred.tolist()) if t != p)
    return [{"true": t, "pred": p, "count": n} for (t, p), n in counts.most_common(top)]


def float_metrics(true: np.ndarray, pred: np.ndarray) -> dict:
    out = {}
    for i, field in enumerate(FLOAT_FIELDS):
        err = np.abs(true[:, i] - pred[:, i])
        out[field] = {"mae": float(err.mean()), "within_0.25": float((err <= 0.25).mean())}
    return out


# ---------------------------------------------------------------------------
# forward passes
# ---------------------------------------------------------------------------

def forward_all(model: DialogueClassifier, feats, sides, order: np.ndarray,
                batch_size: int) -> dict:
    """Logits/outputs for every row in `order`, in that order."""
    chunks = {k: [] for k in ENUM_ORDER + ["floats"]}
    model.eval()
    with torch.no_grad():
        for start in range(0, len(order), batch_size):
            sl = order[start:start + batch_size]
            idx, off, sd = batch_indices(feats, sides, sl)
            out = model(torch.from_numpy(idx), torch.from_numpy(off), torch.from_numpy(sd))
            for k in chunks:
                chunks[k].append(out[k])
    return {k: torch.cat(v) if v else torch.zeros(0) for k, v in chunks.items()}


def evaluate(model, feats, sides, rows, intent_w, batch_size=512) -> tuple[dict, dict]:
    order = np.arange(len(rows))
    out = forward_all(model, feats, sides, order, batch_size)
    tgt_np = targets_of(rows)
    tgt = {k: torch.from_numpy(v) for k, v in tgt_np.items()}
    parts = loss_fn(out, tgt, intent_w)
    preds = {k: out[k].argmax(dim=1).numpy() for k in ENUM_ORDER}
    preds["floats"] = out["floats"].numpy()
    return {k: float(v) for k, v in parts.items()}, preds


def score(rows, preds, tgt) -> dict:
    """The full metric block for one split."""
    res = {}
    for field in ENUM_ORDER:
        names = ENUM_FIELDS[field]
        true, pred = tgt[field], preds[field]
        mask = true != MASKED
        true_m, pred_m = true[mask], pred[mask]
        block = {"n": int(mask.sum()),
                 "accuracy": accuracy(true_m, pred_m),
                 "macro_f1": macro_f1(true_m, pred_m, len(names))}
        if field == "intent":
            block["most_confused"] = confused_pairs(true_m, pred_m, names)
        res[field] = block
    res["floats"] = float_metrics(tgt["floats"], preds["floats"])
    res["by_source"] = by_source(rows, preds, tgt)
    return res


def by_source(rows, preds, tgt) -> dict:
    out = {}
    sources = sorted({r.get("source", "unknown") for r in rows})
    src = np.array([r.get("source", "unknown") for r in rows])
    for s in sources:
        m = src == s
        entry = {"n": int(m.sum())}
        for field in ENUM_ORDER:
            sub = m & (tgt[field] != MASKED)
            entry[field] = accuracy(tgt[field][sub], preds[field][sub]) if sub.any() else None
        out[s] = entry
    return out


# ---------------------------------------------------------------------------
# training
# ---------------------------------------------------------------------------

def train(args) -> dict:
    torch.set_num_threads(1)
    torch.manual_seed(args.seed)
    np.random.seed(args.seed)

    rows = load_jsonl(args.data)
    hard_rows = load_jsonl(args.hardcases_file) if os.path.exists(args.hardcases_file) else []
    rows, n_leaked = drop_hardcases(rows, hard_rows)
    if n_leaked:
        print(f"dropped {n_leaked} training rows that duplicate a hard case")
    if not rows:
        raise SystemExit(f"no rows in {args.data}")

    n_sincerity = sum(1 for r in rows if r.get("sincerity") in SINCERITY)
    train_rows, test_rows = split_rows(rows, TEST_FRACTION, args.split_seed)
    if args.oversample_generated > 1:
        # The generated lines are the only ones with the game's vocabulary and they are a small share of a
        # large corpus; repeating them in the training half (never the test half) keeps them from being drowned.
        extra = [r for r in train_rows if r.get("source") == "generated"]
        train_rows = train_rows + extra * (args.oversample_generated - 1)
        random.Random(args.split_seed).shuffle(train_rows)
    n_gen = sum(1 for r in test_rows if r.get("source") == "generated")

    print(f"data      {args.data}")
    print(f"rows      {len(rows):,}   train {len(train_rows):,}   test {len(test_rows):,}"
          f"   (generated held out: {n_gen})")
    print(f"sincerity labelled on {n_sincerity:,}/{len(rows):,} rows "
          f"({n_sincerity / len(rows):.1%}); the rest are masked out of that head")
    print(f"hardcases {len(hard_rows)} lines from {args.hardcases_file}"
          if hard_rows else "hardcases none")

    feats_tr, sides_tr = featurize(train_rows)
    feats_te, sides_te = featurize(test_rows)
    tgt_tr_np = targets_of(train_rows)
    tgt_tr = {k: torch.from_numpy(v) for k, v in tgt_tr_np.items()}
    tgt_te_np = targets_of(test_rows)

    intent_w = torch.from_numpy(intent_class_weights(train_rows, args.intent_weight_power))
    model = DialogueClassifier()
    opt = torch.optim.Adam(model.parameters(), lr=args.lr, weight_decay=args.weight_decay)

    rng = np.random.RandomState(args.seed)
    best = {"loss": float("inf"), "epoch": 0, "state": None}
    history = []
    t0 = time.perf_counter()

    for epoch in range(1, args.epochs + 1):
        model.train()
        order = rng.permutation(len(train_rows))
        running, seen = 0.0, 0
        for start in range(0, len(order), args.batch_size):
            sl = order[start:start + args.batch_size]
            idx, off, sd = batch_indices(feats_tr, sides_tr, sl)
            out = model(torch.from_numpy(idx), torch.from_numpy(off), torch.from_numpy(sd))
            tgt = {k: v[sl] for k, v in tgt_tr.items()}
            parts = loss_fn(out, tgt, intent_w)
            opt.zero_grad(set_to_none=True)
            parts["total"].backward()
            opt.step()
            running += float(parts["total"]) * len(sl)
            seen += len(sl)

        te_loss, te_preds = evaluate(model, feats_te, sides_te, test_rows, intent_w)
        tr_loss = running / max(seen, 1)
        history.append({"epoch": epoch, "train_loss": tr_loss, "test_loss": te_loss["total"],
                        "test_parts": {k: v for k, v in te_loss.items() if k != "total"}})
        flag = ""
        if te_loss["total"] < best["loss"] - args.min_delta:
            best = {"loss": te_loss["total"], "epoch": epoch,
                    "state": {k: v.detach().clone() for k, v in model.state_dict().items()}}
            flag = " *"
        print(f"epoch {epoch:>3}/{args.epochs}  train {tr_loss:6.3f}  test {te_loss['total']:6.3f}"
              f"   [intent {te_loss['intent']:.3f} topic {te_loss['topic']:.3f}"
              f" addr {te_loss['addressed']:.3f} sinc {te_loss['sincerity']:.3f}"
              f" floats {te_loss['floats']:.3f}]  {time.perf_counter() - t0:5.1f}s{flag}")
        if epoch - best["epoch"] >= args.patience:
            print(f"early stop: no test-loss improvement for {args.patience} epochs")
            break

    if best["state"] is not None:
        model.load_state_dict(best["state"])
    train_seconds = time.perf_counter() - t0
    print(f"\nbest epoch {best['epoch']} (test loss {best['loss']:.4f}), "
          f"{train_seconds:.1f}s total")

    # ---- final metrics ---------------------------------------------------
    te_loss, te_preds = evaluate(model, feats_te, sides_te, test_rows, intent_w)
    tr_loss_eval, tr_preds = evaluate(model, feats_tr, sides_tr, train_rows, intent_w)
    test_metrics = score(test_rows, te_preds, tgt_te_np)
    train_metrics = score(train_rows, tr_preds, tgt_tr_np)

    hard_metrics = None
    if args.hardcases and hard_rows:
        hf, hs = featurize(hard_rows)
        hl, hp = evaluate(model, hf, hs, hard_rows, intent_w)
        hard_metrics = score(hard_rows, hp, targets_of(hard_rows))
        hard_metrics["loss"] = hl["total"]

    metrics = {
        "data": {
            "path": os.path.relpath(args.data).replace("\\", "/"),
            "sha256": file_sha256(args.data),
            "rows": len(rows),
            "train": len(train_rows),
            "test": len(test_rows),
            "generated_held_out": n_gen,
            "sincerity_labeled_rows": n_sincerity,
            "hardcase_rows": len(hard_rows),
            "hardcases_dropped_from_train": n_leaked,
        },
        "config": {
            "epochs_requested": args.epochs, "epochs_run": len(history),
            "best_epoch": best["epoch"], "seed": args.seed, "split_seed": args.split_seed,
            "lr": args.lr, "weight_decay": args.weight_decay,
            "batch_size": args.batch_size, "patience": args.patience,
            "test_fraction": TEST_FRACTION,
            "intent_weight_power": args.intent_weight_power,
            "train_seconds": round(train_seconds, 1),
            "threads": torch.get_num_threads(),
        },
        "loss": {"train": tr_loss_eval["total"], "test": te_loss["total"],
                 "test_parts": {k: v for k, v in te_loss.items() if k != "total"}},
        "history": history,
        "test": test_metrics,
        "train": train_metrics,
        "hardcases": hard_metrics,
    }
    print_report(metrics)
    return {"model": model, "metrics": metrics, "test_rows": test_rows,
            "train_rows": train_rows}


# ---------------------------------------------------------------------------
# reporting
# ---------------------------------------------------------------------------

def print_report(m: dict) -> None:
    t = m["test"]
    print("\n" + "=" * 70)
    print("test set: %d lines" % m["data"]["test"])
    print("=" * 70)
    print(f"{'head':<12}{'n':>7}{'accuracy':>11}{'macro-F1':>11}")
    for field in ENUM_ORDER:
        b = t[field]
        if b["n"] == 0:
            print(f"{field:<12}{0:>7}{'-':>11}{'-':>11}   (no labelled rows)")
        else:
            print(f"{field:<12}{b['n']:>7}{b['accuracy']:>11.3f}{b['macro_f1']:>11.3f}")
    print(f"\n{'float':<12}{'MAE':>11}{'within 0.25':>13}")
    for field in FLOAT_FIELDS:
        b = t["floats"][field]
        print(f"{field:<12}{b['mae']:>11.3f}{b['within_0.25']:>13.1%}")
    print("\nsix most confused intent pairs (true -> predicted)")
    for row in t["intent"]["most_confused"]:
        print(f"  {row['true']:<10} -> {row['pred']:<10} x{row['count']}")
    print(f"\n{'source':<22}{'n':>7}{'intent':>9}{'topic':>9}{'addr':>9}{'sinc':>9}")
    for src, b in sorted(t["by_source"].items()):
        cells = "".join(f"{b[f]:>9.3f}" if b[f] is not None else f"{'-':>9}"
                        for f in ENUM_ORDER)
        print(f"{src:<22}{b['n']:>7}{cells}")
    if m.get("hardcases"):
        h = m["hardcases"]
        print(f"\nhard cases ({h['intent']['n']} hand-written lines)")
        for field in ENUM_ORDER:
            b = h[field]
            print(f"  {field:<11}" + ("n/a" if b["n"] == 0 else f"{b['accuracy']:.3f}"))
        for field in FLOAT_FIELDS:
            b = h["floats"][field]
            print(f"  {field:<11}MAE {b['mae']:.3f}   within 0.25 {b['within_0.25']:.1%}")


def write_report_md(path: str, m: dict, extra: dict) -> None:
    t = m["test"]
    lines = ["# Dialogue classifier", "",
             f"Trained on `{m['data']['path']}` "
             f"({m['data']['rows']:,} lines: {m['data']['train']:,} train, "
             f"{m['data']['test']:,} test, stratified by source, "
             f"seed {m['config']['split_seed']}).",
             "", f"- best epoch {m['config']['best_epoch']} of "
             f"{m['config']['epochs_run']} run, test loss {m['loss']['test']:.4f}",
             f"- {m['config']['train_seconds']}s on "
             f"{m['config']['threads']} CPU thread(s)",
             f"- `sincerity` labelled on {m['data']['sincerity_labeled_rows']:,} of "
             f"{m['data']['rows']:,} rows; unlabelled rows are masked out of that "
             f"head's loss and metrics",
             f"- data sha256 `{m['data']['sha256'][:16]}...`", "",
             "## Test metrics", "",
             "| head | n | accuracy | macro-F1 |", "| --- | ---: | ---: | ---: |"]
    for field in ENUM_ORDER:
        b = t[field]
        if b["n"] == 0:
            lines.append(f"| {field} | 0 | - | - |")
        else:
            lines.append(f"| {field} | {b['n']:,} | {b['accuracy']:.3f} | {b['macro_f1']:.3f} |")
    lines += ["", "| float | MAE | within 0.25 |", "| --- | ---: | ---: |"]
    for field in FLOAT_FIELDS:
        b = t["floats"][field]
        lines.append(f"| {field} | {b['mae']:.3f} | {b['within_0.25']:.1%} |")
    lines += ["", "## Most confused intent pairs", "",
              "| true | predicted | count |", "| --- | --- | ---: |"]
    for row in t["intent"]["most_confused"]:
        lines.append(f"| {row['true']} | {row['pred']} | {row['count']} |")
    lines += ["", "## Accuracy by source (test set)", "",
              "| source | n | intent | topic | addressed | sincerity |",
              "| --- | ---: | ---: | ---: | ---: | ---: |"]
    for src, b in sorted(t["by_source"].items()):
        cells = " | ".join(f"{b[f]:.3f}" if b[f] is not None else "-" for f in ENUM_ORDER)
        lines.append(f"| {src} | {b['n']:,} | {cells} |")
    if m.get("hardcases"):
        h = m["hardcases"]
        lines += ["", "## Hard cases", "",
                  "40 hand-written lines from `text/data/hardcases.jsonl`, never trained on.",
                  "", "| head | n | accuracy |", "| --- | ---: | ---: |"]
        for field in ENUM_ORDER:
            b = h[field]
            lines.append(f"| {field} | {b['n']} | "
                         + ("-" if b["n"] == 0 else f"{b['accuracy']:.3f}") + " |")
        lines += ["", "| float | MAE | within 0.25 |", "| --- | ---: | ---: |"]
        for field in FLOAT_FIELDS:
            b = h["floats"][field]
            lines.append(f"| {field} | {b['mae']:.3f} | {b['within_0.25']:.1%} |")
    if extra.get("latency_us"):
        lat = extra["latency_us"]
        lines += ["", "## Inference (pure numpy, one line at a time)", "",
                  f"- mean {lat['mean']:.1f} us, p99 {lat['p99']:.1f} us "
                  f"over {lat['n']:,} lines",
                  f"- torch/numpy parity: max abs diff {extra['parity_max_diff']:.2e} "
                  f"on {extra['parity_n']} lines"]
    lines += ["", "## Files", "",
              "| file | what |", "| --- | --- |",
              "| `model.pt` | torch state dict |",
              "| `weights.npz` | float32 arrays, the order is in `model.json` |",
              "| `model.json` | dims, buckets, hash, label order, tokenizer rules, metrics |",
              "| `metrics.json` | all of the above plus the per-epoch history, machine readable |",
              "| `report.md` | this file |", ""]
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(lines))


# ---------------------------------------------------------------------------

def build_parser() -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--data", default=DEFAULT_DATA)
    ap.add_argument("--out", default=os.path.join(os.path.dirname(os.path.dirname(
        os.path.abspath(__file__))), "models", "clf"))
    ap.add_argument("--epochs", type=int, default=30)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--split-seed", type=int, default=SPLIT_SEED)
    ap.add_argument("--lr", type=float, default=0.01)
    ap.add_argument("--weight-decay", type=float, default=5e-3,
                    help="Adam L2; the 2M-parameter embedding memorises a 5k-line "
                         "set in two epochs without it")
    ap.add_argument("--batch-size", type=int, default=128)
    ap.add_argument("--patience", type=int, default=4, help="epochs without improvement")
    ap.add_argument("--min-delta", type=float, default=1e-4)
    ap.add_argument("--intent-weight-power", type=float, default=0.5)
    ap.add_argument("--oversample-generated", type=int, default=1,
                    help="repeat the generated source this many times in the training half")
    ap.add_argument("--hardcases", action="store_true",
                    help="also score text/data/hardcases.jsonl separately")
    ap.add_argument("--hardcases-file", default=HARDCASES)
    ap.add_argument("--no-export", action="store_true", help="skip weights.npz/model.json")
    ap.add_argument("--bench-lines", type=int, default=2000,
                    help="lines for the numpy latency benchmark (0 to skip)")
    return ap


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    result = train(args)
    model, metrics = result["model"], result["metrics"]

    os.makedirs(args.out, exist_ok=True)
    torch.save(model.state_dict(), os.path.join(args.out, "model.pt"))

    extra = {}
    if not args.no_export:
        from .export import export_model
        export_model(model, args.out, metrics)
        from .infer import Classifier, check_parity
        clf = Classifier.load(args.out)
        pool = [r["text"] for r in result["train_rows"]]
        extra["parity_max_diff"], extra["parity_n"] = check_parity(
            model, clf, pool, n=min(500, len(pool)), seed=args.seed)
        print(f"\ntorch vs numpy: max abs diff {extra['parity_max_diff']:.2e} "
              f"over {extra['parity_n']} lines")
        if args.bench_lines:
            extra["latency_us"] = clf.benchmark(pool, n=args.bench_lines)
            lat = extra["latency_us"]
            print(f"numpy inference over {lat['n']:,} lines: "
                  f"mean {lat['mean']:.1f} us, p99 {lat['p99']:.1f} us")

    metrics["inference"] = extra
    with open(os.path.join(args.out, "metrics.json"), "w", encoding="utf-8", newline="\n") as fh:
        json.dump(metrics, fh, indent=2)
        fh.write("\n")
    write_report_md(os.path.join(args.out, "report.md"), metrics, extra)
    if not args.no_export:
        from .export import stamp_metrics
        stamp_metrics(args.out, metrics)
    print(f"\nwrote {args.out}: model.pt, weights.npz, model.json, metrics.json, report.md")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
