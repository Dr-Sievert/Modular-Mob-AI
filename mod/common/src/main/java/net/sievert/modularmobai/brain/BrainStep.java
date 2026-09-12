package net.sievert.modularmobai.brain;

import net.sievert.modularmobai.brain.schema.Species;

/**
 * One tick's worth of work for one brain: what every agent can see, what it earned since last time, what it remembers,
 * and where its actions go.
 *
 * <p>Every row is one agent of one species, so the widths are that species' and are the same for every row. A step never
 * mixes two bodies: the driver batches by brain, and a brain drives one species.
 *
 * <p>The buffers are parallel arrays rather than a list of objects, because a flat block of numbers is what a forward
 * pass wants and what a rollout row is written from, with no walking of references. They are grown once and reused for
 * the life of the batch.
 *
 * <p>{@link #agentIds} is the part that is easy to leave out and expensive to leave out. Rows are not agents: an agent
 * dying shifts every agent after it up a row. The id follows the agent, which is what lets a recording tell whose step a
 * row was.
 */
public final class BrainStep {

    /** First step of this agent's episode. Its hidden state arrives as zeroes. */
    public static final byte FLAG_NEW = 1;

    /** Last step of this agent's episode. The observation is still real and worth bootstrapping from. */
    public static final byte FLAG_DONE = 2;

    /** How many agents are in this step. Only the first {@code count} entries of each array are meaningful. */
    public int count;

    /** Stable for as long as an agent is alive, and never reused within a game process. */
    public int[] agentIds = new int[0];

    /** {@code count * species.obsDim()}, agent by agent. */
    public float[] observations = new float[0];

    /** What each agent earned since its previous step. */
    public float[] rewards = new float[0];

    /** {@link #FLAG_NEW} and {@link #FLAG_DONE}, or zero for an ordinary step in the middle of a fight. */
    public byte[] flags = new byte[0];

    /** How many floats of memory each row carries, which is the brain's {@link Brain#hiddenSize()}. */
    public int hiddenSize;

    /** {@code count * hiddenSize}: each agent's memory going in, overwritten by the brain with its memory coming out. */
    public float[] hidden = new float[0];

    /** {@code count * species.actDim()}, filled in by the brain. Ignored for any agent whose step is flagged done. */
    public float[] actions = new float[0];

    public void ensureCapacity(Species species, int agents, int hiddenSize) {

        this.hiddenSize = hiddenSize;

        if (this.agentIds.length >= agents && this.hidden.length >= agents * hiddenSize) {

            return;
        }

        // Grown with headroom, so a suite that creeps up by one agent at a time does not reallocate on every tick.
        int capacity = Math.max(16, agents + (agents >> 1));

        this.agentIds = new int[capacity];
        this.observations = new float[capacity * species.obsDim()];
        this.rewards = new float[capacity];
        this.flags = new byte[capacity];
        this.hidden = new float[capacity * hiddenSize];
        this.actions = new float[capacity * species.actDim()];
    }
}
