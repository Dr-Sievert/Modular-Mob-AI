package net.sievert.modularmobai;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.sievert.modularmobai.entity.agent.AgentMobRenderer;
import net.sievert.modularmobai.entity.ModEntities;

public class ModularMobAiClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {

        // Every body's mob, on the player model. A renderer is a client class and cannot be named in a body's own
        // declaration, which the build reads without the game on its class path, so this is the one place a body that wants
        // a shape of its own is still an edit: give it its own renderer here and in NeoForge's client, and nothing else
        // changes. A body that says nothing gets this, which is what a proof of a second body wants.
        for (ModEntities.Registration registration : ModEntities.registrations()) {

            EntityRendererRegistry.register(ModEntities.of(registration.species(), registration.mob().role()),
                    AgentMobRenderer::new);
        }
    }
}
