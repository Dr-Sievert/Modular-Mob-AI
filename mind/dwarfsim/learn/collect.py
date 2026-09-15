"""Watch the hand-written arbitrator decide, and write down every decision.

    python -m dwarfsim.learn.collect --seeds 1-8 --ticks 2000 --out runs/learn/teacher.npz

One *decision* is one dwarf on one tick: the observation it had, every candidate its skills
proposed, the weight table's score for each, and which one the softmax actually took. That is
everything a student needs to reproduce the teacher, and nothing else.

**The rare reactions need asking for.** ``IGNORE``, ``COMPLAIN_TO`` and ``APOLOGIZE`` are a few
dozen decisions in an ordinary collection, so two knobs aim at them: ``--targeted-seeds`` runs the
three settlements built for them (:mod:`dwarfsim.learn.scenarios`) over extra seeds, and
``--hard-gap`` records *every* tick of those runs rather than every second one wherever the
teacher's best two candidates were that close. Both cost time rather than variety.

**Collecting does not change the run.** Building the full feature vectors draws exactly the one
``noise`` value per candidate that the weight table draws, in the same order, so a collected run
and a plain run of the same seed are the same run tick for tick. ``tests/test_learn.py`` checks it.

**The file.** One ``.npz``, ragged, with an offsets array -- decisions have different numbers of
candidates and padding them all to the widest would double the size:

    obs          (D, OBS_SIZE)  float16   one row per decision
    offsets      (D + 1,)       int64     candidates of decision d are offsets[d]:offsets[d+1]
    cand_terms   (C, N_TERMS)   float16   the raw term values of one candidate
    cand_skill   (C,)           int8      its skill, the one-hot rebuilt on load
    teacher      (C,)           float32   the weight table's score, full precision
    chosen       (D,)           int16     which candidate the teacher took, within the decision
    scenario     (D,)           int8      index into meta["scenarios"]
    seed         (D,)           int16     the seed that produced it, so seeds can be held out
    meta         json string

float16 for the features, float32 for the target: the features are numbers in roughly 0..1 where
the third decimal never mattered, the scores are what the student is fitted against. Storing the
skill as an index instead of 22 floats is the other half of why 170k decisions fit in a quarter of
a gigabyte.
"""

import argparse
import json
import os
import time

import numpy as np

from .. import PLAYER_ORDER, PLAYER_SCRIPT
from ..obligations import make_ask
from ..schema import (CAND_SIZE, CAND_TERMS, N_SKILLS, N_TERMS, OBS_SIZE, PLAYER_ID, SCHEMA_ID,
                      SKILL_NAMES)
from ..world import World
from . import scenarios as scenario_mod

#: The scenarios worth learning from: ordinary life, a feud, a settlement that talks, a settlement
#: with a player in it, and the three targeted ones the reward stage added (:mod:`.scenarios`),
#: which are where the rare reactions actually happen. ``theft`` and ``raid`` are narrower
#: versions of these.
SCENARIOS = ("default", "feud", "gossip", "player") + scenario_mod.NAMES

#: Record every Nth tick, not every tick. Consecutive ticks are nearly the same decision, so this
#: buys variety per megabyte -- and the ticks that are not recorded cost nothing extra to run.
#: At 2, ``--seeds 1-8 --ticks 2000`` over the four scenarios lands at about 145k decisions and
#: 240 MB in 70 seconds, which is already well past the point of diminishing returns for a
#: 13k-parameter model.
DEFAULT_STRIDE = 2

#: ...except where the decision was close. On a tick the stride would have skipped, a decision is
#: still recorded when the teacher's best two candidates were within this of each other: those are
#: the decisions where a wrong pick is a different action rather than a tie broken differently,
#: and they are where the rare reactions live. Same number as ``imitate.HARD_GAP``, kept here so
#: collecting does not import the trainer. ``None`` turns the rule off.
HARD_GAP = 0.5

#: The scenarios the rule applies to, and the seeds they get *on top of* ``--seeds``. The three
#: targeted settlements are the ones built for the rare reactions, so they are the ones worth
#: more of: ``IGNORE``, ``COMPLAIN_TO`` and ``APOLOGIZE`` are a few dozen decisions in an
#: ordinary collection.
TARGETED_SCENARIOS = scenario_mod.NAMES
DEFAULT_TARGETED_SEEDS = (9, 10, 11, 12, 13, 14, 15, 16)

#: A ceiling, so a wide ``--seeds`` cannot quietly ask for ten gigabytes.
DEFAULT_MAX_DECISIONS = 220000


