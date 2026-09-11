package net.sievert.modularmobai.entity.control;

/**
 * What actually happened when a tick's {@link MobControls} were applied.
 *
 * <p>Intent and outcome come apart constantly: a jump asked for in mid air does not happen, a sprint asked for while
 * walking backwards is refused, an aim change is cut short by the turn limit, a swing lands on nothing. The brain is fed
 * this record rather than its own request, so that it learns the rules of the body it is driving instead of assuming
 * every command took effect.
 *
 * <p>Every field is filled at the moment the control is applied, never reconstructed afterwards from entity state, which
 * would silently fold in whatever the world did to the agent in the same tick.
 *
 * <p>Reused for the life of an agent, like the intent buffer it mirrors.
 */
public final class ExecutedControls {

    /** The movement actually handed to the movement code, after clamping and the sneak slowdown. */
    public float moveForward;
    public float moveStrafe;

    /** True only if the agent was actually standing on something and left it. */
    public boolean jumped;

    /** Sprint and sneak as they stood after vanilla's rules had their say, not as they were asked for. */
    public boolean sprinting;
    public boolean sneaking;

    /** Degrees actually turned, which is the request cut down by the per tick limit and, for pitch, the vertical stop. */
    public float aimYawDegrees;
    public float aimPitchDegrees;

    /** A swing happened. Missing still counts, and still costs the attack cooldown, exactly as it does for a player. */
    public boolean attacked;

    /** The swing found something in reach along the line of sight and hurt it. */
    public boolean attackHit;

    /** The attack cooldown at the instant of the swing, from 0 at a fresh swap to 1 fully recovered. */
    public float attackStrength;

    /** Damage actually dealt, before the target's armour and resistances but after the cooldown scaling and criticals. */
    public float attackDamage;

    /** The swing was a critical, a sweep, or carried the extra sprint knockback. */
    public boolean attackCritical;
    public boolean attackSweep;
    public boolean attackSprintKnockback;

    /** Use is only true while an item is genuinely being used; asking to use an empty hand does nothing. */
    public boolean using;
    public boolean usingOffhand;

    /** The use went to the block under the aim and the block took it, which is what placing a block looks like here. */
    public boolean usedOnBlock;

    /** The slot actually held once the swap resolved, which is the requested one clamped into the hotbar. */
    public int selectedSlot;

    /** The swap changed the kind of item held, which is what restarts a player's attack cooldown. */
    public boolean swappedWeapon;

    public void clear() {

        this.moveForward = 0.0F;
        this.moveStrafe = 0.0F;
        this.jumped = false;
        this.sprinting = false;
        this.sneaking = false;
        this.aimYawDegrees = 0.0F;
        this.aimPitchDegrees = 0.0F;
        this.attacked = false;
        this.attackHit = false;
        this.attackStrength = 0.0F;
        this.attackDamage = 0.0F;
        this.attackCritical = false;
        this.attackSweep = false;
        this.attackSprintKnockback = false;
        this.using = false;
        this.usingOffhand = false;
        this.usedOnBlock = false;
        this.selectedSlot = 0;
        this.swappedWeapon = false;
    }
}
