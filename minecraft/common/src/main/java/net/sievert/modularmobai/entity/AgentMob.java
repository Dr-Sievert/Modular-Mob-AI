package net.sievert.modularmobai.entity;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.LookControl;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.sievert.modularmobai.brain.AgentReward;
import net.sievert.modularmobai.brain.EnemySlots;
import net.sievert.modularmobai.entity.control.ExecutedControls;
import net.sievert.modularmobai.entity.control.MobControls;

/**
 * A mob driven entirely from {@link MobControls}, shaped so that what a brain learns here transfers to fighting a player.
 *
 * <p>It has a player's reach, a player's attack cooldown, a player's hotbar and a player's rotation limits, but none of
 * the machinery a real player carries. Every tick it reads the intent buffer, applies it under vanilla's rules, and
 * writes down what actually happened in {@link ExecutedControls}.
 */
public class AgentMob extends PathfinderMob {

    private static final String TAG_HOTBAR = "Hotbar";
    private static final String TAG_SELECTED_SLOT = "SelectedSlot";

    private static final float DEGREES_TO_RADIANS = (float) (Math.PI / 180.0D);

    /**
     * How long a use has to wait before it fires again. The client sets exactly this delay every time the use button
     * resolves, which is what stops a held button placing a block on every single tick.
     */
    public static final int USE_INTERVAL = 4;

    /** A player's crouch, so the hitbox shrinks and the model ducks the way anyone watching the fight would expect. */
    private static final EntityDimensions CROUCHING_DIMENSIONS = EntityDimensions.scalable(0.6F, 1.5F).withEyeHeight(1.27F);

    private final MobControls controls = new MobControls();
    private final ExecutedControls executed = new ExecutedControls();

    private final EnemySlots enemySlots = new EnemySlots();
    private final AgentReward reward = new AgentReward();

    /**
     * The region the agent is allowed to perceive. Arenas in a suite sit only a few blocks apart, so without this an
     * agent would see straight into its neighbours and fight opponents it cannot reach.
     */
    @Nullable
    private AABB arenaBounds;

    private final NonNullList<ItemStack> hotbar = NonNullList.withSize(MobControls.HOTBAR_SIZE, ItemStack.EMPTY);
    private int selectedSlot;

    /** Counts up every tick and restarts on a swing or a change of weapon, exactly as a player's does. */
    private int attackStrengthTicker;
    private ItemStack lastItemInMainHand = ItemStack.EMPTY;

    /** Shared between the hands, as the client's is, so alternating them cannot interact twice as fast as a player can. */
    private int useCooldown;

