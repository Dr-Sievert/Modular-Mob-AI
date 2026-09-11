package net.sievert.modularmobai.gametest.replay;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import it.unimi.dsi.fastutil.booleans.BooleanArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

/**
 * The blocks of a fight's site, the way a replay keeps them: every block of the area the fighters may perceive, from a few
 * blocks under its lowest ground up to the highest block standing on it, as a palette of block states and runs of indices
 * into that palette. docs/replay-format.md describes what is written.
 *
 * <p>A state keeps only the properties that change how the block looks: which way a log lies, which half of a slab or a
 * tall plant it is, how deep the snow, whether it stands in water. Leaves also remember how far they are from a log and a
 * sapling how close it is to growing, none of which shows, so such states share an entry and the palette stays small.
 */
final class SiteBlocks {

    /** The properties kept in the palette, the ones that change how a block looks or its shape. */
    private static final Set<String> VISIBLE = Set.of("age", "attachment", "axis", "berries", "bites", "bottom", "candles",
            "down", "east", "eggs", "extended", "face", "facing", "flower_amount", "half", "hanging", "hinge", "layers",
            "leaves", "level", "lit", "moisture", "north", "open", "part", "pickles", "rotation", "shape", "snowy", "south",
            "thickness", "tilt", "type", "up", "vertical_direction", "waterlogged", "west");

    /** The bits of an entry's flags: a full opaque cube, which hides what is next to it; it stops movement; it holds a fluid. */
    private static final int OPAQUE = 1;
    private static final int SOLID = 2;
    private static final int FLUID = 4;

    /** How far below the lowest ground of the site the blocks go, so the site stands on a slab of what lies under it. */
    private static final int BASE = 5;

    /** How far above what the fighters perceive a tree or a cliff is still kept whole, rather than cut off. */
    private static final int OVERHANG = 32;

    private SiteBlocks() {
    }

    /**
     * Reads the blocks of an area and writes them out as the replay's "blocks" object.
     *
     * @param area    what the fighters may perceive; its sides are the sides of what is written, its bottom and top the
     *                furthest it may reach down and up
     * @param ceiling the height of a roof over the fight; nothing from there up is written
     */
    static String write(ServerLevel level, AABB area, int ceiling) {

        int west = Mth.floor(area.minX);
        int north = Mth.floor(area.minZ);
        int width = Mth.ceil(area.maxX) - west;
        int depth = Mth.ceil(area.maxZ) - north;

        // Up to the highest block standing on the site, which may be a tree or a cliff somewhat above what the fighters
        // perceive, but never into the roof of a closed arena, which seen from outside would hide the whole fight.
        int surface = Integer.MIN_VALUE;

        for (int dz = 0; dz < depth; dz++) {

            for (int dx = 0; dx < width; dx++) {

                surface = Math.max(surface, level.getHeight(Heightmap.Types.WORLD_SURFACE, west + dx, north + dz));
            }
        }

        int floor = Math.max(level.getMinBuildHeight(), Mth.floor(area.minY));
        int roof = Math.min(Math.min(ceiling, level.getMaxBuildHeight()),
                Math.max(Mth.ceil(area.maxY), Math.min(surface, Mth.ceil(area.maxY) + OVERHANG)));
        int height = Math.max(1, roof - floor);

        Palette palette = new Palette(level);
        int[] cells = new int[width * height * depth];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int chunkZ = north >> 4; chunkZ <= (north + depth - 1) >> 4; chunkZ++) {

            for (int chunkX = west >> 4; chunkX <= (west + width - 1) >> 4; chunkX++) {

                // Only chunks that are already there: recording never makes the server load or generate one. A site's own
                // chunks always are; one that is not stays air.
                LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);

                if (chunk == null) {

                    continue;
                }

                int x0 = Math.max(west, chunkX << 4);
                int x1 = Math.min(west + width, (chunkX << 4) + 16);
                int z0 = Math.max(north, chunkZ << 4);
                int z1 = Math.min(north + depth, (chunkZ << 4) + 16);

                for (int y = floor; y < roof; y++) {

                    LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));

                    if (section.hasOnlyAir()) {

                        continue;
                    }

