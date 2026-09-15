package net.sievert.modularmobai.gametest.mixin;

import net.minecraft.world.entity.npc.Villager;
import net.sievert.modularmobai.gametest.GameTestTuning;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Villager.class)
public class VillagerMixin {

    // The baseline suite kills fifty thousand villagers and vanilla logs a line for each. Dropped in a game-test server
    // only: this config is loaded in a development client too, and a real game's log should read as vanilla's does. See
    // GameTestTuning.gameTestServer.
    @Redirect(
            method = "die",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/slf4j/Logger;info(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"
            )
    )
    private void modular_mob_ai$silenceDeathLog(Logger logger, String message, Object villager, Object cause) {

        if (!GameTestTuning.gameTestServer()) {

            logger.info(message, villager, cause);
        }
    }
}
