package net.sievert.modularmobai.entity.control;

import net.minecraft.util.Mth;

/**
 * What a brain asks an agent to do on a tick. This is the whole input surface of the entity: nothing else may move it.
 *
 * <p>The set is the one a player has at a keyboard and mouse, and nothing more. There is deliberately no control over the
 * body direction, because a player has none either: the body follows from where the agent looks and where it walks, and
 * handing the agent a third rotation would let it face a way no player can.
 *
 * <p>One instance is reused for the life of an agent rather than allocated per tick, since a training run ticks millions
 * of times. A brain is expected to write every field on every tick; nothing is cleared in between, so a field left alone
 * keeps whatever the previous tick put there.
 */
public final class MobControls {

    public static final int HOTBAR_SIZE = 9;

    /**
     * How far a full deflection of the aim controls turns the agent in one tick. Sixty degrees puts a half turn three
     * ticks away, which is about as fast as a player flicks a mouse, while still bounding what a single tick can do.
     */
    public static final float MAX_AIM_YAW_PER_TICK = 60.0F;
    public static final float MAX_AIM_PITCH_PER_TICK = 60.0F;

    /**
     * Vanilla only lets a player start sprinting while pushing forward, so a sideways or backward sprint is impossible.
     */
    public static final float SPRINT_FORWARD_THRESHOLD = 0.8F;

    /** Forward is positive, in the frame the agent is looking along. Clamped to [-1, 1] when applied. */
    public float moveForward;

    /** Strafing, positive to the left, matching the sign vanilla gives a player's left input. Clamped to [-1, 1]. */
    public float moveStrafe;

    public boolean jump;
    public boolean sprint;
    public boolean sneak;

    /** Aim change for this tick as a fraction of {@link #MAX_AIM_YAW_PER_TICK}, positive to the right. */
    public float aimYaw;

    /** Aim change for this tick as a fraction of {@link #MAX_AIM_PITCH_PER_TICK}, positive downward, as vanilla pitch is. */
    public float aimPitch;

    public boolean attack;

    /** Holding use on the main hand: raises a shield, draws a bow, eats. Released the tick this goes false. */
    public boolean use;

    /** The same for the off hand, which a player triggers with the same button but resolves separately. */
    public boolean useOffhand;

    /** Which hotbar slot to hold, 0 to {@link #HOTBAR_SIZE} - 1. Out of range values are clamped. */
    public int selectedSlot;

    /**
     * Drops every input back to neutral. Called when an episode starts so no intent survives a reset; it is not called
     * per tick, because holding a key down is a thing a player does.
     */
    public void clear() {

        this.moveForward = 0.0F;
        this.moveStrafe = 0.0F;
        this.jump = false;
        this.sprint = false;
        this.sneak = false;
        this.aimYaw = 0.0F;
        this.aimPitch = 0.0F;
        this.attack = false;
        this.use = false;
        this.useOffhand = false;
        this.selectedSlot = 0;
    }

    public float clampedForward() {

        return Mth.clamp(this.moveForward, -1.0F, 1.0F);
    }

    public float clampedStrafe() {

        return Mth.clamp(this.moveStrafe, -1.0F, 1.0F);
    }
}