# ---------------------------------------------------------------------------
# A growable array that never doubles
# ---------------------------------------------------------------------------


class _Blocks:
    """Append-only numpy storage in fixed blocks, flattened once at the end.

    ``np.concatenate`` would hold the whole thing twice for a moment, and the whole thing is the
    biggest object in this program. Copying block by block and dropping each block as it goes
    peaks at the final size plus one block.
    """

    def __init__(self, width, dtype, block=1 << 16):
        self.width = width
        self.dtype = np.dtype(dtype)
        self.block = block
        self.blocks = []
        self.fill = 0
        self.n = 0

    def _shape(self, rows):
        return (rows,) if self.width is None else (rows, self.width)

    def add(self, rows):
        rows = np.asarray(rows, dtype=self.dtype)
        i = 0
        while i < len(rows):
            if not self.blocks or self.fill == self.block:
                self.blocks.append(np.empty(self._shape(self.block), dtype=self.dtype))
                self.fill = 0
            take = min(self.block - self.fill, len(rows) - i)
            self.blocks[-1][self.fill:self.fill + take] = rows[i:i + take]
            self.fill += take
            i += take
        self.n += len(rows)

    def finish(self):
        out = np.empty(self._shape(self.n), dtype=self.dtype)
        at = 0
        for i, blk in enumerate(self.blocks):
            take = self.fill if i == len(self.blocks) - 1 else self.block
            out[at:at + take] = blk[:take]
            at += take
        self.blocks = []
        return out


class Sink:
    """Where the decisions pile up while the sim runs."""

    def __init__(self, scenarios, max_decisions=DEFAULT_MAX_DECISIONS):
        self.scenarios = list(scenarios)
        self.max_decisions = max_decisions
        self.obs = _Blocks(OBS_SIZE, np.float16)
        self.cand_terms = _Blocks(N_TERMS, np.float16, block=1 << 18)
        self.cand_skill = _Blocks(None, np.int8, block=1 << 18)
        self.teacher = _Blocks(None, np.float32, block=1 << 18)
        self.counts = _Blocks(None, np.int32)
        self.chosen = _Blocks(None, np.int16)
        self.scenario = _Blocks(None, np.int8)
        self.seed = _Blocks(None, np.int16)
        self.decisions = 0
        self.candidates = 0

    @property
    def full(self):
        return self.decisions >= self.max_decisions

    def take(self, collected, scenario, seed):
        """Drain one tick's worth of ``(obs, features, teacher, chosen)`` tuples."""
        if not collected:
            return
        si = self.scenarios.index(scenario)
        obs_rows, feat_rows, teach, counts, chosen = [], [], [], [], []
        for obs, feats, teacher, pick in collected:
            if self.decisions + len(counts) >= self.max_decisions:
                break
            obs_rows.append(obs)
            feat_rows.extend(feats)
            teach.extend(teacher)
            counts.append(len(feats))
            chosen.append(pick)
        if not counts:
            return
        feat = np.asarray(feat_rows, dtype=np.float32).reshape(-1, CAND_SIZE)
        self.obs.add(obs_rows)
        self.cand_terms.add(feat[:, CAND_TERMS:])
        self.cand_skill.add(feat[:, :N_SKILLS].argmax(axis=1))
        self.teacher.add(teach)
        self.counts.add(counts)
        self.chosen.add(chosen)
        self.scenario.add([si] * len(counts))
        self.seed.add([seed] * len(counts))
        self.decisions += len(counts)
        self.candidates += len(feat)

    def arrays(self, meta):
        counts = self.counts.finish()
        offsets = np.zeros(len(counts) + 1, dtype=np.int64)
        np.cumsum(counts, out=offsets[1:])
        meta = dict(meta, scenarios=self.scenarios, decisions=int(self.decisions),
                    candidates=int(self.candidates), obs_size=OBS_SIZE, cand_size=CAND_SIZE,
                    n_terms=N_TERMS, n_skills=N_SKILLS, skills=list(SKILL_NAMES),
                    dwarfsim_schema_id=SCHEMA_ID)
        return {
            "obs": self.obs.finish(),
            "offsets": offsets,
            "cand_terms": self.cand_terms.finish(),
            "cand_skill": self.cand_skill.finish(),
            "teacher": self.teacher.finish(),
            "chosen": self.chosen.finish(),
            "scenario": self.scenario.finish(),
            "seed": self.seed.finish(),
            "meta": np.array(json.dumps(meta)),
        }


# ---------------------------------------------------------------------------
# Running one settlement
# ---------------------------------------------------------------------------


def _player_script():
    return dict((t, what) for t, what in PLAYER_SCRIPT)


