"""Skills: the things a dwarf can actually do.

A skill proposes candidates and executes the one that wins. It never decides anything -- that is the
arbitrator's job -- and it never reaches into another skill. A skill is the analogue of one trained
specialist in the Java mod: a narrow thing that knows how to do one job and how to say when it is
worth doing.

``propose(agent, world) -> [Candidate]``   what this skill could do right now
``execute(agent, world, cand)``            do it, emit the events it causes

A candidate that names a ``place`` the dwarf is not standing in costs a tick of walking instead:
that is handled once, in :meth:`dwarfsim.world.World.step`, so no skill has to think about it and
``distance_cost`` can be an honest scoring term.

The six reaction skills -- IGNORE, RETORT, DEMAND_APOLOGY, COMPLAIN_TO, AVOID and (through the
ordinary ATTACK skill) violence -- exist only while there is an unanswered harm in episodic memory.
None of them is ordered ahead of any other: which one a dwarf reaches for is whatever the
arbitrator's terms add up to, which is why a proud dwarf demands, a timid one avoids and a
forgiving one lets it go.
"""

from . import condition
from . import obligations as obl_mod
from . import profanity, regard, speech
from .memory import HARM_KINDS, HELP_KINDS
from .mind import shift_opinion

WORK_PLACES = ("MINE", "FORGE", "FARM", "TAVERN")

#: How many confidants one complaint may be aimed at in a tick.
COMPLAIN_FANOUT = 3

#: Trust needed before somebody counts as the sort of friend you complain to. The chief,
#: where there is one, is a confidant whatever anyone thinks of them.
CONFIDANT_TRUST = 0.22


class Candidate:
    """One proposed action, with the arbitrator's scoring attached after the fact."""

    __slots__ = ("skill", "target", "place", "hints", "detail", "terms", "contrib", "score")

    def __init__(self, skill, target=None, place=None, hints=None, detail=None):
        self.skill = skill
        self.target = target          # agent id, or None
        self.place = place            # where it must happen, or None for "wherever you are"
        self.hints = hints or {}      # term values only the skill knows (supply_pressure, ...)
        self.detail = detail or {}
        self.terms = {}
        self.contrib = {}
        self.score = 0.0

    def about(self):
        """Who the candidate is *about*, which for gossip and complaints is not who it is aimed at."""
        got = self.detail.get("about")
        return self.target if got is None else got

    def key(self):
        """Stable ordering, so a learned scorer sees candidates in the same order every run."""
        return (self.skill, self.place or "", "" if self.target is None else str(self.target),
                "" if self.detail.get("about") is None else str(self.detail["about"]))

    def label(self):
        bits = [self.skill]
        if self.place:
            bits.append("@" + self.place)
        if self.target is not None:
            bits.append("->%s" % self.target)
        about = self.detail.get("about")
        if about is not None and about != self.target:
            bits.append("~%s" % about)
        return " ".join(bits)


class Skill:
    """One thing a dwarf can do, and what a body has to be able to do to do it.

    The two declarations are what the injury model reads (:mod:`dwarfsim.condition`). A skill
    that needs two working hands is not offered at all past a broken arm, and one that needs
    working legs is not offered past a broken leg; short of that both show up in the
    ``impairment`` term, which prices being merely hampered rather than stopped.
    """

    name = "?"
    #: Mining, forging, and anything with a weapon in it. A broken arm takes these away.
    needs_two_hands = False
    #: Walking somewhere as the point of the action. A broken leg takes these away.
    needs_legs = False

    def propose(self, agent, world):
        return ()

    def execute(self, agent, world, cand):
        pass


# ---------------------------------------------------------------------------
# Shared helpers for the reaction skills
# ---------------------------------------------------------------------------


def _provocations(agent, limit=1):
    """The freshest unanswered harms done to me. One is enough to start a scene."""
    return [m for m in agent.provocations if not m.answered][:limit]


def _publicity(mem):
    return min(1.0, mem.witnesses / 3.0)


def _humiliation(agent, mem):
    """How much being done that, in front of that many, stings *this* dwarf's dignity.

    This is the one place pride and publicity multiply. A linear scorer cannot multiply two
    of its own terms, so the skill hands it over as one number, the way WORK hands over
    ``supply_pressure``.
    """
    return min(1.0, _publicity(mem) * (0.30 + 1.40 * agent.mind.traits["pride"]))


def _react_hints(agent, mem):
    return {"publicity": _publicity(mem), "humiliation": _humiliation(agent, mem)}


def _answer(mem):
    mem.answered = True


def _ask_words(ask):
    """"two ore", "the monster at the gate" -- what an ask sounds like out loud."""
    if not ask:
        return "a hand"
    if ask.get("item"):
        n = ask.get("quantity", 1)
        return ("some %s" % ask["item"]) if n == 1 else ("%d %s" % (n, ask["item"]))
    if ask["action"] == "HELP":
        return "the thing at the %s" % (ask.get("place") or "gate").lower()
    if ask["action"] == "GO_TO":
        return "getting to the %s" % (ask.get("place") or "hall").lower()
    if ask["action"] == "FIGHT":
        return "that business of mine"
    return "a hand"


