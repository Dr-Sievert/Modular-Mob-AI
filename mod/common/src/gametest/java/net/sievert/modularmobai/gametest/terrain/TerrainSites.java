package net.sievert.modularmobai.gametest.terrain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.sievert.modularmobai.Constants;
import net.sievert.modularmobai.gametest.GameTestTuning;

/**
 * Patches of natural ground for fights to happen on, handed out one per fight.
 *
 * <p>The game test framework lays its plots out a few blocks apart, which suits fights inside boxes and not fights in the
 * open, where a fighter can wander into the neighbouring one. So the plots the framework places are only bookkeeping, deep
 * under the spawn, and each fight is put on its own site instead: a lattice of sites a long way apart, each eighty blocks
 * across and kept loaded and ticking. There are no walls. The chunks between sites never tick entities, so anything that
 * walks off its site simply stops there, the fight cannot finish, and it ends the way any stalled fight does: a loss on
 * time. No two fights can ever reach each other.
 *
 * <p>The lattice is placed once per game process, somewhere the world generator says is land, and the sites are handed
 * out round robin. There are more sites than fights run at once, so a site is only ever handed out again after the fight
 * on it has finished and been cleaned up. The terrain stays as the generator left it: fights move nothing but
 * themselves, and what they drop is swept up after them.
 *
 * <p>Generating the lattice is most of what starting a worker costs, so fights do not wait for all of it. The sites are
 * generated a couple at a time, in order, and each one is handed out as soon as it is ready, while the rest are still
 * being generated behind it. Better still, the build keeps the worlds workers generated and hands them to later workers,
 * whose lattice then goes where the earlier one put it and is read from disk in seconds; see {@link #start}. Since
 * fights leave the ground as they found it, a kept world is the same terrain the generator made.
 *
 * <p>Sites move on. A worker in training fights thousands of battles, and on a fixed lattice every site hosted a couple
 * of hundred of them: one bad patch, a pit the opponent fell into and neither could leave, came back every thirty
 * fights and made up half of every fight lost. So each site is swapped for fresh ground after {@link #SITE_FIGHTS}
 * fights, taken from a few spare sites generated ahead in the lattice beyond. A swap waits for a spare to be ready,
 * and a site past its fights keeps being used until one is, so fights never wait on the generator for this. A site where
 * fights run out the clock twice, or with nowhere to stand, is taken out of use straight away: that is the ground,
 * not the fighting. Timeouts still count as the losses they are.
 *
 * <p>With a terrain library, which the build links in whenever there is one, none of the ground is generated here at
 * all. The lattice is the library's, every site and spare is read from disk ready made, and the points the library found
 * no fight could start on are never used; see {@link TerrainLibrary}. A worker starts somewhere of its own in the
 * library and walks on through it, point after point, the way it would walk its own lattice.
 */
public final class TerrainSites {

    private TerrainSites() {}

    /** Carried by everything a fight spawns, so a sweep can tell a fighter from wildlife the world generated. */
    public static final String TAG = Constants.MOD_ID + ".arena";

    /**
     * Chunks kept loaded and ticking either side of a site's centre chunk: two, so a fight has five chunks, eighty
     * blocks, of open ground each way, and starts in the middle of it.
     */
    static final int RADIUS_CHUNKS = 2;
    private static final int SIZE = (2 * RADIUS_CHUNKS + 1) * 16;

    /**
     * Blocks between site centres: eight chunks, so three chunks of dead ground separate one fight from the next. The
     * worlds kept in runs/terrain and the terrain library were generated for this layout; delete them after changing it,
     * or workers generate the new sites anyway, only slower, and a library says what it was built with.
     */
    static final int SPACING = 128;
    static final int COLUMNS = 8;

