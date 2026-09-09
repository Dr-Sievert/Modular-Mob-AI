package net.sievert.modularmobai.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;

public class AgentMob extends PathfinderMob {

    public AgentMob(EntityType<? extends PathfinderMob> type, Level level) {

        super(type, level);
    }

    /**
     * Player shaped, so that what is learned here transfers to fighting a player rather than to fighting a mob with a
     * mob's reach and speed.
     */
    public static AttributeSupplier.Builder createAttributes() {

        return LivingEntity.createLivingAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.1D)
                .add(Attributes.ATTACK_DAMAGE, 1.0D)
                .add(Attributes.ATTACK_KNOCKBACK, 0.0D)
                .add(Attributes.FOLLOW_RANGE, 32.0D)
                .add(Attributes.STEP_HEIGHT, 0.6D);
    }

    @Override
    protected void registerGoals() {
        // Nothing. The controller is the only thing that may move this entity.
    }

    /**
     * Kept alive by the arena that spawned it rather than by distance to a player, since a game test has no players in it.
     */
    @Override
    public boolean removeWhenFarAway(double distance) {

        return false;
    }

    /**
     * Nothing collects it, and dropping experience would only add work to every death in a suite of many thousands.
     */
    @Override
    protected int getBaseExperienceReward() {

        return 0;
    }
}
