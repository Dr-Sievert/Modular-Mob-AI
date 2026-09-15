"""The profanity system: what is recognised and what is said.

1. every tier of ``text/profanity.json`` is *recognised* -- a term in a line raises aggression,
   and a tier 3 term makes the event a ``SLUR``;
2. what a dwarf *says* at tier 3 comes from ``text/profanity_speech.json`` as written, or from
   :data:`dwarfsim.profanity.IN_WORLD_SLURS` if that file is missing.

Nothing in this file writes a recognition-file slur out by hand: those cases read one out of
the user's file at run time. Files the tests need are written to ``tmp_path``.
"""

import json
import os
import re
import sys

import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from dwarfsim import profanity, replies, speech            # noqa: E402
from dwarfsim.talk import Session                          # noqa: E402
from dwarfsim.world import World                           # noqa: E402

FILE = profanity.DEFAULT_PATH


def lexicon(max_tier=3):
    """The user's file, with nothing of the user's speech file in it."""
    return profanity.Lexicon.load(FILE, max_tier=max_tier, speech_path=None)


def write_lexicon(tmp_path, tiers, name="lex.json"):
    """A little lexicon of invented terms, in the user file's shape."""
    path = tmp_path / name
    path.write_text(json.dumps({"tiers": tiers}), encoding="utf-8")
    return str(path)


def a_tier3_term():
    """One term out of the user's tier 3, read at run time and never written down here."""
    lex = lexicon()
    rows = [t for t in lex.tiers[3] if "*" not in t.term and " " not in t.term]
    assert rows, "the user's file has no plain tier 3 term to test with"
    return rows[0].term


def spoken(world, ticks):
    """Every line anything said in ``ticks`` ticks, with the profanity each one carried."""
    out = []
    for _ in range(ticks):
        for ev in world.step()["events"]:
            if ev.get("text"):
                out.append((ev["type"], ev["text"], ev.get("profanity") or []))
    return out


def feud(tier, seed=1, speech_path=None):
    return World(n_agents=4, seed=seed, scenario="feud", profanity_tier=tier,
                 profanity_speech=speech_path)


# ---------------------------------------------------------------------------
# Scanning
# ---------------------------------------------------------------------------


def test_scan_finds_whole_words_and_phrases_and_not_substrings(tmp_path):
    path = write_lexicon(tmp_path, {"1": [
        {"term": "blast", "kind": "expletive", "targets": "none", "strength": 0.2},
        {"term": "by the broken anvil", "kind": "expletive", "targets": "none", "strength": 0.3},
    ]})
    lex = profanity.Lexicon.load(path, speech_path=None)

    assert lex.scan("Blast, I misread the seam.") == [("blast", 1, 0.2)]
    assert lex.scan("BLAST!") == [("blast", 1, 0.2)]
    assert lex.scan("By the broken anvil, that rang wrong.") == [
        ("by the broken anvil", 1, 0.3)]

    # the same letters inside another word are not the word
    assert lex.scan("The blasted winch jammed.") == []
    assert lex.scan("sandblasting the flue") == []
    assert lex.scan("ablast") == []


def test_a_star_is_a_one_character_wildcard(tmp_path):
    path = write_lexicon(tmp_path, {"2": [
        {"term": "b*rk", "kind": "insult", "targets": "person", "strength": 0.5},
    ]})
    lex = profanity.Lexicon.load(path, speech_path=None)

    for line in ("b*rk", "bark", "b4rk", "b#rk", "BARK"):
        assert lex.scan(line) == [("b*rk", 2, 0.5)], line
    assert lex.scan("barking") == []
    assert lex.scan("embark") == []
    assert lex.scan("b rk") == []                 # a wildcard is a character, not a gap


