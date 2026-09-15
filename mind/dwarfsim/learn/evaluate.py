"""Put the teacher, the imitator and the PPO policy in the same settlements and count.

    python -m dwarfsim.learn.evaluate --policies teacher runs/learn/imitator runs/learn/ppo/best \
        --seeds 20-23

Every policy runs every scenario at every seed, so the comparison is paired: the same starting
conditions, the same monster rolls, the same everything except who is scoring the candidates. Two
things come out of it.

**Reward.** Mean total reward per episode with the :mod:`dwarfsim.learn.reward` term breakdown, so
a policy that wins overall can still be read as "it kept more promises but let its needs slide".
The seeds are held out: they are in neither the PPO rollout pool nor its validation seed, so
nothing in training has seen them.

**A behaviour profile.** What the settlement actually did: how often each reaction to a
provocation was chosen, deaths, thefts, promises kept and broken, and fights started unprovoked
(the reward's own definition -- no grudge and no recent blow from the target). This is the part
that says whether a better reward number is better *behaviour* or a degenerate policy that found
a way to farm one term.

**A per-dwarf view.** ``--per-dwarf bully:21`` runs one settlement under each policy and prints
one row per dwarf: its two most pronounced traits and what it actually chose. Traits come off the
seed, so the rows line up across policies and only the action mix moves. That is the question the
trait-conditioned reward exists to answer -- whether six dwarves with six trait rolls do six
different things, or all six converge on the same polite loop.

``teacher`` names the hand-written weight table; anything else is a path to an exported scorer.
``--reward`` picks which table every policy is scored under -- ``v1``, ``v2`` or ``trait`` -- and
scoring the same runs under two tables is how a claim that one table is being farmed is checked.
"""

import argparse
import json
import os

import numpy as np

from ..mind import TRAITS
from ..schema import REACTION_SKILLS
from . import scenarios as scenario_mod
from .collect import parse_seeds
from .ppo import play
from .reward import COUNT_NAMES, MODES, TERM_NAMES, EpisodeCsv, trait_value
from .scorer import LearnedScorer

#: The chosen-action counts worth reporting: the six graded reactions plus the acts that make a
#: settlement what it is. ``SOCIALIZE`` and ``WORK`` are the two the reward's ``time`` term is
#: about -- how much of the day went on chatter and how much on the mine.
PROFILE_SKILLS = tuple(REACTION_SKILLS) + ("ATTACK", "APOLOGIZE", "STEAL", "GOSSIP",
                                           "ACCEPT", "REFUSE", "FULFIL", "SOCIALIZE", "WORK")

DEFAULT_TICKS = 600


class Profile:
    """Counts which skill won each decision. Handed to :func:`dwarfsim.learn.ppo.play`."""

    def __init__(self):
        self.chosen = {}
        self.decisions = 0

    def __call__(self, world, step):
        for rec in step["decisions"]:
            skill = rec["chosen"].split(" ", 1)[0]
            self.chosen[skill] = self.chosen.get(skill, 0) + 1
            self.decisions += 1

    def row(self):
        out = {"decisions": self.decisions}
        for name in PROFILE_SKILLS:
            out[name] = self.chosen.get(name, 0)
        return out


def load_policy(name):
    """``teacher`` is the weight table (``None``); anything else is an exported scorer."""
    if name in ("teacher", "table", "none"):
        return None
    return LearnedScorer.load(name)


def policy_label(name):
    if name in ("teacher", "table", "none"):
        return "teacher"
    base = os.path.basename(os.path.normpath(name))
    parent = os.path.basename(os.path.dirname(os.path.normpath(name)))
    if base in ("best", "last") and parent:
        return "%s/%s" % (parent, base)
    return base


