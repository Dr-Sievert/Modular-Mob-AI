package net.sievert.modularmobai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.resources.ResourceLocation;

public final class Constants {

    private Constants() {}

    public static final String MOD_ID = "modular_mob_ai";
    public static final String MOD_NAME = "Modular Mob AI";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_NAME);

    /** Something of this mod's, by path: a texture, an item, an entity. */
    public static ResourceLocation id(String path) {

        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
