package net.sievert.modularmobai.brain;

import java.util.ArrayList;
import java.util.List;

import net.sievert.modularmobai.Constants;
import net.sievert.modularmobai.arena.Episode;
import net.sievert.modularmobai.arena.FightFacts;
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

    /** How many scrubbed observation values are worth a line each before the log is only repeating itself. */
    private static final int WARN_ABOUT = 8;

    private final Brain brain;
    private final Species species;
    private final BrainStep step = new BrainStep();
    private final List<AgentMob> agents = new ArrayList<>();

    /**
     * How many rows this batch has had to scrub, so the warning is said rather than said ten thousand times. A NaN in the
     * world does not go away on its own, see {@link #finite}.
     */
    private long scrubbed;

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

            int row = index * this.species.obsDim();
            this.species.observe(agent, state.enemySlots(), this.step.observations, row);
            this.finite(agent, row);

            // What the fight is, beside what the agent can see of it. No brain reads this; it goes into the rollout row for
            // the critic on the training side, and it is written here because this is where a fight and a body are both to
            // hand. Every body's, as the reward is: it is about the fight and not about the shape of whoever is in it.
            FightFacts.write(agent, this.step.facts, index * FightFacts.SIZE);

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

    /**
     * Scrubs anything that is not a number out of one agent's finished observation, and says so the first few times.
     *
     * <p><b>Why a body's own writer is not trusted to do this.</b> Most of a row is the agent's own state, which the mod
     * controls, but a good part of it is copied straight off other entities: where they are, how fast they are going, what
     * they are worth. Those numbers belong to vanilla, and vanilla can hand out a NaN — it happened. A breeze in a league
     * fight arrived at a tick with a NaN vertical velocity, and vanilla's own physics then made it permanent: {@code
     * Entity#move} only moves an entity when {@code collide(movement).lengthSqr() > 1e-7}, which is false for a NaN, so the
     * breeze stood exactly still for the rest of the fight with a NaN it could never work off. The slot copied that NaN into
     * {@code ENEMY_VELOCITY_UP} on every one of the remaining 54 ticks.
     *
     * <p><b>What one NaN costs.</b> Every layer of the network mixes the whole row, so a single NaN input makes every logit
     * a NaN, every continuous control a NaN and the log probability of the step a NaN: the agent stops aiming and stops
     * pressing anything for the rest of its life. Out in a world that is a brain-dead agent; in training it is worse,
     * because those rows are written into a rollout shard and one NaN row in sixty five thousand poisons every parameter of
     * the network the moment Adam averages it — see {@code docs/findings.md}. Zero is the reading that keeps the fight
     * going: a still opponent is a thing the network has seen a million times, and it is a great deal nearer the truth about
     * a body that is no longer moving than a NaN is.
     *
     * <p>Done here rather than in each body's writer because this is the one place every body's row is finished, so a body
     * added later is covered by having a brain at all, and because a row is scanned once whatever it contains: the cost is
     * one pass of {@code Float.isFinite} over the row, on a tick that already ran a ray cast per direction.
     */
    private void finite(AgentMob agent, int row) {

        int width = this.species.obsDim();

        for (int offset = 0; offset < width; offset++) {

            float value = this.step.observations[row + offset];

            if (Float.isFinite(value)) {

                continue;
            }

            this.step.observations[row + offset] = 0.0F;
            this.scrubbed++;

            // Every occurrence would be every tick of every fight that ever meets one, since the world does not mend
            // itself; the first few say which field of which body it was, which is what anyone reading the log needs.
            if (this.scrubbed <= WARN_ABOUT) {

                Constants.LOG.warn("A {} observation held {} at offset {} of its row; read as zero instead. Agent {} at"
                        + " {}. The world handed the observation a value that is not a number{}",
                        this.species.name(), value, offset, agent.getId(), agent.blockPosition(),
                        this.scrubbed == WARN_ABOUT ? "; no more of these will be said" : "");
            }
        }
    }
}
