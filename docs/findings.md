# Findings

What was learned the hard way, newest first. Read this before "fixing" something that looks odd: most of the odd things
are deliberate.

## Throughput and stability

- **A fifth of the forward pass was one call to `Math.tanh`, and `Math.exp` gives the same answer.** `tanh(x)` is
  `2 * sigmoid(2x) - 1`, so the GRU's candidate can be worked out from `Math.exp`, which is an intrinsic, instead of from
  `Math.tanh`, which is `StrictMath`'s software `expm1`. Pinned to one core the call went from 30.2 ns to 14.8 ns, and the
  cell makes 128 of them an agent tick. In a worker, four rounds of 6,000 fights:

  | | `Math.tanh` | from `Math.exp` |
  | --- | --- | --- |
  | the pass, per agent tick | 14.7, 14.3 us | 11.6, 11.1 us |
  | arena ticks a second of server-thread CPU | 22,372, 23,815 | 26,297, 27,774 |

  - It is **not** the same bits, and the honest version of that is: over eight million floats from -40 to 40, exactly one
    came out with a different bit; the worst absolute difference was 1.1e-16 and the worst relative one 7.4e-08, against a
    parity check that allows 1e-5. Two edges do differ — a denormal comes back as zero, and negative zero as positive zero
    — and both are differences of about 1e-30 in a number that is then multiplied by a weight and added to a sum.
  - The arena suite's twenty fights still take **exactly 54 ticks** each, which is the test that would have caught it.
  - Write it as `2 / (1 + e) - 1`, never as the prettier `(1 - e) / (1 + e)`: the second is infinity over infinity, and so
    NaN, for any argument below about -355, where the first correctly gives -1. And work it out in double, so that
    subtracting one does not eat the precision of a small tangent.
- **Windows was running every worker's server thread on an efficiency core, and that was costing half the machine.** A
  worker is bound by its one server thread. Workers run at below normal priority so that the desktop stays usable, and
  Windows reads a below-normal thread as background work and parks it on an efficiency core. Confining each worker's
  process to the performance cores **doubles a worker's throughput**, and is worth more than everything else on this list
  put together. One worker, 3,000 fights on the library driven by a network, the same build, the only difference being
  `-PworkerCores=all`:

  | | wherever Windows puts it | performance cores only |
  | --- | --- | --- |
  | the forward pass, per agent tick | 30.5 us | 12.1 us |
  | arena ticks a second of server-thread CPU | 12,413 | 25,479 |
  | the round | 44 s | 26 s |

  At 40,000 fights on two workers it is the same story: 23,248 arena ticks a second against 48,415, and 161 s against 85 s.
  The pass itself measures 9.4 us an agent tick pinned to a performance core and 24.8 on an efficiency one, which is where
  the factor comes from.
  - **Priority is not the lever; affinity is.** Raising a worker to normal priority and leaving it unconfined changed
    nothing (28.4 us against 29.4). So the workers stay below normal and the desktop keeps its protection, which costs
    nothing to confine: a foreground burst measured a median 10.8 us beside unconfined workers and 11.2 beside confined
    ones, because a normal-priority thread preempts a below-normal one wherever it is.
  - **The mask has to be performance cores only.** Leaving the efficiency cores in as well, for the collector and the chunk
    threads, gives the whole gain back: Windows puts the server thread on an efficiency core whenever one is allowed. One
    performance core plus all twelve efficiency ones measured 30.7 us, no better than no mask at all.
  - **Pinning the server thread alone, and letting the process have every core, measures the same and is not worth taking.**
    The idea is tempting: the process mask keeps a worker's collector, chunk workers, netty and compiler threads off twelve
    efficiency cores that sit almost idle while the eight performance ones are pegged, and only the server thread ever
    needed a fast core. Windows sets affinity per thread, and a Java thread hands out no native handle, so the thread has
    to pin itself from inside: `SetThreadAffinityMask` on `GetCurrentThread` through JNA, which the game already ships.
    That works — the pin is real, and the forward pass drops from an unmasked 31.1 us an agent tick to 20 us, exactly what
    confining the process gives — and it buys nothing. Measured one worker at a time with two training runs live, the arms
    interleaved, `-Psuite=arena -Parenas=3000 -Pworkers=1`, in arena ticks a second of server thread processor time:

    | | process on the performance cores | server thread pinned, process everywhere |
    | --- | --- | --- |
    | the scripted fighter, three pairs | 13,061, 11,194, 10,103 | 11,457, 11,486, 11,285 |
    | a network driving, four pairs | 9,698, 8,612, 8,772, 9,390 | 10,116, 9,510, 8,617, 9,185 |
    | the pass, per agent tick, those rounds | 19.4, 22.0, 20.9, 19.1 us | 18.8, 19.5, 21.6, 20.5 us |
    | no mask at all, for comparison | | 7,707, 8,048 and 31.1 us |

    Which is a dead heat: 2.6% on the rate and 2.5% the other way on the clock, inside the 12% each arm swings by on this
    machine. So the default is off, `-PserverThreadCores=performance` turns it on, and the reason it wins nothing is the
    reason the efficiency cores look idle in the first place: a headless worker is one server thread and a handful of
    threads with almost nothing to do. The cores going spare are not throughput being thrown away, they are work that does
    not exist. What the pin does buy is steadiness — the process-masked arm swung from 10.1k to 13.1k where the pinned one
    stayed within 2% — which is worth nothing to a run and something to a measurement.
    - Untested, and the only case left where it could pay: **many** workers, where every one of them has its collector and
      chunk threads on the same sixteen logical processors as every server thread. That comparison wants eight workers of
      each arm on a quiet machine, about twelve gigabytes, and could not be run while two runs were training.
    - **A performance core of its own per worker is worse, not better.** `-PserverThreadCores=one` gives each server thread
      one logical processor nothing else of that worker uses, which reads like the ideal and measures at 23.3 and 25.5 us a
      pass against 20, and 6,057 and 7,375 steady arena ticks a second against about 8,400. A thread pinned to one
      processor waits behind whatever else Windows puts there, and on this machine that is another run's workers.
  - **Every worker gets the whole mask, never a slice.** A worker wants a performance core for its server thread and bursts
    of the others for collection. Two workers confined to one core between them measured *worse* than no mask at all (14.1k
    arena ticks a second against 22.6k, and the round took 123 s); two sharing four cores ran the pass at 19-21 us; two
    sharing all eight, 13 us. Squeezing one worker down to a single logical processor gave it six full collections and 2.85 s
    of pause.
  - Which processors are the fast ones is **measured**, once per machine, into the same calibration file as the worker
    sizing. The count comes from the chip's own numbers, which cannot be wrong: a performance core carries two logical
    processors and an efficiency core one, so L logical and C cores means L - C performance cores. Which of them is decided
    by timing the same burst on every logical processor, best of three. A first attempt classified by "within 25% of the
    fastest" and put three performance cores in the slow class because other runs happened to be using them; taking a known
    count of the fastest is robust where a threshold is not.
