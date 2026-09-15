"""The planner: what a dwarf decides to say, before anything decides how to say it.

The case the whole bank exists for is the MISFORTUNE spread across the five trust levels, and it
is the first thing here. The rest is the two derived columns, the acts that must exist for the
conversation to hold together (CALLBACK, ADMIT_IGNORANCE, the sarcasm acts), and the rule that
nothing outside :data:`dwarfsim.speechplan.RULES` decides an act.
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from dwarfsim import dialogue, replies, speechplan        # noqa: E402
from dwarfsim.schema import MAX_HEALTH, PLAYER_ID         # noqa: E402
from dwarfsim.world import World                          # noqa: E402

#: The five trust levels as a dwarf's relationship row, worst to best.
FEELINGS = {
    "hostile": {"trust": -0.6, "hatred": 0.10},
    "cold": {"trust": -0.30, "hatred": 0.0},
    "neutral": {"trust": 0.0, "hatred": 0.0},
    "warm": {"trust": 0.30, "hatred": 0.0},
    "close": {"trust": 0.80, "hatred": 0.0},
}


def dwarf_at(level, seed=1, **feelings):
    w = World(n_agents=3, seed=seed)
    d = w.living()[0]
    rel = d.mind.rel(PLAYER_ID)
    rel.update(FEELINGS[level])
    rel.update(feelings)
    return w, d


def labels(intent="SMALLTALK", text="Something.", **kw):
    out = {"text": text, "intent": intent, "topic": "NONE", "addressed": "LISTENER",
           "aggression": 0.0, "valence": 0.0, "urgency": 0.0, "names": [],
           "sincerity": "SINCERE"}
    out.update(kw)
    return out


SICK = labels("SMALLTALK", "I've been sick lately.", valence=-0.4)


# ---------------------------------------------------------------------------
# the worked example
# ---------------------------------------------------------------------------


def test_the_misfortune_spread_runs_from_sympathy_to_gloating():
    got = {}
    for level in FEELINGS:
        w, d = dwarf_at(level)
        got[level] = speechplan.plan(d, PLAYER_ID, dict(SICK), w)
    assert speechplan.trust_key(dwarf_at("close")[1], PLAYER_ID) == "close"
    assert got["close"].act == "SYMPATHIZE"
    assert got["warm"].act == "OFFER_HELP"
    assert got["neutral"].act == "SMALLTALK_BACK"
    assert got["cold"].act == "DEFLECT"
    assert got["hostile"].act == "GLOAT"


def test_at_hostile_with_real_hatred_it_is_the_cruel_version():
    w, d = dwarf_at("hostile", hatred=0.8)
    plan = speechplan.plan(d, PLAYER_ID, dict(SICK), w)
    assert plan.act == "MOCK"
    assert plan.rule == "misfortune.cruel"
    # and the cruel rule never fires at warm, whatever the hatred says
    w2, warm = dwarf_at("warm")
    assert speechplan.plan(warm, PLAYER_ID, dict(SICK), w2).act != "MOCK"


def test_the_fortune_spread_runs_the_other_way():
    lucky = labels("SMALLTALK", "I struck a good seam today.", valence=0.6)
    acts = {}
    for level in FEELINGS:
        w, d = dwarf_at(level)
        acts[level] = speechplan.plan(d, PLAYER_ID, dict(lucky), w).act
    assert acts["close"] == acts["warm"] == "CONGRATULATE"
    assert acts["neutral"] == "SMALLTALK_BACK"
    assert acts["cold"] == "DEFLECT"
    assert acts["hostile"] == "BELITTLE_FORTUNE"


# ---------------------------------------------------------------------------
# the two columns the classifier does not label yet
# ---------------------------------------------------------------------------


def test_about_and_news_are_derived_the_way_the_prompt_reads_them():
    rows = [
        ("I've been sick lately.", "SMALLTALK", -0.4, "SPEAKER", "MISFORTUNE"),
        ("You've been sick lately.", "SMALLTALK", -0.2, "LISTENER", "MISFORTUNE"),
        ("I struck a good seam today.", "SMALLTALK", 0.6, "SPEAKER", "FORTUNE"),
        ("I'm heading down the mine at dawn.", "SMALLTALK", 0.1, "SPEAKER", "PLAN"),
        ("The gate's rotten and everyone knows it.", "SMALLTALK", -0.3, "WORLD", "OPINION"),
        ("How's the arm?", "QUESTION", 0.3, "LISTENER", "SEEKING"),
        ("Your ancestor was a slave.", "INSULT", -0.8, "LISTENER", "OPINION"),
        ("Shame if your forge caught fire.", "THREAT", -0.7, "LISTENER", "PLAN"),
        ("Careful, the lava's close.", "WARNING", 0.2, "WORLD", "FACT"),
        ("Well met.", "GREET", 0.4, "NONE", "NONE"),
    ]
    for text, intent, valence, about, news in rows:
        topic = "HOME" if "gate" in text else ("MONSTER" if "lava" in text else "NONE")
        parsed = labels(intent, text, valence=valence, topic=topic)
        got_about, got_news, why = speechplan.derive(parsed)
        assert (got_about, got_news) == (about, news), text
        assert why, "every derivation says why it read it that way"


def test_a_name_that_is_neither_of_us_makes_it_about_a_third():
    w, d = dwarf_at("neutral")
    other = [a for a in w.living() if a.id != d.id][0]
    parsed = labels("SMALLTALK", "%s's been sick lately." % other.name,
                    names=[other.name], valence=-0.2)
    about, news, _ = speechplan.derive(parsed, d, w.agent(PLAYER_ID), w)
    assert (about, news) == ("THIRD", "MISFORTUNE")


def test_labelled_columns_win_over_the_rules_and_say_so():
    parsed = labels("SMALLTALK", "I've been sick lately.", valence=-0.4,
                    about="WORLD", news="OPINION")
    about, news, why = speechplan.derive(parsed)
    assert (about, news) == ("WORLD", "OPINION")
    assert why == [("about/news", "labelled by the interpreter")]


def test_the_mine_is_a_hole_in_the_ground_not_a_possessive():
    """The rule that cost the most to get right: `mine` is in every dwarf's vocabulary as a
    place, so reading it as a first-person possessive sent every question about the mine to
    ADMIT_IGNORANCE."""
    about, news, _ = speechplan.derive(labels("QUESTION", "How is the mine?", topic="MINE"))
    assert about == "WORLD"


# ---------------------------------------------------------------------------
# the acts that hold a conversation together
# ---------------------------------------------------------------------------


def test_asking_the_same_question_twice_gets_a_callback():
    w, d = dwarf_at("warm")
    question = labels("QUESTION", "How is the mine?", topic="MINE")
    first = replies.reply(w, d, PLAYER_ID, dict(question))
    assert first["kind"] != "CALLBACK"
    second = replies.reply(w, d, PLAYER_ID, dict(question))
    assert second["kind"] == "CALLBACK"
    assert second["construction"].rule == "callback.same_question"


def test_asking_for_the_same_thing_twice_gets_a_callback_too():
    w, d = dwarf_at("cold")
    ask = labels("REQUEST", "Fetch me ore from the mine.", topic="MINE", urgency=0.6)
    replies.reply(w, d, PLAYER_ID, dict(ask))
    again = replies.reply(w, d, PLAYER_ID, dict(ask))
    assert again["kind"] == "CALLBACK"


def test_a_question_the_dwarf_cannot_know_gets_ignorance_not_an_invented_fact():
    w, d = dwarf_at("warm")
    # about a third party it has never met, never heard of and has no relationship row for
    parsed = labels("QUESTION", "Where has that one got to?", about="THIRD", news="SEEKING")
    plan = speechplan.plan(d, PLAYER_ID, parsed, w)
    assert plan.act == "ADMIT_IGNORANCE"
    assert not speechplan.knows_answer(plan.scene)
    got = replies.reply(w, d, PLAYER_ID, dict(parsed))
    assert got["kind"] == "ADMIT_IGNORANCE" and got["text"]


def test_an_answering_act_is_never_reached_for_a_question_it_cannot_answer():
    """The guard after the table, not a rule in it: whatever the table chose, an answer to a
    question this dwarf cannot know is the one thing the bank's rules forbid."""
    w, d = dwarf_at("close")
    parsed = labels("QUESTION", "What did that one say?", about="THIRD", news="SEEKING")
    plan = speechplan.plan(d, PLAYER_ID, parsed, w)
    assert plan.act not in speechplan.ANSWERING