def evaluate(policies, seeds, scenarios=scenario_mod.EVAL_SCENARIOS, ticks=DEFAULT_TICKS,
             agents=6, out=None, verbose=True, reward_mode="v2"):
    """Run everything and return ``{"episodes": [...], "policies": {...}}``."""
    rows = []
    csv = EpisodeCsv(out, fields=(["policy", "scenario", "seed", "ticks", "steps", "reward"]
                                  + list(TERM_NAMES) + list(COUNT_NAMES)
                                  + ["decisions"] + list(PROFILE_SKILLS))) if out else None
    try:
        for name in policies:
            label = policy_label(name)
            scorer = load_policy(name)
            for scenario in scenarios:
                for seed in seeds:
                    profile = Profile()
                    ep = play(scorer, scenario, int(seed), ticks, agents=agents,
                              record=False, tally=profile, reward_mode=reward_mode)
                    row = dict(ep["report"], policy=label)
                    row.update(profile.row())
                    rows.append(row)
                    if csv is not None:
                        csv.write(row)
                if verbose:
                    print("  %-18s %-8s done" % (label, scenario))
    finally:
        if csv is not None:
            csv.close()
    return {"episodes": rows, "policies": summarise(rows), "settings": {
        "policies": [policy_label(p) for p in policies], "seeds": [int(s) for s in seeds],
        "scenarios": list(scenarios), "ticks": ticks, "agents": agents,
        "reward_mode": reward_mode}}


def summarise(rows):
    """Per policy: mean reward and terms per episode, and summed behaviour counters."""
    out = {}
    for row in rows:
        p = out.setdefault(row["policy"], {"episodes": 0, "reward": [], "terms": {},
                                           "counts": {}, "skills": {}, "per_scenario": {}})
        p["episodes"] += 1
        p["reward"].append(row["reward"])
        for t in TERM_NAMES:
            p["terms"].setdefault(t, []).append(row[t])
        for c in COUNT_NAMES:
            p["counts"][c] = p["counts"].get(c, 0) + row[c]
        for s in PROFILE_SKILLS:
            p["skills"][s] = p["skills"].get(s, 0) + row[s]
        p["per_scenario"].setdefault(row["scenario"], []).append(row["reward"])
    for p in out.values():
        p["reward_mean"] = round(float(np.mean(p["reward"])), 2)
        p["reward_sd"] = round(float(np.std(p["reward"])), 2)
        p["terms"] = {k: round(float(np.mean(v)), 2) for k, v in p["terms"].items()}
        p["per_scenario"] = {k: round(float(np.mean(v)), 2) for k, v in p["per_scenario"].items()}
        del p["reward"]
    return out


# ---------------------------------------------------------------------------
# Printing
# ---------------------------------------------------------------------------


def report(result):
    names = list(result["policies"])
    scenarios = result["settings"]["scenarios"]
    print("\nmean reward per episode (%d ticks, seeds %s, reward table %s)"
          % (result["settings"]["ticks"],
             ",".join(str(s) for s in result["settings"]["seeds"]),
             result["settings"].get("reward_mode", "v2")))
    head = "%-18s %9s %7s" % ("policy", "reward", "sd")
    head += "".join("%10s" % t for t in TERM_NAMES)
    print(head)
    for n in names:
        p = result["policies"][n]
        line = "%-18s %9.2f %7.2f" % (n, p["reward_mean"], p["reward_sd"])
        line += "".join("%10.2f" % p["terms"][t] for t in TERM_NAMES)
        print(line)

    print("\nmean reward per episode, per scenario")
    print("%-18s" % "policy" + "".join("%10s" % s for s in scenarios))
    for n in names:
        p = result["policies"][n]
        print("%-18s" % n + "".join("%10.1f" % p["per_scenario"].get(s, float("nan"))
                                    for s in scenarios))

    print("\nbehaviour profile (totals over every episode)")
    cols = list(PROFILE_SKILLS) + ["deaths", "thefts", "promises_kept", "promises_broken",
                                   "unprovoked_hits", "hits"]
    print("%-18s" % "policy" + "".join("%9s" % c[:8] for c in cols))
    for n in names:
        p = result["policies"][n]
        vals = [p["skills"].get(c, p["counts"].get(c, 0)) for c in cols]
        print("%-18s" % n + "".join("%9d" % v for v in vals))


# ---------------------------------------------------------------------------
# One settlement, dwarf by dwarf
# ---------------------------------------------------------------------------


class PerDwarf:
    """Counts the chosen skill per *agent*, not per settlement. The question it answers is
    whether six dwarves with six different trait rolls do six different things."""

    def __init__(self):
        self.chosen = {}

    def __call__(self, world, step):
        for rec in step["decisions"]:
            aid = rec["agent"]
            skill = rec["chosen"].split(" ", 1)[0]
            box = self.chosen.setdefault(aid, {})
            box[skill] = box.get(skill, 0) + 1


