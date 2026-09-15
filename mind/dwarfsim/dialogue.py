"""The conversation's memory: what these two have already said to each other.

Everything else in the speech pipeline can be computed from the dwarf's state and the line it
just heard. This module is the part that cannot: *you asked me that already*, *still no*, *about
that axe you wanted*, and the reason a dwarf does not answer the same question with the same
sentence four times running. It is the thing that has to be right, because every callback in the
bank is a lie without it.

One :class:`DialogueState` per ``(dwarf, speaker)`` pair, living on the dwarf in
``agent.dialogue`` beside ``agent.regard``, which is exactly where the rest of what one dwarf has
learned about being talked at by one other dwarf already lives. It holds

* the last :data:`TURN_CAP` **turns**, each a small record of who said what kind of thing about
  whom -- ``(tick, who, intent, about, news, topic, act, entities)``;
* the **open questions** this dwarf owes an answer to, and the **open asks** and **promises**
  between the two of them;
* a per-signature **repetition count**, for what was heard and for what was said, which is
  :class:`dwarfsim.regard.Regard`'s ring and nothing new: the same machinery that stops twenty
  pasted compliments from buying a friend is the machinery that stops a dwarf from answering with
  the same sentence twice;
* **what was last said about whom**, so "he is still at it" means something;
* when the pair last spoke, which is what makes the whole thing expire.

**Expiry.** After :data:`SILENCE_EXPIRY` ticks with nothing said, the state is dropped and the
next line starts a new conversation. Without that, a dwarf greeted once at tick 4 answers a
greeting at tick 3,000 with "you have said that already", which is not memory, it is a bug.

Nothing here is random and nothing here decides anything: it is a record. The planner reads it,
:mod:`dwarfsim.replybank` reads it, and both write one line back into it per exchange.
"""

from . import regard

#: How many turns of one conversation are kept. Six is two exchanges of three, which is as far
#: back as any act in the bank refers.
TURN_CAP = 6

#: Ticks of silence after which the pair are strangers again.
SILENCE_EXPIRY = 600

#: How long a question stays owed. A question nobody answered inside this is not a question any
#: more, it is something that was said once.
QUESTION_TTL = 120

#: How many open questions, asks and promises are tracked per pair. Past this the oldest goes:
#: a conversation with nine unanswered questions in it is not a conversation.
OPEN_CAP = 4

#: The two rings. ``HEARD`` counts what the speaker keeps saying, ``SAID`` counts what this dwarf
#: keeps answering with. Both are :class:`dwarfsim.regard.Regard` and use its window and curve.
HEARD = "heard"
SAID = "said"


def turn_signature(intent, about, news, topic, text=None):
    """What makes two heard turns the same turn, in :func:`dwarfsim.regard.signature`'s shape.

    A four-tuple whose first three entries are the labels and whose fourth is the hash of the
    words, because that is what ``Regard.repetition`` compares: all four for *identical*, the
    first three for *similar*. So the same question in different words is similar, and the same
    question pasted is identical -- which is the difference between somebody who keeps coming
    back to a subject and somebody who is repeating themselves.
    """
    return (str(intent or "SMALLTALK"),
            "%s/%s" % (about or "NONE", news or "NONE"),
            str(topic or "NONE"),
            regard.text_hash(text))


def line_signature(act, lid):
    """The same shape for a line this dwarf *said*: same act is similar, same line is identical."""
    return ("LINE", str(act or "NONE"), str(lid if lid is not None else ""), "")


def subject_key(about, entities):
    """Who a turn was about, as one hashable key.

    ``THIRD`` is keyed by the name that was said, so "Hrolf again" and "Runa again" are two
    different subjects; everything else is keyed by the ``about`` value, because there is only
    ever one speaker, one listener and one world in a conversation.
    """
    if about == "THIRD" and entities:
        return ("THIRD", str(entities[0]))
    return (str(about or "NONE"), None)