def _wants_from(agent, world, other):
    """The one thing this dwarf would ask *this* dwarf for right now, or ``None``.

    This is where obligations come from without a player: an empty belly and somebody standing
    there with two loaves. What the ask *is* comes from need and inventory; whether it is a
    request, an order or a paid offer comes from what the asker thinks of the asked; and whether
    it is taken on at all is the arbitrator's business, not this function's.
    """
    for ob in world._open_obl:
        if ob.frm == agent.id and ob.to == other.id:
            return None          # one ask at a time; do not nag
    m = agent.mind
    rel = m.rel(other.id)
    want = None
    if m.needs["hunger"] > 0.50 and other.inv["food"] >= 2 and agent.inv["food"] < 1:
        want = obl_mod.make_ask("GIVE", item="food", quantity=1, place=agent.place)
    elif m.needs["thirst"] > 0.60 and other.inv["ale"] >= 2 and agent.inv["ale"] < 1:
        want = obl_mod.make_ask("GIVE", item="ale", quantity=1, place=agent.place)
    elif agent.inv["ore"] < 1 and other.inv["ore"] >= 3 and m.traits["greed"] > 0.4:
        want = obl_mod.make_ask("BRING", item="ore", quantity=1, place=agent.place)
    elif (world.monster is not None and m.emotions["fear"] > 0.35
            and regard.felt_trust(agent, other.id) > 0.15):
        want = obl_mod.make_ask("HELP", place=world.monster["place"])
    else:
        # "Deal with him for me." Only asked of somebody trusted, about somebody hated.
        if rel["trust"] > 0.35:
            worst, worst_h = None, 0.55
            for foe_id, r in m.rels.items():
                if foe_id in (agent.id, other.id) or r["hatred"] <= worst_h:
                    continue
                foe = world.agent(foe_id)
                if foe is not None and foe.alive and not foe.external:
                    worst, worst_h = foe_id, r["hatred"]
            if worst is not None:
                want = obl_mod.make_ask("FIGHT", target=worst)
    if want is None:
        return None
    # Somebody who does not owe you anything gets paid, if you can afford it and are not too mean.
    if rel["trust"] < 0.30 and agent.inv["gold"] >= 2:
        want["payment"] = min(3, agent.inv["gold"], 1 + int(3.0 * (1.0 - m.traits["greed"])))
    return want


# ---------------------------------------------------------------------------


class Work(Skill):
    name = "WORK"
    #: A pick and a hammer are both two-handed. Buying ale is not, which is the one candidate
    #: that says so for itself below.
    needs_two_hands = True

    def propose(self, agent, world):
        inv = agent.inv
        out = []
        for place in WORK_PLACES:
            hands = 1.0
            if place == "MINE":
                pressure = max(0.0, 1.0 - inv["ore"] / 6.0)
            elif place == "FORGE":
                pressure = min(1.0, inv["ore"] / 3.0) * 0.8 + (1.0 - inv["weapon"]) * 0.25
            elif place == "FARM":
                pressure = max(0.0, 1.0 - inv["food"] / 5.0)
            else:  # TAVERN: buy ale with gold, which a dwarf can do one-handed
                pressure = min(1.0, inv["gold"] / 4.0) * min(1.0, 0.3 + agent.mind.needs["thirst"])
                hands = 0.0
            out.append(Candidate(self.name, place=place,
                                 hints={"supply_pressure": pressure, "needs_two_hands": hands}))
        return out

    def execute(self, agent, world, cand):
        rng = world.rng
        place = cand.place
        inv = agent.inv
        got = None
        # A sprained hand halves the output; a broken arm would not have got this far except at
        # the tavern. A shift that produces nothing is what being hurt costs.
        output = agent.condition.work_mult()
        works = output >= 1.0 or rng.random() < output
        if place == "MINE":
            if works:
                inv["ore"] += 1
                got = "ore"
            agent.mind.satisfy("fatigue", -0.010)
        elif place == "FORGE":
            if works and inv["ore"] >= 1:
                inv["ore"] -= 1
                inv["gold"] += 2
                got = "gold"
                if rng.random() < 0.25 and inv["weapon"] < 1.0:
                    inv["weapon"] = min(1.0, inv["weapon"] + 0.08)
                    got = "gold and a better edge"
            agent.mind.satisfy("fatigue", -0.010)
        elif place == "FARM":
            if works:
                inv["food"] += 1
                got = "food"
            agent.mind.satisfy("fatigue", -0.008)
        elif place == "TAVERN":
            if inv["gold"] >= 1:
                inv["gold"] -= 1
                inv["ale"] += 2
                got = "ale"
        agent.mind.satisfy("social", -0.002)
        if got is not None:
            # A shift that produced something is worth praising, which is what keeps an
            # honest compliment about the ore from being read as flattery. See
            # :data:`dwarfsim.regard.ACHIEVEMENTS`.
            agent.last_deed = world.tick
        extra = {"got": got}
        if not works:
            extra["hampered"] = agent.condition.describe()
        world.emit("WORK", agent, place=place, apply=False, extra=extra)


class Eat(Skill):
    name = "EAT"

    def propose(self, agent, world):
        if agent.inv["food"] < 1:
            return ()
        return (Candidate(self.name),)

    def execute(self, agent, world, cand):
        agent.inv["food"] -= 1
        agent.mind.satisfy("hunger", 0.55)
        agent.heal(1.2)
        # Eating mends, and it is half of what stops a wound bleeding.
        healed = agent.condition.mend(condition.FOOD_MEND, condition.BLEED_MEND)
        world.emit("EAT", agent, place=agent.place, apply=False)
        for kind in healed:
            world.emit("RECOVERED", agent, place=agent.place, apply=False,
                       extra={"injury": kind, "by": "food"})


