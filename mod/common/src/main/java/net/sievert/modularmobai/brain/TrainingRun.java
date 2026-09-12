package net.sievert.modularmobai.brain;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.sievert.modularmobai.Constants;
import net.sievert.modularmobai.brain.nn.RolloutWriter;
import net.sievert.modularmobai.brain.nn.WeightFile;
import net.sievert.modularmobai.brain.nn.WeightSet;
import net.sievert.modularmobai.brain.schema.Species;

/**
 * This game process's end of a training run, which is nothing but files in one folder.
 *
 * <pre>
 *   run/weights/000042.mbw              iteration 42's policy, written by the training side
 *   run/rollouts/000042/r0003-w01.mbr   what worker 1 of round 3 did under it, written here
 * </pre>
 *
 * <p>An iteration is one policy and the experience collected under it. Every worker plays under iteration N's weights
 * until it has taken its share of the steps, hands its shard over, and waits for iteration N+1's weights to appear. The
 * training side learns from the shards once every worker has delivered, and writes the next weights. Nothing is ever
 * collected from a policy that is about to change, which is what an on-policy method needs, and the game never has to
 * restart for new weights.
 *
 * <p>Fights do not stop for an iteration boundary. Each agent still fighting has its segment cut on the boundary tick,
 * closed on that tick's observation so the training side can bootstrap what the rest of the fight was worth, and a new
 * segment opened under the new weights with the memory the agent already had. The shard a process writes when it shuts
 * down is flagged final, so the training side knows to stop waiting for it.
 */
final class TrainingRun {

    private static final String PREFIX = "modular_mob_ai.training.";

    /** How long to wait for the next weights before deciding the training side is gone. */
    private static final long WAIT_LIMIT_NANOS = 30L * 60L * 1_000_000_000L;

    private final Path directory;
    /**
     * Which body this run is training, which the weights it was handed are what say. A run cannot change body part way
     * through: the shards it has already written would be of something else.
     */
    private Species species;
    private final int round;
    private final int worker;
    private final int workerCount;

    /** Steps this worker takes per iteration: its share of the iteration's total. */
    private final int quota;

    private final RandomGenerator random;

    private int iteration;
    private WeightSet weights;
    private RolloutWriter shard;

    /** Agents with a segment open in the current shard. */
    private final IntSet open = new IntOpenHashSet();

    /** Which rows of the current step start a segment, and the memory each had going in. */
    private boolean[] starts = new boolean[0];
    private float[] startStates = new float[0];

    private TrainingRun(Path directory, int iteration, int round, int worker, int workerCount, int totalSteps) {

        this.directory = directory;
        this.iteration = iteration;
        this.round = round;
        this.worker = worker;
        this.workerCount = workerCount;
        this.quota = Math.max(1, (totalSteps + workerCount - 1) / workerCount);

        // Different in every worker and every round, so no two of them explore the same way.
        this.random = new SplittableRandom(((long) round << 40) ^ ((long) worker << 20) ^ iteration ^ 0x5DEECE66DL);

        this.weights = this.load(iteration);
        this.shard = this.openShard();

        Constants.LOG.info("Training run {}: iteration {}, round {}, worker {} of {}, {} steps a share, network {}",
                directory, iteration, round, worker, workerCount, this.quota, this.weights.topology());
    }

    /**
     * Set up by the training task, never by hand:
     *
     * <pre>
     *   -Dmodular_mob_ai.training.run=runs/default
     *   -Dmodular_mob_ai.training.iteration=42
     *   -Dmodular_mob_ai.training.round=3
     *   -Dmodular_mob_ai.training.rolloutSteps=16384
     * </pre>
     *
     * The worker's index and the worker count come from the same shard properties the parallel test run already sets.
     */
    static boolean configured() {

        return !property("run", "").isEmpty();
    }

    static TrainingRun fromProperties() {

        int worker = Integer.getInteger("modular_mob_ai.gametest.shardIndex", 0);
        int workers = Integer.getInteger("modular_mob_ai.gametest.shardCount", 1);

        return new TrainingRun(
                Path.of(property("run", "")).toAbsolutePath(),
                Integer.parseInt(property("iteration", "0")),
                Integer.parseInt(property("round", "0")),
                worker,
                Math.max(1, workers),
                Integer.parseInt(property("rolloutSteps", "16384")));
    }

