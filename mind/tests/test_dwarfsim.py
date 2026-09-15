"""Tests for dwarfsim. Standard library plus pytest; nothing else.

    python -m pytest -q tests
"""

import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from dwarfsim import arbitrator, goals, memory, mind, obligations, schema  # noqa: E402
from dwarfsim import skills, speech  # noqa: E402
from dwarfsim import log as log_mod  # noqa: E402
from dwarfsim import run_sim  # noqa: E402
from dwarfsim.log import load  # noqa: E402
from dwarfsim.world import World  # noqa: E402


def digest(world, ticks):
    """A fingerprint of a whole run: every decision and every mind, tick by tick."""
    out = []
    for _ in range(ticks):
        step = world.step()
        out.append([d["chosen"] for d in step["decisions"]])
        out.append([(round(a.health, 3), a.place,
                     round(a.mind.emotions["anger"], 6), round(a.mind.emotions["fear"], 6))
                    for a in world.agents])
    return out


# ---------------------------------------------------------------------------
# Determinism
# ---------------------------------------------------------------------------

def test_same_seed_same_run():
    a = digest(World(6, seed=7, scenario="default"), 300)
    b = digest(World(6, seed=7, scenario="default"), 300)
    assert a == b


def test_different_seed_different_run():
    a = digest(World(6, seed=7, scenario="default"), 300)
    b = digest(World(6, seed=8, scenario="default"), 300)
    assert a != b


def test_logged_run_is_reproducible(tmp_path):
    one = run_sim(str(tmp_path / "a.jsonl"), ticks=250, seed=11, scenario="theft")
    two = run_sim(str(tmp_path / "b.jsonl"), ticks=250, seed=11, scenario="theft")
    assert one == two
    assert (tmp_path / "a.jsonl").read_bytes() == (tmp_path / "b.jsonl").read_bytes()


# ---------------------------------------------------------------------------
# The event table
# ---------------------------------------------------------------------------

def test_insult_raises_anger_and_lowers_trust():
    w = World(2, seed=3)
    a, b = w.agents[0], w.agents[1]
    b.mind.emotions["anger"] = 0.2
    before_anger = b.mind.emotions["anger"]
    before_trust = b.mind.rel(a.id)["trust"]
    mind.apply_event("INSULT", a, b)
    assert b.mind.emotions["anger"] > before_anger
    assert b.mind.rel(a.id)["trust"] < before_trust
    assert b.mind.rel(a.id)["hatred"] > 0.0


def test_praise_does_the_opposite():
    w = World(2, seed=3)
    a, b = w.agents[0], w.agents[1]
    b.mind.emotions["anger"] = 0.5
    b.mind.rel(a.id)["hatred"] = 0.4
    mind.apply_event("PRAISE", a, b)
    assert b.mind.emotions["anger"] < 0.5
    assert b.mind.rel(a.id)["trust"] > 0.0
    assert b.mind.rel(a.id)["hatred"] < 0.4
    assert b.mind.emotions["happiness"] > 0.0


def test_apply_event_reports_its_deltas():
    w = World(2, seed=3)
    a, b = w.agents[0], w.agents[1]
    deltas = mind.apply_event("INSULT", a, b)
    fields = {(d["who"], d["f"]) for d in deltas}
    assert (b.id, "anger") in fields
    assert (b.id, "rel:%s:trust" % a.id) in fields
    for d in deltas:
        assert d["from"] != d["to"]


def test_witness_who_likes_the_victim_reacts_harder():
    w = World(3, seed=5)
    actor, victim, friend, = w.agents[0], w.agents[1], w.agents[2]
    stranger = World(3, seed=5).agents[2]     # same traits, no opinion of the victim
    friend.mind.rel(victim.id)["trust"] = 0.9
    mind.apply_event("INSULT", actor, victim, witnesses=[friend])
    mind.apply_event("INSULT", actor, victim, witnesses=[stranger])
    assert friend.mind.rel(actor.id)["hatred"] > stranger.mind.rel(actor.id)["hatred"]


def test_kill_splits_witnesses_by_how_they_felt_about_the_dead():
    w = World(4, seed=5)
    killer, dead, mourner, enemy = w.agents
    for x in w.agents:
        x.place = "HALL"
    mourner.mind.rel(dead.id)["trust"] = 0.8
    enemy.mind.rel(dead.id)["hatred"] = 0.9
    w.kill(dead, killer)
    assert mourner.mind.rel(killer.id)["hatred"] > 0.1
    assert mourner.mind.emotions["grief"] > 0.1
    assert enemy.mind.rel(killer.id)["respect"] > 0.0
    assert enemy.mind.rel(killer.id)["hatred"] <= 0.0


def test_emotions_decay_toward_baseline():
    w = World(1, seed=2)
    a = w.agents[0]
    a.mind.baseline["anger"] = 0.1
    a.mind.emotions["anger"] = 0.9
    a.mind.emotions["fear"] = 0.0
    a.mind.baseline["fear"] = 0.4
    for _ in range(400):
        a.mind.decay()
    assert abs(a.mind.emotions["anger"] - 0.1) < 0.01     # came down
    assert abs(a.mind.emotions["fear"] - 0.4) < 0.01      # came up
    # and monotonically, not by overshooting
    a.mind.emotions["anger"] = 0.9
    seen = []
    for _ in range(20):
        a.mind.decay()
        seen.append(a.mind.emotions["anger"])
    assert all(seen[i] > seen[i + 1] > 0.1 for i in range(len(seen) - 1))


def test_needs_rise_and_relationships_fade():
    w = World(2, seed=2)
    a, b = w.agents
    a.mind.needs["hunger"] = 0.0
    a.mind.rel(b.id)["hatred"] = 0.8
    for _ in range(100):
        a.mind.decay()
    assert a.mind.needs["hunger"] > 0.3
    assert 0.5 < a.mind.rel(b.id)["hatred"] < 0.8


# ---------------------------------------------------------------------------
# The speech hook
# ---------------------------------------------------------------------------

THREAT_LINE = {
    "text": "Get away from my forge, Brokk, or I'll break your arm.",
    "intent": "THREAT", "topic": "FORGE", "addressed": "LISTENER",
    "aggression": 0.85, "valence": -0.8, "urgency": 0.7, "names": ["Brokk"],
}


def test_threat_becomes_a_threat_event_with_the_right_deltas():
    w = World(3, seed=4)
    speaker, listener, bystander = w.agents
    for x in w.agents:
        x.place = "FORGE"
    before = dict(listener.mind.emotions)
    before_trust = listener.mind.rel(speaker.id)["trust"]
    ev = w.say(speaker, listener, THREAT_LINE)
    assert ev["type"] == "THREAT"
    assert ev["actor"] == speaker.id and ev["target"] == listener.id
    assert ev["text"] == THREAT_LINE["text"]
    assert listener.mind.emotions["fear"] > before["fear"]
    assert listener.mind.emotions["anger"] > before["anger"]
    assert listener.mind.rel(speaker.id)["trust"] < before_trust
    assert listener.mind.rel(speaker.id)["hatred"] > 0.0
    # The bystander saw it and thinks less of the speaker, but by a fraction. Dwarves start
    # slightly acquainted, so what matters is the fall, not the sign.
    was = World(3, seed=4).agents[2].mind.rel(speaker.id)["trust"]
    assert bystander.mind.rel(speaker.id)["trust"] < was
    assert was - bystander.mind.rel(speaker.id)["trust"] < \
        before_trust - listener.mind.rel(speaker.id)["trust"]
    assert bystander.mind.emotions["fear"] > 0.0