class Drink(Skill):
    name = "DRINK"

    def propose(self, agent, world):
        if agent.inv["ale"] < 1:
            return ()
        return (Candidate(self.name),)

    def execute(self, agent, world, cand):
        agent.inv["ale"] -= 1
        agent.mind.satisfy("thirst", 0.60)
        agent.mind.emotions["happiness"] = min(1.0, agent.mind.emotions["happiness"] + 0.06)
        agent.mind.satisfy("social", 0.05)
        world.emit("DRINK", agent, place=agent.place, apply=False)


class Rest(Skill):
    name = "REST"

    def propose(self, agent, world):
        return (Candidate(self.name, place="HALL"),)

    def execute(self, agent, world, cand):
        agent.mind.satisfy("fatigue", 0.35)
        agent.heal(1.0)
        # Lying still is what a broken bone wants, and the only thing that reliably stops
        # a bleed. Everything mends faster here than it does on its own.
        healed = agent.condition.mend(condition.REST_MEND, condition.BLEED_MEND)
        world.emit("REST", agent, place=agent.place, apply=False)
        for kind in healed:
            world.emit("RECOVERED", agent, place=agent.place, apply=False,
                       extra={"injury": kind, "by": "rest"})


class Socialize(Skill):
    name = "SOCIALIZE"

    def propose(self, agent, world):
        tick = world.tick
        return tuple(
            Candidate(self.name, target=o.id, place=o.place)
            for o in world.living()
            if o.id != agent.id and not agent.avoiding(o.id, tick)
        )

    def execute(self, agent, world, cand):
        other = world.agent(cand.target)
        if other is None or not other.alive or other.place != agent.place:
            return
        rel = agent.mind.rel(other.id)
        mind = agent.mind
        # Felt trust, not stored trust: a room that has been talking all afternoon is a
        # friendly room even though the afternoon bought almost no trust proper.
        hostility = (rel["hatred"] * 1.3
                     + mind.emotions["anger"] * (0.4 + 0.7 * mind.traits["temper"])
                     - regard.felt_trust(agent, other.id) * 0.5)
        ask = None
        if hostility > 0.55:
            intent = "INSULT"
        else:
            ask = _wants_from(agent, world, other)
            if ask is not None:
                # An ask is not a favour: a dwarf who does not trust you pays, or orders.
                if ask["payment"]:
                    intent = "OFFER"
                elif rel["respect"] < 0.0 or mind.traits["pride"] > 0.7:
                    intent = "COMMAND"
                else:
                    intent = "REQUEST"
            elif regard.felt_trust(agent, other.id) > 0.25                     or mind.emotions["happiness"] > 0.62:
                intent = "PRAISE"
            else:
                intent = "SMALLTALK"
        topic = speech.PLACE_TOPIC.get(agent.place, "NONE")
        parsed = speech.utterance(intent, agent.name, other.name, topic, world.rng,
                                  gold=ask["payment"] if ask else 0,
                                  item=_ask_words(ask),
                                  swear=profanity.for_speaker(world, agent, other),
                                  hurt=agent.condition.complaint())
        if ask is not None:
            parsed["ask"] = ask
        mind.satisfy("social", 0.30)
        other.mind.satisfy("social", 0.22)
        if intent == "INSULT":
            agent.grievances.add(other.id)
        speech.hear(world, agent.id, other.id, parsed)


class Steal(Skill):
    name = "STEAL"

    def propose(self, agent, world):
        out = []
        for o in world.at(agent.place):
            if o.id == agent.id:
                continue
            if o.inv["gold"] + o.inv["ore"] + o.inv["food"] + o.inv["ale"] <= 0:
                continue
            out.append(Candidate(self.name, target=o.id, place=agent.place))
        return out

    def execute(self, agent, world, cand):
        victim = world.agent(cand.target)
        if victim is None or not victim.alive or victim.place != agent.place:
            return
        for slot in ("gold", "ore", "ale", "food"):
            if victim.inv[slot] >= 1:
                take = 2 if slot == "gold" and victim.inv["gold"] >= 2 else 1
                victim.inv[slot] -= take
                agent.inv[slot] += take
                break
        else:
            return
        rng = world.rng
        seen_by = [w for w in world.at(agent.place)
                   if w.id not in (agent.id, victim.id) and rng.random() < 0.6]
        caught = rng.random() < 0.45 or bool(seen_by)
        agent.grievances.add(victim.id)
        world.emit("STEAL", agent, victim if caught else None, witnesses=seen_by,
                   place=agent.place,
                   extra={"item": slot, "count": take, "seen": caught, "victim": victim.id,
                          "witnesses": [w.id for w in seen_by]})