class DialogueState:
    """What one dwarf remembers of one conversation."""

    __slots__ = ("turns", "questions", "asks", "promises", "heard", "said",
                 "subjects", "last_tick", "started")

    def __init__(self, tick=0):
        self.turns = []          # [dict], oldest first, at most TURN_CAP
        self.questions = []      # [dict] questions I owe an answer to
        self.asks = []           # [dict] asks made either way and still open
        self.promises = []       # [dict] promises made either way and still open
        self.heard = regard.Regard()    # the ring of what they said
        self.said = regard.Regard()     # the ring of what I answered with
        self.subjects = {}       # subject key -> {"tick", "news", "by"}
        self.last_tick = tick
        self.started = tick

    # -- turns --------------------------------------------------------------

    def _push(self, turn):
        self.turns.append(turn)
        if len(self.turns) > TURN_CAP:
            del self.turns[0]
        self.last_tick = turn["tick"]

    def note_heard(self, tick, intent, about, news, topic, entities=(), text=None, act=None):
        """File one turn the speaker took. Returns ``(signature, exact repeats)``.

        The repeat count is what a CALLBACK is made of: it is how many times these exact words,
        with these exact labels, have come out of this mouth in this conversation already.
        """
        sig = turn_signature(intent, about, news, topic, text)
        _, same = self.heard.repetition(tick, sig)
        self.heard.note(tick, sig)
        entities = tuple(entities or ())
        self._push({"tick": tick, "who": "them", "intent": intent, "about": about,
                    "news": news, "topic": topic, "act": act, "entities": entities,
                    "sig": sig, "repeats": same})
        key = subject_key(about, entities)
        if key[0] != "NONE":
            self.subjects[key] = {"tick": tick, "news": news, "by": "them"}
        return sig, same

    def note_said(self, tick, act, lid=None, about=None, news=None, topic=None, entities=()):
        """File one turn this dwarf took. Returns ``(signature, exact repeats)``."""
        sig = line_signature(act, lid)
        _, same = self.said.repetition(tick, sig)
        self.said.note(tick, sig)
        entities = tuple(entities or ())
        self._push({"tick": tick, "who": "me", "intent": None, "about": about, "news": news,
                    "topic": topic, "act": act, "entities": entities, "sig": sig,
                    "repeats": same, "line": lid})
        key = subject_key(about, entities)
        if key[0] != "NONE":
            self.subjects[key] = {"tick": tick, "news": news, "by": "me"}
        return sig, same

    # -- repetition ---------------------------------------------------------

    def heard_before(self, tick, sig):
        """How many times this exact thing has been said to me already, this conversation."""
        _, same = self.heard.repetition(tick, sig)
        return same

    def line_freshness(self, tick, act, lid):
        """0..1: what saying this line again would be worth, 1.0 for one never used.

        Straight off :meth:`dwarfsim.regard.Regard.repetition`, so the curve that decides a
        repeated compliment is worth nothing is the curve that decides a repeated sentence is.
        """
        factor, _ = self.said.repetition(tick, line_signature(act, lid))
        return factor

    def last_act(self):
        for turn in reversed(self.turns):
            if turn["who"] == "me" and turn["act"]:
                return turn["act"]
        return None

    def said_about(self, about, entities=()):
        """``{"tick", "news", "by"}`` for the last thing either of us said about this, or ``None``."""
        return self.subjects.get(subject_key(about, entities))

    # -- what is owed -------------------------------------------------------

    def open_question(self, tick, about, topic, sig, text=None):
        """Note a question this dwarf now owes an answer to."""
        self.questions = [q for q in self.questions if tick - q["tick"] <= QUESTION_TTL]
        self.questions.append({"tick": tick, "about": about, "topic": topic, "sig": sig,
                               "text": text})
        if len(self.questions) > OPEN_CAP:
            del self.questions[0]

    def answer_question(self, sig=None):
        """The question is answered: drop it, the matching one first."""
        if not self.questions:
            return None
        for i, q in enumerate(self.questions):
            if sig is not None and q["sig"][:3] == sig[:3]:
                return self.questions.pop(i)
        return self.questions.pop(0)

    def owed(self, tick):
        """The questions still open, freshest last."""
        self.questions = [q for q in self.questions if tick - q["tick"] <= QUESTION_TTL]
        return list(self.questions)

    def note_ask(self, tick, oid, what, mine, payment=0):
        """One ask between the two of us. ``mine`` is true when this dwarf is the one asked."""
        self.asks = [a for a in self.asks if a["oid"] != oid]
        self.asks.append({"tick": tick, "oid": oid, "what": what, "mine": bool(mine),
                          "payment": payment, "status": "PENDING"})
        if len(self.asks) > OPEN_CAP:
            del self.asks[0]

    def close_ask(self, oid, status):
        for a in self.asks:
            if a["oid"] == oid:
                a["status"] = status
                if status in ("KEPT", "BROKEN", "REFUSED", "EXPIRED"):
                    self.asks.remove(a)
                return a
        return None

    def note_promise(self, tick, what, oid=None):
        self.promises.append({"tick": tick, "what": what, "oid": oid})
        if len(self.promises) > OPEN_CAP:
            del self.promises[0]

    def open_asks(self, mine=None):
        return [a for a in self.asks if mine is None or a["mine"] == mine]

    # -- housekeeping -------------------------------------------------------

    def expired(self, tick):
        return tick - self.last_tick > SILENCE_EXPIRY

    def touch(self, tick):
        self.last_tick = tick

    def snapshot(self, tick=None):
        """Plain data, for the panels, ``/why`` and the tests."""
        out = {
            "turns": [{k: v for k, v in t.items() if k != "sig"} for t in self.turns],
            "questions": len(self.questions),
            "asks": len(self.asks),
            "promises": len(self.promises),
            "since": self.started,
            "last": self.last_tick,
        }
        if tick is not None:
            out["silent_for"] = tick - self.last_tick
        return out

    def __repr__(self):
        return "<DialogueState %d turns, %d owed, last t%d>" % (
            len(self.turns), len(self.questions), self.last_tick)


# ---------------------------------------------------------------------------
# The doors
# ---------------------------------------------------------------------------


def book(dwarf):
    """The dwarf's whole dialogue book, made on first use.

    :class:`dwarfsim.world.Agent` declares ``dialogue`` in its slots; an agent-shaped object
    built by a test that did not is given the attribute here rather than blowing up.
    """
    got = getattr(dwarf, "dialogue", None)
    if got is None:
        got = {}
        try:
            dwarf.dialogue = got
        except AttributeError:                       # pragma: no cover - exotic stand-in
            return {}
    return got


def of(dwarf, speaker_id, tick=0):
    """This dwarf's state for one speaker, made on first use and expired after a long silence."""
    shelf = book(dwarf)
    got = shelf.get(speaker_id)
    if got is None or got.expired(tick):
        got = DialogueState(tick)
        shelf[speaker_id] = got
    return got


def peek(dwarf, speaker_id):
    """The state if there is a live one, without making one. ``None`` otherwise."""
    return book(dwarf).get(speaker_id)


def forget(dwarf, speaker_id=None):
    shelf = book(dwarf)
    if speaker_id is None:
        shelf.clear()
    else:
        shelf.pop(speaker_id, None)


def expire(dwarf, tick):
    """Drop every conversation that has gone quiet. Cheap, and called once a tick at most."""
    shelf = book(dwarf)
    for who in [k for k, v in shelf.items() if v.expired(tick)]:
        del shelf[who]
