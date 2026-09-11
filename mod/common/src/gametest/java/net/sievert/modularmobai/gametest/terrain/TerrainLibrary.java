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
import java.util.List;
import java.util.Locale;
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
 *   runs/terrain/1.21.1/library/library.properties  x, z, spacing, columns, points, unusable
 * </pre>
 *
 * <p>Workers link the region files rather than copy them, so they share one copy on disk, and nothing a worker does ever
 * writes to them: nothing is saved while fights run, and a worker on the library throws its world away at the end
 * rather than saving it, see {@link net.sievert.modularmobai.gametest.mixin.RegionFileStorageMixin} for the last guard.
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
     * What a worker reads: where each block of the lattice is, how the lattice is laid out, and which points no fight can
     * start on. Points are numbered block by block, row by row within a block, the way a worker walks them.
     */
    public record Index(List<BlockPos> blocks, int spacing, int columns, int rows, BitSet unusable) {

        public int points() {

            return this.blocks.size() * this.columns * this.rows;
        }

        public boolean usable(int point) {

            return !this.unusable.get(Math.floorMod(point, this.points()));
        }

        public int usableCount() {

            return this.points() - this.unusable.cardinality();
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

            BitSet unusable = new BitSet();
            String list = properties.getProperty("unusable", "").trim();

            if (!list.isEmpty()) {

                for (String part : list.split(",")) {

                    unusable.set(Integer.parseInt(part.trim()));
                }
            }

            return new Index(List.copyOf(blocks), Integer.parseInt(properties.getProperty("spacing").trim()),
                    Integer.parseInt(properties.getProperty("columns").trim()), Integer.parseInt(properties.getProperty("rows").trim()),
                    unusable);
        }

        void write(Path file) throws IOException {

            StringBuilder text = new StringBuilder();

            text.append(String.format(Locale.ROOT, "spacing=%d%ncolumns=%d%nrows=%d%n", this.spacing, this.columns, this.rows));

            for (int block = 0; block < this.blocks.size(); block++) {

                text.append(String.format(Locale.ROOT, "block.%d=%d,%d%n", block, this.blocks.get(block).getX(), this.blocks.get(block).getZ()));
            }

            List<String> unusableList = new ArrayList<>();

            for (int point = this.unusable.nextSetBit(0); point >= 0; point = this.unusable.nextSetBit(point + 1)) {

                unusableList.add(Integer.toString(point));
            }

            text.append("unusable=").append(String.join(",", unusableList)).append(System.lineSeparator());

            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");

            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {

                writer.write(text.toString());
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

            List<BlockPos> origins = new ArrayList<>();

            for (int block = 0; block < blocks; block++) {

                origins.add(TerrainSites.chooseOrigin(level, random, perBlock));
            }

            building = new Index(List.copyOf(origins), TerrainSites.SPACING, TerrainSites.COLUMNS, ROWS, unusable);
            total = building.points();
            startedAt = System.nanoTime();

            Constants.LOG.info("Terrain library: generating {} sites in {} blocks of {}", total, blocks, perBlock);
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
}
