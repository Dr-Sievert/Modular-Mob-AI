# Testing

## The checks to run after a change

```
scripts\test.ps1                 20 fights in the closed arena with the scripted fighter
scripts\test.ps1 -Mechanics      the item and block rules against a player's numbers, and what the agent sees of them
scripts\test.ps1 -Play           the agent in a real game: networks in the jar, /mmai, sides, Infinity loadouts
scripts\parity.ps1               Java forward pass against PyTorch's, after touching brain\nn or the model
```

| Check | Pass looks like | Proves |
| --- | --- | --- |
| `test.ps1` | `All 20 required tests passed`; every fight 54 ticks | the observation, the body and the scripted fighter still work; the 54 ticks are deterministic, so any change in them is a behaviour change |
| `test.ps1 -Mechanics` | `All 58 required tests passed` | bows, crossbows, shields, axes, mining, placing, use slowdown and damage payment follow a player's rules, what a press of attack costs and what holding it down costs, that a swing sends a ghast's fireball back and nothing else, that a hand keeps the slot it started a draw in, what the observation says about a use, about the clock and the quiver, about what is shot at the agent, about how many of the bodies in its view are in the fight with it, about the armour on either side of the fight, about whether the other side has engaged and about what the weapon in the agent's hand takes off — the last three against the same numbers the critic's privileged facts carry — the teacher getting itself out of powder snow and starting no draw it cannot finish, what an enemy slot goes to and what a wall between leaves of it, **which slot each body gets — the fight first, then the nearest, and the fight moved to the front when it engages late** — that the league's bystanders stand aside unpaid until struck, that the size of a crowd is drawn small far more often than large and that a pack of one mob all comes for the agent and is paid for once, and how a league training fight is drawn from the trainer's shares |
| `test.ps1 -Play` | `All 24 required tests passed`, and a `Loaded the mod's jar, modular_mob_ai/models/<name>.mbw from iteration N` line per network the jar carries | what [playing.md](playing.md) promises: the bundled networks load and drive an agent, the commands, saving, sides and friendly fire, the Infinity loadouts, and that nothing a suite tunes reaches a real game |
| `parity.ps1` | `parity ok` once per body for the plain network and again for the attended one, logits agreeing to about 1e-6, and `the plain ones agree to the bit at batches 1 to 64` on both arms | the game runs exactly the network PyTorch trained, **for every body this build has**, plain and attended — the attention over the enemy slots is different arithmetic and not merely a different size — and the forward pass's explicit vector loops give the same bits as its plain ones |