    /**
     * More than run at once, which is fifty unless a run asks for other batches: every fight in progress holds a site,
     * and some of the lattice is always water or cliff that nothing can stand on. When all the usable ones are busy, a
     * fight waits a tick for one. Each site is twenty five chunks kept in memory, which is what keeps this from growing:
     * eighty sites did not fit in a gigabyte and a half of heap. See {@link GameTestTuning#terrainSites()}.
     */
    private static final int COUNT = GameTestTuning.terrainSites();

    /**
     * Sites the world generator works on at once. Asked for all at once, every site came out of the generator at about
     * the same moment, a minute and a half in: it takes every chunk it has been asked for through each stage before the
     * next, so the first fight waited for the last site. Two at a time keeps its threads busy while the first site is
     * ready within seconds.
     *
     * <p>Sites an earlier worker generated are only read from disk, which is quick, and quickest all together: the first
     * two come on their own, so the first fight is not held up, and the rest are asked for as soon as one is ready.
     */
    private static final int GENERATING = 2;

    /** Tries at a site, each from a different spot near its centre, before it is written off as water or cliff. */
    private static final int PLACEMENT_TRIES = 4;

    /** How far a fight's centre may sit from its site's centre, so the same site is not the same fight every time. */
    private static final int JITTER = 6;

    /** How far from the centre to look for somewhere to stand. Kept well inside the site, so nobody starts near an edge. */
    private static final int SEARCH = 10;

    private static final int OPPONENT_MIN_DISTANCE = 7;
    private static final int OPPONENT_MAX_DISTANCE = 11;
    private static final int OPPONENT_MAX_CLIMB = 3;

    /** How many places to try for the lattice before settling for the one with the most land. */
    private static final int ORIGIN_ATTEMPTS = 400;
    private static final int ORIGIN_RANGE = 2_000_000;

    /**
     * A fight's patch of ground.
     *
     * @param bounds what the fighters may perceive: the site's loaded area, from a little below the lower of the two up
     *               to well above the higher
     */
    public record Site(int index, BlockPos agent, BlockPos opponent, AABB bounds) {}

    /**
     * Fights a site hosts before it is swapped for fresh ground, once a spare is ready to take its place. Every swap
     * generates a new site and the ring of part generated chunks around it, which is most of what a worker's other cores
     * do: at twenty five, a worker running flat out generated a new site several times a second. A site that keeps
     * running out the clock is swapped sooner anyway, see {@link #SITE_TIMEOUTS}.
     */
    private static final int SITE_FIGHTS = 100;

    /** Fights on a site that run out the clock before it is taken out of use: two says it is the ground, not luck. */
    private static final int SITE_TIMEOUTS = 2;

    /**
     * Sites generated ahead in the lattice, ready to take the place of one that has had its fights. From the library a
     * spare is read in a fraction of a second rather than generated, so two do, and each one held ready is a site's
     * worth of memory.
     */
    private static final int SPARES = GameTestTuning.library() != null ? 2 : 4;

    @Nullable
    private static BlockPos origin;

    /** The terrain library the sites come from, when the build linked one in; null when this worker generates its own. */
    @Nullable
    private static TerrainLibrary.Index library;

    /** The library point the next site or spare is taken from. */
    private static int libraryCursor;

    private static int next;
    private static final boolean[] unusable = new boolean[COUNT];

    /**
     * Which point of the lattice each site is on right now. It starts on its own index; a swap moves it on to a spare,
     * further along the lattice than any site before it.
     */
    private static final int[] lattice = new int[COUNT];

    /** Fights each site has hosted since it last moved, and how many of those ran out the clock. */
    private static final int[] fights = new int[COUNT];
    private static final int[] timeouts = new int[COUNT];

    /** Points of the lattice asked for as spares, in order, and whether each is loaded yet. */
    private static final int[] spares = new int[SPARES];
    private static final boolean[] spareReady = new boolean[SPARES];
    private static int spareCount;

    /** The next point of the lattice nothing has used, where the next spare goes. */
    private static int nextLattice = COUNT;

    /** How many sites have moved on, for the log. */
    private static int swaps;

    static {

        for (int site = 0; site < COUNT; site++) {

            lattice[site] = site;
        }
    }

