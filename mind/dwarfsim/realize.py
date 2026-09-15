"""Filling in: every ``{slot}`` in a bank line, resolved from real state.

The bank is written blind. A line says ``"Then take my shift at the {place} tomorrow."`` and has
no idea which dwarf will say it, to whom, or whether there is a place to name. This module is the
half that knows: one resolver per slot, each reading the sim and returning a phrase or ``None``.

``None`` is the important half. **A line whose slot cannot be filled is never chosen** -- that is
the rule that keeps a dwarf from inventing a culprit it does not remember or a price it never
named, and it is enforced by :func:`can_fill`, which :mod:`dwarfsim.replybank` runs over every
candidate before it scores any of them. Nothing here ever makes something up to fill a hole.

The slots are the reply prompt's table and no others:

==============  ==========================================================================
``{speaker}``   who is talking, by name
``{dwarf}``     the dwarf's own name
``{third}``     another dwarf, named in the line or the loudest one in memory
``{place}``     a place, as a bare noun: ``forge``, ``mine``, ``tavern``, ``hall`` ...
``{item}``      a thing, as a bare noun: ``ore``, ``ale``, ``bread``, ``axe``
``{qty}``       a number of them
``{price}``     an amount of gold, as a bare number
``{injury}``    the dwarf's worst injury in words: ``my arm``, ``these ribs``
``{culprit}``   who caused it
``{goal}``      what the dwarf wants right now
``{need}``      its most pressing need: ``a meal``, ``sleep``, ``a drink``
``{memory}``    one recent thing that happened to it
``{time}``      a stretch of time: ``since the spring``, ``all week``
``{work}``      what it has been working at
==============  ==========================================================================

``{place}``, ``{item}`` and ``{price}`` are deliberately bare -- no article, no "the" -- because
the line carries the grammar around them ("at the {place}", "that {item} of yours", "{price}
gold"). A resolver that returned "the forge" would give "at the the forge" in a third of the
bank, and that was the first thing this got wrong.

Everything is derived, and derived the same way twice: a seed and the same state give the same
phrase. The only draw from ``world.rng`` in the whole pipeline is which *line* gets picked, in
:mod:`dwarfsim.replybank`, never what goes into it.
"""

import re

from .schema import PLAYER_ID

#: Every ``{slot}`` the bank may use. A line naming anything else is rejected at merge time and
#: refused here, which is two doors on the same rule.
SLOT_NAMES = ("speaker", "dwarf", "third", "place", "item", "qty", "price", "injury",
              "culprit", "goal", "need", "memory", "time", "work")

_SLOT_RE = re.compile(r"\{([a-z_]+)\}")

#: A place as a bare noun. The line supplies the article.
PLACE_WORD = {"FORGE": "forge", "MINE": "mine", "TAVERN": "tavern", "HALL": "hall",
              "FARM": "farm", "GATE": "gate"}

#: Where a topic points, for a line that names a place but was said about a subject.
TOPIC_PLACE = {"FORGE": "FORGE", "WEAPON": "FORGE", "MINE": "MINE", "TREASURE": "MINE",
               "FOOD": "FARM", "DRINK": "TAVERN", "TRADE": "TAVERN", "HOME": "HALL",
               "CLAN": "HALL", "MONSTER": "GATE"}

#: A thing as a bare noun, by topic. ``WORK`` and ``CLAN`` name no object, so a line wanting
#: ``{item}`` is simply not available when that is all there is -- which is the point.
TOPIC_ITEM = {"FORGE": "hammer", "WEAPON": "axe", "MINE": "ore", "TREASURE": "gold",
              "FOOD": "bread", "DRINK": "ale", "TRADE": "ore", "HOME": "hearth",
              "MONSTER": "axe"}

#: Which inventory slot an item word counts, for ``{qty}``.
ITEM_SLOT = {"ore": "ore", "gold": "gold", "bread": "food", "ale": "ale", "axe": "weapon"}

#: The most pressing need, in the words a dwarf would use for it.
NEED_WORD = {"hunger": "a meal", "thirst": "a drink", "fatigue": "sleep", "social": "company"}
#: Below this a need is not pressing and ``{need}`` does not resolve.
NEED_FLOOR = 0.45

