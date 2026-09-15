# Port: the two models into the mod

The brief a Java developer starts from. Both models are frozen in [`shared/models/`](../../shared/models); each
directory's `README.md` is the full specification of that model and needs no Python. This file is
only the mapping onto `Modular-Mob-AI`: which class each becomes, what it needs from the mod, and
what it has to pass before it is believed.

Nothing in the mod (`../mod/`) is changed by reading this. The one thing that *has* to change there is
[the seam in `BrainState`](#the-one-seam-brainstate).

| Model | in `shared/models/` | In | Out | Runs |
| --- | --- | --- | --- | --- |
| interpreter | `models/interpreter` | one chat line, plus the previous line's intent | 4 enum heads, 3 floats, names | once per message |
| decisions | `models/decisions` | 69 observation floats + 64 candidate floats | one score | every 10 ticks, once per candidate |

Neither has recurrence, so neither needs a hidden vector of its own. Both are float32 throughout,
both store Linears as PyTorch does (`weight` is `(out, in)`, row major, `y = x @ weight.T + bias`),
which is what `Topology` already assumes, so nothing transposes anything.

## The two `Brain` implementations

The combat network is a `NeuralBrain` over a batch of agents: observations in, actions out, memory
carried. Neither of these two is that shape, and pretending otherwise costs more than it buys.

**`decisions` is a `Brain`.** It fits `Brain#act` as it stands: a batch of agents, one observation
each, an action out. What differs is that its output is not a control vector but a choice among
*candidates the skills proposed*, so the batch is ragged — agent *a* has 11 candidates this tick and
agent *b* has 23. Score them all in one pass by stacking the candidate rows of the whole batch into
one `(sum of candidates, 64)` matrix, adding each agent's observation product to its own slice, and
splitting the result back. `hiddenSize()` is 0. It drives an agent every tenth tick, not every tick;
the skills themselves run per tick and are hand-written.

**`interpreter` is not a `Brain`** and should not be forced into one. It is not per agent and not per
tick: it runs once per chat message, on the server thread, and its answer is handed to whichever
agent heard it. Make it a plain loadable `NeuralNet`-style object in `brain/nn/` with a static
`classify(String) -> Utterance`, loaded once, shared, stateless, thread-confined to the server
thread. It reads a `.mbw` like a brain does and is checked like one; it just is not batched by
`AgentDriver`, because there is no agent in the loop when a player presses enter.

## The `Species`-style layouts

A `Species` today bundles a body's observation layout, its action layout, its encoder and its
`describeJson()`, whose CRC32 is the schema id stamped into every weight file. Both models want the
same discipline and only half the machinery — neither has a body, a mob, or controls — so declare
each as a **schema without a species**: an `ObservationSchema`-shaped description with named blocks
in offset order, a `describeJson()`, and a `schemaId()` over those bytes. If `Species` cannot be
narrowed to that without dragging `AgentMob` in, a sibling interface in `brain/schema/` with the
same two methods is the cheaper answer; what must not happen is a layout written down twice.

**decisions.** Blocks in offset order, straight out of `models/decisions/layout.json`:
`mind` (44: emotions 4, needs 4, traits 6, health 1, inventory 5, focus 4 slots x 6), `place` (6
one-hot), `crowd`, `monster`, `monster_hp`, `under_attack`, `hit_age`, `alive_fraction`, `clock`,
`goals` (6), `obligations` (2), `memory` (2), `is_chief`, `chief_here` — 69 floats. The action side
is not a head table: it is the **candidate** block, 22 skill one-hot + 42 term values = 64 floats,
and the skill and term name lists are as much part of the layout as the observation blocks are. The
four relationship slots are the same idea as the combat body's enemy slots (fixed slots, filled by
salience, padded with zeros) and the same weakness applies — if they ever grow past four, attend them
rather than giving each its own columns, exactly as `slotHeads` did there.

**interpreter.** There is no observation in the mod's sense. Its layout is the tokenizer rules, the
five feature-string forms, the FNV-1a constants, the 2^16 bucket count, the 20-column side vector's
frozen order, and the label lists. That whole description is `models/interpreter/layout.json`
already; port it as the schema's `describeJson()` body so that the schema id means the same thing on
both sides. Its label lists are the action layout's analogue: they say what an output index *is*.

## The `.mbw` segment order

`Topology` is one flat float array in the order the pass applies the parameters, matrices row major
`[out][in]`, read straight through without seeking. Both models keep that convention. Neither is a
GRU, so both want a topology with no `gruWih`/`gruWhh`/`logStd` segments and no normaliser — the
inputs are already scaled and there are no statistics to travel. Add a **kind** word to the header
rather than overloading the six dimensions: version 4 with `kind = 0` meaning today's recurrent
actor, `1` a plain MLP scorer, `2` the embedding-bag classifier. Versions 1 to 3 read as `kind = 0`,
which is every network ever published.

**decisions** (`kind = 1`, dims `inDim = 133`, `h1 = 64`, `h2 = 64`, `outDim = 1`):

```
fc1W[64 x 133]  fc1B[64]  fc2W[64 x 64]  fc2B[64]  outW[1 x 64]  outB[1]
```

12,801 floats, 51 KB. Identical to `models/decisions/imitator.npz`'s array order, which is
deliberate: the export is already in `.mbw` order and the converter is a header plus a concatenation.

**interpreter** (`kind = 2`, dims `buckets = 65536`, `dim = 32`, `side = 20`, `hidden = 64`, plus the
five head widths 13, 12, 4, 3, 3):