def dominant_traits(agent, n=2):
    """The traits furthest from the middle, largest gap first: ``"greed .88 / bravery .15"``."""
    names = list(TRAITS) + ["loyalty"]
    vals = [(abs(trait_value(agent, t) - 0.5), t, trait_value(agent, t)) for t in names]
    vals.sort(reverse=True)
    return " ".join("%s %.2f" % (t, v) for _, t, v in vals[:n])


def per_dwarf(policies, scenario, seed, ticks=DEFAULT_TICKS, agents=6, reward_mode="trait",
              skills=("WORK", "SOCIALIZE", "GOSSIP", "STEAL", "ATTACK", "FLEE",
                      "DEMAND_APOLOGY", "RETORT", "COMPLAIN_TO", "IGNORE", "AVOID",
                      "APOLOGIZE", "FULFIL")):
    """One settlement under each policy, reported dwarf by dwarf. The traits are the same in
    every run -- they come off the seed -- so the rows line up and only the action mix moves."""
    out = []
    for name in policies:
        tally = PerDwarf()
        ep = play(load_policy(name), scenario, int(seed), ticks, agents=agents, record=False,
                  tally=tally, reward_mode=reward_mode)
        world = ep["world"]
        for a in world.agents:
            box = tally.chosen.get(a.id, {})
            out.append({"policy": policy_label(name), "dwarf": a.name, "id": a.id,
                        "traits": dominant_traits(a), "alive": a.alive,
                        "decisions": sum(box.values()),
                        "skills": {s: box.get(s, 0) for s in skills}})
    return {"rows": out, "skills": list(skills), "scenario": scenario, "seed": int(seed)}


def report_per_dwarf(result):
    skills = result["skills"]
    print("\nper dwarf, %s seed %d: dominant traits and what it chose"
          % (result["scenario"], result["seed"]))
    print("%-12s %-10s %-26s %6s" % ("policy", "dwarf", "dominant traits", "steps")
          + "".join("%8s" % s[:7] for s in skills))
    for row in sorted(result["rows"], key=lambda r: (r["id"], r["policy"])):
        print("%-12s %-10s %-26s %6d" % (row["policy"][:12], row["dwarf"][:10],
                                         row["traits"], row["decisions"])
              + "".join("%8d" % row["skills"][s] for s in skills))


def main(argv=None):
    p = argparse.ArgumentParser(prog="dwarfsim.learn.evaluate",
                                description=__doc__.splitlines()[0])
    p.add_argument("--policies", nargs="+",
                   default=["teacher", "runs/learn/imitator", "runs/learn/ppo/best"])
    p.add_argument("--seeds", default="20-23", help="held out of training entirely")
    p.add_argument("--scenarios", default=",".join(scenario_mod.EVAL_SCENARIOS))
    p.add_argument("--ticks", type=int, default=DEFAULT_TICKS)
    p.add_argument("--agents", type=int, default=6)
    p.add_argument("--reward", default="v2", choices=sorted(MODES),
                   help="which reward table every policy is scored under")
    p.add_argument("--per-dwarf", default=None, metavar="SCENARIO:SEED",
                   help="also print one settlement dwarf by dwarf, e.g. bully:20")
    p.add_argument("--out", default="runs/learn/ppo/evaluation.csv",
                   help="per-episode CSV; the summary goes next to it as .json")
    args = p.parse_args(argv)
    result = evaluate(args.policies, parse_seeds(args.seeds),
                      scenarios=tuple(s.strip() for s in args.scenarios.split(",") if s.strip()),
                      ticks=args.ticks, agents=args.agents, out=args.out,
                      reward_mode=args.reward)
    report(result)
    if args.per_dwarf:
        scen, _, sd = args.per_dwarf.partition(":")
        pd = per_dwarf(args.policies, scen, int(sd or 20), ticks=args.ticks,
                       agents=args.agents, reward_mode=args.reward)
        report_per_dwarf(pd)
        result["per_dwarf"] = pd
    if args.out:
        path = os.path.splitext(args.out)[0] + ".json"
        with open(path, "w", encoding="utf-8", newline="\n") as fh:
            extra = {"settings": result["settings"]}
            if "per_dwarf" in result:
                extra["per_dwarf"] = result["per_dwarf"]
            json.dump(result["policies"] | extra, fh, indent=2)
            fh.write("\n")
        print("\n%s + %s" % (args.out, path))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
