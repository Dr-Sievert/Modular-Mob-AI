# Adding a species: giving another body a brain

A **species** is one body's schema: what an agent of that body sees, what it can be asked to do, and how a network's
outputs turn into that. There are two so far — the **humanoid**, the player-shaped agent every trained network drives, and
the **beast**, a body with no hands that exists to prove a second body needs nothing special. This is how to write a third.

You do not need to have seen the rest of this repository. Everything you need is in one package,
`mod/common/src/main/java/net/sievert/modularmobai/brain/schema/`, plus one entity class and two lines per loader.

## Why a schema per body, rather than one masked brain

A body with no hands has no hotbar to look at, no slot to choose, and no use buttons to press. You could keep one giant
layout and mask those inputs off for bodies that lack them, and it was considered; a schema per body was chosen instead,
because a masked input is still an input the network has to learn to ignore, a masked control is still a control someone
can wire up by mistake, and neither shows up as a failure. A body that cannot press a button has no such button.

The price is that a network belongs to one body. That is enforced, not advised, and in four places — see
[Refusing the wrong body](#refusing-the-wrong-body).

## What a body has to provide

Three files, and one line in a fourth.

### 1. The layout: `<Name>Schema.java`

Constants, and nothing else: the width of each block, where each block starts, the index of every field inside its own
block, the index of every control, and the head table. Copy `BeastSchema.java`; it is the shorter of the two and is
commented for exactly this.

Two blocks are **shared by every body** and you should use them rather than writing your own: the ten enemy slots and the
terrain grid. Their shapes live in `ObservationSchema` (`ENEMY_SLOTS`, `ENEMY_STRIDE`, `ENEMY_SIZE`, `TERRAIN_X/Y/Z`,
`TERRAIN_SIZE`) and their meanings belong to the world rather than to the body looking at it: an opponent looks the same
whoever is looking, ground is ground whoever is standing on it, and two bodies disagreeing about what a hazard is would be a
bug nobody could see. What your body chooses is *whether* it has them and *where they sit* in its row.

The blocks have to start at zero, follow one another with no gap, and add up to your `OBS_DIM`. That is checked when the
species loads, by name, so getting it wrong stops the build rather than the fight.

The head table says how the network's outputs become controls. Three kinds:

| Kind | What it is | Outputs | Action values |
| --- | --- | --- | --- |
| `continuous(name, size)` | a number, sampled around `tanh(logit)` | `size` | `size` |
| `binary(name, size)` | held or not, from `sigmoid(logit)` | `size` | `size` |
| `categorical(name, size, mask)` | one of `size` choices, from a masked softmax | `size` | **1**, the index |

`mask` is an observation offset holding one value per choice; a choice is allowed only while its value is above zero, which
is how the humanoid stops a slot it is carrying nothing in from being chosen. Pass `-1` for no mask. A body can have no
categorical head at all — the beast has none — and nothing downstream minds.

Add the static block at the bottom of `BeastSchema` to your own. It is three lines and it catches the one mistake that is
otherwise silent: a head table that has drifted from the action indices next to it.

### 2. The encoder: `<Name>Observation.java`

One method per block your body has, and a `write` that calls them in order. `BeastObservation.java` is the whole of a
second body's encoder and is about seventy lines, because the two shared blocks are written for you:

```java
static void write(AgentMob agent, EnemySlots slots, float[] out, int base) {

    java.util.Arrays.fill(out, base, base + BeastSchema.OBS_DIM, 0.0F);

    float sin = AgentObservation.yawSin(agent);
    float cos = AgentObservation.yawCos(agent);

    writeSelf(agent, slots, out, base + BeastSchema.SELF_OFFSET, sin, cos);
    writeEcho(agent.executed(), out, base + BeastSchema.ECHO_OFFSET);

    AgentObservation.writeEnemies(agent, slots, out, base + BeastSchema.ENEMY_OFFSET, sin, cos);
    AgentObservation.writeTerrain(agent, out, base + BeastSchema.TERRAIN_OFFSET);
}
```

Fill the row from zero first. A field your body does not have then reads as zero rather than as whatever the last agent in
that row left behind, which matters because the buffer is reused every tick.

Everything about other entities goes in the **agent's own frame**, so that an opponent two blocks ahead reads the same
whichever way the fight is facing; `AgentObservation.forward` and `.right` do that conversion. The terrain grid is the one
exception and stays aligned to the world, with the body's own facing written into its self block (`AIM_SIN`, `AIM_COS`) so a
network can relate the two. Keep every number roughly between minus one and one.

### 3. The descriptor: `<Name>.java`

Implements `Species`, and is mostly one-line methods. Copy `Beast.java`. The two that need thought:

- `observe` calls your encoder.
- `act` writes **only the controls your body has**, and holds the rest at rest. The beast's `act` sets `sneak`, `use` and
  `useOffhand` to false explicitly, so that a control the species does not have cannot come on because some other body left
  it set in a reused buffer.

The constructor lists your blocks and hands them to `Species.describe`, which builds the JSON the training side reads and
checks that the blocks add up.

### 4. One line in `Species.java`

```java
Species BEAST = new Beast();

List<Species> ALL = List.of(HUMANOID, BEAST);
```

Adding it to `ALL` is what makes the parity check go round your body, what makes a weight file for it loadable, and what
makes its name work in `-Pspecies`. Nothing keeps a separate list anywhere else.

## The entity

A species is a schema; a body is an entity. Subclass `AgentMob` and override one method:

```java
public class BeastMob extends AgentMob {

    @Override
    public Species species() {

        return Species.BEAST;
    }
}
```

That is genuinely all of it — the controls, the echo of what the body actually did, the driver, the memory and the arena
plumbing are the same for every body. Then register it, which is four small edits:

- `ModEntities`: an id, a builder, a setter and a getter. Copy the beast's.
- `mod/fabric/.../ModularMobAiMod.java`: register the type, and its attributes.
- `mod/fabric/.../ModularMobAiClient.java`: a renderer. Borrowing `AgentMobRenderer` is fine if you do not want a model.
- `mod/neoforge/.../ModularMobAiMod.java`: the same three things in NeoForge's idiom.

## What the trainer needs told

Nothing, in code. The training side never writes a layout down: the game writes its own and the trainer parses it, which is
the only arrangement where the two halves cannot drift apart.

What it needs told is **which body a run is for**:

```
scripts\train.ps1 -Run beasts -Species beast
scripts\parity.ps1                                  every body
mod\gradlew.bat -p mod :fabric:brainParity -Pspecies=beast     one of them
```

`-Pspecies` is `humanoid` unless given. It decides which layout `BrainTool schema` writes into the run's `schema.json`, and
the trainer builds its network from that file. The blocks arrive as a list, so a body with no hotbar simply has no hotbar
block and the trainer does not go looking for one; anything that wants a particular block asks `schema.block("name")` and
copes with `None`, or `schema.require("name")` to fail clearly.

## What the parity check wants

Nothing, and that is the point: `scripts\parity.ps1` goes round **every** body in `Species.ALL` by itself, because the build
asks the game what bodies there are rather than keeping its own list. Adding your species to `ALL` is what enrols it.

A body nobody has trained yet is checked on a freshly built random network, which proves just as much as a trained one
would: what is being checked is that the Java forward pass and PyTorch's agree about a layout, and the weights only have to
be the same weights on both sides. So you get a real check on a brand new body before a single fight has been run.

```
scripts\parity.ps1
  humanoid: schema 1dffdc67, observation 744 wide, 11 actions from 19 logits
  parity over 67 rows of 744 -> 256 -> GRU 128 -> 128 -> 19, a humanoid
    logits 2.325e-06   hidden state 4.917e-06   log probability 3.815e-06
  parity ok
  beast: schema 686280b1, observation 612 wide, 7 actions from 7 logits
  parity over 67 rows of 612 -> 256 -> GRU 128 -> 128 -> 7, a beast
    logits 1.460e-06   hidden state 6.810e-06   log probability 1.431e-06
  parity ok
```

If your body's figures come out at a tenth rather than a millionth, the two sides disagree about the layout: nearly always
a block offset, a head that reads the wrong outputs, or an encoder writing a field at the wrong index.

## Refusing the wrong body

A network only fits the body its layout was written for, and "this is a beast's brain, the mob is a humanoid" is the mistake
that will actually be made. It is refused in four places, and every message names **both** sides:

| Where | When |
| --- | --- |
| `NeuralBrain.check` | a weight file is made into a brain, from its schema id. An id no body here has is refused too, listing the bodies there are |
| `AgentDriver` | a brain is about to drive a body it was not made for, naming the brain's own name as well |
| `AgentBatch` | the same, asserted where the rows are actually filled in, since every row of a step is one width |
| `DemonstrationBrain` | a teacher of another body: it could not label the fights it is asked about |

The schema id is a CRC32 of the layout JSON, **species name included**, so two bodies can never share one even if they
happen to be the same width. It is stamped into every weight file and every rollout shard.

## The whole checklist

1. `<Name>Schema.java` — the constants and the head table.
2. `<Name>Observation.java` — one writer per block it has; the shared two for enemies and terrain.
3. `<Name>.java` — the descriptor; add it to `Species.ALL`.
4. `<Name>Mob.java` — `extends AgentMob`, overriding `species()`.
5. Register the entity in `ModEntities` and both loaders.
6. `scripts\parity.ps1` — it checks your body without being told to. It must say `parity ok`.
7. `scripts\test.ps1` and `scripts\test.ps1 -Mechanics` — the humanoid must be untouched: 20 of 20 arena fights at
   **exactly 54 ticks**, 23 of 23 mechanics. The 54 is deterministic, so any change in it is a change in behaviour.
8. A game test that spawns your body and drives it. `PlayGameTest.aSecondBodyIsDrivenAndARefusedBrainIsNamed` is the
   pattern: spawn it, check the refusal is named both ways, then drive it with a brain of its own and watch it move. An
   agent out in the world stands still with nobody in view whatever its brain says, so give it something to see.

## If you change a layout that already has trained networks

The schema id changes with any change to the layout, including one that only moves a field. Then:

- If nothing an input **means** has moved — the same inputs in the same blocks at the same offsets — the networks are still
  the networks they were, and only the four bytes that name the layout are out of date. `scripts\restamp.ps1` brings a
  stopped run up to date; `models\` was re-stamped the same way when bodies were given names. The evidence that nothing
  moved is the parity check's own figures staying the same to four places, and the arena's 54 ticks holding.
- If an input's meaning **has** moved, the old weights do not fit and no amount of re-stamping makes them. Retrain, and say
  so rather than carrying a file that quietly means something else.
