package net.sievert.modularmobai.gametest.mixin;

import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.MinecraftServer;
import net.sievert.modularmobai.Constants;
import net.sievert.modularmobai.gametest.GameTestTuning;
import net.sievert.modularmobai.gametest.terrain.TerrainSites;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public class MinecraftServerMixin {

    // A server saves every chunk it has loaded as it stops, which on natural terrain is thousands of chunks and seconds of
    // a worker's time. When nobody keeps the world, that is time spent writing files that are deleted before the next run,
    // so the test server stops without saving and goes straight on to exiting. Everything the run itself produces, the
    // durations, rollouts and replays, was written when the suite finished.
    @Inject(method = "stopServer", at = @At("HEAD"), cancellable = true)
    private void modular_mob_ai$skipSavingThrowawayTerrain(CallbackInfo ci) {

        if ((Object) this instanceof GameTestServer && GameTestTuning.naturalTerrain() && TerrainSites.throwaway()) {

            Constants.LOG.info("Stopping without saving the world: nobody keeps it");
            ci.cancel();
        }
    }
}
