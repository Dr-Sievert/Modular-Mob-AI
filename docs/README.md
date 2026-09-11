# How the pieces fit

Two halves that never talk directly. The **mod** runs the fights and the network that fights them, every tick, in Java.
The **trainer** learns better weights from what the mod recorded, in Python, between iterations. What passes between them
is files in one run folder, and the formats below are the whole contract.

```
mod (Java, every tick)                                trainer (Python, once per iteration)
  observe -> forward pass -> sample -> act               wait for every worker's shard
  write what it did              --- runs/<name>/rollouts/N/*.mbr --->   replay, PPO update
  wait for the next weights      <--- runs/<name>/weights/N+1.mbw ---    export
```

## Inside a tick

At the start of every level tick, before any entity moves, `AgentDriver` gathers every agent in the level, groups them by
the brain driving them, and puts each group through its brain in one call:

1. Each agent's `EnemySlots` refresh and `AgentObservation` fills its row of the observation.
2. Each agent's hidden vector is gathered into the batch.
3. The brain decides. A `NeuralBrain` runs `Forward` over the whole batch, then `ActionDecoder` samples (training) or takes
   the most likely action (deployed).
4. Actions go back into each agent's `MobControls`, hidden vectors go back into each agent's `BrainState`.

Then the entities tick and apply their controls under vanilla's rules, writing what actually happened into
`ExecutedControls`, which becomes part of the next observation.

Weights are immutable and shared by reference; an agent's only brain state is its hidden vector and its enemy leases.
Agents on the same weights share one forward pass whatever they are; agents on different weights never do.

What the network outputs is intent, never outcome. The body applies it under vanilla's rules: no jump in mid air, no
sprint backwards or while crouched, sixty degrees of turn a tick at most, a swing whenever asked but damage scaled by the
attack cooldown, one use every four ticks. What actually happened is written into `ExecutedControls` and fed back as part
of the next observation, so the network learns those rules from experience instead of being told them.

The agent perceives every hostile within 32 blocks in ten stable slots, in its own frame: where it is, how it moves, its
health, which way it faces, what it holds. Animals and villagers never take a slot. There is no aim assist: the agent
turns with its yaw and pitch controls, and a swing hits whatever is under its crosshair within reach, as for a player.
Only damage to its opponent is paid for.

## The fights

Training fights are one agent against one vindicator on natural terrain, generated as a normal world: hills, trees,
water, whatever is there. Each fight owns an 80 by 80 block site of open ground with no walls, and sites sit 128 blocks
apart. The chunks between sites never tick entities, so anything that walks off its site stops there and the fight ends
the way any stalled fight does, as a loss when the minute runs out. The sites are placed once per worker on land, found by
asking the world generator for biomes rather than generating anything, and are generated a couple at a time while fights
already run on the first ones. `gametest/terrain/TerrainSites` holds all of it.

Generating 64 sites takes a worker about a minute and a half, so the build keeps the worlds workers generated, in
`runs/terrain/<minecraft version>`, about 60 MB each. A later worker gets one of those and reads its sites from disk in
seconds: the least used world first, each at most 8 times (`terrainUses`) before it is dropped. The pool holds 8 worlds
(`terrainPool`, 0 turns it off), and while it is short of that, one worker of every run generates a fresh one to add, so
the terrain keeps changing. Delete the folder to start over.

A fight ends when either dies, or after 1200 ticks, a minute, which is a loss.

## The league

The league suite (`-Psuite=league`, `scripts\train.ps1 -Suite league`) is the same fight on the same sites against a
different opponent every time: 26 hostile mobs, the scripted fighter, and frozen checkpoints of the run itself, each
played on its most likely action with nothing recorded, so only the agent learns. The agent, and any agent it fights,
carries a loadout drawn afresh every fight: sword tiers, an axe, armour, a shield, a bow, a crossbow. League fights happen
at midnight with mob griefing off. `gametest/league/Roster` has which mobs and why the rest are left out, and how each is
made to fight an agent rather than a player; `gametest/league/League` has the draw.