def test_aggression_scales_the_blow():
    def fear_after(aggression):
        w = World(2, seed=4)
        line = dict(THREAT_LINE, aggression=aggression)
        w.agents[1].place = w.agents[0].place
        w.say(w.agents[0], w.agents[1], line)
        return w.agents[1].mind.emotions["fear"]
    assert fear_after(0.9) > fear_after(0.1)


def test_praise_and_warning_and_third_party_targeting():
    w = World(3, seed=6)
    speaker, listener, third = w.agents
    for x in w.agents:
        x.place = "HALL"
    w.say(speaker, listener, {"text": "Fine work.", "intent": "PRAISE", "topic": "WORK",
                              "addressed": "LISTENER", "aggression": 0.0, "valence": 0.8,
                              "urgency": 0.1, "names": []})
    assert listener.mind.rel(speaker.id)["trust"] > 0.0

    w.say(speaker, listener, {"text": "Behind you, a creeper!", "intent": "WARNING",
                              "topic": "MONSTER", "addressed": "LISTENER", "aggression": 0.1,
                              "valence": 0.3, "urgency": 0.95, "names": []})
    assert listener.mind.emotions["fear"] > 0.0

    # "Don't trust <third>, he cheats at dice" is aimed at the one named, not the one listening.
    ev = w.say(speaker, listener, {
        "text": "Don't trust %s, he cheats at dice." % third.name, "intent": "ACCUSE",
        "topic": "CLAN", "addressed": "THIRD", "aggression": 0.5, "valence": -0.6,
        "urgency": 0.2, "names": [third.name]})
    assert ev["target"] == third.id
    assert third.mind.emotions["anger"] > 0.0


def test_request_becomes_a_pull_and_not_an_event():
    w = World(2, seed=6)
    speaker, listener = w.agents
    listener.place = speaker.place
    ev = w.say(speaker, listener, {"text": "Get to the mine.", "intent": "COMMAND",
                                   "topic": "MINE", "addressed": "LISTENER", "aggression": 0.2,
                                   "valence": 0.0, "urgency": 0.8, "names": []})
    assert ev["type"] == "REQUEST"
    assert "deltas" not in ev                      # a task proposal, not a feeling
    assert listener.request["place"] == "MINE"
    ctx = arbitrator.TermContext(listener, w)
    from dwarfsim.skills import Candidate
    mine = Candidate("WORK", place="MINE")
    forge = Candidate("WORK", place="FORGE")
    trusted = ctx.value("request_pull", mine)
    assert trusted > 0.0
    assert ctx.value("request_pull", forge) == 0.0
    # weighted by trust: the same order from someone you distrust pulls less
    listener.mind.rel(speaker.id)["trust"] = -0.9
    assert arbitrator.TermContext(listener, w).value("request_pull", mine) < trusted


# ---------------------------------------------------------------------------
# The vectors
# ---------------------------------------------------------------------------

def test_vector_lengths_match_the_schema():
    w = World(6, seed=9)
    a = w.agents[0]
    assert len(a.mind.to_vector()) == schema.MIND_SIZE
    obs = arbitrator.observation_vector(a, w)
    assert len(obs) == schema.OBS_SIZE
    cands = arbitrator.gather(a, w)
    ctx = arbitrator.TermContext(a, w)
    for c in cands:
        assert len(arbitrator.candidate_features(ctx, c)) == schema.CAND_SIZE
    assert schema.MIND_SIZE == schema.MIND_FOCUS + schema.FOCUS_SLOTS * schema.FOCUS_STRIDE
    assert schema.CAND_SIZE == schema.N_SKILLS + schema.N_TERMS
    assert len(schema.TERM_NAMES) == len(set(schema.TERM_NAMES))
    assert len(schema.SKILL_NAMES) == len(set(schema.SKILL_NAMES))


def test_vector_offsets_hold_what_they_claim():
    w = World(6, seed=9)
    a = w.agents[0]
    a.mind.emotions["anger"] = 0.42
    a.mind.needs["thirst"] = 0.31
    a.health = 10.0
    a.inv["gold"] = 10
    a.place = "TAVERN"
    v = arbitrator.observation_vector(a, w)
    assert v[schema.MIND_EMOTIONS + 0] == 0.42
    assert v[schema.MIND_NEEDS + 1] == 0.31
    assert v[schema.MIND_HEALTH] == 0.5
    assert v[schema.MIND_INVENTORY + 1] == 0.5
    assert v[schema.OBS_PLACE + schema.PLACE_INDEX["TAVERN"]] == 1.0
    assert sum(v[schema.OBS_PLACE:schema.OBS_PLACE + schema.N_PLACES]) == 1.0
    for slot in range(schema.FOCUS_SLOTS):
        off = schema.MIND_FOCUS + slot * schema.FOCUS_STRIDE
        assert v[off] in (0.0, 1.0)


def test_every_weighted_term_is_a_known_term():
    for skill, weights in arbitrator.WEIGHTS.items():
        assert skill in schema.SKILL_NAMES
        for term in weights:
            assert term in schema.TERM_NAMES, "%s weighs unknown term %s" % (skill, term)
    assert set(arbitrator.WEIGHTS) == set(schema.SKILL_NAMES)


def test_the_flat_scorer_reproduces_the_table():
    """score(observation, candidate_features) must equal what the table gives the live candidate."""
    w = World(6, seed=13)
    for _ in range(40):
        w.step()
    for a in w.living():
        ctx = arbitrator.TermContext(a, w)
        chosen, cands = arbitrator.decide(a, w, temperature=0.25, ctx=ctx)
        obs = arbitrator.observation_vector(a, w)
        for c in cands:
            # the context caches its noise draw per candidate, so this is an exact check
            flat = arbitrator.score(obs, arbitrator.candidate_features(ctx, c))
            assert abs(flat - c.score) < 1e-9, c.label()
        break


# ---------------------------------------------------------------------------
# Emergence: the feud scenario, read back out of the log
# ---------------------------------------------------------------------------

def test_feud_scenario_ends_in_blows_without_being_scripted(tmp_path):
    path = str(tmp_path / "feud.jsonl")
    run_sim(path, n_agents=6, ticks=2000, seed=1, scenario="feud")
    run = load(path)
    feuding = {0, 1}
    hits = [(t["tick"], e) for t in run["ticks"] for e in t["events"]
            if e["type"] == "HIT" and {e["actor"], e["target"]} == feuding]
    assert hits, "the two feuding dwarves never came to blows"
    first_tick = hits[0][0]

    # It has to have come out of the state, so the attacker's own decision must show ATTACK
    # winning on terms that are about the target, not a constant.
    decision = None
    for t in run["ticks"]:
        if t["tick"] != first_tick:
            continue
        for d in t["decisions"]:
            if d["agent"] == hits[0][1]["actor"] and d["chosen"].startswith("ATTACK"):
                decision = d
    assert decision is not None, "no ATTACK decision logged on the tick of the first blow"
    terms = decision["top"][0]["terms"]
    assert any(k in terms for k in ("anger_at_target", "hatred_target", "being_attacked")), terms
    assert decision["top"][0]["terms"]  # a breakdown, not an empty dict

    # And nothing in the codebase counts insults or schedules an attack.
    source = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "dwarfsim")
    for fname in os.listdir(source):
        if not fname.endswith(".py"):
            continue
        text = open(os.path.join(source, fname), encoding="utf-8").read()
        assert "insult_count" not in text and "attack_after" not in text


