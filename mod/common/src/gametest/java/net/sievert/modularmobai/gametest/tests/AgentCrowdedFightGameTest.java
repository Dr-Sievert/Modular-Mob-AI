package net.sievert.modularmobai.gametest.tests;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
import net.minecraft.world.phys.Vec3;
import net.sievert.modularmobai.Constants;
import net.sievert.modularmobai.arena.Episode;
import net.sievert.modularmobai.brain.schema.AgentObservation;
import net.sievert.modularmobai.brain.schema.EnemySlots;
import net.sievert.modularmobai.brain.schema.ObservationSchema;
import net.sievert.modularmobai.brain.schema.Species;
import net.sievert.modularmobai.entity.ModEntities;
import net.sievert.modularmobai.entity.agent.AgentMob;
import net.sievert.modularmobai.gametest.GameTestGroup;
import net.sievert.modularmobai.gametest.GameTestTuning;
import net.sievert.modularmobai.gametest.RepeatGameTest;
import net.sievert.modularmobai.gametest.league.Bystanders;
import net.sievert.modularmobai.gametest.league.League;
import net.sievert.modularmobai.gametest.league.Loadouts;
import net.sievert.modularmobai.gametest.league.Opposition;
import net.sievert.modularmobai.gametest.league.Roster;
import net.sievert.modularmobai.gametest.terrain.TerrainSites;
import net.sievert.modularmobai.gametest.util.DeathCauses;

/**
 * The same league fight fought with nobody standing about it and with one, three and nine, watched tick by tick.
 *
 * <p>This exists because a run's results could say only that it was losing. {@code blast7} trained 2,200 iterations with a
 * quarter of its fights crowded and its crowded win rate never moved off about 31% against 80% plain; crowded fights record
 * no replay, so nothing had ever been seen. What is wanted is not another win rate but the four numbers that say <b>why</b>,
 * on every tick: which slot the engaged opponent is in, what {@code SELF_ENEMIES_IN_RANGE} reads, where the aim is, and who
 * a press of attack lands on. This suite is those four numbers and nothing else. Run it with
 *
 * <pre>
 *   scripts\test.ps1 -Crowd -Weights models\blast6\best.mbw
 * </pre>
 *
 * <p>It is a suite of its own rather than a class in the league's because it has to field a fixed schedule of crowds — nought,
 * one, three, nine, round and round — where the league draws them, and because the league suite is what a training run runs:
 * a class added there would be in every run's fights.
 *
 * <p>What it found, and what {@link EnemySlots} was then changed by: a slot was handed out in the order the level's own walk
 * over the entity sections returned bodies, which is section x ascending, then z, then y. In a fight against one opponent
 * there is one body, so the opponent always held slot 0 and every plain fight taught the network that slot 0 is the fight.
 * Stand nine bystanders round it and the opponent holds slot 0 only when it happens to be the westernmost of the ten, so
 * about one fight in ten, and the other nine put an idle monster there. The order is now the fight's own — whoever has come
 * for the agent or is on the other team first, then the nearest — so the engaged opponent holds slot 0 in a crowd exactly as
 * it does on its own.
 *
 * <p><b>Nothing here is asserted, deliberately.</b> A slot fights one fight after another out of a shared queue, which is
 * {@code succeedWhen} and not {@code onEachTick}, and the framework treats a failed assertion in a {@code succeedWhen} as
 * "not finished yet" and runs the whole thing again next tick rather than failing the test. So an assertion here would be a
 * silent retry loop, and what is wanted anyway is numbers to read. The rule this suite measured is held as a rule in
 * {@link AgentEnemyOrderGameTest}, in the mechanics suite, where an assertion fails a test.
 */
@GameTestGroup
public class AgentCrowdedFightGameTest {

    /** The framework's own limit on a whole slot, only there to catch a slot that has stopped working. */
    private static final int SLOT_TIMEOUT_TICKS = 50_000_000;

    /** The crowds fought, in turn: none, one, three and nine, so one fight of each comes out of every four. */
    private static final int[] CROWDS = {0, 1, 3, 9};

    /** The opponent and what the agent carries. The zombie with a sword is the fight the real world's report was about. */
    private static final String OPPONENT = "zombie";
    private static final String LOADOUT = "sword";

    /** How many ticks of one fight are printed one line each, and of how many fights per crowd. */
    private static final int TRACED_FIGHTS = 1;
    private static final int TRACED_TICKS = 400;

