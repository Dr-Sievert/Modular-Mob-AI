# Modular Mob AI

Two halves of one mob. The **combat** half is a neural-network brain that fights inside Minecraft: a player-shaped agent
driven every tick by a small network in plain Java, trained offline with PyTorch PPO from what the game recorded, using
swords, axes, bows, crossbows and shields under a player's rules. The **mind** half is everything the fight is not —
persistent emotional and social state, episodic memory, goals, obligations, and one arbitrator choosing among skills —
rehearsed in a pure-Python settlement of dwarves before any of it goes near the game. They share a goal, a weight-file
format, and, for now, no code.

```
combat/     the mod, the trainer, the viewer, the scripts, the trained networks   Java 21, Gradle, Minecraft 1.21.1
mind/       the behaviour testbed: dwarves who remember, want, owe and brawl      Python 3, no game engine
shared/     the frozen, port-ready models the two halves agree on, byte for byte
docs/       how this layout came to be
```

## One command per half

```
cd combat    scripts\setup.ps1 once, then scripts\test.ps1     20 arena fights: expect 20/20
cd mind      python -m pytest -q tests                         expect 214 passed, 2 skipped
```

The combat half installs nothing system-wide: `scripts\setup.ps1` unpacks Java 21 and Python into `combat\.tools\` on a
machine that has neither. The mind half needs only Python 3 and the standard library to run, plus PyTorch and numpy to
retrain.

## Where `shared/` fits

The port carries the mind's two trained models into the mod as `.mbw` brains, and `.mbw` is one format with one header
and one schema-id rule that both sides have to agree on byte for byte. So the frozen artefacts live in one place rather
than in two copies that drift: [`shared/models/`](shared/README.md) holds the interpreter and the decision scorer —
weights, the layout whose sha256 *is* the schema id, a 200-record parity file each, and a specification a Java developer
can implement without reading any Python. `mind/tools/freeze.py` writes them, `mind/tools/check_parity.py` proves they
have not drifted, and [`mind/docs/port.md`](mind/docs/port.md) is the brief. Nothing goes into `shared/` until both
halves genuinely use it.

## Reading on

- [combat/README.md](combat/README.md) — what the mod does, every command, the layout, how to develop in it
- [combat/docs/](combat/docs/README.md) — architecture, training, testing, playing, publishing, and what was learned the hard way
- [mind/README.md](mind/README.md) — what the dwarves do and every command the testbed has
- [shared/README.md](shared/README.md) — the weight-file format, the schema-id rule, the parity procedure
- [docs/monorepo.md](docs/monorepo.md) — why the tree is shaped this way
- [CLAUDE.md](CLAUDE.md) — the short version for an AI assistant, including the rules about this machine

Licensed under the [LICENSE](LICENSE) at this root.
