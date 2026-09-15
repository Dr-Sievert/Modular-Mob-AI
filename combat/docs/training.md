# Training

Everything here runs from `combat/` in Windows PowerShell. `scripts\setup.ps1` must have run once.

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

   **Against what is shot at it**, two rules more, both of them last resorts: a shot already in the air holds an enemy slot
   of its own, and until now the shield was the only rule that ever read one. Neither touches the fight against a body, and
   a drawn weapon reaches neither, since a draw has the hands and the keys at a fifth:
   - **swings at a fireball to send it back**, when the shield is not up because there is none in the off hand or an axe has
     just knocked it aside. A player's swing deflects anything in the `redirectable_projectile` tag along the swinger's own
     look, and the agent's hands are a player's; the aim goes on the shot for the last few blocks of its flight, which is
     the same direction as the ghast that fired it, so the swing finds the fireball and throws it back where it came from.
     It swings at where the shot **will be** when the press resolves rather than where the observation says it is, since a
     fireball covers about a block in that tick. Only a fireball, told by its size: a wind charge and a blaze's small
     fireball are both 0.3125 wide and nothing in a projectile's slot tells them apart, and a swing at the blaze's cannot
     deflect it, does not stop it, and costs a whole cooldown. Worth 7 points against a ghast and most of what a fireball
     used to take off it; see [findings.md](findings.md);
   - **steps out of an arrow's way**, and only where there is no shield to raise — a shield stops the arrow outright, and
     that rule is untouched. Where a shot's line would pass through the agent it puts its feet across that line rather than
     on with the fight, by the same one step the pack rules give ground with, so the grid keeps it off hazards and the rays
     off ledges. Begun while there is still time for a step to matter and never on a tick it would have struck: a blow
     landed is worth more than an arrow dodged. Worth 8.6 points across the seven shooters that are not a ghast.

   **Against a pack** — two or more bodies actually in the fight and within six blocks, which a fight against one opponent
   never is — it fights a different fight, because a network never learns what the teacher never did and the teacher used to
   take the nearest of a pack and trade with it as if alone:
   - **gives ground from the pack as one direction**, the sum of the ways to each of them weighted by nearness, rather than
     from whichever is nearest, since backing away from one body walks into another. It is a **cycle and not a retreat**:
     ground while the swing cools, and it starts closing again a few ticks before the cooldown fills, because the ground
     given up has to be covered before a blow can land. Backing away for as long as two bodies were in the fight was the
     first rule written and is the wrong one — a body outpaces a zombie, so nothing arrives, nothing is landed and the clock
     runs out; see [findings.md](findings.md);
   - **kites a pack it can outwalk**, which is one number off the slots rather than a judgement: the fastest `ENEMY_SPEED`
     in the fight against a backpedal's 0.216 blocks a tick. A zombie, a husk, a drowned and a zombie villager all cover
     0.154, and against those the fighter never steps in at all — it gives ground the moment one comes inside 2.6 blocks
     whether or not its swing is ready, stands still while the swing is ready and lets the body walk into a sword's reach,
     and swings the tick it arrives. A vindicator covers 0.24 and a spider more, and against those none of it applies and
     the cycle above is the whole of the footwork. Only while they are coming: a pack that has stopped is walked up to,
     since holding ground against one that is not arriving is the retreat that ran the clock out wearing a different hat.
     Worth 8.8 points at four zombies and most of the blows it used to take; see [findings.md](findings.md);
   - **gives ground with a drawn weapon too.** A bow takes the movement keys down to a fifth for twenty ticks, and the
     fighter used to choose its weapon before it had counted the fight: a bow in a pack was a fighter standing still among
     four bodies. The pack is sized up first now, a draw in a pack is begun only where it will be full before the nearest
     arrives — the same question a fighter with a sword to fall back on already asked — and the step away while drawing is
     the pack's direction rather than the target's. It is the widest single thing that was wrong with this fighter in a
     pack: over 900 harness fights a crossbow landed 0.0 blows and took 3.2, against a sword's 8.6 and 0.5;
   - **keeps them in front.** The aim goes to the middle of the pack for as long as it is giving ground and onto the body
     only while it is stepping in to strike, so the ones at the edges stay inside the hundred degree cone the agent perceives
     through. A body outside that cone is not in the observation at all;
   - **runs when the pack outlasts it**, which is arithmetic off the slots rather than despair: what one round of their blows
     takes off it against the health it has left, and what their health costs in swings at its own cooldown. It then sprints
     along the clearest ray away from them instead of dying in the middle of them;
   - **throws the sprint blow for the knockback** whenever the second nearest is far enough that the step forward is not a
     step into a second set of hands, and **never leaves the ground for a critical**, since a dozen ticks in the air is a
     dozen ticks of footwork given up. Both halves of that are pinned in the mechanics suite off where the second body
     actually stood when each blow landed;
   - and **changes nothing about the shield**, which was measured rather than assumed: narrowing it in a pack to a body with
     its arm actually up is worse than the rule that was already there.

   Nothing that shoots and nothing that flies counts towards a pack, so skeletons and flyers keep the ranged rules exactly as
   they were: walking backwards from an archer is walking backwards while being shot.

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
     two million ticks, and at 792 floats a tick that is about 7 GB — so two of them side by side is not a matter of
     workers. Tried: a second imitation started beside one already cloning took free memory to nothing and the build
     stopped the first one's workers to protect the machine. Two *training* runs are fine; two imitations are not.
