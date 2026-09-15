"""What a dwarf is actually rewarded for. One table, computed from before and after.

The imitation stage had no reward at all: the student copied the table's choice and could not
discover anything the table did not already do. This is the other half. One *step* is one
decision -- one dwarf on one tick -- and its reward is read off the world state before that tick
and the world state after it, plus the events the tick emitted.

The whole table is :data:`REWARD_WEIGHTS` at the top of this module. Every term is signed so that
more is always better, so the step reward is a plain weighted sum:

    reward = sum(REWARD_WEIGHTS[term] * value[term])

``needs``
    ``-(mean of hunger, thirst, fatigue, social)``: 0 at best, -1 at worst. A dwarf that never
    eats, drinks, rests or talks is not doing well, whatever else it achieves.
``alive``
    ``-1`` on the step the dwarf dies, else 0. The one large penalty; everything else is small
    next to it.
``wealth``
    My change in ``(gold + 2*ore)`` *minus the settlement's mean change*, over
    :data:`WEALTH_SCALE`. Ore is worth two gold at the forge. Relative, so getting ahead of the
    others pays and everybody getting richer together does not -- see below.
``standing``
    The mean, over the other dwarves, of the change in **respect** toward me. The social term:
    what everybody else thinks of me, moved by what I just did.
``promises``
    ``+1`` per promise kept and ``-1`` per promise broken, by me. An obligation is only worth
    something if keeping it is.
``violence``
    ``-1`` per *unprovoked* blow I threw -- see below.
``time``
    ``-1`` on a step where I chose :data:`TIME_SKILLS` -- ``SOCIALIZE`` or ``GOSSIP`` -- while one
    of :data:`TIME_NEEDS` was already over :data:`TIME_NEED_LEVEL`. Chatting is not free while
    there is work to do; it is what makes flattery compete with the rest of the day.

**Unprovoked.** A ``HIT`` counts against the attacker when, at the start of the tick, the
attacker held no grudge against that target (:meth:`dwarfsim.memory.MemoryBook.grudge` at or
below :data:`GRUDGE_EPS`) *and* that target had not hit the attacker in the last
:data:`PROVOKED_WINDOW` ticks. Hitting back, or hitting someone who robbed you, is free: the
reward says nothing about whether violence is right, only that starting it is expensive. Which
is the only claim the sim can actually support.

**Standing is a delta, not a level.** A level would pay a dwarf every tick for a reputation it
earned a thousand ticks ago and make the early state of a scenario the whole return. The delta
credits the tick that moved it. Holders that died during the tick are dropped from both sides of
the mean so the composition of the settlement changing does not read as a social triumph.

**Standing counts respect, not trust** (:data:`STANDING_KEYS`), at weight 12 rather than 20. The
first PPO run maximised the old ``(trust + respect) / 2`` version by praising everybody it could
reach. Respect is the half of a relationship that *deeds* move -- ``HELP`` +0.14, ``PUNISH``
+0.12, ``PROMISE_KEPT`` +0.09 -- where trust is the half that words move. What one event is worth
to the actor, weighted, before and after:

    ==============  ======  ======
    event           v1      v2
    ==============  ======  ======
    ``SMALLTALK``   +0.069  +0.000
    ``PRAISE``      +0.280  +0.143
    ``GIFT``        +0.487  +0.143
    ``PROMISE_KEPT``+0.704  +0.322
    ``HELP``        +0.601  +0.500
    ==============  ======  ======

Small talk is now worth nothing, praise about half, and helping almost what it was: a deed was
2.1 praises and is now 3.5.

**Wealth is relative** (``REWARD_WEIGHTS_V1`` had it absolute). The settlement's mean change is
subtracted, over the dwarves that were alive at both ends of the tick, so the term sums to about
zero across the settlement: mining pays while the others are not mining, and a tick in which
everybody's pile grows equally pays nobody. The absolute version rewarded six dwarves for the
same ore.

**None of it was enough.** A second PPO run against this table is *more* degenerate than the first,
not less: it socialises slightly more, works slightly less, and every provocation in the settlement
is gone. Respect turned out to be as farmable through "accept every ask and fulfil it" as trust was
through praise, and the ``time`` cost was dodged by keeping the needs it is conditioned on down.
The numbers are in ``docs/design.md``, "A second reward table, and what it did not fix";
``runs/learn/ppo/best`` is still the first policy. Read the weights below as the second attempt at
a question this shape of reward has not answered, not as a solved table.

**The weights are a balance, not a scale.** Over a 600-tick settlement under the hand-written
arbitrator the terms come out at roughly ``needs -180``, ``standing +3``, ``wealth 0``, with
deaths, promises, violence and time in the single digits -- so needs are the drum beat and
everything else is a correction on top of it. Wealth is deliberately quiet: gold has almost no
use in this sim, and a wealth term big enough to see makes every dwarf a miner and nothing else.

**Three tables, one argument.** ``mode`` picks one (:data:`MODES`): ``"v1"`` is the first table
exactly, ``"v2"`` the one above, ``"trait"`` the third attempt described next. ``--reward`` on
:mod:`dwarfsim.learn.ppo` and :mod:`dwarfsim.learn.evaluate` is this argument.

**The trait table.** Both PPO runs collapsed the same way -- every dwarf chased the same scalar,
and the cheapest path through it was flattery, then errands. The guess this table tests is that
the collapse is a *single-objective* artefact: if every dwarf's reward is shaped by its own
traits, six dwarves in one settlement have six objectives and no one equilibrium fits them all.
So ``mode="trait"`` adds six terms that only a trait cares about --

``hurt``
    ``-(health lost this tick) / MAX_HEALTH``. A wound, separately from dying of it.
``grudge``
    ``-(mean grudge I am carrying against the living)``, 0..1. A level, not a delta, on purpose:
    it is a standing cost of not letting go, charged every tick it lasts.
``slight``
    ``-1`` on a step where I chose ``IGNORE`` and somebody had insulted me *in front of
    witnesses* within :data:`PROVOKED_WINDOW` ticks.
``duty``
    ``+1`` per ``HELP`` I did for a dwarf I trust (or for the settlement, against a monster), and
    ``+1`` per blow I struck at somebody who had just hit a friend of mine.
``social_act``
    ``+1`` on a step where I chose ``SOCIALIZE``.
``payback``
    ``+1`` on a step where I chose ``RETORT`` or ``ATTACK`` while already provoked.

-- and then multiplies *every* term value, before it is weighted, by that dwarf's own multiplier
out of :data:`TRAIT_SHAPING`. The weight table stays one global table, which is what lets
:class:`dwarfsim.learn.ppo.Rollout` keep computing the reward as ``terms @ weights``; the
per-dwarf part is folded into the value. Because every multiplier is 1.0 at trait 0.5, a
settlement of average dwarves scores :data:`REWARD_WEIGHTS_TRAIT` exactly, and the total scale
stays within sight of v1 -- which is what lets the PPO settings transfer unchanged.

``wealth`` is read *absolutely* here, v1 style. The relative version subtracts the settlement
mean, which makes the term zero-sum, and a zero-sum pot cannot be scaled six different ways
without the scalings fighting over it.

Per-episode totals go to a CSV (:class:`EpisodeCsv`), one row per settlement run, so a training
run or an evaluation can be read without parsing a log.
"""

