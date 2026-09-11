package net.sievert.modularmobai.brain;

import java.util.List;

import net.sievert.modularmobai.entity.AgentMob;

/**
 * Puts every agent alive on a server tick through the brain in a single call.
 *
 * <p>This has to run before the entities themselves tick, because the actions it writes are what those entities will
 * apply a moment later. The order within a tick is: refresh who can see whom, describe the world, ask once for every
 * agent's action, hand each action to its agent, and only then let the world tick.
 *
 * <p>An agent whose fight has ended still gets one last step, flagged done, carrying its final observation and its
 * terminal reward. Training needs that last observation to work out what the position was worth, so dropping a dead
 * agent silently would quietly throw away the most informative step of the episode. Its action is filled in and ignored.
 * The caller stops passing an agent once it has been given a done step.
 */
public final class AgentBatch {

    private final Brain brain;
    private final BrainStep step = new BrainStep();

    public AgentBatch(Brain brain) {

        this.brain = brain;
    }

    /**
     * @param agents every agent that should be stepped this tick, including any whose fight ended on the previous one.
     */
    public void run(List<AgentMob> agents) {

        int count = agents.size();

        if (count == 0) {

            return;
        }

        this.step.ensureCapacity(count);
        this.step.count = count;

        for (int index = 0; index < count; index++) {

            AgentMob agent = agents.get(index);
            AgentReward reward = agent.reward();

            agent.enemySlots().tick(agent, agent.arenaBounds());
            AgentObservation.write(agent, agent.enemySlots(), this.step.observations, index * ObservationSchema.OBS_DIM);

            this.step.agentIds[index] = agent.getId();
            this.step.rewards[index] = reward.takeTick();

            byte flags = 0;

            // Nothing has ticked this agent yet, so this is the first thing the brain has ever seen of it.
            if (reward.elapsedTicks() == 0) {

                flags |= BrainStep.FLAG_NEW;
            }

            if (reward.isTerminal()) {

                flags |= BrainStep.FLAG_DONE;
            }

            this.step.flags[index] = flags;
        }

        this.brain.act(this.step);

        for (int index = 0; index < count; index++) {

            AgentMob agent = agents.get(index);

            if ((this.step.flags[index] & BrainStep.FLAG_DONE) != 0) {

                // Its last step has now been sent, so it never appears in a batch again.
                agent.reward().markReported();
                continue;
            }

            ActionSchema.apply(this.step.actions, index * ActionSchema.ACT_DIM, agent.controls());
        }
    }

    public void close() {

        this.brain.close();
    }
}
