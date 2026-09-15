"""The console app, driven without a console.

Everything here goes through :class:`dwarfsim.talk.Session` -- ``say``, ``command`` and
``snapshot`` -- which is the whole app minus the terminal. The behaviour tests use hand-typed
labels rather than the classifier on purpose: ``text/models/clf`` is retrained in place, and a
test of what an insult does to anger has no business failing because a model file moved.
"""

import os
import sys

import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from dwarfsim import replies, talk, talk_ui  # noqa: E402
from dwarfsim.schema import PLAYER_ID  # noqa: E402

MISSING = os.path.join("text", "models", "no-such-classifier")


def labels(intent, text="...", **kw):
    """A hand-typed parsed utterance, the shape ``/labels`` builds."""
    out = {"text": text, "intent": intent, "topic": "NONE", "addressed": "LISTENER",
           "aggression": 0.0, "valence": 0.0, "urgency": 0.0, "names": []}
    out.update(kw)
    return out


def session(**kw):
    kw.setdefault("seed", 7)
    kw.setdefault("dwarves", 3)
    kw.setdefault("classifier", MISSING)     # nothing here needs the real model
    return talk.Session(**kw)


# ---------------------------------------------------------------------------
# What words do
# ---------------------------------------------------------------------------


def test_insult_raises_anger_and_costs_trust():
    s = session()
    who = s.focus.id
    before = s.snapshot()["dwarves"][who]
    s.say("You couldn't swing a pick straight.",
          parsed=labels("INSULT", "You couldn't swing a pick straight.",
                        aggression=0.9, valence=-0.8))
    after = s.snapshot()["dwarves"][who]
    assert after["emotions"]["anger"] > before["emotions"]["anger"]
    assert after["player"]["trust"] < before["player"]["trust"]
    assert after["player"]["hatred"] > before["player"]["hatred"]


def test_a_second_insult_lands_on_top_of_the_first():
    s = session()
    who = s.focus.id
    line = labels("INSULT", "Everyone knows what you are.", aggression=0.9, valence=-0.8)
    s.say(line["text"], parsed=dict(line))
    one = s.snapshot()["dwarves"][who]
    s.focus = s.world.agent(who)             # stay on the same dwarf whatever it did
    s.say(line["text"], parsed=dict(line))
    two = s.snapshot()["dwarves"][who]
    assert two["player"]["trust"] < one["player"]["trust"]
    assert two["player"]["hatred"] > one["player"]["hatred"]


def test_the_insult_entry_carries_the_deltas_it_caused():
    s = session()
    entry = s.say("Out of my way.",
                  parsed=labels("INSULT", "Out of my way.", aggression=0.8, valence=-0.7))
    assert entry["event"]["type"] == "INSULT"
    fields = {d["field"] for d in entry["deltas"]}
    assert "anger" in fields
    assert any(f.startswith("trust of") for f in fields)
    for d in entry["deltas"]:
        assert d["from"] != d["to"]


def test_a_gift_raises_trust():
    s = session()
    who = s.focus.id
    before = s.snapshot()["dwarves"][who]["player"]["trust"]
    entry = s.command("/give gold 5")
    after = s.snapshot()["dwarves"][who]["player"]["trust"]
    assert entry["event"]["type"] == "GIFT"
    assert after > before
    assert s.world.player.inv["gold"] == 15
    assert s.world.agent(who).inv["gold"] >= 5


def test_giving_ore_is_a_gift_too():
    s = session()
    who = s.focus
    before = who.inv["ore"]
    s.command("/give ore 2")
    assert who.inv["ore"] == before + 2
    assert s.snapshot()["dwarves"][who.id]["player"]["trust"] > 0.0


def test_hitting_costs_health_and_trust():
    s = session()
    who = s.focus
    entry = s.command("/hit")
    assert entry["event"]["type"] == "HIT"
    assert who.health < 20.0
    assert s.snapshot()["dwarves"][who.id]["player"]["hatred"] > 0.0


# ---------------------------------------------------------------------------
# Commands
# ---------------------------------------------------------------------------


def test_tick_advances_and_summarises():
    s = session()
    before = s.world.tick
    entry = s.command("/tick 30")
    assert s.world.tick == before + 30
    assert entry["kind"] == "tick"
    assert isinstance(entry["story"], list)
    assert entry["decisions"], "the focused dwarf should have decided something"