class Attack(Skill):
    name = "ATTACK"

    def propose(self, agent, world):
        out = []
        for o in world.living():
            if o.id == agent.id:
                continue
            hints = {}
            prov = None
            for m in agent.provocations:
                if m.actor == o.id and not m.answered:
                    prov = m
                    break
            if prov is not None:
                hints = _react_hints(agent, prov)
            out.append(Candidate(self.name, target=o.id, place=o.place, hints=hints,
                                 detail={"mem": prov} if prov else None))
        return out

    def execute(self, agent, world, cand):
        victim = world.agent(cand.target)
        if victim is None or not victim.alive or victim.place != agent.place:
            return
        mem = cand.detail.get("mem")
        if mem is not None:
            _answer(mem)
        # What a blow costs is :mod:`dwarfsim.condition`'s business, not this skill's: mostly an
        # injury and a little health with fists, mostly health with an edge.
        dmg, injuries, how = condition.resolve_blow(world.rng, agent, victim, tick=world.tick)
        victim.last_hit_by = agent.id
        victim.last_hit_tick = world.tick
        agent.grievances.add(victim.id)
        agent.last_struck[victim.id] = world.tick
        # The grudge is for what was broken, not for the arithmetic.
        world.emit("HIT", agent, victim, place=agent.place,
                   magnitude=condition.blow_magnitude(dmg, injuries),
                   extra={"damage": dmg, "health": max(0.0, victim.health),
                          "armed": how == "armed",
                          "injuries": [i.snapshot() for i in injuries]})
        if victim.health <= 0.0:
            world.kill(victim, agent)


class Flee(Skill):
    name = "FLEE"

    def propose(self, agent, world):
        threat = None
        if world.tick - agent.last_hit_tick <= 12:
            threat = agent.last_hit_by
        monster = world.monster_at(agent.place)
        if threat is None and monster is None:
            return ()
        return (Candidate(self.name, target=threat),)

    def execute(self, agent, world, cand):
        options = [p for p in world.places if p != agent.place]
        dest = options[world.rng.randrange(len(options))]
        # A broken leg does not forbid running: it makes running not work, which is worse.
        lame = agent.condition.move_penalty()
        if lame > 0.0 and world.rng.random() < lame:
            agent.mind.emotions["fear"] = min(1.0, agent.mind.emotions["fear"] + 0.06)
            world.emit("FLEE", agent, world.agent(cand.target), place=agent.place, apply=False,
                       extra={"to": agent.place, "toward": dest, "limped": True,
                              "hurt": agent.condition.describe()})
            return
        agent.place = dest
        agent.mind.emotions["fear"] = max(0.0, agent.mind.emotions["fear"] - 0.10)
        world.emit("FLEE", agent, world.agent(cand.target), place=dest, apply=False,
                   extra={"to": dest})


class Apologize(Skill):
    name = "APOLOGIZE"

    def propose(self, agent, world):
        out = []
        tick = world.tick
        for o in world.at(agent.place):
            if o.id == agent.id:
                continue
            demanded = agent.demands.get(o.id)
            pressure = 0.0
            if demanded is not None and demanded[1] > tick:
                # Somebody stood up in front of the room and asked for this out loud.
                left = (demanded[1] - tick) / float(max(1, world.demand_ttl))
                pressure = left * (0.40 + 0.60 * max(0.0, agent.mind.rel(o.id)["respect"]))
            if not pressure and o.id not in agent.grievances \
                    and agent.mind.rel(o.id)["hatred"] <= 0.15:
                continue
            out.append(Candidate(self.name, target=o.id, place=agent.place,
                                 hints={"obligation_pressure": pressure}))
        return out

    def execute(self, agent, world, cand):
        other = world.agent(cand.target)
        if other is None or not other.alive or other.place != agent.place:
            return
        parsed = speech.utterance("APOLOGY", agent.name, other.name,
                                  speech.PLACE_TOPIC.get(agent.place, "NONE"), world.rng)
        agent.grievances.discard(other.id)
        if other.id in agent.demands:
            agent.demands[other.id][1] = world.tick
        speech.hear(world, agent.id, other.id, parsed)


class FightMonster(Skill):
    name = "FIGHT_MONSTER"
    #: You cannot hold a weapon with a broken arm and you cannot stand your ground on a broken
    #: leg, so this is the one skill both bones take away outright.
    needs_two_hands = True
    needs_legs = True

    def propose(self, agent, world):
        if world.monster is None:
            return ()
        return (Candidate(self.name, place=world.monster["place"]),)

    def execute(self, agent, world, cand):
        m = world.monster
        if m is None or m["place"] != agent.place:
            return
        dmg = round((3.0 + 6.0 * condition.weapon_quality(agent))
                    * (0.55 + 0.75 * condition.strength(agent))
                    * world.rng.uniform(0.5, 1.2), 2)
        m["hp"] = round(m["hp"] - dmg, 2)
        bite = 0.0
        taken = []
        if m["hp"] > 0.0:
            bite, taken, _ = condition.resolve_blow(world.rng, None, agent, profile="monster",
                                                    power=0.85, tick=world.tick)
            agent.last_hit_tick = world.tick
        bystanders = [o for o in world.at(agent.place) if o.id != agent.id]
        world.emit("FIGHT_MONSTER", agent, place=agent.place, apply=False,
                   extra={"kind": m["kind"], "damage": dmg, "monster_hp": max(0.0, m["hp"]),
                          "taken": bite, "injuries": [i.snapshot() for i in taken]})
        # Standing between the settlement and a creeper is how respect is earned.
        world.emit("HELP", agent, None, witnesses=bystanders, place=agent.place,
                   extra={"kind": m["kind"]})
        if m["hp"] <= 0.0:
            agent.inv["gold"] += 3
            agent.mind.emotions["happiness"] = min(1.0, agent.mind.emotions["happiness"] + 0.12)
            world.monster = None
            world.emit("MONSTER_SLAIN", agent, place=agent.place, apply=False,
                       extra={"kind": m["kind"]})
        if agent.health <= 0.0:
            world.kill(agent, None, cause=m["kind"])


