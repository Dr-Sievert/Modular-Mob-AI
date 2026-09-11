# Modular Mob AI

A neural network brain for Minecraft mobs. The network runs inside the game, in plain Java, one batched forward pass per
tick for every mob on the same weights. It is trained offline in PyTorch from what the game recorded, and the new weights
are swapped in without the game restarting. Right now it learns one thing: to beat a vindicator one on one, out in the
open on natural terrain.

## Layout

```
mod/          the Minecraft mod: the whole Gradle build, one project per loader
  common/       everything both loaders share: the agent, its brain, the network runtime, the fights
  fabric/       Fabric entry points
  neoforge/     NeoForge entry points
trainer/      the PyTorch trainer (see trainer/README.md)
scripts/      what to run from a terminal
docs/         how the pieces fit, the file formats both sides share, and machine stability notes
runs/         training runs: weights, rollouts, logs, checkpoints (not in git)
```

## From a terminal

```
scripts\setup.ps1      once: Java 21, the trainer's Python environment, then the parity check
scripts\test.ps1       20 fights with the scripted brain, the quick "is anything broken" check (-Terrain for real ground)
scripts\parity.ps1     the game's forward pass against PyTorch's, in seconds
scripts\train.ps1      10,000 battles on natural terrain, all the machine can run; resumes where it left off
scripts\watch.ps1      live progress of a run, in a second terminal
scripts\eval.ps1       a trained network's win rate, no exploration
scripts\stop.ps1       stop a run and anything it left behind
```

Each script says what it takes at the top; most have `-Run`, and `train.ps1` has `-Battles`, `-Workers` and
`-Device cpu`. Read [docs/README.md](docs/README.md) before long runs on this PC.

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
