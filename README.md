# Modular Mob AI

> **Work in progress.** The full guide gets written once the system is complete: several weapons, matchups against
> several mobs, an ELO league, evaluation with best weights, and the flags that drive them. Until then this page covers
> what works today, and the sections marked *to be written* are placeholders.

A neural network brain for Minecraft mobs. The network runs inside the game, in plain Java, one batched forward pass per
tick for every mob on the same weights. It is trained offline in PyTorch from what the game recorded, and the new weights
are swapped in without the game restarting. Right now it learns one thing: to beat a vindicator one on one, out in the
open on natural terrain.

## Quick start

Windows 10 or 11 and a clone of this repository. Nothing else has to be installed first.

```
scripts\setup.ps1        once per machine: Java 21, Python, PyTorch (CUDA when there is an NVIDIA card), compile, parity check
scripts\test.ps1 -Terrain  a first look at some fights with the scripted fighter
scripts\compare.ps1      train from scratch and from a copy of the scripted fighter, side by side
scripts\watch.ps1 -Run imitate   live progress of a run, in a second terminal
scripts\eval.ps1 -Run imitate    a trained network's win rate, no exploration
scripts\stop.ps1         stop every run and anything it left behind
```

Setup installs nothing system wide: a machine without Java 21 or Python gets them unpacked into `.tools\`, which git
ignores. Every path is found relative to the repository, so a clone anywhere on any machine works the same.

## Layout

```
mod/          the Minecraft mod: the whole Gradle build, one project per loader
  common/       everything both loaders share: the agent, its brain, the network runtime, the fights
  fabric/       Fabric entry points
  neoforge/     NeoForge entry points
