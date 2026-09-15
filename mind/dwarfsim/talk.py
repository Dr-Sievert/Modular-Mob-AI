"""Talk to the dwarves and watch their minds move.

    python -m dwarfsim.talk --seed 1 --dwarves 3

One settlement, one keyboard. Anything you type that is not a slash command is said to the
dwarf you are focused on, as speaker id ``"player"``: the tiny text classifier in
``text/classifier`` reads it into the seven SCHEMA.md labels, those labels go through
:func:`dwarfsim.speech.hear` exactly as the sim's own player session does, and the sim then
runs a few ticks so the dwarf can answer.

Nothing in here is a new rule. Every state change still comes out of ``mind.EVENT_TABLE``,
every choice still comes out of ``arbitrator.decide``, and the narration is the log's own
:class:`dwarfsim.log.Recorder`. This module only reads, renders and drives.

Three things worth trying, which the first-run banner also says:

* insult a dwarf twice and watch anger climb and trust in you fall;
* ``/give gold 5``, then ask for something, and watch the same order get taken on;
* ``/tick 200`` and read the settlement's own story back.

The layer split: :class:`Session` is the whole app without a terminal (``say``, ``command``,
``snapshot``), and :mod:`dwarfsim.talk_ui` draws it. The tests drive :class:`Session` directly.
"""

import argparse
import os
import sys

from . import profanity, replies, speech
from .log import Recorder
from .obligations import make_ask
from .schema import ASK_ACTIONS, NAME_POOL, PLACES, PLAYER_ID
from .world import SCENARIOS, World

#: How many ticks the sim runs after a line is spoken, so the dwarf has room to answer.
TICKS_PER_SAY = 3

#: How many exchanges the conversation panel keeps.
FEED_CAP = 80

#: Where the trained classifier lives, under the subproject root, so it is found whatever the
#: working directory is: the subproject sits inside a larger repository and is run from anywhere.
DEFAULT_CLASSIFIER = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "text", "models", "clf")

#: What to say when the classifier cannot even be imported. The sim itself has no dependencies;
#: the classifier's numpy is the one thing a fresh clone is missing, and this is the one line
#: the app prints about it. It is the same string ``text/classifier/infer`` raises.
NUMPY_HINT = "numpy is required for the classifier: pip install -r requirements.txt"

INTENTS = tuple(speech.INTENT_EVENT)
TOPICS = tuple(speech.TOPIC_PLACE)
ADDRESSED = ("LISTENER", "THIRD", "GROUP", "NONE")
SINCERITY = ("SINCERE", "SARCASTIC", "JOKING")
#: The two columns `text/CURSOR_UNDERSTANDING_PROMPT.md` adds. The classifier does not label them
#: yet -- `dwarfsim.speechplan.derive` works them out by rule -- but both this and `/labels` pass
#: them straight through when they are there, which is the whole of the seam.
ABOUT = ("SPEAKER", "LISTENER", "THIRD", "WORLD", "OBJECT", "NONE")
NEWS = ("MISFORTUNE", "FORTUNE", "PLAN", "OPINION", "FACT", "SEEKING", "NONE")

GIVEABLE = ("gold", "ore", "food", "ale")

#: What a bare REQUEST/COMMAND about a topic is taken to be asking for. With one of these the
#: utterance becomes a real :class:`~dwarfsim.obligations.Obligation` and the dwarf gets
#: ACCEPT / REFUSE / BARGAIN candidates; without one it is only the vague ``request_pull``.
ASK_FOR_TOPIC = {
    "MINE": ("BRING", "ore", 2, "MINE"),
    "TREASURE": ("BRING", "gold", 2, "MINE"),
    "FOOD": ("BRING", "food", 1, "FARM"),
    "DRINK": ("BRING", "ale", 1, "TAVERN"),
    "MONSTER": ("GO_TO", None, 1, "GATE"),
    "FORGE": ("GO_TO", None, 1, "FORGE"),
    "WEAPON": ("GO_TO", None, 1, "FORGE"),
}

#: Skills whose choice is an answer to you rather than the day's work. Used only to pick which
#: of the few ticks after a line is the one worth showing the term breakdown of.
ANSWERING_SKILLS = frozenset((
    "IGNORE", "RETORT", "DEMAND_APOLOGY", "REFUSE_APOLOGY", "COMPLAIN_TO", "AVOID",
    "ATTACK", "APOLOGIZE", "ACCEPT", "REFUSE", "BARGAIN", "FULFIL", "GOSSIP", "SOCIALIZE",
))

HELP = (
    ("say anything", "talk to the dwarf you are focused on, as \"player\""),
    ("/to NAME", "focus another dwarf (a line that names one switches by itself)"),
    ("/tick N", "run N ticks and read back what the settlement did"),
    ("/give ITEM N", "hand over gold, ore, food or ale"),
    ("/hit", "swing at the focused dwarf"),
    ("/why", "the full term breakdown of its last decision"),
    ("/mind", "everything in its head right now"),
    ("/all", "one line per dwarf"),
    ("/labels k=v ... text=...", "speak with hand-typed labels (no classifier needed)"),
    ("/log N", "print the last N exchanges in full, however long they are"),
    ("/clear", "empty the conversation panel"),
    ("/help", "this"),
    ("/quit", "leave"),
)

