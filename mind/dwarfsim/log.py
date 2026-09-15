"""The log: one JSON object per tick, plus a header and a summary.

The log is the product. Everything the viewer shows and every claim anyone makes about a run has to
come out of this file, so it carries the complete decision breakdown and the complete event deltas
for every tick, and nothing is reconstructed afterwards.

Layout of ``run.jsonl``::

    {"type":"header", ...}          schema id, seed, scenario, roster, term and skill names
    {"tick":1, "events":[...], "decisions":[...], "agents":{...}}
    ...
    {"type":"summary", ...}         deaths, fights, feuds, thefts, the narrated story

Size: events and decisions are complete on every tick. The per-agent snapshot is complete on every
tick too, except the four slow blocks -- relationships, goals, memories and open obligations --
which are written every ``REL_EVERY`` ticks (and on the first and last), along with the
settlement-wide reputation row. Six dwarves times 2,000 ticks lands around 25 MB. All four move
slowly enough that a tenth-resolution trace is honest, and writing memories every tick roughly
doubles the file for nothing.
"""

import json

from . import condition
from .schema import (ASK_ACTIONS, CAND_SIZE, GOAL_KINDS, MEMORY_CAP, MEMORY_SOURCES, OBS_SIZE,
                     PLACES, REACTION_SKILLS, SCHEMA_ID, SKILL_NAMES, TERM_NAMES)

#: How often the full relationship matrix is snapshotted.
REL_EVERY = 10

#: Mutual hatred above this, both ways, counts as a feud.
FEUD_THRESHOLD = 0.50

#: Cap on the narrated story, so it stays a story.
STORY_CAP = 220

#: The ways a dwarf can answer being provoked, for the summary's tally. A fist is one of them.
REACTION_KINDS = ("IGNORE", "RETORT", "DEMAND", "COMPLAIN", "AVOID", "HIT")

#: What counts as a provocation somebody might answer.
PROVOKING_KINDS = ("INSULT", "SLUR", "ACCUSE", "THREAT", "STEAL", "HIT", "REFUSE_APOLOGY")

#: How long an answer still reads as an answer to that provocation.
ANSWER_WINDOW = 45


def _round(o, n=3):
    """Round every float in a structure, so the file is not full of 17-digit noise."""
    if isinstance(o, float):
        return round(o, n)
    if isinstance(o, dict):
        return {k: _round(v, n) for k, v in o.items()}
    if isinstance(o, list):
        return [_round(v, n) for v in o]
    return o


GOAL_PHRASE = {
    "GET_RICH": "get rich",
    "AVENGE": "avenge %s",
    "PROTECT": "stand over %s",
    "REPAY": "repay %s",
    "BEFRIEND": "make a friend of %s",
    "KEEP_PEACE": "keep the peace",
}


def _goal_phrase(kind, who):
    """\"avenge itself on Torvi\" -- the auto-narration of one want."""
    form = GOAL_PHRASE.get(kind, str(kind).lower())
    return (form % who) if ("%s" in form and who) else form.replace(" %s", "")


def _delta(ev, who, field):
    """Find a ``from -> to`` pair in an event's deltas, or None."""
    for d in ev.get("deltas", ()):
        if d["who"] == who and d["f"] == field:
            return d["from"], d["to"]
    return None


