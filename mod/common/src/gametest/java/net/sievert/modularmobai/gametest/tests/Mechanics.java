package net.sievert.modularmobai.gametest.tests;

import java.util.function.IntPredicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.sievert.modularmobai.arena.AgentReward;
import net.sievert.modularmobai.arena.Episode;
import net.sievert.modularmobai.brain.schema.AgentObservation;
import net.sievert.modularmobai.brain.schema.ObservationSchema;
import net.sievert.modularmobai.entity.ModEntities;
import net.sievert.modularmobai.entity.agent.AgentMob;
import net.sievert.modularmobai.gametest.util.HeldControls;
import net.sievert.modularmobai.gametest.util.TestTicks;

/**
 * What every class of the mechanics suite shares: the arena it runs in, the agent it drives, and the handful of readings
 * more than one of them takes.
 *
 * <p>The suite is the agent's body against a player's rules, a rule or two to a test, split by what each class is about:
 * {@link AgentDrawnWeaponGameTest} a bow and a crossbow, {@link AgentMeleeGameTest} a blow and a block,
 * {@link AgentBlockGameTest} breaking and placing, {@link AgentPerceptionGameTest} what the agent sees of all of it,
 * {@link AgentTeacherGameTest} the scripted fighter's own rules, and {@link FightSetupGameTest} the three that are not about
 * the body at all. It was one class of forty tests and two and a half thousand lines.
 *
 * <p>Nothing in any of them is a fight. Every agent is driven by {@link HeldControls}, which hands it back whatever the
 * test pressed on its controls, out through the action vector and back in as a network's actions come, so each rule is
 * reached the way a brain would reach it. The numbers asserted are a player's, worked out from vanilla's own code, and a few
 * of them differ from the ones usually quoted: stone takes a player 151 ticks by hand, not 150, because the client adds the
 * work up a tick at a time in floats.
 *
 * <p>Each test is a script, a step for every tick, that runs in the arena's closed box: a bedrock floor at height one, room
 * from two to eight, and walls round an inside seven blocks across, from one to seven.
 *
 * <p>Run with {@code -Psuite=mechanics}, see {@link net.sievert.modularmobai.gametest.GameTestTuning#suite()}.
 */
final class Mechanics {

    private Mechanics() {}

    static final String ARENA = "arena";

    /**
     * How long a fight may run, for the episodes the reward is checked against: the game's own default, so a test that
     * reads the clock or the critic's limit column is reading the number a training fight is given.
     */
    static final int FIGHT_TICKS = AgentReward.DEFAULT_MAX_TICKS;

    /**
     * How long a freshly spawned body is given to come to rest before anything is pressed. It is not on the ground until
     * it has moved once, and a block is broken five times slower by anyone who is not.
     */
    static final int SETTLE = 5;

    /** Where a block is broken from: an agent's eyes are level with it, half a block off. */
    static final BlockPos MINER = new BlockPos(4, 2, 2);
    static final BlockPos AT_EYE_LEVEL = new BlockPos(4, 3, 3);

    /**
     * The episode holds exactly what the opponent's lost health is worth, which is what it holds when every hit is paid
     * once and nothing else is paid at all.
     */
    static void paidExactly(GameTestHelper helper, Episode episode, Mob opponent) {

        float lost = opponent.getMaxHealth() - opponent.getHealth();
        float owed = AgentReward.DEALT_WEIGHT * lost / Math.max(1.0F, opponent.getMaxHealth());
        float paid = episode.reward().episodeTotal();

        helper.assertTrue(Math.abs(paid - owed) < 1.0E-5F, "The agent was paid " + paid + " for " + lost + " health, not " + owed);
    }

    /** One field of an agent's self block, out of the observation a network would be handed. */
    static float selfField(AgentMob agent, int field) {

        float[] observation = new float[ObservationSchema.OBS_DIM];
        AgentObservation.write(agent, agent.brain().enemySlots(), observation, 0);

        return observation[ObservationSchema.SELF_OFFSET + field];
    }

    /** An arrow in the air from wherever, with whatever velocity, as if somebody had loosed it. */
    static Arrow shoot(GameTestHelper helper, Mob shooter, double x, double y, double z, double vx, double vy, double vz) {

        Vec3 at = helper.absoluteVec(new Vec3(x, y, z));
        Arrow arrow = new Arrow(helper.getLevel(), at.x, at.y, at.z, new ItemStack(Items.ARROW), null);

        arrow.setOwner(shooter);
        arrow.setDeltaMovement(vx, vy, vz);
        helper.getLevel().addFreshEntity(arrow);

        return arrow;
    }

    /** Which of the agent's enemy slots this entity holds, or -1 for none. */
    static int slotOf(AgentMob agent, Entity of) {

        for (int slot = 0; slot < ObservationSchema.ENEMY_SLOTS; slot++) {

            if (agent.brain().enemySlots().occupant(slot) == of) {

                return slot;
            }
        }

        return -1;
    }

    /** The plot this test owns, with a little slack: the box an agent in it is allowed to see into. */
    static AABB bounds(GameTestHelper helper) {

        return new AABB(Vec3.atLowerCornerOf(helper.absolutePos(BlockPos.ZERO)),
                Vec3.atLowerCornerOf(helper.absolutePos(new BlockPos(9, 9, 9)))).inflate(1.0D);
    }

    /** A training agent standing on a block, looking the given way, with nothing but the test's presses to drive it. */
    static AgentMob agent(GameTestHelper helper, BlockPos feet, float yaw, float pitch) {

        AgentMob agent = helper.spawn(ModEntities.trainingAgent(), feet);

        agent.setYRot(yaw);
        agent.setYHeadRot(yaw);
        agent.setYBodyRot(yaw);
        agent.setXRot(pitch);

        HeldControls.drive(agent);
        return agent;
    }

    static Holder<Enchantment> enchantment(GameTestHelper helper, ResourceKey<Enchantment> key) {

        return helper.getLevel().registryAccess().registryOrThrow(Registries.ENCHANTMENT).getHolderOrThrow(key);
    }

    /** See {@link TestTicks#run}, which the play suite runs its own tests through too. */
    static void run(GameTestHelper helper, IntPredicate step) {

        TestTicks.run(helper, step);
    }
}
