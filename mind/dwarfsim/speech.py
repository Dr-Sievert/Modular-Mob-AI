"""The speech hook: a parsed utterance in, mind events out.

:func:`hear` takes exactly the fields in ``text/SCHEMA.md`` -- ``intent topic addressed aggression
valence urgency names`` -- plus one optional field this sim adds, ``ask`` -- and is the only place
the sim turns words into state. A text classifier plugs in here later: it produces the same dict
from a raw string and nothing else changes.

The mapping follows SCHEMA.md's "How the sim uses it":

* ``INSULT`` / ``THREAT`` / ``ACCUSE`` become those events, with aggression driving the magnitude,
  so anger goes up and trust in the speaker goes down;
* ``PRAISE`` / ``OFFER`` / ``APOLOGY`` become ``PRAISE`` / ``GIFT`` / ``APOLOGY``;
* ``WARNING`` becomes ``WARNING`` (fear up, trust up a little);
* ``REQUEST`` / ``COMMAND`` are not events at all: they become a standing task proposal on the
  listener, which the arbitrator weighs by trust in the speaker (the ``request_pull`` term);
* everything else is ``SMALLTALK``.

**The optional ``ask``.** ``REQUEST``, ``COMMAND`` and ``OFFER`` may carry one more key::

    "ask": {"action": "BRING"|"GIVE"|"GO_TO"|"FIGHT"|"STOP"|"HELP",
            "item": str|None, "quantity": int, "place": str|None,
            "target": agent id|None, "payment": int}

With it, the utterance also creates an :class:`~dwarfsim.obligations.Obligation` on the listener,
who then gets ``ACCEPT`` / ``REFUSE`` / ``BARGAIN`` candidates. Every field inside ``ask`` is
optional but ``action``; an ``ask`` that names no known action is ignored. ``hear`` works exactly
as before when the field is absent, so a classifier that does not produce it loses nothing but the
bargaining.

**Swearing.** Every line, from a keyboard or from a skill, goes through :func:`screen` on its way
in: the world's :class:`~dwarfsim.profanity.Lexicon` reads it, and what it finds raises
``aggression`` to at least the strongest term's strength. A tier 3 term -- a group-targeted slur --
takes ``aggression`` to 1.0, ``valence`` to at most -0.8, and makes the event a ``SLUR`` rather
than whatever the intent would have been, which is a heavier row in the event table than an insult.
Witnesses take it as they take an insult. That is the only place words are read for swearing, so
the sim and the talk app cannot disagree about it.

Witnesses are everyone else at the speaker's place, and they update their view of the speaker by a
fraction -- that part lives in :func:`dwarfsim.mind.apply_event`.

The template generator at the bottom exists only so the log has a readable line in it. It is not
training data and it is not meant to be clever.
"""

from . import profanity, regard
from .obligations import normalise_ask
from .schema import NAME_POOL

#: Which mind event each intent becomes. ``None`` means "not an event, a task proposal".
INTENT_EVENT = {
    "GREET": "SMALLTALK",
    "FAREWELL": "SMALLTALK",
    "SMALLTALK": "SMALLTALK",
    "QUESTION": "SMALLTALK",
    "REQUEST": None,
    "COMMAND": None,
    "OFFER": "GIFT",
    "PRAISE": "PRAISE",
    "APOLOGY": "APOLOGY",
    "INSULT": "INSULT",
    "THREAT": "THREAT",
    "WARNING": "WARNING",
    "ACCUSE": "ACCUSE",
}

HOSTILE_INTENTS = ("INSULT", "THREAT", "ACCUSE")

#: Intents that may carry an ``ask`` and so create an obligation.
ASKING_INTENTS = ("REQUEST", "COMMAND", "OFFER")

#: Where a topic sends you, for turning a REQUEST or COMMAND into a pull toward somewhere.
TOPIC_PLACE = {
    "FORGE": "FORGE", "WEAPON": "FORGE",
    "MINE": "MINE", "TREASURE": "MINE",
    "FOOD": "FARM",
    "DRINK": "TAVERN", "TRADE": "TAVERN",
    "HOME": "HALL", "CLAN": "HALL",
    "MONSTER": "GATE",
    "WORK": None,
    "NONE": None,
}

#: How long a request stays on the listener's mind.
REQUEST_TTL = 80


def top_tier(parsed):
    """The worst tier of profanity this line is known to carry. 0 for none."""
    return max((int(r.get("tier", 0)) for r in (parsed.get("profanity") or ())), default=0)


def screen(world, parsed):
    """Read the line for swearing and adjust its labels in place. Returns ``(parsed, hits)``.

    Two sources, and they are added together: what the speaker already knows it said (a skill
    that filled a profanity slot puts it in ``parsed["profanity"]``) and what the world's lexicon
    finds in the text.

    What it then does depends on what kind of term it was, which is the difference between
    swearing at the world and swearing at somebody:

    * an **expletive** aimed at nobody leaves the intent alone and only raises ``aggression`` to
      the term's strength. "What the hell happened to the ore bins" is still a question;
    * an **insult** or a **slur** aimed at a person or at a people *is* an insult, whatever the
      classifier made of the sentence around it. The intent becomes ``INSULT`` (tier 3 goes
      further, to a ``SLUR`` event, in :func:`hear`), ``valence`` drops to at most -0.5, and a
      line the classifier thought was aimed at nobody is taken as aimed at whoever is listening.
      Without that, "dumb cunt" reads as small talk and the dwarf it was said to cheers up.

    A tier 3 term additionally takes ``aggression`` to 1.0 and ``valence`` to at most -0.8: a
    slur is not a matter of how it was delivered.
    """
    lex = getattr(world, "profanity", None) if world is not None else None
    hits = profanity.unrows(parsed.get("profanity"))
    if lex is not None:
        known = {h.term for h in hits}
        hits = hits + [t for t in lex.scan_terms(parsed.get("text") or "")
                       if t.term not in known]
    if not hits:
        return parsed, hits
    tier = max(h.tier for h in hits)
    strongest = max(h.strength for h in hits)
    was = float(parsed.get("aggression") or 0.0)
    parsed["aggression"] = 1.0 if tier >= 3 else max(was, strongest)

    if profanity.aimed_at_somebody(hits):
        intent = parsed.get("intent", "SMALLTALK")
        if intent not in HOSTILE_INTENTS:
            parsed["intent"] = "INSULT"
            parsed["intent_override"] = [intent, "INSULT"]
        parsed["valence"] = min(float(parsed.get("valence") or 0.0), -0.5)
        if parsed.get("addressed") == "NONE":
            parsed["addressed"] = "LISTENER"
            parsed["addressed_override"] = ["NONE", "LISTENER"]
    if tier >= 3:
        parsed["valence"] = min(float(parsed.get("valence") or 0.0), -0.8)
    parsed["profanity"] = profanity.rows(hits)
    parsed["profanity_bump"] = [round(was, 3), round(parsed["aggression"], 3)]
    return parsed, hits


def magnitude_of(parsed):
    """How hard the utterance lands, from aggression, valence and urgency."""
    intent = parsed.get("intent", "SMALLTALK")
    aggression = float(parsed.get("aggression", 0.0))
    valence = float(parsed.get("valence", 0.0))
    urgency = float(parsed.get("urgency", 0.0))
    if intent in HOSTILE_INTENTS or top_tier(parsed) >= 3:
        m = 0.45 + 0.85 * aggression + 0.35 * max(0.0, -valence)
    elif intent == "WARNING":
        m = 0.50 + 0.80 * urgency
    else:
        m = 0.50 + 0.80 * max(0.0, valence)
    if parsed.get("addressed") == "NONE":
        m *= 0.4      # muttering into your beard
    elif parsed.get("addressed") == "GROUP":
        m *= 0.8
    return max(0.1, min(1.8, m))


def hear(world, speaker_id, listener_id, parsed):
    """One utterance heard. Returns the event dict the world logged, or ``None``.

    ``parsed`` has the SCHEMA.md fields plus the optional ``ask``. ``listener_id`` may be ``None``
    for a line thrown at the room.
    """
    speaker = world.agent(speaker_id)
    if speaker is None or not speaker.alive:
        return None

    screen(world, parsed)
    intent = parsed.get("intent", "SMALLTALK")
    addressed = parsed.get("addressed", "LISTENER")
    target = world.agent(listener_id) if listener_id is not None else None

    # "Don't trust Sindri, he cheats at dice": aimed at whoever is named, not at who is listening.
    if addressed == "THIRD":
        for name in parsed.get("names", ()):
            named = world.agent_by_name(name)
            if named is not None and named.alive and named.id != speaker.id:
                target = named
                break

    magnitude = magnitude_of(parsed)
    event = INTENT_EVENT.get(intent, "SMALLTALK")

    # A slur is what the line was, whatever else it was carrying: not an insult, not a request
    # with a bad word in it. It goes through as its own event, which is a heavier row.
    if top_tier(parsed) >= 3 and target is not None and target.alive and target.id != speaker.id:
        magnitude, extra = _weigh(world, speaker, target, "SLUR", parsed, magnitude)
        extra["profanity"] = list(parsed.get("profanity") or ())
        return world.emit("SLUR", speaker, target, dialogue=parsed.get("text"), parsed=parsed,
                          magnitude=magnitude, extra=extra)

    # A structured ask turns any of the three asking intents into an obligation as well.
    ask = normalise_ask(parsed.get("ask")) if intent in ASKING_INTENTS else None
    if ask is not None and target is not None and target.alive and target.id != speaker.id:
        obl = world.propose_obligation(speaker, target, ask)
        return world.emit("ASK", speaker, target, place=speaker.place,
                          dialogue=parsed.get("text"), parsed=parsed, apply=False,
                          extra={"ask": dict(ask), "obl": obl.oid,
                                 "deadline": obl.deadline, "payment": obl.payment()})

    if event is None:
        # REQUEST / COMMAND with no ask: a vague pull, weighted later by trust in the speaker.
        place = TOPIC_PLACE.get(parsed.get("topic", "NONE"))
        if target is not None and target.alive:
            target.request = {
                "place": place,
                "topic": parsed.get("topic", "NONE"),
                "from": speaker.id,
                "urgency": float(parsed.get("urgency", 0.3)),
                "until": world.tick + REQUEST_TTL,
            }
        return world.emit(
            "REQUEST", speaker, target, dialogue=parsed.get("text"), parsed=parsed, apply=False,
        )

    if target is None or not target.alive:
        return None
    if speaker.id == target.id:
        return world.emit(event, speaker, target, dialogue=parsed.get("text"),
                          parsed=parsed, magnitude=magnitude)
    magnitude, extra = _weigh(world, speaker, target, event, parsed, magnitude)
    return world.emit(extra.pop("_event", event), speaker, target, dialogue=parsed.get("text"),
                      parsed=parsed, magnitude=magnitude, extra=extra)


def _weigh(world, speaker, target, event, parsed, magnitude):
    """What this line is *still* worth to the one hearing it, and what to log about that.

    Two mechanics out of :mod:`dwarfsim.regard`, and they are the whole of the anti-farming
    answer at this seam:

    * **habituation** -- the same signature from the same mouth is worth near nothing by the
      third time. It multiplies the magnitude, so the event table lands quieter without a single
      row of it changing;
    * **flattery** -- praise that is unearned, frequent or repeated raises the listener's
      suspicion of the speaker, and past the threshold the ``PRAISE`` becomes a ``FLATTERY``:
      the same words read as a manipulation, which costs trust instead of buying it.

    Returns ``(magnitude, extra)``; ``extra["_event"]`` overrides the event kind when there is
    one. Everything it works out goes in the log, so the "why" stays complete.
    """
    tick = world.tick
    factor, sig, repeats = regard.habituate(target, speaker.id, tick, event, parsed)
    regard.note(target, speaker.id, tick, sig)
    extra = {"habit": round(factor, 3)}
    if factor < 1.0:
        extra["said_before"] = True

    if event == "PRAISE":
        trust = target.mind.rel(speaker.id)["trust"]
        level, flattering = regard.flattery(target, speaker, tick, trust, repeats)
        extra["suspicion"] = round(level, 3)
        if flattering:
            extra["_event"] = "FLATTERY"
            extra["flattery"] = True
            # Being seen through does not get quieter with repetition: the third identical
            # compliment is exactly the one that gives the game away.
            factor = max(factor, 0.60)
    return max(0.0, magnitude * factor), extra


# ---------------------------------------------------------------------------
# Templates, for readability of the log only
# ---------------------------------------------------------------------------
#
# The key is a *speech act* as the sim thinks of it; ACT_INTENT maps it back onto one of the
# thirteen schema intents, because that is the contract a classifier has to satisfy. Several acts
# share an intent: a RETORT is an INSULT as far as the labels go, and the sim's own event kind is
# what keeps them apart in the log.

