package net.sievert.modularmobai.brain.schema;

import net.sievert.modularmobai.brain.nn.Heads;

/**
 * The layout of the <b>beast</b>: a body with no hands.
 *
 * <p>It moves, turns, jumps, sprints and bites, and that is the whole of it. There is no hotbar to see, no slot to choose,
 * nothing in either hand to hold up or draw, and so no use buttons either. That is the point of it: it is the second body,
 * and it differs from the humanoid in every way a body can — a narrower self block, a shorter echo, seven controls instead
 * of eleven, and <b>no categorical head at all</b>, which is the one shape of network the humanoid could never test.
 *
 * <p>What it does share is what is the same for anybody: the ten enemy slots and the terrain grid, whose meanings belong to
 * the world rather than to the body looking at it. Its encoder is {@link BeastObservation} and its descriptor is
 * {@link Beast}; the three of them together are a species, and they are as small as they are because the shared blocks do
 * the heavy lifting. See {@code docs/species.md}.
 */
public final class BeastSchema {

    private BeastSchema() {}

    // -----------------------------------------------------------------------------------------------------------
    // Self: the humanoid's, less everything that needs a hand
    // -----------------------------------------------------------------------------------------------------------

    public static final int SELF_SIZE = 16;

    public static final int SELF_HEALTH = 0;
    public static final int SELF_VELOCITY_FORWARD = 1;
    public static final int SELF_VELOCITY_UP = 2;
    public static final int SELF_VELOCITY_RIGHT = 3;
    public static final int SELF_ON_GROUND = 4;
    public static final int SELF_IN_WATER = 5;

    /** A bite is on the same cooldown a swing is, so the same number is worth as much here. */
    public static final int SELF_ATTACK_STRENGTH = 6;
    public static final int SELF_SPRINTING = 7;
    public static final int SELF_FALL_DISTANCE = 8;
    public static final int SELF_BODY_OFFSET_SIN = 9;
    public static final int SELF_BODY_OFFSET_COS = 10;
    public static final int SELF_PITCH = 11;

    /** The terrain grid is aligned to the world, so these are what line it up with everything else. */
    public static final int SELF_AIM_SIN = 12;
    public static final int SELF_AIM_COS = 13;
    public static final int SELF_HURT_TIME = 14;
    public static final int SELF_ENEMIES_IN_RANGE = 15;

    // -----------------------------------------------------------------------------------------------------------
    // Echo: what the body actually did, which for this one is moving and biting
    // -----------------------------------------------------------------------------------------------------------

    public static final int ECHO_SIZE = 11;

    // -----------------------------------------------------------------------------------------------------------
    // Where each block starts. The enemy slots and the terrain grid are the shared ones, at the shared shapes.
    // -----------------------------------------------------------------------------------------------------------

    public static final int SELF_OFFSET = 0;
    public static final int ECHO_OFFSET = SELF_OFFSET + SELF_SIZE;
    public static final int ENEMY_OFFSET = ECHO_OFFSET + ECHO_SIZE;
    public static final int TERRAIN_OFFSET = ENEMY_OFFSET + ObservationSchema.ENEMY_SIZE;

    public static final int OBS_DIM = TERRAIN_OFFSET + ObservationSchema.TERRAIN_SIZE;

    // -----------------------------------------------------------------------------------------------------------
    // What it can be asked to do
    // -----------------------------------------------------------------------------------------------------------

    public static final int MOVE_FORWARD = 0;
    public static final int MOVE_STRAFE = 1;
    public static final int AIM_YAW = 2;
    public static final int AIM_PITCH = 3;

    public static final int JUMP = 4;
    public static final int SPRINT = 5;
    public static final int ATTACK = 6;

    public static final int ACT_DIM = 7;

    /** In index order, so the far side can name them without either half writing the list down twice. */
    public static final String[] NAMES = {
            "moveForward",
            "moveStrafe",
            "aimYaw",
            "aimPitch",
            "jump",
            "sprint",
            "attack"
    };

    /**
     * Four continuous controls and three buttons, and nothing else. No categorical block, because there is nothing to
     * choose between: with no hands there is no slot, and a body that has nothing to pick up has nothing to pick.
     */
    public static final Heads HEADS = Heads.builder()
            .continuous("movement", AIM_PITCH - MOVE_FORWARD + 1)
            .binary("buttons", ATTACK - JUMP + 1)
            .build();

    static {

        Heads.Block[] blocks = HEADS.blocks();

        if (HEADS.actDim() != ACT_DIM || blocks.length != 2 || blocks[0].action() != MOVE_FORWARD
                || blocks[1].action() != JUMP) {

            throw new IllegalStateException("The beast's head table no longer lines up with its action layout");
        }
    }
}
