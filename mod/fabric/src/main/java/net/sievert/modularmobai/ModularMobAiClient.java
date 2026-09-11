package net.sievert.modularmobai;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.sievert.modularmobai.entity.agent.AgentMobRenderer;
import net.sievert.modularmobai.entity.ModEntities;

public class ModularMobAiClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {

        EntityRendererRegistry.register(ModEntities.agentMob(), AgentMobRenderer::new);
        EntityRendererRegistry.register(ModEntities.trainingAgent(), AgentMobRenderer::new);
    }
}
