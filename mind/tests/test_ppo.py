"""Tests for the reward stage: the reward table, the three scenarios, PPO and the evaluation.

    python -m pytest -q tests/test_ppo.py

The reward tests are the ones that matter: they build the *event* -- a death, a kept promise, a
blow out of nowhere, a blow that was earned -- and check the sign of the term it moves. Everything
else here is structural: two iterations of PPO on tiny settings run end to end, are the same run
twice from the same seed, and export something the sim will actually load.
"""

import os
import sys

import numpy as np
import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from dwarfsim import run_sim, schema  # noqa: E402
from dwarfsim.learn import LearnedScorer  # noqa: E402
from dwarfsim.learn import reward as reward_mod  # noqa: E402
from dwarfsim.learn import scenarios as scenario_mod  # noqa: E402
from dwarfsim.obligations import make_ask  # noqa: E402
from dwarfsim.world import LEARN_SCENARIOS, SCENARIOS, World  # noqa: E402

TINY = dict(iterations=2, ticks=60, scenarios=("default", "bully"), seeds=(1,),
            eval_every=2, verbose=False)


def step_with_reward(world, model, n=1):
    """``n`` ticks, returning the last tick's ``{agent id: {term: value}}``."""
    out = {}
    for _ in range(n):
        model.begin()
        step = world.step()
        out = model.end(step)
    return out


# ---------------------------------------------------------------------------
# The reward terms, on constructed events
# ---------------------------------------------------------------------------

def test_a_death_is_a_large_negative():
    w = World(4, seed=1)
    rm = reward_mod.RewardModel(w)
    victim = w.agents[1]
    rm.begin()
    w.tick += 1
    w.events = []
    w.kill(victim, w.agents[0])
    rows = rm.end()
    assert rows[victim.id]["alive"] == -1.0
    assert reward_mod.reward_of(rows[victim.id]) < -10.0
    assert rows[w.agents[0].id]["alive"] == 0.0
    assert rm.counts["deaths"] == 1


def test_a_kept_promise_is_positive_and_a_broken_one_negative():
    for kind, sign in (("keep", +1.0), ("break", -1.0)):
        w = World(4, seed=2)
        asker, obliged = w.agents[0], w.agents[1]
        obliged.place = asker.place
        ob = w.propose_obligation(asker, obliged, make_ask("GIVE", item="food", quantity=1))
        ob.status = "ACCEPTED"
        rm = reward_mod.RewardModel(w)
        rm.begin()
        w.tick += 1
        w.events = []
        if kind == "keep":
            w.fulfil_obligation(ob)
        else:
            w.break_obligation(ob)
        rows = rm.end()
        assert rows[obliged.id]["promises"] == sign
        # and it is the one who was on the hook that is credited, not the one who asked
        assert rows[asker.id]["promises"] == 0.0
        assert (sign > 0) == (reward_mod.reward_of(rows[obliged.id])
                              > reward_mod.reward_of({"promises": 0.0}))


def test_an_unprovoked_blow_is_penalised_and_a_provoked_one_is_not():
    def hit(provoke):
        w = World(4, seed=3)
        attacker, victim = w.agents[0], w.agents[1]
        victim.place = attacker.place
        if provoke == "grudge":
            w.remember(attacker, "HIT", victim.id, attacker.id, attacker.place, 1.0, "SUFFERED")
        elif provoke == "recent":
            attacker.last_hit_by = victim.id
            attacker.last_hit_tick = w.tick
        rm = reward_mod.RewardModel(w)
        rm.begin()
        w.tick += 1
        w.events = []
        w.emit("HIT", attacker, victim, place=attacker.place, extra={"damage": 1.0})
        return rm.end()[attacker.id]["violence"], rm

    cold, rm_cold = hit(None)
    grudge, rm_grudge = hit("grudge")
    recent, rm_recent = hit("recent")
    assert cold == -1.0                    # no grudge, no recent blow: unprovoked
    assert grudge == 0.0                   # they hurt me before
    assert recent == 0.0                   # they hit me just now
    assert rm_cold.counts["unprovoked_hits"] == 1
    assert rm_grudge.counts["unprovoked_hits"] == 0
    assert rm_recent.counts["unprovoked_hits"] == 0
    assert rm_cold.counts["hits"] == rm_grudge.counts["hits"] == 1


