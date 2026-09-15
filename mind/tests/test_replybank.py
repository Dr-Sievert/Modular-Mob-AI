"""The bank, the chooser and the slot resolvers, plus the merge tool's rejections.

Three rules matter more than the rest and each has its own test: a line whose slot cannot be
filled is never chosen; a dwarf does not repeat itself to the same speaker inside the window; and
a line written for the far end of the trust spread never reaches the near end.
"""

import json
import os
import sys

import pytest

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, HERE)

from dwarfsim import dialogue, realize, replies, replybank, speechplan   # noqa: E402
from dwarfsim.schema import PLAYER_ID                                    # noqa: E402
from dwarfsim.world import World                                         # noqa: E402

sys.path.insert(0, os.path.join(HERE, "text"))


def world(seed=1, n=3):
    return World(n_agents=n, seed=seed)


def labels(intent="SMALLTALK", text="Something.", **kw):
    out = {"text": text, "intent": intent, "topic": "NONE", "addressed": "LISTENER",
           "aggression": 0.0, "valence": 0.0, "urgency": 0.0, "names": [],
           "sincerity": "SINCERE"}
    out.update(kw)
    return out


def record(act="SMALLTALK_BACK", text="Aye.", slots=(), **tags):
    hears = {"intent": "SMALLTALK", "about": "SPEAKER", "news": "FACT", "topic": "NONE",
             "sincerity": "SINCERE", "heat": "calm"}
    state = {"mood": "flat", "trust": "neutral", "condition": "healthy", "suspicion": "low"}
    for k, v in tags.items():
        (hears if k in replybank.HEARS_FIELDS else state)[k] = v
    return {"hears": hears, "state": state, "act": act, "slots": list(slots), "text": text}


# ---------------------------------------------------------------------------
# the file
# ---------------------------------------------------------------------------


def test_the_seed_bank_loads_and_covers_every_act_twice():
    bank = replybank.load()
    assert len(bank) >= 300
    assert bank.rejected == []
    assert bank.source in ("replies.jsonl", "replies_seed.jsonl")
    assert bank.missing_acts(least=2) == []
    assert sorted(bank.coverage()) == sorted(speechplan.ACTS)


def test_the_loader_prefers_the_merged_bank_and_says_which_it_used():
    bank = replybank.load()
    assert bank.source.endswith(".jsonl")
    if os.path.exists(replybank.BANK_PATH):
        assert bank.source == "replies.jsonl"
    else:
        assert bank.source == "replies_seed.jsonl", \
            "with no merged bank it must fall back to the seed and say so"


def test_every_seed_line_declares_exactly_the_slots_its_text_uses():
    bank = replybank.load()
    for line in bank.lines:
        assert set(line.slots) == set(realize.slots_in(line.text))
        for slot in line.slots:
            assert slot in realize.SLOT_NAMES


# ---------------------------------------------------------------------------
# parsing and rejection -- the same rules the merge tool applies
# ---------------------------------------------------------------------------


def test_a_line_with_an_undeclared_slot_is_rejected():
    with pytest.raises(replybank.BadLine) as exc:
        replybank.parse_line(record(text="Sit down, {speaker}.", slots=[]))
    assert "not declared" in str(exc.value)


def test_a_line_with_an_unknown_slot_is_rejected():
    with pytest.raises(replybank.BadLine) as exc:
        replybank.parse_line(record(text="The {weather} is foul.", slots=["weather"]))
    assert "unknown slot" in str(exc.value)


def test_a_line_declaring_a_slot_it_never_uses_is_rejected():
    with pytest.raises(replybank.BadLine) as exc:
        replybank.parse_line(record(text="Aye.", slots=["speaker"]))
    assert "never uses" in str(exc.value)


def test_a_bad_enum_is_rejected():
    for field, bad in (("act", "SHRUG"), ("trust", "friendly"), ("news", "GOSSIP"),
                       ("heat", "boiling"), ("mood", "cross")):
        obj = record()
        if field == "act":
            obj["act"] = bad
        elif field in replybank.HEARS_FIELDS:
            obj["hears"][field] = bad
        else:
            obj["state"][field] = bad
        with pytest.raises(replybank.BadLine):
            replybank.parse_line(obj)