BANNER = (
    "Three things to try:",
    "  1. insult a dwarf twice -- watch anger climb and its trust in you fall,",
    "     then watch which of IGNORE / RETORT / DEMAND_APOLOGY / AVOID / ATTACK it reaches for.",
    "  2. /give gold 8, then \"Fetch me ore from the mine\" -- the order a stranger refuses or",
    "     haggles over is taken on once gratitude_target has moved (put gold on the table with",
    "     /labels ... ask=BRING:ore:1@MINE:pay3 if it still haggles), then watch it FULFIL.",
    "  3. /tick 200 -- let the settlement run and read its own story back.",
    "Type /help for the rest. Everything is deterministic under --seed except what you type.",
)


# ---------------------------------------------------------------------------
# Reading a line of English into the seven labels
# ---------------------------------------------------------------------------


def _clamp(x, lo, hi):
    try:
        x = float(x)
    except (TypeError, ValueError):
        return lo if lo > 0.0 else 0.0
    return lo if x < lo else (hi if x > hi else x)


def clean_parsed(raw, text, pool=NAME_POOL):
    """Force whatever came back into a schema-legal parsed dict, and say what was fixed.

    The classifier is retrained often and its label lists can move under us, so nothing it
    says is trusted straight into :func:`dwarfsim.speech.hear`: an unknown intent becomes
    SMALLTALK with a note rather than a traceback.
    """
    notes = []
    out = {"text": text}

    intent = str(raw.get("intent", "SMALLTALK")).upper()
    if intent not in INTENTS:
        notes.append("unknown intent %r, read as SMALLTALK" % intent)
        intent = "SMALLTALK"
    out["intent"] = intent

    topic = str(raw.get("topic", "NONE")).upper()
    if topic not in TOPICS:
        notes.append("unknown topic %r, read as NONE" % topic)
        topic = "NONE"
    out["topic"] = topic

    addressed = str(raw.get("addressed", "LISTENER")).upper()
    if addressed not in ADDRESSED:
        notes.append("unknown addressed %r, read as LISTENER" % addressed)
        addressed = "LISTENER"
    out["addressed"] = addressed

    out["aggression"] = round(_clamp(raw.get("aggression", 0.0), 0.0, 1.0), 3)
    out["valence"] = round(_clamp(raw.get("valence", 0.0), -1.0, 1.0), 3)
    out["urgency"] = round(_clamp(raw.get("urgency", 0.0), 0.0, 1.0), 3)

    sincerity = str(raw.get("sincerity", "SINCERE")).upper()
    out["sincerity"] = sincerity if sincerity in SINCERITY else "SINCERE"

    # `about` and `news` are optional and are carried through only when they are legal. A
    # classifier that does not produce them loses nothing: `speechplan.derive` fills them in by
    # rule and says in the reasons that it did.
    for field, allowed in (("about", ABOUT), ("news", NEWS)):
        if raw.get(field) is None:
            continue
        value = str(raw[field]).upper()
        if value in allowed:
            out[field] = value
        else:
            notes.append("unknown %s %r, worked out by rule instead" % (field, raw[field]))

    names = [n for n in (raw.get("names") or []) if n in pool]
    for n in pool:                      # exact match, the same rule the classifier uses
        if n not in names and n in text:
            names.append(n)
    out["names"] = names

    ask = raw.get("ask")
    if isinstance(ask, dict) and str(ask.get("action", "")).upper() in ASK_ACTIONS:
        out["ask"] = ask
    elif "ask" in raw:
        out["ask"] = None       # asked for explicitly and refused: do not fill one in later
        if ask:
            notes.append("ignored an ask with no known action")
    return out, notes


def ask_for(parsed):
    """The structured ask a REQUEST or COMMAND carries, from its topic. ``None`` for none."""
    if parsed.get("intent") not in ("REQUEST", "COMMAND", "OFFER"):
        return None
    row = ASK_FOR_TOPIC.get(parsed.get("topic"))
    if row is None:
        return None
    action, item, qty, place = row
    return make_ask(action, item=item, quantity=qty, place=place)


