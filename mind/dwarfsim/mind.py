"""The persistent mind: emotions, needs, traits, relationships -- and one event table.

The point of this module is that every state change an *event* causes is in
:data:`EVENT_TABLE` and applied by :func:`apply_event`, so the whole social rule set is readable on
one screen. Skills change inventory, health and needs; events change how a dwarf feels about the
world and about each other, and nothing else does.

Ranges
------
* emotions  ``anger fear happiness grief``     0..1, decay toward a per-dwarf baseline
* needs     ``hunger thirst fatigue social``   0..1, rise every tick, dropped by skills
* traits    ``bravery greed temper sociability pride forgiveness`` 0..1, static, rolled per seed
* relationships, one row per other dwarf (and for the player):
    - ``trust``   -1..1   would I leave my ore with them
    - ``respect`` -1..1   do they matter, are they dangerous, did they stand at the gate
    - ``hatred``   0..1   would I swing at them
    - ``warmth``   0..1   how well disposed do I *feel* right now, as against what I believe
  the first three decay slowly toward 0; warmth decays about four times as fast. Only the first
  three are in the observation vector.

``pride`` is how much being humiliated in front of others costs; ``forgiveness`` is how fast an
episodic memory's salience fades (:mod:`dwarfsim.memory`), which is what lets two dwarves with the
same relationship numbers hold a grudge for very different lengths of time.
"""

from . import regard
from .schema import INVENTORY, INVENTORY_SCALE, MAX_HEALTH, MIND_SIZE, FOCUS_SLOTS

EMOTIONS = ("anger", "fear", "happiness", "grief")
NEEDS = ("hunger", "thirst", "fatigue", "social")
TRAITS = ("bravery", "greed", "temper", "sociability", "pride", "forgiveness")
REL_FIELDS = ("trust", "respect", "hatred")

#: The fourth number in a relationship row, and the odd one out: see :mod:`dwarfsim.regard`.
#: ``warmth`` 0..1 is how well disposed a dwarf *feels* toward another right now, as against
#: what it believes about them. Words buy warmth; deeds buy trust. It decays much faster than
#: the other three, it is not in the observation vector, and everything that asks "do I like
#: this dwarf" reads it while everything that asks "do I rely on this dwarf" does not.
WARMTH_DECAY = 0.003

#: How fast each need climbs per tick with nothing done about it.
NEED_RATES = {"hunger": 0.0035, "thirst": 0.0050, "fatigue": 0.0030, "social": 0.0035}

#: Fraction of the gap to the baseline closed per tick. Fear burns off fast, grief lingers.
EMOTION_DECAY = {"anger": 0.030, "fear": 0.055, "happiness": 0.020, "grief": 0.006}

#: Fraction of a relationship value shed per tick. Half-life about 460 ticks: a grudge outlives the
#: quarrel that made it, but not forever.
REL_DECAY = 0.0015

NEUTRAL_REL = {"trust": 0.0, "respect": 0.0, "hatred": 0.0, "warmth": 0.0}


def _clamp01(x):
    return 0.0 if x < 0.0 else (1.0 if x > 1.0 else x)


def _clamp11(x):
    return -1.0 if x < -1.0 else (1.0 if x > 1.0 else x)