_TEMPLATES = {
    "INSULT": [
        ("Get away from my {topic_thing}, {you}!", 0.75, -0.8, 0.5),
        ("You couldn't swing a pick straight if the mountain held it for you, {you}.", 0.6, -0.7, 0.1),
        ("Beard like a goat's, {you}, and half the sense.", 0.55, -0.6, 0.1),
        ("Out of my way, {you}.", 0.5, -0.5, 0.4),
        ("Everyone knows what you are, {you}.", 0.65, -0.75, 0.2),
        ("I've seen wet ore with more spine than you, {you}.", 0.6, -0.7, 0.2),
        ("You work like the mountain owes you a favour, {you}.", 0.55, -0.6, 0.15),
        ("Stand somewhere else, {you}. You're in the light.", 0.5, -0.55, 0.35),
        ("Call that a day's work, {you}? I don't.", 0.58, -0.65, 0.2),
        ("I've had mules that complained less, {you}.", 0.52, -0.58, 0.15),
        ("You'd lose a nail in a closed box, {you}.", 0.55, -0.6, 0.15),
        ("That braid's the only true thing on you, {you}.", 0.58, -0.62, 0.2),
        ("Go find a shallow seam, {you}. This one's for workers.", 0.6, -0.65, 0.25),
        ("You smell of surface rain and excuses, {you}.", 0.55, -0.6, 0.2),
        ("I've forgotten better dwarves than you, {you}.", 0.62, -0.7, 0.2),
        ("Your hammer talks louder than your work, {you}.", 0.55, -0.6, 0.2),
        ("Don't stand in a doorway like you own it, {you}.", 0.5, -0.55, 0.35),
        ("If pride filled a cart, you'd have a load, {you}.", 0.52, -0.58, 0.15),
        ("You'd drown in a dry sump, {you}.", 0.58, -0.62, 0.2),
        ("Keep your hands off my bench, {you}.", 0.6, -0.65, 0.4),
        ("I've seen cave-ins with more grace, {you}.", 0.55, -0.6, 0.15),
        ("You're a waste of lamp oil, {you}.", 0.6, -0.68, 0.2),
        ("Talk less. Swing more. Or leave, {you}.", 0.55, -0.6, 0.3),
        ("That look doesn't make you a smith, {you}.", 0.52, -0.58, 0.15),
        ("You'd sell your braid for a warm seat, {you}.", 0.62, -0.7, 0.2),
        ("Step aside before I forget my manners, {you}.", 0.65, -0.7, 0.4),
        ("Your kin would hide their faces, {you}.", 0.68, -0.75, 0.25),
        ("You couldn't hold a face if it sat still, {you}.", 0.58, -0.65, 0.2),
        ("I've no room for dead weight at the {topic_thing}, {you}.", 0.6, -0.65, 0.3),
        ("Go count pebbles. That's your skill, {you}.", 0.55, -0.6, 0.15),
        ("You walk like the floor owes you gold, {you}.", 0.52, -0.58, 0.2),
        ("A child with a stick would outwork you, {you}.", 0.6, -0.68, 0.2),
        ("You're all noise and no ore, {you}.", 0.58, -0.65, 0.2),
        ("I wouldn't trust you with a blunt chisel, {you}.", 0.55, -0.62, 0.2),
        ("That strut of yours is empty, {you}.", 0.5, -0.55, 0.15),
        ("You'd lose a fight with a locked gate, {you}.", 0.55, -0.6, 0.2),
        ("I've had enough of looking at you, {you}.", 0.6, -0.65, 0.35),
        ("You bring bad air with you, {you}.", 0.58, -0.62, 0.25),
        ("Don't smile like that. You haven't earned it, {you}.", 0.52, -0.58, 0.15),
        ("You're slower than a wet fuse, {you}.", 0.55, -0.6, 0.2),
        ("I'd sooner share a bunk with a goat, {you}.", 0.6, -0.65, 0.15),
        ("You couldn't find the hall in the hall, {you}.", 0.52, -0.58, 0.15),
        ("Put that pick down before you hurt a wall, {you}.", 0.58, -0.62, 0.3),
        ("Your word's worth less than slack coal, {you}.", 0.65, -0.7, 0.25),
        ("I've scraped better off my boot, {you}.", 0.68, -0.75, 0.2),
        ("Go on then. Impress me. You won't, {you}.", 0.55, -0.6, 0.2),
        ("You stand like a prop that's already failed, {you}.", 0.58, -0.65, 0.2),
        ("Keep your advice. Keep walking, {you}.", 0.52, -0.58, 0.35),
        ("You're a short fuse and a shorter skill, {you}.", 0.6, -0.68, 0.25),
        ("I wouldn't follow you into daylight, {you}.", 0.62, -0.7, 0.2),
        ("That {topic_thing} deserves better than you, {you}.", 0.6, -0.65, 0.2),
        ("You're in my way and in my patience, {you}.", 0.58, -0.62, 0.4),
        ("I've heard rocks with more to say, {you}.", 0.5, -0.55, 0.1),
        ("Go home to your mother if she'll have you, {you}.", 0.65, -0.72, 0.25),
    ],
    "PRAISE": [
        ("Fine work at the {topic_thing}, {you}.", 0.0, 0.7, 0.1),
        ("There's none steadier than you, {you}.", 0.0, 0.8, 0.1),
        ("Thanks, {you}. I'll not forget it.", 0.0, 0.75, 0.1),
        ("That's a good hand you've got, {you}.", 0.0, 0.6, 0.05),
        ("Aye. That'll hold, {you}.", 0.0, 0.65, 0.05),
        ("You've a true eye, {you}.", 0.0, 0.7, 0.05),
        ("I'd work a face with you any day, {you}.", 0.0, 0.75, 0.1),
        ("That's proper craft, {you}.", 0.0, 0.72, 0.05),
        ("Well struck, {you}.", 0.0, 0.68, 0.1),
        ("The clan's lucky to have that hand, {you}.", 0.0, 0.78, 0.1),
        ("I'll take your mark on a crate, {you}.", 0.0, 0.7, 0.05),
        ("That's how it's done, {you}.", 0.0, 0.66, 0.05),
        ("You've earned your seat, {you}.", 0.0, 0.74, 0.1),
        ("I saw that weld. Clean, {you}.", 0.0, 0.7, 0.05),
        ("Keep that up and I'll buy the next ale, {you}.", 0.0, 0.72, 0.1),
        ("A good dwarf, you are, {you}.", 0.0, 0.76, 0.05),
        ("That saved us a cave-in, {you}.", 0.0, 0.8, 0.2),
        ("I'd trust you with the last lamp, {you}.", 0.0, 0.78, 0.1),
        ("You've a back like a prop, {you}.", 0.0, 0.65, 0.05),
        ("The {topic_thing} looks better for you being there, {you}.", 0.0, 0.7, 0.05),
        ("That's the work I wanted, {you}.", 0.0, 0.68, 0.1),
        ("You set that timber true, {you}.", 0.0, 0.7, 0.05),
        ("I've not seen a better cut this week, {you}.", 0.0, 0.75, 0.05),
        ("Aye, you'll do, {you}.", 0.0, 0.62, 0.05),
        ("That's a braid I can respect, {you}.", 0.0, 0.7, 0.05),
        ("You kept your head when the roof talked, {you}.", 0.0, 0.8, 0.15),
        ("I'd follow you down a new adit, {you}.", 0.0, 0.78, 0.1),
        ("Good. Again like that, {you}.", 0.0, 0.64, 0.1),
        ("You've a smith's patience, {you}.", 0.0, 0.7, 0.05),
        ("That was kindly done, {you}.", 0.0, 0.72, 0.1),
        ("I sleep easier knowing you're on the watch, {you}.", 0.0, 0.76, 0.1),
        ("The hall will hear of that, {you}.", 0.0, 0.74, 0.1),
        ("You didn't flinch. I noticed, {you}.", 0.0, 0.73, 0.1),
        ("That's worth a toast, {you}.", 0.0, 0.7, 0.1),
        ("I'd put my gold on you, {you}.", 0.0, 0.72, 0.05),
        ("You've done the clan a turn, {you}.", 0.0, 0.75, 0.1),
        ("Clean work. I'll say it twice, {you}.", 0.0, 0.7, 0.05),
        ("You're the dwarf I wanted on this, {you}.", 0.0, 0.76, 0.1),
        ("That pick sings when you hold it, {you}.", 0.0, 0.68, 0.05),
        ("Aye. Stand proud of that, {you}.", 0.0, 0.7, 0.05),
        ("You've a fair mind and a fairer swing, {you}.", 0.0, 0.74, 0.05),
        ("I won't forget who stood with me, {you}.", 0.0, 0.8, 0.15),
        ("That's a holder's work, {you}.", 0.0, 0.72, 0.05),
        ("You made that look easy, {you}.", 0.0, 0.66, 0.05),
        ("I'd have you at my back in the dark, {you}.", 0.0, 0.78, 0.1),
        ("Well kept, {you}. The stores will last.", 0.0, 0.68, 0.1),
        ("You've a gift for the stubborn rock, {you}.", 0.0, 0.7, 0.05),
        ("That's the sort of dwarf this hall needs, {you}.", 0.0, 0.76, 0.1),
        ("I take my hat off, {you}. What's left of it.", 0.0, 0.7, 0.05),
        ("You did right by us, {you}.", 0.0, 0.75, 0.1),
        ("Aye. That's why they keep you, {you}.", 0.0, 0.68, 0.05),
        ("A better hand I haven't seen today, {you}.", 0.0, 0.72, 0.05),
        ("You've my thanks and my drink, {you}.", 0.0, 0.74, 0.1),
        ("Hold that standard, {you}. It's a high one.", 0.0, 0.7, 0.05),
    ],
    "SMALLTALK": [
        ("Cold down the {topic_thing} today, {you}.", 0.05, 0.15, 0.05),
        ("Long shift, {you}. Long shift.", 0.05, 0.2, 0.05),
        ("They say the {topic_thing} is running thin.", 0.05, 0.1, 0.1),
        ("Well, {you}.", 0.05, 0.1, 0.05),
        ("Ale's flat tonight, {you}.", 0.05, 0.1, 0.05),
        ("The cart's been squealing since dawn, {you}.", 0.05, 0.12, 0.05),
        ("I could eat a whole loaf, {you}.", 0.05, 0.15, 0.05),
        ("Damp in the boots again, {you}.", 0.05, 0.1, 0.05),
        ("The bell went early, I swear, {you}.", 0.05, 0.12, 0.05),
        ("Quiet watch, for once, {you}.", 0.05, 0.18, 0.05),
        ("They've moved the tool rack again, {you}.", 0.05, 0.1, 0.05),
        ("I dreamt of daylight. Horrible, {you}.", 0.05, 0.12, 0.05),
        ("The stew's the same as Tuesday, {you}.", 0.05, 0.1, 0.05),
        ("Roof's been talking in the north, {you}.", 0.08, 0.08, 0.15),
        ("Another day, another skip, {you}.", 0.05, 0.12, 0.05),
        ("I mended my glove and tore the other, {you}.", 0.05, 0.1, 0.05),
        ("The mule looked at me funny, {you}.", 0.05, 0.15, 0.05),
        ("Shift's half gone already, {you}.", 0.05, 0.1, 0.05),
        ("I'd take a sit if the bench weren't wet, {you}.", 0.05, 0.1, 0.05),
        ("Heard the pump catch. Then it settled, {you}.", 0.06, 0.1, 0.1),
        ("The {topic_thing} smells of old smoke, {you}.", 0.05, 0.1, 0.05),
        ("I left my pipe by the grate, {you}.", 0.05, 0.1, 0.05),
        ("Same faces, same stone, {you}.", 0.05, 0.12, 0.05),
        ("They've put extra lamps on the west, {you}.", 0.05, 0.12, 0.05),
        ("I could sleep standing, {you}.", 0.05, 0.1, 0.05),
        ("The ledger's a mess and it's not mine, {you}.", 0.05, 0.08, 0.05),
        ("Warm by the forge if you need it, {you}.", 0.05, 0.2, 0.05),
        ("Rain's coming down the shaft, {you}.", 0.05, 0.1, 0.1),
        ("I counted the crates twice. Still odd, {you}.", 0.05, 0.1, 0.05),
        ("The night crew left the gate ajar, {you}.", 0.08, 0.08, 0.15),
        ("My knees know the weather, {you}.", 0.05, 0.1, 0.05),
        ("There's a song stuck in my head, {you}.", 0.05, 0.15, 0.05),
        ("I saw a spark jump. Nothing caught, {you}.", 0.06, 0.1, 0.1),
        ("The bread's better when it's not yours, {you}.", 0.05, 0.12, 0.05),
        ("We're short a bucket, I think, {you}.", 0.05, 0.1, 0.05),
        ("The hall's louder than the mine tonight, {you}.", 0.05, 0.12, 0.05),
        ("I'd take a walk if it weren't all downhill, {you}.", 0.05, 0.1, 0.05),
        ("Same rumour as last week, {you}.", 0.05, 0.1, 0.05),
        ("The chalk marks have smeared, {you}.", 0.05, 0.1, 0.05),
        ("I need a new strap for this lamp, {you}.", 0.05, 0.1, 0.05),
        ("It's a day for slow work, {you}.", 0.05, 0.12, 0.05),
        ("They've put salt in the stew again, {you}.", 0.05, 0.1, 0.05),
        ("I heard laughing from the low. Good sign, {you}.", 0.05, 0.18, 0.05),
        ("The skip stopped short. Then it went, {you}.", 0.06, 0.1, 0.1),
        ("I've a stone in my boot and I'm keeping it, {you}.", 0.05, 0.12, 0.05),
        ("The {topic_thing} will be here tomorrow too, {you}.", 0.05, 0.12, 0.05),
        ("Aye. That's the size of it, {you}.", 0.05, 0.1, 0.05),
        ("I forgot what I came to say, {you}.", 0.05, 0.1, 0.05),
        ("The air's sweeter near the east, {you}.", 0.05, 0.15, 0.05),
        ("We'll be older when this seam's done, {you}.", 0.05, 0.12, 0.05),
        ("I like a quiet hour when I can get one, {you}.", 0.05, 0.18, 0.05),
        ("That's the bell. Or a dream of one, {you}.", 0.05, 0.1, 0.05),
        ("Pass the jug if there's any left, {you}.", 0.05, 0.15, 0.05),
        ("Stone underfoot. That's home, {you}.", 0.05, 0.2, 0.05),
    ],
    "THREAT": [
        ("Touch my {topic_thing} again, {you}, and I'll break your arm.", 0.85, -0.8, 0.7),
        ("It would be a shame if your {topic_thing} caught fire, {you}.", 0.6, -0.7, 0.4),
        ("One more word, {you}.", 0.7, -0.7, 0.6),
        ("I'll have your braid on my belt, {you}.", 0.8, -0.8, 0.7),
        ("Step closer and I'll put you down, {you}.", 0.82, -0.8, 0.75),
        ("Say it again and you won't finish the sentence, {you}.", 0.8, -0.78, 0.7),
        ("I'll meet you at the {topic_thing} after the bell, {you}.", 0.7, -0.7, 0.6),
        ("Keep on and I'll close that mouth, {you}.", 0.78, -0.75, 0.65),
        ("I've broken better than you, {you}.", 0.75, -0.75, 0.6),
        ("You'll leave this hall on your back, {you}.", 0.8, -0.8, 0.7),
        ("I know where you sleep, {you}.", 0.7, -0.75, 0.55),
        ("Try me. I'm asking you to, {you}.", 0.78, -0.75, 0.7),
        ("I'll take a finger for that, {you}.", 0.82, -0.8, 0.7),
        ("The dark's deep and I'm not picky, {you}.", 0.75, -0.78, 0.6),
        ("You'll answer for that with blood, {you}.", 0.85, -0.82, 0.75),
        ("I've a hammer and a mind to use it, {you}.", 0.8, -0.78, 0.7),
        ("Next time I see you alone, {you}.", 0.72, -0.75, 0.55),
        ("I'll cave that grin in, {you}.", 0.8, -0.8, 0.7),
        ("Don't make me count to one, {you}.", 0.7, -0.7, 0.65),
        ("I'll have you off this level, {you}. Walking or not.", 0.78, -0.78, 0.7),
        ("Your luck's run out with me, {you}.", 0.72, -0.72, 0.6),
        ("I'll put you in the sump, {you}.", 0.82, -0.8, 0.7),
        ("Come on then. Let's have it, {you}.", 0.75, -0.72, 0.7),
        ("I'll break the pick over you, {you}.", 0.78, -0.75, 0.65),
        ("You've one chance to walk away, {you}.", 0.7, -0.7, 0.6),
        ("I'll make an example of you, {you}.", 0.8, -0.8, 0.65),
        ("The clan will call it fair, {you}.", 0.72, -0.75, 0.55),
        ("I'll take what's yours and then some, {you}.", 0.75, -0.75, 0.6),
        ("You're a breath from the floor, {you}.", 0.78, -0.78, 0.7),
        ("I'll see you in the dark, {you}.", 0.7, -0.75, 0.55),
        ("Keep talking. I like a reason, {you}.", 0.72, -0.7, 0.6),
        ("I'll have that hand off the rail, {you}.", 0.8, -0.78, 0.7),
        ("This hall will remember what I do to you, {you}.", 0.78, -0.8, 0.65),
        ("I'll drag you to the gate myself, {you}.", 0.75, -0.75, 0.65),
        ("You've pushed far enough, {you}.", 0.7, -0.7, 0.6),
        ("I'll split that skull like a geode, {you}.", 0.85, -0.82, 0.75),
        ("Stand there and I'll move you, {you}.", 0.75, -0.72, 0.7),
        ("I'll end this tidy, {you}.", 0.8, -0.78, 0.7),
        ("Your last mistake was opening your mouth, {you}.", 0.78, -0.78, 0.65),
        ("I'll take the {topic_thing} and you with it, {you}.", 0.8, -0.8, 0.7),
        ("Back off or I won't, {you}.", 0.75, -0.72, 0.7),
        ("I've waited for an excuse, {you}.", 0.72, -0.75, 0.55),
        ("You'll wish the cave-in got you first, {you}.", 0.82, -0.82, 0.7),
        ("I'll pin you to the timber, {you}.", 0.8, -0.78, 0.7),
        ("That's twice. There won't be a third, {you}.", 0.78, -0.75, 0.7),
        ("I'll settle this the old way, {you}.", 0.75, -0.75, 0.65),
        ("Keep your distance or lose it, {you}.", 0.75, -0.72, 0.65),
        ("I'll make you eat those words, {you}.", 0.72, -0.72, 0.6),
        ("The next sound you hear is me, {you}.", 0.78, -0.78, 0.6),
        ("I'll put a stop to you, {you}.", 0.8, -0.8, 0.7),
        ("Draw if you like. I will, {you}.", 0.82, -0.8, 0.75),
        ("You're done in this hold if I say so, {you}.", 0.75, -0.78, 0.6),
        ("I'll see your blood on the stone, {you}.", 0.85, -0.85, 0.75),
        ("Last warning, {you}. Then I move.", 0.7, -0.7, 0.7),
    ],
    "ACCUSE": [
        ("Where did my {topic_thing} go, {you}?", 0.4, -0.5, 0.5),
        ("You took it, {you}. Don't play the innocent.", 0.55, -0.6, 0.5),
        ("It was you at the {topic_thing}, {you}, and we all saw it.", 0.5, -0.55, 0.4),
        ("I counted that bin before your shift, {you}.", 0.5, -0.55, 0.5),
        ("Don't look at me like that. You know, {you}.", 0.48, -0.52, 0.45),
        ("The chalk mark was mine. You moved it, {you}.", 0.5, -0.55, 0.45),
        ("You've been at my crate, {you}.", 0.52, -0.58, 0.5),
        ("I saw your boots in the dust, {you}.", 0.48, -0.52, 0.45),
        ("That gold didn't walk off on its own, {you}.", 0.55, -0.6, 0.5),
        ("You were the last one at the {topic_thing}, {you}.", 0.5, -0.52, 0.45),
        ("Own it, {you}. You took what wasn't yours.", 0.55, -0.6, 0.5),
        ("The ledger doesn't lie. You do, {you}.", 0.52, -0.58, 0.45),
        ("I found your print on the lid, {you}.", 0.5, -0.55, 0.5),
        ("You sold the same tag twice, {you}.", 0.58, -0.62, 0.5),
        ("Don't shrug. That was you, {you}.", 0.5, -0.55, 0.45),
        ("The night watch saw you, {you}.", 0.52, -0.55, 0.5),
        ("My ore. Your bag. Explain it, {you}.", 0.55, -0.6, 0.55),
        ("You've been skimming the tally, {you}.", 0.52, -0.58, 0.5),
        ("I left it here. You were here. That's the tale, {you}.", 0.5, -0.55, 0.45),
        ("You opened my locker, {you}.", 0.55, -0.6, 0.5),
        ("The scales were honest till you touched them, {you}.", 0.52, -0.58, 0.45),
        ("I want what you took, {you}. Now.", 0.58, -0.6, 0.6),
        ("You think I don't count, {you}?", 0.5, -0.55, 0.45),
        ("That lie's thinner than your braid, {you}.", 0.52, -0.58, 0.4),
        ("You were in the powder room. Unasked, {you}.", 0.55, -0.58, 0.55),
        ("The skip was full. Then you passed, {you}.", 0.5, -0.55, 0.45),
        ("I've the witness and the empty hook, {you}.", 0.55, -0.6, 0.5),
        ("You swapped the good coal for slack, {you}.", 0.52, -0.58, 0.5),
        ("Don't hide your hands, {you}.", 0.48, -0.52, 0.45),
        ("You ate from the common pot and left the bill, {you}.", 0.5, -0.55, 0.4),
        ("I heard you at the latch, {you}.", 0.5, -0.55, 0.5),
        ("The mark's been scraped. Your knife, {you}.", 0.55, -0.6, 0.5),
        ("You took my share and called it a mistake, {you}.", 0.55, -0.6, 0.5),
        ("Look me in the eye and deny it, {you}.", 0.52, -0.58, 0.5),
        ("The {topic_thing} was watched. You weren't clean, {you}.", 0.52, -0.55, 0.45),
        ("You've done this before. I remember, {you}.", 0.55, -0.6, 0.45),
        ("My gloves were on the peg. They're on you, {you}.", 0.5, -0.55, 0.45),
        ("You signed a name that isn't yours, {you}.", 0.58, -0.62, 0.55),
        ("The ale's down and your cup's wet, {you}.", 0.48, -0.5, 0.4),
        ("I smelled your smoke in my stall, {you}.", 0.5, -0.52, 0.4),
        ("You cut the fuse short on purpose, {you}.", 0.6, -0.65, 0.55),
        ("That wasn't chance. That was you, {you}.", 0.55, -0.6, 0.5),
        ("You pointed the blame the other way, {you}.", 0.52, -0.58, 0.45),
        ("I've the chit. You've the face. Enough, {you}.", 0.52, -0.55, 0.5),
        ("You walked off with the tongs, {you}.", 0.5, -0.55, 0.45),
        ("The sample's light. Your pocket isn't, {you}.", 0.55, -0.6, 0.5),
        ("You left the cage unlatched. After, {you}.", 0.52, -0.55, 0.5),
        ("Don't waste my time with a story, {you}.", 0.5, -0.55, 0.45),
        ("You were seen and you were quick, {you}.", 0.52, -0.58, 0.5),
        ("I want the truth or I want it back, {you}.", 0.55, -0.6, 0.55),
        ("The hall will hear who did this, {you}.", 0.52, -0.58, 0.5),
        ("You thought I wouldn't notice, {you}.", 0.5, -0.55, 0.4),
        ("That's my mark under the dirt, {you}. Yours on top.", 0.55, -0.58, 0.5),
        ("Say you didn't. I'll call you a liar, {you}.", 0.58, -0.62, 0.5),
    ],
    "APOLOGY": [
        ("I was out of order, {you}. Sorry.", 0.0, 0.5, 0.3),
        ("Sorry, {you}.", 0.0, 0.45, 0.3),
        ("Let it lie, {you}. I spoke in temper.", 0.05, 0.5, 0.3),
        ("I was wrong, {you}. That's the whole of it.", 0.0, 0.5, 0.3),
        ("I shouldn't have said it, {you}.", 0.0, 0.48, 0.3),
        ("My mouth ran ahead of me, {you}.", 0.05, 0.45, 0.25),
        ("I take it back, {you}.", 0.0, 0.5, 0.3),
        ("That was poorly done. I'm sorry, {you}.", 0.0, 0.52, 0.3),
        ("I owe you a word and here it is: sorry, {you}.", 0.0, 0.5, 0.3),
        ("I was hot. I'm cooler now, {you}.", 0.05, 0.48, 0.25),
        ("Forgive a fool, {you}.", 0.0, 0.5, 0.3),
        ("I crossed a line. I know it, {you}.", 0.0, 0.5, 0.3),
        ("It won't happen again if I can help it, {you}.", 0.0, 0.52, 0.3),
        ("I spoke like a child, {you}.", 0.05, 0.45, 0.25),
        ("You've the right of it. I haven't, {you}.", 0.0, 0.5, 0.3),
        ("I'll make it good, {you}.", 0.0, 0.52, 0.35),
        ("I was a brute. Sorry, {you}.", 0.0, 0.48, 0.3),
        ("Don't hold it longer than you must, {you}.", 0.05, 0.45, 0.25),
        ("I see what I did, {you}.", 0.0, 0.5, 0.3),
        ("That was my temper, not my mind, {you}.", 0.05, 0.48, 0.25),
        ("I'm ashamed of that, {you}.", 0.0, 0.5, 0.3),
        ("Give me a chance to put it right, {you}.", 0.0, 0.52, 0.35),
        ("I was out of turn, {you}.", 0.0, 0.48, 0.3),
        ("Sorry doesn't mend it. I'll try anyway, {you}.", 0.05, 0.5, 0.3),
        ("I heard myself and I hated it, {you}.", 0.0, 0.5, 0.25),
        ("You've been fairer than I was, {you}.", 0.0, 0.52, 0.25),
        ("I bit when I should have asked, {you}.", 0.05, 0.48, 0.3),
        ("Let me buy the next jug, {you}.", 0.0, 0.5, 0.25),
        ("I was a fool at the {topic_thing}, {you}.", 0.05, 0.48, 0.3),
        ("I won't dress it up. I was wrong, {you}.", 0.0, 0.5, 0.3),
        ("Keep your anger if you like. I've said mine, {you}.", 0.05, 0.4, 0.25),
        ("I lost the thread and hit you with it, {you}.", 0.05, 0.45, 0.25),
        ("That wasn't the dwarf I mean to be, {you}.", 0.0, 0.5, 0.3),
        ("I'll stand the blame, {you}.", 0.0, 0.5, 0.3),
        ("I should have walked away, {you}.", 0.0, 0.48, 0.25),
        ("You've my apology, plain, {you}.", 0.0, 0.5, 0.3),
        ("I was loud and I was wrong, {you}.", 0.0, 0.48, 0.3),
        ("Don't make me say it twice. I'm sorry, {you}.", 0.05, 0.45, 0.3),
        ("I owe the hall better, and you, {you}.", 0.0, 0.5, 0.3),
        ("My pride did that. Not my sense, {you}.", 0.05, 0.45, 0.25),
        ("I'll work it off if words won't do, {you}.", 0.0, 0.52, 0.35),
        ("I was short with you. Too short, {you}.", 0.0, 0.48, 0.3),
        ("Take the sorry and the next shift, {you}.", 0.0, 0.5, 0.3),
        ("I see the hurt. I put it there, {you}.", 0.0, 0.5, 0.3),
        ("No excuse. Only this: sorry, {you}.", 0.0, 0.52, 0.3),
        ("I spoke in the heat. The stone's cooler, {you}.", 0.05, 0.48, 0.25),
        ("You didn't deserve that from me, {you}.", 0.0, 0.52, 0.3),
        ("I'll keep my tongue leashed, {you}.", 0.05, 0.48, 0.3),
        ("That was ugly of me, {you}.", 0.0, 0.48, 0.3),
        ("I ask pardon, {you}.", 0.0, 0.5, 0.3),
        ("I was the smaller dwarf today, {you}.", 0.0, 0.5, 0.25),
        ("If you'll have peace, I'll keep it, {you}.", 0.0, 0.52, 0.3),
        ("I came to say it to your face, {you}.", 0.0, 0.5, 0.35),
        ("Sorry, {you}. And I mean the word.", 0.0, 0.52, 0.3),
    ],
    "WARNING": [
        ("Behind you, {you}, a creeper!", 0.1, 0.3, 0.95),
        ("Something's at the gate. Arm yourselves.", 0.1, 0.2, 0.9),
        ("Careful, {you}.", 0.05, 0.3, 0.7),
        ("The roof's talking, {you}. Move.", 0.1, 0.2, 0.85),
        ("Blackdamp. Get out of the low, {you}.", 0.1, 0.15, 0.9),
        ("Watch the skip, {you}!", 0.1, 0.2, 0.85),
        ("Don't step there. The plank's gone, {you}.", 0.08, 0.25, 0.8),
        ("Lamp's dying. Back to the junction, {you}.", 0.08, 0.2, 0.75),
        ("I hear water where it shouldn't be, {you}.", 0.1, 0.15, 0.8),
        ("Hands off that fuse, {you}. It's live.", 0.1, 0.2, 0.9),
        ("The cage is coming. Stand clear, {you}.", 0.08, 0.2, 0.8),
        ("Something moved in the dark, {you}.", 0.1, 0.15, 0.85),
        ("Don't breathe that. Cover your mouth, {you}.", 0.1, 0.15, 0.85),
        ("The prop's leaning. Out, {you}.", 0.1, 0.2, 0.85),
        ("Fire in the shavings, {you}!", 0.1, 0.2, 0.95),
        ("Mind the rail, {you}. It's slick.", 0.05, 0.25, 0.7),
        ("I wouldn't go that way, {you}.", 0.08, 0.2, 0.7),
        ("The bell's for danger, not dinner, {you}.", 0.1, 0.2, 0.8),
        ("Stay off the face till it settles, {you}.", 0.08, 0.2, 0.75),
        ("Look up. That's a new crack, {you}.", 0.1, 0.15, 0.8),
        ("The mule's spooked. So should you be, {you}.", 0.08, 0.15, 0.7),
        ("Don't strike a light in here, {you}.", 0.1, 0.2, 0.9),
        ("The gate's the thin place. Keep an axe, {you}.", 0.08, 0.2, 0.75),
        ("Hear that rumble? That's not carts, {you}.", 0.1, 0.15, 0.85),
        ("Your lamp, {you}. It's going.", 0.08, 0.2, 0.75),
        ("The ladder's split. Use the other, {you}.", 0.08, 0.25, 0.75),
        ("I smell powder. Too much, {you}.", 0.1, 0.15, 0.85),
        ("Back. The skip's running free, {you}.", 0.1, 0.2, 0.9),
        ("Don't stand under that, {you}.", 0.08, 0.25, 0.8),
        ("The air's wrong. Tell the others, {you}.", 0.1, 0.15, 0.85),
        ("Creeper. Left side, {you}!", 0.1, 0.25, 0.95),
        ("The chain's singing. Get off it, {you}.", 0.1, 0.2, 0.85),
        ("That's a dead end now. Turn, {you}.", 0.08, 0.2, 0.75),
        ("Watch your head on the low, {you}.", 0.05, 0.25, 0.7),
        ("The floor dropped. Slow, {you}.", 0.1, 0.15, 0.8),
        ("Don't drink from that trough, {you}.", 0.08, 0.2, 0.7),
        ("I heard a hiss. Not the pump, {you}.", 0.1, 0.15, 0.85),
        ("Keep your voice down. Something's listening, {you}.", 0.1, 0.1, 0.75),
        ("The timber's groaning. Out, {you}.", 0.1, 0.15, 0.85),
        ("Your boot's on the charge, {you}!", 0.1, 0.2, 0.95),
        ("Don't go alone down there, {you}.", 0.08, 0.2, 0.7),
        ("The winch is frayed. Not that one, {you}.", 0.08, 0.2, 0.75),
        ("Smoke. I don't like it, {you}.", 0.1, 0.15, 0.8),
        ("Stay in the light, {you}.", 0.08, 0.2, 0.7),
        ("That's a hole. A new one, {you}.", 0.1, 0.15, 0.8),
        ("The {topic_thing} isn't safe. Not tonight, {you}.", 0.1, 0.15, 0.8),
        ("Hold. I heard stone fall, {you}.", 0.1, 0.15, 0.85),
        ("Your sleeve's near the brazier, {you}.", 0.08, 0.25, 0.75),
        ("Don't run. Walk, and quick, {you}.", 0.08, 0.2, 0.75),
        ("I've a bad feeling about this drift, {you}.", 0.08, 0.15, 0.7),
        ("The water's rising. Up, {you}.", 0.1, 0.15, 0.85),
        ("Look behind. Now, {you}.", 0.1, 0.2, 0.9),
        ("That spark's too close to the oil, {you}.", 0.1, 0.2, 0.9),
        ("If you value your braid, move, {you}.", 0.1, 0.2, 0.8),
    ],
    "GIFT": [
        ("Here, {you}. Take it.", 0.0, 0.7, 0.2),
        ("You've earned this, {you}.", 0.0, 0.75, 0.2),
        ("For you, {you}. Don't make a fuss.", 0.0, 0.68, 0.15),
        ("Take it before I change my mind, {you}.", 0.0, 0.65, 0.2),
        ("A share for a good hand, {you}.", 0.0, 0.72, 0.15),
        ("I brought you this, {you}.", 0.0, 0.7, 0.2),
        ("It's yours. I mean it, {you}.", 0.0, 0.74, 0.2),
        ("Put that in your pack, {you}.", 0.0, 0.68, 0.15),
        ("You stood with me. This is for that, {you}.", 0.0, 0.76, 0.2),
        ("A little gold. A little thanks, {you}.", 0.0, 0.7, 0.15),
        ("I don't need it. You might, {you}.", 0.0, 0.68, 0.15),
        ("From my crate to yours, {you}.", 0.0, 0.7, 0.2),
        ("Take the extra loaf, {you}.", 0.0, 0.66, 0.15),
        ("This one's on me, {you}.", 0.0, 0.7, 0.15),
        ("You've been short. I'm not, {you}.", 0.0, 0.72, 0.2),
        ("A drink and a bit of ore, {you}.", 0.0, 0.68, 0.15),
        ("Hold out your hand, {you}.", 0.0, 0.65, 0.2),
        ("I set this aside for you, {you}.", 0.0, 0.74, 0.2),
        ("Don't thank me. Just take it, {you}.", 0.0, 0.68, 0.15),
        ("For the watch you stood, {you}.", 0.0, 0.75, 0.2),
        ("A smith's gift. Use it, {you}.", 0.0, 0.7, 0.15),
        ("The clan can spare it. You can use it, {you}.", 0.0, 0.7, 0.15),
        ("I owe you. This starts the paying, {you}.", 0.0, 0.72, 0.25),
        ("Warm gloves. Yours, {you}.", 0.0, 0.68, 0.15),
        ("A spare pick. The good one, {you}.", 0.0, 0.7, 0.15),
        ("Take the gold and say nothing, {you}.", 0.0, 0.66, 0.2),
        ("You've earned a seat and a share, {you}.", 0.0, 0.74, 0.2),
        ("This came off my pile, {you}.", 0.0, 0.68, 0.15),
        ("I want you to have it, {you}.", 0.0, 0.72, 0.2),
        ("A bottle from the back shelf, {you}.", 0.0, 0.7, 0.15),
        ("For your trouble at the {topic_thing}, {you}.", 0.0, 0.7, 0.2),
        ("You're lighter than you should be. Here, {you}.", 0.0, 0.7, 0.2),
        ("A token. Not a bribe, {you}.", 0.0, 0.65, 0.15),
        ("Catch. That's yours, {you}.", 0.0, 0.68, 0.2),
        ("I made extra. You get the extra, {you}.", 0.0, 0.7, 0.15),
        ("Put it toward your next tool, {you}.", 0.0, 0.7, 0.15),
        ("The hall looks after its own, {you}.", 0.0, 0.72, 0.15),
        ("A bit of steel. Keep it sharp, {you}.", 0.0, 0.7, 0.15),
        ("You missed a meal. I didn't, {you}.", 0.0, 0.68, 0.2),
        ("This is for the cut you took, {you}.", 0.0, 0.74, 0.2),
        ("I've more than I need. You don't, {you}.", 0.0, 0.7, 0.15),
        ("A share of the find, {you}.", 0.0, 0.75, 0.2),
        ("Take it with a whole hand, {you}.", 0.0, 0.68, 0.15),
        ("I wrapped it so it wouldn't show, {you}.", 0.0, 0.66, 0.2),
        ("For later, when the stores run down, {you}.", 0.0, 0.7, 0.15),
        ("You did the work. You take the pay, {you}.", 0.0, 0.74, 0.2),
        ("A kindness. Don't look at it too hard, {you}.", 0.0, 0.68, 0.15),
        ("Here. Before someone else claims it, {you}.", 0.0, 0.7, 0.2),
        ("It's not much. It's honest, {you}.", 0.0, 0.7, 0.15),
        ("I want you fed, {you}.", 0.0, 0.72, 0.2),
        ("A gift from the forge, {you}.", 0.0, 0.7, 0.15),
        ("Take the lamp. Mine still burns, {you}.", 0.0, 0.7, 0.2),
        ("This settles a little of what I owe, {you}.", 0.0, 0.72, 0.25),
        ("Yours. No speech. Just yours, {you}.", 0.0, 0.7, 0.15),
    ],

    # -- v1 acts ---------------------------------------------------------------
    "REQUEST": [
        ("Spare me {item}, {you}?", 0.05, 0.2, 0.5),
        ("Could you see to {item} for me, {you}?", 0.05, 0.25, 0.5),
        ("I'd take it kindly, {you}, if you'd sort {item}.", 0.05, 0.3, 0.45),
        ("Have you a moment for {item}, {you}?", 0.05, 0.22, 0.45),
        ("I need {item}, {you}. If you can.", 0.05, 0.2, 0.5),
        ("Would you fetch {item}, {you}?", 0.05, 0.25, 0.5),
        ("I'm short on {item}, {you}. A hand?", 0.05, 0.22, 0.5),
        ("If you're going that way, {item}, {you}?", 0.05, 0.2, 0.4),
        ("I wouldn't ask if I didn't need {item}, {you}.", 0.05, 0.25, 0.5),
        ("Can you spare {item} before the bell, {you}?", 0.05, 0.22, 0.55),
        ("A favour: {item}, {you}.", 0.05, 0.2, 0.45),
        ("Will you see {item} done, {you}?", 0.05, 0.25, 0.5),
        ("I've no one else to ask for {item}, {you}.", 0.05, 0.28, 0.5),
        ("When you've a gap, {item}, {you}?", 0.05, 0.2, 0.4),
        ("I'd be in your debt for {item}, {you}.", 0.05, 0.3, 0.45),
        ("Mind getting {item} while you're up, {you}?", 0.05, 0.2, 0.4),
        ("The stores want {item}, {you}. Can you?", 0.05, 0.22, 0.5),
        ("Help me with {item}, {you}. Please.", 0.05, 0.28, 0.5),
        ("Is {item} a thing you can do, {you}?", 0.05, 0.2, 0.4),
        ("I ask it plain: {item}, {you}.", 0.05, 0.22, 0.5),
        ("If it isn't a trouble, {item}, {you}.", 0.05, 0.2, 0.4),
        ("The shift's against me. {item}, {you}?", 0.05, 0.22, 0.5),
        ("You know the way. {item} would help, {you}.", 0.05, 0.25, 0.45),
        ("I'm asking as a clanmate, {you}: {item}.", 0.05, 0.28, 0.5),
        ("Could you take {item} off my list, {you}?", 0.05, 0.22, 0.45),
        ("I need a back for {item}, {you}.", 0.05, 0.25, 0.5),
        ("Don't say no till you've heard: {item}, {you}.", 0.05, 0.22, 0.5),
        ("A small thing. {item}, {you}.", 0.05, 0.2, 0.4),
        ("The {topic_thing} can wait. {item} can't, {you}.", 0.08, 0.2, 0.55),
        ("Will you stand this for me, {you}? {item}.", 0.05, 0.28, 0.5),
        ("I've asked worse. {item}, {you}.", 0.05, 0.2, 0.45),
        ("If you've the strength, {item}, {you}.", 0.05, 0.22, 0.45),
        ("I wouldn't send a child. I'm sending you, {you}: {item}.", 0.05, 0.25, 0.5),
        ("One job. {item}, {you}.", 0.05, 0.2, 0.5),
        ("The hall asked me. I'm asking you, {you}: {item}.", 0.05, 0.25, 0.5),
        ("Can you be quick about {item}, {you}?", 0.05, 0.22, 0.55),
        ("It's {item} or we stall, {you}.", 0.08, 0.2, 0.55),
        ("I'd take {item} off you if I could, {you}.", 0.05, 0.22, 0.4),
        ("Do an old dwarf a turn: {item}, {you}.", 0.05, 0.28, 0.45),
        ("You're nearer. {item}, {you}?", 0.05, 0.2, 0.45),
        ("I trust your hands with {item}, {you}.", 0.05, 0.3, 0.45),
        ("It's a request, not an order: {item}, {you}.", 0.05, 0.25, 0.4),
        ("When the skip's free, {item}, {you}.", 0.05, 0.2, 0.4),
        ("I need {item} before the next blast, {you}.", 0.08, 0.2, 0.6),
        ("Say aye and I'll not forget {item}, {you}.", 0.05, 0.28, 0.45),
        ("The work's honest. {item}, {you}.", 0.05, 0.22, 0.45),
        ("I've no pride left about {item}, {you}.", 0.05, 0.25, 0.5),
        ("Would it cost you much to do {item}, {you}?", 0.05, 0.2, 0.4),
        ("I'm asking once: {item}, {you}.", 0.05, 0.22, 0.5),
        ("If you say no I'll ask another. {item}, {you}.", 0.05, 0.2, 0.4),
        ("A hand with {item} and I'll owe you ale, {you}.", 0.05, 0.28, 0.45),
        ("The face is waiting on {item}, {you}.", 0.08, 0.2, 0.55),
        ("You'd be doing me a kindness: {item}, {you}.", 0.05, 0.3, 0.45),
        ("Please, {you}. {item}.", 0.05, 0.28, 0.5),
    ],
    "COMMAND": [
        ("{item}, {you}. Now.", 0.35, -0.1, 0.75),
        ("You'll see to {item} for me, {you}.", 0.3, -0.05, 0.7),
        ("Sort {item}, {you}, and don't argue.", 0.4, -0.15, 0.75),
        ("Get {item} done, {you}.", 0.35, -0.1, 0.7),
        ("I said {item}, {you}.", 0.38, -0.12, 0.75),
        ("Move. {item}, {you}.", 0.4, -0.15, 0.8),
        ("That's an order: {item}, {you}.", 0.4, -0.15, 0.75),
        ("You heard me. {item}, {you}.", 0.38, -0.12, 0.75),
        ("No debate. {item}, {you}.", 0.4, -0.15, 0.75),
        ("See {item} finished before the bell, {you}.", 0.35, -0.1, 0.75),
        ("Put your back into {item}, {you}.", 0.35, -0.1, 0.7),
        ("I want {item} and I want it now, {you}.", 0.4, -0.15, 0.8),
        ("Do {item}. Then you can talk, {you}.", 0.35, -0.12, 0.7),
        ("You're on {item}, {you}.", 0.32, -0.08, 0.7),
        ("Get to {item}, {you}.", 0.35, -0.1, 0.75),
        ("I didn't ask. {item}, {you}.", 0.4, -0.18, 0.75),
        ("The hall wants {item}. You do it, {you}.", 0.35, -0.1, 0.7),
        ("Stop standing. {item}, {you}.", 0.38, -0.12, 0.75),
        ("That's your job: {item}, {you}.", 0.32, -0.08, 0.7),
        ("I've said it. {item}, {you}.", 0.35, -0.1, 0.7),
        ("Leave the rest. {item} first, {you}.", 0.35, -0.1, 0.75),
        ("You'll not sit till {item} is done, {you}.", 0.4, -0.15, 0.75),
        ("On your feet. {item}, {you}.", 0.38, -0.12, 0.75),
        ("I want eyes on {item}, {you}. Yours.", 0.35, -0.1, 0.7),
        ("Do as you're told: {item}, {you}.", 0.4, -0.18, 0.75),
        ("The skip's waiting on {item}, {you}.", 0.35, -0.08, 0.75),
        ("Make {item} happen, {you}.", 0.35, -0.1, 0.7),
        ("That's enough talk. {item}, {you}.", 0.38, -0.12, 0.75),
        ("You're assigned {item}, {you}.", 0.3, -0.05, 0.7),
        ("I expect {item} before I eat, {you}.", 0.35, -0.1, 0.75),
        ("Get it. {item}. Go, {you}.", 0.4, -0.15, 0.8),
        ("Don't come back without {item}, {you}.", 0.4, -0.15, 0.75),
        ("I'm not asking twice: {item}, {you}.", 0.4, -0.18, 0.8),
        ("The face needs {item}. You, {you}.", 0.35, -0.1, 0.75),
        ("Drop what you're doing. {item}, {you}.", 0.38, -0.12, 0.8),
        ("I put you on {item}, {you}.", 0.32, -0.08, 0.7),
        ("See it through: {item}, {you}.", 0.32, -0.08, 0.7),
        ("No shortcuts. {item} proper, {you}.", 0.35, -0.1, 0.7),
        ("You've the order. {item}, {you}.", 0.35, -0.1, 0.7),
        ("Be about {item}, {you}.", 0.32, -0.08, 0.7),
        ("I want {item} stacked and counted, {you}.", 0.35, -0.1, 0.7),
        ("The work is {item}. Start, {you}.", 0.35, -0.1, 0.75),
        ("You'll handle {item} or I'll find who will, {you}.", 0.4, -0.18, 0.75),
        ("That's a command, {you}: {item}.", 0.4, -0.15, 0.75),
        ("Look alive. {item}, {you}.", 0.38, -0.12, 0.75),
        ("I need {item} yesterday, {you}.", 0.4, -0.15, 0.8),
        ("Get {item} off my mind, {you}.", 0.35, -0.1, 0.7),
        ("You're wasting light. {item}, {you}.", 0.38, -0.12, 0.75),
        ("Do {item} like you mean to stay, {you}.", 0.35, -0.12, 0.7),
        ("I gave the word. {item}, {you}.", 0.35, -0.1, 0.7),
        ("The {topic_thing} can wait. {item} can't, {you}.", 0.35, -0.1, 0.75),
        ("Move your feet. {item}, {you}.", 0.4, -0.15, 0.8),
        ("I want {item} done right, {you}.", 0.32, -0.08, 0.7),
        ("That's all. {item}. Go, {you}.", 0.38, -0.12, 0.75),
    ],
    "OFFER": [
        ("{gold} gold for {item}, {you}.", 0.05, 0.35, 0.5),
        ("There's {gold} gold in it if you sort {item}, {you}.", 0.05, 0.4, 0.5),
        ("Name {item} done and take {gold} gold, {you}.", 0.05, 0.4, 0.45),
        ("I'll pay {gold} gold for {item}, {you}.", 0.05, 0.38, 0.5),
        ("{gold} gold. {item}. Fair, {you}?", 0.05, 0.35, 0.45),
        ("Do {item} and the gold's yours: {gold}, {you}.", 0.05, 0.4, 0.5),
        ("I'm buying {item} at {gold} gold, {you}.", 0.05, 0.35, 0.5),
        ("A price: {gold} gold, and {item} done, {you}.", 0.05, 0.38, 0.5),
        ("Take {gold} gold and see to {item}, {you}.", 0.05, 0.4, 0.5),
        ("It's worth {gold} gold to me, {you}. {item}.", 0.05, 0.38, 0.45),
        ("I put {gold} gold on {item}, {you}.", 0.05, 0.36, 0.5),
        ("You want coin. I want {item}. {gold} gold, {you}.", 0.05, 0.38, 0.5),
        ("{gold} gold up front if you start {item}, {you}.", 0.05, 0.4, 0.5),
        ("I'll not haggle yet: {gold} gold for {item}, {you}.", 0.05, 0.35, 0.45),
        ("An honest offer. {gold} gold. {item}, {you}.", 0.05, 0.4, 0.45),
        ("The purse says {gold} gold for {item}, {you}.", 0.05, 0.35, 0.5),
        ("Work {item} and I'll count {gold} gold, {you}.", 0.05, 0.38, 0.5),
        ("That's my bid: {gold} gold, {you}.", 0.05, 0.35, 0.5),
        ("{item} for {gold} gold and a drink after, {you}.", 0.05, 0.4, 0.4),
        ("I'm good for {gold} gold, {you}. {item}.", 0.05, 0.38, 0.45),
        ("Say aye and I'll put {gold} gold in your hand, {you}.", 0.05, 0.4, 0.5),
        ("The job is {item}. The pay is {gold} gold, {you}.", 0.05, 0.38, 0.5),
        ("I can do {gold} gold. That's the offer, {you}.", 0.05, 0.35, 0.45),
        ("{gold} gold isn't nothing. {item}, {you}.", 0.05, 0.36, 0.5),
        ("I'll stand the gold if you stand the work: {item}, {you}.", 0.05, 0.4, 0.5),
        ("A clean deal. {gold} gold for {item}, {you}.", 0.05, 0.4, 0.45),
        ("You look like you need coin. {gold} gold, {item}, {you}.", 0.05, 0.35, 0.45),
        ("I'll pay on the nail: {gold} gold, {you}.", 0.05, 0.38, 0.5),
        ("{item} done, {gold} gold paid, {you}.", 0.05, 0.38, 0.5),
        ("The hall can spare {gold} gold for {item}, {you}.", 0.05, 0.36, 0.45),
        ("My offer's {gold} gold, {you}. Take it or leave it.", 0.08, 0.3, 0.5),
        ("I won't insult you with less than {gold} gold, {you}.", 0.05, 0.4, 0.45),
        ("{gold} gold for a dwarf who can do {item}, {you}.", 0.05, 0.38, 0.5),
        ("There's coin in this: {gold} gold, {you}.", 0.05, 0.36, 0.5),
        ("I'll write {gold} gold on the chit for {item}, {you}.", 0.05, 0.38, 0.5),
        ("Do we have a bargain? {gold} gold, {item}, {you}.", 0.05, 0.4, 0.5),
        ("I came with {gold} gold and a need for {item}, {you}.", 0.05, 0.38, 0.5),
        ("Pay's {gold} gold. Work's {item}, {you}.", 0.05, 0.36, 0.5),
        ("I'll match a fair price: {gold} gold, {you}.", 0.05, 0.38, 0.45),
        ("{gold} gold now, {item} after, {you}.", 0.05, 0.4, 0.5),
        ("The offer stands: {gold} gold, {you}.", 0.05, 0.35, 0.45),
        ("You'd be a fool to skip {gold} gold, {you}.", 0.08, 0.32, 0.45),
        ("I need {item} enough to pay {gold} gold, {you}.", 0.05, 0.38, 0.5),
        ("Here's the number: {gold} gold, {you}.", 0.05, 0.35, 0.5),
        ("A day's pay for {item}: {gold} gold, {you}.", 0.05, 0.38, 0.45),
        ("I'll not go higher yet. {gold} gold, {you}.", 0.08, 0.3, 0.5),
        ("Coin on the table. {gold} gold for {item}, {you}.", 0.05, 0.4, 0.5),
        ("If {gold} gold suits, start {item}, {you}.", 0.05, 0.38, 0.5),
        ("That's gold for work: {gold}, {you}.", 0.05, 0.36, 0.5),
        ("I offer {gold} gold and I keep my word, {you}.", 0.05, 0.4, 0.45),
        ("{item} is worth {gold} gold to this hold, {you}.", 0.05, 0.38, 0.45),
        ("Take the {gold} gold and be about {item}, {you}.", 0.05, 0.38, 0.5),
        ("A fair purse: {gold} gold, {you}.", 0.05, 0.36, 0.45),
        ("We can do this easy. {gold} gold. {item}, {you}.", 0.05, 0.4, 0.45),
    ],
    "RETORT": [
        ("Say that again, {you}, and see what it earns you.", 0.7, -0.7, 0.6),
        ("That's rich, coming from you, {you}.", 0.6, -0.65, 0.3),
        ("Aye? And what have you ever hauled out of that {topic_thing}, {you}?", 0.55, -0.6, 0.3),
        ("Mind your own beard, {you}.", 0.5, -0.55, 0.4),
        ("You've a mouth on you, {you}. Put it to work on the face.", 0.55, -0.6, 0.35),
        ("Save it, {you}. I've heard better from a mule.", 0.58, -0.62, 0.3),
        ("Talk like that at the {topic_thing} and see who stands with you, {you}.", 0.6, -0.65, 0.4),
        ("Ha. From you, {you}? That's a laugh.", 0.5, -0.55, 0.25),
        ("Keep walking, {you}. I've no time for that.", 0.52, -0.55, 0.35),
        ("You first, {you}. Then we'll talk.", 0.58, -0.6, 0.4),
        ("Aye, and you're the one to say it, {you}.", 0.55, -0.6, 0.3),
        ("I've had enough of your noise, {you}.", 0.62, -0.65, 0.45),
        ("Watch your tongue in this hall, {you}.", 0.55, -0.58, 0.4),
        ("Is that the best you've got, {you}?", 0.5, -0.55, 0.25),
        ("Come off it, {you}. You'd not last a shift.", 0.58, -0.62, 0.3),
        ("Don't start with me, {you}. Not today.", 0.6, -0.62, 0.45),
        ("You've a lot of breath for a little dwarf, {you}.", 0.55, -0.6, 0.3),
        ("I heard you. I'm unimpressed, {you}.", 0.52, -0.58, 0.25),
        ("Put that back in your mouth, {you}.", 0.6, -0.62, 0.4),
        ("You'd not say that with a pick in your hand, {you}.", 0.58, -0.6, 0.35),
        ("Funny. I don't remember asking, {you}.", 0.52, -0.55, 0.25),
        ("That's your tale. I've mine, {you}.", 0.5, -0.55, 0.3),
        ("Go tell the wall. It cares more, {you}.", 0.55, -0.6, 0.3),
        ("You talk like the gold's already yours, {you}.", 0.55, -0.6, 0.3),
        ("I've taken worse from better, {you}.", 0.58, -0.62, 0.35),
        ("Keep your sermon, {you}.", 0.52, -0.58, 0.3),
        ("If that's a threat, it's a poor one, {you}.", 0.6, -0.62, 0.4),
        ("Look somewhere else when you lie, {you}.", 0.58, -0.62, 0.35),
        ("I was here. You weren't. Sit down, {you}.", 0.6, -0.65, 0.4),
        ("That might work on a child, {you}.", 0.55, -0.6, 0.3),
        ("You've said it. Now live with the answer, {you}.", 0.58, -0.6, 0.4),
        ("Don't wag that finger at me, {you}.", 0.55, -0.58, 0.4),
        ("I know what you are when no one's looking, {you}.", 0.62, -0.68, 0.35),
        ("Spare me the speech, {you}.", 0.52, -0.55, 0.3),
        ("You want a fight or a conversation, {you}?", 0.6, -0.6, 0.45),
        ("I'm still standing. Try harder, {you}.", 0.58, -0.62, 0.4),
        ("That's a lot of words for no ore, {you}.", 0.55, -0.6, 0.3),
        ("I don't take lessons from a cracked pot, {you}.", 0.6, -0.65, 0.35),
        ("Aye, shout. The stone's used to it, {you}.", 0.52, -0.55, 0.3),
        ("You missed. Try the truth next, {you}.", 0.55, -0.6, 0.35),
        ("I've a shift. You've a tantrum, {you}.", 0.55, -0.58, 0.35),
        ("Come say it where the lamps are, {you}.", 0.62, -0.65, 0.45),
        ("You're brave with an audience, {you}.", 0.58, -0.62, 0.35),
        ("I heard worse from the last cave-in, {you}.", 0.55, -0.58, 0.25),
        ("Don't make me repeat myself, {you}.", 0.6, -0.6, 0.45),
        ("That mouth will buy you a broken nose, {you}.", 0.65, -0.68, 0.5),
        ("You've picked the wrong dwarf, {you}.", 0.62, -0.65, 0.45),
        ("I was civil. You're making it hard, {you}.", 0.55, -0.58, 0.35),
        ("Go on. Dig yourself deeper, {you}.", 0.55, -0.6, 0.3),
        ("I don't owe you a smile, {you}.", 0.5, -0.55, 0.25),
        ("You talk. I work. Guess who lasts, {you}.", 0.58, -0.6, 0.3),
        ("That's enough out of you, {you}.", 0.6, -0.62, 0.45),
        ("I'll remember that tone, {you}.", 0.58, -0.62, 0.35),
        ("Find another hall for that noise, {you}.", 0.55, -0.6, 0.4),
    ],
    "DEMAND": [
        ("You'll take that back, {you}. Now.", 0.45, -0.35, 0.8),
        ("Say sorry for that, {you}, in front of everyone.", 0.4, -0.3, 0.75),
        ("I'll have an apology out of you, {you}.", 0.45, -0.4, 0.7),
        ("You'll own that, {you}, or we'll have words.", 0.5, -0.4, 0.75),
        ("Take it back, {you}. I won't ask twice.", 0.48, -0.38, 0.8),
        ("I want a sorry and I want it loud, {you}.", 0.48, -0.38, 0.8),
        ("You'll stand there and say it, {you}.", 0.45, -0.35, 0.75),
        ("Apologise. That's the whole order, {you}.", 0.45, -0.35, 0.75),
        ("I won't move till you take it back, {you}.", 0.48, -0.38, 0.75),
        ("Say you were wrong, {you}.", 0.42, -0.32, 0.75),
        ("The hall heard you. The hall will hear the rest, {you}.", 0.45, -0.35, 0.7),
        ("You'll not walk away from that, {you}.", 0.48, -0.4, 0.75),
        ("I demand the word. Sorry, {you}.", 0.45, -0.35, 0.8),
        ("On your pride then: take it back, {you}.", 0.48, -0.38, 0.75),
        ("You've a debt in words, {you}. Pay it.", 0.45, -0.35, 0.7),
        ("Look at them and say it, {you}.", 0.48, -0.38, 0.8),
        ("I want it clean. An apology, {you}.", 0.42, -0.32, 0.75),
        ("Don't make me fetch the chief, {you}.", 0.45, -0.35, 0.7),
        ("You'll unsay that or we'll settle it, {you}.", 0.5, -0.42, 0.8),
        ("That's not ending like this, {you}.", 0.45, -0.35, 0.75),
        ("Give me the sorry. Then we eat, {you}.", 0.4, -0.3, 0.7),
        ("I asked polite. Now I'm not, {you}.", 0.48, -0.4, 0.8),
        ("You'll not hide behind a shrug, {you}.", 0.45, -0.35, 0.75),
        ("Say it. I have time, {you}.", 0.42, -0.32, 0.7),
        ("I want the words you owe me, {you}.", 0.45, -0.35, 0.75),
        ("Take back the insult or take the consequences, {you}.", 0.5, -0.42, 0.8),
        ("I'm waiting, {you}.", 0.45, -0.35, 0.75),
        ("The face can wait. This can't, {you}.", 0.45, -0.35, 0.75),
        ("You'll swallow that or I'll help you, {you}.", 0.5, -0.42, 0.8),
        ("An apology. In this hall. Now, {you}.", 0.48, -0.38, 0.8),
        ("I won't let that lie, {you}.", 0.45, -0.38, 0.75),
        ("Say you misspoke, {you}.", 0.4, -0.3, 0.7),
        ("You've the chance. Use it, {you}.", 0.42, -0.32, 0.7),
        ("I want it said while they're watching, {you}.", 0.48, -0.38, 0.8),
        ("Don't you walk, {you}.", 0.48, -0.4, 0.8),
        ("That's a stain. You'll wash it, {you}.", 0.45, -0.35, 0.75),
        ("Give me the word or give me a reason, {you}.", 0.45, -0.38, 0.75),
        ("I asked once. This is twice, {you}.", 0.48, -0.38, 0.8),
        ("You'll not leave it hanging, {you}.", 0.45, -0.35, 0.75),
        ("Sorry. That's the word. Use it, {you}.", 0.45, -0.35, 0.8),
        ("I want peace. Start with an apology, {you}.", 0.4, -0.3, 0.7),
        ("The clan heard the first half. Give the second, {you}.", 0.45, -0.35, 0.75),
        ("You'll stand and you'll say it, {you}.", 0.48, -0.38, 0.8),
        ("I won't be the only one who was civil, {you}.", 0.42, -0.32, 0.7),
        ("Take it back before I stop asking, {you}.", 0.5, -0.4, 0.8),
        ("That's my name you used. Unsay it, {you}.", 0.48, -0.4, 0.8),
        ("I want the apology more than the fight, {you}.", 0.4, -0.3, 0.7),
        ("You'll do this or we'll do the other, {you}.", 0.5, -0.42, 0.8),
        ("Speak. The hall's listening, {you}.", 0.45, -0.35, 0.75),
        ("I demand what's owed: a sorry, {you}.", 0.45, -0.38, 0.8),
        ("No work till that's mended, {you}.", 0.45, -0.35, 0.75),
        ("You've a last chance to be decent, {you}.", 0.42, -0.32, 0.7),
        ("Say it now and we can still eat, {you}.", 0.4, -0.3, 0.7),
        ("I'll have those words back, {you}.", 0.48, -0.4, 0.8),
    ],
    "REFUSE_APOLOGY": [
        ("I'll say nothing of the sort, {you}.", 0.5, -0.5, 0.4),
        ("Sorry? To you? Hold your breath, {you}.", 0.6, -0.6, 0.4),
        ("I meant every word, {you}.", 0.55, -0.55, 0.3),
        ("No. I won't, {you}.", 0.52, -0.52, 0.4),
        ("You can wait till the mountain falls, {you}.", 0.58, -0.58, 0.35),
        ("I said what I said, {you}.", 0.55, -0.55, 0.35),
        ("You'll get no sorry from this mouth, {you}.", 0.58, -0.58, 0.4),
        ("Ask again. The answer's the same, {you}.", 0.55, -0.55, 0.35),
        ("I'd rather bite my tongue off, {you}.", 0.6, -0.6, 0.4),
        ("Not today. Not you, {you}.", 0.55, -0.55, 0.4),
        ("I don't owe you that word, {you}.", 0.52, -0.52, 0.35),
        ("Keep waiting, {you}.", 0.5, -0.5, 0.3),
        ("The truth doesn't come with a sorry, {you}.", 0.55, -0.55, 0.35),
        ("I won't kneel for your pride, {you}.", 0.58, -0.58, 0.4),
        ("You heard me the first time, {you}.", 0.55, -0.55, 0.4),
        ("No apology. No retreat, {you}.", 0.58, -0.58, 0.4),
        ("I'd unsay it if it were a lie. It isn't, {you}.", 0.55, -0.55, 0.35),
        ("Save your demand, {you}.", 0.52, -0.52, 0.35),
        ("I'm not sorry. I'm finished talking, {you}.", 0.58, -0.58, 0.4),
        ("You want a show. You'll not get it, {you}.", 0.55, -0.55, 0.35),
        ("The hall can listen. I still won't, {you}.", 0.55, -0.55, 0.4),
        ("My back doesn't bend that way, {you}.", 0.58, -0.58, 0.35),
        ("Ask the stone. It's softer, {you}.", 0.52, -0.52, 0.3),
        ("I won't dress a true word as a fault, {you}.", 0.55, -0.55, 0.35),
        ("That's your want. Not my duty, {you}.", 0.52, -0.52, 0.35),
        ("I spoke as I meant to, {you}.", 0.55, -0.55, 0.35),
        ("No. And you can tell them I said no, {you}.", 0.58, -0.58, 0.4),
        ("You'll die waiting, {you}.", 0.6, -0.6, 0.35),
        ("I don't perform on command, {you}.", 0.55, -0.55, 0.4),
        ("Sorry's a coin I won't spend on you, {you}.", 0.58, -0.58, 0.35),
        ("I won't take it back to keep you sweet, {you}.", 0.55, -0.55, 0.4),
        ("The answer is no, {you}.", 0.5, -0.5, 0.4),
        ("I have no sorry in me for that, {you}.", 0.55, -0.55, 0.35),
        ("You can have a fight. Not an apology, {you}.", 0.6, -0.6, 0.45),
        ("I won't lick your boots, {you}.", 0.58, -0.6, 0.4),
        ("That word dies in my throat, {you}.", 0.55, -0.55, 0.35),
        ("I'm done being civil, {you}.", 0.58, -0.58, 0.4),
        ("Ask a kinder dwarf, {you}.", 0.5, -0.5, 0.3),
        ("I meant it then. I mean it now, {you}.", 0.55, -0.55, 0.35),
        ("You'll not have the satisfaction, {you}.", 0.55, -0.55, 0.35),
        ("I won't unmake a true strike, {you}.", 0.55, -0.55, 0.35),
        ("No pardon. No peace on your terms, {you}.", 0.58, -0.58, 0.4),
        ("I said my piece. That's all, {you}.", 0.52, -0.52, 0.3),
        ("You can shout. I can stay silent, {you}.", 0.52, -0.52, 0.35),
        ("I won't give you what you want, {you}.", 0.55, -0.55, 0.4),
        ("That's a no you can carve in stone, {you}.", 0.58, -0.58, 0.35),
        ("I don't regret it, {you}.", 0.55, -0.55, 0.35),
        ("Find another to bow, {you}.", 0.55, -0.55, 0.35),
        ("I won't, and that's the last of it, {you}.", 0.55, -0.55, 0.4),
        ("You had your chance to be decent. I had mine, {you}.", 0.55, -0.55, 0.35),
        ("The sorry you want isn't in me, {you}.", 0.55, -0.55, 0.35),
        ("I stand by it, {you}.", 0.55, -0.55, 0.4),
        ("Not for the hall. Not for you, {you}.", 0.58, -0.58, 0.4),
        ("I'll live without your forgiveness, {you}.", 0.55, -0.55, 0.3),
    ],
    "COMPLAIN": [
        ("You know what {them} did to me? At the {place_thing}, in front of everyone.", 0.2, -0.4, 0.5),
        ("I'll not stand for what {them} did, {you}. Somebody should hear it.", 0.25, -0.45, 0.6),
        ("Ask anyone who was at the {place_thing}, {you}. {them} did it.", 0.2, -0.4, 0.5),
        ("{them} crossed me at the {place_thing}, {you}. I'm not letting it lie.", 0.22, -0.42, 0.55),
        ("Hear this, {you}: {them} has no shame left.", 0.25, -0.4, 0.5),
        ("I came to tell you what {them} did, {you}.", 0.2, -0.38, 0.5),
        ("{them} took what wasn't theirs, {you}. At the {place_thing}.", 0.22, -0.42, 0.55),
        ("You're a witness now, {you}. {them} did this.", 0.22, -0.4, 0.5),
        ("I want it known: {them} is the one, {you}.", 0.25, -0.42, 0.55),
        ("The {place_thing} saw it. {them} did it, {you}.", 0.2, -0.4, 0.5),
        ("I've kept quiet. I'm done. {them}, {you}.", 0.25, -0.42, 0.55),
        ("{them} made a fool of me, {you}.", 0.22, -0.4, 0.45),
        ("Tell me I'm wrong after you hear {them}'s part, {you}.", 0.2, -0.38, 0.45),
        ("I need someone to know, {you}. {them}.", 0.2, -0.38, 0.5),
        ("{them} walked off like it was nothing, {you}.", 0.22, -0.4, 0.5),
        ("If the hall asks, you'll say you heard it from me, {you}. {them}.", 0.22, -0.4, 0.5),
        ("I'm not inventing this, {you}. {them} was there.", 0.22, -0.4, 0.5),
        ("{them} has a habit and I'm the latest, {you}.", 0.25, -0.42, 0.5),
        ("The {place_thing} isn't safe from {them}, {you}.", 0.22, -0.42, 0.55),
        ("I want {them} named, {you}.", 0.25, -0.45, 0.55),
        ("You work with {them}. You should know, {you}.", 0.2, -0.38, 0.45),
        ("{them} smiled after, {you}. That's what got me.", 0.22, -0.4, 0.45),
        ("I've the empty hook and {them}'s name, {you}.", 0.22, -0.42, 0.55),
        ("Don't tell me to sleep on it, {you}. {them} won't.", 0.25, -0.42, 0.5),
        ("{them} did it in front of the {place_thing}, {you}.", 0.22, -0.4, 0.5),
        ("I'm bringing this to you because I trust you, {you}. {them}.", 0.18, -0.35, 0.5),
        ("{them} thinks no one talks. I'm talking, {you}.", 0.25, -0.42, 0.55),
        ("The tally's short and {them} was last, {you}.", 0.22, -0.42, 0.55),
        ("I want a second pair of ears, {you}. {them}.", 0.2, -0.38, 0.5),
        ("{them} put me in the dirt, {you}.", 0.25, -0.45, 0.5),
        ("If you see {them}, remember what I said, {you}.", 0.2, -0.4, 0.45),
        ("This isn't gossip. It's {them}, {you}.", 0.22, -0.4, 0.5),
        ("I was at the {place_thing}. {them} was too, {you}.", 0.2, -0.38, 0.5),
        ("{them} took the easy share, {you}.", 0.2, -0.4, 0.45),
        ("You'll hear it from someone. Hear it from me, {you}. {them}.", 0.2, -0.38, 0.5),
        ("{them} left me to carry it, {you}.", 0.22, -0.4, 0.5),
        ("I want the chief to know. Start with you, {you}. {them}.", 0.22, -0.42, 0.55),
        ("{them} has no clan manners, {you}.", 0.22, -0.4, 0.45),
        ("The {place_thing} still smells of it, {you}. {them}.", 0.2, -0.4, 0.45),
        ("I'm not asking you to fight. I'm asking you to hear, {you}.", 0.18, -0.35, 0.45),
        ("{them} will do it again if we stay quiet, {you}.", 0.25, -0.45, 0.55),
        ("I counted. {them} didn't like that, {you}.", 0.22, -0.4, 0.5),
        ("You've known {them} longer. Look at me, {you}.", 0.2, -0.38, 0.45),
        ("{them} called it a joke. It wasn't, {you}.", 0.22, -0.42, 0.5),
        ("I want it on the record, {you}: {them}.", 0.25, -0.42, 0.55),
        ("The night watch can back me, {you}. {them} can't.", 0.22, -0.4, 0.5),
        ("{them} went through my things, {you}.", 0.25, -0.45, 0.55),
        ("I'm tired of swallowing this, {you}. {them}.", 0.25, -0.42, 0.5),
        ("If you were me, you'd come too, {you}. {them}.", 0.2, -0.38, 0.45),
        ("{them} made the {place_thing} smaller, {you}.", 0.2, -0.4, 0.45),
        ("I need a dwarf who isn't afraid of {them}, {you}.", 0.22, -0.42, 0.55),
        ("That's the tale. {them} is the name, {you}.", 0.22, -0.4, 0.5),
        ("I won't work a face with {them} till this is said, {you}.", 0.25, -0.45, 0.55),
        ("Keep this, {you}. {them} did me wrong.", 0.22, -0.42, 0.5),
    ],
    "GOSSIP": [
        ("Between us, {you}: {them} is not what they seem.", 0.15, -0.2, 0.2),
        ("Did you hear about {them}, {you}? At the {place_thing}.", 0.1, -0.1, 0.2),
        ("They're saying things about {them}, {you}, and I believe them.", 0.2, -0.25, 0.2),
        ("You'd have liked {them} less if you'd been at the {place_thing}, {you}.", 0.15, -0.2, 0.15),
        ("Keep this close, {you}: {them} has a second face.", 0.15, -0.18, 0.2),
        ("I heard {them} at the {place_thing}, {you}.", 0.12, -0.15, 0.2),
        ("Don't repeat it. {them}'s been seen, {you}.", 0.15, -0.18, 0.2),
        ("The night crew talks about {them}, {you}.", 0.12, -0.15, 0.15),
        ("{them} was asking after stores that aren't theirs, {you}.", 0.15, -0.2, 0.2),
        ("I wouldn't turn my back at the {place_thing}, {you}. {them}.", 0.18, -0.22, 0.2),
        ("There's a smell around {them}, {you}.", 0.15, -0.18, 0.15),
        ("You didn't hear it from me, {you}, but {them}.", 0.12, -0.15, 0.2),
        ("{them} laughed when the tally went short, {you}.", 0.18, -0.22, 0.2),
        ("The {place_thing} has a story and {them} is in it, {you}.", 0.15, -0.18, 0.2),
        ("I watch {them} now, {you}.", 0.15, -0.2, 0.15),
        ("They say {them} pays in promises, {you}.", 0.15, -0.18, 0.15),
        ("{them} was in the dark when the lamp went, {you}.", 0.18, -0.2, 0.2),
        ("A quiet word: {them}, {you}.", 0.12, -0.15, 0.2),
        ("I've seen {them} count other folk's gold, {you}.", 0.18, -0.22, 0.2),
        ("The {place_thing} goes quiet when {them} comes, {you}.", 0.15, -0.18, 0.15),
        ("{them} has friends in low places, {you}.", 0.15, -0.2, 0.15),
        ("I wouldn't lend {them} a nail, {you}.", 0.18, -0.22, 0.15),
        ("They found a mark that looks like {them}'s, {you}.", 0.15, -0.2, 0.2),
        ("{them} talks sweet and works crooked, {you}.", 0.18, -0.22, 0.2),
        ("Keep your crate shut around {them}, {you}.", 0.18, -0.22, 0.2),
        ("The rumour's old. {them} keeps it fresh, {you}.", 0.15, -0.18, 0.15),
        ("I saw {them} by the {place_thing} after hours, {you}.", 0.15, -0.18, 0.2),
        ("{them} asked who holds the keys, {you}.", 0.18, -0.2, 0.2),
        ("Don't sit with your back to {them}, {you}.", 0.18, -0.22, 0.2),
        ("There's two tales of {them}. I believe the worse, {you}.", 0.2, -0.25, 0.2),
        ("{them} was named. That's all I'll say, {you}.", 0.15, -0.18, 0.2),
        ("The {place_thing} lost a tool. {them} gained a shine, {you}.", 0.18, -0.22, 0.2),
        ("I hear {them} in the passage when they think it's empty, {you}.", 0.15, -0.2, 0.2),
        ("{them} buys drinks like someone else's coin, {you}.", 0.15, -0.18, 0.15),
        ("Watch the scales when {them} is near, {you}.", 0.18, -0.22, 0.2),
        ("They say {them} left another hold in a hurry, {you}.", 0.2, -0.22, 0.15),
        ("{them} smiles too easy, {you}.", 0.12, -0.15, 0.15),
        ("I wouldn't put {them} on a night watch, {you}.", 0.18, -0.2, 0.2),
        ("The {place_thing} has ears. So does {them}, {you}.", 0.15, -0.18, 0.2),
        ("{them} was asking after you, {you}.", 0.15, -0.15, 0.25),
        ("A little bird at the {place_thing} named {them}, {you}.", 0.15, -0.18, 0.2),
        ("{them} has a way of being first to a find, {you}.", 0.18, -0.2, 0.15),
        ("I keep my braid and my distance from {them}, {you}.", 0.15, -0.2, 0.15),
        ("They found slack in {them}'s crate, {you}.", 0.18, -0.22, 0.2),
        ("{them} talks like a chief and works like a thief, {you}.", 0.2, -0.25, 0.2),
        ("Don't tell {them} I said this, {you}.", 0.12, -0.15, 0.25),
        ("The low road has {them}'s footprints, {you}.", 0.15, -0.2, 0.2),
        ("I give {them} the same trust I give a wet fuse, {you}.", 0.18, -0.22, 0.15),
        ("{them} was seen where they had no shift, {you}.", 0.18, -0.2, 0.2),
        ("There's a ledger page missing. {them} had the pen, {you}.", 0.2, -0.25, 0.2),
        ("The {place_thing} remembers {them}, {you}.", 0.15, -0.18, 0.15),
        ("I heard it twice, so I'm telling you once, {you}: {them}.", 0.15, -0.18, 0.2),
        ("{them} is a story I wouldn't want mine mixed with, {you}.", 0.15, -0.2, 0.15),
        ("If you value your ore, watch {them}, {you}.", 0.18, -0.22, 0.2),
    ],
    "ACCEPT": [
        ("Aye, {you}. It'll be done.", 0.0, 0.4, 0.3),
        ("Consider it done, {you}.", 0.0, 0.45, 0.3),
        ("Right you are, {you}.", 0.0, 0.35, 0.25),
        ("I'll see to it, {you}.", 0.0, 0.4, 0.3),
        ("Aye. Leave it with me, {you}.", 0.0, 0.42, 0.3),
        ("That's a yes, {you}.", 0.0, 0.38, 0.25),
        ("I'll take it on, {you}.", 0.0, 0.4, 0.3),
        ("Done when it's done, and soon, {you}.", 0.0, 0.4, 0.3),
        ("You've my word, {you}.", 0.0, 0.45, 0.3),
        ("Aye. I heard you, {you}.", 0.0, 0.35, 0.25),
        ("I'll make it so, {you}.", 0.0, 0.4, 0.3),
        ("That's my job now, {you}.", 0.0, 0.38, 0.3),
        ("I can do that, {you}.", 0.0, 0.4, 0.25),
        ("Aye. Go on about your shift, {you}.", 0.0, 0.38, 0.25),
        ("I'll have it before the bell, {you}.", 0.0, 0.42, 0.35),
        ("Consider me on it, {you}.", 0.0, 0.4, 0.3),
        ("Yes. That's fair, {you}.", 0.0, 0.4, 0.25),
        ("I'll not forget, {you}.", 0.0, 0.42, 0.3),
        ("Aye, {you}. I'm your dwarf for this.", 0.0, 0.45, 0.3),
        ("I'll sort it, {you}.", 0.0, 0.4, 0.3),
        ("That's agreed, {you}.", 0.0, 0.38, 0.25),
        ("I'll put my back to it, {you}.", 0.0, 0.42, 0.3),
        ("You can count it done, {you}.", 0.0, 0.45, 0.3),
        ("Aye. I owe you that much, {you}.", 0.0, 0.4, 0.25),
        ("I'll take the ask, {you}.", 0.0, 0.4, 0.3),
        ("Right. I'm moving, {you}.", 0.0, 0.38, 0.35),
        ("I'll see the thing through, {you}.", 0.0, 0.42, 0.3),
        ("That's a load I'll carry, {you}.", 0.0, 0.4, 0.3),
        ("Aye. No need to ask twice, {you}.", 0.0, 0.4, 0.25),
        ("I'll be about it, {you}.", 0.0, 0.38, 0.3),
        ("You've asked. I've said aye, {you}.", 0.0, 0.4, 0.3),
        ("I'll not leave it hanging, {you}.", 0.0, 0.42, 0.3),
        ("That's on me now, {you}.", 0.0, 0.4, 0.3),
        ("Aye. Trust it, {you}.", 0.0, 0.45, 0.25),
        ("I'll do it proper, {you}.", 0.0, 0.42, 0.3),
        ("Say no more. I'll handle it, {you}.", 0.0, 0.4, 0.25),
        ("I'm for it, {you}.", 0.0, 0.38, 0.3),
        ("The work's mine, {you}.", 0.0, 0.4, 0.3),
        ("Aye. I'll not shame the ask, {you}.", 0.0, 0.42, 0.3),
        ("I'll have news when it's done, {you}.", 0.0, 0.4, 0.3),
        ("That's a yes you can take to the hall, {you}.", 0.0, 0.42, 0.25),
        ("I'll start now, {you}.", 0.0, 0.4, 0.35),
        ("You've my aye, {you}.", 0.0, 0.4, 0.25),
        ("I'll see you right, {you}.", 0.0, 0.45, 0.3),
        ("Consider the thing accepted, {you}.", 0.0, 0.4, 0.25),
        ("I'll not argue. I'll do, {you}.", 0.0, 0.4, 0.3),
        ("Aye. That's simple enough, {you}.", 0.0, 0.38, 0.25),
        ("I'll put it first, {you}.", 0.0, 0.42, 0.35),
        ("You asked a dwarf who says yes, {you}.", 0.0, 0.42, 0.25),
        ("I'll keep the promise, {you}.", 0.0, 0.45, 0.3),
        ("That's settled then, {you}.", 0.0, 0.38, 0.25),
        ("Aye. Go. I've got it, {you}.", 0.0, 0.4, 0.3),
        ("I'll bring it when it's done, {you}.", 0.0, 0.4, 0.3),
        ("Right. No more talk, {you}.", 0.0, 0.35, 0.3),
    ],
    "REFUSE": [
        ("No. Do it yourself, {you}.", 0.3, -0.3, 0.3),
        ("Not for you, {you}.", 0.35, -0.35, 0.3),
        ("I've my own work, {you}.", 0.2, -0.2, 0.3),
        ("No, {you}.", 0.25, -0.25, 0.3),
        ("I won't, {you}.", 0.3, -0.3, 0.3),
        ("Ask another, {you}.", 0.25, -0.25, 0.25),
        ("That's not my shift, {you}.", 0.22, -0.22, 0.25),
        ("I've no time for that, {you}.", 0.25, -0.25, 0.3),
        ("The answer is no, {you}.", 0.3, -0.3, 0.3),
        ("I don't owe you that, {you}.", 0.32, -0.32, 0.3),
        ("Find your own back, {you}.", 0.3, -0.3, 0.3),
        ("Not today, {you}.", 0.22, -0.22, 0.25),
        ("I'm not your fetch-dwarf, {you}.", 0.35, -0.35, 0.3),
        ("I've heard you. Still no, {you}.", 0.3, -0.3, 0.3),
        ("The face needs me more, {you}.", 0.22, -0.2, 0.25),
        ("I said no and I meant no, {you}.", 0.32, -0.32, 0.3),
        ("You'll have to do without me, {you}.", 0.25, -0.25, 0.25),
        ("That's a no you can keep, {you}.", 0.28, -0.28, 0.25),
        ("I won't drop my work for yours, {you}.", 0.3, -0.28, 0.3),
        ("Ask someone who likes you, {you}.", 0.35, -0.35, 0.3),
        ("I'm busy, {you}.", 0.2, -0.2, 0.3),
        ("No chance, {you}.", 0.3, -0.3, 0.3),
        ("That's your want. Not my duty, {you}.", 0.28, -0.28, 0.25),
        ("I don't run your errands, {you}.", 0.32, -0.32, 0.3),
        ("The hall didn't assign me to you, {you}.", 0.28, -0.25, 0.25),
        ("I'll not, {you}.", 0.28, -0.28, 0.3),
        ("You've hands. Use them, {you}.", 0.32, -0.3, 0.3),
        ("That's a fool's job and I'm no fool, {you}.", 0.3, -0.3, 0.25),
        ("I like my blood in my veins, {you}. No.", 0.28, -0.25, 0.3),
        ("Not worth my boots, {you}.", 0.28, -0.28, 0.25),
        ("I decline, {you}.", 0.22, -0.22, 0.25),
        ("Go on without me, {you}.", 0.22, -0.2, 0.25),
        ("I won't leave the {topic_thing} for that, {you}.", 0.25, -0.22, 0.3),
        ("That's a no from a tired dwarf, {you}.", 0.2, -0.18, 0.25),
        ("You can want. I can refuse, {you}.", 0.3, -0.3, 0.3),
        ("I've said my piece: no, {you}.", 0.28, -0.28, 0.3),
        ("Find a hungrier dwarf, {you}.", 0.28, -0.25, 0.25),
        ("I don't take every ask that walks by, {you}.", 0.25, -0.25, 0.25),
        ("No, and that's the kindest I can be, {you}.", 0.28, -0.25, 0.3),
        ("My list is full, {you}.", 0.2, -0.2, 0.25),
        ("I won't be used, {you}.", 0.35, -0.35, 0.3),
        ("That's not happening, {you}.", 0.3, -0.3, 0.3),
        ("I like breathing. So no, {you}.", 0.25, -0.22, 0.3),
        ("Ask me when you've coin. Or don't, {you}.", 0.3, -0.28, 0.3),
        ("I have a better place to be, {you}.", 0.25, -0.22, 0.25),
        ("No. And don't look wounded, {you}.", 0.3, -0.3, 0.3),
        ("I'm not the dwarf for that, {you}.", 0.22, -0.2, 0.25),
        ("The answer stays no, {you}.", 0.3, -0.3, 0.3),
        ("I won't put my name on that, {you}.", 0.28, -0.28, 0.25),
        ("Save your breath, {you}.", 0.3, -0.3, 0.3),
        ("I've work that isn't yours, {you}.", 0.22, -0.22, 0.3),
        ("That's a walk I'll not take, {you}.", 0.25, -0.25, 0.25),
        ("No, {you}. Try the next beard.", 0.28, -0.28, 0.25),
        ("I refuse, and I sleep fine, {you}.", 0.3, -0.28, 0.25),
    ],
    "BARGAIN": [
        ("For {gold} gold, {you}, and not a copper less.", 0.15, 0.0, 0.4),
        ("Make it {gold} gold and I'm your dwarf, {you}.", 0.1, 0.1, 0.4),
        ("{gold} gold, {you}. That's the price of my back.", 0.15, 0.0, 0.4),
        ("I can do it for {gold} gold, {you}.", 0.1, 0.05, 0.4),
        ("That's low. {gold} gold, {you}.", 0.15, 0.0, 0.45),
        ("Add a bit. {gold} gold, {you}.", 0.12, 0.05, 0.4),
        ("I'll meet you at {gold} gold, {you}.", 0.1, 0.08, 0.4),
        ("{gold} gold or I walk, {you}.", 0.18, -0.05, 0.45),
        ("The work's worth {gold} gold, {you}.", 0.12, 0.05, 0.4),
        ("Come up to {gold} gold, {you}.", 0.12, 0.05, 0.45),
        ("I won't break my back for less than {gold} gold, {you}.", 0.15, 0.0, 0.4),
        ("{gold} gold and a drink, {you}.", 0.1, 0.1, 0.35),
        ("That's my number: {gold} gold, {you}.", 0.12, 0.0, 0.4),
        ("We can deal at {gold} gold, {you}.", 0.1, 0.08, 0.4),
        ("Pay {gold} gold and we shake, {you}.", 0.1, 0.1, 0.4),
        ("I'm not cheap. {gold} gold, {you}.", 0.15, 0.0, 0.4),
        ("{gold} gold, or find a hungrier dwarf, {you}.", 0.15, -0.05, 0.4),
        ("Double what you said and we're close: {gold} gold, {you}.", 0.15, 0.0, 0.45),
        ("I can hear {gold} gold, {you}.", 0.1, 0.08, 0.4),
        ("The risk wants {gold} gold, {you}.", 0.15, 0.0, 0.45),
        ("{gold} gold and I bring my own tools, {you}.", 0.1, 0.08, 0.4),
        ("Meet me in the middle: {gold} gold, {you}.", 0.1, 0.1, 0.4),
        ("That's the least I'll take: {gold} gold, {you}.", 0.15, 0.0, 0.4),
        ("{gold} gold, paid on the nail, {you}.", 0.12, 0.05, 0.45),
        ("I like {gold} gold better than your first offer, {you}.", 0.1, 0.08, 0.4),
        ("Put {gold} gold on the chit, {you}.", 0.12, 0.05, 0.4),
        ("{gold} gold or the deal dies, {you}.", 0.18, -0.05, 0.45),
        ("You want speed? That's {gold} gold, {you}.", 0.15, 0.0, 0.45),
        ("I can do fair: {gold} gold, {you}.", 0.1, 0.1, 0.4),
        ("{gold} gold, and I don't complain, {you}.", 0.1, 0.08, 0.35),
        ("Raise it to {gold} gold, {you}.", 0.12, 0.05, 0.45),
        ("My back isn't free. {gold} gold, {you}.", 0.15, 0.0, 0.4),
        ("{gold} gold, same as the last job, {you}.", 0.1, 0.05, 0.4),
        ("I won't go under {gold} gold, {you}.", 0.15, 0.0, 0.4),
        ("That's a holder's price: {gold} gold, {you}.", 0.12, 0.05, 0.4),
        ("{gold} gold and you stop talking, {you}.", 0.15, 0.0, 0.4),
        ("We both leave happy at {gold} gold, {you}.", 0.1, 0.12, 0.4),
        ("{gold} gold. I can live with that, {you}.", 0.1, 0.1, 0.35),
        ("Pay like a clan, {you}: {gold} gold.", 0.12, 0.05, 0.4),
        ("{gold} gold, or I go back to my face, {you}.", 0.15, 0.0, 0.4),
        ("I'm listening at {gold} gold, {you}.", 0.1, 0.08, 0.4),
        ("That's not insulting: {gold} gold, {you}.", 0.1, 0.08, 0.4),
        ("{gold} gold, and I start now, {you}.", 0.1, 0.1, 0.45),
        ("Come off your low number. {gold} gold, {you}.", 0.15, 0.0, 0.45),
        ("The stone costs. So do I. {gold} gold, {you}.", 0.15, 0.0, 0.4),
        ("{gold} gold is the door. Open it, {you}.", 0.12, 0.05, 0.4),
        ("I can shake on {gold} gold, {you}.", 0.1, 0.1, 0.4),
        ("{gold} gold, take it or keep walking, {you}.", 0.18, -0.05, 0.45),
        ("A little more. {gold} gold, {you}.", 0.12, 0.05, 0.4),
        ("That's my last: {gold} gold, {you}.", 0.15, 0.0, 0.45),
        ("{gold} gold keeps my mouth shut and my hands busy, {you}.", 0.12, 0.05, 0.4),
        ("Count {gold} gold and we've a bargain, {you}.", 0.1, 0.1, 0.4),
        ("I didn't crawl here for less than {gold} gold, {you}.", 0.15, 0.0, 0.4),
        ("{gold} gold. Say aye, {you}.", 0.1, 0.08, 0.45),
    ],
    "REBUKE": [
        ("Enough, {you}. This hall has had enough of you.", 0.4, -0.4, 0.8),
        ("You'll answer for that here and now, {you}.", 0.45, -0.45, 0.8),
        ("{gold} gold from your purse, {you}, and be glad it's no worse.", 0.35, -0.35, 0.8),
        ("That's enough from you, {you}.", 0.4, -0.4, 0.75),
        ("Stand there. I'm speaking, {you}.", 0.42, -0.4, 0.8),
        ("The hall sees you. So do I, {you}.", 0.4, -0.4, 0.75),
        ("You'll pay for the peace you broke, {you}.", 0.42, -0.42, 0.8),
        ("Quiet. You've had your turn, {you}.", 0.4, -0.38, 0.75),
        ("That's a fine: {gold} gold, {you}.", 0.35, -0.35, 0.8),
        ("I won't have that in my hold, {you}.", 0.45, -0.45, 0.8),
        ("You've shamed the work, {you}.", 0.4, -0.42, 0.75),
        ("Look at me when I'm judging you, {you}.", 0.42, -0.4, 0.8),
        ("The clan's patience is not endless, {you}.", 0.4, -0.4, 0.75),
        ("You'll make this right or you'll leave, {you}.", 0.45, -0.45, 0.8),
        ("I name it: you were wrong, {you}.", 0.4, -0.4, 0.75),
        ("Put the gold down. {gold}, {you}.", 0.35, -0.35, 0.8),
        ("That's enough noise for one life, {you}.", 0.4, -0.38, 0.75),
        ("You don't set the tone here, {you}.", 0.42, -0.4, 0.75),
        ("I'll have order, {you}.", 0.4, -0.4, 0.8),
        ("The {topic_thing} is not your stage, {you}.", 0.38, -0.38, 0.75),
        ("You've been seen. That's enough, {you}.", 0.4, -0.4, 0.75),
        ("Pay the fine or take the gate, {you}.", 0.42, -0.42, 0.8),
        ("I speak for the hall: stop, {you}.", 0.42, -0.4, 0.8),
        ("Your name is on this, {you}.", 0.4, -0.4, 0.75),
        ("Don't make me repeat a rebuke, {you}.", 0.42, -0.42, 0.8),
        ("{gold} gold, and you keep your teeth, {you}.", 0.4, -0.4, 0.8),
        ("You'll stand the shame you earned, {you}.", 0.42, -0.42, 0.75),
        ("That's the line. You crossed it, {you}.", 0.42, -0.42, 0.8),
        ("I want silence and I want the gold, {you}.", 0.4, -0.38, 0.8),
        ("The hold comes first. You don't, {you}.", 0.4, -0.4, 0.75),
        ("You've had warning. This is the next, {you}.", 0.42, -0.42, 0.8),
        ("Put your hands where I can see them, {you}.", 0.4, -0.38, 0.8),
        ("I won't have blood for sport, {you}.", 0.4, -0.4, 0.8),
        ("That's a mark against you, {you}.", 0.38, -0.4, 0.7),
        ("You'll hear this in front of them, {you}.", 0.42, -0.4, 0.8),
        ("The fine is {gold} gold. Pay it, {you}.", 0.35, -0.35, 0.8),
        ("I keep this hall. You remember that, {you}.", 0.45, -0.42, 0.8),
        ("Enough pride. Start with obedience, {you}.", 0.42, -0.4, 0.75),
        ("You've made a mess. You'll sweep it, {you}.", 0.4, -0.4, 0.75),
        ("I don't care who started it. I care who stops, {you}.", 0.4, -0.38, 0.8),
        ("That's the last outburst I take, {you}.", 0.45, -0.45, 0.8),
        ("Count the gold out where they can see, {you}.", 0.38, -0.35, 0.8),
        ("You're not bigger than the clan, {you}.", 0.42, -0.42, 0.75),
        ("I said enough and I meant the word, {you}.", 0.42, -0.4, 0.8),
        ("Take the rebuke or take the road, {you}.", 0.45, -0.45, 0.8),
        ("The {topic_thing} is closed to you till this is paid, {you}.", 0.4, -0.4, 0.75),
        ("You'll not raise your voice over mine, {you}.", 0.45, -0.42, 0.8),
        ("That's settled: you were at fault, {you}.", 0.4, -0.4, 0.75),
        ("I want the gold and the quiet, {you}.", 0.38, -0.38, 0.8),
        ("You've worn my patience through, {you}.", 0.42, -0.42, 0.75),
        ("Stand down, {you}.", 0.4, -0.38, 0.8),
        ("The hall has spoken. I just used its mouth, {you}.", 0.4, -0.4, 0.75),
        ("{gold} gold, and you remember why, {you}.", 0.38, -0.38, 0.8),
        ("That's the end of it, {you}. Or it can get worse.", 0.45, -0.45, 0.8),
    ],
    "IGNORE": [
        ("...", 0.05, -0.1, 0.05),
    ],
}

