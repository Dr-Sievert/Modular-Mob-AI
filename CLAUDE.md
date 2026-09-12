# Modular Mob AI: notes for Claude

A neural-network brain for Minecraft mobs. A player-shaped mob, the **agent**, is driven every tick by a small network
running inside the game in plain Java. The network is trained offline with PyTorch PPO from what the game recorded.
Today it beats a vindicator one on one on natural terrain (about 99.8%). Bows, crossbows, shields, axes, mining and
placing work under a player's rules. An ELO league against every vanilla mob that fights fair is being built.

Read these before changing anything:

| Doc | What it covers |
| --- | --- |
| [docs/README.md](docs/README.md) | Index of every doc |
| [docs/architecture.md](docs/architecture.md) | How the mod and the trainer fit, the tick loop, the observation and action layouts, the reward, the file formats |
| [docs/training.md](docs/training.md) | How to train: teacher, imitation, PPO, evaluation, best weights, every script and flag |
| [docs/testing.md](docs/testing.md) | Game tests, the mechanics suite, evaluation, the parity check, writing tests |
| [docs/species.md](docs/species.md) | Giving another body a brain: a schema per species, and the four files a new one takes |
| [docs/models.md](docs/models.md) | Trained networks in git (`models/`), publishing, loading one |
| [docs/playing.md](docs/playing.md) | Starting the game with a model, spawning agents, loadouts, allies and enemies |
| [docs/viewer.md](docs/viewer.md) | The replay viewer |
| [docs/findings.md](docs/findings.md) | What was learned the hard way; read before "fixing" something that looks odd |

## Hard rules

- **A layout belongs to a body, not to the game.** The humanoid's observation (634 floats) and action (11 controls, 19
  network outputs) are fixed: every trained network depends on them, and a schema id stamped into every weight file refuses a
  mismatch. Don't change them without the owner's agreement. Another body brings its own layout instead of bending that one;
  see [docs/species.md](docs/species.md).
- **Everything runs from `scripts\*.ps1`**, set up once by `scripts\setup.ps1`. Nothing is hardcoded to a machine; paths
  are found relative to the repository.
- **Never commit Mojang assets.** Textures come from the local Gradle cache at runtime, or the game jar.
- **`runs\` is not in git.** Rollouts, demos and checkpoints run to gigabytes. Trained networks that matter are copied
  into `models\` with `scripts\publish.ps1` and committed.
- `.mbw`, `.mbr`, `.pt` and `.nbt` are **binary** in `.gitattributes`. The repository's default is `* text eol=lf`, which
  silently corrupts binary files that aren't listed.
- **Don't build or run Gradle in a checkout that live training runs from.** Use a git worktree for development.
- Mixins that change vanilla behaviour are deliberate and documented at the top of each mixin. Several fix real bugs;
  see findings.md before removing one.

## Everyday commands (Windows PowerShell, from the repository root)

```
scripts\setup.ps1                      once per machine: Java 21, Python + PyTorch, compile, parity check
scripts\test.ps1                       20 arena fights with the scripted fighter: expect 20/20, 54 ticks each
scripts\test.ps1 -Mechanics            the item and block rules against a player's numbers: expect 23 passed
scripts\test.ps1 -Play                 the agent in a real game (jar networks, /mmai, sides, Infinity loadouts): expect 17 passed
scripts\play.ps1                       the dev client, agents on the best network in models\; -Model, -Weights, -Loader
scripts\terrain.ps1                    once per machine before any training: the terrain library a run fights on
scripts\terrain.ps1 -Add 2048          more ground appended to it, without regenerating what is already there
scripts\train.ps1 -Run <name>          train (resumes); see docs/training.md for -FromCopy, -Workers and the rest
scripts\dagger.ps1 -Run <name>         record a round of the teacher's answers to that run's own best network
scripts\watch.ps1                      live dashboard of every run, one line each
scripts\stop.ps1 -Run <name>           stop one run (bare stop.ps1 stops every run on the machine)
scripts\eval.ps1 -Weights models\vs-copy\best.mbw     win rate of a network, 2,000 fights
scripts\publish.ps1 -Run <name> -State -Push          put a run's best network into models\ and push it
scripts\viewer.ps1                     watch recorded fights in the browser, 2D or 3D
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
