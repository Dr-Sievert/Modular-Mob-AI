"""Injuries: what a blow actually does to a dwarf.

Before this module a fight was arithmetic on one number. Twenty health, a blow took two of it,
and the tenth blow was a funeral -- so a tavern brawl between two dwarves who had had a drink and
a disagreement ended with one of them dead about as often as not, which is not what a brawl is.

Health stays, and it still means *how close to dying*. What changed is what a blow spends: an
unarmed blow mostly produces an **injury** and very little health, and it is weapons, monsters and
hitting somebody who is already broken that kill. A fist fight ends in a black eye.

One dwarf's :class:`Condition` is a set of injuries, each with a ``kind``, a ``severity`` 0..1 and
a healing rate. The nine kinds, mild to severe:

===============  ==========================================================================
``bruised``      sore, and nothing else
``cut``          bleeds a little, stings
``black_eye``    you cannot see out of it: aim suffers
``sprained_hand``half the work, half the weapon
``broken_arm``   no two-handed work and no weapon skill at all: no mining, no forging, no bow
``broken_leg``   every walk between places takes longer, fleeing barely works, no monsters
``concussion``   the decisions come out noisier and less of what happens is remembered
``cracked_ribs`` every breath is fear, and there is no force behind a swing
``bleeding``     health drains until it is rested or eaten off
===============  ==========================================================================

What each one *does* is one table, :data:`EFFECTS`, and nothing outside it decides what an injury
means. Each row is the effect at severity 1.0 and scales linearly down to nothing:

===========  ===========================================================================
``pain``     how much this contributes to the ``hurt`` term (summed, then softened)
``work``     added to the work multiplier (``-0.8`` means a fifth of the output)
``weapon``   added to the weapon-quality multiplier
``attack``   added to the force multiplier of a blow
``two_hands``blocks a skill that declares ``needs_two_hands`` once past ``ARM_BLOCK``
``legs``     blocks a skill that declares ``needs_legs`` once past ``LEG_BLOCK``
``move``     chance per tick that a walk between places gets nowhere
``noise``    added to the arbitrator's softmax temperature, as a fraction
``recall``   added to the multiplier on a new memory's intensity
``fear``     added to the ``fear`` term (not to the emotion: it is a flinch, not a mood)
``drain``    health lost per tick
===========  ===========================================================================

**Strength** is not a new trait. It is read off the dwarf: mostly ``bravery`` (build and the
willingness to put weight behind a swing), partly how rested they are, and then whatever their
injuries leave of it. Keeping it derived is what leaves the mind vector's trait block at six.

Everything here draws from ``world.rng``, so a brawl replays from the seed.
"""

import math

from .schema import MAX_HEALTH

#: Mild to severe. The order is stable: the observation block and the docs read it.
INJURY_KINDS = ("bruised", "cut", "black_eye", "sprained_hand", "broken_arm",
                "broken_leg", "concussion", "cracked_ribs", "bleeding")

#: Per kind: how much severity heals per tick on its own, and the phrase the story uses.
#: A bruise is gone in a couple of days, a broken leg takes most of a season.
HEALING = {
    "bruised": 0.0200,
    "cut": 0.0120,
    "black_eye": 0.0100,
    "sprained_hand": 0.0070,
    "bleeding": 0.0060,
    "concussion": 0.0050,
    "cracked_ribs": 0.0040,
    "broken_arm": 0.0022,
    "broken_leg": 0.0020,
}

#: The one table. Every consequence of being injured is a row here and nowhere else.
EFFECTS = {
    "bruised":       {"pain": 0.10},
    "cut":           {"pain": 0.18, "work": -0.05, "drain": 0.004},
    "black_eye":     {"pain": 0.15, "weapon": -0.10, "noise": 0.10},
    "sprained_hand": {"pain": 0.30, "work": -0.50, "weapon": -0.50},
    "broken_arm":    {"pain": 0.60, "work": -0.80, "weapon": -0.80, "two_hands": 1.0},
    "broken_leg":    {"pain": 0.60, "work": -0.35, "legs": 1.0, "move": 0.75},
    "concussion":    {"pain": 0.45, "work": -0.30, "noise": 1.00, "recall": -0.55},
    "cracked_ribs":  {"pain": 0.50, "work": -0.30, "attack": -0.45, "fear": 0.30},
    "bleeding":      {"pain": 0.35, "attack": -0.15, "drain": 0.030},
}

