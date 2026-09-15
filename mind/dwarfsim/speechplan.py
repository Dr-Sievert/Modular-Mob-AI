"""The planner: what to say, decided in rules, before anything decides how to say it.

Speech is two halves. This is the first one. It reads what was heard, what this dwarf feels about
the one who said it, what it wants, what it owes, what it is carrying and what the two of them
have already said to each other, and it produces a :class:`Construction`: **one act** out of the
43 in the reply bank's list, plus the slot values the sim can actually fill. It picks no words.
:mod:`dwarfsim.replybank` does that, from the act and the tags, and the two halves meet nowhere
else.

**The rule table is the thing a person tunes**, so it is one list, :data:`RULES`, in one place,
read top to bottom by :func:`plan`, and every decision carries its reasons out with it the way
:func:`dwarfsim.arbitrator.decide` carries its terms. A rule names any of the six *hears* tags and
any of the four *state* tags, plus an optional predicate for the things a bucket cannot say
("and it hates them"), and the most specific match at the highest priority wins. Ties go to the
earlier rule, so the table reads as a table: the general row first, the exception under it.

The worked example, which is the case the whole bank exists for -- "I've been sick lately", heard
as SMALLTALK / about SPEAKER / news MISFORTUNE::

    close     SYMPATHIZE          warm      OFFER_HELP        neutral   SMALLTALK_BACK
    cold      DEFLECT             hostile   GLOAT             hostile + real hatred   MOCK

**`about` and `news`.** The two columns the retrained classifier will supply are not there yet.
:func:`derive` fills them from the intent, the pronouns and the name pool, in one function, with
its rules written down; when the classifier starts producing them, ``parsed`` carries them and
this function is not called. That is the whole of the seam -- see :data:`DERIVED_NOTE`.
"""

from . import dialogue, regard
from .condition import ARM_BLOCK
from .realize import Scene, SLOT_NAMES
from .schema import MAX_HEALTH

# ---------------------------------------------------------------------------
# The vocabulary. Every one of these is the reply bank's own enum, exactly.
# ---------------------------------------------------------------------------

#: What a reply *does*. The bank's 43, in the reply prompt's order.
ACTS = (
    "GREET_BACK", "FAREWELL_BACK", "SMALLTALK_BACK", "ANSWER_SELF", "ANSWER_PLACE",
    "ANSWER_THIRD", "ANSWER_ITEM", "ANSWER_YESNO", "ANSWER_WHY", "DEFLECT",
    "ADMIT_IGNORANCE", "ASK_BACK", "ASK_CLARIFY", "CALLBACK", "SYMPATHIZE", "GLOAT",
    "CONGRATULATE", "BELITTLE_FORTUNE", "REASSURE", "ADVISE", "OFFER_HELP", "WARN",
    "AGREE", "DISAGREE", "CORRECT", "THANK", "APOLOGIZE", "ACCEPT", "REFUSE", "BARGAIN",
    "DEMAND_PAYMENT", "MOCK", "INSULT_BACK", "THREATEN_BACK", "DEMAND_APOLOGY",
    "ACCUSE_BACK", "SUSPECT_FLATTERY", "BOAST", "COMPLAIN", "REMINISCE", "CHANGE_SUBJECT",
    "SILENCE", "JOKE",
)
ACT_SET = frozenset(ACTS)

INTENTS = ("GREET", "FAREWELL", "SMALLTALK", "QUESTION", "REQUEST", "COMMAND", "OFFER",
           "PRAISE", "APOLOGY", "INSULT", "THREAT", "WARNING", "ACCUSE")
ABOUT = ("SPEAKER", "LISTENER", "THIRD", "WORLD", "OBJECT", "NONE")
NEWS = ("MISFORTUNE", "FORTUNE", "PLAN", "OPINION", "FACT", "SEEKING", "NONE")
TOPICS = ("FORGE", "MINE", "TREASURE", "FOOD", "DRINK", "HOME", "WEAPON", "WORK", "CLAN",
          "MONSTER", "TRADE", "NONE")
SINCERITY = ("SINCERE", "SARCASTIC", "JOKING")
HEAT = ("calm", "edged", "hot")

MOODS = ("glad", "flat", "tired", "angry", "afraid", "grieving")
TRUST = ("hostile", "cold", "neutral", "warm", "close")
CONDITIONS = ("healthy", "bruised", "hurt", "badly_hurt")
SUSPICION = ("low", "high")

#: Acts that show the dwarf caught a sarcastic line. One of these, or the sarcasm went past it.
NOTICED_SARCASM = frozenset(("SUSPECT_FLATTERY", "MOCK", "DISAGREE", "CORRECT", "JOKE",
                             "INSULT_BACK", "CALLBACK"))

#: Acts that answer a question with a fact. A question the dwarf cannot know must not get one.
ANSWERING = frozenset(("ANSWER_SELF", "ANSWER_PLACE", "ANSWER_THIRD", "ANSWER_ITEM",
                       "ANSWER_YESNO", "ANSWER_WHY", "BOAST"))

#: The acts the arbitrator's own decision maps onto, so a dwarf that refused cannot say yes.
DECIDED_ACT = {"ACCEPT": "ACCEPT", "FULFIL": "ACCEPT", "REFUSE": "REFUSE",
               "BARGAIN": "BARGAIN", "IGNORE": "SILENCE", "AVOID": "SILENCE"}

DERIVED_NOTE = ("about/news derived by rule: the classifier does not label them yet "
                "(see mind/text/CURSOR_UNDERSTANDING_PROMPT.md)")


# ---------------------------------------------------------------------------
# Buckets. The bank is indexed on these words, so they are defined once, here.
# ---------------------------------------------------------------------------


