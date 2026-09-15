"""The bank and the chooser: how to say what the planner decided to say.

The second half of the pipeline. :mod:`dwarfsim.speechplan` has already settled the **act**; this
module holds every line anybody wrote for every act, indexed by the tags each line was written
for, and picks one.

**The file.** ``mind/text/data/replies.jsonl``, one line per reply::

    {"hears": {"intent": "SMALLTALK", "about": "SPEAKER", "news": "MISFORTUNE",
               "topic": "NONE", "sincerity": "SINCERE", "heat": "calm"},
     "state": {"mood": "flat", "trust": "warm", "condition": "healthy", "suspicion": "low"},
     "act": "SYMPATHIZE", "slots": ["speaker"],
     "text": "That's a rotten run of luck, {speaker}. Sit by the fire a while."}

That file is written by ``mind/text/merge_replies.py`` out of the Cursor batches. Until it
exists, :func:`load` falls back to the hand-written ``replies_seed.jsonl`` beside it and says
which it used, in :attr:`Bank.source`. **Nothing else changes when the real bank lands.**

**Choosing.** Three steps, in this order, and the order is the whole design:

1. **filter by act** -- the planner has decided what the reply does, and that is not negotiable;
2. **filter by what can be said** -- a line whose ``{slot}`` this dwarf cannot fill is dropped
   before anything is scored (:mod:`dwarfsim.realize`), and a line written for the far end of
   the trust spread is dropped too, because a cruel line must never reach a friend;
3. **score what is left** -- a documented weighted tag match, minus what saying it again would
   cost (straight off the dialogue state's ring, so a dwarf does not repeat itself), plus a
   small preference for a line that uses this dwarf's own state rather than a generic one.

Step 3 is the seam. :func:`score` has the same shape as the arbitrator's
``score(observation, candidate_features)`` and is replaced wholesale by passing a ``ranker`` --
see :mod:`dwarfsim.learn.ranker`. The weighted match stays the default until a learned one is
measured better.

The only randomness in the whole pipeline is here: among lines within :data:`TIE` of the best,
one is drawn from ``world.rng``, so a seed and the same typing give the same conversation.
"""

import json
import os

from . import realize
from .speechplan import (ABOUT, ACT_SET, CONDITIONS, HEAT, INTENTS, MOODS, NEWS, SINCERITY,
                         SUSPICION, TOPICS, TRUST)

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA_DIR = os.path.join(HERE, "text", "data")

#: The merged Cursor output, preferred whenever it is there.
BANK_PATH = os.path.join(DATA_DIR, "replies.jsonl")
#: The hand-written stand-in, so the pipeline is testable with no generated data at all.
SEED_PATH = os.path.join(DATA_DIR, "replies_seed.jsonl")

#: What each tag agreeing is worth. ``trust`` dominates on purpose: it is the axis the bank was
#: written along, and the one a reader can hear in a line without being told.
WEIGHTS = {
    "trust": 4.0,
    "intent": 3.0,
    "about": 2.5,
    "news": 2.5,
    "mood": 2.0,
    "sincerity": 1.5,
    "condition": 1.5,
    "suspicion": 1.5,
    "topic": 1.0,
    "heat": 1.0,
}

#: What saying the same line again costs. Large enough to lose to an imperfect tag match, which
#: is the point: a dwarf that says one thing perfectly, four times, is worse than one that
#: reaches for a near-enough second line.
REPEAT_COST = 6.0

#: A small pull toward lines that name this dwarf's own state instead of saying nothing in
#: particular, capped so a three-slot line cannot beat a right one.
SLOT_BONUS, SLOT_BONUS_CAP = 0.25, 0.75

#: Lines this far apart in score are a coin toss, and ``world.rng`` tosses it.
TIE = 0.35

#: How far down the trust spread a line may be reached for. One step: a ``warm`` line will do at
#: ``close`` or ``neutral`` and never at ``hostile``.
TRUST_REACH = 1
_TRUST_AT = {name: i for i, name in enumerate(TRUST)}

#: When an act has nothing at all, these are tried in order rather than saying nothing.
FALLBACK_ACTS = ("SMALLTALK_BACK", "DEFLECT", "AGREE")

#: What a SILENCE line is, when the bank has none.
SILENT_TEXT = "..."