def test_to_switches_focus_and_a_named_dwarf_switches_it_by_itself():
    s = session()
    other = [a for a in s.world.living() if a.id != s.focus.id][0]
    s.command("/to " + other.name.lower())
    assert s.focus.id == other.id
    third = [a for a in s.world.living() if a.id != other.id][0]
    s.say("%s, a word." % third.name,
          parsed=labels("SMALLTALK", "%s, a word." % third.name, names=[third.name]))
    assert s.focus.id == third.id


def test_unknown_command_prints_the_help():
    s = session()
    entry = s.command("/nonsense")
    assert entry["kind"] == "help"
    assert entry["help"]
    assert any("no such command" in n for n in entry["notes"])


def test_why_and_mind_and_all():
    s = session()
    s.command("/tick 5")
    why = s.command("/why")
    assert why["kind"] == "why" and why["terms"]
    assert any(c["won"] for c in why["candidates"])
    mind = s.command("/mind")
    assert mind["state"]["name"] == s.focus.name
    every = s.command("/all")
    assert len(every["rows"]) == len(s.world.living())


def test_quit_stops_the_session():
    s = session()
    s.command("/quit")
    assert s.running is False


# ---------------------------------------------------------------------------
# The classifier, and doing without it
# ---------------------------------------------------------------------------


def test_a_missing_classifier_is_a_message_not_a_crash():
    s = session()
    entry = s.say("you are a fool")
    assert entry["kind"] == "error"
    assert any("/labels" in n for n in entry["notes"])
    assert s.snapshot()["classifier"] == "unavailable"


def test_a_missing_numpy_is_one_line_not_a_traceback(monkeypatch):
    """A fresh clone with nothing pip-installed is told what to install, and keeps /labels."""
    real_import = __builtins__["__import__"] if isinstance(__builtins__, dict) \
        else __builtins__.__import__

    def no_numpy(name, *args, **kw):
        if name == "numpy" or name.startswith("numpy."):
            raise ImportError("No module named 'numpy'")
        return real_import(name, *args, **kw)

    monkeypatch.delitem(sys.modules, "text.classifier.infer", raising=False)
    monkeypatch.setattr("builtins.__import__", no_numpy)
    interp = talk.Interpreter(path=MISSING)
    assert interp.load() is None
    assert interp.error == talk.NUMPY_HINT
    parsed, notes = interp.parse("you are a fool")
    assert parsed is None
    assert notes[0] == talk.NUMPY_HINT
    assert any("/labels" in n for n in notes)


def test_hand_labels_work_with_no_classifier():
    s = session()
    who = s.focus.id
    before = s.snapshot()["dwarves"][who]["emotions"]["anger"]
    entry = s.command("/labels intent=INSULT aggression=0.9 valence=-0.8 "
                      "text=You couldn't swing a pick straight.")
    assert entry["kind"] == "say"
    assert entry["source"] == "hand"
    assert entry["parsed"]["intent"] == "INSULT"
    assert entry["parsed"]["aggression"] == 0.9
    assert s.snapshot()["dwarves"][who]["emotions"]["anger"] > before


def test_a_command_with_a_topic_becomes_a_real_ask():
    s = session()
    entry = s.command("/labels intent=COMMAND topic=MINE urgency=0.8 "
                      "text=Fetch me ore from the mine.")
    assert entry["parsed"]["ask"]["action"] == "BRING"
    assert entry["event"]["type"] == "ASK"
    assert s.world._open_obl, "the ask should be on the books"
    s.command("/labels intent=COMMAND topic=NONE ask=none text=Do something.")
    assert s.feed[-1]["parsed"].get("ask") is None


def test_junk_labels_are_read_as_something_legal():
    parsed, notes = talk.clean_parsed(
        {"intent": "SHOUTING", "topic": "MOON", "addressed": "SOMEBODY",
         "aggression": 5.0, "valence": -9.0, "urgency": "x"}, "Brokk, hello")
    assert parsed["intent"] == "SMALLTALK" and parsed["topic"] == "NONE"
    assert parsed["addressed"] == "LISTENER"
    assert parsed["aggression"] == 1.0 and parsed["valence"] == -1.0
    assert parsed["names"] == ["Brokk"]
    assert len(notes) == 3


def test_parse_ask():
    ask = talk.parse_ask("BRING:ore:2@MINE:pay4")
    assert ask == {"action": "BRING", "item": "ore", "quantity": 2, "place": "MINE",
                   "target": None, "payment": 4}
    assert talk.parse_ask("DANCE") is None


