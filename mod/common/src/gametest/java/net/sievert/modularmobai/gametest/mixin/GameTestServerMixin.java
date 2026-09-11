package net.sievert.modularmobai.gametest.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.gametest.framework.MultipleTestTracker;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.slf4j.Logger;
import net.sievert.modularmobai.gametest.GameTestBenchmark;
import net.sievert.modularmobai.gametest.GameTestTuning;
import net.sievert.modularmobai.gametest.terrain.TerrainSites;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

@Mixin(GameTestServer.class)
public class GameTestServerMixin {

    /**
     * When the run started and how many ticks it has taken since. The throttle works its delay out against the whole run
     * rather than the last tick, so a rate faster than the operating system can time a single sleep for is still held to
     * on average.
     */
    @Unique
    private long modular_mob_ai$startedAt;

    @Unique
    private long modular_mob_ai$ticks;

    @Inject(method = "tickServer", at = @At("TAIL"))
    private void modular_mob_ai$countAndThrottle(BooleanSupplier hasTimeLeft, CallbackInfo ci) {

        if (this.modular_mob_ai$startedAt == 0L) {

            this.modular_mob_ai$startedAt = System.nanoTime();
        }

        this.modular_mob_ai$ticks++;

        final int ticksPerSecond = GameTestTuning.ticksPerSecond();

        if (ticksPerSecond <= 0) {

            return;
        }

        final long period = 1_000_000_000L / ticksPerSecond;
        final long due = this.modular_mob_ai$startedAt + this.modular_mob_ai$ticks * period;
        final long remaining = due - System.nanoTime();

        if (remaining > 0L) {

            LockSupport.parkNanos(remaining);
        }
    }

    // Fires the moment the suite finishes: the server stops its own stopwatch here and logs its summary right after.
    // Hooking the shutdown callback instead was tried first and was unreliable, because the process can exit before that
    // callback runs; this point is on the same thread as the summary, so it always fires.
    @Inject(
            method = "tickServer",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/google/common/base/Stopwatch;stop()Lcom/google/common/base/Stopwatch;"
            )
    )
    private void modular_mob_ai$reportBenchmark(BooleanSupplier hasTimeLeft, CallbackInfo ci) {

        if (this.modular_mob_ai$startedAt == 0L) {

            return;
        }

        GameTestBenchmark.report(
                this.modular_mob_ai$ticks,
                (System.nanoTime() - this.modular_mob_ai$startedAt) / 1_000_000_000.0D
        );
    }

    // The progress bar carries one character per test, so at ten thousand arenas it is a ten thousand character line,
    // rebuilt and written roughly every twenty ticks. That is tens of megabytes of string building on the server thread
    // over a run, and it grows with the square of the suite size. The periodic render is dropped; the one printed when
    // the suite finishes is left alone.
    @Redirect(
            method = "tickServer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/gametest/framework/MultipleTestTracker;getProgressBar()Ljava/lang/String;",
                    ordinal = 0
            )
    )
    private String modular_mob_ai$skipProgressBar(MultipleTestTracker tracker) {

        return "";
    }

    @Redirect(
            method = "tickServer",
            at = @At(value = "INVOKE", target = "Lorg/slf4j/Logger;info(Ljava/lang/String;)V", ordinal = 0)
    )
    private void modular_mob_ai$skipProgressLog(Logger logger, String message) {}

    @ModifyArg(
            method = "startTests",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/gametest/framework/StructureGridSpawner;<init>(Lnet/minecraft/core/BlockPos;IZ)V"
            ),
            index = 2
    )
    private boolean modular_mob_ai$reusePlots(boolean clearOnBatch) {

        return GameTestTuning.reusePlots() || clearOnBatch;
    }

    // The test server always builds a flat world, which generates for nothing and suits fights in boxes. The terrain suite
    // needs real ground, so for that suite alone the world is asked for as a normal one. The world preset is read inside a
    // lambda of the static factory, so this matches every method and lets the one call site pick itself out.
    @ModifyArg(
            method = "*",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/core/Registry;getHolderOrThrow(Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/core/Holder$Reference;"
            )
    )
    private static ResourceKey<?> modular_mob_ai$naturalTerrain(ResourceKey<?> preset) {

        return GameTestTuning.naturalTerrain() && preset == WorldPresets.FLAT ? WorldPresets.NORMAL : preset;
    }

    // The framework picks a random corner for its plots at the height of a flat world's floor, which on real terrain is
    // somewhere deep under a random ocean. For the terrain suite the plots go under the fight sites instead, and choosing
    // those sites is what starts the world generating them.
    @ModifyArg(
            method = "startTests",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/gametest/framework/StructureGridSpawner;<init>(Lnet/minecraft/core/BlockPos;IZ)V"
            ),
            index = 0
    )
    private BlockPos modular_mob_ai$terrainOrigin(BlockPos corner) {

        return GameTestTuning.naturalTerrain() ? TerrainSites.start(((MinecraftServer) (Object) this).overworld()) : corner;
    }
}