    /** Which fight this process is on, so the crowds go round in turn however many slots are running. */
    private static int drawn;

    /** What every fight at each crowd came to, printed once they are all done. */
    private static final Tally[] TALLIES = new Tally[CROWDS.length];

    /** How many fights are still to come, so the summary is printed by whichever slot finishes the last of them. */
    private static int outstanding = -1;

    @GameTest(template = "arena", timeoutTicks = SLOT_TIMEOUT_TICKS)
    @RepeatGameTest(slots = true)
    public static void theCrowdIsWatchedTickByTick(GameTestHelper helper, int slot) {

        final Fights fights = new Fights(helper);

        helper.succeedWhen(() -> {

            fights.tick();

            if (!fights.done()) {

                throw new GameTestAssertException("Still fighting");
            }
        });
    }

    /** The next crowd to stand about a fight, and which tally it belongs to. */
    private static synchronized int next() {

        if (outstanding < 0) {

            outstanding = GameTestTuning.arenasInShard(GameTestTuning.arenaCount());

            for (int at = 0; at < TALLIES.length; at++) {

                TALLIES[at] = new Tally(CROWDS[at]);
            }
        }

        return drawn++ % CROWDS.length;
    }

    /** Adds one finished fight to its crowd's tally, and prints them all when the last fight is in. */
    private static synchronized void fought(int which, Tally fight) {

        TALLIES[which].add(fight);

        if (--outstanding > 0) {

            return;
        }

        System.out.println("========= A crowd watched tick by tick: " + OPPONENT + " with a " + LOADOUT + " =========");
        System.out.println(Tally.HEADER);

        for (Tally tally : TALLIES) {

            System.out.println(tally.row());
        }

        System.out.println("  slot 0 %: of the ticks the opponent could be seen, the share it held the first slot in; "
                + "nearer %: the share a bystander was closer to the agent than its opponent; in fight: what "
                + "SELF_ENEMIES_IN_RANGE read, which counts the bodies on the agent and not the bodies in its view; "
                + "aim: how far off the opponent the agent was looking, in degrees; "
                + "on opp / on idle / on air: where a press of attack landed.");
        System.out.println("=".repeat(70));
    }

    /** One slot: fight after fight off the shared queue, each watched tick by tick. */
    private static final class Fights {

        private enum Phase {IDLE, FIGHTING, ENDING, DONE}

        private final GameTestHelper helper;
        private final ServerLevel level;

        private Phase phase = Phase.IDLE;
        private boolean holding;

        private TerrainSites.Site site;
        private AgentMob agent;
        private Mob opponent;
        private Roster.Member member;
        private List<Mob> crowd = List.of();

        /** Which of {@link #CROWDS} this fight is, and what it has come to so far. */
        private int which;
        private Tally tally;

        private long started;
        private boolean traced;

        /** Where the observation is read out, kept for the fight rather than allocated every tick. */
        private final float[] observation = new float[ObservationSchema.OBS_DIM];

        private Fights(GameTestHelper helper) {

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

                    // The fight is taken off the queue once and then held while the ground it wants is waited for, exactly as
                    // the league's own slot does it: taking it again every tick would empty the queue without fighting.
                    if (!this.holding) {

                        if (!TerrainSites.takeFight()) {

                            this.phase = Phase.DONE;
                            return;
                        }

                        this.holding = true;
                    }

                    this.site = TerrainSites.claim(this.level, 1, 0, false);

                    if (this.site != null) {

                        this.holding = false;
                        this.phase = this.begin();
                    }
                }

                case FIGHTING -> {

                    this.watch();

                    this.member.provoke(this.opponent, this.agent);

                    for (Mob standing : this.crowd) {

                        Bystanders.leaveAlone(standing, this.agent);
                    }

                    if (!this.agent.isAlive() || !this.opponent.isAlive()
                            || this.helper.getTick() - this.started >= Roster.MELEE_TICKS) {

                        this.finish();
                        this.phase = Phase.ENDING;
                    }
                }

                case ENDING -> {

                    if (this.agent.brain().isFinished() || this.agent.isRemoved()) {

                        TerrainSites.release(this.level, this.site, false, this.agent, this.opponent);

                        this.crowd = List.of();
                        this.phase = Phase.IDLE;
                    }
                }

