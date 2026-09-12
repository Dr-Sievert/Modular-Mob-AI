# Findings

What was learned the hard way, newest first. Read this before "fixing" something that looks odd: most of the odd things
are deliberate.

## Throughput and stability

- **The forward pass is within 15% of what this machine can do, and fusing the multiplies would not change that.**
  Measured outside the game on the real topology (634 to 256, GRU 128, 128, 19), 25 agents a batch as a training worker
  runs, one core to itself:

  | | us per tick | against the loops as they are |
  | --- | --- | --- |
  | the scalar loops in `Forward` | 345 | |
  | the same arithmetic in explicit vectors (Vector API) | 299 | 115% |
  | explicit vectors with fused multiply add | 290 | 119% |

  Fused multiply add halves the floating point operations the matrix loops issue and bought 3.5% over plain vectors, so
  those loops are not waiting on floating point at all: they are waiting on the weights and the running sums moving
  through the caches. **Bit identity is therefore nearly free**, which is worth knowing, because `Math.fma` drifts the
  logits by about 7e-3 relative and nothing here is worth that.
  - Walking each weight matrix once for the whole batch instead of once per pair of agents is worth 2%. The weights were
    never the bottleneck.
  - Masked vector loads and stores are a trap: the same loops written with a mask per iteration instead of whole vectors
    and a scalar tail ran at **40%** of the scalar loops.
- **A quarter of the forward pass is the GRU cell, and four fifths of that is one `Math.tanh`.** At 25 agents the pass
  divides up as: fc1 39%, the gates from the input 20%, the gates from the state 10%, **the cell 24%**, normalising 5%,
  fc2 4%, the head 3%. The cell does two sigmoids and one hyperbolic tangent per unit per agent, 3,200 of each a tick,
  and `Math.tanh` measured 30 ns a call against 3.8 ns for a sigmoid: it is not an intrinsic like `Math.exp`, it is
  `StrictMath`'s software `expm1`. So about a fifth of the whole pass is one library call that no change to the matrix
  loops can reach, and no faster tangent gives the same bits.
- **A benchmark on a busy machine invented a result that was not there.** The first run of the above, unpinned while the
  terrain library was building, showed the pass getting 2.4 times slower between 8 and 25 agents, which read exactly like
  a batch that had outgrown a cache. Pinned to one core at high priority it is flat from 1 to 50 agents. The tell was
  that 25 and 50 agents came out within half a percent of each other, and that every variant, whatever its memory
  behaviour, landed on the same number. Pin the core, keep the bursts short, and go round the variants in turn.
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

## The league's curriculum

- **A bigger fight site costs throughput and disk, not memory.** Going from a radius of 2 (80 blocks across) to 3 (112),
  measured on 320 library sites and one worker fighting 300 league fights off them: 0.84 MB a site on disk becomes 1.38 MB,
  so 4,096 sites go from 3.1 GB to about 5.5 GB; 128 sites take 208 s to build rather than 272 s; the worker still runs in a
  1 GB heap; and 300 fights take 50 s rather than 40 s. Twice the chunks to tick in the same heap, which was the opposite
  of the guess that the heap would have to double.
- **Labelling a site by what is on it needs the ground heightmap, not the motion-blocking one.** The first cut read
  `MOTION_BLOCKING`, which counts leaves, so a jungle canopy was the surface and every gap in it a nine block cliff: every
  site in two runs came out labelled `drop`. `MOTION_BLOCKING_NO_LEAVES`, the same heightmap the sites are laid out on,
  still finds a lava or water surface and stops finding treetops.
- **One steep step is not a cliff.** Over 52 library sites, of 312 neighbouring samples each, a third have no nine block
  step anywhere and the rest run from two to fifty eight. A threshold of one called everything an edge; eight labels about a
  third of the library, which spread the fights over all five kinds of ground: measured over 600 fights, 34% water, 30%
  flat, 18% other hazard, 16% drop, 2% lava.
- **The overworld surface has very little lava.** Only 2% of fights landed on a site with any, so the edge to knock
  something off, and cactus and powder snow, are what the agent will actually get to use. Lava-rich ground would have to be
  put into the library deliberately.

- **The terrain is a weapon, and nothing had ever told the agent so.** Its grid has marked lava, fire, magma, cactus,
  powder snow, berries, cobwebs and drops of more than eight blocks as hazards since the hazard work, above solid, which is
  what keeps it from walking onto them. What it never learned is that the same blocks are somewhere to put an opponent: a
  hundred health of iron golem takes a long time to cut down and no time to knock into a lava lake, and a fight the ground
  finishes already pays as a win. Sites are labelled by what is on them and a quarter of the fights are drawn onto the ones
  with something, `-PleagueHazards`; the number that says whether any of it worked is the share of wins the ground finished,
  per kind of ground, in `league/ground.csv`.
- **A quarter, not all of it.** Plain melee on plain ground is still the fight the agent has to be able to win, and a run
  that only ever fought beside lava would learn to go looking for lava rather than to fight.
- **Labelling a site belongs where the site is handed out, not in the library index.** A site's ground is loaded and
  ticking by then, so the scan costs no disk; it is about 400 block lookups once per site and a site hosts a hundred
  fights; it works on a library that is already built rather than needing gigabytes generated again; and it leaves the
  index format alone.

## The league's opponents

- **Some mobs kill themselves, and the agent was paid for it.** Each of these hands over a win nobody fought for, and a
  rating built on those says nothing:
  - a **bee** dies of its own sting: after stinging once its aiStep rolls for death every five ticks, which over a minute
    it passes about three times in four, and its attack goal never runs again either. In the league a bee never counts as
    having stung (`BeeMixin`), so it keeps fighting for as long as the agent lets it;
  - a **snow golem** melts a heart a tick in any biome warm enough to rain, which is a third of the terrain library. It is
    given fire resistance for good, which is what that damage goes through.
- **The weather is worth switching off.** A fresh world starts clear, but a worker fights for hours of game time and the
  first storm changes the fight for everything the weather touches: rain hurts a blaze and a snow golem, and teleports an
  enderman. League fights now hold it clear along with the time of day.
- **An evoker's vexes were swept away as wildlife.** The sweep that clears what the world generator put down takes
  anything living that no fight spawned, and an evoker's only real attack is entities it summons mid fight. They are taken
  into the fight as they appear, and the site's own cleanup takes them at the end, since the wildlife sweep cannot.

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
- **Air control is a player's:** 0.026 while sprinting.
- **Paid by the health actually removed**, so overkill on a nearly dead vindicator pays no more.
- **Placing was broken** before the mechanics work: blocks always faced north, and wall-mounted blocks and axe use
  threw exceptions.
- **Open sky at spawn.** Fights that started under canopies or mangrove roots lost 9.8% against 0.8%: a fighter could be
  boxed in before it took a step.

## Learning

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