- **The Vector API is only real if the compiler can inline it, and one big method is enough to lose it.** The forward pass's
  loops are now written twice: the plain ones, and the same arithmetic in explicit vectors (`ForwardVectors`). Written as
  one large method with all four of its loop nests inside it, the explicit vectors were **9% slower in the game than the
  plain loops** and allocated 28 GB over a round of 6,000 fights against 11.7 GB: C2 had run out of room to inline the
  vector calls, so every `FloatVector` became a real object on the heap, about 36 kB of garbage an agent tick. Split into
  one small method per loop nest, nothing is allocated and the same loops are **10% faster**.

  | the pass, per agent tick | plain | explicit vectors |
  | --- | --- | --- |
  | in the game, 6,000 fights, three pairs | 34.4, 34.6, 35.4 us | 32.4, 31.9, 30.7 us |
  | allocated over the round | 12.1 GB | 11.8 GB |
  | one big vector method instead | | 37.8, 35.3 us and 28 GB |
  | pinned to one P-core, in a loop | 14.0 us | 10.7 us |
  | pinned to one E-core, in a loop | 30.5 us | 28.4 us |

  So a boxed Vector API loop does not merely fail to help, it costs more than it ever could have saved, and the only tell
  in the numbers is the allocation. Check the garbage, not just the clock.
  - **A pass timed in a loop is not the pass a tick pays for: 10.7 us against 31.7.** In a benchmark loop the 1.3 MB of
    weights stay in the second level cache and the arithmetic is what is left; in a real tick the server thread has ticked
    chunks and entities and written 634 floats an agent in between, and the weights are cold every time. That is also why
    the vectors are worth 30% in the loop and 10% in the game. `Forward` counts its own nanoseconds now and every worker
    prints what the pass cost it, which is the number to trust.
  - **The server thread runs on an efficiency core.** On this i7-14700KF the pass takes 10.7 us pinned to a performance
    core and 28.4 us pinned to an efficiency one, and 31.7 us in a worker: workers run at below-normal priority with every
    core visible, and Windows puts them where it likes. Any absolute figure has to say which core it was measured on.
  - Bit identity is checked, not assumed: `scripts\parity.ps1` runs both sets of loops at every batch size from 1 to 64 and
    compares the raw bits, negative zero included. A jar started without `--add-modules jdk.incubator.vector` loads no
    vector class at all and runs the plain loops, which is what a game that is not this build gets.
