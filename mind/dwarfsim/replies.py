"""What a dwarf says back when you talk to it.

``speech._TEMPLATES`` gives a dwarf a line for the things it *does* -- a retort, a demand, a
bargain -- because those are skills, and a skill knows what it is doing. But most of what a
player says is not a provocation: a greeting, a question, a bit of praise. The sim answers
those in state (trust moves, the mind vector changes) and says nothing, which reads as being
ignored.

This module fills that gap and nothing else. One function, :func:`reply`, keyed on

* the **intent** the interpreter read -- all thirteen in ``text/SCHEMA.md``;
* the dwarf's **mood** -- ``ANGRY``, ``AFRAID``, ``GLAD`` or ``FLAT``, bucketed off the same
  emotions the arbitrator scores on;
* its **stance** toward the speaker -- ``FRIEND``, ``WARM``, ``NEUTRAL``, ``COLD`` or
  ``ENEMY``, bucketed off the same ``trust`` and ``hatred`` the arbitrator scores on.

Two rules keep this honest, and they are the whole reason it is a separate module rather than
more templates in ``speech.py``:

1. **It never contradicts the arbitrator.** For a request, an order or an offer the caller
   passes ``decided``: the skill the dwarf actually chose in answer. The wording is picked from
   that skill's cell, so a dwarf whose arbitrator chose ``REFUSE`` cannot be made to say yes by
   a template. With no decision yet, it says something non-committal -- never yes.
2. **It never speaks twice.** If the sim's own reaction already put a line in the dwarf's mouth
   (``RETORT``, ``DEMAND``, ``APOLOGY``, ``BARGAIN``, ``ACCEPT``, ``REFUSE`` ...), the caller
   passes it as ``already`` and this returns nothing at all. The skill's line is the answer.

It changes no state: it is words for state that already moved. Every variant is drawn from
``world.rng``, so a seed and the same typing give the same conversation.
"""

from . import profanity, regard
from .schema import NAME_POOL

MOODS = ("ANGRY", "AFRAID", "GLAD", "FLAT")
STANCES = ("FRIEND", "WARM", "NEUTRAL", "COLD", "ENEMY")

#: Silence is an answer too. A ``None`` variant means the dwarf says nothing, and the caller
#: gets a note saying so instead of a line.
SILENCE = None


def mood_of(dwarf):
    """One word for how the dwarf feels, off the same emotions the arbitrator reads.

    Order matters: anger and fear are what change how somebody talks to you, and a dwarf that
    is both furious and cheerful is, for the purpose of answering you, furious.
    """
    e = dwarf.mind.emotions
    if e["anger"] >= 0.40:
        return "ANGRY"
    if e["fear"] >= 0.38:
        return "AFRAID"
    if e["happiness"] >= 0.55:
        return "GLAD"
    return "FLAT"


def stance_of(dwarf, other_id):
    """One word for what the dwarf makes of you, off felt trust and ``hatred``.

    Felt trust rather than trust: how a dwarf *speaks* to you is a matter of how it feels about
    you this afternoon, which is exactly what warmth is (:mod:`dwarfsim.regard`). What it would
    lend you is a different question and reads the relationship directly.
    """
    r = dwarf.mind.rels.get(other_id)
    if r is None:
        return "NEUTRAL"
    trust = regard.felt_trust(dwarf, other_id)
    if r["hatred"] >= 0.35:
        return "ENEMY"
    if trust >= 0.40 and r["hatred"] < 0.15:
        return "FRIEND"
    if trust >= 0.12 and r["hatred"] < 0.20:
        return "WARM"
    if trust <= -0.15 or r["hatred"] >= 0.15:
        return "COLD"
    return "NEUTRAL"


#: A word for the topic that fits in a sentence. Kept here rather than imported out of
#: ``speech`` so that module stays free to change its own private table.
TOPIC_WORD = {
    "FORGE": "forge", "MINE": "mine", "TREASURE": "gold", "FOOD": "bread", "DRINK": "ale",
    "HOME": "hall", "WEAPON": "axe", "WORK": "shift", "CLAN": "clan", "MONSTER": "gate",
    "TRADE": "trade",
}

