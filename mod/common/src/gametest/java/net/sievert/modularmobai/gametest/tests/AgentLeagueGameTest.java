package net.sievert.modularmobai.gametest.tests;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.sievert.modularmobai.arena.Episode;
import net.sievert.modularmobai.entity.ModEntities;
import net.sievert.modularmobai.entity.agent.AgentMob;
import net.sievert.modularmobai.gametest.Evaluation;
import net.sievert.modularmobai.gametest.GameTestGroup;
import net.sievert.modularmobai.gametest.GameTestTuning;
import net.sievert.modularmobai.gametest.RepeatGameTest;
import net.sievert.modularmobai.gametest.league.League;
import net.sievert.modularmobai.gametest.replay.FightRecorder;
import net.sievert.modularmobai.gametest.terrain.TerrainSites;
import net.sievert.modularmobai.gametest.util.TestDurationStats;

/**
 * An agent against the league, one on one, on natural ground: every fight a different opponent, drawn by {@link League},
 * and a different loadout. The opponent is a mob, set up and kept fighting by
 * {@link net.sievert.modularmobai.gametest.league.Roster}, or another agent: the scripted fighter, or a frozen checkpoint
 * of the network being trained, playing its most likely action with nothing recorded.
 *
 * <p>Otherwise this is {@link AgentVindicatorTerrainGameTest}: the same sites, the same minute, the same slots working
 * through one shared queue of fights, the same reward. What winning means is the one other difference. A fight is won
 * when the opponent's health is gone and the agent still stands, however it went, off a cliff or on the agent's sword.
 * An opponent that goes without its health gone, a creeper that blew itself up, is not beaten: the fight is a draw,
 * which pays what a loss on time does, since the agent did not win it.
 */
@GameTestGroup
public class AgentLeagueGameTest {

    /** A minute of game time. */
    private static final int FIGHT_TICKS = 1200;

    /** The framework's own limit on a whole slot, only there to catch a slot that has stopped working. */
    private static final int SLOT_TIMEOUT_TICKS = 50_000_000;

    private static final TestDurationStats TIME_TO_RESOLVE =
            new TestDurationStats("League fight length", GameTestTuning.arenasInShard(GameTestTuning.arenaCount()));

    @GameTest(template = "arena", timeoutTicks = SLOT_TIMEOUT_TICKS)
    @RepeatGameTest(slots = true)
    public static void agentFightsTheLeague(GameTestHelper helper, int slot) {

        final Slot fights = new Slot(helper);

        // Called every tick until it stops throwing, which makes it the slot's heartbeat as well as its finish line.
        helper.succeedWhen(() -> {

            fights.tick();

            if (!fights.done()) {

                throw new GameTestAssertException("Still fighting");
            }
        });
    }

    /** One slot: set a fight up, keep the opponent on the agent, wait for it to end, hand the ending over, and go again. */
    private static final class Slot {

        private enum Phase {

            IDLE,
            FIGHTING,

            /** The fight is decided; waiting for the agent's last step, which carries how it ended, to go out. */
            ENDING,

            DONE
        }

        private final GameTestHelper helper;
        private final ServerLevel level;

        private Phase phase = Phase.IDLE;
        private boolean holding;

        private TerrainSites.Site site;
        private League.Matchup matchup;
        private AgentMob agent;
        private LivingEntity opponent;
        private Episode episode;
        private long started;

        /** Whether the opponent hurt the agent, and went for it, at any point in the fight. */
        private boolean landed;
        private boolean targeted;

        /** Whether the agent hurt the opponent at any point, which with the one above says whether they ever met. */
        private boolean struck;

        /**
         * Whether the fight just decided ran out the clock against a mob with the two never having touched each other,
         * which says the ground kept them apart and is what the site is told. A league opponent that keeps its distance
         * runs the clock out on any ground, and two agents that have not learned to fight yet can wander about for the
         * whole minute without meeting; the site is not to blame for either.
         */
        private boolean stuck;

        private FightRecorder replay;

        private Slot(GameTestHelper helper) {

            this.helper = helper;
            this.level = helper.getLevel();

            League.prepareWorld(this.level);
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

                    if (this.opponent instanceof Mob mob && this.matchup.mob() != null && mob.isAlive() && this.agent.isAlive()) {

                        // Asked before the target is made the agent again, so it says whether the mob's own mind kept it
                        // through a tick of its own.
                        this.targeted |= mob.getTarget() == this.agent;
                        this.matchup.mob().provoke(mob, this.agent);
                    }

                    this.landed |= this.agent.getLastHurtByMob() == this.opponent;
                    this.struck |= this.opponent.getLastHurtByMob() == this.agent;

                    if (!this.agent.isAlive() || !this.opponent.isAlive() || this.helper.getTick() - this.started >= FIGHT_TICKS) {

                        this.decide();
                        this.phase = Phase.ENDING;
                    }
                }

