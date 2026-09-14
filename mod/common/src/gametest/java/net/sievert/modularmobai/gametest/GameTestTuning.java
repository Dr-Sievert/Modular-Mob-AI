package net.sievert.modularmobai.gametest;


public final class GameTestTuning {

    private GameTestTuning() {}

    /**
     * The property that names the suite, and so the one thing that says a game-test server started this process at all:
     * every toggle here has to ask before it changes anything vanilla does. See {@link #gameTestServer()}.
     */
    private static final String SUITE_PROPERTY = "modular_mob_ai.gametest.suite";

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
     * @param total The size of the whole suite.
     * @return The number this process will actually run.
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
     * @return The path to write to, or null.
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
     * Whether each test has the world to itself: the play suite's do, and the horde suite's, which stands up to two thousand
     * mobs round one agent. Their agents are the ones met in a real game, with no arena bounding what they see, and a view of
     * 32 blocks reaches across the five block gaps between plots into the tests either side, where the zombie next door would
     * be the enemy an agent goes for. So their tests run one at a time, each on the plot the one before it had, cleared first,
     * which takes everything that test left behind with it.
     */
    public static boolean soloTests() {

        return "play".equals(suite()) || "horde".equals(suite());
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

        // Every site the terrain library hands out is one a fight can start on, so it only takes one or two to spare.
        int fallback = library() != null ? concurrentTests() + 2 : Math.max(concurrentTests() + 1, concurrentTests() * 64 / 50);

        return Math.max(1, intProperty("sites", fallback));
    }