def test_the_real_classifier_if_it_is_there():
    s = talk.Session(seed=7, dwarves=3)
    if s.interp.load() is None:
        pytest.skip("no classifier: %s" % s.interp.error)
    entry = s.say("You are a worthless drunk.")
    assert entry["kind"] == "say"
    assert entry["source"] == "classifier"
    assert entry["parsed"]["intent"] in talk.INTENTS


# ---------------------------------------------------------------------------
# Rendering
# ---------------------------------------------------------------------------


def _busy_session():
    s = session()
    s.command("/labels intent=INSULT aggression=0.9 valence=-0.8 text=You fat-handed oaf.")
    s.command("/give gold 3")
    s.command("/tick 12")
    s.command("/why")
    s.command("/mind")
    s.command("/all")
    s.command("/help")
    return s


def test_render_returns_a_string_with_rich():
    if talk_ui.rich_modules() is None:
        pytest.skip("rich is not installed")
    out = talk_ui.render(_busy_session(), width=120)
    assert isinstance(out, str) and out.strip()
    assert "conversation" in out


def test_render_returns_a_string_without_rich(monkeypatch):
    monkeypatch.setattr(talk_ui, "rich_modules", lambda: None)
    s = _busy_session()
    out = talk_ui.render(s, width=100)
    assert isinstance(out, str)
    assert "THE DWARF" in out and "THE CONVERSATION" in out
    assert s.focus.name in out
    # and the explicit no-rich switch takes the same path
    assert "THE DWARF" in talk_ui.render(s, use_rich=False, width=100)


def test_render_survives_an_empty_session():
    assert talk_ui.render(session(), use_rich=False)
    assert "nothing worth remembering yet" in talk_ui.render(session(), use_rich=False)


def _chatty_session(n=200):
    """A session with a long conversation behind it, longer than any panel."""
    s = session(ticks_per_say=0)
    for i in range(n):
        s.command("/labels intent=SMALLTALK text=line number %d, about the ore and the seam" % i)
    return s


def test_the_conversation_panel_shows_the_newest_not_the_oldest():
    """200 exchanges: the last one is on screen and the first one is long gone."""
    s = _chatty_session()
    lines = talk_ui.conversation_lines(s.snapshot(), limit=40, height=20, width=70)
    text = " ".join(t for _, _, t in lines)
    assert "line number 199" in text
    assert "line number 0," not in text
    # and it really did fit: the tail is measured as it will be drawn, wrapping included
    assert talk_ui.rendered_height(lines, 70) <= 20 + talk_ui.rendered_height(
        talk_ui.entry_lines(s.feed[-1]), 70)
    plain = talk_ui.render(s, use_rich=False, width=90)
    assert "line number 199" in plain and "line number 0," not in plain


def test_the_newest_exchange_is_drawn_whole_even_when_it_does_not_fit():
    s = _chatty_session(3)
    lines = talk_ui.conversation_lines(s.snapshot(), limit=40, height=1, width=70)
    assert " ".join(t for _, _, t in lines).count("line number 2,") >= 1


def test_the_no_rich_prompt_prints_only_what_is_new():
    s = _chatty_session(5)
    mark = s.feed[-1]["n"]
    s.command("/labels intent=SMALLTALK text=one more word about the forge")
    out = talk_ui.render(s, use_rich=False, width=90, since=mark)
    assert "SINCE THE LAST PROMPT" in out
    assert "one more word about the forge" in out
    assert "line number 0," not in out
    # nothing new at all says so rather than reprinting the lot
    assert "(nothing new)" in talk_ui.render(s, use_rich=False, width=90,
                                             since=s.feed[-1]["n"])


def test_log_prints_the_tail_in_full_and_clear_empties_the_panel():
    s = _chatty_session(20)
    entry = s.command("/log 3")
    assert entry["kind"] == "log" and len(entry["show"]) == 3
    out = talk_ui.render(s, use_rich=False, width=90)
    for i in (17, 18, 19):
        assert ("line number %d," % i) in out
    assert s.command("/log x")["kind"] == "error"
    s.command("/clear")
    assert [e["kind"] for e in s.feed] == ["info"]
    assert "line number 19," not in talk_ui.render(s, use_rich=False, width=90)


