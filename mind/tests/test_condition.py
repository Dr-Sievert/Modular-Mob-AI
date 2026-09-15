"""Injuries: what a blow costs, what being broken stops you doing, and how it mends.

The one that matters is ``test_a_brawl_is_not_a_death_sentence``: 200 unarmed brawls, and the
death rate has to stay under 2%. Everything else is the table in :mod:`dwarfsim.condition`
being asked, one row at a time, whether it does what it says.
"""

import os
import random
import sys

import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from dwarfsim import arbitrator, condition, schema, skills, speech  # noqa: E402
from dwarfsim.skills import Candidate  # noqa: E402
from dwarfsim.world import World  # noqa: E402


def world(n=3, seed=1, **kw):
    return World(n, seed=seed, **kw)


def pair(seed=1):
    """Two dwarves in the same room, fists only."""
    w = world(2, seed)
    a, b = w.agents
    a.inv["weapon"] = b.inv["weapon"] = 0.0
    a.place = b.place = "TAVERN"
    return w, a, b


def names(cands):
    return {c.skill for c in cands}


def places(cands, skill):
    return {c.place for c in cands if c.skill == skill}


# ---------------------------------------------------------------------------
# The blow
# ---------------------------------------------------------------------------


def test_one_unarmed_blow_is_mostly_nothing_or_a_bruise():
    """The per-blow census the whole model rests on: fists do not open people."""
    w, a, b = pair()
    got = {}
    for _ in range(4000):
        b.condition.injuries.clear()
        b.health = schema.MAX_HEALTH
        _lost, injuries, how = condition.resolve_blow(w.rng, a, b, tick=0)
        assert how == "unarmed"
        kind = injuries[0].kind if injuries else None
        got[kind] = got.get(kind, 0) + 1
    mild = got.get(None, 0) + got.get("bruised", 0)
    assert mild / 4000.0 > 0.70, got
    assert got.get("broken_arm", 0) / 4000.0 < 0.03, got
    # and it costs almost no health
    w2, a2, b2 = pair(2)
    losses = [condition.resolve_blow(w2.rng, a2, b2, tick=0)[0] for _ in range(200)
              if setattr(b2, "health", schema.MAX_HEALTH) is None]
    assert sum(losses) / len(losses) < 1.2, sum(losses) / len(losses)


def test_a_weapon_is_drawn_not_merely_carried():
    """The line between a brawl and a killing. A quarrel does not get an edge."""
    w, a, b = pair(3)
    a.inv["weapon"] = 0.9
    a.mind.rel(b.id)["hatred"] = 0.0
    assert not any(condition.draws_weapon(w.rng, a, b) for _ in range(200))
    a.mind.rel(b.id)["hatred"] = 0.95
    assert sum(condition.draws_weapon(w.rng, a, b) for _ in range(200)) > 60
    # no weapon, no choice
    a.inv["weapon"] = 0.05
    assert not any(condition.draws_weapon(w.rng, a, b) for _ in range(50))


def test_an_edge_is_what_kills():
    w, a, b = pair(4)
    unarmed = [condition.resolve_blow(w.rng, a, b, profile="unarmed", tick=0)[0]
               for _ in range(200) if setattr(b, "health", 20.0) is None]
    armed = [condition.resolve_blow(w.rng, a, b, profile="armed", tick=0)[0]
             for _ in range(200) if setattr(b, "health", 20.0) is None]
    assert sum(armed) > 2.2 * sum(unarmed)


def test_the_same_seed_is_the_same_fight():
    def run():
        w, a, b = pair(7)
        out = []
        for _ in range(30):
            out.append(condition.resolve_blow(w.rng, a, b, tick=0)[:2])
        return [(round(lost, 3), [(i.kind, round(i.severity, 3)) for i in inj])
                for lost, inj in out]
    assert run() == run()


