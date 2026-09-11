# Testing

## The checks to run after a change

```
scripts\test.ps1                 20 fights in the closed arena with the scripted fighter
scripts\test.ps1 -Mechanics      the item and block rules against a player's numbers
scripts\test.ps1 -Play           the agent in a real game: networks in the jar, /mmai, sides, Infinity loadouts
scripts\parity.ps1               Java forward pass against PyTorch's, after touching brain\nn or the model
```

| Check | Pass looks like | Proves |
| --- | --- | --- |
| `test.ps1` | `All 20 required tests passed`; every fight 54 ticks | the observation, the body and the scripted fighter still work; the 54 ticks are deterministic, so any change in them is a behaviour change |
| `test.ps1 -Mechanics` | `All 19 required tests passed` | bows, crossbows, shields, axes, mining, placing, use slowdown and damage payment follow a player's rules |
| `test.ps1 -Play` | `All 16 required tests passed`, and `Loaded the mod's jar, modular_mob_ai/models/vs-copy.mbw from iteration 650` | what [playing.md](playing.md) promises: the bundled networks load and drive an agent, the commands, saving, sides and friendly fire, the Infinity loadouts |
| `parity.ps1` | logits agree to about 1e-6 | the game runs exactly the network PyTorch trained |

Each boots a headless server in seconds, and all of them need only Java (parity also needs the trainer's Python).
`test.ps1 -Loader neoforge` runs a suite on NeoForge instead of Fabric.

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
Real play gets `bow_infinity` and `crossbow_infinity` instead, one arrow that Infinity never uses up; see
[playing.md](playing.md) for why.

## The play suite (`gametest/tests/PlayGameTest`)

The agent as a player meets it, one test at a time: its agents have no arena bounding their view, and 32 blocks reach
into the tests either side, so the suite runs its tests one after another on one plot, cleared between them
(`GameTestTuning.soloTests`).

| Test | Checks |
| --- | --- |
| `networksInTheJarLoadByName` | the build put the networks in the jar; `best`, a name and `scripted` load; bad names and paths are refused |
| `worldAgentFightsOnTheBundledNetwork` | a playable agent on `best` goes for a zombie and hurts it |
| `aloneAnAgentStandsStill` | with nobody in view an agent stands still whatever its brain says, and moves once a zombie turns up |
| `spawnCommandMakesAnArmedAgentOnItsBrain`, `bareSpawnArmsWithTheConfigsLoadout` | `/mmai spawn`: loadout, brain, position, never despawning; `default` |
| `loadoutCommandArmsAgentsAndMobs`, `brainCommandRefusesWhatLeadsNowhere` | `/mmai loadout` on an agent and a zombie; `/mmai brain` and its refusals |
| `agentKeepsBrainAndLoadoutThroughSaving` | brain name, loadout name and the hotbar as it stands survive saving; `/summon` with `Loadout` and `BrainName` |
| `onlyEnemiesAreInTheView` | an ally never takes an enemy slot; one set against the agent does, on the next tick |
| `alliesNeverFightAndEnemiesDo` | two scripted agents after `/mmai ally` leave each other alone for 100 ticks; after `/mmai enemy` they fight |
| `mobsOnOpposingTeamsFight`, `mobsOnOneTeamLeaveEachOtherAlone` | a zombie and a vindicator on opposing teams fight; an iron golem and a zombie on one team don't |
| `agentFightsTheOtherSideWhateverItIs` | the agent ignores a cow until the cow is on the other side |
| `friendlyFireOffSparesTheSide` | an agent's blow on an ally does nothing with friendly fire off, and lands with it on |
| `infinityBowNeverRunsOut`, `infinityCrossbowNeverRunsOut` | three shots, two bolts, the one arrow still there; the training loadouts keep 64 |

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
| `suite` | `arena` (closed box), `terrain` (natural ground, what training uses), `league` (terrain, a new opponent every fight), `mechanics`, `play`, `baseline` (villager against vindicator, no agent) |
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

Arm fighters with `arena/Loadout.java`: `Loadout.BOW.equip(agent)`. The presets are `sword`, `sword_and_shield`,
`axe_and_shield`, `bow` and `crossbow`; `arena/Loadouts.byName(name, level.registryAccess())` also has `bow_infinity` and
`crossbow_infinity`. Put fighters on sides with `allegiance/Allegiance`: `Allegiance.side(a, b)`, then `Allegiance.disband`
the team when the test is done, since teams outlive it. See [playing.md](playing.md), "From code".

### Development only

The game-test source set never reaches a published jar. The processed metadata points at the game-test entry point and
mixin config for every development run, and the `jar` task strips them again while packaging.
- In `neoforge.mods.toml`, a game-test mixin entry needs a trailing `#gametest` comment on both of its lines.
- In `fabric.mod.json`, the game-test mixin config has to stay first in the `mixins` array.