def test_a_wildcard_does_not_swallow_ordinary_words_when_the_plain_form_is_listed(tmp_path):
    """``h*ll`` and ``hell`` are both in the user's file, and a mine is full of halls.

    A wildcard whose spelled-out form the file already lists keeps the substitutions and gives
    up the letters, which is the difference between a lexicon and a lexicon that cries wolf.
    """
    path = write_lexicon(tmp_path, {"1": [
        {"term": "h*ll", "kind": "expletive", "targets": "none", "strength": 0.2},
        {"term": "hell", "kind": "expletive", "targets": "none", "strength": 0.2},
    ]})
    lex = profanity.Lexicon.load(path, speech_path=None)

    assert lex.scan("The hall is cold.") == []
    assert lex.scan("Hell, that draft is new.") == [("hell", 1, 0.2)]
    assert lex.scan("H*ll, that draft is new.") == [("h*ll", 1, 0.2)]

    # and the user's own file, which is where that pair actually lives
    real = lexicon()
    assert real.scan("This hall has had enough of you.") == []
    assert [t for t, n, s in real.scan("Hell, the hall is cold.")] == ["hell"]


def test_the_longest_match_wins_and_the_file_loads(tmp_path):
    lex = lexicon()
    assert len(lex.tiers[1]) > 50 and len(lex.tiers[2]) > 50 and len(lex.tiers[3]) > 50
    hits = lex.scan("What the hell happened to the ore bins?")
    assert [t for t, n, s in hits] == ["what the hell"]        # not "hell" on its own

    missing = profanity.Lexicon.load(str(tmp_path / "nothing.json"), speech_path=None)
    assert missing.scan("anything at all") == []
    assert missing.speech_terms(1) == [] and missing.speech_terms(3)      # in-world list stands


# ---------------------------------------------------------------------------
# Recognition: what a term does to the labels and to the listener
# ---------------------------------------------------------------------------


def _say(world, text, aggression=0.0, intent="INSULT"):
    """The player says one line to the first dwarf. Returns ``(event, parsed)``."""
    parsed = {"text": text, "intent": intent, "topic": "NONE", "addressed": "LISTENER",
              "aggression": aggression, "valence": 0.0, "urgency": 0.0, "names": []}
    return world.say("player", world.agents[0], parsed), parsed


def test_a_tier_2_term_raises_aggression_to_the_terms_strength():
    world = World(n_agents=3, seed=2, profanity_tier=0)
    term = [t for t in lexicon().tiers[2] if "*" not in t.term and " " not in t.term][0]
    ev, parsed = _say(world, "Move your cart, %s." % term.term, aggression=0.1)
    assert ev["type"] == "INSULT"
    assert parsed["aggression"] == pytest.approx(term.strength)
    assert parsed["profanity"] == [{"term": term.term, "tier": 2,
                                    "strength": round(term.strength, 3),
                                    "kind": term.kind, "targets": term.targets}]
    assert parsed["profanity_bump"] == [0.1, round(term.strength, 3)]


def test_a_name_for_somebody_is_an_insult_whatever_the_classifier_read():
    """The bug this was written for: "dumb cunt" read as SMALLTALK, and the dwarf cheered up.

    A term whose kind is an insult and whose targets is a person is what the line was, so the
    intent is overruled, the valence goes negative and a line the classifier aimed at nobody is
    taken as aimed at whoever is being spoken to.
    """
    world = World(n_agents=3, seed=2, profanity_tier=0)
    term = [t for t in lexicon().tiers[2]
            if t.kind == "insult" and t.targets == "person" and "*" not in t.term][0]
    parsed = {"text": "dumb %s" % term.term, "intent": "SMALLTALK", "topic": "NONE",
              "addressed": "NONE", "aggression": 0.0, "valence": -0.01, "urgency": 0.0,
              "names": []}
    before = dict(world.agents[0].mind.emotions)
    ev = world.say("player", world.agents[0], parsed)

    assert ev["type"] == "INSULT"
    assert parsed["intent"] == "INSULT"
    assert parsed["intent_override"] == ["SMALLTALK", "INSULT"]
    assert parsed["addressed"] == "LISTENER"
    assert parsed["addressed_override"] == ["NONE", "LISTENER"]
    assert parsed["valence"] <= -0.5
    assert parsed["aggression"] == pytest.approx(term.strength)
    assert world.agents[0].mind.emotions["anger"] > before["anger"]
    assert world.agents[0].mind.emotions["happiness"] < before["happiness"]

    from dwarfsim import talk_ui
    read = talk_ui._parsed_line(parsed)
    assert "intent SMALLTALK -> INSULT (lexicon)" in read
    assert "to NONE -> LISTENER (lexicon)" in read


