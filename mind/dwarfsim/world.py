"""The settlement: places, dwarves, things at the gate, and the tick loop.

Six places, fully connected: one move is one tick from anywhere to anywhere. Whoever is in the same
place can be talked to, robbed, hit and seen doing it. Everything random goes through ``world.rng``,
one seeded stream, so a run is reproducible from ``--seed`` alone.

Two inhabitants are not ordinary dwarves:

* **the player** -- speaker id ``"player"``, one relationship row in every dwarf starting neutral.
  It never decides and never ticks; it speaks through :meth:`World.say` and acts through the three
  small doors below (:meth:`player_help`, :meth:`player_give`, :meth:`player_promise`). It has to
  earn trust like anybody else.
* **the chief** -- *off by default*. With ``chief=True`` (or the ``feud-chief`` scenario) one dwarf
  holds ``KEEP_PEACE``, hears complaints and can fine or rebuke. With no chief there is no
  ``PUNISH``, no expected-punishment term, and a complaint goes to a trusted friend instead.
"""

import random

from . import arbitrator, goals, mind, obligations, profanity, skills, speech
from .memory import Memory, MemoryBook
from .obligations import Obligation, RESPONSE_TTL
from .schema import MAX_HEALTH, NAME_POOL, PLACES, PLAYER_ID

MONSTER_KINDS = ("CREEPER", "ZOMBIE")

#: How loud one event is when it is remembered, before the caller's magnitude scales it.
MEMORY_WEIGHT = {
    "INSULT": 0.50, "SLUR": 0.85, "ACCUSE": 0.50, "THREAT": 0.70, "STEAL": 0.90,
    "HIT": 1.00, "KILL": 1.00,
    "RETORT": 0.40, "REFUSE_APOLOGY": 0.60, "BROKEN_PROMISE": 0.90, "PUNISH": 0.80,
    "HELP": 0.70, "GIFT": 0.80, "PROMISE_KEPT": 0.80,
}

#: A witness remembers it less loudly than the one it was done to.
WITNESS_MEMORY = 0.60

#: How long avoiding somebody keeps you from seeking them out.
AVOID_TTL = 150

#: How long a demanded apology hangs in the air.
DEMAND_TTL = 40

#: And how long before the same dwarf may demand of the same dwarf again. Without this, a
#: refusal is itself a fresh provocation and the two of them stand there demanding and
#: refusing every tick until somebody swings.
DEMAND_COOLDOWN = 90

#: How long the chief will still act on a complaint.
COMPLAINT_TTL = 90


class Agent:
    """One dwarf: a body with an inventory, and a mind that remembers and wants things."""

    __slots__ = ("id", "name", "place", "health", "inv", "mind", "alive",
                 "last_hit_by", "last_hit_tick", "grievances", "request",
                 "memories", "goals", "demands", "avoid", "last_struck", "external",
                 "provocations")

    def __init__(self, aid, name, place, rng, external=False):
        self.id = aid
        self.name = name
        self.place = place
        self.health = MAX_HEALTH
        self.inv = {"ore": 0, "gold": rng.randrange(0, 4), "food": rng.randrange(0, 3),
                    "ale": 0, "weapon": round(rng.uniform(0.0, 0.3), 3)}
        self.mind = mind.MindState.random(rng)
        self.alive = True
        self.last_hit_by = None
        self.last_hit_tick = -999
        self.grievances = set()
        self.request = None
        self.memories = MemoryBook()
        self.goals = []
        self.provocations = []   # this tick's unanswered harms, computed once in World.step
        self.demands = {}        # who demanded an apology of me -> [made tick, tick it lapses]
        self.avoid = {}          # who I am steering clear of -> the tick I stop
        self.last_struck = {}    # who I have hit -> when
        self.external = external  # the player: never ticks, never decides

    def heal(self, amount):
        self.health = min(MAX_HEALTH, round(self.health + amount, 2))

    def avoiding(self, other_id, tick):
        return self.avoid.get(other_id, -1) > tick

    def demand_from(self, who):
        """``[made tick, lapse tick]`` for an apology demanded of me, or ``None``."""
        return self.demands.get(who)

    def goal_strength(self, kind, target=None):
        for g in self.goals:
            if g.kind == kind and (target is None or g.target == target):
                return g.strength
        return 0.0

    def __repr__(self):
        return "<%s %s@%s hp=%.1f>" % (self.name, self.id, self.place, self.health)