#: Extra full lines, mixed in when the speaker's swearing tier allows them. The stock
#: templates above stay as they are. Tier 3 is in-world only.
_SWEAR_TEMPLATES = {
    "INSULT": {
        1: [("Hell, {you}, get away from my {topic_thing}.", 0.7, -0.75, 0.45),
            ("Damn it, {you}, out of my way.", 0.55, -0.55, 0.4)],
        2: [("You're a slagforger, {you}, and half a smith.", 0.65, -0.7, 0.2),
            ("Fuck off my {topic_thing}, {you}.", 0.7, -0.8, 0.5),
            ("Beardless whelp, {you}. Grow a braid.", 0.6, -0.65, 0.2)],
        3: [("Sap-drinker, {you}. Keep your kind off this face.", 0.7, -0.8, 0.3),
            ("Stubble-chin, {you}. Everyone knows what you are.", 0.68, -0.78, 0.25)],
    },
    "RETORT": {
        1: [("Hell, {you}, that's rich.", 0.55, -0.6, 0.35),
            ("Damn your noise, {you}.", 0.55, -0.58, 0.35),
            ("By the stone, {you}, enough.", 0.5, -0.55, 0.3)],
        2: [("That's rich, coming from a slagforger, {you}.", 0.65, -0.7, 0.35),
            ("Aye? Fuck off back to the muck, {you}.", 0.7, -0.75, 0.4),
            ("You're a pick-dropper, {you}, and you know it.", 0.62, -0.68, 0.35),
            ("Shut it, you bastard. I've work.", 0.68, -0.7, 0.4),
            ("Don't lecture me, you hollow-beard, {you}.", 0.64, -0.68, 0.35),
            ("Fuck your opinion, {you}. I was here.", 0.7, -0.72, 0.4)],
        3: [("Mind your own beard, you sun-squinter, {you}.", 0.6, -0.7, 0.4),
            ("Take that back to the surface, {you}.", 0.62, -0.7, 0.35),
            ("I've no use for your kind in this hold, {you}.", 0.68, -0.75, 0.4)],
    },
    "THREAT": {
        2: [("Touch my {topic_thing} again, you bastard, and I'll break your arm.",
             0.88, -0.85, 0.75)],
        3: [("Come closer, you warren-filth, {you}.", 0.85, -0.85, 0.7)],
    },
    "ACCUSE": {
        2: [("You ore-thief, {you}. That {topic_thing} was mine.", 0.6, -0.65, 0.55)],
    },
    "REFUSE_APOLOGY": {
        2: [("Sorry? To you, you hollow-beard? Hold your breath, {you}.", 0.65, -0.65, 0.4)],
    },
}

