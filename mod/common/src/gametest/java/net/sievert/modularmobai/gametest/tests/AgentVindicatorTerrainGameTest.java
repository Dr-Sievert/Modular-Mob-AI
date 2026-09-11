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
import net.sievert.modularmobai.gametest.replay.FightRecorder;
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
 *
 * <p>The framework runs its tests in batches and starts a batch only when the last test of the one before has finished,
 * so one fight that runs the clock out holds forty nine finished slots empty for most of a minute. Fights here are not
 * tests. Each test is a slot that fights one battle after another, taking the next from a queue the whole process
 * shares, and the slots only stop when the queue is empty. Every slot stays busy until the very end, where the last few
 * fights run out at most their minute.
 */
@GameTestGroup
public class AgentVindicatorTerrainGameTest {

    /** A minute of game time. */
    private static final int FIGHT_TICKS = 1200;

    /**
     * The framework's own limit on a whole slot, which fights as many battles as the queue has for it. Only there to
     * catch a slot that has stopped working; a fight has its own minute.
     */
    private static final int SLOT_TIMEOUT_TICKS = 50_000_000;

    private static final TestDurationStats TIME_TO_RESOLVE =
            new TestDurationStats("Terrain fight length", GameTestTuning.arenasInShard(GameTestTuning.arenaCount()));

    @GameTest(template = "arena", timeoutTicks = SLOT_TIMEOUT_TICKS)
    @RepeatGameTest(slots = true)
    public static void agentFightsVindicatorOnTerrain(GameTestHelper helper, int slot) {

        final Slot fights = new Slot(helper);

        // Called every tick until it stops throwing, which makes it the slot's heartbeat as well as its finish line.
        helper.succeedWhen(() -> {

            fights.tick();

            if (!fights.done()) {

                throw new GameTestAssertException("Still fighting");
            }
        });
    }

    /** One slot: set a fight up, wait for it to end, hand the ending over, clean up, and go again. */
    private static final class Slot {

        private enum Phase {

            /** Between fights: take the next one from the queue, or finish if there is none. */
            IDLE,

            FIGHTING,

            /** The fight is decided; waiting for the agent's last step, which carries how it ended, to go out. */
            ENDING,

            DONE
        }

        private final GameTestHelper helper;
        private final ServerLevel level;

        private Phase phase = Phase.IDLE;

        /** A fight taken from the queue that is still waiting for a site to be free. */
        private boolean holding;

        private TerrainSites.Site site;
        private AgentMob agent;
        private Vindicator vindicator;
        private Episode episode;
        private long started;

        /** The fight being written down for watching later, or null when this one is not. */
        private FightRecorder replay;

        private Slot(GameTestHelper helper) {

            this.helper = helper;
            this.level = helper.getLevel();
        }

        private boolean done() {

            return this.phase == Phase.DONE;
        }

        private void tick() {

            switch (this.phase) {

                case IDLE -> {

                    if (!this.holding && !TerrainSites.takeFight()) {

                        this.phase = Phase.DONE;
                        return;
                    }

                    // Every usable site can be busy for a moment when much of the lattice is water; the fight waits.
                    this.site = TerrainSites.claim(this.level);
                    this.holding = this.site == null;

                    if (!this.holding) {

                        this.phase = this.begin();
                    }
                }

                case FIGHTING -> {

                    if (this.replay != null) {

                        this.replay.tick();
                    }

                    if (!this.agent.isAlive() || !this.vindicator.isAlive() || this.helper.getTick() - this.started >= FIGHT_TICKS) {

                        this.decide();
                        this.phase = Phase.ENDING;
                    }
                }

                case ENDING -> {

                    // The ending goes out at the start of the next tick; the site is only cleaned once it has, or the
                    // brain would never hear how the fight ended.
                    if (this.agent.brain().isFinished() || this.agent.isRemoved()) {

                        TerrainSites.release(this.level, this.site, this.agent, this.vindicator);
                        this.phase = Phase.IDLE;
                    }
                }

                case DONE -> {
                }
            }
        }

        private Phase begin() {

            // Facing roughly towards each other, as two fighters who have just noticed one another would.
            float agentYaw = yawTowards(this.site.agent(), this.site.opponent()) + Mth.nextFloat(this.level.getRandom(), -45.0F, 45.0F);
            float vindicatorYaw = yawTowards(this.site.opponent(), this.site.agent());

            this.agent = ModEntities.trainingAgent().create(this.level);
            this.vindicator = EntityType.VINDICATOR.create(this.level);

            if (this.agent == null || this.vindicator == null) {

                throw new IllegalStateException("Could not create the fighters");
            }

            place(this.agent, this.site.agent(), agentYaw);
            place(this.vindicator, this.site.opponent(), vindicatorYaw);

            // What spawning on its own would do, including handing it the iron axe it is supposed to carry.
            this.vindicator.finalizeSpawn(this.level, this.level.getCurrentDifficultyAt(this.site.opponent()), MobSpawnType.EVENT, null);

            this.level.addFreshEntity(this.agent);
            this.level.addFreshEntity(this.vindicator);

            this.vindicator.setTarget(this.agent);

            this.agent.setHotbarItem(0, new ItemStack(Items.IRON_SWORD));
            this.agent.startEpisode(new Episode(FIGHT_TICKS, this.site.bounds(), this.vindicator));

            this.episode = this.agent.episode();
            this.started = this.helper.getTick();
            this.replay = FightRecorder.start(this.agent, this.vindicator);

            return Phase.FIGHTING;
        }

        /**
         * Only the arena knows what winning meant. An agent that died has already reported its own loss; one still
         * standing next to a live opponent ran out of time, which is the other way to lose. A vindicator the terrain
         * killed, off a cliff or in lava, still counts: the agent got it there.
         */
        private void decide() {

            boolean won = !this.vindicator.isAlive() && this.agent.isAlive();

            if (won) {

                this.episode.reward().won();
            }

            else if (this.agent.isAlive()) {

                this.episode.reward().lost();
            }

            // Every slot started in the same batch, so any slot's own tick count is the server's since then.
            TIME_TO_RESOLVE.record(this.helper.getTick() - this.started,
                    won ? TestDurationStats.Outcome.WIN : TestDurationStats.Outcome.LOSS, this.helper.getTick());

            // After the reward above, so the replay's last tick carries what the ending paid.
            if (this.replay != null) {

                this.replay.finish(won ? FightRecorder.Outcome.WIN
                        : this.agent.isAlive() ? FightRecorder.Outcome.TIMEOUT : FightRecorder.Outcome.LOSS);
                this.replay = null;
            }
        }
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
