"""Freeze the two trained models into ``models/``: the port-ready artefacts, in one command.

    python -m tools.freeze                      # regenerate everything from the current exports
    python -m tools.freeze --only interpreter   # just the classifier half

What it writes (all of it regenerable, none of it hand-edited):

    models/interpreter/weights.npz     copied from text/models/clf
    models/interpreter/model.json      copied, unchanged
    models/interpreter/layout.json     the layout a port must agree on; its sha256 is the schema id
    models/interpreter/parity.jsonl    200 fixed lines with their exact numpy outputs
    models/interpreter/README.md       the whole model, specified for a Java port
    models/decisions/imitator.npz      copied from runs/learn/imitator.npz
    models/decisions/imitator.json     copied, unchanged
    models/decisions/layout.json       ditto, for the observation and candidate layout
    models/decisions/parity.jsonl      200 fixed (observation, candidate) pairs and their scores
    models/decisions/README.md         ditto
    models/MANIFEST.json               schema ids, arrays, training data hash and date, metrics

``runs/`` is not in git, so this is also the step that makes a trained model tracked. Nothing here
imports torch: the frozen files are what the numpy reference and the Java port both read.

The parity lines are chosen by a fixed rule, not a fresh random draw, so a retrain that does not
change the data changes the *numbers* in parity.jsonl and not the *lines* -- which is what makes a
diff of that file readable.
"""

from __future__ import annotations

import argparse
import datetime as _dt
import glob
import hashlib
import json
import os
import random
import shutil
import sys

import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from text.classifier import features as clf_features                     # noqa: E402
from text.classifier.export import ARRAY_ORDER as CLF_ARRAY_ORDER        # noqa: E402
from text.classifier.infer import Classifier                             # noqa: E402
from text.classifier.labels import INTENTS                               # noqa: E402

#: How many parity records each model gets.
PARITY_LINES = 200
PARITY_PAIRS = 200

#: The seed for every sampled choice below. Changing it reshuffles which lines are in parity.jsonl.
PARITY_SEED = 20260915

#: The collection the decision pairs are drawn from: small, fast, deterministic, and wide enough to
#: contain the rare reactions (the three targeted settlements, recorded on every close tick).
COLLECT = {
    "seeds": (1, 2),
    "ticks": 400,
    "stride": 10,
    "targeted_seeds": (9, 10),
    "hard_gap": 0.5,
}

FORTY_WORDS = ("I told him and I told his brother and I told the whole hall besides that the "
               "east gallery is not safe to work while the water is that high but nobody in this "
               "settlement ever listens to a single word that I say about anything at all")

#: The eight edge cases every port has to get right, and the reason each is here.
EDGE_CASES = [
    ("edge:empty", ""),
    ("edge:one_word", "Stop"),
    ("edge:forty_words", FORTY_WORDS),
    ("edge:all_caps", "GET OUT OF MY FORGE"),
    ("edge:punctuation_only", "?!..."),
    ("edge:unicode_quotes", "“You’re a fool,” Brokk said — again…"),
    ("edge:name_first", "Brokk, get out of my forge."),
    ("edge:name_last", "Get out of my forge, Brokk."),
]


# ---------------------------------------------------------------------------
# small helpers
# ---------------------------------------------------------------------------


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for block in iter(lambda: fh.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def write_json(path: str, obj) -> str:
    """Write pretty JSON with LF endings; return its sha256."""
    data = (json.dumps(obj, indent=2, sort_keys=False, ensure_ascii=False) + "\n").encode("utf-8")
    with open(path, "wb") as fh:
        fh.write(data)
    return sha256_bytes(data)


def write_lines(path: str, records) -> None:
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        for rec in records:
            fh.write(json.dumps(rec, ensure_ascii=False) + "\n")


def write_text(path: str, text: str) -> None:
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)


def f32(x) -> float:
    """One float32 as a Python float, so JSON round-trips it exactly."""
    return float(np.float32(x))


def f32list(a) -> list:
    return [float(v) for v in np.asarray(a, dtype=np.float32)]


def read_jsonl_texts(path: str, limit: int | None = None) -> list:
    """The ``text`` field of every parsable row. A generated batch carries the odd ``STATE:`` line
    the generator wrote between turns; those are skipped rather than made fatal."""
    out = []
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line or not line.startswith("{"):
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError:
                continue
            text = row.get("text")
            if isinstance(text, str) and text:
                out.append(text)
            if limit is not None and len(out) >= limit:
                break
    return out


# ---------------------------------------------------------------------------
# the interpreter (the dialogue classifier)
# ---------------------------------------------------------------------------


