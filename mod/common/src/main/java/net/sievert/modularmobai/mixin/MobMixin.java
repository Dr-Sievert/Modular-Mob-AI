package net.sievert.modularmobai.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.level.Level;
import net.sievert.modularmobai.allegiance.OtherTeamTargetGoal;
import net.sievert.modularmobai.entity.agent.AgentMob;

@Mixin(Mob.class)
public abstract class MobMixin {

    @Shadow
    @Final
    protected GoalSelector targetSelector;

    // Gives every mob that finds its way with a path the goal that sends it after members of other teams, see
    // OtherTeamTargetGoal, which sleeps while the mob is on no team. Added once the mob's constructor is done, which is
    // after its own goals were registered, and on the server only, which is the only place vanilla registers goals. Every
    // mob gets it rather than a list of fighters, so a mod's mobs and the next vanilla update's are covered too, and
    // what a mob with no attack of its own does with a target is nothing. The agent is left out: nothing but its brain
    // may move it, and its enemies are chosen by the observation, not by a target.
    @Inject(method = "<init>", at = @At("TAIL"))
    private void modular_mob_ai$goAfterOtherTeams(EntityType<? extends Mob> type, Level level, CallbackInfo ci) {

        Mob mob = (Mob) (Object) this;

        // The goal measures its reach by the follow range, which a mob built on a bare attribute set may not have.
        if (level != null && !level.isClientSide && mob instanceof PathfinderMob && !(mob instanceof AgentMob)
                && mob.getAttributes().hasAttribute(Attributes.FOLLOW_RANGE)) {

            this.targetSelector.addGoal(OtherTeamTargetGoal.PRIORITY, new OtherTeamTargetGoal(mob));
        }
    }
}
