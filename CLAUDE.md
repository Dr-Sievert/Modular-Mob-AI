# Modular Mob AI: notes for Claude

A neural-network brain for Minecraft mobs. A player-shaped mob, the **agent**, is driven every tick by a small network
running inside the game in plain Java. The network is trained offline with PyTorch PPO from what the game recorded.
Today its published network, `blast8`, wins **79.1%** of a league of every vanilla mob that fights fair, with a crowd of
idle monsters standing about a quarter of the fights and packs of a mob all attacking in a tenth — level with the
hand-written fighter it descends from (79.1%) on the same bench, one sitting, one worker, 2,000 fights each on 2026-09-14;
by kind, 85.2% one on one (that fighter 81.0), **80.9% with one to nine idle monsters in view (78.5)**, and 46.5% against
packs of two to six all attacking (67.9); see docs/models.md. The crowd was the open problem until 2026-09-14, when it was
found to be the representation and not the curriculum: the first layer read every enemy slot through its own weights, nine
of the ten had never been trained, and one bystander bent the aim by 22° a tick. A network now reads its slots through
attention heads (weight file version 3), a plain network converts in place with no retraining, and the converted network
went from 35% to 82% with four to nine idle monsters about it while its one-on-one rate did not move. **Packs that all
attack are the open problem now**: the scripted fighter kites and gives ground and wins 68% of them, the network 47%, and
the run is being pulled towards a record of the fighter's answers on pack-heavy fights. Bows, crossbows,
shields, axes, mining and placing work under a player's rules. That league is an Elo one and it is what a run trains and is
judged on: 48 mobs, 11 squads, 2 jockeys, a difficulty ladder, the hand-written fighter held at 1500 as the anchor, published networks
and the run's own checkpoints.

Read these before changing anything:

| Doc | What it covers |
| --- | --- |
| [docs/README.md](docs/README.md) | Index of every doc |
| [docs/architecture.md](docs/architecture.md) | How the mod and the trainer fit, the tick loop, the observation and action layouts, the reward, the file formats |
| [docs/training.md](docs/training.md) | How to train: teacher, imitation, PPO, evaluation, best weights, every script and flag |
| [docs/testing.md](docs/testing.md) | Game tests, the mechanics suite, evaluation, the parity check, writing tests |
| [docs/species.md](docs/species.md) | Giving another body a brain: the one place a body is declared, and what follows from it |
| [docs/models.md](docs/models.md) | Trained networks in git (`models/`), publishing, loading one |
| [docs/playing.md](docs/playing.md) | Starting the game with a model, spawning agents, loadouts, allies and enemies |
| [docs/viewer.md](docs/viewer.md) | The replay viewer |
| [docs/findings.md](docs/findings.md) | What was learned the hard way; read before "fixing" something that looks odd |

## Hard rules

- **A layout belongs to a body, not to the game.** The humanoid's observation (792 floats) and action (11 controls, 19
  network outputs) are fixed: every trained network depends on them, and a schema id stamped into every weight file refuses a
  mismatch. Don't change them without the owner's agreement. Another body brings its own layout instead of bending that one;
  see [docs/species.md](docs/species.md).
- **The agent perceives like a player, not like a radar.** A body is in its view if it is in a 100° cone about its aim with a
  line of sight, or within 6 blocks all round, or if it just hit the agent; a body it stops perceiving is remembered at its last
  known place for 3 seconds and then forgotten. One mechanism, `EnemySlots`, and the cost of it is one spatial query and a dot
  product per body in range — never a clip per body. Measured at 2,000 mobs: 230 to 410 µs a tick. Don't put the full circle
  back; see [docs/architecture.md](docs/architecture.md) and findings.md. **The network reads those slots through attention
  heads, never by position**: a head scores every occupied slot and hands the first layer one body's fields, so what the
  network computes cannot depend on how many idle bodies stand about it. The enemies-in-range count counts only the bodies
  in the fight. Both are what makes the crowd invariance structural; don't hand a slot's numbers to the first layer by
  position again, and measure any change to crowd handling by that invariance first (offline, on real rows).
- **Everything runs from `scripts\*.ps1`**, set up once by `scripts\setup.ps1`. Nothing is hardcoded to a machine; paths
  are found relative to the repository.
