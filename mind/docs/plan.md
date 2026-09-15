# Plan: a mind for the mob

The combat network in the rest of this repository (the mod, one level up) is one skill. This subproject builds
everything around it, without Minecraft, and only then ports it in.

## The one design rule

**Rules are the body, learning is the choice.** The combat mod already works this way: vanilla applies the controls,
the network only picks them. Here the same split is: hand-written emotional and social dynamics (what an insult does to
anger and trust) and learned or scored choices on top (what to do about it). Emotions cannot be learned from nothing, and
hand-written dynamics are readable, tunable and cheap. The feud is still emergent: nothing says "attack after five
insults", the arbitrator just sees anger and hatred high and the attack candidate wins.

## Components

| Piece | Where | Status |
| --- | --- | --- |
| Abstract world, mind state, skills, utility arbitrator, decision log | `dwarfsim/` | building |
| Single-file HTML viewer: story, timeline, traces, relationship heatmap, decision inspector | `dwarfsim/viewer.py` | building |
| Dialogue label schema | `text/SCHEMA.md` | done |
| Synthetic data prompt for domain vocabulary | `text/PROMPT_generate_dialogue.md` | done |
| Real-corpus pipeline: fetch, filter, chunk, merge, optional API labeling | `text/*.py` | building |
| Tiny text classifier | `text/classifier/`, `text/classify.py` | built, trained on the partial labels |
| Learned arbitrator trained in the sim | `dwarfsim/learn/` | both stages built: imitation (97.9% of the table's choices) then PPO on a named reward table, with three targeted scenarios and a paired evaluation |
| Port: mind state in NBT, per-skill hidden vectors, arbitrator and classifier as `.mbw` brains | the mod, `../mod/` | last |

## The classifier

fastText-style: hashed word unigrams, bigrams and character trigrams, plus the first and the last word of the line
tagged with their position (`^first=`, `$last=`), all FNV-1a into the same 2^16 buckets, an embedding bag of 32 floats
summed. Onto that sum is concatenated a 20-float **side vector** the bag cannot see: log1p word count, log1p character
count, the share of capitals among letters in the raw line before lowercasing, the counts of `!` and `?`, whether the
line contains `..`, and a 14-way one-hot for the previous line's intent (the 13 intents plus `unknown`). Those 52
floats go into a 64-wide MLP with five heads: intent (13-way), topic (12-way), addressed (4-way), sincerity (3-way,
optional and masked when absent), and three regressions for aggression, valence, urgency. Names are found by exact
matching against the pool, not predicted.

The side vector is where the things the bag throws away come back. Lowercasing is what makes the hash portable, so
`GET OUT OF MY FORGE` and `get out of my forge` hash identically; the caps column is what tells them apart, and today
it is worth about +0.03 aggression on a shouted line — real, and smaller than it sounds, because the corpus labels
shouting mildly. The position tags do the same job for word order: `Brokk, get out` and `get out, Brokk` have the same
unigrams and the same trigrams, and only `^first=`/`$last=` separate them at all; they too move the floats by a few
hundredths, and their real earnings are on intent, which went from 0.663 to 0.728 when the two features and the side
vector went in together.

The `prev_intent` one-hot is the one part that is plumbing, not signal. Every row of today's corpus is `unknown`,
because the corpus has no conversations in it, so the thirteen intent columns have never seen a gradient: passing
`--prev-intent INSULT` today does shift the output (it moved `get out` from 0.12 to 0.19 aggression) but that shift is
an untrained column, not a learned reaction, and nothing should be read into it. It is wired end to end (featurizer,
model, export, numpy inference, `classify.py --prev-intent INSULT`) so that the day the corpus has turns in it, the
only work left is labelling, not plumbing.

Why this and not a char-GRU: inference is a few dozen row lookups, one concatenation and two small matrix products,
microseconds in Java; the hash is ten lines and can be made bit-identical in Java, so the parity check the mod already
has applies; and the whole thing fits the mod's flat `.mbw` format as one more weight file with its own schema id
(`dialogue-clf-v2`; v1 was the model before the side vector, and its `fc1` is the wrong shape to load). The exact
layout and order of the side vector is written into `model.json` under `side_features`, because it is the one part of
the model a Java port can get wrong silently.

The classifier has no input limit and truncates nothing: it sums the features of every word in the line, so cost is
linear in length and quality degrades past about 25 words, the longest it was trained on. In the game, classify the whole
message and trust it: a reported story ("I told him I'd wreck him") comes out as small talk aimed at nobody, and the
addressee field gates any reaction. Only a message far past that length gets split, and then the last sentence is
the one that counts, never the most aggressive.

Free-form generation stays out of the tick loop: templates filled from mind state first, a local llama.cpp process
over a socket later if it earns its place.

## The arbitrator

v0 is a utility function whose terms are named and logged per candidate. That is the viewer's "why" panel and it is
also the training target, and both halves of that are now built: the same observation vector and candidate features
feed a tiny MLP scorer trained to imitate the table, and then PPO moves it against a named reward table (needs, staying
alive, wealth, social standing, promises kept and broken, unprovoked violence). It works, in the narrow sense that it
beats both the table and the imitator on that reward on held-out seeds, with far fewer deaths and almost no unprovoked
violence -- and it confirms the warning: **social behaviour needs reward shaping, and that is still the open research
question**. What PPO actually found was that praising everybody and keeping every promise is the cheapest way to move
the social terms, so the settlement got kinder and duller, and the graded reactions had less and less to answer. See
[design.md, "The reward stage"](design.md#the-reward-stage) for the tables.

In the game the arbitrator runs every ten ticks, not every tick; only the skills run per tick.

## Core and extended, for the port

Nothing built in the sim is being removed: it is done, tested, and costs nothing to keep. But the port to the mod
should go in two rings, and the sim already has the switches for it (the chief is opt-in; gossip, goals and
obligations sit in their own modules).

- **Core, port first:** mind state (emotions, needs, traits), relationships with trust and respect, telling
  entities apart, episodic memory with grudge and gratitude, and the graded reactions to provocation. This is
  what makes "insult" lead to anything other than "attack".
- **Extended, port later:** gossip and reputation, obligations and bargaining, persistent goals, the chief. These
  are the parts that need a settlement's worth of agents to matter.

## Port to the mod, when the time comes

Both trained models are frozen, port-ready, in [`models/`](../models): weights, layout, a 200-line
parity file each and a specification a Java developer can implement without reading any Python.
[docs/port.md](port.md) is the brief; `python -m tools.freeze` regenerates all of it after a retrain
and `python -m tools.check_parity` proves it has not drifted.

- `MindState` becomes entity NBT plus a flat vector; the event table becomes hooks on damage, chat, item pickup.
- `BrainState` gains one hidden vector per skill so switching skills does not wipe the combat GRU's memory. This is the
  one seam in the mod that has to open; `BrainState.use()` currently resets memory on any switch.
- The arbitrator and the classifier are two new `Brain` implementations with their own `Species`-style layouts.
- Player chat reaches the classifier through the server chat event, addressed to the nearest agent looking at the
  speaker, and the parsed result goes through the same `hear()` path the sim uses.

## Working rules

- Nothing here needs Minecraft or Gradle. Everything runs from `python -m ...` in seconds.
- The machine is shared with a live training run: single process, tiny models, `torch.set_num_threads(1)`.
- The main assistant thread plans; Opus subagents build anything bigger than a small edit.
