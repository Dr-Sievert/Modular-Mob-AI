"""The reward stage: PPO on the imitator, in the sim, against :mod:`dwarfsim.learn.reward`.

    python -m dwarfsim.learn.ppo --init runs/learn/imitator --out runs/learn/ppo \
        --iterations 45 --ticks 400

**The policy is the imitator.** Same architecture, same file format, started from the imitator's
weights: ``observation ++ candidate_features -> 142 -> 64 -> 64 -> 1`` (133 on the v1 layout; the
width comes off :mod:`dwarfsim.schema` and is never typed into the model). One decision's candidates
are one categorical distribution -- ``softmax(scores / 0.25)``, the sim's own sampling temperature
-- so the "action space" is ragged, different at every step, and the policy head is a score per
candidate rather than a fixed-width logit vector. That is the whole reason this can be trained at
all with 13k parameters: the candidate features carry what the action *is*.

**The value net is separate and tiny**: ``observation ++ the mean of this decision's candidate
features -> 142 -> 64 -> 1``. It reads the same 142 floats the policy does, but the candidate half
is averaged over the decision instead of taken one candidate at a time, which makes it a function
of the state (what is on offer right now) and not of the choice.

**One step is one decision; one trajectory is one dwarf.** Six dwarves in one settlement are six
trajectories that interact -- a genuinely multi-agent setup treated as independent single-agent
problems, which is the standard simplification and is stated as one in the design doc. GAE with
gamma 0.99 and lambda 0.95, bootstrapped off the value of the state each living dwarf is left in
when the episode is cut, and terminated with a hard zero when it dies.

**Two guards against the thing that always happens.** A policy whose logits are scores divided by
0.25 can collapse onto one action in a handful of updates, so: an entropy bonus, and a KL penalty
back to the frozen imitator (``--kl-coef``), which is the behavioural anchor the imitation stage
bought us. The update also stops early when the new policy has moved more than ``--target-kl``
from the one that collected the rollout. All three are logged.

**Determinism.** Everything random comes from ``--seed``: torch's init, the minibatch shuffling,
and the settlement seeds, which are drawn from a fixed pool in a fixed rotation. Two runs with the
same arguments produce the same ``progress.csv``.

Written under ``--out`` (default ``runs/learn/ppo``):

    best.npz / best.json     the best checkpoint by held-out validation reward
    last.npz / last.json     the final one
    progress.csv             per iteration: reward and its terms, entropy, KL, losses
    episodes.csv             per episode: the reward-term totals and behaviour counters

Both checkpoints are written in the imitator's exported format, so
``--scorer runs/learn/ppo/best`` works in the sim and in the viewer with nothing else changed.

``--reward`` picks which table is being maximised -- ``v1``, ``v2`` or the trait-conditioned
``trait`` (:data:`dwarfsim.learn.reward.MODES`). Nothing else about the loop changes: the
per-dwarf part of the trait table is folded into the term *values*, so the reward is still
``terms @ weights`` against one global weight vector.
"""

import argparse
import datetime as _dt
import json
import os
import time

import numpy as np
import torch
from torch import nn

from .. import arbitrator
from ..arbitrator import DEFAULT_TEMPERATURE
from ..schema import CAND_SIZE, OBS_SIZE
from ..world import World
from . import scenarios as scenario_mod
from .collect import parse_seeds
from .model import Scorer
from .reward import (MODES, REWARD_WEIGHTS, RewardModel, TERM_NAMES, EpisodeCsv,
                     weights_for)
from .scorer import LearnedScorer, export, resolve

torch.set_num_threads(1)

#: Defaults. The whole point of the numbers is that a default run finishes in about eight minutes
#: on one CPU thread.
GAMMA = 0.99
LAMBDA = 0.95
CLIP = 0.2
EPOCHS = 4
MINIBATCH = 512
POLICY_LR = 3.0e-4
VALUE_LR = 1.0e-3
ENTROPY_COEF = 0.01
VALUE_COEF = 0.5
KL_COEF = 0.05
TARGET_KL = 0.05

