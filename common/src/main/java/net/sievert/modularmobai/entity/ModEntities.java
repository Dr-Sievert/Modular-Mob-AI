package net.sievert.modularmobai.entity;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.sievert.modularmobai.Constants;

public final class ModEntities {

    private ModEntities() {}

    public static final ResourceLocation AGENT_MOB_ID = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "agent_mob");

    /**
     * Player shaped bounding box, and a tracking range wide enough that an arena never stops updating it.
     */
    public static final EntityType.Builder<AgentMob> AGENT_MOB_BUILDER = EntityType.Builder
            .of(AgentMob::new, net.minecraft.world.entity.MobCategory.CREATURE)
            .sized(0.6F, 1.8F)
            .eyeHeight(1.62F)
            .clientTrackingRange(10);

    private static EntityType<AgentMob> agentMob;

    public static void setAgentMob(EntityType<AgentMob> type) {

        agentMob = type;
    }

    public static EntityType<AgentMob> agentMob() {

        if (agentMob == null) {

            throw new IllegalStateException("The agent mob was read before its loader registered it");
        }

        return agentMob;
    }
}