def test_an_oath_about_the_world_is_not_an_insult():
    """"What the hell happened to the ore bins" stays the question it was."""
    world = World(n_agents=3, seed=2, profanity_tier=0)
    parsed = {"text": "What the hell happened to the ore bins?", "intent": "QUESTION",
              "topic": "MINE", "addressed": "LISTENER", "aggression": 0.1, "valence": 0.0,
              "urgency": 0.3, "names": []}
    before = dict(world.agents[0].mind.emotions)
    ev = world.say("player", world.agents[0], parsed)

    assert ev["type"] == "SMALLTALK"                 # a QUESTION is small talk to the sim
    assert parsed["intent"] == "QUESTION"
    assert "intent_override" not in parsed and "addressed_override" not in parsed
    assert parsed["valence"] == 0.0
    assert parsed["aggression"] == pytest.approx(0.25)    # "what the hell", and nothing else
    assert world.agents[0].mind.emotions["anger"] == before["anger"]


def test_a_tier_3_term_is_a_slur_event_with_aggression_one_and_a_bigger_delta():
    term = a_tier3_term()

    plain = World(n_agents=3, seed=2, profanity_tier=0)
    ev_plain, _ = _say(plain, "You couldn't swing a pick straight.", aggression=0.6)

    slurred = World(n_agents=3, seed=2, profanity_tier=0)
    ev_slur, parsed = _say(slurred, "Get out of my forge, %s." % term, aggression=0.6)

    assert ev_plain["type"] == "INSULT"
    assert ev_slur["type"] == "SLUR"
    assert parsed["aggression"] == 1.0
    assert parsed["valence"] <= -0.8

    def moved(world, ev, field):
        for d in ev["deltas"]:
            if d["who"] == world.agents[0].id and d["f"] == field:
                return d["to"] - d["from"]
        return 0.0

    assert (moved(slurred, ev_slur, "anger")
            > moved(plain, ev_plain, "anger") * 1.3)
    assert (moved(slurred, ev_slur, "rel:player:hatred")
            > moved(plain, ev_plain, "rel:player:hatred") * 1.3)
    assert speech.magnitude_of(parsed) > speech.magnitude_of(
        {"intent": "INSULT", "aggression": 0.6, "valence": 0.0})


def test_witnesses_react_to_a_slur_as_they_react_to_an_insult():
    from dwarfsim.mind import EVENT_TABLE
    assert EVENT_TABLE["SLUR"]["witness"] == EVENT_TABLE["INSULT"]["witness"]
    assert EVENT_TABLE["SLUR"]["target"]["anger"] > EVENT_TABLE["INSULT"]["target"]["anger"]
    assert (EVENT_TABLE["SLUR"]["target_rel"]["hatred"]
            > EVENT_TABLE["INSULT"]["target_rel"]["hatred"])


def test_a_slur_is_a_provocation_the_reactions_can_answer():
    from dwarfsim.memory import HARM_KINDS, PROVOCATION_KINDS
    assert "SLUR" in HARM_KINDS and "SLUR" in PROVOCATION_KINDS

    world = World(n_agents=3, seed=5, profanity_tier=0)
    _say(world, "Get out of my forge, %s." % a_tier3_term(), aggression=0.5)
    world.sense()
    assert [m.kind for m in world.agents[0].provocations] == ["SLUR"]


def test_the_sim_and_the_talk_app_read_a_line_the_same_way():
    """One screening path, so what you type and what a dwarf hears cannot disagree."""
    term = a_tier3_term()
    session = Session(seed=4, dwarves=3, profanity_tier=0, ticks_per_say=0)
    entry = session.command("/labels intent=SMALLTALK text=Out of my way, %s." % term)
    assert entry["event"]["type"] == "SLUR"
    assert entry["parsed"]["aggression"] == 1.0
    assert entry["parsed"]["profanity_bump"][1] == 1.0

    from dwarfsim import talk_ui
    read = talk_ui._parsed_line(entry["parsed"])
    assert "profanity: tier 3 x1" in read
    assert term not in read                       # the screen does not repeat it back at you
    assert "aggression 0.00 -> 1.00" in read


