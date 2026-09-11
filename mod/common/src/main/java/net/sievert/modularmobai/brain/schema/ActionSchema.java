package net.sievert.modularmobai.brain.schema;

import net.sievert.modularmobai.brain.nn.Heads;
import net.sievert.modularmobai.entity.agent.MobControls;

/**
 * The layout of the action vector, which is one number per control and nothing else.
 *
 * <p>What a brain hands over is the action already chosen, not a distribution over actions: the binary controls arrive
 * as zero or one and the hotbar slot arrives as an index. How it was chosen is the brain's business. The network's side
 * of that is {@link #HEADS}, which says how its outputs turn into these values.
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

    /**
     * How the network's outputs become the values above: the four movement and aim controls are continuous, the six
     * buttons are independent yes or no choices, and the slot is one choice of nine with empty slots masked off. All
     * three are decided on the same tick, independently of each other.
     */
    public static final Heads HEADS = Heads.builder()
            .continuous("movement", AIM_PITCH - MOVE_FORWARD + 1)
            .binary("buttons", USE_OFFHAND - JUMP + 1)
            .categorical("slot", MobControls.HOTBAR_SIZE, ObservationSchema.HOTBAR_OFFSET)
            .build();

    static {

        Heads.Block[] blocks = HEADS.blocks();

        if (HEADS.actDim() != ACT_DIM || blocks[0].action() != MOVE_FORWARD || blocks[1].action() != JUMP
                || blocks[2].action() != SELECTED_SLOT) {

            throw new IllegalStateException("The head table no longer lines up with the action layout");
        }
    }

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