class Interpreter:
    """The classifier, loaded at first use and never fatal if it is not there.

    ``text/models/clf`` is rewritten in place by a retrain, so it can be missing, half-written
    or trained on a different label set. All three come back as :attr:`error` and a clear
    message; the app stays usable through ``/labels``.

    A fresh clone with nothing installed is the fourth case, and the commonest: the classifier
    runs on numpy, so an ``ImportError`` here is a missing dependency rather than a broken
    model, and it comes back as the one line :data:`NUMPY_HINT` instead of a traceback.
    """

    def __init__(self, path=DEFAULT_CLASSIFIER):
        self.path = path
        self.clf = None
        self.error = None

    @property
    def status(self):
        if self.clf is not None:
            return "ok"
        return "unavailable" if self.error else "not loaded"

    def load(self):
        """Load once. Returns the classifier or ``None``; the reason is in :attr:`error`."""
        if self.clf is not None or self.error is not None:
            return self.clf
        root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        if root not in sys.path:
            sys.path.insert(0, root)
        try:
            from text.classifier.infer import Classifier
            self.clf = Classifier.load(self.path)
        except ImportError as exc:                        # nothing pip-installed yet
            self.error = str(exc) if NUMPY_HINT in str(exc) else NUMPY_HINT
        except Exception as exc:                          # missing, half-written, incompatible
            self.error = "%s: %s" % (type(exc).__name__, exc)
        return self.clf

    def parse(self, text):
        """``(parsed, notes)``, or ``(None, [why not])`` when there is no classifier."""
        clf = self.load()
        if clf is None:
            return None, [
                self.error if self.error == NUMPY_HINT
                else "no classifier at %s -- %s" % (self.path, self.error),
                "type the labels by hand instead, e.g.",
                "  /labels intent=INSULT aggression=0.8 valence=-0.8 text=%s" % (text or "..."),
            ]
        try:
            raw = clf.predict(text)
        except Exception as exc:
            self.clf, self.error = None, "%s: %s" % (type(exc).__name__, exc)
            return None, ["the classifier failed on that line -- %s" % self.error,
                          "use /labels to type the labels by hand"]
        return clean_parsed(raw, text)


# ---------------------------------------------------------------------------
# The session: the whole app without a terminal
# ---------------------------------------------------------------------------