    private static String property(String name, String fallback) {

        String value = System.getProperty(PREFIX + name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    WeightSet weights() {

        return this.weights;
    }

    RandomGenerator random() {

        return this.random;
    }

    /**
     * Called before the forward pass. When this worker has taken its share, ends the iteration on this tick's
     * observations and blocks until the next weights exist.
     *
     * @return the weights to act with, which are new only on a boundary
     */
    WeightSet beforeStep(BrainStep step) {

        if (this.shard.steps() < this.quota) {

            return this.weights;
        }

        for (int row = 0; row < step.count; row++) {

            int agent = step.agentIds[row];

            if (this.open.remove(agent)) {

                this.shard.end(agent, (step.flags[row] & BrainStep.FLAG_DONE) != 0, step.rewards[row],
                        step.observations, row * this.species.obsDim());
            }
        }

        // Anything still open was not in this step, which means it went away without a last step. The training side
        // bootstraps those from the last row they did write.
        this.open.clear();
        this.shard.close(false);

        this.iteration++;
        this.weights = this.awaitWeights(this.iteration);
        this.shard = this.openShard();

        return this.weights;
    }

    /** Notes the memory going into every row that starts a segment, before the forward pass overwrites it. */
    void captureSegmentStarts(BrainStep step) {

        int memory = step.hiddenSize;

        if (this.starts.length < step.count) {

            this.starts = new boolean[step.agentIds.length];
            this.startStates = new float[step.agentIds.length * memory];
        }

        for (int row = 0; row < step.count; row++) {

            byte flags = step.flags[row];

            // A new episode always starts a segment, even for an id that somehow still has one open.
            boolean starting = (flags & BrainStep.FLAG_DONE) == 0
                    && ((flags & BrainStep.FLAG_NEW) != 0 || !this.open.contains(step.agentIds[row]));

            this.starts[row] = starting;

            if (starting) {

                System.arraycopy(step.hidden, row * memory, this.startStates, row * memory, memory);
            }
        }
    }

    /** Writes the step down, once the actions and their log probabilities are known. */
    void record(BrainStep step, float[] logProbs) {

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

            if (this.starts[row]) {

                this.shard.beginSegment(this.startStates, row * step.hiddenSize);
                this.open.add(agent);
            }

            this.shard.step(agent, (flags & BrainStep.FLAG_NEW) != 0, step.rewards[row], logProbs[row],
                    step.actions, row * this.species.actDim(), step.observations, obs);
        }
    }

    /** The game is shutting down: whatever was collected goes over as this worker's final shard. */
    void close() {

        this.shard.close(true);

        Constants.LOG.info("Training run: handed over the final shard for iteration {}", this.iteration);
    }

    private RolloutWriter openShard() {

        Path file = this.directory.resolve("rollouts").resolve(String.format(Locale.ROOT, "%06d", this.iteration))
                .resolve(String.format(Locale.ROOT, "r%04d-w%02d%s", this.round, this.worker, RolloutWriter.EXTENSION));

        return new RolloutWriter(file, this.species.schemaId(), this.weights.topology().hash(), this.iteration, this.round,
                this.worker, this.workerCount, this.species.obsDim(), this.species.actDim(),
                this.weights.topology().hidden());
    }

    private Path weightsFile(int iteration) {

        return this.directory.resolve("weights").resolve(String.format(Locale.ROOT, "%06d%s", iteration, WeightFile.EXTENSION));
    }

    private WeightSet load(int iteration) {

        Path file = this.weightsFile(iteration);

        try {

            WeightSet loaded = WeightFile.read(file);
            Species resolved = NeuralBrain.check(loaded);

            if (this.species == null) {

                this.species = resolved;
            }

            else if (this.species != resolved) {

                throw new IllegalStateException("This run has been training a " + this.species.name() + " and iteration "
                        + iteration + " is for a " + resolved.name() + "; the shards already written are of another body");
            }

            return loaded;
        }

        catch (IOException exception) {

            throw new UncheckedIOException("Could not load the weights for iteration " + iteration, exception);
        }
    }

    /**
     * Blocks the server thread until the training side has written the next weights. The whole world pauses while it
     * learns, which is exactly right: nothing is collected from a policy that is about to change.
     */
    private WeightSet awaitWeights(int iteration) {

        Path file = this.weightsFile(iteration);
        long started = System.nanoTime();

        // Written under a temporary name and renamed into place, so once the file exists it is whole.
        while (!Files.isRegularFile(file)) {

            if (System.nanoTime() - started > WAIT_LIMIT_NANOS) {

                throw new IllegalStateException("Waited half an hour for " + file + "; the training process is gone");
            }

            try {

                Thread.sleep(20L);
            }

            catch (InterruptedException exception) {

                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for " + file, exception);
            }
        }

        return this.load(iteration);
    }
}