    /** How often, in game ticks, the whole world is swept for wildlife. */
    private static final int WILDLIFE_SWEEP_TICKS = 20;

    /** The game time the next sweep for wildlife is due at; the first fight to ask for a site does the first. */
    private static long nextWildlifeSweep;

    /** Sites whose chunks have been asked for, always the first so many; the rest wait their turn. */
    private static int requested;

    /** How many of the sites, from the first, an earlier worker already generated in the world this one was given. */
    private static int keptSites;

    /** Sites whose chunks are all loaded and ticking. Only these are handed out. */
    private static final boolean[] ready = new boolean[COUNT];
    private static int readyCount;

    /** When the lattice was chosen, so the log can say how long its sites took. */
    private static long startedAt;

    /** Sites with a fight on them right now. Fights end at different times, so the free ones are not simply the next. */
    private static final boolean[] inUse = new boolean[COUNT];

    /** Fights this process still has to run, shared by every slot; -1 until the first slot asks. */
    private static int fightsLeft = -1;

    /** Takes one fight from this process's share of the run, or says there are none left. */
    public static synchronized boolean takeFight() {

        if (fightsLeft < 0) {

            fightsLeft = GameTestTuning.arenasInShard(GameTestTuning.arenaCount());
        }

        if (fightsLeft == 0) {

            return false;
        }

        fightsLeft--;
        return true;
    }

    /**
     * Chooses where the lattice goes, before any fight asks for a site, and returns where the framework's own plots
     * should go, see {@link #plots}. The sites are only asked for once the plots are in place, by {@link #awaitFirstSite}:
     * the framework waits on every chunk the plots cover, and a site asked for first would be generated ahead of those
     * chunks and hold the plots up.
     *
     * <p>When the build copied in a world an earlier worker generated, the lattice goes where that one was, and its sites
     * are read from disk rather than generated.
     */
    public static synchronized BlockPos start(ServerLevel level) {

        if (origin == null) {

            library = openLibrary();

            if (library != null) {

                startLibrary();
                startedAt = System.nanoTime();

                Constants.LOG.info("Terrain arenas: {} sites from the terrain library, {} of its {} points usable, starting at "
                        + "point {}", COUNT, library.usableCount(), library.points(), lattice[0]);

                return plots(level);
            }

            BlockPos kept = keptOrigin();
            RandomSource random = GameTestTuning.terrainSeed() != 0L ? RandomSource.create(GameTestTuning.terrainSeed()) : level.getRandom();

            origin = kept != null ? kept : chooseOrigin(level, random, COUNT);
            startedAt = System.nanoTime();

            Constants.LOG.info("Terrain arenas: {} sites from {} in {}{}", COUNT, origin.toShortString(),
                    level.getBiome(origin).unwrapKey().map(key -> key.location().toString()).orElse("an unnamed biome"),
                    kept != null ? ", generated by an earlier worker" : "");
        }

        return plots(level);
    }

    /**
     * The library the build named, or null when there is none or it cannot be used: unreadable, laid out for another
     * spacing, or too small to walk through without a site ever landing where another still is.
     */
    @Nullable
    private static TerrainLibrary.Index openLibrary() {

        String path = GameTestTuning.library();

        if (path == null) {

            return null;
        }

        try {

            TerrainLibrary.Index index = TerrainLibrary.Index.read(Path.of(path));

            if (index.spacing() != SPACING || index.columns() != COLUMNS) {

                Constants.LOG.warn("The terrain library at {} was built {} blocks apart in {} columns, and sites here are {} apart in "
                        + "{}; generating instead. Build it again with scripts\\terrain.ps1", path, index.spacing(), index.columns(),
                        SPACING, COLUMNS);
                return null;
            }

            if (index.usableCount() < 4 * (COUNT + SPARES)) {

                Constants.LOG.warn("The terrain library at {} has only {} usable sites, too few for {} at a time; generating instead",
                        path, index.usableCount(), COUNT + SPARES);
                return null;
            }

            return index;
        }

        catch (IOException | RuntimeException exception) {

            Constants.LOG.warn("Could not read the terrain library at {}, generating instead: {}", path, exception.toString());
            return null;
        }
    }