import csv
import os

from ..schema import MAX_HEALTH

#: The table. Weights are the only tuning knob in the reward; the term values below are fixed
#: readings off the world. The six trait-only terms are zero here: they are only ever non-zero in
#: :data:`REWARD_WEIGHTS_TRAIT`.
REWARD_WEIGHTS = {
    "needs": 0.50,
    "alive": 15.00,
    "wealth": 0.10,
    "standing": 12.00,
    "promises": 2.00,
    "violence": 3.00,
    "time": 0.10,
    "hurt": 0.00,
    "grudge": 0.00,
    "slight": 0.00,
    "duty": 0.00,
    "social_act": 0.00,
    "payback": 0.00,
}

#: The first table, kept for comparison: standing over ``(trust + respect) / 2`` at weight 20,
#: absolute wealth, no time cost. The weights alone do not restore it -- pass
#: ``standing_keys=STANDING_KEYS_V1, relative_wealth=False`` to :class:`RewardModel` as well, or
#: just ``mode="v1"``.
REWARD_WEIGHTS_V1 = {
    "needs": 0.50,
    "alive": 15.00,
    "wealth": 0.10,
    "standing": 20.00,
    "promises": 2.00,
    "violence": 3.00,
    "time": 0.00,
    "hurt": 0.00,
    "grudge": 0.00,
    "slight": 0.00,
    "duty": 0.00,
    "social_act": 0.00,
    "payback": 0.00,
}

