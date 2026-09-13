package net.sievert.modularmobai.gametest.terrain;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

/**
 * Lava poured onto ground that has none, so that knocking an opponent into it is something the agent meets often enough to
 * learn rather than something it stumbles on.
 *
 * <p>The terrain library is overworld surface, and the overworld surface has almost no lava: of half a million fights,
 * 10,067 were on ground with any, and 197 of those ended with the lava finishing the opponent. That is the best blow in the
 * game against the league's heaviest — a hundred health of iron golem takes a long time to cut down and no time at all to
 * push into a lake — and at two per cent of fights it is far too rare to be learned from. The sites are not the place to fix
 * it: they are a hard-linked library of real ground, shared between workers, several gigabytes of it, and nothing here
 * should be writing to that.
 *
 * <p>So a fight that asked for hazardous ground and was handed flat ground gets a pool poured beside it, and the ground is
 * put back exactly as it was when the fight ends. What the fight is recorded on becomes {@code lava}, so the ground table
 * still says what the agent had to work with.
 *
 * <h2>Why a pool and not a pit</h2>
 *
 * <p>The lava replaces the top layer of ground, so its surface is flush with what surrounds it: a body knocked onto it is
 * in it, where a body knocked at a pit might land on the rim. Flush also means the lava cannot flow, since every block
 * around it at its own height is the ground it replaced and lava only spreads into what it can replace. The layer under it
 * is made solid first, or a pool over a cave pours into the cave.
 *
 * <h2>Putting it back</h2>
 *
 * <p>Every block this changes is remembered with the state it had, and restoring walks the list backwards. Fire and lava are
 * the two things that can escape the list, so the plants and the loose flammables in a five by five around the pool go
 * first, and the restore sweeps for any fire or lava that was not there before. A site hosts a hundred fights, and a pool
 * left behind by one of them would still be there for the other ninety nine.
 *
 * <p><b>Restoring notifies the neighbours</b>, which is what makes a leak temporary rather than permanent. Flowing lava
 * only works out that its source is gone on a scheduled fluid tick, and the only thing that schedules one is a neighbour
 * update: a block put back with clients-only flags leaves any flow it fed standing for good. Measured over blast3, 929,311
 * fights: 97.6% of the surface lava beside a fight on ground labelled {@code flat} and 100% of it on {@code water} was
 * flowing lava with no source anywhere near it, and 81 of the 81 sampled lava deaths on that ground were into flowing lava,
 * while sites labelled {@code drop} — the one kind that is hazardous already and so never poured on — had none at all in
 * 216 sampled fights. That orphaned flow was 4.2% of every flat-ground fight lost and 3.9% of every water one, against
 * 0.014% on drop ground.
 *
 * <p>Measured again after the fix, 1,500 league fights of the scripted fighter either side of it on the same pinned ground:
 * flat and water replays carrying flowing lava with no source went from 22.3%, 62 of 278, to 0 of 292, and lava deaths on
 * that ground from 20.0 per 1,000 fights to none. The neighbour update is nearly all of that — with it and the wider sweep
 * alone the share came to 0.7% — and {@link #walledIn} takes the rest.
 */
public final class PouredHazards {

    private PouredHazards() {}

    /** How wide the pool is, in blocks: three, which is wide enough that a knockback cannot clear it. */
    private static final int WIDTH = 3;

    /** How far the pool goes from the middle of the fight, so it is somewhere either side can be pushed towards. */
    private static final int NEAREST = 3;
    private static final int FURTHEST = 7;

    /** How far from a fighter's own feet the pool must stay, so that nobody starts the fight standing in it. */
    private static final int CLEAR_OF_FIGHTERS = 3;

    /** How much higher or lower than the fight the pool may sit, so that it is ground a body can be pushed onto. */
    private static final int LEVEL = 2;

    /** How many places are tried before giving the fight ordinary ground. */
    private static final int TRIES = 24;

    /**
     * How far lava spreads from a source on land, which is what the sweep after a fight has to cover: the rim of a pool that
     * turns out not to be walled in feeds a flow that goes this far and then falls.
     */
    private static final int SPREAD = 3;

    /** How far a spill can get, which the mechanics suite puts a block of lava at to check the sweep reaches it. */
    public static int spread() {

        return SPREAD;
    }