def trust_key(dwarf, speaker_id):
    """``hostile`` / ``cold`` / ``neutral`` / ``warm`` / ``close``.

    Off :func:`dwarfsim.regard.felt_trust` rather than raw trust, because how somebody is
    *spoken to* is a mood about them and not a belief: an afternoon of talk makes a room
    friendly. Hatred is read separately and outranks everything, which is why a dwarf that
    likes you but hates you -- the end of a feud between friends -- still answers as an enemy.
    """
    rel = dwarf.mind.rel(speaker_id)
    felt = regard.felt_trust(dwarf, speaker_id)
    if rel["hatred"] >= 0.35 or felt <= -0.40:
        return "hostile"
    if felt <= -0.10 or rel["hatred"] >= 0.15:
        return "cold"
    if felt >= 0.45 and rel["hatred"] < 0.10:
        return "close"
    if felt >= 0.15:
        return "warm"
    return "neutral"


def mood_key(dwarf):
    """``angry`` / ``afraid`` / ``grieving`` / ``tired`` / ``glad`` / ``flat``, in that order.

    The order is the point. A dwarf that is furious *and* cheerful sounds furious; a dwarf that
    is exhausted and mildly pleased sounds exhausted. Anger and fear change how somebody talks
    to you more than anything else does, so they come first, and flat is what is left.
    """
    e, n = dwarf.mind.emotions, dwarf.mind.needs
    if e["anger"] >= 0.40:
        return "angry"
    if e["fear"] >= 0.38:
        return "afraid"
    if e["grief"] >= 0.35:
        return "grieving"
    if n["fatigue"] >= 0.70:
        return "tired"
    if e["happiness"] >= 0.55:
        return "glad"
    return "flat"


def condition_key(dwarf):
    """``healthy`` / ``bruised`` / ``hurt`` / ``badly_hurt``, off the injuries and the blood."""
    cond = getattr(dwarf, "condition", None)
    worst = cond.worst() if cond is not None else 0.0
    share = dwarf.health / MAX_HEALTH
    if share <= 0.35 or worst >= 0.55:
        return "badly_hurt"
    if worst >= ARM_BLOCK or share <= 0.60:
        return "hurt"
    if worst > 0.0:
        return "bruised"
    return "healthy"


def suspicion_key(dwarf, speaker_id):
    """``high`` once this dwarf has started reading this mouth as working it."""
    return "high" if regard.suspicion(dwarf, speaker_id) >= regard.SUSPICION_THRESHOLD else "low"


def heat_key(parsed):
    """``calm`` / ``edged`` / ``hot`` -- how aggressive the delivery was, and nothing else."""
    aggression = float(parsed.get("aggression") or 0.0)
    if aggression >= 0.60:
        return "hot"
    if aggression >= 0.25:
        return "edged"
    return "calm"


# ---------------------------------------------------------------------------
# about / news, until the classifier labels them
# ---------------------------------------------------------------------------

#: Apostrophes are stripped before these are looked up, so ``you've`` is ``youve`` and ``I'm``
#: is ``im``. That keeps one spelling per word instead of four.
_SECOND_PERSON = frozenset((
    "you", "your", "youre", "youve", "youll", "youd", "yours", "ye", "yer", "thee", "thy"))

#: Three words are deliberately **not** in the first-person list, and each one cost something:
#:
#: * ``mine`` -- in a mining settlement it is a hole in the ground far more often than it is a
#:   possessive, and reading "how is the mine?" as a question about the speaker sent every such
#:   question to ADMIT_IGNORANCE;
#: * ``ill`` (I'll) -- indistinguishable from ill, which is in half the misfortune lines;
#: * ``id`` (I'd) and ``were`` (we're) -- the same collision, with an identifier and a past
#:   tense. All four are carried by the other markers in the same sentence when they matter.
_FIRST_PERSON = frozenset(("i", "im", "ive", "my", "me", "we", "our", "us", "weve", "ours"))

#: Words that make a statement a piece of bad news about whoever it is about.
_MISFORTUNE_WORDS = frozenset((
    "sick", "ill", "hurt", "hurts", "broken", "bleeding", "wound", "wounded", "dying",
    "died", "dead", "buried", "robbed", "stolen", "took", "lost", "losing", "broke",
    "poor", "starving", "hungry", "thirsty", "tired", "exhausted", "afraid", "scared",
    "ruined", "failed", "trouble", "cursed", "limping", "fever", "cold", "ache", "aches",
    "aching", "beaten", "sore", "widowed", "orphan", "gone", "burnt", "burned", "collapsed"))

#: And good news.
_FORTUNE_WORDS = frozenset((
    "struck", "found", "paid", "rich", "gold", "silver", "lucky", "luck", "mended",
    "healed", "betrothed", "wed", "married", "born", "won", "winning", "seam", "vein",
    "harvest", "feast", "bought", "earned", "promoted", "cleared", "finished", "fixed"))

#: Words that make a line an intention rather than a report.
_PLAN_WORDS = frozenset((
    "tomorrow", "dawn", "tonight", "later", "soon", "next", "will", "shall", "going",
    "heading", "plan", "planning", "intend", "mean", "plans", "afterwards", "week"))

#: Words that make a line a judgement rather than a fact.
_OPINION_WORDS = frozenset((
    "think", "reckon", "believe", "knows", "know", "daft", "fool", "best", "worst",
    "should", "ought", "hate", "love", "like", "dislike", "rotten", "fine", "poor",
    "better", "worse", "always", "never", "everyone", "nobody", "shame", "stupid"))


