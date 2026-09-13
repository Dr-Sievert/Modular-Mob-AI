package net.sievert.modularmobai.gametest.tests;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntPredicate;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
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

            helper.assertValueEqual(view.inRangeCount(), CROWD.length, "bodies in sight");

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
     * A wall between the agent and a body takes the reading away and leaves the slot: while it cannot be seen its slot is
     * empty and it is not counted, and the moment the wall comes down it is back in the slot it had.
     *
     * <p>This is the whole of the decision that came with the sight rule, in one test. The body never moves, so the only
     * thing that changes between one reading and the next is the block in the way — distance, side and everything else are
     * held still. What the agent may not do is read a position through rock: that was the fault. What it may not be told
     * either is a position from two seconds ago, so the slot reads plainly empty rather than stale, and remembering is left
     * to the GRU, which is what a recurrence is for. The lease is kept all the same, for the grace, so an opponent that
     * steps behind a tree comes back to the slot it left instead of reshuffling the view; once the grace is out, the lease
     * goes with it.
     */
    @GameTest(template = ARENA, timeoutTicks = 200)
    public static void aWallTakesTheReadingAndLeavesTheSlot(GameTestHelper helper) {

        AgentMob agent = still(helper, new BlockPos(4, 2, 1));
        Mob standing = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(4, 2, 7));

        EnemySlots view = agent.brain().enemySlots();
        int[] held = {-1};

        run(helper, tick -> {

            if (tick == 5) {

                held[0] = slotOf(view, standing);

                helper.assertTrue(held[0] >= 0, "The zombie across an empty room took no slot");
                helper.assertValueEqual(view.inRangeCount(), 1, "bodies in sight");

                wall(helper, true);
                return false;
            }

            if (tick == 15) {

                helper.assertValueEqual(view.inRangeCount(), 0, "bodies in sight through a wall");
                helper.assertTrue(view.occupant(held[0]) == null, "A zombie behind a wall is still being read");
                helper.assertTrue(view.leaseholder(held[0]) == standing, "The zombie lost the slot it will come back to");
                helper.assertValueEqual(present(agent, held[0]), 0.0F, "the present flag of a slot behind a wall");

                wall(helper, false);
                return false;
            }

            if (tick == 25) {

                helper.assertTrue(view.occupant(held[0]) == standing,
                        "The zombie came back to slot " + slotOf(view, standing) + " rather than the " + held[0] + " it left");
                helper.assertValueEqual(present(agent, held[0]), 1.0F, "the present flag once the wall is down");

                wall(helper, true);
                return false;
            }

            // Half the grace after the wall went back up, the slot is still being kept.
            if (tick == 45) {

                helper.assertTrue(view.leaseholder(held[0]) == standing, "The slot was given up inside its own grace");
                return false;
            }

            // And a good way past it, the lease is gone: a body nothing has seen for two seconds is not being waited for.
            if (tick == 25 + ObservationSchema.LEASE_GRACE_TICKS + 20) {

                helper.assertTrue(view.leaseholder(held[0]) == null, "The slot is still held for a zombie out of sight for "
                        + (ObservationSchema.LEASE_GRACE_TICKS + 20) + " ticks");
                return true;
            }

            return false;
        });
    }

    // ---------------------------------------------------------------------------------------------------------------

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

        float[] observation = new float[ObservationSchema.OBS_DIM];
        AgentObservation.write(agent, agent.brain().enemySlots(), observation, 0);

        return observation[ObservationSchema.enemyOffset(slot) + ObservationSchema.ENEMY_PRESENT];
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
