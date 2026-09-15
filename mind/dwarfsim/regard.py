"""Why "wow, nice sword" twenty times does not buy a friend.

Every social event used to be worth the same as the one before it, so the cheapest way to be
adored was to paste one compliment into the talk app until the trust bar filled. This module is
the answer, and it is four mechanics rather than a list of banned strings:

**Habituation.** Every social event carries a *signature*: the interpreter's labels plus a
normalised hash of the words. Each dwarf keeps a small ring of the signatures it has heard from
each speaker, and the same signature again is worth much less -- near nothing after the second
time. A different sentence with the same intent and topic decays too, just far more slowly.
:func:`Regard.repetition` is that number, and it multiplies the event's magnitude, so it is the
event table itself that lands quieter.

**Words against deeds.** Praise and greetings move a small, capped, decaying **warmth**. Trust and
respect move through *deeds*: helping, giving something that cost you, keeping a promise, standing
at the gate beside somebody. Only :data:`WORD_TRUST_FRACTION` of a word's trust is real trust, the
rest becomes warmth, and there is a hard cap on how much trust one speaker can talk their way into
over :data:`SPEECH_WINDOW` ticks.

**Flattery suspicion.** Praise that is frequent, unearned -- the dwarf being praised has done
nothing worth praising lately -- or repeated raises the listener's suspicion of that speaker. Past
:data:`SUSPICION_THRESHOLD` the next compliment is read as a manipulation: it becomes a
``FLATTERY`` event instead of a ``PRAISE``, which costs trust rather than buying it, and the dwarf
says so. Suspicion decays slowly, and a genuine deed clears it outright.

**Gifts.** A gift is worth what it cost the giver relative to what the giver has, and repeated
gifts from the same hand are worth geometrically less. Twenty coins handed over one at a time are
worth less than one gift of ten.

All of it is per *(listener, speaker)* -- the ring, the warmth, the ledger, the suspicion -- and it
lives on the listener, in ``agent.regard``. Nothing here is random: the same seed replays.
"""

import hashlib
import re

# ---------------------------------------------------------------------------
# Signatures
# ---------------------------------------------------------------------------

_WORD = re.compile(r"[^a-z0-9 ]+")
_SPACE = re.compile(r"\s+")


def normalise(text):
    """Lower-case, strip punctuation, collapse the spaces. Paste detection, and nothing more."""
    if not text:
        return ""
    return _SPACE.sub(" ", _WORD.sub(" ", str(text).lower())).strip()


def text_hash(text):
    """A stable digest of the normalised line. Stable across processes, unlike ``hash()``."""
    flat = normalise(text)
    if not flat:
        return ""
    return hashlib.sha1(flat.encode("utf-8")).hexdigest()[:12]


def signature(kind, parsed=None):
    """``(event kind, intent, topic, text hash)`` -- what makes two social events the same one.

    With no text -- a skill's own event, or a line the classifier never saw -- the hash is empty
    and the labels alone carry it, which is exactly right: a dwarf saying the same kind of thing
    about the same thing is repeating itself whether or not the words matched.
    """
    p = parsed or {}
    return (str(kind), str(p.get("intent") or kind), str(p.get("topic") or "NONE"),
            text_hash(p.get("text")))


# ---------------------------------------------------------------------------
# The numbers
# ---------------------------------------------------------------------------

#: How many recent signatures one relationship remembers, and for how long an *identical* one
#: counts against the next.
RING = 12
HABIT_WINDOW = 400

#: What the 1st, 2nd, 3rd ... identical signature is worth. Past the end: nothing.
IDENTICAL = (1.0, 0.35, 0.06, 0.02)

#: And for a different line with the same intent and topic. Two things keep this gentle, and
#: both had to be learned the hard way: the window is much shorter than the identical one, and
#: the floor is high. Six dwarves in six rooms talk small talk about the mine all day; treating
#: that as spam took the settlement's goodwill away and the killings went up by half. Saying the
#: same *words* again is repetition. Saying another true thing about the same subject is
#: conversation.
SIMILAR_WINDOW = 120
SIMILAR_BASE = 0.80
SIMILAR_FLOOR = 0.35

#: How much of a *word's* trust or respect gain is real, and where the rest goes.
WORD_TRUST_FRACTION = 0.25
WARMTH_SHARE = 1.00
#: All the trust one speaker can talk their way into over SPEECH_WINDOW ticks.
SPEECH_TRUST_CAP = 0.10
SPEECH_WINDOW = 400
#: Warmth is a mood about somebody, not a belief about them: it goes back down. Half of it is
#: gone in about 170 ticks with nothing said.
WARMTH_DECAY = 0.003
#: How much warmth counts wherever a dwarf acts on how well disposed it feels -- the
#: arbitrator's ``trust_target``, and whether small talk comes out friendly.
#:
#: This weight is why rationing words did not turn the settlement into a knife fight. Warmth is
#: real: a room that has been chatting all afternoon behaves like a room that likes each other.
#: What it is not is *evidence*, so it decays, it is capped, and it is not what a dwarf leans on
#: when the question is whether to hand somebody their ore.
WARMTH_WEIGHT = 0.90

