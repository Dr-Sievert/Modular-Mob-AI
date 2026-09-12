package net.sievert.modularmobai.entity;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.sievert.modularmobai.Constants;
import net.sievert.modularmobai.entity.agent.AgentMob;
import net.sievert.modularmobai.entity.agent.BeastMob;

/**
 * The bodies, as the game registers them.
 *
 * <p>Two registrations of the humanoid, which is one entity class: the body, the controls, the observation and the brain
 * interface are identical, which is what lets a network trained against one drive the other without anything drifting. Only
 * how the world treats them differs: the agent is a mob a player meets, with an egg and drops and a despawn timer; the
 * training agent is what the arenas spawn, and is never saved, never summoned by hand, and never goes anywhere on its own.
 *
 * <p>And one of the beast, a body with no hands, which is a different species and so a different entity class: see
 * {@link net.sievert.modularmobai.brain.schema.Beast}. It is shaped like the humanoid on purpose, since it is a proof that a
 * second body needs nothing of its own but a schema, rather than a mob anybody is meant to meet.
 */
public final class ModEntities {

    private ModEntities() {}

    public static final ResourceLocation AGENT_MOB_ID = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "agent_mob");
    public static final ResourceLocation TRAINING_AGENT_ID = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "training_agent");
    public static final ResourceLocation BEAST_AGENT_ID = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "beast_agent");

    /** The shipped one. */
    public static final EntityType.Builder<AgentMob> AGENT_MOB_BUILDER = playerShaped();

    /**
     * The one the arenas fight in. Not saved, because a suite of fifty thousand arenas would otherwise serialise every
     * agent on every world save for no reason; not summonable, because it has no business in a survival world.
     */
    public static final EntityType.Builder<AgentMob> TRAINING_AGENT_BUILDER = playerShaped().noSave().noSummon();

    /**
     * The beast: a second body, in the humanoid's shape because nothing about the proof is its silhouette. Not saved and not
     * summonable for now, since nothing trains it yet and it has no business turning up in a survival world.
     */
    public static final EntityType.Builder<BeastMob> BEAST_AGENT_BUILDER = EntityType.Builder
            .of(BeastMob::new, MobCategory.CREATURE)
            .sized(0.6F, 1.8F)
            .eyeHeight(1.62F)
            .clientTrackingRange(10)
            .noSave()
            .noSummon();

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
    private static EntityType<BeastMob> beastAgent;

    public static void setAgentMob(EntityType<AgentMob> type) {

        agentMob = type;
    }

    public static void setTrainingAgent(EntityType<AgentMob> type) {

        trainingAgent = type;
    }

    public static void setBeastAgent(EntityType<BeastMob> type) {

        beastAgent = type;
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

    public static EntityType<BeastMob> beastAgent() {

        if (beastAgent == null) {

            throw new IllegalStateException("The beast agent was read before its loader registered it");
        }

        return beastAgent;
    }
}
