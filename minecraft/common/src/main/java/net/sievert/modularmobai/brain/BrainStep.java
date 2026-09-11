package net.sievert.modularmobai.brain;

/**
 * One tick's worth of work for the brain: what every agent can see, what it earned since last time, and where its
 * actions go.
 *
 * <p>The buffers are parallel arrays rather than a list of objects, because this is what goes over the wire and a flat
 * block of numbers can be written straight into a byte buffer with no walking of references. They are grown once and
 * reused for the life of the batch.
 *
 * <p>{@link #agentIds} is the part that is easy to leave out and expensive to leave out. A recurrent policy carries
 * hidden state per agent, and if agents were identified by their position in the batch, one of them dying would shift
 * every agent after it into the wrong state. The id follows the agent, so the far side can keep its own book.
 */
public final class BrainStep {

    /** First step of this agent's episode. The far side should start its hidden state from scratch. */
    public static final byte FLAG_NEW = 1;

    /** Last step of this agent's episode. The observation is still real and worth bootstrapping from. */
    public static final byte FLAG_DONE = 2;

    /** How many agents are in this step. Only the first {@code count} entries of each array are meaningful. */
    public int count;

    /** Stable for as long as an agent is alive, and never reused within an episode. */
    public int[] agentIds = new int[0];

    /** {@code count * OBS_DIM}, agent by agent. */
    public float[] observations = new float[0];

    /** What each agent earned since its previous step. */
    public float[] rewards = new float[0];

    /** {@link #FLAG_NEW} and {@link #FLAG_DONE}, or zero for an ordinary step in the middle of a fight. */
    public byte[] flags = new byte[0];

    /** {@code count * ACT_DIM}, filled in by the brain. Ignored for any agent whose step is flagged done. */
    public float[] actions = new float[0];

    public void ensureCapacity(int agents) {

        if (this.agentIds.length >= agents) {

            return;
        }

        // Grown with headroom, so a suite that creeps up by one agent at a time does not reallocate on every tick.
        int capacity = Math.max(16, agents + (agents >> 1));

        this.agentIds = new int[capacity];
        this.observations = new float[capacity * ObservationSchema.OBS_DIM];
        this.rewards = new float[capacity];
        this.flags = new byte[capacity];
        this.actions = new float[capacity * ActionSchema.ACT_DIM];
    }
}