- **The suites that train want no light engine, and that is a fifth more fights a worker.** A vindicator fight never asks
  how bright anywhere is, so the `terrain` and `arena` suites drop both light engines, which vanilla's `LevelLightEngine`
  null checks in every method it has. Measured on one worker over 24,000 fights on the terrain library, the seed pinned so
  both arms fought the same sites, run twice in each order, and 99.5% won either way:

  | | with light | without |
  | --- | --- | --- |
  | the round's fights took | 142, 146 s | 112, 128 s |
  | arena ticks a second of wall clock | 13.1k, 12.9k | 17.0k, 15.1k |
  | arena ticks a second of server-thread CPU | 17.1k, 17.2k | 17.8k, 17.7k |
  | collections | 253, 234 | 138, 131 |
  | time in collection pauses | 2.08, 2.22 s | 1.43, 1.20 s |

  The server-thread cost barely moves, which is the whole finding: **the server thread was not doing the light work, it
  was waiting for it.** Propagation runs on a thread of its own and a chunk does not tick until it is lit, and a worker's
  sites move on hundreds of times a round. Dividing the two rates says the server thread was busy 72% of the round with
  light and 88% without.
  - `canSeeSky` is `getBrightness(SKY, pos) >= 15`, not a heightmap question, so switching light off reaches further than
    it looks: it reaches rain, and burning, and anything spawning. The `league` suite (the undead, spiders, endermen), the
    `library` build, a run that keeps its own world, `play` and `mechanics` all keep their light for that reason.
  - A short round says the opposite. At 6,000 fights, whose steady window is 30 s, light-on measured *faster* twice; the
    difference only separates from the noise at 24,000.
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
  logits by about 7e-3 relative and nothing here is worth that. (The vectors landed at 30% rather than 15% once the
  normalising and the ReLU were written in vectors too, and the pass itself came down as the loops were rearranged; the
  entry above has the numbers as they stand.)
  - Walking each weight matrix once for the whole batch instead of once per pair of agents is worth 2%. The weights were
    never the bottleneck.
  - Masked vector loads and stores are a trap: the same loops written with a mask per iteration instead of whole vectors
    and a scalar tail ran at **40%** of the scalar loops.
- **A quarter of the forward pass is the GRU cell, and four fifths of that is one `Math.tanh`.** At 25 agents the pass
  divides up as: fc1 39%, the gates from the input 20%, the gates from the state 10%, **the cell 24%**, normalising 5%,
  fc2 4%, the head 3%. The cell does two sigmoids and one hyperbolic tangent per unit per agent, 3,200 of each a tick,
  and `Math.tanh` measured 30 ns a call against 3.8 ns for a sigmoid: it is not an intrinsic like `Math.exp`, it is
  `StrictMath`'s software `expm1`. So about a fifth of the whole pass is one library call that no change to the matrix
  loops can reach, and no faster tangent gives the same bits. (It is now worked out from `Math.exp` instead, for that
  fifth; see the entry above for what the bits cost.)
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
- **Clearing out a development worktree emptied the trainer environment every run on the machine uses.** A worktree has no
  `trainer\.venv`, and `brainParity` refuses without one, so four worktrees each had a junction from theirs to the real one.
  Git then refused to delete the worktrees — "Filename too long", from the depth of Gradle's build folders — and the usual
  Windows answer to that, `robocopy /MIR` from an empty folder, **follows a junction**: it mirrored the emptiness through
  one and took torch, numpy and `pyvenv.cfg` with it. The 4,000-fight record that was being collected at the time survived,
  because that half is Java; the imitation that followed it died on the import.
  - The fix is that there is nothing to follow. A worktree with no environment of its own now borrows the main checkout's,
    found through git — `git rev-parse --git-common-dir` in `scripts\_common.ps1`, and the `gitdir:` line of the worktree's
    `.git` file in the build's `pythonExecutable` — so no junction is ever wanted. `robocopy /XJ` also excludes them, and
    `rmdir /s` does not follow them, but a hazard that has to be remembered is a hazard.

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

## The league's curriculum

- **The loadout and the opponent have to be drawn together.** Drawn independently, a bow was handed out against a creeper it
  should kite exactly as often as against a ghast it cannot reach, so the gradient reaching the drawing of a bow was an
  average over the matchups where a bow is the answer and the matchups where it is hopeless: measured over a league run's
  fights, the ranged loadouts won about 40% and the melee ones far more. The unit of matchmaking is now the pairing, and on 150
  fights drawn from a table where a sword cannot touch a ghast, the sword's ghast fights fell from the 17 the independent draw
  would have spent on them to 4 while the bow's rose to 30. What made it cheap is that a pairing's chance is never asked to
  stand on its own: the table is 490 pairings and a run's faded record is a couple of thousand fights, so the estimate is the
  pairing's own record over a prior built from the opponent's chance and the loadout's own record, which is ten times denser.
