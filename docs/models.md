# Trained networks in git

`runs\` is never committed, because rollouts, demos and checkpoints run to gigabytes. The networks that matter are
copied into `models\<run>\` and committed, so anyone with the repository can fight them, evaluate them or train on from
them.

```
models\<run>\
  best.mbw      the network the game loads (the run's best evaluated checkpoint)
  model.json    what it is: run, iteration, fights, won / lost / timed out %, the code commit it was trained with
  eval.csv      every judged checkpoint of the run
  schema.json   the observation and action layout it was trained on
  state.pt      the trainer's whole state (optional), to carry the training on elsewhere
```

| Model | What | Evaluated |
| --- | --- | --- |
| `blast4` | PPO on the league, from the teacher, carried on across three runs | 86.2% won (iteration 8825, 1012 league fights); benched in one sitting at 83.3% against the scripted fighter's 78.2% |

That is one number on one bench: `blast4` fights the whole league roster, wardens and evokers included, where the retired
networks below fought a vindicator. The two are not comparable, and nothing in `models\` is comparable to anything
measured in another sitting; see the win rate section below, and `scripts\bench.ps1` in
[testing.md](testing.md) for the only way to put two networks on one scale.

## What was retired, and why

`blast` (iteration 2150, 76.3%) and `blast2` (3100, 79.5%) were the first two networks published on this layout and were
removed once `blast4` had been benched above both in one sitting (83.3% against 75.2%): every published network is bundled
into the jar, and two weaker copies of the same lineage bought nothing but size. Git history keeps them.

`league-pull05`, `league-scratch`, `league-sharp`, `league2`, `vindicator4`, `vs-copy` and `vs-scratch` were removed once
`blast` was published, which was the first network trained on the layout this build has. All seven were trained against the
humanoid's old 634-float observation, schema `f818e282`, and this build's humanoid is 792 floats, schema `9f7a1358`: the
enemy slots now say what an opponent *is* (hearts, damage, speed, size, knockback resistance, armour, a creeper's fuse,
explodes, shoots, flies, and whether it has the agent as its target), because before that a zombie and a warden filled a
slot identically and the league cost of that was 0% against the warden; and the self block now carries the clock the
reward is paid by, what is left in the quiver, the agent's own armour and what its weapon takes off.

A weight file carries the schema it was trained on and the game refuses one it cannot drive, so those seven loaded
nowhere. **There is no legacy and no backward compatibility here:** a network the build cannot load has no business in
`models\` or in the jar it is bundled into, where it costs 1.3 MB and makes `best` name something every agent will be
refused. Keeping them was not free — `best` was `vs-copy`, the highest win rate published, and it took three play-suite
tests and every agent in a real game down with it.

**Git history keeps them.** `git log --diff-filter=D -- models` finds the commit that removed them, and
`git show <commit>^:models/vs-copy/best.mbw > best.mbw` gets a file back if a measurement ever has to be re-read. What
they were measured at is recorded in [findings.md](findings.md) and [training.md](training.md), which is the part worth
keeping. See [architecture.md](architecture.md) for the layout and findings.md for why it changed.

## Publishing

```
scripts\publish.ps1 -Run blast                 copy best.mbw, eval.csv, schema.json, and write model.json
scripts\publish.ps1 -Run blast -State          and state.pt
scripts\publish.ps1 -Run blast -State -Push    and commit and push
```

A run with no evaluation yet publishes its newest weights. While supervision runs, every improved best is republished
automatically.

**What the win rate in `model.json` is, and is not.** It is the run's own evaluation, on the ground that run fought and
against the opponents that run drew: the fights its workers played, in the biomes their sites happened to be on. It is not
a claim about any other ground, and two runs' numbers are not a comparison. One retired network measured 99.8% over 553
fights of freshly generated terrain and 98.4 to 99.0% over thousands of fights on the 5,120-site terrain library, because
the library's ground is harder, not because the fighter changed — and that was the same roster. Across rosters the gap is
far wider than that. Compare two models on the same ground in one sitting with `scripts\bench.ps1` before believing a gap.

The win rate is still what `best` is picked by, in the jar and in `scripts\play.ps1` alike, because `models\` holds one
lineage's networks at a time and the number is the only thing every `model.json` has. It is a tie-break among networks
that are all publishable, not a measurement.

Networks and trainer states are binary. `.gitattributes` lists `*.mbw`, `*.mbr`, `*.pt` and `*.nbt` as binary; without
that, the repository's `* text eol=lf` default rewrites their bytes and they no longer load.

## Using a published network

- **Evaluate it:** `scripts\eval.ps1 -Weights models\blast\best.mbw`
- **Put it in a league:** `scripts\train.ps1 -Run league -Suite league -LeagueModels blast`. It is fielded as another
  agent and rated under its own name, so a run that fields it can be read beside any other run that does; see
  [training.md](training.md#published-networks-in-the-league--leaguemodels).
- **In a game:** `scripts\play.ps1` takes the best published network, `scripts\play.ps1 -Model blast` a named one; see
  [playing.md](playing.md). Underneath, the game takes
  `-Dmodular_mob_ai.brain=neural -Dmodular_mob_ai.brain.weights=<path to .mbw>`. The mod's jar carries every network
  under `models\` from the moment it's built, by name (`/mmai brain @e blast`), and `best` is the one with the highest
  win rate in its `model.json`.
- **Train on from it:**
  1. Make `runs\<new>\`.
  2. Copy in `models\<run>\state.pt` and `schema.json`, and `best.mbw` as `weights\000000.mbw`.
  3. Start `scripts\train.ps1 -Run <new>` (`-FromCopy` for the safeguarded settings).

  The teacher pull needs demos, which aren't in git; record them with `scripts\imitate.ps1 -Rounds 0`, or leave
  `--teacher-weight` at 0.

A network only loads into a game running the same observation and action layout: the schema id in the file must match.
