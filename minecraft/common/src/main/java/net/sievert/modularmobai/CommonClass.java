package net.sievert.modularmobai;

import net.minecraft.resources.ResourceLocation;
import net.sievert.modularmobai.platform.Services;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Items;

public class CommonClass {

    public static void init() {

        if (Services.PLATFORM.isModLoaded("modular_mob_ai")) {

            Constants.LOG.info("Hello to modular_mob_ai");
        }
    }

    public static ResourceLocation location(String path) {
        return ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, path);
    }
}