class Session:
    """A settlement, a focused dwarf, and the record of what your words did to it.

    ``say(text)`` and ``command(line)`` each return one *entry*: a plain dict of everything
    that happened, which :mod:`dwarfsim.talk_ui` renders and the tests read. ``snapshot()``
    is the whole visible state, including the feed of recent entries.
    """

    def __init__(self, seed=1, dwarves=3, scenario="default", scorer=None,
                 classifier=DEFAULT_CLASSIFIER, ticks_per_say=TICKS_PER_SAY, narrate=True,
                 profanity_tier=profanity.DEFAULT_MAX_TIER, profanity_speech=None):
        self.seed = seed
        self.scenario = scenario
        self.ticks_per_say = max(0, int(ticks_per_say))
        self.world = World(n_agents=dwarves, seed=seed, scenario=scenario, scorer=scorer,
                           profanity_tier=profanity_tier, profanity_speech=profanity_speech)
        self.interp = Interpreter(classifier)
        self.feed = []
        self.focus = self.world.agents[0]
        self.world.player.place = self.focus.place
        self.last_decision = {}       # agent id -> the arbitrator's last full explain() record
        #: agent id -> the last speech construction it answered with, as plain data. The same
        #: idea as ``last_decision`` and for the same reason: ``/why`` has to be able to say
        #: why the dwarf said *that*, not only why it did what it did.
        self.last_construction = {}
        self.deltas = {}              # agent id -> {field: (before, after)} for the last action
        self.running = True
        self.turns = 0
        self._rec = None
        if narrate:
            try:
                self._rec = Recorder(os.devnull, self.world, 10 ** 9)
            except Exception:
                self._rec = None       # narration is a nicety, never a reason to fail to start

    # -- little helpers -----------------------------------------------------

    def name(self, aid):
        if aid is None:
            return None
        if isinstance(aid, str) and aid.isdigit():
            aid = int(aid)
        a = self.world.agent(aid)
        return a.name if a is not None else str(aid)

    def dwarf(self, who):
        """Find a living dwarf by id, name or unambiguous prefix."""
        if who is None:
            return None
        if isinstance(who, int):
            return self.world.agent(who)
        want = str(who).strip().lower()
        for a in self.world.living():
            if a.name.lower() == want:
                return a
        hits = [a for a in self.world.living() if a.name.lower().startswith(want)]
        return hits[0] if len(hits) == 1 else None

    def _named_dwarf(self, names, text):
        """The first living dwarf named in the line, for the automatic focus switch."""
        for n in list(names or []):
            a = self.world.agent_by_name(n)
            if a is not None and a.alive and not a.external:
                return a
        for a in self.world.living():
            if a.name in text:
                return a
        return None

    def _state_of(self, a):
        st = dict(a.mind.emotions)
        for k, v in a.mind.needs.items():
            st["need_" + k] = v
        st["health"] = a.health
        r = a.mind.rel(PLAYER_ID)
        for f in ("trust", "respect", "hatred"):
            st["player_" + f] = r[f]
        return st

    def _capture(self):
        return {a.id: self._state_of(a) for a in self.world.agents if a.alive}

    def _settle(self, before):
        """Diff every dwarf against the state it was in when this action started."""
        self.deltas = {}
        for a in self.world.agents:
            if not a.alive:
                continue
            was = before.get(a.id)
            if was is None:
                continue
            now = self._state_of(a)
            moved = {k: (was[k], v) for k, v in now.items()
                     if k in was and abs(v - was[k]) > 1e-6}
            if moved:
                self.deltas[a.id] = moved

    def pretty(self, label):
        """``ATTACK @MINE ->1 ~2`` -> ``ATTACK @MINE ->Runa ~Tova``.

        :meth:`dwarfsim.skills.Candidate.label` writes ids, because ids are what the log
        should carry. A person reading the screen wants the names.
        """
        bits = []
        for token in str(label).split():
            if token[:2] == "->":
                bits.append("->" + str(self.name(token[2:])))
            elif token[:1] == "~":
                bits.append("~" + str(self.name(token[1:])))
            else:
                bits.append(token)
        return " ".join(bits)

    def field_label(self, f):
        """``rel:player:trust`` -> ``trust of Player``."""
        if isinstance(f, str) and f.startswith("rel:"):
            _, who, field = f.split(":", 2)
            return "%s of %s" % (field, self.name(who))
        return f

    def _describe(self, ev):
        """One event's deltas, grouped by whose head they moved."""
        out = {}
        for d in (ev.get("deltas") or ()):
            if d["who"] == PLAYER_ID:
                continue        # the player's own feelings are not part of the settlement
            out.setdefault(d["who"], []).append(
                {"field": self.field_label(d["f"]), "from": d["from"], "to": d["to"]})
        return out

    def _terms(self, record, n=None):
        """The winning candidate's contributions, biggest first."""
        for entry in record.get("top", ()):
            if entry.get("won"):
                terms = sorted(entry.get("terms", {}).items(), key=lambda kv: -abs(kv[1]))
                return terms if n is None else terms[:n]
        return []

    def _push(self, entry):
        entry.setdefault("tick", self.world.tick)
        # A number that never repeats and never shifts, unlike the feed's index: the renderer
        # uses it to print only what is new since the last prompt.
        entry.setdefault("n", self.turns)
        self.feed.append(entry)
        del self.feed[:-FEED_CAP]
        self.turns += 1
        return entry

    def _record(self, step):
        """Feed one step to the log's Recorder and return the story lines it added.

        The Recorder writes to the null device: nothing here wants a log file, but its
        narration and its provocation bookkeeping are exactly what ``/tick`` should print,
        and rewriting them here would be a second copy of the same rules.
        """
        if self._rec is None:
            return []
        start = len(self._rec.story)
        try:
            self._rec.record(self.world, step)
        except Exception:
            return []
        return [s["text"] for s in self._rec.story[start:]]

    # -- driving the sim ----------------------------------------------------

    def _advance(self, ticks):
        """Run the sim and collect what the focused dwarf did and what got said.

        Two of the things collected are what :mod:`dwarfsim.replies` needs to answer you
        without contradicting the sim: ``answered``, a line the focused dwarf has already
        aimed at you out of its own skills, and ``answer_skill``, what its arbitrator chose
        to do about you.
        """
        lines, decisions, story = [], [], []
        answered, answer_skill = None, None
        for _ in range(max(0, int(ticks))):
            if not self.world.living():
                break
            step = self.world.step()
            story.extend(self._record(step))
            for ev in step["events"]:
                if ev.get("text") and ev.get("actor") is not None:
                    lines.append({"tick": step["tick"], "who": self.name(ev["actor"]),
                                  "kind": ev["type"], "target": self.name(ev.get("target")),
                                  "text": ev["text"]})
                    if (answered is None and ev["actor"] == self.focus.id
                            and ev.get("target") == PLAYER_ID):
                        answered = ev["text"]
            for d in step["decisions"]:
                self.last_decision[d["agent"]] = d
                if d["agent"] == self.focus.id:
                    decisions.append({"tick": step["tick"], "action": self.pretty(d["chosen"]),
                                      "score": d["score"], "terms": self._terms(d, 3),
                                      "moved": d.get("moved")})
                    if answer_skill is None and _aimed_at_player(d["chosen"]):
                        answer_skill = d["chosen"].split()[0]
            if not self.focus.alive:
                alive = self.world.living()
                if alive:
                    self.focus = alive[0]
            self.world.player.place = self.focus.place   # you stand with whoever you are talking to
        return {"lines": lines, "decisions": decisions, "story": story,
                "highlight": _highlight(decisions),
                "answered": answered, "answer_skill": answer_skill}

    # -- saying something ---------------------------------------------------

    def say(self, text, parsed=None):
        """Say one line to the focused dwarf, then let the sim run a few ticks."""
        text = (text or "").strip()
        if not text and parsed is None:
            return None
        entry = {"kind": "say", "you": text, "tick": self.world.tick, "notes": []}

        if parsed is None:
            parsed, notes = self.interp.parse(text)
            entry["notes"].extend(notes)
            if parsed is None:
                entry["kind"] = "error"
                return self._push(entry)
            entry["source"] = "classifier"
        else:
            parsed, notes = clean_parsed(dict(parsed, text=parsed.get("text", text)),
                                         parsed.get("text", text))
            entry["notes"].extend(notes)
            entry["source"] = "hand"
            entry["you"] = parsed["text"]

        if "ask" not in parsed:
            ask = ask_for(parsed)
            if ask is not None:
                parsed["ask"] = ask

        named = self._named_dwarf(parsed.get("names"), parsed["text"])
        if named is not None and named.id != self.focus.id:
            self.focus = named
            entry["notes"].append("focus follows the name: %s" % named.name)

        entry["to"] = self.focus.name
        entry["parsed"] = parsed
        self.world.player.place = self.focus.place
        before = self._capture()

        ev = self.world.say(PLAYER_ID, self.focus, parsed)
        if ev is None:
            entry["notes"].append("nothing landed: nobody was listening")
        else:
            entry["event"] = {"type": ev["type"], "place": ev.get("place"),
                              "target": self.name(ev.get("target")),
                              "magnitude": round(speech.magnitude_of(parsed), 3)}
            if ev.get("ask"):
                entry["event"]["ask"] = dict(ev["ask"])
            moved = self._describe(ev)
            entry["deltas"] = moved.pop(self.focus.id, [])
            entry["witness"] = [{"who": self.name(k), "changes": v} for k, v in moved.items()]
        self._record({"tick": self.world.tick, "events": [ev] if ev else [], "decisions": []})

        entry.update(self._advance(self.ticks_per_say))
        self._answer(entry, parsed)
        self._settle(before)
        return self._push(entry)

    def _answer(self, entry, parsed):
        """Every line you say gets an answer, unless the dwarf already gave you one.

        The wording comes out of the speech pipeline through :func:`dwarfsim.replies.reply`,
        which is told what the arbitrator chose so it cannot say yes to an ask the dwarf
        refused. It moves no state but the conversation's own memory: the rest already moved,
        in ``speech.hear``.

        The **construction** -- the act, the rule that picked it, the tags, the slots that were
        filled and which bank line was used -- is kept beside the reply as plain data, because
        that is what the screen draws and what ``/why`` reads back.
        """
        answer = replies.reply(self.world, self.focus, PLAYER_ID, parsed,
                               decided=entry.get("answer_skill"),
                               already=entry.get("answered"))
        con = answer.pop("construction", None)
        if con is not None:
            plan = con.snapshot()
            plan["bank"] = answer.get("bank")
            plan["line"] = answer.get("line")
            plan["said"] = answer.get("text")
            plan["used"] = dict(answer.get("slots") or {})
            answer["construction"] = plan
            self.last_construction[self.focus.id] = plan
        entry["reply"] = answer
        if answer["text"]:
            entry.setdefault("lines", []).append(
                {"tick": self.world.tick, "who": answer["who"], "kind": "REPLY",
                 "target": "Player", "text": answer["text"]})
        elif answer["note"]:
            entry.setdefault("notes", []).append(answer["note"])
        return answer

    # -- commands -----------------------------------------------------------

    def command(self, line):
        """One slash command, or plain text, which is a line of speech."""
        line = (line or "").strip()
        if not line:
            return None
        if not line.startswith("/"):
            return self.say(line)
        body = line[1:]
        parts = body.split()
        cmd = parts[0].lower() if parts else ""
        args = parts[1:]
        fn = getattr(self, "_cmd_" + cmd, None)
        if fn is None:
            return self._push({"kind": "help", "you": line, "notes": ["no such command: /%s" % cmd],
                               "help": list(HELP)})
        return fn(args, body)

    def _cmd_log(self, args, body):
        """``/log N`` -- the last N exchanges in full.

        The panel shows what fits; this shows what happened. Entries come back as they are,
        and :func:`dwarfsim.talk_ui.entry_lines` draws them, so nothing about the layering
        changes: the session hands over data, the renderer decides how it looks.
        """
        try:
            want = max(1, int(args[0])) if args else 10
        except ValueError:
            return self._push({"kind": "error", "you": "/log " + " ".join(args),
                               "notes": ["how many? e.g. /log 20"]})
        shown = [e for e in self.feed if e.get("kind") != "log"][-want:]
        return self._push({"kind": "log", "you": "/log %d" % want, "show": shown,
                           "notes": ["nothing said yet"] if not shown else []})

    def _cmd_clear(self, args, body):
        self.feed = []
        return self._push({"kind": "info", "you": "/clear",
                           "notes": ["conversation cleared"]})

    def _cmd_help(self, args, body):
        return self._push({"kind": "help", "you": "/help", "help": list(HELP),
                           "banner": list(BANNER)})

    def _cmd_quit(self, args, body):
        self.running = False
        return self._push({"kind": "info", "you": "/quit", "notes": ["leaving the settlement"]})

    _cmd_exit = _cmd_quit

    def _cmd_to(self, args, body):
        if not args:
            return self._push({"kind": "error", "you": "/to",
                               "notes": ["who? try one of: %s" % ", ".join(
                                   a.name for a in self.world.living())]})
        who = self.dwarf(" ".join(args))
        if who is None:
            return self._push({"kind": "error", "you": "/to " + " ".join(args),
                               "notes": ["no such dwarf; here: %s" % ", ".join(
                                   a.name for a in self.world.living())]})
        self.focus = who
        self.world.player.place = who.place
        return self._push({"kind": "info", "you": "/to " + " ".join(args),
                           "notes": ["now talking to %s, at the %s" % (who.name, who.place)]})

    _cmd_focus = _cmd_to

    def _cmd_tick(self, args, body):
        try:
            n = int(args[0]) if args else 10
        except ValueError:
            n = 10
        n = max(1, min(20000, n))
        before = self._capture()
        entry = {"kind": "tick", "you": "/tick %d" % n}
        entry.update(self._advance(n))
        self._settle(before)
        entry["notes"] = ["%d ticks; now tick %d" % (n, self.world.tick)]
        return self._push(entry)

    def _cmd_give(self, args, body):
        item = (args[0].lower() if args else "gold")
        try:
            count = int(args[1]) if len(args) > 1 else 3
        except ValueError:
            count = 3
        count = max(1, min(50, count))
        if item not in GIVEABLE:
            return self._push({"kind": "error", "you": "/give " + " ".join(args),
                               "notes": ["give one of: %s" % ", ".join(GIVEABLE)]})
        who = self.focus
        before = self._capture()
        self.world.player.place = who.place
        if item == "gold":
            ev = self.world.player_give(who, count)
            moved = ev.get("count", 0)
        else:
            # The player's pack is not part of the settlement economy, so ore, food and ale
            # come out of it freely; only gold is counted, because the sim counts gold.
            moved = count
            who.inv[item] = who.inv.get(item, 0) + moved
            ev = self.world.emit("GIFT", self.world.player, who, place=who.place,
                                 magnitude=min(1.6, 0.5 + 0.25 * moved),
                                 extra={"item": item, "count": moved, "by": "player"})
        entry = {"kind": "give", "you": "/give %s %d" % (item, count),
                 "to": who.name,
                 "event": {"type": "GIFT", "target": who.name, "item": item, "count": moved,
                           "place": who.place}}
        seen = self._describe(ev)
        entry["deltas"] = seen.pop(who.id, [])
        entry["witness"] = [{"who": self.name(k), "changes": v} for k, v in seen.items()]
        if item == "gold" and moved < count:
            entry.setdefault("notes", []).append(
                "you only had %d gold" % (moved,))
        self._record({"tick": self.world.tick, "events": [ev], "decisions": []})
        entry.update(self._advance(self.ticks_per_say))
        self._settle(before)
        return self._push(entry)

    def _cmd_hit(self, args, body):
        try:
            damage = float(args[0]) if args else 3.0
        except ValueError:
            damage = 3.0
        damage = max(0.5, min(10.0, damage))
        who = self.focus
        before = self._capture()
        world = self.world
        world.player.place = who.place
        who.health = round(who.health - damage, 2)
        who.last_hit_by = PLAYER_ID
        who.last_hit_tick = world.tick
        world.player.last_struck[who.id] = world.tick
        ev = world.emit("HIT", world.player, who, place=who.place,
                        extra={"damage": round(damage, 2), "health": max(0.0, who.health),
                               "by": "player"})
        entry = {"kind": "hit", "you": "/hit", "to": who.name,
                 "event": {"type": "HIT", "target": who.name, "damage": round(damage, 2),
                           "health": max(0.0, who.health), "place": who.place}}
        seen = self._describe(ev)
        entry["deltas"] = seen.pop(who.id, [])
        entry["witness"] = [{"who": self.name(k), "changes": v} for k, v in seen.items()]
        self._record({"tick": world.tick, "events": [ev], "decisions": []})
        if who.health <= 0.0:
            world.kill(who, world.player, cause="player")
            entry.setdefault("notes", []).append("%s is dead" % who.name)
            alive = world.living()
            if alive:
                self.focus = alive[0]
        entry.update(self._advance(self.ticks_per_say))
        self._settle(before)
        return self._push(entry)

    def _cmd_why(self, args, body):
        """Why it did that, and -- since the reply came out of a rule table too -- why it said
        that. The two are separate machines and the screen keeps them separate."""
        record = self.last_decision.get(self.focus.id)
        said = self.last_construction.get(self.focus.id)
        if record is None:
            if said is None:
                return self._push({"kind": "info", "you": "/why",
                                   "notes": ["%s has not decided anything yet -- /tick 1"
                                             % self.focus.name]})
            return self._push({"kind": "why", "you": "/why", "who": self.focus.name,
                               "action": None, "score": 0.0, "terms": [], "candidates": [],
                               "construction": said})
        runners = [{"action": self.pretty(e["action"]), "score": e["score"],
                    "won": bool(e.get("won"))} for e in record.get("top", ())]
        return self._push({"kind": "why", "you": "/why", "who": self.focus.name,
                           "action": self.pretty(record["chosen"]), "score": record["score"],
                           "terms": self._terms(record), "candidates": runners,
                           "construction": said})

    def _cmd_mind(self, args, body):
        return self._push({"kind": "mind", "you": "/mind",
                           "who": self.focus.name, "state": self.dwarf_state(self.focus)})

    def _cmd_all(self, args, body):
        rows = []
        for a in self.world.living():
            rel = a.mind.rel(PLAYER_ID)
            hot = max(a.mind.emotions.items(), key=lambda kv: kv[1])
            rows.append({
                "name": a.name, "place": a.place, "health": a.health,
                "mood": "%s %.2f" % (hot[0], hot[1]),
                "player": "trust %+.2f respect %+.2f hatred %.2f" % (
                    rel["trust"], rel["respect"], rel["hatred"]),
                "goals": ", ".join(g.kind for g in a.goals) or "-",
                "focus": a.id == self.focus.id,
            })
        return self._push({"kind": "all", "you": "/all", "rows": rows})

    def _cmd_labels(self, args, body):
        """``/labels intent=INSULT aggression=0.9 text=You couldn't swing a pick.``

        The fallback for a missing classifier, and the way to say something the classifier
        would read differently. ``ask=BRING:ore:2@MINE`` attaches a structured ask;
        ``ask=none`` suppresses the one a REQUEST or COMMAND would otherwise get.
        """
        raw, text = body, ""
        cut = body.find("text=")
        if cut >= 0:
            text = body[cut + 5:].strip()
            raw = body[:cut]
        fields = {}
        bad = []
        for token in raw.split()[1:]:
            if "=" not in token:
                bad.append(token)
                continue
            k, v = token.split("=", 1)
            fields[k.strip().lower()] = v.strip()
        ask_spec = fields.pop("ask", None)
        parsed = {
            "text": text or "(no words)",
            "intent": fields.get("intent", "SMALLTALK"),
            "topic": fields.get("topic", "NONE"),
            "addressed": fields.get("addressed", "LISTENER"),
            "aggression": fields.get("aggression", 0.0),
            "valence": fields.get("valence", 0.0),
            "urgency": fields.get("urgency", 0.0),
            "sincerity": fields.get("sincerity", "SINCERE"),
            "names": [n for n in NAME_POOL if n in text],
        }
        for field in ("about", "news"):
            if fields.get(field):
                parsed[field] = fields[field]
        if ask_spec and ask_spec.lower() not in ("none", "no", "0"):
            ask = parse_ask(ask_spec)
            if ask is None:
                bad.append("ask=" + ask_spec)
            else:
                parsed["ask"] = ask
        elif ask_spec:
            parsed["ask"] = None
        entry = self.say(text or "(no words)", parsed=dict(parsed))
        if entry is not None:
            entry.setdefault("notes", []).append("labels typed by hand")
            if bad:
                entry["notes"].append("ignored: %s" % " ".join(bad))
        return entry

    # -- state --------------------------------------------------------------

    def dwarf_state(self, a):
        """Everything the left panel and ``/mind`` show about one dwarf."""
        world = self.world
        rel = a.mind.rel(PLAYER_ID)
        forgiveness = a.mind.traits["forgiveness"]
        others = []
        for o in world.living():
            if o.id == a.id:
                continue
            r = a.mind.rel(o.id)
            others.append({"name": o.name, "trust": r["trust"], "respect": r["respect"],
                           "hatred": r["hatred"], "warmth": round(r["warmth"], 3)})
        mem = []
        for m in a.memories.top(world.tick, forgiveness, 3):
            mem.append({"kind": m["kind"], "actor": self.name(m["actor"]),
                        "target": self.name(m["target"]), "tick": m["tick"],
                        "salience": m["sal"], "source": m["source"]})
        obl = []
        for ob in world._open_obl:
            if ob.frm != a.id and ob.to != a.id:
                continue
            obl.append({"id": ob.oid, "status": ob.status,
                        "mine": ob.to == a.id,
                        "with": self.name(ob.frm if ob.to == a.id else ob.to),
                        "what": ob.label(self.name), "payment": ob.payment(),
                        "deadline": ob.deadline})
        reg = a.regard.get(PLAYER_ID)
        return {
            "id": a.id, "name": a.name, "alive": a.alive, "place": a.place, "health": a.health,
            "chief": a.id == world.chief_id,
            "emotions": dict(a.mind.emotions), "needs": dict(a.mind.needs),
            "traits": dict(a.mind.traits), "inv": dict(a.inv),
            # What is broken, and what talking to this dwarf has been worth lately: warmth
            # is what words buy, suspicion is what saying the same thing too often buys.
            "condition": a.condition.describe(),
            "injuries": a.condition.snapshot(),
            "pain": round(a.condition.pain(), 3),
            "player": {"trust": rel["trust"], "respect": rel["respect"], "hatred": rel["hatred"],
                       "warmth": round(rel["warmth"], 3),
                       "suspicion": round(reg.suspicion, 3) if reg is not None else 0.0},
            "others": others,
            "goals": [{"kind": g.kind, "target": self.name(g.target),
                       "strength": round(g.strength, 3)} for g in a.goals],
            "memories": mem,
            "obligations": obl,
            "deltas": dict(self.deltas.get(a.id, {})),
            "decision": self.last_decision.get(a.id),
        }

    def snapshot(self):
        """The whole visible state, as plain data. What the renderer draws and tests read."""
        world = self.world
        return {
            "tick": world.tick,
            "seed": self.seed,
            "scenario": self.scenario,
            "focus": self.focus.id,
            "focus_name": self.focus.name,
            "living": len(world.living()),
            "player_gold": world.player.inv["gold"],
            "player_place": world.player.place,
            "classifier": self.interp.status,
            "profanity": self.world.profanity.max_tier,
            "profanity_note": self.world.profanity.speech_note(),
            "classifier_error": self.interp.error,
            "running": self.running,
            "turns": self.turns,
            "dwarves": {a.id: self.dwarf_state(a) for a in world.agents if a.alive},
            "feed": list(self.feed),
        }