                case DONE -> {
                }
            }
        }

        private Phase begin() {

            Opposition opposition = Opposition.named(OPPONENT);

            if (opposition == null) {

                throw new IllegalStateException("This build does not field the " + OPPONENT + " these fights are about");
            }

            this.member = opposition.mobs().get(0);
            Roster.Member member = this.member;

            this.which = next();
            this.tally = new Tally(CROWDS[this.which]);
            this.traced = TALLIES[this.which].fights < TRACED_FIGHTS;

            this.agent = ModEntities.training(Species.trained()).create(this.level);
            Mob mob = member.type().create(this.level);

            if (this.agent == null || mob == null) {

                throw new IllegalStateException("Could not make the fighters for a crowded fight");
            }

            this.opponent = mob;

            place(this.agent, this.site.agent());
            place(this.opponent, this.site.opponents().get(0));

            this.opponent.finalizeSpawn(this.level, opposition.spawnDifficulty(this.level, this.site.opponents().get(0)),
                    MobSpawnType.EVENT, member.groupData());
            member.prepare(this.opponent, this.level.getRandom());

            this.level.addFreshEntity(this.opponent);
            this.level.addFreshEntity(this.agent);

            Loadouts.named(LOADOUT).equip(this.agent);
            this.agent.startEpisode(new Episode(Roster.MELEE_TICKS, this.site.bounds(), List.of(this.opponent)));

            member.provoke(this.opponent, this.agent);

            // After the episode, exactly as a league fight stands them: the reward is settled before anything else is in view.
            this.crowd = Bystanders.stand(this.level, this.site, opposition, CROWDS[this.which], this.level.getRandom());

            this.started = this.helper.getTick();

            return Phase.FIGHTING;
        }

        /** The four numbers, this tick. */
        private void watch() {

            EnemySlots view = this.agent.brain().enemySlots();

            // Read out of the row the network is actually handed, not off the slots, so nothing here can be right about a
            // number the network is given differently.
            AgentObservation.write(this.agent, view, this.observation, 0);

            int slot = slotOf(view, this.opponent);
            float inRange = this.observation[ObservationSchema.SELF_OFFSET + ObservationSchema.SELF_ENEMIES_IN_RANGE];

            double toOpponent = this.agent.distanceTo(this.opponent);
            double nearest = Double.MAX_VALUE;

            for (Mob standing : this.crowd) {

                nearest = Math.min(nearest, this.agent.distanceTo(standing));
            }

            Entity struck = this.agent.executed().attacked && this.agent.executed().attackHit
                    ? this.agent.getLastHurtMob() : null;

            this.tally.tick(slot, inRange, aimError(this.agent, this.opponent), nearest < toOpponent,
                    this.agent.executed().attacked, struck == this.opponent, struck != null && struck != this.opponent);

            if (this.traced && this.tally.ticks <= TRACED_TICKS) {

                Constants.LOG.info(String.format(Locale.ROOT,
                        "crowd=%d tick=%3d slot=%2d inFight=%.2f fighting=%2d opp=%5.1fb idle=%5.1fb aim=%5.1f deg "
                                + "press=%s hit=%s health=%.1f/%.1f",
                        CROWDS[this.which], this.tally.ticks, slot, inRange, view.inRangeCount(), toOpponent,
                        nearest == Double.MAX_VALUE ? -1.0D : nearest, aimError(this.agent, this.opponent),
                        this.agent.executed().attacked ? "y" : "n",
                        struck == null ? "-" : struck == this.opponent ? "opponent" : "idle",
                        this.agent.getHealth(), this.opponent.getHealth()));
            }
        }

        private void finish() {

            boolean standing = this.agent.isAlive();
            boolean won = standing && this.opponent.isDeadOrDying();
            boolean timedOut = standing && this.opponent.isAlive();

            // Scored exactly as the league scores it, and this is not bookkeeping: the reward going terminal is what tells the
            // driver the agent has taken its last step, and a fight that never says so leaves its slot waiting for a step
            // that never comes and the suite hanging after its last fight. A win, the clock running out, and an opponent gone
            // without its health ever reaching zero are the same three answers AgentLeagueGameTest#decide gives.
            if (won) {

                this.agent.episode().reward().won();
            }

            else if (timedOut) {

                this.agent.episode().reward().lost();
            }

            else if (standing) {

                this.agent.episode().reward().drew();
            }

            else {

                DeathCauses.record(this.agent, List.of(this.opponent));
            }

            this.tally.outcome(won);

            Constants.LOG.info("one fight: {}", this.tally.row());
            fought(this.which, this.tally);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------

    /** Which slot is reading that body, or -1 when none is: an empty slot, and one held through cover, both answer null. */
    private static int slotOf(EnemySlots view, Entity body) {

        for (int slot = 0; slot < ObservationSchema.ENEMY_SLOTS; slot++) {

            if (view.occupant(slot) == body) {

                return slot;
            }
        }

        return -1;
    }

    /** How far off that body the agent is looking, in degrees: the angle between where it looks and where the body is. */
    private static double aimError(AgentMob agent, LivingEntity body) {

        Vec3 look = agent.getViewVector(1.0F);
        Vec3 towards = body.getEyePosition().subtract(agent.getEyePosition());

        double length = towards.length();

        return length < 1.0e-4D ? 0.0D
                : Math.toDegrees(Math.acos(Mth.clamp(look.dot(towards) / length, -1.0D, 1.0D)));
    }

    private static void place(LivingEntity fighter, BlockPos feet) {

        fighter.moveTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, 0.0F, 0.0F);
        fighter.setYHeadRot(0.0F);
        fighter.setYBodyRot(0.0F);

        if (fighter instanceof Mob mob) {

            mob.setPersistenceRequired();
        }

        fighter.addTag(TerrainSites.TAG);
    }

    /** What the fights at one crowd came to, and what one fight came to, which are added up the same way. */
    private static final class Tally {

        private static final String HEADER = String.format(Locale.ROOT,
                "  %6s %7s %7s %8s %8s %9s %7s %8s %8s %8s %8s",
                "crowd", "fights", "won %", "slot 0 %", "nearer %", "mean slot", "in fight", "aim", "on opp", "on idle",
                "on air");

        private final int crowd;

        private int fights;
        private int wins;

        private int ticks;

        /** Ticks the opponent could be seen on at all, which is what the slot it held is a share of. */
        private int sighted;
        private int inSlotZero;
        private long slotSum;

        private int nearerBystander;

        private double inRangeSum;
        private double aimSum;

        private int presses;
        private int onOpponent;
        private int onBystander;

        private Tally(int crowd) {

            this.crowd = crowd;
        }

        private void tick(int slot, float inRange, double aim, boolean bystanderNearer, boolean pressed,
                          boolean hitOpponent, boolean hitBystander) {

            this.ticks++;

            if (slot >= 0) {

                this.sighted++;
                this.inSlotZero += slot == 0 ? 1 : 0;
                this.slotSum += slot;
            }

            this.nearerBystander += bystanderNearer ? 1 : 0;
            this.inRangeSum += inRange;
            this.aimSum += aim;

            this.presses += pressed ? 1 : 0;
            this.onOpponent += hitOpponent ? 1 : 0;
            this.onBystander += hitBystander ? 1 : 0;
        }

        private void outcome(boolean won) {

            this.fights = 1;
            this.wins = won ? 1 : 0;
        }

        private void add(Tally fight) {

            this.fights += fight.fights;
            this.wins += fight.wins;
            this.ticks += fight.ticks;
            this.sighted += fight.sighted;
            this.inSlotZero += fight.inSlotZero;
            this.slotSum += fight.slotSum;
            this.nearerBystander += fight.nearerBystander;
            this.inRangeSum += fight.inRangeSum;
            this.aimSum += fight.aimSum;
            this.presses += fight.presses;
            this.onOpponent += fight.onOpponent;
            this.onBystander += fight.onBystander;
        }

        private String row() {

            double ticks = Math.max(1, this.ticks);
            double sighted = Math.max(1, this.sighted);

            return String.format(Locale.ROOT,
                    "  %6d %7d %7.1f %8.1f %8.1f %9.2f %7.2f %8.1f %8d %8d %8d",
                    this.crowd, this.fights, 100.0D * this.wins / Math.max(1, this.fights),
                    100.0D * this.inSlotZero / sighted, 100.0D * this.nearerBystander / ticks, this.slotSum / sighted,
                    this.inRangeSum / ticks, this.aimSum / ticks, this.onOpponent, this.onBystander,
                    this.presses - this.onOpponent - this.onBystander);
        }
    }
}
