"""Train the student to reproduce the teacher, and export it.

    python -m dwarfsim.learn.imitate --data runs/learn/teacher.npz --out runs/learn/imitator

**The loss is listwise.** A scorer is only ever asked which candidate wins, so it is trained on
whole decisions, not on candidates in isolation: softmax over the predicted scores of one
decision's candidates, cross-entropy against the teacher's pick. The softmax uses the sim's own
temperature (0.25), so the distribution being fitted is the distribution the sim samples from.

A small MSE term on the raw scores rides along. Cross-entropy alone is invariant to adding any
constant per decision and to scaling all scores, which would let the student drift to whatever
magnitudes it liked -- and then the softmax at 0.25 would be sharper or flatter than the teacher's
and the *variety* of the sim would change even where the argmax did not. The MSE pins it down.

    loss = CE(pred / T, teacher's pick) + mse_weight * mean((pred - teacher)^2)

**Two seeds are held out entirely**, not two random slices: neighbouring decisions inside one run
are nearly the same decision, so a random split would be measuring memorisation. Reported on the
held-out seeds: top-1 agreement with the teacher overall and per scenario, per-skill recall, and
the same overall number restricted to the decisions where the teacher's best two candidates were
within 0.5 of each other -- the ones where getting it wrong actually changes what the dwarf does.

**``--balance-skills``** weights each decision by how rare the teacher's own choice was, softened
inverse-frequency exactly as ``text/classifier`` weights its intents:
``(n / (k * count(skill))) ** power``, zero for a skill that never appears, normalised to mean 1.
Power 0 is off, which is the default; 1.0 is the textbook balanced weighting, which hands a
settlement's whole loss to the twenty ``IGNORE`` decisions in it; 0.5 lifts the tails without
letting them steer. Only the cross-entropy is weighted -- the MSE term is there to pin the
magnitudes the sampling temperature reads, and a per-decision weight on it would distort exactly
the thing it is holding still.
"""

import argparse
import datetime as _dt
import json
import os
import time

import numpy as np
import torch

from ..arbitrator import DEFAULT_TEMPERATURE
from ..schema import CAND_SIZE, CAND_TERMS, N_SKILLS, OBS_SIZE, SKILL_NAMES
from . import collect as collect_mod
from .model import HIDDEN, Scorer
from .scorer import LearnedScorer, export

torch.set_num_threads(1)

#: Two candidates this close together is a decision the noise term could have flipped anyway.
HARD_GAP = 0.5

#: How much to soften the inverse-frequency skill weights by. Same knob, same default and the
#: same reasoning as ``text/classifier``'s ``--intent-weight-power``.
BALANCE_POWER = 0.5


# ---------------------------------------------------------------------------
# Batching ragged decisions
# ---------------------------------------------------------------------------


def target_skills(data, index, chunk=32768):
    """The skill of the candidate the teacher scored highest, one per decision.

    This is the class a decision belongs to: what the student is being asked to choose. Done in
    chunks because the padded ``(D, widest)`` score block is the only large temporary here.
    """
    idx = np.asarray(index, dtype=np.int64)
    out = np.zeros(len(idx), dtype=np.int64)
    for at in range(0, len(idx), chunk):
        part = idx[at:at + chunk]
        starts = data.offsets[part]
        counts = data.offsets[part + 1] - starts
        k = int(counts.max()) if len(counts) else 0
        ar = np.arange(k, dtype=np.int64)
        mask = ar[None, :] < counts[:, None]
        flat = starts[:, None] + np.where(mask, ar[None, :], 0)
        teacher = np.where(mask, data.teacher[flat], -np.inf)
        out[at:at + len(part)] = data.cand_skill[starts + teacher.argmax(axis=1)]
    return out


def skill_weights(skills, power=BALANCE_POWER):
    """Softened inverse-frequency weights per skill, normalised to mean 1 over the ones present.

    ``text/classifier/data.py``'s ``intent_class_weights``, over the 22 skills instead of the 13
    intents. A skill nobody ever chooses gets 0 rather than an enormous number.
    """
    counts = np.bincount(np.asarray(skills, dtype=np.int64), minlength=N_SKILLS)
    n, k = int(counts.sum()), int((counts > 0).sum())
    w = np.zeros(N_SKILLS, dtype=np.float32)
    if not n or not k:
        return w
    present = counts > 0
    w[present] = (n / (k * counts[present])) ** power
    w /= w[present].mean()
    return w