The training side decides who the agent meets and rates everyone; `trainer/mmai/league.py` has the arithmetic. Training
fights go mostly to opponents the agent beats about half the time, with a floor for every one, and a fifth of them to a
pool of checkpoints. Evaluation fights, one in ten, play a checkpoint against an opponent drawn evenly from everyone, and
those are rated: Elo, the scripted fighter held at 1500. A checkpoint's evaluation fights against the mobs and the
scripted fighter are also its evaluation for the best weights and for when the run is done, as on the terrain suite.
`scripts\league.ps1 -Run <run>` prints the tier list and the tables below.

| File | Written by | Holds |
| --- | --- | --- |
| `league/roster.csv` | each worker as it starts | `opponent,kind`: the mobs and the scripted fighter it fields |
| `league/results/wNN.csv` | each worker, a line a fight | `iteration,kind,opponent,loadout,opponent_loadout,outcome,ticks`; kind is `train` or `eval`, outcome `win`, `loss`, `timeout` or `draw` (the opponent went unkilled, a creeper that blew itself up) |
| `league/matchmaking.csv` | the trainer, every iteration | `opponent,share,chance,rating,fights`: the share of training fights each gets, which the workers draw from |
| `league/ratings.csv` | the trainer | every player's rating and rated record |
| `league/opponents.csv`, `league/loadouts.csv` | the trainer | the agent's last 200 evaluation and training fights against each opponent, and with each loadout |
| `league/evaluations.csv` | the trainer | every evaluated checkpoint's record against each opponent |
| `league/state.json` | the trainer | what a resumed run needs to carry the league on |

## The code

```
mod/                the Gradle build
  common/src/main/java/net/sievert/modularmobai/
    entity/agent/     the agent: body, controls, the record of what executed, renderer
    brain/            the driver, the batch, the brains (scripted, neural) and the training link
    brain/schema/     what an agent sees and does: observation layout and encoder, enemy leases, action layout
    brain/nn/         the network runtime, plain Java: topology, weights, forward pass, heads, rollout writer
    arena/            a fight someone set up: who the opponent is, what the agent is paid, what it may see
  common/src/gametest/java/net/sievert/modularmobai/gametest/
    tests/            the fights: in a closed box, on natural terrain, and against the league
    terrain/          the terrain sites
    league/           the league: who is fielded and how, the loadouts, the draw and the results
    tools/            BrainTool: schema export and the parity check, runs without the game
trainer/            see trainer/README.md
scripts/            what to run from a terminal
```

## Files

Everything is little endian.

### `schema.json`

Written by the mod (`BrainTool schema`) at the start of every training run. The observation blocks, the action names, and
the head table that says how the network's outputs become actions. Its **schema id** is the CRC32 of the file's bytes,
and it is stamped into every weight file and shard: a mod running a different layout refuses the weights instead of
feeding a network the wrong numbers.

### `.mbw`, weights

Written by the trainer, read by the mod. A 52 byte header, then every parameter as one float array.

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

Parameter order, matrices row major `[out][in]` exactly as PyTorch stores them:
`normMean[obs] normStd[obs] fc1W fc1B gruWih[3H x h1] gruBih gruWhh[3H x H] gruBhh fc2W fc2B outW outB logStd[std]`.
The GRU gates are in PyTorch's order: reset, update, new. The observation normaliser travels in the file because the
network is meaningless behind any other one.

Anything that does not add up is refused and never coerced: wrong magic, version, schema id, a hash that disagrees with
the dimensions, a parameter count or file length that disagrees with the topology, a non finite parameter.

### `.mbr`, rollout shards

Written by the mod, one per worker per iteration, read by the trainer. A 64 byte header, the rows, then the segment table.

