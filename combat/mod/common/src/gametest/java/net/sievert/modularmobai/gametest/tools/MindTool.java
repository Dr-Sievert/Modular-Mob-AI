package net.sievert.modularmobai.gametest.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.sievert.modularmobai.brain.nn.InterpreterNet;
import net.sievert.modularmobai.brain.nn.MindWeights;
import net.sievert.modularmobai.brain.nn.ScorerNet;
import net.sievert.modularmobai.brain.nn.WeightFile;

/**
 * The mind's two models held against the answer sheets they were frozen with, and timed.
 *
 * <pre>
 *   parity &lt;shared/models&gt;   both models against shared/models/*&#47;parity.jsonl, to a hundred thousandth
 * </pre>
 *
 * <p>{@link BrainTool} is the same idea for the combat network, and the difference is where the answers come from. That
 * one generates a fixture with PyTorch and checks against it in the same command, because the network it checks is
 * whatever a run is training this minute. These two are <b>frozen</b>: {@code mind/tools/freeze.py} wrote 200 records for
 * each, with the exact numbers its numpy reference produced, and committed them. So this side never runs Python at all —
 * it reads the same two files a person would read, which is the whole point of freezing them.
 *
 * <p>The order of the checks is not cosmetic, and the interpreter's is the one that matters:
 *
 * <ol>
 *   <li><b>buckets, exactly.</b> An integer mismatch is a tokenizer or a hash bug and nothing further can line up.
 *       {@code String.toLowerCase()} and walking UTF-16 chars instead of code points are the two ways to get here;</li>
 *   <li><b>the side vector.</b> Its scalar columns are integer counts written as floats, so a difference there is a
 *       counting bug and not rounding;</li>
 *   <li><b>logits, floats and scores</b>, where 1e-5 is the only tolerance allowed to be anything but zero.</li>
 * </ol>
 *
 * <p>It also times both, because a chat line is answered on the server thread and a decision is taken every tenth tick
 * for every agent that has a mind: what those cost is a number this build should have to hand, not a guess.
 *
 * <p>Nothing here touches the game, so it runs as a plain Java program in a second. That also means no JSON library —
 * Gson belongs to the game and is not on this classpath — which is what {@link Json} is for.
 */
public final class MindTool {

    private MindTool() {}

    /** What the frozen answer sheets promise: every number to a hundred thousandth, and the bucket lists exactly. */
    private static final float TOLERANCE = 1.0E-5F;

    /** How many records each model's answer sheet holds. Named so that a half-written file is a failure, not a pass. */
    private static final int RECORDS = 200;

    /** Bursts, and calls per burst, for the timings. The best burst is the one that got the core to itself. */
    private static final int BURSTS = 10;
    private static final int WARMUP = 20;

    public static void main(String[] arguments) throws IOException {

        if (arguments.length < 2 || !arguments[0].equals("parity")) {

            System.err.println("usage: MindTool parity <shared/models>");
            System.exit(2);
            return;
        }

        Path models = Path.of(arguments[1]);
        Map<String, Object> manifest = Json.object(Json.parse(read(models.resolve("MANIFEST.json"))));

        boolean ok = interpreter(models.resolve("interpreter"), layoutSchemaId(manifest, "interpreter"));
        ok &= decisions(models.resolve("decisions"), layoutSchemaId(manifest, "decisions"));

        if (!ok) {

            System.err.println("MIND PARITY FAILED: this build and the frozen models do not agree");
            System.exit(1);
        }

        System.out.println("mind parity ok");
    }

    /**
     * What the header's schema id should be, worked out from the manifest rather than trusted: the first four bytes of
     * that model's {@code layout.json} sha256, read big endian. A layout that moved shows up here as a mismatch with a
     * hexadecimal number in it, which is the signal that the Java side's constant needs the new contract.
     */
    private static int layoutSchemaId(Map<String, Object> manifest, String name) {

        for (Object entry : Json.array(manifest.get("models"))) {

            Map<String, Object> model = Json.object(entry);

            if (name.equals(model.get("name"))) {

                return (int) Long.parseLong(((String) model.get("layout_sha256")).substring(0, 8), 16);
            }
        }

        throw new IllegalStateException("MANIFEST.json names no model called " + name);
    }

    // -- the interpreter ------------------------------------------------------------------------------------------