- **Pairing costs nothing in coverage.** The floor is spread over the pairings rather than the opponents, which is the same
  share per pairing as before: an opponent's even floor was already being split between the ten loadouts by the even loadout
  draw.
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

- **The 1500-rated anchor was drawing a bow.** Every rating in the league is measured against the scripted fighter, so its
  strength has to stay put, and it is held to something to swing for exactly that reason. But `Loadouts.melee` asked only
  whether hotbar slot zero was a sword or an axe, and `sword_and_bow` leads with an iron sword and keeps the bow behind it:
  the anchor drew a bow in one league fight in eight. It is worth about twenty points to it — over league768's 4,602 fights
  against it, counting only the ones where the agent carried no bow so the field is the same, it won **92.2% of 408 with the
  sword and bow against 71.6% of 402 with the plain sword**. Anything that shoots now disqualifies a loadout however the
  hotbar is ordered.
  - Its strength was never flat across the seven that are left, though: 96.1% in iron armour, 88.8% and 88.5% with a shield
    or a diamond sword, 76.3% with stone, 71.6% with iron, and **59.5% with an axe**, whose twenty tick cooldown it handles
    worst. A rating measured against "the scripted fighter" is measured against that spread.
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
- **Keeping off a hazard is not the same as getting off one.** Reading hazards at 1.5 stopped the teacher walking onto
  them, and then a vindicator's blow knocked it on anyway: over 4,000 fights on the 4,096-site terrain library the teacher
  won 98.7% and lost 15 fights to something that was not the vindicator, **14 of them freezing** and one a fall. Powder
  snow is the trap it never left: a body in it cannot jump out, sinks, and freezes where it stands over about forty
  seconds, which is most of a fight.
  - A hazard in the agent's own cell now comes before the fight. It heads for the nearest cell it can stand on that is not
    one, by the shortest way out rather than towards the target, which is what the ordinary search already enumerates.
  - Walking is nearly always enough, since powder snow only takes a tenth off a body's speed sideways. Where it gets
    nowhere the teacher breaks the block instead, looking straight down its own column: the first thing a ray from the eyes
    meets down there is whatever it is standing in or on. Powder snow gives way in eight ticks, a cobweb in eight to a
    sword, a berry bush at a touch, and all three are the same trap.
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

