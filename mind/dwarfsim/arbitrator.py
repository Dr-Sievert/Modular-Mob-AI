"""One action selector for everything.

Every tick each living dwarf asks every skill what it could do, and this module scores the lot and
picks one. The score is a weighted sum of named terms, the names are fixed in
:mod:`dwarfsim.schema`, and every contribution is kept on the candidate so the log can say exactly
why the winner won. That breakdown is the point of the whole testbed.

v0 is the hand-written weight table below. The seam for replacing it is deliberate and narrow:

* :func:`observation_vector` -- what the dwarf knows, ``OBS_SIZE`` flat floats;
* :func:`candidate_features` -- what one proposal looks like, ``CAND_SIZE`` flat floats;
* :func:`score` -- ``score(observation, candidate_features) -> float``.

:func:`score` reproduces the table exactly (the table *is* a linear model over the candidate
features), so a tiny MLP can take its place without any other module noticing. Only the sampling
below stays.

**Aimed at, and about.** Two families of term read different people. ``trust_target``,
``respect_target``, ``hatred_target`` and ``fear_target`` are about whoever the candidate is *aimed
at* -- its ``target``. The memory-derived ones -- ``grudge_target``, ``gratitude_target``,
``reputation_target``, ``provoked_by_target`` -- are about whoever it is *about*, which is the same
person for an attack and a different one for a complaint or a piece of gossip.
"""

import math

from . import condition
from . import goals as goal_module
from . import regard, schema
from .schema import CAND_SIZE, N_SKILLS, OBS_SIZE, SKILL_INDEX, TERM_INDEX, TERM_NAMES