    /** One block as it was before the pool went in. */
    private record Was(BlockPos at, BlockState state) {}

    /** A pool that has been poured, and everything needed to take it away again. */
    public record Pool(List<Was> was, AABB box) {

        public BlockPos centre() {

            return BlockPos.containing(this.box.getCenter());
        }
    }

    /**
     * Pours a pool of lava near a fight, or returns null where there is nowhere sensible to put one.
     *
     * @param fighters where everyone on both sides is standing, which the pool keeps away from
     */
    @Nullable
    public static Pool pour(ServerLevel level, BlockPos middle, List<BlockPos> fighters, RandomSource random) {

        for (int attempt = 0; attempt < TRIES; attempt++) {

            double angle = random.nextDouble() * Math.PI * 2.0D;
            int away = Mth.nextInt(random, NEAREST, FURTHEST);
            int x = middle.getX() + (int) Math.round(Math.cos(angle) * away);
            int z = middle.getZ() + (int) Math.round(Math.sin(angle) * away);

            BlockPos spot = surface(level, x, z);

            if (spot == null || Math.abs(spot.getY() - middle.getY()) > LEVEL || tooClose(spot, fighters)
                    || !walledIn(level, spot)) {

                continue;
            }

            return pourAt(level, spot);
        }

        return null;
    }

    /**
     * Lays a pool on one named block of ground, which is the half of this that can be checked: what a pool is made of and
     * that draining puts every block back. Finding somewhere to put one needs open ground with a heightmap that means
     * something, which the test arena's closed box is not.
     *
     * @param ground the block a body would be standing on, which the lava replaces
     */
    public static Pool pourAt(ServerLevel level, BlockPos ground) {

        return fill(level, ground);
    }