def test_a_sarcastic_compliment_gets_an_act_that_shows_it_was_noticed():
    for level in FEELINGS:
        w, d = dwarf_at(level)
        parsed = labels("PRAISE", "Nice swing, genius.", valence=0.6,
                        sincerity="SARCASTIC")
        plan = speechplan.plan(d, PLAYER_ID, parsed, w)
        assert plan.act in speechplan.NOTICED_SARCASM, level
        assert plan.rule.startswith("sarcasm.")


def test_praise_from_somebody_already_suspected_is_read_as_flattery():
    from dwarfsim import regard
    w, d = dwarf_at("neutral")
    regard.of(d, PLAYER_ID).suspicion = regard.SUSPICION_THRESHOLD + 0.1
    plan = speechplan.plan(d, PLAYER_ID, labels("PRAISE", "Fine work.", valence=0.8), w)
    assert plan.act == "SUSPECT_FLATTERY"
    assert plan.state["suspicion"] == "high"


def test_the_arbitrators_decision_overrules_the_whole_table():
    w, d = dwarf_at("close")                       # as well disposed as it gets
    ask = labels("COMMAND", "Fetch me ore.", topic="MINE", urgency=0.8)
    for decided, act in (("ACCEPT", "ACCEPT"), ("REFUSE", "REFUSE"),
                         ("BARGAIN", "BARGAIN"), ("FULFIL", "ACCEPT"),
                         ("IGNORE", "SILENCE"), ("AVOID", "SILENCE")):
        plan = speechplan.plan(d, PLAYER_ID, dict(ask), w, decided=decided)
        assert plan.act == act and plan.forced == decided
        assert plan.reasons[0][0] == "decided." + decided