    /**
     * Puts the sites on the first usable points from somewhere of this worker's own in the library: the same place for
     * the same terrain seed and worker, anywhere otherwise, and always at the start of a row, so the sites in use sit
     * side by side and share the ground around them.
     */
    private static void startLibrary() {

        RandomSource random = GameTestTuning.terrainSeed() != 0L
                ? RandomSource.create(GameTestTuning.terrainSeed() * 31L + GameTestTuning.shardIndex())
                : RandomSource.create();

        libraryCursor = random.nextInt(Math.max(1, library.points() / COLUMNS)) * COLUMNS;

        for (int site = 0; site < COUNT; site++) {

            lattice[site] = nextLibraryPoint();
        }

        // Every site and spare is on disk already, which the requests treat as quick, see tick.
        keptSites = COUNT;
        origin = library.centre(lattice[0]);
    }

    /** The next usable point of the library, walking on from the last and round past its end. */
    private static int nextLibraryPoint() {

        for (int step = 0; step < library.points(); step++) {

            int point = Math.floorMod(libraryCursor++, library.points());

            if (library.usable(point)) {

                return point;
            }
        }

        throw new IllegalStateException("The terrain library has no usable sites");
    }

    /**
     * Where the framework's plots go: deep under the spawn, far from any site. The server loads the seven by seven chunks
     * around the spawn before anything else, and fifty plots, as many as run at once, fill exactly those: eight to a row,
     * nine blocks wide with five between, each with the few blocks the framework clears around it. More spill over into
     * chunks that have to be loaded first. The plots force their chunks loaded one at a time, with the server waiting on
     * each, so anywhere else they waited for terrain to be generated: under the first site they held up the first fight,
     * and kept the ground between it and its neighbours ticking.
     */
    static BlockPos plots(ServerLevel level) {

        ChunkPos spawn = new ChunkPos(level.getSharedSpawnPos());

        return new BlockPos(spawn.getMinBlockX() - 3 * 16 + 2, level.getMinBuildHeight() + 5, spawn.getMinBlockZ() - 3 * 16 + 3);
    }

    /**
     * Writes down where the lattice was and how many of its sites were ready, once the suite is over, so the build can
     * keep this world for the workers after it. Nothing when the build did not ask.
     */
    public static synchronized void finish() {

        String path = GameTestTuning.terrainFile();

        // A world on the library is never kept: all of it is in the library already.
        if (path == null || origin == null || library != null) {

            return;
        }

        try {

            Files.writeString(Path.of(path),
                    String.format(Locale.ROOT, "x=%d%nz=%d%nsites=%d%n", origin.getX(), origin.getZ(), readyCount));
        }

        catch (IOException exception) {

            Constants.LOG.warn("Could not write {}: {}", path, exception.toString());
        }
    }

    /**
     * Whether nobody keeps this world once the worker exits: no pool asked for it, or it came from the pool with every
     * site already generated, so the kept copy has all of it already, or its sites came from the terrain library, whose
     * files it must never write. Saving such a world only writes files that are deleted before the next run.
     */
    public static synchronized boolean throwaway() {

        return GameTestTuning.terrainFile() == null || keptSites >= COUNT || GameTestTuning.library() != null;
    }