    private static boolean interpreter(Path directory, int schemaId) throws IOException {

        MindWeights weights = WeightFile.readMind(directory.resolve("interpreter" + WeightFile.EXTENSION),
                WeightFile.KIND_CLASSIFIER);

        boolean ok = sameSchema("interpreter", weights.schemaId(), schemaId, InterpreterNet.SCHEMA_ID);
        InterpreterNet net = new InterpreterNet(weights);

        List<Map<String, Object>> records = lines(directory.resolve("parity.jsonl"));

        int bucketsWrong = 0;
        float sideError = 0.0F;
        float logitError = 0.0F;
        float floatError = 0.0F;

        for (Map<String, Object> record : records) {

            String text = (String) record.get("text");
            String previous = (String) record.get("prev_intent");

            net.run(text, previous);

            int[] wanted = Json.ints(record.get("buckets"));

            if (!sameBuckets(net, wanted)) {

                if (bucketsWrong == 0) {

                    System.out.printf(Locale.ROOT, "  record %d (%s) hashes to %d buckets, the answer sheet has %d: %s%n",
                            (int) (double) (Double) record.get("i"), record.get("source"), net.bucketCount(),
                            wanted.length, shorten(text));
                }

                bucketsWrong++;
            }

            sideError = Math.max(sideError, worst(net.side(), Json.floats(record.get("side"))));

            Map<String, Object> logits = Json.object(record.get("logits"));

            logitError = Math.max(logitError, worst(net.logits(InterpreterNet.HEAD_INTENT),
                    Json.floats(logits.get("intent"))));
            logitError = Math.max(logitError, worst(net.logits(InterpreterNet.HEAD_TOPIC),
                    Json.floats(logits.get("topic"))));
            logitError = Math.max(logitError, worst(net.logits(InterpreterNet.HEAD_ADDRESSED),
                    Json.floats(logits.get("addressed"))));
            logitError = Math.max(logitError, worst(net.logits(InterpreterNet.HEAD_SINCERITY),
                    Json.floats(logits.get("sincerity"))));

            floatError = Math.max(floatError, worst(net.floats(), Json.floats(record.get("floats"))));
        }

        boolean buffers = sameOnALongLine(weights, records);
        double micros = timeInterpreter(net, records);

        System.out.printf(Locale.ROOT, "interpreter over %d records of %s%n", records.size(), net.shape());
        System.out.printf(Locale.ROOT, "  buckets         %s%n",
                bucketsWrong == 0 ? "exact on every record" : bucketsWrong + " records DIFFER");
        System.out.printf(Locale.ROOT, "  buffers         %s%n",
                buffers ? "a line longer than any fixture reads the same from cold and from warm"
                        : "a long line reads DIFFERENTLY from cold and from warm");
        System.out.printf(Locale.ROOT, "  side vector     %.3e%n", sideError);
        System.out.printf(Locale.ROOT, "  logits          %.3e%n", logitError);
        System.out.printf(Locale.ROOT, "  floats          %.3e%n", floatError);
        System.out.printf(Locale.ROOT, "  %.1f us per message, over the %d fixture lines%n", micros, records.size());

        return ok && records.size() == RECORDS && bucketsWrong == 0 && buffers
                && sideError <= TOLERANCE && logitError <= TOLERANCE && floatError <= TOLERANCE;
    }

    /**
     * One line longer than anything the answer sheet holds, read by a net that has already grown its buffers and by one
     * straight out of the constructor. The two have to agree.
     *
     * <p>The answer sheet cannot ask this. Its longest line is forty words, and a net's working buffers start wide enough
     * for that, so every record it holds is read out of the buffers the constructor made and nothing that happens when
     * they are outgrown is ever exercised. A buffer grown without carrying what it held is then a bug that appears the
     * first time a player types a long sentence and never before — including here.
     */
    private static boolean sameOnALongLine(MindWeights weights, List<Map<String, Object>> records) {

        StringBuilder text = new StringBuilder();

        for (Map<String, Object> record : records) {

            text.append((String) record.get("text")).append(' ');
        }

        String line = text.toString();
        InterpreterNet net = new InterpreterNet(weights);

        // The first reading of this line grows every buffer it has, several times over; the second grows none of them.
        // Comparing the two is what has teeth, and comparing two *fresh* nets is what does not: they grow identically and
        // would agree on the same wrong answer. The buggy version this was written against loses a token every time a
        // buffer doubles, so the grown reading and the settled one are two different bags.
        net.run(line, null);

        int[] first = new int[net.bucketCount()];
        System.arraycopy(net.buckets(), 0, first, 0, first.length);

        float[] firstFloats = net.floats().clone();

        net.run(line, null);

        return sameBuckets(net, first) && worst(net.floats(), firstFloats) == 0.0F;
    }

    private static boolean sameBuckets(InterpreterNet net, int[] wanted) {

        if (net.bucketCount() != wanted.length) {

            return false;
        }

        int[] got = net.buckets();

        for (int index = 0; index < wanted.length; index++) {

            if (got[index] != wanted[index]) {

                return false;
            }
        }

        return true;
    }

    /**
     * Microseconds a message, over the fixture's own lines, after a warm-up. The best burst rather than the mean of all
     * of them, for {@link BrainTool#main}'s reason: on a machine with training on it the mean measures the other work as
     * much as this.
     */
    private static double timeInterpreter(InterpreterNet net, List<Map<String, Object>> records) {

        for (int warmup = 0; warmup < WARMUP; warmup++) {

            for (Map<String, Object> record : records) {

                net.run((String) record.get("text"), (String) record.get("prev_intent"));
            }
        }

        double best = Double.MAX_VALUE;

        for (int burst = 0; burst < BURSTS; burst++) {

            long started = System.nanoTime();

            for (Map<String, Object> record : records) {

                net.run((String) record.get("text"), (String) record.get("prev_intent"));
            }

            best = Math.min(best, (System.nanoTime() - started) / 1000.0D / records.size());
        }

        return best;
    }

