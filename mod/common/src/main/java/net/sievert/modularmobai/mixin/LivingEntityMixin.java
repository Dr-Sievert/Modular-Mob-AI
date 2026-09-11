package net.sievert.modularmobai.mixin;

import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalFloatRef;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.sievert.modularmobai.allegiance.Allegiance;
import net.sievert.modularmobai.entity.agent.AgentMob;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    // Friendly fire, for the agent as vanilla has it for players: nothing on a team with friendly fire off can hurt
    // another on its side, when an agent is either of them. Vanilla asks this only between two players, in ServerPlayer
    // and Player; everything else, a mob or a player hitting a mob, always goes through. Asked here, first, where every
    // swing, sweep, arrow and bolt arrives, so a hit that is refused is refused whole: nothing is taken off and nothing is
    // paid, and an arrow bounces off as it does off a teammate. With no teams, which is every training fight, this never
    // refuses anything.
    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void modular_mob_ai$friendlyFire(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {

        if (Allegiance.sparedByFriendlyFire((LivingEntity) (Object) this, source.getEntity())) {

            cir.setReturnValue(false);
        }
    }

    // Whatever the agent hurts is paid for here, where the damage lands, rather than where it was dealt. A swing, an arrow,
    // a crossbow bolt, a sweep and anything a loadout brings later all arrive at this one method with the agent as their
    // source or owner, so each is counted exactly once and none is missed. What counts is the health actually taken off,
    // measured around the whole of the hurt, after armour, a raised shield and the rest have had their say. The health
    // before is carried over in a local of the call itself rather than a field, so a hurt inside a hurt measures its own.
    @Inject(method = "hurt", at = @At("HEAD"))
    private void modular_mob_ai$healthBefore(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir,
            @Share("healthBefore") LocalFloatRef healthBefore) {

        healthBefore.set(((LivingEntity) (Object) this).getHealth());
    }

    @Inject(method = "hurt", at = @At("RETURN"))
    private void modular_mob_ai$payTheAgent(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir,
            @Share("healthBefore") LocalFloatRef healthBefore) {

        if (source.getEntity() instanceof AgentMob agent) {

            LivingEntity hurt = (LivingEntity) (Object) this;
            agent.dealtDamage(hurt, healthBefore.get() - hurt.getHealth());
        }
    }
}