class Batcher:
    """Turns decision indices into padded ``(obs, cand, teacher, mask, target)`` tensors.

    Decisions have between about 8 and 30 candidates. Padding every batch to the widest decision
    in the whole set would throw away a third of the compute, so batches are drawn from shuffled
    chunks sorted by candidate count: the shuffle keeps the batches random, the sort inside the
    chunk keeps them nearly rectangular.
    """

    def __init__(self, data, index, target="argmax", weights=None):
        self.d = data
        self.index = np.asarray(index, dtype=np.int64)
        self.counts = (data.offsets[self.index + 1] - data.offsets[self.index]).astype(np.int64)
        self.starts = data.offsets[self.index].astype(np.int64)
        self.target = target
        #: One weight per decision of this split, or ``None`` for the plain unweighted mean.
        self.weights = None if weights is None else np.asarray(weights, dtype=np.float32)

    def weight_of(self, pos):
        return None if self.weights is None else torch.from_numpy(self.weights[pos])

    def __len__(self):
        return len(self.index)

    def order(self, rng, batch, chunk=8192):
        pos = rng.permutation(len(self.index))
        batches = []
        for at in range(0, len(pos), chunk):
            block = pos[at:at + chunk]
            block = block[np.argsort(self.counts[block], kind="stable")]
            for b in range(0, len(block), batch):
                batches.append(block[b:b + batch])
        rng.shuffle(batches)
        return batches

    def build(self, pos):
        """``pos`` indexes into this split. Returns the tensors for one batch."""
        d = self.d
        counts = self.counts[pos]
        starts = self.starts[pos]
        k = int(counts.max())
        ar = np.arange(k, dtype=np.int64)
        mask = ar[None, :] < counts[:, None]
        flat = (starts[:, None] + np.where(mask, ar[None, :], 0)).ravel()

        cand = np.zeros((len(flat), CAND_SIZE), dtype=np.float32)
        cand[np.arange(len(flat)), d.cand_skill[flat].astype(np.int64)] = 1.0
        cand[:, CAND_TERMS:] = d.cand_terms[flat]
        cand = cand.reshape(len(pos), k, CAND_SIZE)

        teacher = d.teacher[flat].reshape(len(pos), k).astype(np.float32)
        teacher = np.where(mask, teacher, 0.0)
        obs = d.obs[self.index[pos]].astype(np.float32)
        if self.target == "chosen":
            target = d.chosen[self.index[pos]].astype(np.int64)
        else:
            target = np.where(mask, teacher, -np.inf).argmax(axis=1)
        return (torch.from_numpy(obs), torch.from_numpy(cand), torch.from_numpy(teacher),
                torch.from_numpy(mask), torch.from_numpy(target))


# ---------------------------------------------------------------------------
# Training
# ---------------------------------------------------------------------------


def listwise_loss(pred, teacher, mask, target, temperature, mse_weight, weight=None):
    """``weight`` is one number per decision; ``None`` is the plain mean. The MSE is never
    weighted -- see the module docstring."""
    logits = torch.where(mask, pred / temperature, torch.full_like(pred, -1e9))
    if weight is None:
        ce = torch.nn.functional.cross_entropy(logits, target)
    else:
        each = torch.nn.functional.cross_entropy(logits, target, reduction="none")
        ce = (each * weight).sum() / weight.sum().clamp_min(1e-6)
    err = (pred - teacher) * mask
    mse = (err * err).sum() / mask.sum()
    return ce + mse_weight * mse, ce.detach(), mse.detach()


