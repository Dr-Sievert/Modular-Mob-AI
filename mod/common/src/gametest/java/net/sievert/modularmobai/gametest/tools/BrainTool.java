package net.sievert.modularmobai.gametest.tools;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import net.sievert.modularmobai.brain.schema.ActionSchema;
import net.sievert.modularmobai.brain.schema.ObservationSchema;
import net.sievert.modularmobai.brain.nn.ActionDecoder;
import net.sievert.modularmobai.brain.nn.Forward;
import net.sievert.modularmobai.brain.nn.WeightFile;
import net.sievert.modularmobai.brain.nn.WeightSet;

/**
 * The two things the training side needs from the game without the game running: what the observation looks like, and
 * proof that the network means the same thing on both sides.
 *
 * <pre>
 *   schema &lt;file&gt;    writes the observation and action layout, which is what the training side builds a network from
 *   parity &lt;dir&gt;     checks this build's forward pass against PyTorch's, on weights and inputs it generated
 * </pre>
 *
 * <p>Nothing here touches the game, so it runs as a plain Java program in a second rather than booting a server.
 *
 * <p>The parity check is not optional diligence, it is the difference between a bug and a mystery. Float arithmetic does
 * not associate, so the two sides never agree exactly; a transposed matrix or the reset gate applied to the wrong half of
 * the new gate shows up at a tenth, which is unmissable. Without this, a layout bug is indistinguishable from "training
 * is hard" and costs days.
 */
public final class BrainTool {

    private BrainTool() {}

    /** Floats accumulate in a different order here than in a BLAS kernel, so the two sides land this far apart. */
    private static final float TOLERANCE = 1.0E-5F;

    /** Log probabilities sum eleven terms and square a z score, so they carry a little more of that difference. */
    private static final float LOG_PROB_TOLERANCE = 1.0E-4F;

    private static final int FIXTURE_MAGIC = 'M' | 'B' << 8 | 'P' << 16 | '1' << 24;

    public static void main(String[] arguments) throws IOException {

        if (arguments.length < 2) {

            System.err.println("usage: BrainTool schema <file> | parity <dir>");
            System.exit(2);
            return;
        }

        switch (arguments[0]) {

            case "schema" -> schema(Path.of(arguments[1]));
            case "parity" -> parity(Path.of(arguments[1]));

            default -> {

                System.err.println("unknown command " + arguments[0]);
                System.exit(2);
            }
        }
    }

    /** Writes the layout exactly as the game describes it; the schema id is a checksum of these very bytes. */
    private static void schema(Path file) throws IOException {

        String json = ObservationSchema.describeJson();

        if (file.getParent() != null) {

            Files.createDirectories(file.getParent());
        }

        Files.write(file, json.getBytes(StandardCharsets.UTF_8));

        System.out.printf(Locale.ROOT, "schema %08x, observation %d wide, %d actions from %d logits -> %s%n",
                ObservationSchema.schemaId(), ObservationSchema.OBS_DIM, ActionSchema.ACT_DIM,
                ActionSchema.HEADS.logitDim(), file.toAbsolutePath());
    }

