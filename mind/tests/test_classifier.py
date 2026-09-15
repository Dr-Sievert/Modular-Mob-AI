"""Tests for the tiny dialogue classifier.

    python -m pytest -q tests

Everything here is CPU-only and single-threaded: the machine is shared with a
live training run. The end-to-end training test uses a 300-line subset and
takes a couple of seconds.
"""

import json
import os
import random
import sys
import tempfile

import numpy as np
import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import torch  # noqa: E402

from text.classifier import export, features, labels  # noqa: E402
from text.classifier.data import (DEFAULT_DATA, HARDCASES, drop_hardcases, featurize,  # noqa: E402
                                  intent_class_weights, load_jsonl, norm_text,
                                  split_rows, targets_of)
from text.classifier.infer import Classifier, check_parity  # noqa: E402
from text.classifier.model import DialogueClassifier  # noqa: E402
from text.classifier.train import build_parser, macro_f1, train  # noqa: E402

torch.set_num_threads(1)

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODEL_DIR = os.path.join(REPO, "text", "models", "clf")


# ---------------------------------------------------------------------------
# hash
# ---------------------------------------------------------------------------

def test_fnv1a_known_vectors():
    assert features.fnv1a_32("") == 0x811C9DC5
    assert features.fnv1a_32("a") == 0xE40C292C
    assert features.fnv1a_32("foobar") == 0xBF9CF968


def test_fnv1a_is_utf8_and_32_bit():
    def reference(data: bytes) -> int:
        h = 0x811C9DC5
        for b in data:
            h = ((h ^ b) * 0x01000193) & 0xFFFFFFFF
        return h

    for s in ("", "a", "hello world", "é中", "x" * 500):
        assert features.fnv1a_32(s) == reference(s.encode("utf-8"))
        assert 0 <= features.fnv1a_32(s) <= 0xFFFFFFFF


def test_buckets_in_range():
    for text in ("Get away from my forge, Brokk!", "", "!!!", "hmm", "x" * 200):
        for b in features.buckets(text):
            assert 0 <= b < features.BUCKETS


# ---------------------------------------------------------------------------
# tokenizer / featurizer
# ---------------------------------------------------------------------------

def test_tokenizer_rules():
    assert features.tokenize("Get away from my forge, Brokk!") == [
        "get", "away", "from", "my", "forge", "brokk"]
    assert features.tokenize("don't  STOP") == ["don't", "stop"]          # apostrophe kept
    assert features.tokenize("'quoted'") == ["quoted"]                    # stripped at the ends
    assert features.tokenize("“don’t”") == ["don't"]       # curly quotes folded
    assert features.tokenize("a--b") == ["a", "b"]                        # dash is a separator
    assert features.tokenize("   ") == []
    assert features.tokenize("gg 42 wp") == ["gg", "42", "wp"]            # digits are token chars


def test_char_trigrams_pad():
    assert features.char_trigrams("a") == ["<a>"]
    assert features.char_trigrams("forge") == ["<fo", "for", "org", "rge", "ge>"]


def test_feature_kinds():
    feats = features.feature_strings("cold forge")
    assert "w:cold" in feats and "w:forge" in feats
    assert "b:cold forge" in feats
    assert "c:<co" in feats and "c:ge>" in feats
    assert "^first=cold" in feats and "$last=forge" in feats
    # unigrams + bigrams + trigrams of both words + first + last
    assert len(feats) == 2 + 1 + (len("cold") + 2 - 2) + (len("forge") + 2 - 2) + 2


def test_position_features_see_word_order():
    """The whole point: same bag of words, different ends of the line."""
    a = features.feature_strings("Brokk, get out")
    b = features.feature_strings("get out, Brokk")
    assert sorted(f for f in a if f.startswith("w:")) == \
           sorted(f for f in b if f.startswith("w:"))
    assert "^first=brokk" in a and "$last=out" in a
    assert "^first=get" in b and "$last=brokk" in b
    assert sorted(features.buckets("Brokk, get out")) != \
           sorted(features.buckets("get out, Brokk"))
    # a one-word line is both its own first and its own last word
    one = features.feature_strings("stop")
    assert "^first=stop" in one and "$last=stop" in one
    # an empty line still has no strings at all, and still gets the empty bucket
    assert features.feature_strings("!?!") == []
    assert features.buckets("!?!") == [features.EMPTY_BUCKET]