class World:
    """Ticks, places, dwarves and the monster at the gate."""

    def __init__(self, n_agents=6, seed=1, scenario="default", temperature=None, chief=None,
                 scorer=None, profanity_tier=None, profanity_speech=None, lexicon=None):
        self.rng = random.Random(seed)
        self.seed = seed
        #: How much the settlement swears, 0 to 3, and the words it has for it. Recognition
        #: always reads every tier; :attr:`Lexicon.max_tier` caps only what is said.
        #: ``lexicon`` takes an already-built :class:`~dwarfsim.profanity.Lexicon`.
        if lexicon is None:
            lexicon = profanity.Lexicon.load(
                max_tier=(profanity.DEFAULT_MAX_TIER if profanity_tier is None
                          else profanity_tier),
                speech_path=profanity_speech or profanity.DEFAULT_SPEECH_PATH)
        elif profanity_tier is not None:
            lexicon = lexicon.copy(profanity_tier)
        self.profanity = lexicon
        #: Swearing draws from its own stream, so turning it on moves no other draw in the run
        #: and a seed still reproduces a settlement word for word.
        self.swear_rng = random.Random((seed or 0) * 7919 + 104729)
        self.scenario = scenario
        self.temperature = temperature
        #: ``None`` for the hand-written weight table, or a learned scorer (see
        #: :class:`dwarfsim.learn.LearnedScorer`). Only the scoring changes; everything else,
        #: sampling included, is the same tick loop.
        self.scorer = scorer
        #: When a list, one ``(observation, features, teacher scores, chosen)`` tuple per decision
        #: is appended to it. Used by ``dwarfsim.learn.collect``; ``None`` in an ordinary run.
        self.collector = None
        self.tick = 0
        self.places = PLACES
        self.events = []
        self.monster = None
        self.monster_rate = 0.010      # chance per tick of something turning up at the gate
        self.monster_ttl = 60
        self.monster_damage = 1.4
        self.avoid_ttl = AVOID_TTL
        self.demand_ttl = DEMAND_TTL
        self.demand_cooldown = DEMAND_COOLDOWN
        self.complaint_ttl = COMPLAINT_TTL
        self._rep_tick = -1
        self._rep = {}

        names = list(NAME_POOL)
        self.rng.shuffle(names)
        self.agents = [Agent(i, names[i], PLACES[self.rng.randrange(len(PLACES))], self.rng)
                       for i in range(n_agents)]
        self._by_id = {a.id: a for a in self.agents}
        self._by_name = {a.name: a for a in self.agents}
        self.start_count = len(self.agents)
        self.deaths = []
        self._acquainted()

        # The player: an outsider with a name in the roster and a neutral row in every head.
        self.player = Agent(PLAYER_ID, "Player", PLACES[0], self.rng, external=True)
        self.player.inv["gold"] = 20
        self._by_id[PLAYER_ID] = self.player
        self._by_name["Player"] = self.player
        for a in self.agents:
            a.mind.rel(PLAYER_ID)
            self.player.mind.rel(a.id)

        self.obligations = []          # every obligation ever made, newest last
        self._open_obl = []            # the ones still PENDING or ACCEPTED
        self._next_obl = 1
        self.complaints = []           # complaints the chief is currently sitting on
        self.chief_id = None

        apply_scenario(self, scenario)
        self._install_chief(chief)

    def _acquainted(self):
        """The settlement did not meet this morning.

        Every pair starts a little positive, rolled from the seed. Without this the first hundred
        ticks are a riot: six strangers with nothing, no reason to trust anybody, and stealing as
        the cheapest way to get anything -- and the reaction skills then turn every theft into a
        grudge. Starting acquainted is one line, it is a starting condition rather than a rule, and
        it is what the old settlement reached on its own after two hundred ticks of small talk.
        The player is deliberately not included: a stranger is a stranger.
        """
        for a in self.agents:
            for b in self.agents:
                if a.id == b.id:
                    continue
                r = a.mind.rel(b.id)
                r["trust"] = round(self.rng.uniform(0.10, 0.35), 3)
                r["respect"] = round(self.rng.uniform(0.00, 0.20), 3)

    # -- authority ----------------------------------------------------------

    def _install_chief(self, chief):
        """Opt in to a chief. Off unless asked for: the settlement has none by default."""
        if chief is False or (chief is None and not getattr(self, "_scenario_chief", False)):
            return
        if chief is True or chief is None:
            if not self.agents:
                return
            def standing(a):
                respect = sum(o.mind.rel(a.id)["respect"] for o in self.agents if o.id != a.id)
                return (respect, a.mind.traits["pride"] + a.mind.traits["bravery"], -a.id)
            chief_agent = max(self.agents, key=standing)
        else:
            chief_agent = self.agent(chief)
            if chief_agent is None:
                raise ValueError("no such dwarf to make chief: %r" % (chief,))
        self.chief_id = chief_agent.id
        # A starting condition, not a script: the settlement already respects its chief.
        for a in self.agents:
            if a.id != chief_agent.id:
                a.mind.rel(chief_agent.id)["respect"] = min(1.0, a.mind.rel(
                    chief_agent.id)["respect"] + 0.35)

    def chief(self):
        return self.agent(self.chief_id) if self.chief_id is not None else None

    # -- lookups ------------------------------------------------------------

    def agent(self, aid):
        return self._by_id.get(aid) if aid is not None else None

    def agent_by_name(self, name):
        return self._by_name.get(name)

    def living(self):
        return [a for a in self.agents if a.alive]

    def others(self, agent):
        """Everyone a dwarf can hold an opinion of: the living, plus the player."""
        out = [a for a in self.agents if a.alive and a.id != agent.id]
        if agent.id != PLAYER_ID:
            out.append(self.player)
        return out

    def at(self, place):
        return [a for a in self.agents if a.alive and a.place == place]

    def monster_at(self, place):
        m = self.monster
        return m if (m is not None and m["place"] == place) else None

    # -- memory -------------------------------------------------------------

    def remember(self, holder, kind, actor, target, place, intensity,
                 source="SEEN", from_id=None, witnesses=0, at_tick=None):
        """Put one episode in one head. The only door into :mod:`dwarfsim.memory`.

        ``at_tick`` is when the thing being remembered actually happened, which for hearsay
        is earlier than now; it is what lets two heads recognise the same episode. Returns
        the memory, or ``None`` if this head already had it.
        """
        if holder is None or not holder.alive or intensity <= 0.02:
            return None
        when = self.tick if at_tick is None else at_tick
        return holder.memories.remember(
            self.tick, holder.mind.traits["forgiveness"],
            Memory(when, kind, actor, target, place, intensity, source, from_id, witnesses))

    def _remember_event(self, kind, actor, target, witnesses, magnitude, place):
        """Everybody who was there files this away, the victim louder than the onlookers."""
        base = MEMORY_WEIGHT.get(kind)
        if base is None or actor is None:
            return
        aid = actor.id
        others = [w for w in witnesses
                  if w.alive and w.id != aid and (target is None or w.id != target.id)]
        n = len(others)
        intensity = base * magnitude
        if target is not None and target.alive and target.id != aid:
            self.remember(target, kind, aid, target.id, place, intensity, "SUFFERED",
                          witnesses=n)
        for w in others:
            self.remember(w, kind, aid, target.id if target else None, place,
                          intensity * WITNESS_MEMORY, "SEEN", witnesses=n)

    # -- events -------------------------------------------------------------

    def emit(self, kind, actor=None, target=None, witnesses=None, place=None,
             dialogue=None, parsed=None, magnitude=1.0, apply=True, extra=None):
        """Record one event and, if it is a social one, apply its mind deltas.

        Returns the logged dict. ``witnesses`` defaults to everyone else in the place.
        """
        if place is None:
            place = actor.place if actor is not None else None
        if witnesses is None:
            witnesses = [o for o in self.at(place)] if place is not None else []
        deltas = []
        if apply and kind in mind.EVENT_TABLE:
            deltas = mind.apply_event(kind, actor, target, witnesses, magnitude)
            self._remember_event(kind, actor, target, witnesses, magnitude, place)
        ev = {
            "type": kind,
            "actor": actor.id if actor is not None else None,
            "target": target.id if target is not None else None,
            "place": place,
        }
        if dialogue:
            ev["text"] = dialogue
        if parsed is not None:
            ev["intent"] = parsed.get("intent")
            ev["topic"] = parsed.get("topic")
            if parsed.get("act"):
                ev["act"] = parsed["act"]
            if parsed.get("profanity"):
                ev["profanity"] = list(parsed["profanity"])
        if extra:
            ev.update(extra)
        if deltas:
            ev["deltas"] = deltas
        self.events.append(ev)
        return ev

    def say(self, speaker, listener, parsed):
        """Inject an utterance, from a dwarf or from a player at the keyboard.

        ``speaker`` and ``listener`` are agent ids, names or :class:`Agent` objects; ``parsed`` is a
        dict with the ``text/SCHEMA.md`` fields plus the optional ``ask``. The player speaks from
        wherever the listener is standing, so the same room hears it.
        """
        sid = self._id_of(speaker)
        lid = self._id_of(listener)
        if sid == PLAYER_ID:
            heard_by = self.agent(lid)
            if heard_by is not None:
                self.player.place = heard_by.place
        return speech.hear(self, sid, lid, parsed)

    def _id_of(self, who):
        if who is None:
            return None
        if isinstance(who, Agent):
            return who.id
        if isinstance(who, str):
            if who == PLAYER_ID:
                return PLAYER_ID
            a = self.agent_by_name(who)
            return a.id if a else None
        return who

    def kill(self, victim, killer, cause=None):
        """Remove a dwarf. Everyone watching takes it hard, and takes a side."""
        witnesses = [o for o in self.at(victim.place) if o.id != victim.id]
        victim.health = 0.0
        self.emit("KILL", killer, victim, witnesses=witnesses, place=victim.place,
                  extra={"cause": cause or (killer.name if killer else "wounds"),
                         "victim": victim.name})
        victim.alive = False
        self.deaths.append({"tick": self.tick, "who": victim.id,
                            "by": killer.id if killer else None, "cause": cause})

    # -- obligations --------------------------------------------------------

    def propose_obligation(self, asker, obliged, ask, deadline=None):
        """Somebody has asked somebody for something. Returns the new, PENDING obligation."""
        if deadline is None:
            deadline = self.tick + obligations.DEFAULT_DEADLINE
        ob = Obligation(self._next_obl, asker.id, obliged.id, ask, self.tick, deadline)
        self._next_obl += 1
        self.obligations.append(ob)
        self._open_obl.append(ob)
        return ob

    def obligations_for(self, agent):
        """``(asks waiting on my answer, asks I have taken on, counter-offers waiting on me)``."""
        pending, accepted, counters = [], [], []
        for ob in self._open_obl:
            if ob.to == agent.id:
                if ob.status == "PENDING" and ob.counter == 0:
                    pending.append(ob)
                elif ob.status == "ACCEPTED":
                    accepted.append(ob)
            elif ob.frm == agent.id and ob.status == "PENDING" and ob.counter > 0:
                counters.append(ob)
        return pending, accepted, counters

    def accept_obligation(self, ob, by):
        """Take the ask on. Gold offered changes hands now, which is what makes breaking it hurt."""
        asker = self.agent(ob.frm)
        obliged = self.agent(ob.to)
        ob.status = "ACCEPTED"
        ob.accepted_tick = self.tick
        pay = ob.payment()
        if pay > 0 and asker is not None and obliged is not None:
            moved = min(pay, asker.inv["gold"])
            asker.inv["gold"] -= moved
            obliged.inv["gold"] += moved
            ob.paid = moved
        place = (by.place if by is not None else None)
        return self.emit("ACCEPT", by, self.agent(ob.frm if by.id == ob.to else ob.to),
                         place=place, extra={"obl": ob.oid, "paid": ob.paid,
                                             "ask": dict(ob.ask)})

    def fulfil_obligation(self, ob):
        """The ask got done. Trust goes up both ways and everyone present sees it."""
        obliged = self.agent(ob.to)
        asker = self.agent(ob.frm)
        ob.status = "KEPT"
        if ob in self._open_obl:
            self._open_obl.remove(ob)
        place = obliged.place if obliged is not None else (
            asker.place if asker is not None else None)
        return self.emit("PROMISE_KEPT", obliged, asker, place=place,
                         extra={"obl": ob.oid, "ask": dict(ob.ask), "paid": ob.paid})

    def break_obligation(self, ob, reason="deadline"):
        """The deadline passed. A BROKEN_PROMISE is a memory gossip is happy to carry."""
        obliged = self.agent(ob.to)
        asker = self.agent(ob.frm)
        ob.status = "BROKEN"
        if ob in self._open_obl:
            self._open_obl.remove(ob)
        if obliged is None or asker is None or not asker.alive:
            return None
        place = asker.place
        witnesses = self.at(place)
        return self.emit("BROKEN_PROMISE", obliged, asker, witnesses=witnesses, place=place,
                         extra={"obl": ob.oid, "ask": dict(ob.ask), "why": reason,
                                "paid": ob.paid})

    def _tick_obligations(self):
        for ob in list(self._open_obl):
            obliged = self.agent(ob.to)
            asker = self.agent(ob.frm)
            if obliged is None or not obliged.alive or asker is None or not asker.alive:
                ob.status = "EXPIRED"
                self._open_obl.remove(ob)
                continue
            if ob.status == "PENDING" and self.tick - ob.made > RESPONSE_TTL:
                ob.status = "EXPIRED"
                self._open_obl.remove(ob)
                self.emit("OBLIGATION_LAPSED", asker, obliged, place=asker.place, apply=False,
                          extra={"obl": ob.oid, "ask": dict(ob.ask)})
                continue
            if ob.status == "ACCEPTED" and self.tick >= ob.deadline:
                if ob.ask["action"] == "STOP" and not self._broke_stop(ob):
                    self.fulfil_obligation(ob)      # kept by not doing the thing
                else:
                    self.break_obligation(ob)

    def _broke_stop(self, ob):
        """A STOP promise is kept by an absence: no harm from me to them since I took it on."""
        obliged = self.agent(ob.to)
        if obliged is None:
            return True
        since = ob.accepted_tick or ob.made
        return obliged.last_struck.get(ob.frm, -999) >= since

    # -- the player's three doors ------------------------------------------

    def player_help(self, dwarf, magnitude=1.0):
        """The player stood between a dwarf and something at the gate."""
        dwarf = self.agent(self._id_of(dwarf))
        self.player.place = dwarf.place
        witnesses = [o for o in self.at(dwarf.place) if o.id != dwarf.id]
        return self.emit("HELP", self.player, dwarf, witnesses=witnesses, place=dwarf.place,
                         magnitude=magnitude, extra={"by": "player"})

    def player_give(self, dwarf, gold=3):
        """The player handed over gold. It actually moves."""
        dwarf = self.agent(self._id_of(dwarf))
        moved = min(gold, self.player.inv["gold"])
        self.player.inv["gold"] -= moved
        dwarf.inv["gold"] += moved
        self.player.place = dwarf.place
        return self.emit("GIFT", self.player, dwarf, place=dwarf.place,
                         magnitude=min(1.6, 0.5 + 0.25 * moved),
                         extra={"item": "gold", "count": moved, "by": "player"})

    def player_promise(self, dwarf, ask, deadline=None):
        """The player promises a dwarf something: an obligation with the player on the hook."""
        dwarf = self.agent(self._id_of(dwarf))
        self.player.place = dwarf.place
        ob = self.propose_obligation(dwarf, self.player, ask, deadline)
        ob.status = "ACCEPTED"
        ob.accepted_tick = self.tick
        self.emit("ACCEPT", self.player, dwarf, place=dwarf.place,
                  extra={"obl": ob.oid, "ask": dict(ask), "by": "player"})
        return ob

    # -- the tick -----------------------------------------------------------

    def sense(self):
        """Refresh what each dwarf thinks is still owed an answer.

        One pass over each head per tick, because all six reaction skills read the same
        list and scanning a memory book five times a tick is the one thing in here that
        would actually be slow.
        """
        for a in self.agents:
            if not a.alive:
                continue
            open_ones = []
            for mem in a.memories.open_provocations(self.tick, a):
                other = self.agent(mem.actor)
                if other is None or not other.alive:
                    mem.answered = True     # settled, one way or another
                    continue
                open_ones.append(mem)
            a.provocations = open_ones


    def step(self):
        """One tick: the world moves, minds drift, then everyone decides and acts."""
        self.tick += 1
        self.events = []
        self._tick_monster()
        self._tick_obligations()
        for a in self.agents:
            if not a.alive:
                continue
            a.mind.decay()
            a.memories.invalidate()
            if a.mind.needs["fatigue"] < 0.6 and a.health < MAX_HEALTH:
                a.heal(0.06)
            if a.mind.needs["hunger"] > 0.95 or a.mind.needs["thirst"] > 0.97:
                a.health = round(a.health - 0.08, 2)
                if a.health <= 0.0:
                    self.kill(a, None, cause="starvation")

        self.sense()
        if self.complaints and self.tick % 20 == 0:
            self.complaints = [c for c in self.complaints
                               if not c["handled"] and self.tick - c["tick"] <= self.complaint_ttl]

        for a in self.agents:
            if not a.alive or (self.tick + a.id) % goals.REVIEW_EVERY:
                continue
            adopted, dropped = goals.review(a, self)
            for g in adopted:
                self.emit("GOAL_ADOPTED", a, self.agent(g.target), place=a.place, apply=False,
                          extra={"goal": g.kind, "strength": round(g.strength, 3)})
            for g in dropped:
                self.emit("GOAL_DROPPED", a, self.agent(g.target), place=a.place, apply=False,
                          extra={"goal": g.kind, "strength": round(g.strength, 3)})

        decisions = []
        for a in self.agents:
            if not a.alive:
                continue
            chosen, cands = arbitrator.decide(a, self, self.temperature,
                                              scorer=self.scorer, collect=self.collector)
            if chosen is None:
                continue
            record = arbitrator.explain(cands, chosen)
            if chosen.place is not None and chosen.place != a.place:
                # Walking there is this tick's action. distance_cost already priced it.
                a.place = chosen.place
                record["moved"] = chosen.place
                self.emit("MOVE", a, place=chosen.place, apply=False,
                          extra={"for": chosen.label()})
            else:
                skills.SKILL_BY_NAME[chosen.skill].execute(a, self, chosen)
            record["agent"] = a.id
            decisions.append(record)

        return {"tick": self.tick, "events": self.events, "decisions": decisions}

    def _tick_monster(self):
        if self.monster is None:
            if self.rng.random() < self.monster_rate:
                kind = MONSTER_KINDS[self.rng.randrange(len(MONSTER_KINDS))]
                self.monster = {"kind": kind, "place": "GATE", "hp": 10.0, "max_hp": 10.0,
                                "since": self.tick}
                present = self.at("GATE")
                self.emit("MONSTER_ARRIVES", None, None, witnesses=present, place="GATE",
                          apply=False, extra={"kind": kind})
                if present:
                    self.emit("THREAT", None, None, witnesses=present, place="GATE",
                              magnitude=0.8, extra={"kind": kind, "source": "monster"})
            return

        m = self.monster
        present = self.at(m["place"])
        if self.tick - m["since"] > self.monster_ttl:
            self.monster = None
            self.emit("MONSTER_LEAVES", None, None, witnesses=present, place=m["place"],
                      apply=False, extra={"kind": m["kind"]})
            return
        if present:
            bitten = present[self.rng.randrange(len(present))]
            dmg = round(self.monster_damage * self.rng.uniform(0.5, 1.3), 2)
            bitten.health = round(bitten.health - dmg, 2)
            bitten.last_hit_tick = self.tick
            self.emit("MONSTER_ATTACK", None, bitten, witnesses=present, place=m["place"],
                      apply=False, extra={"kind": m["kind"], "damage": dmg,
                                          "health": max(0.0, bitten.health)})
            self.emit("THREAT", None, bitten, witnesses=present, place=m["place"],
                      magnitude=0.7, extra={"source": "monster"})
            if bitten.health <= 0.0:
                self.kill(bitten, None, cause=m["kind"])

    # -- for the log --------------------------------------------------------

    def reputation(self):
        """What everyone currently believes about each dwarf, averaged.

        This is the number nobody holds: it is the settlement's opinion, assembled from every
        living head, and it is what gossip actually moves.
        """
        if self._rep_tick == self.tick:
            return self._rep
        subjects = [a for a in self.agents if a.alive] + [self.player]
        holders = [a for a in self.agents if a.alive]
        out = {}
        for s in subjects:
            t = r = h = 0.0
            n = 0
            for holder in holders:
                if holder.id == s.id:
                    continue
                rel = holder.mind.rels.get(s.id)
                if rel is None:
                    continue
                t += rel["trust"]
                r += rel["respect"]
                h += rel["hatred"]
                n += 1
            if n:
                out[str(s.id)] = [round(t / n, 3), round(r / n, 3), round(h / n, 3), n]
        self._rep_tick, self._rep = self.tick, out
        return out

    def snapshot(self, with_rels=True, with_mind=True):
        out = {}
        for a in self.agents:
            if not a.alive:
                continue
            s = {
                "place": a.place,
                "health": a.health,
                "emotions": a.mind.emotions,
                "needs": a.mind.needs,
                "inv": a.inv,
            }
            if with_rels:
                s["rels"] = {str(k): [v["trust"], v["respect"], v["hatred"]]
                             for k, v in a.mind.rels.items()}
            if with_mind:
                s["goals"] = [g.snapshot() for g in a.goals]
                s["mem"] = a.memories.top(self.tick, a.mind.traits["forgiveness"], 8)
                s["obl"] = [ob.snapshot() for ob in self._open_obl
                            if ob.frm == a.id or ob.to == a.id]
            out[str(a.id)] = s
        return out

    def roster(self):
        row = [{"id": a.id, "name": a.name, "traits": a.mind.traits,
                "baseline": a.mind.baseline, "place": a.place,
                "chief": a.id == self.chief_id}
               for a in self.agents]
        row.append({"id": self.player.id, "name": self.player.name, "external": True,
                    "traits": {}, "baseline": {}, "place": self.player.place, "chief": False})
        return row