#: What a question about each topic is answered with. The mood picks a tail, below, so a
#: frightened dwarf and a cheerful one answer the same question differently.
ANSWERS = {
    "MINE": ["The seam's holding, for now.",
             "Two days' digging left in that face, no more.",
             "Wet. It's always wet down there."],
    "FORGE": ["Coal's low and the bellows want mending.",
              "Hot enough to work, if that's what you're asking.",
              "I've irons in it since the morning."],
    "TREASURE": ["Gold enough to count on one hand.",
                 "Whatever there was, somebody's had it.",
                 "Not so much that I'd brag about it."],
    "FOOD": ["Bread and more bread.",
             "The farm's giving what it gives.",
             "There's enough, if nobody's greedy."],
    "DRINK": ["The barrel's half down since Tuesday.",
              "Ale's ale. It does the job.",
              "Good enough to sit over."],
    "HOME": ["The hall stands. That's as much as anyone asks.",
             "Draughty, but it's ours.",
             "Quiet up there, which I'll take."],
    "WEAPON": ["My axe has a chip in it and I know it.",
               "Sharp enough. It'll do what's wanted.",
               "I keep it by me. You'd do the same."],
    "WORK": ["Same as yesterday, and tomorrow after it.",
             "Shift on shift. It's work.",
             "There's always more of it."],
    "CLAN": ["We rub along, mostly.",
             "Ask anyone here and you'll get a different answer.",
             "Blood's blood. It doesn't have to be easy."],
    "MONSTER": ["Something came to the gate. It won't be the last.",
                "The gate's the thin place. It always was.",
                "Keep an axe where you can reach it."],
    "TRADE": ["Prices are what they are.",
              "Nobody's getting rich off it.",
              "I'd trade if there were anything worth trading."],
    "NONE": ["Hard to say.",
             "That depends who's asking.",
             "You'd know as well as I would."],
}

#: How the mood colours an answer. Appended to whatever was said.
MOOD_TAIL = {
    "ANGRY": [" Now leave off.", " And that's all you'll get out of me.",
              " Ask me again and see what it earns you."],
    "AFRAID": [" ...if we're all still here by nightfall.", " Keep your voice down.",
               " I'd not stand about asking, if I were you."],
    "GLAD": [" Good day for it, mind.", " Sit a while, {you}.", " Ha! There it is."],
    "FLAT": ["", "", " That's the whole of it."],
}

#: Said when somebody else from the pool is named in the line.
NAME_TAILS = ["What's {them} to do with it?", "Leave {them} out of it.",
              "{them}? What of {them}?", "You've been talking to {them}, I can hear it."]