#: skill -> {term: weight}. Terms not listed weigh nothing, which is why a candidate's logged
#: breakdown is complete even though only the listed terms are ever computed.
WEIGHTS = {
    "WORK": {
        "base": 0.55, "supply_pressure": 1.15, "wealth_drive": 0.50, "request_pull": 1.00,
        "impairment": -1.60, "hurt": -0.35,
        "need_fatigue": -0.70, "need_hunger": -0.40, "need_thirst": -0.40,
        "fear": -0.55, "grief": -0.30, "anger": -0.15, "distance_cost": -0.85,
        "goal_bias": 0.50, "noise": 0.15,
    },
    "EAT": {
        "base": -0.40, "need_hunger": 3.20, "fear": -0.30, "noise": 0.15,
    },
    "DRINK": {
        "base": -0.40, "need_thirst": 3.00, "happiness": 0.10, "fear": -0.30, "noise": 0.15,
    },
    "REST": {
        "base": -0.40, "need_fatigue": 2.90, "hurt": 1.40, "monster_threat": -0.60,
        "fear": -0.60, "distance_cost": -0.40, "noise": 0.15,
    },
    "SOCIALIZE": {
        "base": -0.55, "need_social": 2.00, "sociability": 0.50, "happiness": 0.10,
        "trust_target": 0.25, "hatred_target": 0.40, "anger_at_target": 0.30,
        "gratitude_target": 0.20, "fear": -0.45, "grief": -0.40, "distance_cost": -0.60,
        "goal_bias": 0.50, "noise": 0.20,
    },
    "STEAL": {
        "base": -1.75, "greed": 1.90, "wealth_drive": 0.80, "need_hunger": 0.50,
        "impairment": -1.00, "hurt": -0.45,
        "trust_target": -0.90, "hatred_target": 0.90, "respect_target": -0.50,
        "grudge_target": 0.30, "reputation_target": -0.20, "expected_punishment": -1.10,
        "fear": -0.80, "goal_bias": 0.60, "noise": 0.25,
    },
    "ATTACK": {
        "base": -2.80, "anger_at_target": 2.40, "hatred_target": 2.00, "being_attacked": 1.60,
        "temper": 0.70, "bravery": 0.80, "grief": 0.15,
        "grudge_target": 0.35, "provoked_by_target": 0.30, "humiliation": 0.35,
        "expected_punishment": -1.70,
        "fear": -2.00, "hurt": -3.40, "impairment": -1.80, "trust_target": -0.50,
        "distance_cost": -0.50, "goal_bias": 0.70, "noise": 0.25,
    },
    "FLEE": {
        "base": -1.70, "fear": 2.60, "hurt": 2.40, "being_attacked": 1.30,
        "monster_threat": 1.00, "bravery": -1.20, "anger": -0.80, "noise": 0.20,
    },
    "APOLOGIZE": {
        "base": -1.20, "happiness": 0.80, "sociability": 0.50, "trust_target": 0.70,
        "hatred_target": -0.90, "anger": -1.20, "fear": 0.50, "fear_target": 0.45,
        "obligation_pressure": 1.90, "forgiveness": 0.45, "respect_target": 0.35,
        "pride": -0.70, "humiliation": -0.40, "grudge_target": -0.50,
        "goal_bias": 1.00, "noise": 0.20,
    },
    "FIGHT_MONSTER": {
        "base": -1.20, "monster_threat": 1.90, "bravery": 1.70, "anger": 0.40,
        "fear": -1.10, "hurt": -2.40, "impairment": -2.60,
        "distance_cost": -0.60, "goal_bias": 1.00, "noise": 0.25,
    },

    # -- the graded reactions to provocation --------------------------------------
    # Every one of these only exists while there is an unanswered harm in memory. Which of them
    # wins is the whole point: nothing orders them, the terms do.

    "IGNORE": {
        # Letting it go still costs the tick it is chosen in, which is why the base has to be
        # high enough to compete with an ordinary shift of work; below about 1.0 a dwarf
        # always finds something better to do and the reaction never appears at all.
        "base": 1.10, "forgiveness": 1.30, "fear": 0.45, "provoked_by_target": 0.40,
        "pride": -0.70, "temper": -0.55, "humiliation": -0.90, "anger": -0.50,
        "grudge_target": -0.40, "goal_bias": 1.00, "noise": 0.25,
    },
    "RETORT": {
        "base": -0.45, "temper": 0.95, "pride": 0.55, "provoked_by_target": 1.00,
        "anger_at_target": 0.80, "grudge_target": 0.35, "sociability": 0.35,
        "humiliation": 0.50, "fear": -0.85, "forgiveness": -0.55,
        "expected_punishment": -0.55, "trust_target": -0.25,
        "distance_cost": -0.60, "goal_bias": 1.00, "noise": 0.25,
    },
    "DEMAND_APOLOGY": {
        "base": 0.05, "pride": 1.50, "humiliation": 1.10, "publicity": 0.45,
        "respect_target": 0.55, "trust_target": 0.40, "bravery": 0.55,
        "provoked_by_target": 0.90, "chief_present": 0.55,
        "temper": -0.25, "fear": -0.45, "hatred_target": -0.45,
        "distance_cost": -0.60, "goal_bias": 1.00, "noise": 0.25,
    },
    "REFUSE_APOLOGY": {
        "base": -0.95, "obligation_pressure": 0.50, "pride": 1.00, "temper": 0.75,
        "hatred_target": 1.40, "grudge_target": 0.70, "anger": 0.50,
        "respect_target": -0.80, "trust_target": -0.50, "forgiveness": -0.60,
        "fear": -0.90, "expected_punishment": -0.60, "goal_bias": 1.00, "noise": 0.25,
    },
    "COMPLAIN_TO": {
        "base": 0.00, "trust_target": 1.30, "respect_target": 0.60, "grudge_target": 0.90,
        "provoked_by_target": 0.80, "publicity": 0.35, "fear": 0.45, "sociability": 0.45,
        "need_social": 0.40, "chief_present": 0.40, "hurt": 0.35,
        "bravery": -0.35, "pride": -0.25, "distance_cost": -0.70,
        "goal_bias": 1.00, "noise": 0.25,
    },
    "AVOID": {
        "base": -0.15, "fear": 1.60, "hurt": 1.90, "provoked_by_target": 1.10,
        "grudge_target": 0.45, "hatred_target": 0.35,
        "bravery": -1.10, "temper": -0.70, "sociability": -0.40, "pride": -0.35,
        "forgiveness": -0.30, "goal_bias": 1.00, "noise": 0.25,
    },

    # -- gossip, bargaining, authority --------------------------------------------

    "GOSSIP": {
        "base": -1.60, "need_social": 1.60, "sociability": 1.10, "gossip_value": 1.30,
        "trust_target": 0.50, "grudge_target": 0.40, "happiness": 0.10,
        "fear": -0.40, "grief": -0.35, "distance_cost": -0.70,
        "goal_bias": 0.60, "noise": 0.22,
    },
    "ACCEPT": {
        "base": -0.35, "trust_target": 1.30, "respect_target": 0.90, "fear_target": 0.70,
        "payment_offered": 1.10, "greed": 0.35, "gratitude_target": 1.20,
        "obligation_pressure": 0.90,
        "ask_cost": -1.60, "hatred_target": -0.90, "grudge_target": -0.55,
        "goal_bias": 1.00, "noise": 0.22,
    },
    "REFUSE": {
        "base": 0.35, "ask_cost": 1.30, "hatred_target": 0.90, "grudge_target": 0.55,
        "temper": 0.30, "obligation_pressure": 0.70,
        "trust_target": -1.20, "respect_target": -0.80, "fear_target": -0.80,
        "payment_offered": -0.90, "goal_bias": 1.00, "noise": 0.22,
    },
    "BARGAIN": {
        "base": -0.20, "greed": 1.50, "ask_cost": 0.90, "wealth_drive": 0.70,
        "obligation_pressure": 0.75, "gratitude_target": -0.80,
        "trust_target": -0.35, "respect_target": -0.25, "fear_target": -0.55,
        "payment_offered": -0.40, "goal_bias": 1.00, "noise": 0.22,
    },
    "FULFIL": {
        "base": -0.30, "obligation_pressure": 1.70, "trust_target": 0.70,
        "respect_target": 0.45, "gratitude_target": 0.50,
        "ask_cost": -0.90, "fear": -0.35, "distance_cost": -0.55,
        "goal_bias": 1.00, "noise": 0.20,
    },
    "PUNISH": {
        "base": -1.20, "punish_pressure": 2.20, "grudge_target": 0.40, "bravery": 0.40,
        "respect_target": -0.40, "trust_target": -0.50, "fear": -0.70,
        "distance_cost": -0.60, "goal_bias": 1.00, "noise": 0.22,
    },
}

