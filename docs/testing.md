# Testing

## The checks to run after a change

```
scripts\test.ps1                 20 fights in the closed arena with the scripted fighter
scripts\test.ps1 -Mechanics      the item and block rules against a player's numbers
scripts\parity.ps1               Java forward pass against PyTorch's, after touching brain\nn or the model
```

| Check | Pass looks like | Proves |
| --- | --- | --- |
| `test.ps1` | `All 20 required tests passed`; every fight 54 ticks | the observation, the body and the scripted fighter still work; the 54 ticks are deterministic, so any change in them is a behaviour change |
| `test.ps1 -Mechanics` | `All 19 required tests passed` | bows, crossbows, shields, axes, mining, placing, use slowdown and damage payment follow a player's rules |
| `parity.ps1` | logits agree to about 1e-6 | the game runs exactly the network PyTorch trained |

Each boots a headless server in seconds, and all three need only Java (parity also needs the trainer's Python).

## More

```
scripts\test.ps1 -Terrain                 the fights on natural terrain; generates a world first
scripts\test.ps1 -Arenas 200              more fights
scripts\test.ps1 -Terrain -Replays        record every fight for the viewer, in runs\gametest\replays
scripts\test.ps1 -League                  twice round every league opponent; a table of each, with how often it hit the agent
scripts\test.ps1 -League -Weights models\vs-copy\best.mbw   the same with a network, which also fights a frozen copy of itself
scripts\league.ps1 -Test                  the league's unit tests: Elo, matchmaking, the pool, reading and resuming results
scripts\eval.ps1 -Weights models\vs-copy\best.mbw          a network's win rate, 2,000 fights, most likely action
scripts\eval.ps1 -Run vs-copy -Iteration 650 -Arenas 400   a checkpoint of a local run
```

`eval.ps1` fights with no exploration and records nothing for training. With 2,000 fights the win rate is within about
a point either way; with 400, within about two and a half. It takes `-Workers` (8), `-Slots`, `-Heap`, `-Suite` and
`-ReplayEvery`.

## The mechanics suite (`gametest/tests/AgentMechanicsGameTest`)

Each test sets up one situation and checks the numbers a player would get:

| Test | Checks |
| --- | --- |
| `bowShotFliesHitsAndIsPaid` | 20-tick draw, full-draw crit arrow at 3.0 blocks/tick, owned by the agent, one arrow used, hit, paid once |
| `bowWithoutArrowsDoesNotDraw`, `bowTakesTheArrowAPlayersWould` | ammo rules; an off-hand spectral arrow goes first |
| `crossbowChargesHoldsItsLoadAndFires`, `crossbowMultishotLoadsThreeForOneArrow` | crossbow loading and firing at a player's speed |
| `shieldBlocksWhatComesFromTheFront` | blocks blows and arrows from the front, not from behind |
| `axeKnocksTheAgentsShieldAside`, `agentsAxeKnocksAShieldAside` | the axe disables a shield for 100 ticks, both ways |
| `usingAnItemSlowsToAFifth`, `noAttackingWhileAnItemIsInUse` | use slowdown and no attacking while using |
| mining tests | the player's break formula: iron shovel on dirt 3 ticks, hand on stone 151 ticks, iron pickaxe on stone 8 |
| `placingUsesABlockUpAndNeverBuildsIntoAnything`, `placedBlocksFaceAsForAPlayer` | placing |
| `swordBlowIsPaidOnceAndOnlyOnTheOpponent` | damage payment |

Ammo: the agent's bow and crossbow loadouts carry 64 finite arrows, one used per shot, and arrows aren't picked back up.
That covers a 60-second fight, since a full-draw shot takes 20 ticks. Vanilla skeletons and pillagers never run out.

## Game tests directly

Game tests live in a `gametest` source set next to `main` in every project. They're written once in
`mod/common/src/gametest` and compiled into each loader, so a test runs on both Fabric and NeoForge.

```
mod\gradlew.bat -p mod :fabric:runGametest
mod\gradlew.bat -p mod :neoforge:runGameTestServer
mod\gradlew.bat -p mod :fabric:runGametestParallel -Psuite=terrain -Parenas=2000 -Pworkers=4
```

| Property | What it does |
| --- | --- |
| `suite` | `arena` (closed box), `terrain` (natural ground, what training uses), `mechanics`, `baseline` (villager against vindicator, no agent) |
| `arenas`, `workers`, `batchSize` | fights, worker processes, fights at once per worker |
| `brain`, `brainWeights` | `scripted` (default) or `neural` with a `.mbw` file |
| `replayEvery`, `replayRun` | record one fight in N, into `runs\<replayRun>\replays` |
| `workerHeap` | heap per worker |
| `ticksPerSecond` | ceiling on the tick rate; unthrottled by default, since a game-test server never sleeps |

Fabric writes a JUnit report to `mod/fabric/build/gametest/report.xml`. The normal client and server runs also load the
game-test source set, so tests can be run by hand in a dev world with `/test runall`.

### Writing a test

1. Add a class under `mod/common/src/gametest/java/net/sievert/modularmobai/gametest/tests`, annotate it with
   `@GameTestGroup`, and list it in `ModularMobAiGameTests`.
2. Each `@GameTest` method resolves its structure as `<namespace>:gametest/<path>/<template>`, so
   `@GameTest(template = "arena")` loads `mod/common/src/gametest/resources/data/modular_mob_ai/structure/gametest/arena.nbt`.
   A structure only declares the size of the region; building the scenery is the test's job.
3. Tests must not import loader-specific code.

Arm fighters with `arena/Loadout.java`: `Loadout.BOW.equip(agent)`. The presets are sword, sword_shield, axe_shield, bow
and crossbow.

### Development only

The game-test source set never reaches a published jar. The processed metadata points at the game-test entry point and
mixin config for every development run, and the `jar` task strips them again while packaging.
- In `neoforge.mods.toml`, a game-test mixin entry needs a trailing `#gametest` comment on both of its lines.
- In `fabric.mod.json`, the game-test mixin config has to stay first in the `mixins` array.