def test_run_script_prints_a_transcript():
    import io
    s = session()
    out = io.StringIO()
    talk_ui.run_script(s, ["# a comment", "/all", "/tick 5", "/quit", "/tick 5"],
                       use_rich=False, width=90, out=out)
    text = out.getvalue()
    assert "Three things to try" in text
    assert "/tick 5" in text
    assert s.world.tick == 5, "everything after /quit is not played"


# ---------------------------------------------------------------------------
# What the dwarf says back
# ---------------------------------------------------------------------------


def _feel(dwarf, trust=0.0, hatred=0.0, anger=0.05, fear=0.05, happiness=0.4):
    r = dwarf.mind.rel(PLAYER_ID)
    r["trust"], r["hatred"] = trust, hatred
    dwarf.mind.emotions.update(anger=anger, fear=fear, happiness=happiness)
    return dwarf


def _said(session, dwarf, intent, **kw):
    return replies.reply(session.world, dwarf, PLAYER_ID, labels(intent, **kw))


def _filled(variants, you="Player"):
    return {v.format(me="?", you=you, topic="day", them=you) for v in variants if v}


def test_the_reply_never_disagrees_with_the_arbitrator():
    s = session()
    who = _feel(s.focus, trust=0.9)          # as well disposed as it gets
    ask = labels("COMMAND", "Fetch me ore.", topic="MINE", urgency=0.8)
    for decided in ("ACCEPT", "REFUSE", "BARGAIN", "FULFIL"):
        got = replies.reply(s.world, who, PLAYER_ID, ask, decided=decided)
        assert got["kind"] == decided
        assert got["text"] in _filled(replies.DECIDED[decided])
    refused = replies.reply(s.world, who, PLAYER_ID, ask, decided="REFUSE")
    assert refused["text"] not in _filled(replies.DECIDED["ACCEPT"])


def test_an_undecided_ask_is_never_answered_with_a_yes():
    s = session()
    who = _feel(s.focus, trust=0.9)
    yes = _filled(replies.DECIDED["ACCEPT"]) | _filled(replies.DECIDED["FULFIL"])
    for _ in range(40):
        for intent in ("REQUEST", "COMMAND", "OFFER"):
            got = replies.reply(s.world, who, PLAYER_ID,
                                labels(intent, "Fetch me ore.", topic="MINE"))
            assert got["text"] not in yes


def test_the_sims_own_line_is_the_answer_and_there_is_not_a_second_one():
    s = session()
    got = replies.reply(s.world, s.focus, PLAYER_ID, labels("INSULT"),
                        already="Say that again, Player, and see what it earns you.")
    assert got["text"] is None
    assert got["kind"] == "ALREADY"
    assert "own line" in got["note"]


def test_trust_changes_the_wording():
    s = session()
    friend = _said(s, _feel(s.focus, trust=0.8), "GREET", text="Well met.")
    enemy = _said(s, _feel(s.focus, trust=-0.5, hatred=0.8), "GREET", text="Well met.")
    assert friend["stance"] == "FRIEND" and enemy["stance"] == "ENEMY"
    assert friend["text"] in _filled(replies.REPLIES["GREET"][("FRIEND", "*")])
    # cold, or nothing at all -- but never the warm greeting
    assert enemy["text"] not in _filled(replies.REPLIES["GREET"][("FRIEND", "*")])
    assert enemy["text"] is not None or "said nothing" in enemy["note"]


def test_praise_is_thanked_or_suspected_depending_on_trust():
    s = session()
    warm = _said(s, _feel(s.focus, trust=0.8), "PRAISE", text="Fine work.", valence=0.8)
    cold = _said(s, _feel(s.focus, trust=-0.4), "PRAISE", text="Fine work.", valence=0.8)
    assert warm["cell"][0] == "FRIEND"
    assert cold["cell"][0] in ("COLD", "ENEMY")
    assert warm["text"] != cold["text"]


def test_mood_changes_the_wording():
    s = session()
    calm = _said(s, _feel(s.focus, trust=0.2), "GREET", text="Well met.")
    angry = _said(s, _feel(s.focus, trust=0.2, anger=0.85), "GREET", text="Well met.")
    afraid = _said(s, _feel(s.focus, trust=0.2, fear=0.85), "GREET", text="Well met.")
    assert (calm["mood"], angry["mood"], afraid["mood"]) == ("FLAT", "ANGRY", "AFRAID")
    assert angry["text"] in _filled(replies.REPLIES["GREET"][("*", "ANGRY")])
    assert afraid["text"] in _filled(replies.REPLIES["GREET"][("*", "AFRAID")])
    assert angry["text"] != calm["text"]