class MindState:
    """Everything a dwarf carries between ticks."""

    __slots__ = ("emotions", "baseline", "needs", "traits", "rels")

    def __init__(self, emotions, baseline, needs, traits):
        self.emotions = dict(emotions)
        self.baseline = dict(baseline)
        self.needs = dict(needs)
        self.traits = dict(traits)
        self.rels = {}

    @classmethod
    def random(cls, rng):
        baseline = {
            "anger": rng.uniform(0.02, 0.12),
            "fear": rng.uniform(0.02, 0.12),
            "happiness": rng.uniform(0.30, 0.60),
            "grief": 0.0,
        }
        traits = {t: round(rng.uniform(0.1, 0.9), 3) for t in TRAITS}
        needs = {n: round(rng.uniform(0.05, 0.35), 3) for n in NEEDS}
        return cls(dict(baseline), baseline, needs, traits)

    # -- relationships ------------------------------------------------------

    def rel(self, other_id):
        r = self.rels.get(other_id)
        if r is None:
            r = {"trust": 0.0, "respect": 0.0, "hatred": 0.0, "warmth": 0.0}
            self.rels[other_id] = r
        return r

    def likes(self, other_id):
        """One number for 'how well disposed am I', used to modulate witness reactions."""
        r = self.rels.get(other_id)
        if r is None:
            return 0.0
        return _clamp11(r["trust"] * 0.7 + r["respect"] * 0.3 - r["hatred"]
                        + r["warmth"] * 0.4)

    # -- per-tick drift -----------------------------------------------------

    def decay(self):
        """Emotions toward the baseline, needs upward, relationships toward neutral."""
        for e in EMOTIONS:
            v = self.emotions[e]
            self.emotions[e] = _clamp01(v + (self.baseline[e] - v) * EMOTION_DECAY[e])
        for n in NEEDS:
            self.needs[n] = _clamp01(self.needs[n] + NEED_RATES[n])
        for r in self.rels.values():
            for f in REL_FIELDS:
                r[f] *= (1.0 - REL_DECAY)
            r["warmth"] *= (1.0 - WARMTH_DECAY)

    def satisfy(self, need, amount):
        self.needs[need] = _clamp01(self.needs[need] - amount)

    # -- the flat vector ----------------------------------------------------

    def to_vector(self, body=None, focus_ids=(), tick=0):
        """Flat floats, laid out by :mod:`dwarfsim.schema`.

        ``body`` is anything with ``.health``, ``.inv`` and ``.memories`` (an
        :class:`~dwarfsim.world.Agent`); without it those blocks read zero. ``focus_ids`` are the
        dwarves whose relationship triples fill the focus slots, most salient first, short lists
        padded with zeros. Each focus slot also carries the two numbers episodic memory derives
        about that dwarf: how much I hold against them and how much I owe them.
        """
        v = [0.0] * MIND_SIZE
        i = 0
        for e in EMOTIONS:
            v[i] = self.emotions[e]
            i += 1
        for n in NEEDS:
            v[i] = self.needs[n]
            i += 1
        for t in TRAITS:
            v[i] = self.traits[t]
            i += 1
        if body is not None:
            v[i] = _clamp01(body.health / MAX_HEALTH)
        i += 1
        for slot in INVENTORY:
            if body is not None:
                v[i] = _clamp01(body.inv[slot] / INVENTORY_SCALE[slot])
            i += 1
        book = getattr(body, "memories", None)
        for s in range(FOCUS_SLOTS):
            if s < len(focus_ids):
                r = self.rel(focus_ids[s])
                v[i] = 1.0
                v[i + 1] = r["trust"]
                v[i + 2] = r["respect"]
                v[i + 3] = r["hatred"]
                if book is not None:
                    v[i + 4] = _clamp01(book.grudge(tick, body, focus_ids[s]))
                    v[i + 5] = _clamp01(book.gratitude(tick, body, focus_ids[s]))
            i += 6
        return v

    def snapshot(self):
        return {
            "emotions": dict(self.emotions),
            "needs": dict(self.needs),
            "traits": dict(self.traits),
            "rels": {k: dict(v) for k, v in self.rels.items()},
        }


# ---------------------------------------------------------------------------
# The one event table
# ---------------------------------------------------------------------------
#
# Per event:
#   target       emotion deltas on whoever it was done to
#   target_rel   how the target's view of the actor moves
#   witness      emotion deltas on everyone else present
#   witness_rel  how each witness's view of the actor moves
#   actor        emotion deltas on whoever did it
#   actor_rel    how the actor's view of the target moves
#
# Every delta is then modulated by the receiver, in apply_event():
#   * anger gains scale with the receiver's temper      (x 0.6 .. 1.4)
#   * fear  gains scale with the receiver's cowardice   (x 0.6 .. 1.4)
#   * witness deltas are cut to WITNESS_FRACTION, then multiplied by
#     (1 + likes(witness -> victim)), so a friend of the victim reacts up to twice as hard and
#     someone who hates the victim barely reacts at all
#   * everything is scaled by the caller's `magnitude` (speech passes aggression through here)

WITNESS_FRACTION = 0.45

