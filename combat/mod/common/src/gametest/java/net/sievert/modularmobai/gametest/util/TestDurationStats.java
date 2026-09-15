package net.sievert.modularmobai.gametest.util;

import net.sievert.modularmobai.gametest.GameTestBenchmark;
import net.sievert.modularmobai.gametest.GameTestTuning;

import java.io.IOException;
import java.lang.management.ManagementFactory;
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

    /**
     * A stretch in the middle of the run, from the run that finishes a tenth of the set to the one that finishes nine
     * tenths, timed on its own. The rate over the whole run folds in the slots all starting at once and the last few
     * fights running their clock out with most slots already empty, and both of those move with luck rather than with
     * what a tick costs. In the middle every slot is busy, which is what two builds should be compared on.
     *
     * <p>The stretch is also timed in processor time on the thread that records it, the server thread, which is the one
     * a worker waits on. On a machine busy with other work the wall clock also counts the moments that thread spent
     * waiting for a core, and that moves with whatever else is running; this does not.
     */
    private long ticksSoFar;
    private long windowStartNanos;
    private long windowStartCpuNanos;
    private long windowStartTicks;
    private long windowStartServerTick = -1L;
    private long windowEndNanos;
    private long windowEndCpuNanos;
    private long windowEndTicks;
    private long windowEndServerTick = -1L;

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

        this.record(ticks, outcome, -1L);
    }

    /**
     * @param serverTick A tick count that advances once a server tick, such as the test's own, so the middle of the run
     *                   can say how many server ticks it took as well as how long; -1 when there is none to hand.
     */
    public synchronized void record(long ticks, Outcome outcome, long serverTick) {

        this.ticks.add(ticks);
        this.ticksSoFar += ticks;

        if (outcome != null) {

            this.outcomes[outcome.ordinal()]++;
        }

        if (this.ticks.size() == Math.max(1, this.expectedRuns / 10)) {

            this.windowStartNanos = System.nanoTime();
            this.windowStartCpuNanos = ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
            this.windowStartTicks = this.ticksSoFar;
            this.windowStartServerTick = serverTick;
        }

        if (this.ticks.size() == this.expectedRuns * 9 / 10) {

            this.windowEndNanos = System.nanoTime();
            this.windowEndCpuNanos = ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
            this.windowEndTicks = this.ticksSoFar;
            this.windowEndServerTick = serverTick;
        }

        this.publishProgress();

        if (this.ticks.size() < this.expectedRuns) {

            return;
        }

        this.write();
        this.report();
        this.ticks.clear();
        java.util.Arrays.fill(this.outcomes, 0);
        this.ticksSoFar = 0L;
        this.windowStartNanos = 0L;
        this.windowEndNanos = 0L;
    }

    /** Arena ticks per second over the middle of the run, or zero when the run was too short to have one. */
    private double steadyArenaTicksPerSecond() {

        final double seconds = (this.windowEndNanos - this.windowStartNanos) / 1_000_000_000.0D;
        return this.windowEndNanos > this.windowStartNanos ? (this.windowEndTicks - this.windowStartTicks) / seconds : 0.0D;
    }

    /** Server ticks per second over the middle of the run, or zero when nobody said which tick it was. */
    private double steadyServerTicksPerSecond() {

        final double seconds = (this.windowEndNanos - this.windowStartNanos) / 1_000_000_000.0D;
        return this.windowEndNanos > this.windowStartNanos && this.windowStartServerTick >= 0L && this.windowEndServerTick >= 0L
                ? (this.windowEndServerTick - this.windowStartServerTick) / seconds : 0.0D;
    }

    /** Arena ticks per second of the server thread's own processor time over the middle of the run, or zero. */
    private double steadyArenaTicksPerCpuSecond() {

        final double seconds = (this.windowEndCpuNanos - this.windowStartCpuNanos) / 1_000_000_000.0D;
        return this.windowEndNanos > this.windowStartNanos && seconds > 0.0D ? (this.windowEndTicks - this.windowStartTicks) / seconds : 0.0D;
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

        if (this.steadyArenaTicksPerSecond() > 0.0D) {

            System.out.println(String.format(Locale.ROOT, "  steady  %,.1f arena ticks and %,.1f server ticks per second, over the middle 80%%",
                    this.steadyArenaTicksPerSecond(), this.steadyServerTicksPerSecond()));
            System.out.println(String.format(Locale.ROOT, "          %,.1f arena ticks per second of server thread processor time",
                    this.steadyArenaTicksPerCpuSecond()));
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

        // Rates rather than counts, since each worker's middle stretch is its own; they add up across workers all the same.
        if (this.steadyArenaTicksPerSecond() > 0.0D) {

            out.append(String.format(Locale.ROOT, "#steady %.3f %.3f %.3f", this.steadyArenaTicksPerSecond(), this.steadyServerTicksPerSecond(),
                    this.steadyArenaTicksPerCpuSecond())).append(System.lineSeparator());
        }

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
