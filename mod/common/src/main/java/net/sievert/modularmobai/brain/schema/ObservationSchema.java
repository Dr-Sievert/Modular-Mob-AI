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

    public static final int SELF_SIZE = 22;

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

    /**
     * How much of this fight's clock has run: nought at the first tick, one once the arena's time is up. Written from the
     * reward's own count, {@code AgentReward#elapsedFraction}, so the network reads the clock it is being paid by.
     *
     * <p>The reward is a function of the clock and nothing in the observation said so. A fight is capped at 1,200 ticks,
     * running the clock out is scored as a loss, and a win pays a speed bonus that scales with how much clock is left, so
     * the same position on the ground is worth about +3 at tick 100 and -2 at tick 1,150. The critic could not price that
     * difference at all, which put the noisiest advantages in exactly the fights that drag on, and the policy could only
     * learn urgency by counting to 1,200 inside the GRU. See {@link AgentObservation#clock}.
     */
    public static final int SELF_CLOCK = 20;

    /**
     * How many shots are left, over {@link #ARROW_SCALE}, and nought for a body carrying nothing that shoots. See
     * {@link AgentObservation#arrows}.
     */
    public static final int SELF_ARROWS = 21;

    /** A full quiver: the 64 arrows a bow or crossbow loadout carries, see {@code arena/Loadout}. */
    public static final float ARROW_SCALE = 64.0F;

    // -----------------------------------------------------------------------------------------------------------
    // Hotbar and the executed action echo
    // -----------------------------------------------------------------------------------------------------------

    /** One coarse item category per slot. A fixed loadout means the agent can learn what lives where. */
    public static final int HOTBAR_SIZE = MobControls.HOTBAR_SIZE;

    public static final int ECHO_SIZE = 20;

    // -----------------------------------------------------------------------------------------------------------
    // Where each block starts
    // -----------------------------------------------------------------------------------------------------------

    // -----------------------------------------------------------------------------------------------------------
    // How far it can see, past the four blocks of the grid
    //
    // The grid is a block a cell and nine cells across, so it reaches four blocks. Everything the agent does about ground
    // beyond that it does blind: where the lava is, which way the cliff runs, whether there is a wall at its back. The
    // limit has been hit before and patched around once already — an empty cell at the bottom of the grid looks seven
    // blocks further down, because two was not enough to see a ravine.
    //
    // Widening the grid is the expensive answer. Doubling its reach is 1,445 cells against 405, three times the encoder's
    // first layer and three and a half times the most expensive thing an agent does per tick, nearly all of it to say that
    // air is air. So instead eight rays go out from the feet, one every forty five degrees, and each says how far it is to
    // the three things worth knowing about. Four times the reach for twenty four numbers and about a sixth more scanning.
    // -----------------------------------------------------------------------------------------------------------

    /** How many ways it looks: the four compass directions and the four between them, world aligned as the grid is. */
    public static final int RAYS = 8;

    /** What each ray reports, all as a fraction of RAY_REACH, and 1 where the ray found no such thing. */
    public static final int RAY_STRIDE = 3;
    public static final int RAY_WALL = 0;
    public static final int RAY_HAZARD = 1;
    public static final int RAY_DROP = 2;

    public static final int RAY_SIZE = RAYS * RAY_STRIDE;

    /**
     * How far a ray reaches: thirty two blocks, the same distance an enemy slot holds an opponent at, so that ground and
     * bodies are seen to the same horizon rather than the ground stopping at four blocks.
     *
     * <p>Sampled finely near and coarsely far, which is where the reach is bought: every {@link #RAY_STEP} blocks out to
     * {@link #RAY_FINE}, then every {@link #RAY_COARSE_STEP}. Twelve samples a ray reach thirty two blocks where an even
     * two-block step would need sixteen, and the far half of a ray only has to say which way a thing lies, not its outline.
     * A ray also stops at the first wall, so most of them cost far less than the worst case.
     */
    public static final int RAY_REACH = 32;
    public static final int RAY_FINE = 16;
    public static final int RAY_STEP = 2;
    public static final int RAY_COARSE_STEP = 4;

    /** How far below the feet counts as a drop worth reporting, which is what a body takes real damage falling. */
    public static final int RAY_DROP_DEPTH = 4;

    public static final int SELF_OFFSET = 0;
    public static final int HOTBAR_OFFSET = SELF_OFFSET + SELF_SIZE;
    public static final int ECHO_OFFSET = HOTBAR_OFFSET + HOTBAR_SIZE;
    public static final int ENEMY_OFFSET = ECHO_OFFSET + ECHO_SIZE;
    public static final int TERRAIN_OFFSET = ENEMY_OFFSET + ENEMY_SIZE;
    public static final int RAY_OFFSET = TERRAIN_OFFSET + TERRAIN_SIZE;

    public static final int OBS_DIM = RAY_OFFSET + RAY_SIZE;

    /** Where one ray's three numbers start, counting from the start of the rays. */
    public static int rayOffset(int ray) {

        return RAY_OFFSET + ray * RAY_STRIDE;
    }

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
