package net.sievert.modularmobai;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;
import net.sievert.modularmobai.entity.AgentMob;
import net.sievert.modularmobai.entity.ModEntities;
import net.sievert.modularmobai.item.ModItems;

public class ModularMobAiMod implements ModInitializer {

    @Override
    public void onInitialize() {

        ModEntities.setAgentMob(Registry.register(
                BuiltInRegistries.ENTITY_TYPE,
                ModEntities.AGENT_MOB_ID,
                ModEntities.AGENT_MOB_BUILDER.build(ModEntities.AGENT_MOB_ID.toString())
        ));

        FabricDefaultAttributeRegistry.register(ModEntities.agentMob(), AgentMob.createAttributes());

        ModItems.setAgentMobSpawnEgg(Registry.register(
                BuiltInRegistries.ITEM,
                ModItems.AGENT_MOB_SPAWN_EGG_ID,
                new SpawnEggItem(ModEntities.agentMob(), ModItems.EGG_BACKGROUND, ModItems.EGG_HIGHLIGHT, new Item.Properties())
        ));

        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.SPAWN_EGGS).register(entries -> entries.accept(ModItems.agentMobSpawnEgg()));

        CommonClass.init();
    }
}