def test_feud_leaves_a_trail_in_the_summary(tmp_path):
    path = str(tmp_path / "feud2.jsonl")
    summary = run_sim(path, n_agents=6, ticks=2000, seed=1, scenario="feud")
    assert summary["fights"]["hits"] > 0
    assert summary["story"], "a run with fights should narrate something"
    assert all(s["text"].startswith("tick ") for s in summary["story"])
    ticks = [s["tick"] for s in summary["story"]]
    assert ticks == sorted(ticks)


# ---------------------------------------------------------------------------
# The log and the viewer
# ---------------------------------------------------------------------------

def test_log_is_complete_every_tick(tmp_path):
    path = str(tmp_path / "run.jsonl")
    run_sim(path, n_agents=6, ticks=120, seed=2, scenario="raid")
    run = load(path)
    assert run["header"]["schema"] == schema.SCHEMA_ID
    assert run["header"]["obs_size"] == schema.OBS_SIZE
    assert len(run["ticks"]) == 120
    for t in run["ticks"]:
        assert "events" in t and "decisions" in t and "agents" in t
        for d in t["decisions"]:
            assert d["top"], "every decision carries its candidates"
            assert any(c.get("won") for c in d["top"]), "the winner is marked"
            for c in d["top"]:
                assert isinstance(c["terms"], dict)
            assert d["chosen"] == [c for c in d["top"] if c.get("won")][0]["action"]
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            json.loads(line)   # every line is its own object


def test_viewer_is_one_self_contained_file(tmp_path):
    from dwarfsim.viewer import write_html
    log_path = str(tmp_path / "run.jsonl")
    html_path = str(tmp_path / "run.html")
    run_sim(log_path, n_agents=6, ticks=60, seed=2)
    write_html(log_path, html_path)
    html = open(html_path, encoding="utf-8").read()
    assert "<script id=\"run\" type=\"application/json\">" in html
    for forbidden in ("http://", "https://", "fetch(", "XMLHttpRequest", "import("):
        assert forbidden not in html, "the viewer must not reach outside the file: %s" % forbidden
    payload = html.split("<script id=\"run\" type=\"application/json\">")[1].split("</script>")[0]
    data = json.loads(payload.replace("\\u003c", "<"))
    assert len(data["ticks"]) == 60
    assert data["summary"]["type"] == "summary"


def test_scenarios_all_run_and_differ():
    seen = {}
    for name in ("default", "feud", "theft", "raid", "gossip", "player", "feud-chief"):
        w = World(6, seed=4, scenario=name)
        for _ in range(200):
            w.step()
        seen[name] = [round(a.mind.emotions["anger"], 4) for a in w.agents]
    assert seen["default"] != seen["feud"]
    assert seen["default"] != seen["theft"]
    assert seen["default"] != seen["raid"]
    assert seen["default"] != seen["gossip"]
    assert seen["feud"] != seen["feud-chief"]


def test_speech_covers_every_intent_in_the_schema():
    """Every intent in text/SCHEMA.md has a mapping, and every event it names exists."""
    intents = ["GREET", "FAREWELL", "SMALLTALK", "QUESTION", "REQUEST", "COMMAND", "OFFER",
               "PRAISE", "APOLOGY", "INSULT", "THREAT", "WARNING", "ACCUSE"]
    assert sorted(speech.INTENT_EVENT) == sorted(intents)
    for intent, event in speech.INTENT_EVENT.items():
        assert event is None or event in mind.EVENT_TABLE
    for topic in ("FORGE", "MINE", "TREASURE", "FOOD", "DRINK", "HOME", "WEAPON", "WORK",
                  "CLAN", "MONSTER", "TRADE", "NONE"):
        assert topic in speech.TOPIC_PLACE
        assert speech.TOPIC_PLACE[topic] is None or speech.TOPIC_PLACE[topic] in schema.PLACES


def test_every_required_event_is_in_the_table():
    for kind in ("INSULT", "PRAISE", "THREAT", "ACCUSE", "APOLOGY", "GIFT", "STEAL", "HIT",
                 "KILL", "HELP", "WARNING"):
        assert kind in mind.EVENT_TABLE
        row = mind.EVENT_TABLE[kind]
        assert "target" in row and "witness" in row


def test_names_come_only_from_the_pool():
    w = World(12, seed=5)
    for a in w.agents:
        assert a.name in schema.NAME_POOL
    assert len({a.name for a in w.agents}) == 12


# ---------------------------------------------------------------------------
# 1. Graded reactions to provocation
# ---------------------------------------------------------------------------

def provoke(world, actor, victim, kind="INSULT", witnesses=None, magnitude=1.0):
    """Do something to somebody in front of a room, then let everyone notice."""
    world.emit(kind, actor, victim, witnesses=witnesses, place=victim.place, magnitude=magnitude)
    world.sense()


def candidates_by_skill(agent, world):
    out = {}
    for c in arbitrator.gather(agent, world):
        out.setdefault(c.skill, []).append(c)
    return out


def test_a_provocation_opens_the_whole_spread_of_reactions():
    """Every graded reaction is a real candidate, and each carries a logged term breakdown."""
    w = World(4, seed=5)
    rude, victim, friend, other = w.agents
    for x in w.agents:
        x.place = "HALL"
    victim.mind.rel(friend.id)["trust"] = 0.8      # somebody worth complaining to
    provoke(w, rude, victim)

    by_skill = candidates_by_skill(victim, w)
    for skill in schema.REACTION_SKILLS:
        assert skill in by_skill, "%s was never proposed after a public insult" % skill
        # COMPLAIN_TO is aimed at a confidant and is *about* the provoker; the others are
        # aimed at the provoker directly. about() collapses the two.
        assert any(c.about() == rude.id for c in by_skill[skill]), skill
    assert any(c.target == rude.id for c in by_skill["ATTACK"])

    # and every one of them is scored on terms that are actually about the provocation
    chosen, cands = arbitrator.decide(victim, w)
    scored = {c.skill: c for c in cands if c.target == rude.id}
    wanted = {"IGNORE": ("forgiveness", "temper", "humiliation"),
              "RETORT": ("temper", "provoked_by_target", "fear"),
              "DEMAND_APOLOGY": ("pride", "humiliation", "publicity"),
              "AVOID": ("fear", "bravery", "provoked_by_target"),
              "ATTACK": ("hatred_target", "grudge_target", "bravery")}
    for skill, terms in wanted.items():
        weighted = set(arbitrator.WEIGHTS[skill])
        for t in terms:
            assert t in weighted, "%s does not weigh %s" % (skill, t)
        assert scored[skill].contrib, "%s was scored with an empty breakdown" % skill
    complaint = [c for c in cands if c.skill == "COMPLAIN_TO"]
    assert complaint and complaint[0].detail["about"] == rude.id
    assert "trust_target" in arbitrator.WEIGHTS["COMPLAIN_TO"]