The network the `-Play` line names is whatever is published: the suite asks the jar what it carries and holds every network
in it to the rule, rather than naming one, so publishing or retiring a network changes the log line and nothing else. The
one thing it will not tolerate is a network in `models\` that this build cannot drive —
`networksInTheJarLoadByName` then fails with that network's name and the schema it carries, which is what to retire; see
[models.md](models.md).

Each boots a headless server in seconds, and all of them need only Java (parity also needs the trainer's Python).
`test.ps1 -Loader neoforge` runs a suite on NeoForge instead of Fabric.

## More

```
scripts\test.ps1 -Terrain                 the fights on natural terrain; generates a world first
scripts\test.ps1 -Arenas 200              more fights
scripts\test.ps1 -Terrain -Replays        record every fight for the viewer, in runs\gametest\replays
scripts\test.ps1 -League                  194 fights, twice round every league opponent and squad on normal and on hard; a table of each
scripts\test.ps1 -League -Weights models\blast\best.mbw     the same with a network, which also fights a frozen copy of itself
scripts\test.ps1 -League -LeagueModels blast                published networks in the league too, each a player of its own; two more fights
scripts\test.ps1 -Crowd -Weights models\blast6\best.mbw     one league fight fought with 0, 1, 3 and 9 monsters standing about it, watched tick by tick
scripts\league.ps1 -Test                  the trainer's whole unit suite, where the league's own tests live: Elo, the pairings and their shares, the pool, reading and resuming results, and beside them the shard reader, the critic, the auxiliary heads and the teacher pull
scripts\eval.ps1 -Weights models\blast\best.mbw            a network's win rate, 2,000 fights, most likely action
scripts\eval.ps1 -Run blast -Iteration 2150 -Arenas 400    a checkpoint of a local run
scripts\eval.ps1 -Teacher -Suite league -Arenas 600        the scripted fighter alone, when nothing is being compared to it
scripts\bench.ps1 -Run blast -Last 4                       several networks on one bench, best first
scripts\bench.ps1 -Run blast -Last 4 -Teacher              and the scripted fighter as one more row of it
scripts\bench.ps1 -Weights models\blast\best.mbw,runs\blast\weights\002000.mbw
```

`eval.ps1` fights with no exploration and records nothing for training. With 2,000 fights the win rate is within about
a point either way; with 400, within about two and a half. It takes `-Workers` (8), `-Slots`, `-Heap`, `-Suite`,
`-Loadouts`, `-Opponents`, `-Ground` and `-ReplayEvery`.

**`-Teacher` measures the scripted fighter instead of a network**, on the same sites in the same order, and it is the
reference a league number is meaningless without. A fresh copy winning 27% of the league says nothing on its own: the
roster holds wardens and evokers, and the question is always how much of what is missing is the copy and how much is the
fight. Ask the teacher the same question and the answer has a scale.

`bench.ps1` takes the same switch, and that is where to reach for it: **the reference has to be measured in the same sitting
as what it is the reference for.** Its own reads span 78.2 to 81.5% over an evening, which is larger than most of the
differences it is being used to judge, so a teacher number from an earlier invocation cannot be subtracted from a network's.
`eval.ps1 -Teacher` is for asking about the fighter itself, not for putting a scale under something else.

`-Opponents` narrows a league evaluation to those players by the names the league writes in its results, `ravager`,
`2x_zombie`, `zombie(hard)`. With `-ReplayEvery 1` that is how to get a replay of a matchup training never wrote one of:
`scripts\eval.ps1 -Weights runs\<run>\best.mbw -Suite league -Arenas 40 -ReplayEvery 1 -Opponents ravager` leaves forty
fights in `runs\eval-<run>-best\replays`. The league page offers that command itself; see [viewer.md](viewer.md).

`bench.ps1` is `eval.ps1` over several networks with the answers side by side, and it is what to reach for whenever the
question is which of two networks is better. Five things it does that doing it by hand does not:

- **every network in one sitting**, which is the whole point. Back to back a network measures within half a point of
  itself, but the same file measured 66.7% early one evening and 60.5% later, with every network in the sitting moving
  together: the worker sizing is taken from the machine's own throughput at startup, so it comes out differently beside four
  training workers than beside two, and different sizing means different fights. **Never subtract a number from one sitting
  from a number in another** — it reversed a result once already;
- **the same worker count for every network**, because each worker takes its own slice of the arenas and 600 fights over
  one worker are not the 600 over three. One worker by default, which also leaves room for a training run beside it;
- **a copy of each network taken first**, because a run keeps only its last few weight files and prunes the rest while the
  bench is running;
- **the reference in the same sitting**, `-Teacher`, as one more row measured first. Everything above applies to the
  scripted fighter as much as to a network, and a reference fetched from a second invocation is the very mistake the first
  bullet is about;
- **it says when the spread is inside what the fights can tell apart** — about half a point at 600 fights within a sitting,
  so three points mean something and one does not.

Don't compare a league run's own `eval.csv` numbers between two runs: those are measured against opponents the matchmaking
keeps changing, and the rating wanders by thirty between checkpoints. The bench is fixed ground and a fixed roster, so two
of its numbers can be subtracted. It is what showed that a run flat for 11,500 iterations gained five points in a thousand
once its entropy came down; see [findings.md](findings.md#learning).

## The mechanics suite (`gametest/tests`)

Each test sets up one situation and checks the numbers a player would get. Seven classes, one per concern, all of them
sharing `tests/Mechanics` — the arena, the agent, the brain that hands back what the test pressed, and the readings more
than one of them takes. Every class has to be named in `ModularMobAiGameTests` or its tests simply do not run, which is why
the count below is worth knowing: **58**.

`AgentDrawnWeaponGameTest` (9) — a bow and a crossbow, and the slot the hand keeps while one is drawn:

| Test | Checks |
| --- | --- |
| `bowShotFliesHitsAndIsPaid` | 20-tick draw, full-draw crit arrow at 3.0 blocks/tick, owned by the agent, one arrow used, hit, paid once |
| `onePressDrawsToFullAndLooses` | one press, the button up for every tick after it, and the same critical arrow twenty ticks later: the draw that runs to full on its own, which is what makes a drawn weapon learnable |
| `bowWithoutArrowsDoesNotDraw`, `bowTakesTheArrowAPlayersWould` | ammo rules; an off-hand spectral arrow goes first |
| `crossbowChargesHoldsItsLoadAndFires`, `crossbowMultishotLoadsThreeForOneArrow` | crossbow loading and firing at a player's speed |
| `aSlotFlipMidDrawWaitsForTheArrow`, `aSlotFlipMidWindWaitsForTheLoad` | the hand keeps the slot it started a draw in: the sword asked for eight ticks into a draw waits, the arrow leaves at full power and the bolt is loaded, and the sword comes up on the tick behind; with the hands free a slot moves on the next tick, and a loaded crossbow is free to be put away |
| `aDrawThatSendsNothingStillHandsTheSlotBack` | what hands the slot back is the use ending and not the arrow going, so an empty quiver cannot strand a hand on the bow |

`AgentMeleeGameTest` (12) — a blow and a block, what a press costs and what it is paid, and what a swing at a ghast's
fireball does:

| Test | Checks |
| --- | --- |
| `shieldBlocksWhatComesFromTheFront` | blocks blows and arrows from the front, not from behind |
| `axeKnocksTheAgentsShieldAside`, `agentsAxeKnocksAShieldAside` | the axe disables a shield for 100 ticks, both ways |
| `usingAnItemSlowsToAFifth`, `noAttackingWhileAnItemIsInUse` | use slowdown and no attacking while using |
| `aSwingAtAirCostsTheCooldownAndOneAtABlockDoesNot` | what a press costs: a swing at nothing restarts the cooldown, one that meets a block leaves it standing |
| `holdingAttackTakesLessHealthThanWaitingForTheCooldown` | two blows a cooldown apart take more health off than fourteen presses in a row, which is the damage curve and the hurt immunity together |
| `aSwingSendsAFireballBackAndSpendsThePress` | a player's deflection: a ghast's fireball under the aim leaves along the agent's look, owned by the agent, taking no damage, and the press costs the cooldown a landed blow costs |
| `aDeflectedFireballHurtsWhatItIsSentInto` | a fireball sent back is the agent's own projectile and hurts what it reaches, four blocks past anything the swing could have hit |
| `aGhastIsSparedItsOwnFireballUnlessAPlayerSentItBack` | and what it does not buy: a ghast is fire immune and vanilla forgives that only for a fireball a `Player` owns, so the ghast keeps all ten health. A limit pinned, not a bug; see [findings.md](findings.md) |
| `aSwingAtAnArrowDeflectsNothing` | only the tag moves: an arrow is not deflected, keeps its owner and its heading, and the swing is the swing at thin air it always was |
| `swordBlowIsPaidOnceAndOnlyOnTheOpponent` | damage payment |

`AgentBlockGameTest` (9) — breaking a block and placing one:

| Test | Checks |
| --- | --- |
| `shovelDigsDirtInAPlayersTime`, `handBreaksStoneSlowlyForNothing`, `pickaxeBreaksStoneForCobblestone` | the player's break formula: iron shovel on dirt 3 ticks, hand on stone 151 ticks, iron pickaxe on stone 8 |
| `miningInTheAirIsFiveTimesSlower` | off the ground a block takes five times the work |
| `lettingGoKeepsTheBlocksProgress`, `lookingAwayStartsTheBlockOver` | the crack waits where it got to while the aim stays on the block, and is lost the moment the aim moves off it |
| `bedrockNeverBreaks` | it never gives, and hitting it costs nothing |
| `placingUsesABlockUpAndNeverBuildsIntoAnything`, `placedBlocksFaceAsForAPlayer` | placing |

`AgentPerceptionGameTest` (8) — what the agent sees of all of it. Four of these readings are held against the same numbers
the critic's privileged facts carry:

| Test | Checks |
| --- | --- |
| `useProgressIsTheItemsOwnCharge` | the echo's use charge: a bow's power curve, a crossbow's wind, nothing with the hands free |
| `onlyShotsComingAtTheAgentTakeASlot` | an arrow on its way takes a slot with a kind of its own; one crossing, one lying still and the agent's own take none, and the mob keeps slot zero |
| `onlyTheBodiesInTheFightAreCounted` | `SELF_ENEMIES_IN_RANGE` counts the bodies **in the fight** and not the bodies in the view: one zombie coming for the agent and two standing beside it reads 0.1 and not 0.3, with all three holding slots, and 0.2 the moment one of the two is struck and comes for it |
| `theClockRunsUpAndTheQuiverRunsDown` | the self block's clock is this fight's own ticks over this fight's own limit, never going backwards and reading 1 once the time is up; arrows left follow the hotbar and fall as a bow is fired; a body with nothing that shoots and no fight reads nought for both |
| `aSlotSaysWhatTheOpponentCanDo` | a slot says what the mob in it can do rather than which mob it is, which is what tells a warden from a zombie |
| `aCreeperSaysItExplodesAndHowCloseItIs` | it says it explodes, and how far along its fuse is |
| `aSlotSaysWhatItWearsAndWhoItIsAfter` | armour, and whether that mob has the agent as its target: set and let go inside one tick, so the facing it used to be inferred from cannot have moved |
| `theAgentSeesItsArmourAndWhatItIsHolding` | its own armour and what its weapon takes off, the weapon from the item's own modifiers so it is right on the first row and follows a swap at once |

`AgentTeacherGameTest` (6) — the scripted fighter's own rules, which matter because it is the league's 1500-rated anchor and
every network was copied from it:

| Test | Checks |
| --- | --- |
| `theTeacherGetsOutOfPowderSnow`, `theTeacherBreaksOutOfPowderSnow` | the scripted fighter, with a zombie to fight, walks out of one block of powder snow and breaks its way out of a patch three wide |
| `theTeacherStartsNoDrawItCannotFinish` | with a sword and a bow against a vindicator six blocks off, every draw begun sends an arrow — none is begun and given up — and it still lands blows |
| `theTeacherDrawsAtWhatShootsBack` | the same loadout against a skeleton six blocks off looses an arrow inside 60 ticks, which is what stops the rule above being satisfied by never drawing |
| `theTeacherBacksOffALitCreeperOnTheFuseAlone` | a creeper lit five blocks off, past the three the old guess waited for and never moving or swinging, so `ENEMY_EXPLODES` and `ENEMY_FUSE` are the only things that can have sent the teacher backwards — and it goes back rather than closing to the band a sword wants |
| `withTwoCreepersTheTeacherFleesTheLitOne` | two creepers either side, the unlit one nearer and so the target: the teacher walks away from the **lit** one, which the old rule, read on the target slot alone, sent it straight into |

`FightSetupGameTest` (3) — the three that are not about the body at all, here because this is the suite that holds a rule to
a number and boots in seconds:

| Test | Checks |
| --- | --- |
| `pouredLavaIsLavaAndLeavesNothingBehind`, `pouredLavaSweepsUpWhatItFedOutsideItself` | the lava poured beside a hazard fight is nine blocks of it, every block of ground it touched is exactly as it was once drained, and lava it fed three blocks away — as far as lava spreads on land, and outside the box the drain used to sweep — is swept up too and reported, which is what makes the site forget a label the spill has made untrue. Where a pool may go needs open ground with a heightmap, so the live run is what exercises that; see [findings.md](findings.md) |
| `theLeagueDrawsAPairingByItsShare` | a league training fight comes out of `league/pairs.csv` as a loadout and an opponent together, in proportion to the shares, the same way twice from one seed, nothing the table names ever starved, and a pairing the build cannot field dropped. The shares themselves are the trainer's, tested by `scripts\league.ps1 -Test` |

`AgentCrowdGameTest` (7) — what the view is allowed to hold, the league's crowd, and its packs:

| Test | Checks |
| --- | --- |
| `theNearestOfWhatIsInSightTakeTheSlots` | in `AgentCrowdGameTest`: twelve standing about in the box with nothing between them, and the ten nearest are the ones described, worked out from where they actually are rather than from where they were put — while the count of what is in the fight reads nought, since none of the twelve is in one |
| `aWallKeepsTheLastKnownPlaceAndThenForgetsIt` | the memory, and the whole of the decision behind it: a wall between agent and zombie leaves the slot reading **where the body was**, present flag up and counted, and the zombie is then **moved** while hidden and the slot goes on saying the old corner — which is the claim, since wall vision would have followed it. Shown again it is in the same slot and read live again; three seconds unperceived and the slot is gone |
| `leagueBystandersStandAsideUntilStruck` | the league's crowd is a crowd: on no team, never the other side of the fight, never paid for by `Episode#pays`, never counted in the fight, holding slots in the view all the same, kept off the agent every tick — and free to fight back once the agent has hit one, which the count follows |
| `aCrowdedFightIsNamedForItsOpponentAndItsCrowd` | `zombie+3_idle`, the share drawn 1 to 9 and at the rate this build was told |
| `aCrowdIsDrawnSmallFarMoreOftenThanLarge` | **how big the crowd is**: 5,000 crowds drawn, each count within four standard deviations of its own weight (one over the count, taken from `Bystanders#chance` rather than copied), none drawn more often than the count below it, nine still drawn at all, and two thirds or more of the crowds four or fewer — the curriculum's answer to a crowded rate that sat at 44% while every count above four was a fight it mostly lost |
| `aHostilePackIsDrawnSmallAndNamedForTheMobItIsAPackOf` | the packs' draw, the other half of the same curriculum: the name (`zombie+3_pack` is a zombie and three more of it), the share this build was told, and the skew — 2,500 packs drawn, each size within four standard deviations of `HostilePacks#chance`, none drawn more often than the size below it, six still drawn, and three fifths or more of them two or three |
| `aHostilePackAllComesForTheAgentAndIsPaidForOnce` | the pack's arrangement, every half of which would be invisible in a run's results if it were wrong: all of them on **one** team against the agent, every one of them coming for it, every one of them paid for by `Episode#pays` and on the other side **exactly once** — the fault a side made of copies invites — and the whole side in front of a body on no team standing nearer |