#: Settlement seeds the rollouts rotate through, and the one held back to pick ``best`` by. The
#: evaluation's own seeds (20-23) appear in neither, so nothing selects on them.
TRAIN_SEEDS = (1, 2, 3, 4, 5, 6, 7, 8)
VALIDATION_SEED = 50


# ---------------------------------------------------------------------------
# The value net
# ---------------------------------------------------------------------------


class Value(nn.Module):
    """``observation ++ mean(candidate features) -> 64 -> 1``."""

    def __init__(self, size=OBS_SIZE + CAND_SIZE, hidden=64):
        super().__init__()
        self.fc1 = nn.Linear(size, hidden)
        self.out = nn.Linear(hidden, 1)

    def forward(self, x):
        return self.out(torch.relu(self.fc1(x))).squeeze(-1)


# ---------------------------------------------------------------------------
# Rolling out
# ---------------------------------------------------------------------------


class Rollout:
    """Every decision of a batch of episodes, flat, with its candidates kept ragged."""

    def __init__(self):
        self.obs = []
        self.cand = []
        self.counts = []
        self.chosen = []
        self.terms = []
        self.value_in = []
        self.traj = []          # (indices, died, final value input or None)
        self.episodes = []      # one report row per episode

    def add_episode(self, ep):
        base = len(self.chosen)
        self.obs.extend(ep["obs"])
        self.cand.extend(ep["cand"])
        self.counts.extend(ep["counts"])
        self.chosen.extend(ep["chosen"])
        self.terms.extend(ep["terms"])
        self.value_in.extend(ep["value_in"])
        for idx, died, final in ep["traj"]:
            self.traj.append((np.asarray(idx, dtype=np.int64) + base, died, final))
        self.episodes.append(ep["report"])

    def finish(self, weights):
        self.obs = np.asarray(self.obs, dtype=np.float32).reshape(-1, OBS_SIZE)
        self.cand = np.asarray(self.cand, dtype=np.float32).reshape(-1, CAND_SIZE)
        self.counts = np.asarray(self.counts, dtype=np.int64)
        self.starts = np.zeros(len(self.counts) + 1, dtype=np.int64)
        np.cumsum(self.counts, out=self.starts[1:])
        self.chosen = np.asarray(self.chosen, dtype=np.int64)
        self.terms = np.asarray(self.terms, dtype=np.float32).reshape(-1, len(TERM_NAMES))
        self.value_in = np.asarray(self.value_in, dtype=np.float32).reshape(
            -1, OBS_SIZE + CAND_SIZE)
        w = np.array([weights.get(t, 0.0) for t in TERM_NAMES], dtype=np.float32)
        self.reward = self.terms @ w
        return self

    def __len__(self):
        return len(self.chosen)


def player_driver(world):
    """The scripted player, for the ``player`` scenario: orders, help, a gift, a promise kept."""
    from .. import PLAYER_ORDER, PLAYER_SCRIPT
    from ..obligations import make_ask
    from ..schema import PLAYER_ID

    script = dict(PLAYER_SCRIPT)
    state = {"who": world.agents[0], "promised": []}

    def drive():
        living = world.living()
        if not living:
            return
        if not state["who"].alive:
            state["who"] = living[0]
        who = state["who"]
        what = script.get(world.tick)
        if what == "order":
            world.say(PLAYER_ID, who, dict(
                PLAYER_ORDER, ask=make_ask("BRING", item="ore", quantity=1, place="MINE")))
        elif what == "help":
            world.player_help(who, magnitude=1.4)
        elif what == "give":
            world.player_give(who, 5)
        elif what == "promise":
            state["promised"].append(world.player_promise(
                who, make_ask("GIVE", item="gold", quantity=3)))
        elif what == "keep" and state["promised"]:
            world.player_give(who, 3)
            world.fulfil_obligation(state["promised"][-1])

    return drive