#: A sim speech act -> the schema intent a classifier would have to label it with. Several acts
#: share one intent: the labels describe the words, the sim's event kind describes the move.
ACT_INTENT = {
    "RETORT": "INSULT",
    "REFUSE_APOLOGY": "INSULT",
    "DEMAND": "COMMAND",
    "COMPLAIN": "ACCUSE",
    "GOSSIP": "SMALLTALK",
    "ACCEPT": "OFFER",
    "BARGAIN": "OFFER",
    "REFUSE": "SMALLTALK",
    "REBUKE": "THREAT",
    "IGNORE": "SMALLTALK",
}

#: A word for the topic that fits in a sentence.
_TOPIC_THING = {
    "FORGE": "forge", "MINE": "mine", "TREASURE": "gold", "FOOD": "bread", "DRINK": "ale",
    "HOME": "hall", "WEAPON": "axe", "WORK": "shift", "CLAN": "clan", "MONSTER": "gate",
    "TRADE": "trade", "NONE": "day",
}

#: The place you are standing in suggests what you talk about.
PLACE_TOPIC = {
    "FORGE": "FORGE", "MINE": "MINE", "TAVERN": "DRINK", "HALL": "CLAN",
    "FARM": "FOOD", "GATE": "MONSTER",
}

_PLACE_THING = {"FORGE": "forge", "MINE": "mine", "TAVERN": "tavern", "HALL": "hall",
                "FARM": "farm", "GATE": "gate"}