def test_featurizer_is_deterministic():
    text = "Careful, Brokk, or you'll end up in the lava."
    first = features.buckets(text)
    for _ in range(5):
        assert features.buckets(text) == first
    # ASCII case-fold: the hashed half is identical, the side vector is not
    assert features.buckets(text) == features.buckets(text.upper())
    side = features.side_features(text)
    for _ in range(5):
        assert features.side_features(text) == side
    assert features.side_features(text.upper()) != side


# ---------------------------------------------------------------------------
# side vector
# ---------------------------------------------------------------------------

def test_side_vector_has_the_documented_length_and_layout():
    assert features.SIDE_DIM == 20 == len(features.SIDE_FIELDS)
    assert features.PREV_INTENTS == labels.INTENTS + ["unknown"]
    assert features.SIDE_SCALAR_DIM == 6
    assert features.SIDE_FIELDS[:6] == [
        "log1p_word_count", "log1p_char_count", "caps_share",
        "exclamation_count", "question_count", "has_ellipsis"]
    for text in ("", "hi", "GET OUT!!! ... what?", "x" * 300):
        v = features.side_features(text)
        assert len(v) == features.SIDE_DIM
        assert all(isinstance(x, float) for x in v)
        # exactly one prev-intent bit, always
        assert sum(v[features.SIDE_SCALAR_DIM:]) == 1.0


def test_side_vector_values():
    import math
    v = features.side_features("GET OUT!!")
    assert v[0] == pytest.approx(math.log1p(2))
    assert v[1] == pytest.approx(math.log1p(len("GET OUT!!")))
    assert v[2] == pytest.approx(1.0)          # caps share, before lowercasing
    assert v[3] == 2.0 and v[4] == 0.0
    assert v[5] == 0.0
    assert features.side_features("get out")[2] == 0.0
    assert features.side_features("Get Out")[2] == pytest.approx(2 / 6)
    assert features.side_features("1234")[2] == 0.0          # no letters at all
    assert features.side_features("well..")[5] == 1.0
    assert features.side_features("well...")[5] == 1.0
    assert features.side_features("well. no.")[5] == 0.0
    assert features.side_features("what? really?")[4] == 2.0


def test_prev_intent_one_hot():
    base = features.SIDE_SCALAR_DIM
    unknown = features.side_features("get out")
    assert unknown[base + features.PREV_INTENTS.index("unknown")] == 1.0
    insulted = features.side_features("get out", "INSULT")
    assert insulted[base + labels.INTENTS.index("INSULT")] == 1.0
    assert insulted[base + features.PREV_INTENTS.index("unknown")] == 0.0
    # the scalar half is untouched by prev_intent
    assert unknown[:base] == insulted[:base]
    # anything that is not exactly an intent falls back to unknown
    for bad in (None, "", "insult", "NOT_AN_INTENT", "unknown"):
        v = features.side_features("get out", bad)
        assert v[base + features.PREV_INTENTS.index("unknown")] == 1.0


def test_empty_text_never_yields_an_empty_bag():
    assert features.buckets("") == [features.EMPTY_BUCKET]
    assert features.buckets("!?!") == [features.EMPTY_BUCKET]


# ---------------------------------------------------------------------------
# label order
# ---------------------------------------------------------------------------

def test_label_orders_match_schema_md():
    schema = labels.parse_schema()
    assert schema["intents"] == labels.INTENTS
    assert schema["topics"] == labels.TOPICS
    assert schema["addressed"] == labels.ADDRESSED
    assert schema["sincerity"] == labels.SINCERITY
    assert schema["names"] == labels.NAME_POOL


def test_head_sizes_match_label_lists():
    m = DialogueClassifier()
    assert m.head_intent.out_features == len(labels.INTENTS) == 13
    assert m.head_topic.out_features == len(labels.TOPICS) == 12
    assert m.head_addressed.out_features == len(labels.ADDRESSED) == 4
    assert m.head_sincerity.out_features == len(labels.SINCERITY) == 3
    assert m.head_floats.out_features == len(labels.FLOAT_FIELDS) == 3
    assert m.emb.num_embeddings == 65536 and m.emb.embedding_dim == 32
    # fc1 takes the embedding sum and the side vector side by side
    assert m.fc1.in_features == 32 + features.SIDE_DIM == 52
    assert m.fc1.out_features == 64


def test_names_are_matched_not_predicted():
    assert labels.names_in_text("Get away from my forge, Brokk!") == ["Brokk"]
    assert labels.names_in_text("brokk is not capitalised") == []
    assert labels.names_in_text("Ormund is a longer word") == []      # boundaries
    assert labels.names_in_text("Ormund is not Orm") == ["Orm"]       # ...but this one is
    assert labels.names_in_text("Brokk and Runa and Brokk") == ["Brokk", "Runa"]