#: The third table: the seven terms above at their v2 weights (with wealth read *absolutely*, v1
#: style, because a relative term cannot be scaled per dwarf without the six of them fighting over
#: one zero-sum pot) plus six terms that only a trait cares about. This is the *base* -- every
#: number here is what a dwarf with every trait at 0.5 is paid, and :data:`TRAIT_SHAPING` moves it
#: from there. A settlement of average dwarves therefore scores this table exactly, which is what
#: keeps the total scale close to v1 and lets the PPO settings transfer unchanged.
REWARD_WEIGHTS_TRAIT = {
    "needs": 0.50,
    "alive": 15.00,
    "wealth": 0.10,
    "standing": 12.00,
    "promises": 2.00,
    "violence": 3.00,
    "time": 0.10,
    "hurt": 3.00,
    "grudge": 0.05,
    "slight": 0.50,
    "duty": 1.00,
    "social_act": 0.02,
    "payback": 0.30,
}

#: **The mapping, in one table.** ``term -> (trait, multiplier at trait 0, multiplier at trait 1)``,
#: linear in between, so a dwarf with the trait at 0.5 is paid the base weight exactly and the
#: settlement mean stays where v1 put it. ``needs_social`` is not a term: it is the social quarter
#: of ``needs``, the only piece of that term a trait touches.
#:
#: ===============  =============  =====  =====  ==================================================
#: term             trait          at 0   at 1   what it means
#: ===============  =============  =====  =====  ==================================================
#: ``wealth``       greed           0.20   1.80  a greedy dwarf's ore is worth four times an
#:                                               indifferent one's; mining and stealing pay it
#: ``standing``     pride           0.40   1.60  the proud one is the one that cares what the
#:                                               room thinks of it
#: ``needs_social`` sociability     0.40   1.60  loneliness bites the sociable dwarf hardest
#: ``social_act``   sociability     0.00   2.00  and a small flat payment for choosing SOCIALIZE
#: ``violence``     bravery         1.60   0.40  starting a fight is cheap for the brave and dear
#:                                               for the timid
#: ``alive``        bravery         1.25   0.75  the brave discount their own death a little
#: ``hurt``         bravery         2.00   0.00  the coward is charged for every point of health
#:                                               it loses; the brave one is not charged at all
#: ``slight``       pride           0.00   2.00  IGNORE-ing an insult thrown in front of witnesses
#: ``grudge``       forgiveness     0.00   2.00  carrying a grudge costs the forgiving and is free
#:                                               to the unforgiving, who may as well keep it
#: ``duty``         loyalty*        0.00   2.00  HELP, and a blow struck for a friend who was hit
#: ``payback``      temper          0.00   2.00  RETORT or ATTACK *after* a provocation
#: ===============  =============  =====  =====  ==================================================
#:
#: * :data:`TRAITS` has six entries and ``loyalty`` is not one of them, so it is derived --
#: :func:`trait_value` -- as ``(forgiveness + (1 - greed)) / 2``: the dwarf that lets things go and
#: does not count its gold. That is a stand-in, and it is the one row of this table that is not a
#: number the sim already rolls.
TRAIT_SHAPING = {
    "wealth": ("greed", 0.20, 1.80),
    "standing": ("pride", 0.40, 1.60),
    "needs_social": ("sociability", 0.40, 1.60),
    "social_act": ("sociability", 0.00, 2.00),
    "violence": ("bravery", 1.60, 0.40),
    "alive": ("bravery", 1.25, 0.75),
    "hurt": ("bravery", 2.00, 0.00),
    "slight": ("pride", 0.00, 2.00),
    "grudge": ("forgiveness", 0.00, 2.00),
    "duty": ("loyalty", 0.00, 2.00),
    "payback": ("temper", 0.00, 2.00),
}

#: The six terms that exist only under ``mode="trait"``; zero-weighted in the other two tables.
TRAIT_TERMS = ("hurt", "grudge", "slight", "duty", "social_act", "payback")