MIN_WORDS, MAX_WORDS = 1, 25

_ENUMS = {
    "intent": INTENTS, "about": ABOUT, "news": NEWS, "topic": TOPICS,
    "sincerity": SINCERITY, "heat": HEAT,
    "mood": MOODS, "trust": TRUST, "condition": CONDITIONS, "suspicion": SUSPICION,
}
HEARS_FIELDS = ("intent", "about", "news", "topic", "sincerity", "heat")
STATE_FIELDS = ("mood", "trust", "condition", "suspicion")


class BadLine(ValueError):
    """A record the bank will not hold. The merge tool raises the same thing."""


class Line:
    """One reply, with the tags it was written for."""

    __slots__ = ("hears", "state", "act", "slots", "text", "lid", "index")

    def __init__(self, hears, state, act, slots, text, index=0):
        self.hears = hears
        self.state = state
        self.act = act
        self.slots = tuple(slots)
        self.text = text
        self.index = index
        self.lid = line_id(act, text)

    @property
    def trust(self):
        return self.state["trust"]

    def hears_key(self):
        return tuple(self.hears[f] for f in HEARS_FIELDS)

    def state_key(self):
        return tuple(self.state[f] for f in STATE_FIELDS)

    def snapshot(self):
        return {"act": self.act, "hears": dict(self.hears), "state": dict(self.state),
                "slots": list(self.slots), "text": self.text, "id": self.lid}

    def __repr__(self):
        return "<Line %s/%s %r>" % (self.act, self.state["trust"], self.text[:40])


def line_id(act, text):
    """A stable id for one line: the same in every process and across a reload of the file."""
    from . import regard
    return "%s:%s" % (act, regard.text_hash(text) or "empty")


def parse_line(obj, index=0):
    """One JSON object into a :class:`Line`, or :class:`BadLine`.

    The same rules the merge tool applies, here as well as there, so a bank file edited by hand
    cannot get past the loader either.
    """
    if not isinstance(obj, dict):
        raise BadLine("not a JSON object")
    act = str(obj.get("act") or "").strip().upper()
    if act not in ACT_SET:
        raise BadLine("act %r is not one of the 43" % obj.get("act"))
    text = obj.get("text")
    if not isinstance(text, str) or not text.strip():
        raise BadLine("no usable text")
    text = " ".join(text.split())
    n = len(text.split())
    if not (MIN_WORDS <= n <= MAX_WORDS):
        raise BadLine("text is %d words, outside %d-%d" % (n, MIN_WORDS, MAX_WORDS))

    hears, state = {}, {}
    for field in HEARS_FIELDS:
        hears[field] = _enum(obj.get("hears"), field)
    for field in STATE_FIELDS:
        state[field] = _enum(obj.get("state"), field)

    declared = obj.get("slots") or []
    if isinstance(declared, str):
        declared = [declared]
    if not isinstance(declared, list) or any(not isinstance(s, str) for s in declared):
        raise BadLine("`slots` is not a list of strings")
    declared = [s.strip().strip("{}") for s in declared if s and s.strip()]
    for slot in declared:
        if slot not in realize.SLOT_NAMES:
            raise BadLine("unknown slot {%s}" % slot)
    used = realize.slots_in(text)
    for slot in used:
        if slot not in realize.SLOT_NAMES:
            raise BadLine("unknown slot {%s} in the text" % slot)
        if slot not in declared:
            raise BadLine("{%s} is used in the text but not declared in `slots`" % slot)
    for slot in declared:
        if slot not in used:
            raise BadLine("`slots` declares {%s}, which the text never uses" % slot)
    return Line(hears, state, act, used, text, index)


def _enum(block, field):
    if not isinstance(block, dict):
        raise BadLine("missing `%s` block" % ("hears" if field in HEARS_FIELDS else "state"))
    raw = block.get(field)
    if not isinstance(raw, str):
        raise BadLine("`%s` is missing or not a string" % field)
    allowed = _ENUMS[field]
    value = raw.strip()
    value = value.upper() if allowed and allowed[0].isupper() else value.lower()
    if value not in allowed:
        raise BadLine("`%s` is not in its enum (%r)" % (field, raw))
    return value


# ---------------------------------------------------------------------------
# The bank
# ---------------------------------------------------------------------------


