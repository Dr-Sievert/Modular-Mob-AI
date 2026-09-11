package net.sievert.modularmobai.gametest.terrain;

import java.util.ArrayList;
import java.util.List;

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
 * underground, and each fight is put on its own site instead: a lattice of sites a long way apart, each eighty blocks
 * across and kept loaded and ticking. There are no walls. The chunks between sites never tick entities, so anything that
 * walks off its site simply stops there, the fight cannot finish, and it ends the way any stalled fight does: a loss on
 * time. No two fights can ever reach each other.
 *
 * <p>The lattice is placed once per game process, somewhere the world generator says is land, and the sites are handed
 * out round robin. There are more sites than fights run at once, so a site is only ever handed out again after the fight
 * on it has finished and been cleaned up. The terrain stays as the generator left it: fights move nothing but
 * themselves, and what they drop is swept up after them.
 */
public final class TerrainSites {

    private TerrainSites() {}

    /** Carried by everything a fight spawns, so a sweep can tell a fighter from wildlife the world generated. */
    public static final String TAG = Constants.MOD_ID + ".arena";

    /**
     * Chunks kept loaded and ticking either side of a site's centre chunk: two, so a fight has five chunks, eighty
     * blocks, of open ground each way, and starts in the middle of it.
     */
    private static final int RADIUS_CHUNKS = 2;
    private static final int SIZE = (2 * RADIUS_CHUNKS + 1) * 16;

    /** Blocks between site centres: eight chunks, so three chunks of dead ground separate one fight from the next. */
    private static final int SPACING = 128;
    private static final int COLUMNS = 8;

    /**
     * More than run at once, which is fifty unless a run asks for bigger batches: every fight in progress holds a site,
     * and some of the lattice is always water or cliff that nothing can stand on. When all the usable ones are busy, a
     * fight waits a tick for one. Each site is twenty five chunks kept in memory, which is what keeps this from growing:
     * eighty sites did not fit in a gigabyte and a half of heap.
     */
    private static final int COUNT = 64;

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

    @Nullable
    private static BlockPos origin;

    private static int next;
    private static final boolean[] unusable = new boolean[COUNT];

    /** How often, in game ticks, the whole world is swept for wildlife. */
    private static final int WILDLIFE_SWEEP_TICKS = 20;

    /** The game time the next sweep for wildlife is due at; the first fight to ask for a site does the first. */
    private static long nextWildlifeSweep;

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
     * Chooses where the lattice goes and starts the world generating it, before any fight asks for a site. Returns where
     * the framework's own plots should go, which is under the lattice and out of the way.
     */
    public static synchronized BlockPos start(ServerLevel level) {

        if (origin == null) {

            origin = chooseOrigin(level);

            // Asked for all at once, so the generator works through them on every thread it has. The level's own
            // setChunkForced would generate each one on the spot, one after another, which for sixteen hundred chunks is
            // most of a minute of the server standing still; the ticket underneath it only asks.
            for (int site = 0; site < COUNT; site++) {

                BlockPos centre = centre(site);
                int chunkX = centre.getX() >> 4;
                int chunkZ = centre.getZ() >> 4;

                for (int dx = -RADIUS_CHUNKS; dx <= RADIUS_CHUNKS; dx++) {

                    for (int dz = -RADIUS_CHUNKS; dz <= RADIUS_CHUNKS; dz++) {

                        level.getChunkSource().updateChunkForced(new ChunkPos(chunkX + dx, chunkZ + dz), true);
                    }
                }
            }

            Constants.LOG.info("Terrain arenas: {} sites from {} in {}", COUNT, origin.toShortString(),
                    level.getBiome(origin).unwrapKey().map(key -> key.location().toString()).orElse("an unnamed biome"));
        }

        return new BlockPos(origin.getX(), level.getMinBuildHeight() + 5, origin.getZ());
    }

