# MultiLoader Template

This project provides a Gradle project template that can compile Minecraft mods for multiple modloaders using a common project for the sources. This project does not require any third party libraries or dependencies. If you have any questions or want to discuss the project, please join our [Discord](https://discord.myceliummod.network).

## Getting Started

### IntelliJ IDEA
This guide will show how to import the MultiLoader Template into IntelliJ IDEA. The setup process is roughly equivalent to setting up the modloaders independently and should be very familiar to anyone who has worked with their MDKs.

1. Clone or download this repository to your computer.
2. Configure the project by setting the properties in the `gradle.properties` file. You will also need to change the `rootProject.name`  property in `settings.gradle`, this should match the folder name of your project, or else IDEA may complain.
3. Open the template's root folder as a new project in IDEA. This is the folder that contains this README.md file and the gradlew executable.
4. If your default JVM/JDK is not Java 21 you will encounter an error when opening the project. This error is fixed by going to `File > Settings > Build, Execution, Deployment > Build Tools > Gradle > Gradle JVM` and changing the value to a valid Java 21 JVM. You will also need to set the Project SDK to Java 21. This can be done by going to `File > Project Structure > Project SDK`. Once both have been set open the Gradle tab in IDEA and click the refresh button to reload the project.
5. Open your Run/Debug Configurations. Under the `Application` category there should now be options to run Fabric and NeoForge projects. Select one of the client options and try to run it.
6. Assuming you were able to run the game in step 5 your workspace should now be set up.

### Eclipse
While it is possible to use this template in Eclipse it is not recommended. During the development of this template multiple critical bugs and quirks related to Eclipse were found at nearly every level of the required build tools. While we continue to work with these tools to report and resolve issues support for projects like these are not there yet. For now Eclipse is considered unsupported by this project. The development cycle for build tools is notoriously slow so there are no ETAs available.

## Development Guide
When using this template the majority of your mod should be developed in the `common` project. The `common` project is compiled against the vanilla game and is used to hold code that is shared between the different loader-specific versions of your mod. The `common` project has no knowledge or access to ModLoader specific code, apis, or concepts. Code that requires something from a specific loader must be done through the project that is specific to that loader, such as the `fabric` or `neoforge` projects.

Loader specific projects such as the `fabric` and `neoforge` project are used to load the `common` project into the game. These projects also define code that is specific to that loader. Loader specific projects can access all the code in the `common` project. It is important to remember that the `common` project can not access code from loader specific projects.

## Game Tests
Game tests live in a `gametest` source set that sits next to `main` in every project. Like the main sources, the tests are
written once in `minecraft/common/src/gametest` and compiled into each loader project, so a single test runs on both Fabric and
NeoForge. The whole game test framework is vanilla, only the registration of a test holder is loader specific.

| Path | Holds |
| --- | --- |
| `minecraft/common/src/gametest` | The test framework, the utilities, the game test mixins, and the tests themselves. |
| `minecraft/fabric/src/gametest` | The `fabric-gametest` entry point and the Fabric implementation of the game test services. |
| `minecraft/neoforge/src/gametest` | The `@GameTestHolder` entry point and the NeoForge implementation of the game test services. |

Run them headless, which boots a server, runs every test, and exits:

```
gradlew :fabric:runGametest
gradlew :neoforge:runGameTestServer
```

Fabric writes a JUnit report to `minecraft/fabric/build/gametest/report.xml`. The normal client and server runs also load the game
test source set, so tests can be driven by hand in a dev world with `/test runall`.

### Writing a test
Add a class under `minecraft/common/src/gametest/java/net/sievert/modularmobai/gametest/tests`, annotate it with `@GameTestGroup`, and
list it in `ModularMobAiGameTests`. Each `@GameTest` method resolves its structure as `<namespace>:gametest/<path>/<template>`, so
`@GameTest(template = "arena")` in a group with no path loads
`minecraft/common/src/gametest/resources/data/modular_mob_ai/structure/gametest/arena.nbt`. Those structures only need to declare
the size of the region the test owns; building the scenery inside it is the test's job.

Tests must not import loader specific code. Anything a test needs from a loader goes through `GameTestServices`, which
mirrors the `Services` lookup the main source set uses, with the implementations living in each loader's game test source
set.

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