    // -- the decisions model --------------------------------------------------------------------------------------

    private static boolean decisions(Path directory, int schemaId) throws IOException {

        MindWeights weights = WeightFile.readMind(directory.resolve("decisions" + WeightFile.EXTENSION),
                WeightFile.KIND_SCORER);

        boolean ok = sameSchema("decisions", weights.schemaId(), schemaId, ScorerNet.SCHEMA_ID);
        ScorerNet net = new ScorerNet(weights);

        List<Map<String, Object>> records = lines(directory.resolve("parity.jsonl"));

        float[][] observations = new float[records.size()][];
        float[][] candidates = new float[records.size()][];
        float[] answers = new float[records.size()];

        for (int index = 0; index < records.size(); index++) {

            Map<String, Object> record = records.get(index);

            observations[index] = Json.floats(record.get("obs"));
            candidates[index] = Json.floats(record.get("cand"));
            answers[index] = ((Number) record.get("score")).floatValue();
        }

        float scoreError = 0.0F;

        for (int index = 0; index < records.size(); index++) {

            scoreError = Math.max(scoreError, Math.abs(net.score(observations[index], candidates[index])
                    - answers[index]));
        }

        // The observation half of the first layer is shared by every candidate of one decision, so a decision's second
        // candidate costs less than its first. Both paths have to give the same number, or the saving is a bug.
        float sharedError = 0.0F;

        for (int index = 0; index < records.size(); index++) {

            net.observe(observations[index]);
            sharedError = Math.max(sharedError, Math.abs(net.scoreObserved(candidates[index]) - answers[index]));
        }

        double micros = timeScorer(net, observations, candidates, false);
        double shared = timeScorer(net, observations, candidates, true);

        System.out.printf(Locale.ROOT, "decisions over %d records of %s%n", records.size(), net.shape());
        System.out.printf(Locale.ROOT, "  scores          %.3e%n", scoreError);
        System.out.printf(Locale.ROOT, "  shared observation product  %.3e%n", sharedError);
        System.out.printf(Locale.ROOT, "  %.2f us per candidate from cold, %.2f us per further candidate of one "
                + "decision, over the %d fixture pairs%n", micros, shared, records.size());

        return ok && records.size() == RECORDS && scoreError <= TOLERANCE && sharedError <= TOLERANCE;
    }

    /**
     * Microseconds a candidate, either from cold or with the observation half of the first layer already worked out.
     * The second is what a decision actually costs after its first candidate, since a decision is eight to thirty of
     * them over one observation.
     */
    private static double timeScorer(ScorerNet net, float[][] observations, float[][] candidates, boolean shared) {

        float sink = 0.0F;

        for (int warmup = 0; warmup < WARMUP; warmup++) {

            sink += pass(net, observations, candidates, shared);
        }

        double best = Double.MAX_VALUE;

        for (int burst = 0; burst < BURSTS; burst++) {

            long started = System.nanoTime();

            sink += pass(net, observations, candidates, shared);

            best = Math.min(best, (System.nanoTime() - started) / 1000.0D / observations.length);
        }

        // Read once so that nothing above can be optimised away as dead.
        if (Float.isNaN(sink)) {

            throw new IllegalStateException("a score came out NaN");
        }

        return best;
    }

    private static float pass(ScorerNet net, float[][] observations, float[][] candidates, boolean shared) {

        float sink = 0.0F;

        if (shared) {

            net.observe(observations[0]);
        }

        for (int index = 0; index < observations.length; index++) {

            sink += shared ? net.scoreObserved(candidates[index]) : net.score(observations[index], candidates[index]);
        }

        return sink;
    }

    // -- shared ---------------------------------------------------------------------------------------------------

    /**
     * Three numbers that all have to be the same: what the weight file carries, what the manifest's layout hashes to,
     * and what this build's own constant says. The first two drifting means a stale weight file; the third means the
     * layout moved and the Java constant did not follow it.
     */
    private static boolean sameSchema(String name, int file, int manifest, int build) {

        if (file == manifest && manifest == build) {

            return true;
        }

        System.out.printf(Locale.ROOT, "%s: the weight file says schema %08x, MANIFEST.json's layout hashes to %08x, "
                + "and this build reads %08x%n", name, file, manifest, build);
        return false;
    }

    private static List<Map<String, Object>> lines(Path path) throws IOException {

        return Files.readAllLines(path, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank())
                .map(line -> Json.object(Json.parse(line)))
                .toList();
    }

    private static String read(Path path) throws IOException {

        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static float worst(float[] mine, float[] theirs) {

        if (mine.length != theirs.length) {

            return Float.MAX_VALUE;
        }

        float worst = 0.0F;

        for (int index = 0; index < mine.length; index++) {

            worst = Math.max(worst, Math.abs(mine[index] - theirs[index]));
        }

        return worst;
    }

    private static String shorten(String text) {

        return text.length() <= 48 ? "\"" + text + "\"" : "\"" + text.substring(0, 45) + "...\"";
    }
}