#: What a goal sounds like out loud. No slot inside a slot: these are finished phrases.
GOAL_WORD = {
    "GET_RICH": "gold in my hand",
    "AVENGE": "to settle a score",
    "PROTECT": "to keep somebody standing",
    "REPAY": "to pay back what I owe",
    "BEFRIEND": "a friendly beard in this hall",
    "KEEP_PEACE": "a quiet hall for once",
}

#: What one remembered episode sounds like out loud, by its event kind.
MEMORY_WORD = {
    "INSULT": "what was said to me",
    "SLUR": "what I was called",
    "ACCUSE": "what I was accused of",
    "THREAT": "the threat I was given",
    "STEAL": "what went missing",
    "HIT": "the beating I took",
    "KILL": "the burying we did",
    "RETORT": "the words we had",
    "REFUSE_APOLOGY": "an apology that was thrown back",
    "BROKEN_PROMISE": "a promise that came to nothing",
    "PUNISH": "the fine I paid",
    "HELP": "the hand I was given",
    "GIFT": "what I was handed",
    "PROMISE_KEPT": "a promise somebody kept",
    "MONSTER_SLAIN": "the thing we put down at the gate",
}

#: A stretch of time, by how many ticks ago it was. Buckets, so it replays.
TIME_BUCKETS = ((60, "this morning"), (180, "since the shift began"), (420, "all week"),
                (900, "since the spring"))
TIME_LONGEST = "longer than I care to count"

#: What a dwarf says it has been working at, by where it is standing.
WORK_WORD = {"FORGE": "the irons", "MINE": "the seam", "FARM": "the beds",
             "TAVERN": "the barrel", "HALL": "the hall's business", "GATE": "the gate watch"}

#: What the price is when nobody named one: a dwarf's own asking price, off its greed. Derived,
#: never drawn, so the same dwarf names the same number twice.
PRICE_BASE, PRICE_GREED = 2, 9


def slots_in(text):
    """Every ``{slot}`` name in a line, in the order they occur, deduplicated."""
    out = []
    for name in _SLOT_RE.findall(text or ""):
        if name not in out:
            out.append(name)
    return tuple(out)


class Scene:
    """Everything a resolver may read: the two dwarves, the world, and what was just heard.

    Built once per reply and handed to the planner, the bank and the resolvers, so the three of
    them cannot disagree about who is talking or what about. Resolved slots are cached, which
    matters because the bank asks whether a slot can be filled once per candidate line.
    """

    __slots__ = ("world", "dwarf", "speaker", "speaker_id", "parsed", "about", "news",
                 "topic", "keys", "facts", "state", "tick", "_cache")

    def __init__(self, world, dwarf, speaker_id, parsed, about=None, news=None,
                 keys=None, state=None, facts=None):
        self.world = world
        self.dwarf = dwarf
        self.speaker_id = speaker_id
        self.speaker = world.agent(speaker_id) if world is not None else None
        self.parsed = parsed or {}
        self.about = about or self.parsed.get("about") or "NONE"
        self.news = news or self.parsed.get("news") or "NONE"
        self.topic = self.parsed.get("topic") or "NONE"
        #: The five tag buckets the bank is indexed on, written by the planner.
        self.keys = keys or {}
        #: The raw numbers behind them, for a rule that needs more than a bucket.
        self.facts = facts or {}
        self.state = state
        self.tick = world.tick if world is not None else 0
        self._cache = {}

    # -- names --------------------------------------------------------------

    @property
    def speaker_name(self):
        if self.speaker is not None:
            return self.speaker.name
        return "outsider" if self.speaker_id == PLAYER_ID else "you"

    def named_third(self):
        """The other dwarf this line is about, if one was named and it is neither of us."""
        for name in (self.parsed.get("names") or ()):
            other = self.world.agent_by_name(name) if self.world is not None else None
            if other is None or not other.alive:
                continue
            if other.id in (self.dwarf.id, self.speaker_id):
                continue
            return other
        return None

    # -- resolution ---------------------------------------------------------

    def value(self, slot):
        """The phrase for one slot, or ``None`` if this dwarf has nothing to put there."""
        if slot in self._cache:
            return self._cache[slot]
        fn = RESOLVERS.get(slot)
        got = None
        if fn is not None:
            try:
                got = fn(self)
            except (AttributeError, KeyError, TypeError, ValueError, IndexError):
                got = None
        if got is not None:
            got = str(got)
            if not got.strip():
                got = None
        self._cache[slot] = got
        return got

    def can_fill(self, slots):
        """True when every slot in the iterable resolves."""
        for slot in slots or ():
            if slot not in RESOLVERS or self.value(slot) is None:
                return False
        return True

    def fill(self, text):
        """The line with its slots filled, or ``None`` if one of them will not resolve."""
        needed = slots_in(text)
        values = {}
        for slot in needed:
            got = self.value(slot)
            if got is None:
                return None
            values[slot] = got
        if not needed:
            return text
        out = text
        for slot, got in values.items():
            out = out.replace("{%s}" % slot, got)
        return out

    def resolved(self, slots):
        """``{slot: phrase}`` for the slots that resolve, for the log and the panels."""
        return {s: self.value(s) for s in (slots or ()) if self.value(s) is not None}