def test_a_brawl_is_not_a_death_sentence():
    """200 unarmed brawls between whole dwarves. Under 2% of them end in a death.

    A brawl here is what a brawl is: fists, three to twelve exchanges, and it stops when one of
    them is down to a quarter of their health. The point of the whole injury model is that the
    ordinary outcome is a black eye.
    """
    deaths = 0
    injured = carried = 0
    for seed in range(1, 201):
        rng = random.Random(seed * 31 + 7)
        w, a, b = pair(seed)
        attack = skills.Attack()
        done = False
        for _ in range(rng.randrange(3, 13)):
            if done:
                break
            for x, y in ((a, b), (b, a)):
                if not (a.alive and b.alive):
                    done = True
                    break
                attack.execute(x, w, Candidate("ATTACK", target=y.id, place=x.place))
                if y.health <= schema.MAX_HEALTH * 0.25:
                    done = True
                    break
        if not (a.alive and b.alive):
            deaths += 1
        for x in (a, b):
            carried += 1
            if x.condition:
                injured += 1
    assert deaths / 200.0 < 0.02, "%d of 200 brawls killed somebody" % deaths
    # ... and they are not walking away untouched either, or none of this would mean anything
    assert injured / float(carried) > 0.5


# ---------------------------------------------------------------------------
# What an injury does
# ---------------------------------------------------------------------------


def test_a_broken_arm_takes_away_two_handed_work_and_the_weapon():
    w = world(3, 5)
    a = w.agents[0]
    before = names(arbitrator.gather(a, w))
    assert "WORK" in before
    a.condition.add("broken_arm", 0.8)
    after = arbitrator.gather(a, w)
    # no mining, no forging, no farming -- but a dwarf can still buy ale one-handed
    assert places(after, "WORK") == {"TAVERN"}
    assert "FIGHT_MONSTER" not in names(after)
    # and everything that is not a pair of hands is untouched
    a.inv["food"] = 2
    assert "EAT" in names(arbitrator.gather(a, w))
    assert "SOCIALIZE" in names(arbitrator.gather(a, w))
    # the weapon is no use to him either
    a.inv["weapon"] = 1.0
    assert condition.weapon_quality(a) < 0.40
    assert "WORK" in condition.blocked_skills(a, skills.SKILLS)


def test_a_sprained_hand_halves_the_work_and_the_weapon():
    w = world(2, 6)
    a = w.agents[0]
    a.inv["weapon"] = 1.0
    a.condition.add("sprained_hand", 1.0)
    assert a.condition.work_mult() == pytest.approx(0.5, abs=0.01)
    assert condition.weapon_quality(a) == pytest.approx(0.5, abs=0.01)
    # a shift with it produces nothing about half the time
    a.place = "MINE"
    got = 0
    for _ in range(200):
        a.inv["ore"] = 0
        skills.Work().execute(a, w, Candidate("WORK", place="MINE"))
        got += a.inv["ore"]
    assert 60 < got < 140, got


def test_a_broken_leg_slows_the_walking_and_forbids_the_gate():
    w = world(3, 8)
    a = w.agents[0]
    a.condition.add("broken_leg", 0.9)
    assert a.condition.move_penalty() > 0.5
    assert "FIGHT_MONSTER" in condition.blocked_skills(a, skills.SKILLS)
    # fleeing barely works
    a.place = "MINE"
    stayed = 0
    for _ in range(100):
        a.place = "MINE"
        skills.Flee().execute(a, w, Candidate("FLEE"))
        stayed += 1 if a.place == "MINE" else 0
    assert stayed > 40, stayed
    # and a move it decides on can cost a tick and get nowhere
    a.place = "HALL"
    limped = 0
    for _ in range(200):
        step = w.step()
        limped += sum(1 for ev in step["events"]
                      if ev["type"] == "MOVE" and ev.get("limped"))
    assert limped > 0


def test_a_concussion_makes_the_decisions_noisier_and_the_memory_shorter():
    w = world(3, 9)
    a = w.agents[0]
    plain = arbitrator._temperature(a, None)
    a.condition.add("concussion", 0.9)
    assert arbitrator._temperature(a, None) > plain * 1.5
    assert a.condition.recall_mult() < 0.6
    # a memory laid down while concussed is quieter than the same one laid down clear
    b = w.agents[1]
    dulled = w.remember(a, "HIT", b.id, a.id, "MINE", 1.0, "SUFFERED")
    a.condition.injuries.clear()
    w.tick += 1
    clear = w.remember(a, "HIT", b.id, a.id, "FORGE", 1.0, "SUFFERED")
    assert dulled.intensity < clear.intensity


