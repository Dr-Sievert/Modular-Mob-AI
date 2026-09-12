package net.sievert.modularmobai.brain;

import java.nio.file.Path;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.util.Mth;
import net.sievert.modularmobai.brain.nn.Heads;
import net.sievert.modularmobai.brain.nn.RolloutWriter;
import net.sievert.modularmobai.brain.schema.Species;

/**
 * Writes down what a teacher would do on every tick, for a network to learn to copy, while the teacher or a student
 * drives.
 *
 * <p>A network trained from nothing starts out flailing, and against an opponent that kills in two hits it can go
 * thousands of fights before a swing happens to land. The scripted fighter already wins most fights, and everything it
 * does is decided from the observation alone, so a network can learn to do the same from a record of what it saw and
 * what it chose. Reinforcement learning then starts from a fighter instead of from noise.
 *
 * <p>A record of a teacher that never makes mistakes teaches nothing about fixing them. The scripted fighter is nearly
 * always on target, so its record is all small corrections, and a copy that drifts off target has never seen what to do
 * about it and simply stays off. Two things fix that, and both are here:
 *
 * <ul>
 *   <li>The movement and aim that get applied can be pushed off by a little noise, while what is written down is still
 *       what the teacher asked for. The record then covers being off target, and the teacher's way back.</li>
 *   <li>A student can drive instead of the teacher: the copy made from the last record fights, and the teacher says
 *       what it would have done in every situation the copy got itself into. Those are exactly the situations the copy
 *       gets wrong, which no record of the teacher driving contains. Learning from both records and fighting again,
 *       a few rounds over, brings a copy close to its teacher.</li>
 * </ul>
 *
 * The teacher can label any situation because it keeps no memory and reads nothing but the observation.
 *
 * <p>The record is an ordinary rollout shard, one per game process, with no hidden state and no log probabilities:
 * the teacher has neither.
 */
final class DemonstrationBrain implements Brain {

    private static final float[] NO_MEMORY = new float[0];

    /** What actually drives the agents: the teacher itself, or a student being corrected. */
    private final Brain driver;
    private final Brain teacher;
    private final Species species;
    private final RolloutWriter shard;

    /** Standard deviation of what is added to each continuous control before it is applied, zero for none. */
    private final float noise;

    private final RandomGenerator random;

    /** What the teacher chose, before any noise, which is what gets written down. */
    private float[] chosen = new float[0];

    /** Agents with a fight open in the record. */
    private final IntSet open = new IntOpenHashSet();

    /** The teacher drives and is recorded. */
    DemonstrationBrain(Brain teacher, Path directory, float noise) {

        this(teacher, teacher, directory, noise);
    }

    /**
     * The student drives, and the teacher's answer to every situation it gets into is recorded. The teacher must keep no
     * memory: it is asked about situations it did not bring about.
     */
    DemonstrationBrain(Brain driver, Brain teacher, Path directory, float noise) {

        int worker = Integer.getInteger("modular_mob_ai.gametest.shardIndex", 0);
        int workers = Math.max(1, Integer.getInteger("modular_mob_ai.gametest.shardCount", 1));

        // The teacher is asked about the very situations the driver got into, so both have to read the same observation and
        // press the same controls. Two bodies could not label each other's fights at all.
        if (driver.species() != teacher.species()) {

            throw new IllegalArgumentException("A " + driver.species().name() + " cannot be labelled by a teacher for a "
                    + teacher.species().name());
        }

        this.species = driver.species();
        this.driver = driver;
        this.teacher = teacher;
        this.noise = noise;
        this.random = new SplittableRandom(0x6A09E667L ^ worker);
        this.shard = new RolloutWriter(directory.resolve(String.format(Locale.ROOT, "w%02d%s", worker, RolloutWriter.EXTENSION)),
                this.species.schemaId(), 0, 0, 0, worker, workers, this.species.obsDim(), this.species.actDim(), 0);
    }

    @Override
    public Species species() {

        return this.species;
    }

    @Override
    public void act(BrainStep step) {

        this.teacher.act(step);

        int values = step.count * this.species.actDim();

        if (this.chosen.length < values) {

            this.chosen = new float[step.actions.length];
        }

        System.arraycopy(step.actions, 0, this.chosen, 0, values);

        // The teacher answered first because it writes nothing but actions: the student then overwrites those with its
        // own, and its memory has only ever been touched by itself.
        if (this.driver != this.teacher) {

            this.driver.act(step);
        }

        if (this.noise > 0.0F) {

            this.perturb(step);
        }

        for (int row = 0; row < step.count; row++) {

            int agent = step.agentIds[row];
            byte flags = step.flags[row];
            int obs = row * this.species.obsDim();

            if ((flags & BrainStep.FLAG_DONE) != 0) {

                if (this.open.remove(agent)) {

                    this.shard.end(agent, true, step.rewards[row], step.observations, obs);
                }

                continue;
            }

            if ((flags & BrainStep.FLAG_NEW) != 0 || !this.open.contains(agent)) {

                this.shard.beginSegment(NO_MEMORY, 0);
                this.open.add(agent);
            }

            this.shard.step(agent, (flags & BrainStep.FLAG_NEW) != 0, step.rewards[row], 0.0F,
                    this.chosen, row * this.species.actDim(), step.observations, obs);
        }
    }

    /** Pushes every continuous control the driver chose a little off, in what is applied only. */
    private void perturb(BrainStep step) {

        for (Heads.Block block : this.species.heads().blocks()) {

            if (block.kind() != Heads.Kind.CONTINUOUS) {

                continue;
            }

            for (int row = 0; row < step.count; row++) {

                int base = row * this.species.actDim() + block.action();

                for (int i = 0; i < block.size(); i++) {

                    float pushed = step.actions[base + i] + this.noise * (float) this.random.nextGaussian();
                    step.actions[base + i] = Mth.clamp(pushed, -1.0F, 1.0F);
                }
            }
        }
    }

    /** Whatever memory the driver needs, it still gets. */
    @Override
    public int hiddenSize() {

        return this.driver.hiddenSize();
    }

    @Override
    public void close() {

        this.shard.close(true);
        this.driver.close();

        if (this.teacher != this.driver) {

            this.teacher.close();
        }
    }
}