def test_who_you_are_decides_how_you_take_it():
    """Same insult, same room: a proud hothead and a meek forgiver do different things."""
    def winner(traits, seed=5):
        w = World(4, seed=seed)
        rude, victim = w.agents[0], w.agents[1]
        for x in w.agents:
            x.place = "HALL"
        victim.mind.traits.update(traits)
        provoke(w, rude, victim)
        ranked = sorted(arbitrator.decide(victim, w, temperature=0.01)[1], key=lambda c: -c.score)
        return [c.skill for c in ranked if c.target == rude.id][0]

    proud = winner({"pride": 0.95, "temper": 0.9, "bravery": 0.8, "forgiveness": 0.1})
    meek = winner({"pride": 0.05, "temper": 0.1, "bravery": 0.05, "forgiveness": 0.95})
    assert proud != meek, "pride and temper made no difference to the reaction"
    assert proud in ("RETORT", "DEMAND_APOLOGY", "ATTACK")
    assert meek in ("IGNORE", "AVOID")


def test_a_public_slight_weighs_more_than_a_private_one():
    """Publicity and pride multiply, which is the one thing a linear scorer cannot do itself."""
    def demand_score(n_witnesses):
        w = World(5, seed=6)
        rude, victim = w.agents[0], w.agents[1]
        for x in w.agents:
            x.place = "HALL"
        victim.mind.traits["pride"] = 0.9
        room = w.agents[2:2 + n_witnesses]
        provoke(w, rude, victim, witnesses=room)
        cands = arbitrator.decide(victim, w)[1]
        return [c for c in cands if c.skill == "DEMAND_APOLOGY" and c.target == rude.id][0]

    alone = demand_score(0)
    crowd = demand_score(3)
    assert crowd.terms.get("humiliation", 0.0) > alone.terms.get("humiliation", 0.0)
    assert crowd.score > alone.score


def test_a_demand_gives_the_provoker_an_apology_and_a_refusal_to_weigh():
    w = World(3, seed=8)
    rude, victim = w.agents[0], w.agents[1]
    for x in w.agents:
        x.place = "HALL"
    provoke(w, rude, victim)
    demand = [c for c in arbitrator.gather(victim, w)
              if c.skill == "DEMAND_APOLOGY" and c.target == rude.id][0]
    skills.SKILL_BY_NAME["DEMAND_APOLOGY"].execute(victim, w, demand)
    assert victim.id in rude.demands

    by_skill = candidates_by_skill(rude, w)
    assert any(c.target == victim.id for c in by_skill["APOLOGIZE"])
    assert any(c.target == victim.id for c in by_skill["REFUSE_APOLOGY"])
    apology = [c for c in by_skill["APOLOGIZE"] if c.target == victim.id][0]
    assert apology.hints["obligation_pressure"] > 0.0


def test_avoiding_somebody_leaves_and_stops_you_seeking_them_out():
    w = World(3, seed=9)
    rude, victim = w.agents[0], w.agents[1]
    for x in w.agents:
        x.place = "HALL"
    provoke(w, rude, victim, kind="HIT")
    avoid = [c for c in arbitrator.gather(victim, w)
             if c.skill == "AVOID" and c.target == rude.id][0]
    skills.SKILL_BY_NAME["AVOID"].execute(victim, w, avoid)
    assert victim.place != "HALL"
    assert victim.avoiding(rude.id, w.tick)
    social = [c for c in arbitrator.gather(victim, w) if c.skill in ("SOCIALIZE", "GOSSIP")]
    assert all(c.target != rude.id for c in social)


# ---------------------------------------------------------------------------
# 2. Episodic memory
# ---------------------------------------------------------------------------

def test_an_event_lands_in_every_head_that_was_there():
    w = World(4, seed=5)
    thief, victim, witness, elsewhere = w.agents
    for x in (thief, victim, witness):
        x.place = "MINE"
    elsewhere.place = "FARM"
    w.tick = 40
    w.emit("STEAL", thief, victim, place="MINE")

    mine = list(victim.memories)[0]
    assert (mine.kind, mine.actor, mine.source) == ("STEAL", thief.id, "SUFFERED")
    assert mine.place == "MINE" and mine.tick == 40 and mine.witnesses == 1
    seen = list(witness.memories)[0]
    assert seen.source == "SEEN"
    assert seen.intensity < mine.intensity       # you feel it less if it was not your ore
    assert len(elsewhere.memories) == 0


def test_salience_decays_and_forgiveness_sets_the_rate():
    bitter = memory.Memory(0, "HIT", 0, 1, "HALL", 1.0, "SUFFERED")
    easy = memory.Memory(0, "HIT", 0, 1, "HALL", 1.0, "SUFFERED")
    assert bitter.salience(0, 0.05) == 1.0
    for t in (50, 200, 800):
        assert bitter.salience(t, 0.05) > easy.salience(t, 0.95)
    assert bitter.salience(2000, 0.05) < 1.0
    assert easy.salience(800, 0.95) < 0.3


def test_grudge_and_gratitude_are_derived_from_memory_not_stored():
    w = World(3, seed=5)
    a, b, friend = w.agents
    for x in w.agents:
        x.place = "HALL"
    assert a.memories.grudge(w.tick, a, b.id) == 0.0
    w.emit("HIT", b, a, place="HALL")
    w.emit("STEAL", b, a, place="HALL")
    assert a.memories.grudge(w.tick, a, b.id) > 0.2
    assert a.memories.gratitude(w.tick, a, b.id) == 0.0
    w.emit("GIFT", b, a, place="HALL")
    assert a.memories.gratitude(w.tick, a, b.id) > 0.0
    # harming somebody I like counts against me too
    friend.mind.rel(a.id)["trust"] = 0.9
    before = friend.memories.grudge(w.tick, friend, b.id)
    w.tick += 1
    w.emit("HIT", b, a, witnesses=[friend], place="HALL")
    assert friend.memories.grudge(w.tick, friend, b.id) > before


def test_memory_is_capped_and_the_faintest_is_forgotten():
    w = World(2, seed=5)
    a, b = w.agents
    for i in range(schema.MEMORY_CAP * 2):
        w.tick = i + 1
        w.remember(a, "HIT", b.id, a.id, "HALL", 0.2 + 0.5 * (i / 200.0), "SUFFERED")
    assert len(a.memories) == schema.MEMORY_CAP
    ticks = [m.tick for m in a.memories]
    assert min(ticks) > 1, "the oldest, faintest memory should have been dropped first"


def test_the_same_episode_is_only_learned_once():
    w = World(3, seed=5)
    a, b, c = w.agents
    w.tick = 10
    first = w.remember(c, "HIT", a.id, b.id, "HALL", 0.6, "HEARD", from_id=b.id, at_tick=4)
    again = w.remember(c, "HIT", a.id, b.id, "HALL", 0.6, "HEARD", from_id=a.id, at_tick=4)
    assert first is not None and again is None
    assert len(c.memories) == 1


# ---------------------------------------------------------------------------
# 3. Gossip and reputation
# ---------------------------------------------------------------------------

def gossip_setup(seed=5, trust=0.8):
    w = World(3, seed=seed)
    teller, listener, subject = w.agents
    for x in w.agents:
        x.place = "TAVERN"
    subject.place = "MINE"                      # not in the room to defend himself
    w.tick = 30
    w.remember(teller, "STEAL", subject.id, teller.id, "MINE", 0.9, "SUFFERED", at_tick=20)
    listener.mind.rel(teller.id)["trust"] = trust
    w.sense()
    return w, teller, listener, subject


