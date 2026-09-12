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
    // Terrain, which every body shares: ground is ground whoever is standing on it, and two bodies disagreeing about what
    // a hazard is would be a bug nobody could see. A species chooses whether it has a grid and where the grid sits, not
    // what a cell of it means. Written by AgentObservation.writeTerrain.
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
    // Enemies, which every body shares as well: an opponent looks the same whoever is looking at it. Written by
    // AgentObservation.writeEnemies, and kept in leases by EnemySlots.
    // -----------------------------------------------------------------------------------------------------------

    public static final int ENEMY_SLOTS = 10;
    public static final int ENEMY_STRIDE = 29;
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
    // What the thing in front of it actually is
    //
    // The five values of ENEMY_KIND say agent, player, monster, something else alive, or a projectile, and every hostile
    // mob in the game is the one value "monster". ENEMY_HEALTH is a fraction, so a zombie at full health and a warden at
    // full health both read 1, and neither carries anything, so their hands read the same too. A network fighting the
    // league was therefore asked to tell two hundred opponents apart by how they moved, and to find out how hard one hits
    // by being hit: against a creeper that is one fight too late, and against a warden it is the whole fight. The league
    // showed it plainly — every ordinary mob beaten 75 to 98%, and 0% against two creepers, 0% against the warden.
    //
    // So a slot now says what the thing can do. Not a species number, which would have to be learned one mob at a time
    // and would say nothing about a mob the run never met, but the capabilities the tactics actually turn on: how much
    // there is of it, how hard and how far it hits, how fast it moves, how big it is, whether knockback moves it, and
    // whether it explodes, shoots or flies. A creeper is then "twenty health, no weapon, explodes, fuse at 0.4" rather
    // than "monster".
    // -----------------------------------------------------------------------------------------------------------

    /** Health in hearts rather than as a fraction: what it has now and what it has when whole, both over HEALTH_SCALE. */
    public static final int ENEMY_MAX_HEALTH = 18;
    public static final int ENEMY_HEALTH_LEFT = 19;

    /** What one of its blows takes off, over DAMAGE_SCALE, and zero for anything that does not strike. */
    public static final int ENEMY_DAMAGE = 20;

    /** How fast it walks or flies, over SPEED_SCALE. */
    public static final int ENEMY_SPEED = 21;

    /**
     * How big it is, each over SIZE_SCALE. Size is most of what tells one mob from another by eye, and it is also the
     * reach: a mob strikes from its own width away, so a ravager at nearly two blocks wide hits from where a zombie cannot.
     */
    public static final int ENEMY_WIDTH = 22;
    public static final int ENEMY_HEIGHT = 23;

    /** How much of a knockback it shrugs off, 0 to 1. A warden and an iron golem barely move; that decides hazard pushes. */
    public static final int ENEMY_KNOCKBACK_RESISTANCE = 24;

    /**
     * How far along a creeper's fuse is, 0 to 1, and 0 for everything else. The teacher had to guess this from empty hands
     * and a mob that stopped coming, and the network had no way to guess it at all.
     */
    public static final int ENEMY_FUSE = 25;

    /** What it does: goes off, shoots at range, or flies. */
    public static final int ENEMY_EXPLODES = 26;
    public static final int ENEMY_SHOOTS = 27;
    public static final int ENEMY_FLIES = 28;

    /** What the absolute numbers above are divided by, so that all of them sit in roughly nought to one. */
    public static final float HEALTH_SCALE = 100.0F;
    public static final float DAMAGE_SCALE = 20.0F;
    public static final float SPEED_SCALE = 0.5F;
    public static final float SIZE_SCALE = 4.0F;

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

    /**
     * Where a terrain cell sits within the grid itself, counting from the grid's own start: x fastest, then y, then z, with
     * the agent's feet block at the centre. Any body's grid is laid out this way, so the shared writer adds this to
     * wherever that body's grid begins.
     */
    public static int gridOffset(int x, int y, int z) {

        return (z * TERRAIN_Y + y) * TERRAIN_X + x;
    }

    /** The same, counting from the start of a humanoid's row. */
    public static int terrainOffset(int x, int y, int z) {

        return TERRAIN_OFFSET + gridOffset(x, y, z);
    }

}
