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
import net.sievert.modularmobai.brain.schema.ActionSchema;
import net.sievert.modularmobai.brain.schema.ObservationSchema;

/**
 * Lets another brain drive, and writes down everything it does, for a network to learn to copy.
 *
 * <p>A network trained from nothing starts out flailing, and against an opponent that kills in two hits it can go
 * thousands of fights before a swing happens to land. The scripted fighter already wins most fights, and everything it
 * does is decided from the observation alone, so a network can learn to do the same from a record of what it saw and
 * what it chose. Reinforcement learning then starts from a fighter instead of from noise.
 *
 * <p>A record of a teacher that never makes mistakes teaches nothing about fixing them. The scripted fighter is nearly
 * always on target, so its record is all small corrections, and a copy that drifts off target has never seen what to do
 * about it and simply stays off. So while recording, the movement and aim the teacher asks for are pushed off by a
 * little noise before they are applied, and what is written down is what the teacher asked for. The record then covers
 * being off target, and the teacher's way back, which is exactly what a copy needs. The noise is off unless asked for.
 *
 * <p>The record is an ordinary rollout shard, one per game process, with no hidden state and no log probabilities:
 * the teacher has neither.
 */
final class DemonstrationBrain implements Brain {

    private static final float[] NO_MEMORY = new float[0];

    private final Brain teacher;
    private final RolloutWriter shard;

    /** Standard deviation of what is added to each continuous control before it is applied, zero for none. */
    private final float noise;

    private final RandomGenerator random;

    /** What the teacher chose, before any noise, which is what gets written down. */
    private float[] chosen = new float[0];

    /** Agents with a fight open in the record. */
    private final IntSet open = new IntOpenHashSet();

    DemonstrationBrain(Brain teacher, Path directory, float noise) {

        int worker = Integer.getInteger("modular_mob_ai.gametest.shardIndex", 0);
        int workers = Math.max(1, Integer.getInteger("modular_mob_ai.gametest.shardCount", 1));

        this.teacher = teacher;
        this.noise = noise;
        this.random = new SplittableRandom(0x6A09E667L ^ worker);
        this.shard = new RolloutWriter(directory.resolve(String.format(Locale.ROOT, "w%02d%s", worker, RolloutWriter.EXTENSION)),
                ObservationSchema.schemaId(), 0, 0, 0, worker, workers, ObservationSchema.OBS_DIM, ActionSchema.ACT_DIM, 0);
    }

    @Override
    public void act(BrainStep step) {

        this.teacher.act(step);

        int values = step.count * ActionSchema.ACT_DIM;

        if (this.chosen.length < values) {

            this.chosen = new float[step.actions.length];
        }

        System.arraycopy(step.actions, 0, this.chosen, 0, values);

        if (this.noise > 0.0F) {

            this.perturb(step);
        }

        for (int row = 0; row < step.count; row++) {

            int agent = step.agentIds[row];
            byte flags = step.flags[row];
            int obs = row * ObservationSchema.OBS_DIM;

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
                    this.chosen, row * ActionSchema.ACT_DIM, step.observations, obs);
        }
    }

    /** Pushes every continuous control the teacher chose a little off, in what is applied only. */
    private void perturb(BrainStep step) {

        for (Heads.Block block : ActionSchema.HEADS.blocks()) {

            if (block.kind() != Heads.Kind.CONTINUOUS) {

                continue;
            }

            for (int row = 0; row < step.count; row++) {

                int base = row * ActionSchema.ACT_DIM + block.action();

                for (int i = 0; i < block.size(); i++) {

                    float pushed = step.actions[base + i] + this.noise * (float) this.random.nextGaussian();
                    step.actions[base + i] = Mth.clamp(pushed, -1.0F, 1.0F);
                }
            }
        }
    }

    /** Whatever memory the brain being recorded needs, it still gets. */
    @Override
    public int hiddenSize() {

        return this.teacher.hiddenSize();
    }

    @Override
    public void close() {

        this.shard.close(true);
        this.teacher.close();
    }
}