    /**
     * The next free site, with somewhere to stand for the agent and for its opponent, or null when every usable site has
     * a fight on it; the caller tries again a tick later. Blocks until the site's chunks exist, which is only ever a wait
     * the first time round.
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

            if (unusable[index] || inUse[index]) {

                continue;
            }

            BlockPos centre = centre(index);
            load(level, centre);

            for (int tries = 0; tries < PLACEMENT_TRIES; tries++) {

                Site site = place(level, index, centre, random);

                if (site != null) {

                    inUse[index] = true;
                    return site;
                }
            }

            // Water or cliff all the way across. Skipped for the rest of this process rather than tried again.
            unusable[index] = true;
        }

        return null;
    }

    /** Takes the fighters away and sweeps up whatever the fight left lying around, so the site is clean for the next. */
    public static synchronized void release(ServerLevel level, Site site, Entity... fighters) {

        for (Entity fighter : fighters) {

            if (fighter != null && !fighter.isRemoved()) {

                fighter.discard();
            }
        }

        sweep(level, site.bounds().inflate(8.0D));
        inUse[site.index()] = false;
    }

    // ---------------------------------------------------------------------------------------------------------------

    private static BlockPos centre(int index) {

        return origin.offset((index % COLUMNS) * SPACING, 0, (index / COLUMNS) * SPACING);
    }

    /** The whole loaded area of a site, from bedrock to the sky. */
    private static AABB siteBox(ServerLevel level, BlockPos centre) {

        int minX = ((centre.getX() >> 4) - RADIUS_CHUNKS) << 4;
        int minZ = ((centre.getZ() >> 4) - RADIUS_CHUNKS) << 4;

        return new AABB(minX, level.getMinBuildHeight(), minZ, minX + SIZE, level.getMaxBuildHeight(), minZ + SIZE);
    }

    private static void load(ServerLevel level, BlockPos centre) {

        int chunkX = centre.getX() >> 4;
        int chunkZ = centre.getZ() >> 4;

        for (int dx = -RADIUS_CHUNKS; dx <= RADIUS_CHUNKS; dx++) {

            for (int dz = -RADIUS_CHUNKS; dz <= RADIUS_CHUNKS; dz++) {

                level.getChunk(chunkX + dx, chunkZ + dz);
            }
        }
    }

    private static void sweep(ServerLevel level, AABB box) {

        List<Entity> leftovers = level.getEntitiesOfClass(Entity.class, box, entity ->
                entity instanceof ItemEntity || entity instanceof ExperienceOrb || entity instanceof Projectile);

        leftovers.forEach(Entity::discard);
    }

    /**
     * Removes every living thing no fight brought, anywhere in the world. Whatever the world generator put down, cows and
     * all, would otherwise wander through the fights, and each one near a fight costs as much to tick as a fighter.
     *
     * <p>A site cannot simply be swept once, when it is first claimed: a chunk that has just been generated only shows
     * its animals to the world a moment after it is handed over, so a sweep that early finds nothing, and the plots the
     * framework keeps under the lattice tick whatever lives above them too. Measured, the cows, sheep and chickens that
     * got through outnumbered the fighters and cost a tenth of the server thread. So the whole world is swept, a second
     * of game time apart; nothing spawns in a game test world, so each sweep only ever finds what the last one could not
     * yet see, and bees let out of a hive.
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
    private static Site place(ServerLevel level, int index, BlockPos centre, RandomSource random) {

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

        boolean solid = level.getBlockState(ground).isFaceSturdy(level, ground, Direction.UP)
                && level.getFluidState(ground).isEmpty();

        boolean clear = level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                && level.getFluidState(feet).isEmpty()
                && level.getFluidState(feet.above()).isEmpty();

        return solid && clear ? feet : null;
    }

    /**
     * Somewhere the lattice lands mostly on land. Asked of the biome source directly, which answers without generating a
     * single chunk, so trying hundreds of places costs next to nothing.
     */
    private static BlockPos chooseOrigin(ServerLevel level) {

        BiomeSource biomes = level.getChunkSource().getGenerator().getBiomeSource();
        Climate.Sampler climate = level.getChunkSource().randomState().sampler();
        RandomSource random = GameTestTuning.terrainSeed() != 0L ? RandomSource.create(GameTestTuning.terrainSeed()) : level.getRandom();

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
                int site = sample * COUNT / samples + COLUMNS / 2;
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