- **The agent holds attack down for hundreds of ticks because a press at a block is the one press that costs nothing,
  and because it is doing it in fights it cannot win.** Measured over 796 recorded league768 fights, 284,000 ticks,
  sampled evenly over the run and joined to the league's own records so the sampled training fights and the deterministic
  evaluation ones could be told apart. Attack was down on 13.0% of ticks in runs averaging 4.05 ticks, the longest 1,763,
  which is most of a 2,400-tick fight. Where the presses were pointed, worked out by casting the ray the body's own swing
  casts through the blocks the replay recorded:

  | What the swing's own ray met first | Share of presses |
  | --- | --- |
  | a solid block | 33.9% |
  | a solid block, looking down: digging | 16.9% |
  | thin air | 20.6% |
  | a plant | 13.3% |
  | the opponent | 11.2% |
  | a plant, looking down | 4.1% |

  So **half the presses meet a block**, which costs no cooldown, misses nothing and starts a crack that only a held
  button finishes, and only a fifth meet thin air, which is the one kind of press that costs anything at all when there is
  nobody to hit. The cost of a wasted press does not arrive twenty ticks later; for half of them it never arrives.
  - **The longer the run, the more certainly it is pointed at a block.** By run length, the share of a run's ticks whose
    ray met a solid block: 23% for a run of one tick, 47% for two, 59% for three or four, 65% for five to seven, and
    **75 to 78% from eight ticks up**. The share on the opponent goes the other way: 47%, 22%, 16%, 8%, 4%, 2%, 0.5%,
    0.2%. A long run is not a fighter spamming its sword; it is a body mining.
  - **Nearly half the ticks in the replay folder are fights that timed out**, because a win averages 204 ticks and a timeout
    2,025, while every 200th fight of a worker is recorded whatever its length. Grouped by how the fight ended: a win
    pressed attack on 12.0% of ticks with 24.4% of them on the opponent and runs averaging 2.35; a loss 12.6%, 15.8%,
    2.76; **a timeout 14.0%, 0.7% on the opponent, runs averaging 11.5** with 87% of its press ticks inside a run of
    eight or more. Anyone scrolling replays is mostly watching stalls, so the behaviour looks far more common than it is.
  - **Against something it can never reach it gives up fighting and digs.** In the 26 deterministic fights of the sample
    where the opponent was never once within reach — a ghast, a phantom, a breeze — attack was down on 20.4% of ticks in
    runs averaging **39.5**, 46% of the presses into a solid block while looking down, and 97% of the press ticks inside
    a run of eight or more. Elsewhere in the sample a fight against a phantom dug 28 blocks straight down while it
    circled overhead, and 3.4% of all fights broke ground the agent then stood inside, up to 21 cells of it. The reward
    pays for that: the fight is a timeout either way, and a hole takes the damage away. It also means a site's ground no
    longer keeps its word that "fights leave the ground as they found it" (`TerrainSites`) — a site hosts a hundred
    fights, and nothing puts back what a fight dug out.
  - **It is not the sampler.** An evaluation fight plays the most likely action, and the stretches are still there: in
    ordinary reachable fights the deterministic policy pressed attack on 10.6% of ticks against the learner's 12.9%,
    with 24.4% on the opponent against 13.6%, and still put 43.6% of its press ticks inside a run of eight or more. What
    the sampler adds is the noise at the short end and a fifth again as many presses, badly aimed; what it does not do is
    create a run of forty. Every run-length bucket is far above what a memoryless presser at the same rate would give (a
    run of 3 to 4 ticks: 10.9% of runs against 1.7%), and a press is followed by another 76% of the time against 4%
    after a tick with none.
  - **It is costing very little damage, which is why nothing has ever shown it.** Read straight off the opponent's
    health column, the blows that landed carried a mean **0.957 of the weapon's damage**, 80% of them over 0.9 and only
    7.3% under a half, and the median gap between one landed blow and the next was 15 ticks against a sword's 12.5-tick
    cooldown. The same blows reconstructed from the swing history agree at 0.951, which is what says the block reading
    above is right.
  - **A policy that has learned the fight does not do it.** vs-copy, 223 recorded fights: attack on 6.1% of ticks,
    80.4% of presses on the opponent, 98% of runs one tick long, the longest 14, and 1.2 swings per landed blow. The
    league runs spend 10.3. Spamming is a symptom of not knowing what to do, not a strategy the reward pays for.
  - **What would actually make it expensive is a rule the body leaves out on purpose.** A player's client restarts the
    attack cooldown when it lets go of a block it was breaking (`MultiPlayerGameMode#stopDestroyBlock`);
    `AgentMob#stopDestroyBlock` does not, and says why. Simulated over the same fights, that rule would have fired 2.8
    times per 100 ticks, and the blows that landed would have carried **0.772 of the weapon instead of 0.951** — a fifth
    of the damage of every network already trained, on the tick it needs it. So it is a change to the sword fight, not to
    breaking blocks, exactly as the comment says.
  - `aSwingAtAirCostsTheCooldownAndOneAtABlockDoesNot` and `holdingAttackTakesLessHealthThanWaitingForTheCooldown` pin
    both halves of the arithmetic in the mechanics suite, so the reading above cannot go stale unnoticed.
  - What is worth doing about it, in order: **the aim, not the button.** A press lands on the opponent 14% of the time
    in training and 24% deployed; the button is not the thing that is wrong. And the fights that produce the stretches
    are the ones with nothing to reach, so the league's real problem there is that a melee loadout against a flyer is an
    unwinnable draw taking 2,400 ticks of every worker's time.
  - The measurement is replays only, and two things about it are worth knowing before it is repeated. A replay records
    the site's blocks once, as the fight starts, so a plant the agent has since cleared still reads as standing: the
    "on the opponent" shares are a floor and the plant shares a ceiling (letting plants through raises on target from
    11.2% to 13.8%). And every group figure here is weighted by ticks, which is why the timeouts dominate; the outcome
    table above is the one to read if that is not wanted.