                case ENDING -> {

                    if (this.agent.brain().isFinished() || this.agent.isRemoved()) {

                        TerrainSites.release(this.level, this.site, this.stuck, this.agent, this.opponent);
                        this.phase = Phase.IDLE;
                    }
                }

                case DONE -> {
                }
            }
        }

        private Phase begin() {

            float agentYaw = yawTowards(this.site.agent(), this.site.opponent()) + Mth.nextFloat(this.level.getRandom(), -45.0F, 45.0F);
            float opponentYaw = yawTowards(this.site.opponent(), this.site.agent());

            // Handed its brain before the driver ever steps it, so the training brain never sees this agent at all when it
            // is an evaluation, and never sees the opponent either way: only the agent's own fights are learned from.
            Evaluation.Assignment evaluation = Evaluation.next();

            this.matchup = League.next(evaluation, this.level.getRandom());
            this.agent = ModEntities.trainingAgent().create(this.level);
            this.opponent = this.matchup.mob() != null ? this.matchup.mob().type().create(this.level) : ModEntities.trainingAgent().create(this.level);

            if (this.agent == null || this.opponent == null) {

                throw new IllegalStateException("Could not create the fighters for " + this.matchup.opponent());
            }

            place(this.agent, this.site.agent(), agentYaw);
            place(this.opponent, this.site.opponent(), opponentYaw);

            if (this.opponent instanceof Mob mob && this.matchup.mob() != null) {

                // What spawning on its own would do, including handing it what it fights with, and then what a fair fight
                // needs on top, see Roster.
                mob.finalizeSpawn(this.level, this.level.getCurrentDifficultyAt(this.site.opponent()), MobSpawnType.EVENT,
                        this.matchup.mob().groupData());
                this.matchup.mob().prepare(mob, this.level.getRandom());
            }

            this.level.addFreshEntity(this.agent);
            this.level.addFreshEntity(this.opponent);

            this.matchup.loadout().equip(this.agent);
            this.agent.startEpisode(new Episode(FIGHT_TICKS, this.site.bounds(), this.opponent));

            if (evaluation != null) {

                this.agent.brain().use(evaluation.brain());
            }

            if (this.opponent instanceof AgentMob other) {

                // Its own fight, bounded the same, against the agent; what it is paid goes nowhere, since nothing records it.
                this.matchup.opponentLoadout().equip(other);
                other.startEpisode(new Episode(FIGHT_TICKS, this.site.bounds(), this.agent));
                other.brain().use(this.matchup.brain());
            }

            else if (this.opponent instanceof Mob mob) {

                this.matchup.mob().provoke(mob, this.agent);
            }

            this.episode = this.agent.episode();
            this.started = this.helper.getTick();
            this.landed = false;
            this.targeted = this.opponent instanceof AgentMob;
            this.struck = false;
            this.replay = FightRecorder.start(this.agent, this.opponent);

            return Phase.FIGHTING;
        }

        private void decide() {

            boolean standing = this.agent.isAlive();
            boolean won = standing && this.opponent.isDeadOrDying();
            boolean timedOut = standing && this.opponent.isAlive();

            String outcome = won ? "win" : !standing ? "loss" : timedOut ? "timeout" : "draw";

            this.stuck = timedOut && !this.landed && !this.struck && this.matchup.mob() != null;

            if (won) {

                this.episode.reward().won();
            }

            else if (standing) {

                this.episode.reward().lost();
            }

            TIME_TO_RESOLVE.record(this.helper.getTick() - this.started,
                    won ? TestDurationStats.Outcome.WIN : TestDurationStats.Outcome.LOSS, this.helper.getTick());

            League.record(this.matchup, outcome, this.helper.getTick() - this.started, this.landed, this.targeted);

            if (this.replay != null) {

                this.replay.finish(won ? FightRecorder.Outcome.WIN : !standing ? FightRecorder.Outcome.LOSS
                        : timedOut ? FightRecorder.Outcome.TIMEOUT : FightRecorder.Outcome.DRAW);
                this.replay = null;
            }
        }
    }

    private static void place(LivingEntity fighter, BlockPos feet, float yaw) {

        fighter.moveTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, yaw, 0.0F);
        fighter.setYHeadRot(yaw);
        fighter.setYBodyRot(yaw);

        if (fighter instanceof Mob mob) {

            mob.setPersistenceRequired();
        }

        fighter.addTag(TerrainSites.TAG);
    }

    /** The yaw that looks from one block to another, in Minecraft's convention where zero faces south. */
    private static float yawTowards(BlockPos from, BlockPos to) {

        return (float) (Mth.atan2(to.getZ() - from.getZ(), to.getX() - from.getX()) * Mth.RAD_TO_DEG) - 90.0F;
    }
}