def play(scorer, scenario, seed, ticks, agents=6, weights=None, temperature=None,
         record=True, tally=None, reward_mode="v2"):
    """Run one settlement under ``scorer`` and return everything a PPO update needs.

    ``scorer`` is ``None`` for the hand-written table or anything with ``score_all``. With
    ``record=False`` nothing is kept but the reward totals and the counters, which is what the
    evaluation and the validation pass want.
    """
    world = World(n_agents=agents, seed=seed, scenario=scenario, scorer=scorer,
                  temperature=temperature)
    rm = RewardModel(world, weights, mode=reward_mode)
    drive = player_driver(world) if scenario == "player" else None

    obs_rows, cand_rows, counts, chosen, term_rows, value_in = [], [], [], [], [], []
    steps_of = {}          # agent id -> the step indices of its trajectory
    last_of = {}           # agent id -> its most recent step index
    died = set()
    ticked = 0

    for _ in range(ticks):
        world.collector = [] if record else None
        rm.begin()
        step = world.step()
        # The player acts inside the tick it belongs to, before the reward is read off, so the
        # trust its gift moves is credited to that tick rather than quietly to the next one.
        if drive is not None:
            drive()
        rewards = rm.end(step)
        ticked += 1
        if tally is not None:
            tally(world, step)
        here = {}
        if record:
            for (obs, feats, _teacher, pick), rec in zip(world.collector, step["decisions"]):
                aid = rec["agent"]
                i = len(chosen)
                obs_rows.append(obs)
                cand_rows.extend(feats)
                counts.append(len(feats))
                chosen.append(pick)
                term_rows.append([0.0] * len(TERM_NAMES))
                mean_cand = np.asarray(feats, dtype=np.float32).mean(axis=0)
                value_in.append(np.concatenate(
                    [np.asarray(obs, dtype=np.float32), mean_cand]))
                steps_of.setdefault(aid, []).append(i)
                last_of[aid] = i
                here[aid] = i
            for aid, terms in rewards.items():
                at = here.get(aid, last_of.get(aid))
                if at is None:
                    continue           # died before it ever decided; nothing to credit it to
                row = term_rows[at]
                for j, name in enumerate(TERM_NAMES):
                    row[j] += terms[name]
                if terms["alive"] < 0.0:
                    died.add(aid)
        if not world.living():
            break

    traj = []
    if record:
        for aid, idx in steps_of.items():
            final = None
            a = world.agent(aid)
            if aid not in died and a is not None and a.alive:
                final = _value_input(a, world)
            traj.append((idx, aid in died, final))

    report = rm.report(scenario=scenario, seed=int(seed), ticks=ticked)
    return {"obs": obs_rows, "cand": cand_rows, "counts": counts, "chosen": chosen,
            "terms": term_rows, "value_in": value_in, "traj": traj, "report": report,
            "world": world, "reward_model": rm}


def _value_input(agent, world):
    """The 133 floats the value net reads for the state a living dwarf is left in."""
    cands = arbitrator.gather(agent, world)
    obs = np.asarray(arbitrator.observation_vector(agent, world), dtype=np.float32)
    if not cands:
        return np.concatenate([obs, np.zeros(CAND_SIZE, dtype=np.float32)])
    ctx = arbitrator.TermContext(agent, world)
    feats = np.asarray([arbitrator.candidate_features(ctx, c) for c in cands], dtype=np.float32)
    return np.concatenate([obs, feats.mean(axis=0)])


def collect_rollout(policy, scenarios, seeds, ticks, agents=6, weights=None, temperature=None,
                    episode_csv=None, iteration=0, policy_name="ppo", reward_mode="v2"):
    """One iteration's worth of episodes, under the current policy."""
    scorer = LearnedScorer(policy.arrays())
    roll = Rollout()
    for scenario in scenarios:
        for seed in seeds:
            ep = play(scorer, scenario, seed, ticks, agents=agents, weights=weights,
                      temperature=temperature, reward_mode=reward_mode)
            roll.add_episode(ep)
            if episode_csv is not None:
                episode_csv.write(dict(ep["report"], policy=policy_name, iteration=iteration))
    return roll.finish(weights or REWARD_WEIGHTS)