#: What the story calls each one, and what a dwarf mentions out loud.
PHRASE = {
    "bruised": ("bruises", "a few bruises"),
    "cut": ("a cut", "this cut"),
    "black_eye": ("a black eye", "my eye"),
    "sprained_hand": ("a sprained hand", "my hand"),
    "broken_arm": ("a broken arm", "my arm"),
    "broken_leg": ("a broken leg", "my leg"),
    "concussion": ("a cracked head", "my head"),
    "cracked_ribs": ("cracked ribs", "my ribs"),
    "bleeding": ("a bleeding wound", "this bleeding"),
}

#: Past this much of a broken arm, a skill that declares ``needs_two_hands`` is not offered.
ARM_BLOCK = 0.25
#: And past this much of a broken leg, one that declares ``needs_legs``.
LEG_BLOCK = 0.30

#: A weapon at or above this quality is a weapon; below it, whatever is on the belt is not
#: worth drawing and the dwarf is swinging fists.
ARMED_AT = 0.25

#: And below this much hatred nobody draws it at all. This is the line between a brawl and a
#: killing: a dwarf does not put an edge through somebody over a spilt ale, and almost every
#: blow struck in the settlement is therefore a fist. Steel comes out for a real enemy.
DRAW_AT = 0.45

#: How much severity one turn of REST and one meal take off every injury, and off bleeding in
#: particular -- lying still and eating is the only thing that stops a wound bleeding.
REST_MEND = 0.020
FOOD_MEND = 0.012
BLEED_MEND = 4.0

#: An injury is gone below this.
GONE = 0.02

#: Health an injury of severity 1.0 is worth when a grudge is weighed, relative to a health point.
#: A broken arm is remembered as a broken arm however little blood it cost.
GRUDGE_PER_SEVERITY = 2.4


def _clamp01(x):
    return 0.0 if x < 0.0 else (1.0 if x > 1.0 else x)


# ---------------------------------------------------------------------------
# Who is swinging
# ---------------------------------------------------------------------------


def strength(agent):
    """How much force this dwarf can put behind a blow, 0..1.

    Derived, not a new trait: ``bravery`` is the build and the willingness to lean into it,
    being rested is the rest of it, and the condition takes its cut. A tired dwarf with cracked
    ribs hits like a child.
    """
    t = agent.mind.traits
    rested = 1.0 - agent.mind.needs["fatigue"]
    base = 0.25 + 0.50 * t["bravery"] + 0.25 * rested
    return _clamp01(base) * agent.condition.attack_mult()


def weapon_quality(agent):
    """The edge actually available: what is carried, times what the hands can hold."""
    return _clamp01(agent.inv["weapon"] * agent.condition.weapon_mult())


# ---------------------------------------------------------------------------
# One injury, and the set of them
# ---------------------------------------------------------------------------


class Injury:
    __slots__ = ("kind", "severity", "tick", "by")

    def __init__(self, kind, severity, tick=0, by=None):
        self.kind = kind
        self.severity = float(severity)
        self.tick = int(tick)
        self.by = by                   # who caused it, so the grudge knows whose it is

    def snapshot(self):
        d = {"kind": self.kind, "severity": round(self.severity, 3), "tick": self.tick}
        if self.by is not None:
            d["by"] = self.by
        return d

    def __repr__(self):
        return "<%s %.2f>" % (self.kind, self.severity)


