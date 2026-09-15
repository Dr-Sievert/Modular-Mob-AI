"""Episodic memory: what a dwarf remembers, how fast it fades, and what it adds up to.

Relationships (``trust respect hatred``) stay the running state -- the number the arbitrator reads
every tick. Memory is the *episodes* behind those numbers: it is what explains a grudge, what
gossip transmits, and what a complaint to a friend is evidence of.

One record::

    Memory(tick, kind, actor, target, place, intensity, source, from_id, witnesses)

``kind`` is the event kind (``INSULT``, ``HIT``, ``STEAL``, ``GIFT``, ``HELP``,
``BROKEN_PROMISE`` ...), ``actor`` did it, ``target`` had it done to them, ``source`` is one of

* ``SEEN``      -- I watched it happen to someone else
* ``SUFFERED``  -- it happened to me
* ``HEARD``     -- someone told me, and ``from_id`` is who

Salience decays exponentially with age at a rate set by the holder's ``forgiveness`` trait, so a
forgiving dwarf's grudge is gone in a few hundred ticks and a bitter one's outlives the quarrel.
At most :data:`~dwarfsim.schema.MEMORY_CAP` memories are held; over that, the least salient is
forgotten.

Two derived numbers are what the arbitrator actually uses:

* ``grudge(who)``    -- summed salience of harms ``who`` did to me or to someone I like
* ``gratitude(who)`` -- summed salience of helps, gifts and kept promises from ``who``
"""

import math

from .schema import MEMORY_CAP

#: Event kinds that count as a harm when they are remembered.
HARM_KINDS = ("INSULT", "SLUR", "ACCUSE", "THREAT", "STEAL", "HIT", "KILL", "RETORT",
              "REFUSE_APOLOGY", "BROKEN_PROMISE", "PUNISH")

#: Event kinds that count as a kindness. Praise and apologies are deliberately not here:
#: they are cheap and frequent, and letting them accumulate turns gratitude into a constant.
HELP_KINDS = ("HELP", "GIFT", "PROMISE_KEPT")

#: The kinds a provocation reaction can be aimed at: what someone did *to me*.
#:
#: ``RETORT`` is deliberately absent. A retort is already an answer, and letting one be
#: answered in turn makes two dwarves trade insults every tick until one of them is dead --
#: which is what happened the first time this ran. He gave as good as he got, and that is
#: the end of it; the retort still lands in memory as a harm, so it still feeds grudges and
#: still travels as gossip.
PROVOCATION_KINDS = ("INSULT", "SLUR", "ACCUSE", "THREAT", "STEAL", "HIT", "REFUSE_APOLOGY")

#: Base fraction of salience shed per tick, before the forgiveness trait scales it.
DECAY_BASE = 0.0016

#: How long a suffered provocation stays answerable.
REACT_WINDOW = 45


class Memory:
    """One episode, as one dwarf holds it."""

    __slots__ = ("tick", "kind", "actor", "target", "place", "intensity",
                 "source", "from_id", "witnesses", "answered")

    def __init__(self, tick, kind, actor=None, target=None, place=None, intensity=1.0,
                 source="SEEN", from_id=None, witnesses=0):
        self.tick = tick
        self.kind = kind
        self.actor = actor
        self.target = target
        self.place = place
        self.intensity = float(intensity)
        self.source = source
        self.from_id = from_id
        self.witnesses = int(witnesses)
        self.answered = False

    def salience(self, tick, forgiveness=0.5):
        """How loud this still is, now. Intensity times an exponential in age."""
        age = tick - self.tick
        if age <= 0:
            return self.intensity
        rate = DECAY_BASE * (0.35 + 1.30 * forgiveness)
        return self.intensity * math.exp(-rate * age)

    def key(self):
        """Two dwarves remembering the same event hold the same key."""
        return episode_key(self.kind, self.actor, self.target, self.tick)

    def snapshot(self, tick, forgiveness=0.5):
        d = {"tick": self.tick, "kind": self.kind, "actor": self.actor, "target": self.target,
             "place": self.place, "intensity": round(self.intensity, 3), "source": self.source,
             "sal": round(self.salience(tick, forgiveness), 3), "wit": self.witnesses}
        if self.from_id is not None:
            d["from"] = self.from_id
        return d

    def __repr__(self):
        return "<mem t%d %s %s->%s %s %.2f>" % (
            self.tick, self.kind, self.actor, self.target, self.source, self.intensity)


def episode_key(kind, actor, target, tick):
    """What makes two memories the same *event*, however many heads it reaches."""
    return (kind, actor, target, tick)


