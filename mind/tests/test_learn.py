"""Tests for the learned arbitrator: collect, imitate, export, and run the sim on it.

    python -m pytest -q tests/test_learn.py

Everything here is tiny on purpose -- a few hundred ticks and a handful of epochs. The claims
being checked are structural (shapes line up, the exported numpy matches torch, the sim runs and
the viewer renders it) plus the one claim that is not: a model trained for a few seconds beats
picking a candidate at random by a wide margin. The real numbers live in
``runs/learn/imitator.json``.
"""

import json
import os
import sys

import numpy as np
import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from dwarfsim import arbitrator, run_sim, schema  # noqa: E402
from dwarfsim.learn import LearnedScorer  # noqa: E402
from dwarfsim.learn import collect as collect_mod  # noqa: E402
from dwarfsim.learn import scorer as scorer_mod  # noqa: E402
from dwarfsim.world import World  # noqa: E402

TINY = dict(seeds=(1, 2), ticks=300, scenarios=("default", "feud"), stride=2)


@pytest.fixture(scope="module")
def tiny(tmp_path_factory):
    """One small collection, shared by every test that needs data."""
    path = str(tmp_path_factory.mktemp("learn") / "teacher.npz")
    meta = collect_mod.collect(path, verbose=False, **TINY)
    return path, meta, collect_mod.load(path)


@pytest.fixture(scope="module")
def trained(tiny, tmp_path_factory):
    """One small model, exported. A few epochs: seconds, not minutes."""
    from dwarfsim.learn import imitate
    path, _, _ = tiny
    out = str(tmp_path_factory.mktemp("model") / "imitator")
    meta = imitate.run(path, out, holdout=[2], epochs=6, batch=256, max_seconds=60,
                       verbose=False)
    return out, meta


# ---------------------------------------------------------------------------
# Collecting
# ---------------------------------------------------------------------------

def test_collection_has_consistent_shapes(tiny):
    _, meta, d = tiny
    assert len(d) == meta["decisions"] > 0
    assert d.n_candidates == meta["candidates"]
    assert d.obs.shape == (len(d), schema.OBS_SIZE)
    assert d.cand_terms.shape == (d.n_candidates, schema.N_TERMS)
    assert d.offsets.shape == (len(d) + 1,)
    assert d.offsets[0] == 0 and d.offsets[-1] == d.n_candidates
    assert np.all(np.diff(d.offsets) > 0)          # every decision had candidates
    assert d.teacher.shape == (d.n_candidates,)
    assert d.chosen.shape == d.scenario.shape == d.seed.shape == (len(d),)
    assert set(np.unique(d.seed).tolist()) == set(TINY["seeds"])
    assert d.scenarios == list(TINY["scenarios"])
    # The chosen candidate is an index inside its own decision, never past the end.
    counts = np.diff(d.offsets)
    assert np.all(d.chosen >= 0) and np.all(d.chosen < counts)
    assert np.all(d.cand_skill >= 0) and np.all(d.cand_skill < schema.N_SKILLS)


def test_features_rebuild_to_the_candidate_layout(tiny):
    _, _, d = tiny
    f = d.features(0)
    assert f.shape == (int(d.offsets[1]), schema.CAND_SIZE)
    one_hot = f[:, schema.CAND_SKILL:schema.CAND_SKILL + schema.N_SKILLS]
    assert np.all(one_hot.sum(axis=1) == 1.0)
    assert np.array_equal(one_hot.argmax(axis=1), d.cand_skill[:len(f)])


def test_stored_teacher_scores_are_the_weight_table(tiny):
    """The saved score must be what ``arbitrator.score`` gives for the saved features."""
    _, _, d = tiny
    rng = np.random.default_rng(0)
    for i in rng.choice(len(d), size=40, replace=False):
        obs = d.observation(int(i))
        feats = d.features(int(i))
        lo, _ = d.slice(int(i))
        for j, f in enumerate(feats):
            # The features are stored as float16, so this is exact only to that resolution.
            assert abs(arbitrator.score(obs, f) - d.teacher[lo + j]) < 0.05


def test_collecting_does_not_change_the_run():
    """Building feature vectors draws the same randomness the weight table does."""
    def digest(collecting):
        w = World(6, seed=4, scenario="gossip")
        sink = collect_mod.Sink(["gossip"]) if collecting else None
        out = []
        for t in range(220):
            if collecting:
                w.collector = [] if t % 2 == 0 else None
            step = w.step()
            out.append([d["chosen"] for d in step["decisions"]])
            if collecting and w.collector:
                sink.take(w.collector, "gossip", 4)
        return out, sink

    plain, _ = digest(False)
    collected, sink = digest(True)
    assert plain == collected
    assert sink.decisions > 100