# ---------------------------------------------------------------------------
# data plumbing
# ---------------------------------------------------------------------------

def test_split_is_stratified_including_generated():
    rows = ([{"text": f"a{i}", "source": "alpha"} for i in range(100)]
            + [{"text": f"b{i}", "source": "beta"} for i in range(50)]
            + [{"text": f"g{i}", "source": "generated"} for i in range(40)])
    train_rows, test_rows = split_rows(rows, 0.15, seed=1)
    assert len(train_rows) + len(test_rows) == len(rows)
    # generated lines carry the game's vocabulary: they train like any other source
    for src, n in (("alpha", 100), ("beta", 50), ("generated", 40)):
        in_test = sum(1 for r in test_rows if r["source"] == src)
        assert in_test == round(n * 0.15)
    # and it is reproducible
    again, _ = split_rows(rows, 0.15, seed=1)
    assert [r["text"] for r in again] == [r["text"] for r in train_rows]


def test_hardcases_are_well_formed_and_never_trained_on():
    hard = load_jsonl(HARDCASES)
    assert len(hard) == 40
    for r in hard:
        assert r["intent"] in labels.INTENTS
        assert r["topic"] in labels.TOPICS
        assert r["addressed"] in labels.ADDRESSED
        assert r.get("sincerity") in labels.SINCERITY
        assert 0.0 <= r["aggression"] <= 1.0
        assert -1.0 <= r["valence"] <= 1.0
        assert 0.0 <= r["urgency"] <= 1.0
        assert r["names"] == labels.names_in_text(r["text"])
    # the hard cases cover the awkward pairs the labeling prompt calls out
    intents = {r["intent"] for r in hard}
    assert {"THREAT", "WARNING", "INSULT", "ACCUSE", "COMMAND", "REQUEST"} <= intents
    assert {r["addressed"] for r in hard} == set(labels.ADDRESSED)

    if os.path.exists(DEFAULT_DATA):
        kept, dropped = drop_hardcases(load_jsonl(DEFAULT_DATA), hard)
        banned = {norm_text(r["text"]) for r in hard}
        assert not any(norm_text(r["text"]) in banned for r in kept)


def test_sincerity_target_is_masked_when_absent():
    rows = [{"text": "a", "intent": "GREET", "topic": "NONE", "addressed": "NONE",
             "aggression": 0.0, "valence": 0.0, "urgency": 0.0},
            {"text": "b", "intent": "GREET", "topic": "NONE", "addressed": "NONE",
             "aggression": 0.0, "valence": 0.0, "urgency": 0.0, "sincerity": "JOKING"}]
    t = targets_of(rows)
    assert t["sincerity"][0] == labels.MASKED
    assert t["sincerity"][1] == labels.SINCERITY.index("JOKING")


def test_masked_sincerity_loss_is_finite_and_zero():
    from text.classifier.model import loss_fn
    m = DialogueClassifier()
    idx = torch.from_numpy(np.asarray(features.buckets("hello there"), dtype=np.int64))
    side = torch.tensor([features.side_features("hello there")], dtype=torch.float32)
    out = m(idx, torch.zeros(1, dtype=torch.long), side)
    tgt = {"intent": torch.zeros(1, dtype=torch.long),
           "topic": torch.zeros(1, dtype=torch.long),
           "addressed": torch.zeros(1, dtype=torch.long),
           "sincerity": torch.full((1,), labels.MASKED, dtype=torch.long),
           "floats": torch.zeros(1, 3)}
    parts = loss_fn(out, tgt)
    assert float(parts["sincerity"]) == 0.0
    assert torch.isfinite(parts["total"])


def test_intent_class_weights_lift_the_rare_classes():
    rows = ([{"intent": "SMALLTALK"}] * 900 + [{"intent": "QUESTION"}] * 90
            + [{"intent": "THREAT"}] * 10)
    w = intent_class_weights(rows, power=0.5)
    i = labels.INTENTS.index
    assert w[i("THREAT")] > w[i("QUESTION")] > w[i("SMALLTALK")]
    assert w[i("GREET")] == 0.0            # absent from the split: no weight


def test_macro_f1_matches_a_hand_computation():
    true = np.array([0, 0, 1, 1])
    pred = np.array([0, 1, 1, 1])
    # class 0: P=1, R=.5, F1=.667 ; class 1: P=.667, R=1, F1=.8
    assert macro_f1(true, pred, 2) == pytest.approx((2 / 3 + 0.8) / 2, abs=1e-9)