# ---------------------------------------------------------------------------
# Scenarios: small pushes on the starting state, never on the decisions
# ---------------------------------------------------------------------------

#: The three targeted settlements the reward stage trains on, defined in
#: :mod:`dwarfsim.learn.scenarios` and imported lazily so the sim proper stays dependency-free.
LEARN_SCENARIOS = ("friends", "bully", "thief")

SCENARIOS = ("default", "feud", "feud-chief", "theft", "raid", "gossip",
             "player") + LEARN_SCENARIOS


def apply_scenario(world, name):
    """Set up starting conditions only. Nothing here scripts an action."""
    world._scenario_chief = False
    if name == "default":
        return
    if name in LEARN_SCENARIOS:
        from .learn.scenarios import apply as apply_learn
        apply_learn(world, name)
        return
    if name in ("feud", "feud-chief"):
        a, b = world.agents[0], world.agents[1]
        for x, y in ((a, b), (b, a)):
            r = x.mind.rel(y.id)
            r["trust"] = -0.55
            r["respect"] = -0.20
            r["hatred"] = 0.25
            x.mind.traits["temper"] = 0.90
            x.mind.traits["sociability"] = max(x.mind.traits["sociability"], 0.60)
            x.mind.baseline["anger"] = 0.18
        b.place = a.place
        # A spread of pride and forgiveness is what makes the reactions differ between dwarves.
        for i, x in enumerate(world.agents):
            x.mind.traits["pride"] = round(0.15 + 0.70 * ((i * 7) % 5) / 4.0, 3)
            x.mind.traits["forgiveness"] = round(0.15 + 0.70 * ((i * 3) % 5) / 4.0, 3)
        world._scenario_chief = (name == "feud-chief")
        return
    if name == "theft":
        a = world.agents[0]
        a.mind.traits["greed"] = 0.95
        a.mind.traits["temper"] = 0.60
        a.inv["gold"] = 0
        return
    if name == "raid":
        world.monster_rate = 0.035
        world.monster_ttl = 35
        return
    if name == "gossip":
        # One sour pair, and a settlement of sociable talkers who remember everything. Nothing
        # says a feud must spread; the question is whether it does.
        a, b = world.agents[0], world.agents[1]
        a.mind.traits["temper"] = 0.85
        a.mind.traits["pride"] = 0.80
        a.mind.rel(b.id)["hatred"] = 0.35
        a.mind.rel(b.id)["trust"] = -0.45
        b.mind.rel(a.id)["hatred"] = 0.20
        b.place = a.place
        for i, x in enumerate(world.agents):
            x.mind.traits["sociability"] = round(min(0.95, 0.60 + 0.08 * i), 3)
            x.mind.traits["forgiveness"] = round(0.15 + 0.15 * (i % 3), 3)
            x.mind.needs["social"] = 0.55
        # Two friendships, so there is somebody for the feud to spread to.
        for friend in world.agents[2:4]:
            friend.mind.rel(b.id)["trust"] = 0.55
            friend.mind.rel(b.id)["respect"] = 0.30
            b.mind.rel(friend.id)["trust"] = 0.55
        return
    if name == "player":
        # Ordinary dwarves, a little wary, so a stranger's orders are worth nothing until earned.
        for x in world.agents:
            x.mind.traits["greed"] = round(min(0.9, x.mind.traits["greed"] * 0.8 + 0.1), 3)
            x.mind.rel(PLAYER_ID)["trust"] = 0.0
        return
    raise ValueError("unknown scenario %r (one of %s)" % (name, ", ".join(SCENARIOS)))