def test_the_hard_gap_records_the_close_decisions_on_the_skipped_ticks():
    """Every tick is offered; the ones the stride would have skipped keep only the close calls."""
    def one(hard_gap):
        sink = collect_mod.Sink(["bully"])
        collect_mod.run_one(sink, "bully", 3, 200, stride=2, hard_gap=hard_gap)
        return sink

    plain, hard = one(None), one(0.5)
    assert hard.decisions > plain.decisions          # the skipped ticks brought something
    assert hard.decisions < 2 * plain.decisions      # but not everything: only the close ones

    # Every decision the gap rule let through really is close, and the rule itself is the
    # difference between the best two scores and nothing else.
    assert collect_mod.is_hard([1.0, 0.7, 0.2], 0.5) is True
    assert collect_mod.is_hard([1.0, 0.2, 0.7], 0.5) is True      # order does not matter
    assert collect_mod.is_hard([1.0, 0.4, 0.39], 0.5) is False    # the *second* is what counts
    assert collect_mod.is_hard([1.0], 0.5) is True                # nothing to be torn between


def test_targeted_scenarios_get_the_extra_seeds_and_nothing_else_does(tmp_path):
    meta = collect_mod.collect(str(tmp_path / "t.npz"), seeds=(1,), ticks=60,
                               scenarios=("default", "bully"), targeted_seeds=(9,),
                               hard_gap=0.5, verbose=False, max_decisions=4000)
    seeds_of = {}
    for row in meta["runs"]:
        seeds_of.setdefault(row["scenario"], []).append(row["seed"])
    assert seeds_of["default"] == [1]                # an ordinary scenario is untouched
    assert seeds_of["bully"] == [1, 9]               # a targeted one gets the extra seed
    assert meta["hard_gap"] == 0.5
    assert meta["targeted_scenarios"] == ["bully"]


def test_parse_seeds():
    assert collect_mod.parse_seeds("1-8") == list(range(1, 9))
    assert collect_mod.parse_seeds("1,3,5") == [1, 3, 5]
    assert collect_mod.parse_seeds("1-3,9") == [1, 2, 3, 9]


# ---------------------------------------------------------------------------
# Training
# ---------------------------------------------------------------------------

def test_a_few_seconds_of_training_beats_chance(trained):
    _, meta = trained
    held = meta["metrics"]["held_out"]
    assert held["decisions"] > 0
    # Chance is 1 / the number of candidates, about 6%. Anything that has learned the shape of
    # the table at all is far past it; this is the floor, not the target.
    assert held["top1"] > 10 * held["chance"]
    assert held["top1"] > 0.4
    assert held["hard"]["top1"] > held["hard"]["chance"]
    for name in TINY["scenarios"]:
        assert meta["metrics"]["held_out"]["per_scenario"][name]["top1"] > 0.3


def test_per_skill_recall_is_reported_and_adds_up(trained, tiny):
    _, meta = trained
    per_skill = meta["metrics"]["held_out"]["per_skill"]
    assert per_skill, "nothing was reported per skill"
    assert set(per_skill) <= set(schema.SKILL_NAMES)
    total = sum(row["decisions"] for row in per_skill.values())
    assert total == meta["metrics"]["held_out"]["decisions"]
    assert all(0.0 <= row["recall"] <= 1.0 for row in per_skill.values())
    # The decision-weighted mean of the recalls is agreement on the *skill*, which is a weaker
    # thing than agreement on the candidate: choosing a different dwarf to gossip to is a miss
    # for top-1 and a hit for GOSSIP's recall. So it is never below top-1, and not far above it.
    by_skill = sum(row["decisions"] * row["recall"] for row in per_skill.values()) / total
    top1 = meta["metrics"]["held_out"]["top1"]
    assert top1 <= by_skill + 1e-9
    assert by_skill < top1 + 0.25


