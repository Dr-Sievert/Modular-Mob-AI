# Training

Everything here runs from the repository root in Windows PowerShell. `scripts\setup.ps1` must have run once.

## The pipeline that works

Training from nothing learns slowly: vs-scratch reached 78.6% after about 650 iterations. The route that reaches ~100% is
to copy a hand-written fighter first, then improve the copy with reinforcement learning.

1. **The scripted teacher.** `brain/ScriptedBrain.java` wins about 98.6% against a vindicator on terrain. It plans a
   path over the agent's own terrain grid, keeps the vindicator between 2.4 and 3.1 blocks, swings only at full strength
   and never into a block, and drops down to a target stuck in a pit. It sees only what the network sees.

   Being in something that hurts comes before the fight: a blow knocks a body onto ground the planner would never have
   walked onto, and powder snow is the one it cannot leave by itself. It heads out by the shortest way, and breaks the
   block when walking gets nowhere, which covers cobwebs and berry bushes too.

   It also uses everything it carries, which matters because PPO only improves what it samples. Seeded from a teacher
   that never pressed use, the first league run held use on 0 of 79,724 ticks over its last 400 fights, fired no arrows
   and raised no shield: a bow needs twenty ticks of held use before the first arrow flies, and nothing in the reward
   finds that by accident. So the teacher now also:
   - **draws a bow** when the target is out of reach with a clear line **and cannot be here before the draw is full**,
     holds it to full power, aims with gravity and drag worked out by bisection and leads the target, looses when the shot
     is on, and goes back to the sword when the target closes or the quiver runs out. The draw takes twenty ticks and only
     a change of slot gets out of one, so a draw begun at something that arrives first is an arrow thrown away: it reads
     the slot's own speed, or the velocity the thing is already coming at, whichever is faster. Anything that shoots back
     or flies is worth a draw at any range, since closing is no answer to either;
   - **winds a crossbow**, holds the bolt, and fires when aimed. A bow and a crossbow read as one item category, and it
     tells them apart from what a release does rather than from the layout;
   - **raises a shield** against something inside reach while its own swing cools, against anything with a bow in its
     hands that has got close, against a reach longer than a man's, and against a shot already in the air;
   - **blocks a ravager to stun it**, which the reach rule covers: only a ravager swings from four blocks away, and a
     blocked ravager is stunned for two seconds and carries no axe to knock the shield aside;
   - **backs away from a lit creeper**: empty hands, never seen to swing, and stopped coming within three blocks is a
     creeper with its fuse lit, and it walks clear to seven and a half blocks before coming back.

   All of it is decided from the observation and a little state per agent, which the network has 128 numbers of memory
   for; see the class comment for what that state is and why every bit of it is checked against the body.

   It also chooses how to throw each blow, since vanilla gives one swing three shapes and allows at most one of them:
   - **into a hazard** wherever it can line one up. The knockback goes exactly along the agent's own look, so a hazard
     directly behind the target is somewhere the target can be pushed, and the ground kills it. It searches the reachable
     spots for one that puts agent, target and hazard on a line, and sprints into the blow from there. Lava, fire, magma
     and the edge of a drop nothing survives all count, which is what the terrain grid's hazard mark is for;
   - **for the knockback** otherwise, whenever what is in front of it is worth having further off: a reach longer than a
     man's, empty hands that have not swung, or health already spent. A sprint is forward only, so it is never asked for
     while backing away;
   - **for the critical**, half again the damage, when neither applies, by leaving the ground exactly as many ticks ahead
     of a full cooldown as a jump spends coming down. An earlier attempt guessed that gap; this one reads the cooldown's
     own rate off two ticks of the observation, so it holds for a sword and an axe alike.
2. **Imitation**, with `scripts\imitate.ps1 -Run <copy>`:
   - It records the teacher's fights with its movement and aim pushed off by noise (DART, 0.1 of full deflection), so
     the record includes getting back on target.
   - It copies that record into a network by behaviour cloning.
   - Then it runs rounds of DAgger: the copy drives with light noise (0.05) and the teacher labels every tick, then the
     network is copied again from everything so far.
   - `runs\vindicator4`, made this way, wins 97.9% on its most likely action.
   - **One imitation at a time on one machine.** Cloning holds the whole record in memory — a 4,000-fight record is some
     two million ticks, and at 770 floats a tick that is about 7 GB — so two of them side by side is not a matter of
     workers. Tried: a second imitation started beside one already cloning took free memory to nothing and the build
     stopped the first one's workers to protect the machine. Two *training* runs are fine; two imitations are not.