def parity_texts(root: str, n: int = PARITY_LINES) -> list:
    """The fixed parity lines: the edge cases, every hard case, then generated and corpus lines.

    Deterministic: sorted file order and one seeded sample, so the same checkout yields the same
    200 lines whatever the model is.
    """
    rng = random.Random(PARITY_SEED)
    picked, seen = [], set()

    def add(source: str, text: str) -> bool:
        if text in seen:
            return False
        seen.add(text)
        picked.append((source, text))
        return True

    for source, text in EDGE_CASES:
        add(source, text)

    hard_path = os.path.join(root, "text", "data", "hardcases.jsonl")
    for text in read_jsonl_texts(hard_path):
        add("hardcase", text)

    generated = []
    for path in sorted(glob.glob(os.path.join(root, "text", "data", "generated", "*.jsonl"))):
        generated.extend(read_jsonl_texts(path))
    corpus = read_jsonl_texts(os.path.join(root, "text", "data", "corpus.jsonl"))

    # Fill the rest 40/60 from generated and corpus, generated first so a short corpus cannot
    # crowd the domain vocabulary out.
    remaining = n - len(picked)
    want_generated = min(len(generated), int(round(remaining * 0.4)))
    for text in rng.sample(generated, min(len(generated), want_generated * 3)):
        if len([p for p in picked if p[0] == "generated"]) >= want_generated:
            break
        add("generated", text)
    for text in rng.sample(corpus, min(len(corpus), (n - len(picked)) * 3)):
        if len(picked) >= n:
            break
        add("corpus", text)
    if len(picked) < n:
        raise RuntimeError("only %d parity lines available, wanted %d" % (len(picked), n))
    return picked[:n]


