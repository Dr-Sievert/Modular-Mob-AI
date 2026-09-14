# Findings

What was learned the hard way, newest first. Read this before "fixing" something that looks odd: most of the odd things
are deliberate.

## Throughput and stability

- **A played world was pitch dark, and what was doing it was a game test's throughput setting.**
  `scripts\play.ps1 -World Test` opened a world with no light anywhere in it, and `/time set day` changed nothing, because
  there was nothing left to work the light out: `gametest/mixin/LevelLightEngineMixin` had nulled both engines of every
  level in the process.

  **The game-test source set is not confined to game tests.** Both loaders hand it to their client and server runs as well,
  so that `/test runall` works in a development world (`mod/fabric/build.gradle`, `mod/neoforge/build.gradle`), and
  `fabric.mod.json` lists `modular_mob_ai.gametest.mixins.json` first in its mixins — so every mixin in that config is
  loaded and applied in a real game. The light mixin asks `GameTestTuning.lighting()`, which is false for the `terrain` and
  `arena` suites when no terrain file is kept, and `suite()` **defaults to `arena` when the suite property is absent**,
  which it is in a client: only the game-test run passes it, and the client and server runs pass the brain, the weights and
  the models and nothing else. So a client asked a question that only means anything inside a suite, and was handed the
  answer a training worker wants.

  **The rule: a toggle in `GameTestTuning` is inert outside a game-test server**, and the signal is the suite property being
  present. Both loaders' game-test runs always pass it — blank when no `-Psuite` was named, which is what makes the arena
  suite the default — and so does every worker, which copies the run task's properties and then names the suite itself.
  Nothing else passes it, and the two other candidates are each wrong somewhere: `-Dfabric-api.gametest` is Fabric's own, so
  a NeoForge suite would quietly run with a real game's settings; and a constructed `GameTestServer` is the right question
  wherever one can be reached — five of the mixins ask it that way, and `GameTestServerMixin` is confined by targeting it —
  but a level's light engine is built before anything holds the level, and a region file's writer never holds a server at
  all.

  The audit of the whole config, since being wrong here is quiet. `LevelLightEngineMixin` was the leak that was noticed.
  `BeeMixin` (a bee never counts as having stung), `BreezeMixin` (a breeze fights an agent) and `VillagerMixin` (a
  villager's death log dropped) were leaking too, harmlessly, and now ask the same question: a published jar carries none of
  this source set, so a development game should behave as that jar does. `GameTestBatchFactoryMixin` and
  `RegionFileStorageMixin` were already inert by the properties they read, and ask anyway. `ChunkMapMixin`, `LevelMixin`,
  `MinecraftServerMixin`, `ServerChunkCacheMixin` and `ServerLevelMixin` already checked
  `getServer() instanceof GameTestServer`; `GameTestServerMixin` — midnight, both cycles stopped, the plots, the throttle —
  targets `GameTestServer`, so it cannot fire anywhere else; `SlimeInvoker` only adds accessors and changes nothing.

  **No suite's fights changed**: the arena's 20 of 20 at exactly 54.0 ticks and the mechanics' 54 came out the same, and the
  light each suite runs with is unchanged to the suite. `PlayGameTest.aRealGameKeepsItsLightEngine` pins both halves — that
  a suite that keeps its light really has one, reading full sky light above its plot, and that with the signal taken away
  every toggle answers as a real game needs.

- **One breeze with a NaN vertical velocity killed a 23,757 iteration run, and the only state left on disk was the
  poisoned one.** blast7 stopped at iteration 23757 with `refusing to export weights that are not finite`. Iteration 23756
  was healthy in every figure (kl 0.0075, drift 3.9e-04), and 23757 went NaN in all of them at once. The whole chain, from
  the eight preserved shards:

  1. In worker 3's fight, agent `(6, 3, 150543)`, a **breeze** arrived at tick 14 of its segment with a NaN in
     `getDeltaMovement().y`. The observation's `ENEMY_VELOCITY_UP` for slot 1 is that number divided by a constant, so the
     row carried a NaN at offset 90 — and only there: `x` and `z` were finite, which is what says the NaN was the entity's
     own and not a rotation or a knockback. The slot is a breeze beyond doubt: 30 health, 3 attack damage, 0.63 movement
     speed, 0.6 by 1.77, shoots, which is `Breeze.createAttributes` to the digit.
     - **Whose arithmetic made the NaN is not pinned, and it is not this mod's.** Nothing in `mod/` writes another entity's
       vertical velocity: the only push the agent gives anything is `AgentMob#resolveAttack`'s knockback, whose `y` is a
       literal `0.1`, and a NaN yaw there would show in `x` and `z` first. The nearest candidate is vanilla's own
       `LongJumpUtil.calculateJumpVectorForAngle`, which a breeze's whole movement goes through: it guards its square root
       with `if (d < 0.0) return empty` and its magnitude with `if (v > max) return empty`, **and a NaN passes both**, since
       every comparison against a NaN is false. A 0/0 in that division therefore returns a NaN jump vector rather than
       nothing. That path would make all three components NaN, though, so it is not the one that fired here, and hunting
       further is not worth it: the mod cannot patch vanilla's physics, and the fix has to be that a number from the world
       is never trusted.
  2. **Vanilla made it permanent.** `Entity#move` only moves an entity when `collide(movement).lengthSqr() > 1.0E-7`, and
     that is false for a NaN, so the breeze never moved again and never worked the NaN off: its position in the shard is
     identical to four decimals for the remaining 54 ticks, and its vertical velocity is NaN on every one of them.
  3. **One NaN input is a NaN network.** Every layer mixes the whole row, so all 19 logits went NaN, all four continuous
     controls sampled NaN, and the log probability the game wrote down was NaN. The agent stopped aiming and stopped
     pressing anything for the rest of the fight, and its own echo block went NaN with it (offsets 33, 34, 38, 39).
  4. **PPO averaged 54 such rows into a loss.** One Adam step then wrote a NaN into all 91 tensors of the run —
     `actor.fc1.weight`, `actor.gru.weight_ih_l0`, `actor.log_std` — and `_refresh_normalizer` took the NaN columns into
     `actor.norm_mean` and `actor.norm_std`, which is the one piece of state no further training would mend.
  5. **Nothing caught it on the way down.** The drift check is `drift > 1e-3` and `NaN > 1e-3` is False; worse, `max()` over
     the per-segment maxima *skips* a NaN, because `nan > current` is also False, so the log reported a perfectly healthy
     drift of 2.8e-04 for the very update that was destroying the network. The export's finite check was the first and only
     one to fire — and it fires *after* `trainer.save`, so `state.pt` had already been overwritten with the poison and the
     run had nothing on disk worth resuming from.

  Reproduced offline: `state-23750.pt` plus those eight shards, with the guards below turned off, gives `pi nan` and 33
  non-finite tensors named in the same order the dead run's state had them. With the guards on, the same update finishes at
  `pi +0.0253  v 0.2203`.

  Three things changed, and all three are cheap:
  - **The game scrubs a row before the network sees it** (`AgentBatch.finite`). Most of an observation is the agent's own
    state, but a good part of it is copied off other entities, and those numbers are vanilla's to get wrong. A non-finite
    value is read as zero — a still opponent, which is nearer the truth about a body that will never move again than a NaN
    is — and the first eight say which field of which body they were. Done where every body's row is finished, so a body
    added later is covered by having a brain at all, and it costs one `Float.isFinite` pass over a row on a tick that
    already casts eight rays.
  - **The trainer drops rows that are not finite before the update** (`Trainer._finite`). A segment is cut at its first bad
    row — in the observation, the privileged columns, the action, the log probability or the reward — and what is before it
    is kept as a stretch that was merely cut off rather than one that ended. **One step fewer than the good rows**: a
    segment of n steps needs n + 1 good observations, the last being what the cut fight is priced against, and keeping the
    bad row as that bootstrap put a NaN back into every advantage of the segment with nothing in the batch to show where
    from. The log line names the field out of the body's own schema — "enemies slot 1, offset 6 of 31 (row offset 90)" —
    which is the sentence that turned four hours of this into ten minutes.
  - **An update that goes non-finite anyway is abandoned whole** (`Config.skip_limit`). The loss is checked before anything
    is stepped and the gradient norm between the backward pass and the step, so no bad minibatch is ever applied; a snapshot
    taken at the top of the update puts the earlier, good minibatches of the same update back, Adam's moments included. The
    iteration reports itself as `NOT LEARNED FROM`, the same weights go out under the next number, and the run carries on.
    Three in a row stops it — **without writing a state**, so the last good one stays where it is. And `Trainer.save` now
    refuses a non-finite state outright: the file that is there is always the one to carry on from.
- **`slot was masked by [0.125 0. 0. ...]` is not a broken mask.** It looks like one and it is not, and the drift line used
  to print nothing but those numbers. A mask is an observation offset whose values are open above zero (`schema.Head`), and
  on the humanoid the slot head's mask *is* the hotbar block: its values are item categories, so a hand carrying one thing
  in slot nought reads exactly that, and both sides masked on the same numbers. The line now says which choices it opened
  and which block it came from, so nobody goes looking again.
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
- **A roof does not keep the sun off a game test's first tick, so every game test runs at midnight.** The framework clears
  each plot to air and places the structure again, and light propagates on a thread of its own: the sky light of a box
  placed this tick is not worked out until the next one. Since `canSeeSky` is "is the sky light here fifteen", it answers
  **yes inside a closed bedrock roof** for exactly one tick — measured at the zombie's eye in the arena box, sky 15 at tick
  zero and 0 from tick one on — and that is the tick a mob the test body spawned takes its first. A zombie there rolls
  vanilla's `isSunBurnTick`, one chance in twenty five, catches fire for eight seconds and is a health down a second later.
  Two of the suite's tests read a zombie's health and so break on it — `friendlyFireOffSparesTheSide`, which then reports
  that a blow got through friendly fire, and `mobsOnOneTeamLeaveEachOtherAlone`, which reports allies hurting each other —
  which is about one run in a dozen, the **one run in seven** the suite was seen to fail at. Both were telling the truth
  about the health and wrong about what took it, which is what widening those two messages to carry `getLastDamageSource()`
  was for.
  - The light engine is not the thing to take away — the suites that keep one keep it for the reasons above — and no suite
    tests daylight, so **the sun goes instead**: `GameTestServerMixin` holds every game-test world at midnight in clear
    weather with both cycles stopped. The `league` suite asked for exactly this first, for its undead, spiders and
    endermen, and now gets it from that one place.
  - Don't assert `canSeeSky` is false to prove a test's roof: it is false from tick one and true at tick zero, so the
    assertion flakes the same one run in seven. Assert there is no sun (`!level.isDay()`), which is what actually holds.
  - **Still open, and not this:** in 23 runs of the play suite before the sun went, the one failure seen was
    `mobsOnOneTeamLeaveEachOtherAlone` tripping its *targeting* assertion, "Allies went after each other", not its health
    one. Nothing a burning zombie does sets a target, and every vanilla target goal asks `TargetingConditions`, which
    refuses an ally; so that is a second, rarer flake with its own cause. If it turns up again, the message wants widening
    to say which of the two went after which, the way the health messages were.
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
  - **And it took the terrain library with it**, which is the same mistake's second victim: 5,120 generated fight sites, half
    an hour of building, the ground every run fights on. A worktree resolves `runs\` against itself, so a worktree that wants
    to fight on natural ground has no library and the cheap answer is to link the real one — and `Remove-Item -Recurse` in
    Windows PowerShell 5.1 follows a directory junction just as `robocopy /MIR` does. Everything else under `runs\` survived,
    which is exactly the shape a link to the library alone leaves behind: the 3.88 GB record and both copies were untouched.
    That part is inference rather than proof — the worktrees were already gone, so the junction could not be examined — but
    nothing else on the machine deletes that folder, and the build had read it successfully twenty minutes earlier.
  - The fix is that there is nothing to follow. A worktree with no environment or library of its own now borrows the main
    checkout's, found through git — `git rev-parse --git-common-dir` in `scripts\_common.ps1`, and the `gitdir:` line of the
    worktree's `.git` file in the build's `mainCheckout` — so no junction is ever wanted. The library is safe to share
    because it is only ever read: every region file is hard linked into a worker's world, never written. `robocopy /XJ` also
    excludes junctions and `rmdir /s` does not follow them, but a hazard that has to be remembered is a hazard.

## Perception

- **A bystander bent the aim because the first layer read every slot through its own weights, and nine of the ten slots
  had never been trained.** Every curriculum answer to the crowd — the sight rule, the slot order, the weighted draw, the
  packs — moved the crowded win rate a few points and stalled, and the reason is in the weight file. The first layer gave
  each of the ten enemy slots its own 31 columns. In every one-on-one fight the opponent sits in slot 0 and slots 1 to 9 are
  zeros, so over a run the normaliser's spread for the late slots sat on its 0.1 floor with a mean near nought, and their
  columns took no gradient worth the name: from `blast6` to `blast7`, 4,000 iterations, the columns for slot 0 moved by 0.83
  and the ones for slot 9 by 0.32. A body arriving in a late slot is therefore turned into inputs of magnitude 5 to 10, up to
  the clip, and fed through weights that are still their initialisation. Measured on `blast7`'s best (iteration 32625): the
  shift one idle zombie makes to the first layer's pre-activations is **1.0 when it stands in slot 0**, which is the real
  signal, 1.9 in slot 3, 2.8 in slot 5 and **8.3 in slot 8** — a bystander in a late slot shakes the first layer eight times
  harder than the opponent does. And at the action, over 60 real one-on-one segments of the teacher's league record with
  idle bodies written into the empty slots:

  | idle bodies in slots 1.. | aim moved, yaw / pitch a tick | attack logit moved | hotbar choice flipped |
  | --- | --- | --- | --- |
  | 1 | 22.0° / 13.3° | 2.4 | 31.7% of ticks |
  | 3 | 24.3° / 15.7° | 3.0 | 41.7% |
  | 9 | 11.7° / 15.8° | 3.0 | 23.0% |

  That is the whole of "one bystander costs 16 points and every one after it costs more", of the pitch pinned straight up
  with seven in view, and of the zero blows in a horde of twenty: the representation, not the curriculum. The
  enemies-in-range count was the same fault by another door — nine bystanders put it at 1.0 against a training mean of 0.11
  and a spread of 0.11, seven standard deviations out — and it is now the count of bodies **in the fight**, engaged by the
  same rule that orders the slots, so on every plain, squad and self-play fight it reads exactly what it did.
  - **The pack fights said the same thing from the other side.** Over 162 recorded `+N_pack` fights of `blast7`, the agent
    landed 3 to 8% of its swings with the aim 20 to 38 degrees off the nearest attacker, and its blows per fight — 8, 6, 9, 8,
    13 for packs of two to six — did not grow with the pack; alone, a deployed network lands about a quarter of its swings.
    It was not fighting the pack badly, it was not seeing it.
  - **The fix is attention over the slots, and it needs no retraining to be invariant.** A head scores every occupied slot with
    a linear score over the normalised fields, a softmax over those and a virtual empty token picks one, and the head hands the
    first layer that one body's fields; the winning slot is struck off so the next head ranks the rest. What the network
    computes therefore cannot depend on how many idle bodies stand about it. A plain network converts in place (`train.py
    attend`): head 0 takes the old slot-0 columns unchanged, heads 1 and 2 the old slot-1 and slot-2 columns rescaled to
    statistics now tied across the slots, and what the old first layer always received from its nine empty slots is folded
    into the bias. Converted `blast7` (iteration 33457) matches `blast7` to **7.6e-5** in the logits on one-on-one rows and
    stays within **5.7e-4** of "alone" with nine idle bodies in view, where `blast7` itself moves by 37. The
    max-pool encoder that had been tried (`l770p`) imitated the teacher thirty times worse than a plain first layer — a
    single ReLU layer pooled by max cannot hand on one body's fields whole — and is replaced rather than kept. The numbers
    are in `docs/architecture.md`; the tests that hold the conversion are `trainer/tests/test_attention.py`.
  - **Benched, one sitting, one worker, 1,000 league fights each, the crowd and the packs drawn as a run draws them**, on
    2026-09-14 an hour after the conversion, blast7's final weights against the same weights converted and the scripted
    fighter, read from each fighter's own table by kind of fight:

    | kind of fight | `blast7` 033457 | the same, converted | the scripted fighter |
    | --- | --- | --- | --- |
    | one on one, nothing about | 82.9% (538) | **86.1%** (555) | 83.9% (539) |
    | 1 to 3 standing about | 69.2% (156) | 76.2% (147) | 83.3% (168) |
    | 4 to 9 standing about | **40.7%** (86) | **79.0%** (81) | 61.1% (72) |
    | a pack of 2 to 4 all attacking | 41.5% (41) | 49.0% (49) | 78.9% (38) |
    | a squad | 75.6% (164) | 73.2% (153) | 83.0% (159) |
    | the whole league | 73.8% | 79.6% | 80.9% |

    The same weights, with the slots read through attention instead of by position, go from 41% to 79% with four to nine
    idle monsters in view — the row where the old network was losing half its fights it now wins as it wins alone, above
    the scripted fighter's 61% on the same row — and nothing about the one-on-one fight moved beyond a sitting's noise. No
    training happened between the two rows; it is the representation alone. The run carried on from the converted state as
    `blast8`, and its own evaluation over its first 400 iterations reads the same story: 86% plain, 80 to 84% with any
    number standing about, against `blast7`'s 76% at one and 38% at nine over its last 4,000.
  - **The packs are the teacher's to teach.** On that bench the scripted fighter wins 79% of the packs of two to four where
    the converted network wins 49% — the fighter simply fights the nearest and swings when its blow is ready, and the
    network has never learned even that against several. So the next step against packs is the pipeline that taught the
    bow: the teacher first, then DAgger on pack-heavy draws, then PPO — not more iterations of the same.
  - **A second engaged body is not reproduced exactly, and cannot be.** With two engaged bodies the converted network differs
    from `blast7` by up to 16 in the logits: the softmax leaves a little of the other body in each head, the heads can read the
    two in the other order than the old leases froze them in, and the old network was clipping — the same body reads |z| up to
    3.5 through slot 0's statistics and up to 49.6 through slot 1's. Squads and packs are what training is for; the invariance
    to idle crowds is what the conversion buys outright.
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
- **Nothing goes into the observation that a real game cannot supply the same way.** The mod runs server-side in an
  ordinary game, so what that server knows about the agent's own body, what it holds and wears, and the entities it
  perceives is fair, and a field of that kind reads the same number in an arena and out in a world. What only the arena
  knows is not: the fight's time limit, who the other side is, how it ends. Those are the critic's, which is never exported.
  A field that read one thing in training and nothing at all in a real game would make every trained network quietly worse
  the moment it left the arena, and nothing would show it. The clock is the edge of the rule and passes: elapsed is a count
  the body keeps, and an agent with no episode reads nought for life.
- **Three of the critic's own facts were fair for the actor all along, and one was not.** Armour was nowhere in the
  observation at all, on either side — a zombie in iron takes under half the damage a bare one does and read as the same
  zombie, and the agent's armoured loadout read exactly like the plain sword — and armour is most of what a hard rung of
  the ladder changes. Whether the other side has engaged had only facing as a proxy, which a mob that has just dropped its
  target keeps for as long as it takes to turn away. What the weapon in the hand takes off had only the hotbar's one
  "sword" category for stone, iron and diamond. All three are per-entity or per-body readings the server always had.
  - **A count of the other side is not.** That is the one the critic keeps, because what makes it worth having is counting
    the side whether it is perceived or not, and in a real game there is no roster to count. Counted over the radius the
    agent does perceive it is `enemies in range` again — both already ask one rule for who is an enemy — so it was left
    out rather than added as a duplicate that costs weights and says nothing.
  - **The weapon is read from the item, not from the attribute the swing uses.** Vanilla applies a held item's modifiers
    when it notices the equipment change, in the body's own tick, and the observation for that tick is written before any
    body moves: so the attribute is what the *last* tick's hand was worth — a bare fist holding an iron sword on the first
    row of a fight, the weapon swapped out of on the row after a slot change. A critic may be priced off a row it reads
    late; a policy choosing whether to swing may not.

- **A field nobody reads is a field nobody has.** `ENEMY_EXPLODES` and `ENEMY_FUSE` were added to every enemy slot so that
  a creeper would read as "twenty health, no weapon, explodes, fuse at 0.4" instead of "monster", and then the teacher went
  on guessing: `ScriptedBrain` had **zero occurrences of either name**. Its test for a lit creeper was the one it was
  written with before the fields existed — empty hands, never swung, stopped coming, and inside 3.05 blocks — and it was
  evaluated on the single `target` slot, so a second creeper was never reasoned about at all however close it came. Two
  measurable costs. Against one creeper, 3.05 blocks is **0.8 blocks inside the creeper's own fuse range**, so the fighter
  was still in the blast when it went off: over 81 recorded single-creeper fights, **41 draws**, ending on 9.2 health of 20
  where a win ended on 19.9. Against two, the rule fired on whichever was nearer and walked the fighter into the other one.
  The teacher now reads the two fields on **every occupied slot** and backs away from the nearest lit thing, target or not;
  the guess is kept underneath for a body whose slots say nothing about exploding, and nothing else. Proved by
  `theTeacherBacksOffALitCreeperOnTheFuseAlone` (a creeper lit five blocks off, where the guess could not have fired) and
  `withTwoCreepersTheTeacherFleesTheLitOne`; with the fuse path disabled both fail, and the second fails by walking east
  into the lit creeper, which is what the old rule did. **After adding a field, grep the teacher for it.**
- **A network will not reliably attack something that is not fighting back, and a test must not ask it to.** The play
  suite's `worldAgentFightsOnTheBundledNetwork` put a `spawnWithNoFreeWill` zombie four blocks from an agent on the
  bundled network. The agent saw it, closed to a block, pressed attack twenty times in two hundred ticks — so the body was
  doing its part — and landed none of them: the aim never came onto it. Give the same zombie its free will and the blow
  lands inside a few hundred ticks. That is the engaged reading above doing exactly what it was added for, from the other
  side: a mob that has never taken the agent as its target is not a fight, and a league network was never trained on one.
  Fights in a test want a live opponent; a dummy is for the body's own rules, which is what the mechanics suite uses one for.
- **A crowded view is what breaks a published network in a real game, and it is not a bug in the body.** Reported from a
  creative world at night: `/mmai spawn`, a zombie summoned beside it, and the agent — `blast4`, 86% of the league and 98%
  against zombies on the bench — walked about and looked around while the zombie hit it, swinging at the grass and never at
  the mob, with `/mmai enemy` set or not. Four things were suspected and all four are innocent. **The observation is the
  world's own**: measured tick by tick against positions worked out independently, the zombie's slot reads its forward,
  right, up and distance in the agent's frame to under a hundredth of a block, with no fight site, episode or arena origin
  anywhere in it. **The zombie holds a slot**, `Allegiance.isEnemy` counting any vanilla `Enemy` as one; `ENEMY_TARGETS_ME`
  reads 1 the tick the zombie takes the agent as its target. **The attack path is whole**: in a bare box the same network
  and the same spawn press attack and kill that zombie in 45 ticks on 20 health. **Plants are not it either**: floor the box
  with grass and plant every block of it and it still kills the zombie in 65.
  What does it is the **number of monsters in view**, and nothing else. One agent on `best` against an engaged zombie two
  blocks off, 200 ticks each, monsters standing about out of reach at twelve blocks:

  | Monsters in view besides the opponent | Presses of attack | Blows landed | Outcome |
  | --- | --- | --- | --- |
  | none (nine of them at forty blocks, outside the view) | 3 | 3 | the zombie dead in 45 ticks, agent on 20 health |
  | one | 83 | 3 | the zombie dead, the agent down to 5 |
  | three | 7 | 0 | **the agent dead**, the zombie untouched |
  | five | 1 | 0 | the agent dead |
  | seven | 16 | 0 | the agent dead, aim pinned at -90°, straight up |
  | nine (every slot full) | **0** | 0 | the agent dead without swinging once |

  A night in a real world puts that many in view easily: the view is 32 blocks of distance and nothing else — no line of
  sight, no reachability — so mobs through a wall, across a valley and in the caves below all take slots, and
  `SELF_ENEMIES_IN_RANGE` reads 1.5 where nothing in training put it over 0.3. The league fields **one** opponent or a squad
  of **two or three**, every one of them on the other team and coming for the agent, so a view of ten bodies that mostly
  ignore it is a shape no network has ever seen. It is a curriculum hole, not a body or an allegiance fault, and
  `theCrowdedViewOfARealWorldIsTheWorldsOwn` in the play suite holds the innocent half — the slots and the numbers being the
  world's own — so nobody has to investigate it twice. What was done about it is the two entries below.
- **Most of that crowd was never a crowd: the view had no line of sight in it, and a wall was all it took.** A slot went to
  any hostile within thirty two blocks, full stop, so at night a real world put the monsters behind the rock, across the
  valley and in the caves below into the agent's ten slots. A candidate now also has to be in sight of the agent's eyes,
  vanilla's own `hasLineOfSight`, which is the test every mob's targeting already makes before it picks anything. Asked last
  of the three conditions, so distance and sides throw most candidates out before anything is clipped through the world; one
  clip per hostile candidate per tick, and the leases read the answer off the same walk instead of clipping again.
  - **Proved on the fight the finding is about.** The play suite's crowd of eleven stands outside the plot's bedrock box,
    twelve and twenty blocks off, which is exactly where a night's monsters are: on the other side of a wall. With the sight
    rule, not one of them is counted or leased, and the agent on `best` kills the engaged zombie two blocks away in **67
    ticks on 17 of its 20 health**. With the rule taken out and nothing else changed, the same eleven filled the slots and
    the same agent was **dead by tick 174 with the zombie on all twenty of its health**. That pair of numbers is the whole
    case.
  - **The arena did not move**, which is the check that says the rule is narrow: 20 of 20 at exactly 54.0 ticks, because a
    bare box and a league fight site have nothing between the fighters to occlude. The teacher reads the same slots, so a
    suite that had moved would have meant the rule was wrong rather than that the fighter was.
  - **Cover takes the reading away and leaves the slot.** An occupant out of sight keeps its lease for the grace — an
    opponent stepping behind a tree comes back to the slot it left, which is what leases are for — but `occupant` answers
    null for it and its slot reads plainly empty. There is no honest third answer: writing where it is now is seeing through
    the wall, which is the fault being fixed, and freezing where it was last tells the network a body is somewhere it has had
    two seconds to leave. A GRU is the thing that remembers, and it is better fed "gone" than a stale position. A slot saying
    nothing is also the first one a newcomer in plain sight takes, so a crowd that ducked behind rock cannot sit on ten slots.
    `aWallTakesTheReadingAndLeavesTheSlot` holds all three halves of that on a body that never moves.
  - **Reachability is not done, and was not needed to fix this.** A mob twenty blocks down in a cave with a line of sight up
    through a hole still takes a slot, and a player would see it too and also not fight it. What is wrong with it is that it
    cannot be reached, which is a property of the ground between and not of the view, and the observation already says how far
    below it is and that the floor under the agent is solid. A real path query per candidate per tick is the expensive kind of
    rule, and sight already removes the great majority of what a cave holds, because rock is what a cave is made of. Left
    undone deliberately; if it is ever wanted it belongs beside the sight test in `EnemySlots`, which is the one place who
    takes a slot is decided.
- **The other half of that crowd was the slot the opponent sat in, and it wiped out a whole curriculum.** With the sight rule
  in and a quarter of its fights standing 1 to 9 idle monsters about them, `blast7` trained **2,200 iterations** and its
  crowded win rate never moved: 32.1 / 29.8 / 31.8 / 30.7 / 32.4% over five buckets of 500 iterations, against 79.7% plain
  over the same fights (36,487 plain and 11,126 crowded evaluation fights). The curriculum was in the fights and nothing was
  being learned from it.
  - **What the results said, before anything was watched.** It is **dying, not stalling**: crowded fights are 59.1% losses
    against 14.2% plain, with timeouts only 7.0% against 3.7%. What kills it is its **own opponent** — `opponent` is 77.0% of
    crowded losses and 77.2% of plain ones, and `mob`, which is what a bystander killing it would read, rises only from 11.9
    to 13.5%. So the crowd is not killing the agent; the agent is losing the fight it was in. It is **not frozen** either, which
    is the one thing the real-world report had shown: presses per tick are identical, 5.12 uses over 443 ticks crowded against
    2.69 over 230 plain. And it is **graded, not a cliff** — one bystander already costs 15.6 points (64.1%) and every one
    after it costs more: 44.1 / 38.6 / 30.2 / 25.4 / 23.3 / 19.7 / 17.0 / 16.7%. The drop is flat across all ten loadouts
    (42 to 53 points) and across every opponent it can normally beat, and nil against the ones it never beats anyway — warden,
    evoker, two creepers — which says it is a flat loss of ability and not a matchup.
  - **What the ticks said.** Crowded fights record no replay, so a suite was built to watch one: `scripts\test.ps1 -Crowd`,
    the same zombie-with-a-sword fight on natural ground with nobody standing about it and with 1, 3 and 9, logging every
    tick. Three fights each, `blast6` driving:

    | Standing about | Won | Opponent in slot 0 | Its mean slot | `SELF_ENEMIES_IN_RANGE` | Aim off it | Blows on it | On a bystander | Into thin air |
    | --- | --- | --- | --- | --- | --- | --- | --- | --- |
    | none | 100% | 100% | 0.00 | 0.10 | 13.5° | 9 | 0 | 0 |
    | one | 100% | 18% | 0.82 | 0.17 | 44.7° | 10 | 0 | 12 |
    | three | 0% | **0%** | 2.07 | 0.36 | 65.0° | 3 | 0 | 193 |
    | nine | 0% | **0%** | 5.52 | 0.78 | 86.6° | 1 | 0 | 206 |

    **A slot went out in the order the level's own walk over its entity sections returned bodies** — section x ascending, then
    z, then y, and insertion order inside a section, which is `EntitySectionStorage#forEachAccessibleNonEmptySection`. Against
    one opponent that is no order at all: there is one body, it takes slot 0, and so **every fight any network had ever been
    trained on put its opponent in slot 0**. Stand nine bystanders round it and the opponent holds slot 0 only when it happens
    to be the westernmost of the ten. Within a fight the lease then freezes whatever the first tick decided, so the whole fight
    is fought with the opponent in slot 2, or slot 5, and the network's one reliable habit is being contradicted at random in a
    quarter of its fights.
  - **Three things the ticks ruled out, all of which had been guessed.** It is **not swinging at the nearest body**: blows on a
    bystander are **0** in every row, and thin-air presses are what rise — so the reward paying only the opponent was never the
    problem. It is **not that the nearest body is a bystander**: one is nearer than the opponent on only 14 to 30% of ticks, so
    even a nearest-first order would have left the opponent out of slot 0 a third of the time. And `SELF_ENEMIES_IN_RANGE` is
    **not saturating**: it reads 0.78 with nine standing about, well inside the 0 to 1.2 the league itself produces, where the
    real-world report's 1.5 came from the sight bug that is already fixed. The clamp was considered and left out: it would cost
    the network the difference between ten bodies and twelve for no measured gain.
  - **The fix is the order, and it is the same order twice.** Whoever has come for the agent or is on a team set against the
    agent's first, then the nearest; and eviction ranks by that same order, so it cannot undo what the order decided. The first
    draft got that wrong and was caught by a test: the fight took a bystander's slot and the evicted bystander took it straight
    back on the same tick, because the rung underneath still knew only about distance. Measured again on the same twelve fights,
    the opponent holds slot 0 on **100%** of ticks at three and at nine standing about, mean slot 0.00, and the aim comes in
    from 86.6° to 74.2°; eleven of the twelve fights hold it for every tick, and the twelfth is a crowd of one where the
    zombie's own target lapsed on the tick before the bystander was first seen and the lease then froze the order. It does
    **not** win those fights yet, and could not: `blast6` was trained with the old order, so it has never seen a crowd it could
    learn from. What the fix buys is a curriculum that is no longer noise.
  - **What was left alone, and why.** A **curriculum ramp**, drawing the idle count from 1 upward rather than 1 to 9 from the
    start, was the other candidate. The flatness is explained by the order, not by the difficulty, and a ramp changes which
    `+N_idle` players a checkpoint's evaluated win rate is averaged over — which is what the best weights are picked by, under
    a run already going. Worth trying if the crowded rate still does not move after a retrain on the fixed order. A **bystander
    that fights back** turned out to need nothing: `Bystanders#leaveAlone` only takes a target away while nothing has hurt it,
    so a struck one already keeps the agent, and `leagueBystandersStandAsideUntilStruck` already held it.
    (It did move, by twelve points, and then stopped; the draw has since been weighted towards the small crowds instead of
    ramped, for the reason written above. See **the flat draw spent the curriculum on the fights it loses** below.)
  - **Crowded fights now record a replay, which is why nobody had seen one.** A replay held exactly one body besides the
    agent, so a squad fight and a crowded one recorded nothing at all — two thousand fights a run in the one place the trouble
    was. The recorder now follows as many bodies as it is handed, each with its own frames and a role: `opponent` for the other
    side, `idle` for a monster standing about, which the viewer draws grey. The format had always said more entries might
    follow; see [replay-format.md](replay-format.md).
  - **A test of the crowd made an older test flaky, and the older test was overstating its case.**
    `leagueBystandersStandAsideUntilStruck` asserted that an unstruck bystander has **no target at all**. `leaveAlone` only
    promises that it has not taken *this* agent, and the mechanics suite's plots sit a few blocks apart, so three more plots
    with agents on them was enough for a bystander with its wits about it to pick a neighbour's and fail the suite about one run
    in three. The assertion now says what the rule says.
- **The view was the full circle, and that cost twice: nothing to turn for, and a bill that grew with the world.** A slot went
  to any hostile within thirty two blocks in any direction that the agent had a line of sight to. Two things follow from the
  circle and both are wrong. **A body at the agent's back read exactly like one in front of it**, so turning was worth nothing,
  the aim had nothing to do but point at what it was already fighting, and "look around" was never a thing the agent could be
  rewarded for learning. And **the work grew with the world**: a clip through the world for every hostile candidate, every tick,
  which on flat ground with a thousand mobs on it is a thousand clips a tick for an agent that is looking at one zombie.
  - **The model is now a player's**: a body is perceived if it is inside a hundred-degree cone about the agent's own aim *and*
    in sight; or within six blocks, all round and through anything, which is hearing; or if it is what last hurt the agent. Any
    one is enough. The cone is on the yaw alone, so overhead and underfoot are inside it — pitch is the aim for a bow and for a
    block and moves far more than a head does, and blinding the agent upwards whenever it looked at the ground would be a worse
    likeness than none. Hearing is what keeps the cone from being a blindfold in the melee, which is the one place being
    surrounded decides everything.
  - **The lease became the memory, which reverses an earlier decision on purpose.** A lease used to keep the slot and blank the
    reading, on the argument — written down here — that a position two seconds old is a lie and remembering is the GRU's job.
    That was right for a circle, where the only way out of the view was to go behind something. With a cone the commonest way to
    stop perceiving a body is that **the agent turned its head**, and a view where looking away deletes the zombie in front of
    you is not a player's. So a slot the agent has stopped perceiving now reads the body's last known position, velocity and
    heading, present flag on, for three seconds, and the window restarts on every tick it is perceived again. The wall test was
    rewritten to hold the new rule the hard way: the zombie is **moved** while it is hidden, and the slot goes on reading the
    corner it was last seen in, which is the thing wall vision could not do.
  - **What is remembered is the geometry and not the whole block.** Health, hands, swinging and whether it has the agent as its
    target read live even for a remembered body. That is a line drawn deliberately rather than an oversight: the geometry is what
    the agent aims and steps by, and a second snapshot of a mob's hands is a second thing to keep in step for no gain anybody
    could measure. It is written here so that the next person to notice does not take it for a bug.
  - **Measured on two thousand mobs**, one agent on flat ground, `scripts\test.ps1 -Horde`: **230 to 410 µs a tick** to perceive,
    with the clips pinned at their ceiling of twenty, where the old rule would have asked for a clip per body. The cost is the
    one query of the range's box and a dot product each; nothing in it is per-mob beyond that. `/mmai info` prints the same three
    numbers per agent in a live game, which is what to look at when a full world starts to feel slow.
  - **`SELF_ENEMIES_IN_RANGE` is now clamped at 2.0, reversing the "no clamp" decision above.** That decision was taken when ten
    bodies was the most the view could hold and the clamp would only have cost the network the difference between ten and twelve.
    In a world with a thousand mobs the same field reads a two hundred, which is a number no training fight ever produced — a
    league fight runs from nought to about 1.2 — so every weight reading it would be out in a range it has never seen. Two is
    twenty bodies: twice the slots, twice anything the league fields, so **nothing any trained network has ever seen changes**,
    and the clamp bites only where the number was meaningless anyway.
  - **The arena did not move**, which is the check that says the cone is narrow: 20 of 20 at exactly 54.0 ticks. The agent starts
    facing its opponent and closes on it, so the cone never comes into it, and the whole mechanics suite passed unchanged but for
    the wall test and two bodies that had been placed within a degree or two of the cone's edge.
- **Nothing in a real game ever came for an agent, so there was no fight to fight, and the spawn order only decided when the
  agent noticed.** Reported from a flat world: "if I spawn the agent after the mobs are already there, it doesn't seem to
  work" — it wanders — while "walking away from a crowd, spawning him, then zombies, then `/mmai enemy` somewhat works". Both
  halves are one cause, and it is not the slots. **Vanilla's hostiles look for a target among players, villagers, iron golems
  and turtles, and an agent is none of those.** So an agent stood in a crowd was ignored by every one of it, every slot in its
  view read `ENEMY_TARGETS_ME` as nought, and a network trained on a league where the opponent comes for it from its first tick
  does not start fights — which is the same reading `worldAgentFightsOnTheBundledNetwork` had already measured from the other
  side ("a network will not reliably attack something that is not fighting back", above). `/mmai enemy` worked because a team is
  the one other thing that brings a mob: `OtherTeamTargetGoal`.
  - **Measured, tick by tick, on the owner's own two orders**: six zombies on the floor of the box and an agent on `best`
    spawned into them in plain sight five to seven blocks off, no teams anywhere; then the same with the agent first and
    `/mmai enemy` after. With the fix taken out again, **not one of the six had the agent as its target for fourteen ticks**,
    and the first that did, on tick 15, did it by `HurtByTargetGoal` — *the agent had hit it* — with the other five taking the
    agent on that same tick by no goal of their own at all, which is the alert above. In other words the only fight that could
    ever start was one the agent started. With the fix in, a zombie takes the agent on **tick 2**, by `HuntAgentsGoal`, before
    the agent has pressed anything; in the teamed order it is `OtherTeamTargetGoal` on tick 1. Both orders then run the same:
    something coming for the agent on 186 of 186 and 176 of 176 ticks, 122 and 124 presses of attack, 7 and 6 blows landed.
  - **The fix is that a monster treats an agent as it treats a player**, `allegiance/HuntAgentsGoal`: a
    `NearestAttackableTargetGoal` for a playable agent, given to every pathfinding mob vanilla marks `Enemy`, at the priority
    vanilla's own player goal sits at, with vanilla's reach, sight and refusal of allies. **A training agent is deliberately
    left out.** An arena hands out every target it wants — an opponent provoked every tick, a bystander unprovoked every tick —
    and a goal reaching into that would quietly turn the crowded quarter of the league's fights into fights with extra
    opponents nobody rates and the reward does not pay for, which is the one fault `Bystanders` exists to prevent. So every
    training fight is byte for byte the fight it was, and the arena suite is still 20 of 20 at exactly 54.0 ticks.
  - **Two limits, documented rather than special-cased.** A spider's own player goal only fires in the dark and this one does
    not, so a spider comes for an agent in daylight where it would leave a player alone; and a mob vanilla drives with a brain
    rather than with goals — warden, piglin, hoglin — reads no goal at all and still has to be angered, which is what `Roster`
    already does for the league.
  - **The other half of the report was the slot the fight sits in, again, arriving by the other door.** Handing slots out by
    the order settles nothing while **nobody has engaged yet**: a crowd that has not noticed the agent is all ranked the same,
    so the slots go out nearest first, and a lease then keeps each body where it landed — the assignment loop only ever ranks a
    body that holds *no* slot. So the one that then came for the agent stayed in whatever slot the walk-up had given it for the
    rest of the fight, and the network's one reliable habit, that slot 0 is the fight, was being contradicted all over again.
    `EnemySlots#promoteTheEngaged` now moves the bodies that are in the fight in front of the bodies that are only in the view,
    among the slots those bodies already hold. **Engagement moves a slot; distance never does** — distance drifts every tick and
    a view that re-sorted on it would churn under the network for nothing, which is what leases are for. Seen in the log: slots
    `[2,3,0*,5,1,4*]` on one tick and `[0*,4*,2,3,5,1]` on the next. `engagingAfterTakingASlotMovesTheFightToTheFront` holds it
    and is the only test that fails when the pass is taken out.
  - **What was left alone.** A body dying in slot 0 leaves slot 0 empty with the rest of the fight behind it. That is not
    fixed, and deliberately: it is exactly what a squad fight has looked like since squads existed, so every network has
    trained on it, where shuffling the whole view up a slot on every death is a shape none of them has ever seen.
  - **It does not make the agent win.** Both orders above end with the agent dead, on 7 and 6 blows landed. Six hostiles all
    coming at once is a shape no league fight ever fielded, and making them come is what turned it from "no fight" into "a
    fight it loses" — which is the curriculum hole the packs below were added to close.
  - **Every kind of mind, by one mechanism, with one exception.** The audit behind `HuntAgentsGoal`: goal-driven hostiles acquire
    a player through a `NearestAttackableTargetGoal<Player>`, and this class *is* that goal with the agent in the player's place,
    so they are covered by existing. Being hurt was already type blind on both sides — `HurtByTargetGoal` and a brain's
    `HURT_BY` take whatever hit them — so nothing was needed for retaliation at all. Brain-driven hostiles, the piglins, hoglins,
    zoglin, breeze and warden, read `MemoryModuleType.ATTACK_TARGET`, and **the memories vanilla fills from a sensor are typed to
    `Player`**: `NEAREST_VISIBLE_ATTACKABLE_PLAYER` is a `MemoryModuleType<Player>` filled from the level's player list, so there
    is no "make the sensor see the agent" to be had — an agent in one would fail every behaviour that reads it back as a player.
    What there is, and what the goal does, is write the target the brain actually reads, plus the anger a piglin needs to keep
    one. Goal selectors tick for a brain mob exactly as for any other, so one goal covers both kinds of mind.
    `aBrainDrivenHostileComesForAnAgentWithNoTeamsSet` holds it on a piglin with no teams: it comes, and the agent kills it.
    **The warden stays the exception** — it picks by anger rather than by sight and its anger drains, so a target handed to it is
    dropped again; angering it over and over is a thing to do to an opponent in an arena and not a thing a mod should do to a
    player's world unasked, so a warden ignores an agent until something wakes it, exactly as it ignores a player standing still.
  - **A horde is a bounded cost and an unwon fight, and both were measured rather than assumed.** `scripts\test.ps1 -Horde`, one
    agent on `best` with a sword, zombies on rings from six blocks out, all of them sided against it:

    | Mobs | Driving | Clips a tick | µs a tick | Slots changing hands a tick | Blows | Slot 0 held by the fight | Ticks lived |
    | --- | --- | --- | --- | --- | --- | --- | --- |
    | 20 | `best` | 11 | 187 | 0.25 | 1 | 100% | 163 |
    | 20 | nothing pressed | 11 | 35 | 0.14 | 0 | 100% | 152 |
    | 100 | `best` | 20 | 97 | 0.38 | 0 | 100% | 188 |
    | 100 | nothing pressed | 20 | 54 | 0.28 | 0 | 100% | 151 |
    | 500 | `best` | 20 | 171 | 0.48 | 0 | 100% | 126 |
    | 2,000 | no AI, cost only | 20 | 287 | — | — | — | 121 |

    Four things to read off it. The **clips never pass twenty** at any size, which is the ceiling doing its job. The **time to
    perceive does not grow with the horde** in any way that matters — the 187 µs on the first row is the just-in-time compiler
    warming up on the first agent of the run, and the two thousand row is the honest steady number. **Slot 0 holds the fight on
    every tick of every row**, which is the one thing that has to keep being true for a trained network to have a chance, and the
    slots barely churn: half a slot changing hands per tick with five hundred bodies around. And the agent **does not win, and
    barely lands a blow**: one at twenty, none at a hundred. Against a body that presses nothing the survival numbers are noise —
    163 against 152 at twenty on this run, 155 against 151 and 148 against 165 on two others — so the suite deliberately does not
    assert them; it asserts that the network acts where the control does not, and reports the rest. A horde is the curriculum's
    problem, not the perception's, and the packs are the first payment on it.

## The league's curriculum

- **Seen on screen, in the owner's own world, the same evening.** Driven through the dev client with keystrokes and
  screenshots (`scripts\play.ps1 -World Test`, chat by clipboard paste, frames every half second): on the build before the
  sight rule, `blast4` killed one engaged zombie in about 3.6 s taking one hit, shot three skeletons dead in a row
  untouched with the Infinity bow, and cleared two zombies behind a shield in 3 s at full health — then stood among nine
  zombies in view and died on 20 → 14 → 5 → gone without landing a blow, its opponent still on 20 of 20. On the build with
  the sight rule, `blast6`: the same crowd of eight *behind a three-block wall* and it killed the engaged zombie in about
  2 s at 20 of 20, twice; the same eight *in plain sight* and it walked into them and died as before. The wall is the
  whole difference the rule makes, and the plain-sight case is what the bystander share is for. Frames are not kept in
  the repository; the driver's helpers (`mc.ps1`, `mc2run.ps1`, `fight.ps1`) live in the session scratch and are worth
  lifting into `scripts\` if this is to be repeated.
  - Two things that bit the driver: `/kill @e[type=!minecraft:player]` kills the agent too, and every loaded animal in
    the world; and a client on the same GPU as a training run takes the VRAM the trainer's 4 GB cap was counting on — the
    run's update hit CUDA out of memory and fell back to the CPU at three times the cost per update until restarted.
- **The flat draw spent the curriculum on the fights it loses, and the plateau was the shape of the draw.** The slot order fix
  worked: with it in, `blast7`'s crowded win rate climbed **31.6% → 44% over 6,000 iterations**, which is what a curriculum that
  is no longer noise looks like. Then it stopped, and sat at **44% for 4,000 more** (iterations 22k to 26k), while the plain
  fights beside it held 79%. Per count over the last buckets it is graded the whole way down and has no cliff in it:

  | Standing about | none | +1 | +2 | +3 | +4 | +5 | +6 | +7 | +8 | +9 |
  | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
  | Won | 79% | 68% | 58% | 48% | 46% | 39% | 37% | 32% | 31% | 31% |

  The count was drawn **flat** from 1 to 9, so five crowded fights in nine were spent on the five counts where it wins about a
  third and the eight points between +5 and +9 are all the room there is; the four where it wins about half, and where the
  twelve points had come from, got four in nine. A fight the agent loses at 31% at iteration 22,000 and at 31% at 26,000 is a
  fight nothing is coming out of, and the majority of the curriculum's spend was on those. The draw is now **weighted towards
  the small crowds**: a weight of one over the count, so one bystander comes up nine times as often as nine do.

  | Standing about | +1 | +2 | +3 | +4 | +5 | +6 | +7 | +8 | +9 |
  | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
  | Share of crowded fights | 35.4% | 17.7% | 11.8% | 8.8% | 7.1% | 5.9% | 5.0% | 4.4% | 3.9% |

  A crowd is now 3.2 bystanders on average instead of 5.0, and 1 to 4 of them takes **73.6%** of the crowded fights instead of
  44.4% — without closing the tail, which is the part that matters as much: nine still comes up on about one crowded fight in
  twenty five, so `+9_idle` is still rated and still trained on, and a count that stops being drawn is a row the run is judged
  on that quietly stops being fed.
  - **One draw changed and nothing else.** The share is still a quarter and still `-PleagueBystanders`; the two draws are
    separate on purpose — whether a fight is crowded, then how big the crowd is — so the skew can be changed without touching
    what a run was told. `Bystanders#name`, `League#crowded`, the trainer's `base()` and the `+N_idle` rating names are all
    exactly what they were, and the **explicit** counts elsewhere are left alone deliberately: `scripts\test.ps1 -Crowd` and
    `AgentCrowdedFightGameTest` fight 0, 1, 3 and 9 because those are the four rows of the table above worth watching tick by
    tick, and a probe that drew its own counts at random would stop being a probe.
  - **It moves the average an evaluated win rate is over, again.** That was the objection that held a ramp back the first time
    and it applies here too, so it is written down rather than dodged: a checkpoint's crowded rate before and after this is not
    the same number, because it is over a different mix of `+N_idle` players. What makes it acceptable where a ramp was not is
    that the mix is **fixed** — the same average from a run's first iteration to its last, so best weights are still picked by
    comparing like with like inside the run. The plain rate is untouched either way and is the number to compare across runs.
  - **What was rejected.** Dropping the large counts altogether (the tail is what the whole thing was built for, and a rating
    nothing feeds is worse than a hard one), and a ramp that walks the count up as the run goes (a moving average under a live
    run, which is what best weights are chosen by). Whether 44% was a plateau in the policy or in the draw is not settled by
    this — it is a curriculum change, and the next run's crowded curve is what says so.
  - `aCrowdIsDrawnSmallFarMoreOftenThanLarge` in the mechanics suite holds the shape, the tail and the share: 5,000 crowds are
    drawn, every count comes up, none more often than the count below it, each within four standard deviations of its own
    weight (`Bystanders#chance`, so the test is not a second copy of the weights), and 1 to 4 take two thirds or more.
- **The crowd the league trains takes no interest, and the crowd a real game has all comes at once.** The bystander share
  filled one of the two shapes a real world has. The other — three to six hostiles that all come for the agent — the league had
  never fielded at all: its opponent is one mob, or one of eleven chosen squads of two or three, and the largest of those is
  three. It became the ordinary case the day a monster started going after a playable agent the way it goes after a player (see
  Perception, above), because a night in the open brings whatever is in view rather than nothing; and it is the owner's second
  report, "he still gets massively overwhelmed", which the measurement of the first one ends in — both spawn orders end with the
  agent dead on six or seven blows landed. So a tenth of the fights against one mob now field **several of it, all of them
  fighting**, `league/HostilePacks` and `-PleagueHostileCrowds`.
  - **It is the squad machinery, which is why it cost almost nothing to add.** A pack is an `Opposition` of copies of the mob
    the fight was already drawn against, so the side, the provocation, the episode paying for each of them exactly once, the
    ground asked for a place to stand for each, the clock and the replay are all what a squad already had.
    `AgentLeagueGameTest` needed no change at all. The one fault a side made of copies invites, the same body on the other side
    twice and so paid twice for one blow, is what `aHostilePackAllComesForTheAgentAndIsPaidForOnce` is for.
  - **Copies rather than a mixed pack**, because the eleven squads already are the mixed packs: a body in front and a shot
    behind, a patrol, two that climb. What the league had no way of asking was **how many**, with nothing else changed, which is
    the question `2x_zombie` and `3x_silverfish` were chosen to ask at three points of the tier list. A pack asks it at every
    size and against every mob.
  - **Weighted small, and the weight is the one the bystanders' draw was already corrected to**: one over the number of extra
    bodies, so 44 / 22 / 15 / 11 / 9% for two through six and 3.19 on average. A flat draw over 2 to 6 would make exactly the
    mistake the flat bystander draw was caught making — most of the curriculum spent on the sizes there is nothing to learn from
    yet — and worse, since a pack of N is harder than a crowd of N at every size. Six is still drawn on about one pack in
    eleven, because a size that stops being drawn is a `+N_pack` row the run is judged on that quietly stops being fed.
  - **A tenth, not a quarter.** A pack is the hardest fight in the league above two and the dearest per fight of anything in
    it, and the plain fight against one opponent is still what the agent has to be able to win.
  - **Never a pack and a crowd in one fight.** Fifteen bodies to tick on one worker is the cost of three fights; and keeping
    the bystander draw exactly where it was, asked of every fight that is not a pack, means the mix of `+N_idle` players a
    checkpoint's evaluated rate is averaged over does not move — the objection that held a curriculum ramp back twice.
  - **Rated as `zombie+3_pack`**, the mob and three more of it, which is the `+N_idle` convention with the other word on the
    end and right for the same reason: the plain `zombie` rating has to keep meaning what it meant in every run before this
    one. Nothing matchmakes over the name, since it is not in the roster the workers hand over; `league.py`'s `base()` strips
    the suffix before the rung, so a pack inherits the mob's kind and the mob's cap and the tier list gets a row. A pack named
    `4x_zombie` was the obvious alternative and was rejected: `2x_zombie` is a **declared squad the trainer matchmakes over**,
    so a pack of two would have poured fights nobody allocated into a rated player's record.
  - **Smoke tested, 120 fights at a share of 0.6 over three mobs**: every size from `+1_pack` to `+5_pack` drawn, fought,
    rated under its own name, and ended — wins, losses and timeouts, nothing hanging and no site starved by asking for six
    places to stand.
- **A crowd of bystanders costs a fifth of a worker, not nothing, and the reason is their wits.** The share that stands 1 to 9
  idle monsters about a quarter of the league's fights was written down as probably free: a probe had run fourteen of them in
  a box with no measurable slowdown. Measured properly — 200 league fights on one worker, share off and on — it is **7,330
  arena ticks a second against 5,475** on one pinned terrain seed and 6,809 against 6,216 on a freely drawn pair, so 10 to 25%
  of a worker for a quarter of the fights, which puts a crowded fight at one and a half to two times a plain one. The probe's
  fourteen had **no AI**; a bystander on real ground has its wits, and a mob with its wits pathfinds every tick. The share is
  a property (`-PleagueBystanders`) so a run that cannot afford it can turn it down, and the number is here so nobody guesses
  again.
  - Keeping them motionless would buy all of it back and was rejected: a crowd that never moves hands the network a tell that
    a real world does not give it. The discriminator that has to be learned is `ENEMY_TARGETS_ME`, which is the same one out
    in a world, and an agent that learned "it is harmless if it is standing still" would fail in exactly the place this was
    built for.
- **A vanilla mob does go after an agent on its own, and the docs said it does not.** Three plain zombies, on no team, with
  nothing having touched them and no provocation of any kind, all took a nearby training agent as their target on tick seven at
  six blocks. Vanilla's zombie looks for a target among players, villagers, iron golems and turtles, and an agent is none of
  those; `OtherTeamTargetGoal` is asleep while a mob has no team; nothing had hurt them. **What does it was not found**, and it
  is written down here rather than chased because the fix does not depend on the cause: a bystander is handed its target back
  on every tick until something hits it (`Bystanders#leaveAlone`), which is the exact opposite of the provocation an opponent
  gets and is asked just as often. Without it a quarter of the league's fights would quietly have gained extra opponents that
  the reward does not pay for and the ratings know nothing about — a fault that would never have shown up in a result.
  `leagueBystandersStandAsideUntilStruck` holds both halves: unstruck they stay off the agent, struck one fights back.
  The line in playing.md has been corrected to what was measured.
  - **Settled: it is `HurtByTargetGoal` alerting its own kind, and it was never "on its own" at all.** Guessing from outside
    could not answer it, and one question from inside could: ask the goal selector which of a mob's target goals is *running*
    the tick it takes the agent. Measured on six zombies round an agent — one took the agent by **`HurtByTargetGoal` at
    priority 1**, and the other five on the **same tick** by **no target goal of their own at all**. That is vanilla's own
    `HurtByTargetGoal#alertOthers`: a mob the agent hurts hands the agent to every mob of its own class within its follow
    range, tens of blocks for a monster, by calling `setTarget` on them directly — no goal of theirs runs, none of them needs
    to see anything, and they all get it on one tick, which is the signature the report had all along. "Nothing had touched
    **them**" was true and beside the point: something had touched their neighbour. The game test plots sit a few blocks apart,
    which is how a bystander three plots away ended up with another test's agent, and is the same reason
    `leagueBystandersStandAsideUntilStruck` had to stop asserting that an unstruck bystander has no target at all.
    `PlayGameTest.aCrowdIsFoughtWhicheverWayRoundItWasSpawned` prints the goal's name on every run, so this cannot go back to
    being a mystery. The consequence for the league is that `Bystanders#leaveAlone` is doing more work than it looked like: a
    crowd of the opponent's own kind is alerted the instant the agent lands its first blow.
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
- **The poured pool leaked, and the leak outlived the fight.** Over blast3's 929,311 fights, lava was the largest cause of
  death that was not the opponent: 31,696 against 162,890, 3.4% of every fight. Three quarters of it was on ground the
  results call flat or water, which by definition has no lava on it. Where it came from:
  - `drop` sites are hazardous already and so are never poured on, and of 216 sampled `drop` replays **not one** had any
    surface lava within nine blocks of the fight. Of 137 `flat` replays 42 did, and of 93 `water` replays 30 — and 97.6%
    of that lava on flat ground and **100% of it on water was flowing lava with no source anywhere near it**. Eighty-one of
    the eighty-one sampled lava deaths on that ground were into flowing lava. Lava-labelled sites, by contrast, were 57.5%
    source blocks, and 53.5% of them carried the clean three by three the pour lays.
  - Flowing lava with no source is lava whose source was taken away without telling it. Two things did that. A pool was
    laid flush by checking the **middle column only**, so any step at the rim left a lava block over open air, and it ran
    the three blocks lava spreads on land and fell, out of the five by five the drain swept. And the drain put every block
    back with clients-only flags: a flow only works out that its source is gone on a scheduled fluid tick, and the only
    thing that schedules one is a neighbour update. So the spill was permanent, and a site hosts a hundred fights.
  - A site's label is worked out once and kept until the site moves on, so all ninety-nine later fights were recorded as
    the ground the site used to be. `ground.csv`, every `site` column and the hazard draw were wrong for them.
  - The bill, taking drop ground as the clean baseline (0.014% lava, 0.066% burning): 26,741 fights, **2.7% of the run**,
    lost to lava and fire on ground that was supposed to have none — about two and a half points of evaluated win rate,
    which is more than the whole remaining gap to the teacher.
  - **Measured before and after, 1,500 league fights of the scripted fighter each on the same pinned ground**
    (`-PterrainSeed`, one worker, a replay every second fight, every replay's blocks searched for surface lava within nine
    blocks of the middle of the fight): `flat` and `water` replays carrying flowing lava with no source went from **22.3%,
    62 of 278, to 0 of 292**, and lava deaths on that ground from **20.0 per 1,000 fights to 0.00**. `drop` had none either
    way. Lava sites still carry their pools: 28 of 41 lava replays have nine or more source blocks beside the fight.
  - **The neighbour update is nearly the whole of it, and the flush check is the last 0.7%.** With `UPDATE_ALL` and the
    wider sweep alone, flat and water came to 0.7% (2 of 269) and no lava deaths at all; the flush check takes those last
    two, at a fifth of the pools. Worth knowing which half is load-bearing before either is touched again.
- **The agent does also walk into real lava, and it is not blind and not knocked.** Of 107 sampled lava deaths, the grid
  marked a hazard **continuously for ten ticks or more** before the step in 94, and a ray reported one in 93; only two had
  nothing on a ray the tick before. Nineteen were knocked in (hurt in the six ticks before), eleven backed in retreating,
  and fifty-eight walked in forward while closing on the opponent, most of them sprinting. So there is no perception gap
  and no pricing gap either — the reward already charges the full loss, the full health bar, and the lava damage tick by
  tick as it lands. What the agent lacks is the teacher's hard rule: its planner never steps onto a hazard cell, and it
  loses 6 fights in 20,000 to causes that are not the opponent. Do not price or gate anything on the numbers above until
  the leak is fixed and the run re-measured: three quarters of them are the harness, not the policy.
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
- **A swing at a ghast's fireball did nothing at all — a fourth divergence from a player's hands, and not a deliberate
  one.** `Player#attack` carries a branch before it ever looks at the damage: a target in the `redirectable_projectile`
  tag is deflected along the swinger's own look, reassigned to the swinger, and the swing ends. `AgentMob#resolveAttack`
  had no such branch and went straight to `target.hurt`, which `Fireball#hurt` answers `false` to, so the press was spent
  for nothing. The aim was never the problem: `Projectile#isPickable` is true for that tag, so `pickAimedEntity` was
  already handing the fireball over. Measured over blast4's 100 recorded ghast fights, **489 fireballs and not one sent
  back**. The ghast sits a mean 21 blocks above the agent — out of a sword's reach for the whole fight, while the fireball
  comes to the agent. The tag holds only the fireball and the two wind charges, so nothing else in the league moves.
- **And it does not kill the ghast, because the agent is not a `Player`.** The obvious next sentence — a ghast has ten
  health and its own fireball takes six on the way in, so sending one back is how you kill one — is true for a player and
  false for the agent, and it was in this patch's own commit message before it was ever built. A ghast is **fire immune**,
  so `DamageTypes.FIREBALL` is refused outright, and vanilla's one exception to that is a type test:
  `Ghast#isReflectedFireball` asks whether the fireball's owner `instanceof Player`, and both `Ghast#isInvulnerableTo` and
  `Ghast#hurt` go through it. The deflection hands the fireball to the agent, a `PathfinderMob`, so the thousand damage a
  player's reflection deals is never reached; and the blast cannot make it up either, since a power-one explosion reaches
  two blocks from its centre and a ghast is four wide, so its middle is always at least that far from wherever the fireball
  met its face. Pinned by `aGhastIsSparedItsOwnFireballUnlessAPlayerSentItBack` rather than fixed: making it work means a
  mixin on a vanilla mob's invulnerability, which is the owner's call. **What the deflection is worth is still real**: the
  six and the blast that were coming at the agent go somewhere else, and the fireball is the agent's own projectile from
  then on, so whatever it does reach it hurts (`aDeflectedFireballHurtsWhatItIsSentInto`). Don't plan a ghast matchup round
  a kill that does not happen.
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

- **blast7's judged best is under two points above the published network, so nothing was published.** blast7 judged its
  best at iteration 27100 (75.9% and a rating of 1652 by its own evaluation, which is the run's highest). Benched against
  the published `blast6` and the scripted fighter, one worker, `bench.ps1 -Teacher`, two sittings an hour apart on
  2026-09-14 while the run itself trained on eight workers:

  | Fighter | 600 fights | 2,000 fights |
  | --- | --- | --- |
  | the scripted fighter | 82.2% | 78.3% |
  | `blast7` 027100, the judged best | 73.5% | 73.5% |
  | `blast7`, the run's newest (027557, then 027636) | 74.2% | 72.2% |
  | `blast6`, published | 71.2% | 71.8% |

  The judged best is **1.7 points above the published network over 2,000 fights** and 2.3 over 600, which is inside what a
  sitting can tell apart, so `models\` was left alone. The two columns are two sittings and cannot be subtracted from each
  other; each is read on its own, and both put the same four fighters in the same order.
  - **The last checkpoint did not beat the judged best this time**, which is the third check of the paired rule below to go
    the best's way: 027100 read 73.5% twice and the newest 74.2% and then 72.2%, all within a sitting's noise of each other.
  - **Every network on this bench is below the scripted fighter, and the crowd is the whole of it.** `blast6` was published
    at 84.7% against the fighter's 82.3%; in the crowded sitting above it reads 6.5 points *under* the same fighter, whose
    own reads held. The anchor did not move, so what moved is what a network is asked to do. Asking the same three fighters
    the plain one-on-one question settled which change did it — `bench.ps1 -Teacher -Bystanders 0`, 1,000 fights each, one
    worker, an hour after the sitting above:

    | Fighter | plain, 1,000 fights | crowded, 2,000 fights |
    | --- | --- | --- |
    | `blast6`, published | **82.7%** | 71.8% |
    | `blast7` 027100 | 81.0% | 73.5% |
    | the scripted fighter | 80.3% | 78.3% |

    Two sittings, so read each column on its own and compare only each row's distance from the fighter, which is in both.
    **Without the crowd `blast6` stands 2.4 points above the fighter — the very margin it was published for — and with it
    6.5 points below: a swing of nearly nine points against the anchor, and the networks and the fighter change places.**
    So there is no one-on-one regression on this build and nothing to hunt in the body or the observation: the crowd of
    bystanders in a quarter of the league's fights is the entire gap, it costs a network about nine points *relative to the
    fighter*, and `blast6` is exactly as good a one-on-one fighter as the day it was published. How much the crowd costs the
    fighter in its own right is not a thing these two sittings can say — that would be subtracting across them, and the
    fighter's own reads span five points with nothing changed at all. The fighter reads every slot it
    is given and does not care what else stands there; a network trained almost entirely on empty ground spends its slots on
    monsters that are not fighting it. **That is where the next points are**, and it is a training problem — the curriculum
    landed after every published network was trained. CLAUDE.md and [models.md](models.md) now carry both numbers rather
    than the one bench that no longer exists.
    - `blast7` is the first network trained *with* the crowd and it is 1.7 points up on the crowded bench and 1.7 points
      down on the plain one, both inside a sitting's noise. So the curriculum has not yet bought anything measurable, which
      is the thing to watch as the run carries on rather than a verdict on it at 27,100 iterations.
  - **The scripted fighter read 77.3%, 82.2% and 78.3% inside one hour**, on one machine, one build, three sittings of the
    same 600 or 2,000 league fights. The recorded span was 78.2 to 81.5; this widens it to 77.3 to 82.2, or **4.9 points of
    drift with nothing at all changed**. It is the sharpest measurement yet of why a number from one sitting cannot be
    subtracted from a number in another.
  - **`bench.ps1` copied each network aside only when its turn came, and the run's pruner ate one mid-sitting.** The script's
    own header promised the copy; it took it inside the fight loop, so the newest checkpoint of a live run — named at the
    start, fought fourth — was gone four minutes later and the sitting died with `Cannot find path ...027471.mbw`. Every
    network is copied before the first fight now. A live run keeps every 25th iteration and its last few, so it is exactly
    the file worth benching that the pruner takes first.

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
    and nothing else. A seeded run is pulled towards the record of the copy it came from, and league768 has never had a
    DAgger round of its own, so the teacher pull it spends 6,000 iterations under has never once shown it a bow, a shield
    or an axe. This is "a network never learns what the teacher never did" again, one level up: the teacher can do it, and
    the record still cannot say so.
  - **The control is league-sharp**, which does have two league DAgger rounds. Same body, same rules: **12.04 draws and
    11.01 arrows a fight** with a bow, won 60.3%, which is *above* its own sword's 56.8%. A bow is not a handicap once the
    record holds one.
  - **What was left, and it was the arrows: a slot to slip to.** In league-sharp a bow alone finishes 91% of its draws; a bow
    with a sword beside it starts 2.42 draws a fight and looses **0.09** arrows, 4%, and sword_and_bow sits 2.4 points under
    the plain sword and 5.9 under the bow. On l770n, with the record fixed but the slot still free, a bow alone took 9.93
    shots a fight and won 55.7% against a sword and bow's 5.84 and 42.4%: the same gap, on a run where the bow itself works.
    Changing slot was the one thing that still dropped a draw, and a network that chooses its slot afresh every tick dropped
    its own.
    - **So the slot is committed too, for the same reason the button was.** While a bow or a crossbow is in use in the main
      hand, a slot the brain asks for is refused, and it is granted on the first tick after the use ends: the arrow goes and
      the sword comes up on the tick behind it (`AgentMob#drawHoldsTheSlot`). It is the third place the hands are not a
      player's. The arithmetic is the button's arithmetic over again — a draw needs the same slot twenty ticks running, and
      4% of draws surviving twenty of them is a slot head holding the bow on 85% of ticks (0.85^20 = 3.9%), which is what a
      head that is mostly right looks like. Committing the button alone had only moved where the draw was being lost.
    - **It has to hold for the whole use and not only below full charge**, because vanilla ticks the use before the controls
      are applied (`LivingEntity#tick` calls `updatingUsingItem` and then `aiStep`). A lock that lifted at full charge would
      lift on the tick the draw finished, one tick before the release resolves, and a slot asked for on that tick with the
      button still down would throw the whole finished draw away: vanilla stops a use whose hand no longer holds what started
      it, and a stop is not a release, so there is no arrow. Put the two rates already measured together — use pressed again
      on 47.6% of the ticks something is in use, a slot flicked on about 15% — and that leak is about one arrow in eight,
      which is arithmetic off two numbers rather than a measurement of its own. Holding on costs one tick of not pressing
      use, which is the same tick that looses the arrow.
    - **Nothing is queued, and no layout changed.** `MobControls` is a keyboard and not a list of events, so a refused slot
      needs no memory in the body: the brain is still asking for it next tick, and a second copy kept in the entity could
      only disagree with the brain's own. Nor is the refusal invisible — the echo already carries the slot actually held and
      how far the use has charged, so a network reading its own last tick is told that its slot did not move and its draw
      did. The recorded action stays the slot the policy sampled, which is what the ratio needs; the body ignores it the way
      it ignores a jump asked for in mid air.
    - **What it takes away is the teacher's one deliberate cancel**, inside `ABANDON_DRAW_RANGE`: it used to drop a draw for
      the sword at two and a half blocks, and it now spends the ticks left in the draw, looses, and swaps behind the arrow.
      That is rare, because `finishesInTime` stops it starting a draw it cannot finish, and the arena's twenty fights still
      take **exactly 54 ticks** each, which is the test that catches a change in the reference fighter.
    - The alternative was to mask the slot head during a draw in the sampling instead, in both `Forward.java` and the PyTorch
      heads. Not worth it: it touches the parity-checked forward pass and the log probabilities on both sides, for a rule the
      body states in one line, and it would only bind a network — the teacher, and any hand written brain after it, could
      still cancel a draw by accident.
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
    of the movement every tick of it, and holding at full draw to aim is still allowed. Changing slot still gave it up,
    which took another measurement to see; that is the bullet above.
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
    - **The scripted fighter is a candidate like any other**, and the reference is worth nothing measured anywhere else: its
      own reads span 78.2 to 81.5% across sittings, which is wider than most of what it is asked to judge. `bench.ps1
      -Teacher` puts it in the same call rather than in a second one; `eval.ps1 -Teacher` is for asking about that fighter
      on its own.
  - **Keep `-Workers` at one, and the same either way, because several workers are worth several points on their own.** This
    was recorded as "each worker takes its own slice of the arenas" without a number on it. The number is large. The scripted
    fighter, 600 league fights, back to back: **three workers gave 77.8% and then 83.0%; one worker gave 79.00% and then
    78.97%.** Five points of slop against three hundredths.
    - The mechanism is the opponents, not the ground. A league evaluation spreads its fights evenly over the opponents *per
      worker*, and workers get through uneven shares of the total, so the aggregate opponent mix is skewed by whichever of
      them ran fastest. An opponent is worth anything from 0% (a warden) to 100% (a wolf), so a skew of a few dozen fights
      between a warden and a wolf moves the whole number. Ground cannot do that: the four kinds sit within a few points of
      each other over 600 fights.
  - `-Ground` is for asking the same question of a different sample of the library, not for steadying the answer — it went
    in believing the opposite and the measurement said otherwise.
    - And the ground is **not** the same from one evaluation to the next, whatever `eval.ps1` used to say at the top of it.
      Without a seed `TerrainSites.startLibrary` starts each worker somewhere random in the library — its own comment says
      "anywhere otherwise" — so every evaluation is a fresh sample of ground. What makes the answer repeat is the number of
      fights, not repeated ground: the two one-worker runs above agreed to three hundredths of a point on ground that was
      nothing like the same, 236 flat sites against 91, 174 lava against 70, no water at all in one and 159 in the other.
    - **And a pinned seed does not make two builds fight the same fights either**, so it is no use for "did this change cost
      anything". Measuring the teacher on one build and then the other, one worker, 600 league fights, `-Ground 7` both
      times: `sword_and_bow` 88.2% then 88.3%, and `bow` **84.2% then 82.3%** — on a change that provably cannot touch a
      bow-only fight, since the teacher with no sword and no axe asks for slot 0, which is the slot the bow is already in.
      Unseeded, the same pair of builds gave 84.3% then 87.7% and 79.0% then 80.2%. Four sittings of one question spanning
      four to five points, which is what "never subtract a number from one sitting from a number in another" means in
      practice; see [testing.md](testing.md). A seed pins where in the library a worker starts and nothing else, and the
      worker sizing is measured from the machine's own throughput at startup, so the fights themselves come out differently
      every sitting. **To show a behaviour change, find a column that is not a win rate**: the same pinned pair moved
      `hit it` on `sword_and_bow` from 164 of 600 fights to 142, which is the change doing its work where the win rate
      could not see it.
- **"Best on the opponents both met" cannot see a network that has learned the rungs opened since the best was set.** The
  paired rule exists so a hardening roster does not make a better checkpoint read worse, and it does that; but the same
  hardening hides the opposite. The run `blast` set its best at iteration 2150 (76.3% plain), then read 77 to 78.7% plain
  from 2675 to 2925 and none of it counted, because over the opponents the two had *both* met the later ones were not a
  point better — what they had learned was the hard rungs the best had never fought. Patience ended the run at 3053.
  Benched in one sitting, one worker, 600 fights: **iteration 3053 won 80.7% against the judged best's 72.7%**, and the
  teacher on the same bench 81.0%. So the run's own verdict was eight points wrong about which of its networks to keep,
  and the published one was the weaker.
  - What to do until the rule is better: **bench the last checkpoint against the best before publishing**, always, with
    `-Teacher` in the same call so the pair has a scale, and carry a plateaued run on as a *new run seeded from its latest
    state* rather than resuming it, so the evaluator judges the current roster afresh.
  - What a better rule needs: the opponents only the candidate has met are evidence too, weighted by how many fights they
    are, rather than discarded. Not done yet.
  - The second time it was checked it was right, which is worth recording beside the first. `blast2` (carried on from
    that 3053) set its best at 3100, read 83.4% and 81.9% plain at 4800 and 4850, and ended on patience at 4944. Benched
    in one sitting: **4944 won 77.0% against 3100's 75.3%** and 2150's 75.2%, a spread the bench itself calls equal, with
    the teacher at 80.5% in the same sitting. So the plain reads overstated it, the judged best was as good as the last,
    and the published network stayed. The two sittings also differ by five points for the same files (80.7 → 77.0, 81.0
    → 80.5 for the teacher), which is the cross-sitting drift above: one ran on an idle machine, the other beside eight
    training workers. Only the numbers inside a sitting are comparable, and "level with the teacher" reads as "within a
    few points of" once both sittings are counted.
  - A third time, and this time the rule missed eight points again: `blast5` judged its best at 11050 (85.0% by its own
    evaluation) and ended on patience at 12407. One sitting, `bench.ps1 -Teacher`: **12407 at 84.7%, the scripted
    fighter 82.3%, the published `blast4` 77.2%, 11050 at 76.7%**. The judged best was the weakest of the four. So the
    standing rule is now firm: **a run's last checkpoint is benched against its judged best before anything is published**,
    and the one that wins the sitting is the one that ships — which is how `blast6` (12450, carried on from 12407) came to
    be published over both.
  - And the third sitting says the run has stopped: `blast3` carried on to 8312 (judged best 6275), and one bench of its
    last, its best and the published 3100 read **80.8, 79.0 and 79.7%** against the teacher's 81.5% — 5,200 iterations of
    pure RL inside 1.8 points of each other. That is a plateau one to two points under the teacher, not a run still
    climbing, and it is where the lava leak below and the harder opponents (warden 0%, evoker 1%, 2× creeper 6%, ghast 22%)
    are the levers left, not more iterations of the same.
  - **The lava leak was the lever, and it took the network past the teacher.** `blast4` resumed on the fixed ground at
    8669: its own lava deaths went from 33.6 to 0.07 per 1,000 flat-and-water fights, its judged best from 82.3% to
    **86.2% at 8825**, and one sitting on an idle machine read **8825 at 83.3%, 10761 at 83.7%, the published 3100 at
    75.2%, and the teacher at 78.2%**. Five points over the fighter it was copied from, on the same bench in the same
    sitting — the first time a network has measured above it. The teacher's own reads span 78.2 to 81.5 across sittings
    and the network's 83.3 to 83.7, so the order holds even at the teacher's best.
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
