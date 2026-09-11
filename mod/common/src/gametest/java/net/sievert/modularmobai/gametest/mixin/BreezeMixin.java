package net.sievert.modularmobai.gametest.mixin;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.breeze.Breeze;
import net.sievert.modularmobai.entity.ModEntities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Breeze.class)
public abstract class BreezeMixin {

    // A breeze will only ever fight a player or an iron golem: handed any other target, it decides on the next tick that
    // the target cannot be attacked and lets it go, and in a league fight it wandered off and never fired a single charge.
    // Here it fights the agent the way it fights a player, which is what the agent is standing in for; everything else
    // about the fight, when it shoots, jumps and slides, is the breeze's own.
    @Inject(method = "canAttackType", at = @At("HEAD"), cancellable = true)
    private void modular_mob_ai$fightsTheAgent(EntityType<?> type, CallbackInfoReturnable<Boolean> cir) {

        if (type == ModEntities.trainingAgent()) {

            cir.setReturnValue(true);
        }
    }
}
