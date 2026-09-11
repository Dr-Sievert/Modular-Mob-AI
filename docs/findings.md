# Findings

What was learned the hard way, newest first. Read this before "fixing" something that looks odd: most of the odd things
are deliberate.

## Throughput and stability

- **A worker's heap fits what it actually holds, and G1's regions have to be four megabytes.** Two things, measured on one
  worker over 6,000 fights on the terrain library, with the terrain seed pinned so both rounds fought the same ground and
  won the same 5,959 of 6,000:

  | | peak private memory | collections | full collections | pause in all | "humongous" in the GC log |
  | --- | --- | --- | --- | --- | --- |
  | 1.5 GB heap, 1 MB regions (as it was) | 1.92 GB | 197 | 4 | 1.85 s | 56 |
  | 1 GB heap, 1 MB regions | 1.48 GB | 360 | 12 | 2.78 s | 137 |
  | **1 GB heap, 4 MB regions** | **1.42 GB** | 156 | 1 | 0.94 s | 1 |

  A worker on the library holds about half a gigabyte live, so a gigabyte is enough and a run fits half again as many
  workers in the same machine. But at a 1 GB heap G1's regions are 1 MB, and the light engine's section-map copies are
  about a megabyte each: over half a region, so **humongous**, straight into the old generation, and freed only by a full
  collection. Shrinking the heap alone therefore made collection worse, not better. At 4 MB regions those copies are
  ordinary young objects and nearly every full collection goes away.
  - Arena ticks per second of server-thread processor time was the same in all three, within the round-to-round noise: the
    collector's threads are not the server thread, so this buys memory and pauses, not tick cost.
  - **Measure with the terrain seed pinned** (`-PterrainSeed=N`), and never trust one round: on this machine, with other
    runs coming and going, the same configuration measured 14.4k and 18.5k arena ticks per second of server-thread CPU
    twenty minutes apart. A round of 24,000 fights, and the two arms back to back, is the smallest thing worth believing.
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
