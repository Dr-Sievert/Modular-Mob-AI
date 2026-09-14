package net.sievert.modularmobai.gametest.tests;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.sievert.modularmobai.allegiance.Allegiance;
import net.sievert.modularmobai.arena.Episode;
import net.sievert.modularmobai.brain.Brain;
import net.sievert.modularmobai.brain.BrainStep;
import net.sievert.modularmobai.brain.schema.ActionSchema;
import net.sievert.modularmobai.brain.schema.EnemySlots;
import net.sievert.modularmobai.brain.schema.ObservationSchema;
import net.sievert.modularmobai.brain.schema.Species;
import net.sievert.modularmobai.entity.ModEntities;
import net.sievert.modularmobai.entity.agent.AgentMob;
import net.sievert.modularmobai.gametest.GameTestGroup;
import net.sievert.modularmobai.gametest.league.Bystanders;
import net.sievert.modularmobai.gametest.util.TestTicks;

/**
 * Which slot each body gets: the fight first, then the nearest.
 *
 * <p>Held as a rule of its own because it is what a whole curriculum turned on. A slot used to go out in the order the
 * level's own walk over its entity sections returned bodies, which is section x ascending; against one opponent that is no
 * order at all, so every fight a network had ever been trained on gave its opponent slot 0, and a network learns that. Stand
 * a crowd of idle monsters round the same fight and the opponent took slot 0 only when it happened to be the westernmost
 * body there — measured on a real fight with nine standing about, it took slot 0 on none of the ticks and sat in slot 5.5 on
 * average, the aim was 87 degrees off it, and 206 of 207 presses of attack went into thin air. The numbers are in
 * findings.md and the harness that took them is {@link AgentCrowdedFightGameTest}; this is the rule that came out of them.
 *
 * <p>Everything here is done with bodies that never move, in the plot's own bedrock box, because what is being pinned is an
 * order and not a fight. The ranks are arranged against the old order deliberately: the body that should win is always the
 * one the walk would have returned last, so a test that passed by accident would have to have been arranged for.
 */
@GameTestGroup
public class AgentEnemyOrderGameTest {

    private static final String ARENA = "arena";

    /** Long enough for every reading here; nothing in this class waits on anything. */
    private static final int FIGHT_TICKS = 600;

    /**
     * The one that has come for the agent takes slot 0 however far off it is, and the idle bodies standing nearer take the
     * slots after it in the order of their distance.
     *
     * <p>This is the crowded league fight in miniature: one opponent and a crowd on no team, unprovoked every tick exactly as
     * {@link Bystanders#leaveAlone} keeps them. The opponent is put furthest away and last in the x order, which is where the
     * old rule would have put it last of all, so the assertion cannot be passing by where anything happens to stand.
     */
    @GameTest(template = ARENA, timeoutTicks = 100)
    public static void theOneFightingTheAgentTakesTheFirstSlot(GameTestHelper helper) {

        AgentMob agent = still(helper, new BlockPos(1, 2, 1));

        // On no team and never provoked: bystanders, and nearer than the opponent, at 2, 4 and 6 blocks.
        List<Mob> idle = new ArrayList<>();

        for (int at = 3; at <= 7; at += 2) {

            idle.add(helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(at, 2, 1)));
        }

