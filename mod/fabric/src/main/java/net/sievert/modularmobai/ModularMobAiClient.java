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

        // The beast borrows the humanoid's renderer, since it borrows its model: it is a proof, not a mob to meet.
        EntityRendererRegistry.register(ModEntities.beastAgent(), AgentMobRenderer::new);
    }
}