# ---------------------------------------------------------------------------
# The graded reactions
# ---------------------------------------------------------------------------


class Ignore(Skill):
    name = "IGNORE"

    def propose(self, agent, world):
        out = []
        for mem in _provocations(agent):
            out.append(Candidate(self.name, target=mem.actor, hints=_react_hints(agent, mem),
                                 detail={"mem": mem}))
        return out

    def execute(self, agent, world, cand):
        mem = cand.detail["mem"]
        _answer(mem)
        # Swallowing it costs a little cheer and leaves the memory sitting there.
        world.emit("IGNORE", agent, world.agent(cand.target), place=agent.place,
                   witnesses=(), extra={"about": mem.kind})


class Retort(Skill):
    name = "RETORT"

    def propose(self, agent, world):
        out = []
        for mem in _provocations(agent):
            other = world.agent(mem.actor)
            if other is None or not other.alive or other.place != agent.place:
                continue
            out.append(Candidate(self.name, target=other.id, place=agent.place,
                                 hints=_react_hints(agent, mem), detail={"mem": mem}))
        return out

    def execute(self, agent, world, cand):
        other = world.agent(cand.target)
        if other is None or not other.alive or other.place != agent.place:
            return
        _answer(cand.detail["mem"])
        parsed = speech.utterance("RETORT", agent.name, other.name,
                                  speech.PLACE_TOPIC.get(agent.place, "NONE"), world.rng,
                                  swear=profanity.for_speaker(world, agent, other))
        agent.grievances.add(other.id)
        agent.mind.satisfy("social", 0.10)
        world.emit("RETORT", agent, other, place=agent.place,
                   dialogue=parsed["text"], parsed=parsed,
                   magnitude=speech.magnitude_of(parsed))


class DemandApology(Skill):
    name = "DEMAND_APOLOGY"

    def propose(self, agent, world):
        out = []
        for mem in _provocations(agent):
            other = world.agent(mem.actor)
            if other is None or not other.alive or other.place != agent.place:
                continue
            asked = other.demands.get(agent.id)
            if asked is not None and world.tick - asked[0] < world.demand_cooldown:
                continue        # already asked, answered or not; do not stand there asking twice
            out.append(Candidate(self.name, target=other.id, place=agent.place,
                                 hints=_react_hints(agent, mem), detail={"mem": mem}))
        return out

    def execute(self, agent, world, cand):
        other = world.agent(cand.target)
        if other is None or not other.alive or other.place != agent.place:
            return
        _answer(cand.detail["mem"])
        parsed = speech.utterance("DEMAND", agent.name, other.name,
                                  speech.PLACE_TOPIC.get(agent.place, "NONE"), world.rng,
                                  swear=profanity.for_speaker(world, agent, other))
        other.demands[agent.id] = [world.tick, world.tick + world.demand_ttl]
        world.emit("DEMAND", agent, other, place=agent.place,
                   dialogue=parsed["text"], parsed=parsed,
                   extra={"until": world.tick + world.demand_ttl})


class RefuseApology(Skill):
    name = "REFUSE_APOLOGY"

    def propose(self, agent, world):
        out = []
        tick = world.tick
        for who, span in agent.demands.items():
            if span[1] <= tick:
                continue
            other = world.agent(who)
            if other is None or not other.alive or other.place != agent.place:
                continue
            left = (span[1] - tick) / float(max(1, world.demand_ttl))
            out.append(Candidate(self.name, target=who, place=agent.place,
                                 hints={"obligation_pressure": left}))
        return out

    def execute(self, agent, world, cand):
        other = world.agent(cand.target)
        if other is None or not other.alive or other.place != agent.place:
            return
        # Answered, not forgotten: the made-tick stays so nobody re-demands straight away.
        agent.demands[cand.target][1] = world.tick
        parsed = speech.utterance("REFUSE_APOLOGY", agent.name, other.name,
                                  speech.PLACE_TOPIC.get(agent.place, "NONE"), world.rng,
                                  swear=profanity.for_speaker(world, agent, other))
        world.emit("REFUSE_APOLOGY", agent, other, place=agent.place,
                   dialogue=parsed["text"], parsed=parsed,
                   magnitude=speech.magnitude_of(parsed))


