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
     * How many arenas the suite should run, which the arenas property in gradle.properties decides.
     *
     * <p>It lives there rather than in the test because the build needs the number before any test is compiled: a
     * parallel run sizes its worker count from it, and a runner that could only learn the count by running would size
     * every run from the one before it and be wrong every time the suite changed.
     *
     * @param declared The fallback for when the property is missing, from the test's own annotation.
     * @return The count to actually run.
     */
    public static int arenaCount(int declared) {

        return Math.max(1, intProperty("arenas", declared));
    }

    /** The count with no fallback of its own, for callers that have no annotation to read. */
    public static int arenaCount() {

        return arenaCount(1);
    }

    public static String captureFile() {

        final String property = System.getProperty("modular_mob_ai.gametest.capture");
        return property == null || property.isBlank() ? null : property.trim();
    }

    /**
     * The play suite runs one test at a time, see {@link #soloTests()}; every other suite takes the framework's batches
     * unless told otherwise.
     */
    public static int batchSize() {

        return intProperty("batchSize", soloTests() ? 1 : BATCH_SIZE);
    }

    /**
     * Whether each test has the world to itself: the play suite's do. Its agents are the ones met in a real game, with no
     * arena bounding what they see, and a view of 32 blocks reaches across the five block gaps between plots into the
     * tests either side, where the zombie next door would be the enemy an agent goes for. So its tests run one at a time,
     * each on the plot the one before it had, cleared first, which takes everything that test left behind with it.
     */
    public static boolean soloTests() {

        return "play".equals(suite());
    }

    /** How many tests the framework runs at once: the batch size when one is set, fifty, vanilla's own, when not. */
    public static int concurrentTests() {

        return batchSize() > 0 ? batchSize() : 50;
    }

    /**
     * How many sites the terrain suite lays out. Every fight running holds one and some always turn out to be water or
     * cliff, so there are a quarter again as many as fights at once, sixty four for the usual fifty, unless a run names
     * a number. The sites are nearly all of a worker's memory: each is twenty five chunks kept loaded, with a ring of
     * generated ground around it, so a worker running fewer fights at once can be given a smaller heap as well.
     */
    public static int terrainSites() {

        return Math.max(1, intProperty("sites", Math.max(concurrentTests() + 1, concurrentTests() * 64 / 50)));
    }

    public static int ticksPerSecond() {

        return intProperty("ticksPerSecond", TICKS_PER_SECOND);
    }

    /**
     * On natural terrain the plots are only bookkeeping underground, but every new one still means generating real
     * chunks, which is far dearer than clearing the old ones. So there the default flips to reusing them, and so it does
     * for the play suite, whose tests each want the world to themselves, see {@link #soloTests()}.
     */
    public static boolean reusePlots() {

        final String property = System.getProperty("modular_mob_ai.gametest.reusePlots");
        return property == null || property.isBlank() ? REUSE_PLOTS || naturalTerrain() || soloTests() : Boolean.parseBoolean(property);
    }

    /**
     * Which fights to run: {@code arena}, the agent against a vindicator in a closed nine block box on a flat world, which
     * boots in seconds and is the quick check; or {@code terrain}, the same fight out in the open on natural ground,
     * which is what training uses. {@code mechanics} runs no fights at all, only short tests of the agent's body against a
     * player's rules, in the arena's box. {@code league} is the terrain fight against a different opponent every time:
     * nearly every hostile mob, the scripted fighter, and frozen copies of the network being trained, see
     * {@link net.sievert.modularmobai.gametest.league.League}. {@code play} is the agent in a real game: the networks
     * the jar carries, the /mmai commands, sides, and the loadouts a real game gets.
     */
    public static String suite() {

        final String property = System.getProperty("modular_mob_ai.gametest.suite");
        return property == null || property.isBlank() ? "arena" : property.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** Whether the world has to be generated as a normal one rather than flat, which the terrain and league suites need. */
    public static boolean naturalTerrain() {

        return "terrain".equals(suite()) || "league".equals(suite());
    }

    /**
     * Where the terrain suite puts its fight sites, as a seed; zero, the default, lets every run pick somewhere new. The
     * world's own seed never changes, so a fixed one here puts every run on the same ground, which is what comparing
     * two builds needs: one run in a forest and the next in the mountains differ by more than most changes do.
     */
    public static long terrainSeed() {

        final String property = System.getProperty("modular_mob_ai.gametest.terrainSeed");

        try {

            return property == null || property.isBlank() ? 0L : Long.parseLong(property.trim());
        }

        catch (NumberFormatException exception) {

            return 0L;
        }
    }

    /**
     * The world an earlier worker generated, when the build copied one in for the terrain suite: where its fight sites
     * are and how many of them it generated, as {@code x,z,sites}. Null when this worker finds a place for its sites and
     * generates them itself.
     */
    public static String keptTerrain() {

        final String property = System.getProperty("modular_mob_ai.gametest.keptTerrain");
        return property == null || property.isBlank() ? null : property.trim();
    }

    /** Where the terrain suite writes down which sites it fought on, for the build to keep the world; null for nowhere. */
    public static String terrainFile() {

        final String property = System.getProperty("modular_mob_ai.gametest.terrainFile");
        return property == null || property.isBlank() ? null : property.trim();
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