#: Softmax temperature. Small: mostly the best candidate, with the occasional surprise.
DEFAULT_TEMPERATURE = 0.25

#: How long a blow keeps reading as "I am under attack".
HIT_WINDOW = 12

#: Terms only the proposing skill can know the value of: which obligation, which memory, which
#: complaint this candidate is about. They arrive on ``cand.hints``.
HINT_TERMS = frozenset((
    "supply_pressure", "obligation_pressure", "ask_cost", "payment_offered",
    "gossip_value", "punish_pressure", "humiliation",
))


class TermContext:
    """Per-agent, per-tick scratch: the terms that do not depend on which candidate it is."""

    __slots__ = ("agent", "world", "flat", "rng", "_noise", "_prov", "_rep")

    def __init__(self, agent, world):
        self.agent = agent
        self.world = world
        self.rng = world.rng
        self._noise = {}
        m = agent.mind
        e, n, t = m.emotions, m.needs, m.traits
        age = world.tick - agent.last_hit_tick
        hit = max(0.0, 1.0 - age / HIT_WINDOW) if age <= HIT_WINDOW else 0.0

        # Who has provoked me lately and how loudly, from episodic memory.
        self._prov = {}
        forgiveness = t["forgiveness"]
        for mem in agent.provocations:
            if mem.answered:
                continue
            s = mem.salience(world.tick, forgiveness)
            prev = self._prov.get(mem.actor)
            if prev is None or s > prev[0]:
                self._prov[mem.actor] = (s, mem.witnesses)
        self._rep = world.reputation()

        here = world.at(agent.place)
        room = max(0, len(here) - 1)
        chief = world.chief()
        chief_here = 1.0 if (chief is not None and chief.alive and chief.id != agent.id
                             and chief.place == agent.place) else 0.0
        publicity = min(1.0, room / 3.0)
        deterrence = 0.0
        if chief is not None and chief.alive and chief.id != agent.id:
            # A settlement with a chief is a settlement where word gets back, so the deterrent is
            # not zero when he is in another room -- it is just much smaller.
            respect = max(0.0, m.rel(chief.id)["respect"])
            watching = 1.0 if chief_here else 0.30
            deterrence = watching * (0.35 + 0.65 * respect) * (0.40 + 0.60 * publicity)

        self.flat = {
            "base": 1.0,
            "need_hunger": n["hunger"], "need_thirst": n["thirst"],
            "need_fatigue": n["fatigue"], "need_social": n["social"],
            # Cracked ribs are a flinch, not a mood: the *term* carries them, the emotion does
            # not, so a dwarf with broken ribs acts frightened without becoming a coward.
            "anger": e["anger"], "fear": min(1.0, e["fear"] + agent.condition.fear_bonus()),
            "happiness": e["happiness"], "grief": e["grief"],
            "bravery": t["bravery"], "greed": t["greed"],
            "temper": t["temper"], "sociability": t["sociability"],
            "pride": t["pride"], "forgiveness": t["forgiveness"],
            "wealth_drive": t["greed"] * (1.0 - min(1.0, agent.inv["gold"] / 20.0)),
            # How close to dying, or how broken, whichever is worse: a dwarf at full health
            # with a shattered leg is hurt, and the old health-only term said he was fine.
            "hurt": condition.hurt_term(agent),
            "chief_present": chief_here,
            "expected_punishment": deterrence,
            "_hit": hit,
            "_room": publicity,
        }

    # -- one term, one candidate -------------------------------------------

    def value(self, name, cand):
        """The raw value of one term for one candidate."""
        v = self.flat.get(name)
        if v is not None:
            return v
        agent, world = self.agent, self.world

        if name == "noise":
            key = id(cand)
            got = self._noise.get(key)
            if got is None:
                got = self.rng.random()
                self._noise[key] = got
            return got
        if name in HINT_TERMS:
            return cand.hints.get(name, 0.0)
        if name == "goal_bias":
            return goal_module.bias(agent, cand)
        if name == "publicity":
            return cand.hints.get("publicity", self.flat["_room"])
        if name == "distance_cost":
            return 1.0 if (cand.place is not None and cand.place != agent.place) else 0.0
        if name == "impairment":
            return agent.condition.impairment(
                two_hands=bool(cand.hints.get("needs_two_hands")),
                legs=bool(cand.hints.get("needs_legs")),
                moving=cand.place is not None and cand.place != agent.place)
        if name == "monster_threat":
            return 1.0 if world.monster_at(cand.place or agent.place) is not None else 0.0
        if name == "being_attacked":
            hit = self.flat["_hit"]
            if hit == 0.0 or cand.target is None:
                return hit
            return hit if agent.last_hit_by == cand.target else hit * 0.25
        if name == "request_pull":
            req = agent.request
            if not req or world.tick > req["until"]:
                return 0.0
            want = req["place"]
            if want is not None and cand.place != want:
                return 0.0
            if want is None and cand.skill != "WORK":
                return 0.0
            trust = agent.mind.rel(req["from"])["trust"]
            return req["urgency"] * max(0.0, 0.5 + 0.5 * trust)

        # Terms about whoever the candidate is *about* -- a third party for gossip and complaints.
        about = cand.about()
        if about is not None:
            if name == "grudge_target":
                return agent.memories.grudge(world.tick, agent, about)
            if name == "gratitude_target":
                return agent.memories.gratitude(world.tick, agent, about)
            if name == "provoked_by_target":
                return self._prov.get(about, (0.0, 0))[0]
            if name == "reputation_target":
                rep = self._rep.get(str(about))
                if rep is None:
                    return 0.0
                return max(-1.0, min(1.0, rep[0] * 0.7 + rep[1] * 0.3 - rep[2]))

        # Everything left is about whoever the candidate is aimed at.
        if cand.target is None:
            return 0.0
        rel = agent.mind.rel(cand.target)
        if name == "hatred_target":
            return rel["hatred"]
        if name == "trust_target":
            # Trust proper, plus what warm words have bought lately. Warmth is capped, decays
            # back and is deliberately not part of the stored relationship: it is how you feel
            # about somebody this afternoon, not what you believe about them.
            # See :mod:`dwarfsim.regard`.
            return regard.felt_trust(agent, cand.target)
        if name == "respect_target":
            return rel["respect"]
        if name == "fear_target":
            f = self.flat["fear"] * (0.40 + 0.60 * max(0.0, rel["respect"]))
            if agent.last_hit_by == cand.target and world.tick - agent.last_hit_tick <= 25:
                f += 0.35
            return min(1.0, f)
        if name == "anger_at_target":
            blame = 0.30 + 0.50 * rel["hatred"]
            if agent.last_hit_by == cand.target and world.tick - agent.last_hit_tick <= 25:
                blame += 0.45
            return self.flat["anger"] * min(1.0, blame)
        return 0.0