def test_a_line_outside_one_to_twenty_five_words_is_rejected():
    with pytest.raises(replybank.BadLine):
        replybank.parse_line(record(text=" ".join(["ore"] * 26)))
    with pytest.raises(replybank.BadLine):
        replybank.parse_line(record(text="   "))


def test_the_merge_tool_rejects_a_real_name_a_bad_enum_and_an_undeclared_slot():
    import merge_replies

    with pytest.raises(replybank.BadLine) as named:
        merge_replies.validate(record(text="Sit down, Brokk, and take the weight off."))
    assert "Brokk" in str(named.value)

    with pytest.raises(replybank.BadLine):
        merge_replies.validate(record(act="SHRUG"))

    with pytest.raises(replybank.BadLine) as slot:
        merge_replies.validate(record(text="Take my shift at the {place}.", slots=[]))
    assert "not declared" in str(slot.value)

    # a SILENCE line has to actually be silence
    with pytest.raises(replybank.BadLine):
        merge_replies.validate(record(act="SILENCE", text="Nothing to say."))
    assert merge_replies.validate(record(act="SILENCE", text="..."))["text"] == "..."

    # and a good one comes back clean, in field order
    good = merge_replies.validate(record(act="SYMPATHIZE", text="That's hard, {speaker}.",
                                         slots=["speaker"]))
    assert list(good) == ["hears", "state", "act", "slots", "text"]
    assert good["slots"] == ["speaker"]


def test_the_merge_tool_keeps_silence_lines_apart_by_their_tags():
    import merge_replies

    a = record(act="SILENCE", text="...", trust="hostile")
    b = record(act="SILENCE", text="...", trust="cold")
    assert merge_replies.dedupe_key(a) != merge_replies.dedupe_key(b)
    assert merge_replies.dedupe_key(a) == merge_replies.dedupe_key(dict(a))


# ---------------------------------------------------------------------------
# choosing
# ---------------------------------------------------------------------------


def plan_for(w, dwarf, parsed, state=None):
    return speechplan.plan(dwarf, PLAYER_ID, parsed, w, state=state)


def test_a_line_whose_slot_cannot_be_filled_is_never_chosen():
    w = world()
    d = w.living()[0]
    d.mind.rel(PLAYER_ID)["trust"] = 0.5
    assert not d.condition, "this dwarf must start unhurt for the test to mean anything"

    bank = replybank.Bank.from_records([
        record(act="SYMPATHIZE", text="It's {injury} again, is it.", slots=["injury"],
               news="MISFORTUNE", trust="warm"),
        record(act="SYMPATHIZE", text="Hard going, that.", news="MISFORTUNE", trust="warm"),
    ])
    con = plan_for(w, d, labels("SMALLTALK", "I've been sick lately.", valence=-0.4))
    con.act = "SYMPATHIZE"
    for _ in range(10):
        got = replybank.choose(con, bank=bank, state=dialogue.DialogueState(w.tick))
        assert got["text"] == "Hard going, that."

    # break the arm and the other line becomes available
    d.condition.add("broken_arm", 0.6, tick=0)
    con2 = plan_for(w, d, labels("SMALLTALK", "I've been sick lately.", valence=-0.4))
    con2.act = "SYMPATHIZE"
    seen = set()
    for _ in range(30):
        seen.add(replybank.choose(con2, bank=bank, state=dialogue.DialogueState(w.tick),
                                  rng=w.rng)["text"])
    assert len(seen) == 2 and any("{" not in t for t in seen)


def test_nothing_that_reaches_a_dwarfs_mouth_still_has_a_brace_in_it():
    w = world()
    for level, feeling in (("hostile", {"trust": -0.6, "hatred": 0.6}),
                           ("cold", {"trust": -0.3}), ("neutral", {}),
                           ("warm", {"trust": 0.3}), ("close", {"trust": 0.8})):
        for intent in speechplan.INTENTS:
            d = w.living()[0]
            d.mind.rel(PLAYER_ID).update({"trust": 0.0, "hatred": 0.0})
            d.mind.rel(PLAYER_ID).update(feeling)
            dialogue.forget(d)
            got = replies.reply(w, d, PLAYER_ID, labels(intent, "A thing about the mine.",
                                                        topic="MINE"))
            assert "{" not in str(got["text"]), (level, intent, got["text"])


