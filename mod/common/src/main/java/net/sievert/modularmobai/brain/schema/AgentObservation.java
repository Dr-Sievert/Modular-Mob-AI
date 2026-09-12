package net.sievert.modularmobai.brain.schema;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.FlyingMob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.entity.animal.Parrot;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.monster.breeze.Breeze;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.sievert.modularmobai.entity.agent.AgentMob;
import net.sievert.modularmobai.entity.agent.ExecutedControls;
import net.sievert.modularmobai.entity.agent.MobControls;

/**
 * Fills the flat observation vector for one agent, and holds the parts of it that every body shares.
 *
 * <p>{@link #write} is the <b>humanoid</b>'s arrangement: its self block, its hotbar, its echo, and then the two blocks
 * below. {@link #writeEnemies} and {@link #writeTerrain} are every body's, and take the offset their block sits at, so a
 * second body writes them wherever its own layout puts them. What a slot or a hand holds, what an enemy is, and what a
 * terrain cell means are shared for the same reason: an opponent and a block of ground look the same whoever is looking at
 * them, and two bodies disagreeing about what a hazard is would be a bug nobody could see. Only the shape of a body — what
 * it has, what it can do, and where each block sits — belongs to a species. See {@link Species}.
 *
 * <p>Everything about other entities is written in the agent's own frame, so an opponent two blocks ahead reads the same
 * whether the fight is happening facing north or facing south. The terrain grid is the exception and stays aligned to
 * the world, with the agent's facing written into the self block so the two can be related.
 *
 * <p>Every number is roughly in the range minus one to one. Nothing here is normalised carefully yet; the scales are
 * first guesses and are meant to be revisited once there is a network to be upset by them.
 */
public final class AgentObservation {

    private AgentObservation() {}

    static final float DEGREES_TO_RADIANS = (float) (Math.PI / 180.0D);

    /** A block a tick is far faster than anything moves, so this keeps velocities near the unit range. Every body's. */
    static final double VELOCITY_SCALE = 0.5D;

    static final float MAX_FALL_DISTANCE = 20.0F;

    /** What a terrain cell holds: see {@link #writeTerrain} and {@link #cell}. */
    public static final float EMPTY = 0.0F;
    public static final float FLUID = 0.5F;
    public static final float SOLID = 1.0F;

    /**
     * Somewhere that hurts or kills a body that goes into it or stands on it. Above solid rather than below empty: a
     * network trained when these read as solid keeps reading them as somewhere it cannot go, where one read below empty
     * could take them for more open than air and walk straight in. Only standing on one is new, and learnable.
     */
    public static final float HAZARD = 1.5F;

    private static final BlockPos.MutableBlockPos SCRATCH = new BlockPos.MutableBlockPos();

    /**
     * The humanoid's row, block by block in the order its layout puts them.
     *
     * @param out  the destination buffer, which may hold a whole batch
     * @param base where this agent's row starts in that buffer
     */
    public static void write(AgentMob agent, EnemySlots slots, float[] out, int base) {

        java.util.Arrays.fill(out, base, base + ObservationSchema.OBS_DIM, 0.0F);

        float yaw = agent.getYRot() * DEGREES_TO_RADIANS;
        float sin = Mth.sin(yaw);
        float cos = Mth.cos(yaw);

        writeSelf(agent, slots, out, base, sin, cos);
        writeHotbar(agent, out, base);
        writeEcho(agent.executed(), out, base);
        writeEnemies(agent, slots, out, base + ObservationSchema.ENEMY_OFFSET, sin, cos);
        writeTerrain(agent, out, base + ObservationSchema.TERRAIN_OFFSET);
        writeRays(agent, out, base + ObservationSchema.RAY_OFFSET);
    }

    /** The way the agent is looking, as the sine and cosine every frame conversion below needs. */
    static float yawSin(AgentMob agent) {

        return Mth.sin(agent.getYRot() * DEGREES_TO_RADIANS);
    }