class ComplainTo(Skill):
    name = "COMPLAIN_TO"

    def propose(self, agent, world):
        out = []
        chief_id = world.chief_id
        for mem in _provocations(agent):
            n = 0
            for o in world.at(agent.place):
                if o.id in (agent.id, mem.actor):
                    continue
                is_chief = (chief_id is not None and o.id == chief_id)
                if not is_chief and regard.felt_trust(agent, o.id) < CONFIDANT_TRUST:
                    continue
                out.append(Candidate(self.name, target=o.id, place=agent.place,
                                     hints=_react_hints(agent, mem),
                                     detail={"mem": mem, "about": mem.actor,
                                             "chief": is_chief}))
                n += 1
                if n >= COMPLAIN_FANOUT:
                    break
        return out

    def execute(self, agent, world, cand):
        listener = world.agent(cand.target)
        if listener is None or not listener.alive or listener.place != agent.place:
            return
        mem = cand.detail["mem"]
        _answer(mem)
        about = world.agent(mem.actor)
        parsed = speech.utterance("COMPLAIN", agent.name, listener.name,
                                  speech.PLACE_TOPIC.get(agent.place, "NONE"), world.rng,
                                  about=about.name if about else "someone", place=mem.place,
                                  swear=profanity.for_speaker(world, agent, about),
                                  hurt=agent.condition.complaint())
        agent.mind.satisfy("social", 0.18)
        credence = max(0.10, 0.35 + 0.65 * listener.mind.rel(agent.id)["trust"])
        loud = mem.salience(world.tick, agent.mind.traits["forgiveness"])
        landed = world.remember(listener, mem.kind, mem.actor, mem.target, mem.place,
                                loud * credence, "HEARD", from_id=agent.id,
                                witnesses=mem.witnesses, at_tick=mem.tick)
        ev = world.emit("COMPLAIN", agent, listener, place=agent.place,
                        dialogue=parsed["text"], parsed=parsed,
                        extra={"about": mem.actor, "kind": mem.kind,
                               "chief": bool(cand.detail.get("chief"))})
        shift = shift_opinion(listener, mem.actor,
                              {"trust": -0.07, "respect": -0.03, "hatred": +0.05},
                              credence * loud) if landed else []
        if shift:
            ev.setdefault("deltas", []).extend(shift)
        if cand.detail.get("chief"):
            world.complaints.append({"tick": world.tick, "by": agent.id, "about": mem.actor,
                                     "kind": mem.kind, "weight": loud * credence,
                                     "witnesses": mem.witnesses, "handled": False})


class Avoid(Skill):
    name = "AVOID"

    def propose(self, agent, world):
        out = []
        for mem in _provocations(agent):
            other = world.agent(mem.actor)
            if other is None or not other.alive or other.place != agent.place:
                continue
            out.append(Candidate(self.name, target=other.id,
                                 hints=_react_hints(agent, mem), detail={"mem": mem}))
        return out

    def execute(self, agent, world, cand):
        other = world.agent(cand.target)
        if other is None:
            return
        _answer(cand.detail["mem"])
        was = agent.place
        options = [p for p in world.places if p != was]
        dest = options[world.rng.randrange(len(options))]
        witnesses = [o for o in world.at(was) if o.id != agent.id]
        agent.avoid[cand.target] = world.tick + world.avoid_ttl
        world.emit("AVOID", agent, other, witnesses=witnesses, place=was,
                   extra={"to": dest, "until": world.tick + world.avoid_ttl})
        agent.place = dest


# ---------------------------------------------------------------------------
# Gossip
# ---------------------------------------------------------------------------


class Gossip(Skill):
    """SOCIALIZE's other half: instead of talking *to* somebody, talk *about* somebody."""

    name = "GOSSIP"

    def propose(self, agent, world):
        here = world.at(agent.place)
        if len(here) < 2:
            return ()
        tellable = agent.memories.tellable(world.tick, agent)
        if not tellable:
            return ()
        out = []
        tick = world.tick
        for o in here:
            if o.id == agent.id or agent.avoiding(o.id, tick):
                continue
            for mem, sal in tellable:
                # No point telling somebody a story they are in, or one they know already.
                if mem.actor == o.id or mem.target == o.id:
                    continue
                if o.memories.knows(mem.key()):
                    continue
                out.append(Candidate(self.name, target=o.id, place=agent.place,
                                     hints={"gossip_value": sal},
                                     detail={"mem": mem, "about": mem.actor}))
                break
        return out

    def execute(self, agent, world, cand):
        listener = world.agent(cand.target)
        if listener is None or not listener.alive or listener.place != agent.place:
            return
        mem = cand.detail["mem"]
        about = world.agent(mem.actor)
        if about is None:
            return
        sal = mem.salience(world.tick, agent.mind.traits["forgiveness"])
        parsed = speech.utterance("GOSSIP", agent.name, listener.name,
                                  speech.PLACE_TOPIC.get(agent.place, "NONE"), world.rng,
                                  about=about.name, place=mem.place,
                                  swear=profanity.for_speaker(world, agent, about))
        agent.mind.satisfy("social", 0.26)
        listener.mind.satisfy("social", 0.20)
        # What the listener takes away is scaled by what they think of the teller.
        credence = max(0.10, 0.30 + 0.70 * listener.mind.rel(agent.id)["trust"])
        landed = world.remember(listener, mem.kind, mem.actor, mem.target, mem.place,
                                sal * credence, "HEARD", from_id=agent.id,
                                witnesses=mem.witnesses, at_tick=mem.tick)
        ev = world.emit("GOSSIP", agent, listener, place=agent.place,
                        dialogue=parsed["text"], parsed=parsed,
                        extra={"about": mem.actor, "kind": mem.kind, "sal": round(sal, 3),
                               "source": mem.source})
        if mem.kind in HARM_KINDS:
            table = {"trust": -0.06, "respect": -0.02, "hatred": +0.05}
        elif mem.kind in HELP_KINDS:
            table = {"trust": +0.06, "respect": +0.04, "hatred": -0.03}
        else:
            table = {}
        # Only news moves an opinion. Being told a third time what you already knew does not.
        shift = shift_opinion(listener, mem.actor, table, credence * sal) if landed else []
        if shift:
            ev.setdefault("deltas", []).extend(shift)