def test_a_dwarf_does_not_reach_across_the_trust_spread_for_a_cruel_line():
    w = world()
    d = w.living()[0]
    d.mind.rel(PLAYER_ID)["trust"] = 0.8
    bank = replybank.Bank.from_records([
        record(act="SYMPATHIZE", text="That's hard.", news="MISFORTUNE", trust="close"),
        record(act="SYMPATHIZE", text="Good. Saves me wishing it on you.",
               news="MISFORTUNE", trust="hostile"),
    ])
    con = plan_for(w, d, labels("SMALLTALK", "I've been sick lately.", valence=-0.4))
    con.act = "SYMPATHIZE"
    for _ in range(20):
        got = replybank.choose(con, bank=bank, state=dialogue.DialogueState(w.tick), rng=w.rng)
        assert got["text"] == "That's hard."


def test_reaching_across_the_spread_is_allowed_when_there_is_nothing_else_and_it_is_noted():
    w = world()
    d = w.living()[0]
    d.mind.rel(PLAYER_ID)["trust"] = 0.8
    bank = replybank.Bank.from_records([
        record(act="SYMPATHIZE", text="Good. Saves me wishing it on you.",
               news="MISFORTUNE", trust="hostile"),
    ])
    con = plan_for(w, d, labels("SMALLTALK", "I've been sick lately.", valence=-0.4))
    con.act = "SYMPATHIZE"
    got = replybank.choose(con, bank=bank, state=dialogue.DialogueState(w.tick), rng=w.rng)
    assert got["text"]
    assert any("trust spread" in n for n in got["notes"])


def test_a_repeated_line_loses_to_a_fresh_one():
    w = world()
    d = w.living()[0]
    bank = replybank.Bank.from_records([
        record(act="SMALLTALK_BACK", text="Aye."),
        record(act="SMALLTALK_BACK", text="Hm. That's the way of it."),
    ])
    state = dialogue.DialogueState(w.tick)
    con = plan_for(w, d, labels("SMALLTALK", "A plain remark."), state=state)
    con.act = "SMALLTALK_BACK"
    first = replybank.choose(con, bank=bank, state=state, rng=w.rng)
    state.note_said(w.tick, first["line"].act, first["line"].lid)
    second = replybank.choose(con, bank=bank, state=state, rng=w.rng)
    assert second["text"] != first["text"]


def test_the_score_is_three_named_terms_and_they_are_reported():
    w = world()
    d = w.living()[0]
    bank = replybank.load()
    con = plan_for(w, d, labels("GREET", "Well met."))
    got = replybank.choose(con, bank=bank, state=dialogue.DialogueState(w.tick), rng=w.rng)
    assert [name for name, _ in got["terms"]] == ["match", "repeat", "slots"]
    assert got["source"] == bank.source
    assert got["line"] is not None and got["line"].act == con.act


def test_an_act_the_bank_cannot_say_falls_back_and_says_so():
    w = world()
    d = w.living()[0]
    bank = replybank.Bank.from_records([record(act="SMALLTALK_BACK", text="Aye.")])
    con = plan_for(w, d, labels("SMALLTALK", "A plain remark."))
    con.act = "REMINISCE"
    got = replybank.choose(con, bank=bank, state=dialogue.DialogueState(w.tick), rng=w.rng)
    assert got["text"] == "Aye."
    assert any("fell back" in n for n in got["notes"])


# ---------------------------------------------------------------------------
# the slot resolvers
# ---------------------------------------------------------------------------


