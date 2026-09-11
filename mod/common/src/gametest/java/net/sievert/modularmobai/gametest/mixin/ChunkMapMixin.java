package net.sievert.modularmobai.gametest.mixin;

import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.sievert.modularmobai.gametest.GameTestTuning;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {

    @Shadow
    @Final
    ServerLevel level;

    // Nothing ever reads a game test world back: the next run clears the folder it was in. Saving it anyway means writing
    // every chunk the run generated, thousands of them, on the server thread as the server stops, which took the terrain
    // suite's shutdown from three seconds to ten once nothing was saved while it ran, and filled the heap while it did. So
    // no chunk is written at all, unless a run asks to keep its world.
    @Inject(method = "save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z", at = @At("HEAD"), cancellable = true)
    private void modular_mob_ai$neverWriteChunks(ChunkAccess chunk, CallbackInfoReturnable<Boolean> cir) {

        if (this.level.getServer() instanceof GameTestServer && !GameTestTuning.saveWorld()) {

            cir.setReturnValue(false);
        }
    }
}
