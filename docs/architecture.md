# How the pieces fit

Two halves that never talk directly.
- The **mod** runs the fights, and the network that fights them, every tick, in Java.
- The **trainer** learns better weights from what the mod recorded, in Python, between iterations.

What passes between them is files in one run folder, and the formats below are the whole contract.

```
mod (Java, every tick)                                trainer (Python, once per iteration)
  observe -> forward pass -> sample -> act               wait for every worker's shard
  write what it did              --- runs/<name>/rollouts/N/*.mbr --->   replay, PPO update
  wait for the next weights      <--- runs/<name>/weights/N+1.mbw ---    export
```

## Inside a tick

At the start of every level tick, before any entity moves, `AgentDriver` gathers every agent in the level, groups them by
the brain driving them, and puts each group through its brain in one call:

1. Each agent's `EnemySlots` refresh, and `AgentObservation` fills its row of the observation.
2. Each agent's hidden vector (its memory) is gathered into the batch.
3. The brain decides:
   - a `NeuralBrain` runs `Forward` over the whole batch (two agents share each pass over the weights), then
     `ActionDecoder` samples (training) or takes the most likely action (deployed);
   - `ScriptedBrain` is the hand-written teacher.
4. Actions go back into each agent's `MobControls`, and hidden vectors back into each agent's `BrainState`.

Then the entities tick and apply their controls under vanilla's rules, writing what actually happened into
`ExecutedControls`. That record becomes part of the next observation.

What the network outputs is intent, never outcome. The body applies it under a player's rules, and the network learns
those rules from the echo:
- no jump in mid air, and no sprinting backwards or while crouched;
- sixty degrees of turn a tick at most;
- a swing whenever asked, but damage scaled by the attack cooldown;
- movement at a fifth while using an item, and no attacking while using one.

There is no aim assist. The agent turns with its yaw and pitch controls, and a swing hits whatever is under its
crosshair within reach, as for a player.

## What the agent sees: 634 floats

| Block | Size | Contents |
| --- | --- | --- |
| self | 20 | health, velocity (forward/up/right), on ground, in water, attack strength, use cooldown, using (main/off hand), sprinting, crouching, fall distance, body offset (sin/cos), pitch, aim (sin/cos), hurt time, enemies in range |
| hotbar | 9 | what each hotbar slot holds |
| echo | 20 | what the body actually did last tick: moved (forward/strafe), jumped, sprinted, sneaked, turned (yaw/pitch), attacked, hit, attack strength and damage, crit, sweep, sprint knockback, used (main/off hand/on a block), selected slot, swapped weapon |
| enemies | 10 × 18 | every hostile within 32 blocks, in ten stable slots, in its own frame: present, position (forward/up/right), distance, velocity, health, facing (sin/cos), pitch, **kind**, main and off hand item, swinging, using, sprinting |
| terrain | 9 × 5 × 9 = 405 | the blocks around it, from 2 below the feet to 2 above: 0 empty, 0.5 fluid, 1 solid by collision, 1.5 hazard. A hazard hurts or kills a body in it or on it: lava, fire, magma, cactus, lit campfires, wither roses, pointed dripstone, powder snow, berry bushes, cobwebs. An empty cell in the bottom layer reads as a hazard when the fall below it would be more than 8 blocks, or would end in a hazard |

Animals and villagers never take an enemy slot. The enemy's `kind` lets one network tell a zombie from a skeleton, which
the league relies on. The layout is fixed: every trained network depends on it, and the schema id refuses a mismatch.
Field constants are in `brain/schema/ObservationSchema.java`, and the encoder is `AgentObservation.java`.

## What it controls: 11 controls, 19 network outputs

| Control | Kind | Outputs |
| --- | --- | --- |
| move forward/back, strafe | continuous | 2 means |
| turn yaw, pitch | continuous | 2 means |
| jump, sprint, sneak, attack, use, use off hand | on/off | 6 logits |
| hotbar slot | one of nine | 9 logits |

The continuous controls add a learned spread while training (`logStd`, per control). A deployed agent uses the mean.

## The network

`634 -> 256 -> GRU 128 -> 128 -> 19`: an encoder, a recurrent layer that carries 128 numbers of memory from tick to tick
for the whole fight (blank at the start of each fight), and one head per kind of control. That's 331,019 parameters.
Training runs the GRU through chunks of 32 ticks. The widths are trainer options (`--h1 --hidden --h3`); the game reads
them from the weight file, so a wider network needs no Java change. `scripts\parity.ps1` checks that the Java forward
pass matches PyTorch's to within about 1e-6.

## The reward (`arena/AgentReward`)

| Case | Dealt | Taken | Outcome | Total |
| --- | --- | --- | --- | --- |
| instant kill, untouched | +1.50 | 0.00 | +3 | +4.50 |
| slow kill, nearly dead | +1.50 | -0.99 | +2 | +2.51 |
| died at the buzzer, nearly won | +1.49 | -1.00 | -2 | -1.51 |
| stalled to the timeout | 0.00 | 0.00 | -2 | -2.00 |
| killed immediately | 0.00 | -1.00 | -2 | -3.00 |