# ---------------------------------------------------------------------------
# The resolvers. One function per slot, each reading state and nothing else.
# ---------------------------------------------------------------------------


def _speaker(scene):
    return scene.speaker_name


def _dwarf(scene):
    return scene.dwarf.name


def _third(scene):
    """Named in the line first; otherwise whoever this dwarf has most on its mind.

    The fallback is deliberate and it is bounded: a dwarf talking about "him" means somebody,
    and the loudest actor in its own memory is the only honest guess the sim can make. A dwarf
    with an empty book gets ``None`` and the line is not offered.
    """
    other = scene.named_third()
    if other is not None:
        return other.name
    dwarf, world = scene.dwarf, scene.world
    if world is None:
        return None
    best, best_score = None, 0.0
    forgiveness = dwarf.mind.traits["forgiveness"]
    for mem in getattr(dwarf, "memories", ()):
        if mem.actor is None or mem.actor in (dwarf.id, scene.speaker_id):
            continue
        who = world.agent(mem.actor)
        if who is None or not who.alive:
            continue
        score = mem.salience(scene.tick, forgiveness)
        if score > best_score:
            best, best_score = who, score
    return best.name if best is not None and best_score > 0.05 else None


def _place(scene):
    """Where the line points, or where the dwarf is standing."""
    want = TOPIC_PLACE.get(scene.topic)
    if want is None:
        want = scene.dwarf.place
    return PLACE_WORD.get(want)


def _ask(scene):
    """The structured ask this line is about: the one just said, or the one still open.

    A line that carries its own ask is about that ask. A line that does not may still be about
    one, and that is what a ``CALLBACK`` is: "about that axe you wanted" is filled from the
    obligation these two already have between them, which :meth:`dwarfsim.world.World.note_obligation`
    put on the dialogue state as it was made. No open ask, no slot, and the bank then cannot
    choose a line that names one -- which is the whole point of resolving slots before choosing.
    """
    ask = scene.parsed.get("ask")
    if isinstance(ask, dict):
        return ask
    return open_ask(scene)


def open_ask(scene):
    """The ask of an obligation still open between these two, freshest first, or ``None``.

    Mine first -- what I was asked to do is what I am most likely to be answering about -- then
    what I asked of them.
    """
    state = getattr(scene, "state", None)
    if state is None:
        return None
    got = state.latest_ask(mine=True) or state.latest_ask(mine=False)
    return got.get("ask") if got else None


def _item(scene):
    ask = _ask(scene)
    if ask and ask.get("item"):
        return str(ask["item"])
    return TOPIC_ITEM.get(scene.topic)


def _qty(scene):
    ask = _ask(scene)
    if ask and ask.get("quantity"):
        return str(int(ask["quantity"]))
    item = _item(scene)
    slot = ITEM_SLOT.get(item or "")
    if slot in ("ore", "gold", "food", "ale"):
        have = int(scene.dwarf.inv.get(slot, 0))
        if have > 0:
            return str(have)
    return None