#: Praise that earns nothing raises this much suspicion, scaled by how unearned, how repetitive
#: and how frequent it is. All three multiply, and all three have to be there to get anywhere:
#: an early draft where any one of them was enough had sociable dwarves reading each other as
#: manipulators within a few hundred ticks, and the settlement fell apart.
SUSPICION_GAIN = 0.20
SUSPICION_THRESHOLD = 0.50
SUSPICION_DECAY = 0.0015
#: How recently the praised dwarf must have done something for the praise to be earned. A shift
#: at the seam counts: see :data:`ACHIEVEMENTS`.
DEED_WINDOW = 250
#: How far back praise counts as "frequent", and how many are free before it starts to.
PRAISE_WINDOW = 300
FREE_PRAISES = 2

#: Gifts: how fast repeats lose their value, and how long a hand is remembered.
GIFT_REPEAT = 0.60
GIFT_WINDOW = 600
#: What one of each is worth when the giver's wealth is weighed.
ITEM_VALUE = {"gold": 1.0, "ore": 0.5, "ale": 0.4, "food": 0.4, "weapon": 6.0}

#: Events that are only words, in the sense that matters: cheap, repeatable and free. Their
#: positive trust and respect are rationed into warmth.
#:
#: An **apology** is deliberately not here, and neither is an accepted ask. Both cost the one
#: making them -- an apology is humbling and the room watches it happen, and it is the only
#: thing in the sim that ends a feud. Rationing them once, in an early draft, left the
#: settlement with no way back from a quarrel and the killings went up by half. Habituation
#: still quietens a repeated apology, which is the right answer to somebody who keeps saying
#: sorry and keeps doing it.
WORD_EVENTS = frozenset(("PRAISE", "SMALLTALK", "GOSSIP"))

#: Events that are deeds. These move trust in full, and clear suspicion of whoever did them.
DEED_EVENTS = frozenset(("HELP", "GIFT", "PROMISE_KEPT"))

#: What counts as the praised dwarf having earned it: something they did, that somebody saw.
#: A productive shift counts too and is not in this set, because only ``WORK`` knows whether the
#: shift produced anything -- :class:`dwarfsim.skills.Work` stamps ``last_deed`` itself. Without
#: that, a dwarf who mines all day is "unearned" forever and every kind word about the ore is
#: read as flattery.
ACHIEVEMENTS = frozenset(("HELP", "GIFT", "PROMISE_KEPT", "MONSTER_SLAIN", "FIGHT_MONSTER"))


# ---------------------------------------------------------------------------


class Regard:
    """What one dwarf has learned about being talked at by one other dwarf."""

    __slots__ = ("ring", "spoken", "suspicion", "gifts", "praises", "last_deed")

    def __init__(self):
        self.ring = []          # [(tick, signature)], newest last, at most RING
        self.spoken = []        # [(tick, trust gained by speech)]
        self.suspicion = 0.0    # 0..1
        self.gifts = []         # [(tick, value)]
        self.praises = []       # [tick]
        self.last_deed = None   # the last tick this speaker did something real for me

    # -- habituation --------------------------------------------------------

    def repetition(self, tick, sig):
        """What a fresh event with this signature is still worth to me, 0..1."""
        same = similar = 0
        for when, old in self.ring:
            age = tick - when
            if age > HABIT_WINDOW:
                continue
            if old == sig:
                same += 1
            elif old[:3] == sig[:3] and age <= SIMILAR_WINDOW:
                similar += 1
        if same:
            return IDENTICAL[same] if same < len(IDENTICAL) else 0.0
        return max(SIMILAR_FLOOR, SIMILAR_BASE ** similar)

    def note(self, tick, sig):
        self.ring.append((tick, sig))
        if len(self.ring) > RING:
            del self.ring[0]

    # -- words against deeds ------------------------------------------------

    def spent(self, tick):
        self.spoken = [(t, a) for t, a in self.spoken if tick - t <= SPEECH_WINDOW]
        return sum(a for _, a in self.spoken)

    def word_gain(self, tick, field, amount):
        """Split one word's relationship gain into ``(real, warmth)``.

        Respect is rationed by the fraction alone; trust is also held under the rolling cap,
        which is what stops an afternoon of compliments from doing what a kept promise does.
        """
        kept = amount * WORD_TRUST_FRACTION
        if field == "trust":
            room = max(0.0, SPEECH_TRUST_CAP - self.spent(tick))
            kept = min(kept, room)
            if kept > 0.0:
                self.spoken.append((tick, kept))
        return kept, max(0.0, amount - kept) * WARMTH_SHARE

    def deed(self, tick):
        """Something real happened. Suspicion is not proof against evidence."""
        self.last_deed = tick
        self.suspicion = 0.0

    # -- flattery -----------------------------------------------------------

    def praises_in(self, tick):
        self.praises = [t for t in self.praises if tick - t <= PRAISE_WINDOW]
        return len(self.praises)

    def note_praise(self, tick):
        self.praises.append(tick)

    # -- gifts --------------------------------------------------------------

    def gift_repeats(self, tick):
        self.gifts = [(t, v) for t, v in self.gifts if tick - t <= GIFT_WINDOW]
        return len(self.gifts)

    def note_gift(self, tick, value):
        self.gifts.append((tick, float(value)))

    # -- per tick, and for the panels ---------------------------------------

    def decay(self):
        """Warmth is not here: it lives in the relationship row, and ``MindState.decay`` has it."""
        if self.suspicion > 0.0:
            self.suspicion = max(0.0, self.suspicion - SUSPICION_DECAY)

    def snapshot(self):
        return {"suspicion": round(self.suspicion, 3), "heard": len(self.ring),
                "gifts": len(self.gifts)}