#: intent -> {(stance, mood): [variants]}. ``*`` is a wildcard and the most specific cell
#: wins; see :func:`_score` for which of a stance cell and a mood cell takes it.
REPLIES = {
    "GREET": {
        ("FRIEND", "*"): ["{you}! Sit yourself down.", "Well met, {you}.",
                          "Ah, {you}. A friendly beard for once."],
        ("WARM", "*"): ["{you}. Keeping well?", "Morning, {you}.", "Aye, {you}. Morning."],
        ("NEUTRAL", "*"): ["{you}.", "Aye.", "Hm. {you}."],
        ("COLD", "*"): ["...{you}.", "What is it you want, {you}?", "Aye. {you}."],
        ("ENEMY", "*"): [SILENCE, "Don't.", "Save your breath, {you}."],
        ("*", "ANGRY"): ["Not now, {you}.", "I'm in no mood, {you}."],
        ("*", "AFRAID"): ["Keep your voice down, {you}.", "Aye -- quiet, now."],
        ("*", "*"): ["{you}.", "Aye, {you}."],
    },
    "FAREWELL": {
        ("FRIEND", "*"): ["Mind the ice, {you}.", "Come back when the shift's done, {you}."],
        ("ENEMY", "*"): ["Good. Go.", "Don't hurry back, {you}.", SILENCE],
        ("COLD", "*"): ["Aye. Off you go.", "Right."],
        ("*", "*"): ["Farewell, {you}.", "Aye, {you}. Mind how you go.", "Right, {you}."],
    },
    "SMALLTALK": {
        ("FRIEND", "*"): ["Aye, and the {topic} the same as ever.", "True enough, {you}.",
                          "You're not wrong, {you}."],
        ("ENEMY", "*"): ["Talk to somebody else, {you}.", SILENCE],
        ("COLD", "*"): ["If you say so.", "Hm."],
        ("*", "ANGRY"): ["Is that all you came to say, {you}?", "Not now."],
        ("*", "GLAD"): ["Aye! And the {topic} looking up with it.", "That it is, {you}."],
        ("*", "*"): ["Aye.", "Hm. The {topic}, aye.", "Long shift, {you}. Long shift."],
    },
    "QUESTION": {
        # Handled by ANSWERS, above: these are only the cells where the dwarf will not answer.
        ("ENEMY", "*"): ["Ask somebody else, {you}.", SILENCE, "Why would I tell you?"],
        ("COLD", "ANGRY"): ["Find out yourself, {you}."],
    },
    "PRAISE": {
        ("FRIEND", "*"): ["That's kind, {you}.", "Thanks, {you}. I'll not forget it."],
        ("WARM", "*"): ["Aye, well. It's the job, {you}.", "Kind of you to say, {you}."],
        ("COLD", "*"): ["What is it you want, {you}?", "Flattery's cheap, {you}."],
        ("ENEMY", "*"): ["Save it, {you}.", "From you? Keep it."],
        ("*", "ANGRY"): ["Fine words don't mend it, {you}.", "Don't."],
        ("*", "*"): ["Hm. Thanks, {you}.", "Aye. It was nothing."],
    },
    # Praise from somebody the dwarf has decided wants something. The intent on the wire is
    # still PRAISE; what changed is that the listener stopped reading it that way. See
    # :mod:`dwarfsim.regard`.
    "FLATTERY": {
        ("FRIEND", "*"): ["You've said that twice now, {you}. What is it you're after?",
                          "Aye, aye. And the favour?"],
        ("ENEMY", "*"): ["Save it, {you}. I know what you are.",
                         "Sweet words out of that mouth. No."],
        ("*", "ANGRY"): ["Say it a third time and I'll start wondering what you want.",
                         "Enough, {you}. Nobody talks like that for nothing."],
        ("*", "AFRAID"): ["Why are you being so kind, {you}? What have you done?"],
        ("*", "*"): ["Say it a third time and I'll start wondering what you want.",
                     "You've been laying it on thick, {you}. Out with it.",
                     "Flattery's cheap, {you}, and you're spending it fast."],
    },
    "APOLOGY": {
        ("FRIEND", "*"): ["Let it lie, {you}. It's forgotten.", "Say no more, {you}."],
        ("ENEMY", "*"): ["Words, {you}.", "I'll believe it when I see it.",
                         "You'll need more than a word, {you}."],
        ("*", "ANGRY"): ["Sorry doesn't put it back, {you}.", "That's easily said, {you}."],
        ("*", "*"): ["Aye, well. Let it lie.", "Right, {you}. Said and done."],
    },
    "INSULT": {
        ("FRIEND", "*"): ["Ha! From you I'll take it, {you}.", "You're one to talk, {you}."],
        ("ENEMY", "*"): ["One more word, {you}.", "Say it closer, {you}.",
                         "You've said your piece. Now get.",
                         "I don't take that from you, {you}."],
        ("*", "ANGRY"): ["Say that again to my face, {you}.", "Mind your tongue, {you}.",
                         "Try that once more, {you}.", "I've had my fill of you."],
        ("*", "AFRAID"): ["...as you like, {you}.", "I want no trouble."],
        ("*", "*"): ["That's your opinion, {you}.", "Mind your beard, {you}."],
    },
    "THREAT": {
        ("*", "AFRAID"): ["Easy, {you}. There's no call for that.", "I want no trouble, {you}."],
        ("*", "ANGRY"): ["Try it, {you}.", "You'll find me at the {topic}, {you}."],
        ("ENEMY", "*"): ["Any time you like, {you}.", "That's twice now, {you}."],
        ("*", "*"): ["Big words, {you}.", "Is that how it is, {you}?"],
    },
    "WARNING": {
        ("*", "AFRAID"): ["Where? Mahal's beard, where?", "Then we're for it."],
        ("ENEMY", "*"): ["And why would you warn me, {you}?", "If you say so."],
        ("*", "*"): ["At the gate? Arms, then.", "Say that twice, {you}.",
                     "Aye -- I'd sooner know."],
    },
    "ACCUSE": {
        ("FRIEND", "*"): ["That wasn't me, {you}. Ask anyone who was there."],
        ("ENEMY", "*"): ["Blame me, then. You always do.", "Prove it, {you}."],
        ("*", "ANGRY"): ["I did no such thing, {you}, and you know it.",
                         "Watch what you're saying, {you}."],
        ("*", "AFRAID"): ["It wasn't me. It wasn't.", "Why are you asking me?"],
        ("*", "*"): ["It wasn't me, {you}.", "You've the wrong dwarf, {you}."],
    },
    # REQUEST, COMMAND and OFFER are answered out of DECIDED, below, whenever the arbitrator
    # has decided. These cells are the not-yet-decided ones, and none of them says yes.
    "REQUEST": {
        ("FRIEND", "*"): ["I'll see to it when I can, {you}.", "If I've time, {you}."],
        ("ENEMY", "*"): ["And why would I, {you}?", "Ask somebody who owes you."],
        ("COLD", "*"): ["I've my own shift, {you}.", "We'll see."],
        ("*", "ANGRY"): ["You've picked your moment, {you}.", "Not now."],
        ("*", "*"): ["Maybe. I've a shift to finish first.", "I'll think on it, {you}."],
    },
    "COMMAND": {
        ("FRIEND", "*"): ["You could have asked, {you}. I'll see to it."],
        ("ENEMY", "*"): ["You don't give me orders, {you}.", "Give orders to your own kin."],
        ("COLD", "*"): ["You don't give me orders, {you}.", "Is that so?"],
        ("*", "ANGRY"): ["Orders, is it? Not from you, {you}."],
        ("*", "AFRAID"): ["...aye. If it's wanted.", "Right, right."],
        ("*", "*"): ["I'll think on it, {you}.", "We'll see, {you}."],
    },
    "OFFER": {
        ("ENEMY", "*"): ["I'll take it. It buys you nothing, {you}.", "Keep it, {you}."],
        ("*", "*"): ["Aye, I'll take it, {you}.", "That's decent of you, {you}.",
                     "Much obliged, {you}."],
    },
}