# ---------------------------------------------------------------------------
# The flat vectors: the seam a learned scorer plugs into
# ---------------------------------------------------------------------------


def focus_ids(agent, world):
    """The dwarves whose relationships fill the mind vector's focus slots, most salient first."""
    scored = []
    for o in world.others(agent):
        r = agent.mind.rels.get(o.id)
        s = 0.0
        if r is not None:
            s = r["hatred"] * 2.0 + abs(r["trust"]) + abs(r["respect"]) * 0.5
        if o.place == agent.place and not o.external:
            s += 1.5
        if agent.last_hit_by == o.id and world.tick - agent.last_hit_tick <= HIT_WINDOW:
            s += 2.0
        scored.append((-s, str(o.id), o.id))
    scored.sort()
    return [oid for _, _, oid in scored[:schema.FOCUS_SLOTS]]


def observation_vector(agent, world):
    """Flat floats, laid out by :mod:`dwarfsim.schema`. One row per agent per tick."""
    v = agent.mind.to_vector(agent, focus_ids(agent, world), world.tick)
    v.extend([0.0] * (OBS_SIZE - len(v)))
    v[schema.OBS_PLACE + schema.PLACE_INDEX[agent.place]] = 1.0
    here = world.at(agent.place)
    v[schema.OBS_CROWD] = min(1.0, len(here) / 6.0)
    m = world.monster_at(agent.place)
    v[schema.OBS_MONSTER] = 1.0 if m else 0.0
    v[schema.OBS_MONSTER_HP] = (m["hp"] / m["max_hp"]) if m else 0.0
    age = world.tick - agent.last_hit_tick
    v[schema.OBS_UNDER_ATTACK] = 1.0 if age <= HIT_WINDOW else 0.0
    v[schema.OBS_HIT_AGE] = min(1.0, age / 50.0)
    v[schema.OBS_ALIVE_FRACTION] = len(world.living()) / max(1, world.start_count)
    v[schema.OBS_CLOCK] = (world.tick % 200) / 200.0
    for g in agent.goals:
        i = schema.GOAL_INDEX.get(g.kind)
        if i is not None:
            v[schema.OBS_GOALS + i] = max(v[schema.OBS_GOALS + i], g.strength)
    owed, taken, _ = world.obligations_for(agent)
    v[schema.OBS_OBLIGATIONS] = min(1.0, (len(owed) + len(taken)) / 3.0)
    v[schema.OBS_OBLIGATIONS + 1] = min(1.0, sum(
        1 for ob in world._open_obl if ob.frm == agent.id) / 3.0)
    book = agent.memories
    v[schema.OBS_MEMORY] = min(1.0, len(book) / float(schema.MEMORY_CAP))
    if len(book):
        f = agent.mind.traits["forgiveness"]
        v[schema.OBS_MEMORY + 1] = min(1.0, sum(
            mem.salience(world.tick, f) for mem in book) / len(book))
    chief = world.chief()
    v[schema.OBS_IS_CHIEF] = 1.0 if (chief is not None and chief.id == agent.id) else 0.0
    v[schema.OBS_CHIEF_HERE] = 1.0 if (chief is not None and chief.alive
                                       and chief.place == agent.place) else 0.0
    block = agent.condition.block()
    v[schema.OBS_CONDITION:schema.OBS_CONDITION + schema.CONDITION_SIZE] = block
    return v


