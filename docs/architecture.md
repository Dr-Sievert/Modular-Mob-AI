# How the pieces fit

Two halves that never talk directly.
- The **mod** runs the fights, and the network that fights them, every tick, in Java.
- The **trainer** learns better weights from what the mod recorded, in Python, between iterations.

What passes between them is files in one run folder, and the formats below are the whole contract.

```
mod (Java, every tick)                                trainer (Python, once per iteration)
  observe -> forward pass -> sample -> act               wait for every worker's shard
  write what it did              --- runs/<name>/rollouts/N/*.mbr --->   replay, PPO update
  wait for the next weights      <--- runs/<name>/weights/N+1.mbw ---    export
```

## Inside a tick

At the start of every level tick, before any entity moves, `AgentDriver` gathers every agent in the level, groups them by
the brain driving them, and puts each group through its brain in one call:

1. Each agent's `EnemySlots` refresh, and `AgentObservation` fills its row of the observation.
2. Each agent's hidden vector (its memory) is gathered into the batch.
3. The brain decides:
   - a `NeuralBrain` runs `Forward` over the whole batch (two agents share each pass over the weights), then
     `ActionDecoder` samples (training) or takes the most likely action (deployed). `Forward` has two sets of loops and
     picks one when it loads: explicit vector instructions (`ForwardVectors`) where `jdk.incubator.vector` is there, which
     the build asks for, and plain loops in a jar dropped into any other game. The bits are the same either way, and
     `scripts\parity.ps1` proves it at every batch size from 1 to 64;
   - `ScriptedBrain` is the hand-written teacher.
4. Actions go back into each agent's `MobControls`, and hidden vectors back into each agent's `BrainState`.

Then the entities tick and apply their controls under vanilla's rules, writing what actually happened into
`ExecutedControls`. That record becomes part of the next observation.

What the network outputs is intent, never outcome. The body applies it under a player's rules, and the network learns
those rules from the echo:
- no jump in mid air, and no sprinting backwards or while crouched;
- sixty degrees of turn a tick at most;
- a swing whenever asked, but damage scaled by the attack cooldown;
- movement at a fifth while using an item, and no attacking while using one.

There is no aim assist. The agent turns with its yaw and pitch controls, and a swing hits whatever is under its
crosshair within reach, as for a player.

A swing is resolved the way `Player#attack` resolves one (`AgentMob#resolveAttack`), including the branch that comes before
the damage: a target in the `redirectable_projectile` tag — a ghast's fireball, a wind charge — is **deflected along the
agent's own look**, taken over as the agent's own projectile, and the swing ends there, costing the cooldown a landed blow
costs and dealing nothing itself. This branch was missing for a long time and the swing reached `Fireball#hurt` instead,
which refuses every blow, so the press was spent for nothing. What it buys is the six damage and the blast that were coming
at the agent going somewhere else, and a fireball the agent then owns and can put into something. It does **not** kill the
ghast: a ghast is fire immune and vanilla forgives that only for a fireball a `Player` owns, which the agent is not; see
[findings.md](findings.md). It is not one of the three places below — it is one of the places the hands were *not* yet a
player's, and now are.

## What the agent sees: 792 floats

This is the **humanoid**'s observation, the player-shaped body every trained network drives. A layout belongs to a body
rather than to the game: a body with no hands has no hotbar to see, no slot to choose and no use buttons to press, and no
amount of masking makes those inputs mean anything. `brain/schema/Species.java` is what the rest of the game asks how wide
an observation is, and the humanoid's answer is the table below. The second body, the **beast**, has no hands: 769 floats,
seven controls and no categorical head at all. See [species.md](species.md) for how to write a third.

**Nothing goes in here that a real game cannot supply the same way.** The mod runs server-side in an ordinary game — see
[playing.md](playing.md) — so anything that server knows about the agent's own body, what it holds and wears, and the
entities it perceives is fair, and a field of that kind reads the same number in an arena and out in a world. What only the
arena knows is not, however much it would help: how long this fight is given, who the other side is, how it ends. Those go
to the **critic** instead, which is never exported (`arena/FightFacts`, and the critic below). The clock is the edge of the
rule and passes it: elapsed ticks are a count the body itself keeps, and an agent with no episode reads nought for the whole
of its life, which is the honest reading of a clock that will never expire. The limit it is a fraction of fails the same
test and stays with the critic. A field that would read one number in training and nothing at all in a real game is a field
that would quietly make every trained network worse the moment it left the arena.