def train(data, train_index, epochs=30, batch=512, lr=3e-3, mse_weight=0.2,
          temperature=DEFAULT_TEMPERATURE, target="argmax", seed=0, max_seconds=240.0,
          balance_skills=None, verbose=True):
    torch.manual_seed(seed)
    model = Scorer()
    opt = torch.optim.Adam(model.parameters(), lr=lr)
    weights = None
    if balance_skills:
        classes = target_skills(data, train_index)
        per_skill = skill_weights(classes, power=balance_skills)
        weights = per_skill[classes]
        if verbose:
            rare = np.argsort(-per_skill)[:5]
            print("  balanced at power %.2f: heaviest %s"
                  % (balance_skills, ", ".join("%s %.1f" % (SKILL_NAMES[i], per_skill[i])
                                               for i in rare)))
    batcher = Batcher(data, train_index, target=target, weights=weights)
    rng = np.random.default_rng(seed)
    started = time.time()
    history = []
    for epoch in range(epochs):
        batches = batcher.order(rng, batch)
        # Full rate until the last three epochs, then halve each time. Two lines instead of a
        # scheduler object, because this is the only thing the schedule ever has to do.
        for group in opt.param_groups:
            group["lr"] = lr * (0.5 ** max(0, epoch - (epochs - 3)))
        tot = ce_tot = mse_tot = 0.0
        seen = 0
        for pos in batches:
            obs, cand, teach, mask, tgt = batcher.build(pos)
            pred = model(obs, cand)
            loss, ce, mse = listwise_loss(pred, teach, mask, tgt, temperature, mse_weight,
                                          weight=batcher.weight_of(pos))
            opt.zero_grad(set_to_none=True)
            loss.backward()
            opt.step()
            n = len(pos)
            tot += float(loss) * n
            ce_tot += float(ce) * n
            mse_tot += float(mse) * n
            seen += n
        row = {"epoch": epoch + 1, "loss": round(tot / seen, 5), "ce": round(ce_tot / seen, 5),
               "mse": round(mse_tot / seen, 5), "seconds": round(time.time() - started, 1)}
        history.append(row)
        if verbose:
            print("  epoch %d  loss %.4f  ce %.4f  mse %.4f  (%.1f s)"
                  % (row["epoch"], row["loss"], row["ce"], row["mse"], row["seconds"]))
        if time.time() - started > max_seconds:
            if verbose:
                print("  stopping early: %.0f s budget spent" % max_seconds)
            break
    return model, history, round(time.time() - started, 2)


# ---------------------------------------------------------------------------
# Measuring
# ---------------------------------------------------------------------------


@torch.no_grad()
def agreement(model, data, index, batch=1024):
    """Top-1 agreement with the teacher, overall, per scenario, and on the hard decisions.

    A *hard* decision is one where the teacher's best two candidates were within
    :data:`HARD_GAP` of each other: the ones where a wrong pick is a different action rather than
    a tie broken differently.

    ``per_skill`` is recall: of the decisions where the teacher chose this skill, the share where
    the student chose it too. It is the number the overall figure hides -- a skill the teacher
    picks forty times in forty thousand decisions can be missed completely at 97.9% agreement.
    """
    batcher = Batcher(data, index)
    agree = np.zeros(len(index), dtype=bool)
    gap = np.zeros(len(index), dtype=np.float32)
    mae = np.zeros(len(index), dtype=np.float32)
    want = np.zeros(len(index), dtype=np.int64)
    got = np.zeros(len(index), dtype=np.int64)
    n_cands = batcher.counts.astype(np.float32)
    for at in range(0, len(index), batch):
        pos = np.arange(at, min(at + batch, len(index)))
        obs, cand, teach, mask, tgt = batcher.build(pos)
        pred = model(obs, cand)
        pred = torch.where(mask, pred, torch.full_like(pred, -1e9))
        agree[pos] = (pred.argmax(dim=1) == tgt).numpy()
        want[pos] = data.cand_skill[batcher.starts[pos] + tgt.numpy()]
        got[pos] = data.cand_skill[batcher.starts[pos] + pred.argmax(dim=1).numpy()]
        masked = np.where(mask.numpy(), teach.numpy(), -np.inf)
        top2 = np.sort(masked, axis=1)[:, -2:]
        gap[pos] = np.where(np.isfinite(top2[:, 0]), top2[:, 1] - top2[:, 0], np.inf)
        err = (pred.numpy() - teach.numpy()) * mask.numpy()
        mae[pos] = np.abs(err).sum(axis=1) / mask.numpy().sum(axis=1)

    idx = np.asarray(index, dtype=np.int64)
    hard = gap < HARD_GAP
    out = {
        "decisions": int(len(idx)),
        "top1": _pct(agree),
        "chance": round(float(np.mean(1.0 / n_cands)), 4),
        "mean_candidates": round(float(n_cands.mean()), 2),
        "score_mae": round(float(mae.mean()), 4),
        "hard": {"decisions": int(hard.sum()), "fraction": _pct(hard),
                 "top1": _pct(agree[hard]) if hard.any() else None,
                 "chance": (round(float(np.mean(1.0 / n_cands[hard])), 4) if hard.any() else None)},
        "per_skill": {},
        "per_scenario": {},
    }
    for si, name in enumerate(SKILL_NAMES):
        sel = want == si
        if not sel.any():
            continue
        out["per_skill"][name] = {"decisions": int(sel.sum()),
                                  "recall": _pct(got[sel] == si),
                                  "chosen": int((got == si).sum())}
    for si, name in enumerate(data.scenarios):
        sel = data.scenario[idx] == si
        if not sel.any():
            continue
        h = sel & hard
        out["per_scenario"][name] = {
            "decisions": int(sel.sum()),
            "top1": _pct(agree[sel]),
            "chance": round(float(np.mean(1.0 / n_cands[sel])), 4),
            "hard_decisions": int(h.sum()),
            "hard_top1": _pct(agree[h]) if h.any() else None,
        }
    return out