def _words(text):
    """The line as bare alphabetic words, apostrophes of either shape removed."""
    out = []
    for raw in (text or "").lower().split():
        word = "".join(ch for ch in raw if ch.isalpha())
        if word:
            out.append(word)
    return out


def derive(parsed, dwarf=None, speaker=None, world=None):
    """``(about, news, reasons)`` -- the two new columns, worked out by rule.

    This is a **stand-in for a learned head**, kept in one function on purpose so the learned
    one drops in against the same contract: when ``parsed`` already carries legal ``about`` and
    ``news`` values -- which is what a retrained classifier will give it -- the rules below are
    not consulted at all and the reasons say so.

    ``about``, in order, first hit wins:

    1. a name from the pool that is neither of the two talking -- ``THIRD``;
    2. a second-person pronoun -- ``LISTENER``, meaning the dwarf being spoken to;
    3. a first-person pronoun -- ``SPEAKER``;
    4. a hostile intent aimed at the listener -- ``LISTENER``;
    5. a topic that names a thing (treasure, food, drink, weapon, trade) -- ``OBJECT``;
    6. a topic that names the settlement (mine, forge, home, clan, monster, work) -- ``WORLD``;
    7. a question with nothing else to go on -- ``LISTENER``, because that is what asking
       somebody a question is ("How's the arm?" names nobody and means you);
    8. otherwise ``NONE``, which is what a greeting and a fragment are.

    ``news``: a question is ``SEEKING``, a greeting is ``NONE``, an ask is ``PLAN``, a threat is
    ``PLAN``, a warning is ``FACT``, praise and blame are ``OPINION``; and a plain statement is
    read off its words -- a misfortune word, then a fortune word, then a plan word, then an
    opinion word, then the valence, and ``FACT`` if none of that fires.
    """
    reasons = []
    about = str(parsed.get("about") or "").upper()
    news = str(parsed.get("news") or "").upper()
    if about in ABOUT and news in NEWS:
        reasons.append(("about/news", "labelled by the interpreter"))
        return about, news, reasons

    intent = str(parsed.get("intent") or "SMALLTALK").upper()
    text = parsed.get("text") or ""
    words = set(_words(text))
    names = [n for n in (parsed.get("names") or ())]
    mine = getattr(dwarf, "name", None)
    theirs = getattr(speaker, "name", None)
    third = [n for n in names if n not in (mine, theirs)]

    if about not in ABOUT:
        if intent in ("GREET", "FAREWELL"):
            about, why = "NONE", "a greeting is about nobody"
        elif third:
            about, why = "THIRD", "names %s, who is neither of us" % third[0]
        elif words & _SECOND_PERSON:
            about, why = "LISTENER", "second person: it is about me"
        elif words & _FIRST_PERSON:
            about, why = "SPEAKER", "first person: it is about them"
        elif intent in ("INSULT", "THREAT", "ACCUSE") \
                and parsed.get("addressed", "LISTENER") == "LISTENER":
            about, why = "LISTENER", "aimed at me"
        elif parsed.get("topic") in ("TREASURE", "FOOD", "DRINK", "WEAPON", "TRADE"):
            about, why = "OBJECT", "the topic is a thing"
        elif parsed.get("topic") in ("MINE", "FORGE", "HOME", "CLAN", "MONSTER", "WORK"):
            about, why = "WORLD", "the topic is the settlement"
        elif intent == "QUESTION":
            # "How's the arm?" names nobody and means you. A question with nothing else to go
            # on is about the one being asked -- that is what asking somebody a question is.
            about, why = "LISTENER", "a question with no other subject is about the one asked"
        else:
            about, why = "NONE", "nothing in particular"
        reasons.append(("about=" + about, why))

    if news not in NEWS:
        valence = float(parsed.get("valence") or 0.0)
        if intent == "QUESTION":
            news, why = "SEEKING", "a question wants to be told something"
        elif intent in ("GREET", "FAREWELL", "APOLOGY"):
            news, why = "NONE", "neither news nor a judgement"
        elif intent in ("REQUEST", "COMMAND", "OFFER", "THREAT"):
            news, why = "PLAN", "it says what is to happen"
        elif intent == "WARNING":
            news, why = "FACT", "a warning states how things are"
        elif intent in ("PRAISE", "INSULT", "ACCUSE"):
            news, why = "OPINION", "a judgement about somebody"
        elif words & _MISFORTUNE_WORDS:
            news, why = "MISFORTUNE", "a misfortune word"
        elif words & _FORTUNE_WORDS:
            news, why = "FORTUNE", "a fortune word"
        elif words & _PLAN_WORDS:
            news, why = "PLAN", "it points at something not yet done"
        elif words & _OPINION_WORDS:
            news, why = "OPINION", "a judgement word"
        elif valence <= -0.30:
            news, why = "MISFORTUNE", "sour, on valence alone"
        elif valence >= 0.35:
            news, why = "FORTUNE", "cheerful, on valence alone"
        else:
            news, why = "FACT", "a plain statement"
        reasons.append(("news=" + news, why))
    return about, news, reasons


# ---------------------------------------------------------------------------
# The rule table
# ---------------------------------------------------------------------------


