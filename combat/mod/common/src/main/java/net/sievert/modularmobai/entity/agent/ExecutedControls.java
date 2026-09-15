package net.sievert.modularmobai.entity.agent;

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

    /**
     * How far the item in use has charged, from 0 at the first tick of a use to 1 fully charged, and 0 with nothing in
     * use. For a bow that is the power the arrow would leave at, which is what a player watches the string for; for a
     * crossbow, how much of its wind is in; for anything with a use that simply runs its course, how much of it has run.
     *
     * <p>Held here rather than worked out from the entity because everything else in this record is: a use that started or
     * ended on this very tick is only knowable while the hands are being settled.
     */
    public float useProgress;

    /** The use went to the block under the aim and the block took it, which is what placing a block looks like here. */
    public boolean usedOnBlock;

    /**
     * An arrow or a bolt actually left: a drawn bow let go of with enough in the string to send one, or a loaded crossbow
     * fired. Not a use, and not the release of one either: a bow let go of at once sends nothing, and loading a crossbow is
     * a use that shoots nothing.
     *
     * <p>Nothing in any observation reads this, and nothing should. The echo a network is fed is a fixed layout that every
     * trained network depends on, and what an arrow did is already in the reward and in the enemy slots that see it fly. It
     * is here because this is the one record of what the body did on a tick, and the league writes down how many shots a
     * fight took; see {@link net.sievert.modularmobai.gametest.league.Behaviour}.
     */
    public boolean shotFired;

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
        this.useProgress = 0.0F;
        this.usedOnBlock = false;
        this.shotFired = false;
        this.selectedSlot = 0;
        this.swappedWeapon = false;
    }
}
