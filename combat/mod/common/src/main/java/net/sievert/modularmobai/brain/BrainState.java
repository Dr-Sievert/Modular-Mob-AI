package net.sievert.modularmobai.brain;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import net.sievert.modularmobai.brain.schema.EnemySlots;

/**
 * Everything one agent's brain remembers between ticks, and which brain that is.
 *
 * <p>This is the only mutable brain state an agent owns. The brain itself, weights and all, is shared by every agent it
 * drives; what makes one agent's fight its own is the hidden vectors here and the enemy leases beside them. The driver
 * gathers the hidden vector of a whole batch before the forward pass and hands each one back after, so nothing else
 * ever writes to them.
 *
 * <h2>One hidden vector per brain, not one per agent</h2>
 *
 * <p>This used to hold a single vector and clear it on <b>any</b> change of brain, which was right while a switch only
 * happened between a scripted teacher and a network that never shared an agent. It is wrong the moment an agent can
 * change brains mid-fight — which is what the mind's arbitrator is for: fight, flee, work, fight again, and each switch
 * would have wiped the combat network's 128 floats of memory in the middle of the fight it was remembering.
 *
 * <p>So the memory belongs to the <b>brain</b> and not to the switch. {@link #use} now only says which brain is current;
 * a vector is cleared by {@link #reset}, which is the start of an episode, and dropped when the brain it belongs to has
 * not driven this agent for {@link #FORGET_TICKS} ticks. An agent that spends twenty ticks under the scripted fighter
 * and comes back to its network comes back to the same vector, holding what it held — that is the whole of the seam,
 * and {@code theCombatMemorySurvivesASpellUnderAnotherBrain} is the test that holds it.
 *
 * <p>A brain with no memory of its own — the scripted fighter, the arbitrator to come, anything with
 * {@link Brain#hiddenSize()} of zero — never gets a vector at all, so none of this costs it anything.
 */
public final class BrainState {

    /**
     * How long a brain's memory is kept after it last drove this agent, in ticks. Half a minute: long enough that any
     * spell under another brain keeps the fight it was in the middle of, short enough that a network an agent was
     * driven by an hour ago is not still holding 128 floats and a reference to its weights. Dropping it on a timer
     * rather than on the switch is the point; see the class comment.
     */
    public static final int FORGET_TICKS = 600;

    /** Null until the first tick, when the driver fills in the agent's own or the game's default, see Brains#forAgent. */
    @Nullable
    private Brain brain;

    /**
     * What each brain remembers about this agent. Keyed by identity because a brain is shared by everything that names
     * the same weights, which is what makes two agents on one network one batch; two brains are the same memory exactly
     * when they are the same object.
     *
     * <p>It holds one entry for an agent that has only ever run one network, and none at all for one that has only ever
     * been driven by the scripted fighter.
     */
    private final Map<Brain, Held> memories = new IdentityHashMap<>();

    private final EnemySlots enemySlots = new EnemySlots();

    private int steps;
    private boolean finished;

    @Nullable
    public Brain brain() {

        return this.brain;
    }

    /**
     * Hands the agent to a different brain. Nothing is forgotten: what it remembered belonged to the brain that
     * remembered it, and that brain will want it back. Null leaves the choice to the driver's next tick.
     */
    public void use(@Nullable Brain replacement) {

        this.brain = replacement;
    }

    /**
     * That brain's memory of this agent, sized for it, made on the spot the first time and zeroed then. Reallocated, and
     * so cleared, only if the size ever changes — which is a brain whose weights were swapped underneath it.
     *
     * @param now the game time, for the timer that drops what nothing has used for a while
     */
    float[] hidden(Brain of, int size, long now) {

        Held held = this.memories.get(of);

        if (held == null) {

            held = new Held(new float[size]);
            this.memories.put(of, held);
        }

        else if (held.vector.length != size) {

            held.vector = new float[size];
        }

        held.used = now;

        this.forgetTheUnused(now);

        return held.vector;
    }

    /**
     * What that brain remembers about this agent, or null where it has never driven it or has been forgotten. Read by
     * the test that holds the seam and by {@code /mmai}; nothing in a fight reads it.
     */
    @Nullable
    public float[] memoryOf(Brain of) {

        Held held = this.memories.get(of);

        return held == null ? null : held.vector;
    }

    /** How many brains are holding a memory of this agent. */
    public int memoryCount() {

        return this.memories.size();
    }

    /**
     * Drops what nothing has used for a while. Walked on a tick a memory is handed out, and only where there is more
     * than one to walk, so the ordinary agent — one brain, one memory, for its whole life — never pays for it.
     */
    private void forgetTheUnused(long now) {

        if (this.memories.size() < 2) {

            return;
        }

        Iterator<Map.Entry<Brain, Held>> entries = this.memories.entrySet().iterator();

        while (entries.hasNext()) {

            Map.Entry<Brain, Held> entry = entries.next();

            if (now - entry.getValue().used > FORGET_TICKS) {

                entries.remove();
            }
        }
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

    /**
     * Back to the start of an episode, with the same brain. Every brain's memory of this agent is zeroed and not
     * dropped: a vector keeps its identity, so a brain that is handed one mid-batch is handed the array it was handed
     * before, holding nothing.
     */
    public void reset() {

        for (Held held : this.memories.values()) {

            Arrays.fill(held.vector, 0.0F);
        }

        this.enemySlots.clear();
        this.steps = 0;
        this.finished = false;
    }

    /** One brain's memory of this agent, and when it was last wanted. */
    private static final class Held {

        private float[] vector;
        private long used;

        private Held(float[] vector) {

            this.vector = vector;
        }
    }
}
