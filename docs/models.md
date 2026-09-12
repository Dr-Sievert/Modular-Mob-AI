# Trained networks in git

`runs\` is never committed, because rollouts, demos and checkpoints run to gigabytes. The networks that matter are
copied into `models\<run>\` and committed, so anyone with the repository can fight them, evaluate them or train on from
them.

> **Every network below is retired.** They were trained against the humanoid's old 634-float observation, schema
> `f818e282`, and this build's humanoid is 770 floats, schema `7d7bf60e`: the enemy slots now say what an opponent *is* —
> hearts, damage, speed, size, knockback resistance, a creeper's fuse, explodes, shoots, flies — because before that a
> zombie and a warden filled a slot identically and the league cost of that was 0% against the warden; and the self block
> now carries the clock the reward is paid by and what is left in the quiver. A weight file
> carries the schema it was trained on and the game refuses one it cannot drive, so these load nowhere and are kept only as
> a record of what was measured. The chain is being rebuilt from the teacher: record, imitate, DAgger, league. See
> [architecture.md](architecture.md) for the layout and [findings.md](findings.md) for why it changed.

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
| `vs-copy` | PPO from a copy of the scripted fighter, with the teacher pull | 99.8% won, 0.2% lost (iteration 650, 553 fights) |
| `vs-scratch` | PPO from nothing | 78.6% won (iteration 650) |
| `vindicator4` | the copy itself: imitation plus 3 DAgger rounds | 97.9% |

## Publishing

```
scripts\publish.ps1 -Run vs-copy                 copy best.mbw, eval.csv, schema.json, and write model.json
scripts\publish.ps1 -Run vs-copy -State          and state.pt
scripts\publish.ps1 -Run vs-copy -State -Push    and commit and push
```

A run with no evaluation yet publishes its newest weights. While supervision runs, every improved best is republished
automatically.

**What the win rate in `model.json` is, and is not.** It is the run's own evaluation, on the ground that run fought: the
fights its workers played, in the biomes their sites happened to be on. It is not a claim about any other ground. The
same vs-copy weights that the table above credits with 99.8% over 553 fights of freshly generated terrain measure 98.4
to 99.0% over thousands of fights on the 5,120-site terrain library, because the library's ground is harder, not because
the fighter changed. Compare two models on the same ground with `scripts\eval.ps1 -Weights` before believing a gap.

Networks and trainer states are binary. `.gitattributes` lists `*.mbw`, `*.mbr`, `*.pt` and `*.nbt` as binary; without
that, the repository's `* text eol=lf` default rewrites their bytes and they no longer load.

## Using a published network

- **Evaluate it:** `scripts\eval.ps1 -Weights models\vs-copy\best.mbw`
- **Put it in a league:** `scripts\train.ps1 -Run league -Suite league -LeagueModels vs-copy`. It is fielded as another
  agent and rated under its own name, so a run that fields it can be read beside any other run that does; see
  [training.md](training.md#published-networks-in-the-league--leaguemodels).
- **In a game:** `scripts\play.ps1 -Model vs-copy`; see [playing.md](playing.md). Underneath, the game takes
  `-Dmodular_mob_ai.brain=neural -Dmodular_mob_ai.brain.weights=<path to .mbw>`. The mod's jar carries every network
  under `models\` from the moment it's built, by name (`/mmai brain @e vs-copy`), and `best` is the one with the highest
  win rate in its `model.json`.
- **Train on from it:**
  1. Make `runs\<new>\`.
  2. Copy in `models\<run>\state.pt` and `schema.json`, and `best.mbw` as `weights\000000.mbw`.
  3. Start `scripts\train.ps1 -Run <new>` (`-FromCopy` for the safeguarded settings).

  The teacher pull needs demos, which aren't in git; record them with `scripts\imitate.ps1 -Rounds 0`, or leave
  `--teacher-weight` at 0.

A network only loads into a game running the same observation and action layout: the schema id in the file must match.