class Rule:
    """One row: what it matches, what act it asks for, and why, in words."""

    __slots__ = ("name", "hears", "state", "when", "act", "why", "priority")

    def __init__(self, name, act, why, priority=10, when=None, **tags):
        self.name = name
        self.act = act
        self.why = why
        self.priority = priority
        self.when = when
        self.hears = {k: _tuple(v) for k, v in tags.items() if k in _HEARS_KEYS}
        self.state = {k: _tuple(v) for k, v in tags.items() if k in _STATE_KEYS}
        unknown = set(tags) - _HEARS_KEYS - _STATE_KEYS
        if unknown:
            raise ValueError("rule %s names unknown tags %s" % (name, sorted(unknown)))
        if act not in ACT_SET:
            raise ValueError("rule %s wants act %r, which is not one of the 43" % (name, act))

    def specificity(self):
        return len(self.hears) + len(self.state) + (2 if self.when is not None else 0)

    def matches(self, scene):
        keys = scene.keys
        for field, wanted in self.hears.items():
            if keys.get(field) not in wanted:
                return False
        for field, wanted in self.state.items():
            if keys.get(field) not in wanted:
                return False
        if self.when is not None and not self.when(scene):
            return False
        return True

    def __repr__(self):
        return "<Rule %s -> %s>" % (self.name, self.act)


_HEARS_KEYS = frozenset(("intent", "about", "news", "topic", "sincerity", "heat"))
_STATE_KEYS = frozenset(("mood", "trust", "condition", "suspicion"))


def _tuple(v):
    return (v,) if isinstance(v, str) else tuple(v)


# -- the predicates a bucket cannot express ---------------------------------


def _hates(scene):
    return scene.facts.get("hatred", 0.0) >= 0.55


def _repeated(scene):
    return scene.facts.get("repeats", 0) >= 1


def _repeated_twice(scene):
    return scene.facts.get("repeats", 0) >= 2


def _owes_answer(scene):
    return scene.facts.get("owed", 0) >= 1


def _ask_still_pending(scene):
    """They have an ask on the table that I have not answered, and here they are asking again.

    Only an ask made *of* this dwarf counts. One this dwarf made of them is their business to
    call back on, not its own.
    """
    got = scene.facts.get("open_ask")
    return bool(got) and got.get("mine") and got.get("status") == "PENDING"


def _ask_taken_on(scene):
    """I took their ask on and it is not done yet: "about that axe you wanted"."""
    got = scene.facts.get("open_ask")
    return bool(got) and got.get("mine") and got.get("status") == "ACCEPTED"


def _cannot_know(scene):
    return not knows_answer(scene)


def _brave(scene):
    return scene.dwarf.mind.traits["bravery"] >= 0.60


def _proud(scene):
    return scene.dwarf.mind.traits["pride"] >= 0.60


def _hot_tempered(scene):
    return scene.dwarf.mind.traits["temper"] >= 0.60


def _guilty(scene):
    """This dwarf really did do something to the one accusing it, and remembers doing it."""
    dwarf = scene.dwarf
    forgiveness = dwarf.mind.traits["forgiveness"]
    for mem in getattr(dwarf, "memories", ()):
        if mem.actor == dwarf.id and mem.target == scene.speaker_id \
                and mem.salience(scene.tick, forgiveness) > 0.10:
            return True
    return False


def _subject_already_raised(scene):
    state = scene.state
    if state is None:
        return False
    return state.said_about(scene.about, scene.parsed.get("names") or ()) is not None


def _long_conversation(scene):
    state = scene.state
    return state is not None and len(state.turns) >= 4


def knows_answer(scene):
    """Whether this dwarf could honestly answer the question it was just asked.

    The rule the bank's own instructions state -- *never invent a fact the game cannot fill from
    a slot* -- enforced at the planning end, where it belongs. A dwarf knows about itself and
    about the settlement it lives in. It does not know about a stranger it has never met, about
    a thing nobody named, or about nothing in particular.
    """
    about = scene.about
    if about in ("LISTENER", "WORLD"):
        return True
    if about == "OBJECT":
        return scene.value("item") is not None
    if about == "THIRD":
        name = scene.value("third")
        if name is None:
            return False
        other = scene.world.agent_by_name(name) if scene.world is not None else None
        if other is None:
            return False
        rel = scene.dwarf.mind.rels.get(other.id)
        known = rel is not None and (abs(rel["trust"]) > 0.02 or rel["respect"] != 0.0
                                     or rel["hatred"] > 0.0)
        if known:
            return True
        return any(m.actor == other.id or m.target == other.id
                   for m in getattr(scene.dwarf, "memories", ()))
    if about == "SPEAKER":
        # What I know about you is what you have done to me and what I have been told.
        return any(m.actor == scene.speaker_id or m.target == scene.speaker_id
                   for m in getattr(scene.dwarf, "memories", ()))
    return False


_YESNO_OPENERS = ("do", "does", "did", "is", "are", "was", "were", "have", "has", "had",
                  "will", "would", "can", "could", "should", "shall", "am", "any")


def _asks_yesno(scene):
    words = _words(scene.parsed.get("text") or "")
    return bool(words) and words[0] in _YESNO_OPENERS


def _asks_why(scene):
    """``why``, and not ``how``. "How is the mine?" wants the state of the mine, not a reason,
    and answering it with "Because ..." was the first thing this got wrong."""
    words = _words(scene.parsed.get("text") or "")
    return bool(words) and words[0] in ("why", "wherefore")


# -- and the table itself ---------------------------------------------------
#
# Read it as a table: priority first, then the general row, then the exceptions under it. The
# highest priority that matches wins; among equals, the most constraints; among those, the one
# written first. Nothing else decides an act, and nothing outside this list is consulted.