def test_standing_follows_what_the_others_think_of_me():
    w = World(4, seed=4)
    actor, other = w.agents[0], w.agents[1]
    other.place = actor.place
    rm = reward_mod.RewardModel(w)
    rm.begin()
    w.tick += 1
    w.events = []
    w.emit("PRAISE", actor, other, place=actor.place, magnitude=1.0)
    praised = rm.end()[actor.id]["standing"]

    rm2 = reward_mod.RewardModel(w)
    rm2.begin()
    w.tick += 1
    w.events = []
    w.emit("INSULT", actor, other, place=actor.place, magnitude=1.0)
    insulted = rm2.end()[actor.id]["standing"]
    assert praised > 0.0 > insulted


def test_needs_and_wealth_point_the_right_way():
    w = World(4, seed=5)
    a = w.agents[0]
    a.mind.needs.update({"hunger": 0.9, "thirst": 0.9, "fatigue": 0.9, "social": 0.9})
    rm = reward_mod.RewardModel(w)
    rm.begin()
    w.tick += 1
    w.events = []
    starving = rm.end()[a.id]["needs"]
    assert starving < -0.8                       # nearly the worst it can be

    a.mind.needs.update({"hunger": 0.05, "thirst": 0.05, "fatigue": 0.05, "social": 0.05})
    rm.begin()
    before = reward_mod.wealth_of(a)
    w.tick += 1
    w.events = []
    a.inv["gold"] += 5
    row = rm.end()[a.id]
    assert row["needs"] > starving
    # Relative: the settlement's mean went up by 5/4 too, so only 5 - 1.25 of it was getting ahead.
    assert row["wealth"] == pytest.approx((5.0 - 5.0 / 4) / reward_mod.WEALTH_SCALE)
    assert reward_mod.wealth_of(a) == before + 5


def test_wealth_pays_for_getting_ahead_and_not_for_a_rising_tide():
    """Four dwarves. One gets richer: it is paid and the other three are charged. All four get
    equally richer: nobody is paid, because nobody got ahead."""
    w = World(4, seed=15)
    rm = reward_mod.RewardModel(w)
    rm.begin()
    w.tick += 1
    w.events = []
    w.agents[0].inv["ore"] += 2                              # worth 4 gold at the forge
    rows = rm.end()
    assert rows[w.agents[0].id]["wealth"] > 0.0
    for a in w.agents[1:]:
        assert rows[a.id]["wealth"] < 0.0
    assert sum(r["wealth"] for r in rows.values()) == pytest.approx(0.0, abs=1e-6)

    rm.begin()
    w.tick += 1
    w.events = []
    for a in w.agents:
        a.inv["gold"] += 3
    rows = rm.end()
    assert all(r["wealth"] == pytest.approx(0.0) for r in rows.values())

    # The old table's absolute wealth is still reachable, and pays everybody for the same tide.
    rm_v1 = reward_mod.RewardModel(w, weights=reward_mod.REWARD_WEIGHTS_V1,
                                   standing_keys=reward_mod.STANDING_KEYS_V1,
                                   relative_wealth=False)
    rm_v1.begin()
    w.tick += 1
    w.events = []
    for a in w.agents:
        a.inv["gold"] += 3
    rows = rm_v1.end()
    assert all(r["wealth"] == pytest.approx(3.0 / reward_mod.WEALTH_SCALE)
               for r in rows.values())