    private static void parity(Path directory) throws IOException {

        WeightSet weights = WeightFile.read(directory.resolve("weights" + WeightFile.EXTENSION), ObservationSchema.schemaId());

        ByteBuffer fixture = ByteBuffer.wrap(Files.readAllBytes(directory.resolve("fixture.bin"))).order(ByteOrder.LITTLE_ENDIAN);

        if (fixture.getInt() != FIXTURE_MAGIC) {

            throw new IOException("Not a parity fixture: " + directory.resolve("fixture.bin"));
        }

        int count = fixture.getInt();
        int obsDim = fixture.getInt();
        int hidden = fixture.getInt();
        int logitDim = fixture.getInt();
        int actDim = fixture.getInt();

        if (obsDim != weights.topology().obsDim() || hidden != weights.topology().hidden()
                || logitDim != weights.topology().outDim() || actDim != ActionSchema.ACT_DIM) {

            throw new IOException("The fixture and the weights disagree about the shapes involved");
        }

        float[] obs = read(fixture, count * obsDim);
        float[] state = read(fixture, count * hidden);
        float[] actions = read(fixture, count * actDim);
        float[] expectedLogits = read(fixture, count * logitDim);
        float[] expectedState = read(fixture, count * hidden);
        float[] expectedLogProbs = read(fixture, count);

        Forward.Scratch scratch = new Forward.Scratch();

        float[] logits = new float[count * logitDim];
        float[] advanced = state.clone();

        Forward.forward(weights, obs, advanced, logits, count, scratch);

        float logitError = maxDifference(logits, expectedLogits);
        float stateError = maxDifference(advanced, expectedState);

        float[] logProbs = new float[count];

        for (int row = 0; row < count; row++) {

            logProbs[row] = ActionDecoder.logProb(ActionSchema.HEADS, weights, logits, row * logitDim,
                    obs, row * obsDim, actions, row * actDim);
        }

        float logProbError = maxDifference(logProbs, expectedLogProbs);

        // The batch loop lives inside each layer, so the same agent has to come out the same whether it was alone or in
        // the middle of a batch. Replaying it one row at a time is what proves a rollout can be replayed at all.
        float[] singleLogits = new float[count * logitDim];
        float[] singleState = state.clone();

        for (int row = 0; row < count; row++) {

            float[] oneObs = new float[obsDim];
            float[] oneState = new float[hidden];
            float[] oneLogits = new float[logitDim];

            System.arraycopy(obs, row * obsDim, oneObs, 0, obsDim);
            System.arraycopy(state, row * hidden, oneState, 0, hidden);

            Forward.forward(weights, oneObs, oneState, oneLogits, 1, new Forward.Scratch());

            System.arraycopy(oneLogits, 0, singleLogits, row * logitDim, logitDim);
            System.arraycopy(oneState, 0, singleState, row * hidden, hidden);
        }

        float alone = Math.max(maxDifference(singleLogits, logits), maxDifference(singleState, advanced));

        // Timed before the two sets of loops are held against each other below, and never against each other in the one
        // process. Every layer calls its loops through one interface, which a process that has only ever used one set
        // reaches straight through; running both sets makes that call site polymorphic for the rest of the process and
        // costs the measurement more than the change being measured is worth. A worker only ever uses one, so that is
        // what gets timed. To compare, run this twice, once with --add-modules jdk.incubator.vector and once without.
        double perStep = benchmark(weights, obs, state, logits, count, scratch);

        // The explicit vector loops against the plain ones, at every batch size the game ever runs. -1 where this virtual
        // machine has no vector module and there is only one set of loops to check.
        int paths = bothPaths(weights, obs, state, count, hidden, obsDim, logitDim);

        System.out.printf(Locale.ROOT, "parity over %d rows of %s%n", count, weights.topology());
        System.out.printf(Locale.ROOT, "  logits          %.3e%n", logitError);
        System.out.printf(Locale.ROOT, "  hidden state    %.3e%n", stateError);
        System.out.printf(Locale.ROOT, "  log probability %.3e%n", logProbError);
        System.out.printf(Locale.ROOT, "  batched against one at a time %.3e%n", alone);
        System.out.printf(Locale.ROOT, "  loops           %s%n", Forward.vectorised()
                ? "explicit vectors, and the plain ones agree to the bit at batches 1 to " + PATH_BATCHES
                : "plain; no jdk.incubator.vector on this virtual machine");

        if (paths > 0) {

            System.out.printf(Locale.ROOT, "  the two sets of loops DIFFER at a batch of %d%n", paths);
        }

        System.out.printf(Locale.ROOT, "  %.1f us per agent per tick, so %.1f ms for a thousand agents%n",
                perStep * 1.0E6D, perStep * 1000.0D * 1000.0D);

        if (logitError > TOLERANCE || stateError > TOLERANCE || logProbError > LOG_PROB_TOLERANCE || alone != 0.0F
                || paths > 0) {

            System.err.println("PARITY FAILED: this build and the training side do not agree about the network");
            System.exit(1);
        }

        System.out.println("parity ok");
    }