class Recorder:
    """Writes the JSONL as the run goes, and keeps the tallies the summary needs."""

    def __init__(self, path, world, ticks):
        self.path = path
        self.names = {a.id: a.name for a in world.agents}
        self.names[world.player.id] = world.player.name
        self.ticks = ticks
        self.fh = open(path, "w", encoding="utf-8", newline="\n")
        self.bytes = 0
        self.story = []
        self.hits = 0
        self.hit_pairs = {}
        self.seen_pairs = set()
        self.thefts = []
        self.feuds = {}
        self.monsters_arrived = 0
        self.monsters_slain = 0
        self.reactions = {}          # reaction kind -> how many times it was chosen
        self.answers = {}            # how provocations actually got answered
        self.provoked = 0            # provocations anybody suffered
        self.insults = 0
        self.insult_then_hit = 0
        self.gossip = 0
        self.promises = {"made": 0, "accepted": 0, "kept": 0, "broken": 0,
                         "refused": 0, "bargained": 0, "gold": 0}
        self.punishments = []
        self.goals_adopted = 0
        #: Injuries dealt, by kind, and how many of them mended before the run ended. The
        #: settlement's casualty list, which is the honest answer to "was that brawl bad".
        self.injuries = {}
        self.recoveries = 0
        self.swears = 0
        self.swear_tiers = {1: 0, 2: 0, 3: 0}
        self._open_prov = {}         # (victim, provoker) -> tick, until it is answered
        self._write({
            "type": "header",
            "schema": SCHEMA_ID,
            "seed": world.seed,
            "scenario": world.scenario,
            "ticks": ticks,
            "places": list(PLACES),
            "agents": _round(world.roster()),
            "skills": list(SKILL_NAMES),
            "terms": list(TERM_NAMES),
            "obs_size": OBS_SIZE,
            "cand_size": CAND_SIZE,
            "rel_every": REL_EVERY,
            "goals": list(GOAL_KINDS),
            "asks": list(ASK_ACTIONS),
            "sources": list(MEMORY_SOURCES),
            "reactions": list(REACTION_SKILLS),
            "memory_cap": MEMORY_CAP,
            "chief": world.chief_id,
            "player": world.player.id,
        })

    def _write(self, obj):
        line = json.dumps(obj, separators=(",", ":"))
        self.fh.write(line)
        self.fh.write("\n")
        self.bytes += len(line) + 1

    def name(self, aid):
        return self.names.get(aid, "someone")

    def record(self, world, step):
        tick = step["tick"]
        slow = (tick % REL_EVERY == 0) or tick == 1 or tick == self.ticks
        for ev in step["events"]:
            self._note(world, tick, ev)
        self._check_feuds(world, tick)
        row = {
            "tick": tick,
            "events": step["events"],
            "decisions": step["decisions"],
            "agents": world.snapshot(with_rels=slow, with_mind=slow),
        }
        if slow:
            row["rep"] = world.reputation()
        self._write(_round(row))

    # -- narration ----------------------------------------------------------

    def _add(self, tick, priority, text):
        # seq keeps several moments in one tick in the order they happened, whatever their priority.
        self.story.append({"tick": tick, "seq": len(self.story), "priority": priority, "text": text})

    def _answered(self, tick, ev, kind):
        """Close the books on one provocation, and say how it was answered.

        A reaction counts as an answer when the dwarf who suffered something aims one of the
        six reaction kinds back at whoever did it, within ANSWER_WINDOW ticks. The tally this
        keeps is the spread the whole feature exists to produce.
        """
        if kind not in REACTION_KINDS or ev["actor"] is None:
            return
        other = ev.get("about") if kind == "COMPLAIN" else ev["target"]
        if other is None:
            return
        when = self._open_prov.get((ev["actor"], other))
        if when is None or tick - when > ANSWER_WINDOW:
            return
        del self._open_prov[(ev["actor"], other)]
        self.answers[kind] = self.answers.get(kind, 0) + 1
        if kind == "HIT":
            self.insult_then_hit += 1

    def _note(self, world, tick, ev):
        """Tally an event and, if it is worth telling, narrate it in plain English."""
        kind = ev["type"]
        if ev.get("profanity"):
            self.swears += 1
            for row in ev["profanity"]:
                try:
                    n = int(row.get("tier", 0))
                except (TypeError, ValueError):
                    continue
                if n in self.swear_tiers:
                    self.swear_tiers[n] += 1
        actor = self.name(ev["actor"]) if ev["actor"] is not None else None
        target = self.name(ev["target"]) if ev["target"] is not None else None
        place = ev.get("place")
        self._answered(tick, ev, kind)
        victim = ev.get("victim") if kind == "STEAL" else ev["target"]
        if kind in PROVOKING_KINDS and ev["actor"] is not None and victim is not None \
                and victim != ev["actor"]:
            self._open_prov[(victim, ev["actor"])] = tick
            self.provoked += 1

        if kind == "HIT":
            self.hits += 1
            self._react("HIT")
            pair = tuple(sorted((ev["actor"], ev["target"])))
            self.hit_pairs[pair] = self.hit_pairs.get(pair, 0) + 1
            first = pair not in self.seen_pairs
            self.seen_pairs.add(pair)
            # A brawl is thirty blows. Tell the first, the ones that nearly finish someone, and
            # every fourth in between; the timeline still has all of them.
            nearly = ev.get("health", 20.0) <= 5.0
            hurt = self._injured(tick, ev, target)
            if first or nearly or hurt or self.hit_pairs[pair] % 4 == 0:
                d = _delta(ev, ev["target"], "fear")
                tail = "; %s's fear %.2f -> %.2f" % (target, d[0], d[1]) if d else ""
                with_what = " with an edge" if ev.get("armed") else ""
                self._add(tick, 4 if self._serious(ev) else (3 if (first or hurt) else
                                                             (2 if nearly else 1)),
                          "%s%s struck %s%s at the %s for %.1f (down to %.1f hp)%s%s" % (
                              actor, " first" if first else "", target, with_what, place,
                              ev.get("damage", 0.0), ev.get("health", 0.0), hurt, tail))
        elif kind == "KILL":
            by = ("%s killed" % actor) if actor else "the %s killed" % ev.get("cause", "dark")
            witnesses = sorted({d["who"] for d in ev.get("deltas", ()) if d["f"] == "grief"}
                               - {ev["actor"]})
            seen = (", watched by %s" % ", ".join(self.name(w) for w in witnesses)) \
                if witnesses else ""
            self._add(tick, 5, "%s %s at the %s%s" % (by, target, place, seen))
        elif kind == "STEAL":
            self.thefts.append({"tick": tick, "thief": ev["actor"], "victim": ev.get("victim"),
                                "item": ev.get("item"), "seen": ev.get("seen", False)})
            victim = self.name(ev.get("victim"))
            wit = ev.get("witnesses") or []
            seen = (", witnessed by %s" % ", ".join(self.name(w) for w in wit)) if wit else \
                   ("" if ev.get("seen") else ", unnoticed")
            d = _delta(ev, ev["target"], "anger") if ev["target"] is not None else None
            tail = "; %s's anger %.2f -> %.2f" % (victim, d[0], d[1]) if d else ""
            self._add(tick, 4, "%s stole %s from %s at the %s%s%s" % (
                actor, ev.get("item", "something"), victim, place, seen, tail))
        elif kind == "MONSTER_ARRIVES":
            self.monsters_arrived += 1
            here = [a.name for a in world.at(place)]
            self._add(tick, 2, "a %s came to the %s%s" % (
                ev.get("kind", "thing").lower(), place,
                (", with %s standing there" % ", ".join(here)) if here else ", to an empty gate"))
        elif kind == "MONSTER_SLAIN":
            self.monsters_slain += 1
            self._add(tick, 4, "%s put down the %s at the %s" % (
                actor, ev.get("kind", "thing").lower(), place))
        elif kind == "INSULT":
            self.insults += 1
            d = _delta(ev, ev["target"], "anger")
            tail = "; %s's anger %.2f -> %.2f" % (target, d[0], d[1]) if d else ""
            self._add(tick, 2, "%s to %s at the %s: \"%s\"%s" % (
                actor, target, place, ev.get("text", "..."), tail))
        elif kind == "SLUR":
            self.insults += 1
            d = _delta(ev, ev["target"], "rel:%s:hatred" % ev["actor"])
            tail = "; %s's hatred of %s %.2f -> %.2f" % (target, actor, d[0], d[1]) if d else ""
            self._add(tick, 5, "%s to %s at the %s: \"%s\"%s" % (
                actor, target, place, ev.get("text", "..."), tail))
        elif kind == "APOLOGY":
            d = _delta(ev, ev["target"], "rel:%s:hatred" % ev["actor"])
            if d and d[0] - d[1] > 0.05:
                self._add(tick, 3, "%s apologised to %s at the %s; %s's hatred %.2f -> %.2f" % (
                    actor, target, place, target, d[0], d[1]))
        elif kind == "THREAT" and ev["actor"] is not None:
            self._add(tick, 1, "%s threatened %s at the %s: \"%s\"" % (
                actor, target, place, ev.get("text", "...")))

        # -- the graded reactions ------------------------------------------------
        elif kind == "IGNORE":
            self._react("IGNORE")
            self._add(tick, 2, "%s let what %s did pass, and said nothing" % (actor, target))
        elif kind == "RETORT":
            self._react("RETORT")
            self._add(tick, 2, "%s gave it back to %s at the %s: \"%s\"" % (
                actor, target, place, ev.get("text", "...")))
        elif kind == "DEMAND":
            self._react("DEMAND")
            self._add(tick, 3, "%s demanded an apology of %s at the %s: \"%s\"" % (
                actor, target, place, ev.get("text", "...")))
        elif kind == "REFUSE_APOLOGY":
            self._react("REFUSE_APOLOGY")
            d = _delta(ev, ev["target"], "rel:%s:hatred" % ev["actor"])
            tail = "; %s's hatred %.2f -> %.2f" % (target, d[0], d[1]) if d else ""
            self._add(tick, 4, "%s refused %s an apology at the %s: \"%s\"%s" % (
                actor, target, place, ev.get("text", "..."), tail))
        elif kind == "COMPLAIN":
            self._react("COMPLAIN")
            about = self.name(ev.get("about"))
            who = "the chief" if ev.get("chief") else target
            shift = _delta(ev, ev["target"], "rel:%s:hatred" % ev.get("about"))
            tail = "; %s's hatred of %s %.2f -> %.2f" % (target, about, shift[0], shift[1]) \
                if shift else ""
            self._add(tick, 3, "%s complained to %s about %s (%s) at the %s: \"%s\"%s" % (
                actor, who, about, ev.get("kind", "it").lower(), place,
                ev.get("text", "..."), tail))
        elif kind == "AVOID":
            self._react("AVOID")
            self._add(tick, 2, "%s walked out of the %s rather than face %s, and will keep clear" % (
                actor, place, target))

        # -- gossip and reputation -----------------------------------------------
        elif kind == "GOSSIP":
            self.gossip += 1
            about = self.name(ev.get("about"))
            shift = _delta(ev, ev["target"], "rel:%s:hatred" % ev.get("about"))
            if shift is None:
                shift = _delta(ev, ev["target"], "rel:%s:trust" % ev.get("about"))
                field = "trust"
            else:
                field = "hatred"
            # Most gossip moves an opinion by a hundredth. Telling every one of those would be
            # the whole story; the timeline still has them all.
            if shift and abs(shift[1] - shift[0]) >= 0.02:
                self._add(tick, 2, "%s told %s about %s at the %s: \"%s\" -- %s's %s of %s "
                                   "%.2f -> %.2f" % (
                              actor, target, about, place, ev.get("text", "..."),
                              target, field, about, shift[0], shift[1]))

        # -- asks, bargains and promises ------------------------------------------
        elif kind == "ASK":
            self.promises["made"] += 1
            ask = ev.get("ask") or {}
            self._add(tick, 1, "%s asked %s to %s%s at the %s: \"%s\"" % (
                actor, target, ask.get("action", "help").lower(),
                (" %s" % ask["item"]) if ask.get("item") else "",
                place, ev.get("text", "...")))
        elif kind == "ACCEPT":
            self.promises["accepted"] += 1
            self.promises["gold"] += ev.get("paid", 0) or 0
            paid = (", %d gold up front" % ev["paid"]) if ev.get("paid") else ""
            self._add(tick, 2, "%s took on %s's ask%s" % (actor, target, paid))
        elif kind == "REFUSE":
            self.promises["refused"] += 1
            self._add(tick, 1, "%s refused %s: \"%s\"" % (actor, target, ev.get("text", "...")))
        elif kind == "BARGAIN":
            self.promises["bargained"] += 1
            self._add(tick, 2, "%s haggled with %s over it: \"%s\"" % (
                actor, target, ev.get("text", "...")))
        elif kind == "PROMISE_KEPT":
            self.promises["kept"] += 1
            d = _delta(ev, ev["target"], "rel:%s:trust" % ev["actor"])
            tail = "; %s's trust in %s %.2f -> %.2f" % (target, actor, d[0], d[1]) if d else ""
            self._add(tick, 3, "%s kept the promise to %s%s" % (actor, target, tail))
        elif kind == "BROKEN_PROMISE":
            self.promises["broken"] += 1
            d = _delta(ev, ev["target"], "rel:%s:trust" % ev["actor"])
            tail = "; %s's trust in %s %.2f -> %.2f" % (target, actor, d[0], d[1]) if d else ""
            self._add(tick, 4, "%s broke the promise to %s%s" % (actor, target, tail))

        # -- authority, when there is any -----------------------------------------
        elif kind == "PUNISH":
            self.punishments.append({"tick": tick, "chief": ev["actor"], "who": ev["target"],
                                     "mode": ev.get("mode"), "fine": ev.get("fine", 0),
                                     "for": ev.get("for")})
            how = ("fined %s %d gold" % (target, ev["fine"])) if ev.get("fine") \
                else ("rebuked %s in front of the hall" % target)
            self._add(tick, 4, "%s, the chief, %s for %s at the %s: \"%s\"" % (
                actor, how, str(ev.get("for", "it")).lower(), place, ev.get("text", "...")))

        # -- the body --------------------------------------------------------------
        elif kind == "RECOVERED":
            self.recoveries += 1
            self._add(tick, 2, "%s's %s" % (actor, condition.day_phrase(ev.get("injury", ""))))
        elif kind == "MONSTER_ATTACK":
            hurt = self._injured(tick, ev, target)
            if hurt:
                self._add(tick, 3 if self._serious(ev) else 1,
                          "the %s caught %s at the %s%s" % (
                    str(ev.get("kind", "thing")).lower(), target, place, hurt))
        elif kind == "FLEE" and ev.get("limped"):
            self._add(tick, 2, "%s tried to get away from the %s and could not: %s" % (
                actor, place, ev.get("hurt", "the leg")))
        elif kind == "MOVE" and ev.get("limped"):
            self._add(tick, 1, "%s set off for the %s and got no further than the %s" % (
                actor, str(ev.get("toward", "hall")).lower(), place))

        # -- words that did not land the way they were meant to ---------------------
        elif kind == "FLATTERY":
            d = _delta(ev, ev["target"], "rel:%s:trust" % ev["actor"])
            tail = "; %s's trust in %s %.2f -> %.2f" % (target, actor, d[0], d[1]) if d else ""
            self._add(tick, 3, "%s complimented %s once too often at the %s, and %s heard it "
                               "for what it was%s" % (actor, target, place, target, tail))

        # -- wants -----------------------------------------------------------------
        elif kind == "GOAL_ADOPTED":
            self.goals_adopted += 1
            self._add(tick, 3 if ev.get("goal") in ("AVENGE", "PROTECT") else 1,
                      "%s now wants to %s" % (actor, _goal_phrase(ev.get("goal"), target)))
        elif kind == "GOAL_DROPPED":
            self._add(tick, 1, "%s stopped wanting to %s" % (
                actor, _goal_phrase(ev.get("goal"), target)))

    #: The injuries the story always makes room for. Bruises and a black eye are colour; a
    #: broken bone changes what a dwarf can do tomorrow.
    SERIOUS = ("broken_arm", "broken_leg", "concussion", "bleeding", "cracked_ribs")

    def _serious(self, ev):
        return any(i.get("kind") in self.SERIOUS for i in (ev.get("injuries") or ()))

    def _injured(self, tick, ev, whom):
        """Tally the injuries one blow caused and return the clause the story reads.

        This is where a fight stops being arithmetic: "down to 14.2 hp" says nothing a reader
        cares about, and "and broke his arm" says all of it.
        """
        got = ev.get("injuries") or ()
        if not got:
            return ""
        words = []
        for inj in got:
            kind = inj.get("kind")
            self.injuries[kind] = self.injuries.get(kind, 0) + 1
            words.append(condition.PHRASE.get(kind, (kind, kind))[0])
        return " and left %s with %s" % (whom, " and ".join(words))

    def _react(self, kind):
        self.reactions[kind] = self.reactions.get(kind, 0) + 1

    def _check_feuds(self, world, tick):
        living = world.living()
        for i, a in enumerate(living):
            for b in living[i + 1:]:
                ha = a.mind.rels.get(b.id, {}).get("hatred", 0.0)
                hb = b.mind.rels.get(a.id, {}).get("hatred", 0.0)
                if ha < FEUD_THRESHOLD or hb < FEUD_THRESHOLD:
                    continue
                key = (a.id, b.id)
                if key in self.feuds:
                    continue
                self.feuds[key] = {"a": a.id, "b": b.id, "tick": tick,
                                   "hatred": [round(ha, 3), round(hb, 3)]}
                self._add(tick, 4, "%s and %s hate each other now (%.2f / %.2f)" % (
                    a.name, b.name, ha, hb))

    # -- closing ------------------------------------------------------------

    def summary(self, world):
        story = sorted(self.story, key=lambda s: (-s["priority"], s["tick"]))[:STORY_CAP]
        story.sort(key=lambda s: s["seq"])
        return {
            "type": "summary",
            "ticks": world.tick,
            "survivors": [{"id": a.id, "name": a.name, "health": a.health} for a in world.living()],
            "deaths": [{"tick": d["tick"], "who": self.name(d["who"]),
                        "by": self.name(d["by"]) if d["by"] is not None else None,
                        "cause": d["cause"]} for d in world.deaths],
            "fights": {
                "hits": self.hits,
                "pairs": [{"a": self.name(p[0]), "b": self.name(p[1]), "hits": n}
                          for p, n in sorted(self.hit_pairs.items(), key=lambda kv: -kv[1])],
            },
            "thefts": [{"tick": t["tick"], "thief": self.name(t["thief"]),
                        "victim": self.name(t["victim"]) if t["victim"] is not None else None,
                        "item": t["item"], "seen": t["seen"]} for t in self.thefts],
            "feuds": [{"a": self.name(f["a"]), "b": self.name(f["b"]),
                       "tick": f["tick"], "hatred": f["hatred"]}
                      for f in sorted(self.feuds.values(), key=lambda f: f["tick"])],
            "monsters": {"arrived": self.monsters_arrived, "slain": self.monsters_slain},
            # What a fight actually costs now, which is mostly not health.
            "injuries": {"dealt": dict(self.injuries), "mended": self.recoveries,
                         "carried": {a.name: a.condition.describe()
                                     for a in world.living() if a.condition}},
            # How the settlement answered being provoked. The spread is the whole point: one
            # reaction kind dominating would mean the terms are not doing any work.
            "reactions": {k: self.reactions.get(k, 0) for k in REACTION_KINDS},
            "provocations": {
                "suffered": self.provoked,
                "answered": {k: self.answers.get(k, 0) for k in REACTION_KINDS},
                "unanswered": max(0, self.provoked - sum(self.answers.values())),
            },
            "insults": {"count": self.insults, "answered_with_a_fist": self.insult_then_hit},
            "gossip": self.gossip,
            "promises": dict(self.promises),
            "punishments": [{"tick": p["tick"], "chief": self.name(p["chief"]),
                             "who": self.name(p["who"]), "mode": p["mode"],
                             "fine": p["fine"], "for": p["for"]} for p in self.punishments],
            "goals_adopted": self.goals_adopted,
            "swears": {"count": self.swears, "tiers": dict(self.swear_tiers)},
            "reputation": world.reputation(),
            "chief": self.name(world.chief_id) if world.chief_id is not None else None,
            "profanity": getattr(world, "profanity", None) and world.profanity.max_tier,
            # Only present when the settlement's tier 3 speech is not coming from where the
            # user's speech file says it should. See Lexicon.speech_note().
            "profanity_note": (getattr(world, "profanity", None)
                               and world.profanity.speech_note()) or None,
            "story": [{"tick": s["tick"], "text": "tick %d: %s" % (s["tick"], s["text"])}
                      for s in story],
        }

    def close(self, world):
        s = self.summary(world)
        self._write(_round(s))
        self.fh.close()
        return s


def load(path):
    """Read a run back: ``{"header":..., "ticks":[...], "summary":...}``."""
    header, summary, ticks = None, None, []
    with open(path, "r", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            obj = json.loads(line)
            kind = obj.get("type")
            if kind == "header":
                header = obj
            elif kind == "summary":
                summary = obj
            else:
                ticks.append(obj)
    return {"header": header, "ticks": ticks, "summary": summary}