EVENT_TABLE = {
    "INSULT": {
        "target": {"anger": +0.26, "happiness": -0.10},
        "target_rel": {"trust": -0.10, "respect": -0.06, "hatred": +0.11},
        "witness": {"anger": +0.03},
        "witness_rel": {"trust": -0.05, "respect": -0.02, "hatred": +0.04},
        "actor": {"anger": -0.04},
        "actor_rel": {"hatred": +0.03},
    },
    "SLUR": {
        # An insult aimed at what somebody *is* rather than at what they did: a tier 3 term out
        # of the profanity lexicon (:mod:`dwarfsim.profanity`). It lands harder than an INSULT
        # on the one it was aimed at -- more anger, twice the hatred, and it leaves a mark the
        # decay takes much longer to shift -- and the room takes it as it takes an insult, which
        # is what the equal witness row says. Speaking one is also a public act: the speaker's
        # own hatred hardens rather than venting, so it does not work as a way of cooling off.
        "target": {"anger": +0.40, "happiness": -0.18, "grief": +0.05},
        "target_rel": {"trust": -0.18, "respect": -0.12, "hatred": +0.22},
        "witness": {"anger": +0.03},
        "witness_rel": {"trust": -0.05, "respect": -0.02, "hatred": +0.04},
        "actor": {"anger": -0.02},
        "actor_rel": {"hatred": +0.05},
    },
    "PRAISE": {
        "target": {"happiness": +0.16, "anger": -0.07},
        "target_rel": {"trust": +0.07, "respect": +0.04, "hatred": -0.07},
        "witness": {"happiness": +0.02},
        "witness_rel": {"trust": +0.03, "respect": +0.02},
        "actor": {"happiness": +0.04},
        "actor_rel": {"trust": +0.03},
    },
    "FLATTERY": {
        # The same words, read for what they are. Once a dwarf is suspicious of a speaker
        # (:mod:`dwarfsim.regard`), praise from them stops being praise: the valence flips, the
        # room is not impressed either, and saying it again makes it worse rather than better.
        "target": {"anger": +0.05, "happiness": -0.03},
        "target_rel": {"trust": -0.05, "respect": -0.04, "hatred": +0.02},
        "witness": {},
        "witness_rel": {"trust": -0.01, "respect": -0.02},
        "actor": {},
        "actor_rel": {},
    },
    "SMALLTALK": {
        "target": {"happiness": +0.05},
        "target_rel": {"trust": +0.03},
        "witness": {"happiness": +0.01},
        "witness_rel": {"trust": +0.01},
        "actor": {"happiness": +0.03},
        "actor_rel": {"trust": +0.02},
    },
    "THREAT": {
        "target": {"fear": +0.22, "anger": +0.14},
        "target_rel": {"trust": -0.14, "respect": +0.04, "hatred": +0.09},
        "witness": {"fear": +0.08},
        "witness_rel": {"trust": -0.07, "respect": +0.03, "hatred": +0.04},
        "actor": {"anger": -0.02},
    },
    "ACCUSE": {
        "target": {"anger": +0.17, "happiness": -0.05},
        "target_rel": {"trust": -0.09, "hatred": +0.06},
        "witness": {},
        "witness_rel": {"trust": -0.04, "hatred": +0.02},
        "actor": {"anger": +0.03},
        "actor_rel": {"trust": -0.05},
    },
    "APOLOGY": {
        "target": {"anger": -0.22, "happiness": +0.07},
        "target_rel": {"trust": +0.11, "respect": -0.02, "hatred": -0.10},
        "witness": {},
        "witness_rel": {"trust": +0.03, "respect": -0.01},
        "actor": {"anger": -0.10, "happiness": +0.02},
        "actor_rel": {"hatred": -0.06},
    },
    "GIFT": {
        "target": {"happiness": +0.14, "anger": -0.06},
        "target_rel": {"trust": +0.16, "respect": +0.04, "hatred": -0.08},
        "witness": {"happiness": +0.02},
        "witness_rel": {"trust": +0.05, "respect": +0.03},
        "actor": {"happiness": +0.03},
        "actor_rel": {"trust": +0.04},
    },
    "STEAL": {
        "target": {"anger": +0.34, "happiness": -0.08},
        "target_rel": {"trust": -0.30, "respect": -0.05, "hatred": +0.18},
        "witness": {"anger": +0.05},
        "witness_rel": {"trust": -0.16, "respect": -0.06, "hatred": +0.08},
        "actor": {"fear": +0.06, "happiness": +0.05},
    },
    "HIT": {
        "target": {"anger": +0.33, "fear": +0.26},
        "target_rel": {"trust": -0.24, "respect": +0.05, "hatred": +0.25},
        "witness": {"fear": +0.11, "anger": +0.03},
        "witness_rel": {"trust": -0.11, "respect": +0.04, "hatred": +0.07},
        "actor": {"anger": +0.04, "fear": +0.03},
        "actor_rel": {"hatred": +0.05},
    },
    "KILL": {
        # The dead take no deltas. Witnesses get the emotions below and a relationship shift toward
        # the killer computed from how they felt about the dead: see apply_event()'s KILL branch.
        "target": {},
        "target_rel": {},
        "witness": {"fear": +0.38, "grief": +0.42, "happiness": -0.20},
        "witness_rel": {},
        "actor": {"fear": +0.10, "grief": +0.08, "anger": -0.15},
    },
    "HELP": {
        # Someone fought the monster while you stood there.
        "target": {"fear": -0.10, "happiness": +0.08},
        "target_rel": {"trust": +0.08, "respect": +0.14, "hatred": -0.05},
        "witness": {"fear": -0.06, "happiness": +0.05},
        "witness_rel": {"trust": +0.06, "respect": +0.14, "hatred": -0.04},
        "actor": {"happiness": +0.06},
    },
    "WARNING": {
        "target": {"fear": +0.13},
        "target_rel": {"trust": +0.04, "respect": +0.02},
        "witness": {"fear": +0.06},
        "witness_rel": {"trust": +0.02},
        "actor": {},
    },

    # -- v1: the graded reactions, bargaining, gossip and authority ----------------

    "IGNORE": {
        # Letting it go costs a little cheer and leaves the memory sitting exactly where it was.
        # The one ignored never knows, so their row is empty.
        "target": {},
        "target_rel": {},
        "witness": {},
        "witness_rel": {},
        "actor": {"happiness": -0.03},
        "actor_rel": {"hatred": +0.02},
    },
    "RETORT": {
        # An insult back. It lands nearly as hard, but a room that saw the provocation judges the
        # retorter far less harshly than it judged the one who started it, and the retorter vents.
        "target": {"anger": +0.21, "happiness": -0.06},
        "target_rel": {"trust": -0.06, "respect": -0.01, "hatred": +0.08},
        "witness": {"anger": +0.02},
        "witness_rel": {"trust": -0.02, "hatred": +0.01},
        "actor": {"anger": -0.13, "happiness": +0.02},
        "actor_rel": {"hatred": +0.02},
    },
    "DEMAND": {
        # "You will say sorry for that." Standing on your dignity in front of the room.
        "target": {"anger": +0.12, "fear": +0.05},
        "target_rel": {"trust": -0.03, "respect": +0.06},
        "witness": {},
        "witness_rel": {"respect": +0.03},
        "actor": {"anger": -0.05},
    },
    "REFUSE_APOLOGY": {
        # Being told no in front of witnesses is worse than the original slight.
        "target": {"anger": +0.18, "happiness": -0.06},
        "target_rel": {"trust": -0.08, "respect": -0.04, "hatred": +0.07},
        "witness": {"anger": +0.02},
        "witness_rel": {"trust": -0.06, "respect": -0.04, "hatred": +0.03},
        "actor": {"anger": +0.02},
        "actor_rel": {"hatred": +0.03},
    },
    "COMPLAIN": {
        # Telling someone you trust what was done to you. The shift in the listener's view of the
        # one complained about is applied separately, by shift_opinion(), because the table only
        # knows about the actor and the target.
        "target": {"anger": +0.04},
        "target_rel": {"trust": +0.03, "respect": -0.01},
        "witness": {},
        "witness_rel": {},
        "actor": {"anger": -0.06, "happiness": +0.03},
        "actor_rel": {"trust": +0.04},
    },
    "GOSSIP": {
        # Deliberately tiny. Gossip is frequent, and anything it adds to trust compounds into the
        # settlement adoring itself within a few hundred ticks. What gossip really moves is the
        # listener's view of the dwarf being talked about, and that is done in the skill.
        "target": {"happiness": +0.02},
        "target_rel": {"trust": +0.012},
        "witness": {},
        "witness_rel": {},
        "actor": {"happiness": +0.015},
        "actor_rel": {"trust": +0.012},
    },
    "AVOID": {
        # Walking out on someone. They notice, and it is a small public snub.
        "target": {"anger": +0.04},
        "target_rel": {"trust": -0.02},
        "witness": {},
        "witness_rel": {"respect": -0.02},
        "actor": {"fear": -0.04, "anger": -0.05},
    },
    "ACCEPT": {
        "target": {"happiness": +0.06},
        "target_rel": {"trust": +0.05, "respect": +0.03},
        "witness": {},
        "witness_rel": {"trust": +0.01},
        "actor": {},
    },
    "REFUSE": {
        "target": {"anger": +0.09, "happiness": -0.03},
        "target_rel": {"trust": -0.06, "respect": -0.04},
        "witness": {},
        "witness_rel": {"respect": -0.01},
        "actor": {},
    },
    "BARGAIN": {
        "target": {"anger": +0.03},
        "target_rel": {"trust": -0.02, "respect": +0.03},
        "witness": {},
        "witness_rel": {},
        "actor": {},
    },
    "PROMISE_KEPT": {
        "target": {"happiness": +0.11, "anger": -0.05},
        "target_rel": {"trust": +0.19, "respect": +0.09, "hatred": -0.07},
        "witness": {"happiness": +0.02},
        "witness_rel": {"trust": +0.06, "respect": +0.05},
        "actor": {"happiness": +0.06},
        "actor_rel": {"trust": +0.09, "respect": +0.03},
    },
    "BROKEN_PROMISE": {
        "target": {"anger": +0.22, "happiness": -0.09},
        "target_rel": {"trust": -0.30, "respect": -0.11, "hatred": +0.12},
        "witness": {"anger": +0.03},
        "witness_rel": {"trust": -0.13, "respect": -0.07, "hatred": +0.03},
        "actor": {"happiness": -0.04},
    },
    "PUNISH": {
        # The chief fines or rebukes. The culprit resents it a little and respects it a lot; the
        # room's loss of respect for the *culprit* is applied by shift_opinion() in the skill.
        "target": {"anger": +0.20, "fear": +0.18, "happiness": -0.12},
        "target_rel": {"trust": -0.05, "respect": +0.12, "hatred": +0.05},
        "witness": {"fear": +0.05},
        "witness_rel": {"respect": +0.09, "trust": +0.03},
        "actor": {},
    },
}

