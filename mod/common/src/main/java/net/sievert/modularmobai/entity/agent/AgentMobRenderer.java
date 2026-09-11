package net.sievert.modularmobai.entity.agent;

import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.ResourceLocation;
import net.sievert.modularmobai.Constants;
import org.jetbrains.annotations.NotNull;

public class AgentMobRenderer extends HumanoidMobRenderer<AgentMob, PlayerModel<AgentMob>> {

    private static final ResourceLocation SKIN = Constants.id("textures/entity/agent.png");

    public AgentMobRenderer(EntityRendererProvider.Context context) {

        super(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
    }

    @Override
    public @NotNull ResourceLocation getTextureLocation(@NotNull AgentMob entity) {

        return SKIN;
    }
}