#: ``mode -> (weights, standing_keys, relative_wealth, trait-conditioned)``. One argument instead
#: of three, so ``--reward v1`` and ``--reward trait`` mean exactly one thing each.
MODES = {
    "v1": ("REWARD_WEIGHTS_V1", ("trust", "respect"), False, False),
    "v2": ("REWARD_WEIGHTS", ("respect",), True, False),
    "trait": ("REWARD_WEIGHTS_TRAIT", ("respect",), False, True),
}

#: Above this much trust, the other dwarf is a friend: ``duty`` pays for helping and defending
#: them and for nobody else.
LOYAL_TRUST = 0.25

#: Fixed order, for the CSV columns and the vectors in :mod:`dwarfsim.learn.ppo`.
TERM_NAMES = ("needs", "alive", "wealth", "standing", "promises", "violence", "time",
              "hurt", "grudge", "slight", "duty", "social_act", "payback")

#: The four needs, averaged into the ``needs`` term.
NEED_NAMES = ("hunger", "thirst", "fatigue", "social")

#: What ``standing`` averages over the other dwarves. Respect only: see the module docstring.
STANDING_KEYS = ("respect",)
STANDING_KEYS_V1 = ("trust", "respect")

#: The ``time`` term: these skills, charged while one of these needs is over this level.
#:
#: ``social`` is not one of the needs on purpose -- ``SOCIALIZE`` is the cure for it, so charging a
#: lonely dwarf for talking would make the term fight itself. These three are the ones a day's work
#: answers.
#:
#: The level is 0.35 and not the 0.6 it started at because 0.6 never happens here: over 7,200 steps
#: of ``default`` and ``friends`` at seed 20 the teacher chatted 604 times and *not once* with a
#: need over 0.6, and with ``social`` included as well, still not once. Dwarves in this sim keep
#: themselves fed; the 90th percentile of the largest of these three needs is 0.40. At 0.35 the
#: term charges about 36% of chat steps under every policy, so it is a tax on chatter that scales
#: with how much of it there is, which is what it was for. One constant to move it back.
TIME_SKILLS = ("SOCIALIZE", "GOSSIP")
TIME_NEEDS = ("hunger", "thirst", "fatigue")
TIME_NEED_LEVEL = 0.35

#: What counts as "a lot" of wealth gained in one tick. Mining one ore is +2 before this.
WEALTH_SCALE = 5.0

#: The skills ``payback`` pays for, taken while already provoked.
PAYBACK_SKILLS = ("RETORT", "ATTACK")

#: What counts as being slighted in public, for ``slight``: one of these, aimed at me, in a place
#: holding somebody besides the two of us.
PUBLIC_INSULTS = ("INSULT", "SLUR")

#: Above this much remembered harm from somebody, hitting them is provoked.
GRUDGE_EPS = 0.02

#: And a blow this recent from them counts as provocation whatever memory says.
PROVOKED_WINDOW = 25

#: Counters that are not reward terms but are what an evaluation reports. ``idle_chat`` is the
#: number of steps the ``time`` term charged for.
COUNT_NAMES = ("deaths", "hits", "unprovoked_hits", "thefts",
               "promises_kept", "promises_broken", "insults", "idle_chat",
               "ignored_slights", "paybacks", "duties")


def weights_for(mode):
    """The base weight table one of :data:`MODES` names."""
    if mode not in MODES:
        raise ValueError("unknown reward mode %r; one of %s" % (mode, sorted(MODES)))
    return dict(globals()[MODES[mode][0]])


def trait_value(agent, trait):
    """One trait of one dwarf, 0..1. ``loyalty`` is derived; everything else is rolled by
    :class:`dwarfsim.mind.MindState`."""
    t = agent.mind.traits
    if trait == "loyalty":
        return 0.5 * (t["forgiveness"] + (1.0 - t["greed"]))
    return t[trait]


def trait_scale(agent, term):
    """The multiplier :data:`TRAIT_SHAPING` gives this dwarf for this term. 1.0 off the table."""
    row = TRAIT_SHAPING.get(term)
    if row is None:
        return 1.0
    trait, low, high = row
    return low + (high - low) * trait_value(agent, trait)