def test_standing_counts_respect_and_not_trust():
    """Trust moves and respect does not: the v1 term sees it, the v2 term does not."""
    w = World(4, seed=16)
    actor, other = w.agents[0], w.agents[1]
    v2 = reward_mod.RewardModel(w)
    v1 = reward_mod.RewardModel(w, standing_keys=reward_mod.STANDING_KEYS_V1)
    v2.begin()
    v1.begin()
    w.tick += 1
    w.events = []
    other.mind.rel(actor.id)["trust"] += 0.30            # flattery moves trust the most
    assert v2.end()[actor.id]["standing"] == pytest.approx(0.0)
    assert v1.end()[actor.id]["standing"] > 0.0

    v2.begin()
    w.tick += 1
    w.events = []
    other.mind.rel(actor.id)["respect"] += 0.30
    assert v2.end()[actor.id]["standing"] > 0.0

    # And on the sim's own events. What the table buys is that *words* are worth much less than
    # they were and *deeds* are worth about the same: SMALLTALK moves no respect at all, PRAISE
    # moves trust 0.07 against respect 0.04, HELP moves respect 0.14 against trust 0.08.
    def standing(kind, keys, weights):
        ww = World(4, seed=17)
        a, o = ww.agents[0], ww.agents[1]
        o.place = a.place
        rm = reward_mod.RewardModel(ww, weights=weights, standing_keys=keys)
        rm.begin()
        ww.tick += 1
        ww.events = []
        ww.emit(kind, a, o, place=a.place, magnitude=1.0)
        return rm.weights["standing"] * rm.end()[a.id]["standing"]

    def v1(kind):
        return standing(kind, reward_mod.STANDING_KEYS_V1, reward_mod.REWARD_WEIGHTS_V1)

    def v2(kind):
        return standing(kind, reward_mod.STANDING_KEYS, reward_mod.REWARD_WEIGHTS)

    assert v2("SMALLTALK") == pytest.approx(0.0)       # small talk buys nothing at all now
    assert 0.0 < v2("PRAISE") <= 0.55 * v1("PRAISE")   # and praise buys about half
    assert v2("HELP") > 0.80 * v1("HELP")              # while helping is nearly untouched
    assert v2("HELP") > 3.0 * v2("PRAISE")             # a deed was 2.1 praises, now it is 3.5


def test_the_time_term_charges_chatter_only_while_a_need_is_up():
    """One dwarf, one decision record, four ways round: the skill and the need both have to be
    there before the term fires."""
    def charged(skill, hunger):
        w = World(4, seed=18)
        a = w.agents[0]
        a.mind.needs.update({"hunger": hunger, "thirst": 0.1, "fatigue": 0.1, "social": 0.9})
        rm = reward_mod.RewardModel(w)
        rm.begin()
        w.tick += 1
        w.events = []
        step = {"decisions": [{"agent": a.id, "chosen": "%s Brokk" % skill}]}
        return rm.end(step)[a.id]["time"], rm.counts["idle_chat"]

    high = reward_mod.TIME_NEED_LEVEL + 0.2
    low = reward_mod.TIME_NEED_LEVEL - 0.2
    assert charged("SOCIALIZE", high) == (-1.0, 1)
    assert charged("GOSSIP", high) == (-1.0, 1)
    assert charged("SOCIALIZE", low) == (0.0, 0)        # nothing pressing: chat away
    assert charged("WORK", high) == (0.0, 0)            # working while hungry is the point
    assert charged("EAT", high) == (0.0, 0)
    # social is deliberately not one of the needs that charge: it was 0.9 in every case above.
    assert "social" not in reward_mod.TIME_NEEDS
    # and with no step handed over there is no way to know, so the term is zero.
    w = World(4, seed=18)
    rm = reward_mod.RewardModel(w)
    rm.begin()
    assert all(row["time"] == 0.0 for row in rm.end().values())


def test_the_time_term_is_small_next_to_a_death_and_real_next_to_a_praise():
    """Signed and scaled: chatting while hungry costs something, but not much."""
    w = reward_mod.REWARD_WEIGHTS
    assert 0.0 < w["time"] < w["needs"] < w["standing"] < w["alive"]
    assert reward_mod.reward_of({"time": -1.0}) == pytest.approx(-w["time"])
    assert reward_mod.REWARD_WEIGHTS_V1["time"] == 0.0        # the first run had no such term
    assert reward_mod.REWARD_WEIGHTS["standing"] <= reward_mod.REWARD_WEIGHTS_V1["standing"]
    assert set(reward_mod.REWARD_WEIGHTS_V1) == set(reward_mod.TERM_NAMES)


def test_the_reward_is_the_weighted_sum_of_the_table():
    terms = {t: 1.0 for t in reward_mod.TERM_NAMES}
    assert reward_mod.reward_of(terms) == pytest.approx(sum(reward_mod.REWARD_WEIGHTS.values()))
    assert set(reward_mod.TERM_NAMES) == set(reward_mod.REWARD_WEIGHTS)


