"""No approval farming: habituation, words against deeds, flattery and gifts.

Four claims, and the tests are stated the way the design states them:

* pasting the same praise twenty times moves trust less than two genuinely different ones;
* twenty pastes end in suspicion and a trust that has gone the other way;
* one kept promise beats fifty compliments;
* a gift of one gold twenty times is worth less than one gift of ten.
"""

import os
import sys

import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from dwarfsim import mind, obligations, regard, speech  # noqa: E402
from dwarfsim.schema import PLAYER_ID  # noqa: E402
from dwarfsim.world import World  # noqa: E402

PRAISES = (
    "that is a fine sword",
    "your work at the forge is the best in the hall",
    "nobody swings a pick like you do",
    "the seam you opened is still paying us back",
)


def world(seed=1, n=3, **kw):
    return World(n, seed=seed, **kw)


def praise(text, topic="WEAPON"):
    return {"text": text, "intent": "PRAISE", "topic": topic, "addressed": "LISTENER",
            "aggression": 0.0, "valence": 0.9, "urgency": 0.0, "names": []}


def say(w, text, to=None, ticks=1, **kw):
    """The player says one line to a dwarf, and the world moves on a tick."""
    dwarf = to if to is not None else w.agents[0]
    ev = w.say(PLAYER_ID, dwarf.id, praise(text, **kw))
    for _ in range(ticks):
        w.tick += 1
    return ev


def trust_in_player(dwarf):
    return dwarf.mind.rel(PLAYER_ID)["trust"]


# ---------------------------------------------------------------------------
# Signatures and habituation
# ---------------------------------------------------------------------------


def test_a_signature_sees_through_punctuation_and_case():
    a = regard.signature("PRAISE", praise("Wow, NICE sword!!"))
    b = regard.signature("PRAISE", praise("wow nice sword"))
    assert a == b
    assert a != regard.signature("PRAISE", praise("wow, nice beard"))
    # and it is stable across processes, unlike hash()
    assert regard.text_hash("wow nice sword") == regard.text_hash("Wow -- nice sword.")


def test_the_same_words_again_are_worth_almost_nothing():
    reg = regard.Regard()
    sig = regard.signature("PRAISE", praise("wow, nice sword"))
    worth = []
    for _ in range(5):
        worth.append(reg.repetition(0, sig)[0])
        reg.note(0, sig)
    assert worth[0] == 1.0
    assert worth[1] < 0.5
    assert worth[2] < 0.1
    assert worth[4] == 0.0


def test_a_different_line_on_the_same_subject_decays_far_more_slowly():
    reg = regard.Regard()
    worth = []
    for text in PRAISES:
        sig = regard.signature("PRAISE", praise(text))
        worth.append(reg.repetition(0, sig)[0])
        reg.note(0, sig)
    assert worth[0] == 1.0
    assert worth[1] > 0.7
    assert min(worth) >= regard.SIMILAR_FLOOR
    # and a while later it is fresh again, which is what the shorter window is for
    sig = regard.signature("PRAISE", praise(PRAISES[0] + " indeed"))
    assert reg.repetition(regard.SIMILAR_WINDOW + 1, sig)[0] == 1.0


def test_habituation_reaches_the_event_itself():
    """It multiplies the magnitude, so the event table lands quieter without changing a row."""
    w = world()
    first = say(w, "wow, nice sword")
    again = say(w, "wow, nice sword")
    assert first["habit"] == 1.0 and "said_before" not in first
    assert again["habit"] < 0.5 and again["said_before"] is True


# ---------------------------------------------------------------------------
# Words against deeds
# ---------------------------------------------------------------------------


def test_words_buy_warmth_and_deeds_buy_trust():
    w = world()
    dwarf = w.agents[0]
    for text in PRAISES:
        say(w, text)
    row = dwarf.mind.rel(PLAYER_ID)
    assert row["warmth"] > row["trust"] * 2.0
    assert row["trust"] < regard.SPEECH_TRUST_CAP
    # warmth counts wherever a dwarf acts on how it feels, and fades where trust does not
    assert regard.felt_trust(dwarf, PLAYER_ID) > row["trust"]
    was = row["warmth"]
    for _ in range(600):
        dwarf.mind.decay()
    assert dwarf.mind.rel(PLAYER_ID)["warmth"] < was * 0.25


def test_there_is_a_ceiling_on_the_trust_one_mouth_can_talk_its_way_into():
    w = world()
    dwarf = w.agents[0]
    for i in range(60):
        say(w, "%s, and that is the truth of it" % PRAISES[i % len(PRAISES)])
    assert trust_in_player(dwarf) <= regard.SPEECH_TRUST_CAP + 1e-9


def test_twenty_pastes_move_trust_less_than_two_real_compliments():
    pasted = world()
    for _ in range(20):
        say(pasted, "wow, nice sword")
    varied = world()
    say(varied, PRAISES[0])
    say(varied, PRAISES[1])
    assert trust_in_player(pasted.agents[0]) < trust_in_player(varied.agents[0])


def test_twenty_pastes_end_in_suspicion_and_no_trust_at_all():
    w = world()
    dwarf = w.agents[0]
    kinds = []
    for _ in range(20):
        kinds.append(say(w, "wow, nice sword")["type"])
    assert kinds[0] == "PRAISE"
    assert kinds[-1] == "FLATTERY"
    assert regard.suspicion(dwarf, PLAYER_ID) >= regard.SUSPICION_THRESHOLD
    assert trust_in_player(dwarf) <= 0.0, trust_in_player(dwarf)