class Bank:
    """Every line anybody wrote, indexed by act and by the two tag keys."""

    __slots__ = ("lines", "by_act", "by_key", "source", "rejected")

    def __init__(self, lines=(), source="<memory>", rejected=()):
        self.lines = list(lines)
        self.source = source
        self.rejected = list(rejected)
        self.by_act = {}
        self.by_key = {}
        for line in self.lines:
            self.by_act.setdefault(line.act, []).append(line)
            self.by_key.setdefault((line.act, line.hears_key(), line.state_key()),
                                   []).append(line)

    def __len__(self):
        return len(self.lines)

    def for_act(self, act):
        return self.by_act.get(act, ())

    def acts(self):
        return sorted(self.by_act)

    def coverage(self):
        """``{act: how many lines}`` -- what the tests and the docs count."""
        return {act: len(rows) for act, rows in sorted(self.by_act.items())}

    def missing_acts(self, least=1):
        return sorted(a for a in ACT_SET if len(self.by_act.get(a, ())) < least)

    # -- loading -----------------------------------------------------------

    @classmethod
    def read(cls, path, strict=False):
        """Every good line in one file. Bad ones are collected, not raised, unless ``strict``."""
        lines, bad = [], []
        with open(path, "r", encoding="utf-8-sig") as fh:
            for i, raw in enumerate(fh, 1):
                raw = raw.strip()
                if not raw or raw.startswith("//"):
                    continue
                try:
                    obj = json.loads(raw)
                except json.JSONDecodeError as exc:
                    bad.append((i, "not JSON: %s" % exc.msg))
                    continue
                try:
                    lines.append(parse_line(obj, i))
                except BadLine as exc:
                    if strict:
                        raise BadLine("%s:%d %s" % (os.path.basename(path), i, exc))
                    bad.append((i, str(exc)))
        return cls(lines, os.path.basename(path), bad)

    @classmethod
    def from_records(cls, records, source="<memory>"):
        return cls([parse_line(r, i) for i, r in enumerate(records, 1)], source)


_DEFAULT = None


def load(path=None, refresh=False):
    """The bank, preferring the merged file and falling back to the seed. Cached.

    ``Bank.source`` says which file was read, which is the one thing the caller has to be able
    to see: a run against the seed bank and a run against 6,000 generated lines are the same
    code and very different conversations.
    """
    global _DEFAULT
    if path is not None:
        return Bank.read(path)
    if _DEFAULT is not None and not refresh:
        return _DEFAULT
    for candidate in (BANK_PATH, SEED_PATH):
        if os.path.exists(candidate):
            _DEFAULT = Bank.read(candidate)
            return _DEFAULT
    _DEFAULT = Bank((), source="<empty>")
    return _DEFAULT


# ---------------------------------------------------------------------------
# Scoring and choosing
# ---------------------------------------------------------------------------


def tag_match(line, construction):
    """The weighted agreement between one line's tags and the construction's, 0..sum(WEIGHTS)."""
    total = 0.0
    for field in HEARS_FIELDS:
        if line.hears[field] == construction.hears.get(field):
            total += WEIGHTS[field]
    for field in STATE_FIELDS:
        if line.state[field] == construction.state.get(field):
            total += WEIGHTS[field]
    return total


def score(line, construction, scene, state=None, tick=0):
    """``(score, terms)`` for one candidate line. The seam a learned ranker replaces.

    Three terms, and they are all that is here on purpose -- each one is a sentence a person can
    argue with:

    * ``match``  -- the weighted tag agreement above;
    * ``repeat`` -- what it costs to say this again, off the dialogue state's own ring, so the
      curve that quietens a repeated compliment is the curve that quietens a repeated sentence;
    * ``slots``  -- a small pull toward a line that names something real about this dwarf.
    """
    match = tag_match(line, construction)
    freshness = 1.0 if state is None else state.line_freshness(tick, line.act, line.lid)
    repeat = REPEAT_COST * (1.0 - freshness)
    slots = min(SLOT_BONUS_CAP, SLOT_BONUS * len(line.slots))
    total = match - repeat + slots
    return total, (("match", round(match, 3)), ("repeat", round(-repeat, 3)),
                   ("slots", round(slots, 3)))


