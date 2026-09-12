package net.sievert.modularmobai.brain;

import java.util.ArrayList;
import java.util.List;

import net.sievert.modularmobai.arena.Episode;
import net.sievert.modularmobai.brain.schema.Species;
import net.sievert.modularmobai.entity.agent.AgentMob;

/**
 * Every agent driven by one brain on one tick, put through that brain in a single call. One body's: a brain is made for
 * one species, and the rows of a step are that species' width, so a batch never mixes two.
 *
 * <p>This has to run before the entities themselves tick, because the actions it writes are what those entities will
 * apply a moment later. The order is: refresh who can see whom, describe the world, gather each agent's memory, ask once
 * for every agent's action, hand each agent its action and its advanced memory back, and only then let the world tick.
 *
 * <p>An agent whose fight has ended still gets one last step, flagged done, carrying its final observation and its
 * terminal reward. Training needs that last observation to work out what the position was worth, so dropping a dead
 * agent silently would quietly throw away the most informative step of the episode. Its action is filled in and ignored.
 * The agent is not batched again after that.
 */
public final class AgentBatch {

    private final Brain brain;
    private final Species species;
    private final BrainStep step = new BrainStep();
    private final List<AgentMob> agents = new ArrayList<>();

    public AgentBatch(Brain brain) {

        this.brain = brain;
        this.species = brain.species();
    }

    void add(AgentMob agent) {

        // Every row of a step is the same width, so a batch is one species. The driver has already refused to give this
        // brain a body it was not made for; this is the assertion that says so where the rows are actually filled in.
        if (agent.species() != this.species) {

            throw new IllegalStateException("A " + this.species.name() + " brain was handed a " + agent.species().name()
                    + " to drive; a batch is one body's");
        }

        this.agents.add(agent);
    }

    /**
     * Steps every agent added since the last run, then forgets them; the driver adds them again next tick.
     *
     * @return whether there was anyone to step
     */
    public boolean run() {

        int count = this.agents.size();

        if (count == 0) {

            return false;
        }

        int memory = this.brain.hiddenSize();

        this.step.ensureCapacity(this.species, count, memory);
        this.step.count = count;

        for (int index = 0; index < count; index++) {

            AgentMob agent = this.agents.get(index);
            BrainState state = agent.brain();
            Episode episode = agent.episode();

            state.enemySlots().tick(agent, episode == null ? null : episode.bounds());
            this.species.observe(agent, state.enemySlots(), this.step.observations, index * this.species.obsDim());

            this.step.agentIds[index] = agent.getId();
            this.step.rewards[index] = episode == null ? 0.0F : episode.reward().takeTick();

            byte flags = 0;
            boolean fresh = state.steps() == 0;

            // Nothing has stepped this agent yet, so this is the first thing its brain has ever seen of it.
            if (fresh) {

                flags |= BrainStep.FLAG_NEW;
            }

            // An arena says when its fight is over. Out in the world, nobody does, so the fight is over when the agent is.
            if (episode != null ? episode.reward().isTerminal() : !agent.isAlive()) {

                flags |= BrainStep.FLAG_DONE;
            }

            this.step.flags[index] = flags;

            if (memory > 0) {

                float[] hidden = state.hidden(memory);

                if (fresh) {

                    java.util.Arrays.fill(hidden, 0.0F);
                }

                System.arraycopy(hidden, 0, this.step.hidden, index * memory, memory);
            }
        }

        this.brain.act(this.step);

        for (int index = 0; index < count; index++) {

            AgentMob agent = this.agents.get(index);
            BrainState state = agent.brain();

            if ((this.step.flags[index] & BrainStep.FLAG_DONE) != 0) {

                // Its last step has now been taken, so it never appears in a batch again.
                state.finish();
                continue;
            }

            if (memory > 0) {

                System.arraycopy(this.step.hidden, index * memory, state.hidden(memory), 0, memory);
            }

            state.stepped();
            this.species.act(this.step.actions, index * this.species.actDim(), agent.controls());
        }

        this.agents.clear();
        return true;
    }
}