        // The opponent: furthest of the four and last in every order the world has, and the only one fighting the agent. The
        // far corner of the room's own floor, which is 1 to 7 — the plot's walls stand on 0 and 8, and a body put in one is a
        // body the agent cannot see.
        Mob opponent = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(7, 2, 7));
        opponent.setTarget(agent);

        EnemySlots view = agent.brain().enemySlots();

        run(helper, tick -> {

            // As the league does it, every tick and before anything is asked, so the crowd stays a crowd.
            for (Mob standing : idle) {

                Bystanders.leaveAlone(standing, agent);
            }

            opponent.setTarget(agent);

            // Ten ticks in, so everyone has finished the block they fall when they are put a block above the floor.
            if (tick < 10) {

                return false;
            }

            // One of the four in the view is in this fight, and that is the whole of what the count says now: the crowd
            // takes slots and adds nothing to the number the network reads off its own block.
            helper.assertValueEqual(view.inRangeCount(), 1,
                    "bodies in the fight, with " + idle.size() + " idle ones in the view");

            helper.assertTrue(Allegiance.goesFor(opponent, agent), "The opponent is not being counted as coming for the agent");
            helper.assertValueEqual(slotOf(view, opponent), 0, "the slot the one fighting the agent holds");

            // And the rest by distance behind it, which is what the slots always claimed and only eviction ever did.
            List<Mob> byDistance = new ArrayList<>(idle);
            byDistance.sort(java.util.Comparator.comparingDouble(agent::distanceToSqr));

            for (int place = 0; place < byDistance.size(); place++) {

                helper.assertValueEqual(slotOf(view, byDistance.get(place)), place + 1,
                        "the slot of the " + (place + 1) + "th nearest of the idle bodies");
            }

            return true;
        });
    }

    /**
     * A whole side takes the slots in front of anything idle, and among the side the nearest comes first: the squad fight,
     * where nobody has to have picked the agent yet because being on the other team is what says whose fight it is.
     *
     * <p>The team half of the rule is the half that is easy to leave out and impossible to notice missing. A squad member
     * whose path is blocked, and one whose own mind has let the agent go for a tick, are both still the other side; without
     * the team reading they would drop behind the crowd on exactly those ticks and the slots would shuffle under the network
     * mid fight.
     */
    @GameTest(template = ARENA, timeoutTicks = 100)
    public static void aWholeSideComesBeforeTheCrowdAndTheNearestOfItFirst(GameTestHelper helper) {

        AgentMob agent = still(helper, new BlockPos(1, 2, 1));

        List<Mob> idle = new ArrayList<>();

        for (int at = 3; at <= 5; at += 2) {

            idle.add(helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(at, 2, 1)));
        }

        // The side: further off than the crowd and, again, last in the world's own order. Their targets are taken away on
        // every tick below, so the only thing that can be making them the fight is the team.
        Mob far = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(7, 2, 7));
        Mob near = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(6, 2, 6));

        List<PlayerTeam> teams = List.copyOf(Allegiance.enemy(List.of(agent), List.of(far, near)));

        EnemySlots view = agent.brain().enemySlots();

        run(helper, tick -> {

            for (Mob standing : idle) {

                Bystanders.leaveAlone(standing, agent);
            }

            // The side's own targets are taken away every tick, the way a bystander's are, so that nothing but the team can
            // be putting it in front of the crowd. Held rather than asserted, because a mob's mind is its own: asking it to
            // have no target and failing the test when it does would be a test of vanilla's judgement and not of the order.
            far.setTarget(null);
            near.setTarget(null);

            if (tick < 10) {

                return false;
            }

            helper.assertTrue(Allegiance.opposed(agent, far), "The side is not on a team set against the agent's");
            helper.assertFalse(Allegiance.goesFor(far, agent),
                    "The side is coming for the agent, so the team is no longer the only thing putting it first");

            helper.assertValueEqual(slotOf(view, near), 0, "the slot of the nearer of the other side");
            helper.assertValueEqual(slotOf(view, far), 1, "the slot of the further of the other side");

            for (Mob standing : idle) {

                helper.assertTrue(slotOf(view, standing) >= 2,
                        "An idle body took a slot in front of the side the fight is against");
            }

            // Teams outlive the entities on them and are saved with the world, so a suite that left them behind would end
            // with a scoreboard full of them. Taken down on the last tick and not in a finally: run only schedules the
            // ticks and returns at once, so a finally here would disband the teams before the first one ran.
            teams.forEach(Allegiance::disband);

            return true;
        });
    }

    /**
     * With more bodies in sight than there are slots, one that is fighting the agent takes an idle body's slot rather than
     * being shut out of the view.
     *
     * <p>The one path the order cannot reach. The order decides who gets a free slot; when a crowd got there first there are
     * none, and eviction used to give up only a body further off than the newcomer — so a fight walking up to a crowd that
     * had every slot could never be seen at all, however near it came, because every occupant was closer. A squad of three
     * with nine standing about it is twelve bodies for ten slots, so this is the league and not a hypothetical.
     */
    @GameTest(template = ARENA, timeoutTicks = 120)
    public static void aFightTakesAnIdleSlotWhenThereAreNoneLeft(GameTestHelper helper) {

        AgentMob agent = still(helper, new BlockPos(1, 2, 1));

        // Every slot filled by bodies on no team that nothing has provoked, all of them nearer than the opponent will be, and
        // all of them on the room's own floor, which is 1 to 7: the plot's walls stand on 0 and 8.
        List<Mob> idle = new ArrayList<>();

        for (int at = 0; at < ObservationSchema.ENEMY_SLOTS; at++) {

            idle.add(helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(2 + at % 5, 2, 2 + at / 5)));
        }

        Mob opponent = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(7, 2, 7));

        run(helper, tick -> {

            for (Mob standing : idle) {

                Bystanders.leaveAlone(standing, agent);
            }

            // Ten ticks in, so everyone has finished the block they fall when they are put a block above the floor.
            if (tick < 10) {

                return false;
            }

            // First that the crowd really has every slot, which is what makes the reading after it mean anything: without
            // this the test could pass on a slot that was free all along.
            if (tick == 10) {

                helper.assertValueEqual(view(agent).inRangeCount(), 0,
                        "bodies in the fight while every slot is held by a body that is in none");

                for (Mob standing : idle) {

                    helper.assertTrue(slotOf(view(agent), standing) >= 0,
                            "An idle body in plain sight holds no slot, so the crowd is not filling the view");
                }

                helper.assertValueEqual(slotOf(view(agent), opponent), -1,
                        "the slot of an idle body that arrived after every slot was taken");

                // And now it is the fight, which is the only thing about it that changes.
                opponent.setTarget(agent);
                return false;
            }

            // A tick later, because the slots are worked out when the agent is driven and not when a test presses a button:
            // asking on the same tick would be asking before the view had seen the change.
            opponent.setTarget(agent);

            helper.assertTrue(slotOf(view(agent), opponent) >= 0,
                    "A body fighting the agent could not get into a view full of bodies that are not");

            return true;
        });
    }

    /**
     * A body that becomes the fight after it already holds a slot is moved to the front of the view, and the bodies it passes
     * keep their own order behind it.
     *
     * <p>This is the half of the order the first rule cannot reach, and a real game is where it came from. Handing slots out
     * by the order settles nothing when <b>nobody has engaged yet</b>: a crowd of monsters that has not noticed the agent is
     * all ranked the same, so the slots go out nearest first, and a lease then keeps each of them where it landed. Reported
     * from a creative world: an agent spawned into six zombies already standing there. The moment one of them came for it that
     * body was the fight and it was sitting in slot 3, where it stayed for the whole fight, because the assignment loop only
     * ever ranks a body holding no slot. So the network's one reliable habit — slot 0 is the fight — was being contradicted
     * again, by the other door.
     *
     * <p>Arranged so that nothing but the promotion can pass the test: the body that engages is put <b>furthest</b> away, so it
     * takes the last slot of the five on distance alone, and every other body stays exactly where it is and keeps its target
     * taken away. A view that only ranked newcomers would leave it in slot 4 for ever.
     */
    @GameTest(template = ARENA, timeoutTicks = 100)
    public static void engagingAfterTakingASlotMovesTheFightToTheFront(GameTestHelper helper) {

        AgentMob agent = still(helper, new BlockPos(4, 2, 1));

        // Four idle bodies in front of the agent at about 1, 2, 3 and 4 blocks, and the one that will engage six blocks out:
        // the last slot of the five by distance, which is the only thing ordering them while none of them is fighting.
        List<Mob> idle = new ArrayList<>();

        for (int[] at : new int[][] {{4, 2}, {3, 3}, {5, 4}, {3, 5}}) {

            idle.add(helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(at[0], 2, at[1])));
        }

        Mob engages = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(4, 2, 7));

        EnemySlots view = agent.brain().enemySlots();
        int[] held = {-1};

        run(helper, tick -> {

            for (Mob standing : idle) {

                Bystanders.leaveAlone(standing, agent);
            }

            // Ten ticks in, so everyone has finished the block they fall when they are put a block above the floor.
            if (tick < 10) {

                return false;
            }

            // Nobody is in this fight yet, so the slots are the distances: the one that will engage is last of the five.
            if (tick == 10) {

                helper.assertValueEqual(view.inRangeCount(), 0,
                        "bodies in the fight while nobody in the crowd is fighting");

                held[0] = slotOf(view, engages);

                helper.assertValueEqual(held[0], idle.size(), "the slot of the furthest of a crowd that is not fighting");

                for (int place = 0; place < idle.size(); place++) {

                    helper.assertValueEqual(slotOf(view, idle.get(place)), place,
                            "the slot of the " + (place + 1) + "th nearest while nobody is fighting");
                }

                // And now the only thing about it that changes.
                engages.setTarget(agent);
                return false;
            }

            // A tick later, because the slots are worked out when the agent is driven and not when a test presses a button.
            engages.setTarget(agent);

            helper.assertValueEqual(slotOf(view, engages), 0, "the slot of the body that engaged while holding slot " + held[0]);

            // The bodies it passed keep their own order, one slot further back each: only engagement moves a slot, so the
            // rest of the view is the view it was rather than a fresh sort by distance.
            for (int place = 0; place < idle.size(); place++) {

                helper.assertValueEqual(slotOf(view, idle.get(place)), place + 1,
                        "the slot of the " + (place + 1) + "th nearest once the fight came to the front");
            }

            return true;
        });
    }

    // ---------------------------------------------------------------------------------------------------------------

    private static EnemySlots view(AgentMob agent) {

        return agent.brain().enemySlots();
    }

    /** Which slot is reading that body, or -1 when none is: an empty slot, and one held through cover, both answer null. */
    private static int slotOf(EnemySlots view, Entity body) {

        for (int slot = 0; slot < ObservationSchema.ENEMY_SLOTS; slot++) {

            if (view.occupant(slot) == body) {

                return slot;
            }
        }

        return -1;
    }

    /**
     * A training agent that presses nothing, so the only thing deciding a slot here is where a body stands and whose fight it
     * is. Given the plot's own bounds because the mechanics suite's plots sit close enough together that thirty two blocks
     * reaches into the neighbours.
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