# ---------------------------------------------------------------------------
# The doors
# ---------------------------------------------------------------------------


def of(listener, speaker_id):
    """The listener's regard for one speaker, made on first use."""
    got = listener.regard.get(speaker_id)
    if got is None:
        got = Regard()
        listener.regard[speaker_id] = got
    return got


def warmth(holder, other_id):
    """How warmly a conversation has left one dwarf feeling about another, 0..1.

    It lives in the relationship row beside trust, respect and hatred, because everything that
    reads how well disposed a dwarf is has to see it -- including
    :meth:`dwarfsim.mind.MindState.likes`, which has no idea this module exists.
    """
    r = holder.mind.rels.get(other_id)
    return r["warmth"] if r is not None else 0.0


def felt_trust(holder, other_id):
    """Trust, plus how warmly this afternoon's conversation has left me feeling about them.

    The number a dwarf *acts* on. ``rel["trust"]`` on its own is the number a dwarf *believes*,
    and it moves through deeds; this is the one that decides whether the next thing said comes
    out friendly. Anything that wants evidence rather than mood reads the relationship directly.
    """
    r = holder.mind.rel(other_id)
    return max(-1.0, min(1.0, r["trust"] + WARMTH_WEIGHT * r["warmth"]))


def suspicion(listener, speaker_id):
    got = listener.regard.get(speaker_id)
    return got.suspicion if got is not None else 0.0


def decay(agent):
    for reg in agent.regard.values():
        reg.decay()


def habituate(listener, speaker_id, tick, kind, parsed=None):
    """``(factor, signature)``: what this line is still worth, and what it was.

    The caller multiplies the event's magnitude by the factor and files the signature with
    :func:`note`, once the event is known to have happened.
    """
    reg = of(listener, speaker_id)
    sig = signature(kind, parsed)
    return reg.repetition(tick, sig), sig


def note(listener, speaker_id, tick, sig):
    of(listener, speaker_id).note(tick, sig)


def flattery(listener, speaker, tick, trust, habit=1.0):
    """Take one piece of praise. Returns ``(suspicion now, is it read as flattery)``.

    Three things make praise suspect, and they *multiply*, so any one of them on its own gets
    nowhere:

    * **unearned** -- the dwarf being praised has done nothing lately worth praising, not even a
      shift that produced something;
    * **repetitive** -- ``habit`` is what habituation already worked out about this line, and
      one minus it is how much of a paste this was. The identical compliment for the third time
      is precisely the one that gives the game away;
    * **frequent** -- past :data:`FREE_PRAISES` from this mouth inside :data:`PRAISE_WINDOW`.

    A dwarf who already trusts the speaker discounts all of it: a friend who watched you kill a
    creeper may say so all evening.
    """
    reg = of(listener, speaker.id)
    earned = (listener.last_deed is not None
              and tick - listener.last_deed <= DEED_WINDOW)
    unearned = 0.0 if earned else 1.0
    repeats = reg.praises_in(tick)
    ramp = min(1.0, max(0.0, repeats - FREE_PRAISES + 1) / 3.0)
    gain = (SUSPICION_GAIN
            * (0.15 + 0.85 * unearned)
            * (0.20 + 0.80 * (1.0 - min(1.0, max(0.0, habit))))
            * ramp
            * (1.0 - 0.60 * max(0.0, trust)))
    reg.suspicion = min(1.0, reg.suspicion + gain)
    reg.note_praise(tick)
    return reg.suspicion, reg.suspicion >= SUSPICION_THRESHOLD


def wealth_of(agent):
    """What a dwarf has, in gold-equivalents, so a gift can be priced against it."""
    total = 0.0
    for slot, value in ITEM_VALUE.items():
        total += value * agent.inv.get(slot, 0)
    return max(1.0, total)


def gift_magnitude(giver, receiver, item, count, tick):
    """How much one gift is worth to whoever receives it.

    Two things, and both of them are about the giver rather than the gift: what it cost them
    relative to what they had, and how many times they have done this already.
    """
    value = ITEM_VALUE.get(item, 1.0) * max(0, count)
    if value <= 0.0:
        return 0.0
    reg = of(receiver, giver.id)
    share = min(1.0, value / wealth_of(giver))
    magnitude = 0.30 + 1.70 * share
    magnitude *= GIFT_REPEAT ** reg.gift_repeats(tick)
    reg.note_gift(tick, value)
    reg.deed(tick)
    return round(min(1.80, magnitude), 3)