class MemoryBook:
    """The bounded list one dwarf keeps, plus the per-tick derived sums."""

    __slots__ = ("items", "keys", "_cache_tick", "_grudge", "_gratitude")

    def __init__(self):
        self.items = []
        self.keys = set()
        self._cache_tick = -1
        self._grudge = {}
        self._gratitude = {}

    def __len__(self):
        return len(self.items)

    def __iter__(self):
        return iter(self.items)

    def knows(self, key):
        return key in self.keys

    def remember(self, tick, forgiveness, memory):
        """Store one memory, evicting the least salient if the book is full.

        An episode already in the book is not stored twice: hearing the same story from a
        second dwarf only makes it a little louder, and returns ``None``. Without that, one
        piece of gossip goes round the settlement for two thousand ticks and every retelling
        moves opinion again.
        """
        key = memory.key()
        if key in self.keys:
            for m in self.items:
                if m.key() == key:
                    m.intensity = max(m.intensity, memory.intensity * 0.6)
                    self._cache_tick = -1
                    return None
        self.items.append(memory)
        self.keys.add(key)
        if len(self.items) > MEMORY_CAP:
            worst, worst_i = None, -1
            for i, m in enumerate(self.items):
                s = m.salience(tick, forgiveness)
                if worst is None or s < worst:
                    worst, worst_i = s, i
            gone = self.items.pop(worst_i)
            self.keys.discard(gone.key())
        self._cache_tick = -1
        return memory

    # -- derived ------------------------------------------------------------

    def _recompute(self, tick, holder):
        """Sum salience into per-dwarf grudge and gratitude, once per tick."""
        forgiveness = holder.mind.traits["forgiveness"]
        grudge, gratitude = {}, {}
        for m in self.items:
            if m.actor is None or m.actor == holder.id:
                continue
            s = m.salience(tick, forgiveness)
            if s < 0.01:
                continue
            if m.kind in HARM_KINDS:
                # A harm counts fully if it was done to me, and by how much I like the victim
                # otherwise: hurting my friend is hurting me, hurting my enemy is not.
                if m.target == holder.id:
                    weight = 1.0
                elif m.target is None:
                    weight = 0.25
                else:
                    weight = max(0.0, holder.mind.likes(m.target)) * 0.8
                if weight > 0.0:
                    grudge[m.actor] = grudge.get(m.actor, 0.0) + s * weight
            elif m.kind in HELP_KINDS:
                if m.target == holder.id:
                    weight = 1.0
                elif m.target is None:
                    weight = 0.20
                else:
                    weight = max(0.0, holder.mind.likes(m.target)) * 0.5
                if weight > 0.0:
                    gratitude[m.actor] = gratitude.get(m.actor, 0.0) + s * weight
        self._grudge, self._gratitude = grudge, gratitude
        self._cache_tick = tick

    def grudge(self, tick, holder, who):
        """0..1. Summed salience, then softened, so a hundred small slights cannot outweigh
        every other term in the arbitrator the way a raw sum would."""
        if self._cache_tick != tick:
            self._recompute(tick, holder)
        x = self._grudge.get(who, 0.0)
        return x / (x + 1.0)

    def gratitude(self, tick, holder, who):
        if self._cache_tick != tick:
            self._recompute(tick, holder)
        x = self._gratitude.get(who, 0.0)
        return x / (x + 1.0)

    def invalidate(self):
        self._cache_tick = -1

    # -- queries the skills use --------------------------------------------

    def open_provocations(self, tick, holder):
        """Recent things done to me that I have not yet reacted to, freshest first."""
        out = []
        forgiveness = holder.mind.traits["forgiveness"]
        for m in self.items:
            if m.answered or m.source != "SUFFERED" or m.kind not in PROVOCATION_KINDS:
                continue
            if m.actor is None or m.actor == holder.id:
                continue
            if tick - m.tick > REACT_WINDOW:
                m.answered = True          # too old to be worth a scene
                continue
            out.append((m, m.salience(tick, forgiveness)))
        out.sort(key=lambda p: (-p[1], p[0].tick))
        return [m for m, _ in out]

    def tellable(self, tick, holder, n=5):
        """The few episodes about other dwarves worth repeating, loudest first.

        Gossip picks the loudest of these that the listener has not already heard, which is
        what makes a story spread across the settlement once and then stop.
        """
        forgiveness = holder.mind.traits["forgiveness"]
        scored = []
        for m in self.items:
            if m.actor is None or m.actor == holder.id:
                continue
            if m.kind not in HARM_KINDS and m.kind not in HELP_KINDS:
                continue
            s = m.salience(tick, forgiveness)
            if m.source == "HEARD":
                s *= 0.7           # you repeat what you saw before what you were told
            if s > 0.08:
                scored.append((-s, m.tick, s, m))
        scored.sort(key=lambda r: (r[0], r[1]))
        return [(m, s) for _, _, s, m in scored[:n]]

    def top(self, tick, forgiveness, n=8):
        ranked = sorted(self.items, key=lambda m: -m.salience(tick, forgiveness))
        return [m.snapshot(tick, forgiveness) for m in ranked[:n]]