# ---------------------------------------------------------------------------
# export / numpy parity
# ---------------------------------------------------------------------------

def test_export_round_trip_and_torch_numpy_parity():
    torch.manual_seed(0)
    model = DialogueClassifier()
    with torch.no_grad():                   # random but not degenerate
        for p in model.parameters():
            p.uniform_(-0.3, 0.3)
    with tempfile.TemporaryDirectory() as tmp:
        meta = export.export_model(model, tmp, {"data": {"sincerity_labeled_rows": 0}})
        assert [a["name"] for a in meta["weights"]["arrays"]] == \
               [n for n, _ in export.ARRAY_ORDER]
        with np.load(os.path.join(tmp, "weights.npz")) as npz:
            assert sorted(npz.files) == sorted(n for n, _ in export.ARRAY_ORDER)
            assert npz["emb.weight"].shape == (65536, 32)
            assert npz["emb.weight"].dtype == np.float32
            assert npz["fc1.weight"].shape == (64, 32 + features.SIDE_DIM)
        clf = Classifier.load(tmp)
        assert clf.labels["intent"] == labels.INTENTS
        assert clf.labels["sincerity"] == labels.SINCERITY

        texts = _sample_texts(500)
        worst, n = check_parity(model, clf, texts, n=500, seed=0)
        assert n >= 100
        assert worst < 1e-5


def test_model_json_documents_the_side_vector():
    torch.manual_seed(0)
    model = DialogueClassifier()
    with tempfile.TemporaryDirectory() as tmp:
        meta = export.export_model(model, tmp, None)
        assert meta["dims"]["side"] == features.SIDE_DIM
        assert meta["dims"]["fc1_in"] == meta["dims"]["embedding"] + features.SIDE_DIM
        side = meta["side_features"]
        assert side["dim"] == features.SIDE_DIM
        assert side["order"] == features.SIDE_FIELDS       # the layout, in order
        assert side["prev_intent_labels"] == features.PREV_INTENTS
        assert side["prev_intent_offset"] == features.SIDE_SCALAR_DIM
        assert set(side["fields"]) == {f.split("=")[0] for f in features.SIDE_FIELDS[:6]} | \
               {"prev_intent"}
        assert meta["weights"]["shapes"]["fc1.weight"] == [64, 32 + features.SIDE_DIM]
        # the position features are written down for the Java port too
        joined = " ".join(meta["tokenizer"]["features"])
        assert "^first=" in joined and "$last=" in joined
        # and a v1 file (no side block) is refused rather than silently misread
        with open(os.path.join(tmp, "model.json"), encoding="utf-8") as fh:
            broken = json.load(fh)
        broken["dims"].pop("side")
        with open(os.path.join(tmp, "model.json"), "w", encoding="utf-8") as fh:
            json.dump(broken, fh)
        with pytest.raises(ValueError):
            Classifier.load(tmp)


def test_prev_intent_reaches_the_numpy_forward_pass():
    torch.manual_seed(2)
    model = DialogueClassifier()
    with torch.no_grad():
        for p in model.parameters():
            p.uniform_(-0.3, 0.3)
    with tempfile.TemporaryDirectory() as tmp:
        export.export_model(model, tmp, None)
        clf = Classifier.load(tmp)
        text = "get out"
        assert (clf.hidden(text) == clf.hidden(text, "unknown")).all()
        assert (clf.hidden(text) == clf.hidden(text, None)).all()
        assert not (clf.hidden(text) == clf.hidden(text, "INSULT")).all()
        # and it runs the same way through predict, without changing the shape
        rec = clf.predict(text, prev_intent="INSULT")
        assert list(rec) == list(clf.predict(text))
        assert rec["intent"] in labels.INTENTS


def test_classify_cli_takes_prev_intent(capsys, tmp_path):
    import text.classify as classify_cli
    torch.manual_seed(3)
    model = DialogueClassifier()
    out_dir = str(tmp_path / "clf")
    export.export_model(model, out_dir, None)
    assert classify_cli.main(["--model", out_dir, "--prev-intent", "INSULT",
                              "get out"]) == 0
    rec = json.loads(capsys.readouterr().out.strip())
    assert rec["intent"] in labels.INTENTS
    with pytest.raises(SystemExit):                    # not an intent: argparse refuses
        classify_cli.main(["--model", out_dir, "--prev-intent", "NOPE", "get out"])