    /**
     * Puts back every block the pool replaced, and anything the lava set alight or fed on its way.
     *
     * <p>Every block goes back with a neighbour update, {@link Block#UPDATE_ALL}. That is the whole of the difference
     * between a leak that lasts a fight and one that lasts the site's remaining ninety nine: a flow whose source has gone
     * only notices on a scheduled fluid tick, and nothing but a neighbour update schedules one. Drops are still suppressed,
     * so putting the ground back does not litter the site with items.
     *
     * <p>The pool's own blocks are last in the list and so go back first, which means there is no lava left in the pool by
     * the time the ring around it is restored, and the ground is back under the plants before the plants are.
     *
     * @return whether the sweep found fire or lava that was not on the list, which means the pool got out of its box and
     *         this site's ground is no longer what it was labelled
     */
    public static boolean drain(ServerLevel level, Pool pool) {

        for (int i = pool.was().size() - 1; i >= 0; i--) {

            Was was = pool.was().get(i);
            level.setBlock(was.at(), was.state(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
        }

        // Fire and lava are what get out of the list: fire spreads to whatever it likes while the fight runs, and a pool
        // whose rim turned out not to be walled in feeds a flow that runs three blocks and falls. Anything burning or
        // molten left in the box after the list has been put back was not there before.
        boolean[] escaped = {false};

        BlockPos.betweenClosedStream(pool.box()).forEach(at -> {

            BlockState state = level.getBlockState(at);

            if (state.is(Blocks.FIRE) || state.is(Blocks.LAVA)) {

                level.setBlock(at.immutable(), Blocks.AIR.defaultBlockState(),
                        Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
                escaped[0] = true;
            }
        });

        return escaped[0];
    }

    /**
     * The block a body would stand on at this column, or null where there is nothing to stand on: the top of the ground,
     * with room above it and something solid under it.
     */
    @Nullable
    private static BlockPos surface(ServerLevel level, int x, int z) {

        BlockPos ground = ground(level, x, z);

        return ground != null && level.getBlockState(ground.above()).isAir() ? ground : null;
    }

    /**
     * The top solid block of a column, whatever stands on it, or null where the top of the ground is not something a pool
     * could rest on or be walled in by.
     *
     * <p>Kept apart from {@link #surface} because the two questions are not the same one. Where the pool itself goes, the
     * column has to be clear: a body is knocked onto it and has to land in the lava, not on a fence post. Whether the ring
     * around it is level does not care what grows there, because {@link #fill} pulls every plant in the five by five up
     * before a drop of lava goes in. Asking for clear ground in the ring as well passed over most of the overworld's flat
     * grass — a single fern in any of twenty five columns was enough — and measured at two thirds of every pool the league
     * used to pour.
     */
    @Nullable
    private static BlockPos ground(ServerLevel level, int x, int z) {

        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos ground = new BlockPos(x, top - 1, z);

        return level.getBlockState(ground).isCollisionShapeFullBlock(level, ground) ? ground : null;
    }

    /**
     * Whether a pool laid on this block would really be flush: every column of the five by five it and its ring occupy has
     * its ground at the same height, so each of the nine lava blocks has solid ground beside it at its own level and the lava
     * has nowhere to go.
     *
     * <p>Flush is what the whole design rests on and it was only ever checked at the middle column. One block of step at the
     * rim — which is most of any patch of overworld, {@code flat} label or not — puts a lava block over open air, and from
     * there it runs three blocks and falls, out of the box the fight sweeps afterwards. The cost of being wrong is not one
     * fight: the site hosts a hundred and its label is worked out once, so every later fight on it is fought beside lava and
     * recorded as flat ground. The cost of being too careful is that this fight gets ordinary ground, which is a fight the
     * hazard share does not get and nothing worse.
     *
     * <p>What stands on the ring is not asked about, only how high its ground is: see {@link #ground}.
     */
    private static boolean walledIn(ServerLevel level, BlockPos spot) {

        int ring = WIDTH / 2 + 1;

        for (int dx = -ring; dx <= ring; dx++) {

            for (int dz = -ring; dz <= ring; dz++) {

                BlockPos ground = ground(level, spot.getX() + dx, spot.getZ() + dz);

                if (ground == null || ground.getY() != spot.getY()) {

                    return false;
                }
            }
        }

        return true;
    }

    private static boolean tooClose(BlockPos spot, List<BlockPos> fighters) {

        for (BlockPos fighter : fighters) {

            if (fighter.distSqr(new BlockPos(spot.getX(), fighter.getY(), spot.getZ()))
                    < (double) CLEAR_OF_FIGHTERS * CLEAR_OF_FIGHTERS) {

                return true;
            }
        }

        return false;
    }

    /**
     * Lays the pool in: the ground under it made solid, the plants over it taken away, and then the lava itself, in that
     * order, so that no lava is ever placed over a hole or beside a flower.
     */
    private static Pool fill(ServerLevel level, BlockPos spot) {

        List<Was> was = new ArrayList<>();
        int reach = WIDTH / 2;
        int margin = reach + 1;

        // What the sweep after the fight has to cover, which is wider than what this touches: a rim that is not walled in
        // after all feeds a flow that runs SPREAD blocks and then falls, and fire spreads to whatever it likes. Reaching far
        // enough costs a few hundred block reads once a fight; reaching too little costs the site.
        int sweep = reach + SPREAD;

        AABB box = new AABB(spot.offset(-sweep, -1 - SPREAD, -sweep)).minmax(new AABB(spot.offset(sweep, 2, sweep)));

        // Anything that burns, and anything a fighter would rather not be standing in, out of the way first: a flower next
        // to lava is a fire, and fire spreads where this cannot follow it.
        for (int dx = -margin; dx <= margin; dx++) {

            for (int dz = -margin; dz <= margin; dz++) {

                for (int dy = 1; dy <= 2; dy++) {

                    BlockPos at = spot.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(at);

                    if (!state.isAir()) {

                        was.add(new Was(at, state));
                        level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
                    }
                }
            }
        }

        // What holds the lava up. A pool over a cave empties into it, taking the fight's ground with it.
        for (int dx = -reach; dx <= reach; dx++) {

            for (int dz = -reach; dz <= reach; dz++) {

                BlockPos under = spot.offset(dx, -1, dz);

                if (!level.getBlockState(under).isCollisionShapeFullBlock(level, under)) {

                    was.add(new Was(under, level.getBlockState(under)));
                    level.setBlock(under, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
                }
            }
        }

        // The pool, level with the ground it replaces, which is what keeps it from flowing anywhere.
        for (int dx = -reach; dx <= reach; dx++) {

            for (int dz = -reach; dz <= reach; dz++) {

                BlockPos at = spot.offset(dx, 0, dz);

                was.add(new Was(at, level.getBlockState(at)));
                level.setBlock(at, Blocks.LAVA.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
            }
        }

        return new Pool(was, box);
    }
}