```
emb[65536 x 32]
fc1W[64 x 52]  fc1B[64]
headIntentW[13 x 64]     headIntentB[13]
headTopicW[12 x 64]      headTopicB[12]
headAddressedW[4 x 64]   headAddressedB[4]
headSincerityW[3 x 64]   headSincerityB[3]
headFloatsW[3 x 64]      headFloatsB[3]
```

2,102,819 floats, 8.0 MB — the embedding is all of it, and it is read one row at a time, so keep it
as the flat array and index it, never as rows of objects. The order is `weights.npz`'s order, and
`models/interpreter/README.md` §5 is the pass. The head widths are layout, not topology: they come
from the schema, and the topology hash covers the dims the way `Topology#hash` does today.

Keep the existing refusals exactly as they are: wrong magic, version, schema id, a topology hash that
disagrees with its own dimensions, a parameter count or file length that disagrees with the topology,
a non-finite parameter. A model handed the wrong layout must fail at load, not play badly.

## The one seam: `BrainState`

`BrainState.use(Brain)` clears the hidden vector on **any** brain change, because today a switch only
happens between a scripted teacher and a network that never share an agent. With an arbitrator
choosing skills, an agent switches brains constantly — fight, flee, work, fight again — and each
switch would wipe the combat GRU's 128 floats of memory mid-fight. That is the one thing in the mod
that has to open.

The change: `BrainState` keeps **one hidden vector per brain**, not one per agent — a small
`Map<Brain, float[]>` or a parallel array indexed by a brain's slot, sized by `hiddenSize()`, cleared
on `reset()` and never on `use()`. `use()` then only says which brain is current. Memory of a brain an
agent has not run for a long time is worth dropping, but drop it on a timer, not on the switch.
Nothing else in the mod reads `hidden` directly; the driver gathers and scatters it, so the seam is
narrow.

`decisions` has no memory, so it needs none of this. It is the *reason* for it.

## The chat hook

The mod has no chat hook today. It needs one per loader — Fabric's `ServerMessageEvents.CHAT_MESSAGE`
and NeoForge's `ServerChatEvent` — behind the existing `mod/common` + `mod/fabric` + `mod/neoforge`
split, with everything below living in `common`. On the server thread:

1. take the raw message text, unmodified — no stripping, no lowercasing, no truncation (the model
   truncates nothing and is linear in length; only a message far past ~25 words is worth splitting,
   and then the **last sentence** is the one that counts, never the most aggressive);
2. `interpreter.classify(text, prevIntent)` where `prevIntent` is what that agent last said to this
   player, or `unknown`;
3. address it: the nearest agent within hearing that is looking at the speaker. `addressed` from the
   model then gates the reaction — a `THIRD`-addressed or `NONE`-addressed line is overheard, not
   said to you, and a reported threat ("I told him I'd wreck him") lands as small talk aimed at
   nobody;
4. hand the result to the same `hear()` path the sim uses, which applies the hand-written emotional
   deltas; the arbitrator sees the consequences on its next decision, ten ticks later at worst.

The player is a relationship row like any other (`PLAYER_ID` in `dwarfsim/schema.py`), starting
neutral. Classification is microseconds and off the tick loop, but it is still I/O-shaped work on the
server thread: if a message ever costs more than a tick's budget, queue it, do not thread it.

## Parity, which the port must pass

The mod already has this procedure for the combat network (`scripts\parity.ps1`, Java against
PyTorch, ~1e-6). Both models ship their own frozen version of it, so the Java side never has to run
Python:

* `models/interpreter/parity.jsonl` — 200 fixed lines (hard cases, generated data, corpus, and eight
  edge cases: empty, one word, forty words, all caps, punctuation only, unicode quotes, a pool name
  first, a pool name last) with, for each, the bucket list, the side vector, every head's pre-softmax
  logits and the three activated floats;
* `models/decisions/parity.jsonl` — 200 fixed (observation, candidate) pairs, round-robin over the
  skills so the rare reactions weigh as much as `WORK`, each with its exact score.

The port passes when, reading the `.mbw` converted from the frozen `.npz`, it reproduces every number
to **1e-5** absolute. Check in this order, because each stage is downstream of the last:

1. **buckets exactly** — an integer mismatch is a tokenizer or hash bug and nothing further will
   line up. `String.toLowerCase()` (locale) and UTF-16 char iteration are the two ways to get this
   wrong;
2. **side vector** — its scalar columns are integer counts as floats, so a difference there is a
   counting bug, not rounding;
3. **logits, floats, scores** — arithmetic, where 1e-5 is the only tolerance that is allowed to be
   non-zero.

`tools/check_parity.py` is the same check on the Python side and runs in `tests/test_freeze.py`, so
the answer sheets cannot drift from the models they describe. A retrain regenerates all of it with
`python -m tools.freeze`, and then both sides re-check.

## What is not in scope

The sim's hand-written half — the emotion deltas, needs, relationships, episodic memory, the skills
that propose candidates — is rules, not weights, and ports as ordinary Java from
[`docs/design.md`](design.md). Port the [core ring first](plan.md#core-and-extended-for-the-port):
mind state, relationships, telling entities apart, episodic memory, the graded reactions. Gossip,
obligations, goals and the chief are columns in the observation and skills in the candidate list;
until they exist in the mod, those columns are zero and those candidates are never proposed, which
the model handles the way it handles any agent that has none of them.