def test_bleeding_drains_until_it_is_rested_or_eaten_off():
    w = world(2, 10)
    a = w.agents[0]
    a.condition.add("bleeding", 1.0)
    before = a.health
    w._tick_condition(a)
    assert a.health < before
    # rest, and it stops
    was = a.condition.severity("bleeding")
    skills.Rest().execute(a, w, Candidate("REST", place="HALL"))
    assert a.condition.severity("bleeding") < was - 0.05
    a.inv["food"] = 1
    skills.Eat().execute(a, w, Candidate("EAT"))
    assert a.condition.severity("bleeding") < was - 0.1


def test_cracked_ribs_raise_the_fear_and_take_the_force_out_of_a_swing():
    w = world(3, 11)
    a = w.agents[0]
    before_fear = arbitrator.TermContext(a, w).flat["fear"]
    before_force = condition.strength(a)
    a.condition.add("cracked_ribs", 1.0)
    assert arbitrator.TermContext(a, w).flat["fear"] > before_fear
    assert condition.strength(a) < before_force * 0.7
    # the emotion itself is untouched: it is a flinch, not a mood
    assert a.mind.emotions["fear"] < arbitrator.TermContext(a, w).flat["fear"]


def test_injuries_mend_over_days_and_faster_with_rest_and_food():
    w = world(2, 12)
    a, b = w.agents
    a.condition.add("cut", 0.5)
    b.condition.add("cut", 0.5)
    for _ in range(20):
        w._tick_condition(a)
        w._tick_condition(b)
        skills.Rest().execute(b, w, Candidate("REST", place="HALL"))
    assert b.condition.severity("cut") < a.condition.severity("cut")
    # and when one finally goes, the world says so
    a.condition.add("bruised", 0.03)
    events = []
    w.events = events
    for _ in range(3):
        w._tick_condition(a)
    assert any(ev["type"] == "RECOVERED" and ev.get("injury") == "bruised" for ev in events)


# ---------------------------------------------------------------------------
# What the rest of the sim makes of it
# ---------------------------------------------------------------------------


def test_the_grudge_is_for_what_was_broken_not_for_the_health_lost():
    """A punch that takes two health and leaves nothing fades; a broken arm does not."""
    nothing = condition.blow_magnitude(2.0, [])
    broken = condition.blow_magnitude(0.4, [condition.Injury("broken_arm", 0.8)])
    assert broken > nothing * 1.5


def test_a_blow_leaves_a_memory_of_who_caused_it():
    w, a, b = pair(13)
    skills.Attack().execute(a, w, Candidate("ATTACK", target=b.id, place=a.place))
    hit = [ev for ev in w.events if ev["type"] == "HIT"][0]
    for inj in hit.get("injuries") or ():
        assert inj["by"] == a.id
    assert any(m.kind == "HIT" and m.actor == a.id for m in b.memories)


def test_the_observation_carries_the_injury_block():
    w = world(3, 14)
    a = w.agents[0]
    v = arbitrator.observation_vector(a, w)
    assert len(v) == schema.OBS_SIZE == 77
    block = v[schema.OBS_CONDITION:schema.OBS_CONDITION + schema.CONDITION_SIZE]
    assert block == [0.0] * schema.CONDITION_SIZE
    a.condition.add("broken_leg", 0.6)
    a.condition.add("bleeding", 0.3)
    block = arbitrator.observation_vector(a, w)[
        schema.OBS_CONDITION:schema.OBS_CONDITION + schema.CONDITION_SIZE]
    assert block[0] > 0.0 and block[1] == pytest.approx(0.6)
    assert block[2] == pytest.approx(0.5)
    assert block[4] == pytest.approx(0.6) and block[6] == pytest.approx(0.3)
    assert all(0.0 <= x <= 1.0 for x in block)


