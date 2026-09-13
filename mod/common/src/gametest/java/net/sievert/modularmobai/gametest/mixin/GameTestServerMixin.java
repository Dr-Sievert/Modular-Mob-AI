package net.sievert.modularmobai.gametest.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.gametest.framework.MultipleTestTracker;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.slf4j.Logger;
import net.sievert.modularmobai.gametest.GameTestBenchmark;
import net.sievert.modularmobai.gametest.GameTestTuning;
import net.sievert.modularmobai.gametest.terrain.TerrainLibrary;
import net.sievert.modularmobai.gametest.terrain.TerrainSites;
import net.sievert.modularmobai.gametest.util.ServerThreadAffinity;
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

    /** Midnight, when no undead burns, every spider is hostile and no enderman is chased off by the light. */
    @Unique
    private static final long modular_mob_ai$MIDNIGHT = 18000L;

    /**
     * Every game test runs at midnight in clear weather, with neither clock moving.
     *
     * <p>Not a convenience: a roof is no protection on a test's first tick. The framework clears each plot to air and
     * places the structure again, and the sky light of a box placed this tick is not worked out until the next one —
     * {@code canSeeSky} is "is the sky light here fifteen", so for exactly one tick it answers yes inside a closed bedrock
     * roof. That is the tick a mob spawned by the test body takes its first, and a zombie in a daylit world rolls vanilla's
     * one-in-twenty-five sun burn on it. Measured: at tick zero the sky reads 15 at the zombie's eye in the arena box, and
     * 0 from tick one on. The play suite failed about one run in seven that way, as whichever of its tests reads a zombie's
     * health drew the short straw: a blow that got through friendly fire, or allies on one team hurting each other.
     *
     * <p>The light engine is not the thing to take away: the suites that keep one keep it for reasons of their own, see
     * {@link net.sievert.modularmobai.gametest.GameTestTuning#lighting}. Nothing any suite tests is about daylight or
     * weather, so the sun goes instead, and with the cycles stopped a long run cannot drift back into either. The league
     * asked for this first, for its undead, spiders and endermen fighting in the open; it now gets it from here, and keeps
     * only the rules that are its own, see {@link net.sievert.modularmobai.gametest.league.League#prepareWorld}.
     */
    @Inject(method = "startTests", at = @At("HEAD"))
    private void modular_mob_ai$holdTheWorldAtMidnight(ServerLevel level, CallbackInfo ci) {

        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, level.getServer());
        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, level.getServer());
        level.setDayTime(modular_mob_ai$MIDNIGHT);
        level.setWeatherParameters(0, 0, false, false);
    }

    // The first tick is where the server thread finds out which processors it is allowed, because a thread can only set its
    // own affinity: see ServerThreadAffinity. Here rather than earlier on purpose. Starting up is the one part of a worker
    // that really does want every core, since it compiles and loads on all of them, and by this point that is over and
    // everything that follows is this thread ticking. Costs a field read a tick once it has settled, and does nothing at
    // all unless the build asked for a mask.
    @Inject(method = "tickServer", at = @At("HEAD"))
    private void modular_mob_ai$pinServerThread(BooleanSupplier hasTimeLeft, CallbackInfo ci) {

        ServerThreadAffinity.pinCallingThread();
    }

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

    // The terrain suite waits for its first site as soon as the tests have started, in the same tick, so the wait counts
    // as starting up rather than as testing, which it would once the tick was over.
    @Inject(
            method = "tickServer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/gametest/framework/GameTestServer;startTests(Lnet/minecraft/server/level/ServerLevel;)V",
                    shift = At.Shift.AFTER
            )
    )
    private void modular_mob_ai$awaitFirstSite(BooleanSupplier hasTimeLeft, CallbackInfo ci) {

        if (GameTestTuning.naturalTerrain() && !GameTestTuning.buildingLibrary()) {

            TerrainSites.awaitFirstSite(((MinecraftServer) (Object) this).overworld());
        }
    }

    // The terrain suite's sites are generated a couple at a time while fights run on the ones already there. Every tick
    // hands the sites that have finished to the fights and asks for the next ones. Building the terrain library, it
    // generates and checks the library's sites instead.
    @Inject(method = "tickServer", at = @At("TAIL"))
    private void modular_mob_ai$generateTerrain(BooleanSupplier hasTimeLeft, CallbackInfo ci) {

        if (GameTestTuning.buildingLibrary()) {

            TerrainLibrary.tick(((MinecraftServer) (Object) this).overworld());
        }

        else if (GameTestTuning.naturalTerrain()) {

            TerrainSites.tick(((MinecraftServer) (Object) this).overworld());
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

    // At the same moment the terrain suite writes down where its sites were, so the build can keep the world it generated
    // for the workers after this one.
    @Inject(
            method = "tickServer",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/google/common/base/Stopwatch;stop()Lcom/google/common/base/Stopwatch;"
            )
    )
    private void modular_mob_ai$reportTerrain(BooleanSupplier hasTimeLeft, CallbackInfo ci) {

        if (GameTestTuning.buildingLibrary()) {

            TerrainLibrary.finish();
        }

        else if (GameTestTuning.naturalTerrain()) {

            TerrainSites.finish();
        }
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
    // somewhere deep under a random ocean that would have to be generated first. For the terrain suite the plots go deep
    // under the spawn instead, which is loaded already, and this is also where the fight sites are chosen and start
    // generating.
    @ModifyArg(
            method = "startTests",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/gametest/framework/StructureGridSpawner;<init>(Lnet/minecraft/core/BlockPos;IZ)V"
            ),
            index = 0
    )
    private BlockPos modular_mob_ai$terrainOrigin(BlockPos corner) {

        if (GameTestTuning.buildingLibrary()) {

            return TerrainLibrary.start(((MinecraftServer) (Object) this).overworld());
        }

        return GameTestTuning.naturalTerrain() ? TerrainSites.start(((MinecraftServer) (Object) this).overworld()) : corner;
    }
}