RULES = (
    # -- 31: the obligation book, which beats the ring below it because it is a record and not
    # a count. The ring says "you have said that before"; these two say what was actually asked
    # and where it stands, so the slots are filled from the ask itself and the line can name it.
    Rule("callback.ask_open", "CALLBACK", "that ask is still on the table and unanswered",
         priority=31, intent=("REQUEST", "COMMAND"), when=_ask_still_pending),
    Rule("callback.ask_taken_on", "CALLBACK", "I took that on already and it is not done yet",
         priority=31, intent=("REQUEST", "COMMAND", "QUESTION"), when=_ask_taken_on),

    # -- 30: the conversation's own memory. These beat every other consideration, because a
    # dwarf that answers "I've told you" with fresh small talk is a dwarf with no memory. -----
    Rule("callback.same_question", "CALLBACK", "you have asked me that already",
         priority=30, intent="QUESTION", when=_repeated),
    Rule("callback.same_ask", "CALLBACK", "you asked me that before and the answer stands",
         priority=30, intent=("REQUEST", "COMMAND"), when=_repeated),
    Rule("callback.pasted", "CALLBACK", "that is the third time you have said it",
         priority=30, when=_repeated_twice),

    # -- 25: sarcasm and flattery, which must be seen to be caught ---------------------------
    Rule("sarcasm.hostile", "MOCK", "said sweetly, meant otherwise, and we are not friends",
         priority=25, sincerity="SARCASTIC", trust=("hostile", "cold")),
    # Only a real friend gets the benefit of the doubt. At `warm` the line below catches it,
    # which is the difference between somebody you drink with and somebody you nod to.
    Rule("sarcasm.friendly", "JOKE", "sarcasm from a friend is a joke and is taken as one",
         priority=25, sincerity="SARCASTIC", trust="close"),
    Rule("sarcasm.noted", "SUSPECT_FLATTERY", "the words and the meaning did not match",
         priority=25, sincerity="SARCASTIC"),
    Rule("flattery.seen_through", "SUSPECT_FLATTERY", "I have stopped hearing that as praise",
         priority=25, intent="PRAISE", suspicion="high"),
    Rule("joking.taken", "JOKE", "a joke between two who can take one",
         priority=24, sincerity="JOKING", trust=("warm", "close")),

    # -- 20: a question this dwarf cannot honestly answer ------------------------------------
    Rule("question.unknowable", "ADMIT_IGNORANCE", "I have no way of knowing that",
         priority=20, intent="QUESTION", when=_cannot_know),

    # -- 13: the cruel versions. Hatred, not coldness, is what buys these -------------------
    Rule("misfortune.cruel", "MOCK", "their bad luck, and I hate them",
         priority=13, about="SPEAKER", news="MISFORTUNE", trust="hostile", when=_hates),
    Rule("fortune.cruel", "MOCK", "their good luck, and I hate them",
         priority=13, about="SPEAKER", news="FORTUNE", trust="hostile", when=_hates),
    Rule("insult.cruel", "THREATEN_BACK", "an insult from somebody I hate, and I am hot",
         priority=13, intent="INSULT", trust="hostile", heat="hot", when=_hot_tempered),

    # -- 12: the body and the strong moods, and the two question shapes ----------------------
    Rule("hurt.complains", "COMPLAIN", "I am in no state for this",
         priority=12, condition="badly_hurt", intent=("SMALLTALK", "GREET", "PRAISE")),
    Rule("grief.remembers", "REMINISCE", "grieving, to somebody I trust",
         priority=12, mood="grieving", trust=("warm", "close")),
    Rule("grief.silent", "SILENCE", "grieving, and you are the last mouth I want to hear",
         priority=12, mood="grieving", trust=("hostile", "cold")),
    Rule("afraid.silent", "SILENCE", "frightened, and this is being shouted at me",
         priority=12, mood="afraid", heat="hot"),
    Rule("tired.deflects", "DEFLECT", "too tired for you in particular",
         priority=12, mood="tired", trust=("hostile", "cold")),
    Rule("question.yesno", "ANSWER_YESNO", "it is a yes or a no",
         priority=12, intent="QUESTION", when=_asks_yesno),
    Rule("question.why", "ANSWER_WHY", "it asks why, and I have a reason",
         priority=12, intent="QUESTION", when=_asks_why),
    Rule("question.muddled", "ASK_CLARIFY", "my head is not clear enough to follow that",
         priority=12, intent="QUESTION", condition="badly_hurt"),
    Rule("subject.worn_out", "CHANGE_SUBJECT", "we have been round this once already",
         priority=12, trust=("cold", "neutral"), when=_subject_already_raised),

    # -- 11: pride, courage and the long conversation ---------------------------------------
    Rule("self.boasts", "BOAST", "asked about my own work, and I am proud of it",
         priority=11, intent="QUESTION", about="LISTENER", topic=("WORK", "FORGE", "MINE"),
         when=_proud),
    Rule("warning.reassures", "REASSURE", "a warning, and I am not the frightened sort",
         priority=11, intent="WARNING", trust=("warm", "close"), when=_brave),
    Rule("threat.answered", "THREATEN_BACK", "threatened, and I do not scare",
         priority=11, intent="THREAT", when=_brave),
    Rule("accuse.guilty", "APOLOGIZE", "they are right, and I remember doing it",
         priority=11, intent="ACCUSE", when=_guilty),
    Rule("clan.remembers", "REMINISCE", "we have been talking a while, and it is the clan",
         priority=11, topic="CLAN", trust=("warm", "close"), when=_long_conversation),

    # -- 10: the about/news table. The worked example, and every case beside it --------------
    # a misfortune of theirs
    Rule("misfortune.close", "SYMPATHIZE", "their bad news, and I am close to them",
         about="SPEAKER", news="MISFORTUNE", trust="close"),
    Rule("misfortune.warm", "OFFER_HELP", "their bad news, and I would do something about it",
         about="SPEAKER", news="MISFORTUNE", trust="warm"),
    Rule("misfortune.neutral", "SMALLTALK_BACK", "their bad news, and they are nothing to me",
         about="SPEAKER", news="MISFORTUNE", trust="neutral"),
    Rule("misfortune.cold", "DEFLECT", "their bad news, and I would rather not",
         about="SPEAKER", news="MISFORTUNE", trust="cold"),
    Rule("misfortune.hostile", "GLOAT", "their bad news, and good",
         about="SPEAKER", news="MISFORTUNE", trust="hostile"),
    # a fortune or a plan of theirs
    Rule("fortune.close", "CONGRATULATE", "their good news, and I am glad of it",
         about="SPEAKER", news="FORTUNE", trust=("close", "warm")),
    Rule("fortune.neutral", "SMALLTALK_BACK", "their good news, politely",
         about="SPEAKER", news="FORTUNE", trust="neutral"),
    Rule("fortune.cold", "DEFLECT", "their good news, and I have heard enough of it",
         about="SPEAKER", news="FORTUNE", trust="cold"),
    Rule("fortune.hostile", "BELITTLE_FORTUNE", "their good news, which I will not have",
         about="SPEAKER", news="FORTUNE", trust="hostile"),
    Rule("plan.close", "ADVISE", "they are going to do something, and I have a word on it",
         about="SPEAKER", news="PLAN", trust=("close", "warm")),
    Rule("plan.neutral", "SMALLTALK_BACK", "their plans, which are their own",
         about="SPEAKER", news="PLAN", trust="neutral"),
    Rule("plan.cold", "DEFLECT", "their plans, and none of mine",
         about="SPEAKER", news="PLAN", trust=("cold", "hostile")),
    Rule("speaker.opinion", "AGREE", "their judgement, and I am inclined to take it",
         about="SPEAKER", news="OPINION", trust=("close", "warm")),
    Rule("speaker.fact", "SMALLTALK_BACK", "they said a thing about themselves",
         about="SPEAKER", news=("FACT", "NONE")),
    # something said about me
    Rule("me.misfortune.warm", "AGREE", "they noticed, and they are right",
         about="LISTENER", news="MISFORTUNE", trust=("close", "warm")),
    Rule("me.misfortune.neutral", "COMPLAIN", "they noticed, and I have plenty to say about it",
         about="LISTENER", news="MISFORTUNE", trust="neutral"),
    Rule("me.misfortune.cold", "DEFLECT", "my business, not yours",
         about="LISTENER", news="MISFORTUNE", trust="cold"),
    Rule("me.misfortune.hostile", "INSULT_BACK", "you are enjoying this",
         about="LISTENER", news="MISFORTUNE", trust="hostile"),
    Rule("me.fortune.warm", "THANK", "a kind thing said about me",
         about="LISTENER", news="FORTUNE", trust=("close", "warm", "neutral")),
    Rule("me.fortune.cold", "SUSPECT_FLATTERY", "a kind thing, from you, about me",
         about="LISTENER", news="FORTUNE", trust=("cold", "hostile")),
    Rule("me.opinion.warm", "AGREE", "a judgement about me from somebody I trust",
         about="LISTENER", news="OPINION", trust=("close", "warm")),
    Rule("me.opinion.cold", "DISAGREE", "a judgement about me, and it is wrong",
         about="LISTENER", news="OPINION", trust=("cold", "neutral")),
    Rule("me.opinion.hostile", "INSULT_BACK", "a judgement about me, from you",
         about="LISTENER", news="OPINION", trust="hostile"),
    Rule("me.fact", "CORRECT", "a plain statement about me, and it wants correcting",
         about="LISTENER", news="FACT"),
    # somebody else
    Rule("third.misfortune", "ANSWER_THIRD", "news about a third, and I know something of it",
         about="THIRD", news=("MISFORTUNE", "FORTUNE", "FACT")),
    Rule("third.opinion.warm", "AGREE", "gossip from somebody I believe",
         about="THIRD", news="OPINION", trust=("close", "warm")),
    Rule("third.opinion.cold", "DISAGREE", "gossip, from you",
         about="THIRD", news="OPINION", trust=("cold", "neutral")),
    Rule("third.opinion.hostile", "MOCK", "gossip, from you, about them",
         about="THIRD", news="OPINION", trust="hostile"),
    Rule("third.plan", "ANSWER_THIRD", "what somebody else means to do",
         about="THIRD", news="PLAN"),
    # the settlement and the things in it
    Rule("world.misfortune", "WARN", "something is wrong out there",
         about="WORLD", news="MISFORTUNE"),
    Rule("world.opinion.warm", "AGREE", "we see the place the same way",
         about="WORLD", news="OPINION", trust=("close", "warm", "neutral")),
    Rule("world.opinion.cold", "DISAGREE", "you do not know this place as I do",
         about="WORLD", news="OPINION", trust=("cold", "hostile")),
    Rule("world.fact", "SMALLTALK_BACK", "a remark about the place",
         about="WORLD", news=("FACT", "FORTUNE", "PLAN", "NONE")),
    Rule("object.any", "SMALLTALK_BACK", "a remark about a thing",
         about="OBJECT", news=("FACT", "OPINION", "FORTUNE", "MISFORTUNE", "PLAN", "NONE")),

    # -- 9: questions, by what they are about ------------------------------------------------
    Rule("question.hostile", "DEFLECT", "ask somebody else",
         priority=9, intent="QUESTION", trust="hostile"),
    Rule("question.me", "ANSWER_SELF", "asked about myself",
         priority=9, intent="QUESTION", about="LISTENER"),
    Rule("question.place", "ANSWER_PLACE", "asked about the settlement",
         priority=9, intent="QUESTION", about="WORLD"),
    Rule("question.third", "ANSWER_THIRD", "asked about somebody else",
         priority=9, intent="QUESTION", about="THIRD"),
    Rule("question.item", "ANSWER_ITEM", "asked about a thing",
         priority=9, intent="QUESTION", about="OBJECT"),
    Rule("question.them", "ASK_BACK", "asked about themselves, which they would know better",
         priority=9, intent="QUESTION", about="SPEAKER"),

    # -- 8: the plain intent table, for everything the two columns did not settle ------------
    Rule("greet.angry", "DEFLECT", "greeted, and in no mood for it",
         priority=9, intent="GREET", mood="angry"),
    Rule("greet.afraid", "DEFLECT", "greeted, and listening for something else",
         priority=9, intent="GREET", mood="afraid"),
    Rule("farewell.angry", "DEFLECT", "good, go",
         priority=9, intent="FAREWELL", mood="angry"),
    Rule("greet.hostile", "SILENCE", "I have nothing to say to you",
         priority=8, intent="GREET", trust="hostile"),
    Rule("greet.cold", "DEFLECT", "what is it you want",
         priority=8, intent="GREET", trust="cold"),
    Rule("greet.any", "GREET_BACK", "a greeting gets a greeting",
         priority=8, intent="GREET"),
    Rule("farewell.hostile", "SILENCE", "go, then",
         priority=8, intent="FAREWELL", trust="hostile"),
    Rule("farewell.any", "FAREWELL_BACK", "a farewell gets a farewell",
         priority=8, intent="FAREWELL"),
    Rule("ask.close", "ACCEPT", "asked by somebody I would do it for",
         priority=8, intent=("REQUEST", "COMMAND", "OFFER"), trust=("close", "warm")),
    Rule("ask.neutral", "BARGAIN", "asked by somebody I would want something from",
         priority=8, intent=("REQUEST", "COMMAND", "OFFER"), trust="neutral"),
    Rule("ask.cold", "DEMAND_PAYMENT", "asked by somebody who can pay for it",
         priority=8, intent=("REQUEST", "COMMAND", "OFFER"), trust="cold"),
    Rule("ask.hostile", "REFUSE", "asked by you, of all people",
         priority=8, intent=("REQUEST", "COMMAND", "OFFER"), trust="hostile"),
    Rule("praise.suspect", "SUSPECT_FLATTERY", "praise, from you, and I wonder what for",
         priority=8, intent="PRAISE", trust=("cold", "hostile")),
    Rule("praise.any", "THANK", "praise, and I will take it",
         priority=8, intent="PRAISE"),
    Rule("apology.warm", "THANK", "an apology, and it is enough",
         priority=8, intent="APOLOGY", trust=("close", "warm")),
    Rule("apology.neutral", "AGREE", "an apology, and we will leave it there",
         priority=8, intent="APOLOGY", trust="neutral"),
    Rule("apology.cold", "DEFLECT", "an apology, and words are cheap",
         priority=8, intent="APOLOGY", trust="cold"),
    Rule("apology.hostile", "REFUSE", "an apology, and it is not nearly enough",
         priority=8, intent="APOLOGY", trust="hostile"),
    Rule("insult.friend", "JOKE", "an insult from a friend is not an insult",
         priority=8, intent="INSULT", trust=("close", "warm")),
    Rule("insult.angry", "DEMAND_APOLOGY", "an insult, and I am already angry",
         priority=8, intent="INSULT", mood="angry"),
    Rule("insult.afraid", "SILENCE", "an insult, and I want no part of this",
         priority=8, intent="INSULT", mood="afraid"),
    Rule("insult.hostile", "INSULT_BACK", "an insult, from you",
         priority=8, intent="INSULT", trust="hostile"),
    Rule("insult.any", "MOCK", "an insult, and I will not take it quietly",
         priority=8, intent="INSULT"),
    Rule("threat.afraid", "SILENCE", "threatened, and frightened",
         priority=8, intent="THREAT", mood="afraid"),
    Rule("threat.hostile", "THREATEN_BACK", "threatened by somebody I already hate",
         priority=8, intent="THREAT", trust="hostile"),
    Rule("threat.any", "WARN", "threatened, and it is worth saying where that leads",
         priority=8, intent="THREAT"),
    Rule("accuse.warm", "CORRECT", "blamed by a friend, who has it wrong",
         priority=8, intent="ACCUSE", trust=("close", "warm")),
    Rule("accuse.hostile", "ACCUSE_BACK", "blamed, by you",
         priority=8, intent="ACCUSE", trust="hostile"),
    Rule("accuse.any", "DISAGREE", "blamed, and it was not me",
         priority=8, intent="ACCUSE"),
    Rule("warning.warm", "THANK", "a warning, and I am glad of it",
         priority=8, intent="WARNING", trust=("close", "warm")),
    Rule("warning.hostile", "DEFLECT", "a warning, from you",
         priority=8, intent="WARNING", trust="hostile"),
    Rule("warning.any", "AGREE", "a warning, taken",
         priority=8, intent="WARNING"),
    Rule("owed.answer", "ANSWER_SELF", "I still owe them an answer",
         priority=7, intent="SMALLTALK", when=_owes_answer),

    # -- the floor. Something is always said, and these are the two shapes it takes ----------
    Rule("floor.cold", "DEFLECT", "nothing to say to you in particular",
         priority=-10, trust=("cold", "hostile")),
    Rule("floor.any", "SMALLTALK_BACK", "small talk gets small talk",
         priority=-10),
)