def test_target_skills_are_the_skill_of_the_teachers_best_candidate(tiny):
    from dwarfsim.learn.imitate import target_skills
    _, _, d = tiny
    index = np.arange(0, len(d), max(1, len(d) // 200))
    got = target_skills(d, index)
    for at, i in enumerate(index):
        lo, hi = d.slice(int(i))
        assert got[at] == d.cand_skill[lo + int(np.argmax(d.teacher[lo:hi]))]


def test_balanced_weights_lift_the_rare_skills_and_average_to_one():
    from dwarfsim.learn.imitate import skill_weights
    common, rare = 0, 5
    classes = np.array([common] * 900 + [rare] * 100)
    w = skill_weights(classes, power=0.5)
    assert w[rare] > w[common] > 0.0
    assert w[rare] / w[common] == pytest.approx(3.0, rel=0.01)      # sqrt(900/100)
    assert w[[i for i in range(len(w)) if i not in (common, rare)]].tolist() == [0.0] * (len(w) - 2)
    assert w[[common, rare]].mean() == pytest.approx(1.0)

    # power 0 is the plain unweighted mean, power 1 the textbook balanced one.
    assert skill_weights(classes, power=0.0)[[common, rare]].tolist() == [1.0, 1.0]
    full = skill_weights(classes, power=1.0)
    assert full[rare] / full[common] == pytest.approx(9.0, rel=0.01)
    assert skill_weights(np.array([], dtype=np.int64)).sum() == 0.0


def test_the_weighted_loss_is_the_weighted_mean_of_the_unweighted_one():
    import torch

    from dwarfsim.learn.imitate import listwise_loss
    torch.manual_seed(0)
    pred = torch.randn(4, 3)
    teacher = torch.randn(4, 3)
    mask = torch.ones(4, 3, dtype=torch.bool)
    target = torch.tensor([0, 1, 2, 0])
    args = (teacher, mask, target, 0.25, 0.0)

    flat, _, _ = listwise_loss(pred, *args, weight=torch.ones(4))
    plain, _, _ = listwise_loss(pred, *args)
    assert float(flat) == pytest.approx(float(plain), abs=1e-5)

    # One decision carrying all the weight is that decision's own loss, and nothing else's.
    only = torch.tensor([0.0, 0.0, 1.0, 0.0])
    one, _, _ = listwise_loss(pred, *args, weight=only)
    alone, _, _ = listwise_loss(pred[2:3], teacher[2:3], mask[2:3], target[2:3], 0.25, 0.0)
    assert float(one) == pytest.approx(float(alone), abs=1e-5)

    # And the MSE term is deliberately left out of the weighting.
    _, _, mse_w = listwise_loss(pred, teacher, mask, target, 0.25, 0.2, weight=only)
    _, _, mse_p = listwise_loss(pred, teacher, mask, target, 0.25, 0.2)
    assert float(mse_w) == pytest.approx(float(mse_p), abs=1e-6)


def test_balancing_moves_the_model_toward_the_rare_skills(tiny):
    """Two models, same data and seed, one balanced: the balanced one picks rare skills more."""
    from dwarfsim.learn.imitate import agreement, split, train
    _, _, d = tiny
    train_index, test_index = split(d, [2])
    rare = {"IGNORE", "COMPLAIN_TO", "APOLOGIZE", "AVOID", "DEMAND_APOLOGY", "EAT"}

    def chosen_rare(balance):
        model, _, _ = train(d, train_index, epochs=3, batch=256, seed=0,
                            balance_skills=balance, max_seconds=60, verbose=False)
        model.eval()
        m = agreement(model, d, test_index)
        return sum(row["chosen"] for name, row in m["per_skill"].items() if name in rare), m

    plain, m_plain = chosen_rare(None)
    balanced, m_bal = chosen_rare(0.5)
    assert balanced > plain, "balancing did not move anything toward the rare skills"
    assert m_bal["top1"] > 0.3 and m_plain["top1"] > 0.3


def test_held_out_seeds_are_held_out_entirely(tiny):
    from dwarfsim.learn.imitate import split
    _, _, d = tiny
    train_index, test_index = split(d, [2])
    assert len(train_index) and len(test_index)
    assert set(np.unique(d.seed[train_index]).tolist()) == {1}
    assert set(np.unique(d.seed[test_index]).tolist()) == {2}
    assert len(train_index) + len(test_index) == len(d)


def test_the_export_is_the_documented_pair(trained):
    out, meta = trained
    npz_path, json_path = scorer_mod.resolve(out)
    assert os.path.exists(npz_path) and os.path.exists(json_path)
    with np.load(npz_path) as fh:
        assert sorted(fh.files) == sorted(n for n, _ in scorer_mod.ARRAY_ORDER)
        assert fh["fc1.weight"].shape == (64, schema.OBS_SIZE + schema.CAND_SIZE)
        assert fh["fc2.weight"].shape == (64, 64)
        assert fh["out.weight"].shape == (1, 64)
        assert all(fh[n].dtype == np.float32 for n, _ in scorer_mod.ARRAY_ORDER)
    with open(json_path, encoding="utf-8") as fh:
        blob = json.load(fh)
    assert blob["dwarfsim_schema_id"] == schema.SCHEMA_ID
    assert blob["input"]["size"] == schema.OBS_SIZE + schema.CAND_SIZE
    assert blob["metrics"]["held_out"]["per_scenario"]
    assert blob["training"]["holdout_seeds"] == [2]
    assert json.dumps(blob)   # the whole thing is plain JSON, no numpy scalars
    assert meta["dims"]["parameters"] < 20000


def test_torch_and_numpy_agree(trained, tiny):
    """The exported numpy scorer must be the trained torch model to 1e-5."""
    import torch

    from dwarfsim.learn.model import Scorer

    out, _ = trained
    _, _, d = tiny
    loaded = LearnedScorer.load(out)
    with np.load(out + ".npz") as fh:
        arrays = {k: fh[k] for k in fh.files}
    model = Scorer()
    model.load_state_dict({k: torch.from_numpy(v.copy()) for k, v in arrays.items()})
    model.eval()

    worst = 0.0
    with torch.no_grad():
        for i in range(0, min(len(d), 400), 7):
            obs = d.observation(i)
            feats = d.features(i)
            x = torch.from_numpy(np.concatenate(
                [np.repeat(obs[None, :], len(feats), axis=0), feats], axis=1))
            a = model.score_flat(x).numpy()
            b = loaded.score_all(obs, feats)
            assert b.shape == a.shape
            worst = max(worst, float(np.abs(a - b).max()))
            # and the one-candidate form is the same number as the batched one
            assert abs(loaded.score(obs, feats[0]) - float(b[0])) < 1e-6
    assert worst < 1e-5, worst


def test_the_scorer_loads_from_a_prefix_or_a_directory(trained):
    out, _ = trained
    prefix = LearnedScorer.load(out)
    assert LearnedScorer.load(out + ".npz").score_all(
        np.zeros(schema.OBS_SIZE), np.zeros((1, schema.CAND_SIZE))) == pytest.approx(
        prefix.score_all(np.zeros(schema.OBS_SIZE), np.zeros((1, schema.CAND_SIZE))))
    assert LearnedScorer.load(os.path.dirname(out)) is not None


def test_a_stale_schema_is_refused(trained, tmp_path):
    out, _ = trained
    with open(out + ".json", encoding="utf-8") as fh:
        blob = json.load(fh)
    blob["dwarfsim_schema_id"] = "dwarfsim-v0"
    stale = str(tmp_path / "stale")
    with open(stale + ".json", "w", encoding="utf-8") as fh:
        json.dump(blob, fh)
    with open(out + ".npz", "rb") as src, open(stale + ".npz", "wb") as dst:
        dst.write(src.read())
    with pytest.raises(ValueError):
        LearnedScorer.load(stale)


# ---------------------------------------------------------------------------
# Running the sim on it
# ---------------------------------------------------------------------------

def test_the_sim_runs_end_to_end_with_a_learned_scorer(trained, tmp_path):
    from dwarfsim.viewer import write_html
    out, _ = trained
    log_path = str(tmp_path / "learned.jsonl")
    html_path = str(tmp_path / "learned.html")
    summary = run_sim(log_path, n_agents=6, ticks=120, seed=9, scenario="feud",
                      scorer=LearnedScorer.load(out))
    assert summary["ticks"] > 0
    assert os.path.getsize(log_path) > 0

    # Every decision's "why" is the learned score, and the winner is in the list.
    seen = 0
    with open(log_path, encoding="utf-8") as fh:
        for line in fh:
            row = json.loads(line)
            for dec in row.get("decisions", []):
                assert dec["top"], dec
                assert any(c.get("won") for c in dec["top"])
                for c in dec["top"]:
                    assert list(c["terms"]) == ["learned"]
                    assert c["terms"]["learned"] == pytest.approx(c["score"])
                seen += 1
    assert seen > 100

    write_html(log_path, html_path)
    html = open(html_path, encoding="utf-8").read()
    assert '<script id="run" type="application/json">' in html
    assert "learned" in html
    for forbidden in ("http://", "https://", "fetch(", "XMLHttpRequest", "import("):
        assert forbidden not in html, forbidden


def test_a_learned_run_is_still_reproducible(trained, tmp_path):
    out, _ = trained
    scorer = LearnedScorer.load(out)
    one = run_sim(str(tmp_path / "a.jsonl"), ticks=90, seed=3, scenario="feud", scorer=scorer)
    two = run_sim(str(tmp_path / "b.jsonl"), ticks=90, seed=3, scenario="feud",
                  scorer=LearnedScorer.load(out))
    assert one == two
    assert (tmp_path / "a.jsonl").read_bytes() == (tmp_path / "b.jsonl").read_bytes()


def test_the_learned_scorer_actually_changes_the_scores(trained):
    """A sanity check that the seam is live: the learned run is not the table's run."""
    out, _ = trained
    scorer = LearnedScorer.load(out)
    w = World(6, seed=5, scenario="feud")
    for _ in range(40):
        w.step()
    a = w.living()[0]
    ctx = arbitrator.TermContext(a, w)
    cands = arbitrator.gather(a, w)
    obs = arbitrator.observation_vector(a, w)
    feats = [arbitrator.candidate_features(ctx, c) for c in cands]
    table = np.array([arbitrator.score(obs, f) for f in feats])
    learned = scorer.score_all(obs, feats)
    assert learned.shape == table.shape
    assert not np.allclose(learned, table)
    # ... but it is in the same territory, not off by an order of magnitude.
    assert abs(float(learned.mean()) - float(table.mean())) < 3.0