def candidates(bank, construction, scene):
    """The lines that could be said at all: right act, fillable slots, near enough on trust.

    Returns ``(lines, notes)``. The filters relax in a fixed order rather than giving up --
    first the trust reach, then the act -- and every relaxation is named in the notes, because
    a dwarf reaching outside its trust band for a line is exactly the kind of thing that should
    be visible in ``/why`` rather than discovered in a transcript.
    """
    notes = []
    rows = [ln for ln in bank.for_act(construction.act) if scene.can_fill(ln.slots)]
    want = _TRUST_AT.get(construction.state.get("trust"), 2)
    near = [ln for ln in rows if abs(_TRUST_AT[ln.trust] - want) <= TRUST_REACH]
    if near:
        return near, notes
    if rows:
        notes.append("no line for %s at %s: reached across the trust spread"
                     % (construction.act, construction.state.get("trust")))
        return rows, notes
    for act in FALLBACK_ACTS:
        if act == construction.act:
            continue
        rows = [ln for ln in bank.for_act(act) if scene.can_fill(ln.slots)]
        if rows:
            notes.append("the bank has nothing sayable for %s: fell back to %s"
                         % (construction.act, act))
            return rows, notes
    notes.append("the bank has nothing for %s at all" % construction.act)
    return [], notes


def choose(construction, dwarf=None, speaker=None, world=None, bank=None, state=None,
           rng=None, ranker=None):
    """Pick the line. Returns a dict, or one with ``text`` ``None`` when nothing can be said.

    ================  =========================================================
    ``text``          the line with its slots filled, or ``None``
    ``line``          the :class:`Line` it came from, or ``None``
    ``act``           the act, echoed back
    ``slots``         ``{slot: phrase}`` for the slots this line actually used
    ``score``         what it scored
    ``terms``         the terms behind that score, the arbitrator's shape
    ``runners``       the next two candidates and their scores
    ``source``        which bank file it came out of
    ``notes``         anything the filters had to relax
    ================  =========================================================
    """
    scene = construction.scene
    world = world if world is not None else scene.world
    state = state if state is not None else scene.state
    bank = bank if bank is not None else load()
    tick = scene.tick
    out = {"text": None, "line": None, "act": construction.act, "slots": {}, "score": 0.0,
           "terms": (), "runners": [], "source": bank.source, "notes": []}

    rows, notes = candidates(bank, construction, scene)
    out["notes"].extend(notes)
    if not rows:
        if construction.act == "SILENCE":
            out["text"] = SILENT_TEXT
        return out

    if ranker is not None:
        scored = _rank(ranker, rows, construction, scene, state, tick)
    else:
        scored = []
        for line in rows:
            total, terms = score(line, construction, scene, state, tick)
            scored.append((total, line, terms))
    scored.sort(key=lambda r: (-r[0], r[1].lid))

    best = scored[0][0]
    tied = [r for r in scored if best - r[0] <= TIE]
    rng = rng if rng is not None else (world.rng if world is not None else None)
    pick = tied[rng.randrange(len(tied))] if (rng is not None and len(tied) > 1) else tied[0]
    total, line, terms = pick

    text = scene.fill(line.text)
    if text is None:                                 # can_fill said otherwise: trust can_fill
        out["notes"].append("a slot went missing between filtering and filling")
        return out
    out.update({"text": text, "line": line, "score": round(total, 3), "terms": terms,
                "slots": scene.resolved(line.slots),
                "runners": [{"text": r[1].text, "score": round(r[0], 3)} for r in scored[1:3]]})
    return out


def _rank(ranker, rows, construction, scene, state, tick):
    """The learned chooser, when one is wired in. Same output shape as :func:`score`."""
    from .learn import ranker as ranker_mod
    obs = ranker_mod.construction_vector(construction)
    feats = [ranker_mod.line_vector(line) for line in rows]
    scores = ranker.score_all(obs, feats)
    out = []
    for line, value in zip(rows, scores):
        freshness = 1.0 if state is None else state.line_freshness(tick, line.act, line.lid)
        total = float(value) - REPEAT_COST * (1.0 - freshness)
        out.append((total, line, (("ranker", round(float(value), 3)),
                                  ("repeat", round(-REPEAT_COST * (1.0 - freshness), 3)))))
    return out