3. **Reinforcement learning from the copy**, with `scripts\train.ps1 -Run <run> -FromCopy` (start by copying the copy's
   `state.pt`, `schema.json`, `weights\000000.mbw` and a link to its `demos` into the new run; `scripts\compare.ps1` does
   this). It's PPO with safeguards, because plain PPO made a good copy worse twice:
   - four times the experience per update (65,536 steps);
   - smaller, bounded steps: learning rate 5e-5, clip 0.1, target KL 0.01;
   - 30 iterations where only the critic learns;
   - little exploration: entropy 0.001, and a narrow spread on aim;
   - a pull back towards the teacher's answers (`--teacher-weight 0.5`: imitation loss on a sample of the recorded demos),
     **falling to nothing over `--teacher-decay` iterations** (1,500) from the first one that pulled, and sooner if
     evaluation says the teacher has stopped helping.

     The fall is the point. Held at full strength for a whole run, the pull is a ceiling and not a floor: a league run at
     0.2 for its entire life beat every ordinary mob 75 to 98% and still lost to the scripted fighter it had been copied
     from, 32.5% over 200 fights, because an update that always carries an instruction to answer as the teacher would
     cannot arrive anywhere the teacher is not. The teacher starts the copy and then gets out of the way. `--teacher-decay 0`
     holds it where it is for ever, which is what the old behaviour was. Where the fall counts from is kept in the run's
     state, so resuming does not start it over and a run cannot hold itself at full pull by being restarted.

     **The fall also has to finish inside the run's own life**, which a horizon of 6,000 did not. Measured on `league768`:
     the pull started at 0.5, the run stopped improving at iteration 925, had still not beaten that checkpoint 1,200
     iterations later — 54.5% against the best candidate's 54.3% over the 84 opponents they both met — and at iteration
     2,212 was being pulled at 0.316, 63% of what it began with. Patience ends a run about 1,000 iterations after its
     best, so that horizon was never reached and the pull never left.

     So a horizon is only the outer bound, and evaluation decides the rest: once `--teacher-release` judged checkpoints in
     a row (6) have failed to beat the best while the pull is still on, the rest of it goes over `--teacher-release-over`
     iterations (200). A run that is still being pulled and has stopped improving is the shape of a ceiling, and the pull
     is the first thing to suspect. It is said once and kept in the run's state; a run that improves again afterwards
     keeps its teacher released, because it improved without it. `--teacher-release 0` waits for the horizon instead.
4. **Evaluation inside the run.** Every 25th iteration's checkpoint is played by the workers on its most likely action,
   in one fight in ten, until it has had `--eval-fights` fights (500 by default).
   - Verdicts go to `eval.csv`, and the best checkpoint's weights to `best.mbw`.
   - **Which is best is decided on the opponents two checkpoints both met**, each opponent counting once however often it
     was drawn, and a new best has to win by a point. A league's roster grows as rungs and squads open, so the plain win
     rate asks a harder question of a later checkpoint and once kept iteration 1175 as the best for ever; the Elo rating was
     tried next and is worse, since it wanders by thirty to forty points with the field and picking its maximum picks the
     luckiest draw. The rating is still written down, and decides nothing. Only checkpoints are excluded from the record
     that decides this — a fight against a mob, the scripted fighter or a published network is what counts, since those hold
     still.
   - The run is done when a checkpoint reaches `--eval-target` (0.995), or after `--eval-patience` judged checkpoints in
     a row without a new best (10).
   - The build then starts no more rounds, and the `finished` file says why.