def parse_ask(spec):
    """``BRING:ore:2@MINE:pay3`` -> a structured ask, or ``None`` if it makes no sense."""
    if not spec:
        return None
    place = None
    payment = 0
    if "@" in spec:
        spec, rest = spec.split("@", 1)
        bits = rest.split(":")
        place = bits[0].upper() if bits[0] else None
        for b in bits[1:]:
            if b.lower().startswith("pay"):
                try:
                    payment = int(b[3:])
                except ValueError:
                    payment = 0
    bits = spec.split(":")
    action = bits[0].upper()
    if action not in ASK_ACTIONS:
        return None
    item = bits[1] if len(bits) > 1 and bits[1] else None
    try:
        qty = int(bits[2]) if len(bits) > 2 and bits[2] else 1
    except ValueError:
        qty = 1
    if place is not None and place not in PLACES:
        place = None
    return make_ask(action, item=item, quantity=qty, place=place, payment=payment)


def _aimed_at_player(label):
    """Is this candidate label aimed at the player? ``RETORT @MINE ->player``."""
    return any(token == "->" + PLAYER_ID for token in str(label).split())


def _highlight(decisions):
    """Which of the few decisions after a line is the answer worth explaining."""
    for i, d in enumerate(decisions):
        if d["action"].split()[0] in ANSWERING_SKILLS:
            return i
    return 0 if decisions else None


