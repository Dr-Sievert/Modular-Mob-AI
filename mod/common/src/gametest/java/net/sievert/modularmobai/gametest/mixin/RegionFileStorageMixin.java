package net.sievert.modularmobai.gametest.mixin;

import java.nio.file.Path;

import org.jetbrains.annotations.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.sievert.modularmobai.gametest.GameTestTuning;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RegionFileStorage.class)
public abstract class RegionFileStorageMixin {

    @Shadow
    @Final
    private Path folder;

    // A worker on the terrain library reads its chunks from region files every other worker shares, linked into its world
    // rather than copied. Nothing writes a chunk while the tests run and a world on the library is thrown away rather than
    // saved, so nothing should ever come here; this is the last guard, at the one place chunk data reaches a region file,
    // so that no mistake anywhere else can change the ground under every worker at once. Entities and points of interest
    // have folders of their own, which are never linked, and are left alone.
    @Inject(method = "write", at = @At("HEAD"), cancellable = true)
    private void modular_mob_ai$neverWriteTheLibrary(ChunkPos pos, @Nullable CompoundTag data, CallbackInfo ci) {

        if (GameTestTuning.library() != null && !GameTestTuning.buildingLibrary()
                && "region".equals(String.valueOf(this.folder.getFileName()))) {

            ci.cancel();
        }
    }
}
