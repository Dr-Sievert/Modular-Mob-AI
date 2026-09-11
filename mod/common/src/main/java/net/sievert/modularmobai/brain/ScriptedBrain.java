package net.sievert.modularmobai.brain;

import net.minecraft.util.Mth;
import net.sievert.modularmobai.brain.schema.ActionSchema;
import net.sievert.modularmobai.brain.schema.AgentObservation;
import net.sievert.modularmobai.brain.schema.ObservationSchema;
import net.sievert.modularmobai.entity.agent.MobControls;

/**
 * A hand written fighter, useful twice over.
 *
 * <p>First as a test: it plays the fight from nothing but the observation vector, never touching the entity it is
 * driving. If this can close, aim and land hits knowing only those numbers, the observation is describing the world
 * correctly. If it walks into walls or swings at nothing, the observation is wrong and no amount of training would have
 * fixed it, it would only have hidden it.
 *
 * <p>Second as an opponent: this is the rung of the ladder between the vanilla mobs and self play, and the agent it
 * fights on that rung is a copy of this.
 *
 * <p>It fights the way the reach allows. A sword reaches three blocks from the eyes, so a mob's middle can be about three
 * and a quarter blocks away and still be hit; a mob's own melee reaches under a block and a half head on, two across a
 * diagonal. So it holds its target in the band between, lets it walk into reach rather than walking into its reach, swings
 * the moment it arrives with the cooldown recovered, and backs away while the cooldown recovers. It steps up onto what a
 * jump clears and swims up out of water, reading both off the terrain grid, since a fighter that walks into a one block
 * step and stays there is not fighting.
 *
 * <p>It does not jump for criticals. Timed from the observation alone, the fall rarely lined up with the target walking
 * into reach, one hit in ten landed as one, and it won no more fights for trying; that is left for training to find.
 */
public final class ScriptedBrain implements Brain {

    /** Closer than this and it backs away: out of a mob's reach even across the diagonal, with a little to spare. */
    private static final float BACK_OFF_RANGE = 2.4F;

    /** Farther than this and it closes in. Between the two it holds its ground and lets the target come. */
    private static final float CLOSE_IN_RANGE = 3.0F;

    /** Eye to eye, the farthest a swing at the middle of a mob still meets its box. */
    private static final float SWING_RANGE = 3.2F;

    /** How far off the target the aim may be and still be worth swinging. */
    private static final float SWING_CONE_DEGREES = 20.0F;

    private static final float SPRINT_RANGE = 6.0F;

    /**
     * The cooldown as the observation reads it, when a swing lands at full strength. The observation is taken half a tick
     * before the swing resolves, so this is a little short of one.
     */
    private static final float FULL_STRENGTH = 0.96F;

    /** How close the target has to be before a blocked line matters; farther off it is still being walked towards. */
    private static final float LINE_CHECK_RANGE = 4.0F;

    /** A player's eyes above its feet, where a swing starts from. */
    private static final double EYE_HEIGHT = 1.62D;

    @Override
    public void act(BrainStep step) {

        java.util.Arrays.fill(step.actions, 0, step.count * ActionSchema.ACT_DIM, 0.0F);

        for (int index = 0; index < step.count; index++) {

            if ((step.flags[index] & BrainStep.FLAG_DONE) != 0) {

                continue;
            }

            this.actFor(step, index * ObservationSchema.OBS_DIM, index * ActionSchema.ACT_DIM);
        }
    }