    /**
     * Keeps the lattice coming: marks the sites the generator has finished as ready for fights, and asks for the next in
     * their place. Called every server tick rather than only when a fight wants a site, so generating carries on while
     * every slot is busy.
     */
    public static synchronized void tick(ServerLevel level) {

        if (origin == null) {

            return;
        }

        if (readyCount == COUNT) {

            rotate(level);
            return;
        }

        for (int site = 0; site < requested; site++) {

            if (!ready[site] && loaded(level, centre(site))) {

                ready[site] = true;
                readyCount++;

                if (readyCount == 1 || readyCount == COUNT) {

                    Constants.LOG.info("Terrain arenas: {} ready after {} s", readyCount == 1 ? "first site" : "all " + COUNT + " sites",
                            String.format(Locale.ROOT, "%.1f", (System.nanoTime() - startedAt) / 1.0E9D));
                }
            }
        }

        while (requested < COUNT) {

            boolean onDisk = requested < keptSites;

            if (onDisk ? readyCount == 0 && requested >= GENERATING : requested - readyCount >= GENERATING) {

                break;
            }

            force(level, centre(requested++), true);
        }
    }

    /**
     * Asks for the first sites and waits until the first one's chunks are there. Called once, as the tests start. No fight
     * can start before a site is ready, and a server ticking on empty in the meantime leaves the chunk work only it can do
     * waiting between ticks: the first site of a new world took twice as long. Loading a chunk this way has the server do
     * that work until the chunk is there. A chunk only ticks entities once the two chunks around it are loaded as well,
     * so those are waited for too; what is left takes a tick or two.
     */
    public static synchronized void awaitFirstSite(ServerLevel level) {

        tick(level);

        BlockPos centre = centre(0);
        int reach = RADIUS_CHUNKS + 2;

        for (int dx = -reach; dx <= reach; dx++) {

            for (int dz = -reach; dz <= reach; dz++) {

                level.getChunk((centre.getX() >> 4) + dx, (centre.getZ() >> 4) + dz);
            }
        }
    }

    /**
     * The next free site, with somewhere to stand for the agent and for its opponent, or null when every usable site that
     * is ready has a fight on it; the caller tries again a tick later. Never waits for the world generator.
     */
    @Nullable
    public static synchronized Site claim(ServerLevel level) {

        if (origin == null) {

            start(level);
        }

        RandomSource random = level.getRandom();

        if (level.getGameTime() >= nextWildlifeSweep) {

            sweepWildlife(level);
            nextWildlifeSweep = level.getGameTime() + WILDLIFE_SWEEP_TICKS;
        }

        for (int attempt = 0; attempt < COUNT; attempt++) {

            int index = next++ % COUNT;

            if (!ready[index] || unusable[index] || inUse[index]) {

                continue;
            }

            BlockPos centre = centre(index);

            for (int tries = 0; tries < PLACEMENT_TRIES; tries++) {

                Site site = place(level, index, centre, random);

                if (site != null) {

                    inUse[index] = true;
                    return site;
                }
            }

            // Water or cliff all the way across. Out of use until a spare takes its place.
            unusable[index] = true;
        }

        return null;
    }

