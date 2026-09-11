package net.sievert.modularmobai;

import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.sievert.modularmobai.brain.AgentDriver;
import net.sievert.modularmobai.entity.agent.AgentMobRenderer;
import net.sievert.modularmobai.entity.agent.AgentMob;
import net.sievert.modularmobai.entity.ModEntities;
import net.sievert.modularmobai.item.ModItems;

import java.util.function.Supplier;

@Mod(Constants.MOD_ID)
public class ModularMobAiMod {

    private static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(Registries.ENTITY_TYPE, Constants.MOD_ID);

    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, Constants.MOD_ID);

    private static final Supplier<EntityType<AgentMob>> AGENT_MOB = ENTITY_TYPES.register(
            ModEntities.AGENT_MOB_ID.getPath(),
            () -> ModEntities.AGENT_MOB_BUILDER.build(ModEntities.AGENT_MOB_ID.toString())
    );

    private static final Supplier<EntityType<AgentMob>> TRAINING_AGENT = ENTITY_TYPES.register(
            ModEntities.TRAINING_AGENT_ID.getPath(),
            () -> ModEntities.TRAINING_AGENT_BUILDER.build(ModEntities.TRAINING_AGENT_ID.toString())
    );

    // Only the shipped one gets an egg. The training one is spawned by arenas and nothing else.
    private static final Supplier<Item> AGENT_MOB_SPAWN_EGG = ITEMS.register(
            ModItems.AGENT_MOB_SPAWN_EGG_ID.getPath(),
            () -> new DeferredSpawnEggItem(AGENT_MOB, ModItems.EGG_BACKGROUND, ModItems.EGG_HIGHLIGHT, new Item.Properties())
    );

    public ModularMobAiMod(IEventBus eventBus) {

        ENTITY_TYPES.register(eventBus);
        ITEMS.register(eventBus);

        eventBus.addListener(ModularMobAiMod::registerAttributes);
        eventBus.addListener(ModularMobAiMod::addToCreativeTab);
        eventBus.addListener(ModularMobAiMod::handOver);

        // Every agent in a level gets its actions at the start of the level's tick, before any entity moves.
        NeoForge.EVENT_BUS.addListener(ModularMobAiMod::driveAgents);
    }

    private static void driveAgents(LevelTickEvent.Pre event) {

        if (event.getLevel() instanceof ServerLevel level) {

            AgentDriver.tick(level);
        }
    }

    private static void handOver(FMLCommonSetupEvent event) {

        ModEntities.setAgentMob(AGENT_MOB.get());
        ModEntities.setTrainingAgent(TRAINING_AGENT.get());
        ModItems.setAgentMobSpawnEgg(AGENT_MOB_SPAWN_EGG.get());
    }

    private static void registerAttributes(EntityAttributeCreationEvent event) {

        // The same attributes for both: a network trained against one has to find the same body in the other.
        event.put(AGENT_MOB.get(), AgentMob.createAttributes().build());
        event.put(TRAINING_AGENT.get(), AgentMob.createAttributes().build());
    }

    private static void addToCreativeTab(BuildCreativeModeTabContentsEvent event) {

        if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {

            event.accept(AGENT_MOB_SPAWN_EGG.get());
        }
    }

    @EventBusSubscriber(modid = Constants.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class Client {

        @SubscribeEvent
        public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {

            event.registerEntityRenderer(AGENT_MOB.get(), AgentMobRenderer::new);
            event.registerEntityRenderer(TRAINING_AGENT.get(), AgentMobRenderer::new);
        }
    }
}