    private void actFor(BrainStep step, int obs, int act) {

        float[] o = step.observations;
        float[] a = step.actions;

        int self = obs + ObservationSchema.SELF_OFFSET;

        // Swimming up is the same key as jumping, and an agent that stops pressing it in deep water drowns, whether or not
        // there is anything in sight.
        boolean inWater = o[self + ObservationSchema.SELF_IN_WATER] > 0.5F;
        a[act + ActionSchema.JUMP] = inWater ? 1.0F : 0.0F;

        int target = nearestEnemy(o, obs);

        if (target < 0) {

            return;
        }

        // Positions arrive in the agent's own frame, already scaled down by the view distance.
        float forward = o[target + ObservationSchema.ENEMY_FORWARD];
        float right = o[target + ObservationSchema.ENEMY_RIGHT];
        float up = o[target + ObservationSchema.ENEMY_UP];
        float distance = o[target + ObservationSchema.ENEMY_DISTANCE] * (float) ObservationSchema.VIEW_DISTANCE;

        // Turning right is a rising yaw, and a target off to the right has a positive right component, so the error and
        // the control share a sign and no correction is needed.
        float yawError = (float) Math.toDegrees(Mth.atan2(right, forward));
        a[act + ActionSchema.AIM_YAW] = Mth.clamp(yawError / MobControls.MAX_AIM_YAW_PER_TICK, -1.0F, 1.0F);

        float horizontal = (float) Math.sqrt(forward * forward + right * right) * (float) ObservationSchema.VIEW_DISTANCE;
        float wantedPitch = (float) -Math.toDegrees(Mth.atan2(up * (float) ObservationSchema.VIEW_DISTANCE, horizontal));
        float pitch = o[obs + ObservationSchema.SELF_OFFSET + ObservationSchema.SELF_PITCH] * 90.0F;

        a[act + ActionSchema.AIM_PITCH] =
                Mth.clamp((wantedPitch - pitch) / MobControls.MAX_AIM_PITCH_PER_TICK, -1.0F, 1.0F);

        if (distance > CLOSE_IN_RANGE) {

            a[act + ActionSchema.MOVE_FORWARD] = 1.0F;
            a[act + ActionSchema.SPRINT] = distance > SPRINT_RANGE ? 1.0F : 0.0F;
        }

        else if (distance < BACK_OFF_RANGE) {

            a[act + ActionSchema.MOVE_FORWARD] = -1.0F;
        }

        float moving = a[act + ActionSchema.MOVE_FORWARD];

        if (moving != 0.0F && o[self + ObservationSchema.SELF_ON_GROUND] > 0.5F && stepAhead(o, obs, moving)) {

            a[act + ActionSchema.JUMP] = 1.0F;
        }

        // A block between its eyes and the target's stops every swing, and in a forest or a bamboo thicket the two can
        // stand in reach of each other for the whole minute with neither ever landing a blow. So it waits for a clear line
        // instead of swinging into the leaves, and sidesteps to find one, the same way it steps round a trunk in its path.
        boolean blocked = distance <= LINE_CHECK_RANGE && lineBlocked(o, obs, forward, right, up);

        if (blocked || (moving > 0.0F && wallAhead(o, obs))) {

            a[act + ActionSchema.MOVE_STRAFE] = sidestep(o, obs);
        }

        // Swinging wide costs the whole cooldown, so the swing waits until the target is actually in front of it. It also
        // waits for the cooldown to come all the way back: damage goes with the square of it, so a swing at nine tenths
        // does barely more than five of a sword's six, and a vindicator then takes five hits instead of four.
        float strength = o[self + ObservationSchema.SELF_ATTACK_STRENGTH];

        if (distance <= SWING_RANGE && strength >= FULL_STRENGTH && Math.abs(yawError) < SWING_CONE_DEGREES && !blocked) {

            a[act + ActionSchema.ATTACK] = 1.0F;
        }
    }

    /**
     * Whether a block stands between its eyes and the target's, walking the straight line between them through the terrain
     * grid a quarter block at a time. The agent is taken to stand in the middle of its block, which is as close as the
     * grid can say.
     */
    private static boolean lineBlocked(float[] o, int obs, float forward, float right, float up) {

        float sin = o[obs + ObservationSchema.SELF_OFFSET + ObservationSchema.SELF_AIM_SIN];
        float cos = o[obs + ObservationSchema.SELF_OFFSET + ObservationSchema.SELF_AIM_COS];
        double view = ObservationSchema.VIEW_DISTANCE;

        // Back from the agent's frame to the world's: forward runs along minus sine, cosine and right along minus cosine,
        // minus sine.
        double dx = (-sin * forward - cos * right) * view;
        double dz = (cos * forward - sin * right) * view;
        double dy = up * view;

        int steps = (int) Math.ceil(Math.sqrt(dx * dx + dy * dy + dz * dz) / 0.25D);

        for (int i = 1; i < steps; i++) {

            double t = i / (double) steps;
            int x = ObservationSchema.TERRAIN_RADIUS_XZ + (int) Math.floor(0.5D + dx * t);
            int y = ObservationSchema.TERRAIN_RADIUS_Y + (int) Math.floor(EYE_HEIGHT + dy * t);
            int z = ObservationSchema.TERRAIN_RADIUS_XZ + (int) Math.floor(0.5D + dz * t);

            if (x < 0 || y < 0 || z < 0 || x >= ObservationSchema.TERRAIN_X || y >= ObservationSchema.TERRAIN_Y
                    || z >= ObservationSchema.TERRAIN_Z) {

                continue;
            }

            if (o[obs + ObservationSchema.terrainOffset(x, y, z)] >= AgentObservation.SOLID) {

                return true;
            }
        }

        return false;
    }

