package net.sievert.modularmobai;

import net.minecraft.core.registries.Registries;
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
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.sievert.modularmobai.client.AgentMobRenderer;
import net.sievert.modularmobai.entity.AgentMob;
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

        CommonClass.init();
    }

    private static void handOver(FMLCommonSetupEvent event) {

        ModEntities.setAgentMob(AGENT_MOB.get());
        ModItems.setAgentMobSpawnEgg(AGENT_MOB_SPAWN_EGG.get());
    }

    private static void registerAttributes(EntityAttributeCreationEvent event) {

        event.put(AGENT_MOB.get(), AgentMob.createAttributes().build());
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
        }
    }
}