#: How much a witness's view of the *killer* moves per point of how much they liked the dead.
#: Liked the dead -> hatred and lost trust; hated the dead -> a wary sort of respect.
KILL_WITNESS_LIKED = {"trust": -0.30, "respect": +0.10, "hatred": +0.35}
KILL_WITNESS_HATED = {"trust": -0.05, "respect": +0.30, "hatred": -0.10}


def _mod(field, amount, mind):
    """Temper makes anger bigger, bravery makes fear smaller. Only gains are modulated."""
    if amount <= 0.0:
        return amount
    if field == "anger":
        return amount * (0.6 + 0.8 * mind.traits["temper"])
    if field == "fear":
        return amount * (1.4 - 0.8 * mind.traits["bravery"])
    return amount


def _emote(deltas, agent, field, amount, scale):
    amount = _mod(field, amount, agent.mind) * scale
    if amount == 0.0:
        return
    before = agent.mind.emotions[field]
    after = _clamp01(before + amount)
    if after != before:
        agent.mind.emotions[field] = after
        deltas.append({"who": agent.id, "f": field, "from": before, "to": after})


def _relate(deltas, agent, other_id, field, amount, scale):
    amount *= scale
    if amount == 0.0:
        return
    r = agent.mind.rel(other_id)
    before = r[field]
    # Diminishing returns: a change that pushes away from neutral is scaled by the headroom left,
    # so the hundredth kind word is worth much less than the first, and nothing saturates at 1.0
    # and stays there. A change back toward neutral is never damped.
    if (amount > 0.0) == (before >= 0.0):
        amount *= max(0.0, 1.0 - abs(before))
    after = _clamp01(before + amount) if field == "hatred" else _clamp11(before + amount)
    if after != before:
        r[field] = after
        deltas.append({"who": agent.id, "f": "rel:%s:%s" % (other_id, field),
                       "from": before, "to": after})