class Condition:
    """Every injury one dwarf is carrying, and what they add up to.

    The accessors are the only way anything reads an injury: nothing outside this class knows
    that a broken arm is what stops the mining.
    """

    __slots__ = ("injuries",)

    def __init__(self):
        self.injuries = []

    def __len__(self):
        return len(self.injuries)

    def __iter__(self):
        return iter(self.injuries)

    def __bool__(self):
        return bool(self.injuries)

    # -- writing ------------------------------------------------------------

    def add(self, kind, severity, tick=0, by=None):
        """Take one injury. A second of the same kind deepens the first rather than stacking."""
        severity = _clamp01(severity)
        if severity < GONE:
            return None
        for inj in self.injuries:
            if inj.kind == kind:
                # Hitting a broken arm again makes it worse, but two breaks are not two arms.
                inj.severity = _clamp01(inj.severity + severity * (1.0 - 0.5 * inj.severity))
                inj.tick = tick
                inj.by = by if by is not None else inj.by
                return inj
        inj = Injury(kind, severity, tick, by)
        self.injuries.append(inj)
        self.injuries.sort(key=lambda i: INJURY_KINDS.index(i.kind))
        return inj

    def mend(self, amount, bleed_scale=1.0):
        """Rest or food. Returns the kinds that finished healing."""
        healed = []
        for inj in list(self.injuries):
            take = amount * (bleed_scale if inj.kind == "bleeding" else 1.0)
            inj.severity -= take
            if inj.severity < GONE:
                self.injuries.remove(inj)
                healed.append(inj.kind)
        return healed

    def tick_heal(self, scale=1.0):
        """One tick of the body doing its own work. Returns the kinds that finished healing."""
        healed = []
        for inj in list(self.injuries):
            inj.severity -= HEALING[inj.kind] * scale
            if inj.severity < GONE:
                self.injuries.remove(inj)
                healed.append(inj.kind)
        return healed

    # -- reading ------------------------------------------------------------

    def severity(self, kind):
        for inj in self.injuries:
            if inj.kind == kind:
                return inj.severity
        return 0.0

    def _sum(self, field):
        total = 0.0
        for inj in self.injuries:
            got = EFFECTS[inj.kind].get(field)
            if got:
                total += got * inj.severity
        return total

    def pain(self):
        """0..1, the whole of it. Summed then softened, so ten bruises are not a broken leg."""
        x = self._sum("pain")
        return x / (x + 1.0)

    def worst(self):
        """The severity of the worst single injury, 0..1."""
        return max((i.severity for i in self.injuries), default=0.0)

    def work_mult(self):
        return max(0.10, 1.0 + self._sum("work"))

    def weapon_mult(self):
        return max(0.05, 1.0 + self._sum("weapon"))

    def attack_mult(self):
        return max(0.20, 1.0 + self._sum("attack"))

    def move_penalty(self):
        """Chance that a walk between places gets nowhere this tick."""
        return min(0.85, self._sum("move"))

    def noise(self):
        """Extra softmax temperature, as a fraction of the base."""
        return min(2.0, self._sum("noise"))

    def recall_mult(self):
        """What a new memory's intensity is multiplied by. A concussed dwarf takes less in."""
        return max(0.25, 1.0 + self._sum("recall"))

    def fear_bonus(self):
        return min(0.6, self._sum("fear"))

    def drain(self):
        """Health lost per tick to open wounds."""
        return self._sum("drain")

    def blocks_two_hands(self):
        return self.severity("broken_arm") >= ARM_BLOCK

    def blocks_legs(self):
        return self.severity("broken_leg") >= LEG_BLOCK

    def impairment(self, two_hands=False, legs=False, moving=False):
        """0..1: how much this candidate in particular is hampered by what is broken.

        The arbitrator's ``impairment`` term. It is not the same as ``pain``: a broken leg makes
        walking to the mine hopeless and saying sorry no harder at all.
        """
        worst = 0.0
        if two_hands:
            worst = max(worst, 1.0 - self.work_mult(), self.severity("broken_arm"))
        if legs or moving:
            worst = max(worst, self.severity("broken_leg"))
        if moving:
            worst = max(worst, self.move_penalty())
        return min(1.0, worst)

    # -- for the vector, the panels and the log -----------------------------

    def block(self):
        """The compact injury block of the observation. See :mod:`dwarfsim.schema`."""
        return [
            self.pain(),
            self.worst(),
            min(1.0, len(self.injuries) / 4.0),
            self.severity("broken_arm"),
            self.severity("broken_leg"),
            self.severity("concussion"),
            self.severity("bleeding"),
            self.severity("cracked_ribs"),
        ]

    def snapshot(self):
        return [i.snapshot() for i in self.injuries]

    def describe(self):
        """"a broken arm and a few bruises" -- worst first, for the story and the panels."""
        if not self.injuries:
            return "unhurt"
        ranked = sorted(self.injuries, key=lambda i: -i.severity)
        words = [PHRASE[i.kind][0] for i in ranked[:3]]
        if len(words) == 1:
            return words[0]
        return ", ".join(words[:-1]) + " and " + words[-1]

    def complaint(self):
        """"mind the arm" -- the thing a hurt dwarf actually says, or ``None``."""
        if not self.injuries:
            return None
        worst = max(self.injuries, key=lambda i: i.severity)
        return PHRASE[worst.kind][1]

    def limps(self):
        return self.severity("broken_leg") > 0.1


