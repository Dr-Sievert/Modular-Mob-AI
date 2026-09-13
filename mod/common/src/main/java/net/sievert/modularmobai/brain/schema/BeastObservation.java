package net.sievert.modularmobai.brain.schema;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.sievert.modularmobai.entity.agent.AgentMob;
import net.sievert.modularmobai.entity.agent.ExecutedControls;
import net.sievert.modularmobai.entity.agent.MobControls;

/**
 * Fills the {@link BeastSchema beast}'s row: its own self block, its own echo, and then the two blocks every body shares.
 *
 * <p>This is the whole of a second body's encoder, and it is this short because the enemy slots and the terrain grid — which
 * are nine tenths of the numbers and all of the difficult code — are written by {@link AgentObservation} for anybody who
 * asks, at whatever offset that body's layout puts them.
 */
final class BeastObservation {

    private BeastObservation() {}

    static void write(AgentMob agent, EnemySlots slots, float[] out, int base) {

        java.util.Arrays.fill(out, base, base + BeastSchema.OBS_DIM, 0.0F);

        float sin = AgentObservation.yawSin(agent);
        float cos = AgentObservation.yawCos(agent);

        writeSelf(agent, slots, out, base + BeastSchema.SELF_OFFSET, sin, cos);
        writeEcho(agent.executed(), out, base + BeastSchema.ECHO_OFFSET);

        AgentObservation.writeEnemies(agent, slots, out, base + BeastSchema.ENEMY_OFFSET, sin, cos);
        AgentObservation.writeTerrain(agent, out, base + BeastSchema.TERRAIN_OFFSET);
        AgentObservation.writeRays(agent, out, base + BeastSchema.RAY_OFFSET);
    }

    private static void writeSelf(AgentMob agent, EnemySlots slots, float[] out, int at, float sin, float cos) {

        Vec3 velocity = agent.getDeltaMovement();

        out[at + BeastSchema.SELF_HEALTH] = agent.getHealth() / agent.getMaxHealth();
        out[at + BeastSchema.SELF_VELOCITY_FORWARD] =
                (float) (AgentObservation.forward(velocity.x, velocity.z, sin, cos) / AgentObservation.VELOCITY_SCALE);
        out[at + BeastSchema.SELF_VELOCITY_UP] = (float) (velocity.y / AgentObservation.VELOCITY_SCALE);
        out[at + BeastSchema.SELF_VELOCITY_RIGHT] =
                (float) (AgentObservation.right(velocity.x, velocity.z, sin, cos) / AgentObservation.VELOCITY_SCALE);
        out[at + BeastSchema.SELF_ON_GROUND] = agent.onGround() ? 1.0F : 0.0F;
        out[at + BeastSchema.SELF_IN_WATER] = agent.isInWater() ? 1.0F : 0.0F;
        out[at + BeastSchema.SELF_ATTACK_STRENGTH] = agent.getAttackStrengthScale(0.0F);
        out[at + BeastSchema.SELF_SPRINTING] = agent.isSprinting() ? 1.0F : 0.0F;
        out[at + BeastSchema.SELF_FALL_DISTANCE] =
                Math.min(agent.fallDistance, AgentObservation.MAX_FALL_DISTANCE) / AgentObservation.MAX_FALL_DISTANCE;

        // How far the body has been left behind by the aim, which is what tells it it is about to be dragged round.
        float bodyOffset = Mth.wrapDegrees(agent.yBodyRot - agent.getYRot()) * AgentObservation.DEGREES_TO_RADIANS;
        out[at + BeastSchema.SELF_BODY_OFFSET_SIN] = Mth.sin(bodyOffset);
        out[at + BeastSchema.SELF_BODY_OFFSET_COS] = Mth.cos(bodyOffset);

        out[at + BeastSchema.SELF_PITCH] = agent.getXRot() / 90.0F;
        out[at + BeastSchema.SELF_AIM_SIN] = sin;
        out[at + BeastSchema.SELF_AIM_COS] = cos;
        out[at + BeastSchema.SELF_HURT_TIME] = agent.hurtTime / 10.0F;
        out[at + BeastSchema.SELF_ENEMIES_IN_RANGE] = slots.inRangeCount() / (float) ObservationSchema.ENEMY_SLOTS;

        // The same clock the humanoid reads, from the same place: the beast's fights are timed the same way and paid the
        // same way, so the reward is as much a function of the clock for it as for anybody. See BeastSchema#SELF_CLOCK.
        out[at + BeastSchema.SELF_CLOCK] = AgentObservation.clock(agent);

        // What it wears and what its bite is worth, both from the shared readings: neither needs a hand, and a body that
        // cannot tell how hard it hits or how much it soaks cannot tell how a fight is going. See BeastSchema#SELF_ARMOUR.
        out[at + BeastSchema.SELF_ARMOUR] = AgentObservation.armour(agent);
        out[at + BeastSchema.SELF_ATTACK_DAMAGE] = AgentObservation.blowDamage(agent);
    }

    private static void writeEcho(ExecutedControls echo, float[] out, int at) {

        out[at] = echo.moveForward;
        out[at + 1] = echo.moveStrafe;
        out[at + 2] = echo.jumped ? 1.0F : 0.0F;
        out[at + 3] = echo.sprinting ? 1.0F : 0.0F;
        out[at + 4] = echo.aimYawDegrees / MobControls.MAX_AIM_YAW_PER_TICK;
        out[at + 5] = echo.aimPitchDegrees / MobControls.MAX_AIM_PITCH_PER_TICK;
        out[at + 6] = echo.attacked ? 1.0F : 0.0F;
        out[at + 7] = echo.attackHit ? 1.0F : 0.0F;
        out[at + 8] = echo.attackStrength;
        out[at + 9] = echo.attackDamage / 20.0F;
        out[at + 10] = echo.attackCritical ? 1.0F : 0.0F;
    }
}