#: How each answer to an ask sounds. The key is the skill the arbitrator actually chose, so
#: nothing here can disagree with the decision the log records.
DECIDED = {
    "ACCEPT": ["Right you are, {you}. It'll be done.", "Consider it done, {you}.",
               "Aye. I'll see to it."],
    "REFUSE": ["No. Do it yourself, {you}.", "I've my own work, {you}.", "Not for you, {you}."],
    "BARGAIN": ["Not for nothing, {you}.", "There's a price on my back, {you}.",
                "Name a figure and we'll talk, {you}."],
    "FULFIL": ["It's done, {you}.", "There. As asked."],
    "IGNORE": [SILENCE, "..."],
    "AVOID": ["I've somewhere to be, {you}.", SILENCE],
}

#: When the interpreter says the line was sarcastic, the dwarf says it noticed.
SARCASM = {
    ("FRIEND", "*"): ["Ha. Very funny, {you}.", "I hear the teeth in that, {you}."],
    ("ENEMY", "*"): ["Say it plain or don't say it, {you}.", "I know exactly what you meant."],
    ("*", "ANGRY"): ["Don't take me for a fool, {you}.", "Say it plain, {you}."],
    ("*", "*"): ["I know what you meant by that, {you}.", "That's not what you mean, {you}.",
                 "Aye, very clever, {you}."],
}

