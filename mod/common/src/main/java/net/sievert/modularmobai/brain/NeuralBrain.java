package net.sievert.modularmobai.brain;

import java.util.random.RandomGenerator;

import org.jetbrains.annotations.Nullable;

import net.sievert.modularmobai.brain.nn.ActionDecoder;
import net.sievert.modularmobai.brain.nn.Forward;
import net.sievert.modularmobai.brain.nn.Heads;
import net.sievert.modularmobai.brain.nn.Topology;
import net.sievert.modularmobai.brain.nn.WeightSet;
import net.sievert.modularmobai.brain.schema.ActionSchema;
import net.sievert.modularmobai.brain.schema.ObservationSchema;

/**
 * A trained network, run inside the game.
 *
 * <p>There is no other process in the loop. The network is a few matrices and a GRU over plain float arrays, and one
 * forward pass covers every agent this brain drives on a tick. The weights are shared by reference and never written;
 * each agent's memory arrives in the step and leaves it advanced.
 *
 * <p>Two modes over the same weights. Deployed, it takes the most likely action. Training, it samples, which is the only
 * way a policy ever tries something it does not already believe in, and writes down every step it took, with the log
 * probability of what it chose, for the training side to learn from afterwards. Between training iterations it swaps
 * in the next weights without the game restarting.
 */
public final class NeuralBrain implements Brain {

    private static final Heads HEADS = ActionSchema.HEADS;

    private WeightSet weights;

    /** Null when deployed, which is what makes the decoder take the most likely action rather than sample. */
    @Nullable
    private final RandomGenerator random;

    @Nullable
    private final TrainingRun training;

    private final Forward.Scratch scratch = new Forward.Scratch();
    private float[] logits = new float[0];
    private float[] logProbs = new float[0];

    private NeuralBrain(WeightSet weights, @Nullable RandomGenerator random, @Nullable TrainingRun training) {

        check(weights);

        this.weights = weights;
        this.random = random;
        this.training = training;
    }

    /** Most likely action, nothing recorded. What a finished network runs as. */
    public static NeuralBrain deployed(WeightSet weights) {

        return new NeuralBrain(weights, null, null);
    }

    static NeuralBrain training(TrainingRun run) {

        return new NeuralBrain(run.weights(), run.random(), run);
    }

    /**
     * Refuses weights that cannot drive this game, however they got here. The schema id in the file already says the
     * layout matches; this is the same promise checked against the dimensions actually in hand.
     */
    static void check(WeightSet weights) {

        Topology topology = weights.topology();

        if (topology.obsDim() != ObservationSchema.OBS_DIM || topology.outDim() != HEADS.logitDim()
                || topology.stdDim() != HEADS.stdDim()) {

            throw new IllegalStateException(weights.id() + " is shaped " + topology + ", which does not fit an observation of "
                    + ObservationSchema.OBS_DIM + " and " + HEADS.logitDim() + " head outputs");
        }
    }

    public WeightSet weights() {

        return this.weights;
    }

    @Override
    public int hiddenSize() {

        return this.weights.topology().hidden();
    }

    @Override
    public void act(BrainStep step) {

        int count = step.count;

        if (this.training != null) {

            WeightSet next = this.training.beforeStep(step);

            if (next != this.weights) {

                check(next);

                // Agents carry memory sized for the old network. A new iteration of the same run never changes shape,
                // and one that did would hand every agent a hidden vector of the wrong length mid fight.
                if (!next.topology().equals(this.weights.topology())) {

                    throw new IllegalStateException(next.id() + " changed the network's shape in the middle of a run");
                }

                this.weights = next;
            }

            this.training.captureSegmentStarts(step);
        }

        int outputs = this.weights.topology().outDim();

        if (this.logits.length < count * outputs) {

            this.logits = new float[Math.max(16, count + (count >> 1)) * outputs];
            this.logProbs = new float[this.logits.length / outputs];
        }

        Forward.forward(this.weights, step.observations, step.hidden, this.logits, count, this.scratch);

        for (int row = 0; row < count; row++) {

            int actions = row * ActionSchema.ACT_DIM;

            if ((step.flags[row] & BrainStep.FLAG_DONE) != 0) {

                java.util.Arrays.fill(step.actions, actions, actions + ActionSchema.ACT_DIM, 0.0F);
                this.logProbs[row] = 0.0F;
                continue;
            }

            this.logProbs[row] = ActionDecoder.decode(HEADS, this.weights, this.logits, row * outputs,
                    step.observations, row * ObservationSchema.OBS_DIM, step.actions, actions, this.random);
        }

        if (this.training != null) {

            this.training.record(step, this.logProbs);
        }
    }

    @Override
    public void close() {

        if (this.training != null) {

            this.training.close();
        }
    }
}