def test_a_question_is_answered_by_topic_and_coloured_by_mood():
    s = session()
    who = _feel(s.focus, trust=0.3, happiness=0.9)
    got = _said(s, who, "QUESTION", text="How is the mine?", topic="MINE")
    assert got["kind"] == "ANSWER"
    assert any(got["text"].startswith(a) for a in replies.ANSWERS["MINE"])
    assert got["cell"] == ("MINE", "GLAD")
    hated = _said(s, _feel(s.focus, hatred=0.8), "QUESTION", text="How is the mine?",
                  topic="MINE")
    assert hated["kind"] == "QUESTION"       # it will not answer you at all
    assert not any(str(hated["text"]).startswith(a) for a in replies.ANSWERS["MINE"])


def test_sarcasm_is_noticed():
    s = session()
    got = _said(s, _feel(s.focus, trust=0.3), "PRAISE", text="Nice swing, genius.",
                sincerity="SARCASTIC")
    assert got["kind"] == "SARCASM"
    assert got["text"] in _filled(sum((list(v) for v in replies.SARCASM.values()), []))


def test_a_third_party_named_can_be_picked_up():
    s = session()
    other = [a for a in s.world.living() if a.id != s.focus.id][0]
    seen = set()
    for _ in range(60):
        got = _said(s, _feel(s.focus, trust=0.3), "SMALLTALK",
                    text="Cold today, %s." % other.name, names=[other.name])
        seen.add(other.name in str(got["text"]))
    assert True in seen, "the other dwarf's name should turn up in some of the answers"


def test_every_intent_stance_and_mood_produces_something():
    s = session()
    who = s.focus
    for intent in talk.INTENTS:
        for stance in replies.STANCES:
            for mood in replies.MOODS:
                _feel(who,
                      trust={"FRIEND": 0.8, "WARM": 0.3, "NEUTRAL": 0.0,
                             "COLD": -0.4, "ENEMY": -0.6}[stance],
                      hatred=0.8 if stance == "ENEMY" else 0.0,
                      anger=0.9 if mood == "ANGRY" else 0.05,
                      fear=0.9 if mood == "AFRAID" else 0.05,
                      happiness=0.9 if mood == "GLAD" else 0.2)
                got = replies.reply(s.world, who, PLAYER_ID, labels(intent, "Something."))
                assert got["mood"] == mood and got["stance"] == stance
                assert got["text"] is not None or got["note"]
                assert "{" not in str(got["text"])


def test_every_line_the_player_says_gets_an_answer():
    s = session()
    for intent in ("GREET", "QUESTION", "PRAISE", "SMALLTALK", "FAREWELL", "WARNING"):
        entry = s.say("something", parsed=labels(intent, "something", topic="MINE"))
        spoke = [line for line in entry["lines"] if line["kind"] == "REPLY"]
        assert spoke or entry["reply"]["note"], "%s got nothing back" % intent


def test_the_session_keeps_the_reply_consistent_with_the_decision():
    """The end-to-end half of the same rule: whatever the arbitrator chose about the player,
    the words that reach the screen are that skill's words or the skill's own line."""
    seen = 0
    for seed in range(1, 12):
        s = session(seed=seed)
        s.command("/labels intent=INSULT aggression=0.9 valence=-0.8 text=Out of my way.")
        s.command("/labels intent=COMMAND topic=MINE urgency=0.8 text=Fetch me ore.")
        entry = s.feed[-1]
        skill, answer = entry.get("answer_skill"), entry["reply"]
        if skill in replies.DECIDED:
            seen += 1
            assert answer["kind"] in (skill, "ALREADY")
            if answer["kind"] == skill and skill == "REFUSE":
                assert answer["text"] not in _filled(replies.DECIDED["ACCEPT"])
    assert seen, "no seed got as far as a decision about the player"


# ---------------------------------------------------------------------------
# The seed
# ---------------------------------------------------------------------------


def test_the_same_seed_and_the_same_typing_give_the_same_settlement():
    def play():
        s = session(seed=11)
        s.command("/labels intent=INSULT aggression=0.8 valence=-0.7 text=Out of my way.")
        s.command("/tick 40")
        snap = s.snapshot()
        return [(d["name"], d["place"], d["health"], round(d["emotions"]["anger"], 6))
                for d in snap["dwarves"].values()]
    assert play() == play()
