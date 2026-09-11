package net.sievert.modularmobai.gametest.tests;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Vindicator;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.sievert.modularmobai.arena.Episode;
import net.sievert.modularmobai.entity.ModEntities;
import net.sievert.modularmobai.entity.agent.AgentMob;
import net.sievert.modularmobai.gametest.GameTestGroup;
import net.sievert.modularmobai.gametest.GameTestTuning;
import net.sievert.modularmobai.gametest.RepeatGameTest;
import net.sievert.modularmobai.gametest.terrain.TerrainSites;
import net.sievert.modularmobai.gametest.util.TestDurationStats;

/**
 * An agent against a vindicator, one on one, on natural ground: hills, trees, water, whatever the world generator put
 * there.
 *
 * <p>Every fight gets its own patch of land from {@link TerrainSites}, somewhere different each time. The framework's plot
 * for the test is only bookkeeping, far underground; nothing happens in it.
 *
 * <p>A fight lasts until one of them is dead or a minute has passed, which is a loss. What the agent is paid only counts
 * damage done to the vindicator, so hitting anything else the terrain throws up earns nothing.
 */
@GameTestGroup
public class AgentVindicatorTerrainGameTest {

    /** A minute of game time. */
    private static final int FIGHT_TICKS = 1200;

    /**
     * The framework's own limit, deliberately later than the fight's, with room for the last step to be sent and the site
     * to be cleaned. If this ever fires, something is wrong with the arena rather than with the fight.
     */
    private static final int TIMEOUT_TICKS = FIGHT_TICKS + 60;

    private static final TestDurationStats TIME_TO_RESOLVE =
            new TestDurationStats("Terrain fight length", GameTestTuning.arenasInShard(GameTestTuning.arenaCount()));

    @GameTest(template = "arena", timeoutTicks = TIMEOUT_TICKS)
    @RepeatGameTest
    public static void agentFightsVindicatorOnTerrain(GameTestHelper helper, int arena) {

        final ServerLevel level = helper.getLevel();
        final TerrainSites.Site site = TerrainSites.claim(level);

        // Facing roughly towards each other, as two fighters who have just noticed one another would.
        final float agentYaw = yawTowards(site.agent(), site.opponent()) + Mth.nextFloat(level.getRandom(), -45.0F, 45.0F);
        final float vindicatorYaw = yawTowards(site.opponent(), site.agent());

        final AgentMob agent = ModEntities.trainingAgent().create(level);
        final Vindicator vindicator = EntityType.VINDICATOR.create(level);

        if (agent == null || vindicator == null) {

            throw new GameTestAssertException("Could not create the fighters");
        }

        place(agent, site.agent(), agentYaw);
        place(vindicator, site.opponent(), vindicatorYaw);

        // What spawning on its own would do, including handing it the iron axe it is supposed to carry.
        vindicator.finalizeSpawn(level, level.getCurrentDifficultyAt(site.opponent()), MobSpawnType.EVENT, null);

        level.addFreshEntity(agent);
        level.addFreshEntity(vindicator);

        vindicator.setTarget(agent);

        agent.setHotbarItem(0, new ItemStack(Items.IRON_SWORD));
        agent.startEpisode(new Episode(FIGHT_TICKS, site.bounds(), vindicator));

        final Episode episode = agent.episode();
        final long started = helper.getTick();

        helper.startSequence()
                .thenWaitUntil(() -> {

                    if (agent.isAlive() && vindicator.isAlive() && helper.getTick() - started < FIGHT_TICKS) {

                        throw new GameTestAssertException("Fight still going");
                    }
                })
                .thenExecute(() -> {

                    // Only the arena knows what winning meant. An agent that died has already reported its own loss; one
                    // still standing next to a live opponent ran out of time, which is the other way to lose. A
                    // vindicator the terrain killed, off a cliff or in lava, still counts: the agent got it there.
                    final boolean won = !vindicator.isAlive() && agent.isAlive();

                    if (won) {

                        episode.reward().won();
                    }

                    else if (agent.isAlive()) {

                        episode.reward().lost();
                    }

                    TIME_TO_RESOLVE.record(helper.getTick() - started,
                            won ? TestDurationStats.Outcome.WIN : TestDurationStats.Outcome.LOSS);
                })
                // The agent's last step, the one that carries how the fight ended, goes out at the start of the next tick.
                // The site is only cleaned once it has, or that ending would never reach the brain.
                .thenWaitUntil(() -> {

                    if (!agent.brain().isFinished() && !agent.isRemoved()) {

                        throw new GameTestAssertException("Last step not sent yet");
                    }
                })
                .thenExecute(() -> TerrainSites.release(level, site, agent, vindicator))
                .thenSucceed();
    }

    private static void place(Mob mob, BlockPos feet, float yaw) {

        mob.moveTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, yaw, 0.0F);
        mob.setYHeadRot(yaw);
        mob.setYBodyRot(yaw);
        mob.setPersistenceRequired();
        mob.addTag(TerrainSites.TAG);
    }

    /** The yaw that looks from one block to another, in Minecraft's convention where zero faces south. */
    private static float yawTowards(BlockPos from, BlockPos to) {

        return (float) (Mth.atan2(to.getZ() - from.getZ(), to.getX() - from.getX()) * Mth.RAD_TO_DEG) - 90.0F;
    }
}