def shift_opinion(agent, about_id, table, scale=1.0):
    """Move one dwarf's view of a *third party*, and report the deltas.

    The event table only knows about the actor and the target, so the two acts that are explicitly
    about somebody who is not in the room -- gossip and a complaint -- use this instead. It is the
    same machinery, including diminishing returns.
    """
    deltas = []
    if about_id is None or agent.id == about_id:
        return deltas
    for field, amount in table.items():
        _relate(deltas, agent, about_id, field, amount, scale)
    return deltas


def _target_rel(deltas, target, actor, kind, row, magnitude, tick):
    """How the one it was done to now sees whoever did it -- words rationed, deeds not.

    This is the "words against deeds" half of :mod:`dwarfsim.regard`. A word's *positive* trust
    and respect are cut to :data:`~dwarfsim.regard.WORD_TRUST_FRACTION`, the rest becomes warmth,
    and trust is additionally held under a rolling cap, so no amount of talking does what one
    kept promise does. Nothing damps a word's *negative* deltas: an insult is a deed to whoever
    it lands on, and habituation has already quietened a repeated one before it gets here.
    """
    words = kind in regard.WORD_EVENTS
    reg = regard.of(target, actor.id) if (words or kind in regard.DEED_EVENTS) else None
    for f, a in row.get("target_rel", {}).items():
        amount = a * magnitude
        if words and a > 0.0 and f in ("trust", "respect"):
            amount, warm = reg.word_gain(tick, f, amount)
            if warm > 0.0:
                row_ = target.mind.rel(actor.id)
                before = row_["warmth"]
                row_["warmth"] = min(1.0, before + warm)
                if row_["warmth"] != before:
                    deltas.append({"who": target.id, "f": "rel:%s:warmth" % actor.id,
                                   "from": before, "to": row_["warmth"]})
        _relate(deltas, target, actor.id, f, amount, 1.0)
    if reg is not None and kind in regard.DEED_EVENTS and magnitude > 0.0:
        # Evidence. Suspicion of a speaker does not survive them actually doing something.
        before = reg.suspicion
        reg.deed(tick)
        if before:
            deltas.append({"who": target.id, "f": "suspicion:%s" % actor.id,
                           "from": round(before, 4), "to": 0.0})