def is_hard(teacher, gap):
    """Were the teacher's best two candidates within ``gap`` of each other?"""
    if len(teacher) < 2:
        return True
    best, second = np.partition(np.asarray(teacher, dtype=np.float32), -2)[-2:][::-1]
    return bool(best - second < gap)


def run_one(sink, scenario, seed, ticks, agents=6, stride=DEFAULT_STRIDE, hard_gap=None):
    """One settlement, recorded. Returns how many decisions came out of it.

    With ``hard_gap`` set, *every* tick is recorded rather than every ``stride``-th, and the ticks
    the stride would have skipped keep only their close decisions -- the ones where the teacher's
    best two candidates were within ``hard_gap``. Building the feature vectors is what costs, not
    keeping them, so this is bought with time rather than with megabytes.

    The ``player`` scenario is driven the way :func:`dwarfsim.run_player_session` drives it --
    orders, help, a gift, a promise kept -- because a player scenario with nobody at the keyboard
    is just ``default`` with a spare relationship row.
    """
    world = World(n_agents=agents, seed=seed, scenario=scenario)
    before = sink.decisions
    script = _player_script() if scenario == "player" else {}
    who = world.agents[0]
    promised = []
    for t in range(ticks):
        if sink.full:
            break
        on_stride = t % stride == 0
        world.collector = [] if (on_stride or hard_gap is not None) else None
        world.step()
        living = world.living()
        if not living:
            break
        if script:
            if not who.alive:
                who = living[0]
            what = script.get(world.tick)
            if what == "order":
                world.say(PLAYER_ID, who, dict(
                    PLAYER_ORDER, ask=make_ask("BRING", item="ore", quantity=1, place="MINE")))
            elif what == "help":
                world.player_help(who, magnitude=1.4)
            elif what == "give":
                world.player_give(who, 5)
            elif what == "promise":
                promised.append(world.player_promise(
                    who, make_ask("GIVE", item="gold", quantity=3)))
            elif what == "keep" and promised:
                world.player_give(who, 3)
                world.fulfil_obligation(promised[-1])
        if world.collector:
            got = world.collector
            if hard_gap is not None and not on_stride:
                got = [c for c in got if is_hard(c[2], hard_gap)]
            sink.take(got, scenario, seed)
    world.collector = None
    return sink.decisions - before


def collect(out, seeds=(1, 2, 3, 4, 5, 6, 7, 8), ticks=2000, agents=6, scenarios=SCENARIOS,
            stride=DEFAULT_STRIDE, max_decisions=DEFAULT_MAX_DECISIONS,
            targeted_seeds=(), targeted_scenarios=TARGETED_SCENARIOS, hard_gap=None,
            verbose=True):
    """Run every scenario at every seed and write one ``.npz``. Returns its metadata.

    ``targeted_scenarios`` get ``targeted_seeds`` on top of ``seeds``, and are the ones
    ``hard_gap`` applies to -- both knobs exist for the same reason, which is that the rare
    reactions only happen in those three settlements and only in the close decisions.
    """
    started = time.time()
    sink = Sink(scenarios, max_decisions=max_decisions)
    targeted_scenarios = tuple(targeted_scenarios)
    per_run = []
    for scenario in scenarios:
        targeted = scenario in targeted_scenarios
        gap = hard_gap if targeted else None
        run_seeds = list(seeds) + (list(targeted_seeds) if targeted else [])
        for seed in run_seeds:
            if sink.full:
                break
            n = run_one(sink, scenario, seed, ticks, agents=agents, stride=stride, hard_gap=gap)
            per_run.append({"scenario": scenario, "seed": int(seed), "decisions": int(n)})
            if verbose:
                print("  %-8s seed %-3d %7d decisions%s  (%6.1f s)"
                      % (scenario, seed, n, " hard" if gap is not None else "    ",
                         time.time() - started))
    meta = {
        "created_by": "dwarfsim.learn.collect",
        "seeds": [int(s) for s in seeds],
        "ticks": int(ticks),
        "agents": int(agents),
        "stride": int(stride),
        "targeted_seeds": [int(s) for s in targeted_seeds],
        "targeted_scenarios": [s for s in targeted_scenarios if s in scenarios],
        "hard_gap": hard_gap,
        "seconds": round(time.time() - started, 2),
        "runs": per_run,
    }
    arrays = sink.arrays(meta)
    directory = os.path.dirname(os.path.abspath(out))
    if directory:
        os.makedirs(directory, exist_ok=True)
    with open(out, "wb") as fh:
        np.savez(fh, **arrays)
    meta = json.loads(str(arrays["meta"]))
    meta["bytes"] = os.path.getsize(out)
    if verbose:
        print("%s: %d decisions, %d candidates, %.1f MB, %.1f s"
              % (out, meta["decisions"], meta["candidates"], meta["bytes"] / 1e6,
                 time.time() - started))
    return meta


