package net.sievert.modularmobai.gametest.mixin;

import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.lighting.LightEngine;
import net.sievert.modularmobai.gametest.GameTestTuning;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The suites that train have no light engine at all, because a vindicator fight never asks how bright anywhere is.
 *
 * <p>Working light out is most of what a worker allocates. Every time the light engine runs it hands readers a fresh
 * snapshot of where every lit section's light lives, by copying the whole table
 * ({@code LayerLightSectionStorage.swapSectionMap}, once per run per layer), and a worker holds thousands of sections. A
 * worker's sites move on constantly, and every chunk that loads or unloads marks all of its sections changed, so that
 * copy is made again and again: a megabyte a time, which is also why the workers are given four megabyte heap regions.
 * Beside it the propagation runs on a background thread of its own, and a chunk is not ticking until it is lit, which is
 * what the server thread ends up waiting for.
 *
 * <p>What it is worth: 24,000 fights on the terrain library, one worker, the same sites both ways and the same 99.5% won,
 * measured twice in each order.
 *
 * <pre>
 *                                       with a light engine    without
 *   the round's fights took                  142, 146 s        112, 128 s
 *   arena ticks a second of wall clock       13.1k, 12.9k      17.0k, 15.1k
 *   arena ticks a second of server CPU       17.1k, 17.2k      17.8k, 17.7k
 *   collections                               253, 234          138, 131
 *   time in collection pauses                2.08, 2.22 s      1.43, 1.20 s
 * </pre>
 *
 * <p>So it costs the server thread nothing to speak of, and that is the point: the server thread was not doing the work,
 * it was waiting for it. Dividing the two rates says the server thread was busy 72% of the round with light and 88%
 * without, and the round finishes about a fifth sooner for the same fights. Nearly half the collections go with it.
 *
 * <p>Dropping both engines is a configuration vanilla supports rather than a hole punched in it. {@link LevelLightEngine}
 * null checks both in every method it has: it reports no light work to do, reads every brightness as zero, and hands out
 * its own dummy listener. They are private to it, so {@code ThreadedLevelLightEngine} cannot reach past those checks
 * either. Nothing is queued, nothing propagates, and no snapshot is ever taken.
 *
 * <p>Which suites go without, and why the others keep theirs, is in {@link GameTestTuning#lighting()}. The short of it is
 * that {@code canSeeSky} is a light question rather than a heightmap one, so switching light off reaches further than it
 * first appears, and only the vindicator suites are provably untouched by it. That the arena suite's twenty fights still
 * take exactly 54 ticks each is what says so.
 *
 * <p><b>This applies in a development game too, which is why the decision asks whether it is in one.</b> Both loaders hand
 * the game-test source set to their client and server runs and {@code fabric.mod.json} lists this mixin config, so nothing
 * about being a game test keeps this out of a real world: it is the suite property that says so, and a client passes none.
 * With the property absent the suite defaults to {@code arena}, which is how a world made with {@code scripts\play.ps1}
 * came out pitch dark with no way to light it again. {@link GameTestTuning#gameTestServer()} is now the first thing
 * {@link GameTestTuning#lighting()} asks, and {@code PlayGameTest.aRealGameKeepsItsLightEngine} holds it to that.
 *
 * <p>The two engines are built and then let go rather than never built. That costs one pair of empty engines per level,
 * and buys not having to inject into a constructor ahead of its own body, which is the kind of thing that works until a
 * mapping changes.
 */
@Mixin(LevelLightEngine.class)
public abstract class LevelLightEngineMixin {

    @Mutable
    @Shadow
    @Final
    private LightEngine<?, ?> blockEngine;

    @Mutable
    @Shadow
    @Final
    private LightEngine<?, ?> skyEngine;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void modular_mob_ai$noLightWhileFighting(LightChunkGetter chunkSource, boolean blockLight,
                                                    boolean skyLight, CallbackInfo ci) {

        if (GameTestTuning.lighting()) {

            return;
        }

        this.blockEngine = null;
        this.skyEngine = null;
    }
}
