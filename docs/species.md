# Adding a species: giving another body a brain

A **species** is one body: what an agent of that body sees, what it can be asked to do, how a network's outputs turn into
that, what it can hold, and what mobs the game registers for it. There are three — the **humanoid**, the player-shaped
agent every trained network drives; the **beast**, a body with no hands; and **test_body**, a layout and nothing else,
which exists so that "adding a body is one place" is checked rather than claimed. This is how to write a fourth.

## The one place

`brain/schema/Species.java`. A body is declared by adding it to `Species.ALL` and **nowhere else**:

```java
List<Species> ALL = checked(List.of(HUMANOID, BEAST, TEST_BODY));
```

Everything that goes round the bodies goes round that list, and asks the declaration for whatever it needs:

| What follows automatically | How |
| --- | --- |
| Both loaders register its mobs | `ModEntities` builds an entity type per mob per body from `Species.mobs()`; Fabric and NeoForge iterate that and name nothing |
| Its mobs get a player's attributes and the player renderer | the same loop |
| Which body a mob is | its entity type, through `ModEntities.speciesOf`, which is what `AgentMob.species()` reads. There is no entity class per body |
| `scripts\parity.ps1` checks it | the build asks the game what bodies there are (`BrainTool species`) rather than keeping a list |
| `-Pspecies=<name>` works | `Species.byName` |
| The trainer builds a network of its width | the game writes the layout, `trainer/mmai/schema.py` reads it. No literal anywhere on that side |
| Its weights are refused by every other body | the schema id, a CRC32 of the layout JSON with the species name in it |
| It appears in every message that says what bodies there are | `Species.ALL` |
| The arenas and the league field it | `Species.trained()`, from `-Pspecies`, which the build tells the game as well |
| `best`, publishing, `/mmai models` | one best per body; see [Publishing](#publishing-per-body) |

`checked` catches, as the class loads, the two mistakes a declaration can make that nothing else would: two bodies sharing
a mob id, and two sharing a schema id.

## Why a schema per body, rather than one masked brain

A body with no hands has no hotbar to look at, no slot to choose, and no use buttons to press. You could keep one giant
layout and mask those inputs off for bodies that lack them, and it was considered; a schema per body was chosen instead,
because a masked input is still an input the network has to learn to ignore, a masked control is still a control someone
can wire up by mistake, and neither shows up as a failure. A body that cannot press a button has no such button.

The price is that a network belongs to one body. That is enforced, not advised; see
[Refusing the wrong body](#refusing-the-wrong-body).

## What a body has to provide

### 1. The declaration: `name`, `mobs`, `holdsItems`

Three short methods, and they are what the rest of the mod reads instead of knowing your body by heart.

```java
@Override
public List<Species.Mob> mobs() {

    return List.of(Species.Mob.playerShaped("beast_agent", Species.Mob.Role.TRAINING));
}

@Override
public boolean holdsItems() {

    return false;
}
```

`mobs()` is the mobs the game registers for this body: the path of the id, the box it stands in, and whether a player
meets it (`WORLD`: saved, summonable, given a spawn egg) or an arena fights in it (`TRAINING`: never saved, never
summoned). The humanoid declares one of each, which is why there are two registrations of one body. A body may declare
**none**, which is what a body is before anyone models it: it can be checked and trained for, and everything that would
put one in the world refuses it by name.

`holdsItems()` is whether it has hands. It is asked rather than inferred, because a mob's inventory is the game's and takes
whatever is put in it: a handless body handed an iron sword would have carried it, lost every fight, and said nothing about
why.

**No class of the game's may appear in a declaration.** The build reads a layout by running the schema tool as a plain
Java program with no Minecraft on its class path, so a mob is declared by its id and five numbers and turned into an
`EntityType` by `ModEntities`, which is the first place allowed to mention one.

### 2. The layout: `<Name>Schema.java`

Constants, and nothing else: the width of each block, where each block starts, the index of every field inside its own
block, the index of every control, and the head table. Copy `BeastSchema.java`; it is the shorter of the two real ones and
is commented for exactly this. A body with no encoder can keep its constants in its descriptor instead, which is what
`TestBody.java` does — it has nothing to share them with.

Two blocks are **shared by every body** and you should use them rather than writing your own: the ten enemy slots and the
terrain grid. Their shapes live in `ObservationSchema` (`ENEMY_SLOTS`, `ENEMY_STRIDE`, `ENEMY_SIZE`, `TERRAIN_X/Y/Z`,
`TERRAIN_SIZE`) and their meanings belong to the world rather than to the body looking at it: an opponent looks the same
whoever is looking, ground is ground whoever is standing on it, and two bodies disagreeing about what a hazard is would be
a bug nobody could see. What your body chooses is *whether* it has them and *where they sit* in its row.

The blocks have to start at zero, follow one another with no gap, and add up to your `OBS_DIM`. That is checked when the
species loads, by name, so getting it wrong stops the build rather than the fight.

The head table says how the network's outputs become controls. Three kinds:

| Kind | What it is | Outputs | Action values |
| --- | --- | --- | --- |
| `continuous(name, size)` | a number, sampled around `tanh(logit)` | `size` | `size` |
| `binary(name, size)` | held or not, from `sigmoid(logit)` | `size` | `size` |
| `categorical(name, size, mask)` | one of `size` choices, from a masked softmax | `size` | **1**, the index |

`mask` is an observation offset holding one value per choice; a choice is allowed only while its value is above zero,
which is how the humanoid stops a slot it is carrying nothing in from being chosen. Pass `-1` for no mask. It need not be
a hotbar — `test_body` masks its choice with a block of its own — and a body can have no categorical head at all, which
the beast has not, and nothing downstream minds.

Add the static block at the bottom of `BeastSchema` to your own. It is three lines and it catches the one mistake that is
otherwise silent: a head table that has drifted from the action indices next to it.

### 3. The encoder: `<Name>Observation.java`

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
exception and stays aligned to the world, with the body's own facing written into its self block (`AIM_SIN`, `AIM_COS`) so
a network can relate the two. Keep every number roughly between minus one and one.

A body with no mob writes no encoder. `TestBody.observe` refuses by name instead, because an encoder that quietly wrote
zeroes would let such a body be trained against nothing and look like it was working.

### 4. The descriptor: `<Name>.java`

Implements `Species`, and is mostly one-line methods. Copy `Beast.java`. The three that need thought are `mobs` and
`holdsItems` above, and:

- `act`, which writes **only the controls your body has**, and holds the rest at rest. The beast's `act` sets `sneak`,
  `use` and `useOffhand` to false explicitly, so that a control the species does not have cannot come on because some
  other body left it set in a reused buffer.

The constructor lists your blocks and hands them to `Species.describe`, which builds the JSON the training side reads and
checks that the blocks add up.

## What still takes an edit, and why

Two things, and both are honest rather than oversights.

- **A renderer, if your body is not to borrow the player model.** A renderer is a client class, and a declaration may hold
  none, so both loaders' clients give every body `AgentMobRenderer` and a body that wants its own says so there:
  `ModularMobAiClient` on Fabric and `ModularMobAiMod.Client` on NeoForge, two lines. A replay of it renders either way —
  the viewer draws anything of this mod's as the player model — and to see its own shape in the 3D view it also needs a
  cuboid shape exported into `viewer/models/`, which `MobModelTool` does for vanilla mobs out of their own model classes
  and cannot do for a model you drew.
- **A lang entry.** `assets/modular_mob_ai/lang/en_us.json`, `entity.modular_mob_ai.<path>`, plus
  `item.modular_mob_ai.<path>_spawn_egg` if your body declares a `WORLD` mob. A missing one shows the raw key, which is
  cosmetic and cannot break a fight, so nothing refuses it.

## What refuses your body by name, and why

Some things genuinely cannot be generic yet. Every one of them stops and says which body and what it has not got, rather
than doing something that looks like working:

| Where | When | Why not generic |
| --- | --- | --- |
| `ModEntities.of` / `.training` / `.world` | the body declares no mob of that role | there is nothing to spawn; a null here would be traced back from somewhere else entirely |
| `Loadout.equip` | `holdsItems()` is false | a mob's inventory takes whatever is put in it, so nothing else would ever have said no |
| `TestBody.observe` / `.act` | a body with no mob | there is no agent of it to observe or drive |
| `Published` | a published network of another body is named for the league | a rating belongs to one player and a network to one body |
| The build, writing a run's `schema.json` | the run has been training another body | a run is one body's for its whole life, and `-Pspecies` is easy to leave off a second command |
| `Brains.named("best", body)` | nothing is published for that body | handing over another body's network would be caught only by the driver, mid fight |

A body that is to fight the league needs hands, because every loadout is a weapon and the hand-written teacher that anchors
the ratings is a humanoid. That is the one substantial thing a handless body cannot yet do, and `Loadout.equip` is where it
says so.

## Refusing the wrong body

A network only fits the body its layout was written for, and "this is a beast's brain, the mob is a humanoid" is the
mistake that will actually be made. It is refused in these places, and every message names **both** sides:

| Where | When |
| --- | --- |
| `NeuralBrain.check` | a weight file is made into a brain, from its schema id. An id no body here has is refused too, listing the bodies there are |
| `AgentDriver` | a brain is about to drive a body it was not made for, naming the brain's own name as well |
| `AgentBatch` | the same, asserted where the rows are actually filled in, since every row of a step is one width |
| `DemonstrationBrain` | a teacher of another body: it could not label the fights it is asked about |
| `Published` | a published network fielded as a league player, before a fight is ever set up |
| `Trainer.load`, `train.py check` | a `state.pt` or a shard of another body, naming it where the file says which it was |

The schema id is a CRC32 of the layout JSON, **species name included**, so two bodies can never share one even if they
happen to be the same width. It is stamped into every weight file and every rollout shard.

## What the trainer needs told

Nothing, in code. The training side never writes a layout down: the game writes its own and the trainer parses it, which is
the only arrangement where the two halves cannot drift apart.

What it needs told is **which body a run is for**:

```
scripts\train.ps1 -Run beasts -Species beast
scripts\dagger.ps1 -Run beasts -Species beast     the same run, and it has to be the same body
scripts\parity.ps1                                every body
mod\gradlew.bat -p mod :fabric:brainParity -Pspecies=beast     one of them
```

`-Pspecies` is `humanoid` unless given. It decides which layout `BrainTool schema` writes into the run's `schema.json`,
which body the game's arenas field, and which body a published network has to be for to be fielded against it. A run's own
`schema.json` is read back before it is overwritten, so a command that leaves the flag off is refused with both bodies
named rather than quietly replacing the layout of a run in progress.

The blocks arrive as a list, so a body with no hotbar simply has no hotbar block and the trainer does not go looking for
one; anything that wants a particular block asks `schema.block("name")` and copes with `None`, or `schema.require("name")`
to fail clearly. `trainer/tests/test_species.py` holds every body to that.

## Publishing, per body

`scripts\publish.ps1 -Run <name>` copies the run's `best.mbw`, its `eval.csv`, its `model.json` **and its `schema.json`**
into `models\<name>\`. That last file is what says which body the network drives, and the build reads it: the jar's index
records a body per network and a **best per body**, so `best` means the best network for the body asking. One global best
would have handed a beast's network to a humanoid on any day a beast evaluated higher, and the only thing that would have
said so is the driver, in the middle of a fight.

A folder published before the layout was recorded beside the weights says no body. Such a network is still loadable by its
own name, where its own schema id decides, and is no body's `best`.

## What the parity check wants

Nothing, and that is the point: `scripts\parity.ps1` goes round **every** body in `Species.ALL` by itself, because the
build asks the game what bodies there are rather than keeping its own list.

A body nobody has trained yet is checked on a freshly built random network, which proves just as much as a trained one
would: what is being checked is that the Java forward pass and PyTorch's agree about a layout, and the weights only have to
be the same weights on both sides. So you get a real check on a brand new body before a single fight has been run — and on
a body that has no mob at all.

```
scripts\parity.ps1
  humanoid: schema 9f7a1358, observation 792 wide, 11 actions from 19 logits
  parity over 67 rows of 792 -> 792 -> 256 -> GRU 128 -> 128 -> 19 (371,783 parameters), a humanoid
  parity ok
  beast: schema 36f36b69, observation 769 wide, 7 actions from 7 logits
  parity over 67 rows of 769 -> 769 -> 256 -> GRU 128 -> 128 -> 7 (364,301 parameters), a beast
  parity ok
  test_body: schema 7685bdc1, observation 322 wide, 4 actions from 7 logits
  parity over 67 rows of 322 -> 322 -> 256 -> GRU 128 -> 128 -> 7 (248,973 parameters), a test_body
  parity ok
```

The second width is what the first layer actually takes, which is the whole observation unless the network pools its enemy
slots; see [architecture.md](architecture.md#mbw-weights). A plain run also goes round every body a second time with the
pooled pass, since that is different arithmetic and not merely a different size.

If your body's figures come out at a tenth rather than a millionth, the two sides disagree about the layout: nearly always
a block offset, a head that reads the wrong outputs, or an encoder writing a field at the wrong index.

## Adding a body: the checklist

1. `<Name>.java` in `brain/schema/` — the descriptor: `name`, `mobs`, `holdsItems`, the widths, the heads, `observe`,
   `act`, and the blocks handed to `Species.describe`.
2. `<Name>Schema.java` — the constants and the head table, with the static block at the bottom that holds the two in step.
   A body with no encoder may keep them in the descriptor instead.
3. `<Name>Observation.java` — one writer per block it has, the shared two for enemies and terrain, the row cleared first.
   A body with no mob writes none and refuses `observe` by name.
4. One entry in `Species.ALL`. **That is the registration.** No entity class, no `ModEntities` edit, no loader edit.
5. A renderer in both loaders' clients, only if it is not to borrow the player model; a shape in `viewer/models/` only if
   its own silhouette matters in the 3D replay.
6. A lang entry per mob it declares, and a spawn-egg one if any of them is a `WORLD` mob.
7. `scripts\parity.ps1` — it checks your body without being told to, plain and pooled. It must say `parity ok` for it.
8. `scripts\league.ps1 -Test` — `trainer/tests/test_species.py` walks every body without being told about yours: the
   layout adds up, an actor builds at its width, a shard of its width round trips, and the blocks it has and has not are
   asked for rather than assumed.
9. `scripts\test.ps1` and `scripts\test.ps1 -Mechanics` — the humanoid must be untouched: 20 of 20 arena fights at
   **exactly 54 ticks**, 46 of 46 mechanics. The 54 is deterministic, so any change in it is a change in behaviour.
10. `scripts\test.ps1 -Play` — `PlayGameTest.aSecondBodyIsDrivenAndARefusedBrainIsNamed` walks the register at the game's
    end as well: every mob you declared is registered and leads back to you, and your body is armed or refused by name
    according to `holdsItems`. It will pick your body up on its own. If your body has a mob and is meant to fight, add the
    half that only you can write — spawn it, check a brain of another body is refused with both named, then drive it with
    one of its own and watch it move. An agent out in the world stands still with nobody in view whatever its brain says,
    so give it something to see.

## If you change a layout that already has trained networks

The schema id changes with any change to the layout, including one that only moves a field. Then:

- If nothing an input **means** has moved — the same inputs in the same blocks at the same offsets — the networks are still
  the networks they were, and only the four bytes that name the layout are out of date. There is no tool for that any more:
  the one that re-stamped `models\` when bodies were given names was written for that one change and deleted after it, and
  nothing here will load a file whose id does not match. The evidence that nothing moved would be the parity check's own
  figures staying the same to four places, and the arena's 54 ticks holding; write the re-stamp again if a change ever
  earns it.
- If an input's meaning **has** moved, the old weights do not fit and no amount of re-stamping makes them. Retrain, and say
  so rather than carrying a file that quietly means something else.

Adding a **body** changes no other body's id, because an id is a checksum of that body's own layout. The two that exist
are `9f7a1358` and `36f36b69`, and they have not moved since bodies were given names.
