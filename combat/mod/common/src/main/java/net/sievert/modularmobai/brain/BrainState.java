package net.sievert.modularmobai.brain;

import java.util.Arrays;

import org.jetbrains.annotations.Nullable;

import net.sievert.modularmobai.brain.schema.EnemySlots;

/**
 * Everything one agent's brain remembers between ticks, and which brain that is.
 *
 * <p>This is the only mutable brain state an agent owns. The brain itself, weights and all, is shared by every agent it
 * drives; what makes one agent's fight its own is the hidden vector here and the enemy leases beside it. The driver
 * gathers the hidden vectors of a whole batch before the forward pass and hands each one back after, so nothing else
 * ever writes to them.
 */
public final class BrainState {

    /** Null until the first tick, when the driver fills in the agent's own or the game's default, see Brains#forAgent. */
    @Nullable
    private Brain brain;

    private float[] hidden = new float[0];
    private final EnemySlots enemySlots = new EnemySlots();

    private int steps;
    private boolean finished;

    @Nullable
    public Brain brain() {

        return this.brain;
    }

    /**
     * Hands the agent to a different brain. Its memory belonged to the old one and means nothing to the new one, so it
     * starts again from nothing, exactly as a fresh episode would. Null leaves the choice to the driver's next tick.
     */
    public void use(@Nullable Brain replacement) {

        if (replacement != this.brain) {

            this.brain = replacement;
            this.hidden = new float[0];
            this.steps = 0;
        }
    }

    /** The agent's memory, sized for the brain driving it. Reallocated, and so cleared, only if that size changes. */
    float[] hidden(int size) {

        if (this.hidden.length != size) {

            this.hidden = new float[size];
        }

        return this.hidden;
    }

    public EnemySlots enemySlots() {

        return this.enemySlots;
    }

    /** How many steps this agent has been through. Zero means its next step is its first. */
    public int steps() {

        return this.steps;
    }

    void stepped() {

        this.steps++;
    }

    /** Whether the agent's last step has been sent, so it should not be batched again. */
    public boolean isFinished() {

        return this.finished;
    }

    void finish() {

        this.finished = true;
    }

    /**
     * Forgets what the agent remembered, so that its next step is a first one, with the same brain and the same view.
     * The driver zeroes the memory of an agent on its first step, so nothing else has to be cleared.
     */
    public void restart() {

        this.steps = 0;
    }

    /** Back to the start of an episode, with the same brain. */
    public void reset() {

        Arrays.fill(this.hidden, 0.0F);
        this.enemySlots.clear();
        this.steps = 0;
        this.finished = false;
    }
}