def candidate_features(ctx, cand):
    """Flat floats for one proposal: the skill one-hot, then every term's raw value."""
    v = [0.0] * CAND_SIZE
    v[schema.CAND_SKILL + SKILL_INDEX[cand.skill]] = 1.0
    for i, name in enumerate(TERM_NAMES):
        v[schema.CAND_TERMS + i] = ctx.value(name, cand)
    return v


def score(observation, features):
    """``score(observation, candidate_features) -> float``.

    The v0 scorer ignores the observation: everything it needs is already in the candidate's term
    values, which is what makes the hand table and a learned scorer interchangeable. A trained
    model reads both.
    """
    skill_one_hot = features[schema.CAND_SKILL:schema.CAND_SKILL + N_SKILLS]
    total = 0.0
    for si, on in enumerate(skill_one_hot):
        if not on:
            continue
        for term, w in WEIGHTS[schema.SKILL_NAMES[si]].items():
            total += w * features[schema.CAND_TERMS + TERM_INDEX[term]] * on
    return total


# ---------------------------------------------------------------------------
# Deciding
# ---------------------------------------------------------------------------


def gather(agent, world):
    """Every candidate from every skill, in a stable order, minus what a broken bone forbids.

    Each skill declares what a body has to be able to do to do it (``needs_two_hands``,
    ``needs_legs``); a candidate may override either for itself, which is how buying ale at the
    tavern stays available to a dwarf who cannot swing a pick. Past
    :data:`dwarfsim.condition.ARM_BLOCK` or :data:`~dwarfsim.condition.LEG_BLOCK` the candidate
    is not proposed at all -- the decision record names the skills that were dropped, so the
    inspector does not show a dwarf mysteriously never choosing to mine. Short of that, the
    ``impairment`` term prices being hampered rather than stopped.
    """
    from .skills import SKILLS
    cond = agent.condition
    no_hands = cond.blocks_two_hands()
    no_legs = cond.blocks_legs()
    cands = []
    for skill in SKILLS:
        for c in skill.propose(agent, world):
            hands = c.hints.setdefault("needs_two_hands", 1.0 if skill.needs_two_hands else 0.0)
            legs = c.hints.setdefault("needs_legs", 1.0 if skill.needs_legs else 0.0)
            if (hands and no_hands) or (legs and no_legs):
                continue
            cands.append(c)
    cands.sort(key=lambda c: c.key())
    return cands


