package net.sievert.modularmobai.gametest.tests;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.scores.PlayerTeam;
import net.sievert.modularmobai.allegiance.Allegiance;
import net.sievert.modularmobai.arena.Episode;
import net.sievert.modularmobai.entity.ModEntities;
import net.sievert.modularmobai.entity.agent.AgentMob;
import net.sievert.modularmobai.gametest.Evaluation;
import net.sievert.modularmobai.gametest.GameTestGroup;
import net.sievert.modularmobai.gametest.GameTestTuning;
import net.sievert.modularmobai.gametest.RepeatGameTest;
import net.sievert.modularmobai.gametest.league.League;
import net.sievert.modularmobai.gametest.league.Opposition;
import net.sievert.modularmobai.gametest.league.Roster;
import net.sievert.modularmobai.gametest.replay.FightRecorder;
import net.sievert.modularmobai.gametest.terrain.TerrainSites;
import net.sievert.modularmobai.gametest.util.DeathCauses;
import net.sievert.modularmobai.gametest.util.TestDurationStats;

/**
 * An agent against the league on natural ground: every fight a different opponent, drawn by {@link League}, and a
 * different loadout. The opponent is a mob or a squad of several at once, set up and kept fighting by
 * {@link net.sievert.modularmobai.gametest.league.Roster} and named by
 * {@link net.sievert.modularmobai.gametest.league.Opposition}, or another agent: the scripted fighter, or a frozen
 * checkpoint of the network being trained, playing its most likely action with nothing recorded.
 *
 * <p>Otherwise this is {@link AgentVindicatorTerrainGameTest}: the same sites, the same minute, the same slots working
 * through one shared queue of fights, the same reward. What winning means is the one other difference. A fight is won
 * when every opponent's health is gone and the agent still stands, however it went, off a cliff or on the agent's sword.
 * An opponent that goes without its health gone, a creeper that blew itself up, is not beaten: the fight is a draw,
 * which pays what a loss on time does, since the agent did not win it.
 *
 * <p>A squad fights as a side of its own, {@link net.sievert.modularmobai.allegiance.Allegiance}: the agent on one team
 * and all of them on another, so each goes for the agent and not for its own, and the agent counts every one of them an
 * enemy whatever kind of mob it is. The teams live on the server's scoreboard, so they are taken down again the moment the
 * fight is over. A fight against one mob is set up with no teams at all, exactly as it always was.
 *
 * <p>The pairing is drawn before any ground is asked for, since what the agent is up against is what decides how many
 * places to stand the site has to have.
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
        private Evaluation.Assignment evaluation;
        private AgentMob agent;

        /** Everyone on the other side: one mob, one agent, or a whole squad. */
        private final List<LivingEntity> opponents = new ArrayList<>();

        /** The sides a squad fight was set up with, to take down again afterwards; empty for a fight against one. */
        private List<PlayerTeam> teams = List.of();

        private Episode episode;
        private long started;

        /** Whether the other side hurt the agent, and went for it, at any point in the fight. */
        private boolean landed;
        private boolean targeted;

        /** Whether the agent hurt any of them at any point, which with the one above says whether they ever met. */
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

                    if (!this.holding) {

                        if (!TerrainSites.takeFight()) {

                            this.phase = Phase.DONE;
                            return;
                        }

                        // Drawn before any ground is asked for: a squad needs a place to stand for every one of them, and
                        // only the pairing says how many that is. The brain is handed over before the driver ever steps this
                        // agent, so the training brain never sees it at all when it is an evaluation, and never sees the
                        // other side either way: only the agent's own fights are learned from.
                        this.evaluation = Evaluation.next();
                        this.matchup = League.next(this.evaluation, this.level.getRandom());
                        this.holding = true;
                    }

                    this.site = TerrainSites.claim(this.level, this.matchup.mobs());

                    if (this.site != null) {

                        this.holding = false;
                        this.phase = this.begin();
                    }
                }

                case FIGHTING -> {

                    if (this.replay != null) {

                        this.replay.tick();
                    }

                    for (int on = 0; on < this.opponents.size(); on++) {

                        LivingEntity opponent = this.opponents.get(on);

                        if (opponent instanceof Mob mob && this.matchup.opposition() != null && mob.isAlive() && this.agent.isAlive()) {

                            // Asked before the target is made the agent again, so it says whether the mob's own mind kept it
                            // through a tick of its own.
                            this.targeted |= mob.getTarget() == this.agent;
                            this.matchup.opposition().mobs().get(on).provoke(mob, this.agent);
                        }

                        this.landed |= this.agent.getLastHurtByMob() == opponent;
                        this.struck |= opponent.getLastHurtByMob() == this.agent;
                    }

                    if (!this.agent.isAlive() || this.beaten() || this.helper.getTick() - this.started >= FIGHT_TICKS) {

                        this.decide();
                        this.phase = Phase.ENDING;
                    }
                }

                case ENDING -> {

                    if (this.agent.brain().isFinished() || this.agent.isRemoved()) {

                        List<Entity> fighters = new ArrayList<>(this.opponents);
                        fighters.add(this.agent);

                        TerrainSites.release(this.level, this.site, this.stuck, fighters.toArray(new Entity[0]));

                        // Teams outlive the entities on them and are saved with the world, so a run that left them behind
                        // would end with thousands on the scoreboard.
                        this.teams.forEach(Allegiance::disband);
                        this.teams = List.of();
                        this.matchup = null;
                        this.phase = Phase.IDLE;
                    }
                }

                case DONE -> {
                }
            }
        }

        private Phase begin() {

            float agentYaw = yawTowards(this.site.agent(), this.site.opponent()) + Mth.nextFloat(this.level.getRandom(), -45.0F, 45.0F);

            Opposition opposition = this.matchup.opposition();

            this.agent = ModEntities.trainingAgent().create(this.level);
            this.opponents.clear();

            if (this.agent == null) {

                throw new IllegalStateException("Could not create the agent for " + this.matchup.opponent());
            }

            place(this.agent, this.site.agent(), agentYaw);

            // Whatever flies starts that far up in the air, which a site's open sky always leaves clear, and the fight's own
            // patch of sky grows by as much, so an opponent that climbs from there is still something the agent can see.
            AABB bounds = this.site.bounds();

            for (int on = 0; on < this.matchup.mobs(); on++) {

                Roster.Member member = opposition != null ? opposition.mobs().get(on) : null;
                LivingEntity opponent = member != null ? member.type().create(this.level) : ModEntities.trainingAgent().create(this.level);

                if (opponent == null) {

                    throw new IllegalStateException("Could not create the opposition for " + this.matchup.opponent());
                }

                BlockPos ground = this.site.opponents().get(on);
                int height = member != null ? member.height() : 0;

                place(opponent, ground.above(height), yawTowards(ground, this.site.agent()));
                bounds = height > 0 ? bounds.expandTowards(0.0D, height, 0.0D) : bounds;

                if (member != null) {

                    // What spawning on its own would do, including handing it what it fights with, and then what a fair
                    // fight needs on top, see Roster.
                    Mob mob = (Mob) opponent;

                    mob.finalizeSpawn(this.level, this.level.getCurrentDifficultyAt(ground), MobSpawnType.EVENT, member.groupData());
                    member.prepare(mob, this.level.getRandom());
                }

                this.level.addFreshEntity(opponent);
                this.opponents.add(opponent);
            }

            this.level.addFreshEntity(this.agent);

            // Several of them are a side, so each goes for the agent rather than for its own, and the agent counts every one
            // an enemy whatever kind of mob it is. One of them never needed a team, and does not get one: the ratings the
            // roster already has were fought with no teams anywhere.
            this.teams = this.opponents.size() > 1
                    ? List.copyOf(Allegiance.enemy(List.of(this.agent), this.opponents))
                    : List.of();

            this.matchup.loadout().equip(this.agent);
            this.agent.startEpisode(new Episode(FIGHT_TICKS, bounds, this.opponents));

            if (this.evaluation != null) {

                this.agent.brain().use(this.evaluation.brain());
            }

            for (int on = 0; on < this.opponents.size(); on++) {

                LivingEntity opponent = this.opponents.get(on);

                if (opponent instanceof AgentMob other) {

                    // Its own fight, bounded the same, against the agent; what it is paid goes nowhere, since nothing
                    // records it.
                    this.matchup.opponentLoadout().equip(other);
                    other.startEpisode(new Episode(FIGHT_TICKS, bounds, this.agent));
                    other.brain().use(this.matchup.brain());
                }

                else if (opponent instanceof Mob mob && opposition != null) {

                    opposition.mobs().get(on).provoke(mob, this.agent);
                }
            }

            this.episode = this.agent.episode();
            this.started = this.helper.getTick();
            this.landed = false;
            this.targeted = this.opponents.get(0) instanceof AgentMob;
            this.struck = false;

            // A replay holds one agent and one opponent, so a squad fight is not one: a recording with the rest of the side
            // missing would show the agent losing to nothing at all. See docs/replay-format.md.
            this.replay = this.opponents.size() > 1 ? null : FightRecorder.start(this.agent, this.opponents.get(0));

            return Phase.FIGHTING;
        }

        /** Whether nothing on the other side is still standing, which is when there is nothing left to fight. */
        private boolean beaten() {

            for (LivingEntity opponent : this.opponents) {

                if (opponent.isAlive()) {

                    return false;
                }
            }

            return true;
        }

        private void decide() {

            boolean standing = this.agent.isAlive();
            boolean killed = true;
            boolean alive = false;

            for (LivingEntity opponent : this.opponents) {

                killed &= opponent.isDeadOrDying();
                alive |= opponent.isAlive();
            }

            // Won only with every one of them dead: one left alive is the clock running out, and one gone without its health
            // ever reaching zero, a creeper that blew itself up, is a draw whatever became of the others.
            boolean won = standing && killed;
            boolean timedOut = standing && alive;

            String outcome = won ? "win" : !standing ? "loss" : timedOut ? "timeout" : "draw";

            this.stuck = timedOut && !this.landed && !this.struck && this.matchup.opposition() != null;

            if (won) {

                this.episode.reward().won();
            }

            else if (standing) {

                this.episode.reward().lost();
            }

            else {

                DeathCauses.record(this.agent, this.opponents);
            }

            TIME_TO_RESOLVE.record(this.helper.getTick() - this.started,
                    won ? TestDurationStats.Outcome.WIN : TestDurationStats.Outcome.LOSS, this.helper.getTick());

            League.record(this.matchup, outcome, this.helper.getTick() - this.started, this.landed, this.targeted,
                    standing ? "-" : DeathCauses.cause(this.agent, this.opponents));

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