def trait_scales(agent):
    """Every multiplier for one dwarf, ``needs_social`` included. Computed once per episode."""
    return {term: trait_scale(agent, term) for term in TRAIT_SHAPING}


def label_parts(label):
    """``"RETORT @TAVERN ->3 ~5"`` -> ``("RETORT", 3)``. The target is ``None`` when there is
    none, or when it is not a dwarf id (the player's is a string)."""
    bits = label.split(" ")
    target = None
    for b in bits[1:]:
        if b.startswith("->"):
            try:
                target = int(b[2:])
            except ValueError:
                target = b[2:]
            break
    return bits[0], target



def wealth_of(agent):
    """One number for what a dwarf owns. Ore is worth two gold at the forge, so it counts twice."""
    return agent.inv["gold"] + 2.0 * agent.inv["ore"]


def _skill_by_agent(step):
    """``{agent id: chosen label}`` out of what ``world.step()`` returned. ``None`` -> ``{}``.

    A decision record's ``chosen`` is a label like ``"SOCIALIZE @TAVERN ->3"``; the skill is the
    first word, which is exactly what :class:`dwarfsim.learn.evaluate.Profile` reads too, and
    :func:`label_parts` pulls the target off the rest.
    """
    if step is None:
        return {}
    decisions = step["decisions"] if isinstance(step, dict) else step
    out = {}
    for rec in decisions:
        aid = rec.get("agent")
        if aid is not None:
            out[aid] = rec["chosen"]
    return out


def reward_of(terms, weights=None):
    """The weighted sum of one step's term values."""
    w = REWARD_WEIGHTS if weights is None else weights
    return sum(w.get(k, 0.0) * v for k, v in terms.items())