- **Never commit Mojang assets.** Textures come from the local Gradle cache at runtime, or the game jar.
- **`runs\` is not in git.** Rollouts, demos and checkpoints run to gigabytes. Trained networks that matter are copied
  into `models\` with `scripts\publish.ps1` and committed.
- `.mbw`, `.mbr`, `.pt` and `.nbt` are **binary** in `.gitattributes`. The repository's default is `* text eol=lf`, which
  silently corrupts binary files that aren't listed.
- **Don't build or run Gradle in a checkout that live training runs from.** Use a git worktree for development.
- **A worktree borrows the machine's trainer environment and terrain library; never link them in, and never mirror-delete a
  worktree.** A fresh worktree has no `trainer\.venv` and no `runs\terrain\...\library`, and both are found in the main
  checkout automatically (`scripts\_common.ps1`, and `mainCheckout` in the build). Linking them was done by hand in four
  worktrees at once and cost both: `git worktree remove` refuses on Gradle's deep paths ("Filename too long"), and the usual
  answers — `robocopy /MIR` from an empty folder, `Remove-Item -Recurse` — **follow a junction** and mirrored the deletion
  through it. Torch, numpy and `pyvenv.cfg` went in seconds, and half an hour of generated ground with them. If a worktree
  must be deleted by hand, `robocopy /XJ` or `rmdir /s` do not follow junctions.
- **Never `gradlew --stop` while a run is training.** Gradle daemons are shared by every worktree on the machine, and a
  live run's supervisor (`runTraining`: the memory floor, worker restarts, the round loop) is one of them. A `--stop` from
  a worktree at 08:25 on 2026-09-14 took blast7's supervisor down mid-run; the trainer and its workers carried on orphaned
  until they were stopped and restarted by hand. A worktree that wants its own daemon uses `--no-daemon` on its own call.
- Mixins that change vanilla behaviour are deliberate and documented at the top of each mixin. Several fix real bugs;
  see findings.md before removing one.
- **Everything in the game-test source set is inert outside a game-test server**, and anything added there has to be. Both
  loaders hand that source set to their client and server runs as well, so that `/test runall` works in a dev world, and
  `fabric.mod.json` lists its mixin config — so every mixin in it applies in a real game. A throughput setting that did not
  ask left a played world pitch dark with no way to light it. `GameTestTuning.gameTestServer` is the question to ask; see
  findings.md.
- **The agent's hands are a player's in all but three places, and all three are deliberate.** A drawn weapon runs to full
  once started, the hand keeps the slot it started a draw in until the draw is done, and a block keeps the crack it has
  while the aim stays on it. A network chooses each button **and its slot** afresh every tick, so without these three a bow
  fires weak arrows, a crossbow fires none at all, a bow with a sword beside it cancels its own draw, and no block is ever
  broken. Don't "restore parity" here; see [docs/architecture.md](docs/architecture.md) and findings.md.
  **A swing at a ghast's fireball sends it back**, and that is not a fourth deviation: it is a player's rule
  (`Player#attack` deflects anything in `redirectable_projectile` before it looks at the damage) that `AgentMob#resolveAttack`
  was simply missing, so the press did nothing at all. Adding it moved the hands towards a player's, not away. It does not
  kill the ghast — vanilla forgives a ghast's fire immunity only for a `Player`'s fireball — so don't plan a matchup round
  that; see findings.md.

## Everyday commands (Windows PowerShell, from the repository root)

```
scripts\setup.ps1                      once per machine: Java 21, Python + PyTorch, compile, parity check
scripts\test.ps1                       20 arena fights with the scripted fighter: expect 20/20, 54 ticks each
scripts\test.ps1 -Mechanics            the item and block rules against a player's numbers: expect 70 passed
scripts\test.ps1 -Crowd -Weights models\blast8\best.mbw    one fight with 0, 1, 3 and 9 monsters standing about it, tick by tick
scripts\test.ps1 -Pack                 the teacher, or -Weights, against packs of 2, 3 and 4 zombies and 2 and 3 vindicators, tick by tick
scripts\test.ps1 -Shots                the teacher, or -Weights, against a skeleton, a stray, a pillager and a ghast: what became of every shot
scripts\test.ps1 -Horde                20, 100, 500 and 2,000 mobs round one agent: clips a tick, time to perceive, ticks lived
scripts\test.ps1 -Play                 the agent in a real game (jar networks, /mmai, sides, loadouts, pickup, its screen): expect 29 passed
scripts\play.ps1                       the dev client, agents on the best network in models\; -Model, -Weights, -Loader
scripts\terrain.ps1                    once per machine before any training: the terrain library a run fights on
scripts\terrain.ps1 -Add 2048          more ground appended to it, without regenerating what is already there
scripts\train.ps1 -Run <name>          train (resumes); see docs/training.md for -FromCopy, -Workers and the rest
scripts\dagger.ps1 -Run <name>         record a round of the teacher's answers to that run's own best network
scripts\watch.ps1                      live dashboard of every run, one line each
scripts\stop.ps1 -Run <name>           stop one run (bare stop.ps1 stops every run on the machine)
scripts\eval.ps1 -Weights models\blast\best.mbw        win rate of a network, 2,000 fights
scripts\bench.ps1 -Run blast -Last 4 -Teacher         networks and the scripted fighter on one bench, best first: the way to compare two
scripts\publish.ps1 -Run <name> -State -Push          put a run's best network into models\ and push it
scripts\viewer.ps1                     watch recorded fights in the browser, 2D or 3D
scripts\viewer.ps1 -League             the league standings beside them: tier list, tables, per-model stats
```

## Environment notes

- Windows PowerShell 5.1. There's no `&&`, and native commands writing to stderr trip `$ErrorActionPreference = 'Stop'`.
  Variable names are case-insensitive, so `$weights` and a `-Weights` parameter are the same variable.
- Minecraft 1.21.1, Java 21, MultiLoader: `mod/common` is shared, `mod/fabric` and `mod/neoforge` are thin. Training
  and tests run on Fabric.
- Each training worker is a headless game-test server. Its heap comes from what it fights on: 1 GB on the terrain library,
  about 1.4 GB of memory in all; 2 GB where it generates its own ground. The build refuses to start more workers than free
  memory holds, and stops a run whose free memory falls under 1.5 GB.
- Workers run at below normal priority **and on the performance cores only**, which doubles a worker's throughput on a chip
  with two kinds of core: Windows otherwise parks a below-normal server thread on an efficiency core. The build measures
  which cores those are, once per machine. `-PworkerCores=all` turns it off.
- Starting a run that is already training is refused, rather than killing the live one.