# ---------------------------------------------------------------------------
# Speech: how far a dwarf will go, and never further
# ---------------------------------------------------------------------------


def test_tier_zero_never_swears_in_five_hundred_ticks_of_feud():
    lex = lexicon()
    lines = spoken(feud(0), 500)
    assert lines, "the feud said nothing at all, so this proves nothing"
    assert [l for l in lines if l[2]] == []
    assert [l for l in lines if lex.scan(l[1])] == []


def test_tier_two_swears_and_tier_one_stays_mild():
    lex = lexicon()
    mild = spoken(feud(1), 500)
    crude = spoken(feud(2), 500)

    assert [l for l in mild if l[2]], "tier 1 said nothing rude in a whole feud"
    assert max(r["tier"] for l in mild for r in l[2]) == 1
    assert [l for l in crude if l[2] and max(r["tier"] for r in l[2]) >= 2]
    assert max(r["tier"] for l in crude for r in l[2]) == 2

    # and everything either of them said is something the lexicon can read back
    for _, text, rows in mild + crude:
        for r in rows:
            assert lex.scan(text), text


def test_without_a_speech_file_tier_three_is_the_in_world_list(tmp_path):
    missing = str(tmp_path / "nope.json")
    in_world = {t.term for t in profanity.in_world_terms()}
    said_a_slur = False
    for seed in range(1, 7):
        for kind, text, rows in spoken(feud(3, seed=seed, speech_path=missing), 500):
            for r in rows:
                if r["tier"] == 3:
                    said_a_slur = True
                    assert r["term"] in in_world
    assert said_a_slur, "tier 3 was set and no dwarf ever reached for it"


def test_the_default_speech_file_is_what_tier_three_says():
    lex = profanity.Lexicon.load(FILE, max_tier=3)
    allowed = {t.term for t in lex.speech_terms(3)}
    users = {t.term for t in lexicon().tiers[3]}
    assert lex.speech_source == "file"
    assert allowed & users, "the speech file should share terms with recognition tier 3"
    said = set()
    for seed in range(1, 7):
        for kind, text, rows in spoken(feud(3, seed=seed), 500):
            for r in rows:
                if r["tier"] == 3:
                    said.add(r["term"])
                    assert r["term"] in allowed
    assert said, "tier 3 was set and no dwarf ever reached for the speech file"


def test_the_in_world_list_is_a_list_of_its_own():
    rows = profanity.in_world_terms()
    assert 30 <= len(rows) <= 50
    assert len({t.term for t in rows}) == len(rows)
    assert all(0.0 < t.strength <= 1.0 and t.tier == 3 and t.kind == "slur" for t in rows)
    assert len({t.targets for t in rows}) >= 5

    lex = lexicon()
    assert [t.term for t in rows if lex.scan(t.term)] == []       # no overlap with the file


def test_a_dwarf_swears_by_temper_and_anger_not_by_dice_alone():
    world = feud(3)
    calm, hot = world.agents[0], world.agents[1]
    calm.mind.emotions["anger"] = 0.0
    assert profanity.for_speaker(world, calm, hot) is None

    hot.mind.emotions["anger"] = 0.9
    hot.mind.traits["temper"] = 0.9
    hot.mind.rel(calm.id).update({"hatred": 0.0, "trust": 0.5})
    assert profanity.for_speaker(world, hot, calm).tier == 1        # cross, but not with you

    hot.mind.rel(calm.id).update({"hatred": 0.2, "trust": -0.3})
    assert profanity.for_speaker(world, hot, calm).tier == 2
    hot.mind.rel(calm.id)["hatred"] = 0.8
    assert profanity.for_speaker(world, hot, calm).tier == 3

    # and the world's ceiling is a ceiling
    assert profanity.for_speaker(feud(1), hot, calm) is not None
    world.profanity = world.profanity.copy(1)
    assert profanity.for_speaker(world, hot, calm).tier == 1
    world.profanity = world.profanity.copy(0)
    assert profanity.for_speaker(world, hot, calm) is None