# ---------------------------------------------------------------------------
# Reading it back
# ---------------------------------------------------------------------------


class Decisions:
    """A loaded collection. ``features(d)`` rebuilds one decision's ``(n, CAND_SIZE)`` block."""

    def __init__(self, arrays):
        self.obs = arrays["obs"]
        self.offsets = arrays["offsets"]
        self.cand_terms = arrays["cand_terms"]
        self.cand_skill = arrays["cand_skill"]
        self.teacher = arrays["teacher"]
        self.chosen = arrays["chosen"]
        self.scenario = arrays["scenario"]
        self.seed = arrays["seed"]
        self.meta = json.loads(str(arrays["meta"]))
        self.scenarios = self.meta["scenarios"]

    def __len__(self):
        return len(self.obs)

    @property
    def n_candidates(self):
        return len(self.cand_terms)

    def slice(self, d):
        return int(self.offsets[d]), int(self.offsets[d + 1])

    def features(self, d):
        """The full ``(n, CAND_SIZE)`` float32 feature block of decision ``d``."""
        lo, hi = self.slice(d)
        return expand(self.cand_terms[lo:hi], self.cand_skill[lo:hi])

    def observation(self, d):
        return self.obs[d].astype(np.float32)


def expand(terms, skill):
    """Rebuild candidate feature rows: the skill one-hot, then the raw term values."""
    terms = np.asarray(terms, dtype=np.float32).reshape(-1, N_TERMS)
    out = np.zeros((len(terms), CAND_SIZE), dtype=np.float32)
    out[np.arange(len(terms)), np.asarray(skill, dtype=np.int64)] = 1.0
    out[:, CAND_TERMS:] = terms
    return out


def load(path):
    with np.load(path, allow_pickle=False) as fh:
        arrays = {k: fh[k] for k in fh.files}
    got = json.loads(str(arrays["meta"])).get("dwarfsim_schema_id")
    if got != SCHEMA_ID:
        raise ValueError("collection is %s, this sim is %s" % (got, SCHEMA_ID))
    return Decisions(arrays)


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def parse_seeds(text):
    """``1-8``, ``1,3,5`` or ``1-4,9``."""
    out = []
    for part in str(text).split(","):
        part = part.strip()
        if not part:
            continue
        if "-" in part[1:]:
            lo, hi = part.split("-", 1)
            out.extend(range(int(lo), int(hi) + 1))
        else:
            out.append(int(part))
    if not out:
        raise ValueError("no seeds in %r" % text)
    return out


def main(argv=None):
    p = argparse.ArgumentParser(prog="dwarfsim.learn.collect", description=__doc__.splitlines()[0])
    p.add_argument("--seeds", default="1-8", help="e.g. 1-8 or 1,3,5 (default 1-8)")
    p.add_argument("--ticks", type=int, default=2000, help="ticks per run (default 2000)")
    p.add_argument("--agents", type=int, default=6)
    p.add_argument("--scenarios", default=",".join(SCENARIOS))
    p.add_argument("--stride", type=int, default=DEFAULT_STRIDE,
                   help="record every Nth tick (default %d)" % DEFAULT_STRIDE)
    p.add_argument("--max-decisions", type=int, default=DEFAULT_MAX_DECISIONS)
    p.add_argument("--targeted-seeds", default="",
                   help="extra seeds for the three targeted scenarios, e.g. 9-16")
    p.add_argument("--targeted-scenarios", default=",".join(TARGETED_SCENARIOS))
    p.add_argument("--hard-gap", type=float, default=None,
                   help="in the targeted scenarios, also record the ticks the stride would skip "
                        "wherever the teacher's top two are this close (try %.1f)" % HARD_GAP)
    p.add_argument("--out", default="runs/learn/teacher.npz")
    args = p.parse_args(argv)
    collect(args.out, seeds=parse_seeds(args.seeds), ticks=args.ticks, agents=args.agents,
            scenarios=tuple(s.strip() for s in args.scenarios.split(",") if s.strip()),
            stride=args.stride, max_decisions=args.max_decisions,
            targeted_seeds=parse_seeds(args.targeted_seeds) if args.targeted_seeds else (),
            targeted_scenarios=tuple(s.strip() for s in args.targeted_scenarios.split(",")
                                     if s.strip()),
            hard_gap=args.hard_gap)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