A win pays 2 plus up to 1 for speed. A loss, or running out the minute, costs 2. Damage is paid by the health it actually
removed, dealt at 1.5 and taken at 1.0. Nothing is paid for lasting longer: that taught agents to run. Damage is paid in
one place, where it lands (`LivingEntityMixin`), so arrows count the same as swings.

## The fights

Training fights are one agent against one vindicator on natural terrain, generated as a normal world. A fight ends when
either fighter dies, or after 1200 ticks (a minute), which counts as a loss.

Each worker runs 25 fights at once on a quarter again as many sites (32), plus 4 spares, all held by
`gametest/terrain/TerrainSites`:
- A site is 80 × 80 blocks (5 × 5 chunks). Sites sit on a lattice 128 blocks apart, 8 to a row.
- Both fighters start with open sky above them. Starts under canopies and mangrove roots lost 9.8% against 0.8%.
- A site hosts 100 fights, then moves on to fresh ground once a spare is ready. A site where 2 fights time out is
  retired at once: that's the ground, not luck.
- Chunks unload when a site moves on (`ChunkMapMixin`). Nothing is saved while tests run (`ServerLevelMixin`), and
  vanilla skips unloading entirely for a level that doesn't save, so without that mixin workers ran out of memory.

Workers keep the worlds they generate in `runs/terrain/<minecraft version>`, up to a pool of 8 (`terrainPool`), each
reused at most 8 times (`terrainUses`). A later worker reads its first sites from disk in seconds instead of generating
them.

## The league

The league suite (`-Psuite=league`, `scripts\train.ps1 -Suite league`) is the same fight on the same sites against a
different opponent every time, with a different loadout:
- 26 mobs (`gametest/league/Roster`, which also says why the rest are left out): zombie, husk, drowned, zombie villager,
  skeleton, stray, bogged, wither skeleton, spider, cave spider, creeper, vindicator, pillager, witch, ravager, enderman,
  silverfish, endermite, slime, magma cube, zombified piglin, piglin, piglin brute, hoglin, zoglin, breeze. Each gets its
  own finalizeSpawn, is grown up, kept from zombifying and, for a slime, made its biggest, and is made to go for the
  agent every tick it has let go: as its target, angered, or in its brain's memory. Slimes and breezes treat the agent
  as a player (`SlimeInvoker`, `BreezeMixin`).
- The scripted fighter and frozen checkpoints of the run, as another agent with a brain of its own on its most likely
  action, so only the agent's steps are recorded.
- 10 loadouts (`gametest/league/Loadouts`, armed with `arena/Loadout`): iron, stone and diamond swords, an axe, a sword
  with iron armour, sword or axe with a shield, a bow, a crossbow, a sword with a bow behind it.
- League fights happen at midnight with mob griefing off: no undead burn, spiders stay hostile, no crater stays in a kept
  world. A creeper that blows itself up without killing the agent is a draw, which pays as a loss.

