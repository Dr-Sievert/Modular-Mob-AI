package net.sievert.modularmobai.brain;

import net.sievert.modularmobai.brain.schema.Species;

/**
 * Whatever is choosing the actions for a batch of agents.
 *
 * <p>Deliberately nothing but a block of numbers in and a block of numbers out, which is what lets the same entity be
 * driven by a hand written fighter in a test and by a trained network everywhere else without knowing which. One brain
 * instance is shared by every agent it drives; anything an agent needs to remember between ticks lives with the agent
 * and is passed in, never kept here.
 *
 * <p>Agents are batched by brain, so every agent in one call shares the same weights. That is what makes a single
 * forward pass over the whole batch possible, and it is also why two brains can drive agents in the same world without
 * either knowing about the other.
 */
public interface Brain {

    /**
     * Which body this brain drives. A brain is made for one species: its weights were trained against that species'
     * observation and action layout, and handing it any other body would feed it numbers that mean something else. The
     * driver refuses the mismatch rather than batching the two together, so this is not advice, it is the check.
     */
    Species species();

    /**
     * Chooses an action for every agent in the step, in one call.
     *
     * <p>Implementations read {@link BrainStep#observations}, {@link BrainStep#rewards}, {@link BrainStep#flags} and
     * {@link BrainStep#hidden}, and fill in {@link BrainStep#actions}. A brain with memory advances {@code hidden} in
     * place; the caller hands it back to each agent afterwards.
     */
    void act(BrainStep step);

    /** How many floats of memory each agent carries between ticks for this brain. Zero for a brain with none. */
    default int hiddenSize() {

        return 0;
    }

    /**
     * Nothing more is coming. Lets a recorder flush, without the caller having to know which kind of brain it is
     * holding.
     */
    default void close() {
    }
}