- **A bow behind the sword is not what makes the agent worse; a teacher record with no bow in it is.** league768 wins
  67.6% with a sword and 31 to 32% with a bow, a crossbow or a sword and a bow, which are three of its ten loadouts and
  about a third of every league fight. The first guess was the teacher: it draws whenever the target is out of reach with
  a clear line, a draw is twenty ticks at a fifth of walking pace, and fights start a median 9.1 blocks apart, so it
  looked as though the teacher opened every fight by drawing, ate the first blow at full draw, and the copy inherited it.
  Two measurements say otherwise.
  - **The teacher is better with a bow, not worse.** It fights the league as the 1500-rated anchor, so its own results are
    in every run's records. Over league768's 4,602 fights against it, counting only the ones where the agent carried no bow
    so the field is the same, it won **92.2% of 408 fights with the sword and bow** against **71.6% of 402 with the plain
    iron sword**, 88.5% with a diamond sword and 59.5% with an axe. The bow behind the sword is worth twenty points to it.
  - **The opening draw finishes.** Over 399 of the teacher's own recorded answers with that loadout (a DAgger round, where
    the actions in the shard are the teacher's labels), it starts asking for a draw **inside the first five ticks in 90% of
    fights**, from a median 9.1 blocks, and **keeps asking past twenty ticks in 90% of them**. A zombie covers 3.1 blocks in
    a draw and a vindicator 4.8, so from nine blocks the arrow is away before either arrives.
  - **What is actually wrong is that the network never touches the bow.** Over 46,044 bow fights it began **0.00 uses and
    fired 0.00 shots a fight**, and over 45,793 with a sword and a bow, 0.01 and 0.01 — while the same network raised its
    shield 0.2 to 0.5 times a fight, so `use` is not dead, only the bow is. With a bow alone it holds the arrows and
    punches; with a sword and a bow it holds the **bow** for the whole fight and punches with that, never swapping to the
    sword in slot zero.
  - **Because every demo the run is pulled towards is an iron sword.** All 16,000 fights in `runs\league768\demos` — the
    teacher record and all three DAgger rounds — were recorded on the *terrain* suite, one vindicator, hotbar `[iron_sword]`
    and nothing else. `scripts\compare.ps1` carries a copy's record into the run it seeds, and league768 has never had a
    DAgger round of its own, so the teacher pull it spends 6,000 iterations under has never once shown it a bow, a shield
    or an axe. This is "a network never learns what the teacher never did" again, one level up: the teacher can do it, and
    the record still cannot say so.
  - **The control is league-sharp**, which does have two league DAgger rounds. Same body, same rules: **12.04 draws and
    11.01 arrows a fight** with a bow, won 60.3%, which is *above* its own sword's 56.8%. A bow is not a handicap once the
    record holds one.
  - **What is left, and it is small: a slot to slip to.** In league-sharp a bow alone finishes 91% of its draws; a bow with
    a sword beside it starts 2.42 draws a fight and looses **0.09** arrows, 4%, and sword_and_bow sits 2.4 points under the
    plain sword and 5.9 under the bow. Changing slot is the one thing that still drops a draw, and a network that chooses
    its slot afresh every tick drops its own.
  - The teacher's share of that is now fixed: of the draws it gave up before twenty ticks, **79% were begun between five
    and seven and a half blocks**, the band every walker in the league crosses in less than a draw. It draws only at what
    cannot be here before the draw is full — the slot's own `ENEMY_SPEED`, or the velocity it is already coming at,
    whichever is faster, against `ABANDON_DRAW_RANGE` — and a thing that shoots back or flies is a draw at any range, since
    closing is no answer to either. The opening arrow all but survives it: a zombie has to be past five and a half blocks
    and a vindicator past seven and a third, against a median nine block start. The one it does cost is the first draw of a
    fight, which is judged on a crossbow's twenty five ticks because nothing has said yet which of the two is in hand.
  - **A mob covers about 0.67 blocks a tick for each point of its movement speed attribute**, which is what that rule needs
    and is nowhere in vanilla in those units. Measured over 2,040 recorded fights, as the ninetieth percentile of a five
    tick mean so that a knockback does not count: a zombie (0.23) 0.154, a vindicator and a piglin brute (0.35) 0.239 and
    0.240 — one constant to two percent. Working it out from vanilla's friction instead happens to land on the zombie and
    is 50% out on the vindicator, which is exactly the trap. Some close faster than they walk — an enderman teleports
    (0.296), a warden charges (0.275), a wolf sprints (0.236) — by up to half again, which is what taking the observed
    velocity as well covers.
- **A network never learns what the teacher never did.** Seeded from a copy of a teacher that only ever swung, the first
  league run held use on 0 of 79,724 ticks over its last 400 fights and fired no arrows at all. PPO only improves what it
  samples, and a bow pays nothing until twenty ticks of held use have gone by, so no amount of exploration finds one. The
  loadouts show it: bow 42% won and crossbow 48% against 75-82% for every melee loadout.
  - The answer is the teacher, not the reward. Anything the teacher cannot do is worth building there first.
