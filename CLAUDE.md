# Modular Mob AI: notes for Claude

A neural-network brain for Minecraft mobs. A player-shaped mob, the **agent**, is driven every tick by a small network
running inside the game in plain Java. The network is trained offline with PyTorch PPO from what the game recorded.
Today its published network wins about 83 to 86% of a league of every vanilla mob that fights fair, about five points more
than the hand-written fighter it was copied from on the same bench (see docs/models.md). Bows, crossbows, shields, axes, mining and
placing work under a player's rules. That league is an Elo one and it is what a run trains and is judged on: 37 mobs, 11
squads, a difficulty ladder, the hand-written fighter held at 1500 as the anchor, published networks and the run's own
checkpoints.

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
- Mixins that change vanilla behaviour are deliberate and documented at the top of each mixin. Several fix real bugs;
  see findings.md before removing one.
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
scripts\test.ps1 -Mechanics            the item and block rules against a player's numbers: expect 46 passed
scripts\test.ps1 -Play                 the agent in a real game (jar networks, /mmai, sides, Infinity loadouts): expect 19 passed
scripts\play.ps1                       the dev client, agents on the best network in models\; -Model, -Weights, -Loader
scripts\terrain.ps1                    once per machine before any training: the terrain library a run fights on
scripts\terrain.ps1 -Add 2048          more ground appended to it, without regenerating what is already there
scripts\train.ps1 -Run <name>          train (resumes); see docs/training.md for -FromCopy, -Workers and the rest
scripts\dagger.ps1 -Run <name>         record a round of the teacher's answers to that run's own best network
scripts\watch.ps1                      live dashboard of every run, one line each
scripts\stop.ps1 -Run <name>           stop one run (bare stop.ps1 stops every run on the machine)
scripts\eval.ps1 -Weights models\blast\best.mbw        win rate of a network, 2,000 fights
scripts\bench.ps1 -Run blast -Last 4                  several networks on one bench, best first: the way to compare two
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