```
header   16 u32: 'MBR1' | version | schema id | topology hash | iteration | round | worker | worker count
                 | obsDim | actDim | hidden | final | row count | segment count | steps | 0
row      u32 agent | u32 flags | f32 reward | f32 log probability | f32 action[actDim] | f32 obs[obsDim]
segment  u32 row | f32 h0[hidden]
```

Flags: 1 the agent's episode starts on this row, 2 the fight ended on this row, 4 the shard was cut here while the fight
went on. A row's reward belongs to the action on that agent's previous row. A segment is one agent's run of rows in one
shard; it starts on a row listed in the segment table, with the hidden state the agent had going in, and its last row
carries an observation and a reward but no action. `final` means the worker is shutting down and will send nothing more.

Shards are written under `.tmp` and renamed when complete; the trainer only reads `.mbr`.

### `trainer.status`

One line, `waiting N R` or `training N R`: the iteration the trainer is on, and the highest round that is over and fully
learned from. The build waits for `waiting` before starting a round, so workers never start under weights about to change.

### `rollouts/gone/` and `rollouts/rounds/`

Empty marker files the build writes. `gone/r0003-w01` means worker 1 of round 3 died, so the trainer stops waiting for its
shards; `rounds/r0003.done` means every worker of round 3 has exited. Without them a crashed worker would leave the
trainer, and so every other worker, waiting forever.

## Machine stability

Training is the heaviest thing this machine runs: several Minecraft servers, a trainer, sometimes the GPU. On
**2026-09-11 it blue screened** (a kernel double fault) seconds after two servers started booting at once, and later the
same day two Minecraft servers died with access violations inside the JVM itself, one in its JIT compiler and one in its
garbage collector, both while generating terrain on every core. This PC has an **i7-14700KF**, one of Intel's 13th and
14th generation desktop chips that degrade and crash under load on microcode older than **0x12B**, and it was running
**0x11F** on a BIOS from October 2023. The event log also had CPU parity errors from August and repeated GPU driver
resets, both known symptoms. The scripts warn about this until it is fixed.

The fix is outside this repository:

1. Update the BIOS to MSI's newest for the **PRO Z790-P WIFI (MS-7E06)**, one whose notes list Intel microcode 0x12F or
   newer. Flash it with M-Flash from a FAT32 USB stick.
2. In the BIOS, load the **Intel Default Settings** power profile.
3. Leave XMP off for a few days. The 14700K is rated for DDR5-5600; the kit runs at 6000 under XMP.
4. If crashes or WHEA errors continue after that, the chip may already be degraded. Intel extended the warranty on these
   chips for this; contact Intel support for a replacement.

Until then, a quick way to calm the chip down is Windows' own power plan: Power Options, the plan's advanced settings,
Processor power management, Maximum processor state at **99%**. That switches off turbo boost, which is where these chips
fail, at the cost of a good part of their single core speed.

Training runs at full power by default and is built to survive this rather than avoid it. A worker that dies is written
off, the trainer stops waiting for it, and the others carry on; battles are fought in rounds of 2,000 with fresh workers,
so one crash costs at most the rest of its round, and the run keeps going until the battle count is reached. What the
build does with the machine:

| Property | Default | What it does |
| --- | --- | --- |
| `maxWorkers` | 16 | Workers in a training round, cut down to what the cores and the free memory hold. |
| `memoryReserve` | 3 | GB left for everything else when deciding how many workers fit, at heap plus one GB each. |
| `workerCpus` | auto | Cores each server sees: the machine's share per worker, so a dozen servers never fight over every core. |
| `workerStagger` | 1 | Seconds between starting workers, so boot bursts do not land on the same instant. |
| `memoryFloor` | 1.5 | GB of free memory below which the workers are stopped rather than left to swap. |

The trainer caps itself at half the GPU's memory, so it can never spill into system memory and crawl, and an update that
does run out falls back to the CPU. Workers and the trainer run below normal priority, `scripts\train.ps1 -Device cpu`
keeps the GPU out of it entirely, and `scripts\stop.ps1` stops everything a run started.
