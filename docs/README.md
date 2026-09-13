# Documentation

Start with [architecture.md](architecture.md); the rest are how-tos.

| Doc | Read it to |
| --- | --- |
| [architecture.md](architecture.md) | understand the system: mod and trainer, the tick loop, what the agent sees and controls, the network, the reward, the fights, the code layout, every file format |
| [training.md](training.md) | train: the pipeline that works (teacher, imitation, PPO from the copy, evaluation), every script, parameter and trainer option, the run folder, the machine, troubleshooting |
| [species.md](species.md) | give another body a brain: the one place a body is declared, what follows from it automatically, what must still be written per body, what refuses a body by name, and how the wrong brain is refused |
| [testing.md](testing.md) | check a change: the arena and mechanics suites, evaluation, the parity check, running and writing game tests |
| [models.md](models.md) | find, publish and use the trained networks kept in git |
| [playing.md](playing.md) | start the game with a model, spawn agents, give them loadouts and brains, set allies and enemies, every `/mmai` command, the config and the networks the jar carries |
| [viewer.md](viewer.md) | watch recorded fights in 2D and 3D, and delete replays |
| [replay-format.md](replay-format.md) | read or write replay files |
| [findings.md](findings.md) | know what was learned the hard way before changing something that looks odd |

The trainer has its own notes in [../trainer/README.md](../trainer/README.md). Each script documents its parameters at
its top.