# ---------------------------------------------------------------------------
# Resolving a blow
# ---------------------------------------------------------------------------
#
# Three profiles, because the same fist does not land like the same axe. Each is a list of
# (kind or None, weight); the roll walks it once. Unarmed is mostly nothing and bruises, which
# is the whole point: "no injury, just bruised" has to be the commonest thing that happens.

UNARMED_TABLE = (
    (None, 35.0), ("bruised", 33.0), ("black_eye", 10.0), ("cut", 7.0),
    ("sprained_hand", 6.0), ("cracked_ribs", 4.0), ("concussion", 2.5),
    ("broken_arm", 1.3), ("broken_leg", 1.2), ("bleeding", 1.0),
)

ARMED_TABLE = (
    (None, 8.0), ("cut", 26.0), ("bleeding", 18.0), ("cracked_ribs", 12.0),
    ("bruised", 10.0), ("broken_arm", 8.0), ("broken_leg", 7.0), ("concussion", 6.0),
    ("sprained_hand", 3.0), ("black_eye", 2.0),
)

MONSTER_TABLE = (
    (None, 10.0), ("cut", 24.0), ("bleeding", 24.0), ("bruised", 12.0),
    ("cracked_ribs", 11.0), ("broken_arm", 7.0), ("broken_leg", 6.0), ("concussion", 6.0),
)

PROFILES = {"unarmed": UNARMED_TABLE, "armed": ARMED_TABLE, "monster": MONSTER_TABLE}

#: Health a blow costs before force, per profile: a fist is a bruise, an axe is a wound.
HEALTH_BASE = {"unarmed": 0.25, "armed": 0.80, "monster": 0.50}
HEALTH_FORCE = {"unarmed": 0.75, "armed": 2.80, "monster": 1.40}

#: How much being already broken makes the next blow worse. This is what kills, eventually:
#: not the tenth punch, but the tenth punch on somebody who cannot lift an arm any more.
PAIN_ESCALATION = 1.20

#: Chance of taking any injury at all, before the profile's own "nothing happened" weight.
INJURE_BASE = {"unarmed": 0.62, "armed": 0.95, "monster": 0.92}


def _roll_kind(rng, profile, force):
    """One draw against a profile, with force tilting the table toward the severe end."""
    table = PROFILES[profile]
    total = 0.0
    weights = []
    for i, (kind, w) in enumerate(table):
        if kind is None:
            w *= max(0.15, 1.6 - force)          # a hard blow rarely does nothing
        else:
            # Later entries are the worse ones; force reaches them.
            w *= 0.55 + 0.90 * force if i >= 5 else 1.0
        weights.append(w)
        total += w
    draw = rng.random() * total
    acc = 0.0
    for (kind, _), w in zip(table, weights):
        acc += w
        if draw <= acc:
            return kind
    return table[-1][0]


def draws_weapon(rng, attacker, defender):
    """Whether this blow comes with the edge or with the fist.

    Whether a dwarf is *carrying* a weapon and whether they *draw* it are different questions,
    and the second one is what decides whether a quarrel ends in a black eye or a funeral. The
    chance climbs with hatred past :data:`DRAW_AT`, with the quality of what is on the belt, and
    with how badly this same dwarf has already hurt them -- somebody who broke your ribs last
    week does not get your fists.
    """
    quality = weapon_quality(attacker)
    if quality < ARMED_AT:
        return False
    rel = attacker.mind.rel(defender.id)
    heat = rel["hatred"]
    if attacker.last_hit_by == defender.id:
        heat += 0.25 * defender.condition.pain()
    if heat <= DRAW_AT:
        return False
    chance = (heat - DRAW_AT) / (1.0 - DRAW_AT) * (0.35 + 0.65 * quality)
    return rng.random() < min(1.0, chance)


