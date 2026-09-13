# Modular Mob AI

A neural-network brain for Minecraft mobs. A player-shaped mob, the agent, is driven every tick by a small network (792
inputs, a GRU memory, 11 controls) that runs inside the game in plain Java, one batched pass per tick for every mob on
the same weights. It's trained offline with PyTorch PPO from what the game recorded, and new weights swap in without the
game restarting.

Its published network wins about 75 to 80% of a league of every vanilla mob that fights fair, within a few points of the hand-written fighter it was copied from on the same bench. It uses swords, axes, bows, crossbows and
shields, and mines and places blocks, under the same rules as a player. Next: an ELO league against most vanilla mobs,
itself and past versions, with random loadouts.

## Quick start

Windows 10 or 11 and a clone of this repository; nothing else has to be installed first.

```
scripts\setup.ps1                                   once: Java 21, Python + PyTorch, compile, parity check
scripts\test.ps1                                    20 fights with the scripted fighter: expect 20/20
scripts\eval.ps1 -Weights models\blast\best.mbw     the published network's win rate
scripts\play.ps1                                    Minecraft with the trained network; /mmai spawn in a world, see docs\playing.md
scripts\train.ps1 -Run mine                         train a run of your own; see docs\training.md
scripts\watch.ps1                                   live progress of every run
scripts\viewer.ps1                                  watch recorded fights in 2D or 3D
```

Setup installs nothing system-wide: a machine without Java 21 or Python gets them unpacked into `.tools\`, which git
ignores.

## Documentation

Everything is in [docs/](docs/README.md): how it works, how to train, test, play and publish, and what was learned along
the way. [CLAUDE.md](CLAUDE.md) is the short version for an AI assistant working in this repository.

## Layout

```
mod/        the Minecraft mod (MultiLoader: common, fabric, neoforge), Minecraft 1.21.1, Java 21
trainer/    the PyTorch trainer
viewer/     the replay viewer
scripts/    everything you run
models/     trained networks, in git
docs/       documentation
runs/       training runs (not in git)
```

## Development

Open `mod/` in IntelliJ IDEA with Java 21 as the Gradle JVM and the project SDK, or run `mod\gradlew.bat -p mod <task>`
from the root. Almost everything lives in `mod/common`, compiled against the vanilla game; `mod/fabric` and
`mod/neoforge` hold registration and the per-level tick hook. Common code never reaches into a loader project.
