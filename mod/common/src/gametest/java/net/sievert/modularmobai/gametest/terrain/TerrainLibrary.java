package net.sievert.modularmobai.gametest.terrain;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import org.jetbrains.annotations.Nullable;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.sievert.modularmobai.Constants;
import net.sievert.modularmobai.gametest.GameTestTuning;

/**
 * Fight sites generated once and kept, so the workers that fight on them never generate any ground.
 *
 * <p>Generating terrain was most of what a worker did besides fighting: every time a site moved on to fresh ground, the
 * world generator took the site, the ground around it and a wide ring of part generated chunks through every stage, on
 * two to three cores, and the part generated ring was most of the worker's heap. But vanilla already knows how to skip
 * all of that. A chunk that is on disk, finished, with the chunks right next to it on disk at least lit, is loaded
 * rather than generated, and loading it needs nothing further out than those neighbours: no ring, no generator. So the
 * library is a lattice of sites generated once, each with that neighbourhood, and kept on disk; the build links its
 * region files into every worker's world, and a worker's sites are loaded from them, in a fraction of a second, into a
 * fraction of the memory.
 *
 * <p>The library is built by the {@code library} suite, a worker that fights nothing: it lays out the same lattice the
 * terrain suite would, has the sites generated a few at a time, checks each one for somewhere to start a fight, lets it
 * go, and writes its chunks as they unload. {@code scripts\terrain.ps1} runs it. The index it writes says where the
 * lattice is, how many points it has, and which of them are water or cliff that no fight can start on, so workers never
 * have to find that out for themselves.
 *
 * <pre>
 *   runs/terrain/1.21.1/library/region/r.X.Z.mca    the chunks, never written by a worker
 *   runs/terrain/1.21.1/library/library.properties  spacing, columns, rows, block.N, unusable, kinds
 * </pre>
 *
 * <p>Workers link the region files rather than copy them, so they share one copy on disk, and nothing a worker does ever
 * writes to them: nothing is saved while fights run, and a worker on the library throws its world away at the end
 * rather than saving it, see {@link net.sievert.modularmobai.gametest.mixin.RegionFileStorageMixin} for the last guard.
 *
 * <p>A library <b>grows</b> rather than being rebuilt. {@code scripts\terrain.ps1 -Add 2048} generates that many sites in
 * blocks of their own, well clear of every block already in the library, and appends them: the new blocks' region files are
 * moved into the library whole, and only then is a new index moved over the old one. Points are numbered block by block, so
 * appending blocks at the end leaves every point that already existed with the number it had. Nothing half finished is ever
 * readable: until the index is swapped, the new region files are simply files no point refers to, and a worker already
 * reading the library holds its own links to the files it was given and never looks at the index again.
 */
public final class TerrainLibrary {

    private TerrainLibrary() {}

    /**
     * Sites being generated at once while building. Vanilla takes a chunk through most of its stages one task at a time,
     * whatever it has been asked for, so more in flight only means each takes longer and more of them sit half made in
     * memory; a few keep it busy. The lattice is walked row by row, so a site shares most of its ring with the one
     * generated before it.
     */
    private static final int GENERATING = 4;

    /** Tries at placing a fight on a site before it is marked unusable: more than a worker makes, so few good ones are lost. */
    private static final int PLACEMENT_TRIES = 8;

    /**
     * Sites in a block of the library: rows of the lattice's columns, all on one patch of ground chosen for being land.
     * One long lattice would cross an ocean every few kilometres; blocks of this size each go somewhere of their own,
     * the way a worker's own lattice always did, and a worker walking the library in order still has its sites side by
     * side, sharing the ground around them, for all but one site in a block's worth.
     */
    static final int ROWS = 16;

    /**
     * How far apart two blocks' origins have to be, in blocks of the world, for their region files never to touch: the
     * length of a block along its longer side, plus two kilometres of slack for the ring of part generated ground the
     * generator reaches out to and for the region files either sits in. A region file is 512 blocks, so the slack is four
     * of them. It comes to 4,096 for the usual site radius of two and grows with the radius, as it has to: a site three
     * chunks either side of its centre puts its blocks further apart. Nothing here is worth shaving, since the world sites
     * are placed in is four million blocks wide and land is not scarce.
     */
    static final int BLOCKS_APART = ROWS * TerrainSites.SPACING + 2048;

