package net.sievert.modularmobai.gametest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

import org.jetbrains.annotations.Nullable;

import net.sievert.modularmobai.Constants;
import net.sievert.modularmobai.brain.Brain;
import net.sievert.modularmobai.brain.Brains;

/**
 * Fights played by frozen weights in among a training run's own, so the run can tell how good the fighter it would ship
 * really is.
 *
 * <p>What a run learns from is a policy that explores, and how often that wins says little about the weights on their
 * most likely action: twice a run's training win rate rose while the fighter it would have shipped got worse, and it
 * took stopping the run and evaluating it by hand to find out. So one fight in {@link #EVERY} of a training worker is
 * handed to a checkpoint the trainer names instead, played on its most likely action with nothing recorded for learning,
 * and how it ended is written down. The trainer adds those up for each checkpoint, keeps the best weights, and decides
 * when the run has stopped getting better.
 *
 * <pre>
 *   runs/RUN/eval/target      written by the trainer: the iteration whose weights are being evaluated
 *   runs/RUN/eval/wNN.csv     appended by each worker: iteration,outcome,ticks for every evaluation fight
 * </pre>
 *
 * Only a training run evaluates, and only once the trainer has named something to evaluate.
 */
public final class Evaluation {

    private Evaluation() {}

    /** One fight in this many is an evaluation: at today's pace, the five hundred a checkpoint needs in half a minute. */
    private static final int EVERY = 10;

    /** Weights to play an evaluation fight with, and the iteration they are, which is what the result is filed under. */
    public record Assignment(int iteration, Brain brain) {}

    /** The run's eval folder, or null outside a training run or once something has gone wrong with it. */
    @Nullable
    private static Path directory;
    private static boolean resolved;

    private static int worker;
    private static long fights;

    /** The target as last read, and when its file last changed, so it is only read again when it does. */
    private static long targetModified = Long.MIN_VALUE;
    private static int targetIteration = -1;
    @Nullable
    private static Brain targetBrain;

    /** The weights to play this fight with when it is to be an evaluation, or null for an ordinary training fight. */
    @Nullable
    public static synchronized Assignment next() {

        if (!resolved) {

            resolve();
        }

        if (directory == null || fights++ % EVERY != 0) {

            return null;
        }

        refreshTarget();
        return targetBrain == null ? null : new Assignment(targetIteration, targetBrain);
    }

    /** Writes down how an evaluation fight ended: "win", "loss" or "timeout", and how long it took. */
    public static synchronized void record(Assignment assignment, String outcome, long ticks) {

        if (directory == null) {

            return;
        }

        String line = String.format(Locale.ROOT, "%d,%s,%d%n", assignment.iteration(), outcome, ticks);

        try {

            Files.writeString(directory.resolve(String.format(Locale.ROOT, "w%02d.csv", worker)), line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }

        catch (IOException exception) {

            fail("Could not write an evaluation result", exception);
        }
    }

    private static void resolve() {

        resolved = true;

        String run = System.getProperty("modular_mob_ai.training.run", "").trim();

        if (run.isEmpty()) {

            return;
        }

        try {

            directory = Files.createDirectories(Path.of(run).resolve("eval"));
            worker = GameTestTuning.shardIndex();
        }

        catch (IOException exception) {

            fail("Could not make the evaluation folder", exception);
        }
    }

    /** Reads the target again when its file has changed, and loads the weights it names. */
    private static void refreshTarget() {

        Path file = directory.resolve("target");

        try {

            if (!Files.isRegularFile(file)) {

                targetBrain = null;
                return;
            }

            long modified = Files.getLastModifiedTime(file).toMillis();

            if (modified == targetModified) {

                return;
            }

            int iteration = Integer.parseInt(Files.readString(file, StandardCharsets.UTF_8).trim());
            Path weights = directory.getParent().resolve("weights").resolve(String.format(Locale.ROOT, "%06d.mbw", iteration));

            // The trainer names a checkpoint only once its weights are written, and never prunes one while it is named.
            if (Files.isRegularFile(weights)) {

                targetBrain = Brains.network(weights);
                targetIteration = iteration;
                targetModified = modified;
            }
        }

        catch (IOException | RuntimeException exception) {

            // Caught mid write, most likely; the next evaluation fight looks again.
            Constants.LOG.debug("Could not read the evaluation target yet: {}", exception.toString());
        }
    }

    private static void fail(String message, Exception exception) {

        Constants.LOG.warn("{}; no more fights will be evaluated in this process", message, exception);
        directory = null;
    }
}