def test_the_episode_csv_rows_add_up(tmp_path):
    w = World(6, seed=6, scenario="thief")
    rm = reward_mod.RewardModel(w)
    step_with_reward(w, rm, 40)
    row = rm.report(policy="teacher", scenario="thief", seed=6, ticks=40)
    assert row["steps"] > 100
    assert sum(row[t] for t in reward_mod.TERM_NAMES) == pytest.approx(row["reward"], abs=1e-3)
    path = str(tmp_path / "episodes.csv")
    with reward_mod.EpisodeCsv(path) as csv:
        csv.write(row)
    text = open(path, encoding="utf-8").read().splitlines()
    assert text[0].startswith("policy,scenario,seed")
    assert len(text) == 2 and text[1].startswith("teacher,thief,6")


# ---------------------------------------------------------------------------
# The three scenarios
# ---------------------------------------------------------------------------

def test_the_new_scenarios_are_ordinary_scenarios():
    for name in scenario_mod.NAMES:
        assert name in SCENARIOS and name in LEARN_SCENARIOS
        w = World(6, seed=7, scenario=name)
        for _ in range(60):
            w.step()
        assert w.tick == 60
    with pytest.raises(ValueError):
        World(6, seed=7, scenario="no-such-scenario")


def test_each_scenario_sets_up_what_it_claims():
    friends = World(6, seed=8, scenario="friends")
    pairs = [a.mind.rel(b.id) for a in friends.agents for b in friends.agents if a.id != b.id]
    assert all(r["trust"] > 0.4 and r["hatred"] < 0.2 for r in pairs)
    assert friends.agents[1].place == friends.agents[0].place

    bully = World(6, seed=8, scenario="bully")
    hostile, victim, protector = bully.agents[0], bully.agents[1], bully.agents[2]
    assert hostile.mind.rel(victim.id)["hatred"] > 0.4
    assert protector.mind.rel(victim.id)["trust"] > 0.5
    assert protector.inv["weapon"] > hostile.inv["weapon"] > victim.inv["weapon"]
    assert hostile.place == victim.place == protector.place
    assert victim.mind.traits["bravery"] < hostile.mind.traits["bravery"]

    thief = World(6, seed=8, scenario="thief")
    assert thief.agents[0].mind.traits["greed"] > 0.9 and thief.agents[0].inv["gold"] == 0
    assert all(a.place == "TAVERN" for a in thief.agents)
    assert all(a.inv["gold"] >= 4 for a in thief.agents[1:])


def test_the_targeted_scenarios_produce_what_they_are_for():
    """Over a few seeds: friends insult without killing, the bully hits, the thief is seen."""
    insults = hits = thefts = seen = 0
    for seed in (11, 12, 13):
        for name, box in (("friends", "i"), ("bully", "h"), ("thief", "t")):
            w = World(6, seed=seed, scenario=name)
            for _ in range(300):
                for ev in w.events:
                    if box == "i" and ev["type"] == "INSULT":
                        insults += 1
                    if box == "h" and ev["type"] == "HIT":
                        hits += 1
                    if box == "t" and ev["type"] == "STEAL":
                        thefts += 1
                        seen += 1 if ev.get("seen") else 0
                w.step()
    assert insults > 0, "friends never rub each other up the wrong way"
    assert hits > 0, "the bully never swings"
    assert thefts > 0 and seen > 0, "the thief is never caught"


# ---------------------------------------------------------------------------
# PPO
# ---------------------------------------------------------------------------

@pytest.fixture(scope="module")
def trained(tmp_path_factory):
    """Two iterations on tiny settings, from the imitator if there is one."""
    from dwarfsim.learn import ppo
    init = "runs/learn/imitator"
    if not os.path.exists(init + ".npz"):
        init = None
    out = str(tmp_path_factory.mktemp("ppo") / "run")
    meta = ppo.train(init=init, out=out, seed=0, **TINY)
    return out, meta