# ---------------------------------------------------------------------------
# Batching, values, advantages
# ---------------------------------------------------------------------------


def _batches(n, counts, rng, size, chunk=4096):
    """Shuffled minibatches, nearly rectangular inside each: the imitator's trick, again."""
    pos = rng.permutation(n)
    out = []
    for at in range(0, n, chunk):
        block = pos[at:at + chunk]
        block = block[np.argsort(counts[block], kind="stable")]
        for b in range(0, len(block), size):
            out.append(block[b:b + size])
    rng.shuffle(out)
    return out


def _build(roll, pos):
    """Padded tensors for one minibatch of decisions."""
    counts = roll.counts[pos]
    starts = roll.starts[pos]
    k = int(counts.max())
    ar = np.arange(k, dtype=np.int64)
    mask = ar[None, :] < counts[:, None]
    flat = (starts[:, None] + np.where(mask, ar[None, :], 0)).ravel()
    cand = roll.cand[flat].reshape(len(pos), k, CAND_SIZE)
    return (torch.from_numpy(roll.obs[pos]), torch.from_numpy(cand),
            torch.from_numpy(mask), flat, k)


@torch.no_grad()
def flat_scores(model, roll, batch=1024):
    """Every candidate's score under ``model``, in the rollout's flat candidate order."""
    out = np.zeros(len(roll.cand), dtype=np.float32)
    for at in range(0, len(roll), batch):
        pos = np.arange(at, min(at + batch, len(roll)))
        obs, cand, mask, flat, k = _build(roll, pos)
        pred = model(obs, cand).numpy()
        out[flat.reshape(len(pos), k)[mask.numpy()]] = pred[mask.numpy()]
    return out


def log_probs(scores, roll, temperature):
    """Per-decision log-softmax over the ragged candidate scores, and the entropy of each."""
    logp = np.zeros(len(scores), dtype=np.float32)
    ent = np.zeros(len(roll), dtype=np.float32)
    for d in range(len(roll)):
        lo, hi = roll.starts[d], roll.starts[d + 1]
        z = scores[lo:hi] / temperature
        z = z - z.max()
        e = np.exp(z)
        s = e.sum()
        row = z - np.log(s)
        logp[lo:hi] = row
        p = e / s
        ent[d] = float(-(p * row).sum())
    return logp, ent


@torch.no_grad()
def values_of(value, rows, batch=4096):
    out = np.zeros(len(rows), dtype=np.float32)
    for at in range(0, len(rows), batch):
        chunk = torch.from_numpy(rows[at:at + batch])
        out[at:at + len(chunk)] = value(chunk).numpy()
    return out


def advantages(roll, values, value_net, gamma=GAMMA, lam=LAMBDA):
    """GAE per dwarf, bootstrapped where the episode was merely cut short."""
    adv = np.zeros(len(roll), dtype=np.float32)
    for idx, died, final in roll.traj:
        if len(idx) == 0:
            continue
        tail = 0.0
        if not died and final is not None:
            tail = float(values_of(value_net, final.reshape(1, -1))[0])
        running = 0.0
        for j in range(len(idx) - 1, -1, -1):
            i = idx[j]
            if j == len(idx) - 1:
                nonterminal = 0.0 if died else 1.0
                next_v = tail
            else:
                nonterminal = 1.0
                next_v = values[idx[j + 1]]
            delta = roll.reward[i] + gamma * next_v * nonterminal - values[i]
            running = delta + gamma * lam * nonterminal * running
            adv[i] = running
    return adv


# ---------------------------------------------------------------------------
# The update
# ---------------------------------------------------------------------------