    /** Batch sizes the two sets of loops are held against each other at: past every layer's vector width and tail. */
    private static final int PATH_BATCHES = 64;

    /**
     * Runs the pass both ways, with the loops this machine chose and with the plain ones, at every batch size from 1 to
     * {@link #PATH_BATCHES}, and returns the first size at which they disagree by a single bit, or 0 for none.
     *
     * <p>Every batch size, because the batch loop pairs agents up and a lone one is left over at odd sizes, and because the
     * width of a vector divides each layer differently: this is what says the explicit vector loops are the same
     * arithmetic and not merely close to it. Nothing weaker is worth having, since a network's weights were trained
     * against one answer and a difference here would show up as a fighter that is slightly worse for no reason anybody
     * could find.
     */
    private static int bothPaths(WeightSet weights, float[] obs, float[] state, int rows, int hidden, int obsDim,
                                 int logitDim) {

        if (!Forward.vectorised()) {

            return -1;
        }

        for (int agents = 1; agents <= Math.min(PATH_BATCHES, rows); agents++) {

            float[] batchObs = new float[agents * obsDim];
            float[] chosenState = new float[agents * hidden];
            float[] plainState = new float[agents * hidden];
            float[] chosenLogits = new float[agents * logitDim];
            float[] plainLogits = new float[agents * logitDim];

            System.arraycopy(obs, 0, batchObs, 0, agents * obsDim);
            System.arraycopy(state, 0, chosenState, 0, agents * hidden);
            System.arraycopy(state, 0, plainState, 0, agents * hidden);

            Forward.forward(weights, batchObs, chosenState, chosenLogits, agents, new Forward.Scratch());
            Forward.forwardScalar(weights, batchObs, plainState, plainLogits, agents, new Forward.Scratch());

            // Bit for bit, not to a tolerance: the raw bits, so that a negative zero against a positive one counts.
            if (!identical(chosenLogits, plainLogits) || !identical(chosenState, plainState)) {

                return agents;
            }
        }

        return 0;
    }

    private static boolean identical(float[] mine, float[] theirs) {

        for (int index = 0; index < mine.length; index++) {

            if (Float.floatToRawIntBits(mine[index]) != Float.floatToRawIntBits(theirs[index])) {

                return false;
            }
        }

        return true;
    }

    /**
     * Seconds per agent per tick with whichever loops this process chose, which is what the server's tick budget is spent
     * out of. The best of a few bursts rather than one long one: on a machine with training on it, the mean measures the
     * other work as much as this, and the fastest burst is the one that got the core to itself. Pin the process to a core
     * at high priority and the spread closes to a couple of percent; see findings.md.
     */
    private static double benchmark(WeightSet weights, float[] obs, float[] state, float[] logits, int count,
                                    Forward.Scratch scratch) {

        float[] working = state.clone();

        for (int warmup = 0; warmup < 200; warmup++) {

            Forward.forward(weights, obs, working, logits, count, scratch);
        }

        int passes = 200;
        double best = Double.MAX_VALUE;

        for (int burst = 0; burst < 10; burst++) {

            long started = System.nanoTime();

            for (int pass = 0; pass < passes; pass++) {

                Forward.forward(weights, obs, working, logits, count, scratch);
            }

            best = Math.min(best, (System.nanoTime() - started) / 1.0E9D / ((double) passes * count));
        }

        return best;
    }

    private static float[] read(ByteBuffer buffer, int count) {

        float[] values = new float[count];
        buffer.asFloatBuffer().get(values);
        buffer.position(buffer.position() + 4 * count);
        return values;
    }

    private static float maxDifference(float[] mine, float[] theirs) {

        float worst = 0.0F;

        for (int index = 0; index < mine.length; index++) {

            worst = Math.max(worst, Math.abs(mine[index] - theirs[index]));
        }

        return worst;
    }
}
