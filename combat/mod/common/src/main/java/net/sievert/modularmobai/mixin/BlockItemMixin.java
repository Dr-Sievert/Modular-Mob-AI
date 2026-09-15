package net.sievert.modularmobai.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.sievert.modularmobai.entity.agent.AgentUseOnContext;

@Mixin(BlockItem.class)
public abstract class BlockItemMixin {

    // A block item places through a copy of the context it was used with, and the copy only knows which way to face and
    // where to look from a player. For an agent's use it is made from the agent instead, see AgentUseOnContext.
    @Redirect(
            method = "useOn",
            at = @At(
                    value = "NEW",
                    target = "(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/item/context/BlockPlaceContext;"
            )
    )
    private BlockPlaceContext modular_mob_ai$placeAsTheAgent(UseOnContext context) {

        return context instanceof AgentUseOnContext agent ? agent.placing() : new BlockPlaceContext(context);
    }

    // A block that is also food, a sweet berry or a glow berry, is eaten instead when it will not go down, and eating is
    // asked of the player, who is not there when an agent is the one using it. It passes instead, and the agent eats it
    // the way it uses anything nothing under its aim took.
    @Inject(
            method = "useOn",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/item/Item;use(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResultHolder;"
            ),
            cancellable = true
    )
    private void modular_mob_ai$noEatingWithoutAPlayer(UseOnContext context, CallbackInfoReturnable<InteractionResult> cir) {

        if (context.getPlayer() == null) {

            cir.setReturnValue(InteractionResult.PASS);
        }
    }
}