def update(policy, value, opt, roll, old_logp, imitator_logp, adv, ret, rng,
           temperature=DEFAULT_TEMPERATURE, epochs=EPOCHS, minibatch=MINIBATCH,
           clip=CLIP, entropy_coef=ENTROPY_COEF, value_coef=VALUE_COEF, kl_coef=KL_COEF,
           target_kl=TARGET_KL):
    """Clipped PPO, entropy bonus, KL back to the imitator, early stop on policy movement."""
    n = len(roll)
    norm = (adv - adv.mean()) / (adv.std() + 1e-6)
    stats = {"policy_loss": 0.0, "value_loss": 0.0, "entropy": 0.0, "kl_imitator": 0.0,
             "kl_step": 0.0, "clipped": 0.0, "batches": 0, "epochs": 0}
    stopped = False
    for epoch in range(epochs):
        if stopped:
            break
        stats["epochs"] += 1
        for pos in _batches(n, roll.counts, rng, minibatch):
            obs, cand, mask, flat, k = _build(roll, pos)
            neg = torch.full((len(pos), k), -1e9)
            pred = policy(obs, cand)
            logits = torch.where(mask, pred / temperature, neg)
            logp = torch.log_softmax(logits, dim=1)
            p = logp.exp()

            take = torch.from_numpy(roll.chosen[pos]).unsqueeze(1)
            lp = logp.gather(1, take).squeeze(1)
            old = torch.from_numpy(old_logp[roll.starts[pos] + roll.chosen[pos]])
            ratio = torch.exp(lp - old)
            a = torch.from_numpy(norm[pos])
            pg = -torch.min(ratio * a, torch.clamp(ratio, 1 - clip, 1 + clip) * a).mean()

            entropy = -(p * torch.where(mask, logp, torch.zeros_like(logp))).sum(1).mean()
            imit = torch.from_numpy(imitator_logp[flat].reshape(len(pos), k))
            imit = torch.where(mask, imit, neg)
            kl_im = (p * torch.where(mask, logp - imit, torch.zeros_like(logp))).sum(1).mean()

            v = value(torch.from_numpy(roll.value_in[pos]))
            vloss = torch.nn.functional.mse_loss(v, torch.from_numpy(ret[pos]))

            loss = pg + value_coef * vloss - entropy_coef * entropy + kl_coef * kl_im
            opt.zero_grad(set_to_none=True)
            loss.backward()
            torch.nn.utils.clip_grad_norm_(
                list(policy.parameters()) + list(value.parameters()), 0.5)
            opt.step()

            with torch.no_grad():
                step_kl = float((old - lp).mean())
                stats["policy_loss"] += float(pg)
                stats["value_loss"] += float(vloss)
                stats["entropy"] += float(entropy)
                stats["kl_imitator"] += float(kl_im)
                stats["kl_step"] += abs(step_kl)
                stats["clipped"] += float(
                    ((ratio - 1.0).abs() > clip).float().mean())
                stats["batches"] += 1
            if abs(step_kl) > target_kl:
                stopped = True
                break
    b = max(1, stats["batches"])
    for key in ("policy_loss", "value_loss", "entropy", "kl_imitator", "kl_step", "clipped"):
        stats[key] /= b
    stats["early_stop"] = int(stopped)
    return stats


# ---------------------------------------------------------------------------
# The whole thing
# ---------------------------------------------------------------------------


def load_policy(init):
    """A torch :class:`~dwarfsim.learn.model.Scorer` with the exported weights in it."""
    model = Scorer()
    if init:
        npz_path, _ = resolve(init)
        with np.load(npz_path) as fh:
            state = {k: torch.from_numpy(fh[k].copy()) for k in fh.files}
        model.load_state_dict(state)
    return model


def save(model, prefix, meta):
    return export(model.arrays(), prefix, meta)