def test_gossip_hands_over_a_memory_and_shifts_the_listener():
    w, teller, listener, subject = gossip_setup()
    cand = [c for c in arbitrator.gather(teller, w)
            if c.skill == "GOSSIP" and c.target == listener.id][0]
    assert cand.detail["about"] == subject.id
    assert cand.hints["gossip_value"] > 0.0
    before = dict(listener.mind.rel(subject.id))
    skills.SKILL_BY_NAME["GOSSIP"].execute(teller, w, cand)

    heard = [m for m in listener.memories if m.source == "HEARD"]
    assert len(heard) == 1
    assert heard[0].kind == "STEAL" and heard[0].actor == subject.id
    assert heard[0].from_id == teller.id
    assert listener.mind.rel(subject.id)["hatred"] > before["hatred"]
    assert listener.mind.rel(subject.id)["trust"] < before["trust"]
    ev = [e for e in w.events if e["type"] == "GOSSIP"][0]
    assert ev["about"] == subject.id and ev["text"]


def test_what_the_listener_takes_away_scales_with_trust_in_the_teller():
    def landed(trust):
        w, teller, listener, subject = gossip_setup(trust=trust)
        cand = [c for c in arbitrator.gather(teller, w)
                if c.skill == "GOSSIP" and c.target == listener.id][0]
        skills.SKILL_BY_NAME["GOSSIP"].execute(teller, w, cand)
        return (listener.mind.rel(subject.id)["hatred"],
                [m for m in listener.memories if m.source == "HEARD"][0].intensity)
    believed = landed(0.9)
    doubted = landed(-0.6)
    assert believed[0] > doubted[0]
    assert believed[1] > doubted[1]


def test_a_story_only_moves_an_opinion_the_first_time():
    w, teller, listener, subject = gossip_setup()
    cand = [c for c in arbitrator.gather(teller, w)
            if c.skill == "GOSSIP" and c.target == listener.id][0]
    skills.SKILL_BY_NAME["GOSSIP"].execute(teller, w, cand)
    after_once = listener.mind.rel(subject.id)["hatred"]
    # the same episode again: no new candidate, and forcing it through moves nothing
    again = [c for c in arbitrator.gather(teller, w)
             if c.skill == "GOSSIP" and c.target == listener.id]
    assert not again, "gossip should have nothing new to tell this listener"
    skills.SKILL_BY_NAME["GOSSIP"].execute(teller, w, cand)
    assert abs(listener.mind.rel(subject.id)["hatred"] - after_once) < 1e-9


def test_reputation_is_what_everyone_believes_and_reaches_the_log(tmp_path):
    w = World(3, seed=5)
    a, b, c = w.agents
    b.mind.rel(a.id)["hatred"] = 0.6
    c.mind.rel(a.id)["hatred"] = 0.4
    rep = w.reputation()
    assert abs(rep[str(a.id)][2] - 0.5) < 1e-6
    assert rep[str(a.id)][3] == 2
    assert schema.PLAYER_ID in rep

    path = str(tmp_path / "rep.jsonl")
    run_sim(path, n_agents=4, ticks=40, seed=3)
    run = load(path)
    with_rep = [t for t in run["ticks"] if "rep" in t]
    assert with_rep, "reputation never reached the log"
    assert "reputation" in run["summary"]


def test_a_feud_spreads_to_friends_without_anything_saying_so(tmp_path):
    """Nobody tells B's friends to dislike A. They hear about it and come to on their own."""
    path = str(tmp_path / "gossip.jsonl")
    summary = run_sim(path, n_agents=6, ticks=2000, seed=1, scenario="gossip")
    assert summary["gossip"] > 5, "the gossip scenario produced almost no gossip"
    run = load(path)
    spread = 0
    for t in run["ticks"]:
        for e in t["events"]:
            if e["type"] != "GOSSIP":
                continue
            about = e.get("about")
            for d in e.get("deltas", ()):
                if str(d["f"]).startswith("rel:%s:" % about) and d["who"] == e["target"]:
                    spread += 1
    assert spread > 0, "no piece of gossip ever moved a listener's view of the third party"
    # and it reached dwarves who were not there: somebody holds a HEARD memory
    hearsay = [m for t in run["ticks"] for s in t.get("agents", {}).values()
               for m in s.get("mem", []) if m.get("source") == "HEARD"]
    assert hearsay, "nobody ended up holding a second-hand memory"


# ---------------------------------------------------------------------------
# 4. Obligations and bargaining
# ---------------------------------------------------------------------------

ORDER = {"text": "Bring me ore.", "intent": "COMMAND", "topic": "MINE", "addressed": "LISTENER",
         "aggression": 0.3, "valence": 0.0, "urgency": 0.7, "names": []}


def test_an_ask_makes_an_obligation_and_hear_still_works_without_one():
    w = World(2, seed=6)
    a, b = w.agents
    b.place = a.place
    plain = w.say(a, b, dict(ORDER))
    assert plain["type"] == "REQUEST"
    assert b.request["place"] == "MINE"
    assert not w.obligations, "a REQUEST with no ask must not create an obligation"

    ask = obligations.make_ask("BRING", item="ore", quantity=2, place="MINE", payment=3)
    ev = w.say(a, b, dict(ORDER, ask=ask))
    assert ev["type"] == "ASK" and ev["ask"]["action"] == "BRING"
    ob = w.obligations[-1]
    assert (ob.frm, ob.to, ob.status) == (a.id, b.id, "PENDING")
    assert set(schema.OBLIGATION_STATUS) == {
        "PENDING", "ACCEPTED", "REFUSED", "KEPT", "BROKEN", "EXPIRED"}
    assert ob.status in schema.OBLIGATION_STATUS
    assert ob.payment() == 3 and ob.deadline > w.tick
    # an ask nobody understands is ignored, not an error
    assert w.say(a, b, dict(ORDER, ask={"action": "DANCE"}))["type"] == "REQUEST"


def test_the_obligated_gets_accept_refuse_and_bargain_to_weigh():
    w = World(2, seed=6)
    a, b = w.agents
    b.place = a.place
    b.inv["ore"] = 4
    w.say(a, b, dict(ORDER, ask=obligations.make_ask("BRING", item="ore", place=a.place,
                                                     payment=2)))
    by_skill = candidates_by_skill(b, w)
    for skill in ("ACCEPT", "REFUSE", "BARGAIN"):
        assert skill in by_skill, skill
        cand = by_skill[skill][0]
        assert cand.target == a.id
        assert cand.hints["ask_cost"] > 0.0
    assert by_skill["ACCEPT"][0].hints["payment_offered"] > 0.0
    for term in ("trust_target", "respect_target", "fear_target", "ask_cost", "greed",
                 "payment_offered"):
        assert term in arbitrator.WEIGHTS["ACCEPT"] or term in arbitrator.WEIGHTS["REFUSE"] \
            or term in arbitrator.WEIGHTS["BARGAIN"], term