# ---------------------------------------------------------------------------
# Planning
# ---------------------------------------------------------------------------


class Construction:
    """What to say: one act, the tags it was chosen under, the slots, and why.

    ``reasons`` is the list the panels and ``/why`` read, in the shape
    :func:`dwarfsim.arbitrator.decide` uses for its terms: the winning rule first, then the
    runners-up it beat, each with the short sentence out of the table.
    """

    __slots__ = ("act", "rule", "reasons", "hears", "state", "slots", "about", "news",
                 "scene", "forced")

    def __init__(self, act, rule, reasons, scene, forced=None):
        self.act = act
        self.rule = rule
        self.reasons = reasons
        self.scene = scene
        self.about = scene.about
        self.news = scene.news
        self.forced = forced
        self.hears = {k: scene.keys.get(k) for k in
                      ("intent", "about", "news", "topic", "sincerity", "heat")}
        self.state = {k: scene.keys.get(k) for k in
                      ("mood", "trust", "condition", "suspicion")}
        self.slots = scene.resolved(SLOT_NAMES)

    def top_reasons(self, n=2):
        return self.reasons[:n]

    def snapshot(self):
        return {"act": self.act, "rule": self.rule, "hears": dict(self.hears),
                "state": dict(self.state), "slots": dict(self.slots),
                "why": [{"rule": r[0], "why": r[1]} for r in self.reasons],
                "forced": self.forced}

    def __repr__(self):
        return "<Construction %s by %s>" % (self.act, self.rule)