def mean_reward(roll):
    """Mean total reward per episode, which is what a checkpoint is judged on."""
    if not roll.episodes:
        return 0.0
    return float(np.mean([e["reward"] for e in roll.episodes]))


def validate(policy, scenarios, seed, ticks, agents=6, weights=None, episode_csv=None,
             iteration=0, reward_mode="v2"):
    """The held-back settlement, under the current policy. Nothing selects on the eval seeds."""
    scorer = LearnedScorer(policy.arrays())
    rewards = []
    for scenario in scenarios:
        ep = play(scorer, scenario, seed, ticks, agents=agents, weights=weights, record=False,
                  reward_mode=reward_mode)
        rewards.append(ep["report"]["reward"])
        if episode_csv is not None:
            episode_csv.write(dict(ep["report"], policy="validation", iteration=iteration))
    return float(np.mean(rewards)) if rewards else 0.0


PROGRESS_FIELDS = (["iteration", "seconds", "episodes", "steps", "reward"]
                   + list(TERM_NAMES)
                   + ["entropy", "kl_imitator", "kl_step", "policy_loss", "value_loss",
                      "clipped", "early_stop", "epochs", "validation", "best",
                      "deaths", "hits", "unprovoked_hits", "thefts",
                      "promises_kept", "promises_broken"])