5. **Publishing.** `scripts\publish.ps1 -Run <run> -State -Push` copies the best network into `models\<run>\` and pushes
   it; see [models.md](models.md).

Results so far (evaluated on the most likely action):

| Run | Started from | Best | Won / lost / timed out |
| --- | --- | --- | --- |
| vindicator4 | imitation + 3 DAgger rounds | 97.9% | |
| vs-copy | vindicator4, PPO with the teacher pull | iteration 650 | 99.8 / 0.2 / 0.0 (553 fights) |
| vs-scratch | nothing | iteration 650 | 78.6 / 18.4 / 3.0 (500 fights), still climbing |

## The terrain library, before any training

```
scripts\terrain.ps1                       4,096 fight sites, on as much of the machine as fits
scripts\terrain.ps1 -Add 2048             2,048 more, appended to the library that is already there
scripts\terrain.ps1 -Sites 8192           a new library of that size; about 0.8 MB and 1.5 s of one builder per site
scripts\terrain.ps1 -Radius 3             bigger sites, 112 blocks across rather than 80
```

Training on natural ground reads its sites from the library and generates nothing, so a run stops and says to build one
if it is missing (`-PterrainLibrary=false` asks for the old behaviour, where every worker generates its own ground).
Generating cost two to three cores and a third more memory per worker, and memory is what caps how many workers run.

- It lives in `runs\terrain\<minecraft version>\library` and is not in git: about 0.8 MB a site, and machine-local.
- Workers hard-link its region files, so it is on disk once however many run, and nothing can write back to it.
- **`-Add` grows it instead of rebuilding it.** Only the new sites are generated, in blocks of ground well clear of every
  block already in the library, and the points that existed keep the numbers they had. Measured: 1,024 sites appended to
  the 4,096-site library took 9 minutes on two builders and added 797 MB, against about 38 minutes to generate all 5,120
  again. A worker then reads all 5,120 and fights normally.
- Building a new one, or adding to one, while training runs is safe: a new library replaces the old one only once it is
  whole, and an addition's ground is moved in whole before a new index is moved over the old one, so nothing half finished
  is ever readable. A worker already running keeps its own links and never rereads the index.
- Run it once per machine, and again whenever you want fresh ground. Build a new one rather than adding when a block's
  layout or a site's size changes, since a point's number is worked out from the layout and the ground a site needs from
  the spacing; the build refuses to append blocks of a different shape to what is already there.
- The index can carry facts about a point beyond whether a fight can start on it, as `kinds=lava,ravine` and then
  `kind.lava=3,17,42`, read, written and merged on append exactly as the unusable points are. Nothing fills them in yet;
  that is where sites the league should draw hazardous ground from will be named. A reader that does not know a kind ignores
  it, so adding one needs no rebuild.
- `-Radius` is how much ground one site holds, in chunks either side of its centre: 2 (the default) is 80 blocks across,
  3 is 112. It is the library's property, and the only way a fight gets more ground than 80 blocks; what a matchup can ask
  for on its own is how far apart it starts and how much air it wants overhead. A run may use a library built for bigger
  sites and fight on the inner part of each one; one built for smaller is refused, with the `-Radius` to rebuild at.
  Measured from 2 to 3, on 320 sites and one worker fighting 300 league fights off them:

  | | radius 2 (80 blocks) | radius 3 (112 blocks) |
  | --- | --- | --- |
  | disk a site | 0.84 MB | 1.38 MB (4,096 sites: 3.1 GB to about 5.5 GB) |
  | build, 128 sites on one builder | 208 s | 272 s |
  | worker heap it runs in | 1 GB | 1 GB, unchanged |
  | 300 fights, one worker | 40 s | 50 s |

  So the price of the bigger site is disk and about a quarter of the throughput, not memory: twice the chunks to tick, in
  the same heap.

## Scripts

### `scripts\train.ps1`: one run

```
scripts\train.ps1                                  10,000 battles on run 'default', then stops
scripts\train.ps1 -Run name -Battles 0             until evaluation says it's done, or Ctrl+C
scripts\train.ps1 -Run name -FromCopy              the safeguarded PPO above, for a run started from a copy
scripts\train.ps1 -Run name -Workers 4             4 workers; 0 (default) means as many as cores and memory allow
scripts\train.ps1 -Run name -Extra '--eval-target 0.999 --eval-fights 2000'    any trainer option, see below
scripts\train.ps1 -Run name -Full                  everything the build prints, instead of the half-minute feed
scripts\train.ps1 -Run league -Suite league -Seed vs-copy     the league, from vs-copy's best (see the league below)
```

| Parameter | Default | Meaning |
| --- | --- | --- |
| `-Run` | default | folder under `runs\`; an existing run resumes from its `state.pt` |
| `-Battles` | 10000 (0 with `-FromCopy`) | stop after this many fights; 0 means run until evaluation says done |
| `-RoundSize` | 250000 | fights per round; each round starts fresh worker processes |
| `-Workers` | 0 = auto | worker processes (game servers) |
| `-Slots` | 25 | fights at once per worker |
| `-Heap` | from the suite | heap per worker: the build gives 1G on the terrain library, where a worker holds about half a gigabyte live, and 2G where a worker generates its own ground and settles at about 0.95 GB |
| `-SiteRadius` | 2 | chunks either side of a fight site's centre: 2 is 80 blocks across, 3 is 112. No more than the terrain library was built for; 3 costs about a quarter of the throughput and no extra heap |
| `-RolloutSteps` | 16384 (65536 with `-FromCopy`) | steps of experience per update (an iteration) |
| `-Device` | cuda | `cpu` keeps the GPU out of it |
| `-Suite` | terrain | `arena` is a closed 9-block box, for quick checks; `league` is every mob, the scripted fighter and the run's own checkpoints |
| `-LeagueModels` | | league only: published networks in `models\` to field as rated players, `vs-copy,vs-scratch`; see the league below |
| `-ReplayEvery` | 200 | record one fight in this many per worker, for the viewer; 0 for none |
| `-FromCopy` | off | the safeguarded settings for a run that starts from a copy |
| `-Seed` | | start a new run from another's best checkpoint state: `runs\<seed>`, else `models\<seed>\state.pt`, else a folder by path; gentle settings as `-FromCopy` but no teacher pull, the critic alone for 30 iterations, 65536 steps and no battle limit by default |
| `-TeacherWeight` | 0 | pull every update back towards the teacher's recorded answers in `runs\<run>\demos`; see `scripts\dagger.ps1` below |
| `-Full` | off | the whole build output |
| `-Extra` | | options passed to the trainer |

A run is refused if it's already training, rather than killing the live one. The console shows a line every half
minute: iteration, ticks/s, fights/s, training win rate, fight length; plus evaluations, rounds and errors.

### Trainer options (`-Extra`)

Every field of `Config` in `trainer/mmai/ppo.py` is an option, as `--field-name value`. The useful ones:

| Option | Default | Meaning |
| --- | --- | --- |
| `--learning-rate` | 3e-4 | Adam step size |
| `--clip` | 0.2 | PPO clip range |
| `--target-kl` | 0.02 | stop an update early past this KL |
| `--epochs` | 4 | passes over each iteration's data |
| `--entropy-coef` | 0.01 | exploration bonus |
| `--critic-warmup` | 0 | iterations where only the critic learns |
| `--teacher-weight` | 0 | pull towards the run's demos; needs `runs\<run>\demos` |
| `--seq-len` | 32 | ticks of GRU unrolled per training chunk |
| `--h1 --hidden --h3` | 256, 128, 128 | network widths; only for a new run, and the game needs no change |
| `--eval-fights` | 500 | fights per judged checkpoint (2,000 tells 99.6% from 99.9%) |
| `--eval-patience` | 10 | judged checkpoints without a new best before done |
| `--eval-target` | 0.995 | win rate at which the run is done at once |
| `--checkpoint-every` | 25 | iterations between kept checkpoints, and so between evaluations |
| `--league-pool`, `--league-recent` | 8, 4 | checkpoints the league agent meets, and how many of them are the newest |
| `--league-self-play` | 0.2 | share of league training fights against those checkpoints |
| `--league-floor` | 0.25 | share of each group's fights spread evenly, whatever the agent's chances |
| `--league-frontier` | 0.15 | below this chance an opponent keeps only `--league-probe` of that even floor: learning happens where fights are close, and an even floor over a hundred opponents was spending 9% of a run on sixteen it never once beat |
| `--league-probe` | 0.2 | how much of the floor those keep, so they are still tried now and then — one that is hopeless at a thousand iterations may not be at ten thousand |
| `--league-k` | 16 | Elo K (twice that for a player's first `--league-provisional` 30 rated fights) |
| `--league-hard-at`, `--league-easy-below` | 0.80, 0.20 | evaluated win rate at which an opponent's hard or easy rung opens |
| `--league-rung-fights` | 30 | evaluation fights an opponent needs before a rung can open |

### The league: `-Suite league` and `scripts\league.ps1`

A league run fights 37 mobs, 11 squads of several mobs at once, the scripted fighter, any published networks it was told
to field, and frozen checkpoints of itself, with a loadout drawn every fight; see
[architecture.md](architecture.md#the-league). Matchmaking sends training fights where the agent wins about half the
time, and what it draws is a **pairing** of one loadout with one opponent rather than the opponent alone, so a bow is handed
out against the opponents a bow can learn from; see the pair table below. Evaluation fights are drawn evenly, loadout
included, and rated. A checkpoint is judged on 1,000 evaluation fights over the mobs and
the scripted fighter, and the run is done after ten judged checkpoints in a row without a new best. An opponent the
workers cap, which today is only the warden, takes no more than its cap of the training fights however even the fight
looks, and is rated on as many evaluation fights as any other.

Every opponent also has a harder and an easier rung, `zombie(hard)` and `zombie(easy)`, which the run opens for itself as
the agent earns them and then rates as players of their own. Nothing needs asking for: the log says
`zombie is met on hard from now on: 84% of the last 40 evaluation fights on normal`, and the tier list shows both.

A quarter of the fights are drawn onto ground with something on it worth knocking an opponent into, lava or a cliff edge
(`-PleagueHazards=0.25`, 0 for none). Whether the agent is learning that trick is the `ground %` column of
`scripts\league.ps1` and `league/ground.csv`: how many of its wins on each kind of ground were finished by the ground
rather than by the agent. The log says `the ground finished the opponent in lava 8% of 240 wins; drop 3% of 510 wins`.

```
scripts\train.ps1 -Run league -Suite league -Seed vs-copy     start one from vs-copy's best, run until done
scripts\league.ps1 -Run league                                the tier list, the record against each opponent and loadout
scripts\league.ps1 -Run league -All                           every rated checkpoint in the tier list
scripts\league.ps1 -Test                                      the unit tests of the Elo, the pairings and the pool arithmetic
scripts\eval.ps1 -Run league -Suite league                    a network round every opponent, with a table at the end
scripts\viewer.ps1 -League                                    all of it in the browser, and the per-model stats besides
```

#### What a training fight is drawn as: `league/pairs.csv`

The unit of matchmaking is a pairing of one loadout with one opponent, because the two used to be drawn independently and that
spent a run's fights in the wrong places. A bow went to a creeper it should kite exactly as often as to a ghast it cannot
reach, so the gradient reaching the drawing of a bow was an average over the matchups where a bow is the answer and the
matchups where it is hopeless; measured on a league run, the ranged loadouts won about 40% of their fights and the melee ones
far more. Pairing them puts the fights where a loadout can still learn something, and hands a loadout that is losing more of
the matchups it is losing. A pairing near an even result gets the most fights, a floor keeps every one of them coming round,
and a cap is the opponent's: the warden's two thousandths cover every loadout against it between them.

The table is loadouts times opponents: 490 pairings at the start of a run (10 loadouts against 48 mobs and squads plus the
scripted fighter), 1,450 once every rung of the ladder is open, and 80 more for the self-play pool. A pairing's own record is
thin at that size — a couple of thousand fights fade through the whole table, so single figures each and plenty with none — so
**a pairing's chance is never asked to stand on its own**: it is the pairing's own record over a prior worth `--league-prior`
fights, and that prior is the opponent's chance moved by how the loadout does over all of its fights, which is a tenth of the
run's and dense enough to mean something. With nothing recorded anywhere the prior is exactly the opponent's chance, so a
fresh run draws as it always did and only separates as the fights say it should. Evaluation fights are not paired: they draw
the opponent evenly and the loadout evenly, since every rating is measured on them.

`pairs.csv` is `loadout,opponent,share,chance,fights,wins`, largest share first, and `matchmaking.csv` is the same shares
added up per opponent. Both are rewritten every iteration, and the log says `the pairings with the most: bow against ghast
1.4%; sword against ravager 1.3%; axe against ravager 1.2%`. The workers say which loadouts they field in `roster.csv`, under
the kind `loadout`; a worker that finds no pair table draws from `matchmaking.csv` with the loadout even, which is what a run
whose trainer is older than the pairings does.

#### Published networks in the league: `-LeagueModels`

A run only rates its own checkpoints, so two lineages never meet: a run trained from the teacher and one trained from
nothing each have a tier list, and the only player they share is the scripted fighter. Naming a published network puts
them on one list.

```
scripts\train.ps1 -Run league -Suite league -LeagueModels vs-copy,vs-scratch
scripts\test.ps1 -League -LeagueModels vs-copy                a quick look with one in it
```

Each name is a folder under `models\`. It is fielded as another agent on its most likely action, exactly as a frozen
checkpoint is, and **rated under its own name**, so `vs-copy` appears in the tier list, in `ratings.csv`, in
`opponents.csv` and in the viewer beside the mobs. What it takes to read two runs together is that both field the same
network: each rates it on its own fights, and the two answers should agree to within a tier. Where they do not, one run
has met it far too seldom or the scales have drifted, and the tier lists should not be read against each other — the
viewer's *Two runs* tab says so outright.

What was chosen, and why:

- **One anchor, still.** The scripted fighter alone is held at 1500. A model enters where everyone enters and its rating
  moves on its fights like a mob's. Holding a model still as well would assert the distance between it and the scripted
  fighter instead of measuring it, and every rating between the two would be pulled towards whatever that assumption was
  wrong by. Nothing about the scale moves by adding players: every rated fight is zero sum except against the anchor,
  whose K is zero, so a newcomer takes its points from the opponents it actually beats.
- **In the mobs' group, not the self play share.** A model never learns, so matchmaking weighs it with the mobs and the
  scripted fighter, towards the even fight, under the same floor. The self play share stays what it was, for the run's
  own moving pool of checkpoints, which is what it is for.
- **Every loadout**, as a checkpoint gets. The scripted fighter is melee only because it is the anchor and its strength
  may not move under a run already going; a network fielded for the first time has nothing to hold still for.
- **Its fights judge a checkpoint**, as the scripted fighter's do and a checkpoint's do not: what an evaluation fight has
  to measure is an opponent that holds still.
- **A rung of the difficulty ladder belongs to a mob or a squad**, and neither a model nor the scripted fighter gets one.

A name is refused, by name and before any fight is set up, when no models folder and no jar has a network for it, when
something in the league already answers to it (a mob, a squad, a rung, `scripted`, or the shape a checkpoint is written
in), or when the network was trained for **another body** — the message names both bodies, since a beast's network cannot
drive a humanoid at all; see [species.md](species.md).

### `scripts\compare.ps1`: from the copy and from nothing, side by side

```
scripts\compare.ps1                                  copy of runs\vindicator4 vs from nothing, runs vs-copy / vs-scratch
scripts\compare.ps1 -CopyWorkers 4 -ScratchWorkers 2
```

This sets up `runs\<prefix>-copy` from the copy the first time (state, weights, a junction to its demos) and starts both
runs in the background, with output going to each run's `console.log`. The copy's run stops when evaluation says done;
the run from nothing only stops at `-ScratchBattles` (3,000,000).

### `scripts\dagger.ps1`: correct a run that is already training

```
scripts\dagger.ps1 -Run league                    one round for runs\league on the league, from its best weights
scripts\dagger.ps1 -Run league -Fights 8000       more of it
scripts\dagger.ps1 -Run league -Weights models\vs-copy\best.mbw     another network's mistakes to correct
scripts\dagger.ps1 -Run vindicator -Suite terrain one vindicator instead, as imitate.ps1's rounds are
```

This is `imitate.ps1`'s correction round on its own, for a run past imitation: the run's own best network drives, the
scripted fighter says what it would have done on every tick, and the answers go into the run's `demos`. What is new is
the suite. On the league the student meets every mob and every loadout in turn, so the record covers a bow, a crossbow, a
shield and all 26 opponents, which is what a league run can usefully be pulled back towards; a record of one fight
against one vindicator is not.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `-Run` | required | the run whose demos to add to |
| `-Fights` | 4000 | fights recorded, about fifteen per opponent and loadout pairing |
| `-Suite` | league | `terrain` or `arena` for the one on one fight instead |
| `-Weights` | `runs\<run>\best.mbw` | which network drives |
| `-Workers`, `-Slots`, `-Heap`, `-StudentNoise` | 8, 25, 1280M, 0.05 | as for `imitate.ps1` |

Records are named after the suite they were made on: `demos\league-round-1` beside `demos\round-1`, so a league record
never lands on top of a vindicator one and a run can keep both. `runs\vindicator4`'s melee record stays exactly as
usable as it was, and `train.py imitate` and `--teacher-weight` both read every folder under `demos`.

The script refuses to write into a `demos` folder that is a junction to another run's, which `compare.ps1` makes: a round
recorded there would put this run's corrections into the other run's record.

Then train pulled back towards it:

```
scripts\train.ps1 -Run league -Suite league -TeacherWeight 0.5
```

`-TeacherWeight` is an imitation loss on a sample of the record, applied on every update alongside PPO's own; it is what
`-FromCopy` sets to 0.5, and an explicit one beats that. It stops the run before it starts if there is no record to pull
towards.

### `scripts\imitate.ps1`: make a copy of the teacher

```
scripts\imitate.ps1 -Run vindicator                 record the teacher, copy it, then three rounds of correction
scripts\imitate.ps1 -Run vindicator -Rounds 0       only record and copy
scripts\imitate.ps1 -Run vindicator -Rounds 2       two more rounds on top
scripts\imitate.ps1 -Run league -Suite league       every opponent and every loadout, for a copy that will fight the league
```

| Parameter | Default | Meaning |
| --- | --- | --- |
| `-Fights` | 4000 | fights recorded per round |
| `-Suite` | `terrain` | what the record is of: one vindicator with one sword, or the whole league |
| `-Loadouts` | every one | record only these, for weighting a round towards what the copy is worst at |
| `-TeacherNoise` | 0.1 | noise on the teacher's recorded fights (0.2 lost too much) |
| `-StudentNoise` | 0.05 | noise on the copy while it drives in correction rounds |
| `-Workers`, `-Slots`, `-Heap` | 8, 25, 1280M | as for training |

**A copy that will fight the league wants `-Suite league`**, and this defaulting to the vindicator is what cost league768
a third of its fights. Its record was 16,000 terrain-suite fights, hotbar `[iron_sword]` and nothing else, so neither the
copy nor the 2,000 iterations of teacher pull that followed ever saw a bow: over 46,044 bow fights it fired **0.00
arrows**, holding the bow and punching with it, while the same teacher recorded on the league fires 11 arrows a fight and
wins 60.3% with one. Nothing downstream can learn what the record does not hold; see [findings.md](findings.md).

Demos go to `runs\<run>\demos\round-N\` on the terrain suite, and `demos\<suite>-round-N\` on any other. They're
gigabytes, and not in git.

### Watching and stopping

```
scripts\watch.ps1                 every live run, one wide line each, redrawn in place; -Once to print once
scripts\stop.ps1 -Run name        stop that run: its trainer, its workers, its Gradle client
scripts\stop.ps1                  stop EVERY run on the machine
```

`watch.ps1` shows, per run:
- state and workers;
- iteration, fights/s and game ticks/s over the last minute;
- the training win rate with its trend and a sparkline (training explores, so it sits below what the weights can do);
- the latest evaluation as won / lost / timed out;
- the best evaluation.

A last line adds the runs up. Stopping loses nothing: the next `train.ps1` for the run carries on from `state.pt`.

## What's in a run folder (`runs\<run>\`)

```
schema.json          the layout the game ran; stamped into weights
state.pt             everything the trainer needs to carry on: networks, optimiser, normaliser, iteration
weights\NNNNNN.mbw   the policy of each iteration (older ones pruned, checkpoints kept)
rollouts\            shards in flight, deleted once learned from
checkpoints\         state.pt every 25 iterations
eval\ eval.csv best.mbw finished       evaluation, see architecture.md
logs\train-*.log     the trainer's log; one line per iteration
console.log          the whole build output of a background run
replays\*.json       recorded fights for the viewer
demos\               the teacher's recorded answers, a folder per round and suite, or a junction to another run's
```

## The machine

Each worker is a whole headless Minecraft server and uses 2.5 to 4 cores: its server thread, plus terrain generation and
garbage collection. On a 32 GB machine, memory decides how many fit. Nearly all of a worker's heap is the ground under
and around the sites it is fighting on, so the build picks the heap from what the run actually fights on: **1 GB on the
terrain library**, where the sites are read from disk and about half a gigabyte is live, measured at 1.42 GB of private
memory in all; **2 GB** where a worker generates its own ground and settles at about 0.95 GB live. `-Heap` overrides it.
Every worker also gets `-XX:G1HeapRegionSize=4m`, without which a gigabyte heap collects worse than a larger one; see
[findings.md](findings.md).

A worker on the `terrain` or `arena` suite runs with **no light engine at all**, since a vindicator fight never asks how
bright anywhere is: about a fifth more fights per worker-second and nearly half the collections, measured over 24,000
fights. The `league` suite keeps its light, because the undead burn by day and an enderman takes damage in rain, and so do
the library build, `play` and `mechanics`; see `GameTestTuning.lighting`.

On a chip with two kinds of core, every worker is confined to the **performance cores**, and it is worth more than
everything else here put together: a worker's throughput doubles. A worker is bound by its one server thread, and because
workers run at below normal priority Windows reads that thread as background work and parks it on an efficiency core, where
the same forward pass costs 2.6 times as much. The build times a burst on every logical processor once per machine, keeps
the mask in its calibration file, and sets it on each worker as it starts; the log says which cores they got. Priority is
not the lever and stays below normal, which is what keeps the desktop yours. See [findings.md](findings.md) for the numbers
and for why the mask must not include a single efficiency core. The build protects the machine:

| Gradle property | Default | What it does |
| --- | --- | --- |
| `maxWorkers` | 16 | ceiling on workers in a round |
| `memoryReserve` | 3 | GB left for everything else when deciding how many workers fit (heap + 0.5 GB each) |
| `memoryFloor` | 1.5 | GB of free memory below which the workers are stopped rather than left to swap |
| `memoryGrace` | 10 | seconds the shortage has to last first, so that a moment's dip from something else on the machine does not cost a round |
| `pourHazards` | true | whether a fight that asks for ground with something worth knocking an opponent into, and is handed flat ground, gets a pool of lava poured beside it for that fight and the ground put back after; `false` leaves it to what the library happens to hold, which is lava on 2% of fights |
| `workerCores` | measured | `all` lets the workers run on every core, as they did before the performance cores were measured |
| `serverThreadCores` | off | `performance` pins each worker's server thread to the performance cores and lets the rest of the process have every core; `one` gives each server thread a performance core of its own. Measured a dead heat and worse respectively, so off; see [findings.md](findings.md) |
| `workerCpus` | auto | cores each server sees, out of the performance cores it is allowed |
| `workerStagger` | 1 | seconds between starting workers |
| `terrainPool`, `terrainUses` | 8, 8 | kept terrain worlds, and how often each is reused |
| `terrainSeed` | 0 = anywhere | pins where every worker's fight sites come from, so two rounds fight the same ground; what comparing two builds needs |

The trainer caps itself at half the GPU's memory and falls back to the CPU if an update runs out. An update takes 0.5 to
1.6 s on an RTX 4070 Ti SUPER.

Throughput today: about 3.5k game ticks per second per worker inside training; vs-copy on 4 workers does about 12–16k.
Every worker pauses while the trainer learns (about a fifth of the time), and each iteration waits for the slowest
worker. Work to remove both is under way.

Every worker also prints what the network itself cost it when its suite ends, which is the number to judge any change to
the forward pass by:

```
  forward pass  2.94 s over 227,229 agent ticks, 12.9 us each, 21% of the run; vector loops
