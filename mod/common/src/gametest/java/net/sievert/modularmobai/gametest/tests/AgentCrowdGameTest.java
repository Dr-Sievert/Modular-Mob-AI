package net.sievert.modularmobai.gametest.tests;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntPredicate;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.sievert.modularmobai.Constants;
import net.sievert.modularmobai.allegiance.Allegiance;
import net.sievert.modularmobai.arena.Episode;
import net.sievert.modularmobai.brain.Brain;
import net.sievert.modularmobai.brain.BrainStep;
import net.sievert.modularmobai.brain.schema.ActionSchema;
import net.sievert.modularmobai.brain.schema.AgentObservation;
import net.sievert.modularmobai.brain.schema.EnemySlots;
import net.sievert.modularmobai.brain.schema.ObservationSchema;
import net.sievert.modularmobai.brain.schema.Species;
import net.sievert.modularmobai.entity.ModEntities;
import net.sievert.modularmobai.entity.agent.AgentMob;
import net.sievert.modularmobai.gametest.GameTestGroup;
import net.sievert.modularmobai.gametest.league.Bystanders;
import net.sievert.modularmobai.gametest.league.HostilePacks;
import net.sievert.modularmobai.gametest.league.Opposition;
import net.sievert.modularmobai.gametest.league.Roster;
import net.sievert.modularmobai.gametest.league.Splits;
import net.sievert.modularmobai.gametest.terrain.TerrainSites;
import net.sievert.modularmobai.gametest.util.TestTicks;

/**
 * A crowd in the agent's view, and what the view is allowed to make of it.
 *
 * <p>Two rules are held here, both of them about slots rather than about fighting, which is why this is in the mechanics
 * suite and not the league's: the nearest of what the agent can see win the ten slots, and <b>a slot goes only to what it
 * could see at all</b>. The second is new, and it is what a real game turned out to want: thirty two blocks of distance with
 * no sight test in it hands the slots to the monsters through the wall and in the caves below, and the published network,
 * fed a view of ten bodies that mostly ignored it, stopped fighting the zombie beside it. The whole story and the numbers are
 * in findings.md; {@code PlayGameTest.theCrowdedViewOfARealWorldIsTheWorldsOwn} is the same thing proved on a real fight.
 *
 * <p>The three after that are the curriculum's half of the same problem: the bystanders a share of league fights now stands
 * about it, which are the crowd the league never had, and which have to stay out of the fight's own arithmetic while filling
 * its view — what one is, what a fight with one is called and at what rate they come, and how big the crowd is when it comes,
 * which is weighted towards the small crowds the agent can still learn something in. See {@link Bystanders}.
 *
 * <p>The last two are the other half of that curriculum: the packs, several of the same mob that <b>all</b> fight, which is
 * what a real world puts round an agent now that a monster comes for one the way it comes for a player. What is pinned is the
 * draw — the name, the share and the skew — and the arrangement: one side, every one of them coming, and every one of them paid
 * for exactly once, which is the fault a side made of copies invites. See {@link HostilePacks}.
 *
 * <p>Everything happens inside the plot's own bedrock box, and the walls that take sight away are built by the test rather
 * than borrowed from the arena's, so nothing moves between the two readings but the block in the way. An agent that could
 * see out of its plot would see its neighbours' fights instead, which is what the episode's bounds are for.
 */
@GameTestGroup
public class AgentCrowdGameTest {

    private static final String ARENA = "arena";

    /** Long enough for the leases' own grace to run out twice over, which the wall test waits through. */
    private static final int FIGHT_TICKS = 1200;

    /**
     * How many crowds the skew is measured over, and how many draws it may spend getting them. Enough that the rarest count —
     * nine, at about one crowd in twenty five — is expected two hundred times, so a count of nought there means the tail really
     * is closed and not that the sample was small. The cap is what a share far below a quarter costs, and no draw touches a
     * world, so even four hundred thousand of them is a few milliseconds.
     */
    private static final int CROWDS_WANTED = 5_000;
    private static final int CROWD_DRAWS_CAP = 400_000;

    /**
     * The same for the packs: enough that six of them, at about one pack in eleven, is expected two hundred times, so a count
     * of nought there says the tail really is closed. The draws cap is shared with the crowd's, since a share far below a tenth
     * costs the same there.
     */
    private static final int PACKS_WANTED = 2_500;

    /**
     * How long a killed slime is given to leave its children behind: vanilla makes them when the body is finally removed,
     * which is twenty ticks of death animation after its health reaches nought, and this is generous room around that.
     */
    private static final int SPLIT_WAIT_TICKS = 120;

    /** And how long after they appear before the agent's view is asked about them: it is worked out when the agent is stepped. */
    private static final int SPLIT_SETTLE_TICKS = 5;

    /** How near where a body of the fight fell a new one has to be to be its child; the arena's own {@code SPLIT_NEAR}. */
    private static final double SPLIT_NEAR = 4.0D;

    /** The largest pack the draw fields, and the size the arrangement below is held on: four, which is ten slots half full. */
    private static final int HOSTILE_MOST = 6;
    private static final int PACK_SIZE = 4;

    /**
     * Where the twelve stand, as offsets inside the room from the corner the agent is in. Twelve candidates for ten slots,
     * with a clear gap between the tenth nearest and the eleventh so that a body settling a fraction of a block after it
     * falls cannot change the order.
     */
    private static final int[][] CROWD = {{2, 1}, {3, 1}, {4, 1}, {5, 1}, {6, 1}, {7, 1},
            {2, 3}, {2, 4}, {2, 5}, {2, 7}, {6, 7}, {7, 7}};