    /**
     * What a worker reads: where each block of the lattice is, how the lattice is laid out, which points no fight can start
     * on, and whatever else has been found out about a point. Points are numbered block by block, row by row within a block,
     * the way a worker walks them, so appending blocks never renumbers a point that already existed.
     *
     * @param kinds anything known about a point beyond whether a fight can start on it, as a named set of points each:
     *              {@code kinds=lava,ravine} and then {@code kind.lava=3,17,42}, read and written and merged on append
     *              exactly as {@code unusable} is. Nothing fills these in yet; the league wants to draw hazardous ground
     *              deliberately rather than by luck, and this is where the sites it should draw from will be named. A
     *              reader that does not know a kind ignores it, and a library written before a kind existed simply has none
     *              of it, so adding one costs no rebuild.
     */
    public record Index(List<BlockPos> blocks, int spacing, int columns, int rows, BitSet unusable,
                        Map<String, BitSet> kinds) {

        public Index(List<BlockPos> blocks, int spacing, int columns, int rows, BitSet unusable) {

            this(blocks, spacing, columns, rows, unusable, Map.of());
        }

        public int points() {

            return this.blocks.size() * this.columns * this.rows;
        }

        public boolean usable(int point) {

            return !this.unusable.get(Math.floorMod(point, this.points()));
        }

        public int usableCount() {

            return this.points() - this.unusable.cardinality();
        }

        /** Whether a point is known to be of a kind; false for every point of a kind nothing has named. */
        public boolean of(String kind, int point) {

            BitSet named = this.kinds.get(kind);

            return named != null && named.get(Math.floorMod(point, this.points()));
        }

        /** The centre of a point, which wraps around the end of the library. */
        public BlockPos centre(int point) {

            int wrapped = Math.floorMod(point, this.points());
            int perBlock = this.columns * this.rows;
            int local = wrapped % perBlock;

            return this.blocks.get(wrapped / perBlock).offset((local % this.columns) * this.spacing, 0, (local / this.columns) * this.spacing);
        }

        public static Index read(Path file) throws IOException {

            Properties properties = new Properties();

            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {

                properties.load(reader);
            }

            List<BlockPos> blocks = new ArrayList<>();

            for (int block = 0; properties.containsKey("block." + block); block++) {

                String[] parts = properties.getProperty("block." + block).split(",");
                blocks.add(new BlockPos(Integer.parseInt(parts[0].trim()), 64, Integer.parseInt(parts[1].trim())));
            }

            Map<String, BitSet> kinds = new LinkedHashMap<>();

            for (String kind : properties.getProperty("kinds", "").trim().split(",")) {

                if (!kind.trim().isEmpty()) {

                    kinds.put(kind.trim(), points(properties.getProperty("kind." + kind.trim(), "")));
                }
            }

            return new Index(List.copyOf(blocks), Integer.parseInt(properties.getProperty("spacing").trim()),
                    Integer.parseInt(properties.getProperty("columns").trim()), Integer.parseInt(properties.getProperty("rows").trim()),
                    points(properties.getProperty("unusable", "")), Map.copyOf(kinds));
        }

        /** A comma separated list of point numbers, as written by {@link #list}. */
        private static BitSet points(String list) {

            BitSet points = new BitSet();

            for (String part : list.trim().split(",")) {

                if (!part.trim().isEmpty()) {

                    points.set(Integer.parseInt(part.trim()));
                }
            }

            return points;
        }

        /** Every point in a set, comma separated, which is how both unusable and the kinds are written. */
        private static String list(BitSet points) {

            List<String> numbers = new ArrayList<>();

            for (int point = points.nextSetBit(0); point >= 0; point = points.nextSetBit(point + 1)) {

                numbers.add(Integer.toString(point));
            }

            return String.join(",", numbers);
        }

        /** The index as the file holds it, which the build writes too when it puts several builders' work together. */
        public String text() {

            StringBuilder text = new StringBuilder();

            text.append(String.format(Locale.ROOT, "spacing=%d%ncolumns=%d%nrows=%d%n", this.spacing, this.columns, this.rows));

            for (int block = 0; block < this.blocks.size(); block++) {

                text.append(String.format(Locale.ROOT, "block.%d=%d,%d%n", block, this.blocks.get(block).getX(), this.blocks.get(block).getZ()));
            }

            text.append("unusable=").append(list(this.unusable)).append(System.lineSeparator());
            text.append("kinds=").append(String.join(",", this.kinds.keySet())).append(System.lineSeparator());

            this.kinds.forEach((kind, points) ->
                    text.append("kind.").append(kind).append('=').append(list(points)).append(System.lineSeparator()));

            return text.toString();
        }

        void write(Path file) throws IOException {

            // Written beside the real one and moved over it, so that nobody ever reads half an index: a worker starting
            // while a library grows gets either the whole of the old one or the whole of the new one.
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");

            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {

                writer.write(this.text());
            }

            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Building
    // ---------------------------------------------------------------------------------------------------------------

    /** The library being built: its blocks are chosen at the start, its unusable points found as it goes. */
    @Nullable
    private static Index building;

    private static int total;
    private static int nextPoint;
    private static int built;
    private static final IntArrayList inFlight = new IntArrayList();
    private static final BitSet unusable = new BitSet();
    private static long startedAt;
    private static boolean finished;

    /**
     * Lays out the library, each block on a patch of land chosen the way the terrain suite chooses where its own lattice
     * goes, and returns where the framework's plots go.
     */
    public static synchronized BlockPos start(ServerLevel level) {

        if (building == null) {

            // Several builders can share the work, each generating its own blocks into its own world, which the build
            // puts together afterwards: vanilla generates most of a chunk one task at a time, so a builder gets through
            // about half a site a second however many it asks for, and more builders are the only way to go faster.
            int perBlock = TerrainSites.COLUMNS * ROWS;
            int allBlocks = Math.max(1, (GameTestTuning.librarySites() + perBlock - 1) / perBlock);
            int builders = GameTestTuning.shardCount();
            int blocks = Math.max(1, allBlocks / builders + (allBlocks % builders > GameTestTuning.shardIndex() ? 1 : 0));

            RandomSource random = GameTestTuning.terrainSeed() != 0L
                    ? RandomSource.create(GameTestTuning.terrainSeed() * 31L + GameTestTuning.shardIndex())
                    : RandomSource.create();

            // Ground that is already in the library, when this build is adding to one rather than making a new one: no new
            // block may land near any of it, because two blocks close together would share region files and the second one
            // written would be the first one's chunks. Each origin this builder picks joins the list, so its own blocks
            // keep clear of each other too. Builders cannot see each other's choices, so the build checks every pair
            // afterwards and refuses to commit an addition where any two are too close, which is the last guard.
            List<BlockPos> taken = new ArrayList<>(taken());

            List<BlockPos> origins = new ArrayList<>();

            for (int block = 0; block < blocks; block++) {

                BlockPos origin = TerrainSites.chooseOrigin(level, random, perBlock, taken);
                origins.add(origin);
                taken.add(origin);
            }

            building = new Index(List.copyOf(origins), TerrainSites.SPACING, TerrainSites.COLUMNS, ROWS, unusable);
            total = building.points();
            startedAt = System.nanoTime();

            Constants.LOG.info("Terrain library: generating {} sites in {} blocks of {}, keeping clear of {} blocks already "
                    + "in the library", total, blocks, perBlock, taken.size() - blocks);
        }

        return TerrainSites.plots(level);
    }

    /**
     * Every server tick: checks each site in flight for somewhere to start a fight once it is all there, lets it go so its
     * chunks are written and unloaded, and asks for the next.
     */
    public static synchronized void tick(ServerLevel level) {

        if (building == null || finished) {

            return;
        }

        RandomSource random = level.getRandom();

        for (int index = inFlight.size() - 1; index >= 0; index--) {

            int point = inFlight.getInt(index);
            BlockPos centre = point(point);

            if (!TerrainSites.loaded(level, centre)) {

                continue;
            }

            boolean placed = false;

            for (int tries = 0; tries < PLACEMENT_TRIES && !placed; tries++) {

                placed = TerrainSites.place(level, point, centre, random) != null;
            }

            if (!placed) {

                unusable.set(point);
            }

            TerrainSites.force(level, centre, false);
            inFlight.removeInt(index);
            built++;

            if (built % 128 == 0 || built == total) {

                double seconds = (System.nanoTime() - startedAt) / 1.0E9D;

                Constants.LOG.info("Terrain library: {} of {} sites after {} s, {} a second, {} unusable so far", built, total,
                        String.format(Locale.ROOT, "%.0f", seconds), String.format(Locale.ROOT, "%.1f", built / seconds),
                        unusable.cardinality());
            }
        }

        while (inFlight.size() < GENERATING && nextPoint < total) {

            TerrainSites.force(level, point(nextPoint), true);
            inFlight.add(nextPoint++);
        }

        finished = built == total;
    }

    /** Whether every site has been generated, checked and let go. */
    public static synchronized boolean done() {

        return finished;
    }

    /** Writes the index, once the suite is over; the chunks themselves are written as the server stops. */
    public static synchronized void finish() {

        String path = GameTestTuning.library();

        if (path == null || building == null || !finished) {

            return;
        }

        try {

            building.write(Path.of(path));

            Constants.LOG.info("Terrain library: wrote {} sites, {} of them usable, to {}", total, building.usableCount(), path);
        }

        catch (IOException exception) {

            Constants.LOG.warn("Could not write the terrain library's index to {}: {}", path, exception.toString());
        }
    }

    private static BlockPos point(int point) {

        return building.centre(point);
    }

    /**
     * Where the blocks of the library this build is adding to already are, or nothing when it is making a new library. The
     * build names the existing index; it is read here rather than passed as a list of coordinates so that a library of any
     * size costs the same one path on a command line.
     */
    private static List<BlockPos> taken() {

        String path = GameTestTuning.libraryAdding();

        if (path == null) {

            return List.of();
        }

        try {

            List<BlockPos> blocks = Index.read(Path.of(path)).blocks();

            Constants.LOG.info("Terrain library: adding to the {} blocks already in {}", blocks.size(), path);

            return blocks;
        }

        catch (IOException | RuntimeException exception) {

            // Refusing outright: generating blocks that might land on top of the library's would quietly replace ground
            // that thousands of fights have been drawn from.
            throw new IllegalStateException("Could not read the terrain library being added to at " + path, exception);
        }
    }
}