```

Nothing weaker is worth using: the same pass timed on its own in a loop comes out at 10.7 us because the weights stay in
cache, and on an efficiency core at 24.8 us. See [findings.md](findings.md).

## Troubleshooting

| Symptom | Cause, fix |
| --- | --- |
| "Run 'X' is already training" | a live trainer for that run; watch it, or `scripts\stop.ps1 -Run X` first |
| "Stopped the workers to protect the machine" | free memory stayed under `memoryFloor` for `memoryGrace` seconds; use fewer workers, or close things. **A game test build counts**: on a 32 GB machine, six training workers and a `scripts\test.ps1` at the same time is over the line, and the build wins because the runs are the ones being watched. Leave a run's worth of memory free while developing |
| a worker "stopped with exit code 1" | read its `mod\fabric\build\training\<run>\worker-N\log.txt`; `OutOfMemoryError` means the heap is too small |
| iterations suddenly take many times longer | one worker is slow and every worker waits for it; check its garbage collection with `jstat -gcutil <pid>` |
| `PermissionError` on a rename | fixed in `mmai/files.py`; a reader held the file open |
| win rate jumps around | training explores; judge by the evaluation columns |
| "was trained against schema X and the game is running Y" | the layout's checksum changed under a stopped run. If nothing an input *means* moved, `scripts\restamp.ps1 -Run runs\<name>` brings the run's files up to the new id and it carries on; if something did move, the run has to start again. `-WhatIf` first. **One-off:** delete the script and `trainer\restamp_schema.py` once every run has been through it |
