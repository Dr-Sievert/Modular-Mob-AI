package net.sievert.modularmobai;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;
import net.sievert.modularmobai.brain.AgentDriver;
import net.sievert.modularmobai.brain.Brains;
import net.sievert.modularmobai.command.AgentCommands;
import net.sievert.modularmobai.entity.agent.AgentMob;
import net.sievert.modularmobai.entity.ModEntities;
import net.sievert.modularmobai.item.ModItems;

public class ModularMobAiMod implements ModInitializer {

    @Override
    public void onInitialize() {

        Config.load(FabricLoader.getInstance().getGameDir(), FabricLoader.getInstance().getConfigDir());

        // Every mob every body declares, and nothing named here: see ModEntities and docs/species.md. The same attributes
        // for all of them, because a body differs in what it may be asked to do rather than in what its body is, and a
        // network trained against one has to find the same body in the next.
        for (ModEntities.Registration registration : ModEntities.registrations()) {

            EntityType<AgentMob> type = Registry.register(BuiltInRegistries.ENTITY_TYPE, registration.id(),
                    registration.builder().build(registration.id().toString()));

            ModEntities.accept(registration, type);
            FabricDefaultAttributeRegistry.register(type, AgentMob.createAttributes());
        }

        // Only the shipped one gets an egg. The training one is spawned by arenas and nothing else.
        ModItems.setAgentMobSpawnEgg(Registry.register(
                BuiltInRegistries.ITEM,
                ModItems.AGENT_MOB_SPAWN_EGG_ID,
                new SpawnEggItem(ModEntities.agentMob(), ModItems.EGG_BACKGROUND, ModItems.EGG_HIGHLIGHT, new Item.Properties())
        ));

        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.SPAWN_EGGS).register(entries -> entries.accept(ModItems.agentMobSpawnEgg()));

        // Every agent in a level gets its actions at the start of the level's tick, before any entity moves.
        ServerTickEvents.START_WORLD_TICK.register(AgentDriver::tick);

        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> AgentCommands.register(dispatcher));

        // The log says which network drives the agents as soon as a world is open.
        ServerLifecycleEvents.SERVER_STARTED.register(Brains::serverStarted);
    }
}
