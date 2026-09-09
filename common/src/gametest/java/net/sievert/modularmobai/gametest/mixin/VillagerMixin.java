package net.sievert.modularmobai.gametest.mixin;

import net.minecraft.world.entity.npc.Villager;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Villager.class)
public class VillagerMixin {

    @Redirect(
            method = "die",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/slf4j/Logger;info(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"
            )
    )
    private void modular_mob_ai$silenceDeathLog(Logger logger, String message, Object villager, Object cause) {}
}