def decide(agent, world, temperature=None, ctx=None, scorer=None, collect=None):
    """Score every candidate, sample one, and return ``(chosen, all candidates)``.

    Every candidate comes back with ``terms`` (raw values) and ``contrib`` (weight x value) filled
    in, which is what the log writes out. Pass ``ctx`` to reuse a context -- it caches one noise
    draw per candidate, so only the same context reproduces the same scores.

    Two optional seams, both used by :mod:`dwarfsim.learn`:

    * ``scorer`` -- anything with ``score_all(observation, [features, ...]) -> sequence of float``
      (or just ``score(observation, features)``) takes the weight table's place. The breakdown
      then carries a single term named ``learned``, so the viewer still renders it.
    * ``collect`` -- a list that one ``(observation, features, teacher scores, chosen index)``
      tuple is appended to per decision. The teacher scores are the weight table's, whichever
      scorer actually chose.

    Either seam makes the full candidate feature vectors, which the table path never needs. That
    costs time but not randomness: :func:`candidate_features` draws exactly the one ``noise`` value
    per candidate that the table path draws, in the same order, so a collected run and a plain run
    of the same seed are the same run.
    """
    cands = gather(agent, world)
    if not cands:
        return None, []
    if ctx is None:
        ctx = TermContext(agent, world)
    if scorer is None and collect is None:
        for c in cands:
            weights = WEIGHTS[c.skill]
            total = 0.0
            terms, contrib = {}, {}
            for term, w in weights.items():
                val = ctx.value(term, c)
                if val == 0.0:
                    continue
                terms[term] = val
                part = w * val
                contrib[term] = part
                total += part
            c.terms = terms
            c.contrib = contrib
            c.score = total
        t = _temperature(agent, temperature)
        return _sample(cands, world.rng, t), cands

    obs = observation_vector(agent, world)
    feats = [candidate_features(ctx, c) for c in cands]
    teacher = None
    if scorer is None or collect is not None:
        teacher = _table_from_features(cands, feats)
    if scorer is not None:
        run = getattr(scorer, "score_all", None)
        learned = run(obs, feats) if run is not None else [scorer.score(obs, f) for f in feats]
        for c, s in zip(cands, learned):
            s = float(s)
            c.score = s
            c.terms = {"learned": s}
            c.contrib = {"learned": s}
    t = _temperature(agent, temperature)
    chosen = _sample(cands, world.rng, t)
    if collect is not None:
        collect.append((obs, feats, teacher, cands.index(chosen)))
    return chosen, cands