def _price(scene):
    """What was offered, or what this dwarf would ask. Never a number nobody could have said."""
    ask = _ask(scene)
    if ask and ask.get("payment"):
        return str(int(ask["payment"]))
    greed = scene.dwarf.mind.traits.get("greed", 0.5)
    return str(int(PRICE_BASE + round(PRICE_GREED * greed)))


def _injury(scene):
    return scene.dwarf.condition.complaint()


def _culprit(scene):
    """Who broke this dwarf: the one who dealt the worst injury, or the loudest harm it suffered."""
    dwarf, world = scene.dwarf, scene.world
    if world is None:
        return None
    injuries = list(getattr(dwarf, "condition", ()) or ())
    if injuries:
        worst = max(injuries, key=lambda i: i.severity)
        who = world.agent(getattr(worst, "by", None)) if worst.by is not None else None
        if who is not None and who.id != dwarf.id:
            return who.name
    if dwarf.last_hit_by is not None:
        who = world.agent(dwarf.last_hit_by)
        if who is not None and who.id != dwarf.id:
            return who.name
    forgiveness = dwarf.mind.traits["forgiveness"]
    best, best_score = None, 0.0
    for mem in getattr(dwarf, "memories", ()):
        if mem.source != "SUFFERED" or mem.actor in (None, dwarf.id):
            continue
        score = mem.salience(scene.tick, forgiveness)
        if score > best_score:
            who = world.agent(mem.actor)
            if who is not None:
                best, best_score = who, score
    return best.name if best is not None and best_score > 0.05 else None


def _goal(scene):
    goals = getattr(scene.dwarf, "goals", ()) or ()
    if not goals:
        return None
    top = max(goals, key=lambda g: g.strength)
    return GOAL_WORD.get(top.kind)


def _need(scene):
    needs = scene.dwarf.mind.needs
    name = max(needs, key=lambda n: needs[n])
    if needs[name] < NEED_FLOOR:
        return None
    return NEED_WORD.get(name)


def _memory(scene):
    dwarf = scene.dwarf
    forgiveness = dwarf.mind.traits["forgiveness"]
    best, best_score = None, 0.0
    for mem in getattr(dwarf, "memories", ()):
        word = MEMORY_WORD.get(mem.kind)
        if word is None:
            continue
        score = mem.salience(scene.tick, forgiveness)
        if score > best_score:
            best, best_score = word, score
    return best if best_score > 0.05 else None


def _time(scene):
    """How long the thing being talked about has been going on.

    The oldest of: how long this conversation has run, how old the worst injury is, how old the
    loudest memory is. A dwarf with none of those has been here no time at all and the slot does
    not resolve, which keeps "all week" off the tongue of somebody who arrived this tick.
    """
    ages = []
    state = scene.state
    if state is not None and state.turns:
        ages.append(scene.tick - state.started)
    for inj in list(getattr(scene.dwarf, "condition", ()) or ()):
        ages.append(scene.tick - getattr(inj, "tick", scene.tick))
    dwarf = scene.dwarf
    forgiveness = dwarf.mind.traits["forgiveness"]
    for mem in getattr(dwarf, "memories", ()):
        if mem.salience(scene.tick, forgiveness) > 0.05:
            ages.append(scene.tick - mem.tick)
    if not ages:
        return None
    age = max(ages)
    for limit, word in TIME_BUCKETS:
        if age < limit:
            return word
    return TIME_LONGEST


def _work(scene):
    return WORK_WORD.get(scene.dwarf.place)


RESOLVERS = {
    "speaker": _speaker,
    "dwarf": _dwarf,
    "third": _third,
    "place": _place,
    "item": _item,
    "qty": _qty,
    "price": _price,
    "injury": _injury,
    "culprit": _culprit,
    "goal": _goal,
    "need": _need,
    "memory": _memory,
    "time": _time,
    "work": _work,
}

assert set(RESOLVERS) == set(SLOT_NAMES)