def test_one_kept_promise_beats_fifty_compliments():
    talked = world()
    for i in range(50):
        say(talked, "%s -- number %d" % (PRAISES[i % len(PRAISES)], i))
    kept = world()
    dwarf = kept.agents[0]
    ask = obligations.make_ask("GIVE", item="gold", quantity=1, place=dwarf.place)
    ob = kept.player_promise(dwarf, ask)
    kept.fulfil_obligation(ob)
    assert trust_in_player(dwarf) > trust_in_player(talked.agents[0])


# ---------------------------------------------------------------------------
# Flattery
# ---------------------------------------------------------------------------


def test_unearned_praise_is_suspect_even_when_the_words_are_new():
    w = world()
    dwarf = w.agents[0]
    dwarf.last_deed = None
    for i in range(10):
        say(w, "%s, number %d" % (PRAISES[i % len(PRAISES)], i))
    unearned = regard.suspicion(dwarf, PLAYER_ID)

    w2 = world()
    other = w2.agents[0]
    for i in range(10):
        other.last_deed = w2.tick        # he has just come off a productive shift
        say(w2, "%s, number %d" % (PRAISES[i % len(PRAISES)], i))
    assert unearned > regard.suspicion(other, PLAYER_ID)


def test_flattery_flips_the_valence_and_the_dwarf_says_so():
    from dwarfsim import replies
    w = world()
    dwarf = w.agents[0]
    for _ in range(20):
        ev = say(w, "wow, nice sword")
    assert ev["type"] == "FLATTERY" and ev["flattery"] is True
    assert ev["suspicion"] >= regard.SUSPICION_THRESHOLD
    # it cost the speaker rather than buying anything
    moved = [d for d in ev.get("deltas", ())
             if d["f"] == "rel:%s:trust" % PLAYER_ID and d["who"] == dwarf.id]
    assert moved and moved[0]["to"] < moved[0]["from"]
    answer = replies.reply(w, dwarf, PLAYER_ID, praise("wow, nice sword"))
    assert answer["kind"] == "FLATTERY"
    assert answer["text"]


def test_suspicion_decays_slowly_and_a_real_deed_clears_it():
    w = world()
    dwarf = w.agents[0]
    for _ in range(20):
        say(w, "wow, nice sword")
    high = regard.suspicion(dwarf, PLAYER_ID)
    for _ in range(50):
        regard.decay(dwarf)
    slow = regard.suspicion(dwarf, PLAYER_ID)
    assert 0.0 < slow < high
    w.player_help(dwarf)
    assert regard.suspicion(dwarf, PLAYER_ID) == 0.0


# ---------------------------------------------------------------------------
# Gifts
# ---------------------------------------------------------------------------


def test_a_gift_is_worth_what_it_cost_the_giver():
    w = world()
    dwarf = w.agents[0]
    w.player.inv["gold"] = 20
    rich = regard.gift_magnitude(w.player, dwarf, "gold", 1, 0)
    w.player.inv["gold"] = 2
    poor_giver = regard.gift_magnitude(w.player, w.agents[1], "gold", 1, 0)
    assert poor_giver > rich


def test_twenty_coins_one_at_a_time_are_worth_less_than_one_gift_of_ten():
    dribbled = world()
    dribbled.player.inv["gold"] = 40
    for _ in range(20):
        dribbled.player_give(dribbled.agents[0], 1)
        dribbled.tick += 1
    once = world()
    once.player.inv["gold"] = 40
    once.player_give(once.agents[0], 10)
    assert trust_in_player(once.agents[0]) > trust_in_player(dribbled.agents[0])
    # and the twentieth coin really was worth almost nothing
    reg = regard.of(dribbled.agents[0], PLAYER_ID)
    assert reg.gift_repeats(dribbled.tick) == 20


def test_the_gift_still_moves_trust_properly_because_it_is_a_deed():
    w = world()
    w.player.inv["gold"] = 20
    w.player_give(w.agents[0], 10)
    assert trust_in_player(w.agents[0]) > regard.SPEECH_TRUST_CAP


# ---------------------------------------------------------------------------
# It is still one settlement, and still deterministic
# ---------------------------------------------------------------------------


def test_the_settlement_still_trusts_itself_after_a_thousand_ticks():
    """Rationing words must not leave six dwarves who feel nothing about each other."""
    w = world(seed=3, n=6)
    for _ in range(1000):
        w.step()
    felt = [regard.felt_trust(a, o.id) for a in w.living() for o in w.living() if a.id != o.id]
    assert felt and sum(felt) / len(felt) > 0.20


def test_the_same_seed_is_the_same_settlement():
    def run():
        w = world(seed=11, n=4)
        for _ in range(300):
            w.step()
        return [(a.id, round(a.mind.rel(o.id)["warmth"], 6),
                 round(regard.suspicion(a, o.id), 6))
                for a in w.agents for o in w.agents if a.id != o.id]
    assert run() == run()


def test_the_event_table_has_a_row_for_flattery():
    assert "FLATTERY" in mind.EVENT_TABLE
    row = mind.EVENT_TABLE["FLATTERY"]
    assert row["target_rel"]["trust"] < 0.0
    assert regard.WORD_EVENTS and "PRAISE" in regard.WORD_EVENTS
    assert "GIFT" in regard.DEED_EVENTS and "PROMISE_KEPT" in regard.DEED_EVENTS
    # an apology is a cost, not a word: it is the only way back from a feud
    assert "APOLOGY" not in regard.WORD_EVENTS