def test_accepting_moves_the_gold_and_keeping_it_raises_trust_both_ways():
    w = World(2, seed=6)
    a, b = w.agents
    b.place = a.place
    a.inv["gold"], b.inv["gold"], b.inv["ore"] = 10, 0, 3
    w.say(a, b, dict(ORDER, ask=obligations.make_ask("BRING", item="ore", place=a.place,
                                                     payment=4)))
    ob = w.obligations[-1]
    accept = [c for c in arbitrator.gather(b, w) if c.skill == "ACCEPT"][0]
    skills.SKILL_BY_NAME["ACCEPT"].execute(b, w, accept)
    assert ob.status == "ACCEPTED"
    assert (a.inv["gold"], b.inv["gold"]) == (6, 4), "the payment did not actually move"

    fulfil = [c for c in arbitrator.gather(b, w) if c.skill == "FULFIL"][0]
    assert fulfil.hints["obligation_pressure"] > 0.0
    before = (a.mind.rel(b.id)["trust"], b.mind.rel(a.id)["trust"])
    skills.SKILL_BY_NAME["FULFIL"].execute(b, w, fulfil)
    assert ob.status == "KEPT"
    assert a.inv["ore"] == 1 and b.inv["ore"] == 2
    assert a.mind.rel(b.id)["trust"] > before[0]
    assert b.mind.rel(a.id)["trust"] > before[1]
    assert any(m.kind == "PROMISE_KEPT" for m in a.memories)


def test_breaking_a_promise_costs_trust_and_leaves_a_memory_gossip_can_carry():
    w = World(3, seed=6)
    a, b, c = w.agents
    for x in w.agents:
        x.place = "HALL"
    w.say(a, b, dict(ORDER, ask=obligations.make_ask("GO_TO", place="GATE")))
    ob = w.obligations[-1]
    w.accept_obligation(ob, b)
    before = a.mind.rel(b.id)["trust"]
    w.tick = ob.deadline
    w.step()
    assert ob.status == "BROKEN"
    assert a.mind.rel(b.id)["trust"] < before
    assert any(m.kind == "BROKEN_PROMISE" and m.actor == b.id for m in a.memories)
    assert "BROKEN_PROMISE" in memory.HARM_KINDS      # so gossip will carry it


def test_a_bargain_names_a_price_and_hands_the_choice_back():
    w = World(2, seed=6)
    a, b = w.agents
    b.place = a.place
    a.inv["gold"] = 12
    b.mind.traits["greed"] = 0.9
    w.say(a, b, dict(ORDER, ask=obligations.make_ask("BRING", item="ore", place=a.place,
                                                     payment=1)))
    ob = w.obligations[-1]
    bargain = [c for c in arbitrator.gather(b, w) if c.skill == "BARGAIN"][0]
    skills.SKILL_BY_NAME["BARGAIN"].execute(b, w, bargain)
    assert ob.counter > 1 and ob.status == "PENDING"
    ev = [e for e in w.events if e["type"] == "BARGAIN"][0]
    assert str(ob.counter) in ev["text"]
    # now it is the asker who has an answer to give
    by_skill = candidates_by_skill(a, w)
    assert any(c.target == b.id for c in by_skill["ACCEPT"])
    assert any(c.target == b.id for c in by_skill["REFUSE"])
    assert "BARGAIN" not in by_skill, "one round of haggling is enough"


# ---------------------------------------------------------------------------
# 5. Persistent goals
# ---------------------------------------------------------------------------

def test_hatred_plus_a_remembered_harm_adopts_a_goal_of_revenge():
    w = World(3, seed=7)
    a, b, _ = w.agents
    for x in w.agents:
        x.place = "HALL"
    assert not a.goals
    a.mind.rel(b.id)["hatred"] = 0.75
    w.remember(a, "HIT", b.id, a.id, "HALL", 1.0, "SUFFERED")
    adopted, _ = goals.review(a, w)
    kinds = {(g.kind, g.target) for g in adopted}
    assert ("AVENGE", b.id) in kinds
    g = [x for x in a.goals if x.kind == "AVENGE"][0]
    assert 0.0 < g.strength <= 1.0

    # the goal tilts the candidates that serve it and tilts against the ones that do not
    attack = skills.Candidate("ATTACK", target=b.id, place="HALL")
    apology = skills.Candidate("APOLOGIZE", target=b.id, place="HALL")
    other = skills.Candidate("ATTACK", target=w.agents[2].id, place="HALL")
    assert goals.bias(a, attack) > 0.0
    assert goals.bias(a, apology) < 0.0
    assert goals.bias(a, other) == 0.0
    assert "goal_bias" in arbitrator.WEIGHTS["ATTACK"]


def test_goals_decay_and_are_dropped_when_they_are_satisfied():
    w = World(2, seed=7)
    a, b = w.agents
    goals.adopt(a, w, "BEFRIEND", b.id, 0.9)      # nothing in the state re-adopts this
    strengths = []
    for _ in range(300):
        w.tick += goals.REVIEW_EVERY
        goals.review(a, w)
        if not a.goals:
            break
        strengths.append(a.goals[0].strength)
    assert not a.goals, "a goal with nothing feeding it should fade away"
    assert strengths == sorted(strengths, reverse=True), "it should fade, not flicker"

    # and a goal that got what it wanted goes at once, rather than fading
    goals.adopt(a, w, "GET_RICH", None, 0.9)
    a.inv["gold"] = 30
    _, dropped = goals.review(a, w)
    assert any(g.kind == "GET_RICH" for g in dropped)
    a.mind.rel(b.id)["hatred"] = 0.02
    goals.adopt(a, w, "AVENGE", b.id, 0.9)
    _, dropped = goals.review(a, w)
    assert any(g.kind == "AVENGE" for g in dropped), "nothing left to avenge"


def test_a_dwarf_holds_only_so_many_wants():
    w = World(8, seed=7)
    a = w.agents[0]
    for i, other in enumerate(w.agents[1:7]):
        goals.adopt(a, w, "BEFRIEND", other.id, 0.3 + 0.05 * i)
    goals.review(a, w)
    assert len(a.goals) <= goals.GOAL_CAP


def test_goals_reach_the_snapshot_and_the_narration(tmp_path):
    path = str(tmp_path / "goals.jsonl")
    summary = run_sim(path, n_agents=6, ticks=600, seed=1, scenario="feud")
    assert summary["goals_adopted"] > 0
    run = load(path)
    adopted = [e for t in run["ticks"] for e in t["events"] if e["type"] == "GOAL_ADOPTED"]
    assert adopted and "goal" in adopted[0] and "strength" in adopted[0]
    snapped = [s.get("goals") for t in run["ticks"] for s in t["agents"].values()
               if s.get("goals")]
    assert snapped, "goals never reached a snapshot"
    assert {"kind", "strength", "since"} <= set(snapped[0][0])
    wants = [s["text"] for s in summary["story"] if " now wants to " in s["text"]]
    assert wants, "no goal was ever narrated"


# ---------------------------------------------------------------------------
# Authority: opt-in, and off in every default scenario
# ---------------------------------------------------------------------------

def test_there_is_no_chief_unless_one_is_asked_for():
    for name in ("default", "feud", "gossip", "player", "theft", "raid"):
        assert World(6, seed=2, scenario=name).chief_id is None, name
    assert World(6, seed=2, scenario="feud-chief").chief_id is not None
    assert World(6, seed=2, chief=True).chief_id is not None
    assert World(6, seed=2, scenario="feud-chief", chief=False).chief_id is None

    w = World(6, seed=2, chief=True)
    chief = w.chief()
    assert all(a.mind.rel(chief.id)["respect"] > 0.0 for a in w.living() if a.id != chief.id)
    assert goals.review(chief, w) and any(g.kind == "KEEP_PEACE" for g in chief.goals)
    assert not any(g.kind == "KEEP_PEACE" for a in w.living() if a.id != chief.id
                   for g in a.goals)