    static float yawCos(AgentMob agent) {

        return Mth.cos(agent.getYRot() * DEGREES_TO_RADIANS);
    }

    // -----------------------------------------------------------------------------------------------------------

    private static void writeSelf(AgentMob agent, EnemySlots slots, float[] out, int base, float sin, float cos) {

        int at = base + ObservationSchema.SELF_OFFSET;
        Vec3 velocity = agent.getDeltaMovement();

        out[at + ObservationSchema.SELF_HEALTH] = agent.getHealth() / agent.getMaxHealth();
        out[at + ObservationSchema.SELF_VELOCITY_FORWARD] = (float) (forward(velocity.x, velocity.z, sin, cos) / VELOCITY_SCALE);
        out[at + ObservationSchema.SELF_VELOCITY_UP] = (float) (velocity.y / VELOCITY_SCALE);
        out[at + ObservationSchema.SELF_VELOCITY_RIGHT] = (float) (right(velocity.x, velocity.z, sin, cos) / VELOCITY_SCALE);
        out[at + ObservationSchema.SELF_ON_GROUND] = agent.onGround() ? 1.0F : 0.0F;
        out[at + ObservationSchema.SELF_IN_WATER] = agent.isInWater() ? 1.0F : 0.0F;
        out[at + ObservationSchema.SELF_ATTACK_STRENGTH] = agent.getAttackStrengthScale(0.0F);
        out[at + ObservationSchema.SELF_USE_COOLDOWN] = agent.useCooldown() / (float) AgentMob.USE_INTERVAL;
        out[at + ObservationSchema.SELF_USING] = agent.executed().using ? 1.0F : 0.0F;
        out[at + ObservationSchema.SELF_USING_OFFHAND] = agent.executed().usingOffhand ? 1.0F : 0.0F;
        out[at + ObservationSchema.SELF_SPRINTING] = agent.isSprinting() ? 1.0F : 0.0F;
        out[at + ObservationSchema.SELF_CROUCHING] = agent.isShiftKeyDown() ? 1.0F : 0.0F;
        out[at + ObservationSchema.SELF_FALL_DISTANCE] = Math.min(agent.fallDistance, MAX_FALL_DISTANCE) / MAX_FALL_DISTANCE;

        // How far the body has been left behind by the aim, which is what tells the agent it is about to be dragged round.
        float bodyOffset = Mth.wrapDegrees(agent.yBodyRot - agent.getYRot()) * DEGREES_TO_RADIANS;
        out[at + ObservationSchema.SELF_BODY_OFFSET_SIN] = Mth.sin(bodyOffset);
        out[at + ObservationSchema.SELF_BODY_OFFSET_COS] = Mth.cos(bodyOffset);

        out[at + ObservationSchema.SELF_PITCH] = agent.getXRot() / 90.0F;
        out[at + ObservationSchema.SELF_AIM_SIN] = sin;
        out[at + ObservationSchema.SELF_AIM_COS] = cos;
        out[at + ObservationSchema.SELF_HURT_TIME] = agent.hurtTime / 10.0F;
        out[at + ObservationSchema.SELF_ENEMIES_IN_RANGE] = slots.inRangeCount() / (float) ObservationSchema.ENEMY_SLOTS;
    }

    private static void writeHotbar(AgentMob agent, float[] out, int base) {

        int at = base + ObservationSchema.HOTBAR_OFFSET;

        for (int slot = 0; slot < MobControls.HOTBAR_SIZE; slot++) {

            out[at + slot] = itemKind(agent.getHotbarItem(slot));
        }
    }