| Block | Size | Contents |
| --- | --- | --- |
| self | 24 | health, velocity (forward/up/right), on ground, in water, attack strength, use cooldown, using (main/off hand), sprinting, crouching, fall distance, body offset (sin/cos), pitch, aim (sin/cos), hurt time, enemies in range, **the clock**, **arrows left**, **its own armour**, **what its weapon takes off** |
| hotbar | 9 | what each hotbar slot holds |
| echo | 20 | what the body actually did last tick: moved (forward/strafe), jumped, sprinted, sneaked, turned (yaw/pitch), attacked, hit, attack strength and damage, crit, sweep, sprint knockback, used (main/off hand/on a block), selected slot, swapped weapon, **how far the use has charged** |
| enemies | 10 × 31 | every hostile within 32 blocks, and anything shot at the agent, in ten stable slots, in its own frame. Where it is and what it is doing: present, position (forward/up/right), distance, velocity, health as a fraction, facing (sin/cos), pitch, **kind**, main and off hand item, swinging, using, sprinting, **whether it has the agent as its target**. **What it is**: max health and health left in hearts, attack damage, speed, width, height, knockback resistance, **armour**, a creeper's fuse, and whether it explodes, shoots or flies |
| terrain | 9 × 5 × 9 = 405 | the blocks around it, from 2 below the feet to 2 above: 0 empty, 0.5 fluid, 1 solid by collision, 1.5 hazard. A hazard hurts or kills a body in it or on it: lava, fire, magma, cactus, lit campfires, wither roses, pointed dripstone, powder snow, berry bushes, cobwebs. An empty cell in the bottom layer reads as a hazard when the fall below it would be more than 8 blocks, or would end in a hazard |
| rays | 8 × 3 = 24 | eight rays out from the feet, one every 45° and world aligned as the grid is: how far to a wall, to something that hurts, and to a drop of more than 4, each a fraction of 32 blocks and 1 where that ray found none. The grid is a block a cell and reaches four, so this is the agent's only sight of the lava lake, the ravine lip or the wall at its back; widening the grid to the same distance would be 1,445 cells against 405 |

Animals and villagers never take an enemy slot. The enemy's `kind` says what sort of thing it is: another agent, a
player, a monster, something else alive, or, below zero, something shot at the agent. The layout is fixed: every trained
network depends on it, and the schema id refuses a mismatch.

**What the opponent is, not just where.** `kind` has five values and every hostile mob in the game is the one value
"monster", so for a long time a creeper, a zombie, a ravager and a warden filled a slot identically: same kind, health as
a *fraction* so all of them read 1 when whole, and empty hands for all four. The league showed the bill — every ordinary
mob beaten 75 to 98%, and 0% against the warden and against two creepers — because the network was being asked to tell two
hundred opponents apart by how they moved, and to learn how hard one hits by being hit, which against a creeper is a fight
too late. So a slot now carries the capabilities the tactics turn on: **hearts rather than a fraction** (what it has and
what it has when whole), attack damage, speed, width and height, knockback resistance (a warden barely moves, which decides
whether it can be pushed into anything), a creeper's **fuse**, and three flags for explodes, shoots and flies. Capabilities
rather than a species number, so a mob the run never met still describes itself, and one policy conditions on what it faces
instead of memorising a roster. Written by `AgentObservation#writeCapabilities`. The humanoid's field constants are in
`brain/schema/ObservationSchema.java`, its controls in `ActionSchema.java`, its encoder in `AgentObservation.java`, and the
three add up to `Humanoid.java`, the descriptor the rest of the game reads.

