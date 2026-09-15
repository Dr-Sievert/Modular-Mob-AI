"""Three settlements where the *rare* reaction is the sensible one.

The imitation stage's honest weakness is its tails: ``IGNORE``, ``COMPLAIN_TO`` and ``APOLOGIZE``
are a few dozen decisions in thirty-six thousand, so a student that never chooses them still
scores 97.9%. A reward stage can only fix that if the training runs actually contain situations
where those choices pay. These three are those situations, and like every other scenario in the
sim they are **starting conditions only** -- nothing here scripts an action.

``friends``
    Six dwarves who trust and respect each other, all quick-tempered, two of them starting angry.
    The insults are between friends: let it go, snap back, or ask for an apology -- not a fist.
``bully``
    One hostile dwarf, the dwarf it hates (timid, forgiving) and a strong dwarf who likes the
    victim, all in one room. The victim avoids or complains; the protector steps in, and is the
    one dwarf in the settlement for whom violence *is* provoked.
``thief``
    A broke, greedy dwarf and five others holding goods, all in the tavern. The theft is seen: the
    victim and the witnesses have a case, and the thief has a reason to apologise.

They are wired into :mod:`dwarfsim.learn.collect` (so an imitation set contains them too), into the
PPO rollouts, and into :mod:`dwarfsim.learn.evaluate`. ``World(scenario="bully")`` and
``python -m dwarfsim run --scenario bully`` work like any other scenario;
:func:`dwarfsim.world.apply_scenario` hands the three names here.
"""

#: The names this module owns. ``dwarfsim.world.SCENARIOS`` includes them.
NAMES = ("friends", "bully", "thief")

#: What the PPO rollouts run. ``player`` is left out: it needs somebody at the keyboard, and the
#: script that stands in for one belongs to the evaluation rather than to every rollout.
TRAIN_SCENARIOS = ("default", "feud", "gossip") + NAMES

#: What an evaluation runs: the training set plus the scripted player session.
EVAL_SCENARIOS = TRAIN_SCENARIOS + ("player",)


def apply(world, name):
    """Set one of the three up. Raises ``ValueError`` on any other name."""
    if name == "friends":
        return _friends(world)
    if name == "bully":
        return _bully(world)
    if name == "thief":
        return _thief(world)
    raise ValueError("not one of %s: %r" % (", ".join(NAMES), name))


def _friends(world):
    """Mild insults between dwarves who think well of each other.

    Everybody trusts everybody and nobody hates anybody, but they are all short-tempered and two
    of them start angry, which is enough for the occasional insult -- ``SOCIALIZE`` turns hostile
    at ``hatred * 1.3 + anger * (0.4 + 0.7 * temper) - trust * 0.5 > 0.55``, and with trust this
    high only anger can get it there. Anger decays back to a baseline that is *not* enough on its
    own, so the friction comes in bursts and burns out: a quarrel among friends, not a feud.
    """
    for i, x in enumerate(world.agents):
        t = x.mind.traits
        t["temper"] = 0.90
        t["sociability"] = round(min(0.95, 0.70 + 0.04 * i), 3)
        t["forgiveness"] = round(0.50 + 0.12 * (i % 4), 3)
        t["pride"] = round(0.20 + 0.18 * (i % 3), 3)
        t["bravery"] = round(0.35 + 0.10 * (i % 3), 3)
        x.mind.baseline["anger"] = 0.32
        x.mind.emotions["anger"] = 0.32
        x.mind.needs["social"] = 0.50
    for a in world.agents:
        for b in world.agents:
            if a.id == b.id:
                continue
            r = a.mind.rel(b.id)
            r["trust"] = 0.55
            r["respect"] = 0.45
            r["hatred"] = 0.06
    # Two of them are already cross about something, standing in the same room.
    a, b = world.agents[0], world.agents[1]
    b.place = a.place
    for x in (a, b):
        x.mind.emotions["anger"] = 0.85


def _bully(world):
    """One dwarf leaning on another, and somebody standing close enough to do something.

    The bully hates the victim and nobody else; the victim is timid and forgiving, which is what
    makes avoiding or complaining the sensible answer rather than swinging back; the protector is
    brave, armed, and thinks well of the victim, so once it sees a blow it adopts ``PROTECT`` and
    becomes the one dwarf in the settlement whose violence is provoked.
    """
    bully, victim, protector = world.agents[0], world.agents[1], world.agents[2]

    bt = bully.mind.traits
    bt.update({"temper": 0.95, "bravery": 0.85, "pride": 0.90, "forgiveness": 0.15,
               "greed": 0.60, "sociability": 0.55})
    bully.mind.baseline["anger"] = 0.40
    bully.mind.emotions["anger"] = 0.70
    bully.inv["weapon"] = 0.50
    r = bully.mind.rel(victim.id)
    r["trust"], r["respect"], r["hatred"] = -0.60, -0.50, 0.55

    vt = victim.mind.traits
    vt.update({"bravery": 0.12, "temper": 0.20, "pride": 0.20, "forgiveness": 0.80,
               "sociability": 0.65})
    victim.mind.baseline["fear"] = 0.30
    victim.mind.emotions["fear"] = 0.35
    victim.inv["weapon"] = 0.05
    r = victim.mind.rel(bully.id)
    r["trust"], r["respect"], r["hatred"] = -0.40, 0.10, 0.10

    pt = protector.mind.traits
    pt.update({"bravery": 0.95, "temper": 0.50, "pride": 0.60, "forgiveness": 0.45,
               "sociability": 0.60})
    protector.inv["weapon"] = 0.80
    for x, y, trust, respect in ((protector, victim, 0.70, 0.55), (victim, protector, 0.70, 0.60)):
        rel = x.mind.rel(y.id)
        rel["trust"], rel["respect"] = trust, respect
    rel = protector.mind.rel(bully.id)
    rel["trust"], rel["respect"] = -0.20, 0.10

    for x in (bully, victim, protector):
        x.place = "HALL"
    # The rest of the settlement is elsewhere, so the room is these three and what they do in it.
    for x in world.agents[3:]:
        x.place = "TAVERN"
        x.mind.traits["sociability"] = 0.70


def _thief(world):
    """A dwarf with nothing, five dwarves with something, and one room to do it in.

    ``STEAL`` only proposes against somebody standing in the same place, and a theft is seen by
    each onlooker on a 0.6 coin flip, so putting everybody in the tavern is what makes a theft a
    public event with a victim and witnesses rather than a private transfer.
    """
    thief = world.agents[0]
    t = thief.mind.traits
    t.update({"greed": 0.95, "temper": 0.45, "pride": 0.30, "forgiveness": 0.50,
              "bravery": 0.60, "sociability": 0.55})
    thief.inv.update({"gold": 0, "food": 0, "ale": 0, "ore": 0})
    thief.mind.needs["hunger"] = 0.55

    for i, x in enumerate(world.agents[1:], start=1):
        x.inv["gold"] = 4 + (i % 3)
        x.inv["food"] = 3
        xt = x.mind.traits
        xt.update({"greed": round(0.20 + 0.05 * i, 3), "sociability": round(0.70 + 0.04 * i, 3),
                   "pride": round(0.45 + 0.10 * (i % 3), 3),
                   "forgiveness": round(0.35 + 0.12 * (i % 3), 3)})
        for y in world.agents[1:]:
            if y.id != x.id:
                rel = x.mind.rel(y.id)
                rel["trust"], rel["respect"] = 0.45, 0.30
    for x in world.agents:
        x.place = "TAVERN"