trainer/      the PyTorch trainer
viewer/       the fight replay viewer
scripts/      what to run from a terminal
docs/         how the pieces fit and the file formats both sides share
runs/         training runs: weights, rollouts, replays, logs, checkpoints (not in git)
```

## Scripts and their parameters

*To be written.* Each script documents its parameters at its top in the meantime.

## How training works

*To be written:* the fights, the reward, imitation then PPO, evaluation and best weights, matchups and the ELO league.

## Findings so far

- **Exploration noise on aim has to be small.** At a spread of 0.37 of full deflection the crosshair jerked about 22
  degrees a tick at random, and the copy of the scripted fighter won about 1% of its fights sampling against 63% on its
  most likely action. With aim at 0.1 (about 6 degrees a tick) the sampled copy won 20% from the start.
- **Training from nothing does not get anywhere against a vindicator.** 10,000 battles, no wins, and the policy got more
  random as it went. Starting from a copy of the scripted fighter does: 21% rising to 36% while training, and 48.8% to
  55.0% on its most likely action over 400 evaluation fights, in the same 10,000 battles.
- **A copy needs to have seen mistakes.** Recording the scripted fighter with its movement and aim pushed off by noise
  (DART), and writing down its correction, took the copy from 0% to 63%.
- **Letting the copy drive and having the teacher correct it (DAgger) is the big one.** Three rounds of 1,500 fights,
  the copy driving with light noise and the scripted fighter labelling every tick, took the copy from 48.8% to 89.8% on
  its most likely action over 400 fights; the teacher itself wins about 96%. The copy driving won 46%, 81% and 80% of
  the recorded rounds. `scripts\imitate.ps1` does all of it.
- **Reinforcement learning on a good copy has to explore gently.** Starting PPO from the 89.8% copy with a movement
  spread of 0.37 and the usual entropy bonus, the training win rate rose from 48% to 68% while the spread kept widening,
  and the deployed fighter fell to 84–85%: PPO improves the policy it samples from, and it had learned to cope with its
  own jitter. A copy now starts at 0.14 on movement and 0.05 on aim, with a tenth of the entropy bonus.
- **The teacher's own record needs less noise than it got.** At 0.2 of full deflection the scripted fighter won only 11%
  of the fights it was recorded in; the correction rounds use 0.05.

## Development
The mod follows the MultiLoader layout: almost everything lives in `mod/common`, which is compiled against the vanilla game
and knows nothing of either loader. `mod/fabric` and `mod/neoforge` load it and hold the little that is loader specific,
such as registration and the per level tick hook that drives the agents. Common code can never reach into a loader
project.

The Gradle build is `mod/`: open that folder in IntelliJ IDEA with Java 21 as both the Gradle JVM and the project SDK, or
run `mod\gradlew.bat -p mod <task>` from the root. The Fabric and NeoForge run configurations appear under `Application`
once Gradle has synced.

## Game Tests
Game tests live in a `gametest` source set that sits next to `main` in every project. Like the main sources, the tests are
written once in `mod/common/src/gametest` and compiled into each loader project, so a single test runs on both Fabric and
NeoForge. The whole game test framework is vanilla, only the registration of a test holder is loader specific.

| Path | Holds |
| --- | --- |
| `mod/common/src/gametest` | The test framework, the utilities, the game test mixins, the tests, and `tools/BrainTool`. |
| `mod/fabric/src/gametest` | The `fabric-gametest` entry point and the Fabric implementation of the game test services. |
| `mod/neoforge/src/gametest` | The `@GameTestHolder` entry point and the NeoForge implementation of the game test services. |

Run them headless, which boots a server, runs every test, and exits:

```
gradlew :fabric:runGametest
gradlew :neoforge:runGameTestServer
```

Fabric writes a JUnit report to `mod/fabric/build/gametest/report.xml`. The normal client and server runs also load the game
test source set, so tests can be driven by hand in a dev world with `/test runall`.

Which brain drives the agents is chosen when the game starts: the scripted fighter by default, or a trained network with
`-Pbrain=neural -PbrainWeights=runs/default/weights/000100.mbw`, which is what `scripts\eval.ps1` does.

### Writing a test
Add a class under `mod/common/src/gametest/java/net/sievert/modularmobai/gametest/tests`, annotate it with `@GameTestGroup`, and
list it in `ModularMobAiGameTests`. Each `@GameTest` method resolves its structure as `<namespace>:gametest/<path>/<template>`, so
`@GameTest(template = "arena")` in a group with no path loads
`mod/common/src/gametest/resources/data/modular_mob_ai/structure/gametest/arena.nbt`. Those structures only need to declare
the size of the region the test owns; building the scenery inside it is the test's job.

Tests must not import loader specific code; only the registration of the test holder lives in each loader's game test
source set.

A run plays one suite, chosen with `-Psuite`: `arena`, the agent against a vindicator in a closed nine block box on a flat
world, which boots in seconds; `terrain`, the same fight out in the open on natural ground, which is what training uses;
or `baseline`, the villager against vindicator throughput benchmark with no agent in it.

### Tuning a run
`GameTestTuning` holds the knobs that control how a suite is executed. Every default there is the setting that measured
fastest over a ten thousand arena run, so the defaults are the optimised configuration. Each can be overridden for a single
run without editing code:

```
gradlew :neoforge:runGameTestServer -PbatchSize=250
gradlew :fabric:runGametest -PticksPerSecond=50
```

| Property | Default | What it does |
| --- | --- | --- |
| `batchSize` | framework default, 50 | How many arenas run at once. Fifty measured fastest: a run costs roughly `(arenas / batchSize) * slowestArenaInABatch * costPerTick(batchSize)`, and both smaller and larger batches lose. All ten thousand in one batch works, and takes 5.6 times as long. |
| `ticksPerSecond` | unthrottled | Ceiling on the server tick rate. The game test server never sleeps between ticks, unlike a normal server pinned to twenty, so a suite runs at whatever the machine manages, measured at roughly 150 to 330 ticks per second. A ceiling above that does nothing. |
| `reusePlots` | off | Whether each batch reuses the plots of the one before it. Roughly halves the world left on disk and costs time, because clearing the previous batch is more work than the smaller world saves. |

### Development only
The game test source set never reaches a published jar, but nothing has to be enabled by hand to run the tests. The
processed metadata always points at the game test entry point and mixin config, so every development run has them whether
it was started by Gradle or from the IDE, and the `jar` task strips them again while packaging so a published jar never
declares classes it does not ship.

Two details matter when editing that metadata. In `neoforge.mods.toml` a game test mixin entry needs a trailing
`#gametest` comment on both of its lines, since that marker is what the `jar` task removes. In `fabric.mod.json` the game
test mixin config has to stay first in the `mixins` array, because entries are dropped line by line and removing any other
one would leave a trailing comma behind.
