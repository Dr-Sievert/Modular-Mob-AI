"""The conversation's memory: turns, repeats, questions owed, and expiry.

``dwarfsim.dialogue`` is the one piece of the speech pipeline that cannot be recomputed from
state, so it is the one piece that has to be right. Everything here is about a pair of dwarves
and what they have already said to each other.
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from dwarfsim import dialogue, replies                    # noqa: E402
from dwarfsim.schema import PLAYER_ID                     # noqa: E402
from dwarfsim.world import World                          # noqa: E402


def world(seed=1, n=3):
    return World(n_agents=n, seed=seed)


def labels(intent="SMALLTALK", text="Something.", **kw):
    out = {"text": text, "intent": intent, "topic": "NONE", "addressed": "LISTENER",
           "aggression": 0.0, "valence": 0.0, "urgency": 0.0, "names": [],
           "sincerity": "SINCERE"}
    out.update(kw)
    return out


# ---------------------------------------------------------------------------
# turns
# ---------------------------------------------------------------------------


def test_a_state_is_made_on_first_use_and_kept_on_the_dwarf():
    w = world()
    dwarf = w.living()[0]
    assert dialogue.peek(dwarf, PLAYER_ID) is None
    state = dialogue.of(dwarf, PLAYER_ID, w.tick)
    assert dialogue.peek(dwarf, PLAYER_ID) is state
    assert dwarf.dialogue[PLAYER_ID] is state


def test_turns_are_kept_newest_last_and_bounded():
    state = dialogue.DialogueState(0)
    for i in range(dialogue.TURN_CAP + 4):
        state.note_heard(i, "SMALLTALK", "SPEAKER", "FACT", "NONE", text="line %d" % i)
    assert len(state.turns) == dialogue.TURN_CAP
    assert state.turns[-1]["tick"] == dialogue.TURN_CAP + 3
    assert state.turns[0]["tick"] == 4


def test_a_turn_records_everything_the_planner_reads_back():
    state = dialogue.DialogueState(0)
    state.note_heard(5, "SMALLTALK", "THIRD", "MISFORTUNE", "MINE",
                     entities=["Brokk"], text="Brokk's hurt.")
    turn = state.turns[-1]
    assert (turn["tick"], turn["who"], turn["intent"]) == (5, "them", "SMALLTALK")
    assert (turn["about"], turn["news"], turn["topic"]) == ("THIRD", "MISFORTUNE", "MINE")
    assert turn["entities"] == ("Brokk",)
    state.note_said(6, "ANSWER_THIRD", lid="ANSWER_THIRD:abc", about="THIRD")
    assert state.turns[-1]["who"] == "me" and state.turns[-1]["act"] == "ANSWER_THIRD"
    assert state.last_act() == "ANSWER_THIRD"


def test_what_was_last_said_about_whom_is_kept_per_subject():
    state = dialogue.DialogueState(0)
    state.note_heard(1, "SMALLTALK", "THIRD", "MISFORTUNE", "NONE", entities=["Brokk"])
    state.note_heard(2, "SMALLTALK", "THIRD", "FORTUNE", "NONE", entities=["Runa"])
    assert state.said_about("THIRD", ["Brokk"])["news"] == "MISFORTUNE"
    assert state.said_about("THIRD", ["Runa"])["news"] == "FORTUNE"
    assert state.said_about("WORLD") is None


# ---------------------------------------------------------------------------
# repetition, out of regard.py's own machinery
# ---------------------------------------------------------------------------


def test_the_same_line_twice_is_counted_and_a_different_one_is_not():
    state = dialogue.DialogueState(0)
    state.note_heard(1, "QUESTION", "WORLD", "SEEKING", "MINE", text="How is the mine?")
    sig = dialogue.turn_signature("QUESTION", "WORLD", "SEEKING", "MINE", "How is the mine?")
    assert state.heard_before(2, sig) == 1
    state.note_heard(2, "QUESTION", "WORLD", "SEEKING", "MINE", text="How is the mine?")
    assert state.heard_before(3, sig) == 2
    other = dialogue.turn_signature("QUESTION", "WORLD", "SEEKING", "MINE", "And the seam?")
    assert state.heard_before(3, other) == 0


def test_a_line_this_dwarf_has_just_used_is_worth_less_than_a_fresh_one():
    state = dialogue.DialogueState(0)
    assert state.line_freshness(1, "SYMPATHIZE", "a") == 1.0
    state.note_said(1, "SYMPATHIZE", "a")
    used = state.line_freshness(2, "SYMPATHIZE", "a")
    fresh = state.line_freshness(2, "SYMPATHIZE", "b")
    assert used < fresh <= 1.0


def test_the_ring_is_regards_ring_and_not_a_second_one():
    from dwarfsim import regard
    state = dialogue.DialogueState(0)
    assert isinstance(state.heard, regard.Regard)
    assert isinstance(state.said, regard.Regard)


# ---------------------------------------------------------------------------
# what is owed
# ---------------------------------------------------------------------------


def test_an_open_question_is_owed_until_it_is_answered_or_goes_stale():
    state = dialogue.DialogueState(0)
    sig = dialogue.turn_signature("QUESTION", "WORLD", "SEEKING", "MINE", "How is the mine?")
    state.open_question(1, "WORLD", "MINE", sig)
    assert len(state.owed(2)) == 1
    assert len(state.owed(1 + dialogue.QUESTION_TTL + 1)) == 0
    state.open_question(200, "WORLD", "MINE", sig)
    assert state.answer_question(sig) is not None
    assert state.owed(201) == []


def test_open_asks_are_tracked_both_ways_and_close():
    state = dialogue.DialogueState(0)
    state.note_ask(1, "o1", "bring 2 ore", mine=True, payment=3)
    state.note_ask(2, "o2", "fetch ale", mine=False)
    assert len(state.open_asks(mine=True)) == 1
    assert len(state.open_asks(mine=False)) == 1
    state.close_ask("o1", "KEPT")
    assert state.open_asks(mine=True) == []
    state.note_promise(3, "two ore by nightfall")
    assert len(state.promises) == 1


# ---------------------------------------------------------------------------
# expiry
# ---------------------------------------------------------------------------


def test_a_conversation_expires_after_a_long_silence():
    w = world()
    dwarf = w.living()[0]
    state = dialogue.of(dwarf, PLAYER_ID, 0)
    state.note_heard(0, "GREET", "NONE", "NONE", "NONE", text="Well met.")
    assert not state.expired(dialogue.SILENCE_EXPIRY)
    assert state.expired(dialogue.SILENCE_EXPIRY + 1)
    # and asking for it again past the window gives a fresh one, not the old turns
    later = dialogue.of(dwarf, PLAYER_ID, dialogue.SILENCE_EXPIRY + 2)
    assert later is not state and later.turns == []


def test_expire_drops_only_the_quiet_ones():
    w = world()
    dwarf = w.living()[0]
    old = dialogue.of(dwarf, "a", 0)
    old.touch(0)
    fresh = dialogue.of(dwarf, "b", 0)
    fresh.touch(500)
    dialogue.expire(dwarf, dialogue.SILENCE_EXPIRY + 10)
    assert dialogue.peek(dwarf, "a") is None
    assert dialogue.peek(dwarf, "b") is fresh


# ---------------------------------------------------------------------------
# through the pipeline
# ---------------------------------------------------------------------------


def test_saying_something_files_both_halves_of_the_exchange():
    w = world()
    dwarf = w.living()[0]
    replies.reply(w, dwarf, PLAYER_ID, labels("GREET", "Well met."))
    state = dialogue.peek(dwarf, PLAYER_ID)
    assert state is not None
    assert [t["who"] for t in state.turns] == ["them", "me"]
    assert state.turns[-1]["act"]


def test_a_question_is_owed_until_the_dwarf_answers_it():
    w = world()
    dwarf = w.living()[0]
    dwarf.mind.rel(PLAYER_ID)["hatred"] = 0.9        # it will not answer you at all
    replies.reply(w, dwarf, PLAYER_ID, labels("QUESTION", "How is the mine?", topic="MINE"))
    state = dialogue.peek(dwarf, PLAYER_ID)
    assert len(state.owed(w.tick)) == 1, "a deflected question is still owed"

    w2 = world()
    friendly = w2.living()[0]
    friendly.mind.rel(PLAYER_ID)["trust"] = 0.5
    replies.reply(w2, friendly, PLAYER_ID, labels("QUESTION", "How is the mine?", topic="MINE"))
    answered = dialogue.peek(friendly, PLAYER_ID)
    assert answered.owed(w2.tick) == [], "an answered question is not still owed"


def test_the_dwarf_does_not_repeat_the_same_line_inside_the_window():
    """Twenty different ways of saying the same kind of thing, to the same dwarf. The bank has
    fewer than twenty lines for any one act, so some repetition is inevitable; what must not
    happen is the same line twice in a row while another was available."""
    w = world()
    dwarf = w.living()[0]
    dwarf.mind.rel(PLAYER_ID)["trust"] = 0.5
    said = []
    for i in range(8):
        got = replies.reply(w, dwarf, PLAYER_ID,
                            labels("SMALLTALK", "The gate is rotten, number %d." % i,
                                   topic="HOME", valence=-0.4))
        if got["text"]:
            said.append(got["text"])
        w.tick += 1
    assert len(said) >= 4
    for a, b in zip(said, said[1:]):
        assert a != b, "it said the same line twice running"