def test_the_candidate_features_carry_impairment():
    w = world(3, 15)
    a = w.agents[0]
    a.condition.add("broken_leg", 0.2)          # under the block, so WORK is still offered
    ctx = arbitrator.TermContext(a, w)
    cands = arbitrator.gather(a, w)
    feats = arbitrator.candidate_features(ctx, cands[0])
    assert len(feats) == schema.CAND_SIZE == 65
    mine = [c for c in cands if c.skill == "WORK" and c.place == "MINE"][0]
    talk = [c for c in cands if c.skill == "APOLOGIZE" or c.skill == "EAT"]
    assert ctx.value("impairment", mine) > 0.0
    for c in talk:
        assert ctx.value("impairment", c) == 0.0


def test_hurt_reads_the_condition_not_only_the_health():
    w = world(3, 16)
    a = w.agents[0]
    assert a.hurt() == 0.0
    a.condition.add("broken_arm", 0.9)
    assert a.health == schema.MAX_HEALTH and a.hurt() > 0.3


def test_a_badly_hurt_dwarf_does_not_swing():
    """The scorer's side of it: whole, he hits back; broken, he flees, avoids or complains."""
    w = world(4, 17)
    a, b = w.agents[0], w.agents[1]
    a.place = b.place = "TAVERN"
    a.mind.rel(b.id)["hatred"] = 0.9
    a.mind.emotions["anger"] = 0.9
    a.last_hit_by, a.last_hit_tick = b.id, w.tick

    def best(agent):
        ctx = arbitrator.TermContext(agent, w)
        ranked = []
        for c in arbitrator.gather(agent, w):
            total = sum(weight * ctx.value(term, c)
                        for term, weight in arbitrator.WEIGHTS[c.skill].items()
                        if term != "noise")
            ranked.append((total, c.skill))
        ranked.sort(reverse=True)
        return ranked[0][1], dict((s, v) for v, s in ranked)

    whole, whole_scores = best(a)
    for kind in ("broken_arm", "cracked_ribs", "bleeding"):
        a.condition.add(kind, 0.85)
    a.health = 6.0
    broken, broken_scores = best(a)
    assert broken_scores["ATTACK"] < whole_scores["ATTACK"]
    assert broken in ("FLEE", "AVOID", "COMPLAIN_TO", "REST", "EAT", "IGNORE"), broken


def test_a_hurt_dwarf_mentions_it():
    rng = random.Random(1)
    tails = [speech.hurt_tail(rng, "my arm", chance=1.0) for _ in range(20)]
    assert all("my arm" in t for t in tails)
    assert speech.hurt_tail(rng, None) == ""
    w = world(3, 18)
    a = w.agents[0]
    a.condition.add("broken_arm", 0.7)
    assert a.condition.complaint() == "my arm"
    assert "broken arm" in a.condition.describe()


def test_the_story_narrates_injuries_and_recoveries(tmp_path):
    from dwarfsim import run_sim
    summary = run_sim(str(tmp_path / "run.jsonl"), n_agents=6, ticks=2000, seed=2,
                      scenario="feud")
    assert summary["injuries"]["dealt"], "a feud that broke nobody is not a feud"
    assert summary["injuries"]["mended"] > 0
    text = " ".join(s["text"] for s in summary["story"])
    assert "left" in text and any(
        w in text for w in ("broken arm", "cracked ribs", "black eye", "bruises"))
    assert "mended" in text or "faded" in text or "closed" in text


def test_the_condition_reaches_the_talk_app_and_the_viewer(tmp_path):
    from dwarfsim import talk, viewer
    s = talk.Session(seed=1, dwarves=3, classifier="nope", ticks_per_say=0)
    s.focus.condition.add("broken_leg", 0.6)
    state = s.snapshot()["dwarves"][s.focus.id]
    assert "broken leg" in state["condition"]
    assert state["injuries"] and state["pain"] > 0.0
    w = world(3, 19)
    w.agents[0].condition.add("cut", 0.4)
    assert w.snapshot()["0"]["injuries"][0]["kind"] == "cut"
    # the viewer knows the words for them
    assert "INJURY_WORD" in viewer.TEMPLATE
    assert "CONDITION" in viewer.TEMPLATE