def apply_event(kind, actor=None, target=None, witnesses=(), magnitude=1.0, tick=0):
    """Apply one social event and return the list of state deltas it caused.

    ``actor`` is whoever did it (``None`` for the world itself: a monster's THREAT), ``target``
    whoever it was done to, ``witnesses`` everyone else present. Each delta is
    ``{"who": agent id, "f": field, "from": x, "to": y}`` and goes straight into the log, which is
    what makes an event in the viewer say what it actually changed.

    ``tick`` is when, which the rolling windows in :mod:`dwarfsim.regard` need; it defaults to 0
    so a bare ``apply_event`` in a test still works, with every window starting at the beginning.
    """
    row = EVENT_TABLE[kind]
    deltas = []

    if target is not None and target.alive:
        for f, a in row["target"].items():
            _emote(deltas, target, f, a, magnitude)
        if actor is not None and actor.id != target.id:
            _target_rel(deltas, target, actor, kind, row, magnitude, tick)

    if actor is not None and actor.alive:
        for f, a in row.get("actor", {}).items():
            _emote(deltas, actor, f, a, magnitude)
        if target is not None and target.id != actor.id:
            for f, a in row.get("actor_rel", {}).items():
                _relate(deltas, actor, target.id, f, a, magnitude)

    for w in witnesses:
        if not w.alive or (actor is not None and w.id == actor.id):
            continue
        if target is not None and w.id == target.id:
            continue
        # A friend of the victim takes it harder; someone who hated the victim shrugs.
        sympathy = 1.0
        if target is not None:
            sympathy = max(0.0, 1.0 + w.mind.likes(target.id))
        scale = magnitude * WITNESS_FRACTION * sympathy
        for f, a in row["witness"].items():
            _emote(deltas, w, f, a, scale)
        if actor is None or w.id == actor.id:
            continue
        if kind == "KILL":
            liked = w.mind.likes(target.id) if target is not None else 0.0
            table = KILL_WITNESS_LIKED if liked >= 0.0 else KILL_WITNESS_HATED
            weight = min(1.0, abs(liked) + 0.25)
            for f, a in table.items():
                _relate(deltas, w, actor.id, f, a * weight, magnitude)
        else:
            for f, a in row.get("witness_rel", {}).items():
                _relate(deltas, w, actor.id, f, a, magnitude * WITNESS_FRACTION * sympathy)

    return deltas