    /**
     * Whether the column it walks into next is too tall to step or jump onto: solid at head height. Only walking forward
     * is checked. Sidestepping a trunk while backing away as well cut how often it was caught stuck, but turned more
     * forest fights into stand-offs that ran the clock out, and won fewer fights overall.
     */
    private static boolean wallAhead(float[] o, int obs) {

        float sin = o[obs + ObservationSchema.SELF_OFFSET + ObservationSchema.SELF_AIM_SIN];
        float cos = o[obs + ObservationSchema.SELF_OFFSET + ObservationSchema.SELF_AIM_COS];

        int x = ObservationSchema.TERRAIN_RADIUS_XZ + Math.round(-sin);
        int z = ObservationSchema.TERRAIN_RADIUS_XZ + Math.round(cos);

        return o[obs + ObservationSchema.terrainOffset(x, ObservationSchema.TERRAIN_RADIUS_Y + 1, z)] >= AgentObservation.SOLID;
    }

    /** Left, when the column to its left has room for a body; otherwise right, when that one does; otherwise nowhere. */
    private static float sidestep(float[] o, int obs) {

        float sin = o[obs + ObservationSchema.SELF_OFFSET + ObservationSchema.SELF_AIM_SIN];
        float cos = o[obs + ObservationSchema.SELF_OFFSET + ObservationSchema.SELF_AIM_COS];

        // Left of the way it looks is cosine, sine in the world; strafing is positive to the left.
        if (roomFor(o, obs, Math.round(cos), Math.round(sin))) {

            return 1.0F;
        }

        return roomFor(o, obs, Math.round(-cos), Math.round(-sin)) ? -1.0F : 0.0F;
    }

    private static boolean roomFor(float[] o, int obs, int dx, int dz) {

        int x = ObservationSchema.TERRAIN_RADIUS_XZ + dx;
        int z = ObservationSchema.TERRAIN_RADIUS_XZ + dz;
        int feet = ObservationSchema.TERRAIN_RADIUS_Y;

        return o[obs + ObservationSchema.terrainOffset(x, feet, z)] < AgentObservation.SOLID
                && o[obs + ObservationSchema.terrainOffset(x, feet + 1, z)] < AgentObservation.SOLID;
    }

    /**
     * Whether the cell it is walking into holds a one block step: solid at the feet with room above it to stand. The grid
     * is aligned to the world, so the way it is walking is turned into the neighbouring column it walks into next.
     */
    private static boolean stepAhead(float[] o, int obs, float moving) {

        float sin = o[obs + ObservationSchema.SELF_OFFSET + ObservationSchema.SELF_AIM_SIN];
        float cos = o[obs + ObservationSchema.SELF_OFFSET + ObservationSchema.SELF_AIM_COS];

        // Looking along yaw means heading towards minus sine along x and cosine along z; backing away is the reverse.
        int dx = Math.round(-sin * Math.signum(moving));
        int dz = Math.round(cos * Math.signum(moving));

        int x = ObservationSchema.TERRAIN_RADIUS_XZ + dx;
        int z = ObservationSchema.TERRAIN_RADIUS_XZ + dz;
        int feet = ObservationSchema.TERRAIN_RADIUS_Y;

        return o[obs + ObservationSchema.terrainOffset(x, feet, z)] >= AgentObservation.SOLID
                && o[obs + ObservationSchema.terrainOffset(x, feet + 1, z)] < AgentObservation.SOLID
                && o[obs + ObservationSchema.terrainOffset(x, feet + 2, z)] < AgentObservation.SOLID;
    }

    /** The offset of the closest occupied enemy slot, or -1 when nothing is in view. */
    private static int nearestEnemy(float[] o, int obs) {

        int best = -1;
        float bestDistance = Float.MAX_VALUE;

        for (int slot = 0; slot < ObservationSchema.ENEMY_SLOTS; slot++) {

            int at = obs + ObservationSchema.enemyOffset(slot);

            if (o[at + ObservationSchema.ENEMY_PRESENT] < 0.5F) {

                continue;
            }

            float distance = o[at + ObservationSchema.ENEMY_DISTANCE];

            if (distance < bestDistance) {

                bestDistance = distance;
                best = at;
            }
        }

        return best;
    }
}