def scene_for(world, dwarf, speaker_id, parsed, state=None):
    """Everything the table reads, worked out once: the tags, the numbers and the two columns."""
    speaker = world.agent(speaker_id) if world is not None else None
    about, news, why = derive(parsed, dwarf, speaker, world)
    tick = world.tick if world is not None else 0
    if state is None:
        state = dialogue.of(dwarf, speaker_id, tick)
    scene = Scene(world, dwarf, speaker_id, parsed, about=about, news=news, state=state)
    scene.keys = {
        "intent": str(parsed.get("intent") or "SMALLTALK").upper(),
        "about": about,
        "news": news,
        "topic": str(parsed.get("topic") or "NONE").upper(),
        "sincerity": str(parsed.get("sincerity") or "SINCERE").upper(),
        "heat": heat_key(parsed),
        "mood": mood_key(dwarf),
        "trust": trust_key(dwarf, speaker_id),
        "condition": condition_key(dwarf),
        "suspicion": suspicion_key(dwarf, speaker_id),
    }
    sig = dialogue.turn_signature(scene.keys["intent"], about, news, scene.keys["topic"],
                                  parsed.get("text"))
    rel = dwarf.mind.rel(speaker_id)
    scene.facts = {
        "hatred": rel["hatred"],
        "trust": rel["trust"],
        "felt_trust": regard.felt_trust(dwarf, speaker_id),
        "suspicion": regard.suspicion(dwarf, speaker_id),
        "repeats": state.heard_before(tick, sig),
        "owed": len(state.owed(tick)),
        # The obligation these two already have between them, as the dialogue book has it. It is
        # what makes a CALLBACK about an ask a fact rather than a flourish; see
        # dwarfsim.world.World.note_obligation, which is the only thing that writes it.
        "open_ask": state.latest_ask(mine=True) or state.latest_ask(mine=False),
        "grudge": dwarf.memories.grudge(tick, dwarf, speaker_id),
        "gratitude": dwarf.memories.gratitude(tick, dwarf, speaker_id),
        "sig": sig,
        "derived": why,
    }
    return scene