    private static void writeEcho(ExecutedControls echo, float[] out, int base) {

        int at = base + ObservationSchema.ECHO_OFFSET;

        out[at] = echo.moveForward;
        out[at + 1] = echo.moveStrafe;
        out[at + 2] = echo.jumped ? 1.0F : 0.0F;
        out[at + 3] = echo.sprinting ? 1.0F : 0.0F;
        out[at + 4] = echo.sneaking ? 1.0F : 0.0F;
        out[at + 5] = echo.aimYawDegrees / MobControls.MAX_AIM_YAW_PER_TICK;
        out[at + 6] = echo.aimPitchDegrees / MobControls.MAX_AIM_PITCH_PER_TICK;
        out[at + 7] = echo.attacked ? 1.0F : 0.0F;
        out[at + 8] = echo.attackHit ? 1.0F : 0.0F;
        out[at + 9] = echo.attackStrength;
        out[at + 10] = echo.attackDamage / 20.0F;
        out[at + 11] = echo.attackCritical ? 1.0F : 0.0F;
        out[at + 12] = echo.attackSweep ? 1.0F : 0.0F;
        out[at + 13] = echo.attackSprintKnockback ? 1.0F : 0.0F;
        out[at + 14] = echo.using ? 1.0F : 0.0F;
        out[at + 15] = echo.usingOffhand ? 1.0F : 0.0F;
        out[at + 16] = echo.usedOnBlock ? 1.0F : 0.0F;
        out[at + 17] = echo.selectedSlot / (float) (MobControls.HOTBAR_SIZE - 1);
        out[at + 18] = echo.swappedWeapon ? 1.0F : 0.0F;

        // The slot the layout kept spare, which is what lets this go in without moving a single number after it. A drawn
        // weapon is the one thing the agent does that takes many ticks to pay off, and nothing else in the observation says
        // how far along one is: the using flags say a bow is drawn but not whether letting go now would send an arrow or a
        // dart. A network that never pressed use reads zero here, exactly as it read the spare.
        out[at + 19] = echo.useProgress;
    }

    /**
     * Every body's enemy block: ten slots of eighteen, each an opponent or something shot at the agent, in the agent's own
     * frame. The block's shape is shared because an opponent looks the same whoever is looking at it; what a species
     * chooses is whether it has one and where it sits.
     *
     * @param block where this body's enemy block starts in the row, not where the row starts
     */
    static void writeEnemies(AgentMob agent, EnemySlots slots, float[] out, int block, float sin, float cos) {

        Vec3 eye = agent.getEyePosition();

        for (int slot = 0; slot < ObservationSchema.ENEMY_SLOTS; slot++) {

            Entity enemy = slots.occupant(slot);

            if (enemy == null) {

                // Left as zeroes, with the present flag off. The training side masks on that flag.
                continue;
            }

            int at = block + slot * ObservationSchema.ENEMY_STRIDE;

            Vec3 delta = enemy.getEyePosition().subtract(eye);
            double distance = Math.sqrt(delta.x * delta.x + delta.y * delta.y + delta.z * delta.z);

            out[at + ObservationSchema.ENEMY_PRESENT] = 1.0F;
            out[at + ObservationSchema.ENEMY_FORWARD] = (float) (forward(delta.x, delta.z, sin, cos) / ObservationSchema.VIEW_DISTANCE);
            out[at + ObservationSchema.ENEMY_UP] = (float) (delta.y / ObservationSchema.VIEW_DISTANCE);
            out[at + ObservationSchema.ENEMY_RIGHT] = (float) (right(delta.x, delta.z, sin, cos) / ObservationSchema.VIEW_DISTANCE);
            out[at + ObservationSchema.ENEMY_DISTANCE] = (float) (distance / ObservationSchema.VIEW_DISTANCE);

            Vec3 velocity = enemy.getDeltaMovement();
            out[at + ObservationSchema.ENEMY_VELOCITY_FORWARD] = (float) (forward(velocity.x, velocity.z, sin, cos) / VELOCITY_SCALE);
            out[at + ObservationSchema.ENEMY_VELOCITY_UP] = (float) (velocity.y / VELOCITY_SCALE);
            out[at + ObservationSchema.ENEMY_VELOCITY_RIGHT] = (float) (right(velocity.x, velocity.z, sin, cos) / VELOCITY_SCALE);

            // Where the enemy is looking relative to the line between us, so that facing away and facing straight at the
            // agent are told apart without the network having to work out world directions. An arrow has no head to turn,
            // and its own rotation is the way it is flying, which is exactly as useful in the same slot.
            float heading = enemy instanceof LivingEntity looking ? looking.getYHeadRot() : enemy.getYRot();
            float bearing = (float) Mth.atan2(-delta.x, delta.z);
            float facing = Mth.wrapDegrees(heading) * DEGREES_TO_RADIANS - bearing;
            out[at + ObservationSchema.ENEMY_FACING_SIN] = Mth.sin(facing);
            out[at + ObservationSchema.ENEMY_FACING_COS] = Mth.cos(facing);
            out[at + ObservationSchema.ENEMY_PITCH] = enemy.getXRot() / 90.0F;

            out[at + ObservationSchema.ENEMY_KIND] = entityKind(enemy);
            out[at + ObservationSchema.ENEMY_SPRINTING] = enemy.isSprinting() ? 1.0F : 0.0F;

            // How big it is, which every entity has, an arrow included: an arrow is thin and a ghast fills the sky.
            out[at + ObservationSchema.ENEMY_WIDTH] = enemy.getBbWidth() / ObservationSchema.SIZE_SCALE;
            out[at + ObservationSchema.ENEMY_HEIGHT] = enemy.getBbHeight() / ObservationSchema.SIZE_SCALE;

            // What only a body has. A projectile is left at zero for all of it: no health, no hands, no swing and nothing
            // in use, which is the plain truth about an arrow and is why its kind is off the ladder the bodies are on.
            if (enemy instanceof LivingEntity living) {

                out[at + ObservationSchema.ENEMY_HEALTH] = living.getHealth() / Math.max(1.0F, living.getMaxHealth());
                out[at + ObservationSchema.ENEMY_MAIN_HAND] = itemKind(living.getMainHandItem());
                out[at + ObservationSchema.ENEMY_OFF_HAND] = itemKind(living.getOffhandItem());
                out[at + ObservationSchema.ENEMY_SWINGING] = living.swinging ? 1.0F : 0.0F;
                out[at + ObservationSchema.ENEMY_USING] = living.isUsingItem() ? 1.0F : 0.0F;

                writeCapabilities(living, out, at);
            }
        }
    }