def test_with_no_chief_there_is_no_punishment_and_no_deterrence():
    w = World(4, seed=2, chief=False)
    a, b = w.agents[0], w.agents[1]
    for x in w.agents:
        x.place = "HALL"
    w.emit("HIT", a, b, place="HALL")
    w.sense()
    for agent in w.living():
        assert not skills.SKILL_BY_NAME["PUNISH"].propose(agent, w)
        ctx = arbitrator.TermContext(agent, w)
        assert ctx.flat["chief_present"] == 0.0
        assert ctx.flat["expected_punishment"] == 0.0
    # and a complaint has only friends to go to
    complaints = [c for c in arbitrator.gather(b, w) if c.skill == "COMPLAIN_TO"]
    assert all(not c.detail.get("chief") for c in complaints)


def test_a_chief_punishes_what_it_sees_and_the_room_thinks_less_of_the_culprit():
    w = World(4, seed=2, chief=2)
    chief = w.chief()
    culprit, victim, bystander = w.agents[0], w.agents[1], w.agents[3]
    for x in w.agents:
        x.place = "HALL"
    culprit.inv["gold"] = 6
    w.emit("HIT", culprit, victim, place="HALL")
    w.sense()
    punish = [c for c in arbitrator.gather(chief, w)
              if c.skill == "PUNISH" and c.target == culprit.id]
    assert punish, "the chief watched a beating and had nothing to propose"
    assert punish[0].hints["punish_pressure"] > 0.0
    before = bystander.mind.rel(culprit.id)["respect"]
    skills.SKILL_BY_NAME["PUNISH"].execute(chief, w, punish[0])
    ev = [e for e in w.events if e["type"] == "PUNISH"][0]
    assert ev["mode"] == "fine" and ev["fine"] > 0
    assert culprit.inv["gold"] < 6 and victim.inv["gold"] > 0
    assert bystander.mind.rel(culprit.id)["respect"] < before
    assert culprit.mind.rel(chief.id)["hatred"] > 0.0
    assert culprit.mind.rel(chief.id)["respect"] > 0.0


def test_violence_in_front_of_the_chief_carries_an_expected_punishment():
    w = World(4, seed=2, chief=3)
    a, b = w.agents[0], w.agents[1]
    for x in w.agents:
        x.place = "HALL"
    together = arbitrator.TermContext(a, w).flat["expected_punishment"]
    w.chief().place = "FARM"
    apart = arbitrator.TermContext(a, w).flat["expected_punishment"]
    # word gets back, so it is not nothing when he is elsewhere -- just much less
    assert together > apart > 0.0
    none = World(4, seed=2, chief=False)
    assert arbitrator.TermContext(none.agents[0], none).flat["expected_punishment"] == 0.0
    assert arbitrator.WEIGHTS["ATTACK"]["expected_punishment"] < 0
    assert arbitrator.WEIGHTS["STEAL"]["expected_punishment"] < 0


CHIEF_SEEDS = (1, 2, 3, 4)


def test_a_chief_means_fewer_fights_and_more_going_through_him(tmp_path):
    """Four seeds averaged, generous thresholds: authority has to show up in the numbers.

    One run is noisy -- an early killing changes everything downstream -- so this asks only for
    the direction, never a size.
    """
    def totals(scenario):
        got = {"hits": 0, "complaints": 0, "demands": 0, "apologies": 0, "punishments": 0}
        for seed in CHIEF_SEEDS:
            s = run_sim(str(tmp_path / ("%s%d.jsonl" % (scenario, seed))),
                        n_agents=6, ticks=2000, seed=seed, scenario=scenario)
            got["hits"] += s["fights"]["hits"]
            got["complaints"] += s["reactions"]["COMPLAIN"]
            got["demands"] += s["reactions"]["DEMAND"]
            got["apologies"] += len([x for x in s["story"] if "apologised" in x["text"]])
            got["punishments"] += len(s["punishments"])
        return dict((k, v / float(len(CHIEF_SEEDS))) for k, v in got.items())

    loose = totals("feud")
    ruled = totals("feud-chief")
    assert ruled["hits"] < loose["hits"] * 0.90, (loose, ruled)
    assert loose["punishments"] == 0 and ruled["punishments"] > 0, (loose, ruled)
    channels = ("complaints", "demands", "apologies", "punishments")
    assert sum(ruled[k] for k in channels) > sum(loose[k] for k in channels), (loose, ruled)


# ---------------------------------------------------------------------------
# The player
# ---------------------------------------------------------------------------

def test_the_player_is_a_speaker_with_a_neutral_row_in_every_dwarf():
    w = World(5, seed=4, scenario="player")
    assert w.agent(schema.PLAYER_ID) is w.player
    assert w.player not in w.agents and w.player.external
    for a in w.agents:
        assert a.mind.rels[schema.PLAYER_ID] == {"trust": 0.0, "respect": 0.0, "hatred": 0.0}
    roster = {r["id"]: r for r in w.roster()}
    assert roster[schema.PLAYER_ID]["external"] is True
    # the player never decides and never ages
    before = dict(w.player.mind.emotions)
    for _ in range(20):
        w.step()
    assert w.player.mind.emotions == before


def test_the_player_speaks_through_the_same_door_and_is_heard_by_the_room():
    w = World(3, seed=4, scenario="player")
    listener, bystander = w.agents[1], w.agents[2]
    bystander.place = listener.place
    ev = w.say("player", listener, {
        "text": "You're a disgrace.", "intent": "INSULT", "topic": "CLAN",
        "addressed": "LISTENER", "aggression": 0.8, "valence": -0.8, "urgency": 0.2,
        "names": []})
    assert ev["type"] == "INSULT" and ev["actor"] == schema.PLAYER_ID
    assert listener.mind.rel(schema.PLAYER_ID)["hatred"] > 0.0
    assert bystander.mind.rel(schema.PLAYER_ID)["trust"] < 0.0
    assert any(m.actor == schema.PLAYER_ID for m in listener.memories)


def test_a_strangers_order_is_refused_and_the_same_order_is_obeyed_once_earned(tmp_path):
    """The only difference between the two runs is what the player did first."""
    def outcomes(earn):
        tally = {"yes": 0, "no": 0}
        for seed in (1, 2, 3, 4):
            w = World(6, seed=seed, scenario="player")
            for _ in range(40):
                w.step()
            for dwarf in w.living():
                if earn:
                    w.player_help(dwarf, magnitude=1.4)
                    w.player_give(dwarf, 4)
                    w.fulfil_obligation(w.player_promise(
                        dwarf, obligations.make_ask("GIVE", item="gold", quantity=2)))
                w.say("player", dwarf, dict(ORDER, ask=obligations.make_ask(
                    "BRING", item="ore", quantity=1, place="MINE")))
                ob = w.obligations[-1]
                for _ in range(obligations.RESPONSE_TTL + 5):
                    w.step()
                    if ob.status != "PENDING":
                        break
                if ob.status in ("ACCEPTED", "KEPT"):
                    tally["yes"] += 1
                else:
                    tally["no"] += 1
        return tally

    stranger = outcomes(False)
    friend = outcomes(True)
    assert stranger["no"] > stranger["yes"] * 3, stranger
    assert friend["yes"] > friend["no"], friend