def train(init="runs/learn/imitator", out="runs/learn/ppo", iterations=45, ticks=400,
          scenarios=scenario_mod.TRAIN_SCENARIOS, seeds=TRAIN_SEEDS, seeds_per_iter=1,
          agents=6, seed=0, lr=POLICY_LR, value_lr=VALUE_LR, epochs=EPOCHS,
          minibatch=MINIBATCH, clip=CLIP, entropy_coef=ENTROPY_COEF, kl_coef=KL_COEF,
          target_kl=TARGET_KL, gamma=GAMMA, lam=LAMBDA, eval_every=5,
          validation_seed=VALIDATION_SEED, weights=None, budget=None, verbose=True,
          reward_mode="v2"):
    """Run the whole reward stage. Returns the metadata written next to ``best``."""
    started = time.time()
    torch.manual_seed(seed)
    rng = np.random.default_rng(seed)
    weights = dict(weights or weights_for(reward_mode))

    policy = load_policy(init)
    imitator = load_policy(init)
    imitator.eval()
    for p in imitator.parameters():
        p.requires_grad_(False)
    value = Value()
    opt = torch.optim.Adam([
        {"params": policy.parameters(), "lr": lr},
        {"params": value.parameters(), "lr": value_lr},
    ])

    os.makedirs(out, exist_ok=True)
    episodes_csv = EpisodeCsv(os.path.join(out, "episodes.csv"))
    progress_path = os.path.join(out, "progress.csv")
    progress = open(progress_path, "w", encoding="utf-8", newline="")
    progress.write(",".join(PROGRESS_FIELDS) + "\n")

    seeds = list(seeds)
    history = []
    best_score, best_iter = None, -1
    try:
        for it in range(1, iterations + 1):
            pick = [seeds[(it - 1 + j) % len(seeds)] for j in range(seeds_per_iter)]
            roll = collect_rollout(policy, scenarios, pick, ticks, agents=agents,
                                   weights=weights, episode_csv=episodes_csv, iteration=it,
                                   reward_mode=reward_mode)
            scores = flat_scores(policy, roll)
            old_logp, ent = log_probs(scores, roll, DEFAULT_TEMPERATURE)
            imit_scores = flat_scores(imitator, roll)
            imit_logp, _ = log_probs(imit_scores, roll, DEFAULT_TEMPERATURE)
            values = values_of(value, roll.value_in)
            adv = advantages(roll, values, value, gamma=gamma, lam=lam)
            ret = adv + values

            stats = update(policy, value, opt, roll, old_logp, imit_logp, adv, ret, rng,
                           epochs=epochs, minibatch=minibatch, clip=clip,
                           entropy_coef=entropy_coef, kl_coef=kl_coef, target_kl=target_kl)

            row = {"iteration": it, "seconds": round(time.time() - started, 1),
                   "episodes": len(roll.episodes), "steps": len(roll),
                   "reward": round(mean_reward(roll), 3)}
            for name in TERM_NAMES:
                row[name] = round(float(np.mean([e[name] for e in roll.episodes])), 3)
            for name in ("deaths", "hits", "unprovoked_hits", "thefts",
                         "promises_kept", "promises_broken"):
                row[name] = int(sum(e[name] for e in roll.episodes))
            row["entropy"] = round(float(ent.mean()), 4)
            for key in ("kl_imitator", "kl_step", "policy_loss", "value_loss", "clipped"):
                row[key] = round(stats[key], 4)
            row["early_stop"] = stats["early_stop"]
            row["epochs"] = stats["epochs"]
            row["validation"] = ""

            if it % eval_every == 0 or it == iterations:
                val = validate(policy, scenarios, validation_seed, ticks, agents=agents,
                               weights=weights, episode_csv=episodes_csv, iteration=it,
                               reward_mode=reward_mode)
                row["validation"] = round(val, 3)
                if best_score is None or val > best_score:
                    best_score, best_iter = val, it
                    save(policy, os.path.join(out, "best"),
                         _meta(init, it, val, row, locals_summary(
                             iterations, ticks, scenarios, seeds, seeds_per_iter, agents, seed,
                             lr, value_lr, epochs, minibatch, clip, entropy_coef, kl_coef,
                             target_kl, gamma, lam, weights, reward_mode), history))
            row["best"] = "" if best_iter < 0 else best_iter
            history.append(row)
            progress.write(",".join(str(row.get(f, "")) for f in PROGRESS_FIELDS) + "\n")
            progress.flush()
            if verbose:
                print("  it %3d  reward %8.2f  entropy %.3f  KL(imit) %.3f  ent-stop %d  "
                      "unprovoked %d  deaths %d  %s(%.0f s)"
                      % (it, row["reward"], row["entropy"], row["kl_imitator"],
                         row["early_stop"], row["unprovoked_hits"], row["deaths"],
                         "" if row["validation"] == "" else "val %.2f  " % row["validation"],
                         time.time() - started))
            if budget is not None and time.time() - started > budget:
                if verbose:
                    print("  stopping: %.0f s budget spent" % budget)
                break
    finally:
        progress.close()
        episodes_csv.close()

    settings = locals_summary(iterations, ticks, scenarios, seeds, seeds_per_iter, agents, seed,
                              lr, value_lr, epochs, minibatch, clip, entropy_coef, kl_coef,
                              target_kl, gamma, lam, weights, reward_mode)
    last_meta = _meta(init, len(history), best_score, history[-1] if history else {}, settings,
                      history)
    save(policy, os.path.join(out, "last"), last_meta)
    if best_iter < 0:            # never validated: the last one is all there is
        save(policy, os.path.join(out, "best"), last_meta)
        best_iter, best_score = len(history), None
    meta = dict(last_meta, best_iteration=best_iter, best_validation=best_score,
                seconds=round(time.time() - started, 1))
    with open(os.path.join(out, "training.json"), "w", encoding="utf-8", newline="\n") as fh:
        json.dump(meta, fh, indent=2)
        fh.write("\n")
    if verbose:
        print("%s: %d iterations in %.0f s; best is iteration %s (validation %s)"
              % (out, len(history), time.time() - started, best_iter, best_score))
    return meta