                    for (int z = z0; z < z1; z++) {

                        for (int x = x0; x < x1; x++) {

                            BlockState state = section.getBlockState(x & 15, y & 15, z & 15);

                            if (!state.isAir()) {

                                cells[((y - floor) * depth + (z - north)) * width + (x - west)] = palette.index(state, pos.set(x, y, z));
                            }
                        }
                    }
                }
            }
        }

        // Most of what lies under the ground is never seen, so the blocks stop a few layers under its lowest point: the
        // bed of a pond, the bottom of a pit. Above, they stop at the highest block there is.
        int columns = width * depth;
        int lowest = height;
        int highest = 0;

        for (int column = 0; column < columns; column++) {

            for (int layer = height - 1; layer >= 0; layer--) {

                int index = cells[layer * columns + column];

                if (index != 0) {

                    highest = Math.max(highest, layer);
                }

                if (palette.ground.getBoolean(index)) {

                    lowest = Math.min(lowest, layer);
                    break;
                }
            }
        }

        int from = Math.max(0, Math.min(lowest, highest) - BASE);
        int to = Math.max(from + 1, highest + 1);

        StringBuilder out = new StringBuilder(64 * 1024);

        out.append("{\"x\":").append(west).append(",\"y\":").append(floor + from).append(",\"z\":").append(north)
                .append(",\"width\":").append(width).append(",\"height\":").append(to - from).append(",\"depth\":").append(depth);

        out.append(",\n\"palette\":[");

        for (int index = 0; index < palette.names.size(); index++) {

            (index == 0 ? out : out.append(',')).append('"').append(palette.names.get(index)).append('"');
        }

        out.append("],\n\"color\":[");
        list(out, palette.colours);
        out.append("],\n\"flags\":[");
        list(out, palette.flags);
        out.append("],\n\"runs\":[");

        // Layer by layer from the bottom, each row from west to east and the rows from north to south, so the runs are
        // long where the ground is plain: a layer of stone, a layer of air.
        int start = from * columns;
        int end = to * columns;
        int last = cells[start];
        int length = 0;
        boolean first = true;

        for (int cell = start; cell < end; cell++) {

            if (cells[cell] != last) {

                (first ? out : out.append(',')).append(last).append(',').append(length);
                first = false;
                last = cells[cell];
                length = 0;
            }

            length++;
        }

        (first ? out : out.append(',')).append(last).append(',').append(length);

        return out.append("]}").toString();
    }

    private static void list(StringBuilder out, IntArrayList values) {

        for (int index = 0; index < values.size(); index++) {

            (index == 0 ? out : out.append(',')).append(values.getInt(index));
        }
    }

    /** A block state as the palette names it: its id, and the properties that show, in the game's own order. */
    private static String name(BlockState state) {

        StringBuilder name = new StringBuilder(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        boolean first = true;

        for (Property<?> property : state.getProperties()) {

            if (VISIBLE.contains(property.getName())) {

                name.append(first ? '[' : ',').append(property.getName()).append('=').append(value(state, property));
                first = false;
            }
        }

        return first ? name.toString() : name.append(']').toString();
    }

    private static <T extends Comparable<T>> String value(BlockState state, Property<T> property) {

        return property.getName(state.getValue(property));
    }

    /**
     * The block states met so far, each with its palette index. Air of every kind is index 0, which is what a cell holds
     * until a block is found in it.
     */
    private static final class Palette {

        private final ServerLevel level;

        private final Reference2IntOpenHashMap<BlockState> byState = new Reference2IntOpenHashMap<>();
        private final Object2IntOpenHashMap<String> byName = new Object2IntOpenHashMap<>();

        private final List<String> names = new ArrayList<>();
        private final IntArrayList colours = new IntArrayList();
        private final IntArrayList flags = new IntArrayList();

        /** Whether an entry is ground: something that stops movement and is not leaves, which a canopy is made of. */
        private final BooleanArrayList ground = new BooleanArrayList();

        private Palette(ServerLevel level) {

            this.level = level;
            this.byState.defaultReturnValue(-1);
            this.byName.defaultReturnValue(-1);

            this.names.add("minecraft:air");
            this.colours.add(0);
            this.flags.add(0);
            this.ground.add(false);
        }

        private int index(BlockState state, BlockPos pos) {

            int index = this.byState.getInt(state);

            if (index >= 0) {

                return index;
            }

            String name = name(state);
            index = this.byName.getInt(name);

            if (index < 0) {

                // The colour and the flags of the first block seen of this kind stand for all of them; they depend on the
                // state, not on where it is.
                index = this.names.size();

                this.byName.put(name, index);
                this.names.add(name);
                this.colours.add(state.getMapColor(this.level, pos).col);
                this.flags.add((state.isSolidRender(this.level, pos) ? OPAQUE : 0) | (state.blocksMotion() ? SOLID : 0)
                        | (state.getFluidState().isEmpty() ? 0 : FLUID));
                this.ground.add(state.blocksMotion() && !state.is(BlockTags.LEAVES));
            }

            this.byState.put(state, index);
            return index;
        }
    }
}