def _table_from_features(cands, feats):
    """Fill ``terms``/``contrib``/``score`` from already-computed features, and return the scores.

    Reads the same numbers :func:`decide` would read off the context, so the result is identical
    to the table path -- it just does not pay for the term lookups twice.
    """
    out = []
    for c, f in zip(cands, feats):
        total = 0.0
        terms, contrib = {}, {}
        for term, w in WEIGHTS[c.skill].items():
            val = f[schema.CAND_TERMS + TERM_INDEX[term]]
            if val == 0.0:
                continue
            terms[term] = val
            part = w * val
            contrib[term] = part
            total += part
        c.terms = terms
        c.contrib = contrib
        c.score = total
        out.append(total)
    return out


def _temperature(agent, temperature):
    """The softmax temperature for this dwarf, this tick.

    A concussion is the one injury that does not change what a dwarf *can* do, only how
    reliably they choose it: it widens the softmax, so a cracked head picks the second-best
    option a good deal more often. It wears off as the injury heals.
    """
    t = DEFAULT_TEMPERATURE if temperature is None else temperature
    return t * (1.0 + agent.condition.noise())


def _sample(cands, rng, temperature):
    best = max(c.score for c in cands)
    weights = [math.exp((c.score - best) / temperature) for c in cands]
    total = sum(weights)
    draw = rng.random() * total
    acc = 0.0
    for c, w in zip(cands, weights):
        acc += w
        if draw <= acc:
            return c
    return cands[-1]


def explain(cands, chosen, top=5):
    """The log's ``decisions`` payload: the winner, then the best few with their breakdowns."""
    ranked = sorted(cands, key=lambda c: (-c.score, c.key()))[:top]
    if chosen not in ranked:
        ranked.append(chosen)   # the occasional surprise the softmax picked from further down
    out = {"chosen": chosen.label(), "score": chosen.score, "top": []}
    for c in ranked:
        # skill, place and target are all readable off the label, so they are not written twice.
        entry = {"action": c.label(), "score": c.score, "terms": dict(c.contrib)}
        if c is chosen:
            entry["won"] = True
        out["top"].append(entry)
    return out
