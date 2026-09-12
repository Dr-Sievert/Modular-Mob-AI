package net.sievert.modularmobai.entity.agent;

import java.util.List;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.LookControl;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemCooldowns;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.GameMasterBlock;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.sievert.modularmobai.Config;
import net.sievert.modularmobai.Constants;
import net.sievert.modularmobai.allegiance.Allegiance;
import net.sievert.modularmobai.arena.Episode;
import net.sievert.modularmobai.arena.Loadout;
import net.sievert.modularmobai.arena.Loadouts;
import net.sievert.modularmobai.brain.Brain;
import net.sievert.modularmobai.brain.BrainState;
import net.sievert.modularmobai.brain.Brains;
import net.sievert.modularmobai.brain.schema.Species;
import net.sievert.modularmobai.entity.ModEntities;
import net.sievert.modularmobai.mixin.ProjectileWeaponItemInvoker;

/**
 * A mob driven entirely from {@link MobControls}, shaped so that what a brain learns here transfers to fighting a player.
 *
 * <p>It has a player's reach, a player's attack cooldown, a player's hotbar and a player's rotation limits, and it uses
 * its items under a player's rules: bows and crossbows fire as a player's do, shields block and are knocked aside by
 * axes, blocks break over time and go down where they are aimed. It has none of the rest of the machinery a real player
 * carries. Every tick it reads the intent buffer, applies it under vanilla's rules, and writes down what actually
 * happened in {@link ExecutedControls}.
 *
 * <p>Much of vanilla's item code only works for a {@link net.minecraft.world.entity.player.Player}: a bow only fires for
 * one, a shield is only knocked aside for one, a block only cracks for one. Where that is so, the player's rule is written
 * out here with the agent in the player's place, and the item's own code is still what does the work wherever it can
 * be. What goes through a Player and nothing else, statistics, hunger, a block's own reaction to being broken by a
 * player, is left out.
 *
 * <p>It never runs its own brain. The level's driver fills the intent buffer before this ticks, from the memory held in
 * {@link #brain()}; the entity only applies what it is given and reports what happened to its body.
 */
public class AgentMob extends PathfinderMob {

    private static final String TAG_HOTBAR = "Hotbar";
    private static final String TAG_SELECTED_SLOT = "SelectedSlot";

    // Not "Brain": vanilla already saves every living entity's own Brain, its memories, under that key.
    private static final String TAG_BRAIN_NAME = "BrainName";
    private static final String TAG_LOADOUT = "Loadout";

    private static final float DEGREES_TO_RADIANS = (float) (Math.PI / 180.0D);

    /**
     * How long a use has to wait before it fires again. The client sets exactly this delay every time the use button
     * resolves, which is what stops a held button placing a block on every single tick.
     */
    public static final int USE_INTERVAL = 4;

    /**
     * What an item in use leaves of the movement keys: a fifth, which a player's client takes off the keys themselves while
     * a bow is drawn, a shield held up or something eaten.
     */
    private static final float USING_ITEM_MOVEMENT = 0.2F;

    /** How long an axe knocks a raised shield aside for, every shield at once, as it does a player's. */
    public static final int SHIELD_DISABLED_TICKS = 100;

    /** How long a player's hand rests after a block has given way before the next one starts to crack. */
    private static final int DESTROY_DELAY = 5;

    /**
     * The charge a draw is committed up to, as {@link #useProgress} reads it: a bow's full power, a crossbow's finished
     * wind. Just under one, because both are the item's own numbers and neither is asked to land on one exactly.
     */
    private static final float FULL_DRAW = 0.999F;

    /** A drawn bow's arrow speed at full power, a crossbow's bolt and firework, and the spread, all a player's. */
    private static final float BOW_SPEED = 3.0F;
    private static final float CROSSBOW_ARROW_SPEED = 3.15F;
    private static final float CROSSBOW_FIREWORK_SPEED = 1.6F;
    private static final float PLAYER_INACCURACY = 1.0F;

    /** A player's crouch, so the hitbox shrinks and the model ducks the way anyone watching the fight would expect. */
    private static final EntityDimensions CROUCHING_DIMENSIONS = EntityDimensions.scalable(0.6F, 1.5F).withEyeHeight(1.27F);

    private final MobControls controls = new MobControls();
    private final ExecutedControls executed = new ExecutedControls();

    private final BrainState brain = new BrainState();

    /** The fight an arena put this agent in, if any. Out in the world there is none. */
    @Nullable
    private Episode episode;

    private final NonNullList<ItemStack> hotbar = NonNullList.withSize(MobControls.HOTBAR_SIZE, ItemStack.EMPTY);
    private int selectedSlot;

    /** Counts up every tick and restarts on a swing or a change of weapon, exactly as a player's does. */
    private int attackStrengthTicker;
    private ItemStack lastItemInMainHand = ItemStack.EMPTY;

    /** Shared between the hands, as the client's is, so alternating them cannot interact twice as fast as a player can. */
    private int useCooldown;

    /** Items that answer to nothing for a while, which for the agent means a shield an axe has just knocked aside. */
    private ItemCooldowns itemCooldowns = new ItemCooldowns();

    // The block being broken, kept the way a player's client keeps it: where it is, what it is being broken with, how far
    // along it is, and which stage of the crack the rest of the world was last shown. No block is being broken while the
    // position is null.
    @Nullable
    private BlockPos destroyPos;
    private ItemStack destroyingItem = ItemStack.EMPTY;
    private float destroyProgress;
    private int destroyDelay;
    private int destroyStage = -1;

    /**
     * Whether this is the training registration rather than the shipped one. Everything the brain sees and does is the
     * same either way; this only decides how the world treats the body when nobody is driving it.
     */
    private final boolean training;

    /**
     * The brain this agent was given by name, see Brains#named, which it keeps through saving. Null follows whatever the
     * game's default is; see Brains#forAgent. The brain itself is never saved, only this, since weights are shared and
     * found again by name.
     */
    @Nullable
    private String brainName;

    /** The loadout it was last armed with by name, for /mmai info. What it carries is the hotbar, saved as it stands. */
    @Nullable
    private String loadoutName;

    public AgentMob(EntityType<? extends PathfinderMob> type, Level level) {

        super(type, level);

        this.training = EntityType.getKey(type).equals(ModEntities.TRAINING_AGENT_ID);

        // Both of the vanilla controls fight the controller on every tick if they are left in place: the look control
        // snaps the pitch back to zero and drags the head toward the body, and the move control zeroes the forward input
        // whenever it has nothing of its own to do.
        this.lookControl = new InertLookControl(this);
        this.moveControl = new InertMoveControl(this);
    }