#: And when it was a joke, from somebody it can take a joke from.
JOKING = {
    ("FRIEND", "*"): ["Ha! You're a card, {you}.", "Get away with you, {you}."],
    ("WARM", "*"): ["Ha. Aye.", "You're not serious, {you}."],
}

#: The intents whose answer must agree with what the arbitrator chose.
ASKING = ("REQUEST", "COMMAND", "OFFER")

#: Choices that are an answer to anything at all, not only to an ask: a dwarf that chose to let
#: it go says nothing, and one that chose to walk off says so. Neither skill speaks for itself.
ALWAYS_DECIDED = ("IGNORE", "AVOID")


#: The answers that may carry swearing at all: what a dwarf says back to a provocation. An
#: answer to a greeting, a question or an ask stays civil whatever the dwarf feels, because
#: those are the cells the arbitrator and the obligation machinery are read out of, and a
#: dwarf that swears at "good morning" is a different feature. Nothing swears unless the
#: world's ``max_tier`` allows it -- see :mod:`dwarfsim.profanity`.
SWEARING_KINDS = ("INSULT", "THREAT", "ACCUSE", "REFUSE_APOLOGY")

#: Full insult sentences mixed into a provocation answer when the world's swearing
#: ceiling allows that tier. Existing cells stay; these are added, not replacements.
#: Tier 3 uses the in-world list only -- recognition-file slurs stay out of spoken lines.
SWEAR_REPLIES = {
    "INSULT": {
        1: ["Hell, {you}, that's a load of slag.",
            "By the broken anvil, {you}, mind your beard.",
            "Damn your tongue, {you}."],
        2: ["You're a slagforger, {you}. Recut the whole thing.",
            "Fuck off my forge, {you}.",
            "Beardless whelp, {you}. Grow a braid before you talk craft.",
            "You hollow-beard, {you}. There's no clan in you.",
            "Pick-dropper, {you}. You froze on the face.",
            "You piece of shit, {you}. Stay off this level."],
        3: ["Sap-drinker talk stays in the trees, {you}.",
            "Stubble-chin, {you}. Don't lecture a braid.",
            "Sun-squinter, {you}. Go chase daylight.",
            "Keep your sky-gawper hands off the scales, {you}."],
    },
    "THREAT": {
        1: ["By my beard, {you}, one more word."],
        2: ["Touch my forge again, you bastard, and I'll break your arm.",
            "Say that again, you fucker, {you}."],
        3: ["Come closer, you warren-filth, {you}."],
    },
    "ACCUSE": {
        1: ["Hell, {you}, that was you at the bins."],
        2: ["You ore-thief, {you}. I counted that bin.",
            "Don't play innocent, you shithead, {you}."],
        3: ["A hall-crasher took it, and I say it was you, {you}."],
    },
}


def _swear_extras(world, dwarf, speaker_id, kind):
    """Extra full lines this dwarf would actually reach for, or ``[]``."""
    sw = profanity.for_speaker(world, dwarf, world.agent(speaker_id))
    ceiling = sw.tier if sw is not None else 0
    out = []
    for need, lines in SWEAR_REPLIES.get(kind, {}).items():
        if ceiling >= need:
            out.extend(lines)
    return out


#: Moods that talk over everything else. Somebody furious or frightened sounds like it
#: whoever you are to them; the rest of the time, who you are is what shapes the answer.
STRONG_MOODS = ("ANGRY", "AFRAID")