    /**
     * More bodies in sight than there are slots, and the nearest of them are the ones described: twelve standing about in a
     * bedrock room with nothing between them, and the two furthest go without.
     *
     * <p>Which two is worked out from where they actually are rather than from where they were put, so this says what it
     * means to say — the slots go by distance — instead of restating the arrangement.
     *
     * <p>None of the twelve is in a fight with anything, so the count the observation carries reads <b>nought</b> while all
     * ten slots are full. The two are different questions and are meant to be: the slots describe what the agent can see,
     * and {@code SELF_ENEMIES_IN_RANGE} says how many of them are actually on it.
     */
    @GameTest(template = ARENA, timeoutTicks = 100)
    public static void theNearestOfWhatIsInSightTakeTheSlots(GameTestHelper helper) {

        AgentMob agent = still(helper, new BlockPos(1, 2, 1));
        List<Mob> crowd = new ArrayList<>();

        for (int[] at : CROWD) {

            crowd.add(helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(at[0], 2, at[1])));
        }

        EnemySlots view = agent.brain().enemySlots();

        run(helper, tick -> {

            // Ten ticks in, so everyone has finished the block they fall when they are put a block above the floor.
            if (tick < 10) {

                return false;
            }

            helper.assertValueEqual(view.inRangeCount(), 0,
                    "bodies in the fight, of " + CROWD.length + " standing about that are in no fight at all");

            List<Mob> byDistance = new ArrayList<>(crowd);
            byDistance.sort(Comparator.comparingDouble(agent::distanceToSqr));

            for (int place = 0; place < byDistance.size(); place++) {

                Mob standing = byDistance.get(place);
                boolean wanted = place < ObservationSchema.ENEMY_SLOTS;

                helper.assertTrue(occupies(view, standing) == wanted, "The " + (place + 1) + "th nearest of "
                        + byDistance.size() + ", " + blocks(agent.distanceTo(standing)) + " blocks off, "
                        + (wanted ? "has no slot" : "took one of " + ObservationSchema.ENEMY_SLOTS));
            }

            return true;
        });
    }

    /**
     * A wall between the agent and a body leaves the slot reading **where the body was when the agent last saw it**, and
     * forgets it once the memory runs out. The body is moved while it is hidden, and the slot goes on saying the old place: the
     * agent remembers, and it does not see through rock.
     *
     * <p>This is the whole of the memory in one test, and it is the decision the sight rule used to make the other way round.
     * The slot used to read plainly **empty** behind a wall, on the argument that a stale position is a lie and remembering is
     * the GRU's job. That was right while the view was the full circle, because going behind something was the only way to
     * leave it. With a cone the commonest way to stop perceiving a body is that the agent turned its head, and a model where
     * looking away deletes the zombie in front of you is not a player's; so the lease and the memory are one mechanism now and
     * the slot keeps the last reading. What has not changed is the thing the sight rule was added for: the reading is where the
     * body **was**, never where it is, which is what moving it behind the wall proves.
     */
    @GameTest(template = ARENA, timeoutTicks = 300)
    public static void aWallKeepsTheLastKnownPlaceAndThenForgetsIt(GameTestHelper helper) {

        // Across the far corner of the room, six and three quarter blocks: further than the agent can hear, so sight is the
        // only thing perceiving it and the wall can take that away, and well inside the cone at twenty seven degrees. It is
        // then moved to the other corner, which is the same distance and the other side: a move the slot can only follow by
        // seeing through rock.
        AgentMob agent = still(helper, new BlockPos(4, 2, 1));
        Mob standing = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(7, 2, 7));

        // It comes for the agent, and that is what makes the count below say anything: a body the agent is aware of counts in
        // SELF_ENEMIES_IN_RANGE only while it is in the fight, so a zombie standing about would read nought whether it were
        // remembered or forgotten and the count would prove nothing about the memory. Handed back on every tick rather than
        // once, the way the order tests do it, because a mob's mind is its own.
        standing.setTarget(agent);

        EnemySlots view = agent.brain().enemySlots();
        int[] held = {-1};
        float[] seenForward = {0.0F};
        float[] seenRight = {0.0F};

        run(helper, tick -> {

            standing.setTarget(agent);

            if (tick == 5) {

                held[0] = slotOf(view, standing);

                helper.assertTrue(held[0] >= 0, "The zombie across an empty room took no slot");
                helper.assertValueEqual(view.inRangeCount(), 1, "bodies in the fight");
                helper.assertFalse(view.remembering(held[0]), "A zombie in plain sight is being remembered rather than seen");

                seenForward[0] = field(agent, held[0], ObservationSchema.ENEMY_FORWARD);
                seenRight[0] = field(agent, held[0], ObservationSchema.ENEMY_RIGHT);

                helper.assertTrue(Math.abs(seenRight[0]) > 0.05F, "The zombie is straight ahead, so moving it across would "
                        + "prove nothing: the slot reads " + seenRight[0] + " to the right");

                wall(helper, true);
                return false;
            }

            if (tick == 15) {

                // Still there, still counted, and still being read — from memory now, which is what the flag says.
                helper.assertTrue(view.occupant(held[0]) == standing, "The zombie behind a wall lost its slot at once");
                helper.assertTrue(view.remembering(held[0]), "A zombie behind a wall is being read as perceived");
                helper.assertValueEqual(view.inRangeCount(), 1, "bodies in the fight, remembered through a wall");
                helper.assertValueEqual(present(agent, held[0]), 1.0F, "the present flag of a remembered slot");
                helper.assertValueEqual(field(agent, held[0], ObservationSchema.ENEMY_FORWARD), seenForward[0],
                        "how far ahead the slot says the zombie is, a moment after the wall went up");

                // And the part that says this is a memory and not wall vision: it is moved six blocks sideways while hidden.
                standing.moveTo(helper.absolutePos(new BlockPos(1, 2, 7)).getCenter().subtract(0.0D, 0.5D, 0.0D));
                return false;
            }

            if (tick == 25) {

                helper.assertValueEqual(field(agent, held[0], ObservationSchema.ENEMY_FORWARD), seenForward[0],
                        "how far ahead the slot says the zombie is after it moved behind the wall");
                helper.assertValueEqual(field(agent, held[0], ObservationSchema.ENEMY_RIGHT), seenRight[0],
                        "how far to the side the slot says the zombie is after it moved six blocks across");

                // Said the other way round, which is the claim itself: the slot is describing a place the zombie is not in.
                helper.assertTrue(view.seenAt(held[0]).distanceTo(standing.getEyePosition()) > 4.0D,
                        "The remembered place followed the zombie, so the agent is reading it through the wall");

                // Put back where it was and shown again: the same slot, and the reading is this tick's own once more.
                standing.moveTo(helper.absolutePos(new BlockPos(7, 2, 7)).getCenter().subtract(0.0D, 0.5D, 0.0D));
                wall(helper, false);
                return false;
            }

            if (tick == 35) {

                helper.assertTrue(view.occupant(held[0]) == standing,
                        "The zombie came back to slot " + slotOf(view, standing) + " rather than the " + held[0] + " it left");
                helper.assertFalse(view.remembering(held[0]), "A zombie in plain sight again is still being remembered");

                wall(helper, true);
                return false;
            }

            // Half the memory after the wall went back up, the slot is still held and still reading.
            if (tick == 35 + ObservationSchema.MEMORY_TICKS / 2) {

                helper.assertTrue(view.occupant(held[0]) == standing, "The slot was given up inside the memory's own window");
                helper.assertValueEqual(present(agent, held[0]), 1.0F, "the present flag halfway through the memory");
                return false;
            }

            // And past it, the memory is gone: a body the agent has not perceived for three seconds is one it has lost.
            if (tick == 35 + ObservationSchema.MEMORY_TICKS + 20) {

                helper.assertTrue(view.occupant(held[0]) == null, "The slot is still held for a zombie unperceived for "
                        + (ObservationSchema.MEMORY_TICKS + 20) + " ticks");
                helper.assertValueEqual(view.inRangeCount(), 0, "bodies in the fight once the memory is out");
                helper.assertValueEqual(present(agent, held[0]), 0.0F, "the present flag once the memory is out");
                return true;
            }

            return false;
        });
    }

    /**
     * The bystanders a share of league fights stands about it: on no team, off the agent until something hits them, holding
     * slots in its view all the same, and nothing the fight is paid for.
     *
     * <p>Here rather than in the league suite because what is worth pinning is the arrangement and not the fight. A bystander
     * that ended up on the opponent's team would come for the agent and be an extra opponent nobody rated; one the episode
     * paid for would turn a crowd into a reward for farming it; and one that took no slot would make the number in a crowded
     * fight's name a number about nothing. All three would be invisible in a run's results.
     *
     * <p>What a bystander does <b>not</b> do is count: {@code SELF_ENEMIES_IN_RANGE} reads one here with three of them in the
     * view, and two the moment one is struck and comes for the agent. A body in a slot is not by itself a fight, and the field
     * that says how outnumbered the agent is has to agree with that or a crowd would tell a trained network it was dying.
     *
     * <p>The last claim is the one that had to be built rather than assumed, and this is where it is held. Three plain zombies
     * on no team, with nothing having touched them, all take the agent as their target on tick seven at six blocks — vanilla
     * looks for players and an agent is none, so that should not happen and it does. So the crowd is unprovoked every tick,
     * {@link Bystanders#leaveAlone}, and the last third of this test strikes one of them and shows that it is then free to
     * fight back, which is what passive <b>until struck</b> means.
     */
    @GameTest(template = ARENA, timeoutTicks = 120)
    public static void leagueBystandersStandAsideUntilStruck(GameTestHelper helper) {

        AgentMob agent = still(helper, new BlockPos(4, 2, 1));
        Mob opponent = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(4, 2, 3));

        // Coming for the agent, as a league opponent is on every tick of its fight: that is what the count below is counting,
        // and what tells it apart from the three that are merely standing there.
        opponent.setTarget(agent);

        agent.startEpisode(new Episode(FIGHT_TICKS, bounds(helper), List.of(opponent)));

        // Three of them with all their free will, stood in this box rather than on a fight site: what is tested is the
        // arrangement, which is the part a fight could get wrong, and not the ground.
        List<Mob> idle = new ArrayList<>();

        for (int index = 0; index < 3; index++) {

            idle.add(helper.spawn(EntityType.ZOMBIE, new BlockPos(1 + index * 3, 2, 7)));
        }

        EnemySlots view = agent.brain().enemySlots();
        Mob struck = idle.get(0);
        boolean[] hit = {false};

        run(helper, tick -> {

            // Every tick, as the league does it, and before anything is asked: a mob's own mind is not still.
            for (Mob standing : idle) {

                Bystanders.leaveAlone(standing, agent);
            }

            opponent.setTarget(agent);

            if (tick < 5) {

                return false;
            }

            for (Mob standing : idle) {

                helper.assertTrue(standing.getTeam() == null, "A bystander is on a team, so it is somebody's side");
                helper.assertFalse(agent.episode().pays(standing), "The fight pays for hurting a bystander");
                helper.assertFalse(agent.episode().opponents().contains(standing),
                        "A bystander counts as the other side of the fight");

                // And the thing they are there for: a monster is an enemy on sight whoever it is coming for, so it fills a
                // slot, which is the crowd the league had never shown the agent.
                helper.assertTrue(occupies(view, standing), "A bystander in plain sight holds no slot, so it crowds nothing");

                if (!hit[0] || standing != struck) {

                    // Not coming for this agent, which is the whole of what leaveAlone promises: it takes away a target that
                    // is this agent and leaves anything else alone. Asking for no target at all was stronger than the rule
                    // and made this test flaky — the mechanics suite's plots sit a few blocks apart, so a bystander with its
                    // wits about it can pick a neighbouring test's agent, which says nothing about the rule held here.
                    helper.assertTrue(standing.getTarget() != agent, "A bystander came for the agent unprovoked");
                    helper.assertValueEqual(targetsMe(agent, slotOf(view, standing)), 0.0F, "a bystander's targets-me flag");
                }
            }

            // Four bodies in the view and one of them in the fight, which is the whole of what the count now says: the crowd
            // fills the slots and adds nothing to the number the network reads off the self block.
            if (!hit[0]) {

                helper.assertValueEqual(view.inRangeCount(), 1,
                        "bodies in the fight, with " + idle.size() + " bystanders standing in the view");
            }

            // The opponent is still the opponent: a fight with bystanders in it is the fight it was.
            helper.assertTrue(agent.episode().pays(opponent), "The fight stopped paying for its own opponent");
            helper.assertValueEqual(agent.episode().opponents().size(), 1, "who the other side is");

            // Struck by the agent, and from then on it is allowed to fight back: nothing hands its target away again.
            if (tick == 40) {

                hit[0] = struck.hurt(helper.getLevel().damageSources().mobAttack(agent), 2.0F);
                helper.assertTrue(hit[0], "The bystander took no damage from the agent");
            }

            if (tick == 60) {

                helper.assertTrue(struck.getLastHurtByMob() == agent, "The agent's blow did not land on the bystander");
                helper.assertTrue(struck.getTarget() == agent, "A struck bystander is still being kept off the agent");

                // And the count follows it in: a bystander that fights back is in the fight, whatever the results call it.
                helper.assertValueEqual(view.inRangeCount(), 2, "bodies in the fight once a bystander has been struck");
                return true;
            }

            return false;
        });
    }

    /**
     * A crowd is drawn from the monsters that walk, and the name a crowded fight goes into the results under says which
     * opponent it was against and how many stood about it.
     */
    @GameTest(template = ARENA, timeoutTicks = 40)
    public static void aCrowdedFightIsNamedForItsOpponentAndItsCrowd(GameTestHelper helper) {

        helper.assertValueEqual(Bystanders.name("zombie", 3), "zombie+3_idle", "a crowded fight's name");
        helper.assertValueEqual(Bystanders.name("2x_zombie(hard)", 9), "2x_zombie(hard)+9_idle", "a crowded squad's name");
        helper.assertValueEqual(Bystanders.name("zombie", 0), "zombie", "a fight with no crowd");

        // Every draw asks for 1 to 9 of them or for none at all, and the fights that get a crowd come out at the share this
        // build was told, which is what a run turns the curriculum up and down by.
        RandomSource random = RandomSource.create(7L);
        int draws = 4_000;
        int crowded = 0;

        for (int draw = 0; draw < draws; draw++) {

            int wanted = Bystanders.wanted(random);

            helper.assertTrue(wanted >= 0 && wanted <= 9, "A draw asked for " + wanted + " bystanders");
            crowded += wanted > 0 ? 1 : 0;
        }

        // Asked of the share this process is running with rather than of a quarter, so a run told -PleagueBystanders=0.1 does
        // not fail a suite for doing as it was told. Four standard deviations of a binomial draw either way.
        double share = Bystanders.share();
        double expected = draws * share;
        double spread = 4.0D * Math.sqrt(Math.max(1.0D, expected * (1.0D - share)));

        helper.assertTrue(Math.abs(crowded - expected) <= spread, crowded + " of " + draws + " fights were given a crowd, "
                + "where a share of " + share + " asks for about " + Math.round(expected));

        helper.succeed();
    }

    /**
     * How big the crowd is, when there is one: weighted towards the small ones, and the big ones still drawn. One over the
     * count, so one bystander comes up nine times as often as nine, which is the curriculum's answer to a crowded win rate that
     * sat at 44% for four thousand iterations while every count above four was a fight the agent mostly died in; see
     * findings.md.
     *
     * <p>Three things are held, and they are the three a skew can get wrong. The <b>shape</b>: every count is drawn, none of
     * them more often than the one below it, and the small half takes the great majority. The <b>tail</b>: nine still comes up,
     * because a count that stops being drawn stops being rated and the run quietly loses a row it is judged on. And the
     * <b>share</b>: this draw may not have moved how many fights are crowded at all, which is the number a run was told and
     * {@code aCrowdedFightIsNamedForItsOpponentAndItsCrowd} asserts against — so the counts here are taken from crowded draws
     * only and checked against {@link Bystanders#chance}, the weights themselves rather than a second copy of them.
     */
    @GameTest(template = ARENA, timeoutTicks = 40)
    public static void aCrowdIsDrawnSmallFarMoreOftenThanLarge(GameTestHelper helper) {

        RandomSource random = RandomSource.create(11L);
        int[] counts = new int[10];
        int crowded = 0;

        // Drawn until there are enough crowds to measure, rather than for a fixed number of fights: the share is whatever this
        // build was told, and a run told a tenth would otherwise have a tenth of the sample and fail a suite for obeying.
        for (int draw = 0; draw < CROWD_DRAWS_CAP && crowded < CROWDS_WANTED; draw++) {

            int wanted = Bystanders.wanted(random);

            helper.assertTrue(wanted >= 0 && wanted <= 9, "A draw asked for " + wanted + " bystanders");

            if (wanted > 0) {

                counts[wanted]++;
                crowded++;
            }
        }

        // Nothing is asserted about a share of nought: a run told -PleagueBystanders=0 has no crowds to measure the skew of,
        // and is doing exactly as it was told.
        if (crowded == 0) {

            helper.assertValueEqual(Bystanders.share(), 0.0D, "the share, with not one crowd drawn");
            helper.succeed();

            return;
        }

        for (int standing = 1; standing <= 9; standing++) {

            // Every count, each against its own weight. Four standard deviations of a binomial draw either way, which at this
            // many crowded fights is well under a point for the rare counts and about a point for the common ones.
            double chance = Bystanders.chance(standing);
            double expected = crowded * chance;
            double spread = 4.0D * Math.sqrt(Math.max(1.0D, expected * (1.0D - chance)));

            helper.assertTrue(counts[standing] > 0, "No fight in " + crowded + " stood " + standing + " bystanders about, so "
                    + "the +" + standing + "_idle rating would never be fed");

            helper.assertTrue(Math.abs(counts[standing] - expected) <= spread, counts[standing] + " of " + crowded
                    + " crowds were " + standing + ", where a weight of " + percent(chance) + " asks for about "
                    + Math.round(expected));

            // Never more often than the count below it: the skew is monotone, which is what makes it a skew and not a bump.
            if (standing > 1) {

                helper.assertTrue(chance <= Bystanders.chance(standing - 1), "A crowd of " + standing + " is drawn more often "
                        + "than a crowd of " + (standing - 1));
            }
        }

        // And the point of the whole thing: the fights go where the rate was still moving. One to four is where the crowded win
        // rate was 68 / 58 / 48 / 46%, against 39% and under from five up.
        int small = counts[1] + counts[2] + counts[3] + counts[4];

        helper.assertTrue(small >= crowded * 2 / 3, small + " of " + crowded + " crowds were four or fewer, where the skew "
                + "means two thirds or more of them to be");

        helper.succeed();
    }

    /**
     * The other shape a real world has: several of the same mob, all of them fighting. What is drawn, how often, how many, and
     * what the fight is called.
     *
     * <p>Beside the crowd's draw because it is the same kind of thing measured the same way, and the numbers matter for the same
     * reason: a share nobody can read off a run's own results is a curriculum nobody can turn. Three claims. The <b>name</b>
     * says which mob and how many more of it, {@code zombie+3_pack} being a zombie and three more, which is what lets the
     * trainer's {@code base()} inherit the mob's kind and its cap. The <b>share</b> is whatever this build was told, asked of
     * {@link HostilePacks#share()} rather than of a tenth, so a run told otherwise is not failed for obeying. And the
     * <b>size</b> is weighted small — one over the number of extra bodies — with the tail still open, since a size that stops
     * being drawn is a row the run is judged on that quietly stops being fed.
     */
    @GameTest(template = ARENA, timeoutTicks = 40)
    public static void aHostilePackIsDrawnSmallAndNamedForTheMobItIsAPackOf(GameTestHelper helper) {

        helper.assertValueEqual(HostilePacks.name("zombie", 4), "zombie+3_pack", "a pack's name");
        helper.assertValueEqual(HostilePacks.name("zombie(hard)", 6), "zombie(hard)+5_pack", "a hard pack's name");
        helper.assertValueEqual(HostilePacks.name("zombie", 1), "zombie", "a fight against one mob");

        Roster.Member zombie = Roster.any("zombie");
        RandomSource random = RandomSource.create(23L);

        int[] sizes = new int[HOSTILE_MOST + 1];
        int packs = 0;

        // Drawn until there are enough packs to measure rather than for a fixed number of fights, for the same reason the
        // crowd's draw is: the share is whatever this build was told.
        for (int draw = 0; draw < CROWD_DRAWS_CAP && packs < PACKS_WANTED; draw++) {

            int fighting = HostilePacks.wanted(zombie, random);

            helper.assertTrue(fighting == 0 || fighting >= 2 && fighting <= HOSTILE_MOST,
                    "A draw asked for a pack of " + fighting);

            if (fighting > 0) {

                sizes[fighting]++;
                packs++;
            }
        }

        // Nothing is asserted about a share of nought: a run told -PleagueHostileCrowds=0 has no packs to measure.
        if (packs == 0) {

            helper.assertValueEqual(HostilePacks.share(), 0.0D, "the share, with not one pack drawn");
            helper.succeed();

            return;
        }

        for (int fighting = 2; fighting <= HOSTILE_MOST; fighting++) {

            double chance = HostilePacks.chance(fighting);
            double expected = packs * chance;
            double spread = 4.0D * Math.sqrt(Math.max(1.0D, expected * (1.0D - chance)));

            helper.assertTrue(sizes[fighting] > 0, "No pack in " + packs + " was " + fighting + " strong, so the +"
                    + (fighting - 1) + "_pack rating would never be fed");

            helper.assertTrue(Math.abs(sizes[fighting] - expected) <= spread, sizes[fighting] + " of " + packs
                    + " packs were " + fighting + " strong, where a weight of " + percent(chance) + " asks for about "
                    + Math.round(expected));

            // Never more often than the size below it: the skew is monotone, which is what makes it a skew and not a bump.
            if (fighting > 2) {

                helper.assertTrue(chance <= HostilePacks.chance(fighting - 1),
                        "A pack of " + fighting + " is drawn more often than a pack of " + (fighting - 1));
            }
        }

        // And the point of the weighting: the fights go where there is something to learn. Two and three are two thirds of
        // them, and six — the hardest fight in the league — is the one in eleven the tail is kept open for.
        helper.assertTrue(sizes[2] + sizes[3] >= packs * 3 / 5, sizes[2] + sizes[3] + " of " + packs + " packs were two or "
                + "three, where the skew means three fifths or more of them to be");

        helper.succeed();
    }

    /**
     * A pack is a side: all of them on one team against the agent, every one of them coming for it, every one of them paid for
     * exactly once, and every one of them in the agent's view in front of anything that is not fighting.
     *
     * <p>Here rather than in the league suite for the reason the bystanders' arrangement is: what can go wrong is the
     * arrangement and not the fight, and every way it can go wrong would be invisible in a run's results. A pack left off a
     * team would wander after each other instead of the agent. One left out of the episode would be an opponent the reward does
     * not pay for and the win condition does not wait for. One in the episode <b>twice</b> would be paid twice for the same
     * blow, which is the fault a pack of copies invites and a squad of different mobs never could. And a pack that did not fill
     * the view in front of the idle would be the crowded fight's old fault over again.
     *
     * <p>The pack is built the way a fight builds one, {@link HostilePacks#pack}, so what is held here is the thing the league
     * actually fields rather than four zombies a test arranged.
     */
    @GameTest(template = ARENA, timeoutTicks = 120)
    public static void aHostilePackAllComesForTheAgentAndIsPaidForOnce(GameTestHelper helper) {

        Opposition one = Opposition.named("zombie");

        if (one == null) {

            throw new GameTestAssertException("This build fields no zombie, so there is no pack to make of one");
        }

        Opposition pack = HostilePacks.pack(one, PACK_SIZE);

        helper.assertValueEqual(pack.name(), "zombie+" + (PACK_SIZE - 1) + "_pack", "the pack's name");
        helper.assertValueEqual(pack.mobs().size(), PACK_SIZE, "how many are on the pack");
        helper.assertValueEqual(pack.kind(), "squad", "what kind of opponent a pack is to the trainer");

        AgentMob agent = still(helper, new BlockPos(4, 2, 1));

        // The pack, in the room's own floor, and one body on no team that nothing provokes: the pack has to come in front of
        // that one, which is the crowded fight's rule and the reason a pack of copies is worth watching at all.
        List<Mob> fighting = new ArrayList<>();

        for (int on = 0; on < PACK_SIZE; on++) {

            fighting.add(helper.spawnWithNoFreeWill(pack.mobs().get(on).type(), new BlockPos(2 + on, 2, 6)));
        }

        Mob idle = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(4, 2, 2));

        List<PlayerTeam> teams = List.copyOf(Allegiance.enemy(List.of(agent), List.copyOf(fighting)));

        // Exactly as a fight does it: the episode is given every one of them and nothing else, and each is provoked every tick.
        agent.startEpisode(new Episode(FIGHT_TICKS, bounds(helper), List.copyOf(fighting)));

        EnemySlots view = agent.brain().enemySlots();

        run(helper, tick -> {

            Bystanders.leaveAlone(idle, agent);

            for (int on = 0; on < fighting.size(); on++) {

                pack.mobs().get(on).provoke(fighting.get(on), agent);
            }

            // Ten ticks in, so everyone has finished the block they fall when they are put a block above the floor.
            if (tick < 10) {

                return false;
            }

            for (Mob member : fighting) {

                helper.assertTrue(Allegiance.opposed(agent, member), "A member of the pack is not on a side against the agent");
                helper.assertTrue(member.getTeam() == fighting.get(0).getTeam(), "The pack is on more than one team, so its "
                        + "members would go after each other");
                helper.assertTrue(member.getTarget() == agent, "A member of the pack is not coming for the agent");

                helper.assertTrue(agent.episode().pays(member), "The fight does not pay for hurting a member of the pack");

                // Once, which is the fault copies invite: the same body twice in the opponents would be paid twice for one blow
                // and waited for twice by the win condition.
                helper.assertValueEqual(occurrences(agent.episode().opponents(), member), 1,
                        "how many times a member of the pack is on the other side");

                helper.assertTrue(occupies(view, member), "A member of the pack in plain sight holds no slot");
            }

            helper.assertValueEqual(agent.episode().opponents().size(), PACK_SIZE, "how many the other side is");

            // The pack, and not the body standing beside them: the count is of the fight and the slots are of the view.
            helper.assertValueEqual(view.inRangeCount(), PACK_SIZE, "bodies in the fight, with one idle body in the view too");

            // The whole side in front of the body that is not fighting, however near that one stands: it is two blocks off and
            // the pack is five, so distance alone would have put it first.
            for (Mob member : fighting) {

                helper.assertTrue(slotOf(view, member) < slotOf(view, idle), "A body on no team took a slot in front of the "
                        + "pack the fight is against");
            }

            // Teams outlive the entities on them and are saved with the world. Taken down on the last tick rather than in a
            // finally: run only schedules the ticks and returns at once.
            teams.forEach(Allegiance::disband);

            return true;
        });
    }

    /**
     * A jockey: one mob riding another, which the game spawns on its own and which the league fields as two bodies on one
     * side. What is held is the arrangement and what winning means, because both are invisible in a run's results and both
     * are easy to get wrong for a mount.
     *
     * <p><b>The rider is really on its mount</b> — two bodies standing a block apart would fight and win and look the same in
     * every column the league prints. <b>Both are on the other side</b>, each paid for exactly once and each in the agent's
     * view, since the skeleton on top is the half that shoots and a fight that ended when the spider died would pay nothing
     * for the archer and call it a win. And <b>killing the mount is not winning</b>: the rider comes off it alive, still an
     * opponent, still paid for, so the fight goes on until it is down too.
     *
     * <p>The room and the clock are the rider's as well, which follows from a side taking whatever the mob on it that wants
     * most asks for: a spider jockey is a shooting match at twenty blocks, not a spider's minute at eight.
     */
    @GameTest(template = ARENA, timeoutTicks = 120)
    public static void aJockeyRidesItsMountAndIsNotBeatenUntilBothAreDown(GameTestHelper helper) {

        Opposition jockey = Opposition.named("spider_jockey");

        if (jockey == null) {

            throw new GameTestAssertException("This build fields no spider jockey");
        }

        helper.assertValueEqual(jockey.mobs().size(), 2, "how many bodies a spider jockey is");
        helper.assertValueEqual(jockey.kind(), "squad", "what kind of opponent a jockey is to the trainer");
        helper.assertValueEqual(jockey.ticks(), Opposition.named("skeleton").ticks(), "a spider jockey's clock");
        helper.assertValueEqual(jockey.start(), Opposition.named("skeleton").start(), "how far off a spider jockey starts");

        // The other jockey is a melee fight, and its chicken is not a player of the league at all: a mount the roster fields
        // on its own would take a rating for a body that has no attack of any kind.
        Opposition chickenJockey = Opposition.named("chicken_jockey");

        helper.assertTrue(chickenJockey != null && chickenJockey.mobs().size() == 2, "A chicken jockey is not two bodies");
        helper.assertValueEqual(chickenJockey.ticks(), Roster.MELEE_TICKS, "a chicken jockey's clock");
        helper.assertTrue(Opposition.named("chicken") == null, "The league fields a chicken as an opponent of its own");

        AgentMob agent = still(helper, new BlockPos(4, 2, 1));
        List<Mob> side = new ArrayList<>();

        for (int on = 0; on < jockey.mobs().size(); on++) {

            side.add(helper.spawnWithNoFreeWill(jockey.mobs().get(on).type(), new BlockPos(2 + on * 2, 2, 6)));
        }

        // Exactly as a fight does it: the bodies go in first and the rider is put on its mount afterwards, since startRiding
        // wants both of them in the world.
        jockey.mount(List.copyOf(side));

        Mob mount = side.get(0);
        Mob rider = side.get(1);

        List<PlayerTeam> teams = List.copyOf(Allegiance.enemy(List.of(agent), List.copyOf(side)));

        agent.startEpisode(new Episode(FIGHT_TICKS, bounds(helper), List.copyOf(side)));

        EnemySlots view = agent.brain().enemySlots();

        run(helper, tick -> {

            // Ten ticks in, so both have finished the block they fall when they are put a block above the floor.
            if (tick < 10) {

                return false;
            }

            if (tick == 10) {

                helper.assertTrue(rider.getVehicle() == mount, "The rider is not on its mount");
                helper.assertTrue(mount.getPassengers().contains(rider), "The mount is not carrying its rider");

                for (Mob body : side) {

                    helper.assertTrue(agent.episode().pays(body), "The fight does not pay for hurting a jockey's " + body.getName().getString());
                    helper.assertValueEqual(occurrences(agent.episode().opponents(), body), 1,
                            "how many times a jockey's " + body.getName().getString() + " is on the other side");
                    helper.assertTrue(occupies(view, body), "A jockey's " + body.getName().getString() + " holds no slot");
                }

                helper.assertValueEqual(agent.episode().opponents().size(), 2, "how many the other side is");

                // And the claim the mount exists for: taking the mount down leaves the fight unwon.
                mount.kill();

                return false;
            }

            // A body that has just died is dead but not gone: vanilla keeps it for its twenty ticks of death animation and
            // only ejects what is riding it when it is finally removed. So the rider comes off a little after the mount dies,
            // not on the tick it does.
            if (tick < 40) {

                return false;
            }

            helper.assertTrue(!mount.isAlive(), "The mount was not killed");
            helper.assertTrue(rider.isAlive(), "Killing the mount killed the rider with it, so a jockey would be one body");
            helper.assertTrue(rider.getVehicle() == null, "The rider is still riding a mount that is dead");
            helper.assertTrue(agent.episode().pays(rider), "The fight stopped paying for the rider when its mount died");

            teams.forEach(Allegiance::disband);

            return true;
        });
    }

    /**
     * A slime that dies leaves two to four copies of itself standing, and they are the fight too.
     *
     * <p>This is the test that measured what the league used to do, and its failure message is the measurement: on the build
     * before {@link Splits} the other side was still the one body the fight spawned, so the tick the big slime died every
     * opponent was dead and the arena wrote the fight down as a <b>win</b> — with two to four middling slimes standing on
     * ground the agent had never touched, each of which would have split again. Nothing said so in a run's results, because
     * the children carried no fight tag and the sweep for wildlife took them within the second.
     *
     * <p>Three claims, and they are the three halves of taking a body into a fight. The <b>split happens at all</b> and is
     * more than one body, so there is something to take. Taking them <b>pays for each exactly once</b> and puts each in the
     * agent's view, which is what makes killing them worth doing. And the <b>fight is not won</b> while they stand: the other
     * side is no longer all dead, which is the condition {@code AgentLeagueGameTest} waits on.
     *
     * <p>Built the way a fight builds it — {@link Splits#taken} and {@link Episode#join}, the two calls the arena's own
     * {@code adopt} makes — so what is held is what the league actually fields.
     */
    @GameTest(template = ARENA, timeoutTicks = 200)
    public static void aSlimeThatSplitsLeavesTheFightUnwon(GameTestHelper helper) {

        Opposition big = Opposition.named("slime");

        if (big == null) {

            throw new GameTestAssertException("This build fields no slime, so there is nothing to split");
        }

        helper.assertTrue(Splits.splitting(big) != null, "A slime is not a mob the fight expects to split");
        helper.assertTrue(Splits.splitting(Opposition.named("zombie")) == null, "A zombie is expected to split");

        AgentMob agent = still(helper, new BlockPos(4, 2, 1));
        Mob slime = helper.spawnWithNoFreeWill(big.mobs().get(0).type(), new BlockPos(4, 2, 6));

        // Its own preparation, so it is the biggest a natural one comes, which is the one the league fields.
        big.mobs().get(0).prepare(slime, helper.getLevel().getRandom());
        slime.addTag(TerrainSites.TAG);

        agent.startEpisode(new Episode(FIGHT_TICKS, bounds(helper), List.of(slime)));

        EnemySlots view = agent.brain().enemySlots();

        // What the split leaves, the tick it was taken in, and whether the fight would have been called won that tick.
        List<Slime> left = new ArrayList<>();
        int[] taken = {-1};
        boolean[] everyoneDead = {false};

        run(helper, tick -> {

            if (tick < 10) {

                return false;
            }

            if (tick == 10) {

                slime.kill();
                return false;
            }

            if (taken[0] < 0) {

                left.addAll(Splits.taken(helper.getLevel(), bounds(helper), List.of(slime.position()), SPLIT_NEAR));

                // A slime's children are made in Slime#remove, which is the end of the twenty ticks of death animation and
                // not the tick its health reached nought. That gap is the whole reason the arena cannot call a fight over as
                // soon as the last body stops being alive; see AgentLeagueGameTest#beaten.
                if (left.isEmpty()) {

                    helper.assertTrue(tick < SPLIT_WAIT_TICKS, "A big slime left nothing behind in " + tick + " ticks");

                    return false;
                }

                // Asked before they are taken in, because this is the measurement: on the build before Splits the fight's
                // other side was still the one body it spawned, so every opponent was dead and the arena wrote down a win.
                everyoneDead[0] = agent.episode().opponents().stream().noneMatch(LivingEntity::isAlive);
                taken[0] = tick;

                Constants.LOG.info("A big slime killed at tick 10 left {} bodies behind by tick {}; the fight it was the "
                        + "whole of had {} on the other side, every one of them dead: {}", left.size(), tick,
                        agent.episode().opponents().size(), everyoneDead[0]);

                for (Slime child : left) {

                    agent.episode().join(child);
                }

                return false;
            }

            // A few ticks on, so the agent has had a tick of its own to take them into its view: the slots are worked out
            // when the agent is stepped, and a body that appeared this tick has not been looked at yet.
            if (tick < taken[0] + SPLIT_SETTLE_TICKS) {

                return false;
            }

            helper.assertTrue(!slime.isAlive(), "The slime the fight spawned was not killed");
            helper.assertTrue(left.size() >= 2, "A big slime left " + left.size() + " bodies behind, where vanilla leaves "
                    + "two to four; there is nothing here to take into the fight");
            helper.assertTrue(everyoneDead[0], "The fight's own other side was not all dead when the slime split, so this "
                    + "says nothing about what the league used to do with one");

            for (Slime child : left) {

                helper.assertTrue(agent.episode().pays(child), "The fight does not pay for hurting what the slime left");
                helper.assertValueEqual(occurrences(agent.episode().opponents(), child), 1,
                        "how many times one of the slime's children is on the other side");
                helper.assertTrue(child.getTags().contains(TerrainSites.TAG), "A child of the fight's slime carries no "
                        + "fight tag, so the sweep for wildlife would take it away mid fight");
                helper.assertTrue(occupies(view, child), "A child of the fight's slime, " + blocks(Math.sqrt(agent.distanceToSqr(child)))
                        + " blocks off and in plain sight, holds no slot");
            }

            // Taken once and not again: a second look finds nothing, since the first tagged them.
            helper.assertValueEqual(Splits.taken(helper.getLevel(), bounds(helper), List.of(slime.position()), SPLIT_NEAR).size(),
                    0, "how many more children a second look finds");

            // And what the second half of the rule is for: a slime standing where nothing of the fight fell is not the
            // fight's, however untagged it is. A crowd of bystanders can hold slimes, and one the agent has struck and
            // killed leaves children exactly as an opponent does.
            Mob stranger = helper.spawnWithNoFreeWill(EntityType.SLIME, new BlockPos(8, 2, 8));

            helper.assertValueEqual(Splits.taken(helper.getLevel(), bounds(helper), List.of(slime.position()), SPLIT_NEAR).size(),
                    0, "how many bodies a look takes from where nothing of the fight fell");
            helper.assertTrue(!stranger.getTags().contains(TerrainSites.TAG), "A slime nothing of the fight left behind was "
                    + "taken into it");

            stranger.discard();

            helper.assertValueEqual(agent.episode().opponents().size(), left.size() + 1, "how many the other side is now");

            // And the point of the whole thing: the fight is not won, because not everything on the other side is dead.
            helper.assertTrue(agent.episode().opponents().stream().anyMatch(LivingEntity::isAlive),
                    "The fight counts as beaten with " + left.size() + " of the slime still standing");

            return true;
        });
    }

    // ---------------------------------------------------------------------------------------------------------------

    /** How many of one thing are in a list, for the claim that a pack member is on the other side exactly once. */
    private static int occurrences(List<? extends Entity> among, Entity one) {

        int found = 0;

        for (Entity each : among) {

            found += each == one ? 1 : 0;
        }

        return found;
    }

    /** A wall of bedrock across the middle of the room, or the air it was built out of. */
    private static void wall(GameTestHelper helper, boolean up) {

        for (int x = 1; x <= 7; x++) {

            for (int y = 1; y <= 7; y++) {

                helper.setBlock(new BlockPos(x, y, 4), up ? Blocks.BEDROCK : Blocks.AIR);
            }
        }
    }

    /** The present flag of one slot, read out of the observation the network is handed rather than off the slots. */
    private static float present(AgentMob agent, int slot) {

        return field(agent, slot, ObservationSchema.ENEMY_PRESENT);
    }

    /** Whether the slot's occupant has come for the agent, as the observation says it. */
    private static float targetsMe(AgentMob agent, int slot) {

        return field(agent, slot, ObservationSchema.ENEMY_TARGETS_ME);
    }

    private static float field(AgentMob agent, int slot, int offset) {

        float[] observation = new float[ObservationSchema.OBS_DIM];
        AgentObservation.write(agent, agent.brain().enemySlots(), observation, 0);

        return observation[ObservationSchema.enemyOffset(slot) + offset];
    }

    private static boolean occupies(EnemySlots view, LivingEntity entity) {

        return slotOf(view, entity) >= 0;
    }

    /** Which slot is reading it, or -1 for none. */
    private static int slotOf(EnemySlots view, Entity entity) {

        for (int slot = 0; slot < ObservationSchema.ENEMY_SLOTS; slot++) {

            if (view.occupant(slot) == entity) {

                return slot;
            }
        }

        return -1;
    }

    private static String blocks(double distance) {

        return String.format(java.util.Locale.ROOT, "%.2f", distance);
    }

    /** A weight as a share, for the message a failed draw leaves behind. */
    private static String percent(double chance) {

        return String.format(java.util.Locale.ROOT, "%.1f%%", chance * 100.0D);
    }

    /**
     * A training agent that presses nothing, so the only thing moving in these tests is the wall. It is a training agent
     * because one is driven whatever is in its view, and given the plot's own bounds because the mechanics suite's plots sit
     * close enough together that thirty two blocks reaches into the neighbours.
     */
    private static AgentMob still(GameTestHelper helper, BlockPos feet) {

        AgentMob agent = helper.spawn(ModEntities.trainingAgent(), feet);

        agent.setYRot(0.0F);
        agent.setYHeadRot(0.0F);
        agent.setYBodyRot(0.0F);
        agent.setXRot(0.0F);
        agent.startEpisode(new Episode(FIGHT_TICKS, bounds(helper), List.of()));
        agent.brain().use(STILL);

        return agent;
    }

    /** The plot this test owns, with a little slack: the box an agent in it is allowed to see into. */
    private static AABB bounds(GameTestHelper helper) {

        return new AABB(Vec3.atLowerCornerOf(helper.absolutePos(BlockPos.ZERO)),
                Vec3.atLowerCornerOf(helper.absolutePos(new BlockPos(9, 9, 9)))).inflate(1.0D);
    }

    private static void run(GameTestHelper helper, IntPredicate step) {

        TestTicks.run(helper, step);
    }

    /** A brain that presses nothing at all, so the agent stands where it was put and only looks. */
    private static final Brain STILL = new Brain() {

        @Override
        public Species species() {

            return Species.HUMANOID;
        }

        @Override
        public void act(BrainStep step) {

            java.util.Arrays.fill(step.actions, 0, step.count * ActionSchema.ACT_DIM, 0.0F);
        }
    };
}