def parity_prev_intent(index: int) -> str | None:
    """Every twentieth line answers something, so the prev_intent one-hot is exercised too."""
    if index % 20 != 19:
        return None
    return INTENTS[(index // 20) % len(INTENTS)]


def clf_logits(clf: Classifier, text: str, prev_intent: str | None) -> dict:
    """The pre-softmax logits of every head plus the three activated floats."""
    h = clf.hidden(text, prev_intent)
    out = {name: f32list(w @ h + b) for name, (w, b) in clf.heads.items()}
    raw = clf.floats_w @ h + clf.floats_b
    out["floats"] = [f32(1.0 / (1.0 + np.exp(-raw[0]))), f32(np.tanh(raw[1])),
                     f32(1.0 / (1.0 + np.exp(-raw[2])))]
    return out


def interpreter_parity(clf: Classifier, lines) -> list:
    records = []
    for i, (source, text) in enumerate(lines):
        prev = parity_prev_intent(i)
        logits = clf_logits(clf, text, prev)
        records.append({
            "i": i,
            "source": source,
            "text": text,
            "prev_intent": prev,
            "buckets": [int(b) for b in clf_features.buckets(text)],
            "side": [f32(v) for v in clf_features.side_features(text, prev)],
            "logits": {"intent": logits["intent"], "topic": logits["topic"],
                       "addressed": logits["addressed"], "sincerity": logits["sincerity"]},
            "floats": logits["floats"],
        })
    return records


def interpreter_layout(meta: dict) -> dict:
    """Everything a port must agree on, and nothing that changes when the weights change.

    Its sha256 is this model's schema id, the same discipline as the mod's ``schema.json``.
    """
    return {
        "model": "interpreter",
        "schema_id": meta["schema_id"],
        "dims": meta["dims"],
        "hash": meta["hash"],
        "tokenizer": meta["tokenizer"],
        "side_features": meta["side_features"],
        "labels": meta["labels"],
        "optional_labels": meta["optional_labels"],
        "forward": meta["forward"],
        "arrays": [{"name": n, "shape": meta["weights"]["shapes"][n], "dtype": "float32",
                    "role": role} for n, role in CLF_ARRAY_ORDER],
        "linear_layout": meta["weights"]["linear_layout"],
    }


def interpreter_readme(meta: dict, layout_sha: str, lines) -> str:
    d = meta["dims"]
    tok = meta["tokenizer"]
    side = meta["side_features"]
    shapes = meta["weights"]["shapes"]
    seps = tok["separators"]
    arrays = "\n".join("| %d | `%s` | %s | %s |" % (i, n, "x".join(str(s) for s in shapes[n]), role)
                       for i, (n, role) in enumerate(CLF_ARRAY_ORDER))
    side_rows = "\n".join(["| index | column |", "| --- | --- |"]
                          + ["| %d | `%s` |" % (i, f) for i, f in enumerate(side["order"])])
    printable = "".join(c for c in seps if c.isprintable())
    control = ", ".join("U+%04X" % ord(c) for c in sorted(seps) if not c.isprintable())
    sources = {}
    for source, _ in lines:
        key = source.split(":")[0]
        sources[key] = sources.get(key, 0) + 1
    source_line = ", ".join("%d %s" % (v, k) for k, v in sorted(sources.items()))
    return f"""# interpreter: the dialogue classifier, frozen

One line of chat in, one label set out. Schema id `{meta["schema_id"]}`, layout sha256
`{layout_sha}` -- the sha256 of `layout.json`, which is
what a port checks itself against.

Files: `weights.npz` ({d["buckets"]} x {d["embedding"]} embedding and two small layers, float32),
`model.json` (the same facts as data, plus the training metrics), `layout.json` (the contract whose
hash is the schema id), `parity.jsonl` ({len(lines)} lines with their exact outputs), this file.

Everything below is the whole model. A Java port needs nothing else, and needs no Python.

## 1. Tokenizer

In order, over the raw line:

1. **Quote folding.** `U+2018` and `U+2019` become ASCII `'`; `U+201C` and `U+201D` become ASCII
   `"`. Nothing else is normalised: no NFC, no accent stripping.
2. **Split on separators.** A separator is any of these {len(seps)} characters -- the ASCII
   punctuation block *without* the apostrophe, plus en dash, em dash and ellipsis:

   ```
   {printable}
   ```

   (the first of those is a space), and the whitespace controls {control}. Every other character,
   digits and non-ASCII letters included, belongs to the token. Runs of separators produce no empty
   tokens.
3. **Lowercase, ASCII only.** `A`-`Z` map to `a`-`z` (`ch + 32`); every other character is left
   exactly as it is. Do **not** use `String.toLowerCase()`: it is locale-dependent (Turkish `I`)
   and folds non-ASCII, and either one changes the hash.
4. **Strip apostrophes from both ends of a token.** `'hello'` -> `hello`, `don't` stays `don't`. A
   token that becomes empty is dropped.

Iterate over Unicode code points, not UTF-16 chars, wherever a character is compared.

## 2. Feature strings

From the token list, in this order (the bag is a sum, so the order is documentation, but the list
itself is not):

| kind | string |
| --- | --- |
| word unigram | `"w:" + token` |
| word bigram | `"b:" + token[i] + " " + token[i+1]` |
| char trigram | `"c:" + t` for every 3-character window of `"<" + token + ">"` |
| first word | `"^first=" + tokens[0]` |
| last word | `"$last=" + tokens[-1]` |

A one-character token yields exactly one trigram (`<a>`). A one-token line emits both position
features over the same token. **A line with no tokens at all** (the empty string, `"?!..."`) emits
no feature strings and instead uses the single bucket `{tok["empty_text_bucket"]}`, which is
`fnv1a_32("") & 0xffff`; the bag is never empty.

## 3. Hash

FNV-1a, 32 bit, over the **UTF-8 bytes** of the feature string:

```java
int h = 0x811c9dc5;
for (byte b : s.getBytes(StandardCharsets.UTF_8)) {{
    h ^= (b & 0xff);
    h *= 0x01000193;          // int overflow is the & 0xffffffff
}}
int bucket = h & ({d["buckets"]} - 1);
```

Known vectors: `fnv1a_32("")` = `0x811c9dc5`, `fnv1a_32("a")` = `0xe40c292c`,
`fnv1a_32("foobar")` = `0xbf9cf968`. `{d["buckets"]}` is a power of two, so the mask and a modulo
are the same thing. Buckets repeat, and a repeat is counted every time: the bag **sums** rows, it
does not average or deduplicate.

## 4. Side vector

{side["dim"]} floats, computed from the **raw** line before any lowercasing or quote folding, in
this frozen order:

{side_rows}

* `log1p_word_count` = `ln(1 + number of tokens)`, the same tokens as the bag;
* `log1p_char_count` = `ln(1 + number of characters in the raw line)`, code points;
* `caps_share` = ASCII `A`-`Z` count / ASCII letter count, `0.0` when the line has no ASCII letter;
* `exclamation_count`, `question_count` = raw counts, not capped;
* `has_ellipsis` = `1.0` when two `.` are adjacent anywhere (`..`, `...`), else `0.0`;
* `prev_intent=*` = a 14-way one-hot at offset {side["prev_intent_offset"]}: the 13 intents in
  label order, then `unknown`. Anything the caller does not supply, or does not spell exactly like
  an intent, is `unknown`.

`prev_intent` is plumbing, not signal: every training row carries `unknown`, so the other thirteen
columns have never had a gradient. Wire it, do not read anything into what it does today.

## 5. Forward pass

`weights.npz` holds these arrays and nothing else, in this order (this is the order a flat
`.mbw`-style segment layout should follow):

| # | array | shape | role |
| --- | --- | --- | --- |
{arrays}

A Linear is stored the way torch stores it, `weight` is `(out, in)` row major, so one output's
weights are contiguous and `y = x @ weight.T + bias`.

```
bag  = sum over the line's buckets of emb.weight[bucket]      # {d["embedding"]} floats
h    = max(bag, 0)                                            # ReLU
x    = concat(h, side)                                        # {d["fc1_in"]} floats
h    = max(x @ fc1.weight.T + fc1.bias, 0)                    # {d["hidden"]} floats
intent    = h @ head_intent.weight.T    + head_intent.bias    # {d["heads"]["intent"]} logits
topic     = h @ head_topic.weight.T     + head_topic.bias     # {d["heads"]["topic"]} logits
addressed = h @ head_addressed.weight.T + head_addressed.bias # {d["heads"]["addressed"]} logits
sincerity = h @ head_sincerity.weight.T + head_sincerity.bias # {d["heads"]["sincerity"]} logits
raw       = h @ head_floats.weight.T    + head_floats.bias    # 3
floats    = [sigmoid(raw[0]), tanh(raw[1]), sigmoid(raw[2])]  # aggression, valence, urgency
```

Each enum label is `argmax` of its logits; a probability, where one is wanted, is `softmax` of them.
`names` are **not predicted**: they are exact matches of the name pool (`model.json`,
`labels.names`) in the raw text. `sincerity` is optional and was masked when absent while training.

Arithmetic is float32 throughout; accumulating the bag in float32 is what the reference does and
what the tolerance below assumes.

## 6. parity.jsonl

{len(lines)} lines, one JSON object each, the frozen answer sheet: {source_line}. A port passes when
every field matches to **1e-5** absolute (`buckets` matches exactly).

| field | what |
| --- | --- |
| `i` | the record index, 0..{len(lines) - 1}; `prev_intent` is non-null where `i % 20 == 19` |
| `source` | where the line came from: `edge:*`, `hardcase`, `generated`, `corpus` |
| `text` | the raw input line, exactly as it goes in |
| `prev_intent` | the previous line's intent, or `null` for `unknown` |
| `buckets` | the hashed bucket list, in feature order, repeats kept |
| `side` | the {side["dim"]} side floats |
| `logits.*` | the four heads' **pre-softmax** logits |
| `floats` | the three floats **after** their activations |

The scalar counts in `side` are exact integers as floats, so a mismatch there is a tokenizer bug and
not rounding. Check `buckets` first: everything else is downstream of it.
"""


def freeze_interpreter(root: str, clf_dir: str, out_dir: str) -> dict:
    os.makedirs(out_dir, exist_ok=True)
    for name in ("weights.npz", "model.json"):
        shutil.copyfile(os.path.join(clf_dir, name), os.path.join(out_dir, name))
    with open(os.path.join(out_dir, "model.json"), encoding="utf-8") as fh:
        meta = json.load(fh)

    layout_sha = write_json(os.path.join(out_dir, "layout.json"), interpreter_layout(meta))

    clf = Classifier.load(out_dir)
    lines = parity_texts(root)
    records = interpreter_parity(clf, lines)
    write_lines(os.path.join(out_dir, "parity.jsonl"), records)
    write_text(os.path.join(out_dir, "README.md"), interpreter_readme(meta, layout_sha, lines))

    data = meta.get("data", {})
    return {
        "name": "interpreter",
        "what": "the dialogue classifier: one line of chat in, the SCHEMA.md label set out",
        "directory": "models/interpreter",
        "source": os.path.relpath(clf_dir, root).replace("\\", "/"),
        "schema_id": meta["schema_id"],
        "layout_sha256": layout_sha,
        "exported": meta.get("created"),
        "weights": {
            "file": "weights.npz",
            "dtype": "float32",
            "bytes": os.path.getsize(os.path.join(out_dir, "weights.npz")),
            "sha256": sha256_file(os.path.join(out_dir, "weights.npz")),
            "arrays": [{"name": n, "shape": meta["weights"]["shapes"][n]}
                       for n, _ in CLF_ARRAY_ORDER],
            "parameters": int(sum(int(np.prod(meta["weights"]["shapes"][n]))
                                  for n, _ in CLF_ARRAY_ORDER)),
        },
        "training_data": {
            "path": data.get("path"),
            "sha256": data.get("sha256"),
            "rows": data.get("rows"),
            "train": data.get("train"),
            "test": data.get("test"),
            "date": meta.get("created"),
        },
        "metrics": _interpreter_metrics(meta),
        "parity": {"file": "parity.jsonl", "records": len(records), "tolerance": 1e-5},
    }


def _interpreter_metrics(meta: dict) -> dict:
    test = (meta.get("metrics") or {}).get("test", {})
    out = {}
    for head in ("intent", "topic", "addressed", "sincerity"):
        if head in test:
            out[head] = {"accuracy": test[head].get("accuracy"),
                         "macro_f1": test[head].get("macro_f1"), "n": test[head].get("n")}
    if "floats" in test:
        out["floats"] = {k: v.get("mae") for k, v in test["floats"].items()}
    return out


# ---------------------------------------------------------------------------
# the decisions model (the learned arbitrator)
# ---------------------------------------------------------------------------


def decision_pairs(root: str, n: int = PARITY_PAIRS) -> list:
    """Collect a small run with the hand-written teacher and pick `n` (observation, candidate) pairs.

    The pairs are spread over the decisions and biased towards skill coverage: the rare reactions
    are exactly what a port most easily gets wrong, because they are the columns furthest from
    `WORK`. Deterministic: fixed seeds, fixed ticks, one seeded shuffle.
    """
    import tempfile

    from dwarfsim.learn import collect as collect_mod

    scenarios = collect_mod.SCENARIOS
    tmp = os.path.join(tempfile.mkdtemp(prefix="freeze-"), "teacher.npz")
    meta = collect_mod.collect(tmp, seeds=COLLECT["seeds"], ticks=COLLECT["ticks"],
                               scenarios=scenarios, stride=COLLECT["stride"],
                               targeted_seeds=COLLECT["targeted_seeds"],
                               hard_gap=COLLECT["hard_gap"], verbose=False)
    decisions = collect_mod.load(tmp)

    # One entry per (decision, candidate), grouped by the candidate's skill, so the draw below can
    # take round-robin over the skills that actually occur.
    from dwarfsim.schema import SKILL_NAMES

    by_skill = {}
    for d in range(len(decisions)):
        lo, hi = decisions.slice(d)
        for c in range(lo, hi):
            by_skill.setdefault(int(decisions.cand_skill[c]), []).append((d, c))
    rng = random.Random(PARITY_SEED)
    for rows in by_skill.values():
        rng.shuffle(rows)

    order = sorted(by_skill)
    picked, cursor = [], {k: 0 for k in order}
    while len(picked) < n:
        progressed = False
        for skill in order:
            if len(picked) >= n:
                break
            rows = by_skill[skill]
            if cursor[skill] < len(rows):
                picked.append(rows[cursor[skill]])
                cursor[skill] += 1
                progressed = True
        if not progressed:
            raise RuntimeError("only %d candidates collected, wanted %d" % (len(picked), n))
    picked.sort()

    pairs = []
    for i, (d, c) in enumerate(picked):
        lo, _ = decisions.slice(d)
        obs = decisions.observation(d)
        cand = collect_mod.expand(decisions.cand_terms[c:c + 1], decisions.cand_skill[c:c + 1])[0]
        pairs.append({
            "i": i,
            "scenario": decisions.scenarios[int(decisions.scenario[d])],
            "seed": int(decisions.seed[d]),
            "decision": int(d),
            "candidate": int(c - lo),
            "skill": SKILL_NAMES[int(decisions.cand_skill[c])],
            "chosen": bool(int(decisions.chosen[d]) == c - lo),
            "obs": f32list(obs),
            "cand": f32list(cand),
            "teacher": f32(decisions.teacher[c]),
        })
    shutil.rmtree(os.path.dirname(tmp), ignore_errors=True)
    return pairs, meta


def decisions_layout(meta: dict) -> dict:
    from dwarfsim import schema

    return {
        "model": "decisions",
        "schema_id": meta["schema_id"],
        "dwarfsim_schema_id": meta["dwarfsim_schema_id"],
        "input": meta["input"],
        "observation": observation_blocks(),
        "candidate": {
            "size": schema.CAND_SIZE,
            "skill_one_hot_at": schema.CAND_SKILL,
            "skills": list(schema.SKILL_NAMES),
            "terms_at": schema.CAND_TERMS,
            "terms": list(schema.TERM_NAMES),
        },
        "forward": meta["forward"],
        "arrays": [{"name": a["name"], "shape": meta["weights"]["shapes"][a["name"]],
                    "dtype": "float32", "role": a["role"]} for a in meta["weights"]["arrays"]],
        "linear_layout": meta["weights"]["linear_layout"],
    }


def observation_blocks() -> list:
    """The observation as an offset table, read off dwarfsim.schema so it cannot drift."""
    from dwarfsim import schema

    mind = [
        ("emotions", schema.MIND_EMOTIONS, 4, "anger, fear, happiness, grief"),
        ("needs", schema.MIND_NEEDS, 4, "hunger, thirst, fatigue, social"),
        ("traits", schema.MIND_TRAITS, 6, "bravery, greed, temper, sociability, pride, forgiveness"),
        ("health", schema.MIND_HEALTH, 1, "health / %g" % schema.MAX_HEALTH),
        ("inventory", schema.MIND_INVENTORY, 5,
         "ore, gold, food, ale, weapon quality; each count divided by %s, weapon quality already 0..1"
         % ", ".join("%s %g" % (k, v) for k, v in schema.INVENTORY_SCALE.items() if k != "weapon")),
        ("focus", schema.MIND_FOCUS, schema.FOCUS_SLOTS * schema.FOCUS_STRIDE,
         "%d relationship slots of %d: present, trust, respect, hatred, grudge, gratitude; "
         "filled by salience, padded with zeros"
         % (schema.FOCUS_SLOTS, schema.FOCUS_STRIDE)),
    ]
    rest = [
        ("place", schema.OBS_PLACE, schema.N_PLACES,
         "one-hot over %s" % ", ".join(schema.PLACES)),
        ("crowd", schema.OBS_CROWD, 1, "living dwarves here / 6"),
        ("monster", schema.OBS_MONSTER, 1, "a monster is here"),
        ("monster_hp", schema.OBS_MONSTER_HP, 1, "its health fraction"),
        ("under_attack", schema.OBS_UNDER_ATTACK, 1, "hit within the last 12 ticks"),
        ("hit_age", schema.OBS_HIT_AGE, 1, "ticks since the last hit / 50"),
        ("alive_fraction", schema.OBS_ALIVE_FRACTION, 1, "living dwarves / starting count"),
        ("clock", schema.OBS_CLOCK, 1, "(tick % 200) / 200"),
        ("goals", schema.OBS_GOALS, schema.N_GOALS,
         "strength per goal: %s" % ", ".join(schema.GOAL_KINDS)),
        ("obligations", schema.OBS_OBLIGATIONS, 2, "open owed by me, owed to me, each / 3"),
        ("memory", schema.OBS_MEMORY, 2, "memories held / %d, mean salience" % schema.MEMORY_CAP),
        ("is_chief", schema.OBS_IS_CHIEF, 1, "am I the chief (0 when nobody is)"),
        ("chief_here", schema.OBS_CHIEF_HERE, 1, "the chief is standing here"),
    ]
    blocks = [{"name": "mind", "at": schema.OBS_MIND, "size": schema.MIND_SIZE,
               "parts": [{"name": n, "at": at, "size": size, "what": what}
                         for n, at, size, what in mind]}]
    blocks += [{"name": n, "at": at, "size": size, "what": what} for n, at, size, what in rest]
    return {"size": schema.OBS_SIZE, "blocks": blocks}


def decisions_readme(meta: dict, layout_sha: str, pairs, collect_meta: dict) -> str:
    from dwarfsim import schema

    layout = observation_blocks()
    rows = []
    for block in layout["blocks"]:
        if block["name"] == "mind":
            rows.append("| `mind` | %d | %d | the whole mind vector, below |"
                        % (block["at"], block["size"]))
            for part in block["parts"]:
                rows.append("| &nbsp;&nbsp;`mind.%s` | %d | %d | %s |"
                            % (part["name"], part["at"], part["size"], part["what"]))
        else:
            rows.append("| `%s` | %d | %d | %s |"
                        % (block["name"], block["at"], block["size"], block["what"]))
    obs_rows = "\n".join(rows)
    shapes = meta["weights"]["shapes"]
    array_rows = "\n".join("| %d | `%s` | %s | %s |"
                           % (i, a["name"], "x".join(str(s) for s in shapes[a["name"]]), a["role"])
                           for i, a in enumerate(meta["weights"]["arrays"]))
    skills = ", ".join("%d `%s`" % (i, s) for i, s in enumerate(schema.SKILL_NAMES))
    skills_seen = {p["skill"] for p in pairs}
    missing_skills = ", ".join("`%s`" % s for s in schema.SKILL_NAMES if s not in skills_seen) \
        or "none"
    terms = ", ".join("%d `%s`" % (i, t) for i, t in enumerate(schema.TERM_NAMES))
    held = (meta.get("metrics") or {}).get("held_out", {})
    return f"""# decisions: the learned arbitrator, frozen

What to do next. One observation plus one candidate action in, one score out; the highest score
wins, or a softmax at T = 0.25 samples among them the way the sim does. Schema id
`{meta["schema_id"]}` against world layout `{meta["dwarfsim_schema_id"]}`, layout sha256
`{layout_sha}` (the sha256 of `layout.json`).

Files: `imitator.npz` (six float32 arrays), `imitator.json` (dims, layout, training run, metrics),
`layout.json`, `parity.jsonl` ({len(pairs)} pairs with their exact scores), this file.

It was trained to imitate the hand-written weight table and makes {held.get("top1", 0) * 100:.1f}% of
its choices on held-out seeds ({held.get("decisions", "?"):,} decisions, chance
{held.get("chance", 0) * 100:.1f}%). The table is still the teacher and still the readable
explanation; this is the same function with the terms folded in.

## 1. The observation: {layout["size"]} floats

| block | at | size | what |
| --- | --- | --- | --- |
{obs_rows}

Everything is roughly 0..1 (a few relationship columns are signed). There is no normaliser and no
clip: the vector is built already scaled, which is why nothing about a normaliser travels with the
weights the way the combat network's does.

## 2. The candidate: {schema.CAND_SIZE} floats

One proposed action. `[0, {schema.N_SKILLS})` is a **skill one-hot**, `[{schema.CAND_TERMS},
{schema.CAND_SIZE})` are the **raw, unweighted term values** the hand-written table would have
multiplied by its weights.

Skills, in the frozen order (appended to, never reordered):

{skills}

Terms, in the frozen order:

{terms}

`base` is always 1.0. `noise` is one draw per candidate per decision, from the sim's own RNG: in the
game it is what keeps identical agents from moving in lockstep, and a port that wants determinism
sets it to 0 for every candidate rather than dropping the column.

## 3. Forward pass

| # | array | shape | role |
| --- | --- | --- | --- |
{array_rows}

Linears are stored the way torch stores them, `weight` is `(out, in)` row major, `y = x @ weight.T
+ bias`.

```
x     = observation ++ candidate                         # {meta["input"]["size"]} floats
h     = max(x @ fc1.weight.T + fc1.bias, 0)              # {shapes["fc1.bias"][0]}
h     = max(h @ fc2.weight.T + fc2.bias, 0)              # {shapes["fc2.bias"][0]}
score = (h @ out.weight.T + out.bias)[0]                 # one float
```

No normalisation, no embedding, no residual, no recurrence: six arrays, {meta["dims"]["parameters"]}
parameters, two matrix products and a dot.

**One decision, many candidates.** The observation is the same for every candidate, so split the
first layer's weight in two at column {layout["size"]}: the observation half times the observation
is computed once and added to the candidate half times each candidate row. That is what the numpy
reference does and what the port should do, because a decision is 8 to 30 candidates.

## 4. parity.jsonl

{len(pairs)} pairs, one JSON object each, drawn from a fresh collection of the teacher playing
{len(collect_meta.get("runs", []))} runs ({collect_meta.get("ticks")} ticks, seeds
{collect_meta.get("seeds")} plus targeted {collect_meta.get("targeted_seeds")}), round-robin over
the skills so the rare reactions are represented as heavily as `WORK`: {len(skills_seen)} of the
{schema.N_SKILLS} skills occur, and the missing ones are the ones these scenarios never open
({missing_skills}). A port passes when every score matches to **1e-5** absolute.

| field | what |
| --- | --- |
| `i` | the record index, 0..{len(pairs) - 1} |
| `scenario`, `seed`, `decision`, `candidate` | where it came from; not input |
| `skill` | the candidate's skill, the one-hot's name; not input |
| `chosen` | whether the sim actually took this candidate; not input |
| `obs` | the {layout["size"]} observation floats, exactly as they go in |
| `cand` | the {schema.CAND_SIZE} candidate floats, exactly as they go in |
| `teacher` | the hand-written table's score, for context; **not** what parity checks |
| `score` | **the answer**: this model's score for that pair |

The field that is checked is `score`: run `obs ++ cand` through the pass above and compare. `obs`
was stored as float16 in the collection and widened to float32, which is exactly what the reference
scored, so there is nothing to round.
"""


def freeze_decisions(root: str, imitator_prefix: str, out_dir: str) -> dict:
    from dwarfsim.learn.scorer import LearnedScorer, resolve

    os.makedirs(out_dir, exist_ok=True)
    npz_src, json_src = resolve(imitator_prefix)
    shutil.copyfile(npz_src, os.path.join(out_dir, "imitator.npz"))
    shutil.copyfile(json_src, os.path.join(out_dir, "imitator.json"))
    meta_path = os.path.join(out_dir, "imitator.json")
    with open(meta_path, encoding="utf-8") as fh:
        meta = json.load(fh)
    # The copy is named imitator.npz whatever the training run called it.
    if meta["weights"].get("file") != "imitator.npz":
        meta["weights"]["source_file"] = meta["weights"]["file"]
        meta["weights"]["file"] = "imitator.npz"
        write_json(meta_path, meta)

    layout_sha = write_json(os.path.join(out_dir, "layout.json"), decisions_layout(meta))

    scorer = LearnedScorer.load(out_dir)
    pairs, collect_meta = decision_pairs(root)
    for pair in pairs:
        pair["score"] = f32(scorer.score(np.asarray(pair["obs"], dtype=np.float32),
                                         np.asarray(pair["cand"], dtype=np.float32)))
    write_lines(os.path.join(out_dir, "parity.jsonl"), pairs)
    write_text(os.path.join(out_dir, "README.md"),
               decisions_readme(meta, layout_sha, pairs, collect_meta))

    data = meta.get("data", {})
    train_path = os.path.join(root, "runs", "learn", str(data.get("file", "")))
    train_hash = sha256_file(train_path) if os.path.exists(train_path) else None
    held = (meta.get("metrics") or {}).get("held_out", {})
    return {
        "name": "decisions",
        "what": "the learned arbitrator: one observation and one candidate action in, one score out",
        "directory": "models/decisions",
        "source": os.path.relpath(npz_src, root).replace("\\", "/"),
        "schema_id": meta["schema_id"],
        "world_schema_id": meta["dwarfsim_schema_id"],
        "layout_sha256": layout_sha,
        "exported": meta.get("created"),
        "weights": {
            "file": "imitator.npz",
            "dtype": "float32",
            "bytes": os.path.getsize(os.path.join(out_dir, "imitator.npz")),
            "sha256": sha256_file(os.path.join(out_dir, "imitator.npz")),
            "arrays": [{"name": a["name"], "shape": meta["weights"]["shapes"][a["name"]]}
                       for a in meta["weights"]["arrays"]],
            "parameters": meta["dims"]["parameters"],
        },
        "training_data": {
            "path": ("runs/learn/" + str(data.get("file"))) if data.get("file") else None,
            "sha256": train_hash,
            "decisions": data.get("decisions"),
            "candidates": data.get("candidates"),
            "scenarios": data.get("scenarios"),
            "date": meta.get("created"),
        },
        "metrics": {
            "held_out_top1": held.get("top1"),
            "held_out_decisions": held.get("decisions"),
            "chance": held.get("chance"),
            "hard_top1": (held.get("hard") or {}).get("top1"),
            "score_mae": held.get("score_mae"),
            "torch_numpy_max_abs_diff": (meta.get("metrics") or {}).get("torch_numpy_max_abs_diff"),
        },
        "parity": {"file": "parity.jsonl", "records": len(pairs), "tolerance": 1e-5,
                   "collected": {k: collect_meta.get(k)
                                 for k in ("seeds", "ticks", "stride", "targeted_seeds",
                                           "hard_gap", "decisions", "candidates")}},
    }


# ---------------------------------------------------------------------------
# manifest and CLI
# ---------------------------------------------------------------------------


def write_manifest(out_root: str, models: list) -> str:
    manifest = {
        "created": _dt.datetime.now().replace(microsecond=0).isoformat(),
        "created_by": "tools/freeze.py",
        "repository": "Modular-Mob-AI, in mind/",
        "what": "the frozen, port-ready models: numpy on this side, a .mbw brain on the mod's. "
                "See docs/port.md for the mapping and models/*/README.md for the layouts.",
        "schema_id_is": "the sha256 of that model's layout.json, the way the mod's schema id is "
                        "the CRC32 of its schema.json; the short name is the model's own id",
        "parity": "tools/check_parity.py re-runs both parity.jsonl files against the numpy "
                  "implementations and fails on any drift beyond 1e-5",
        "models": models,
    }
    return write_json(os.path.join(out_root, "MANIFEST.json"), manifest)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", default=ROOT)
    ap.add_argument("--out", default=None, help="default: <root>/models")
    ap.add_argument("--clf", default=None, help="default: <root>/text/models/clf")
    ap.add_argument("--imitator", default=None, help="default: <root>/runs/learn/imitator")
    ap.add_argument("--only", choices=("interpreter", "decisions"), default=None)
    args = ap.parse_args(argv)

    root = os.path.abspath(args.root)
    out_root = args.out or os.path.join(root, "models")
    clf_dir = args.clf or os.path.join(root, "text", "models", "clf")
    imitator = args.imitator or os.path.join(root, "runs", "learn", "imitator")
    os.makedirs(out_root, exist_ok=True)

    existing = {}
    manifest_path = os.path.join(out_root, "MANIFEST.json")
    if args.only and os.path.exists(manifest_path):
        with open(manifest_path, encoding="utf-8") as fh:
            existing = {m["name"]: m for m in json.load(fh).get("models", [])}

    models = []
    for name, fn, dest in (("interpreter", lambda: freeze_interpreter(
                                root, clf_dir, os.path.join(out_root, "interpreter")), None),
                           ("decisions", lambda: freeze_decisions(
                                root, imitator, os.path.join(out_root, "decisions")), None)):
        if args.only and args.only != name:
            if name in existing:
                models.append(existing[name])
            continue
        entry = fn()
        models.append(entry)
        print("%-12s %s: %d parity records, %d parameters, %.1f KB of weights"
              % (entry["name"], entry["schema_id"], entry["parity"]["records"],
                 entry["weights"]["parameters"], entry["weights"]["bytes"] / 1024))

    write_manifest(out_root, models)
    print("MANIFEST.json: %d models" % len(models))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