# ---------------------------------------------------------------------------
# Command line
# ---------------------------------------------------------------------------


def build_parser():
    p = argparse.ArgumentParser(
        prog="python -m dwarfsim.talk",
        description="Talk to the dwarves and watch their minds move.")
    p.add_argument("--seed", type=int, default=1, help="everything random comes from this")
    p.add_argument("--dwarves", type=int, default=3, help="how many dwarves (default 3)")
    p.add_argument("--scenario", default="default", choices=SCENARIOS,
                   help="starting conditions only (default: default)")
    p.add_argument("--scorer", default=None,
                   help="an exported learned scorer, as the sim's --scorer")
    p.add_argument("--classifier", default=DEFAULT_CLASSIFIER,
                   help="where the text classifier lives (default text/models/clf)")
    p.add_argument("--profanity", type=int, default=profanity.DEFAULT_MAX_TIER,
                   choices=(0, 1, 2, 3),
                   help="how far the dwarves will go: 0 never swears, 1 mild oaths (default), "
                        "2 crude insults, 3 the speech-file slurs. What you say is always read "
                        "for all three tiers.")
    p.add_argument("--profanity-speech", default=None,
                   help="a JSON file of tier 3 terms the dwarves may say, in the shape of "
                        "text/profanity.json's tiers (default text/profanity_speech.json, and "
                        "the in-world list in dwarfsim/profanity.py if it is not there)")
    p.add_argument("--ticks-per-say", type=int, default=TICKS_PER_SAY,
                   help="ticks run after each line you say (default %d)" % TICKS_PER_SAY)
    p.add_argument("--no-rich", action="store_true", help="plain ANSI even if rich is installed")
    p.add_argument("--width", type=int, default=None, help="force a console width")
    p.add_argument("--script", default=None,
                   help="read lines from this file (or - for stdin) instead of the keyboard, "
                        "print the transcript and exit")
    return p


def main(argv=None):
    from . import talk_ui
    args = build_parser().parse_args(argv)
    scorer = None
    if args.scorer:
        from .learn import LearnedScorer
        scorer = LearnedScorer.load(args.scorer)
    session = Session(seed=args.seed, dwarves=args.dwarves, scenario=args.scenario,
                      scorer=scorer, classifier=args.classifier,
                      ticks_per_say=args.ticks_per_say, profanity_tier=args.profanity,
                      profanity_speech=args.profanity_speech)
    if args.script:
        if args.script == "-":
            lines = sys.stdin.read().splitlines()
        else:
            with open(args.script, encoding="utf-8") as fh:
                lines = fh.read().splitlines()
        return talk_ui.run_script(session, lines, use_rich=not args.no_rich, width=args.width)
    return talk_ui.run(session, use_rich=not args.no_rich, width=args.width)


if __name__ == "__main__":
    sys.exit(main())