def _score(key, stance, mood):
    """How well one cell key fits, or ``None`` if it does not.

    Two exact matches beat one. Between a cell that named the stance and one that named the
    mood, the mood wins if it is a strong one and the stance wins otherwise -- which is the
    difference between a friend who is furious with you (furious) and a friend who is merely
    cheerful (a friend).
    """
    ks, km = key
    if ks != "*" and ks != stance:
        return None
    if km != "*" and km != mood:
        return None
    exact = (1 if ks != "*" else 0) + (1 if km != "*" else 0)
    tie = 0
    if exact == 1:
        tie = 1 if ((km != "*") == (mood in STRONG_MOODS)) else 0
    return (exact, tie)


def cell(table, stance, mood):
    """``(key, variants)`` for the most specific cell that fits, or ``(None, [])``."""
    best, best_key = None, None
    for key in table:
        s = _score(key, stance, mood)
        if s is None:
            continue
        if best is None or s > best:
            best, best_key = s, key
    return (best_key, list(table[best_key])) if best_key is not None else (None, [])


def _usable(variants, topic, them):
    """Drop variants that need a word this line does not have."""
    out = []
    for v in variants:
        if v is None:
            out.append(v)
            continue
        if "{topic}" in v and not topic:
            continue
        if "{them}" in v and not them:
            continue
        out.append(v)
    return out


def _pick(rng, variants):
    return variants[rng.randrange(len(variants))] if variants else None


def _fill(template, me, you, topic, them):
    if template is None:
        return None
    return template.format(me=me, you=you, topic=topic or "day", them=them or you)


def third_party(parsed, world, dwarf, speaker_id):
    """The other dwarf named in the line, if there is one and it is not the two of us."""
    for name in (parsed.get("names") or ()):
        if name not in NAME_POOL or name == dwarf.name:
            continue
        other = world.agent_by_name(name)
        if other is not None and other.id != dwarf.id and other.id != speaker_id:
            return other.name
    return None