def test_predict_has_the_schema_shape():
    torch.manual_seed(1)
    model = DialogueClassifier()
    with tempfile.TemporaryDirectory() as tmp:
        export.export_model(model, tmp, None)
        clf = Classifier.load(tmp)
        rec = clf.predict("Get away from my forge, Brokk!")
        assert list(rec) == ["text", "intent", "topic", "addressed",
                             "aggression", "valence", "urgency", "names", "sincerity"]
        assert rec["intent"] in labels.INTENTS
        assert rec["topic"] in labels.TOPICS
        assert rec["addressed"] in labels.ADDRESSED
        assert rec["sincerity"] in labels.SINCERITY
        assert rec["names"] == ["Brokk"]
        assert 0.0 <= rec["aggression"] <= 1.0
        assert -1.0 <= rec["valence"] <= 1.0
        assert 0.0 <= rec["urgency"] <= 1.0
        assert clf.predict("")["text"] == ""          # empty line must not crash


def _sample_texts(n: int) -> list[str]:
    if os.path.exists(DEFAULT_DATA):
        rows = load_jsonl(DEFAULT_DATA)
        rng = random.Random(0)
        rng.shuffle(rows)
        return [r["text"] for r in rows[:n]]
    return [f"line number {i} about the forge" for i in range(n)]


# ---------------------------------------------------------------------------
# end to end
# ---------------------------------------------------------------------------

@pytest.mark.skipif(not os.path.exists(DEFAULT_DATA), reason="no merged data yet")
def test_train_end_to_end_on_300_lines_beats_chance():
    rows = load_jsonl(DEFAULT_DATA)[:300]
    with tempfile.TemporaryDirectory() as tmp:
        data_path = os.path.join(tmp, "subset.jsonl")
        with open(data_path, "w", encoding="utf-8", newline="\n") as fh:
            for r in rows:
                fh.write(json.dumps(r, ensure_ascii=False) + "\n")
        args = build_parser().parse_args(
            ["--data", data_path, "--out", os.path.join(tmp, "clf"),
             "--epochs", "8", "--seed", "0", "--bench-lines", "0"])
        result = train(args)
        m = result["metrics"]["test"]

        # chance for a majority-class guesser on this subset, per head
        assert m["intent"]["accuracy"] > 1.0 / len(labels.INTENTS)
        assert m["topic"]["accuracy"] > 1.0 / len(labels.TOPICS)
        assert m["addressed"]["accuracy"] > 1.0 / len(labels.ADDRESSED)
        # a constant 0.5 guess would give MAE around 0.3 on these
        assert m["floats"]["aggression"]["mae"] < 0.3
        assert m["floats"]["urgency"]["mae"] < 0.3
        d = result["metrics"]["data"]
        assert d["test"] == d["rows"] - d["train"]


@pytest.mark.skipif(not os.path.exists(os.path.join(MODEL_DIR, "model.json")),
                    reason="no trained model in text/models/clf")
def test_shipped_model_loads_and_is_consistent():
    clf = Classifier.load(MODEL_DIR)
    meta = clf.meta
    assert meta["dims"]["buckets"] == features.BUCKETS
    assert meta["dims"]["side"] == features.SIDE_DIM
    assert meta["side_features"]["order"] == features.SIDE_FIELDS
    assert meta["labels"]["intent"] == labels.INTENTS
    assert meta["labels"]["float_activations"] == labels.FLOAT_ACTIVATIONS
    assert meta["hash"]["known_vectors"]["a"] == "0xe40c292c"
    threat = clf.predict("Get away from my forge, Brokk, or I'll break your arm.")
    assert threat["names"] == ["Brokk"]
    # the model should at least hear the difference in tone
    assert threat["aggression"] > clf.predict("Good morning, Ingrid.")["aggression"]
    # shouting is the side vector's caps column doing work
    assert clf.predict("GET OUT OF MY FORGE")["aggression"] > \
           clf.predict("get out of my forge")["aggression"]


# model.pt is the training checkpoint: regenerable, and not in git (see the
# repository .gitignore). weights.npz + model.json, checked above, are what
# ships. So the shipped model is checked in every checkout and the torch/numpy
# drift only where somebody has trained.
@pytest.mark.skipif(not os.path.exists(os.path.join(MODEL_DIR, "model.pt")),
                    reason="no training checkpoint in text/models/clf (not in git)")
def test_shipped_model_matches_torch():
    clf = Classifier.load(MODEL_DIR)
    state = torch.load(os.path.join(MODEL_DIR, "model.pt"), map_location="cpu",
                       weights_only=True)
    model = DialogueClassifier()
    model.load_state_dict(state)
    worst, n = check_parity(model, clf, _sample_texts(500), n=500, seed=7)
    assert worst < 1e-5, f"torch/numpy drift {worst}"