def _pct(flags):
    flags = np.asarray(flags)
    if flags.size == 0:
        return None
    return round(float(flags.mean()), 4)


@torch.no_grad()
def parity(model, scorer, data, index, samples=256):
    """Largest absolute difference between the torch model and the exported numpy one."""
    worst = 0.0
    for d in np.asarray(index, dtype=np.int64)[:samples]:
        obs = data.observation(d)
        feats = data.features(d)
        x = torch.from_numpy(np.concatenate(
            [np.repeat(obs[None, :], len(feats), axis=0), feats], axis=1))
        a = model.score_flat(x).numpy()
        b = scorer.score_all(obs, feats)
        worst = max(worst, float(np.abs(a - b).max()))
    return worst


# ---------------------------------------------------------------------------
# The whole thing
# ---------------------------------------------------------------------------


def split(data, holdout):
    holdout = set(int(s) for s in holdout)
    seeds = data.seed.astype(np.int64)
    held = np.isin(seeds, sorted(holdout))
    return np.nonzero(~held)[0], np.nonzero(held)[0]


def default_holdout(data):
    seeds = sorted(set(int(s) for s in np.unique(data.seed)))
    return seeds[-2:] if len(seeds) > 2 else seeds[-1:]


def run(data_path, out_prefix, holdout=None, epochs=30, batch=512, lr=3e-3, mse_weight=0.2,
        target="argmax", seed=0, max_seconds=240.0, balance_skills=None, verbose=True):
    data = collect_mod.load(data_path)
    holdout = list(holdout) if holdout else default_holdout(data)
    train_index, test_index = split(data, holdout)
    if verbose:
        print("%s: %d decisions, %d candidates, scenarios %s"
              % (data_path, len(data), data.n_candidates, ", ".join(data.scenarios)))
        print("  train %d decisions, held out seeds %s -> %d decisions"
              % (len(train_index), holdout, len(test_index)))
    model, history, seconds = train(
        data, train_index, epochs=epochs, batch=batch, lr=lr, mse_weight=mse_weight,
        target=target, seed=seed, max_seconds=max_seconds, balance_skills=balance_skills,
        verbose=verbose)
    model.eval()

    metrics = {
        "held_out": agreement(model, data, test_index),
        "train": agreement(model, data, train_index[:min(len(train_index), 20000)]),
    }
    arrays = model.arrays()
    meta = {
        "created": _dt.datetime.now().replace(microsecond=0).isoformat(),
        "created_by": "dwarfsim.learn.imitate",
        "teacher": "dwarfsim.arbitrator.WEIGHTS, the hand-written table",
        "data": {"file": os.path.basename(data_path), **{
            k: data.meta[k] for k in ("decisions", "candidates", "seeds", "ticks", "agents",
                                      "stride", "scenarios") if k in data.meta}},
        "dims": {"obs": OBS_SIZE, "cand": CAND_SIZE, "input": OBS_SIZE + CAND_SIZE,
                 "hidden": list(HIDDEN), "parameters": model.n_parameters()},
        "training": {"epochs": len(history), "batch": batch, "lr": lr,
                     "mse_weight": mse_weight, "temperature": DEFAULT_TEMPERATURE,
                     "target": target, "balance_skills": balance_skills,
                     "holdout_seeds": [int(s) for s in holdout],
                     "seconds": seconds, "history": history,
                     "loss": "CE(pred / T, teacher pick) + mse_weight * mean((pred - teacher)^2)"},
        "metrics": metrics,
    }
    written = export(arrays, out_prefix, meta)
    scorer = LearnedScorer.load(out_prefix)
    worst = parity(model, scorer, data, test_index)
    written["metrics"]["torch_numpy_max_abs_diff"] = float("%.3g" % worst)
    with open(out_prefix + ".json", "w", encoding="utf-8", newline="\n") as fh:
        json.dump(written, fh, indent=2)
        fh.write("\n")
    if verbose:
        _report(written, out_prefix)
    return written


