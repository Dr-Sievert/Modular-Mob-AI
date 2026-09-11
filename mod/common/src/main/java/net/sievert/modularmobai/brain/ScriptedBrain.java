package net.sievert.modularmobai.brain;

import java.util.Arrays;

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
 * diagonal. So it wants to stand in the band between, with nothing between its eyes and the target's, and swing the
 * moment the target arrives with the cooldown recovered, backing away while the cooldown recovers.
 *
 * <p>Getting to such a place is a search, not a reflex. Every tick it finds, over the terrain grid it is given, every spot
 * it could walk to, stepping up what a jump clears and down what it can drop, and heads for the nearest one in the band
 * with a clear line, or failing that as close to its target as the ground allows. Walking straight at a target with a
 * tree in the way, it used to stand behind the trunk and sidestep while the target walked round it, and neither landed a
 * blow for the whole minute.
 *
 * <p>It does not jump for criticals. Timed from the observation alone, the fall rarely lined up with the target walking
 * into reach, one hit in ten landed as one, and it won no more fights for trying; that is left for training to find.
 * Nor does it sprint into its swings: the extra knockback did not keep the target away any longer than a plain hit.
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

    /** A player's eyes above its feet, where a swing starts from. */
    private static final double EYE_HEIGHT = 1.62D;

    /** Where in the echo of last tick's controls a swing, and a swing that landed, are written. */
    private static final int ECHO_ATTACKED = 7;
    private static final int ECHO_HIT = 8;

    private static final int X = ObservationSchema.TERRAIN_X;
    private static final int Z = ObservationSchema.TERRAIN_Z;
    private static final int CENTRE = ObservationSchema.TERRAIN_RADIUS_XZ;
    private static final int FEET = ObservationSchema.TERRAIN_RADIUS_Y;

    /** Where it can stand: one level below its feet, level with them, or one above, which the grid has room around. */
    private static final int LEVELS = 3;

    private static final int[] STEP_X = {1, -1, 0, 0, 1, 1, -1, -1};
    private static final int[] STEP_Z = {0, 0, 1, -1, 1, -1, 1, -1};

    // The search's working space, reused for every agent on every tick. A brain is only ever stepped from the server
    // thread.
    private final int[] depth = new int[X * Z * LEVELS];
    private final int[] parent = new int[X * Z * LEVELS];
    private final int[] queue = new int[X * Z * LEVELS];
    private final boolean[] dropped = new boolean[X * Z * LEVELS];

    /**
     * How far below it a target has to be, eye to eye, before dropping down to it is worth considering, and how far at
     * most. A fall costs a heart for every block past the third, so five blocks is about one heart.
     */
    private static final double MIN_DROP = 1.5D;
    private static final double MAX_DROP = 5.5D;

    /**
     * How far it will fall to reach a target that is stuck below and not coming up. A fall costs one health for every
     * block past the third, so nine leaves fourteen of twenty, still more than a vindicator's axe takes in one blow:
     * dropping in costs no more blows to die than staying up did.
     */
    private static final double MAX_DROP_TO_STUCK = 9.0D;

    /** Slower than this, in the observation's velocity units, a target is standing still rather than coming. */
    private static final float STILL_SPEED = 0.1F;

    @Override
    public void act(BrainStep step) {

        Arrays.fill(step.actions, 0, step.count * ActionSchema.ACT_DIM, 0.0F);

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
        boolean grounded = o[self + ObservationSchema.SELF_ON_GROUND] > 0.5F;

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
        float pitch = o[self + ObservationSchema.SELF_PITCH] * 90.0F;

        a[act + ActionSchema.AIM_PITCH] =
                Mth.clamp((wantedPitch - pitch) / MobControls.MAX_AIM_PITCH_PER_TICK, -1.0F, 1.0F);

        // Back from the agent's frame to the world's, in blocks: forward runs along minus sine, cosine and right along
        // minus cosine, minus sine. The grid puts the agent in the middle of its own column.
        float sin = o[self + ObservationSchema.SELF_AIM_SIN];
        float cos = o[self + ObservationSchema.SELF_AIM_COS];
        double view = ObservationSchema.VIEW_DISTANCE;
        double targetX = CENTRE + 0.5D + (-sin * forward - cos * right) * view;
        double targetZ = CENTRE + 0.5D + (cos * forward - sin * right) * view;
        double targetEye = FEET + EYE_HEIGHT + up * view;

        // The game says more than the grid does. A swing last tick that hit nothing yet left the cooldown standing met a
        // block on the way, since only a swing at thin air costs the cooldown, and the grid, which only knows which block
        // the agent is in and not where in it, can call that same line clear. Left at that, it stood where it was and
        // clicked at the leaves every tick for the rest of the minute.
        float strength = o[self + ObservationSchema.SELF_ATTACK_STRENGTH];
        int echo = obs + ObservationSchema.ECHO_OFFSET;
        boolean swungIntoBlock = o[echo + ECHO_ATTACKED] > 0.5F && o[echo + ECHO_HIT] < 0.5F && strength >= FULL_STRENGTH;

        boolean clear = !swungIntoBlock && !blocked(o, obs, CENTRE, 0, CENTRE, targetX, targetEye, targetZ);
        int start = state(CENTRE, 0, CENTRE);
        int next = start;

        if (distance < BACK_OFF_RANGE) {

            next = this.retreat(o, obs, targetX, targetZ);
        }

        else if (distance > CLOSE_IN_RANGE || !clear) {

            float targetSpeed = Math.abs(o[target + ObservationSchema.ENEMY_VELOCITY_FORWARD])
                    + Math.abs(o[target + ObservationSchema.ENEMY_VELOCITY_RIGHT]);

            next = this.approach(o, obs, targetX, targetEye, targetZ, swungIntoBlock, targetSpeed < STILL_SPEED);
        }

        if (next != start) {

            this.walkTowards(next, a, act, sin, cos, grounded);

            if (distance > SPRINT_RANGE && a[act + ActionSchema.MOVE_FORWARD] >= MobControls.SPRINT_FORWARD_THRESHOLD) {

                a[act + ActionSchema.SPRINT] = 1.0F;
            }
        }

        // Asked to move on the tick before and did not: the grid missed something, a corner or an edge of a block the
        // body is wider than. A jump gets it over most of those.
        if (grounded && stuck(o, obs)) {

            a[act + ActionSchema.JUMP] = 1.0F;
        }

        // Swinging wide costs the whole cooldown, so the swing waits until the target is actually in front of it. It also
        // waits for the cooldown to come all the way back: damage goes with the square of it, so a swing at nine tenths
        // does barely more than five of a sword's six, and a vindicator then takes five hits instead of four. A swing
        // that meets a block on the way costs nothing, as a player's does, and clears grass and ferns out of the way, so a
        // line the grid says is blocked is no reason to hold back; it only decides where to walk.
        if (distance <= SWING_RANGE && strength >= FULL_STRENGTH && Math.abs(yawError) < SWING_CONE_DEGREES) {

            a[act + ActionSchema.ATTACK] = 1.0F;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Where to go
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * The first step towards the nearest spot it can reach that is in the band with a clear line to the target, or,
     * where there is none in the grid, towards the reachable spot closest to the target without going inside the band.
     */
    private int approach(float[] o, int obs, double targetX, double targetEye, double targetZ, boolean notHere,
            boolean targetStuck) {

        // A target well below can be dropped down to, as long as the fall does little more harm than a missed swing. One
        // stuck at the bottom of a pit, that cannot come up and will not be reached any other way, is worth a deeper fall:
        // down to where what the fall takes still leaves more than one blow from a vindicator can.
        double below = FEET + EYE_HEIGHT - targetEye;
        double deepest = targetStuck ? MAX_DROP_TO_STUCK : MAX_DROP;
        int reached = this.search(o, obs, below >= MIN_DROP && below <= deepest);

        int best = -1;
        int bestDepth = Integer.MAX_VALUE;
        int closest = -1;
        double closestDistance = Double.MAX_VALUE;

        for (int i = 0; i < reached; i++) {

            int s = this.queue[i];

            // Where it stands has just been shown not to work, whatever the grid makes of it.
            if (notHere && i == 0) {

                continue;
            }

            int x = s % X;
            int level = s / (X * Z) - 1;
            int z = (s / X) % Z;

            // Eye to eye, the way a swing reaches, and not across the ground: a target three blocks down the slope
            // beneath its feet is not in reach however close it stands. Where a drop lands is not known, only that it is
            // somewhere near the target's own level, so there the ground distance is all there is to go on.
            double across = Math.hypot(targetX - (x + 0.5D), targetZ - (z + 0.5D));
            double reach = this.dropped[s] ? across : Math.hypot(across, targetEye - (FEET + level + EYE_HEIGHT));

            if (!this.dropped[s] && reach >= BACK_OFF_RANGE && reach <= SWING_RANGE - 0.1D && this.depth[s] < bestDepth
                    && !blocked(o, obs, x, level, z, targetX, targetEye, targetZ)) {

                best = s;
                bestDepth = this.depth[s];
            }

            double shortfall = Math.max(reach, BACK_OFF_RANGE) + 0.05D * this.depth[s];

            if (shortfall < closestDistance) {

                closest = s;
                closestDistance = shortfall;
            }
        }

        return this.firstStep(best >= 0 ? best : closest);
    }

    /** The neighbouring spot, one step away, that puts the most ground between it and the target. */
    private int retreat(float[] o, int obs, double targetX, double targetZ) {

        int reached = this.search(o, obs, false);
        int start = state(CENTRE, 0, CENTRE);

        int best = start;
        double bestDistance = Math.hypot(targetX - (CENTRE + 0.5D), targetZ - (CENTRE + 0.5D));

        for (int i = 0; i < reached; i++) {

            int s = this.queue[i];

            if (this.depth[s] != 1) {

                continue;
            }

            double away = Math.hypot(targetX - (s % X + 0.5D), targetZ - ((s / X) % Z + 0.5D));

            if (away > bestDistance) {

                best = s;
                bestDistance = away;
            }
        }

        return best;
    }

    /**
     * Every spot it can walk to from where it stands, breadth first, leaving each one's distance in steps and the spot it
     * was reached from. Returns how many were reached; they are the first entries of the queue, nearest first.
     *
     * <p>Allowed to, it also steps off edges whose ground is too far down for the grid to see. Where it lands is unknown,
     * so such a spot is marked as a drop and nothing is searched beyond it.
     */
    private int search(float[] o, int obs, boolean mayDrop) {

        Arrays.fill(this.depth, -1);
        Arrays.fill(this.dropped, false);

        int start = state(CENTRE, 0, CENTRE);
        this.depth[start] = 0;
        this.parent[start] = start;
        this.queue[0] = start;

        int head = 0;
        int tail = 1;

        while (head < tail) {

            int s = this.queue[head++];

            if (this.dropped[s]) {

                continue;
            }

            int x = s % X;
            int z = (s / X) % Z;
            int level = s / (X * Z) - 1;

            for (int d = 0; d < STEP_X.length; d++) {

                int nx = x + STEP_X[d];
                int nz = z + STEP_Z[d];

                if (nx < 0 || nz < 0 || nx >= X || nz >= Z) {

                    continue;
                }

                // Across a diagonal both sides have to be open, or the body, wider than a gap between two corners,
                // snags on them.
                if (d >= 4 && (!open(o, obs, x + STEP_X[d], level, z) || !open(o, obs, x, level, z + STEP_Z[d]))) {

                    continue;
                }

                boolean found = false;

                for (int to = level - 1; to <= level + 1 && !found; to++) {

                    if (to < -1 || to > 1 || !standable(o, obs, nx, to, nz)) {

                        continue;
                    }

                    // Stepping up needs the room to jump from where it stands.
                    if (to > level && !open(o, obs, x, level + 1, z)) {

                        continue;
                    }

                    found = true;
                    tail = this.visit(state(nx, to, nz), s, tail, false);
                }

                // Open all the way down past the bottom of the grid: a drop of three blocks or more.
                if (!found && mayDrop && level == 0 && open(o, obs, nx, 0, nz)
                        && passable(cell(o, obs, nx, FEET - 1, nz))
                        && passable(cell(o, obs, nx, FEET - 2, nz))) {

                    tail = this.visit(state(nx, -1, nz), s, tail, true);
                }
            }
        }

        return tail;
    }

    private int visit(int n, int from, int tail, boolean drop) {

        if (this.depth[n] >= 0) {

            return tail;
        }

        this.depth[n] = this.depth[from] + 1;
        this.parent[n] = from;
        this.dropped[n] = drop;
        this.queue[tail] = n;

        return tail + 1;
    }

    /** Walks the search back from a spot to the one it is first reached through from where the agent stands. */
    private int firstStep(int s) {

        int start = state(CENTRE, 0, CENTRE);

        if (s < 0) {

            return start;
        }

        while (this.parent[s] != start && this.parent[s] != s) {

            s = this.parent[s];
        }

        return s;
    }

    /** Presses whatever moves it from the middle of its own column towards the middle of the next one. */
    private void walkTowards(int next, float[] a, int act, float sin, float cos, boolean grounded) {

        double dx = next % X - CENTRE;
        double dz = (next / X) % Z - CENTRE;

        // Into the agent's own frame: forward along its look, and strafing positive to the left, which is minus right.
        double ahead = dx * -sin + dz * cos;
        double rightward = dx * -cos + dz * -sin;
        double scale = Math.max(Math.abs(ahead), Math.abs(rightward));

        a[act + ActionSchema.MOVE_FORWARD] = (float) (ahead / scale);
        a[act + ActionSchema.MOVE_STRAFE] = (float) (-rightward / scale);

        if (grounded && next / (X * Z) - 1 > 0) {

            a[act + ActionSchema.JUMP] = 1.0F;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Reading the grid
    // ---------------------------------------------------------------------------------------------------------------

    private static int state(int x, int level, int z) {

        return ((level + 1) * Z + z) * X + x;
    }

    private static float cell(float[] o, int obs, int x, int y, int z) {

        return y < 0 || y >= ObservationSchema.TERRAIN_Y ? AgentObservation.SOLID : o[obs + ObservationSchema.terrainOffset(x, y, z)];
    }

    /** Air or water: somewhere a body can be. Neither solid nor a hazard, which reads above solid. */
    private static boolean passable(float cell) {

        return cell >= AgentObservation.EMPTY && cell < AgentObservation.SOLID;
    }

    /** Room for a body standing at this level: nothing solid, and nothing that hurts, where its legs and head would be. */
    private static boolean open(float[] o, int obs, int x, int level, int z) {

        return passable(cell(o, obs, x, FEET + level, z)) && passable(cell(o, obs, x, FEET + level + 1, z));
    }

    /**
     * Somewhere to stand: room for a body, and ground under it or water to float in. A hazard is never ground: the top of
     * a lava lake, a magma block or a cactus reads as a hazard, not as solid, which is what keeps the planner off them.
     */
    private static boolean standable(float[] o, int obs, int x, int level, int z) {

        float under = cell(o, obs, x, FEET + level - 1, z);
        float at = cell(o, obs, x, FEET + level, z);

        boolean ground = under >= AgentObservation.SOLID && under < AgentObservation.HAZARD;

        return open(o, obs, x, level, z) && (ground || at >= AgentObservation.FLUID);
    }

    /**
     * Whether a block stands on the straight line from the eyes of a body standing at a spot to the target's eyes, walked
     * through the grid a quarter block at a time. Positions are in grid units, with the agent's own column at the middle.
     */
    private static boolean blocked(float[] o, int obs, int fromX, int level, int fromZ, double toX, double toEye, double toZ) {

        double x0 = fromX + 0.5D;
        double y0 = FEET + level + EYE_HEIGHT;
        double z0 = fromZ + 0.5D;

        double dx = toX - x0;
        double dy = toEye - y0;
        double dz = toZ - z0;

        int steps = (int) Math.ceil(Math.sqrt(dx * dx + dy * dy + dz * dz) / 0.25D);

        for (int i = 1; i < steps; i++) {

            double t = i / (double) steps;
            int x = (int) Math.floor(x0 + dx * t);
            int y = (int) Math.floor(y0 + dy * t);
            int z = (int) Math.floor(z0 + dz * t);

            if (x < 0 || y < 0 || z < 0 || x >= X || y >= ObservationSchema.TERRAIN_Y || z >= Z) {

                continue;
            }

            if (o[obs + ObservationSchema.terrainOffset(x, y, z)] >= AgentObservation.SOLID) {

                return true;
            }
        }

        return false;
    }

    /** It pressed to move on the tick before, and hardly moved. */
    private static boolean stuck(float[] o, int obs) {

        int echo = obs + ObservationSchema.ECHO_OFFSET;
        int self = obs + ObservationSchema.SELF_OFFSET;

        boolean pressed = Math.abs(o[echo]) > 0.3F || Math.abs(o[echo + 1]) > 0.3F;
        float speed = Math.abs(o[self + ObservationSchema.SELF_VELOCITY_FORWARD]) + Math.abs(o[self + ObservationSchema.SELF_VELOCITY_RIGHT]);

        return pressed && speed < 0.02F;
    }

    /**
     * The offset of the closest body in an enemy slot, or -1 when nothing is in view. A slot can also hold something shot
     * at the agent, and an arrow a block away is nearer than whatever fired it: what to fight is only ever a body.
     */
    private static int nearestEnemy(float[] o, int obs) {

        int best = -1;
        float bestDistance = Float.MAX_VALUE;

        for (int slot = 0; slot < ObservationSchema.ENEMY_SLOTS; slot++) {

            int at = obs + ObservationSchema.enemyOffset(slot);

            if (o[at + ObservationSchema.ENEMY_PRESENT] < 0.5F
                    || AgentObservation.isProjectileKind(o[at + ObservationSchema.ENEMY_KIND])) {

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