#: What a hurt dwarf tacks on, by where the dialogue's own momentum leaves it. ``{it}`` is the
#: :meth:`dwarfsim.condition.Condition.complaint` phrase -- "my arm", "these ribs".
HURT_TAILS = (
    "Mind {it}.",
    "Go easy, {it} is still bad.",
    "Not with {it} the way it is.",
    "I'd help if it weren't for {it}.",
    "{it} is killing me, if you want the truth.",
)

#: How often a hurt dwarf mentions it at all. Often enough to notice in the story, rarely enough
#: that the settlement is not a hospital ward.
HURT_TAIL_CHANCE = 0.35


def hurt_tail(rng, hurt, chance=HURT_TAIL_CHANCE):
    """The clause a hurt dwarf adds, or ``""``. ``hurt`` is a complaint phrase or ``None``."""
    if not hurt or rng.random() >= chance:
        return ""
    return " " + HURT_TAILS[rng.randrange(len(HURT_TAILS))].format(it=hurt)


def utterance(act, speaker_name, listener_name, topic, rng, about=None, place=None, gold=0,
              item="a hand", swear=None, hurt=None):
    """A line of dialogue plus its parsed labels, in the SCHEMA.md shape.

    ``act`` is either one of the thirteen schema intents or one of the sim's own speech acts
    (``RETORT``, ``DEMAND``, ``COMPLAIN``, ``GOSSIP``, ``BARGAIN`` ...). The returned dict always
    carries a schema-legal ``intent``; ``act`` is kept alongside it so the log can say which of the
    several acts sharing that intent this one was.

    ``swear`` is the speaker's :class:`~dwarfsim.profanity.Swearing`, from
    :func:`dwarfsim.profanity.for_speaker`, or ``None``. A term is woven into the
    line by :func:`dwarfsim.profanity.fill` according to its ``form`` / ``number``
    / ``article`` -- never hung off the end as a second sentence.
    """
    forms = list(_TEMPLATES.get(act) or _TEMPLATES["SMALLTALK"])
    if swear is not None:
        for need, rows in _SWEAR_TEMPLATES.get(act, {}).items():
            if swear.tier >= need:
                forms.extend(rows)
    text, aggression, valence, urgency = forms[rng.randrange(len(forms))]
    text = text.format(you=listener_name, topic_thing=_TOPIC_THING.get(topic, "day"),
                       them=about or "someone", place_thing=_PLACE_THING.get(place, "hall"),
                       gold=gold, item=item)
    hits = []
    if swear is not None:
        text, hits = swear.apply(text, act, name=listener_name, addressed="LISTENER")
        if hits:
            tier = max(h.tier for h in hits)
            aggression = 1.0 if tier >= 3 else max(aggression, max(h.strength for h in hits))
            if tier >= 3:
                valence = min(valence, -0.8)
    # A dwarf with a broken arm says so, sooner or later, whatever else they are saying.
    tail = hurt_tail(rng, hurt)
    if tail:
        text += tail
    names = [n for n in NAME_POOL if n in text]
    intent = act if act in INTENT_EVENT else ACT_INTENT.get(act, "SMALLTALK")
    out = {
        "text": text,
        "intent": intent,
        "act": act,
        "topic": topic,
        "addressed": "LISTENER",
        "aggression": aggression,
        "valence": valence,
        "urgency": urgency,
        "names": names,
    }
    if hits:
        out["profanity"] = profanity.rows(hits)
    return out