def locals_summary(iterations, ticks, scenarios, seeds, seeds_per_iter, agents, seed, lr,
                   value_lr, epochs, minibatch, clip, entropy_coef, kl_coef, target_kl,
                   gamma, lam, weights, reward_mode="v2"):
    return {"iterations": iterations, "ticks": ticks, "scenarios": list(scenarios),
            "seeds": [int(s) for s in seeds], "seeds_per_iteration": seeds_per_iter,
            "agents": agents, "seed": seed, "policy_lr": lr, "value_lr": value_lr,
            "epochs": epochs, "minibatch": minibatch, "clip": clip,
            "entropy_coef": entropy_coef, "kl_coef": kl_coef, "target_kl": target_kl,
            "gamma": gamma, "lambda": lam, "temperature": DEFAULT_TEMPERATURE,
            "reward_weights": dict(weights), "reward_mode": reward_mode}


def _meta(init, iteration, validation, row, settings, history):
    return {
        "created": _dt.datetime.now().replace(microsecond=0).isoformat(),
        "created_by": "dwarfsim.learn.ppo",
        "teacher": "reward, not imitation: see dwarfsim.learn.reward.REWARD_WEIGHTS",
        "initialised_from": init,
        "iteration": iteration,
        "validation_reward": validation,
        "dims": {"obs": OBS_SIZE, "cand": CAND_SIZE, "input": OBS_SIZE + CAND_SIZE,
                 "hidden": [64, 64], "value_net": [OBS_SIZE + CAND_SIZE, 64, 1]},
        "training": settings,
        "metrics": {"last": dict(row)},
        "history": history[-40:],
    }


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def main(argv=None):
    p = argparse.ArgumentParser(prog="dwarfsim.learn.ppo", description=__doc__.splitlines()[0])
    p.add_argument("--init", default="runs/learn/imitator", help="the policy to start from")
    p.add_argument("--out", default="runs/learn/ppo")
    p.add_argument("--iterations", type=int, default=45)
    p.add_argument("--ticks", type=int, default=400, help="ticks per episode")
    p.add_argument("--scenarios", default=",".join(scenario_mod.TRAIN_SCENARIOS))
    p.add_argument("--seeds", default="1-8", help="the pool rollout seeds rotate through")
    p.add_argument("--seeds-per-iter", type=int, default=1)
    p.add_argument("--agents", type=int, default=6)
    p.add_argument("--seed", type=int, default=0, help="everything random comes from this")
    p.add_argument("--lr", type=float, default=POLICY_LR)
    p.add_argument("--value-lr", type=float, default=VALUE_LR)
    p.add_argument("--epochs", type=int, default=EPOCHS)
    p.add_argument("--minibatch", type=int, default=MINIBATCH)
    p.add_argument("--clip", type=float, default=CLIP)
    p.add_argument("--entropy-coef", type=float, default=ENTROPY_COEF)
    p.add_argument("--kl-coef", type=float, default=KL_COEF,
                   help="pull back toward the imitator; 0 turns the anchor off")
    p.add_argument("--target-kl", type=float, default=TARGET_KL)
    p.add_argument("--gamma", type=float, default=GAMMA)
    p.add_argument("--lam", type=float, default=LAMBDA)
    p.add_argument("--eval-every", type=int, default=5)
    p.add_argument("--validation-seed", type=int, default=VALIDATION_SEED)
    p.add_argument("--budget", type=float, default=None, help="seconds; stop after this")
    p.add_argument("--reward", default="v2", choices=sorted(MODES),
                   help="which reward table: v1, v2, or the trait-conditioned one")
    args = p.parse_args(argv)
    train(init=args.init, out=args.out, iterations=args.iterations, ticks=args.ticks,
          scenarios=tuple(s.strip() for s in args.scenarios.split(",") if s.strip()),
          seeds=parse_seeds(args.seeds), seeds_per_iter=args.seeds_per_iter,
          agents=args.agents, seed=args.seed, lr=args.lr, value_lr=args.value_lr,
          epochs=args.epochs, minibatch=args.minibatch, clip=args.clip,
          entropy_coef=args.entropy_coef, kl_coef=args.kl_coef, target_kl=args.target_kl,
          gamma=args.gamma, lam=args.lam, eval_every=args.eval_every,
          validation_seed=args.validation_seed, budget=args.budget, reward_mode=args.reward)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