    /**
     * What the body in a slot can do: how much of it there is, how hard it hits, how fast it moves, how much knockback it
     * shrugs off, and whether it explodes, shoots or flies.
     *
     * <p>This is what tells a creeper from a zombie and a warden from either, which nothing in the layout did before: the
     * kind says "monster" for all three and the health is a fraction, so all three read the same at full health with empty
     * hands. Every number is the mob's own, from its attributes and its class, so a mob the run never met still describes
     * itself and a network has capabilities to condition on rather than two hundred opponents to memorise.
     *
     * <p>The scale is deliberately absolute. A fraction of health says how nearly dead something is; hearts say whether it
     * can be killed before it kills you, and that is the question the tactics turn on.
     */
    private static void writeCapabilities(LivingEntity living, float[] out, int at) {

        out[at + ObservationSchema.ENEMY_MAX_HEALTH] = living.getMaxHealth() / ObservationSchema.HEALTH_SCALE;
        out[at + ObservationSchema.ENEMY_HEALTH_LEFT] = living.getHealth() / ObservationSchema.HEALTH_SCALE;
        out[at + ObservationSchema.ENEMY_DAMAGE] = attribute(living, Attributes.ATTACK_DAMAGE) / ObservationSchema.DAMAGE_SCALE;
        out[at + ObservationSchema.ENEMY_SPEED] = attribute(living, Attributes.MOVEMENT_SPEED) / ObservationSchema.SPEED_SCALE;
        out[at + ObservationSchema.ENEMY_KNOCKBACK_RESISTANCE] = attribute(living, Attributes.KNOCKBACK_RESISTANCE);

        if (living instanceof Creeper creeper) {

            out[at + ObservationSchema.ENEMY_EXPLODES] = 1.0F;
            out[at + ObservationSchema.ENEMY_FUSE] = creeper.getSwelling(1.0F);
        }

        out[at + ObservationSchema.ENEMY_SHOOTS] = shoots(living) ? 1.0F : 0.0F;
        out[at + ObservationSchema.ENEMY_FLIES] = flies(living) ? 1.0F : 0.0F;
    }

