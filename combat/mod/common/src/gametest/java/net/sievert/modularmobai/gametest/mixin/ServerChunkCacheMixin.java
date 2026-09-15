package net.sievert.modularmobai.gametest.mixin;

import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheMixin {

    @Shadow
    @Final
    ServerLevel level;

    // Every tick the chunk cache lists every chunk it keeps ticking, counts every entity in the level against the mob
    // caps, shuffles the list, and then asks of each chunk whether a player is near enough for anything to happen there.
    // Mobs spawn, snow settles, lightning strikes and blocks tick at random only near a player, and the block changes it
    // would send out are sent to players. Nobody ever joins a game test server, so the answer is always no and the whole
    // pass does nothing; on the terrain suite, with thousands of chunks loaded, it was an eighth of the server thread.
    @Inject(method = "tickChunks", at = @At("HEAD"), cancellable = true)
    private void modular_mob_ai$nothingToTickWithoutPlayers(CallbackInfo ci) {

        if (this.level.getServer() instanceof GameTestServer && this.level.players().isEmpty()) {

            ci.cancel();
        }
    }
}
