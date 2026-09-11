package net.sievert.modularmobai.entity;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.sievert.modularmobai.Constants;
import net.sievert.modularmobai.entity.agent.AgentMob;

/**
 * Two registrations of the one entity class.
 *
 * <p>The body, the controls, the observation and the brain interface are identical, which is what lets a network
 * trained against one drive the other without anything drifting. Only how the world treats them differs: the agent is a
 * mob a player meets, with an egg and drops and a despawn timer; the training agent is what the arenas spawn, and is
 * never saved, never summoned by hand, and never goes anywhere on its own.
 */
public final class ModEntities {

    private ModEntities() {}

    public static final ResourceLocation AGENT_MOB_ID = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "agent_mob");
    public static final ResourceLocation TRAINING_AGENT_ID = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "training_agent");

    /** The shipped one. */
    public static final EntityType.Builder<AgentMob> AGENT_MOB_BUILDER = playerShaped();

    /**
     * The one the arenas fight in. Not saved, because a suite of fifty thousand arenas would otherwise serialise every
     * agent on every world save for no reason; not summonable, because it has no business in a survival world.
     */
    public static final EntityType.Builder<AgentMob> TRAINING_AGENT_BUILDER = playerShaped().noSave().noSummon();

    /**
     * A player's bounding box and eye height, and a tracking range wide enough that an arena never stops updating it.
     */
    private static EntityType.Builder<AgentMob> playerShaped() {

        return EntityType.Builder
                .of(AgentMob::new, MobCategory.CREATURE)
                .sized(0.6F, 1.8F)
                .eyeHeight(1.62F)
                .clientTrackingRange(10);
    }

    private static EntityType<AgentMob> agentMob;
    private static EntityType<AgentMob> trainingAgent;

    public static void setAgentMob(EntityType<AgentMob> type) {

        agentMob = type;
    }

    public static void setTrainingAgent(EntityType<AgentMob> type) {

        trainingAgent = type;
    }

    public static EntityType<AgentMob> agentMob() {

        if (agentMob == null) {

            throw new IllegalStateException("The agent mob was read before its loader registered it");
        }

        return agentMob;
    }

    public static EntityType<AgentMob> trainingAgent() {

        if (trainingAgent == null) {

            throw new IllegalStateException("The training agent was read before its loader registered it");
        }

        return trainingAgent;
    }
}