# ---------------------------------------------------------------------------
# the buckets, and the table itself
# ---------------------------------------------------------------------------


def test_the_buckets_are_the_banks_own_words():
    w, d = dwarf_at("neutral")
    assert speechplan.trust_key(d, PLAYER_ID) in speechplan.TRUST
    assert speechplan.mood_key(d) in speechplan.MOODS
    assert speechplan.condition_key(d) in speechplan.CONDITIONS
    assert speechplan.suspicion_key(d, PLAYER_ID) in speechplan.SUSPICION
    assert speechplan.heat_key({"aggression": 0.9}) == "hot"
    assert speechplan.heat_key({"aggression": 0.4}) == "edged"
    assert speechplan.heat_key({}) == "calm"


def test_being_broken_is_read_off_the_injuries_and_the_blood():
    w, d = dwarf_at("neutral")
    assert speechplan.condition_key(d) == "healthy"
    d.condition.add("bruised", 0.2, tick=0)
    assert speechplan.condition_key(d) == "bruised"
    d.condition.add("broken_arm", 0.4, tick=0)
    assert speechplan.condition_key(d) == "hurt"
    d.condition.add("broken_leg", 0.9, tick=0)
    assert speechplan.condition_key(d) == "badly_hurt"
    d.condition.injuries.clear()
    d.health = 0.3 * MAX_HEALTH
    assert speechplan.condition_key(d) == "badly_hurt"


def test_anger_and_fear_talk_over_everything_else():
    w, d = dwarf_at("close")
    d.mind.emotions.update(happiness=0.9, anger=0.9)
    assert speechplan.mood_key(d) == "angry"
    d.mind.emotions.update(anger=0.0, fear=0.9)
    assert speechplan.mood_key(d) == "afraid"
    d.mind.emotions.update(fear=0.0, grief=0.6)
    assert speechplan.mood_key(d) == "grieving"
    d.mind.emotions.update(grief=0.0)
    d.mind.needs["fatigue"] = 0.9
    assert speechplan.mood_key(d) == "tired"


def test_every_rule_names_a_real_act_and_the_table_always_answers():
    assert len(speechplan.ACTS) == 43 and len(set(speechplan.ACTS)) == 43
    for rule in speechplan.RULES:
        assert rule.act in speechplan.ACT_SET
    # the floor cannot miss: every intent, at every trust level, gets a construction
    for level in FEELINGS:
        for intent in speechplan.INTENTS:
            w, d = dwarf_at(level)
            plan = speechplan.plan(d, PLAYER_ID, labels(intent, "Something or other."), w)
            assert plan.act in speechplan.ACT_SET
            assert plan.reasons and plan.rule


def test_a_construction_carries_its_reasons_the_way_a_decision_carries_its_terms():
    w, d = dwarf_at("close")
    plan = speechplan.plan(d, PLAYER_ID, dict(SICK), w)
    assert plan.reasons[0] == ("misfortune.close", "their bad news, and I am close to them")
    assert len(plan.top_reasons(2)) == 2
    snap = plan.snapshot()
    assert snap["act"] == "SYMPATHIZE" and snap["rule"] == "misfortune.close"
    assert snap["hears"]["news"] == "MISFORTUNE" and snap["state"]["trust"] == "close"
    assert "speaker" in snap["slots"]


def test_the_plan_is_the_same_twice_for_the_same_state():
    w, d = dwarf_at("cold")
    parsed = labels("INSULT", "You couldn't swing a pick straight.", aggression=0.8,
                    valence=-0.7)
    state = dialogue.DialogueState(w.tick)
    a = speechplan.plan(d, PLAYER_ID, dict(parsed), w, state=state)
    b = speechplan.plan(d, PLAYER_ID, dict(parsed), w, state=state)
    assert a.act == b.act and a.rule == b.rule