def test_a_swear_is_a_slot_in_a_line_not_a_line():
    lines = [text for _, text, rows in spoken(feud(2, seed=7), 500) if rows]
    assert lines
    for text in lines:
        assert len(text.split()) >= 3, text
        assert text[0].isupper() or text[0] in "\"'", text
        assert text.rstrip()[-1] in ".!?", text


# ---------------------------------------------------------------------------
# The settings
# ---------------------------------------------------------------------------


def test_the_same_seed_says_the_same_words():
    for tier in (0, 1, 2, 3):
        one = spoken(feud(tier, seed=11), 300)
        two = spoken(feud(tier, seed=11), 300)
        assert one == two
    assert spoken(feud(3, seed=11), 300) != spoken(feud(0, seed=11), 300)


def test_swearing_draws_from_its_own_stream():
    """It spends none of the world's randomness, so the words are an addition, not a reshuffle."""
    world = feud(3, seed=12)
    assert world.swear_rng is not world.rng
    speaker, at = world.agents[0], world.agents[1]
    speaker.mind.emotions["anger"] = 0.9
    speaker.mind.rel(at.id)["hatred"] = 0.8
    before = world.rng.getstate()
    sw = profanity.for_speaker(world, speaker, at)
    assert [sw.pick("RETORT") for _ in range(20)]
    assert world.rng.getstate() == before


def test_a_speech_file_of_the_users_own_is_used_verbatim(tmp_path):
    """Whatever the user puts in it is what the dwarves say, term for term and strength for
    strength."""
    path = write_lexicon(tmp_path, {"3": [
        {"term": "chalk-eater", "kind": "slur", "targets": "outsiders", "strength": 0.6},
        {"term": "lamp-snuffer", "kind": "slur", "targets": "outsiders", "strength": 0.55},
    ]}, name="speech.json")
    lex = profanity.Lexicon.load(FILE, max_tier=3, speech_path=path)
    assert {t.term for t in lex.speech_terms(3)} == {"chalk-eater", "lamp-snuffer"}
    assert {(t.term, t.strength, t.targets) for t in lex.speech_terms(3)} == {
        ("chalk-eater", 0.6, "outsiders"), ("lamp-snuffer", 0.55, "outsiders")}
    assert lex.speech_terms(1) and lex.speech_terms(2)        # the file still feeds 1 and 2
    assert lex.speech_source == "file"
    assert lex.speech_dropped == 0 and lex.speech_note() is None

    said = {r["term"] for _, _, rows in spoken(feud(3, seed=1, speech_path=path), 500)
            for r in rows if r["tier"] == 3}
    assert said and said <= {"chalk-eater", "lamp-snuffer"}


def test_a_speech_file_may_repeat_the_recognition_files_tier_three(tmp_path):
    """Recognition and speech are allowed to share a list. A copy of tier 3 is spoken."""
    users = json.loads(open(profanity.resolve(FILE), encoding="utf-8").read())
    path = tmp_path / "copy.json"
    path.write_text(json.dumps({"tiers": {"3": users["tiers"]["3"]}}), encoding="utf-8")

    lex = profanity.Lexicon.load(FILE, max_tier=3, speech_path=str(path))
    said_ok = {str(e["term"]).strip().lower() for e in users["tiers"]["3"]}
    assert {t.term for t in lex.speech_terms(3)} == said_ok
    assert lex.speech_source == "file"
    assert lex.speech_dropped == 0
    assert lex.speech_note() is None
    assert [t for t in lex.speech_terms(3) if lex.scan(t.term, tiers=(3,))]


def test_a_mixed_speech_file_keeps_every_entry(tmp_path):
    users = json.loads(open(profanity.resolve(FILE), encoding="utf-8").read())
    borrowed = str(users["tiers"]["3"][0]["term"]).strip().lower()
    path = tmp_path / "mixed.json"
    path.write_text(json.dumps({"tiers": {"3": [
        {"term": "chalk-eater", "kind": "slur", "targets": "outsiders", "strength": 0.6},
        users["tiers"]["3"][0],
    ]}}), encoding="utf-8")

    lex = profanity.Lexicon.load(FILE, max_tier=3, speech_path=str(path))
    assert {t.term for t in lex.speech_terms(3)} == {"chalk-eater", borrowed}
    assert lex.speech_source == "file" and lex.speech_dropped == 0
    assert lex.speech_note() is None