- **A skill that takes twenty ticks of the same choice cannot be sampled, and the teacher alone will not hold it.** Once
  the teacher had been taught to shoot (85% with a bow) and the run was pulled towards it, the network did start pressing
  use — and got no further. Measured over its own recordings, its longest hold was **6 ticks and not one draw in 2,984
  reached 20**, against the teacher's mean of 10.7 with 22% at 20 or more. It pressed use on 47.6% of the ticks an item
  was already in use: a coin flip, so a full draw is that raised to the twentieth, which is once in ten million. What the
  league saw: 42.6 draws a fight and 5.5 weak arrows with a bow, and with a crossbow, which fires nothing at all short of
  a full wind, **41.5 loads a fight and 0.03 bolts**, 15% won against 50-62% for melee.
  - Behaviour cloning cannot fix it on its own. The pull raises the chance of a press; PPO lowers it, because every draw
    it samples is aborted and an aborted draw is pure cost — a fifth of the movement, the swing swallowed, no arrow. The
    two settle at a coin flip, which is the worst of both.
  - The fix is in the body, not the reward or the trainer: a draw once started runs to full on its own, and letting go is
    the agent's only when the weapon is charged (`AgentMob#drawingToFull`). One press is one full arrow, which is a thing
    a policy can find. Nothing else moves — no layout changes, no log probabilities change, the draw still costs a fifth
    of the movement every tick of it, changing slot still gives it up, and holding at full draw to aim is still allowed.
  - What it did, on the same run and the same roster, about 1,150 training fights a loadout either side of the change:

    | | button held | draw committed |
    | --- | --- | --- |
    | bow: draws started a fight | 42.6 | 15.0 |
    | bow: arrows loosed a fight | 5.5 | 10.3 |
    | bow: draws that finished | 13% | 69% |
    | bow: won, training | 22.5% | 40.1% |
    | crossbow: bolts fired a fight | 0.03 | 6.91 |
    | crossbow: loads that finished | 0.07% | 62.8% |
    | crossbow: won, training | 11.0% | 35.2% |

  - **Evaluation cannot see this bug, and that is why it lasted.** A deployed agent takes its most likely action, so a
    logit over a half means the button is pressed every tick and the draw completes: the coin flip only exists where the
    actions are sampled, which is training. Evaluated on the same weights either side of the change, 300 fights a
    loadout, the win rate barely moves — bow 41.3% to 40.0%, crossbow 37.3% to 42.7% — while timeouts fall about five
    points and the fights it lands a hit in go from 169 and 188 of 300 to 207 and 224. So a run can be evaluated for ten
    thousand iterations, judged on what it evaluates at, and never show the thing that is stopping it learning.
  - **What the gap between evaluation and training is worth as a tell: a little, and only against its peers.** Every
    loadout evaluates above what it trains at, because training explores and exploration costs; over a 200-fight window
    the gap runs from 6 to 18 points and moves by 5 on noise alone. So no single loadout's gap means anything. What did
    mean something was the ranking: with the button held, the bow and the crossbow sat at the wide end (10.5 and 18.5
    points) and after the draw was committed the bow has the narrowest gap of all ten. The tell that was actually decisive
    was neither — it was **counting the bolts**: 0.03 a fight cannot be explained away.
  - The general lesson: **before shaping a reward for a skill, measure whether the policy can physically emit it.** Count
    the action, not the outcome. Both of these were invisible in the win rate and obvious in one histogram of hold
    lengths.
- **Every button is close to a coin flip, so nothing that needs a held button works.** The drawn weapon was the loudest
  case, not the only one. Over sixty training replays, 20,319 ticks (replays are the cheap place to measure this: a
  rollout shard is deleted the moment the trainer reads it, a replay stays on disk and costs a hundred kilobytes):

  | button | holds | mean length | reached 8 | reached 20 | down |
  | --- | --- | --- | --- | --- | --- |
  | jump | 3,646 | 2.34 | 2.3% | 0.2% | 42.0% |
  | sprint | 3,111 | 2.83 | 6.2% | 1.2% | 43.3% |
  | sneak | 3,345 | 2.27 | 1.2% | 0.3% | 37.3% |
  | attack | 3,256 | 3.06 | 4.3% | 0.7% | 49.1% |
  | use | 3,433 | 2.88 | 2.6% | 0.6% | 48.6% |
  | use off hand | 3,294 | 2.51 | 2.0% | 0.4% | 40.8% |

  - **Breaking a block was the second casualty.** Powder snow takes eight ticks of held attack, a cobweb eight, dirt
    fifteen, and a player's client throws the crack away the instant the button comes up. At 4.3% of holds reaching eight,
    the agent could break nothing — so it could not dig itself out of powder snow, which is what 1.3% of its fights were
    ending in, nor out of a cobweb, nor clear the plant in the way that the swing rules had been taught to clear. The
    teacher had a powder snow escape written and working; the network could not copy it, because the escape is a held
    button. A crack now waits where it got to while the aim stays on the block, the press still being the only thing that
    deepens it.
  - What is left on the list, and not yet worth changing: a shield is a held button too, but a shield blocks on the tick
    it is up, so a short raise is worth something where a short draw is worth nothing — and the loadout rows agree, the
    shield adding a point or two rather than nothing. Sprint holds the same way, and the sprint blow only needs the tick
    of the hit. **Sneak the teacher never presses at all**, in either record, which means sneaking is a control the network
    has never seen used; in 1.21 it stops a body walking off an edge, which is worth trying against the fall deaths.