3. **Reinforcement learning from the copy**, with `scripts\train.ps1 -Run <run> -FromCopy` (start by copying the copy's
   `state.pt`, `schema.json` and `weights\000000.mbw` into the new run, and naming the copy's record with `-Demos`;
   `scripts\train.ps1 -Seed <copy> -Demos <copy>` does all of it). It's PPO with safeguards, because plain PPO made a good
   copy worse twice:
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

     **A pull is only charged for the iterations it was actually on**, which is the rule in one sentence: a run seeded from
     another and a run converted by `train.py attend` start with no clock and begin their fall at the first iteration
     *they* pull, and a state saved with `--teacher-weight 0` that is resumed with a pull starts the fall afresh, while a
     run resumed with the pull it was saved under keeps the clock it had however little is left of it. Measured on
     `blast-8`: its state carried `teacher_from = 0`, inherited through `blast7` and `blast6` from the imitation copy the
     lineage began with, so `--teacher-weight 0.3` at iteration 34840 computed `0.3 * max(0, 1 - 34841/1500)` = **0** on
     every update and the pull was dead on arrival for 1,654 iterations. Nothing said so: the only line about the pull was
     the startup `pulling towards the teacher with weight 0.30`, which is the configured weight and never was the
     effective one. Now **the effective weight is said** — a `teacher 0.15` field beside `drift` on the iteration line
     wherever it has left the configured weight, and a WARNING at startup naming `teacher_from`, the iteration and the
     decay when a run is configured to pull and the fall has already run out. A state written before either was recorded
     says nothing about where it came from or whether it was pulling, and keeps the clock it has.

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

Results of that route (evaluated on the most likely action, against a vindicator). All three networks were trained against
the humanoid's old 634-float observation and have been retired from `models\`; the numbers stand, the weight files load
nowhere. See [models.md](models.md).

| Run | Started from | Best | Won / lost / timed out |
| --- | --- | --- | --- |
| vindicator4 | imitation + 3 DAgger rounds | 97.9% | |
| vs-copy | vindicator4, PPO with the teacher pull | iteration 650 | 99.8 / 0.2 / 0.0 (553 fights) |
| vs-scratch | nothing | iteration 650 | 78.6 / 18.4 / 3.0 (500 fights), still climbing |

## The critic

The critic never leaves this side: it is not exported, the game never runs it, and no weight file mentions it. So it is
allowed to remember more and to know more than the agent it judges, and it does both.

**A memory of its own** (`--critic-gru`, on for a new run). It used to read the *actor's* hidden state, recovered by the
replay and held still — memory trained to choose a button rather than to price a position. It now runs a GRU of its own
over the same chunks, as wide as the actor's unless `--critic-gru-width` says otherwise, and the replay recovers its state
per step so that a chunk in the middle of a fight starts from where the chunk before it ended, exactly as the actor's
chunks do. Advantage noise is the lever on everything downstream — on the league the scripted teacher still scores about
78% where the best network scores about 55% — and a better value estimate is better credit assignment everywhere.

It starts each segment from nothing, and that is an answer rather than a gap. The actor's state on a segment's first row
comes from the game, which carried it tick by tick and wrote it into the shard; nothing carries the critic's, because the
game never runs it, and a state held over from the previous iteration would have been produced by weights that have since
moved. What makes a blank start cheap is the inputs below.

**What it is told that the agent is not.** Fourteen numbers, from two places, in this order, and the order is the contract:
a critic reads its columns from the first, so anything added goes on the end and a critic already trained against fewer
columns goes on reading exactly the ones it learned.

Four are what only *this* side knows, `PRIVILEGED` in `trainer/mmai/model.py`, worked out by `Trainer._scale`:

| Column | What it is |
| --- | --- |
| `paid` | the fight's reward account before this row, in the same scaled units as the value target |
| `last` | what the action on the row before this one earned |
| `age` | how many ticks the fight has run before this row, over 1,200 |
| `start` | one on a segment's first row: the one row whose previous reward is in a shard this side no longer has, and whose own memory starts from nothing |

Every one of those is strictly *behind* the row it sits on. The return is the target, so an input carrying any part of the
return would teach the critic to read the answer off its own inputs instead of learning what a position is worth: the
fight's eventual outcome and the true number of ticks left to it are precisely the two things not to hand it. The past it
may condition on as freely as its own memory does.

