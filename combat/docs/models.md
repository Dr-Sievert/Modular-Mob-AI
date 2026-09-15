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

One lineage, each carried on from the one before it: `blast` → `blast2` → `blast3` (not published) → `blast4` →
`blast5` (not published) → `blast6` → `blast7` (not published) → `blast-8`.

| Model | What | Evaluated |
| --- | --- | --- |
| `provocator-1` | the first run of the new lineage: `blast8`'s last state carried on under a fresh name after that run finished on patience, pulled for 1,500 iterations towards two records of the rebuilt teacher on pack-heavy draws, then PPO alone with a fifth of its one-mob fights as packs | 85.6% won by its own run (iteration 42500, 1,017 league fights). Benched on this build, 2026-09-15, one sitting, one worker, 2,000 fights each, the crowd and the packs drawn as a run draws them: **85.5% of the league against the scripted fighter's 83.0% and `blast-8`'s 79.0%** — the first network above the anchor on the whole league; by kind of fight, one on one 88.7% (fighter 85.0, `blast-8` 84.5), one to nine idle monsters standing about **87.9% (82.8, 80.4)**, packs of two to six all attacking 55.3% (65.8, 43.1), squads 81.7% (84.6, 67.7) |

**The crowd is no longer the difference, and the pack is where the fighter still leads.** `blast-8` read every enemy slot through
its own first-layer weights, and the nine slots that are empty in a one-on-one fight had never been trained, so a bystander
standing about bent its aim; with four to nine idle monsters in view it won 35% of its fights on the bench that replaced it.
The same lineage with the slots read through attention won 79% of those the day of the conversion and 86% two days on
(`provocator-1`); the numbers and the mechanism are in [findings.md](findings.md#perception). What it has not learned yet
is the pack: several hostiles all attacking at once, where the scripted fighter, which kites and gives ground, is ten
points ahead of it, and what closes that is the pack share of the training draw rather than more of the teacher.

`blast7` was `blast6` carried on and was never published: its judged best read 1.7 points up on the crowd and 1.7 down
without it against `blast6`, inside what a sitting resolves. Its final weights are what `blast-8` was converted from.

And every number here is one bench in the older sense too: `blast-8` fights the whole league roster, wardens and evokers
included, where the retired networks below fought a vindicator. The two are not comparable, and **nothing in `models\` is
comparable to anything measured in another sitting**, since each run judges against the opponents its own matchmaking drew.
See the win rate section below, and `scripts\bench.ps1` in
[testing.md](testing.md) for the only way to put two networks on one scale.

## Names

The league is a *ludus*, the scripted fighter that every network is copied from is its *lanista*, and from here on each
generation of network is named for a gladiator class, in Latin, in this order. A **generation** is a lineage: it starts
when a network cannot be carried on from the one before it, because its shape or its layout changed, or when a lineage is
deliberately started afresh; the runs inside a generation are numbered as they are carried on from one another's state,
`secutor-1`, `secutor-2`, and the published network is `models\<generation>`, its `model.json` naming the run it came
from. The first eight runs of the previous lineage were `blast` to `blast8`, published last as `blast-8`. `blast8` finished on
2026-09-15 at iteration 38738, forty judged checkpoints without beating a best judged on the roster before that night's
fifteen new players, and the lineage carries on as `provocator-1`, seeded from its latest state rather than that best.

| Generation | The class | Why the name |
| --- | --- | --- |
| `provocator` | the challenger | the first of the new names, and the one the others are measured from |
| `secutor` | the pursuer | closes on what it fights, which is most of the league |
| `murmillo` | the heavy shield | the armoured and shielded loadouts |
| `thraex` | the curved sword | the sword fight, the one it must always win |
| `hoplomachus` | spear and small shield | reach and the guard together |
| `retiarius` | net and trident | fights at a distance and on the move: the kite |
| `sagittarius` | the archer | the bow and the crossbow |
| `dimachaerus` | two swords | the swap between hands and slots |
| `essedarius` | the charioteer | mobility: the ground, the drop, the sprint |
| `crupellarius` | the armoured one | what stands in a horde |

A name is used once; when a generation ends its name is not reused for another. `scripts\publish.ps1 -Run <run>`
publishes under the run's name, so a run is named `<generation>-<n>` from its first iteration and its network lands in
`models\<generation>-<n>`; the one to load by generation name is whichever `model.json` reads the highest win rate, as
`best` already is.

## What was retired, and why

`blast-8` (iteration 34600, 81.7% by its own run) went when `provocator-1` was published on 2026-09-15, benched 6.5 points
below it in one sitting of 2,000 fights each and behind on every kind of fight. It was the network the attention
conversion was proved on; git history keeps it.

`blast6` (iteration 12450, 83.1% by its own run) went when `blast-8` was published on 2026-09-14, benched 7.5 points below
it in one sitting of 2,000 fights each, and 27 points below it with a crowd standing about. It was the last network with
a plain first layer over the slots; the build still reads such a file, and git history keeps this one.

`blast4` (iteration 8825, 86.2% by its own run) went when `blast6` was published, and the reason is worth keeping: `best`
is picked by the win rate in `model.json`, and blast4's 86.2% — judged on ground the lava leak was still spoiling, which
made fights easier to lose and the roster's numbers unlike today's — would have outranked blast6's 83.1% although blast6's
parent checkpoint benched 84.7% against blast4's 77.2% in one sitting. Two runs' own numbers are not a comparison, and
`models\` holding one network is what keeps that rule honest.

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