def test_what_the_player_did_shows_up_as_gratitude_and_reputation():
    w = World(4, seed=4, scenario="player")
    dwarf = w.agents[0]
    assert w.reputation()[schema.PLAYER_ID][0] == 0.0
    w.player_give(dwarf, 5)
    assert dwarf.inv["gold"] >= 5
    assert w.player.inv["gold"] == 15
    assert dwarf.memories.gratitude(w.tick, dwarf, schema.PLAYER_ID) > 0.0
    assert dwarf.mind.rel(schema.PLAYER_ID)["trust"] > 0.0
    w._rep_tick = -1
    assert w.reputation()[schema.PLAYER_ID][0] > 0.0


# ---------------------------------------------------------------------------
# The feud scenario, with the new reactions in it
# ---------------------------------------------------------------------------

def test_the_feud_produces_more_than_one_kind_of_reaction(tmp_path):
    path = str(tmp_path / "feud3.jsonl")
    summary = run_sim(path, n_agents=6, ticks=2000, seed=1, scenario="feud")
    answered = summary["provocations"]["answered"]
    kinds = [k for k, n in answered.items() if n > 0]
    assert len(kinds) >= 3, answered
    assert sum(answered.values()) > answered["HIT"], \
        "every provocation was answered with a fist: %r" % (answered,)
    assert summary["reactions"]["HIT"] > 0
    # nothing in the codebase orders the reactions or counts insults
    source = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "dwarfsim")
    for fname in os.listdir(source):
        if fname.endswith(".py"):
            text = open(os.path.join(source, fname), encoding="utf-8").read()
            assert "insult_count" not in text and "attack_after" not in text


# ---------------------------------------------------------------------------
# Schema, log and viewer for everything above
# ---------------------------------------------------------------------------

def test_the_schema_carries_the_new_skills_terms_and_blocks():
    for skill in ("IGNORE", "RETORT", "DEMAND_APOLOGY", "REFUSE_APOLOGY", "COMPLAIN_TO",
                  "AVOID", "GOSSIP", "ACCEPT", "REFUSE", "BARGAIN", "FULFIL", "PUNISH"):
        assert skill in schema.SKILL_NAMES
        assert skill in arbitrator.WEIGHTS
        assert skill in skills.SKILL_BY_NAME
    for term in ("pride", "forgiveness", "grudge_target", "gratitude_target", "reputation_target",
                 "provoked_by_target", "publicity", "humiliation", "chief_present",
                 "expected_punishment", "fear_target", "goal_bias", "obligation_pressure",
                 "ask_cost", "payment_offered", "gossip_value", "punish_pressure"):
        assert term in schema.TERM_NAMES
    assert set(schema.REACTION_SKILLS) < set(schema.SKILL_NAMES)
    assert schema.OBS_MIND == 0 and schema.MIND_TRAITS == 8
    assert len(mind.EMOTIONS) + len(mind.NEEDS) == schema.MIND_TRAITS
    assert schema.MIND_HEALTH == schema.MIND_TRAITS + len(mind.TRAITS)
    assert schema.MIND_SIZE == schema.MIND_FOCUS + schema.FOCUS_SLOTS * schema.FOCUS_STRIDE
    assert schema.CAND_SIZE == schema.N_SKILLS + schema.N_TERMS
    assert schema.SCHEMA_ID == "dwarfsim-v1"
    assert len(mind.TRAITS) == 6 and "pride" in mind.TRAITS and "forgiveness" in mind.TRAITS


def test_the_observation_carries_goals_obligations_and_memory():
    w = World(4, seed=9)
    a, b = w.agents[0], w.agents[1]
    b.place = a.place
    goals.adopt(a, w, "AVENGE", b.id, 0.8)
    w.remember(a, "HIT", b.id, a.id, a.place, 1.0, "SUFFERED")
    w.say(b, a, dict(ORDER, ask=obligations.make_ask("GO_TO", place="GATE")))
    v = arbitrator.observation_vector(a, w)
    assert len(v) == schema.OBS_SIZE
    assert v[schema.OBS_GOALS + schema.GOAL_INDEX["AVENGE"]] > 0.0
    assert v[schema.OBS_OBLIGATIONS] > 0.0
    assert v[schema.OBS_MEMORY] > 0.0 and v[schema.OBS_MEMORY + 1] > 0.0
    assert v[schema.OBS_IS_CHIEF] == 0.0 and v[schema.OBS_CHIEF_HERE] == 0.0
    # the focus slots now carry the memory-derived pair as well as the relationship triple
    off = schema.MIND_FOCUS
    slots = [v[off + i * schema.FOCUS_STRIDE: off + (i + 1) * schema.FOCUS_STRIDE]
             for i in range(schema.FOCUS_SLOTS)]
    assert any(s[0] == 1.0 and s[4] > 0.0 for s in slots), "no focus slot carries a grudge"

    chief_world = World(4, seed=9, chief=True)
    chief = chief_world.chief()
    cv = arbitrator.observation_vector(chief, chief_world)
    assert cv[schema.OBS_IS_CHIEF] == 1.0


def test_the_mind_blocks_ride_on_the_slow_snapshot(tmp_path):
    path = str(tmp_path / "slow.jsonl")
    ticks = 60
    run_sim(path, n_agents=6, ticks=ticks, seed=1, scenario="feud")
    run = load(path)
    for t in run["ticks"]:
        slow = (t["tick"] % log_mod.REL_EVERY == 0) or t["tick"] in (1, ticks)
        for snap in t["agents"].values():
            assert ("mem" in snap) == slow, t["tick"]
            assert ("goals" in snap) == slow
            assert ("obl" in snap) == slow
            assert ("rels" in snap) == slow
        assert ("rep" in t) == slow


def test_a_full_run_stays_well_under_the_size_budget(tmp_path):
    path = str(tmp_path / "big.jsonl")
    run_sim(path, n_agents=6, ticks=2000, seed=4, scenario="feud-chief")
    size = os.path.getsize(path)
    assert size < 40e6, "%.1f MB is over budget" % (size / 1e6)


def test_the_viewer_shows_the_mind_panel_and_the_reputation_toggle(tmp_path):
    from dwarfsim.viewer import write_html
    log_path = str(tmp_path / "v.jsonl")
    html_path = str(tmp_path / "v.html")
    run_sim(log_path, n_agents=6, ticks=80, seed=2, scenario="feud")
    write_html(log_path, html_path)
    html = open(html_path, encoding="utf-8").read()
    for want in ('id="v-mind"', 'id="mind-pick"', "drawMind", "REMEMBERS", "WANTS",
                 "believed by others", "reputation", "GOAL_WORDS", "askText"):
        assert want in html, want
    for forbidden in ("http://", "https://", "fetch(", "XMLHttpRequest", "import("):
        assert forbidden not in html, forbidden


def test_the_new_events_all_have_a_row_in_the_table():
    for kind in ("IGNORE", "RETORT", "DEMAND", "REFUSE_APOLOGY", "COMPLAIN", "GOSSIP", "AVOID",
                 "ACCEPT", "REFUSE", "BARGAIN", "PROMISE_KEPT", "BROKEN_PROMISE", "PUNISH"):
        assert kind in mind.EVENT_TABLE, kind
        row = mind.EVENT_TABLE[kind]
        assert "target" in row and "witness" in row
    # and the ones memory cares about are all events the table knows how to apply
    for kind in memory.HARM_KINDS + memory.HELP_KINDS:
        assert kind in mind.EVENT_TABLE, kind