# ---------------------------------------------------------------------------
# Obligations: answering an ask, haggling over it, and doing it
# ---------------------------------------------------------------------------


def _answer_hints(agent, world, ob, as_asker):
    """The four numbers an answer to an ask is scored on."""
    if as_asker:
        # I am the one who asked; what is on the table is the price they want.
        cost = min(1.0, ob.counter / 8.0)
        payment = 0.0
    else:
        cost = ob.cost(agent, world)
        payment = min(1.0, ob.payment() / 8.0)
    left = 1.0 - min(1.0, (world.tick - ob.made) / float(obl_mod.RESPONSE_TTL))
    return {"ask_cost": cost, "payment_offered": payment, "obligation_pressure": 0.35 + 0.65 * left}


class AcceptAsk(Skill):
    name = "ACCEPT"

    def propose(self, agent, world):
        pending, _, counters = world.obligations_for(agent)
        out = []
        for ob in pending:
            out.append(Candidate(self.name, target=ob.frm,
                                 hints=_answer_hints(agent, world, ob, False),
                                 detail={"obl": ob}))
        for ob in counters:
            out.append(Candidate(self.name, target=ob.to,
                                 hints=_answer_hints(agent, world, ob, True),
                                 detail={"obl": ob}))
        return out

    def execute(self, agent, world, cand):
        ob = cand.detail["obl"]
        if ob.status != "PENDING":
            return
        other = world.agent(cand.target)
        parsed = speech.utterance("ACCEPT", agent.name, other.name if other else "you",
                                  speech.PLACE_TOPIC.get(agent.place, "NONE"), world.rng)
        ev = world.accept_obligation(ob, agent)
        if ev is not None:
            ev["text"] = parsed["text"]
            ev["intent"] = parsed["intent"]
            ev["act"] = parsed["act"]


class RefuseAsk(Skill):
    name = "REFUSE"

    def propose(self, agent, world):
        pending, _, counters = world.obligations_for(agent)
        out = []
        for ob in pending:
            out.append(Candidate(self.name, target=ob.frm,
                                 hints=_answer_hints(agent, world, ob, False),
                                 detail={"obl": ob}))
        for ob in counters:
            out.append(Candidate(self.name, target=ob.to,
                                 hints=_answer_hints(agent, world, ob, True),
                                 detail={"obl": ob}))
        return out

    def execute(self, agent, world, cand):
        ob = cand.detail["obl"]
        if ob.status != "PENDING":
            return
        other = world.agent(cand.target)
        ob.status = "REFUSED"
        if ob in world._open_obl:
            world._open_obl.remove(ob)
        parsed = speech.utterance("REFUSE", agent.name, other.name if other else "you",
                                  speech.PLACE_TOPIC.get(agent.place, "NONE"), world.rng,
                                  swear=profanity.for_speaker(world, agent, other))
        world.emit("REFUSE", agent, other, place=agent.place,
                   dialogue=parsed["text"], parsed=parsed,
                   extra={"obl": ob.oid, "ask": dict(ob.ask)})


class BargainOver(Skill):
    """Not yes, not no: name a price and hand the decision back."""

    name = "BARGAIN"

    def propose(self, agent, world):
        pending, _, _ = world.obligations_for(agent)
        return tuple(
            Candidate(self.name, target=ob.frm, hints=_answer_hints(agent, world, ob, False),
                      detail={"obl": ob})
            for ob in pending if ob.counter == 0
        )

    def execute(self, agent, world, cand):
        ob = cand.detail["obl"]
        if ob.status != "PENDING" or ob.counter:
            return
        other = world.agent(cand.target)
        want = ob.payment() + obl_mod.BARGAIN_STEP + int(round(3.0 * agent.mind.traits["greed"]))
        ob.counter = want
        ob.made = world.tick          # the ball is back in the asker's court, with a fresh clock
        parsed = speech.utterance("BARGAIN", agent.name, other.name if other else "you",
                                  speech.PLACE_TOPIC.get(agent.place, "NONE"), world.rng,
                                  gold=want)
        world.emit("BARGAIN", agent, other, place=agent.place,
                   dialogue=parsed["text"], parsed=parsed,
                   extra={"obl": ob.oid, "want": want, "ask": dict(ob.ask)})