    /**
     * Whether this is the training registration rather than the shipped one. Everything the brain sees and does is the
     * same either way; this only decides how the world treats the body when nobody is driving it.
     */
    private final boolean training;

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
     * mob's reach and speed. The last four are attributes vanilla only gives to players; without them the agent has no
     * attack cooldown, no reach and no sweep.
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
                .add(Attributes.SNEAKING_SPEED);
    }

    @Override
    protected void registerGoals() {
        // Nothing. The controller is the only thing that may move this entity.
    }

    public MobControls controls() {

        return this.controls;
    }

    public ExecutedControls executed() {

        return this.executed;
    }

    public EnemySlots enemySlots() {

        return this.enemySlots;
    }

    public AgentReward reward() {

        return this.reward;
    }

    @Nullable
    public AABB arenaBounds() {

        return this.arenaBounds;
    }

    public void setArenaBounds(@Nullable AABB bounds) {

        this.arenaBounds = bounds;
    }

    /** How many ticks are left before a use will fire again. */
    public int useCooldown() {

        return this.useCooldown;
    }

    /** Puts the agent back to a fresh episode without respawning it. */
    public void resetEpisode() {

        this.controls.clear();
        this.executed.clear();
        this.enemySlots.clear();
        this.reward.reset();
        this.attackStrengthTicker = 0;
        this.useCooldown = 0;
    }

    /** Damage that actually got through, which is what the reward is scaled against rather than the amount swung for. */
    @Override
    protected void actuallyHurt(DamageSource source, float amount) {

        super.actuallyHurt(source, amount);
        this.reward.damageTaken(amount, this.getMaxHealth());
    }

    /**
     * Dying ends the episode by itself. Winning cannot, because only the arena knows what winning was supposed to mean,
     * so that half is called from the test.
     */
    @Override
    public void die(DamageSource cause) {

        this.reward.lost();
        super.die(cause);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The control loop
    // ---------------------------------------------------------------------------------------------------------------

    @Override
    protected void customServerAiStep() {

        this.executed.clear();
        this.reward.tick();

        if (this.useCooldown > 0) {

            this.useCooldown--;
        }

        this.applySelectedSlot();
        this.applyAim();
        this.applyMovement();
        this.applyUse();
        this.applyAttack();

        // Vanilla resolves a player's attack from its packet before aiStep advances the cooldown and notices a change of
        // weapon, so both of those happen after the controls above rather than before them.
        this.attackStrengthTicker++;
        this.trackMainHandForCooldown();
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
        // or backwards out of a fight.
        boolean sprint = this.controls.sprint && !sneak && forward >= MobControls.SPRINT_FORWARD_THRESHOLD;
        this.setSprinting(sprint);

        if (sneak) {

            float slowdown = (float) this.getAttributeValue(Attributes.SNEAKING_SPEED);
            forward *= slowdown;
            strafe *= slowdown;
        }

        this.setZza(forward);
        this.setXxa(strafe);

        // travel() moves the entity at whatever speed was last set rather than reading the attribute itself, and nothing
        // sets it for a mob with no navigation running.
        this.setSpeed((float) this.getAttributeValue(Attributes.MOVEMENT_SPEED));

        boolean grounded = this.onGround();

        if (this.controls.jump && grounded) {

            // Going through the jump control rather than setJumping, because that control ticks after this method and
            // would otherwise overwrite the flag before the jump is read.
            this.getJumpControl().jump();
        }

        this.executed.moveForward = forward;
        this.executed.moveStrafe = strafe;
        this.executed.jumped = this.controls.jump && grounded;
        this.executed.sprinting = sprint;
        this.executed.sneaking = sneak;
    }

    private void applyUse() {

        this.applyUse(InteractionHand.MAIN_HAND, this.controls.use);
        this.applyUse(InteractionHand.OFF_HAND, this.controls.useOffhand);

        boolean using = this.isUsingItem();
        this.executed.using = using && this.getUsedItemHand() == InteractionHand.MAIN_HAND;
        this.executed.usingOffhand = using && this.getUsedItemHand() == InteractionHand.OFF_HAND;
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

        if (!wanted) {

            if (this.isUsingItem() && this.getUsedItemHand() == hand) {

                // Released rather than cancelled, so that a drawn bow actually fires.
                this.releaseUsingItem();
            }

            return;
        }

        // An item already being used keeps the hands busy, and a use that just fired has to wait its interval out.
        if (this.isUsingItem() || this.useCooldown > 0) {

            return;
        }

        this.useCooldown = USE_INTERVAL;

        ItemStack stack = this.getItemInHand(hand);

        if (stack.isEmpty()) {

            return;
        }

        BlockHitResult aimed = this.pickAimedBlock();

        if (aimed.getType() != HitResult.Type.MISS) {

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

        // Nothing took it, so the item acts on its own. Only items with a use animation answer to being held down, which
        // covers shields, bows, food and potions; the rest go through Item#use, which takes a Player.
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

    private void applyAttack() {

        if (!this.controls.attack) {

            return;
        }

        float strength = this.getAttackStrengthScale(0.5F);
        this.executed.attacked = true;
        this.executed.attackStrength = strength;

        Entity target = this.pickAimedEntity();
        this.swing(InteractionHand.MAIN_HAND);

        if (target != null) {

            this.executed.attackHit = this.resolveAttack(target, strength);
        }

        // A player's cooldown restarts whether the swing landed or not, so a miss is a real cost and aiming is a skill
        // rather than a formality.
        this.resetAttackStrengthTicker();
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
     * for every one of them.
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

        if (target instanceof LivingEntity hurt) {

            this.reward.damageDealt(total, hurt.getMaxHealth());
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
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {

        super.readAdditionalSaveData(tag);

        if (tag.contains(TAG_HOTBAR, Tag.TAG_COMPOUND)) {

            ContainerHelper.loadAllItems(tag.getCompound(TAG_HOTBAR), this.hotbar, this.registryAccess());
        }

        this.selectedSlot = Mth.clamp(tag.getInt(TAG_SELECTED_SLOT), 0, MobControls.HOTBAR_SIZE - 1);
        this.syncMainHand();
    }

    public boolean isTraining() {

        return this.training;
    }

    /**
     * A training agent is kept alive by the arena that spawned it rather than by distance to a player, since a game test
     * has no players in it. The shipped one despawns like any other mob.
     */
    @Override
    public boolean removeWhenFarAway(double distance) {

        return !this.training && super.removeWhenFarAway(distance);
    }

    /**
     * In an arena nothing collects experience, and dropping it would only add work to every death in a suite of many
     * thousands. In the game it is worth what a player's worth of health is worth.
     */
    @Override
    protected int getBaseExperienceReward() {

        return this.training ? 0 : 5;
    }

    /**
     * Supplies the three things a use context normally reads off the player it was given. Without this a block placed by
     * the agent would always face north, because a context with no player has nothing else to go on.
     */
    private static final class AgentUseOnContext extends UseOnContext {

        private final AgentMob agent;

        private AgentUseOnContext(AgentMob agent, InteractionHand hand, ItemStack stack, BlockHitResult hit) {

            super(agent.level(), null, hand, stack, hit);
            this.agent = agent;
        }

        @Override
        public Direction getHorizontalDirection() {

            return this.agent.getDirection();
        }

        @Override
        public float getRotation() {

            return this.agent.getYRot();
        }

        @Override
        public boolean isSecondaryUseActive() {

            return this.agent.isShiftKeyDown();
        }
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
