package net.sievert.modularmobai.gametest.terrain;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * What a fight site offers a fighter who can push: lava to knock something into, an edge to knock it off, water, or
 * nothing but ground.
 *
 * <p>The point of knowing is that the terrain is a weapon. A hundred health of iron golem takes a long time to cut down
 * and no time at all to knock into a lava lake, and a fight the ground finishes already counts as the agent's win, so
 * there is something real to learn here. The agent can already see it: its terrain grid marks lava, fire, magma, cactus,
 * powder snow, berry bushes, cobwebs, wither roses, lit campfires and drops of more than eight blocks as hazards, above
 * solid, which is what keeps it from walking onto them. Nothing has ever told it that what it must not step on is
 * somewhere to put an opponent.
 *
 * <p>So a site is labelled by what is on it, a share of the fights is drawn from the sites with something worth using, and
 * every fight writes down which kind of ground it was on and what finished the opponent. That last number is the whole
 * exercise: if the agent is learning the trick, terrain finishes rise on lava and cliff sites and nowhere else.
 *
 * <p>The labelling happens here rather than in the terrain library's index, for three reasons. A site's ground is loaded
 * and ticking by the time it is handed out, so the scan costs no disk and happens once per site rather than once per
 * fight; it works on a library that is already built, with no rebuild of several gigabytes; and it leaves the index format
 * alone, which is worth something while it is being taught to grow a site at a time.
 *
 * <p>What counts as a hazard here has to mean the same as what the agent sees as one, {@code AgentObservation.hazard},
 * which is a private rule of the observation's. The two lists are deliberately the same and have to be kept that way: a
 * block the agent reads as a hazard and this does not is ground the agent will refuse to use and this will promise.
 */
public final class SiteHazards {

    private SiteHazards() {}

    /**
     * What a site is worth to a fighter who can push, most useful first. A site is labelled with the best thing on it, so
     * {@code LAVA} means there is lava somewhere on it and not that it is all lava.
     */
    public enum Kind {

        /** A lava lake or flow: what kills anything that is knocked into it, whatever its health. */
        LAVA,

        /** A cliff or a ravine: an edge with a fall of more than eight blocks off it. */
        DROP,

        /** Something else that hurts what stands in it: fire, magma, cactus, powder snow, berries, cobweb, dripstone. */
        HAZARD,

        /** Water, which kills nothing but which drowns what cannot swim and breaks a fall. */
        WATER,

        /** Ground, and nothing on it worth knocking anything into. */
        FLAT;

        /** Whether there is something on it worth using, which is what a fight asking for hazardous ground means. */
        public boolean hazardous() {

            return this == LAVA || this == DROP || this == HAZARD;
        }

        /** The name it goes into the results under. */
        public String label() {

            return this.name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * How far apart the columns sampled across a site are. Four blocks is fine enough that a lava lake, a cactus patch or
     * the lip of a ravine cannot hide between samples, and coarse enough that a whole site is four hundred odd lookups: it
     * is scanned once per site, and a site hosts a hundred fights.
     */
    private static final int STEP = 4;

    /**
     * The fall that makes an edge worth knocking something off: more than eight blocks, which is where the agent's own
     * terrain grid starts calling a drop a hazard, and about five health of twenty to anything that goes over.
     */
    private static final int DROP = 9;

    /** What is on the site around that centre, over a square that many blocks across. */
    static Kind of(ServerLevel level, BlockPos centre, int size) {

        int across = size / STEP;
        int[] surface = new int[(across + 1) * (across + 1)];

        boolean lava = false;
        boolean hazard = false;
        boolean water = false;

        for (int row = 0; row <= across; row++) {

            for (int column = 0; column <= across; column++) {

                int x = centre.getX() - size / 2 + column * STEP;
                int z = centre.getZ() - size / 2 + row * STEP;

                // The first thing that stops movement, fluids counted, so the top of a lava lake is the surface and not
                // whatever stone lies under it.
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                BlockPos at = new BlockPos(x, top - 1, z);
                BlockState state = level.getBlockState(at);

                surface[row * (across + 1) + column] = top;

                lava |= state.getFluidState().is(FluidTags.LAVA);
                water |= state.getFluidState().is(FluidTags.WATER);
                hazard |= hazard(state);
            }
        }

        if (lava) {

            return Kind.LAVA;
        }

        if (edged(surface, across + 1)) {

            return Kind.DROP;
        }

        return hazard ? Kind.HAZARD : water ? Kind.WATER : Kind.FLAT;
    }

    /**
     * Whether any two samples next to each other are a long fall apart, which is a cliff or the lip of a ravine. Measured
     * between neighbours rather than across the whole site, since a site that climbs eighty blocks over eighty is a slope
     * and nothing to knock anything off.
     */
    private static boolean edged(int[] surface, int side) {

        for (int row = 0; row < side; row++) {

            for (int column = 0; column < side; column++) {

                int here = surface[row * side + column];

                if (column + 1 < side && Math.abs(here - surface[row * side + column + 1]) >= DROP) {

                    return true;
                }

                if (row + 1 < side && Math.abs(here - surface[(row + 1) * side + column]) >= DROP) {

                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Whether standing in this would hurt. The same list as the agent's own observation reads as a hazard, which is the
     * point: see the class comment.
     */
    private static boolean hazard(BlockState state) {

        return state.getFluidState().is(FluidTags.LAVA)
                || state.getBlock() instanceof BaseFireBlock
                || state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.CACTUS)
                || state.is(Blocks.POWDER_SNOW)
                || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.COBWEB)
                || state.is(Blocks.WITHER_ROSE)
                || state.is(Blocks.POINTED_DRIPSTONE)
                || state.getBlock() instanceof CampfireBlock && state.getValue(CampfireBlock.LIT);
    }
}