def test_every_slot_has_a_resolver_and_none_of_them_invent_anything():
    w = world()
    d = w.living()[0]
    scene = speechplan.scene_for(w, d, PLAYER_ID, labels("SMALLTALK", "A remark."))
    assert set(realize.RESOLVERS) == set(realize.SLOT_NAMES)
    for slot in realize.SLOT_NAMES:
        value = scene.value(slot)
        assert value is None or (isinstance(value, str) and value.strip())
    # a fresh dwarf remembers nothing and has nobody to blame
    assert scene.value("culprit") is None
    assert scene.value("memory") is None
    assert scene.value("injury") is None


def test_the_injury_and_the_culprit_come_from_the_body_and_the_book():
    w = world()
    d, other = w.living()[0], w.living()[1]
    d.condition.add("broken_arm", 0.7, tick=w.tick, by=other.id)
    scene = speechplan.scene_for(w, d, PLAYER_ID, labels("SMALLTALK", "A remark."))
    assert scene.value("injury") == "my arm"
    assert scene.value("culprit") == other.name


def test_the_bare_slots_are_bare_so_the_line_carries_the_grammar():
    w = world()
    d = w.living()[0]
    scene = speechplan.scene_for(w, d, PLAYER_ID, labels("SMALLTALK", "A remark.",
                                                         topic="MINE"))
    assert scene.value("place") == "mine"           # not "the mine"
    assert scene.value("item") == "ore"             # not "some ore"
    assert scene.value("price").isdigit()           # not "9 gold"


def test_fill_refuses_a_line_it_cannot_complete():
    w = world()
    d = w.living()[0]
    scene = speechplan.scene_for(w, d, PLAYER_ID, labels("SMALLTALK", "A remark."))
    assert scene.fill("Aye, {speaker}.").startswith("Aye, ")
    assert scene.fill("It's {injury} again.") is None
    assert scene.fill("Nothing to fill.") == "Nothing to fill."


# ---------------------------------------------------------------------------
# the learned chooser, which exists but is not the default
# ---------------------------------------------------------------------------


def test_the_ranker_builds_its_pairs_out_of_the_bank_and_scores_like_the_arbitrator():
    from dwarfsim.learn import ranker

    bank = replybank.load()
    rows = ranker.pairs(bank, negatives=3, seed=1)
    assert rows, "the bank must yield ranking problems"
    obs, cands, answer = rows[0]
    assert obs.shape == (ranker.OBS_SIZE,)
    assert all(c.shape == (ranker.CAND_SIZE,) for c in cands)
    assert 0 <= answer < len(cands)
    # the hand-weighted match is what a learned one has to beat, and it is computed the same way
    assert 0.0 <= ranker.baseline_accuracy(rows[:50], bank) <= 1.0

    line = bank.lines[0]
    vec = ranker.line_vector(line)
    assert vec[ranker.CAND_ACT + speechplan.ACTS.index(line.act)] == 1.0


def test_the_weighted_match_is_still_the_default():
    w = world()
    d = w.living()[0]
    con = plan_for(w, d, labels("GREET", "Well met."))
    got = replybank.choose(con, bank=replybank.load(),
                           state=dialogue.DialogueState(w.tick), rng=w.rng)
    assert [n for n, _ in got["terms"]][0] == "match", \
        "nothing may switch to the learned ranker on its own"


# ---------------------------------------------------------------------------
# determinism
# ---------------------------------------------------------------------------


def test_the_same_seed_and_the_same_line_give_the_same_reply():
    def play():
        w = world(seed=7)
        d = w.living()[0]
        d.mind.rel(PLAYER_ID)["trust"] = 0.5
        out = []
        for i in range(6):
            got = replies.reply(w, d, PLAYER_ID,
                                labels("SMALLTALK", "The gate is rotten, %d." % i,
                                       topic="HOME", valence=-0.4))
            out.append((got["kind"], got["text"]))
            w.tick += 1
        return out
    assert play() == play()


def test_the_seed_bank_file_is_jsonl_one_object_per_line():
    with open(replybank.SEED_PATH, encoding="utf-8") as fh:
        rows = [json.loads(line) for line in fh if line.strip()]
    assert len(rows) >= 300
    assert all(list(r) == ["hears", "state", "act", "slots", "text"] for r in rows)