    /** An attribute's value, or zero where the mob has no such attribute at all, which asking for it outright would throw on. */
    private static float attribute(LivingEntity living, Holder<Attribute> attribute) {

        return living.getAttributes().hasAttribute(attribute) ? (float) living.getAttributeValue(attribute) : 0.0F;
    }

    /**
     * Whether it attacks from range. {@link RangedAttackMob} covers everything that draws or throws; the rest are named
     * because they shoot through goals of their own rather than through that interface.
     */
    private static boolean shoots(LivingEntity living) {

        return living instanceof RangedAttackMob || living instanceof Ghast || living instanceof Blaze
                || living instanceof Shulker || living instanceof Breeze || living instanceof WitherBoss;
    }

    /** Whether it is in the air by nature, which decides whether a sword can reach it at all. */
    private static boolean flies(LivingEntity living) {

        return living instanceof FlyingMob || living instanceof Bee || living instanceof Vex
                || living instanceof Allay || living instanceof Bat || living instanceof Parrot;
    }

    /**
     * Every body's terrain grid: the blocks around the agent, aligned to the world rather than to the aim. Shared, because
     * ground is ground whoever is standing on it, and two bodies disagreeing about what a hazard is would be a bug nobody
     * could see. {@code block} is where this body's grid starts in the row, not where the row starts.
     *
     * <p>The single most expensive thing an agent does, so it is written to keep the work down rather than to read nicely.
     *
     * <p>Going through the level for each block would resolve the chunk four hundred times over. Instead the loop walks
     * one vertical column at a time and resolves the chunk once per column, which is eighty one lookups, and holds onto
     * the last one because a nine wide box spans at most two chunks in each direction.
     *
     * <p>A cell is {@link #HAZARD} when it hurts or kills a body in it or on it, {@link #SOLID} when something in it stops a
     * body, {@link #FLUID} when it holds water and nothing solid, and empty otherwise. Grass, flowers and anything else a body walks through are empty: counted as
     * solid, as they once were, every meadow read as a wall at foot height all the way round, and a river as solid
     * ground, and neither a step that needs a jump nor water that needs swimming could be told from them.
     */
    /**
     * Eight rays out from the feet, one every forty five degrees, each saying how far it is to a wall, to something that
     * hurts, and to a drop. A fraction of {@link ObservationSchema#RAY_REACH} in each case, and 1 where the ray reached the
     * end without finding one.
     *
     * <p>This is the agent's only sight of ground beyond the grid's four blocks, and the grid is why it is needed: nine
     * cells of one block reach four, so a lava lake five blocks away, the lip of a ravine, and a wall at its back are all
     * outside anything the body can see. Widening the grid to reach the same distance would be 1,445 cells against 405 and
     * three and a half times the scan; this is twenty four numbers and about a sixth more.
     *
     * <p>Sampled every {@link ObservationSchema#RAY_STEP} blocks rather than every block, which is the resolution that
     * matters at this range: a lake is not two blocks wide, and what a fighter needs is which way it lies, not its outline.
     * Each sample is one heightmap lookup and at most one block read, and the ray stops at the first wall, because nothing
     * beyond a wall can be walked to or pushed into.
     */
    static void writeRays(AgentMob agent, float[] out, int block) {

        Level level = agent.level();
        BlockPos feet = agent.blockPosition();
        int reach = ObservationSchema.RAY_REACH;

        for (int ray = 0; ray < ObservationSchema.RAYS; ray++) {

            double angle = ray * (Math.PI * 2.0D / ObservationSchema.RAYS);
            int stepX = (int) Math.round(Math.cos(angle));
            int stepZ = (int) Math.round(Math.sin(angle));

            int at = block + ray * ObservationSchema.RAY_STRIDE;
            float wall = 1.0F;
            float hazard = 1.0F;
            float drop = 1.0F;

            for (int away = ObservationSchema.RAY_STEP; away <= reach;
                    away += away < ObservationSchema.RAY_FINE ? ObservationSchema.RAY_STEP : ObservationSchema.RAY_COARSE_STEP) {

                int worldX = feet.getX() + stepX * away;
                int worldZ = feet.getZ() + stepZ * away;
                float fraction = away / (float) reach;

                // The ground the ray is over, and the block a body would be standing in there.
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ);
                BlockPos standing = new BlockPos(worldX, top, worldZ);

                if (hazard >= 1.0F && (hazard(level.getBlockState(standing)) || hazard(level.getBlockState(standing.below())))) {

                    hazard = fraction;
                }

                if (drop >= 1.0F && feet.getY() - top >= ObservationSchema.RAY_DROP_DEPTH) {

                    drop = fraction;
                }

                // A wall is ground standing well above the feet, which is also where the ray stops: what is behind a wall
                // is neither somewhere to walk nor somewhere to push anything.
                if (top - feet.getY() >= 2) {

                    wall = fraction;
                    break;
                }
            }

            out[at + ObservationSchema.RAY_WALL] = wall;
            out[at + ObservationSchema.RAY_HAZARD] = hazard;
            out[at + ObservationSchema.RAY_DROP] = drop;
        }
    }

    static void writeTerrain(AgentMob agent, float[] out, int block) {

        Level level = agent.level();
        BlockPos feet = agent.blockPosition();

        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();

        int cachedChunkX = Integer.MIN_VALUE;
        int cachedChunkZ = Integer.MIN_VALUE;
        ChunkAccess chunk = null;

        for (int z = 0; z < ObservationSchema.TERRAIN_Z; z++) {

            int worldZ = feet.getZ() + z - ObservationSchema.TERRAIN_RADIUS_XZ;

            for (int x = 0; x < ObservationSchema.TERRAIN_X; x++) {

                int worldX = feet.getX() + x - ObservationSchema.TERRAIN_RADIUS_XZ;
                int chunkX = worldX >> 4;
                int chunkZ = worldZ >> 4;

                if (chunkX != cachedChunkX || chunkZ != cachedChunkZ) {

                    // Never generates: an arena that has not been loaded reads as solid, which is the safe way for an
                    // agent to be wrong about a place it cannot go anyway.
                    chunk = level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
                    cachedChunkX = chunkX;
                    cachedChunkZ = chunkZ;
                }

                for (int y = 0; y < ObservationSchema.TERRAIN_Y; y++) {

                    int worldY = feet.getY() + y - ObservationSchema.TERRAIN_RADIUS_Y;
                    float cell = SOLID;

                    if (chunk != null && worldY >= minY && worldY < maxY) {

                        SCRATCH.set(worldX, worldY, worldZ);
                        cell = cell(chunk, chunk.getBlockState(SCRATCH));

                        if (y == 0 && cell == EMPTY) {

                            cell = drop(chunk, worldX, worldY, worldZ, minY);
                        }
                    }

                    out[block + ObservationSchema.gridOffset(x, y, z)] = cell;
                }
            }
        }
    }

    /**
     * What one terrain cell holds, as {@link #writeTerrain} describes. Both lookups are cached by the block state.
     *
     * <p>Some blocks hurt or kill a body that goes into them or stands on them:
     * <ul>
     *   <li>lava and fire burn;</li>
     *   <li>magma blocks, cactus, lit campfires, wither roses and pointed dripstone hurt;</li>
     *   <li>powder snow swallows a body and freezes it;</li>
     *   <li>a sweet berry bush or a cobweb holds it nearly still, and the bush tears at it.</li>
     * </ul>
     * Read as empty, they looked like open ground, and agents walked into them and died. Read as solid, as they were next,
     * they stopped anyone walking into them sideways, but the top of a lava lake looked exactly like stone to stand on: the
     * scripted fighter walked out onto lava, and the network copied it. So they read as {@link #HAZARD}: still nowhere a
     * body can go, as solid is, and now also nowhere to stand.
     */
    private static float cell(ChunkAccess chunk, BlockState state) {

        byte known = CELLS.getByte(state);

        if (known != UNKNOWN) {

            return VALUES[known];
        }

        float cell = classify(chunk, state);

        // A block whose shape is the same wherever it stands, which vanilla already keeps one of per state, reads the same
        // everywhere too; one whose shape depends on where it stands is worked out every time, as before.
        if (!state.getBlock().hasDynamicShape()) {

            CELLS.put(state, code(cell));
        }

        return cell;
    }

    /** What a cell holds, worked out from scratch: see {@link #cell}. */
    private static float classify(ChunkAccess chunk, BlockState state) {

        if (hazard(state)) {

            return HAZARD;
        }

        if (!state.getCollisionShape(chunk, SCRATCH).isEmpty()) {

            return SOLID;
        }

        FluidState fluid = state.getFluidState();

        return fluid.isEmpty() ? EMPTY : FLUID;
    }

    /**
     * What {@link #cell} makes of each block state, remembered, since the answer only depends on the state for all but
     * the few blocks whose shape changes with where they stand: four hundred cells an agent a tick asked the lava tag and
     * the collision shape every time, which was more than any other part of the observation. Only ever read and written
     * on the server thread, as the rest of this class is.
     */
    private static final it.unimi.dsi.fastutil.objects.Reference2ByteOpenHashMap<BlockState> CELLS = new it.unimi.dsi.fastutil.objects.Reference2ByteOpenHashMap<>();

    private static final byte UNKNOWN = -1;
    private static final float[] VALUES = {EMPTY, FLUID, SOLID, HAZARD};

    static {

        CELLS.defaultReturnValue(UNKNOWN);
    }

    private static byte code(float cell) {

        return cell == EMPTY ? 0 : cell == FLUID ? (byte) 1 : cell == SOLID ? (byte) 2 : (byte) 3;
    }

    /**
     * How far below the bottom of the grid a fall still lands on something, before it reads as a {@link #HAZARD}. The
     * bottom of the grid is two blocks under the feet, so an empty cell there only says that stepping over it drops three
     * blocks or more. How much more is what matters: agents walked off the edges of ravines whose bottom they could not
     * see, and died of the fall. Ground found within this many blocks under that cell is a fall of at most eight, five
     * health of twenty; deeper, or into lava or powder snow, is a hazard. Water breaks any fall, so it lands safely.
     */
    private static final int DROP_SCAN = 7;

    /** What an empty cell at the bottom of the grid hides below it: see {@link #DROP_SCAN}. */
    private static float drop(ChunkAccess chunk, int worldX, int worldY, int worldZ, int minY) {

        for (int below = 1; below <= DROP_SCAN; below++) {

            if (worldY - below < minY) {

                return HAZARD;
            }

            SCRATCH.set(worldX, worldY - below, worldZ);

            // The same three questions as a cell, in the same order: a hazard ends the fall badly, anything a body lands
            // on or in ends it well, and open air goes on down.
            float cell = cell(chunk, chunk.getBlockState(SCRATCH));

            if (cell == HAZARD) {

                return HAZARD;
            }

            if (cell != EMPTY) {

                return EMPTY;
            }
        }

        return HAZARD;
    }

    /**
     * Whether a block hurts or kills a body in it or on it, which is what {@link #HAZARD} means. Public because anything
     * that reasons about the ground the agent fights on has to mean the same by it as the agent does: a block one counts
     * and the other does not is ground the agent refuses to use and the other promises. The game test side's
     * {@code SiteHazards} asks this.
     */
    public static boolean hazard(BlockState state) {

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

    // -----------------------------------------------------------------------------------------------------------

    /** The component of a world space horizontal delta along the way the agent is looking. Any body's self block wants it. */
    static double forward(double dx, double dz, float sin, float cos) {

        return dx * -sin + dz * cos;
    }

    /** The component to the agent's right. */
    static double right(double dx, double dz, float sin, float cos) {

        return dx * -cos + dz * -sin;
    }

    /**
     * What a hotbar slot or a hand reads as: a coarse category rather than an item id, because an id is a number with no
     * order to it and the loadout is fixed anyway. This only has to be fine enough to tell a weapon from a shield from
     * something to eat.
     *
     * <p>A bow and a crossbow share one category, since both are a drawn ranged weapon and one more category would buy a
     * distinction the body already makes: a crossbow answers a release by loading and a bow by firing, which is what
     * {@link net.sievert.modularmobai.brain.ScriptedBrain} tells them apart by. Naming the categories rather than
     * returning the fractions in place is what lets anything reading the observation ask what a slot holds without
     * writing the same eighths down a second time.
     */
    public static final float ITEM_NONE = 0.0F;
    public static final float ITEM_SWORD = 1.0F / 8.0F;
    public static final float ITEM_AXE = 2.0F / 8.0F;
    public static final float ITEM_SHIELD = 3.0F / 8.0F;
    public static final float ITEM_RANGED = 4.0F / 8.0F;
    public static final float ITEM_FOOD = 5.0F / 8.0F;
    public static final float ITEM_BLOCK = 6.0F / 8.0F;
    public static final float ITEM_OTHER = 7.0F / 8.0F;

    /** Half the gap between two categories, which is how close a reading has to be to count as one of them. */
    public static final float ITEM_TOLERANCE = 1.0F / 16.0F;

    /** Whether a hotbar slot or a hand reads as one particular category. */
    public static boolean isItem(float reading, float kind) {

        return Math.abs(reading - kind) < ITEM_TOLERANCE;
    }

    private static float itemKind(ItemStack stack) {

        if (stack.isEmpty()) {

            return ITEM_NONE;
        }

        Item item = stack.getItem();

        if (item instanceof SwordItem) {

            return ITEM_SWORD;
        }

        if (item instanceof AxeItem) {

            return ITEM_AXE;
        }

        if (item instanceof ShieldItem) {

            return ITEM_SHIELD;
        }

        if (item instanceof BowItem || item instanceof CrossbowItem) {

            return ITEM_RANGED;
        }

        if (stack.has(DataComponents.FOOD)) {

            return ITEM_FOOD;
        }

        if (item instanceof BlockItem) {

            return ITEM_BLOCK;
        }

        return ITEM_OTHER;
    }

    /**
     * Something shot at the agent, which is the one thing in an enemy slot that is not a body. Below zero rather than
     * anywhere on the ladder the bodies share, because it is not more or less of anything they are: half the numbers in
     * the slot mean nothing for an arrow, and a network that treated one as a very weak monster would walk up and try to
     * swing at it. Every body's own kind is at least half a unit away from this, and a slot with nothing in it is all
     * zeroes with the present flag off, so there is no reading this by accident either.
     */
    public static final float KIND_PROJECTILE = -0.25F;

    public static final float KIND_AGENT = 0.25F;
    public static final float KIND_PLAYER = 0.5F;
    public static final float KIND_MONSTER = 0.75F;
    public static final float KIND_OTHER = 1.0F;

    /** Whether an occupied enemy slot holds something shot rather than a body, which is the whole of what below zero means. */
    public static boolean isProjectileKind(float kind) {

        return kind < 0.0F;
    }

    private static float entityKind(Entity entity) {

        if (entity instanceof Projectile) {

            return KIND_PROJECTILE;
        }

        if (entity instanceof AgentMob) {

            return KIND_AGENT;
        }

        if (entity instanceof Player) {

            return KIND_PLAYER;
        }

        return entity instanceof Enemy ? KIND_MONSTER : KIND_OTHER;
    }
}