Ten are what only the *game* knows, `SHARD_PRIVILEGED` here and `arena/FightFacts.java` there, which the game now writes
into every rollout row; the shard format is in [architecture.md](architecture.md#mbr-rollout-shards). Two things decide what
belongs on this list. Nothing may carry the answer — no outcome, no reward, and no count of the ticks *left*. And a column
has to change how a position should be priced, in a way the observation either cannot say or says in a form the critic
cannot use: the critic reads the raw observation and has no encoder over the enemy slots, so a fact spread across ten slots
is a fact it has to find, and a slot is filled only while the opponent is perceived — inside 32 blocks, inside the episode's
bounds, and for 40 ticks of grace after that — so an opponent behind a hill reads exactly like no opponent at all.

| Column | What it is, and why the observation will not do |
| --- | --- |
| `foes` | how many of the other side are still standing, over the ten enemy slots. Two zombies are not twice a zombie, and the observation's count of enemies in range counts only what is perceived |
| `foe_health` | the health still standing over the health the other side has when whole: one at the start, nought once all of them are gone. The strongest single thing a value estimate can be given, and it is the past — it is the damage the agent has already dealt. A slot's health is one mob's own fraction, in one of ten places, and only while seen |
| `foe_hearts` | what the other side has when whole, over 100: a zombie 0.2, a warden 5 |
| `foe_damage` | the hardest blow anything still standing strikes with, over 20 |
| `foe_armour` | the best armour anything still standing wears, over 20. **Nowhere in the observation at all**: a zombie in iron takes less than half the damage from the same swing and reads as the same zombie. It is also most of what a hard rung of the ladder changes |
| `foe_fuse` | how far along a lit creeper's fuse is. The fight is about to move three or four in the reward, which is the largest jump a position can make |
| `went_for` | whether anything on the other side has the agent as its target right now. Not in the observation in any form — facing is a proxy that a mob which just let its target go still shows — and it decides whether the fight happens at all, since an opponent that never engages runs the clock out and a timeout is paid as a loss |
| `own_damage` | what one of the agent's own blows takes off, over 20. The hotbar says "a sword", one category for stone, iron and diamond, and the echo says what a blow took off only after one has landed |
| `own_armour` | the armour the agent is wearing, over 20. The agent cannot see its own armour anywhere: the armoured loadout reads exactly like the plain sword, and it halves what every blow costs |
| `limit` | how long this fight is given, in ticks over 1,200. Fixed before the first tick, so it is not a countdown; the observation's clock is a fraction of *this* limit, so tick 600 reads 0.5 in a melee fight and 0.25 against a ghast, and the speed bonus is paid against the limit. It also says coarsely what kind of opponent this is: 1,200 for something reachable, 1,800 for something that shoots, 2,400 for something that flies |

The other side is described by what it **can do** rather than by which mob it is, for the same reason an enemy slot is: a
species number has to be learned one mob at a time, says nothing about a mob the run never met, and would need a table kept
in step between the shard and the trainer, where a rung of the ladder is a new player every time one opens. Capabilities
need none of that, and they carry a rung for free — a hard zombie is one with more health and better armour, and that is
what these numbers say. The same argument settled the agent's own loadout: `own_damage` and `own_armour` are what the
hotbar block cannot show, and they generalise to a loadout no run has fielded yet.

Left off deliberately: **the kind of ground the fight is on**. The site label (`lava`, `drop`, `hazard`, `water`, `flat`) is
drawn from a scan of the middle 48 blocks of the site, and the observation's eight rays already say how far it is to a wall,
to something that hurts and to a drop, out to the same 32 blocks — the local truth rather than a site-wide summary. It would
have been the least informative float in the set and the only one needing the game-test side to tell the arena something.

**A resume keeps the critic it has.** `--critic-gru` decides what a *new* run builds. The shape of the critic is written
into `state.pt`, and a run carries on with the one its state holds whatever the flags say, with a line in the log to say
so. A critic is learned rather than configured: swapping its architecture under a run in progress throws away everything it
knew about the fight and hands the policy nonsense advantages until it has learned again, which is the whole reason
`--critic-warmup` exists. A state written before the critic had a memory of its own names no shape at all, and that reads
as the plain feed-forward critic it holds, whose parameters are unchanged. Change it by starting a new run.

Both halves of its input width are the state's, the number of privileged columns included: a run that was already going
when the game started writing its own ten carries on reading the four it learned, which is exactly what the fixed order
with the trainer's columns first buys. It is handed all fourteen either way, as every shape of critic is, and reads the ones
it was built for.

**A critic that has never learned is built to the flags instead.** Every word of the rule above is about not throwing away
what a critic has learned, and a critic that has taken no gradient step has learned nothing: what is in it is the random
initialisation of whatever build wrote the state. That is exactly a copy's state — imitation trains the actor and never the
critic — so `scripts\train.ps1 -Seed <copy>` used to inherit, silently, the critic of the build that made the copy, and
could never be given the one it asked for. The state says which case it is (`critic_trained`, written by `save`), and where
the critic has not learned it is built to the flags and the optimizer's moments are left where a new run's would be: a
copy's optimizer holds nothing anyway, since imitation steps one of its own over the actor alone. The actor, the
normaliser, the reward scaler and the iteration all still come across, which is what a seed is for. A state written before
this was recorded goes by its iteration, since nothing but an imitation writes a state at iteration zero.

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
scripts\train.ps1 -Run league -Suite league -Seed blast       the league, from runs\blast's best (see the league below)
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
| `-LeagueModels` | | league only: published networks in `models\` to field as rated players, `blast`, or several separated by commas; see the league below |
| `-ReplayEvery` | 200 | record one fight in this many per worker, for the viewer; 0 for none |
| `-FromCopy` | off | the safeguarded settings for a run that starts from a copy |
| `-Seed` | | start a new run from another's best checkpoint state: `runs\<seed>`, else `models\<seed>\state.pt`, else a folder by path; gentle settings as `-FromCopy` but no teacher pull, the critic alone for 30 iterations, 65536 steps and no battle limit by default. A seed whose critic never learned anything gets the critic this run configured rather than that one, see [the critic](#the-critic). **The teacher's clock is not seeded**: a seeded run's pull falls from the first iteration it itself pulls, not from whenever the run it came from first did, see [the pipeline](#the-pipeline-that-works) |
| `-TeacherWeight` | 0 | pull every update back towards the teacher's recorded answers in `runs\<run>\demos`; see `scripts\dagger.ps1` below. A run given a pull it was not saved with starts the fall afresh, and says the effective weight beside `drift` wherever it differs from this one |
| `-Full` | off | the whole build output |
| `-Extra` | | options passed to the trainer |

A run is refused if it's already training, rather than killing the live one. The console shows a line every half
minute: iteration, ticks/s, fights/s, training win rate, fight length; plus evaluations, rounds and errors.

### Trainer options (`-Extra`)

Every field of `Config` in `trainer/mmai/ppo.py` is an option, as `--field-name value`. The useful ones below; each field's
own comment in `ppo.py` is where the reasoning and the measurements are.

**Why some of these are `train.ps1` parameters and the rest are not.** A setting is first-class — its own named parameter —
when a documented workflow asks for it by name: `-Suite`, `-Seed`, `-TeacherWeight`, `-LeagueModels`, `-Workers`. Anything
else goes through `-Extra`, which reaches every field of `Config` without the script having to know it exists. That keeps
`train.ps1` a list of the ways a run is actually started rather than a second copy of `Config` to be kept in step, and it is
why a new knob in the trainer needs no change here at all.

| Option | Default | Meaning |
| --- | --- | --- |
| `--learning-rate` | 3e-4 | Adam step size |
| `--clip` | 0.2 | PPO clip range |
| `--target-kl` | 0.02 | stop an update early past this KL |
| `--kl-adapt` | 1.5 | the learning rate moves against the KL each update produced, by this factor, so `--target-kl` is a step size rather than a trip switch. It was firing after the damage: on league768, 251 of 300 updates ran a single epoch at a KL of 0.013 against a target of 0.010 — 65,536 steps of collected fighting used once, and the policy 30% past the step it asked for anyway. The rate is in the run's state, so resuming keeps what it found; 0 turns it off |
| `--kl-adapt-band` | 2.0 | how far either side of the target counts as on target, as a factor: inside it the rate is left alone |
| `--kl-adapt-range` | 10.0 | how far the rate may wander from the configured one, as a factor either way |
| `--epochs` | 4 | passes over each iteration's data |
| `--entropy-coef` | 0.01 | exploration bonus |
| `--critic-warmup` | 0 | iterations where only the critic learns |
| `--critic-gru` | true | the critic runs its own GRU and reads the fourteen privileged inputs; `false` is the plain feed-forward critic. Decides what a new run builds, and what a run seeded from a copy builds; a resume keeps the critic its state holds, see [the critic](#the-critic) |
| `--critic-gru-width` | 0 = `--hidden` | how wide that memory is |
| `--teacher-weight` | 0 | pull towards the run's demos; needs `runs\<run>\demos` |
| `--teacher-decay` | 1500 | iterations over which that pull falls to nothing, from the first one this run ever pulled — a seeded or converted run's clock is its own, and a pull is not charged for iterations it was off for; 0 holds it for ever. A pull held at full strength is a ceiling and not a floor, see [the pipeline](#the-pipeline-that-works) |
| `--teacher-release` | 6 | judged checkpoints in a row that fail to beat the best, while the pull is still on, before the rest of it is let go without waiting for the horizon: a run that is still being pulled and has stopped improving is the shape of a ceiling. 0 waits for the horizon |
| `--teacher-release-over` | 200 | iterations that release takes. Gradual, because an imitation term removed between two updates moves the policy on its own |
| `--teacher-rows` | 262144 | steps of the record a pull is scored on per update |
| `--aux-coef` | 0.05 | how hard the auxiliary predictions pull on the memory; 0 turns them off, see below |
| `--aux-horizon` | 32 | ticks ahead that "the fight ends soon" looks |
| `--seq-len` | 32 | ticks of GRU unrolled per training chunk |
| `--h1 --hidden --h3` | 256, 128, 128 | network widths; only for a new run, and the game needs no change |
| `--slot-heads` | 0 | how many heads read the ten enemy slots, or 0 for a first layer that takes every slot's numbers where they sit. Each head picks one occupied slot out by a learned score and hands the first layer that slot, with the slot it chose taken away from the heads after it, so what the layer sees does not depend on which slot a body is in or on how many idle bodies stand about — which is worth 22 degrees of aim a tick per bystander, see [`train.py attend`](#trainpy-attend-carry-a-plain-run-into-attention-over-the-slots). It is part of the network's shape and not a setting: a state trained with heads cannot be read into a network without them, which is why `-Seed` reads the count out of the state it is seeding from. A run already training is converted rather than reconfigured |
| `--scale-rewards` | true | divide rewards by the running spread of the return, so the value loss is the same size whatever the reward is measured in |
| `--skip-limit` | 3 | updates in a row that may go non-finite before the run stops. One such update is abandoned whole — every parameter and every one of Adam's moments back where it was, the iteration reported as `NOT LEARNED FROM`, the same weights out again under the next number — and the run carries on; three in a row stops it **without writing a state**, so the last good one stays on disk. Rows that are not finite are dropped before any of that, with a line naming the field they came from. See [findings.md](findings.md) for the breeze that made this necessary |
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

#### Auxiliary predictions: `--aux-coef`

The agent's memory — the GRU's 128 numbers — is trained by the policy gradient and by nothing else. That is one noisy
number a step for a network whose whole job is to understand what the fight is doing. The critic reads the memory as well,
but the value loss is deliberately kept from reaching the policy's features, so nothing it works out arrives there either.

So the memory is also asked to **predict**, from three targets that every rollout row already carries. Nothing changes on
the game's side, and nothing is added to the network the game runs: the heads are a module the trainer owns, they are not
part of the actor, and the weight file is the same file it was (`trainer/tests/test_aux_heads.py` proves the exported
parameter count is unchanged).

| Prediction | Target | Why |
| --- | --- | --- |
| the body a tick on | the observation's own self block one row later, through the same normaliser the input goes through | a memory that can say where its own body is about to be has learned what the controls do. The fields that barely move are free to get right and stop mattering; what is left of the error is velocity, the attack cooldown, hurt time, on the ground or not |
| what the step earns | the scaled reward, the one the critic is fitted against | this is exactly the signal the wall around the value loss keeps out, and knowing that a blow is about to land is what the policy wants to be built on |
| the fight ending soon | whether the fight ends within `--aux-horizon` ticks | a win pays for being quick and the clock running out is a loss, so the difference between a fight seconds from over and one that has just begun is worth real reward |

Each loss is reported on the run's own `aux` line, one field per head, which is separate from the iteration line
`scripts\watch.ps1` reads field by field.

Two things to know before turning the dial:

- **`--aux-coef 0` is the old update exactly.** Off means the heads are never built, not built and multiplied by zero: a
  network that exists draws from the random generator as it is made, and every draw after it — the order the minibatches
  come in included — would land somewhere else. Zero is the comparison to run against.
- **`--aux-coef 0.05` is a starting guess and has not been measured over a run.** A head that has learned nothing scores
  about one, against a policy loss of a few hundredths, so at 0.05 the three of them together weigh about what the policy
  does at the start and less as they come good. Judge it on the `aux` line and on `scripts\bench.ps1` against a run with
  it off, the way anything else here is judged.

The heads learn in an optimizer of their own, at the configured rate and never at the steered one: a predictor has no KL.
That is also what lets a run that started before they existed resume into them — Adam refuses a saved state whose
parameter group is a size other than the one it is loaded into, so heads in the main optimizer would have ended every run
on the machine the moment it restarted. Nothing is predicted during `--critic-warmup`, since those iterations exist to
hold a copied policy still and a loss that moves the memory moves the policy with it.

### The league: `-Suite league` and `scripts\league.ps1`

A league run fights 48 mobs, 11 squads of several mobs at once, 2 jockeys, the scripted fighter, any published networks it was told
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
scripts\train.ps1 -Run league -Suite league -Seed blast       start one from runs\blast's best, run until done
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

The table is loadouts times opponents: 620 pairings at the start of a run (10 loadouts against 61 mobs, squads and jockeys
plus the scripted fighter), 1,840 once every rung of the ladder is open, and 80 more for the self-play pool. A pairing's own record is
thin at that size — a couple of thousand fights fade through the whole table, so single figures each and plenty with none — so
**a pairing's chance is never asked to stand on its own**: it is the pairing's own record over a prior worth `--league-prior`
fights, and that prior is the opponent's chance moved by how the loadout does over all of its fights, which is a tenth of the
run's and dense enough to mean something. With nothing recorded anywhere the prior is exactly the opponent's chance, so a
fresh run draws as it always did and only separates as the fights say it should. Evaluation fights are not paired: they draw
the opponent evenly and the loadout evenly, since every rating is measured on them.

**One pairing is never drawn at all**: a loadout that carries nothing that shoots against a flyer that never comes within
reach, the ghast and the phantom, on any rung, packed, crowded, or on a squad with one of them on it. A ghast drifts and
fires, a phantom swoops past and climbs away, and a deflected fireball kills a ghast only for a real player, so there is
nothing there to win or to lose: every one of those fights was 2,400 ticks of timeout, dragging a rating with a number that
means nothing and spending a worker's minute on a question with one answer. It gets no share, which is also what keeps the
frontier probe off it — the probe holds a hopeless pairing down to a trickle rather than to nothing, and a pairing that is
never in the table is never probed. The workers say which rows those are in the `reach` column of `roster.csv`, and the game
side refuses the same pairing in its own draw. **It changes what a checkpoint's evaluated win rate is averaged over**: an
evaluation fight draws its opponent evenly and its loadout evenly, so those hopeless fights were about one evaluation fight
in seventy and every one of them a timeout. So this belongs at a **run boundary** — a run that crossed it could not compare
its best weights before with its best weights after — and the orchestrator restarts the run when it lands. The plain bench
(`scripts\bench.ps1`) is untouched either way and is still the number to compare across runs.

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
scripts\train.ps1 -Run league -Suite league -LeagueModels blast
scripts\test.ps1 -League -LeagueModels blast                  a quick look with one in it
```

Each name is a folder under `models\`. It is fielded as another agent on its most likely action, exactly as a frozen
checkpoint is, and **rated under its own name**, so `blast` appears in the tier list, in `ratings.csv`, in
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

### A crowded view

Every league fight used to be the agent against one opponent or a squad of two or three, and every one of them on the other
team and coming for it. A real game is not like that, and the published network showed it: with an engaged zombie two blocks
off and idle monsters standing about, 200 ticks each, none in view and it kills the zombie in 45 ticks on full health; three
and it lands nothing and dies; nine and it never presses attack once. The numbers and what was ruled out first are in
[findings.md](findings.md#perception).

Half of that was the view, and the view has been narrowed: a slot now goes only to what the agent could see, so the monsters
through the wall and in the caves below take none. The other half is the crowd that really is in sight, and that is a
curriculum hole. It is filled by `gametest/league/Bystanders`:

```
-PleagueBystanders=0.25      the share of fights against a mob or a squad with a crowd standing about; 0 for none
```

- **A quarter of the fights**, which is what the hazard ground was given and for the same reason: the plain fight on plain
  ground is still the fight the agent has to be able to win. 1 to 9 monsters, **weighted towards the small crowds** (one over
  the count, so 35% of crowded fights stand one and 4% stand nine), 8 to 30 blocks from the middle of the fight, on
  **no** team, and handed their target back on every tick until something hits them — the exact opposite of the provocation a
  league opponent gets, and needed rather than assumed; see findings.md. They are not part of the win condition and
  `Episode#pays` never pays for one, so the reward and the fight are exactly what they were: the only thing that changes is
  what is in the view.
- **A player of its own, not a modifier on the old one.** `zombie+3_idle`, rated separately the way a squad is, so the plain
  `zombie` rating stays a number that can be compared with every run before this one. The trainer never matchmakes over those
  names — they are not in the roster the workers hand it — so they cost a row in the tier list and nothing in the machinery.
  What they do move is a checkpoint's **evaluated win rate**, which is what the best weights are picked by, and deliberately:
  a network that cannot fight in a crowd should not be a run's best. That number is therefore not comparable with a run from
  before this.
- **Drawn from the monsters that walk.** Narrower than the roster, and both halves are about the crowd being a crowd: an iron
  golem or a wolf is not an enemy on sight and would take no slot, so the count in the name would stop being the count in the
  view; and a flyer starts in the air, which a site guarantees is clear over the fighters and not over a spot twenty blocks
  away. The warden is left out by name — it picks what to fight by anger rather than by sight, so it would not stay a
  bystander.
- **What it costs, measured.** 200 league fights on one worker, with the share off and on: **7,330 arena ticks a second
  against 5,475** on the same pinned terrain seed, and 6,809 against 6,216 on a second pair with the sites drawn freely. So
  **10 to 25% of a worker's throughput** for a quarter of the fights crowded, which puts a crowded fight itself at something
  like one and a half to two times the cost of a plain one — far more than the guess that extra mobs would be free, because a
  bystander with its wits about it pathfinds every tick where the probe's fourteen in a box had no AI at all. Lower the share
  if a run cannot afford it; that is what the property is for. The other cost is the one that was expected: a run that spends
  a quarter of its fights here spends it away from the plain fight.
- **What it changes beside the policy.** `SELF_ENEMIES_IN_RANGE` and the critic's count of the other side start seeing what a
  real world shows them, which is the point, and both are already one rule in one place. Nothing in the layout moves, so no
  published network is invalidated: this is fights, not fields.

**The crowd did nothing for 2,200 iterations, and the reason was which slot the opponent sat in.** `blast7` ran the share
above for 2,200 iterations and its crowded win rate never moved off about 31%, against 80% on the plain fights beside them,
flat over every bucket of 500. A slot used to go out in the order the level's own walk over its entity sections returned
bodies, so in a plain fight — one body — the opponent always took slot 0, and in a crowd it took whatever its position in the
world happened to give it: measured, slot 0 on **none** of the ticks with nine standing about, and slot 5.5 on average. So a
quarter of the fights were contradicting the other three quarters, and there was nothing in them to learn. The order is now
the fight's own — whoever has come for the agent or is on a team set against the agent's first, then the nearest — and the
opponent holds slot 0 in a crowd exactly as it does on its own. **A network trained before this is not fixed by it**: it never
saw a crowd it could learn from, so the crowded rate is a thing to watch on the next run rather than a thing already better.
The numbers, what was ruled out, and what was deliberately left alone are in [findings.md](findings.md#perception); the harness
that took them is `scripts\test.ps1 -Crowd`, and crowded fights now record a replay like any other.

**Then it moved twelve points and stopped, and the draw is why the count is no longer flat.** With the order fixed, `blast7`'s
crowded win rate climbed 31.6% → 44% over 6,000 iterations — and then sat at 44% for 4,000 more, against 79% plain. Per count
it is graded the whole way down, 68 / 58 / 48 / 46 / 39 / 37 / 32 / 31 / 31% from one bystander to nine, so a **flat** draw from
1 to 9 spent five crowded fights in nine on the counts where it wins about a third and where nothing had moved in four thousand
iterations, and four in nine on the small crowds the twelve points had actually come from. The count is now drawn with a weight
of one over the count — 35.4 / 17.7 / 11.8 / 8.8 / 7.1 / 5.9 / 5.0 / 4.4 / 3.9% for one to nine, a crowd of 3.2 on average
rather than 5.0, and 73.6% of crowded fights at four or fewer instead of 44.4%. Nine is still drawn, on about one crowded fight
in twenty five, because a count that stops being drawn stops feeding the `+N_idle` row a run is judged on. **The share did not
change** and neither did anything outside this one draw: `-PleagueBystanders` means exactly what it meant, and the explicit 0,
1, 3 and 9 of `scripts\test.ps1 -Crowd` are left alone on purpose. What it does move is the mix a checkpoint's crowded win rate
is averaged over, so that number is not comparable with a run from before it — fixed for the whole of a run, which is what best
weights need, but a different average; the plain rate is the one to compare across runs. See
[findings.md](findings.md#the-leagues-curriculum).

**The view has since been narrowed as well**, which was the other half: a slot now wants a line of sight from the agent's
eyes and not only thirty two blocks, so a mob behind rock never takes one. That is what actually fixed the reported game —
the agent kills the zombie beside it where it used to die to it — and it cost the arena suite nothing, since a league fight
site has nothing between the fighters. The flicker it was feared for is answered by keeping the lease through cover and
letting only the reading go; see [findings.md](findings.md#perception) and `architecture.md`. It is still a change to what
every network sees, so a published network is better off retrained than trusted out in a world. Reachability — the mob in the
cave below with a line of sight up through a hole — is deliberately not done, and the reason is in findings.md.

### A hostile crowd

A crowd standing about taking no interest is one shape a real world has. The other is **several of them all coming at once**,
and that one the league had never fielded at all: its opponent is one mob or one of eleven chosen squads of two or three. It
matters more than it used to, because a monster now goes after a playable agent the way it goes after a player
(`allegiance/HuntAgentsGoal`), so a night in a real world brings whatever is in view rather than nothing at all — which is the
owner's second report, "he still gets massively overwhelmed". It is filled by `gametest/league/HostilePacks`:

```
-PleagueHostileCrowds=0.1    the share of fights against one mob that field several of it, all fighting; 0 for none
```

- **A tenth of the fights**, against **one mob** only — never a squad, whose composition was chosen to ask one question, and
  never the scripted fighter, a published network or a checkpoint, which are the fixed policies every rating is measured
  against. Two to six of the mob, **weighted small** by one over the number of extra bodies: 44 / 22 / 15 / 11 / 9% for two
  through six, 3.19 on average. A tenth rather than the crowd's quarter because a pack is the hardest fight in the league at
  every size above two and the dearest per fight of anything in it; a run that wants more says so.
- **A player of its own**, `zombie+3_pack` — the mob and three more of it, so four fight. Rated like `zombie+3_idle` and for
  the same reason: the plain `zombie` rating has to keep meaning what it meant in every run before this one. The trainer never
  matchmakes over these names, since they are not in the roster the workers hand over; `league.py`'s `base()` strips the suffix
  so the pack inherits the mob's kind and the mob's cap, and the tier list gets a row.
- **It is the squad machinery, not a new kind of fight.** A pack is an `Opposition` of copies, so the side, the provocation,
  the reward paying for each of them exactly once, the ground asked for a place to stand for each, the clock and the replay are
  all what a squad already had. Nothing downstream knows a pack from a squad, and `AgentLeagueGameTest` needed no change.
- **Never a pack and a crowd in the same fight.** A pack of six with nine standing about it is fifteen bodies to tick on one
  worker, and the bystander draw is left exactly where it was for every fight that is not a pack, so the mix of `+N_idle`
  players a checkpoint's evaluated rate is averaged over does not move — the objection that held a curriculum ramp back twice.
- **What it costs.** The same kind of bill the bystanders sent, for the same reason — every one of them has its wits and
  pathfinds every tick — but on a tenth of the fights and at 3.19 bodies rather than 3.2, and unlike a bystander a pack member
  is in the fight, so the fight ends sooner or the agent does. What it also moves is a checkpoint's evaluated win rate, and
  deliberately: a network that cannot fight a pack should not be a run's best weights. That number is therefore not comparable
  with a run from before this.

### `train.py attend`: carry a plain run into attention over the slots

A network that reads its ten enemy slots where they sit cannot be trained out of doing so. In every one-on-one fight the
opponent is in slot 0 and slots 1..9 are all zeros, so the first layer's columns for the late slots never receive a gradient
worth the name and their normaliser statistics sit on the floor — and then the first idle bystander to stand in slot 5
arrives as inputs of magnitude 5 to 10 against weights still near their initialisation. Measured on blast7 over 60 real
one-on-one segments: **one** idle body written into slot 1 moved its deterministic aim by 22 degrees of yaw and 13 of pitch
a tick, the attack logit by 2.4, and flipped the chosen hotbar slot on 32% of ticks; nine of them, 12 / 16 degrees and 3.0.
The first layer's pre-activation shift from that one idle zombie was 1.0 in slot 0, the real signal, and 8.3 in slot 8.

`--slot-heads K` is the answer, and this is how a run that has already learned to fight gets it without starting again:

```
python train.py attend --from runs\blast7 --into runs\blast-8 --heads 3      from trainer\, with its .venv
scripts\train.ps1 -Run blast-8 -Suite league -Seed blast-8                    then carry on training it
```

`--from` reads that run's `state.pt` and `schema.json` and touches nothing; `--into` is written whole — `state.pt`,
`schema.json` and the weight file for the iteration it converted, which is the one the workers are handed. `-Seed <itself>`
is what makes `train.ps1` read the widths **and the head count** out of the state it is about to load, exactly as it always
read them: the shape is a property of the weights and not a setting, and a run resuming without it builds the default
network around a state of another shape and dies on the load.

**Where the old network's slot k held what head k now reads, the conversion is exact.** Head k keeps slot k's columns,
rescaled per column to the statistics the attended network normalises every slot with, and the first layer's bias folds in
what the old one always received from its empty slots and takes back out what the new heads receive from the empty token.
Measured on blast7's own weights over 60 real one-on-one segments: **7.6e-05** at most in the logits one-on-one, and
**5.7e-04** with nine idle bodies written into slots 1..9 — where blast7 itself moves by **36.7** on those very rows. Two
engaged bodies at once is the one case that is not exact and cannot be: each head's softmax leaves a little of the other
body in, and the old network read the second one through columns it had never trained and a spread on the floor. Everything
behind the first layer — both GRUs, fc2, the output, the value head — keeps its weights and Adam's moments; the layers whose
columns moved start their moments again.

**The starting scores** rank a slot engaged first, then nearest, then bodies before shots, and the empty token sits between
"engaged" and "idle" for every head after the first, so such a head reads another body only if that body is in the fight
too. They are written in raw field units and converted into the normalised space the network reads slots in, so they go on
meaning what they say as the statistics move. They are a starting point: the scores are learned from there like anything
else.

**The normaliser's statistics for the slots are tied** from the conversion on: one set of `stride` means and spreads shared
by all ten slots, taken over **occupied** slots only, and written into every slot's entries of the weight file, so nothing
about the file or the game's normalise step changes. Per slot they are useless to an attended network, which reads a slot
for what is in it rather than for where it sits; the tied ones are the statistics of a body. A run that carries on plain
keeps the untied statistics it has always had.

### `scripts\compare.ps1`: seeded from a copy and from nothing, side by side

```
scripts\imitate.ps1 -Run league-copy -Suite league    the copy first
scripts\compare.ps1 -Copy league-copy                 both arms on the league, runs vs-copy / vs-scratch
scripts\compare.ps1 -Copy league-copy -CopyWorkers 4 -ScratchWorkers 2
scripts\compare.ps1 -Copy vindicator -Suite terrain   the one on one fight instead
```

It starts both runs in the background, output going to each run's `console.log`. The seeded run is `train.ps1 -Seed <copy>
-Demos <copy> -TeacherWeight 0.5`, which takes the copy's state and leaves the copy alone; the other is the same suite from
nothing. The seeded one stops when evaluation says done; the run from nothing only stops at `-ScratchBattles` (3,000,000).

`-Copy` has to be named. It used to default to `runs\vindicator4`, a copy trained against the humanoid's old 634-float
observation whose weights load nowhere, so the default could only fail. It also used to link the copy's `demos` in as a
junction, and now names it with `-Demos`: a link under `runs\` is one more thing for a recursive delete to follow, which
has cost this repository a trainer environment and half an hour of generated ground; see
[findings.md](findings.md#throughput-and-stability).

**This compares two lineages, not two networks.** For "which of these two is better" the answer is `scripts\bench.ps1`,
which puts both on one bench in one sitting with the scripted fighter beside them.

### `scripts\dagger.ps1`: correct a run that is already training

```
scripts\dagger.ps1 -Run league                    one round for runs\league on the league, from its best weights
scripts\dagger.ps1 -Run league -Fights 8000       more of it
scripts\dagger.ps1 -Run league -Weights models\blast\best.mbw       another network's mistakes to correct
scripts\dagger.ps1 -Run vindicator -Suite terrain one vindicator instead, as imitate.ps1's rounds are
```

This is `imitate.ps1`'s correction round on its own, for a run past imitation: the run's own best network drives, the
scripted fighter says what it would have done on every tick, and the answers go into the run's `demos`. What is new is
the suite. On the league the student meets every mob and every loadout in turn, so the record covers a bow, a crossbow, a
shield and every opponent on the roster, which is what a league run can usefully be pulled back towards; a record of one fight
against one vindicator is not.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `-Run` | required | the run whose demos to add to |
| `-Fights` | 4000 | fights recorded, about fifteen per opponent and loadout pairing |
| `-Suite` | league | `terrain` or `arena` for the one on one fight instead |
| `-Weights` | `runs\<run>\best.mbw` | which network drives |
| `-Workers`, `-Slots`, `-Heap`, `-StudentNoise` | 8, 25, 1280M, 0.05 | as for `imitate.ps1` |

Records are named after the suite they were made on: `demos\league-round-1` beside `demos\round-1`, so a league record
never lands on top of a vindicator one and a run can keep both: a melee record made before the naming stays exactly as
usable as it was, and `train.py imitate` and `--teacher-weight` both read every folder under `demos`. A record of an older
*layout* is a different matter — a shard carries its schema id and the trainer refuses a mismatch by name.

The script refuses to write into a `demos` folder that is a junction to another run's: a round recorded there would put this
run's corrections into the other run's record. Nothing makes such a junction any more — `compare.ps1` used to, and names
the record with `-Demos` instead — but a run started before that change still carries one.

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
scripts\imitate.ps1 -Run wide -Demos runs\league -Extra '--h1 512 --hidden 256 --h3 256'
                                                    another shape of copy from a record already collected
```

| Parameter | Default | Meaning |
| --- | --- | --- |
| `-Fights` | 4000 | fights recorded per round |
| `-Suite` | `terrain` | what the record is of: one vindicator with one sword, or the whole league |
| `-Loadouts` | every one | record only these, for weighting a round towards what the copy is worst at |
| `-Demos` | its own | another run's record to copy from instead of collecting one, by name or path |
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

**`-Demos` is how two architectures are compared fairly.** A record is a record of the teacher and has no shape of its own,
so two copies made from the same one differ by their network and nothing else; a second record would differ by its fights
as well, and a run is not started twice from the same seed by accident. It is read and never written, and handed to the
trainer as a path rather than linked in. Correction rounds are skipped with it, since a round is of *this* copy's own
mistakes and would be recorded where the copy it came from never looks.

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
| "dropped N steps that were not finite before the update" | the game handed the trainer a row holding something that is not a number, and the line names the field it was. The rows are thrown away and the run carries on, so this is a thing to look into rather than a thing to fix at once — but a field that keeps appearing is a real bug on the game's side, and `AgentBatch.finite` should have caught it before the shard was written |
| "iteration N was NOT LEARNED FROM" | that update's loss or gradient was not a number, so nothing of it was applied: the weights, Adam's moments and the normaliser are as they were, and the same policy goes out again. `--skip-limit` of these in a row stops the run **without** writing a state, so `state.pt` is still the last one that learned anything and the run can be carried on from it once the cause is found |
| "refusing to write a state that is not finite" | a NaN got past both guards above and into the network itself. The state on disk is untouched and is the one to resume from; the iteration it names is where to look. See [findings.md](findings.md) |
| "was trained against schema X and the game is running Y" | the layout's checksum changed under a stopped run, so the run's weights and shards name a layout this build no longer has. The run has to start again: there is no re-stamping tool any more, and nothing here reads a file whose id does not match. See [species.md](species.md), "If you change a layout that already has trained networks", for what a layout change costs |