class RewardModel:
    """Reads one tick's reward for every dwarf that was alive when the tick began.

    Call :meth:`begin` before ``world.step()`` and :meth:`end` after it. ``end`` returns
    ``{agent id: {term: value}}`` -- one row per dwarf that was alive at the start of the tick,
    including one that died during it.

    ``end`` takes what ``world.step()`` returned (or just its ``decisions`` list): the ``time``
    term is the only one that needs to know *which action* was chosen rather than what changed in
    the world, and without it that term is simply zero.

    ``standing_keys`` and ``relative_wealth`` are what separates this table from
    :data:`REWARD_WEIGHTS_V1`'s; they are arguments so the first run stays reproducible, and
    ``mode`` is the one-word way of setting all three at once (:data:`MODES`).

    Under ``mode="trait"`` the six extra terms are read as well and *every* term value is
    multiplied, before it is weighted, by that dwarf's own :data:`TRAIT_SHAPING` multiplier. The
    weight table therefore stays one global table -- which is what lets
    :class:`dwarfsim.learn.ppo.Rollout` keep computing the reward as ``terms @ weights`` -- while
    six dwarves in one settlement pursue six different objectives.
    """

    def __init__(self, world, weights=None, standing_keys=None, relative_wealth=None,
                 mode="v2"):
        if mode not in MODES:
            raise ValueError("unknown reward mode %r; one of %s" % (mode, sorted(MODES)))
        table, keys, relative, conditioned = MODES[mode]
        self.world = world
        self.mode = mode
        self.conditioned = conditioned
        self.weights = dict(globals()[table] if weights is None else weights)
        self.standing_keys = tuple(keys if standing_keys is None else standing_keys)
        self.relative_wealth = relative if relative_wealth is None else bool(relative_wealth)
        self._before = {}
        self._scales = {}          # agent id -> its TRAIT_SHAPING multipliers; traits are static
        self._insulted = {}        # agent id -> the tick it was last insulted in front of others
        self.totals = {t: 0.0 for t in TERM_NAMES}
        self.counts = {c: 0 for c in COUNT_NAMES}
        self.steps = 0
        self.total = 0.0

    def scales(self, agent):
        """This dwarf's multipliers, cached: traits never change once a world is built."""
        got = self._scales.get(agent.id)
        if got is None:
            got = self._scales[agent.id] = trait_scales(agent)
        return got

    # -- before ------------------------------------------------------------

    def begin(self):
        """Snapshot what the terms are differences of, and who had a reason to swing."""
        w = self.world
        snap = {}
        for a in w.agents:
            if not a.alive:
                continue
            provoked = set()
            for o in w.agents:
                if o.id == a.id or not o.alive:
                    continue
                if a.memories.grudge(w.tick, a, o.id) > GRUDGE_EPS:
                    provoked.add(o.id)
            if a.last_hit_by is not None and w.tick - a.last_hit_tick <= PROVOKED_WINDOW:
                provoked.add(a.last_hit_by)
            pressed = max(a.mind.needs[n] for n in TIME_NEEDS) > TIME_NEED_LEVEL
            row = {"wealth": wealth_of(a), "standing": self._standing(a),
                   "provoked": provoked, "pressed": pressed}
            if self.conditioned:
                # Somebody hit a friend of mine, recently: striking them is ``duty``, not violence.
                defend = set()
                for o in w.agents:
                    if o.id == a.id or not o.alive:
                        continue
                    if a.mind.rel(o.id)["trust"] <= LOYAL_TRUST:
                        continue
                    if o.last_hit_by is not None and o.last_hit_by != a.id                             and w.tick - o.last_hit_tick <= PROVOKED_WINDOW:
                        defend.add(o.last_hit_by)
                row["defend"] = defend
                row["health"] = a.health
                row["slighted"] = w.tick - self._insulted.get(a.id, -10 ** 9) <= PROVOKED_WINDOW
            snap[a.id] = row
        self._before = snap

    def _standing(self, agent):
        """``{holder id: the mean of STANDING_KEYS toward this dwarf}`` over the living."""
        out = {}
        for o in self.world.agents:
            if o.id == agent.id or not o.alive:
                continue
            r = o.mind.rels.get(agent.id)
            out[o.id] = 0.0 if r is None else (
                sum(r[k] for k in self.standing_keys) / len(self.standing_keys))
        return out

    def _mean_wealth(self, ids):
        if not ids:
            return 0.0
        return sum(wealth_of(self.world.agent(i)) for i in ids) / len(ids)

    # -- after -------------------------------------------------------------

    def end(self, step=None):
        """One row per dwarf that began the tick alive. Call immediately after ``world.step()``.

        ``step`` is what ``world.step()`` returned, or its ``decisions`` list, or ``None``.
        """
        w = self.world
        labels = _skill_by_agent(step)
        chosen = {aid: label_parts(lab) for aid, lab in labels.items()}
        # The settlement's own change, over the dwarves alive at both ends of the tick, so that
        # the relative wealth term is a comparison between survivors and not with the dead.
        common = [aid for aid in self._before if w.agent(aid) is not None and w.agent(aid).alive]
        settlement = 0.0
        if self.relative_wealth and common:
            settlement = (self._mean_wealth(common)
                          - sum(self._before[i]["wealth"] for i in common) / len(common))
        rows = {}
        for aid, before in self._before.items():
            a = w.agent(aid)
            terms = {t: 0.0 for t in TERM_NAMES}
            needs = a.mind.needs
            terms["needs"] = -sum(needs[n] for n in NEED_NAMES) / len(NEED_NAMES)
            if not a.alive:
                terms["alive"] = -1.0
                self.counts["deaths"] += 1
            terms["wealth"] = (wealth_of(a) - before["wealth"] - settlement) / WEALTH_SCALE
            skill, chose_at = chosen.get(aid, (None, None))
            if before["pressed"] and skill in TIME_SKILLS:
                terms["time"] = -1.0
                self.counts["idle_chat"] += 1
            after = self._standing(a)
            moved = [after[k] - v for k, v in before["standing"].items() if k in after]
            terms["standing"] = sum(moved) / len(moved) if moved else 0.0
            if self.conditioned:
                scale = self.scales(a)
                # The only term a trait reaches *inside*: loneliness, one of the four needs.
                terms["needs"] = -(sum(needs[n] for n in NEED_NAMES if n != "social")
                                   + needs["social"] * scale["needs_social"]) / len(NEED_NAMES)
                terms["hurt"] = -max(0.0, before["health"] - a.health) / MAX_HEALTH
                terms["grudge"] = -self._grudge_held(a)
                if skill == "IGNORE" and before["slighted"]:
                    terms["slight"] = -1.0
                    self.counts["ignored_slights"] += 1
                if skill == "SOCIALIZE":
                    terms["social_act"] = 1.0
                if skill in PAYBACK_SKILLS and (before["provoked"] or before["slighted"]):
                    terms["payback"] = 1.0
                    self.counts["paybacks"] += 1
            rows[aid] = terms

        for ev in w.events:
            kind = ev["type"]
            actor = ev.get("actor")
            if kind == "STEAL":
                self.counts["thefts"] += 1
            elif kind == "INSULT":
                self.counts["insults"] += 1
            if actor is None or actor not in rows:
                continue          # the world itself, the player, or somebody already dead
            if kind == "PROMISE_KEPT":
                rows[actor]["promises"] += 1.0
                self.counts["promises_kept"] += 1
            elif kind == "BROKEN_PROMISE":
                rows[actor]["promises"] -= 1.0
                self.counts["promises_broken"] += 1
            elif kind == "HIT":
                self.counts["hits"] += 1
                target = ev.get("target")
                if target is not None and target not in self._before[actor]["provoked"]:
                    rows[actor]["violence"] -= 1.0
                    self.counts["unprovoked_hits"] += 1
                if self.conditioned and target in self._before[actor].get("defend", ()):
                    rows[actor]["duty"] += 1.0
                    self.counts["duties"] += 1
            elif kind == "HELP" and self.conditioned:
                target = ev.get("target")
                friend = target is None or (
                    w.agent(actor) is not None
                    and w.agent(actor).mind.rel(target)["trust"] > LOYAL_TRUST)
                if friend:
                    rows[actor]["duty"] += 1.0
                    self.counts["duties"] += 1

        if self.conditioned:
            # Remembered for the *next* tick: an IGNORE can only answer an insult already thrown.
            for ev in w.events:
                place = ev.get("place")
                if ev["type"] in PUBLIC_INSULTS and ev.get("target") is not None                         and place is not None and len(w.at(place)) > 2:
                    self._insulted[ev["target"]] = w.tick
            for aid, terms in rows.items():
                a = w.agent(aid)
                if a is None:
                    continue
                scale = self.scales(a)
                for k in terms:
                    if k in scale:
                        terms[k] *= scale[k]

        for terms in rows.values():
            self.steps += 1
            self.total += reward_of(terms, self.weights)
            for k, v in terms.items():
                self.totals[k] += v
        return rows

    def _grudge_held(self, agent):
        """0..1: the mean grudge this dwarf is carrying against the living. A *level*, not a
        delta, on purpose -- it is a standing cost of not letting go, charged every tick it
        lasts, which is the only shape "holding a grudge" has."""
        held, n = 0.0, 0
        for o in self.world.agents:
            if o.id == agent.id or not o.alive:
                continue
            held += agent.memories.grudge(self.world.tick, agent, o.id)
            n += 1
        return held / n if n else 0.0

    # -- reporting ---------------------------------------------------------

    def weighted_totals(self):
        """Per-term episode totals, already weighted, so that they sum to the reward."""
        return {k: self.weights.get(k, 0.0) * v for k, v in self.totals.items()}

    def report(self, **extra):
        """One flat dict: the totals, the counters and whatever the caller wants alongside."""
        row = dict(extra)
        row["steps"] = self.steps
        row["reward"] = round(self.total, 4)
        for k, v in self.weighted_totals().items():
            row[k] = round(v, 4)
        row.update(self.counts)
        return row


class EpisodeCsv:
    """One row per episode: the reward-term totals and the behaviour counters.

    The term columns are *weighted* totals, so they add up to the ``reward`` column.
    """

    FIELDS = ("policy", "scenario", "seed", "iteration", "ticks", "steps", "reward") \
        + TERM_NAMES + COUNT_NAMES

    def __init__(self, path, fields=None):
        self.path = path
        self.fields = list(fields or self.FIELDS)
        directory = os.path.dirname(os.path.abspath(path))
        if directory:
            os.makedirs(directory, exist_ok=True)
        self.fh = open(path, "w", encoding="utf-8", newline="")
        self.writer = csv.DictWriter(self.fh, fieldnames=self.fields, extrasaction="ignore")
        self.writer.writeheader()

    def write(self, row):
        out = {k: "" for k in self.fields}
        out.update({k: v for k, v in row.items() if k in out})
        self.writer.writerow(out)
        self.fh.flush()

    def close(self):
        self.fh.close()

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()
