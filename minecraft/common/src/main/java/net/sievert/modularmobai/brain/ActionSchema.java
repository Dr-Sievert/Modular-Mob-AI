package net.sievert.modularmobai.brain;

import net.sievert.modularmobai.entity.control.MobControls;

/**
 * The layout of the action vector, which is one number per control and nothing else.
 *
 * <p>What comes back is the action already chosen, not a distribution over actions: the binary controls arrive as zero
 * or one and the hotbar slot arrives as an index. Sampling happens where the network is, so the mod never has to know
 * how an action was decided, only what it was.
 */
public final class ActionSchema {

    private ActionSchema() {}

    public static final int MOVE_FORWARD = 0;
    public static final int MOVE_STRAFE = 1;
    public static final int AIM_YAW = 2;
    public static final int AIM_PITCH = 3;

    public static final int JUMP = 4;
    public static final int SPRINT = 5;
    public static final int SNEAK = 6;
    public static final int ATTACK = 7;
    public static final int USE = 8;
    public static final int USE_OFFHAND = 9;

    public static final int SELECTED_SLOT = 10;

    public static final int ACT_DIM = 11;

    /** In index order, so the far side can name its heads without either half writing the list down twice. */
    public static final String[] NAMES = {
            "moveForward",
            "moveStrafe",
            "aimYaw",
            "aimPitch",
            "jump",
            "sprint",
            "sneak",
            "attack",
            "use",
            "useOffhand",
            "selectedSlot"
    };

    /** Anything at or above this counts as the button being held. */
    private static final float PRESSED = 0.5F;

    public static void apply(float[] actions, int base, MobControls out) {

        out.moveForward = actions[base + MOVE_FORWARD];
        out.moveStrafe = actions[base + MOVE_STRAFE];
        out.aimYaw = actions[base + AIM_YAW];
        out.aimPitch = actions[base + AIM_PITCH];

        out.jump = actions[base + JUMP] >= PRESSED;
        out.sprint = actions[base + SPRINT] >= PRESSED;
        out.sneak = actions[base + SNEAK] >= PRESSED;
        out.attack = actions[base + ATTACK] >= PRESSED;
        out.use = actions[base + USE] >= PRESSED;
        out.useOffhand = actions[base + USE_OFFHAND] >= PRESSED;

        // Rounded rather than truncated, so a slot that arrives as 2.999 is slot three and not slot two. The entity
        // clamps it again anyway, since nothing here should be able to put an agent into a slot that does not exist.
        out.selectedSlot = Math.round(actions[base + SELECTED_SLOT]);
    }
}