def test_ppo_runs_end_to_end_and_writes_what_it_says(trained):
    out, meta = trained
    for name in ("best.npz", "best.json", "last.npz", "last.json",
                 "progress.csv", "episodes.csv", "training.json"):
        assert os.path.getsize(os.path.join(out, name)) > 0, name
    progress = open(os.path.join(out, "progress.csv"), encoding="utf-8").read().splitlines()
    assert progress[0].split(",")[:5] == ["iteration", "seconds", "episodes", "steps", "reward"]
    assert len(progress) == 1 + TINY["iterations"]
    for term in reward_mod.TERM_NAMES:
        assert term in progress[0].split(",")
    assert "entropy" in progress[0] and "kl_imitator" in progress[0]
    episodes = open(os.path.join(out, "episodes.csv"), encoding="utf-8").read().splitlines()
    assert len(episodes) > TINY["iterations"] * len(TINY["scenarios"])
    assert meta["training"]["gamma"] == 0.99 and meta["training"]["lambda"] == 0.95
    assert meta["training"]["reward_weights"] == reward_mod.REWARD_WEIGHTS


def test_the_exported_policy_is_a_loadable_scorer(trained, tmp_path):
    out, _ = trained
    scorer = LearnedScorer.load(os.path.join(out, "best"))
    scores = scorer.score_all(np.zeros(schema.OBS_SIZE), np.zeros((3, schema.CAND_SIZE)))
    assert scores.shape == (3,)
    summary = run_sim(str(tmp_path / "ppo.jsonl"), n_agents=6, ticks=60, seed=21,
                      scenario="feud", scorer=scorer)
    assert summary["ticks"] == 60


def test_ppo_is_deterministic_under_a_seed(tmp_path):
    from dwarfsim.learn import ppo
    init = "runs/learn/imitator" if os.path.exists("runs/learn/imitator.npz") else None
    rows = []
    for run in ("a", "b"):
        out = str(tmp_path / run)
        ppo.train(init=init, out=out, seed=3, iterations=2, ticks=40,
                  scenarios=("default",), seeds=(1,), eval_every=2, verbose=False)
        rows.append(open(os.path.join(out, "progress.csv"), encoding="utf-8").read())
    a, b = rows
    # The clock column is the one thing that legitimately differs between two runs.
    strip = lambda text: [",".join(line.split(",")[:1] + line.split(",")[2:])  # noqa: E731
                          for line in text.splitlines()]
    assert strip(a) == strip(b)


def test_gae_matches_the_textbook_on_a_hand_built_trajectory():
    from dwarfsim.learn import ppo

    class Roll:
        """The three fields :func:`dwarfsim.learn.ppo.advantages` actually reads."""

        reward = np.array([1.0, -2.0, 0.5], dtype=np.float32)
        traj = [(np.array([0, 1, 2]), True, None)]

        def __len__(self):
            return len(self.reward)

    roll = Roll()
    values = np.array([0.1, 0.2, 0.3], dtype=np.float32)
    adv = ppo.advantages(roll, values, None, gamma=0.99, lam=0.95)

    expect = np.zeros(3)
    running = 0.0
    for i in (2, 1, 0):
        next_v = 0.0 if i == 2 else values[i + 1]
        nonterminal = 0.0 if i == 2 else 1.0
        delta = roll.reward[i] + 0.99 * next_v * nonterminal - values[i]
        running = delta + 0.99 * 0.95 * nonterminal * running
        expect[i] = running
    assert adv == pytest.approx(expect, abs=1e-5)


# ---------------------------------------------------------------------------
# Evaluation
# ---------------------------------------------------------------------------

def test_evaluate_compares_policies_on_the_same_settlements(trained, tmp_path):
    from dwarfsim.learn import evaluate
    out, _ = trained
    csv_path = str(tmp_path / "evaluation.csv")
    result = evaluate.evaluate(["teacher", os.path.join(out, "best")], seeds=[20],
                               scenarios=("feud", "thief"), ticks=60, out=csv_path,
                               verbose=False)
    assert set(result["policies"]) == {"teacher", "run/best"}
    for name, row in result["policies"].items():
        assert row["episodes"] == 2
        assert set(row["terms"]) == set(reward_mod.TERM_NAMES)
        assert set(row["per_scenario"]) == {"feud", "thief"}
        assert row["counts"]["hits"] >= row["counts"]["unprovoked_hits"] >= 0
        assert row["skills"]["ATTACK"] >= 0
    lines = open(csv_path, encoding="utf-8").read().splitlines()
    assert len(lines) == 1 + 4
    evaluate.report(result)      # must not raise: it is the deliverable's table


