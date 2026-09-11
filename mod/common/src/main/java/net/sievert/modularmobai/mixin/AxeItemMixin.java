package net.sievert.modularmobai.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.sievert.modularmobai.entity.agent.AgentMob;
import net.sievert.modularmobai.entity.agent.AgentUseOnContext;

@Mixin(AxeItem.class)
public abstract class AxeItemMixin {

    // Before an axe strips a log it asks whether the use was really meant for a shield in the other hand, and it asks the
    // player, without checking there is one: an agent using an axe on a block threw there. The same question is put to
    // the agent, and a use with nobody behind it at all is taken at its word.
    @Inject(method = "playerHasShieldUseIntent", at = @At("HEAD"), cancellable = true)
    private static void modular_mob_ai$shieldIntentWithoutAPlayer(UseOnContext context, CallbackInfoReturnable<Boolean> cir) {

        if (context.getPlayer() != null) {

            return;
        }

        if (context instanceof AgentUseOnContext use) {

            AgentMob agent = use.agent();

            cir.setReturnValue(context.getHand() == InteractionHand.MAIN_HAND
                    && agent.getOffhandItem().is(Items.SHIELD)
                    && !agent.isShiftKeyDown());
        }

        else {

            cir.setReturnValue(false);
        }
    }
}
