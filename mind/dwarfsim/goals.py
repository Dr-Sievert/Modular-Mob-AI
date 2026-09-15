"""Persistent goals: what a dwarf is trying to do for longer than one tick.

Without these the arbitrator is memoryless between ticks and dwarves flip-flop -- insult, work,
insult, drink. A goal is adopted from state, decays on its own, is dropped when it is satisfied, and
while it is held it adds one bias term (``goal_bias``) to the candidates that serve it. Nothing here
picks an action; it only tilts the table.

    GET_RICH           greedy and poor
    AVENGE(target)     hatred plus a remembered harm
    PROTECT(target)    saw someone I trust get hurt
    REPAY(target)      they gave me something, or kept a promise to me
    BEFRIEND(target)   I like them and I am lonely
    KEEP_PEACE         the chief's, and only the chief's

The whole adoption table is :func:`review` and the whole bias table is :data:`GOAL_MATCH`, so both
are readable on one screen, like the event table.
"""

from .memory import HARM_KINDS
from .mind import NEUTRAL_REL
from .schema import GOAL_KINDS

#: Fraction of a goal's strength shed per tick.
GOAL_DECAY = 0.0045

#: Below this a goal is dropped.
GOAL_FLOOR = 0.08

#: A goal never gets stronger than this.
GOAL_CEIL = 1.0

#: How many wants a dwarf carries at once. Over this the weakest is let go, so a settlement
#: full of friends does not end up with every dwarf holding five standing BEFRIEND goals.
GOAL_CAP = 4

#: How often a dwarf reconsiders what it wants. Adoption is not free and it need not be per tick.
REVIEW_EVERY = 5


class Goal:
    """One want, held with a strength in 0..1."""

    __slots__ = ("kind", "target", "strength", "tick")

    def __init__(self, kind, target=None, strength=0.3, tick=0):
        self.kind = kind
        self.target = target
        self.strength = strength
        self.tick = tick

    def key(self):
        return (self.kind, -1 if self.target is None else self.target)

    def label(self, namer=None):
        if self.target is None:
            return self.kind
        who = namer(self.target) if namer else str(self.target)
        return "%s(%s)" % (self.kind, who)

    def snapshot(self):
        d = {"kind": self.kind, "strength": round(self.strength, 3), "since": self.tick}
        if self.target is not None:
            d["target"] = self.target
        return d


# ---------------------------------------------------------------------------
# The bias table: goal kind -> skill -> how well this candidate serves the goal.
# A number is multiplied by the goal's strength; "@" means the candidate's target must be the
# goal's target, "~" means it must NOT be, and "*" means the target does not matter.
# ---------------------------------------------------------------------------

GOAL_MATCH = {
    "AVENGE": {
        "ATTACK": ("@", 0.90), "RETORT": ("@", 0.60), "DEMAND_APOLOGY": ("@", 0.40),
        "COMPLAIN_TO": ("*", 0.30), "STEAL": ("@", 0.40), "REFUSE_APOLOGY": ("@", 0.50),
        "GOSSIP": ("~", 0.30), "PUNISH": ("@", 0.40),
        "APOLOGIZE": ("@", -0.90), "SOCIALIZE": ("@", -0.55), "IGNORE": ("@", -0.45),
        "ACCEPT": ("@", -0.50),
    },
    "PROTECT": {
        "ATTACK": ("~", 0.35), "COMPLAIN_TO": ("*", 0.30), "FIGHT_MONSTER": ("*", 0.45),
        "SOCIALIZE": ("@", 0.30), "FULFIL": ("@", 0.35),
        "AVOID": ("*", -0.40), "STEAL": ("@", -0.60),
    },
    "REPAY": {
        "FULFIL": ("@", 0.90), "ACCEPT": ("@", 0.80), "SOCIALIZE": ("@", 0.45),
        "APOLOGIZE": ("@", 0.30), "FIGHT_MONSTER": ("*", 0.20),
        "STEAL": ("@", -1.00), "ATTACK": ("@", -0.90), "REFUSE": ("@", -0.70),
    },
    "BEFRIEND": {
        "SOCIALIZE": ("@", 0.70), "GOSSIP": ("@", 0.45), "ACCEPT": ("@", 0.40),
        "APOLOGIZE": ("@", 0.35), "FULFIL": ("@", 0.30),
        "ATTACK": ("@", -1.00), "STEAL": ("@", -0.80), "RETORT": ("@", -0.60),
        "AVOID": ("@", -0.50),
    },
    "GET_RICH": {
        # Working is the obvious way to get rich; stealing is what a greedy dwarf does when
        # it already dislikes the owner, which the STEAL weights price separately.
        "WORK": ("*", 0.55), "STEAL": ("*", 0.20), "BARGAIN": ("*", 0.55),
        "FIGHT_MONSTER": ("*", 0.25), "ACCEPT": ("*", 0.20),
    },
    "KEEP_PEACE": {
        "PUNISH": ("*", 0.85), "DEMAND_APOLOGY": ("*", 0.30), "APOLOGIZE": ("*", 0.30),
        "SOCIALIZE": ("*", 0.20), "FIGHT_MONSTER": ("*", 0.30),
        "ATTACK": ("*", -1.20), "RETORT": ("*", -0.60), "STEAL": ("*", -0.80),
    },
}

