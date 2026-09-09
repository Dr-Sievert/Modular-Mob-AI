package net.sievert.modularmobai.gametest;


public final class GameTestTuning {

    private GameTestTuning() {}

    /**
     * How many arenas run at once. Zero leaves the framework's own batching alone, which batches fifty at a time.
     *
     * Fifty measured fastest. The cost of a run is roughly {@code (arenas / batchSize) * slowestArenaInABatch *
     * costPerTick(batchSize)}: below fifty the batch count climbs faster than the batches shorten, and above it the per
     * tick cost of holding that many arenas live outruns the saving. Running all ten thousand in one batch works and
     * passes, but takes five and a half times as long as leaving this alone.
     */
    public static final int BATCH_SIZE = 0;

    /**
     * A ceiling on how fast the game test server ticks, in ticks per second. Zero lets it run flat out, which is fastest
     * and is what a normal run wants.
     *
     * The game test server never sleeps between ticks, unlike a normal server pinned to twenty, so a suite runs as fast as
     * the machine manages, which measured at roughly three hundred ticks per second with fifty arenas live. Setting a
     * ceiling is for making a run reproducible across machines, or slowing it enough to watch. A ceiling above the rate
     * the machine already achieves does nothing, and the accuracy of a very high ceiling is bounded by the operating
     * system's timer resolution.
     */
    public static final int TICKS_PER_SECOND = 0;

    /**
     * Whether each batch reuses the plots of the batch before it instead of laying out fresh ground for the whole run.
     *
     * Off measured faster. Turning it on roughly halves the world left on disk, because the run stops growing a strip of
     * thousands of chunks, but clearing the previous batch costs more time than the smaller world saves. Worth turning on
     * only when the disk footprint of a large suite is the problem.
     */
    public static final boolean REUSE_PLOTS = false;

    /**
     * Which slice of the arenas this process runs, and how many slices there are. A single process leaves both alone and
     * runs everything; the parallel run task sets them so that each worker takes an interleaved slice.
     * <p>
     * The slice is interleaved rather than contiguous (arena 1 to worker 0, arena 2 to worker 1, and so on) so every
     * worker gets the same mix of short and long fights. A contiguous split would be just as valid statistically, but the
     * run finishes when the slowest worker does, so an even spread keeps them finishing together.
     */
    public static int shardIndex() {

        return Math.max(0, intProperty("shardIndex", 0));
    }

    public static int shardCount() {

        return Math.max(1, intProperty("shardCount", 1));
    }

    /**
     * How many arenas of a suite of the given size fall to this worker.
     *
     *  total The size of the whole suite.
     *  The number this process will actually run.
     */
    public static int arenasInShard(int total) {

        final int count = shardCount();

        if (count <= 1) {

            return total;
        }

        // The remainder is handed to the lowest numbered workers, one each.
        return total / count + (total % count > shardIndex() ? 1 : 0);
    }

    /**
     * Where this process should write the durations it recorded, so the process that started the workers can combine them
     * into one summary. Empty when nothing is collecting them.
     *
     *  The path to write to, or null.
     */
    public static String statsFile() {

        final String property = System.getProperty("modular_mob_ai.gametest.statsFile");
        return property == null || property.isBlank() ? null : property.trim();
    }

    /**
     * How many arenas the suite should run. A test declares a default on its @RepeatGameTest annotation, which has to be a
     * compile time constant; this lets a run override it without recompiling, and lets the build know the real number so
     * it can size a parallel run correctly.
     *
     * @param declared The count the test declared.
     * @return The count to actually run.
     */
    public static int arenaCount(int declared) {

        return Math.max(1, intProperty("arenas", declared));
    }

    public static String captureFile() {

        final String property = System.getProperty("modular_mob_ai.gametest.capture");
        return property == null || property.isBlank() ? null : property.trim();
    }

    public static int batchSize() {

        return intProperty("batchSize", BATCH_SIZE);
    }

    public static int ticksPerSecond() {

        return intProperty("ticksPerSecond", TICKS_PER_SECOND);
    }

    public static boolean reusePlots() {

        final String property = System.getProperty("modular_mob_ai.gametest.reusePlots");
        return property == null || property.isBlank() ? REUSE_PLOTS : Boolean.parseBoolean(property);
    }

    private static int intProperty(String name, int fallback) {

        final String property = System.getProperty("modular_mob_ai.gametest." + name);

        if (property == null || property.isBlank()) {

            return fallback;
        }

        try {

            return Integer.parseInt(property.trim());
        }

        catch (NumberFormatException exception) {

            return fallback;
        }
    }
}