class Fulfil(Skill):
    name = "FULFIL"

    def propose(self, agent, world):
        _, accepted, _ = world.obligations_for(agent)
        out = []
        for ob in accepted:
            action = ob.ask["action"]
            if action == "STOP":
                continue                      # kept by not doing the thing, not by doing one
            place = None
            asker = world.agent(ob.frm)
            if action in ("BRING", "GIVE"):
                item = ob.ask.get("item") or "gold"
                if agent.inv.get(item, 0) < max(1, ob.ask.get("quantity", 1)):
                    continue
                place = asker.place if asker is not None else None
            elif action == "GO_TO":
                place = ob.ask.get("place")
            elif action == "FIGHT":
                foe = world.agent(ob.ask.get("target"))
                if foe is None or not foe.alive:
                    continue
                place = foe.place
            elif action == "HELP":
                if world.monster is None:
                    continue
                place = world.monster["place"]
            span = max(1, ob.deadline - (ob.accepted_tick or ob.made))
            urgency = min(1.0, (world.tick - (ob.accepted_tick or ob.made)) / float(span))
            out.append(Candidate(self.name, target=ob.frm, place=place,
                                 hints={"obligation_pressure": 0.35 + 0.85 * urgency,
                                        "ask_cost": ob.cost(agent, world)},
                                 detail={"obl": ob}))
        return out

    def execute(self, agent, world, cand):
        ob = cand.detail["obl"]
        if ob.status != "ACCEPTED":
            return
        asker = world.agent(ob.frm)
        action = ob.ask["action"]
        if action in ("BRING", "GIVE"):
            item = ob.ask.get("item") or "gold"
            qty = max(1, ob.ask.get("quantity", 1))
            if asker is None or asker.place != agent.place or agent.inv.get(item, 0) < qty:
                return
            agent.inv[item] -= qty
            asker.inv[item] = asker.inv.get(item, 0) + qty
        elif action == "GO_TO":
            if agent.place != ob.ask.get("place"):
                return
        elif action == "FIGHT":
            foe = world.agent(ob.ask.get("target"))
            if foe is None or not foe.alive or foe.place != agent.place:
                return
            Attack().execute(agent, world, Candidate("ATTACK", target=foe.id, place=agent.place))
        elif action == "HELP":
            if world.monster is None or world.monster["place"] != agent.place:
                return
            FightMonster().execute(agent, world,
                                   Candidate("FIGHT_MONSTER", place=agent.place))
        world.fulfil_obligation(ob)


# ---------------------------------------------------------------------------
# Authority: only ever proposed when the settlement has a chief at all
# ---------------------------------------------------------------------------


class Punish(Skill):
    """The chief's one power. Off unless the world was built with a chief."""

    name = "PUNISH"

    def propose(self, agent, world):
        if world.chief_id is None or agent.id != world.chief_id:
            return ()
        pressure = {}
        detail = {}
        for c in world.complaints:
            if c["handled"] or world.tick - c["tick"] > world.complaint_ttl:
                continue
            culprit = world.agent(c["about"])
            if culprit is None or not culprit.alive or culprit.place != agent.place:
                continue
            weight = c["weight"] * (0.6 + 0.4 * min(1.0, c["witnesses"] / 3.0))
            if weight > pressure.get(c["about"], 0.0):
                pressure[c["about"]] = weight
                detail[c["about"]] = c
        # The chief needs nobody's complaint about what it saw with its own eyes.
        forgiveness = agent.mind.traits["forgiveness"]
        for mem in agent.memories:
            if mem.answered or mem.source != "SEEN" or mem.kind not in ("HIT", "STEAL", "KILL"):
                continue
            culprit = world.agent(mem.actor)
            if culprit is None or not culprit.alive or culprit.place != agent.place:
                continue
            weight = mem.salience(world.tick, forgiveness) * 1.1
            if weight > pressure.get(mem.actor, 0.0):
                pressure[mem.actor] = weight
                detail[mem.actor] = mem
        return tuple(
            Candidate(self.name, target=who, place=agent.place,
                      hints={"punish_pressure": min(1.5, w)},
                      detail={"case": detail[who]})
            for who, w in sorted(pressure.items(), key=lambda kv: str(kv[0]))
        )

    def execute(self, agent, world, cand):
        culprit = world.agent(cand.target)
        if culprit is None or not culprit.alive or culprit.place != agent.place:
            return
        case = cand.detail["case"]
        if isinstance(case, dict):
            case["handled"] = True
            victim = world.agent(case["by"])
        else:
            case.answered = True
            victim = world.agent(case.target)
        here = [o for o in world.at(agent.place) if o.id != agent.id]
        fine = 0
        if culprit.inv["gold"] >= 2:
            fine = min(4, culprit.inv["gold"])
            culprit.inv["gold"] -= fine
            if victim is not None and victim.alive and victim.id != culprit.id:
                victim.inv["gold"] += fine
            else:
                agent.inv["gold"] += fine
        parsed = speech.utterance("REBUKE", agent.name, culprit.name,
                                  speech.PLACE_TOPIC.get(agent.place, "NONE"), world.rng,
                                  gold=fine, swear=profanity.for_speaker(world, agent, culprit))
        ev = world.emit("PUNISH", agent, culprit, witnesses=here, place=agent.place,
                        dialogue=parsed["text"], parsed=parsed,
                        extra={"mode": "fine" if fine else "rebuke", "fine": fine,
                               "for": case["kind"] if isinstance(case, dict) else case.kind})
        # A public rebuke is public: everybody standing there thinks less of the culprit.
        for o in here:
            if o.id == culprit.id:
                continue
            shift = shift_opinion(o, culprit.id, {"respect": -0.12, "trust": -0.05}, 1.0)
            if shift:
                ev.setdefault("deltas", []).extend(shift)


#: Instantiated once; a skill holds no per-agent state, like a brain in the mod.
SKILLS = (Work(), Eat(), Drink(), Rest(), Socialize(),
          Steal(), Attack(), Flee(), Apologize(), FightMonster(),
          Ignore(), Retort(), DemandApology(), RefuseApology(), ComplainTo(), Avoid(),
          Gossip(), AcceptAsk(), RefuseAsk(), BargainOver(), Fulfil(), Punish())

SKILL_BY_NAME = {s.name: s for s in SKILLS}
