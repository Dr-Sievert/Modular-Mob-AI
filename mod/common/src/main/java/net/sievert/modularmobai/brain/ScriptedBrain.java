package net.sievert.modularmobai.brain;

import net.minecraft.util.Mth;
import net.sievert.modularmobai.brain.schema.ActionSchema;
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
 * <p>It is not meant to be good. It closes to sword range, faces its target and swings on cooldown, which is roughly
 * what a person does on their first day and exactly the bar the network has to clear to be worth anything.
 */
public final class ScriptedBrain implements Brain {

    /** Just inside the three block reach, so a step in either direction does not immediately lose the target. */
    private static final float PREFERRED_RANGE = 2.2F;

    /** How far off the target the aim may be and still be worth swinging. */
    private static final float SWING_CONE_DEGREES = 20.0F;

    private static final float SPRINT_RANGE = 6.0F;

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

        if (distance > PREFERRED_RANGE) {

            a[act + ActionSchema.MOVE_FORWARD] = 1.0F;
            a[act + ActionSchema.SPRINT] = distance > SPRINT_RANGE ? 1.0F : 0.0F;
        }

        else if (distance < PREFERRED_RANGE - 0.6F) {

            a[act + ActionSchema.MOVE_FORWARD] = -1.0F;
        }

        // Swinging wide costs the whole cooldown, so the swing waits until the target is actually in front of it.
        float strength = o[obs + ObservationSchema.SELF_OFFSET + ObservationSchema.SELF_ATTACK_STRENGTH];

        if (distance <= 3.0F && strength > 0.9F && Math.abs(yawError) < SWING_CONE_DEGREES) {

            a[act + ActionSchema.ATTACK] = 1.0F;
        }
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