def reply(world, dwarf, speaker_id, parsed, decided=None, already=None, rng=None):
    """What ``dwarf`` says back to ``speaker_id``. Returns a dict; changes no state.

    ``decided`` is the skill the arbitrator chose in answer (``ACCEPT``, ``REFUSE``,
    ``BARGAIN``, ``FULFIL``, ``IGNORE``, ``AVOID``) or ``None`` if it has not decided. It wins
    over everything else for an ask, which is rule 1 at the top of this module.

    ``already`` is a line the sim's own skill has already put in this dwarf's mouth in answer.
    When there is one, this says nothing: rule 2.

    The result:

    ===========  ==========================================================
    ``text``     the line, or ``None`` for silence or for saying nothing
    ``note``     why there is no line, when there is none
    ``kind``     which cell it came from: an intent, a decided skill, ``SARCASM`` ...
    ``mood``     ``ANGRY`` / ``AFRAID`` / ``GLAD`` / ``FLAT``
    ``stance``   ``FRIEND`` / ``WARM`` / ``NEUTRAL`` / ``COLD`` / ``ENEMY``
    ``cell``     the table key that was used, for the tests and for ``/why``
    ===========  ==========================================================
    """
    rng = rng or world.rng
    speaker = world.agent(speaker_id)
    you = speaker.name if speaker is not None else "you"
    mood = mood_of(dwarf)
    stance = stance_of(dwarf, speaker_id)
    out = {"text": None, "note": None, "kind": None, "mood": mood, "stance": stance,
           "cell": None, "who": dwarf.name}

    if already:
        out["kind"] = "ALREADY"
        out["note"] = "%s answered with its own line" % dwarf.name
        return out
    if not dwarf.alive:
        out["note"] = "%s is past answering" % dwarf.name
        return out

    intent = parsed.get("intent", "SMALLTALK")
    topic_name = parsed.get("topic", "NONE")
    topic = TOPIC_WORD.get(topic_name)
    them = third_party(parsed, world, dwarf, speaker_id)
    sincerity = (parsed.get("sincerity") or "SINCERE").upper()

    table, kind = None, intent
    if intent == "PRAISE" and regard.suspicion(dwarf, speaker_id) >= regard.SUSPICION_THRESHOLD:
        # The words were praise. This dwarf stopped hearing them that way some compliments
        # ago, and the answer is where that becomes visible.
        table, kind = REPLIES["FLATTERY"], "FLATTERY"
    elif decided in DECIDED and (intent in ASKING or decided in ALWAYS_DECIDED):
        # Rule 1: the arbitrator has spoken, so the words come from its cell and no other.
        table, kind = {("*", "*"): DECIDED[decided]}, decided
    elif sincerity == "SARCASTIC":
        table, kind = SARCASM, "SARCASM"
    elif sincerity == "JOKING" and stance in ("FRIEND", "WARM"):
        table, kind = JOKING, "JOKING"
    elif intent == "QUESTION":
        key, refusal = cell(REPLIES["QUESTION"], stance, mood)
        if refusal:
            table, kind = {key: refusal}, "QUESTION"
        else:
            answer = _pick(rng, ANSWERS.get(topic_name) or ANSWERS["NONE"])
            answer += _pick(rng, MOOD_TAIL[mood])
            out.update({"text": _fill(answer, dwarf.name, you, topic, them),
                        "kind": "ANSWER", "cell": (topic_name, mood)})
            _add_name_tail(out, rng, dwarf, you, topic, them)
            return swear(world, dwarf, speaker_id, out)
    if table is None:
        table = REPLIES.get(intent) or REPLIES["SMALLTALK"]

    key, variants = cell(table, stance, mood)
    variants = _usable(variants, topic, them)
    if kind in SWEAR_REPLIES:
        variants = variants + _usable(
            _swear_extras(world, dwarf, speaker_id, kind), topic, them)
    if not variants:
        key, variants = cell(REPLIES["SMALLTALK"], stance, mood)
        variants = _usable(variants, topic, them) or ["Aye."]
    chosen = _pick(rng, variants)
    out["kind"], out["cell"] = kind, key
    if chosen is SILENCE:
        out["note"] = "%s looked at you and said nothing" % dwarf.name
        return out
    out["text"] = _fill(chosen, dwarf.name, you, topic, them)
    if "{them}" not in chosen:
        _add_name_tail(out, rng, dwarf, you, topic, them)
    _add_hurt_tail(out, rng, dwarf)
    return swear(world, dwarf, speaker_id, out)


def swear(world, dwarf, speaker_id, out):
    """Fill the answer's optional profanity slot, if this dwarf is in a state to use it.

    The same rules as the sim's own lines: the world's ``max_tier`` is the ceiling and the
    dwarf's temper, anger and hatred of you decide what goes in the slot, or whether anything
    does. Only an answer to a provocation has a slot at all -- see :data:`SWEARING_KINDS`.
    """
    if not out.get("text"):
        return out
    if out.get("kind") not in SWEARING_KINDS:
        return out
    sw = profanity.for_speaker(world, dwarf, world.agent(speaker_id))
    if sw is None:
        return out
    who = world.agent(speaker_id)
    you = who.name if who is not None else None
    text, hits = sw.apply(out["text"], directed=True, name=you, addressed="LISTENER")
    if hits:
        out["text"] = text
        out["profanity"] = profanity.rows(hits)
    return out


def _add_hurt_tail(out, rng, dwarf):
    """A dwarf with a broken arm mentions it, whatever else it was going to say."""
    if not out.get("text"):
        return
    from . import speech
    tail = speech.hurt_tail(rng, dwarf.condition.complaint())
    if tail:
        out["text"] += tail
        out["hurt"] = dwarf.condition.describe()


def _add_name_tail(out, rng, dwarf, you, topic, them):
    """Somebody else was named: every other answer picks it up."""
    if not them or out["text"] is None:
        return
    if rng.random() < 0.5:
        out["text"] += " " + _fill(_pick(rng, NAME_TAILS), dwarf.name, you, topic, them)