def plan(dwarf, speaker, parsed, world, state=None, decided=None):
    """``Construction``: the act this dwarf should answer with, and why.

    ``speaker`` may be an agent or an id. ``decided`` is the skill the arbitrator chose about
    this speaker, if it has chosen one: it is the one thing that overrules the table, because a
    dwarf whose arbitrator refused cannot be made to say yes by a rule.
    """
    speaker_id = getattr(speaker, "id", speaker)
    scene = scene_for(world, dwarf, speaker_id, parsed, state=state)
    reasons = [(name, why) for name, why in scene.facts.get("derived", ())]

    forced = None
    if decided in DECIDED_ACT and (scene.keys["intent"] in ("REQUEST", "COMMAND", "OFFER")
                                   or decided in ("IGNORE", "AVOID")):
        forced = decided
        reasons.insert(0, ("decided." + decided,
                           "the arbitrator chose %s about them: the words follow it" % decided))
        return Construction(DECIDED_ACT[decided], "decided." + decided, reasons, scene,
                            forced=forced)

    ranked = []
    for i, rule in enumerate(RULES):
        if rule.matches(scene):
            ranked.append(((rule.priority, rule.specificity(), -i), rule))
    ranked.sort(key=lambda p: p[0], reverse=True)
    if not ranked:                                   # the floor cannot miss, but never assume
        return Construction("SMALLTALK_BACK", "floor.none",
                            reasons + [("floor.none", "no rule fitted")], scene)

    won = ranked[0][1]
    reasons.insert(0, (won.name, won.why))
    for _, rule in ranked[1:4]:
        reasons.append((rule.name, "also fitted: " + rule.why))

    act = won.act
    # The one thing checked after the table rather than in it: an answering act on a question
    # this dwarf cannot answer is the invented fact the bank's rules forbid.
    if act in ANSWERING and scene.keys["intent"] == "QUESTION" and not knows_answer(scene):
        reasons.insert(1, ("guard.ignorance", "it wanted to answer something it cannot know"))
        act = "ADMIT_IGNORANCE"
    return Construction(act, won.name, reasons, scene)
