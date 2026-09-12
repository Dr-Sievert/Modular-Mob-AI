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

        ModEntities.setAgentMob(Registry.register(
                BuiltInRegistries.ENTITY_TYPE,
                ModEntities.AGENT_MOB_ID,
                ModEntities.AGENT_MOB_BUILDER.build(ModEntities.AGENT_MOB_ID.toString())
        ));

        ModEntities.setTrainingAgent(Registry.register(
                BuiltInRegistries.ENTITY_TYPE,
                ModEntities.TRAINING_AGENT_ID,
                ModEntities.TRAINING_AGENT_BUILDER.build(ModEntities.TRAINING_AGENT_ID.toString())
        ));

        ModEntities.setBeastAgent(Registry.register(
                BuiltInRegistries.ENTITY_TYPE,
                ModEntities.BEAST_AGENT_ID,
                ModEntities.BEAST_AGENT_BUILDER.build(ModEntities.BEAST_AGENT_ID.toString())
        ));

        // The same attributes for all of them: a network trained against one humanoid has to find the same body in the
        // other, and the beast differs in what it may be asked to do rather than in what its body is.
        FabricDefaultAttributeRegistry.register(ModEntities.agentMob(), AgentMob.createAttributes());
        FabricDefaultAttributeRegistry.register(ModEntities.trainingAgent(), AgentMob.createAttributes());
        FabricDefaultAttributeRegistry.register(ModEntities.beastAgent(), AgentMob.createAttributes());

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
