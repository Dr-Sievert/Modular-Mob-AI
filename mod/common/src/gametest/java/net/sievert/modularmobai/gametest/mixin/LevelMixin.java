package net.sievert.modularmobai.gametest.mixin;

import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Level.class)
public abstract class LevelMixin {

    // Block entities in a game test world never do anything a fight can see. With the world's structures turned off the
    // ground only holds spawners, which wait for a player; bee nests, whose bees would only be swept up as wildlife; and
    // the sculk of the deep dark, which answers vibrations with sound and redstone, and spreads where something dies
    // within eight blocks of a catalyst, all of it far underground below the fights. The framework's own plots carry a
    // beacon each, which only ever works on players. There can be thousands of them all the same, each looked up on
    // every tick, and on ground with deep dark under it they were a fifth of the server thread. So with nobody on the
    // server, none of them is ticked.
    @Inject(method = "tickBlockEntities", at = @At("HEAD"), cancellable = true)
    private void modular_mob_ai$noBlockEntitiesWithoutPlayers(CallbackInfo ci) {

        if ((Object) this instanceof ServerLevel level && level.getServer() instanceof GameTestServer && level.players().isEmpty()) {

            ci.cancel();
        }
    }
}