def _report(meta, out_prefix):
    m = meta["metrics"]["held_out"]
    print("held out: %d decisions, top-1 %.1f%% (chance %.1f%%), score MAE %.3f"
          % (m["decisions"], 100 * m["top1"], 100 * m["chance"], m["score_mae"]))
    h = m["hard"]
    if h["top1"] is not None:
        print("  hard (teacher top two within %.1f): %d decisions, top-1 %.1f%%"
              % (HARD_GAP, h["decisions"], 100 * h["top1"]))
    for name, row in m["per_scenario"].items():
        print("  %-8s %6d decisions, top-1 %.1f%%%s"
              % (name, row["decisions"], 100 * row["top1"],
                 "" if row["hard_top1"] is None
                 else ", hard %.1f%% of %d" % (100 * row["hard_top1"], row["hard_decisions"])))
    print("per-skill recall (of the decisions where the teacher chose it)")
    for name, row in sorted(m["per_skill"].items(), key=lambda kv: kv[1]["recall"]):
        print("  %-15s %5d decisions  recall %.2f  (chosen %d times)"
              % (name, row["decisions"], row["recall"], row["chosen"]))
    print("torch vs numpy: max abs diff %g" % meta["metrics"]["torch_numpy_max_abs_diff"])
    print("%s.npz (%d bytes) + %s.json" % (out_prefix, meta["weights"]["bytes"], out_prefix))


def main(argv=None):
    p = argparse.ArgumentParser(prog="dwarfsim.learn.imitate",
                                description=__doc__.splitlines()[0])
    p.add_argument("--data", default="runs/learn/teacher.npz")
    p.add_argument("--out", default="runs/learn/imitator",
                   help="prefix: writes <out>.npz and <out>.json")
    p.add_argument("--holdout", default=None,
                   help="seeds to keep out entirely (default: the last two in the data)")
    p.add_argument("--epochs", type=int, default=30,
                   help="about 50 s on 145k decisions, single-threaded")
    p.add_argument("--batch", type=int, default=512)
    p.add_argument("--lr", type=float, default=3e-3)
    p.add_argument("--mse-weight", type=float, default=0.2)
    p.add_argument("--target", default="argmax", choices=("argmax", "chosen"),
                   help="argmax: the teacher's best candidate; chosen: the one its softmax drew")
    p.add_argument("--seed", type=int, default=0)
    p.add_argument("--max-seconds", type=float, default=240.0)
    p.add_argument("--balance-skills", type=float, default=None, metavar="POWER",
                   help="weight each decision by how rare the teacher's own choice was, softened "
                        "by POWER (0 off, %.1f the usual, 1 fully balanced)" % BALANCE_POWER)
    args = p.parse_args(argv)
    holdout = collect_mod.parse_seeds(args.holdout) if args.holdout else None
    run(args.data, args.out, holdout=holdout, epochs=args.epochs, batch=args.batch, lr=args.lr,
        mse_weight=args.mse_weight, target=args.target, seed=args.seed,
        max_seconds=args.max_seconds, balance_skills=args.balance_skills)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