def resolve_blow(rng, attacker, defender, profile=None, power=1.0, tick=0, by=None):
    """One blow. Returns ``(health lost, [Injury, ...], profile)``, applied to ``defender``.

    The profile comes back because whether the edge came out is not knowable from outside:
    :func:`draws_weapon` decides it here, and the log has to be able to say which it was.

    ``profile`` defaults to ``"armed"`` or ``"unarmed"`` read off what the attacker is holding;
    a monster passes ``"monster"`` and no attacker. ``power`` scales the whole thing, which is
    what a monster's bite and a fulfilled "deal with him for me" use.
    """
    if profile is None:
        profile = "armed" if (attacker is not None
                              and draws_weapon(rng, attacker, defender)) else "unarmed"
    if attacker is not None:
        force = strength(attacker)
        if profile == "armed":
            force *= 0.55 + 0.75 * weapon_quality(attacker)
    else:
        force = 0.75
    force = _clamp01(force * power)

    already = defender.condition.pain()
    lost = (HEALTH_BASE[profile] + HEALTH_FORCE[profile] * force) * rng.uniform(0.55, 1.20)
    lost *= 1.0 + PAIN_ESCALATION * already
    lost = round(max(0.0, lost), 2)

    injuries = []
    if rng.random() < INJURE_BASE[profile] * (0.45 + 0.75 * force):
        kind = _roll_kind(rng, profile, force)
        if kind is not None:
            severity = _clamp01(rng.uniform(0.20, 0.55) * (0.55 + 0.90 * force))
            inj = defender.condition.add(kind, severity, tick,
                                         by if by is not None else
                                         (attacker.id if attacker is not None else None))
            if inj is not None:
                injuries.append(Injury(kind, severity, tick, inj.by))

    defender.health = round(defender.health - lost, 2)
    return lost, injuries, profile


def blow_magnitude(lost, injuries):
    """How loudly a blow is felt and remembered.

    The grudge is for what was broken, not for the arithmetic: a punch that takes two health and
    leaves nothing behind fades, and a broken arm is remembered whether or not it bled.
    """
    m = 0.28 + 0.12 * min(4.0, lost)
    for inj in injuries:
        m += GRUDGE_PER_SEVERITY * inj.severity * (0.35 if inj.kind == "bruised" else 1.0) * 0.30
    return round(min(1.80, m), 3)


def hurt_term(agent):
    """The arbitrator's ``hurt``: how close to dying, or how broken, whichever is worse."""
    bled = 1.0 - max(0.0, agent.health) / MAX_HEALTH
    return max(bled, agent.condition.pain())


def blocked_skills(agent, skills):
    """The names of the skills this dwarf's injuries take off the table right now."""
    cond = agent.condition
    out = []
    for skill in skills:
        if getattr(skill, "needs_two_hands", False) and cond.blocks_two_hands():
            out.append(skill.name)
        elif getattr(skill, "needs_legs", False) and cond.blocks_legs():
            out.append(skill.name)
    return out


def rest_scale(agent):
    """How fast this dwarf's body is working on it: fed and rested mends, starving does not."""
    n = agent.mind.needs
    fed = 1.0 - n["hunger"]
    watered = 1.0 - n["thirst"]
    return 0.45 + 0.75 * (0.6 * fed + 0.4 * watered)


def day_phrase(kind):
    """"Hrolf's arm has mended" -- what the story says when an injury finally goes."""
    return {
        "bruised": "bruises have faded",
        "cut": "cut has closed",
        "black_eye": "eye has cleared",
        "sprained_hand": "hand is good again",
        "broken_arm": "arm has mended",
        "broken_leg": "leg has mended",
        "concussion": "head has cleared",
        "cracked_ribs": "ribs have knitted",
        "bleeding": "bleeding has stopped",
    }[kind]


assert set(INJURY_KINDS) == set(EFFECTS) == set(HEALING) == set(PHRASE)
assert not math.isnan(sum(HEALING.values()))
