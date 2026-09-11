package net.sievert.modularmobai.item;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.sievert.modularmobai.Constants;

public final class ModItems {

    private ModItems() {}

    public static final ResourceLocation AGENT_MOB_SPAWN_EGG_ID = Constants.id("agent_mob_spawn_egg");

    public static final int EGG_BACKGROUND = 0xE0AC69;
    public static final int EGG_HIGHLIGHT = 0x3C44AA;

    private static Item agentMobSpawnEgg;

    public static void setAgentMobSpawnEgg(Item item) {

        agentMobSpawnEgg = item;
    }

    public static Item agentMobSpawnEgg() {

        if (agentMobSpawnEgg == null) {

            throw new IllegalStateException("The agent mob spawn egg was read before its loader registered it");
        }

        return agentMobSpawnEgg;
    }
}