assert set(GOAL_MATCH) <= set(GOAL_KINDS)


def bias(agent, cand):
    """``goal_bias`` for one candidate: every held goal's strength times how well it is served."""
    total = 0.0
    for g in agent.goals:
        row = GOAL_MATCH.get(g.kind)
        if not row:
            continue
        entry = row.get(cand.skill)
        if entry is None:
            continue
        scope, weight = entry
        target = cand.target
        if cand.skill in ("COMPLAIN_TO", "GOSSIP"):
            # These name a listener; what they are *about* is the thing the goal cares about.
            about = cand.detail.get("about")
            if about is not None:
                target = about
        if scope == "@" and target != g.target:
            continue
        if scope == "~" and (g.target is None or target == g.target):
            continue
        total += weight * g.strength
    return total


# ---------------------------------------------------------------------------
# Adoption and upkeep
# ---------------------------------------------------------------------------


def _find(agent, kind, target):
    for g in agent.goals:
        if g.kind == kind and g.target == target:
            return g
    return None


def adopt(agent, world, kind, target, want):
    """Raise (or start) a goal toward ``want``. Returns the goal if it was newly adopted."""
    g = _find(agent, kind, target)
    if g is None:
        if want < GOAL_FLOOR:
            return None
        g = Goal(kind, target, min(GOAL_CEIL, want), world.tick)
        agent.goals.append(g)
        agent.goals.sort(key=lambda x: x.key())
        return g
    if want > g.strength:
        g.strength = min(GOAL_CEIL, g.strength + (want - g.strength) * 0.5)
    return None


def review(agent, world):
    """Decay, drop what is satisfied, adopt what the state now calls for.

    Returns ``(adopted, dropped)``: two lists of goals, for the log's narration.
    """
    tick = world.tick
    dropped = []
    for g in list(agent.goals):
        g.strength *= (1.0 - GOAL_DECAY * REVIEW_EVERY)
        if g.strength < GOAL_FLOOR or _satisfied(agent, world, g):
            agent.goals.remove(g)
            dropped.append(g)

    before = {g.key() for g in agent.goals}
    book, m = agent.memories, agent.mind

    if m.traits["greed"] > 0.65 and agent.inv["gold"] < 4:
        adopt(agent, world, "GET_RICH", None,
              0.25 + 0.50 * m.traits["greed"] * (1.0 - agent.inv["gold"] / 4.0))

    if world.chief_id == agent.id:
        adopt(agent, world, "KEEP_PEACE", None, 0.85)

    for other in world.living():
        if other.id == agent.id:
            continue
        oid = other.id
        rel = m.rels.get(oid)
        if rel is None:
            continue
        grudge = book.grudge(tick, agent, oid)
        gratitude = book.gratitude(tick, agent, oid)
        if rel["hatred"] > 0.50 and grudge > 0.30:
            adopt(agent, world, "AVENGE", oid, min(1.0, rel["hatred"] * 0.6 + grudge * 0.4))
        if gratitude > 0.35:
            adopt(agent, world, "REPAY", oid, min(1.0, 0.20 + gratitude * 0.6))
        if rel["trust"] > 0.50 and rel["hatred"] < 0.20 and m.needs["social"] > 0.45:
            adopt(agent, world, "BEFRIEND", oid,
                  min(1.0, rel["trust"] * 0.5 * (0.4 + 0.8 * m.traits["sociability"])))

    # Witnessing harm done to someone I trust is what makes me want to stand over them.
    for mem in book.items:
        if mem.source != "SEEN" or mem.kind not in HARM_KINDS:
            continue
        if mem.target is None or mem.target == agent.id:
            continue
        if tick - mem.tick > 120:
            continue
        if m.likes(mem.target) > 0.30:
            adopt(agent, world, "PROTECT", mem.target,
                  min(1.0, 0.30 + m.likes(mem.target) * 0.6))

    if len(agent.goals) > GOAL_CAP:
        agent.goals.sort(key=lambda g: -g.strength)
        for g in agent.goals[GOAL_CAP:]:
            dropped.append(g)
        del agent.goals[GOAL_CAP:]
        agent.goals.sort(key=lambda g: g.key())

    adopted = [g for g in agent.goals if g.key() not in before]
    return adopted, dropped


def _satisfied(agent, world, goal):
    """Dropped because it worked, not because it faded."""
    m = agent.mind
    if goal.kind == "GET_RICH":
        return agent.inv["gold"] >= 14
    if goal.target is None:
        return False
    other = world.agent(goal.target)
    if other is None or not other.alive:
        return goal.kind in ("AVENGE", "PROTECT", "BEFRIEND", "REPAY")
    rel = m.rels.get(goal.target) or NEUTRAL_REL
    if goal.kind == "AVENGE":
        return rel["hatred"] < 0.18
    if goal.kind == "BEFRIEND":
        return rel["trust"] > 0.60
    if goal.kind == "REPAY":
        return agent.memories.gratitude(world.tick, agent, goal.target) < 0.12
    if goal.kind == "PROTECT":
        return m.likes(goal.target) < 0.10
    return False
