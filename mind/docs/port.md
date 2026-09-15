# Port: the two models into the mod

The brief a Java developer starts from. Both models are frozen in [`shared/models/`](../../shared/models); each
directory's `README.md` is the full specification of that model and needs no Python. This file is
only the mapping onto `Modular-Mob-AI`: which class each becomes, what it needs from the mod, and
what it has to pass before it is believed.

**Stage A has landed.** The two loaders, their parity and their timings are in the mod; nothing behavioural is.
[What stage A settled](#what-stage-a-settled) is at the bottom, with the plan for
[stage B](#stage-b-the-mind-in-nbt) and [stage C](#stage-c-the-arbitrator-in-the-loop) and the Java files each touches.
Everything above that is the brief as it was written, corrected where stage A found it wrong.

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

*Settled in stage A; this is what was built.* The **kind** word is **appended**, at offset 68, and
the header is 72 bytes — nothing before it moves, so every file ever published reads byte for byte as
it did and versions 1 to 3 read as `kind = 0`. The ten shape words stay where they are and the kind
says what they mean; the stored hash is a CRC32 of only the words that kind uses, exactly as
`Topology#hash` already covered six words or ten. `WeightFile.read` is the actor and refuses another
kind by name; `WeightFile.readMind(path, kind)` is the other two and **asks** for the kind it wants,
so nothing is ever decided by what a file claims to be.

`Topology` is one flat float array in the order the pass applies the parameters, matrices row major
`[out][in]`, read straight through without seeking. Both models keep that convention. Neither is a
GRU, so both have a shape with no `gruWih`/`gruWhh`/`logStd` segments and no normaliser — the
inputs are already scaled and there are no statistics to travel.

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

**The schema id** is a u32 in the header and a sha256 here, so the file carries the **first four
bytes of `layout.json`'s sha256, read big endian**: `09a397c9` for the interpreter and `b7dba1c4`
for the decisions model, which are prefixes of the ids in `MANIFEST.json`, so printing one finds the
other. Each Java net holds its id as a constant, and `MindTool` holds all three against each other —
the file's, the manifest's layout hash, and the build's constant — so a layout that moves is a
refusal with three hexadecimal numbers in it and not a quiet misreading.

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

The Java half of it is `combat\scripts\parity.ps1`, whose last arm is `:fabric:mindParity`. **It runs
no Python**, which is the whole value of freezing the answers: it reads the same two `parity.jsonl`
files a person would read, and the two sides can therefore be checked in different weeks by different
people. Measured on 2026-09-15, first run: buckets exact on all 200 records, side vector 0.000e+00,
logits 1.907e-06, floats 1.192e-07, scores 3.815e-06 — the same order as the combat network's own
parity, and an order of magnitude inside the 1e-5 the files promise.

## What is not in scope

The sim's hand-written half — the emotion deltas, needs, relationships, episodic memory, the skills
that propose candidates — is rules, not weights, and ports as ordinary Java from
[`docs/design.md`](design.md). Port the [core ring first](plan.md#core-and-extended-for-the-port):
mind state, relationships, telling entities apart, episodic memory, the graded reactions. Gossip,
obligations, goals and the chief are columns in the observation and skills in the candidate list;
until they exist in the mod, those columns are zero and those candidates are never proposed, which
the model handles the way it handles any agent that has none of them.

## What stage A settled

Stage A is the two loaders and their parity, and nothing behavioural. It is done, and these are the
decisions it made that the rest of the port now rests on.

| Decision | What was built |
| --- | --- |
| the kind word is **appended** | offset 68, header 72 bytes, version 4. Every published `.mbw` reads byte for byte as it did, and the existing parity check proved it on all three bodies, plain and attended |
| the ten shape words stay, the kind says what they mean | kind 1 uses four of them, kind 2 uses four plus its head widths, zero terminated. The hash covers only the words the kind uses, as `Topology#hash` already did for six against ten |
| one shape class per kind | `ScorerShape` and `ClassifierShape` beside `Topology`: each owns its own segment offsets, parameter count and hash, so the reader and the pass cannot disagree about where a segment starts |
| the reader asks for the kind | `WeightFile.read` is the actor and refuses anything else by name; `WeightFile.readMind(path, kind)` returns a `MindWeights` and refuses a kind it was not asked for |
| the schema id is the layout sha's first four bytes | printed as `09a397c9` and `b7dba1c4`; `MindTool` holds the file, the manifest and the build's constant against each other |
| the converter lives in `mind/` | `tools/mbw.py`, runnable on its own against whatever is frozen (`python -m tools.mbw`) and called by `tools/freeze.py` at the end of each half, so the `.npz` and the `.mbw` come out of the same arrays in the same breath. `MANIFEST.json` carries the sha256 of both, and `tools/check_parity.py` fails on a stale one |
| no `Species` for either model | the layouts are `layout.json`, whose sha256 is already the schema id. The label lists are Java constants on `InterpreterNet`, as an action head's names are, and the parity check holds them against the shape the file declares. A schema class with no body, no mob and no controls would have been a wrapper round a hash |
| the interpreter is not a `Brain` | as this file said. It is a plain object in `brain/nn/`, thread confined to the server thread, allocating nothing per message after warm-up |
| the scorer's shape is `score(float[] observation, float[] candidate)` | plus `observe` and `scoreObserved`, which are the same arithmetic with one decision's observation product shared. Both are checked against the answer sheet, because a saving that is not the same number is a bug |

**What it cost, on this machine with a training run beside it.** The interpreter 4.7 to 11.2 µs per
message over five runs — the spread is the machine, not the model — and the decisions model 3.6 to
4.1 µs per candidate from cold, 2.4 to 2.6 µs per further candidate of one decision. A thirty
candidate decision is therefore about 75 µs, taken once per agent per ten ticks, against the 11.2 µs
the combat network costs per agent *every* tick. Neither is near a tick's budget, and the interpreter
is off the tick loop entirely.

**One check the answer sheet could not give.** Its longest line is forty words and the interpreter's
working buffers start wider than that, so nothing in the 200 records ever outgrows one — and the
first version of the growth path lost a token every time a buffer doubled, passed all 200 records,
and would have failed the first time a player typed a long sentence. `MindTool` now reads a line
longer than any fixture twice on one net, once while the buffers grow and once after, and that check
was confirmed to fail on the bug before the bug was fixed. Worth remembering for stage B: a frozen
fixture only checks what somebody thought to freeze.

**Simplified deliberately, and worth knowing.** The interpreter does not extract `names`: the pool is
in `model.json` rather than in the weight file, nothing in `parity.jsonl` checks it, and it is an
exact substring match over a 24 name pool that stage C can add in ten lines. Nothing else in either
model is missing.

Files stage A added, all under `combat/mod/common/src/`:
`main/.../brain/nn/ScorerShape.java`, `ClassifierShape.java`, `MindWeights.java`, `ScorerNet.java`,
`InterpreterNet.java`; `gametest/.../tools/MindTool.java` and `Json.java`. It changed
`brain/nn/WeightFile.java`, `buildSrc/.../multiloader-loader.gradle` (`javaTool`, `mindParity`) and
`scripts/parity.ps1`.

## Stage B: the mind in NBT

The hand-written half, with no arbitrator yet: an agent that *has* a mind and whose mind moves, read
from the world and saved with it, with the graded reactions firing. Nothing chooses a skill; the
combat brain still drives. That is deliberate — it makes every part of stage B observable on its own,
through `/mmai` and a debug readout, before anything acts on it.

1. **`MindState`** — a new `entity/agent/mind/MindState.java`: the 44 float mind vector as named
   fields (four emotions, four needs, six traits, health, five inventory, four relationship slots of
   six), a `decay(ticks)` and an `observation(float[] into)` that writes the 69 columns
   `models/decisions/layout.json` describes, in offset order. The columns for goals, obligations,
   gossip and the chief stay zero, exactly as this file says they may.
2. **NBT** — `AgentMob#addAdditionalSaveData` and `#readAdditionalSaveData` already carry the brain
   name, the loadout and the hotbar; the mind goes beside them under one compound. A mind that fails
   to load is a fresh mind, never a half loaded one.
3. **The event table** — `mind/Events.java`: what happens to an agent turned into deltas on that
   state, the hand-written half of [`design.md`](design.md). It is fed from the places the mod
   already knows things: `AgentMob#actuallyHurt` for being struck, `Episode` for a fight ending,
   `EnemySlots` for who is in view.
4. **Relationships and telling entities apart** — a small `mind/Relationships.java` keyed by entity
   UUID, four slots filled by salience and padded with zeros, which is the same idea as `EnemySlots`
   and should borrow its ordering discipline rather than inventing a second one. The player is a row
   like any other and starts neutral.
5. **Episodic memory** — `mind/Memory.java`, a ring of what happened with a salience each, which is
   the two `memory` columns of the observation.
6. **The `BrainState` seam** — the one thing in the mod that has to open, and stage B is where,
   because from here an agent's brain can change between ticks. `BrainState.use` clears the hidden
   vector on any brain change; it must instead keep **one hidden vector per brain**, cleared on
   `reset()` and dropped on a timer, with `use()` only saying which brain is current. Nothing outside
   `BrainState` and `AgentDriver` reads `hidden`, so the seam is narrow. Files:
   `brain/BrainState.java`, `brain/AgentDriver.java`, `brain/AgentBatch.java`.

What proves it: a game test class in `gametest/tests` — one agent, a struck event, a saved and
reloaded world, and the 69 columns read back — plus `mind/`'s own suite, which already holds the same
rules in Python and is the reference the Java is being written from.

## Stage C: the arbitrator in the loop

1. **The chat hook**, one per loader behind the existing split: `fabric/.../ModularMobAiMod.java`
   registers `ServerMessageEvents.CHAT_MESSAGE`, `neoforge/.../ModularMobAiMod.java` registers
   `ServerChatEvent`, and both call one `common` entry point — `mind/Hearing.java` — which holds the
   single shared `InterpreterNet`, classifies the raw line, addresses it to the nearest agent in
   hearing that is looking at the speaker, gates the reaction on the `addressed` head, and hands the
   result to the same `hear()` path stage B built. It is microseconds and off the tick loop; if a
   message ever costs more than a tick's budget, queue it, do not thread it.
2. **The arbitrator brain** — `brain/ArbitratorBrain.java`, a `Brain` with `hiddenSize() == 0` that
   runs every tenth tick. Its skills are hand written and propose candidates per tick; it stacks the
   candidate rows of the whole batch into one matrix, shares each agent's observation product across
   its own slice, and splits the result back. `ScorerNet.observe` and `scoreObserved` are already that
   shape. It then either takes the highest or samples at T = 0.25, as the sim does.
3. **Switching between brains** — `brain/Brains.java` and the driver: an agent under the arbitrator
   runs the combat brain while it is fighting and something else while it is not, which is exactly
   what stage B's `BrainState` change is for. This is the first point at which that change is load
   bearing rather than merely correct.
4. **The first in-game test** — a `PlayGameTest` case: an agent, a player who says something to it,
   and a reaction that follows from the interpreter's answer rather than from a script. That is the
   whole of what the port is for, and it is one test long.
