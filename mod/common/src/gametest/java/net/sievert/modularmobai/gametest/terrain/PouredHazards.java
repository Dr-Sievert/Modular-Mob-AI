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
 * <p>Every block this changes is remembered with the state it had, and restoring walks the list backwards. Fire is the one
 * thing that can escape the list, so the plants and the loose flammables in a five by five around the pool go first, and
 * the restore sweeps the same box for any fire or lava that was not there before. A site hosts a hundred fights, and a
 * pool left behind by one of them would still be there for the other ninety nine.
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

            if (spot == null || Math.abs(spot.getY() - middle.getY()) > LEVEL || tooClose(spot, fighters)) {

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

    /** Puts back every block the pool replaced, and anything the lava set alight on its way. */
    public static void drain(ServerLevel level, Pool pool) {

        for (int i = pool.was().size() - 1; i >= 0; i--) {

            Was was = pool.was().get(i);
            level.setBlock(was.at(), was.state(), Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
        }

        // Fire is the one thing that gets out of the list: it spreads to whatever it likes while the fight runs. Anything
        // burning or molten left in the box after the list has been put back was not there before.
        BlockPos.betweenClosedStream(pool.box()).forEach(at -> {

            BlockState state = level.getBlockState(at);

            if (state.is(Blocks.FIRE) || state.is(Blocks.LAVA)) {

                level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
            }
        });
    }

    /**
     * The block a body would stand on at this column, or null where there is nothing to stand on: the top of the ground,
     * with room above it and something solid under it.
     */
    @Nullable
    private static BlockPos surface(ServerLevel level, int x, int z) {

        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos ground = new BlockPos(x, top - 1, z);

        if (!level.getBlockState(ground).isCollisionShapeFullBlock(level, ground)) {

            return null;
        }

        return level.getBlockState(ground.above()).isAir() ? ground : null;
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

        AABB box = new AABB(spot.offset(-margin, -1, -margin)).minmax(new AABB(spot.offset(margin, 2, margin)));

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