`AgentEnemyOrderGameTest` (4) — **which** slot each body gets, which is the rule a whole curriculum turned on. A slot used to
go out in the order the level's own walk over its entity sections returned bodies, so against one opponent it always landed in
slot 0 and in a crowd it landed anywhere; see [findings.md](findings.md#perception). Every case here is arranged so the body
that should win is the one the old order returned last:

| Test | Checks |
| --- | --- |
| `theOneFightingTheAgentTakesTheFirstSlot` | one opponent that has come for the agent, furthest of four, takes slot 0, and three idle bodies nearer than it take the slots behind it in order of their distance |
| `aWholeSideComesBeforeTheCrowdAndTheNearestOfItFirst` | a squad's own half of the rule: two on a team set against the agent's, with their targets taken away every tick so the team is the only thing making them the fight, take slots 0 and 1 nearest first, and the crowd takes nothing in front of them |
| `aFightTakesAnIdleSlotWhenThereAreNoneLeft` | with ten idle bodies holding every slot, one that then comes for the agent gets in — eviction ranks by the same order the slots are handed out in, so it cannot undo what the order decided |
| `engagingAfterTakingASlotMovesTheFightToTheFront` | the half of the order handing slots out cannot reach, and the one a real game turned up: with nobody engaged the slots go out nearest first, so the body that then comes for the agent is sitting in a late slot and a lease would keep it there. Furthest of five, it takes slot 4, engages, and is in slot 0 on the next tick, with the four it passed one slot further back each and in their own order — engagement moves a slot, distance never does |

Ammo: the agent's bow and crossbow loadouts carry 64 finite arrows, one used per shot, and arrows aren't picked back up.
That covers a 60-second fight, since a full-draw shot takes 20 ticks. Vanilla skeletons and pillagers never run out.
Real play gets `bow_infinity` and `crossbow_infinity` instead, one arrow that Infinity never uses up; see
[playing.md](playing.md) for why.

## The crowd suite (`gametest/tests/AgentCrowdedFightGameTest`, `-Psuite=crowd`)

One league fight — a zombie, a sword, natural ground — fought with nobody standing about it and with 1, 3 and 9, and watched
tick by tick. It answers four questions and nothing else: **which slot the engaged opponent is in**, what
`SELF_ENEMIES_IN_RANGE` reads, how far off the opponent the aim is, and **who a press of attack lands on**. It prints a line
per fight and a table per crowd, which is how the slot-order fault was found; the numbers are in
[findings.md](findings.md#perception).

```
scripts\test.ps1 -Crowd -Weights models\blast6\best.mbw     twelve fights, three per crowd
scripts\test.ps1 -Crowd -Weights <w> -Arenas 40             more of them; the crowds go round in turn, so use a multiple of four
```

**Nothing in it is asserted, deliberately.** A slot fights one fight after another out of a shared queue, which is
`succeedWhen` and not `onEachTick`, and the framework treats a failed assertion in a `succeedWhen` as "not finished yet" and
runs the whole thing again next tick rather than failing the test — so an assertion here would be a silent retry loop. The
rule the suite measured is held as a rule by `AgentEnemyOrderGameTest` in the mechanics suite, where an assertion fails a test.
It is a suite of its own rather than a class in the league's because it fields a fixed schedule of crowds where the league
draws them, and because the league suite is what a training run runs.

## The horde suite (`gametest/tests/AgentHordeGameTest`, `-Psuite=horde`)

Twenty, a hundred, five hundred and two thousand mobs round one agent on flat ground. It is a **cost** suite before it is a fight
suite: the agent is meant for worlds with thousands of mobs in them, and the old view — the full circle, a clip through the world
per hostile — would have put a thousand clips a tick into a night that a player would have walked through. The plot's box is
opened first, walls and roof to air, because a wall is a perfect answer to a crowd and a horde fought from inside one would be
measuring the box.

```
scripts\test.ps1 -Horde
scripts\test.ps1 -Horde -Weights models\blast6\best.mbw     a network of your own driving the agent
```

| Test | Checks |
| --- | --- |
| `theCostOfPerceivingDoesNotGrowWithTheHorde` | two thousand mobs, no minds, a hundred and twenty ticks after a warm-up: the clips never pass twenty on any tick, and the mean time to perceive is under a millisecond — 230 to 410 µs measured, with half a millisecond as the target it warns about missing rather than failing on. They have no AI deliberately: what is timed is the **agent's** perception, and two thousand pathfinding zombies would time the server instead. None of the two thousand is coming for the agent, so `SELF_ENEMIES_IN_RANGE` reads nought on every tick of it with the slots full the whole time, which is the invariance at the largest size anything here fields |
| `inAHordeOfTwentyTheAgentFightsBackWhereAnIdleBodyDoesNot` | the same horde fought by the network and by a body that presses nothing. The network presses and the control does not, which is the floor; blows landed and ticks lived are reported |
| `inAHordeOfAHundredTheCostStaysBoundedAndTheFightHoldsSlotZero` | the same at a hundred, where nothing is asked of the outcome. From a hundred up the count of what is in the fight is held to going past the ten slots at some point, which is what the clamp on that field is for |
| `aHordeOfFiveHundredIsStillBounded` | five hundred, reported: the clips bounded, and slot 0 holding a body that is in the fight on every tick one is in the view |

It prints a row per fight — mobs, what drove the agent, clips a tick, µs a tick, slots changing hands a tick, blows, the share of
ticks slot 0 held the fight, ticks lived — and the whole table grows as the tests finish. **Winning is not the criterion and is
not asserted**: a network trained on one opponent and a crowd of nine does not beat five hundred zombies, and the survival
comparison against an idle body is noise run to run (163 against 152 one run, 148 against 165 another). The numbers and what they
say are in [findings.md](findings.md#perception).

## The play suite (`gametest/tests/PlayGameTest`)

The agent as a player meets it, one test at a time: its agents have no arena bounding their view, and 32 blocks reach
into the tests either side, so the suite runs its tests one after another on one plot, cleared between them
(`GameTestTuning.soloTests`).

| Test | Checks |
| --- | --- |
| `networksInTheJarLoadByName` | the build put the networks in the jar; `best`, a name and `scripted` load; bad names and paths are refused |
| `worldAgentFightsOnTheBundledNetwork` | a playable agent on `best` goes for a zombie and hurts it, inside a fight's length. The zombie keeps its free will, unlike every other fight here: against a dummy the network closes and swings and lands nothing, because the enemy slots now say whether an opponent has the agent as its target and a league network has never met one that ignores it |
| `theCrowdedViewOfARealWorldIsTheWorldsOwn` | eleven monsters standing about behind the box's wall take no slot and are not counted, the zombie beside the agent keeps its own, every position in that slot is the world's own to a hundredth of a block, `ENEMY_TARGETS_ME` follows the zombie's own target, and the agent on `best` kills the zombie in 67 ticks — where with the sight rule taken out the same eleven filled the slots and the agent was dead by tick 174 with the zombie untouched. See [findings.md](findings.md#perception) |
| `aloneAnAgentStandsStill` | with nobody in view an agent stands still whatever its brain says, and moves once a zombie turns up |
| `spawnCommandMakesAnArmedAgentOnItsBrain`, `bareSpawnArmsWithTheConfigsLoadout` | `/mmai spawn`: loadout, brain, position, never despawning; `default` |
| `spawnCommandWorksFromAFunction` | the same from a data pack's function or a command block: a function's permission, relative coordinates |
| `loadoutCommandArmsAgentsAndMobs`, `brainCommandRefusesWhatLeadsNowhere` | `/mmai loadout` on an agent and a zombie; `/mmai brain` and its refusals |
| `agentKeepsBrainAndLoadoutThroughSaving` | brain name, loadout name and the hotbar as it stands survive saving; `/summon` with `Loadout` and `BrainName` |
| `onlyEnemiesAreInTheView` | an ally never takes an enemy slot; one set against the agent does, on the next tick |
| `alliesNeverFightAndEnemiesDo` | two scripted agents after `/mmai ally` leave each other alone for 100 ticks; after `/mmai enemy` they fight |
| `mobsOnOpposingTeamsFight`, `mobsOnOneTeamLeaveEachOtherAlone` | a zombie and a vindicator on opposing teams fight; an iron golem and a zombie on one team don't |
| `agentFightsTheOtherSideWhateverItIs` | the agent ignores a cow until the cow is on the other side |
| `friendlyFireOffSparesTheSide` | an agent's blow on an ally does nothing with friendly fire off, and lands with it on |
| `infinityBowNeverRunsOut`, `infinityCrossbowNeverRunsOut` | three shots, two bolts, the one arrow still there; the training loadouts keep 64 |
| `aSecondBodyIsDrivenAndARefusedBrainIsNamed` | a beast, the body with no hands, is spawned, sees the enemy slots every body shares, and walks on its own seven-wide action vector; and a humanoid's brain offered to it is refused with both bodies named |
| `aRealGameKeepsItsLightEngine` | **nothing a suite tunes reaches a real game.** A suite that keeps its light has one, read as full sky light above its plot; and with the suite property taken away — which is what a client passes — `GameTestTuning` answers as a real game needs, `lighting` included. A world made with `play.ps1` was pitch dark because it did not; see [findings.md](findings.md). What a suite gets is pinned in the same test, since the value of that fix was that it changed no fight |
| `aZombieAndAnAgentFightWhicheverWasPutDownFirst` | the plainest default: one zombie, one agent, no teams and no commands, and they fight whichever was put down first. The mob takes the agent on its own inside 60 ticks, the fight is in slot 0 with its targets-me flag up, and one of the two is dead inside a fight's length |
| `aBrainDrivenHostileComesForAnAgentWithNoTeamsSet` | the same for a mob vanilla drives with a brain rather than goals: a piglin, immune to zombification as the league's own is, comes for the agent with no teams set and the fight resolves. Its kind of mind reads `ATTACK_TARGET`, and the memories vanilla fills from a sensor are typed to `Player`, so the goal writes the one the brain reads; see `HuntAgentsGoal` |
| `hordeCommandStandsThemUpAndSetsThemAgainstTheAgent` | `/mmai horde zombie 12 4`: twelve stood up, twelve in the world, and every one of them on one team set against the agent's |
| `aCrowdIsFoughtWhicheverWayRoundItWasSpawned` | the owner's own two orders, in one test: six zombies on the floor and an agent spawned into them with **no teams anywhere**, then the agent first with `/mmai enemy` set on them after. Something comes for the agent in both, the slot reading it says so, and whoever is fighting holds slot 0. It logs a line per tick — who holds which slot, which are engaged, the aim off slot 0, presses, health — and names the goal that handed out the first target, which is what settled the "a vanilla mob goes after an agent on its own" question. Winning is not asserted: six at once is what the packs are for. See [findings.md](findings.md#perception) |

## Game tests directly

Game tests live in a `gametest` source set next to `main` in every project. They're written once in
`mod/common/src/gametest` and compiled into each loader, so a test runs on both Fabric and NeoForge.

```
mod\gradlew.bat -p mod :fabric:runGametest
mod\gradlew.bat -p mod :neoforge:runGameTestServer
mod\gradlew.bat -p mod :fabric:runGametestParallel -Psuite=terrain -Parenas=2000 -Pworkers=4
```

| Property | What it does |
| --- | --- |
| `suite` | `arena` (closed box), `terrain` (natural ground, what training uses), `league` (terrain, a new opponent every fight), `crowd` (the same fight with 0, 1, 3 and 9 standing about it, watched tick by tick), `horde` (20 to 2,000 mobs round one agent: what perceiving them costs), `mechanics`, `play`, `baseline` (villager against vindicator, no agent), `library` (no fights: builds the terrain library, see `scripts\terrain.ps1`) |
| `terrainLibrary`, `librarySites` | `false` makes terrain workers generate their own ground even when a library exists; how many sites a library build generates |
| `addSites` | how many sites to append to the library that is already there, instead of building a new one; only the new ones are generated |
| `leagueOpponents`, `leagueLoadouts`, `leagueDifficulties` | league only: fewer opponents (`zombie,2x_zombie`), fewer loadouts, which rungs of the ladder a run with no trainer goes round (`easy,normal,hard`) |
| `leagueModels` | league only: published networks in `models\` to field as rated players (`blast`, or several separated by commas); nobody unless named, and a name that is another player's, or a network of another body, is refused by name |
| `leagueHazards` | league only: the share of fights drawn onto ground with lava or an edge on it, 0.25 by default |
| `leagueBystanders`, `leagueHostileCrowds` | league only: the share of fights that stand a crowd of monsters about them taking no interest (0.25), and the share of the fights against one mob that field several of it, all of them fighting (0.1). `0` turns either off; see [training.md](training.md) |
| `arenas`, `workers`, `batchSize` | fights, worker processes, fights at once per worker |
| `sites`, `siteRadius` | fight sites laid out, and chunks either side of each one's centre (2 = 80 blocks across) |
| `brain`, `brainWeights` | `scripted` (default) or `neural` with a `.mbw` file |
| `species` | which body the run trains and parity checks, `humanoid` by default. Naming one also narrows `brainParity` to that body instead of going round every body the build has; see [species.md](species.md) |
| `opponent` | who the agents fight, for trying them against something other than a vindicator: `-Popponent=minecraft:skeleton`, any entity type id. The `arena` and `terrain` suites only — the league draws its own |
| `demonstrations` | a folder, relative to the repository, to write shards of whatever drives the agents into: `-Pdemonstrations=runs/x/looked-at`. Any brain, any suite, for looking at a policy's own answers afterwards. It is not how a teacher record is made — that is the `recordDemonstrations` task, which `imitate.ps1` and `dagger.ps1` call |
| `replayEvery`, `replayRun` | record one fight in N, into `runs\<replayRun>\replays` |
| `capture` | a CSV of every tick of the `baseline` suite's fights — both fighters' positions and health, relative to each arena's own corner — written into each worker's folder. Only that suite records it (`ArenaRecorder`); for the agent's fights the replays are the record |
| `reusePlots` | whether a batch keeps the plots it has instead of clearing them and placing the structures again. `true` or `false` overrides the default, which is to reuse on natural terrain and in the play suite — out there a plot is only bookkeeping underground, and a fresh one means generating real chunks, which is far dearer than clearing |
| `workerJvmArgs` | anything else a worker's virtual machine should start with, split on spaces: a profiler, or a collector to try, without editing the build. A relative filename lands in the worker's own folder, which the next run clears. `-PworkerJvmArgs="-XX:StartFlightRecording=delay=60s,duration=120s,filename=worker.jfr,settings=profile"` |
| `workerHeap` | heap per worker; the build picks 1G on the terrain library and 2G otherwise when this is absent |
| `workerCores` | `all` lets the workers run on every core; by default they are confined to the performance cores, which doubles a worker's throughput on a chip that has two kinds |
| `serverThreadCores` | `performance` confines the server thread instead of the whole worker, leaving the rest of the process every core; off by default, because it measured no faster |
| `terrainSeed` | pins where the fight sites come from, so two rounds fight the same ground; 0, the default, picks somewhere new |
| `ticksPerSecond` | ceiling on the tick rate; unthrottled by default, since a game-test server never sleeps |

Fabric writes a JUnit report to `mod/fabric/build/gametest/report.xml`. The normal client and server runs also load the
game-test source set, so tests can be run by hand in a dev world with `/test runall`.

The `terrain` and `arena` suites run with no light engine, which is worth about a fifth of a worker's throughput and does
not change a single fight: nothing the agent or a vindicator does reads light. Every other suite keeps it, since `canSeeSky`
is a light question and the undead, spiders, endermen and rain all are too. `GameTestTuning.lighting` decides, and a run
that keeps its own world keeps its light so the light it saves is worth reading back.

**A toggle in `GameTestTuning` is inert outside a game-test server**, and anything added there has to be. The client and
server runs of both loaders are handed this source set too, so that `/test runall` works in a dev world, and
`fabric.mod.json` lists its mixin config — so every mixin here is loaded in a real game. `GameTestTuning.gameTestServer`
is what says whether this process is a suite: the suite property being present, which every game-test run and every worker
passes and nothing else does. The light above was the setting that proved it, by leaving a played world with no light at
all; `PlayGameTest.aRealGameKeepsItsLightEngine` holds the rule, and [findings.md](findings.md) has the audit of every
mixin in the config.

Every suite runs **at midnight in clear weather**, both cycles stopped (`GameTestServerMixin`). A plot's roof is no
protection on a test's first tick: the plot is cleared and rebuilt between tests, and the light of a box placed this tick is
not worked out until the next, so the sky shows through bedrock for that one tick and an undead mob catches fire under it.
That cost the play suite about one run in seven, reported each time as whatever assertion the burning mob happened to trip;
see [findings.md](findings.md). A test that depends on it should assert there is no sun, not that its mob cannot see the sky.

### Writing a test

1. Add a class under `mod/common/src/gametest/java/net/sievert/modularmobai/gametest/tests`, annotate it with
   `@GameTestGroup`, and list it in `ModularMobAiGameTests`.
2. Each `@GameTest` method resolves its structure as `<namespace>:gametest/<path>/<template>`, so
   `@GameTest(template = "arena")` loads `mod/common/src/gametest/resources/data/modular_mob_ai/structure/gametest/arena.nbt`.
   A structure only declares the size of the region; building the scenery is the test's job.
3. Tests must not import loader-specific code.

Arm fighters with `arena/Loadout.java`: `Loadout.BOW.equip(agent)`. The presets are `sword`, `sword_and_shield`,
`axe_and_shield`, `bow` and `crossbow`; `arena/Loadouts.byName(name, level.registryAccess())` also has `bow_infinity` and
`crossbow_infinity`. Put fighters on sides with `allegiance/Allegiance`: `Allegiance.side(a, b)`, then `Allegiance.disband`
the team when the test is done, since teams outlive it. See [playing.md](playing.md), "From code".

### Development only

The game-test source set never reaches a published jar. The processed metadata points at the game-test entry point and
mixin config for every development run, and the `jar` task strips them again while packaging.
- In `neoforge.mods.toml`, a game-test mixin entry needs a trailing `#gametest` comment on both of its lines.
- In `fabric.mod.json`, the game-test mixin config has to stay first in the `mixins` array.