def test_a_missing_or_empty_speech_file_is_the_in_world_list(tmp_path):
    for path in (str(tmp_path / "nope.json"),
                 write_lexicon(tmp_path, {"3": []}, name="empty.json"),
                 None):
        lex = profanity.Lexicon.load(FILE, max_tier=3, speech_path=path)
        assert {t.term for t in lex.speech_terms(3)} == {
            t.term for t in profanity.in_world_terms()}, path
        assert lex.speech_source == "in-world" and lex.speech_note() is None

    broken = tmp_path / "broken.json"
    broken.write_text("{not json at all", encoding="utf-8")
    lex = profanity.Lexicon.load(FILE, max_tier=3, speech_path=str(broken))
    assert {t.term for t in lex.speech_terms(3)} == {t.term for t in profanity.in_world_terms()}


def test_the_run_and_the_talk_app_use_a_speech_file_that_repeats_tier_three(tmp_path):
    users = json.loads(open(profanity.resolve(FILE), encoding="utf-8").read())
    path = tmp_path / "copy.json"
    path.write_text(json.dumps({"tiers": {"3": users["tiers"]["3"]}}), encoding="utf-8")

    session = Session(seed=1, dwarves=2, profanity_tier=3, profanity_speech=str(path))
    snap = session.snapshot()
    assert snap.get("profanity_note") in (None, "")
    assert session.world.profanity.speech_source == "file"

    from dwarfsim import run_sim
    summary = run_sim(str(tmp_path / "run.jsonl"), n_agents=3, ticks=20, seed=1,
                      profanity_tier=3, profanity_speech=str(path))
    assert summary["profanity"] == 3
    assert not summary.get("profanity_note")


def test_the_setting_reaches_the_world_the_session_and_both_command_lines(tmp_path, capsys):
    from dwarfsim import __main__ as cli
    from dwarfsim import talk

    assert World(n_agents=2, seed=1).profanity.max_tier == profanity.DEFAULT_MAX_TIER == 1
    assert World(n_agents=2, seed=1, profanity_tier=3).profanity.max_tier == 3
    assert World(n_agents=2, seed=1, profanity_tier=9).profanity.max_tier == 3

    session = Session(seed=1, dwarves=2, profanity_tier=2)
    assert session.world.profanity.max_tier == 2
    assert session.snapshot()["profanity"] == 2

    args = talk.build_parser().parse_args(["--profanity", "0", "--profanity-speech", "x.json"])
    assert args.profanity == 0 and args.profanity_speech == "x.json"
    assert talk.build_parser().parse_args([]).profanity == profanity.DEFAULT_MAX_TIER

    out = str(tmp_path / "run.jsonl")
    assert cli.main(["run", "--agents", "3", "--ticks", "40", "--seed", "1",
                     "--scenario", "feud", "--profanity", "3",
                     "--profanity-speech", str(tmp_path / "none.json"), "--out", out]) == 0
    capsys.readouterr()
    assert os.path.getsize(out) > 0


def test_replies_only_swear_in_answer_to_a_provocation():
    assert set(replies.SWEARING_KINDS) <= {"INSULT", "THREAT", "ACCUSE", "REFUSE_APOLOGY"}
    session = Session(seed=8, dwarves=3, profanity_tier=2, ticks_per_say=0)
    dwarf = session.focus
    dwarf.mind.emotions["anger"] = 0.95
    dwarf.mind.traits["temper"] = 0.95
    dwarf.mind.rel("player").update({"trust": -0.4, "hatred": 0.3})
    greeting = session.command("/labels intent=GREET text=Well met.")
    assert not (greeting["reply"] or {}).get("profanity")


# ---------------------------------------------------------------------------
# Grammar: one person is singular, and the word is woven in, not tacked on
# ---------------------------------------------------------------------------


class _Rng:
    """A seeded stand-in: ``randrange(k)`` walks 0, 1, ... so every weave is hit."""

    def __init__(self, start=0):
        self.n = start

    def randrange(self, k):
        if k <= 0:
            raise ValueError(k)
        i = self.n % k
        self.n += 1
        return i

    def random(self):
        return 0.0


