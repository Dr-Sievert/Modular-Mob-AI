package net.sievert.modularmobai.brain;

/**
 * Whatever is choosing the actions.
 *
 * <p>Deliberately nothing but a block of numbers in and a block of numbers out. Keeping the boundary this narrow is what
 * lets the same entity be driven by a network in a Python process while it is being trained and by one embedded in the
 * jar once it ships, without the entity knowing or caring which. It is also why the mod never learns what a tensor is
 * and the training side never learns what a Minecraft entity is.
 *
 * <p>Sampling, log probabilities and masking all belong on the far side. The mask can be worked out from the observation
 * itself, since an empty hotbar slot and an absent opponent are both already in there, so nothing extra has to be
 * shipped to describe what is unavailable.
 */
public interface Brain {

    /**
     * Chooses an action for every agent in the step, in one call. One call per server tick is the whole point: a round
     * trip per agent would put the process boundary in the way many times a tick instead of once.
     *
     * <p>Implementations read {@link BrainStep#observations}, {@link BrainStep#rewards} and {@link BrainStep#flags}, and
     * fill in {@link BrainStep#actions}. Everything is keyed by {@link BrainStep#agentIds}, not by row.
     */
    void act(BrainStep step);

    /**
     * Nothing more is coming. Lets a transport close its connection and a recorder flush, without the caller having to
     * know which kind of brain it is holding.
     */
    default void close() {
    }
}