    /**
     * Player shaped, so that what is learned here transfers to fighting a player rather than to fighting a mob with a
     * mob's reach and speed. Everything from the attack speed on is an attribute vanilla only gives to players: without
     * them the agent has no attack cooldown, no reach and no sweep, and breaks blocks at no speed a player would know.
     */
    public static AttributeSupplier.Builder createAttributes() {

        return LivingEntity.createLivingAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.1D)
                .add(Attributes.ATTACK_DAMAGE, 1.0D)
                .add(Attributes.ATTACK_KNOCKBACK, 0.0D)
                .add(Attributes.FOLLOW_RANGE, 32.0D)
                .add(Attributes.STEP_HEIGHT, 0.6D)
                .add(Attributes.ATTACK_SPEED)
                .add(Attributes.ENTITY_INTERACTION_RANGE, 3.0D)
                .add(Attributes.BLOCK_INTERACTION_RANGE, 4.5D)
                .add(Attributes.SWEEPING_DAMAGE_RATIO)
                .add(Attributes.SNEAKING_SPEED)
                .add(Attributes.BLOCK_BREAK_SPEED)
                .add(Attributes.MINING_EFFICIENCY)
                .add(Attributes.SUBMERGED_MINING_SPEED);
    }

    @Override
    protected void registerGoals() {
        // Nothing. The controller is the only thing that may move this entity.
    }

    public MobControls controls() {

        return this.controls;
    }

    /**
     * Which body this is, and so what it sees and what it can be asked to do. A brain is trained for one species and is
     * refused any other, so this is what decides whether a set of weights may drive this mob at all.
     *
     * <p>A second body overrides this and nothing else about the plumbing: the driver batches by brain, the observation is
     * filled in by the species, and the action comes back through it. See {@code docs/species.md}.
     */
    public Species species() {

        return Species.HUMANOID;
    }

    public ExecutedControls executed() {

        return this.executed;
    }

    /** What the agent's brain remembers between ticks, and which brain that is. */
    public BrainState brain() {

        return this.brain;
    }

    /** The name of the brain this agent was given, or null when it follows the game's default. */
    @Nullable
    public String brainName() {

        return this.brainName;
    }

    /**
     * Gives the agent a brain of its own by name, kept through saving, or with null hands it back to the game's default.
     * The name is resolved here and now, so one that leads nowhere is refused before anything about the agent changes,
     * and the agent starts on its new brain with a fresh memory.
     *
     * @throws IllegalArgumentException for a name that is no brain; see Brains#named
     */
    public void setBrainName(@Nullable String name) {

        Brain chosen = name == null ? null : Brains.named(name);

        this.brainName = name == null ? null : name.trim();
        this.brain.use(chosen);
    }

    @Nullable
    public String loadoutName() {

        return this.loadoutName;
    }

    /** Arms the agent with a loadout and remembers its name. */
    public void equip(Loadout loadout) {

        loadout.equip(this);
        this.loadoutName = loadout.name();
    }

    @Nullable
    public Episode episode() {

        return this.episode;
    }

    /**
     * Puts the agent into a fresh fight: intent, memory and cooldowns all start again, and whatever it is paid from here
     * on goes to the given episode.
     */
    public void startEpisode(Episode episode) {

        this.controls.clear();
        this.executed.clear();
        this.brain.reset();
        this.attackStrengthTicker = 0;
        this.useCooldown = 0;
        this.itemCooldowns = new ItemCooldowns();
        this.destroyPos = null;
        this.destroyProgress = 0.0F;
        this.destroyDelay = 0;
        this.destroyStage = -1;
        this.episode = episode;
    }

    /** How many ticks are left before a use will fire again. */
    public int useCooldown() {

        return this.useCooldown;
    }

    /** Which items cannot be used yet, the way a player's cooldowns say it. */
    public ItemCooldowns itemCooldowns() {

        return this.itemCooldowns;
    }

    /** Damage that actually got through, which is what the reward is scaled against rather than the amount swung for. */
    @Override
    protected void actuallyHurt(DamageSource source, float amount) {

        super.actuallyHurt(source, amount);

        if (this.episode != null) {

            this.episode.reward().damageTaken(amount, this.getMaxHealth());
        }
    }

    /**
     * Health the agent took off something, however it did it: a swing, an arrow, a crossbow bolt, a sweep. Called from
     * where the damage lands (see LivingEntityMixin), the one place every kind of it passes through, so nothing is paid
     * twice. What is paid is what came off, the same as damage taken is counted: a finishing blow pays only the health
     * that was left, so hitting harder than a kill needs earns nothing extra and a kill is worth one health bar however it
     * was done. Hurting an ally is never paid, whatever the episode pays for; with friendly fire on, it still happens.
     */
    public void dealtDamage(LivingEntity target, float healthRemoved) {

        if (this.episode != null && target != this && healthRemoved > 0.0F && this.episode.pays(target)
                && !Allegiance.allied(this, target)) {

            this.episode.reward().damageDealt(healthRemoved, target.getMaxHealth());
        }
    }

    /**
     * Dying ends the episode by itself. Winning cannot, because only the arena knows what winning was supposed to mean,
     * so that half is called from the test.
     */
    @Override
    public void die(DamageSource cause) {

        if (this.episode != null) {

            this.episode.reward().lost();
        }

        super.die(cause);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The control loop
    // ---------------------------------------------------------------------------------------------------------------

    @Override
    protected void customServerAiStep() {

        this.executed.clear();

        if (this.episode != null) {

            this.episode.reward().tick();
        }

        if (this.useCooldown > 0) {

            this.useCooldown--;
        }

        boolean wasSprinting = this.isSprinting();

        this.idleWhenAlone();
        this.applySelectedSlot();
        this.applyAim();
        this.applyMovement();
        this.applyHands();
        this.slowForItemUse(wasSprinting);

        // Vanilla resolves a player's attack from its packet before aiStep advances the cooldown and notices a change of
        // weapon, so both of those happen after the controls above rather than before them. A player's item cooldowns
        // tick after it has moved, too.
        this.attackStrengthTicker++;
        this.trackMainHandForCooldown();
        this.itemCooldowns.tick();
    }

    /**
     * An agent out in a real game with nobody in view stands where it is, swimming up in water as any mob does, whatever
     * its brain said, and meets its next opponent with a fresh memory.
     *
     * <p>A network has only ever been trained with an opponent in view from its first tick to its last, so what it does
     * with nobody there is anything at all, and a walk off into the distance is typical: by the time something worth
     * fighting turned up it would be out of sight. Standing still keeps it where it was put, and restarting its memory
     * means the next fight begins the way every training fight began. The brain is still asked every tick, so the moment
     * anything takes a slot in its view it is acting on it.
     *
     * <p>Only out in the world. An arena always has an opponent, and a mechanics test drives its training agent with
     * nobody about on purpose.
     */
    private void idleWhenAlone() {

        if (this.training || this.episode != null || !this.brain.enemySlots().isEmpty()) {

            return;
        }

        int slot = this.controls.selectedSlot;

        this.controls.clear();
        this.controls.selectedSlot = slot;
        this.controls.jump = this.isInWater() && this.getFluidHeight(FluidTags.WATER) > this.getFluidJumpThreshold()
                || this.isInLava();

        this.brain.restart();
    }

    private void applySelectedSlot() {

        int slot = Mth.clamp(this.controls.selectedSlot, 0, MobControls.HOTBAR_SIZE - 1);

        if (slot != this.selectedSlot) {

            this.selectedSlot = slot;
            this.syncMainHand();
        }

        this.executed.selectedSlot = this.selectedSlot;
    }

    private void applyAim() {

        float yawDelta = Mth.clamp(this.controls.aimYaw, -1.0F, 1.0F) * MobControls.MAX_AIM_YAW_PER_TICK;
        float pitchDelta = Mth.clamp(this.controls.aimPitch, -1.0F, 1.0F) * MobControls.MAX_AIM_PITCH_PER_TICK;

        float pitchBefore = this.getXRot();
        float pitch = Mth.clamp(pitchBefore + pitchDelta, -90.0F, 90.0F);
        float yaw = Mth.wrapDegrees(this.getYRot() + yawDelta);

        this.setYRot(yaw);
        this.setXRot(pitch);
        // A mob aims and renders from its head rotation, which nothing else here would move.
        this.setYHeadRot(yaw);

        this.executed.aimYawDegrees = yawDelta;
        this.executed.aimPitchDegrees = pitch - pitchBefore;
    }

    private void applyMovement() {

        boolean sneak = this.controls.sneak;
        this.setShiftKeyDown(sneak);

        if (sneak && this.getPose() == Pose.STANDING) {

            this.setPose(Pose.CROUCHING);
        }

        else if (!sneak && this.getPose() == Pose.CROUCHING) {

            this.setPose(Pose.STANDING);
        }

        float forward = this.controls.clampedForward();
        float strafe = this.controls.clampedStrafe();

        // Sprinting is forward only and cannot be combined with a crouch, which is what stops a player sprinting sideways
        // or backwards out of a fight. Nor can a sprint start while an item is in use, though one already running carries
        // on through it, as a player's does.
        boolean sprint = this.controls.sprint && !sneak && forward >= MobControls.SPRINT_FORWARD_THRESHOLD
                && (!this.isUsingItem() || this.isSprinting());
        this.setSprinting(sprint);

        if (sneak) {

            float slowdown = (float) this.getAttributeValue(Attributes.SNEAKING_SPEED);
            forward *= slowdown;
            strafe *= slowdown;
        }

        // travel() moves the entity at whatever speed was last set rather than reading the attribute itself, and nothing
        // sets it for a mob with no navigation running. It has to be set before the inputs and not after them: on a mob,
        // setSpeed also writes the speed into the forward input, which is how the vanilla move control walks a mob along
        // its path, so setting it last would throw the brain's forward input away and leave the agent creeping ahead
        // whatever it asked for. It comes after the sprint, since sprinting is a modifier on the attribute read here.
        this.setSpeed((float) this.getAttributeValue(Attributes.MOVEMENT_SPEED));

        this.setZza(forward);
        this.setXxa(strafe);

        // Held jump goes through whenever it is held, the way a player's space bar does, and vanilla works out from where
        // the body is what that means: a jump off the ground, swimming up through water, climbing a ladder. Passed on
        // only while standing on something, it left an agent in deep water with no way back up, and it drowned.
        boolean lifted = this.onGround() || this.isInLiquid() || this.onClimbable();

        if (this.controls.jump) {

            // Going through the jump control rather than setJumping, because that control ticks after this method and
            // would otherwise overwrite the flag before the jump is read.
            this.getJumpControl().jump();
        }

        this.executed.moveForward = forward;
        this.executed.moveStrafe = strafe;
        this.executed.jumped = this.controls.jump && lifted;
        this.executed.sprinting = sprint;
        this.executed.sneaking = sneak;
    }

    /**
     * How hard the agent can steer in the air, which is a player's figure rather than a mob's. Vanilla gives every mob the
     * same fixed amount, and a player a little more while sprinting, which is what makes a sprint jump carry; without it
     * the agent's sprint jumps fall short of the ones it is meant to learn to match.
     */
    @Override
    protected float getFlyingSpeed() {

        return this.isSprinting() ? 0.025999999F : 0.02F;
    }

    /**
     * An item in use takes the movement keys down to a fifth. A player's client does that to the keys before it moves,
     * and it knows by then whether the use started or ended on this tick, so it is done here, once the hands have
     * settled that, by rewriting what movement handed over.
     */
    private void slowForItemUse(boolean wasSprinting) {

        if (!this.isUsingItem()) {

            return;
        }

        // Movement went first and could not know that a use was about to start, so a sprint it started on this very tick
        // is taken back: none can start with an item in use.
        if (this.isSprinting() && !wasSprinting) {

            this.setSprinting(false);
            this.executed.sprinting = false;

            // Before the inputs, for the reason applyMovement gives.
            this.setSpeed((float) this.getAttributeValue(Attributes.MOVEMENT_SPEED));
        }

        if (!this.isPassenger()) {

            this.executed.moveForward *= USING_ITEM_MOVEMENT;
            this.executed.moveStrafe *= USING_ITEM_MOVEMENT;
        }

        this.setZza(this.executed.moveForward);
        this.setXxa(this.executed.moveStrafe);
    }

    /**
     * Attack and use, in the order a player's client resolves them. An item in use is let go first if its button is up,
     * and a tick that began with an item in use swallows the attack whole: nothing is struck, swung at or broken while a
     * bow is drawn or a shield is up, and nothing new is started either. Otherwise the attack goes before the use, so a
     * blow and a raised shield can land in the same tick, and last of all a held attack keeps working at whatever block
     * it is on.
     */
    private void applyHands() {

        boolean busy = this.isUsingItem();

        if (busy && !this.wantsToUse(this.getUsedItemHand()) && !this.drawingToFull()) {

            // Released rather than cancelled, so that a drawn bow actually fires.
            this.releaseUsingItem();
        }

        if (!this.isUsingItem()) {

            // What is under the aim, found once for the tick the way the game finds what is under a player's crosshair,
            // and only looked for while attack is held. A use looks for itself, and only on the ticks it fires.
            Entity aimedEntity = this.controls.attack ? this.pickAimedEntity() : null;

            // A block already cracking is looked for whether or not the button is down this tick, so that letting go for a
            // tick keeps the crack instead of throwing it away: see continueDestroying.
            BlockHitResult aimedBlock = (this.controls.attack || this.destroyPos != null) && aimedEntity == null
                    ? this.pickAimedBlock() : null;

            boolean brokeAtTouch = false;

            if (!busy) {

                brokeAtTouch = this.applyAttack(aimedEntity, aimedBlock);
                this.applyUse(InteractionHand.MAIN_HAND, this.controls.use);
                this.applyUse(InteractionHand.OFF_HAND, this.controls.useOffhand);
            }

            // A block that broke at the touch has done its work for the tick, and a use that has just started keeps the
            // hands busy.
            if (!this.isUsingItem()) {

                this.continueDestroying(brokeAtTouch ? null : aimedBlock);
            }
        }

        boolean using = this.isUsingItem();
        this.executed.using = using && this.getUsedItemHand() == InteractionHand.MAIN_HAND;
        this.executed.usingOffhand = using && this.getUsedItemHand() == InteractionHand.OFF_HAND;
        this.executed.useProgress = using ? this.useProgress() : 0.0F;
    }

    /**
     * How far the item in use has charged, as a player sees it: the pull of a bow's string, the fill of a crossbow's
     * charge bar, or how much of an ordinary use has run. Every number is the item's own, so nothing here decides how long
     * anything takes.
     *
     * <p>A bow gives back the power its arrow would leave at rather than the plain fraction of the twenty ticks, because
     * power is what the draw is for and it is not linear in the time: half the draw is a third of the power. A crossbow
     * gives the fraction of its wind, since a wind is all or nothing and what matters is how much is left. Anything else,
     * food and a shield included, gives how much of its use duration has passed, which for a shield is nearly nothing
     * however long it is held: a shield does not charge, and the raised flag already says it is up.
     */
    private float useProgress() {

        ItemStack stack = this.getUseItem();
        int ticks = this.getTicksUsingItem();

        if (stack.getItem() instanceof BowItem) {

            return BowItem.getPowerForTime(ticks);
        }

        if (stack.getItem() instanceof CrossbowItem) {

            return Math.min(1.0F, ticks / (float) Math.max(1, CrossbowItem.getChargeDuration(stack, this)));
        }

        int duration = stack.getUseDuration(this);

        return duration <= 0 ? 0.0F : Math.min(1.0F, ticks / (float) duration);
    }

    private boolean wantsToUse(InteractionHand hand) {

        return hand == InteractionHand.MAIN_HAND ? this.controls.use : this.controls.useOffhand;
    }

    /**
     * Whether a draw already under way keeps going even though its button came up. True for a bow or a crossbow that has
     * not finished charging, and for nothing else: a shield drops the tick it is let go, food stops being eaten, and a
     * weapon at full charge is the agent's own to hold or to loose.
     *
     * <p>This is the one place the agent's hands are not a player's, and it is here because a draw is a single skill that
     * arrives twenty ticks late. A player holds the button down through those ticks without thinking about it. A network
     * chooses the button afresh every tick from a probability, so a full draw asks it to choose the same thing twenty
     * times over, and the odds of that happening by chance are the odds of pressing it once raised to the twentieth power.
     * A network at even odds gets there once in a million draws, which is never, so a weapon that takes a draw can never
     * be discovered by trying: the league's first bow network started forty-two draws a fight and loosed five weak
     * arrows, and its crossbow, which fires nothing at all short of a full wind, started forty-one loads a fight and fired
     * three bolts in a hundred fights. Committing the draw makes one press one arrow, which is a thing a policy can find.
     *
     * <p>Nothing is taken away by it. Changing slot still drops the draw, because the hand no longer holds what it started,
     * which is how the teacher gives up a shot to swing instead. The movement it costs is still a fifth of the keys for
     * every tick of it, so drawing at the wrong moment is still paid for.
     */
    private boolean drawingToFull() {

        Item item = this.getUseItem().getItem();

        if (!(item instanceof BowItem) && !(item instanceof CrossbowItem)) {

            return false;
        }

        return this.useProgress() < FULL_DRAW;
    }

    /**
     * A use resolves the way a player's right click does: whatever is under the aim gets first refusal, and only if it
     * declines does the item itself get used. That is the whole reason placing a block is not a control of its own, and
     * it is why aiming matters as much for use as it does for attacking.
     *
     * <p>The one branch that is missing is interacting with an entity, and with the block itself, since both of those go
     * through methods that take a Player. What is left is the item's own reaction to a block, which is exactly the path
     * a block is placed down.
     */
    private void applyUse(InteractionHand hand, boolean wanted) {

        // An item already being used keeps the hands busy, a use that just fired has to wait its interval out, and a
        // player cannot start using anything at all while it is breaking a block.
        if (!wanted || this.isUsingItem() || this.useCooldown > 0 || this.destroyPos != null) {

            return;
        }

        this.useCooldown = USE_INTERVAL;

        ItemStack stack = this.getItemInHand(hand);

        // An item that is cooling down, a shield an axe has just knocked aside, answers to nothing, on a block or alone.
        if (stack.isEmpty() || this.itemCooldowns.isOnCooldown(stack.getItem())) {

            return;
        }

        // A mob under the aim takes a player's use before any block behind it, and since a mob only answers a player, the
        // item is then left to act on its own: nothing is placed through a body.
        BlockHitResult aimed = this.pickAimedEntity() == null ? this.pickAimedBlock() : null;

        if (aimed != null && aimed.getType() != HitResult.Type.MISS) {

            InteractionResult result = stack.useOn(new AgentUseOnContext(this, hand, stack, aimed));

            if (result.consumesAction()) {

                this.swing(hand);
                this.executed.usedOnBlock = true;
                return;
            }

            if (result == InteractionResult.FAIL) {

                return;
            }
        }

        this.useOnItsOwn(hand, stack);
    }

    /**
     * What an item does when nothing under the aim took the use, which vanilla decides in Item#use, a method that takes a
     * Player. The bow and the crossbow are worked through as they are for a player. Everything else starts being used if
     * it has a use animation, which covers shields, food and potions.
     */
    private void useOnItsOwn(InteractionHand hand, ItemStack stack) {

        if (stack.getItem() instanceof CrossbowItem crossbow && CrossbowItem.isCharged(stack)) {

            this.fireCrossbow(crossbow, hand, stack);
            return;
        }

        // No arrow, no draw: a bow, or a crossbow with nothing to load, does not even come up.
        if (stack.getItem() instanceof ProjectileWeaponItem && this.getProjectile(stack).isEmpty()) {

            return;
        }

        if (stack.getUseAnimation() != UseAnim.NONE) {

            this.startUsingItem(hand);
        }
    }

    /** The block under the aim, within the reach a player has for blocks rather than the shorter one it has for mobs. */
    private BlockHitResult pickAimedBlock() {

        double reach = this.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE);
        Vec3 eye = this.getEyePosition();
        Vec3 end = eye.add(this.getViewVector(1.0F).scale(reach));

        return this.level().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, this));
    }

    /**
     * A press of attack, which strikes whatever is under the aim: a mob is hit, a block starts to break, and thin air is
     * swung at.
     *
     * @return whether the press broke a block outright, which a player's client takes as all the attack does that tick
     */
    private boolean applyAttack(@Nullable Entity target, @Nullable BlockHitResult aimed) {

        if (!this.controls.attack) {

            return false;
        }

        float strength = this.getAttackStrengthScale(0.5F);
        this.executed.attacked = true;
        this.executed.attackStrength = strength;

        this.swing(InteractionHand.MAIN_HAND);

        if (target != null) {

            this.executed.attackHit = this.resolveAttack(target, strength);
            this.resetAttackStrengthTicker();
            return false;
        }

        if (aimed == null || aimed.getType() == HitResult.Type.MISS) {

            // A player's swing at thin air restarts the cooldown, so a miss is a real cost and aiming is a skill rather
            // than a formality.
            this.resetAttackStrengthTicker();
            return false;
        }

        // A player's swing at a block starts breaking it instead, and costs no cooldown. Whatever breaks at a touch is gone
        // there and then: grass, ferns, flowers. An agent that could not do this stood in an old spruce forest swinging
        // ninety times at the fern between it and a vindicator, and never landed a blow.
        BlockPos pos = aimed.getBlockPos();
        this.startDestroyBlock(pos);

        return this.level().getBlockState(pos).isAir();
    }

    private void trackMainHandForCooldown() {

        ItemStack main = this.getMainHandItem();

        if (!ItemStack.matches(this.lastItemInMainHand, main)) {

            // Only a different kind of item restarts the cooldown. Damage taken by the stack does not, which is why this
            // tests the item rather than the stack, and why swapping away and back cannot dodge a cooldown.
            if (!ItemStack.isSameItem(this.lastItemInMainHand, main)) {

                this.resetAttackStrengthTicker();
                this.executed.swappedWeapon = true;
            }

            this.lastItemInMainHand = main.copy();
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Attacking
    // ---------------------------------------------------------------------------------------------------------------

    public float getCurrentItemAttackStrengthDelay() {

        return (float) (1.0D / this.getAttributeValue(Attributes.ATTACK_SPEED) * 20.0D);
    }

    /** Zero straight after a swing, one once the cooldown has fully recovered. */
    public float getAttackStrengthScale(float adjustTicks) {

        return Mth.clamp(
                ((float) this.attackStrengthTicker + adjustTicks) / this.getCurrentItemAttackStrengthDelay(), 0.0F, 1.0F);
    }

    public void resetAttackStrengthTicker() {

        this.attackStrengthTicker = 0;
    }

    /**
     * What the agent is looking at within reach, found the same way the game finds what is under a player's crosshair: a
     * ray from the eyes along the look vector, stopped by the first block it meets.
     */
    @Nullable
    private Entity pickAimedEntity() {

        double reach = this.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
        Vec3 eye = this.getEyePosition();
        Vec3 view = this.getViewVector(1.0F);
        Vec3 end = eye.add(view.scale(reach));

        BlockHitResult blocked = this.level().clip(
                new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, this));

        double limitSq = reach * reach;

        if (blocked.getType() != HitResult.Type.MISS) {

            end = blocked.getLocation();
            limitSq = eye.distanceToSqr(end);
        }

        AABB search = this.getBoundingBox().expandTowards(view.scale(reach)).inflate(1.0D);

        EntityHitResult hit = ProjectileUtil.getEntityHitResult(
                this, eye, end, search, entity -> !entity.isSpectator() && entity.isPickable(), limitSq);

        return hit == null ? null : hit.getEntity();
    }

    /**
     * The damage, criticals, sweep and knockback a player's swing carries. Durability, statistics, hunger and the sounds
     * and particles are all left out: nothing here is watching or listening, and a suite of fifty thousand arenas pays
     * for every one of them. What the blow is paid is worked out where it lands, see {@link #dealtDamage}.
     */
    private boolean resolveAttack(Entity target, float strength) {

        if (!target.isAttackable() || target.skipAttackInteraction(this)) {

            return false;
        }

        DamageSource source = this.damageSources().mobAttack(this);
        float damage = (float) this.getAttributeValue(Attributes.ATTACK_DAMAGE);
        float bonus = this.enchantedDamage(target, damage, source) - damage;

        // The cooldown curve: a fifth of the damage on a swing that was spammed, all of it on one that was waited for.
        damage *= 0.2F + strength * strength * 0.8F;
        bonus *= strength;

        if (damage <= 0.0F && bonus <= 0.0F) {

            return false;
        }

        boolean recovered = strength > 0.9F;
        boolean sprintKnockback = this.isSprinting() && recovered;

        boolean critical = recovered
                && this.fallDistance > 0.0F
                && !this.onGround()
                && !this.onClimbable()
                && !this.isInWater()
                && !this.hasEffect(MobEffects.BLINDNESS)
                && !this.isPassenger()
                && !this.isSprinting()
                && target instanceof LivingEntity;

        if (critical) {

            damage *= 1.5F;
        }

        boolean sweep = recovered
                && !critical
                && !sprintKnockback
                && this.onGround()
                && this.walkDist - this.walkDistO < this.getSpeed()
                && this.getMainHandItem().getItem() instanceof SwordItem;

        float total = damage + bonus;

        if (!target.hurt(source, total)) {

            return false;
        }

        float knockback = this.getKnockback(target, source) + (sprintKnockback ? 1.0F : 0.0F);

        if (knockback > 0.0F) {

            float yaw = this.getYRot() * DEGREES_TO_RADIANS;

            if (target instanceof LivingEntity living) {

                living.knockback(knockback * 0.5D, Mth.sin(yaw), -Mth.cos(yaw));
            }

            else {

                target.push(-Mth.sin(yaw) * knockback * 0.5F, 0.1D, Mth.cos(yaw) * knockback * 0.5F);
            }

            this.setDeltaMovement(this.getDeltaMovement().multiply(0.6D, 1.0D, 0.6D));
            this.setSprinting(false);
        }

        if (sweep) {

            this.sweepAround(target, source, damage, strength);
        }

        this.setLastHurtMob(target);

        if (this.level() instanceof ServerLevel server) {

            EnchantmentHelper.doPostAttackEffects(server, target, source);
        }

        this.executed.attackDamage = total;
        this.executed.attackCritical = critical;
        this.executed.attackSweep = sweep;
        this.executed.attackSprintKnockback = sprintKnockback;

        return true;
    }

    /**
     * The enchantment contribution to a swing, which vanilla only works out for a player. Doing it here keeps enchanted
     * gear working the day a loadout gains any, and costs nothing until then.
     */
    private float enchantedDamage(Entity target, float damage, DamageSource source) {

        return this.level() instanceof ServerLevel server
                ? EnchantmentHelper.modifyDamage(server, this.getWeaponItem(), target, source, damage)
                : damage;
    }

    private void sweepAround(Entity target, DamageSource source, float damage, float strength) {

        float sweepDamage = 1.0F + (float) this.getAttributeValue(Attributes.SWEEPING_DAMAGE_RATIO) * damage;
        double reachSq = Mth.square(this.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE));
        float yaw = this.getYRot() * DEGREES_TO_RADIANS;

        for (LivingEntity other : this.level().getEntitiesOfClass(
                LivingEntity.class, target.getBoundingBox().inflate(1.0D, 0.25D, 1.0D))) {

            if (other == this || other == target || this.isAlliedTo(other) || this.distanceToSqr(other) >= reachSq) {

                continue;
            }

            if (other instanceof ArmorStand stand && stand.isMarker()) {

                continue;
            }

            other.knockback(0.4D, Mth.sin(yaw), -Mth.cos(yaw));
            other.hurt(source, this.enchantedDamage(other, sweepDamage, source) * strength);

            if (this.level() instanceof ServerLevel server) {

                EnchantmentHelper.doPostAttackEffects(server, other, source);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Bows and crossbows
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Where a bow or a crossbow finds its ammunition, the way a player's does: in a hand first, the off hand before the
     * main one, and otherwise the first stack in the hotbar that fits, which is all the inventory the agent has. The stack
     * found is the one taken from, so an arrow fired is an arrow gone from where it lay.
     */
    @Override
    public ItemStack getProjectile(ItemStack weapon) {

        if (!(weapon.getItem() instanceof ProjectileWeaponItem projectileWeapon)) {

            return ItemStack.EMPTY;
        }

        // Fireworks count here and only here: a crossbow loads one from a hand, but never goes looking for one.
        ItemStack held = ProjectileWeaponItem.getHeldProjectile(this, projectileWeapon.getSupportedHeldProjectiles());

        if (!held.isEmpty()) {

            return held;
        }

        Predicate<ItemStack> ammunition = projectileWeapon.getAllSupportedProjectiles();

        for (ItemStack stack : this.hotbar) {

            if (ammunition.test(stack)) {

                return stack;
            }
        }

        return ItemStack.EMPTY;
    }

    /**
     * Letting go of a drawn bow fires it, as BowItem#releaseUsing does for a player, the only one it fires for. Whatever
     * else happens to the arrow is the bow's own doing: the power from how long it was drawn, a critical at full draw, the
     * spread, the enchantments, and which arrow goes, tipped and spectral ones keeping what they carry. A crossbow needs
     * nothing of the kind, since its release, which loads it, already works for anyone.
     */
    @Override
    public void releaseUsingItem() {

        if (this.useItem.getItem() instanceof BowItem bow && this.level() instanceof ServerLevel server) {

            this.releaseBow(server, bow, this.useItem, this.getUseItemRemainingTicks());
        }

        super.releaseUsingItem();
    }

    private void releaseBow(ServerLevel server, BowItem bow, ItemStack stack, int timeLeft) {

        ItemStack ammunition = this.getProjectile(stack);

        if (ammunition.isEmpty()) {

            return;
        }

        float power = BowItem.getPowerForTime(bow.getUseDuration(stack, this) - timeLeft);

        // Let go almost at once, the string has too little in it to send anything.
        if ((double) power < 0.1D) {

            return;
        }

        List<ItemStack> arrows = ProjectileWeaponItemInvoker.modular_mob_ai$draw(stack, ammunition, this);

        if (!arrows.isEmpty()) {

            ((ProjectileWeaponItemInvoker) bow).modular_mob_ai$shoot(server, this, this.getUsedItemHand(), stack, arrows,
                    power * BOW_SPEED, PLAYER_INACCURACY, power == 1.0F, null);

            // Recorded where the arrow actually goes, and nowhere else: a bow let go of with too little in the string, or
            // with nothing to fire, does not shoot. See ExecutedControls#shotFired.
            this.executed.shotFired = true;
        }

        server.playSound(null, this.getX(), this.getY(), this.getZ(), SoundEvents.ARROW_SHOOT, this.getSoundSource(),
                1.0F, 1.0F / (server.getRandom().nextFloat() * 0.4F + 1.2F) + power * 0.5F);
    }

    /**
     * A loaded crossbow fires on the next use, as CrossbowItem#performShooting does for a player: at a player's speed and
     * spread, every bolt a critical, which the item decides by asking whether the one shooting is a player. A mob's
     * crossbow shoots slower, wider and without them, and the agent is not meant to be a mob about it.
     */
    private void fireCrossbow(CrossbowItem crossbow, InteractionHand hand, ItemStack stack) {

        if (!(this.level() instanceof ServerLevel server)) {

            return;
        }

        ChargedProjectiles loaded = stack.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.EMPTY);

        if (loaded == null || loaded.isEmpty()) {

            return;
        }

        float speed = loaded.contains(Items.FIREWORK_ROCKET) ? CROSSBOW_FIREWORK_SPEED : CROSSBOW_ARROW_SPEED;

        ((ProjectileWeaponItemInvoker) crossbow).modular_mob_ai$shoot(server, this, hand, stack, loaded.getItems(), speed,
                PLAYER_INACCURACY, true, null);

        // The bolts are away; loading the crossbow, which is the use before this one, shot nothing. See
        // ExecutedControls#shotFired.
        this.executed.shotFired = true;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Shields
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * A raised shield already stops what comes at it from the front, for anything that holds one up. What vanilla keeps
     * for players is the axe: a blow from one knocks the shield aside.
     */
    @Override
    protected void blockUsingShield(LivingEntity attacker) {

        super.blockUsingShield(attacker);

        if (attacker.canDisableShield()) {

            this.disableShield();
        }
    }

    /** The shield drops, and no shield comes up again for five seconds. */
    public void disableShield() {

        this.itemCooldowns.addCooldown(Items.SHIELD, SHIELD_DISABLED_TICKS);
        this.stopUsingItem();
        this.level().broadcastEntityEvent(this, EntityEvent.SHIELD_DISABLED);
    }

    /**
     * A shield wears as a player's does, by one more than the damage it stopped, once that is three or more, and it can
     * break. A mob's shield never wears at all, and a fight against an axe is not the same fight without this.
     *
     * <p>Public because Fabric's access wideners make it so, and an override may not narrow it.
     */
    @Override
    public void hurtCurrentlyUsedShield(float damage) {

        if (!this.useItem.is(Items.SHIELD) || damage < 3.0F) {

            return;
        }

        InteractionHand hand = this.getUsedItemHand();
        this.useItem.hurtAndBreak(1 + Mth.floor(damage), this, LivingEntity.getSlotForHand(hand));

        if (!this.useItem.isEmpty()) {

            return;
        }

        if (hand == InteractionHand.MAIN_HAND) {

            this.hotbar.set(this.selectedSlot, ItemStack.EMPTY);
            this.syncMainHand();
        }

        else {

            this.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        }

        // Nothing is left to hold up. A player's client notices that a tick later and stops; the agent stops now.
        this.stopUsingItem();
        this.playSound(SoundEvents.SHIELD_BREAK, 0.8F, 0.8F + this.level().random.nextFloat() * 0.4F);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Breaking blocks
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * The first touch on a block, which is MultiPlayerGameMode#startDestroyBlock and the server's answer to it. A block
     * that gives way at once is gone there and then: grass and flowers, and anything a strong enough tool breaks at a
     * touch. Anything harder starts to crack. Touching another block abandons the one before, and touching the one
     * already being broken changes nothing.
     */
    private void startDestroyBlock(BlockPos pos) {

        if (!this.level().getWorldBorder().isWithinBounds(pos) || this.destroyPos != null && this.sameDestroyTarget(pos)) {

            return;
        }

        this.stopDestroyBlock();

        BlockState state = this.level().getBlockState(pos);

        // Whatever a hit on a block sets off in the item doing the hitting. Nothing a sword carries does anything here.
        if (this.level() instanceof ServerLevel server) {

            EnchantmentHelper.onHitBlock(server, this.getMainHandItem(), this, this, EquipmentSlot.MAINHAND,
                    Vec3.atCenterOf(pos), state, item -> this.onEquippedItemBroken(item, EquipmentSlot.MAINHAND));
        }

        float progress = this.getDestroyProgress(state, pos);

        if (progress >= 1.0F) {

            this.destroyBlock(pos);
            return;
        }

        this.destroyPos = pos.immutable();
        this.destroyingItem = this.getMainHandItem();
        this.destroyProgress = 0.0F;
        this.showDestroyStage(pos, (int) (progress * 10.0F));
    }

    /**
     * What a held attack does to the block under the aim, as Minecraft#continueAttack and
     * MultiPlayerGameMode#continueDestroyBlock have it: the block cracks a tick's worth further, and once it has cracked
     * all the way it breaks. Looking away abandons it and all its progress.
     *
     * <p>**A hand does not forget.** Where a player's client throws the crack away the instant the button comes up, the
     * agent keeps it for as long as the aim stays on the same block. The crack still only deepens on the ticks the button
     * is actually down, so breaking anything costs exactly the presses it costs a player; what is forgiven is the tick in
     * the middle where the press did not come. That is the same thing the committed draw is for and for the same reason:
     * powder snow takes eight ticks of held attack to break out of, a cobweb eight, and the network holds attack for eight
     * ticks or more on 4.3% of its holds, so without this it can break nothing at all and drowns, freezes or stays webbed
     * where a player would dig out. See {@link #drawingToFull}.
     */
    private void continueDestroying(@Nullable BlockHitResult aimed) {

        if (aimed == null || aimed.getType() != HitResult.Type.BLOCK || this.level().getBlockState(aimed.getBlockPos()).isAir()) {

            this.stopDestroyBlock();
            return;
        }

        BlockPos pos = aimed.getBlockPos();

        if (this.destroyDelay > 0) {

            this.destroyDelay--;
        }

        // The press is what deepens a crack, so a tick without one leaves the block exactly as it was, cracked as far as
        // it had got. Only a crack that has already started is kept this way: the first press is still what starts one.
        else if (!this.controls.attack) {

            if (this.destroyPos != null && !this.sameDestroyTarget(pos)) {

                this.stopDestroyBlock();
            }
        }

        else if (this.destroyPos == null || !this.sameDestroyTarget(pos)) {

            this.startDestroyBlock(pos);
        }

        else {

            // Added up a tick at a time in floats, as the client does, which is why stone by hand takes a player 151 ticks
            // rather than the 150 the division suggests.
            this.destroyProgress += this.getDestroyProgress(this.level().getBlockState(pos), pos);

            if (this.destroyProgress >= 1.0F) {

                this.showDestroyStage(pos, -1);
                this.destroyPos = null;
                this.destroyProgress = 0.0F;
                this.destroyDelay = DESTROY_DELAY;
                this.destroyBlock(pos);
            }

            else {

                this.showDestroyStage(pos, (int) (this.destroyProgress * 10.0F));
            }
        }

        // The press that started the tick has swung already, and a tick that is only keeping a crack has nothing to swing
        // for: an arm that swung without a press would say the agent attacked when it did not.
        if (this.controls.attack && !this.executed.attacked) {

            this.swing(InteractionHand.MAIN_HAND);
        }
    }

    /**
     * Lets go of the block being broken: its progress is lost and its crack taken away.
     *
     * <p>A player's client restarts the attack cooldown here as well. That is left out on purpose: a swing that meets a
     * block costs the agent nothing, the fights it has learned in depend on that, and changing it would be a change to the
     * sword fight rather than to breaking blocks.
     */
    private void stopDestroyBlock() {

        if (this.destroyPos == null) {

            return;
        }

        this.showDestroyStage(this.destroyPos, -1);
        this.destroyPos = null;
        this.destroyProgress = 0.0F;
    }

    /** The same block, still being broken with the same tool, which is what a player's client keeps going with. */
    private boolean sameDestroyTarget(BlockPos pos) {

        return pos.equals(this.destroyPos) && ItemStack.isSameItemSameComponents(this.getMainHandItem(), this.destroyingItem);
    }

    /** Shows the rest of the world the crack, as the server does for a player's: only when its stage changes. */
    private void showDestroyStage(BlockPos pos, int stage) {

        if (stage != this.destroyStage) {

            this.level().destroyBlockProgress(this.getId(), pos, stage);
            this.destroyStage = stage;
        }
    }

    /**
     * ServerPlayerGameMode#destroyBlock with the agent in the player's place. What goes through a Player alone is left
     * out: the block's own reaction to being broken by one, which melts ice into water, angers the bees of a nest and the
     * piglins round a chest of gold, and the statistics. What is left is what the world sees: the block's particles and
     * sound, the block gone, the tool worn, and the drops a player holding that tool would get, which is nothing at all
     * from a block that needs the right tool and did not get it.
     */
    private void destroyBlock(BlockPos pos) {

        Level level = this.level();
        BlockState state = level.getBlockState(pos);

        // The agent is nobody's operator.
        if (state.getBlock() instanceof GameMasterBlock) {

            return;
        }

        BlockEntity blockEntity = level.getBlockEntity(pos);

        level.levelEvent(LevelEvent.PARTICLES_DESTROY_BLOCK, pos, Block.getId(state));
        level.gameEvent(GameEvent.BLOCK_DESTROY, pos, GameEvent.Context.of(this, state));

        boolean removed = level.removeBlock(pos, false);

        if (removed) {

            state.getBlock().destroy(level, pos, state);
        }

        ItemStack tool = this.getMainHandItem();
        ItemStack used = tool.copy();
        boolean harvests = this.hasCorrectToolForDrops(state);

        tool.getItem().mineBlock(tool, level, state, pos, this);

        if (removed && harvests) {

            Block.dropResources(state, level, pos, blockEntity, this, used);
        }
    }

    /** BlockState#getDestroyProgress with the agent as the player: how much of the block one tick's work breaks. */
    private float getDestroyProgress(BlockState state, BlockPos pos) {

        float hardness = state.getDestroySpeed(this.level(), pos);

        // Bedrock, barriers and the like.
        if (hardness == -1.0F) {

            return 0.0F;
        }

        return this.getDestroySpeed(state) / hardness / (this.hasCorrectToolForDrops(state) ? 30.0F : 100.0F);
    }

    /**
     * Player#getDestroySpeed: the held tool's speed on the block, with Efficiency, Haste and Mining Fatigue, and a fifth of
     * it with the eyes under water or the feet off the ground. Aqua Affinity would lift the first of those, which it does
     * through the attribute read here, from a helmet the agent has not got.
     */
    private float getDestroySpeed(BlockState state) {

        float speed = this.getMainHandItem().getDestroySpeed(state);

        if (speed > 1.0F) {

            speed += (float) this.getAttributeValue(Attributes.MINING_EFFICIENCY);
        }

        if (MobEffectUtil.hasDigSpeed(this)) {

            speed *= 1.0F + (float) (MobEffectUtil.getDigSpeedAmplification(this) + 1) * 0.2F;
        }

        if (this.hasEffect(MobEffects.DIG_SLOWDOWN)) {

            speed *= switch (this.getEffect(MobEffects.DIG_SLOWDOWN).getAmplifier()) {

                case 0 -> 0.3F;
                case 1 -> 0.09F;
                case 2 -> 0.0027F;
                default -> 8.1E-4F;
            };
        }

        speed *= (float) this.getAttributeValue(Attributes.BLOCK_BREAK_SPEED);

        if (this.isEyeInFluid(FluidTags.WATER)) {

            speed *= (float) this.getAttributeValue(Attributes.SUBMERGED_MINING_SPEED);
        }

        if (!this.onGround()) {

            speed /= 5.0F;
        }

        return speed;
    }

    /** Whether the held item gets the block's drops, which without it a block that needs the right tool withholds. */
    private boolean hasCorrectToolForDrops(BlockState state) {

        return !state.requiresCorrectToolForDrops() || this.getMainHandItem().isCorrectToolForDrops(state);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Hotbar
    // ---------------------------------------------------------------------------------------------------------------

    public void setHotbarItem(int slot, ItemStack stack) {

        this.hotbar.set(Mth.clamp(slot, 0, MobControls.HOTBAR_SIZE - 1), stack);
        this.syncMainHand();
    }

    public ItemStack getHotbarItem(int slot) {

        return this.hotbar.get(Mth.clamp(slot, 0, MobControls.HOTBAR_SIZE - 1));
    }

    public int getSelectedSlot() {

        return this.selectedSlot;
    }

    /** The hotbar is the source of truth; the main hand is a view of whichever slot is held. */
    private void syncMainHand() {

        this.setItemSlot(EquipmentSlot.MAINHAND, this.hotbar.get(this.selectedSlot));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Rotation
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * A mob swings its whole body around to face wherever it is looking the instant it starts moving, which would make
     * aiming away from the direction of travel invisible and pointless to learn. This puts back the rule vanilla uses
     * for a player: the body eases toward the direction of travel, and is only dragged around once the head is more than
     * fifty degrees off it. The agent can look wherever it likes, but it has to turn the way a person does to get there.
     */
    @Override
    protected float tickHeadTurn(float yBodyRot, float animStep) {

        float towardTravel = Mth.wrapDegrees(yBodyRot - this.yBodyRot);
        this.yBodyRot += towardTravel * 0.3F;

        float offset = Mth.wrapDegrees(this.getYRot() - this.yBodyRot);
        float limit = this.getMaxHeadRotationRelativeToBody();

        if (Math.abs(offset) > limit) {

            this.yBodyRot += offset - (float) Mth.sign(offset) * limit;
        }

        return offset < -90.0F || offset >= 90.0F ? -animStep : animStep;
    }

    @Override
    protected EntityDimensions getDefaultDimensions(Pose pose) {

        return pose == Pose.CROUCHING ? CROUCHING_DIMENSIONS : super.getDefaultDimensions(pose);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Housekeeping
    // ---------------------------------------------------------------------------------------------------------------

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {

        super.addAdditionalSaveData(tag);

        CompoundTag hotbarTag = new CompoundTag();
        ContainerHelper.saveAllItems(hotbarTag, this.hotbar, this.registryAccess());

        tag.put(TAG_HOTBAR, hotbarTag);
        tag.putInt(TAG_SELECTED_SLOT, this.selectedSlot);

        if (this.brainName != null) {

            tag.putString(TAG_BRAIN_NAME, this.brainName);
        }

        if (this.loadoutName != null) {

            tag.putString(TAG_LOADOUT, this.loadoutName);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {

        super.readAdditionalSaveData(tag);

        boolean saved = tag.contains(TAG_HOTBAR, Tag.TAG_COMPOUND);

        if (saved) {

            ContainerHelper.loadAllItems(tag.getCompound(TAG_HOTBAR), this.hotbar, this.registryAccess());
        }

        this.selectedSlot = Mth.clamp(tag.getInt(TAG_SELECTED_SLOT), 0, MobControls.HOTBAR_SIZE - 1);
        this.syncMainHand();

        // Kept as it was saved and not checked here: a world can name a network this game does not have, and the agent
        // should still have it back once the network is. The driver finds it by name, and falls back while it cannot.
        if (tag.contains(TAG_BRAIN_NAME, Tag.TAG_STRING)) {

            String name = tag.getString(TAG_BRAIN_NAME).trim();

            this.brainName = name.isEmpty() ? null : name;
            this.brain.use(null);
        }

        if (tag.contains(TAG_LOADOUT, Tag.TAG_STRING)) {

            this.loadoutName = tag.getString(TAG_LOADOUT);

            // A save always carries the hotbar, which is the loadout as it now stands, arrows spent and shield worn, and
            // must never be armed afresh on every load. Only a tag with a loadout and no hotbar, which is a /summon asking
            // for one, is armed with it here.
            if (!saved) {

                this.armWith(this.loadoutName, this.registryAccess());
            }
        }
    }

    /**
     * An agent that arrives the way any mob does, hatched from an egg, dispensed, or summoned with no tag, comes armed with
     * the config's loadout rather than empty handed, and right handed as a player is and as every agent in training was.
     * The arenas never come through here; they arm their own.
     */
    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, MobSpawnType spawnType,
            @Nullable SpawnGroupData spawnGroupData) {

        SpawnGroupData data = super.finalizeSpawn(level, difficulty, spawnType, spawnGroupData);

        this.setLeftHanded(false);

        if (!this.training && this.carriesNothing()) {

            this.armWith(Config.loadout(), level.registryAccess());
        }

        return data;
    }

    private boolean carriesNothing() {

        return this.hotbar.stream().allMatch(ItemStack::isEmpty) && this.getOffhandItem().isEmpty();
    }

    private void armWith(String name, HolderLookup.Provider registries) {

        Loadouts.byName(name, registries).ifPresentOrElse(this::equip, () -> Constants.LOG.warn(
                "There is no loadout '{}' to arm an agent with; there are {}", name, String.join(", ", Loadouts.names())));
    }

    public boolean isTraining() {

        return this.training;
    }

    /**
     * A training agent is kept alive by the arena that spawned it rather than by distance to a player, since a game test
     * has no players in it. The shipped one never despawns either, see {@link #requiresCustomPersistence}.
     */
    @Override
    public boolean removeWhenFarAway(double distance) {

        return !this.training && super.removeWhenFarAway(distance);
    }

    /**
     * The agent in a real game never despawns. Nothing spawns it on its own, so every one was put there on purpose, with
     * a brain and a loadout someone chose, and walking away should not lose it. Being persistent this way also keeps it
     * out of the mob cap, so a crowd of agents never stops animals from spawning.
     */
    @Override
    public boolean requiresCustomPersistence() {

        return !this.training || super.requiresCustomPersistence();
    }

    /**
     * In an arena nothing collects experience, and dropping it would only add work to every death in a suite of many
     * thousands. In the game it is worth what a player's worth of health is worth.
     */
    @Override
    protected int getBaseExperienceReward() {

        return this.training ? 0 : 5;
    }

    /** Does nothing, so that the controller is the only thing writing the movement inputs. */
    private static final class InertMoveControl extends MoveControl {

        private InertMoveControl(Mob mob) {

            super(mob);
        }

        @Override
        public void tick() {
        }
    }

    /** Does nothing, so that the controller is the only thing writing the rotations. */
    private static final class InertLookControl extends LookControl {

        private InertLookControl(Mob mob) {

            super(mob);
        }

        @Override
        public void tick() {
        }
    }
}