def test_a_plural_noun_is_not_said_to_one_person():
    """niggers / greenskins / ... name a group. A line at one player must not use them."""
    lex = profanity.Lexicon.load(FILE, max_tier=3)
    plurals = [t for n in (1, 2, 3) for t in list(lex.tiers[n]) + list(lex.speech3)
               if t.form in ("noun", "adj") and t.number == "pl"]
    assert any(t.term == "niggers" for t in plurals)
    assert any(t.term == "greenskins" for t in plurals)
    for t in plurals:
        assert not profanity.directed_ok(t, "LISTENER")
        assert profanity.directed_ok(t, "GROUP")

    line = "Get away from my forge, Brokk!"
    for t in plurals:
        out = profanity.fill(line, t, _Rng(), name="Brokk", addressed="LISTENER")
        assert out == line
        assert t.term not in out.lower() or t.term in line.lower()


def test_infer_grammar_tags_plurals_and_articles():
    assert profanity.infer_grammar("niggers", "slur", "group") == ("noun", "pl", "")
    assert profanity.infer_grammar("nigger", "slur", "group") == ("noun", "sg", "a")
    assert profanity.infer_grammar("ass", "insult", "person") == ("noun", "sg", "an")
    assert profanity.infer_grammar("scum", "insult", "person") == ("noun", "mass", "")
    assert profanity.infer_grammar("beardless", "insult", "person")[0] == "adj"
    assert profanity.infer_grammar("fucking", "expletive", "none") == ("intensifier", "sg", "")
    assert profanity.infer_grammar("hell", "expletive", "none") == ("oath", "sg", "")
    assert profanity.infer_grammar("coward", "insult", "person", form="noun",
                                   number="sg", article="a") == ("noun", "sg", "a")


def test_a_noun_takes_the_vocative_not_a_second_sentence():
    """The insult sits where the name sat, or opens the line. Never ', Brokk, you X.'"""
    term = profanity.Term("coward", "insult", "person", 0.5, 2, "noun", "sg", "a")
    line = "Get away from my forge, Brokk!"
    seen = set()
    rng = _Rng()
    for _ in range(12):
        out = profanity.fill(line, term, rng, name="Brokk", addressed="LISTENER")
        seen.add(out)
        assert out and out[0].isupper() and out.rstrip()[-1] in ".!?"
        assert "coward" in out.lower()
        assert ", Brokk, you " not in out
        assert not out.lower().startswith(line.lower().rstrip("!").lower())
    assert "Get away from my forge, you coward!" in seen
    assert any(s.lower().startswith("you coward,") for s in seen)


def test_an_intensifier_goes_after_the_determiner():
    term = profanity.Term("fucking", "expletive", "none", 0.6, 2, "intensifier", "sg", "")
    out = profanity.fill("Get away from my forge, Brokk!", term, _Rng(), name="Brokk")
    assert out == "Get away from my fucking forge, Brokk!"
    bare = profanity.fill("Stand clear.", term, _Rng(), name="Brokk")
    assert bare == "Fucking stand clear."


def test_fill_always_finishes_a_sentence_for_every_speakable_term():
    lex = profanity.Lexicon.load(FILE, max_tier=3)
    line = "Get away from my forge, Brokk!"
    rng = _Rng()
    for tier in (1, 2, 3):
        for t in lex.speech_terms(tier):
            if not profanity.speakable(t):
                continue
            out = profanity.fill(line, t, rng, name="Brokk", addressed="LISTENER")
            assert out, t.term
            assert out[0].isupper() or out[0] in "\"'", out
            assert out.rstrip()[-1] in ".!?", out
            assert ", Brokk, you " not in out, out
            if t.number == "pl":
                assert out == line, t.term


def test_one_to_one_speech_never_uses_a_plural_noun():
    plurals = {t.term for t in lexicon().tiers[3] if t.number == "pl" and profanity.speakable(t)}
    assert "niggers" in plurals
    said = set()
    for seed in range(1, 5):
        for _, text, rows in spoken(feud(3, seed=seed), 400):
            said.update(r["term"] for r in rows)
            for word in plurals:
                assert not re.search(r"(?i)(?<![a-z])%s(?![a-z])" % re.escape(word), text), text
    assert said
