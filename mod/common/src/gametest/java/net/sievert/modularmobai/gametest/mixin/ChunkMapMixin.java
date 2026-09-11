package net.sievert.modularmobai.gametest.mixin;

import java.util.Queue;
import java.util.function.BooleanSupplier;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {

    @Shadow
    @Final
    ServerLevel level;

    @Shadow
    @Final
    LongSet toDrop;

    @Shadow
    @Final
    private Long2ObjectLinkedOpenHashMap<ChunkHolder> updatingChunkMap;

    @Shadow
    @Final
    private Long2ObjectLinkedOpenHashMap<ChunkHolder> pendingUnloads;

    @Shadow
    @Final
    private Queue<Runnable> unloadQueue;

    @Shadow
    private boolean modified;

    @Shadow
    protected abstract void scheduleUnload(long pos, ChunkHolder holder);

    // ServerLevelMixin turns saving off while the tests run, and the chunk map only unloads chunks when saving is on: its
    // tick skips the whole unload pass for a level that does not save. So every chunk a worker ever generated stayed in
    // memory, and as terrain sites moved on to fresh ground a 1 GB worker filled its heap in minutes, spent its time
    // collecting garbage, and died of it. This is the vanilla pass without its last part, the walk over every chunk held
    // looking for one to save, which is what saving off was there to skip.
    @Inject(method = "tick(Ljava/util/function/BooleanSupplier;)V", at = @At("TAIL"))
    private void modular_mob_ai$unloadWithoutSaving(BooleanSupplier hasMoreTime, CallbackInfo ci) {

        if (!this.modular_mob_ai$testing()) {

            return;
        }

        LongIterator dropping = this.toDrop.iterator();

        while (dropping.hasNext()) {

            long pos = dropping.nextLong();
            ChunkHolder holder = this.updatingChunkMap.get(pos);

            if (holder != null) {

                // Still being generated for a neighbour; vanilla leaves it for a later tick too.
                if (holder.getGenerationRefCount() != 0) {

                    continue;
                }

                this.updatingChunkMap.remove(pos);
                this.pendingUnloads.put(pos, holder);
                this.modified = true;
                this.scheduleUnload(pos, holder);
            }

            dropping.remove();
        }

        Runnable unload;

        while ((unload = this.unloadQueue.poll()) != null) {

            unload.run();
        }
    }

    // Unloading a chunk writes it first. Nothing reads a game test world back while it runs, and a world that is kept is
    // saved whole at shutdown, when saving is back on, so a chunk let go now is simply dropped; the terrain comes back the
    // same from the seed if a site ever returns to it.
    @Inject(method = "save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z", at = @At("HEAD"), cancellable = true)
    private void modular_mob_ai$dropWithoutWriting(ChunkAccess chunk, CallbackInfoReturnable<Boolean> cir) {

        if (this.modular_mob_ai$testing()) {

            cir.setReturnValue(false);
        }
    }

    private boolean modular_mob_ai$testing() {

        return this.level.noSave() && this.level.getServer() instanceof GameTestServer;
    }
}
