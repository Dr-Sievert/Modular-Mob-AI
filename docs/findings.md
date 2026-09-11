# Findings

What was learned the hard way, newest first. Read this before "fixing" something that looks odd: most of the odd things
are deliberate.

## Throughput and stability

- **Saving off also switched off chunk unloading.** `ServerLevelMixin` turns saving off while tests run, which removed an
  eighth of the server thread. But vanilla's `ChunkMap.tick` skips its whole unload pass for a level that doesn't save,
  so no chunk was ever let go.
  - As terrain sites moved on, a 1 GB worker filled its heap in minutes and spent most of its time in full collections
    (983 of them, 229 s of one worker's 10 minutes), then died of an `OutOfMemoryError`.
  - Because every worker waits for the slowest at the end of an iteration, one sick worker held up the whole run.
  - `ChunkMapMixin` now runs the unload pass without the save walk, and drops chunks without writing them. Live heap
    settles at about 950 MB, hence the 1.5 GB heap.
- **Moving sites on generates a lot of terrain.** Every move generates a site plus the half-generated ring around it:
  chunk holders reach 13 chunks out from each forced chunk. At 25 fights per site that kept a worker's spare cores busy,
  so sites now host 100.
- **Windows refuses a rename while anyone reads the file.** `scripts\watch.ps1` read `trainer.status` at the moment the
  trainer renamed a new one over it, and the trainer died of a `PermissionError`. All the trainer's renames now retry
  (`mmai/files.py`).
- **Git corrupted the first published models.** The repository's `* text eol=lf` rule rewrote CR LF byte pairs inside
  `.mbw` and `.pt` files. They're binary in `.gitattributes` now.
- **Two builds for one run killed each other.** Starting a run cleans up leftover trainers from crashes, so a second start
  killed the live one. Starting a run that's already training is now refused.
- **The machine blue-screened under load (2026-09-11).** The i7-14700KF was on microcode 0x11F, below Intel's fix for
  13th and 14th gen instability (0x12B). A BIOS update (A.K0, microcode 0x137) fixed it; crashes since are code bugs.

## Perception

- **The layout had one spare slot left in it, and a drawn weapon needed it.** Nothing in the observation said how far a
  use had charged, so a network holding a bow could not tell a full draw from a tick of one. The echo's twentieth field
  was kept spare for exactly this; filling it moved nothing, so `schema.json` is byte for byte what it was (id
  `3e475bda`) and every published network still loads.
  - The number is the item's own, not a count of ticks: a bow's is the power its arrow would leave at, which is not
    linear in the draw, and a crossbow's is the fraction of its wind. Both reach one at the moment letting go is worth
    it, so one rule works for either without knowing which is held.
- **An arrow must never become the nearest enemy.** Putting shots into the enemy slots is the only place they could go,
  and a slot is where "what to fight" is read from: a skeleton twelve blocks off with an arrow a block from the agent
  would have the agent turning to swing at the arrow. So bodies hold their slots against every projectile, a body
  arriving evicts a projectile before anything alive, and the teacher's nearest enemy skips them outright.
- **Only a shot that is coming is worth a slot.** Most arrows in a fight are lying in the grass or flying past: one
  earns a slot while it is moving, while the agent is ahead of it, and while its line would pass within a block and a
  half. Without that, slots filled with litter.

## Mechanics (a player's rules, and bugs that broke them)

- **Forward movement did nothing before commit c38efe9.** Vanilla's `Mob.setSpeed` also writes the forward input, and it
  ran after the agent's. The agent crept at a tenth of walking pace (strafing worked).
  - Jumping only worked on the ground, so it drowned in deep water.
  - The terrain grid saw grass as walls and water as ground.
  - Everything learned before that fix was learned in a broken body.
- **Swings at plants.** A player's swing breaks grass and flowers and costs no cooldown; the agent's swings used to stop
  at a fern. A swing into a block now never resets the attack cooldown, and instant-break blocks break.
- **Hazards read as hazards, not as solid (1.5 in the grid).** Read as solid, the top of a lava lake or of powder snow
  looked like stone to stand on. The teacher walked onto them and the network copied it. Over 20,000 fights, the vs-copy
  network died 18 times of something other than the vindicator: 13 falls (nearly all at one ravine), 2 lava, 2 powder
  snow, 1 berry bush. Hazards now read above solid, so networks trained before still treat them as walls. The bottom
  layer also marks drops deeper than 8 blocks. The fixed teacher dies of such causes 6 times in 20,000, and
  `gametest/util/DeathCauses` logs every one with its position and biome.
- **One swing, three shapes, pick one.** Sprinting into a blow adds a point of knockback; falling into it adds half again
  the damage and only counts if the fighter is not sprinting; standing still with a sword sweeps, and either of the other
  two cancels that. So a fighter chooses per blow, and there is no swing that both crits and knocks back.
  - **A sprint needs no reset here.** A player has to let the key go and press it again, because the blow cancels the
    sprint. The agent's body reads the sprint control fresh every tick, so asking for it on the tick of the swing is the
    whole of it.
  - **The knockback is thrown along the agent's own look**, which is what makes a hazard behind the target reachable: the
    push is `-(sin yaw, -cos yaw)` normalised, which is the agent's forward. A mob the ground kills still counts as the
    agent's win.
- **Air control is a player's:** 0.026 while sprinting.
- **Paid by the health actually removed**, so overkill on a nearly dead vindicator pays no more.
- **Placing was broken** before the mechanics work: blocks always faced north, and wall-mounted blocks and axe use
  threw exceptions.
- **Open sky at spawn.** Fights that started under canopies or mangrove roots lost 9.8% against 0.8%: a fighter could be
  boxed in before it took a step.

## Learning

- **A network never learns what the teacher never did.** Seeded from a copy of a teacher that only ever swung, the first
  league run held use on 0 of 79,724 ticks over its last 400 fights and fired no arrows at all. PPO only improves what it
  samples, and a bow pays nothing until twenty ticks of held use have gone by, so no amount of exploration finds one. The
  loadouts show it: bow 42% won and crossbow 48% against 75-82% for every melee loadout.
  - The answer is the teacher, not the reward. Anything the teacher cannot do is worth building there first.
- **A teacher that keeps state has to check it against the body.** The teacher labels a student's fight in a DAgger
  round, and there its own presses never happen. A state machine that assumed they had would decide on the first tick of
  the first fight that the quiver was empty and the off hand held no shield, and would never show the student either
  again. The use cooldown settles it: any press with it clear sets it to full, so a cooldown that did not move says
  nobody pressed anything, and nothing is concluded from a press nobody made.

- **PPO made a good copy worse, twice.** A policy near its best has little to gain from a critic that hasn't learned the
  fight yet, and much to lose. What fixed it (`-FromCopy`):
  - 4x the experience per update, and smaller, bounded steps;
  - a longer critic warm-up;
  - little exploration;
  - a pull back towards the teacher's answers.

  With those, vindicator4 (97.9%) became vs-copy at 99.8%.
- **Judge by evaluation, not by the training win rate.** Twice the training win rate rose while the deployed fighter got
  worse: training explores, and PPO learns to cope with its own jitter. Hence evaluation inside the run, on the most
  likely action.
- **Aim noise has to be small.** At a spread of 0.37 of full deflection the crosshair jerked about 22 degrees a tick, and
  the sampled copy won about 1% against 63% on its most likely action. Aim now has its own, much narrower spread.
- **A copy needs to have seen mistakes (DART),** and above all **the copy driving while the teacher labels (DAgger).**
  Three DAgger rounds took a copy from 48.8% to 89.8%.
- **The teacher's record needs little noise.** At 0.2 the scripted fighter won only 11% of its recorded fights, and a
  record of a fighter losing is mostly positions nobody should be in. 0.1 works.
- **From nothing, it learns slowly but it learns.** With real movement and the current reward, vs-scratch went from 0%
  to 78.6% evaluated in about 650 iterations. Before the movement fix it learned nothing in 10,000 battles.
- **Never pay for surviving.** Any reward for lasting longer made running away the best thing an agent that couldn't yet
  win could do.
- **The scripted teacher's own ceiling.** 98.6% on terrain. What it still loses is mostly forest and mangrove ground:
  getting caught under cover, or a target stuck in a pit (it now drops down to one up to 9 blocks).