The trainer decides who the agent meets and rates everyone (`trainer/mmai/league.py`). Training fights are shared by the
agent's chance against each opponent times its complement, from its recent fights and filled in from the ratings, with
a quarter spread evenly and a fifth for a pool of 8 checkpoints (the newest 4, and 4 spread over the run). Evaluation
fights, one in ten, play a checkpoint against an opponent drawn evenly from everyone, and those are rated: Elo, K 16 (32
for a player's first 30 fights), the scripted fighter held at 1500. Those against mobs and the scripted fighter are also
the checkpoint's evaluation, so best weights and the end of the run work as on the terrain suite, on 1,000 fights each.
`scripts\league.ps1 -Run <run>` prints the tier list and the tables.

| File (`runs/<run>/league/`) | Written by | Holds |
| --- | --- | --- |
| `roster.csv` | each worker as it starts | `opponent,kind`: the mobs and the scripted fighter it fields |
| `results/wNN.csv` | each worker, a line a fight | `iteration,kind,opponent,loadout,opponent_loadout,outcome,ticks,cause`; kind `train` or `eval`, outcome `win`, `loss`, `timeout` or `draw`, cause what the agent died of when it died |
| `matchmaking.csv` | the trainer, every iteration | `opponent,share,chance,rating,fights`: each opponent's share of the training fights, which the workers draw from |
| `ratings.csv` | the trainer | every player's rating and rated record |
| `opponents.csv`, `loadouts.csv` | the trainer | the agent's last 200 evaluation and training fights against each opponent and with each loadout |
| `evaluations.csv` | the trainer | every evaluated checkpoint's record against each opponent |
| `state.json` | the trainer | what a resumed run needs to carry the league on |

## The code

```
mod/                    the Gradle build (MultiLoader: common + fabric + neoforge)
  common/src/main/java/net/sievert/modularmobai/
    entity/agent/         the agent: body, controls, the record of what executed, item and block rules
    brain/                the driver, the batch, the brains (scripted, neural, demonstration) and the training link
    brain/schema/         what an agent sees and does: observation layout and encoder, enemy slots, action layout
    brain/nn/             the network runtime in plain Java: topology, weight file, forward pass, heads, rollout writer
    arena/                a fight someone set up: loadouts, the reward, what the agent may see
    allegiance/           sides: vanilla teams, the agent's enemy rule, mobs going after other teams (see playing.md)
    command/              /mmai (see playing.md)
    mixin/                vanilla changes the agent needs (placing, axes, damage payment, bow access, friendly fire,
                          mobs' goal for other teams)
    Config.java           config/modular_mob_ai.properties, read by agents in a real game only
  common/src/gametest/java/net/sievert/modularmobai/gametest/
    tests/                the fights (closed arena, natural terrain, the league), the mechanics suite and the play suite
    terrain/              the terrain sites
    league/               the league: the mobs and how each is fielded, the loadouts, the draw and the results
    replay/               fight recording for the viewer (FightRecorder, SiteBlocks)
    mixin/                game-test-only server changes: no saving, chunk unloading, no idle chunk ticking
    tools/                BrainTool: schema export and the parity check, runs without the game
  buildSrc/               the build logic: runTraining, runGametestParallel, workers, memory guard (multiloader-loader.gradle)
trainer/                PyTorch: train.py and mmai/ (ppo, model, evaluate, run folder protocol, weights, rollouts)
viewer/                 the replay viewer: serve.py and the page
scripts/                everything you run
models/                 published networks, in git
runs/                   training runs, not in git
```

## Files

Everything is little endian.

### `schema.json`

Written by the mod (`BrainTool schema`) at the start of every training run. It holds the observation blocks, the action
names, and the head table saying how the network's outputs become actions. Its **schema id** is the CRC32 of the file's
bytes. It is stamped into every weight file and shard, so a mod running a different layout refuses the weights instead
of feeding a network the wrong numbers.

### `.mbw`: weights

Written by the trainer, read by the mod. A 52-byte header, then every parameter as one float array.

```
0   'MBW1'
4   u32 format version (1)
8   u32 schema id
12  u32 topology hash      CRC32 of the six dimensions below
16  u32 obsDim, h1, hidden, h3, outDim, stdDim
40  f32 observation clip
44  u32 iteration
48  u32 parameter count
52  f32 parameters
```

Parameters are in this order, matrices row major `[out][in]` exactly as PyTorch stores them:
`normMean[obs] normStd[obs] fc1W fc1B gruWih[3H x h1] gruBih gruWhh[3H x H] gruBhh fc2W fc2B outW outB logStd[std]`.
The GRU gates are in PyTorch's order: reset, update, new. The observation normaliser travels in the file because the
network is meaningless behind any other one.

Anything that doesn't add up is refused and never coerced: wrong magic, version or schema id, a hash that disagrees with
the dimensions, a parameter count or file length that disagrees with the topology, a non-finite parameter.

### `.mbr`: rollout shards

Written by the mod, one per worker per iteration, read by the trainer. A 64-byte header, the rows, then the segment table.

```
header   16 u32: 'MBR1' | version | schema id | topology hash | iteration | round | worker | worker count
                 | obsDim | actDim | hidden | final | row count | segment count | steps | 0
row      u32 agent | u32 flags | f32 reward | f32 log probability | f32 action[actDim] | f32 obs[obsDim]
segment  u32 row | f32 h0[hidden]
```

Flags:
- 1: the agent's episode starts on this row;
- 2: the fight ended on this row;
- 4: the shard was cut here while the fight went on.

A row's reward belongs to the action on that agent's previous row. A segment is one agent's run of rows in one shard. It
starts on a row listed in the segment table, with the hidden state the agent had going in, and its last row carries an
observation and a reward but no action. `final` means the worker is shutting down and will send nothing more. Shards are
written under `.tmp` and renamed when complete.

### Run folder control files

| File | Written by | Meaning |
| --- | --- | --- |
| `trainer.status` | trainer | `waiting N R` or `training N R`: the iteration, and the highest round fully learned from. The build waits for `waiting` before starting a round |
| `rollouts/gone/r0003-w01` | build | worker 1 of round 3 died; the trainer stops waiting for its shards |
| `rollouts/rounds/r0003.done` | build | every worker of round 3 has exited |
| `eval/target` | trainer | the checkpoint iteration the workers should evaluate in one fight in ten |
| `eval/wNN.csv` | workers | `iteration,outcome,ticks` per evaluation fight (outcome is win, loss or timeout) |
| `eval.csv` | trainer | one line per judged checkpoint: fights, wins, timeouts, win rate, best |
| `best.mbw` | trainer | the best judged checkpoint's weights |
| `finished` | trainer | the reason evaluation says the run is done; the build starts no more rounds |

Everything the trainer hands over is written under a temporary name and renamed into place. On Windows the rename is
refused while a reader has the file open, so `mmai/files.py` retries for up to ten seconds rather than ending the run.
