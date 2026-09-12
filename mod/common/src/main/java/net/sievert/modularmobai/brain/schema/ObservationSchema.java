package net.sievert.modularmobai.brain.schema;

import net.sievert.modularmobai.entity.agent.MobControls;

/**
 * The layout of the <b>humanoid</b>'s flat observation vector, and the single place any of its numbers is written down.
 *
 * <p>This is one body's, not every body's. {@link Species} is what the rest of the game asks how wide an observation is;
 * nothing outside this package reads the constants below, because a body with no hands has no hotbar block and no slot to
 * choose, and an offset from here would mean nothing to it. The humanoid's descriptor is {@code Humanoid}, which is what
 * turns these numbers into a schema the training side can read.
 *
 * <p>Nothing here is settled. The whole point of keeping the offsets in one class is that changing the size of a block
 * moves everything after it without anyone having to remember to follow, so the schema stays cheap to rearrange while the
 * design is still moving.
 *
 * <p>The vector is flat on purpose: the terrain grid could have gone through its own encoder, but a single input path
 * keeps the recorded row a single row, which is what the training side and the shard format both want.
 */
public final class ObservationSchema {

    private ObservationSchema() {}

    // -----------------------------------------------------------------------------------------------------------
    // Terrain
    // -----------------------------------------------------------------------------------------------------------

    /** Nine wide covers the four and a half blocks of build reach in every horizontal direction. */
    public static final int TERRAIN_X = 9;

    /** Two below the feet for ledges, the feet, the head, and one above for placing. */
    public static final int TERRAIN_Y = 5;
    public static final int TERRAIN_Z = 9;

    public static final int TERRAIN_RADIUS_XZ = TERRAIN_X / 2;
    public static final int TERRAIN_RADIUS_Y = TERRAIN_Y / 2;
    public static final int TERRAIN_SIZE = TERRAIN_X * TERRAIN_Y * TERRAIN_Z;

    // -----------------------------------------------------------------------------------------------------------
    // Enemies
    // -----------------------------------------------------------------------------------------------------------

    public static final int ENEMY_SLOTS = 10;
    public static final int ENEMY_STRIDE = 18;
    public static final int ENEMY_SIZE = ENEMY_SLOTS * ENEMY_STRIDE;

    /** How far an enemy can be and still hold a slot. Beyond this it is only part of the in range count. */
    public static final double VIEW_DISTANCE = 32.0D;

    /**
     * How long a slot stays reserved for an enemy that has left the view. Without this an opponent that steps out and
     * back can return in a different slot, which is exactly the reshuffling the leases exist to prevent.
     */
    public static final int LEASE_GRACE_TICKS = 40;

    // Offsets within one enemy slot.
    public static final int ENEMY_PRESENT = 0;
    public static final int ENEMY_FORWARD = 1;
    public static final int ENEMY_UP = 2;
    public static final int ENEMY_RIGHT = 3;
    public static final int ENEMY_DISTANCE = 4;
    public static final int ENEMY_VELOCITY_FORWARD = 5;
    public static final int ENEMY_VELOCITY_UP = 6;
    public static final int ENEMY_VELOCITY_RIGHT = 7;
    public static final int ENEMY_HEALTH = 8;
    public static final int ENEMY_FACING_SIN = 9;
    public static final int ENEMY_FACING_COS = 10;
    public static final int ENEMY_PITCH = 11;
    public static final int ENEMY_KIND = 12;
    public static final int ENEMY_MAIN_HAND = 13;
    public static final int ENEMY_OFF_HAND = 14;
    public static final int ENEMY_SWINGING = 15;
    public static final int ENEMY_USING = 16;
    public static final int ENEMY_SPRINTING = 17;

    // -----------------------------------------------------------------------------------------------------------
    // Self
    // -----------------------------------------------------------------------------------------------------------

    public static final int SELF_SIZE = 20;

    public static final int SELF_HEALTH = 0;
    public static final int SELF_VELOCITY_FORWARD = 1;
    public static final int SELF_VELOCITY_UP = 2;
    public static final int SELF_VELOCITY_RIGHT = 3;
    public static final int SELF_ON_GROUND = 4;
    public static final int SELF_IN_WATER = 5;
    public static final int SELF_ATTACK_STRENGTH = 6;
    public static final int SELF_USE_COOLDOWN = 7;
    public static final int SELF_USING = 8;
    public static final int SELF_USING_OFFHAND = 9;
    public static final int SELF_SPRINTING = 10;
    public static final int SELF_CROUCHING = 11;
    public static final int SELF_FALL_DISTANCE = 12;
    public static final int SELF_BODY_OFFSET_SIN = 13;
    public static final int SELF_BODY_OFFSET_COS = 14;
    public static final int SELF_PITCH = 15;
    /**
     * The terrain grid is aligned to the world rather than to the aim, because rotating a voxel grid either costs a
     * rotation per cell or aliases badly. These two let the network line the grid up with everything else, which is all
     * that alignment was buying.
     */
    public static final int SELF_AIM_SIN = 16;
    public static final int SELF_AIM_COS = 17;
    public static final int SELF_HURT_TIME = 18;
    public static final int SELF_ENEMIES_IN_RANGE = 19;

    // -----------------------------------------------------------------------------------------------------------
    // Hotbar and the executed action echo
    // -----------------------------------------------------------------------------------------------------------

    /** One coarse item category per slot. A fixed loadout means the agent can learn what lives where. */
    public static final int HOTBAR_SIZE = MobControls.HOTBAR_SIZE;

    public static final int ECHO_SIZE = 20;

    // -----------------------------------------------------------------------------------------------------------
    // Where each block starts
    // -----------------------------------------------------------------------------------------------------------

    public static final int SELF_OFFSET = 0;
    public static final int HOTBAR_OFFSET = SELF_OFFSET + SELF_SIZE;
    public static final int ECHO_OFFSET = HOTBAR_OFFSET + HOTBAR_SIZE;
    public static final int ENEMY_OFFSET = ECHO_OFFSET + ECHO_SIZE;
    public static final int TERRAIN_OFFSET = ENEMY_OFFSET + ENEMY_SIZE;

    public static final int OBS_DIM = TERRAIN_OFFSET + TERRAIN_SIZE;

    public static int enemyOffset(int slot) {

        return ENEMY_OFFSET + slot * ENEMY_STRIDE;
    }

    /** Terrain cells run x fastest, then y, then z, with the agent's feet block at the centre. */
    public static int terrainOffset(int x, int y, int z) {

        return TERRAIN_OFFSET + (z * TERRAIN_Y + y) * TERRAIN_X + x;
    }

}