**The use charge** (the echo's last field) is how far the item in use has come, as the item itself reckons it: a bow
gives the power its arrow would leave at, a crossbow the fraction of its wind that is in, anything else how much of its
use duration has run. Both weapons read 1 at the moment letting go is worth it, so one number says "loose now" for
either. Nothing else in the observation says a bow is nearly drawn, and a bow needs twenty ticks of held use before an
arrow ever flies.

**The clock** (the self block, `SELF_CLOCK`) is how much of this fight's own time limit has run: nought on the first tick,
one once the time is up, read straight off the reward's count (`AgentReward#elapsedFraction`) so the agent sees the clock it
is being paid by. The reward is a function of the clock and nothing in the observation was: a fight is capped at 1,200
ticks, running the clock out is scored as a loss, and a win pays a speed bonus that scales with how much clock is left, so
the same position on the same ground is worth about +3 at tick 100 and -2 at tick 1,150. The critic could not price that
difference, which put the noisiest advantages in exactly the fights that drag on, and the policy could only learn urgency
by counting to 1,200 inside the GRU. It is a fraction of **this** fight's limit rather than of a constant, because a league
matchup sets its own clock and a longer one has to read as more time left. An agent out in a world has no episode and reads
nought for the whole of its life, which is the honest reading of a clock that will never expire.

**Arrows left** (`SELF_ARROWS`) is what is in the quiver, over the 64 a bow or crossbow loadout carries, and nought for a
body carrying nothing that shoots. A quiver that can empty was invisible: an agent on its last arrow read exactly like one
on its first, and the only thing that knew better was the teacher, which infers an empty quiver from a use press that
produced nothing — an inference a network sampling a button afresh each tick cannot make. An Infinity weapon reads full
whatever it carries, since vanilla spends nothing from it and the real-game loadouts carry a single arrow for that reason
(`arena/Loadouts`). The beast gets the clock as well, for the same reward and the same timed fights, and not the arrows:
it has no hands, so there is no quiver to be out of.

**Armour, on both sides of the fight** (`SELF_ARMOUR`, `ENEMY_ARMOUR`), each over the 20 points a body tops out at. Armour
was nowhere in the observation at all: a zombie in iron takes under half the damage a bare one does from the same swing and
read as the same zombie with the same empty hands, and the agent's own armoured loadout read exactly like the plain sword
while halving what every blow of the fight cost it. It is also most of what a hard rung of the league's ladder changes — a
rung is how likely a mob is to spawn in armour and to have it enchanted — so a network that could not see armour could not
see what made the rung hard. Both read `getArmorValue`, the same call the damage a blow gets through is worked out from and
the same one the critic's `FOE_ARMOUR` and `OWN_ARMOUR` read, so a slot and the privileged column cannot drift apart; one
mechanics test asserts they are the same number.

**Whether the other side has actually come for the agent** (`ENEMY_TARGETS_ME`), per slot. Facing was the only proxy and it
is poor both ways: a mob that has just let its target go goes on facing the agent for as long as it takes to turn away, and
one walking over from behind a hill faces nothing yet. Whether the other side engages decides whether the fight happens at
all, and a fight that does not happen runs the clock out, which is paid as a loss. The rule is `Allegiance#goesFor` — its
own target, and an agent always, since an agent fights from a network and holds no target — and the enemy slot, the critic's
`WENT_FOR` and the league's "went for" column all ask that one.

**What the weapon in its hand takes off** (`SELF_WEAPON_DAMAGE`), over 20. The hotbar says "a sword" for stone, iron and
diamond alike, and the echo says what a blow took off only once one has landed, which against something that kills in three
is a fight too late. It is added up from the held item's own attribute modifiers rather than read off the attack damage
attribute, and the difference is a tick: vanilla applies a held item's modifiers when it notices the equipment change, in
the body's own tick, and the observation for that tick was written before any body moved. So the attribute is what the
*last* tick's hand was worth — a bare fist holding an iron sword on the first row of a fight, and the weapon swapped out of
on the row after a slot change, while the blow on that row already takes off what the weapon now held does. The critic can
be priced off a row it reads late (`OWN_DAMAGE` is the attribute, and documents the same quirk); a policy deciding whether
to swing cannot. The beast has the same field under its own name, its bite rather than a weapon, since a blow is paid out of
the same strength whichever body throws it; and it has armour for the same reason it has a clock — neither needs a hand.

**There is no count of the other side**, and that is the rule above doing its work. The critic gets one (`FOES`); what makes
it worth having is that it counts the side whether anything is perceived or not, so an opponent behind a hill is still two
zombies standing. In a real game there is no roster to count, and a count over the radius the agent does perceive is
`enemies in range` again, since both already ask one rule for who is an enemy (`Allegiance#isEnemy`). A field that
duplicates its neighbour costs weights and teaches nothing, so none was added.

### The three places the hands are not a player's

All three are there for one reason, and it is not a shortcut: a network chooses every control afresh each tick from a
distribution, so a skill that needs the same choice for twenty ticks needs that distribution to land twenty times over, and
it never does. Measured, the network's longest hold was six ticks and not one draw in 2,984 reached twenty. See
[findings.md](findings.md#learning) for the numbers and for what it cost.

**A draw runs to full on its own.** Once a bow or a crossbow has started drawing, the button coming up does not stop it,
and the agent's choice comes back only when the weapon is charged — hold to keep aiming, let go to loose. A press is
therefore one arrow rather than twenty presses in a row. The draw is still paid for, a fifth of the movement for every tick
of it and the swing swallowed. Everything else drops the moment its button does: a shield, a bite of food.
`AgentMob#drawingToFull`.

**The hand keeps the slot it started a draw in.** The button is not the only thing a draw needs twenty ticks of; the slot
is the other, and changing slot cancels a use outright, with no release and so no arrow. So while a bow or a crossbow is in
use in the main hand, a slot the brain asks for is refused, and it is granted on the first tick after the use ends — the
arrow goes, and the sword comes up on the tick behind it. What it is worth is nearly every arrow a mixed loadout ever
fired: a bow alone, which has no other slot worth slipping to, finished 91% of its draws, while a bow with a sword beside it
started 2.42 draws a fight and loosed 0.09 arrows. Nothing is queued: the intent buffer is a keyboard, not a list of events,
so a refused slot is simply the one the brain is still asking for. A loaded crossbow is not held to its slot, since its bolt
is in the item rather than the hand, and an off-hand draw is not either, since changing slot does not disturb one.
`AgentMob#drawHoldsTheSlot`.

**A crack waits for the next press.** Breaking a block takes eight ticks of held attack for powder snow, eight for a
cobweb, fifteen for dirt, and a player's client throws the progress away the instant the button lifts. The agent keeps it
while the aim stays on the same block, the press still being the only thing that deepens it, so a block costs the presses
it costs a player and only the gaps between them are forgiven. Looking somewhere else still loses it. Without this the
agent could break nothing at all, and so could not dig out of powder snow or a cobweb whatever the teacher showed it.
`AgentMob#continueDestroying`.

Letting a cracking block go comes with the same decision: a player's client restarts the attack cooldown there and the
agent's does not (`AgentMob#stopDestroyBlock`), because a swing that meets a block costing nothing is what every trained
network fights by. It is the reason a press pointed at a block is completely free, which is the whole of why the agent
holds attack down on stretches of a hundred ticks and more; putting the rule back would take a fifth off the damage of
every blow those networks land. See [findings.md](findings.md#learning) for the measurement.

**Shots in the enemy slots** (`EnemySlots`): a slot can hold an arrow, a bolt, a wind charge or any other projectile on
its way to the agent, with a `kind` of its own below zero and its health, hands, swing and use left at zero, since an
arrow has none of those. Two rules keep that from spoiling what a slot means:
- Bodies come first and are never displaced. Projectiles take only the slots nothing alive wants, and a body arriving
  with no free slot evicts a projectile before anything else. Otherwise a network's nearest enemy could quietly become
  an arrow two blocks away while the skeleton that fired it fell out of the view.
- Only what is actually coming. A projectile earns a slot while it is still moving, while the agent is ahead of it
  rather than behind, and while its line would pass within a block and a half. Its own arrows and an ally's never do.

`inRangeCount`, which the self block carries, still counts bodies only.

## What it controls: 11 controls, 19 network outputs

| Control | Kind | Outputs |
| --- | --- | --- |
| move forward/back, strafe | continuous | 2 means |
| turn yaw, pitch | continuous | 2 means |
| jump, sprint, sneak, attack, use, use off hand | on/off | 6 logits |
| hotbar slot | one of nine | 9 logits |

The continuous controls add a learned spread while training (`logStd`, per control). A deployed agent uses the mean.

## The network

`792 -> 256 -> GRU 128 -> 128 -> 19`: an encoder, a recurrent layer that carries 128 numbers of memory from tick to tick
for the whole fight (blank at the start of each fight), and one head per kind of control. That's 371,783 parameters.
Training runs the GRU through chunks of 32 ticks. The widths are trainer options (`--h1 --hidden --h3`); the game reads
them from the weight file, so a wider network needs no Java change. `scripts\parity.ps1` checks that the Java forward
pass matches PyTorch's to within about 1e-6.

That is the whole of what the game runs. The **critic**, which PPO needs to work out what a position was worth, is a second
network that never leaves the trainer: it is not exported, no weight file mentions it, and nothing in the game has to carry
weights it will never use. Because it is never exported it is allowed to know things the agent cannot, and it is told two
sets of numbers besides the observation — what only the trainer knows about the episode, and the privileged floats the game
writes into every rollout row. None of them reaches the actor, so the layout above, the schema id and every trained network
are untouched by anything the critic is given. See [training.md](training.md#the-critic).

## The reward (`arena/AgentReward`)

| Case | Dealt | Taken | Outcome | Total |
| --- | --- | --- | --- | --- |
| instant kill, untouched | +1.50 | 0.00 | +3 | +4.50 |
| slow kill, nearly dead | +1.50 | -0.99 | +2 | +2.51 |
| died at the buzzer, nearly won | +1.49 | -1.00 | -2 | -1.51 |
| stalled to the timeout | 0.00 | 0.00 | -2 | -2.00 |
| killed immediately | 0.00 | -1.00 | -2 | -3.00 |

A win pays 2 plus up to 1 for speed. A loss, or running out the minute, costs 2. Damage is paid by the health it actually
removed, dealt at 1.5 and taken at 1.0. Nothing is paid for lasting longer: that taught agents to run. Damage is paid in
one place, where it lands (`LivingEntityMixin`), so arrows count the same as swings. The agent sees the clock this table
turns on: the self block's `SELF_CLOCK` is the same fraction `AgentReward` charges by.

## The fights

Training fights are one agent against one vindicator on natural terrain, generated as a normal world. A fight ends when
either fighter dies, or after 1200 ticks (a minute), which counts as a loss. A league fight's clock is the matchup's; see
the league below.

Each worker runs 25 fights at once on a quarter again as many sites (32), plus 4 spares, all held by
`gametest/terrain/TerrainSites`:
- A site is 80 × 80 blocks (5 × 5 chunks) and sites sit 128 blocks apart, 8 to a row: three chunks of dead ground between
  one fight and the next, which is why nothing on one site can reach or see anything on another. Both follow one number,
  `-PsiteRadius` (`scripts\train.ps1 -SiteRadius`, `scripts\terrain.ps1 -Radius`), which is chunks either side of a site's
  centre. It is one size for a whole run, not per matchup: a site's chunks are nearly all of a worker's heap, and the
  terrain library holds its sites at the size it was built for. A worker will read a library built for bigger sites and use
  the inner part; a library built for smaller ones it refuses and says which `-Radius` to rebuild with.
- A site is handed out with a place to stand for the agent and one for each of the other side, 7 to 11 blocks away, a
  squad's members within 3 blocks of each other. It also knows what is on it, which the league draws a share of its fights
  by; see the league below.
- Every fighter starts with open sky above it. Starts under canopies and mangrove roots lost 9.8% against 0.8%.
- A site hosts 100 fights, then moves on to fresh ground once a spare is ready. A site where 2 fights time out is
  retired at once: that's the ground, not luck.
- Chunks unload when a site moves on (`ChunkMapMixin`). Nothing is saved while tests run (`ServerLevelMixin`), and
  vanilla skips unloading entirely for a level that doesn't save, so without that mixin workers ran out of memory.

### The terrain library

Generating that ground was most of what a worker did besides fighting: two to three cores, and the ring of part generated
chunks the generator needs around every site was most of the worker's heap. So the sites are generated once and kept, by
`scripts\terrain.ps1` (`gametest/terrain/TerrainLibrary`, the `library` suite):

- Sites go in blocks of 128, each block placed on land of its own, on the same lattice the fights use. Every site is
  checked for somewhere a fight can start; the ones that are water or cliff are listed as unusable and never handed out.
- The build puts the builders' worlds together into `runs/terrain/<minecraft version>/library`, with an index saying where
  the blocks are, how the lattice is laid out, which points are unusable, and anything else known about a point (`kinds`,
  for the league to draw hazardous ground deliberately; nothing fills them in yet). 2,048 sites are about 1.6 GB.
- **The library grows.** `scripts\terrain.ps1 -Add 1024` generates only the new sites, in blocks placed well clear of every
  block already in it, and appends them: points are numbered block by block, so the ones that existed keep their numbers.
  The new ground is moved in whole, and only then is a new index moved over the old one, so nothing half finished is ever
  readable and a worker already running never notices. 1,024 sites appended to a 4,096-site library took 9 minutes on two
  builders, against about 38 to generate all 5,120 afresh.
- Every terrain worker hard-links the library's region files into its own world, so it is on disk once however many
  workers run, and walks the library from a place of its own. Vanilla loads a finished chunk whose neighbours are on disk
  rather than generating it, and reads nothing further out, so no ground is generated in a worker again.
- Nothing a worker does writes to the library: nothing is saved while tests run, a world on the library is thrown away
  rather than saved, and `RegionFileStorageMixin` refuses a chunk write at the one place chunk data reaches a region file.
  Entities and points of interest are not linked, so the wildlife the generator put down never appears either.

Measured on one worker, 25 slots, 20,000 fights: 1.30 cores instead of 2.54, a third fewer chunk sections held, every
site ready in 16 s instead of 64 s, and slots idle waiting for a site down from 55% to 12.8%. A tick costs the same
either way; what the library saves is the cores and the memory that decide how many workers fit.

Without a library, workers generate their own ground and keep the worlds they generate in
`runs/terrain/<minecraft version>`, up to a pool of 8 (`terrainPool`), each reused at most 8 times (`terrainUses`); a
later worker then reads its first sites from disk in seconds instead of generating them. `-PterrainLibrary=false` asks
for that even when a library exists.

## The league

The league suite (`-Psuite=league`, `scripts\train.ps1 -Suite league`) is the same fight on the same sites against a
different opponent every time, with a different loadout:
- 37 mobs (`gametest/league/Roster`, which also says why the rest are left out): zombie, husk, drowned, zombie villager,
  skeleton, stray, bogged, wither skeleton, spider, cave spider, creeper, vindicator, pillager, witch, ravager, enderman,
  silverfish, endermite, slime, magma cube, zombified piglin, piglin, piglin brute, hoglin, zoglin, breeze, evoker,
  blaze, ghast, phantom, vex, bee, wolf, polar bear, iron golem, snow golem, warden. Each gets its own finalizeSpawn, is
  grown up, kept from zombifying and, for a slime, made its biggest, and is made to go for the agent every tick it has
  let go: as its target, angered, or in its brain's memory. Slimes and breezes treat the agent as a player
  (`SlimeInvoker`, `BreezeMixin`); a bee never counts as having stung, or it would die of its own sting (`BeeMixin`); a
  snow golem is given fire resistance, or a warm biome would melt it.
- Whatever flies starts in the air over its spawn spot, which always has open sky: a ghast 8 blocks up, a phantom 6, a
  vex 3, a blaze and a bee 2. A flyer cannot be reached in melee at all, which is what the bow and crossbow loadouts are
  for. An evoker's vexes are taken into the fight as it calls them, and swept up with it.
- The warden is a benchmark, not a lesson: nothing beats it and the reward cannot pay for escaping, so its share of the
  training fights is capped at 0.2% (`Roster.Member.trainingCap`, written into `roster.csv`). It stays fully rated.
- 11 squads of several mobs at once (`gametest/league/Opposition`): `2x_zombie`, `2x_vindicator`, `3x_silverfish`,
  `2x_skeleton`, `zombie+skeleton`, `witch+zombie`, `pillager+vindicator`, `spider+cave_spider`, `2x_wither_skeleton`,
  `2x_creeper`, `phantom+zombie`. They are curated, not generated: every pair of 37 mobs would be 600 ratings saying
  little. **Each composition is a player of its own** — two zombies are not twice a zombie, and nothing anywhere adds a
  squad's members up. A squad fights as a side: the agent on one team and all of them on another
  (`allegiance/Allegiance`), so each goes for the agent and the agent counts every one an enemy whatever it is; the teams
  are disbanded the moment the fight ends. A fight against one mob still uses no teams at all. The warden is in no squad,
  and a squad fight is not recorded for the viewer, whose format holds two fighters.
- A difficulty ladder, three rungs per opponent, the name saying which: `zombie` on normal, `zombie(hard)` and
  `zombie(easy)`. A rung is the `DifficultyInstance` the mob's own finalizeSpawn is handed — on hard it is likelier to
  spawn in armour, likelier to have that armour and its weapon enchanted, rolls higher on the bonus health, damage and
  follow range a zombie rolls for, and a spider gets a potion effect it never gets below hard. Nothing else changes. The
  few things vanilla decides mid fight from the level's own difficulty (a husk's hunger, a zombie's reinforcements) stay
  on normal for every fight: difficulty there belongs to the whole level and fifty fights share one, which is also why a
  normal fight is exactly the fight it was before the ladder. **Each rung is a player of its own.** The trainer opens one
  when the agent's evaluated win rate against the opponent passes 80% (hard) or is still under 20% (easy), over at least
  30 evaluation fights, and a rung once open stays open. Only a mob or a squad has rungs at all: a rung is how hard the
  mobs on the other side spawn, and neither the scripted fighter nor a published network has anything to turn up. A run with no trainer goes round every rung the build enabled
  (`-PleagueDifficulties`, normal and hard by default).
- The scripted fighter and frozen checkpoints of the run, as another agent with a brain of its own on its most likely
  action, so only the agent's steps are recorded.
- **Published networks a run names** (`gametest/league/Published`, `scripts\train.ps1 -LeagueModels blast`).
  A network under `models\` is a fixed policy anyone can load, so it plays as another agent exactly as a checkpoint does
  and is rated under its own name. That is what puts two lineages on one tier list: a run that trained from the teacher
  and one that trained from nothing never meet, but both can rate the same published network, and each run's own checkpoints are then a
  known distance from a player the other run fought too. A model is **not** a second anchor — the scripted fighter alone
  is held still, so a model's rating is measured rather than asserted, and two runs disagreeing about what it is worth is
  the sign that their scales have drifted apart. It never learns, so it is weighed with the mobs rather than in the self
  play share, its fights judge a checkpoint as the scripted fighter's do, and it draws from every loadout, as a
  checkpoint does. A network of another **body** is refused by name, naming both bodies, before a fight is set up; see
  [species.md](species.md).
- 10 loadouts (`gametest/league/Loadouts`, armed with `arena/Loadout`): iron, stone and diamond swords, an axe, a sword
  with iron armour, sword or axe with a shield, a bow, a crossbow, a sword with a bow behind it.
- **Room and time per matchup** (`Roster.MELEE_TICKS` and its neighbours). A melee fight keeps the minute and the 7 to 11
  blocks it always had. A fight against something that shoots from the ground (skeleton, stray, bogged, pillager, witch,
  breeze, evoker, snow golem) gets 1,800 ticks and starts 20 blocks apart; something flying gets 2,400 and the same 20,
  plus its air overhead. A squad takes whatever the mob on it that wants most asks for. A fight against another agent, the
  scripted fighter or a checkpoint, keeps the melee minute whatever loadout it drew, so their ratings do not move. Ground
  with no room for the wanted distance falls back to the ordinary one rather than losing the fight, and the clock is also
  what the speed bonus is paid against, so fast means fast for the fight it was.
- **Ground worth using.** Each site is labelled by what is on it (`gametest/terrain/SiteHazards`): `lava`, `drop` (a cliff
  or ravine edge, a fall of more than 8), `hazard` (fire, magma, cactus, powder snow, berries, cobweb, dripstone), `water`
  or `flat`. A quarter of the league's fights (`-PleagueHazards`) look for ground with something on it, since the terrain
  is a weapon: a hundred health of iron golem goes into a lava lake as easily as a zombie does, and a fight the ground
  finishes is already the agent's win. Only a quarter, because the plain melee on plain ground is still the fight it has to
  win, and a run that only saw hazards would learn to hunt for them. Every fight records the ground it was on and what
  finished the other side — the agent, the ground (as the damage names it: `lava`, `fall`), its own side, or nothing — and
  `league/ground.csv` adds that up per kind of ground. **That number is the point**: a fight the ground ends counts as a
  win either way, so terrain finishes rising on lava and cliff sites is the only sign the agent has learned the trick.
  Labelling happens when a site is handed out, not in the library's index: it costs about 400 block lookups once per site
  (a site hosts 100 fights), works on a library already built, and leaves the index format alone.
- **Lava poured where there is none** (`gametest/terrain/PouredHazards`, `-PpourHazards=false` to turn it off). The
  overworld surface is the wrong place to look for lava: 2% of the library's fights had any, which is far too rare for the
  best blow in the game to be learned from. So a fight that asked for hazardous ground and was handed flat ground gets a
  three by three pool poured three to seven blocks from the middle of it, clear of both sides, and is then recorded as a
  `lava` fight, which is what it is. That takes lava from 2% of fights to the whole hazard share.
  - The pool is **flush** with the ground it replaces, not a pit: a body knocked onto it is in it, and lava level with
    solid ground on every side cannot flow. What is under it is made solid first, or a pool over a cave empties into the
    cave. Flush is checked over the whole five by five the pool and its ring occupy, not just the middle column: one block
    of step at the rim puts a lava block over open air, and from there it runs and falls. A spot that is not level is
    passed over, and over 1,500 league fights that cost a fifth of the poured pools — 103 `lava` fights against 129 with no
    check at all. **What stands on the ring is not asked about, only how high its ground is**: the pour pulls every plant in
    the five by five up before a drop of lava goes in, and demanding clear ground in the ring as well passed over most of
    the overworld's flat grass, a single fern in any of twenty-five columns being enough. That cost two thirds of the pools,
    57 fights against 162, which is the whole hazard share the pour exists to fill.
  - Every block it changes is remembered and **put back when the fight ends**, with a neighbour update, and a box wide
    enough to hold what lava can reach is swept for fire and lava that were not there before. This matters more than it
    sounds: a site hosts a hundred fights and the ground is a hard-linked library shared between workers, so one pool left
    behind would be there for the other ninety-nine. The neighbour update is what makes a leak last a fight rather than the
    site's whole life — a flow only works out that its source has gone on a scheduled fluid tick, and nothing but a
    neighbour update schedules one — and a drain that had to sweep anything up forgets the site's label, so the next fight
    works out again what is on the ground rather than reporting the ground it used to be.
  - League fights also run with **`doFireTick` off**, so fire never spreads. One pool beside a birch forest, left for a
    minute, would burn a library site down for good. Nothing that makes lava lethal depends on that rule.
- League fights happen at midnight, clear and with mob griefing off: no undead burn, spiders stay hostile, rain neither
  hurts a blaze or a snow golem nor teleports an enderman, and no crater stays in a kept world. A creeper that blows
  itself up without killing the agent is a draw, which pays as a loss.

The trainer decides who the agent meets and rates everyone (`trainer/mmai/league.py`). What it shares out is a **pairing**
of one loadout with one opponent, not an opponent to be handed a loadout afterwards: drawn apart, a bow went to a creeper it
should kite as often as to a ghast it cannot reach, and what a run learned about drawing a bow was averaged over both.
Training fights are shared by the agent's chance in each pairing times its complement, from its recent fights in that
pairing and filled in from the loadout's own record, the opponent's and the ratings, with a quarter spread evenly and a
fifth for a pool of 8 checkpoints (the newest 4, and 4 spread over the run). A cap is the opponent's and holds every loadout
against it down between them. Evaluation fights, one in ten, play a checkpoint against an opponent drawn evenly from
everyone with a loadout drawn evenly too — pairing them would move the scale every rating is measured on — and those are
rated: Elo, K 16 (32
for a player's first 30 fights), the scripted fighter held at 1500. Those against anything that holds still — the mobs,
the scripted fighter, a published network — are also the checkpoint's evaluation, so best weights and the end of the run
work as on the terrain suite, on 1,000 fights each. `scripts\league.ps1 -Run <run>` prints the tier list and the tables,
and the viewer draws them: `scripts\viewer.ps1 -League`, see [viewer.md](viewer.md).

| File (`runs/<run>/league/`) | Written by | Holds |
| --- | --- | --- |
| `roster.csv` | each worker as it starts | `opponent,kind,cap`: the mobs, the scripted fighter and the published networks it fields, and the largest share of the training fights each may take (1 for no cap). `kind` is `mob`, `squad`, `scripted` or `model`, and `loadout` for the rows that are not opponents at all but the loadouts the worker arms the agent with, which the trainer needs to weigh a pairing |
| `results/wNN.csv` | each worker, a line a fight | `iteration,kind,opponent,loadout,opponent_loadout,outcome,ticks,cause,site,finish,weapon,swaps,uses,shots,replay`; kind `train` or `eval`, outcome `win`, `loss`, `timeout` or `draw`, cause what the agent died of when it died, site what was on the ground, finish what finished the other side (`agent`, `side`, a damage name like `lava`, or `-`), then what the agent did with its hands — the item it held longest, ticks that changed the kind of item held, uses begun, arrows and bolts loosed — and the file its replay is in, or `-`. **The columns grow to the right and never move**: a run appended to across builds has short older lines, and both the trainer and the viewer read one as saying nothing about what it leaves out |
| `pairs.csv` | the trainer, every iteration | `loadout,opponent,share,chance,fights,wins`: each pairing of a loadout and an opponent, the share of the training fights it gets, the agent's chance in it, and the faded training record behind that chance. **This is what a worker draws a training fight from**; largest share first |
| `matchmaking.csv` | the trainer, every iteration | `opponent,share,chance,rating,fights`: the same shares added up per opponent, which is what the tables and the tier list read, what says which checkpoints are in the pool, and what a worker falls back to when there is no pair table |
| `ratings.csv` | the trainer | every player's rating and rated record |
| `opponents.csv`, `loadouts.csv` | the trainer | the agent's last 200 evaluation and training fights against each opponent and with each loadout |
| `ground.csv` | the trainer | every fight of the run on each kind of ground, and what finished the other side: the agent, the ground, its own side |
| `evaluations.csv` | the trainer | every evaluated checkpoint's record against each opponent |
| `state.json` | the trainer | what a resumed run needs to carry the league on, the rungs of the ladder it has opened included |

## The code

```
mod/                    the Gradle build (MultiLoader: common + fabric + neoforge)
  common/src/main/java/net/sievert/modularmobai/
    entity/agent/         the agent: body, controls, the record of what executed, item and block rules
    brain/                the driver, the batch, the brains (scripted, neural, demonstration) and the training link
    brain/schema/         what an agent sees and does: Species (a body's layout), the humanoid's and the beast's own
                          tables and encoders, enemy slots
    brain/nn/             the network runtime in plain Java: topology, weight file, forward pass, heads, rollout writer
    arena/                a fight someone set up: loadouts, the reward, what the agent may see
    allegiance/           sides: vanilla teams, the agent's enemy rule, mobs going after other teams (see playing.md)
    command/              /mmai (see playing.md)
    mixin/                vanilla changes the agent needs (placing, axes, damage payment, bow access, friendly fire,
                          mobs' goal for other teams)
    Config.java           config/modular_mob_ai.properties, read by agents in a real game only
  common/src/gametest/java/net/sievert/modularmobai/gametest/
    tests/                the fights (closed arena, natural terrain, the league), the mechanics suite and the play suite
    terrain/              the terrain sites, the library they come from, and what each site has on it
    league/               the league: the mobs and how each is fielded, the squads, the loadouts, the draw and the results
    replay/               fight recording for the viewer (FightRecorder, SiteBlocks)
    mixin/                game-test-only server changes: no saving, chunk unloading, no idle chunk ticking
    tools/                BrainTool: schema export and the parity check, runs without the game
  buildSrc/               the build logic: runTraining, runGametestParallel, workers, memory guard (multiloader-loader.gradle)
trainer/                PyTorch: train.py and mmai/ (ppo, model, evaluate, run folder protocol, weights, rollouts)
viewer/                 the replay viewer: serve.py and the page
scripts/                everything you run
models/                 published networks, in git
runs/                   training runs, not in git
```

## Files

Everything is little endian.

### `schema.json`

Written by the mod (`BrainTool schema <species> <file>`) at the start of every training run, for the body that run is
training. It names the species, then the observation blocks in offset order, the action names, and the head table saying
how the network's outputs become actions. Its **schema id** is the CRC32 of the file's bytes, **species name included**. It
is stamped into every weight file and shard, so a mod running a different layout, or a network handed to the wrong body,
refuses the weights instead of feeding a network the wrong numbers.

The blocks are a list rather than an object keyed by name, because which blocks a body has is the thing that varies. Each
carries whatever facts its kind has — an enemy block's `slots` and `stride`, a terrain block's `x`, `y` and `z` — and the
training side reads them rather than assuming them; they have to multiply out to the block's size, which is checked on both
sides. `-Pspecies=<name>` says which body a run is for; it is `humanoid` unless asked.

### `.mbw`: weights

Written by the trainer, read by the mod. A 52-byte header, then every parameter as one float array.

```
0   'MBW1'
4   u32 format version (1)
8   u32 schema id          which body's layout these weights were trained against
12  u32 topology hash      CRC32 of the six dimensions below
16  u32 obsDim, h1, hidden, h3, outDim, stdDim
40  f32 observation clip
44  u32 iteration
48  u32 parameter count
52  f32 parameters
```

Parameters are in this order, matrices row major `[out][in]` exactly as PyTorch stores them:
`normMean[obs] normStd[obs] fc1W fc1B gruWih[3H x h1] gruBih gruWhh[3H x H] gruBhh fc2W fc2B outW outB logStd[std]`.
The GRU gates are in PyTorch's order: reset, update, new. The observation normaliser travels in the file because the
network is meaningless behind any other one.

Anything that doesn't add up is refused and never coerced: wrong magic, version or schema id, a hash that disagrees with
the dimensions, a parameter count or file length that disagrees with the topology, a non-finite parameter.

### `.mbr`: rollout shards

Written by the mod, one per worker per iteration, read by the trainer. A 64-byte header, the rows, then the segment table.
Version 2; version 1 is refused, not read, and the trainer's message says which version it found. A shard is one
iteration's experience, learned from and deleted within the minute, so there is no such thing as a legacy shard worth
keeping, and filling the columns version 2 added with zeroes would tell the critic there was no opponent and no clock.

```
header   16 u32: 'MBR1' | version | schema id | topology hash | iteration | round | worker | worker count
                 | obsDim | actDim | hidden | final | row count | segment count | steps | privDim
row      u32 agent | u32 flags | f32 reward | f32 log probability | f32 action[actDim] | f32 obs[obsDim] | f32 priv[privDim]
segment  u32 row | f32 h0[hidden]
```

The **privileged floats** are `arena/FightFacts`, for the critic, and nothing an exported network ever sees; see
[training.md](training.md#the-critic) for what they are and why each one. They sit at the end of the row, after the observation, so
that one reshape reads a shard and nothing before them moved, and their width is in the header word the format kept spare
rather than assumed. The two sides of the list are `FightFacts` in the game and `SHARD_PRIVILEGED` in
`trainer/mmai/model.py`; a shard whose width disagrees with this build is refused by name.

Flags:
- 1: the agent's episode starts on this row;
- 2: the fight ended on this row;
- 4: the shard was cut here while the fight went on.

A row's reward belongs to the action on that agent's previous row. A segment is one agent's run of rows in one shard. It
starts on a row listed in the segment table, with the hidden state the agent had going in, and its last row carries an
observation, its privileged floats and a reward but no action — that row is what a cut fight is bootstrapped from, so the
critic has to be able to price it. `final` means the worker is shutting down and will send nothing more. Shards are
written under `.tmp` and renamed when complete.

### Run folder control files

| File | Written by | Meaning |
| --- | --- | --- |
| `trainer.status` | trainer | `waiting N R` or `training N R`: the iteration, and the highest round fully learned from. The build waits for `waiting` before starting a round |
| `rollouts/gone/r0003-w01` | build | worker 1 of round 3 died; the trainer stops waiting for its shards |
| `rollouts/rounds/r0003.done` | build | every worker of round 3 has exited |
| `eval/target` | trainer | the checkpoint iteration the workers should evaluate in one fight in ten |
| `eval/wNN.csv` | workers | `iteration,outcome,ticks` per evaluation fight (outcome is win, loss or timeout) |
| `eval.csv` | trainer | one line per judged checkpoint: fights, wins, timeouts, win rate, best |
| `best.mbw` | trainer | the best judged checkpoint's weights |
| `finished` | trainer | the reason evaluation says the run is done; the build starts no more rounds |

Everything the trainer hands over is written under a temporary name and renamed into place. On Windows the rename is
refused while a reader has the file open, so `mmai/files.py` retries for up to ten seconds rather than ending the run.