# ---------------------------------------------------------------------------
# The trait-conditioned table
# ---------------------------------------------------------------------------

def _traits(agent, **kw):
    """Set some traits and leave the rest at dead centre, so only what is named can matter."""
    for t in agent.mind.traits:
        agent.mind.traits[t] = 0.5
    agent.mind.traits.update(kw)
    return agent


def test_the_trait_table_is_the_base_table_for_an_average_dwarf():
    """Every multiplier is 1.0 at trait 0.5, which is what keeps the settlement-wide scale where
    v1 put it and lets the PPO settings transfer."""
    w = World(4, seed=30)
    a = _traits(w.agents[0])
    for term in reward_mod.TRAIT_SHAPING:
        assert reward_mod.trait_scale(a, term) == pytest.approx(1.0), term
    # the six trait-only terms are dead weight in the other two tables, so a v1 or v2 run is
    # exactly the run it always was
    for table in (reward_mod.REWARD_WEIGHTS, reward_mod.REWARD_WEIGHTS_V1):
        assert all(table[t] == 0.0 for t in reward_mod.TRAIT_TERMS)
        assert set(table) == set(reward_mod.TERM_NAMES)
    assert set(reward_mod.REWARD_WEIGHTS_TRAIT) == set(reward_mod.TERM_NAMES)
    # and loyalty is derived, because the sim only rolls six traits
    assert "loyalty" not in a.mind.traits
    _traits(a, forgiveness=1.0, greed=0.0)
    assert reward_mod.trait_value(a, "loyalty") == pytest.approx(1.0)
    _traits(a, forgiveness=0.0, greed=1.0)
    assert reward_mod.trait_value(a, "loyalty") == pytest.approx(0.0)


def test_a_greedy_dwarf_is_paid_more_for_the_same_gold():
    w = World(4, seed=31)
    greedy = _traits(w.agents[0], greed=0.9)
    indifferent = _traits(w.agents[1], greed=0.1)
    rm = reward_mod.RewardModel(w, mode="trait")
    rm.begin()
    w.tick += 1
    w.events = []
    greedy.inv["gold"] += 5
    indifferent.inv["gold"] += 5
    rows = rm.end()
    assert rows[greedy.id]["wealth"] > rows[indifferent.id]["wealth"] > 0.0
    assert (reward_mod.reward_of(rows[greedy.id], reward_mod.REWARD_WEIGHTS_TRAIT)
            > reward_mod.reward_of(rows[indifferent.id], reward_mod.REWARD_WEIGHTS_TRAIT))
    # the same five gold, worth 1.64 against 0.36: the table's 0.20 -> 1.80 across the range
    assert rows[greedy.id]["wealth"] == pytest.approx(
        rows[indifferent.id]["wealth"] * reward_mod.trait_scale(greedy, "wealth")
        / reward_mod.trait_scale(indifferent, "wealth"))
    assert reward_mod.trait_scale(greedy, "wealth") == pytest.approx(1.64)
    assert reward_mod.trait_scale(indifferent, "wealth") == pytest.approx(0.36)
    # and under v2 the two are identical, because nothing there knows who is greedy
    w2 = World(4, seed=31)
    _traits(w2.agents[0], greed=0.9)
    _traits(w2.agents[1], greed=0.1)
    plain = reward_mod.RewardModel(w2)
    plain.begin()
    w2.tick += 1
    w2.events = []
    w2.agents[0].inv["gold"] += 5
    w2.agents[1].inv["gold"] += 5
    rows2 = plain.end()
    assert rows2[w2.agents[0].id]["wealth"] == pytest.approx(rows2[w2.agents[1].id]["wealth"])


