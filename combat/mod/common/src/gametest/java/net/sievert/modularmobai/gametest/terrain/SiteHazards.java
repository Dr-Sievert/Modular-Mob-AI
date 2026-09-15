package net.sievert.modularmobai.gametest.terrain;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.sievert.modularmobai.brain.schema.AgentObservation;

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
     * the lip of a ravine cannot hide between samples, and coarse enough that a site is a few hundred lookups: it is
     * scanned once per site, and a site hosts a hundred fights.
     */
    private static final int STEP = 4;

    /**
     * How much of a site is looked at, as a share of its width. The fighters start within a few blocks of the middle and a
     * minute of fighting does not carry them far, so what is out at the corners is ground neither of them will ever see; a
     * label taken from the whole site would promise lava that is forty blocks away.
     */
    private static final double LOOKED_AT = 0.6D;

    /**
     * The fall that makes an edge worth knocking something off: more than eight blocks, which is where the agent's own
     * terrain grid starts calling a drop a hazard, and about five health of twenty to anything that goes over.
     */
    private static final int DROP = 9;

    /**
     * How many pairs of neighbouring samples have to be a long fall apart before a site counts as having an edge worth
     * using. One is nothing: ground steps that far somewhere in almost any patch of it.
     *
     * <p>Measured over fifty two sites of the terrain library, of 312 neighbouring pairs each: a third of them have no such
     * pair at all, and the rest run from two to fifty eight. Eight is where a run of edge starts rather than a single ledge,
     * and it labels about a third of the library, which is room enough for the quarter of the fights that ask for it.
     */
    private static final int EDGES = 8;

    /** What is on the site around that centre, over a square that many blocks across. */
    static Kind of(ServerLevel level, BlockPos centre, int size) {

        int looked = (int) (size * LOOKED_AT);
        int across = looked / STEP;
        int side = across + 1;
        int[] surface = new int[side * side];

        boolean lava = false;
        boolean hazard = false;
        boolean water = false;

        for (int row = 0; row < side; row++) {

            for (int column = 0; column < side; column++) {

                int x = centre.getX() - looked / 2 + column * STEP;
                int z = centre.getZ() - looked / 2 + row * STEP;

                // The first thing that stops movement, fluids counted but leaves not, which is the same ground the sites
                // are laid out on. Counting leaves made a jungle canopy the surface, and every gap in it a cliff.
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos at = new BlockPos(x, top - 1, z);
                BlockState state = level.getBlockState(at);

                surface[row * side + column] = top;

                lava |= state.getFluidState().is(FluidTags.LAVA);
                water |= state.getFluidState().is(FluidTags.WATER);
                hazard |= hazard(state);
            }
        }

        if (lava) {

            return Kind.LAVA;
        }

        if (edges(surface, side) >= EDGES) {

            return Kind.DROP;
        }

        return hazard ? Kind.HAZARD : water ? Kind.WATER : Kind.FLAT;
    }

    /**
     * How many pairs of samples next to each other are a long fall apart, which is what a cliff or the lip of a ravine
     * looks like. Measured between neighbours rather than across the whole site, since a site that climbs forty blocks over
     * forty is a slope and nothing to knock anything off.
     */
    private static int edges(int[] surface, int side) {

        int found = 0;

        for (int row = 0; row < side; row++) {

            for (int column = 0; column < side; column++) {

                int here = surface[row * side + column];

                if (column + 1 < side && Math.abs(here - surface[row * side + column + 1]) >= DROP) {

                    found++;
                }

                if (row + 1 < side && Math.abs(here - surface[(row + 1) * side + column]) >= DROP) {

                    found++;
                }
            }
        }

        return found;
    }

    /**
     * Whether standing in this would hurt: the agent's own rule, asked rather than copied, so the two can never drift
     * apart. See the class comment for why that matters.
     */
    private static boolean hazard(BlockState state) {

        return AgentObservation.hazard(state);
    }
}
