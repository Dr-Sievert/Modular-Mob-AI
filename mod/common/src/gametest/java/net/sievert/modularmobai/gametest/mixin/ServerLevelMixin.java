package net.sievert.modularmobai.gametest.mixin;

import java.util.function.BooleanSupplier;

import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {

    // Nothing is saved while the tests run. With saving on, the chunk map walks every chunk it holds, every tick, looking
    // for one to write: it saves in whatever time a tick has to spare, and a game test server never sleeps, so it always
    // seems to have some. On the terrain suite that walk over thousands of chunks was an eighth of the server thread. The
    // server turns saving back on for every level before it shuts down, and a stopping server is no longer running, so a
    // world that is kept is still saved whole as the worker stops; MinecraftServerMixin skips that for one nobody keeps.
    // Saving off also stops the chunk map unloading anything; ChunkMapMixin puts the unloading back.
    @Inject(method = "tick", at = @At("HEAD"))
    private void modular_mob_ai$noSavingWhileTesting(BooleanSupplier hasTimeLeft, CallbackInfo ci) {

        final ServerLevel level = (ServerLevel) (Object) this;

        if (!level.noSave && level.getServer() instanceof GameTestServer && level.getServer().isRunning()) {

            level.noSave = true;
        }
    }
}