- **The entropy bonus was the plateau.** league2 had been flat for 11,500 iterations: measured on a fixed benchmark, 600
  fights of the league suite on the same ground every time, it went 58.0% at iteration 1,200 to 61.2% at 12,691. Forked at
  iteration 6,075 into the regime a seeded run already uses — entropy coefficient 0.001 rather than 0.01, learning rate
  5e-5, clip 0.1, target KL 0.01 — and given the same two workers and the same wall time as the run it left behind:

  The same three networks, benched three times over the evening. Read the rows, never the columns: **the benchmark's
  absolute level drifts about four points between one sitting and the next, and only what is measured inside one invocation
  can be subtracted.**

  | | first sitting | second | third |
  | --- | --- | --- | --- |
  | where both arms forked (iteration 6,075) | 61.2% | 60.2% | 56.7% |
  | the arm it left behind, entropy 0.01 | 61.8% | | 58.8% |
  | the sharp arm, about 1,000 iterations on | 66.7% | 63.2% | 60.5% |
  | the sharp arm's lead over the fork | +5.5 | +3.0 | +3.8 |

  So three to five points in a thousand iterations, against three points in eleven thousand five hundred, and ahead of the
  control by 1.7 to 4.9. The direction held in every sitting; the size of it is worth no more than "three to five". The
  regime that produced vs-copy at 99.8%, and was only ever used for seeding a run from a good policy, is what a league run
  wants as well.
  - What is not separated: the seeded regime also quadruples the rollout steps, so an iteration of the sharp arm holds four
    times the experience. The arms had equal workers and equal wall time, which is the comparison that decides what the
    machine should do, but entropy alone is not proven to be the whole of it.
  - **It did not replicate on a second lineage.** league-scratch was converted to the same regime from a measured baseline,
    and the two were first read in different sittings — 53.5% then 57.5%, which looked like four points of gain and was
    nothing but the drift. Benched together afterwards: 57.5% before, 54.8% after. So on that lineage the same change is
    2.7 points behind its own baseline over 630 iterations. One result on one lineage, then.
- **Self play was a handicap, not a mirror, and a quarter of it was a stalemate.** A fifth of a league run's fights are
  against frozen checkpoints of itself, and those played their most likely action while the learner sampled, so the
  exploration the learner pays for was charged to one side of the mirror only. league2's own records, before the fix:
  33.9% won against its own checkpoints in rated fights, where both sides are deployed, against **16.5% in training
  fights**, where only it explores; against mobs the same gap is nine points rather than seventeen. Letting the frozen copy
  sample too took the training figure to 28.3%, near the 33.9% that says what the matchup really is.
  - **The timeouts are the louder half.** 27.4% of those mirror matches ran the clock out, and a timeout is scored as a
    loss, so a fifth of a run's experience was mostly "you lost" with nothing in it to learn from. In the arm that trains at
    an entropy coefficient of 0.001 rather than 0.01, the same fights time out **1.8%** of the time. Two policies full of
    per-tick noise flail at each other and neither finishes it; the same two policies played crisply settle it. That is the
    clearest measurement yet that the entropy bonus was not buying exploration so much as paying for stalemates.
- **An evaluation repeats within the minute and drifts across the evening, so bench every candidate in one invocation.** Run
  twice back to back, one network over 600 fights measured 64.2% and 64.7%, and four times over 300 fights, 44.7%, 44.7%,
  45.7% and 46.3%. That is half a point, and it is what "the evaluation repeats itself" means. But the *same file* measured
  66.7% earlier in the evening and 60.5% later, and every network in that sitting moved with it, so what repeats is a
  sitting and not a number.
  - The suspect is the worker sizing, which is measured from the machine's own throughput at startup ("measured 20.0 arenas
    a second per worker, sizing for 600 arenas") and therefore comes out differently on a machine with four training
    workers on it than on one with two. Different sizing, different fights.
  - What follows for anything being compared: **put every candidate in one `bench.ps1` call**, which is why that script
    takes a list, and never subtract a number from one sitting from a number in another. It also means a published model's
    recorded win rate is a statement about the sitting it was measured in, to a few points.
  - Keep `-Workers` the same too. Each worker takes its own slice of the arenas, so 600 fights over one worker are not the
    600 over three.
  - `-Ground` is for asking the same question of a different sample of the library, not for steadying the answer — it went
    in believing the opposite and the measurement said otherwise.
- **An experiment on one worker, judged on the league rating, cannot be judged.** league-pull05 forked league2 at
  iteration 6,000 to try a teacher pull of 0.05 against 0.2, and over 500 iterations on its single worker it produced three
  evaluations: 1610 against league2's 1600 to 1604 at the same iterations. But league2's own rating wanders between 1567
  and 1632 from one checkpoint to the next, so ±30 of noise swamps it and the arm was retired without a verdict. **Give an
  experiment enough workers to outrun the noise of the thing it is measured with, or do not start it.** The rating moves
  that much because the opponents move: matchmaking steers towards an even fight, so a checkpoint that got stronger is
  rated on harder opponents.
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