    /**
     * Takes the fighters away and sweeps up whatever the fight left lying around, so the site is clean for the next, and
     * counts the fight against the site.
     *
     * @param timedOut whether the fight ran out the clock, which a site is only allowed so often
     */
    public static synchronized void release(ServerLevel level, Site site, boolean timedOut, Entity... fighters) {

        for (Entity fighter : fighters) {

            if (fighter != null && !fighter.isRemoved()) {

                fighter.discard();
            }
        }

        sweep(level, site.bounds().inflate(8.0D));

        int index = site.index();
        inUse[index] = false;
        fights[index]++;

        if (timedOut && ++timeouts[index] >= SITE_TIMEOUTS) {

            unusable[index] = true;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Moving sites on
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Once the first sites are all up: keeps the spares coming, and moves a site that has had its fights, or that is out
     * of use, onto a spare that is ready. A site is only ever moved between fights.
     */
    private static void rotate(ServerLevel level) {

        for (int i = 0; i < spareCount; i++) {

            if (!spareReady[i] && loaded(level, point(spares[i]))) {

                spareReady[i] = true;
            }
        }

        // A couple at a time, the same as at the start, and only as many as are wanted.
        int loading = 0;

        for (int i = 0; i < spareCount; i++) {

            loading += spareReady[i] ? 0 : 1;
        }

        while (spareCount < SPARES && loading < GENERATING) {

            spares[spareCount] = library != null ? nextLibraryPoint() : nextLattice++;
            spareReady[spareCount] = false;
            force(level, point(spares[spareCount]), true);
            spareCount++;
            loading++;
        }

        for (int site = 0; site < COUNT; site++) {

            if (inUse[site] || !(unusable[site] || fights[site] >= SITE_FIGHTS)) {

                continue;
            }

            int spare = takeReadySpare();

            if (spare < 0) {

                return;
            }

            force(level, centre(site), false);

            lattice[site] = spare;
            fights[site] = 0;
            timeouts[site] = 0;
            unusable[site] = false;

            if (++swaps % 100 == 0) {

                Constants.LOG.info("Terrain arenas: {} sites moved on to fresh ground", swaps);
            }
        }
    }

    /** The point of the lattice of the first spare that is loaded, taken off the list, or -1 while none is. */
    private static int takeReadySpare() {

        for (int i = 0; i < spareCount; i++) {

            if (spareReady[i]) {

                int point = spares[i];

                spareCount--;
                spares[i] = spares[spareCount];
                spareReady[i] = spareReady[spareCount];

                return point;
            }
        }

        return -1;
    }

    // ---------------------------------------------------------------------------------------------------------------

    /** Where a site is now. */
    private static BlockPos centre(int index) {

        return point(lattice[index]);
    }

    /**
     * The centre of a point of the lattice, which runs on in rows of {@link #COLUMNS} as far as it is ever needed, or of
     * the library's.
     */
    private static BlockPos point(int latticeIndex) {

        return library != null ? library.centre(latticeIndex)
                : origin.offset((latticeIndex % COLUMNS) * SPACING, 0, (latticeIndex / COLUMNS) * SPACING);
    }

    /** The whole loaded area of a site, from bedrock to the sky. */
    private static AABB siteBox(ServerLevel level, BlockPos centre) {

        int minX = ((centre.getX() >> 4) - RADIUS_CHUNKS) << 4;
        int minZ = ((centre.getZ() >> 4) - RADIUS_CHUNKS) << 4;

        return new AABB(minX, level.getMinBuildHeight(), minZ, minX + SIZE, level.getMaxBuildHeight(), minZ + SIZE);
    }

    /**
     * Asks for a site's chunks to be kept loaded and ticking, or lets them go. The level's own setChunkForced would
     * generate each one on the spot, one after another, with the server standing still; the ticket underneath it only
     * asks, and the generator works through them on every thread it has. Let go, a site's chunks unload, which is what
     * keeps a worker that moves its sites on hundreds of times in the same memory.
     */
    static void force(ServerLevel level, BlockPos centre, boolean keep) {

        int chunkX = centre.getX() >> 4;
        int chunkZ = centre.getZ() >> 4;

        for (int dx = -RADIUS_CHUNKS; dx <= RADIUS_CHUNKS; dx++) {

            for (int dz = -RADIUS_CHUNKS; dz <= RADIUS_CHUNKS; dz++) {

                level.getChunkSource().updateChunkForced(new ChunkPos(chunkX + dx, chunkZ + dz), keep);
            }
        }
    }

    /** Whether every chunk of a site is loaded, with its entities, and ticking, which is when a fight can go on it. */
    static boolean loaded(ServerLevel level, BlockPos centre) {

        int chunkX = centre.getX() >> 4;
        int chunkZ = centre.getZ() >> 4;

        for (int dx = -RADIUS_CHUNKS; dx <= RADIUS_CHUNKS; dx++) {

            for (int dz = -RADIUS_CHUNKS; dz <= RADIUS_CHUNKS; dz++) {

                ChunkPos chunk = new ChunkPos(chunkX + dx, chunkZ + dz);

                if (!level.isPositionEntityTicking(chunk.getWorldPosition()) || !level.areEntitiesLoaded(chunk.toLong())) {

                    return false;
                }
            }
        }

        return true;
    }

    /**
     * Everything a finished fight left on its site: what it dropped and what is still in the air, and anything living the
     * fight brought that was not one of the fighters handed back. An evoker's vexes are the case that needs the last one:
     * they are part of the fight, so the sweep for wildlife spares them, and nothing else would ever take them away. The
     * sweep for wildlife cannot do it either, since a vex still fighting has to survive that.
     */
    private static void sweep(ServerLevel level, AABB box) {

        List<Entity> leftovers = level.getEntitiesOfClass(Entity.class, box, entity ->
                entity instanceof ItemEntity || entity instanceof ExperienceOrb || entity instanceof Projectile
                        || (entity instanceof LivingEntity && entity.getTags().contains(TAG)));

        leftovers.forEach(Entity::discard);
    }

    /**
     * Removes every living thing no fight brought, anywhere in the world. Whatever the world generator put down, cows and
     * all, would otherwise wander through the fights, and each one near a fight costs as much to tick as a fighter.
     *
     * <p>Sweeping each site once, when first claimed, missed the plots the framework keeps under the spawn, which tick
     * whatever lives above them, and, before sites waited for their entities to load, most of the sites as well: the
     * cows, sheep and chickens that got through outnumbered the fighters and cost a tenth of the server thread. So the
     * whole world is swept, a second of game time apart; nothing spawns in a game test world, so each sweep only ever
     * finds what the last one could not yet see.
     */
    private static void sweepWildlife(ServerLevel level) {

        List<Entity> wildlife = new ArrayList<>();

        for (Entity entity : level.getAllEntities()) {

            if (entity instanceof LivingEntity && !(entity instanceof Player) && !entity.getTags().contains(TAG)) {

                wildlife.add(entity);
            }
        }

        wildlife.forEach(Entity::discard);
    }

    @Nullable
    static Site place(ServerLevel level, int index, BlockPos centre, RandomSource random) {

        int x = centre.getX() + Mth.nextInt(random, -JITTER, JITTER);
        int z = centre.getZ() + Mth.nextInt(random, -JITTER, JITTER);

        BlockPos agent = nearestStanding(level, x, z, SEARCH);

        if (agent == null) {

            return null;
        }

        BlockPos opponent = null;

        // A few directions at a random start, so the opponent is not always on the same side.
        float heading = random.nextFloat() * Mth.TWO_PI;

        for (int turn = 0; turn < 8 && opponent == null; turn++) {

            float angle = heading + turn * (Mth.TWO_PI / 8.0F);
            int distance = Mth.nextInt(random, OPPONENT_MIN_DISTANCE, OPPONENT_MAX_DISTANCE);

            BlockPos candidate = nearestStanding(level,
                    agent.getX() + Math.round(Mth.cos(angle) * distance),
                    agent.getZ() + Math.round(Mth.sin(angle) * distance), 3);

            if (candidate != null && Math.abs(candidate.getY() - agent.getY()) <= OPPONENT_MAX_CLIMB
                    && candidate.distSqr(agent) >= OPPONENT_MIN_DISTANCE * OPPONENT_MIN_DISTANCE / 2) {

                opponent = candidate;
            }
        }

        if (opponent == null) {

            return null;
        }

        AABB box = siteBox(level, centre);
        int low = Math.min(agent.getY(), opponent.getY());
        int high = Math.max(agent.getY(), opponent.getY());

        return new Site(index, agent, opponent, new AABB(box.minX, low - 16, box.minZ, box.maxX, high + 24, box.maxZ));
    }

    /** The closest place within the radius where something can stand on dry, solid ground with its head clear. */
    @Nullable
    private static BlockPos nearestStanding(ServerLevel level, int x, int z, int radius) {

        for (int ring = 0; ring <= radius; ring++) {

            for (int dx = -ring; dx <= ring; dx++) {

                for (int dz = -ring; dz <= ring; dz++) {

                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {

                        continue;
                    }

                    BlockPos feet = standing(level, x + dx, z + dz);

                    if (feet != null) {

                        return feet;
                    }
                }
            }
        }

        return null;
    }

    @Nullable
    private static BlockPos standing(ServerLevel level, int x, int z) {

        // The first block above anything that stops movement, not counting leaves, so a canopy is not mistaken for ground.
        BlockPos feet = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
        BlockPos ground = feet.below();

        // And nothing overhead, leaves included: fights start out in the open, as two fighters who have just seen each
        // other would. Started under a canopy, or in among mangrove roots, a fighter could be boxed in before it had
        // taken a step; over 2,000 fights those starts lost 9.8% and the ones in the open 0.8%. Fights still go wherever
        // the fighters take them, trees and all.
        if (feet.getY() < level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z)) {

            return null;
        }

        boolean solid = level.getBlockState(ground).isFaceSturdy(level, ground, Direction.UP)
                && level.getFluidState(ground).isEmpty();

        boolean clear = level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                && level.getFluidState(feet).isEmpty()
                && level.getFluidState(feet.above()).isEmpty();

        return solid && clear ? feet : null;
    }

    /**
     * Takes up the world the build copied in, if it did: the lattice goes where the earlier worker put it, and the sites it
     * generated are known to be on disk. Returns where the lattice goes, or null when there is no such world.
     */
    @Nullable
    private static BlockPos keptOrigin() {

        String given = GameTestTuning.keptTerrain();

        if (given == null) {

            return null;
        }

        try {

            String[] parts = given.split(",");

            keptSites = Mth.clamp(Integer.parseInt(parts[2].trim()), 0, COUNT);
            return new BlockPos(Integer.parseInt(parts[0].trim()), 64, Integer.parseInt(parts[1].trim()));
        }

        catch (RuntimeException exception) {

            Constants.LOG.warn("Ignoring kept terrain that is not x,z,sites: {}", given);
            keptSites = 0;
            return null;
        }
    }

    /**
     * Somewhere the lattice lands mostly on land. Asked of the biome source directly, which answers without generating a
     * single chunk, so trying hundreds of places costs next to nothing.
     */
    static BlockPos chooseOrigin(ServerLevel level, RandomSource random, int points) {

        BiomeSource biomes = level.getChunkSource().getGenerator().getBiomeSource();
        Climate.Sampler climate = level.getChunkSource().randomState().sampler();

        BlockPos best = BlockPos.ZERO;
        int bestLand = -1;
        int samples = 16;

        for (int attempt = 0; attempt < ORIGIN_ATTEMPTS; attempt++) {

            // Chunk aligned plus eight, so every site centre sits in the middle of its chunk.
            int x = (Mth.nextInt(random, -ORIGIN_RANGE, ORIGIN_RANGE) & ~15) + 8;
            int z = (Mth.nextInt(random, -ORIGIN_RANGE, ORIGIN_RANGE) & ~15) + 8;

            int land = 0;

            for (int sample = 0; sample < samples; sample++) {

                // Spread over the whole lattice, not just its corner.
                int site = sample * points / samples + COLUMNS / 2;
                int sx = x + (site % COLUMNS) * SPACING;
                int sz = z + (site / COLUMNS) * SPACING;

                Holder<Biome> biome = biomes.getNoiseBiome(QuartPos.fromBlock(sx), QuartPos.fromBlock(64), QuartPos.fromBlock(sz), climate);

                if (!biome.is(BiomeTags.IS_OCEAN) && !biome.is(BiomeTags.IS_DEEP_OCEAN)
                        && !biome.is(BiomeTags.IS_RIVER) && !biome.is(BiomeTags.IS_BEACH)) {

                    land++;
                }
            }

            if (land > bestLand) {

                bestLand = land;
                best = new BlockPos(x, 64, z);
            }

            if (land == samples) {

                break;
            }
        }

        return best;
    }
}