    /**
     * How much ground a fight site holds: chunks either side of its centre chunk, so two gives the eighty blocks across
     * that every run so far has fought on. Ranged and flying fights want more room than that, and this is where it comes
     * from, but it is one number for a whole run rather than something a matchup can ask for: the terrain library holds its
     * sites at the size it was built for, so a run can only read back ground a library has, and raising it means building
     * the library again, {@code scripts\terrain.ps1 -Radius}, where what it costs is measured. Twice the chunks tick in the
     * same heap, so the price is disk and about a quarter of the throughput, not memory. See
     * {@link net.sievert.modularmobai.gametest.terrain.TerrainSites}.
     */
    public static int siteRadius() {

        return Math.max(1, Math.min(8, intProperty("siteRadius", 2)));
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
     * Whether this process is a game-test server.
     *
     * <p><b>Why anything here has to ask.</b> The game-test source set is not confined to game tests. Both loaders hand it
     * to their client and server runs as well, so that {@code /test runall} works in a development world
     * ({@code mod/fabric/build.gradle}, {@code mod/neoforge/build.gradle}), and {@code fabric.mod.json} lists
     * {@code modular_mob_ai.gametest.mixins.json} first in its mixins — so every mixin in that config is loaded and applied
     * in a real game. Anything in here with a default that changes what vanilla does therefore changes a development game
     * too, unless it asks this first. That is not a hypothetical: see {@link #lighting()}.
     *
     * <p><b>The signal.</b> The suite property being present. Both loaders' game-test runs always pass it — blank when no
     * {@code -Psuite} was named, which is what makes {@link #suite()} answer {@code arena} — and so does every worker a
     * parallel or training run starts, which copies the run task's own properties and then names the suite itself
     * ({@code runArenas} in {@code multiloader-loader.gradle}). Nothing else passes it: the client and server runs pass the
     * brain, the weights and the models and nothing more, and {@code BrainTool} and {@code MobModelTool} pass nothing at
     * all and boot no server.
     *
     * <p><b>The two other candidates are each wrong somewhere.</b> {@code -Dfabric-api.gametest} is Fabric's own, and a
     * NeoForge game-test server never sees it, so a NeoForge suite would quietly run with a development game's settings. A
     * constructed {@link net.minecraft.gametest.framework.GameTestServer} is the right question wherever one can be
     * reached, and every mixin that can reach one asks it that way instead — but a level's light engine is built before
     * anything holds the level, and a region file's writer never holds a server at all, which is exactly where two of these
     * toggles are read.
     *
     * <p>Read live rather than cached, so a test can clear the property and see the decision a real game gets; see
     * {@code PlayGameTest.aRealGameKeepsItsLightEngine}.
     */
    public static boolean gameTestServer() {

        return System.getProperties().containsKey(SUITE_PROPERTY);
    }

    /**
     * Which fights to run: {@code arena}, the agent against a vindicator in a closed nine block box on a flat world, which
     * boots in seconds and is the quick check; or {@code terrain}, the same fight out in the open on natural ground,
     * which is what training uses. {@code mechanics} runs no fights at all, only short tests of the agent's body against a
     * player's rules, in the arena's box. {@code league} is the terrain fight against a different opponent every time:
     * nearly every hostile mob, the scripted fighter, and frozen copies of the network being trained, see
     * {@link net.sievert.modularmobai.gametest.league.League}. {@code play} is the agent in a real game: the networks
     * the jar carries, the /mmai commands, sides, and the loadouts a real game gets.
     * <p>{@code library} runs no fights either: it generates fight sites and keeps them, the terrain library that the
     * terrain and league suites read their sites from instead of generating them, see
     * {@link net.sievert.modularmobai.gametest.terrain.TerrainLibrary}.
     */
    public static String suite() {

        final String property = System.getProperty(SUITE_PROPERTY);
        return property == null || property.isBlank() ? "arena" : property.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** Whether the world has to be generated as a normal one rather than flat, which the terrain and league suites need. */
    public static boolean naturalTerrain() {

        return "terrain".equals(suite()) || "league".equals(suite()) || "crowd".equals(suite()) || buildingLibrary();
    }

    /** Whether this process builds the terrain library rather than fighting. */
    public static boolean buildingLibrary() {

        return "library".equals(suite());
    }

    /**
     * Whether the light engine has to work out how bright anywhere is. Working light out is most of what a worker
     * allocates, and the two suites that train run without a light engine at all; see
     * {@link net.sievert.modularmobai.gametest.mixin.LevelLightEngineMixin} for what that saves.
     *
     * <p>Those two are named rather than every suite but a few, because being wrong here is quiet. Nothing about the
     * agent reads light: the observation has none in it, and neither the network nor the scripted fighter nor the reward
     * ever asks. What reads light is vanilla, and it reads it through more doors than it looks: brightness directly, for
     * the undead burning by day, a spider giving up by day and anything spawning naturally; and {@code canSeeSky}, which
     * is not a heightmap question but "is the sky light here fifteen", which rain then asks in turn. A vindicator, which
     * is the whole of the {@code terrain} and {@code arena} suites, asks none of them, and neither does the agent facing
     * it: nothing there burns, nothing spawns, and nothing catches fire for rain to put out.
     *
     * <p>Everything else keeps its light, and each for its own reason. The {@code league} suite fields 37 mobs and 11
     * squads of them, among
     * them the undead, spiders and endermen, and an enderman takes damage in rain; its ratings are a record of vanilla
     * behaviour and should stay one. The {@code library} suite saves the chunks it generates, light and all, and that
     * saved light is exactly what later workers read instead of working it out again. A run that keeps its world
     * ({@link #terrainFile()}, the pool behind {@code -PterrainLibrary=false}) saves it for the same reason. And
     * {@code play} and {@code mechanics} are checks, not throughput.
     *
     * <p><b>And a real game keeps its light whatever any of that says.</b> The default when nothing names a suite is
     * {@code arena}, which is the right default for a bare {@code runGametest} and was the wrong one for everything else:
     * the mixin that acts on this is loaded in a development client too, which passes no suite property, so it read "arena,
     * no terrain file" and threw away the light engine of a real world. A world made with {@code scripts\play.ps1} was pitch dark and
     * {@code /time set day} could not help it, because there was nothing left to work the light out. So the question this
     * answers is asked of {@link #gameTestServer()} first, and the rule behind it holds for everything in this class: a
     * toggle here is inert outside a game-test server. See docs/findings.md.
     */
    public static boolean lighting() {

        if (!gameTestServer()) {

            return true;
        }

        final String suite = suite();

        return !(("terrain".equals(suite) || "arena".equals(suite)) && terrainFile() == null);
    }

    /**
     * Whether a fight that asks for ground with something worth knocking an opponent into, and is handed ground with
     * nothing, may have a pool of lava poured beside it for the length of that fight. On by default;
     * {@code -PpourHazards=false} turns it off.
     *
     * <p>It is on because the overworld surface is the wrong place to look for lava: two per cent of the library's fights
     * have any, which is far too rare for the best blow in the game to be learned from. See
     * {@link net.sievert.modularmobai.gametest.terrain.PouredHazards}.
     */
    public static boolean pourHazards() {

        final String property = System.getProperty("modular_mob_ai.gametest.pourHazards");

        return property == null || property.isBlank() || Boolean.parseBoolean(property.trim());
    }

    /**
     * The terrain library's index, when the build linked a library into this worker's world: the terrain suite then
     * takes every site from it, already generated, and never generates any ground itself. Null otherwise.
     */
    public static String library() {

        final String property = System.getProperty("modular_mob_ai.gametest.library");
        return property == null || property.isBlank() ? null : property.trim();
    }

    /** How many sites the library suite generates. */
    public static int librarySites() {

        return Math.max(1, intProperty("librarySites", 4096));
    }

    /**
     * The index of the terrain library this build is adding to, when it is growing one rather than making a new one: the
     * builders read where its blocks already are and put their new ones well clear of them, since two blocks close together
     * would share region files and one would be written over the other. Null when a new library is being made.
     *
     * <p>A path rather than a list of coordinates, so that adding to a library of any size costs one argument.
     */
    public static String libraryAdding() {

        final String property = System.getProperty("modular_mob_ai.gametest.libraryAdding");
        return property == null || property.isBlank() ? null : property.trim();
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

    /**
     * Which logical processors this worker's server thread may run on, as a Windows affinity mask; zero, the default,
     * leaves it wherever Windows puts it.
     *
     * <p>The build sets this when it starts a worker without confining the whole process, so that the thread a worker
     * waits on keeps a performance core while the collector and the chunk workers spread over the efficiency ones. See
     * {@link net.sievert.modularmobai.gametest.util.ServerThreadAffinity}, which does the pinning, and the
     * {@code serverThreadCores} property of the parallel run.
     */
    public static long serverThreadCores() {

        final String property = System.getProperty("modular_mob_ai.gametest.serverThreadCores");

        if (property == null || property.isBlank()) {

            return 0L;
        }

        final String mask = property.trim();

        try {

            return mask.startsWith("0x") || mask.startsWith("0X")
                    ? Long.parseLong(mask.substring(2), 16)
                    : Long.parseLong(mask);
        }

        catch (NumberFormatException exception) {

            return 0L;
        }
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
