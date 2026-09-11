package net.sievert.modularmobai.gametest.util;

import net.sievert.modularmobai.gametest.GameTestBenchmark;
import net.sievert.modularmobai.gametest.GameTestTuning;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.LongSummaryStatistics;

// Collects how long each run of a repeated test took and prints the spread once the whole set has reported. Game tests
// run on the server thread, so this only guards against a loader ever changing that.
public final class TestDurationStats {

    /**
     * How a fight ended. A run that never ends is not here: the framework times it out and fails the test, so it shows
     * up in the run's own output rather than in this tally.
     */
    public enum Outcome {

        WIN,
        LOSS
    }

    private final String label;
    private final int expectedRuns;
    private final List<Long> ticks = new ArrayList<>();
    private final int[] outcomes = new int[Outcome.values().length];

    public TestDurationStats(String label, int expectedRuns) {

        this.label = label;
        this.expectedRuns = expectedRuns;
    }

    /**
     * Records one run. The run that completes the set prints the summary and empties the collector, so running the suite
     * a second time in the same session reports on that run alone instead of averaging over both.
     *
     * @param ticks How long the run took.
     */
    public synchronized void record(long ticks) {

        this.record(ticks, null);
    }

    /**
     * @param outcome How the run ended, or null for a test where winning does not mean anything.
     */
    public synchronized void record(long ticks, Outcome outcome) {

        this.ticks.add(ticks);

        if (outcome != null) {

            this.outcomes[outcome.ordinal()]++;
        }

        this.publishProgress();

        if (this.ticks.size() < this.expectedRuns) {

            return;
        }

        this.write();
        this.report();
        this.ticks.clear();
        java.util.Arrays.fill(this.outcomes, 0);
    }

    /**
     * Prints the minimum, maximum and average across everything recorded so far. Only runs that reported are counted, so
     * a run that failed or timed out is absent from the spread rather than skewing it.
     */
    public synchronized void report() {

        final LongSummaryStatistics stats = this.ticks.stream().mapToLong(Long::longValue).summaryStatistics();

        GameTestBenchmark.recordArenas(stats.getSum(), (int) stats.getCount());

        final String header = "========= %s over %d runs =========".formatted(this.label, stats.getCount());

        System.out.println(header);
        System.out.println("  minimum " + format(stats.getMin()));
        System.out.println("  maximum " + format(stats.getMax()));
        System.out.println("  average " + format(stats.getAverage()));

        final int wins = this.outcomes[Outcome.WIN.ordinal()];
        final int losses = this.outcomes[Outcome.LOSS.ordinal()];

        if (wins + losses > 0) {

            System.out.println(String.format(Locale.ROOT, "  won     %,7d (%5.1f%%)", wins, 100.0D * wins / (wins + losses)));
            System.out.println(String.format(Locale.ROOT, "  lost    %,7d (%5.1f%%)", losses, 100.0D * losses / (wins + losses)));
        }

        System.out.println("=".repeat(header.length()));
    }

    /**
     * Publishes how far along this process is, so the run that started it can report progress while it works. Written at
     * most once a percent, next to the durations file, and skipped entirely when nothing is collecting.
     */
    private void publishProgress() {

        final String path = GameTestTuning.statsFile();

        if (path == null) {

            return;
        }

        final int step = Math.max(1, this.expectedRuns / 100);

        if (this.ticks.size() % step != 0 && this.ticks.size() != this.expectedRuns) {

            return;
        }

        try {

            Files.writeString(Path.of(path + ".progress"), this.ticks.size() + " " + this.expectedRuns);
        }

        catch (IOException exception) {

            // Progress is only ever a convenience; a run must not fail because it could not be reported.
        }
    }

    /**
     * Hands the raw durations to whoever started this process, when it is one worker of a parallel run. Combining the
     * numbers has to happen on the raw values: a minimum, maximum and average cannot be recovered from other summaries.
     */
    private void write() {

        final String path = GameTestTuning.statsFile();

        if (path == null) {

            return;
        }

        final StringBuilder out = new StringBuilder();

        for (Long value : this.ticks) {

            out.append(value).append(System.lineSeparator());
        }

        // Counts rather than raw values, since unlike the durations these combine across workers by adding up.
        out.append("#wins ").append(this.outcomes[Outcome.WIN.ordinal()]).append(System.lineSeparator());
        out.append("#losses ").append(this.outcomes[Outcome.LOSS.ordinal()]).append(System.lineSeparator());

        try {

            final Path file = Path.of(path);
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, out.toString());
        }

        catch (IOException exception) {

            System.err.println("[TestDurationStats] Could not write " + path + ": " + exception);
        }
    }

    private static String format(double ticks) {

        return String.format(Locale.ROOT, "%7.1f ticks (%5.2f seconds)", ticks, ticks / 20.0D);
    }
}