def test_a_cowardly_dwarf_is_charged_more_for_the_same_wound():
    w = World(4, seed=32)
    brave = _traits(w.agents[0], bravery=0.9)
    coward = _traits(w.agents[1], bravery=0.1)
    rm = reward_mod.RewardModel(w, mode="trait")
    rm.begin()
    w.tick += 1
    w.events = []
    brave.health -= 4.0
    coward.health -= 4.0
    rows = rm.end()
    assert rows[coward.id]["hurt"] < rows[brave.id]["hurt"] < 0.0
    assert (reward_mod.reward_of(rows[coward.id], reward_mod.REWARD_WEIGHTS_TRAIT)
            < reward_mod.reward_of(rows[brave.id], reward_mod.REWARD_WEIGHTS_TRAIT))
    # dying costs the timid one more too, and starting a fight costs it much more
    assert reward_mod.trait_scale(coward, "alive") > reward_mod.trait_scale(brave, "alive")
    assert reward_mod.trait_scale(coward, "violence") > reward_mod.trait_scale(brave, "violence")
    # v2 has no hurt term at all, whoever is bleeding
    plain = reward_mod.RewardModel(w)
    plain.begin()
    w.tick += 1
    w.events = []
    coward.health -= 4.0
    assert plain.end()[coward.id]["hurt"] == 0.0


def test_a_proud_dwarf_is_charged_for_ignoring_a_public_insult():
    def answered_with(skill, pride, public=True):
        w = World(4, seed=33)
        target, rude = w.agents[0], w.agents[1]
        _traits(target, pride=pride, temper=0.5)
        rude.place = target.place
        for other in w.agents[2:]:
            other.place = target.place if public else "MINE"
        rm = reward_mod.RewardModel(w, mode="trait")
        rm.begin()
        w.tick += 1
        w.events = []
        w.emit("INSULT", rude, target, place=target.place, magnitude=1.0)
        rm.end()                       # the insult is noticed here, and answered next tick
        rm.begin()
        w.tick += 1
        w.events = []
        step = {"decisions": [{"agent": target.id, "chosen": "%s ->%d" % (skill, rude.id)}]}
        return rm.end(step)[target.id]

    proud = answered_with("IGNORE", 0.9)
    humble = answered_with("IGNORE", 0.1)
    assert proud["slight"] < humble["slight"] < 0.0
    assert (reward_mod.reward_of(proud, reward_mod.REWARD_WEIGHTS_TRAIT)
            < reward_mod.reward_of(humble, reward_mod.REWARD_WEIGHTS_TRAIT))
    # answering it is not charged -- and a quick-tempered dwarf is paid a little for answering
    assert answered_with("DEMAND_APOLOGY", 0.9)["slight"] == 0.0
    assert answered_with("RETORT", 0.9)["slight"] == 0.0
    assert answered_with("RETORT", 0.9)["payback"] > 0.0
    assert answered_with("IGNORE", 0.9)["payback"] == 0.0
    # said with nobody else in the room it is not a humiliation, so ignoring it is free
    assert answered_with("IGNORE", 0.9, public=False)["slight"] == 0.0


def test_forgiveness_is_what_makes_a_grudge_cost_anything():
    """Holding a grudge is charged to the forgiving and is nearly free to the unforgiving, who
    may as well keep it -- which is the one term deliberately pointed the unintuitive way."""
    w = World(4, seed=34)
    forgiving = _traits(w.agents[0], forgiveness=0.9)
    bitter = _traits(w.agents[1], forgiveness=0.1)
    for holder in (forgiving, bitter):
        w.remember(holder, "HIT", w.agents[2].id, holder.id, holder.place, 1.0, "SUFFERED")
    rm = reward_mod.RewardModel(w, mode="trait")
    rm.begin()
    w.tick += 1
    w.events = []
    rows = rm.end()
    assert rows[forgiving.id]["grudge"] < rows[bitter.id]["grudge"] < 0.0
    assert rows[w.agents[3].id]["grudge"] == 0.0        # nothing held, nothing charged


def test_every_reward_mode_runs_a_settlement_and_keeps_the_same_scale():
    """The three tables on the same 200 ticks: the trait one is the same order of magnitude as
    v1, which is the whole reason the PPO settings carry over."""
    from dwarfsim.learn.ppo import play
    got = {}
    for mode in sorted(reward_mod.MODES):
        ep = play(None, "default", 20, 200, record=False, reward_mode=mode)
        got[mode] = ep["report"]["reward"]
        assert ep["report"]["steps"] > 500
    assert 0.5 < got["trait"] / got["v1"] < 2.0
    with pytest.raises(ValueError):
        reward_mod.RewardModel(World(4, seed=35), mode="v3")
