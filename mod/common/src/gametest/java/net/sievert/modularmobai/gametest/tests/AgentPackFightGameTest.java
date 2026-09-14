package net.sievert.modularmobai.gametest.tests;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.scores.PlayerTeam;
import net.sievert.modularmobai.Constants;
import net.sievert.modularmobai.allegiance.Allegiance;
import net.sievert.modularmobai.arena.Episode;
import net.sievert.modularmobai.arena.Loadout;
import net.sievert.modularmobai.brain.schema.Species;
import net.sievert.modularmobai.entity.ModEntities;
import net.sievert.modularmobai.entity.agent.AgentMob;
import net.sievert.modularmobai.gametest.GameTestGroup;
import net.sievert.modularmobai.gametest.GameTestTuning;
import net.sievert.modularmobai.gametest.RepeatGameTest;
import net.sievert.modularmobai.gametest.league.HostilePacks;
import net.sievert.modularmobai.gametest.league.League;
import net.sievert.modularmobai.gametest.league.Loadouts;
import net.sievert.modularmobai.gametest.league.Opposition;
import net.sievert.modularmobai.gametest.league.Roster;
import net.sievert.modularmobai.gametest.terrain.TerrainSites;
import net.sievert.modularmobai.gametest.util.DeathCauses;

/**
 * A pack of melee mobs, on natural ground, watched tick by tick: <b>why</b> a kite fails rather than how often.
 *
 * <p>The bench already says how often. Every one-mob fight made a pack, 600 fights a sitting, the scripted fighter wins
 * 68.6% of the small zombie packs and 44.1% of the large ones, and 66.1% and 41.5% of the same against fast melee. Those
 * two pairs are the finding this suite exists for: <b>the fighter does no better against a zombie than against a
 * vindicator</b>, and a zombie is the one opponent a kite ought to beat outright. A body walking backwards covers 0.216
 * blocks a tick and a zombie covers 0.154, so a fighter that holds the nearest at the edge of its own reach, swings the
 * tick it steps in, and backs off while the swing cools should never be touched at all; a vindicator covers 0.24 and the
 * same cannot hold. A win rate cannot tell those two apart. These columns can: how far the nearest of the pack actually
 * stood, how often two of them were on top of the agent at once, how far away its blows landed, how many blows it took
 * and from whom, and which way its feet were going.
 *
 * <pre>
 *   scripts\test.ps1 -Pack                                  the scripted fighter
 *   scripts\test.ps1 -Pack -Weights models\blast8\best.mbw   a network instead
 * </pre>
 *
 * <p>Five packs, fought in turn: two, three and four zombies, which are slower than a backpedal, and two and three
 * vindicators, which are faster. That is the whole question — the same fight on either side of the one number that decides
 * whether a kite is possible at all — and it is why the suite fields packs of one kind of mob rather than the league's
 * whole roster, which {@code bench.ps1 -HostileCrowds 1} already covers and answers with win rates.
 *
 * <p><b>Nothing here is asserted</b>, exactly as in {@link AgentCrowdedFightGameTest} and for the same reason: a slot
 * fights one fight after another out of a shared queue, which is {@code succeedWhen} and not {@code onEachTick}, and the
 * framework reads a failed assertion there as "not finished yet" and runs the whole thing again next tick. What is wanted
 * is numbers to read. The rules this suite measured are held as rules in {@link AgentTeacherGameTest}, in the mechanics
 * suite, where an assertion fails a test.
 */
@GameTestGroup
public class AgentPackFightGameTest {

    /** The framework's own limit on a whole slot, only there to catch a slot that has stopped working. */
    private static final int SLOT_TIMEOUT_TICKS = 50_000_000;

    /**
     * What the agent carries, drawn in turn over every loadout the league fields, and the second table below is by
     * loadout rather than by pack.
     *
     * <p>It was a plain sword to begin with, on the grounds that the numbers wanted to be about the footwork. That is
     * exactly the measurement that says the sword is not where the trouble is: with one, on the hard rung, the teacher
     * won 93%, 100% and 100% of thirty fights each against two, three and four zombies, against the 68.6% the pack bench
     * reads for small packs of slow walkers. A column that cannot go up says nothing, and a diagnosis that leaves out
     * nine loadouts in ten is a diagnosis of the tenth.
     */
    private static final List<String> LOADOUTS = Loadouts.enabled().stream().map(Loadout::name).toList();

    /** How near a body has to be to be able to strike: anything man sized reaches a block and a half, two on a diagonal. */
    private static final double ON_TOP = 2.0D;

    /** The cooldown at which a swing is at full strength, as the fighter's own rule reads it. */
    private static final float READY = 0.96F;

    /** Below this the feet are neither closing nor giving ground, in blocks a tick along the way to the pack. */
    private static final double DRIFT = 0.02D;

    /** How many fights of each pack are printed one line a tick, and how many ticks of each. */
    private static final int TRACED_FIGHTS = 1;
    private static final int TRACED_TICKS = 300;

    /**
     * The packs fought, in turn, so one fight of each comes out of every five.
     *
     * <p><b>On the hard rung</b>, which is one of the two the league draws and is the one that makes this a fight worth
     * measuring. Measured first on the normal rung with the same five packs: the teacher won <b>every one</b> of the
     * ninety zombie fights, which is a column that can only go down and says nothing about a kite. Hard spawns them with
     * armour and something to swing and takes more off the agent per blow, and it is half of what the pack bench is
     * averaging when it reads 68.6%.
     */
    private static final Pack[] PACKS = {
            new Pack("zombie(hard)", 2), new Pack("zombie(hard)", 3), new Pack("zombie(hard)", 4),
            new Pack("vindicator(hard)", 2), new Pack("vindicator(hard)", 3),
    };

    /** How many of one mob, all of them fighting: the fight this suite is about. */
    private record Pack(String opponent, int fighting) {

        private String label() {

            return this.fighting + "x " + this.opponent;
        }
    }

    /** Which fight this process is on, so the packs and the loadouts go round however many slots are running. */
    private static int drawn;

    /** What every fight of each pack came to, and of each loadout, printed once they are all done. */
    private static final Tally[] TALLIES = new Tally[PACKS.length];
    private static final Tally[] BY_LOADOUT = new Tally[LOADOUTS.size()];

    /** How many fights are still to come, so the summary is printed by whichever slot finishes the last of them. */
    private static int outstanding = -1;

    @GameTest(template = "arena", timeoutTicks = SLOT_TIMEOUT_TICKS)
    @RepeatGameTest(slots = true)
    public static void aPackIsWatchedTickByTick(GameTestHelper helper, int slot) {

        final Fights fights = new Fights(helper);

        helper.succeedWhen(() -> {

            fights.tick();

            if (!fights.done()) {

                throw new GameTestAssertException("Still fighting");
            }
        });
    }

    /**
     * Which fight this process is on. The pack turns over every fight and the loadout every time the packs have been all
     * the way round, so each pack meets each loadout equally often and neither column is a sample of the other.
     */
    private static synchronized int next() {

        if (outstanding < 0) {

            outstanding = GameTestTuning.arenasInShard(GameTestTuning.arenaCount());

            for (int at = 0; at < TALLIES.length; at++) {

                TALLIES[at] = new Tally(PACKS[at].label());
            }

            for (int at = 0; at < BY_LOADOUT.length; at++) {

                BY_LOADOUT[at] = new Tally(LOADOUTS.get(at));
            }
        }

        return drawn++;
    }

    private static int packOf(int fight) {

        return fight % PACKS.length;
    }

    private static int loadoutOf(int fight) {

        return fight / PACKS.length % LOADOUTS.size();
    }

    /** Adds one finished fight to its pack's tally and its loadout's, and prints them all when the last fight is in. */
    private static synchronized void fought(int fight, Tally tally) {

        TALLIES[packOf(fight)].add(tally);
        BY_LOADOUT[loadoutOf(fight)].add(tally);

        if (--outstanding > 0) {

            return;
        }

        System.out.println("========= A pack watched tick by tick =========");
        System.out.println(Tally.HEADER);

        for (Tally by : TALLIES) {

            System.out.println(by.row());
        }

        System.out.println("  ----- the same fights by what the agent was carrying -----");

        for (Tally by : BY_LOADOUT) {

            System.out.println(by.row());
        }

        System.out.println("  near: how far the nearest live body stood on an average tick; two on top %: the share of "
                + "ticks with two or more of them within " + ON_TOP + " blocks; blow at: the mean distance its blows "
                + "landed from; ready %: the share of ticks its swing was at full strength; back %/in %: the share of "
                + "ticks its feet were going away from the pack and towards it; taken: blows taken a fight, and flanked "
                + "%: the share of those thrown by something that was not the nearest body at the time.");
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
        private Opposition opposition;
        private final List<Mob> pack = new ArrayList<>();
        private List<PlayerTeam> teams = List.of();

        /** Which fight of the whole sitting this is, which says both its pack and its loadout, and how it has gone. */
        private int which;
        private Tally tally;

        private long started;
        private boolean traced;

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

                    // Taken off the queue once and then held while the ground it wants is waited for, exactly as the
                    // league's own slot does it: taking it again every tick would empty the queue without fighting.
                    if (!this.holding) {

                        if (!TerrainSites.takeFight()) {

                            this.phase = Phase.DONE;
                            return;
                        }

                        this.which = next();
                        this.holding = true;
                    }

                    // A place to stand for every one of them, which is what a pack needs of the ground and one opponent
                    // does not.
                    this.site = TerrainSites.claim(this.level, PACKS[packOf(this.which)].fighting(), 0, false);

                    if (this.site != null) {

                        this.holding = false;
                        this.phase = this.begin();
                    }
                }

                case FIGHTING -> {

                    this.watch();

                    for (int on = 0; on < this.pack.size(); on++) {

                        Mob mob = this.pack.get(on);

                        if (mob.isAlive() && this.agent.isAlive()) {

                            this.opposition.mobs().get(on).provoke(mob, this.agent);
                        }
                    }

                    if (!this.agent.isAlive() || this.beaten()
                            || this.helper.getTick() - this.started >= Roster.MELEE_TICKS) {

                        this.finish();
                        this.phase = Phase.ENDING;
                    }
                }

                case ENDING -> {

                    if (this.agent.brain().isFinished() || this.agent.isRemoved()) {

                        List<Entity> fighters = new ArrayList<>(this.pack);
                        fighters.add(this.agent);

                        TerrainSites.release(this.level, this.site, false, fighters.toArray(new Entity[0]));

                        this.teams.forEach(Allegiance::disband);
                        this.teams = List.of();
                        this.pack.clear();
                        this.phase = Phase.IDLE;
                    }
                }

                case DONE -> {
                }
            }
        }

        /** Whether nothing on the other side is still standing, which is when there is nothing left to fight. */
        private boolean beaten() {

            for (Mob mob : this.pack) {

                if (mob.isAlive()) {

                    return false;
                }
            }

            return true;
        }

        private Phase begin() {

            Pack wanted = PACKS[packOf(this.which)];
            Opposition one = Opposition.named(wanted.opponent());

            if (one == null) {

                throw new IllegalStateException("This build does not field the " + wanted.opponent() + " these fights are about");
            }

            // The league's own machinery for several of one mob, all of them fighting, rather than a second copy of it
            // here: see league/HostilePacks.
            this.opposition = HostilePacks.pack(one, wanted.fighting());
            this.tally = new Tally(wanted.label());
            this.traced = TALLIES[packOf(this.which)].fights < TRACED_FIGHTS;

            this.agent = ModEntities.training(Species.trained()).create(this.level);

            if (this.agent == null) {

                throw new IllegalStateException("Could not make the agent for a pack fight");
            }

            place(this.agent, this.site.agent());
            this.pack.clear();

            for (int on = 0; on < wanted.fighting(); on++) {

                Roster.Member member = this.opposition.mobs().get(on);
                Mob mob = member.type().create(this.level);

                if (mob == null) {

                    throw new IllegalStateException("Could not make the opposition for a pack fight");
                }

                BlockPos ground = this.site.opponents().get(on);

                place(mob, ground);
                mob.finalizeSpawn(this.level, this.opposition.spawnDifficulty(this.level, ground), MobSpawnType.EVENT,
                        member.groupData());
                member.prepare(mob, this.level.getRandom());

                this.level.addFreshEntity(mob);
                this.pack.add(mob);
            }

            this.level.addFreshEntity(this.agent);

            // A side of their own, so each comes for the agent rather than for its own, exactly as a squad or a league
            // pack is stood up.
            this.teams = List.copyOf(Allegiance.enemy(List.of(this.agent), List.copyOf(this.pack)));

            Loadouts.named(LOADOUTS.get(loadoutOf(this.which))).equip(this.agent);
            this.agent.startEpisode(new Episode(Roster.MELEE_TICKS, this.site.bounds(), List.copyOf(this.pack)));

            for (int on = 0; on < this.pack.size(); on++) {

                this.opposition.mobs().get(on).provoke(this.pack.get(on), this.agent);
            }

            this.started = this.helper.getTick();

            return Phase.FIGHTING;
        }

        /** Where the pack is this tick, and what the agent did about it. */
        private void watch() {

            double nearest = Double.MAX_VALUE;
            Mob closest = null;
            int onTop = 0;
            int alive = 0;
            double middleX = 0.0D;
            double middleZ = 0.0D;

            for (Mob mob : this.pack) {

                if (!mob.isAlive()) {

                    continue;
                }

                double away = this.agent.distanceTo(mob);

                alive++;
                onTop += away <= ON_TOP ? 1 : 0;
                middleX += mob.getX();
                middleZ += mob.getZ();

                if (away < nearest) {

                    nearest = away;
                    closest = mob;
                }
            }

            if (alive == 0) {

                return;
            }

            // Which way the feet are actually going, along the line to the middle of what is left of the pack. The body's
            // own movement rather than the keys, so a fighter walking into a wall reads as going nowhere.
            double toX = middleX / alive - this.agent.getX();
            double toZ = middleZ / alive - this.agent.getZ();
            double span = Math.hypot(toX, toZ);
            double closing = span < 1.0E-6D ? 0.0D
                    : (this.agent.getDeltaMovement().x * toX + this.agent.getDeltaMovement().z * toZ) / span;

            boolean ready = this.agent.getAttackStrengthScale(0.5F) >= READY;

            // Where a blow landed, which is the one number that says whether the fighter is striking at the edge of its
            // reach or standing in the middle of them.
            LivingEntity struck = this.agent.executed().attackHit ? this.agent.getLastHurtMob() : null;
            double blowAt = struck == null ? -1.0D : this.agent.distanceTo(struck);

            // A blow taken, and from whom: health that went down this tick, with the body that took it off written in the
            // agent's own last-hurt-by. Flanked is one thrown by something that was not the nearest body at the time,
            // which is what being surrounded looks like in one number.
            boolean taken = this.agent.getHealth() < this.tally.health;
            LivingEntity by = taken ? this.agent.getLastHurtByMob() : null;
            boolean flanked = taken && by != null && by != closest;

            this.tally.tick(nearest, onTop, ready, this.agent.executed().attacked, blowAt, taken, flanked, closing,
                    this.agent.getHealth());

            if (this.traced && this.tally.ticks <= TRACED_TICKS) {

                Constants.LOG.info(String.format(Locale.ROOT,
                        "pack=%-28s tick=%3d alive=%d near=%5.2fb onTop=%d ready=%s press=%s blowAt=%5.2f taken=%s "
                                + "by=%-12s feet=%-4s health=%.1f",
                        PACKS[packOf(this.which)].label() + " " + LOADOUTS.get(loadoutOf(this.which)),
                        this.tally.ticks, alive, nearest, onTop, ready ? "y" : "n",
                        this.agent.executed().attacked ? "y" : "n", blowAt, taken ? (flanked ? "flank" : "front") : "-",
                        by == null ? "-" : by == closest ? "nearest" : "another",
                        closing > DRIFT ? "in" : closing < -DRIFT ? "back" : "hold", this.agent.getHealth()));
            }
        }

        private void finish() {

            boolean standing = this.agent.isAlive();
            boolean won = standing && this.beaten();
            boolean timedOut = standing && !won;

            // Scored exactly as the league scores it, and this is not bookkeeping: the reward going terminal is what tells
            // the driver the agent has taken its last step, and a fight that never says so leaves its slot waiting for a
            // step that never comes and the suite hanging after its last fight.
            if (won) {

                this.agent.episode().reward().won();
            }

            else if (timedOut) {

                this.agent.episode().reward().lost();
            }

            else {

                DeathCauses.record(this.agent, List.copyOf(this.pack));
            }

            this.tally.outcome(won, timedOut);
            fought(this.which, this.tally);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------

    private static void place(LivingEntity fighter, BlockPos feet) {

        fighter.moveTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, 0.0F, 0.0F);
        fighter.setYHeadRot(0.0F);
        fighter.setYBodyRot(0.0F);

        if (fighter instanceof Mob mob) {

            mob.setPersistenceRequired();
        }

        fighter.addTag(TerrainSites.TAG);
    }

    /** What the fights against one pack came to, and what one fight came to, which are added up the same way. */
    private static final class Tally {

        private static final String HEADER = String.format(Locale.ROOT,
                "  %-19s %6s %7s %9s %7s %7s %10s %8s %8s %7s %7s %7s %7s",
                "pack", "fights", "won %", "timed out", "ticks", "near", "two on top", "swings", "blows", "blow at",
                "ready %", "back %", "in %");

        private final String label;

        private int fights;
        private int wins;
        private int timeouts;

        private int ticks;

        private double nearestSum;
        private int twoOnTop;
        private int readyTicks;
        private int backTicks;
        private int inTicks;

        private int swings;
        private int landed;
        private double landedAtSum;

        private int taken;
        private int flanked;

        /**
         * The agent's health as the tick before left it, which is how a blow taken is seen without a damage listener.
         * Nought until the first tick has written one, so the first tick of a fight never reads as a blow taken.
         */
        private float health;

        private Tally(String label) {

            this.label = label;
        }

        private void tick(double nearest, int onTop, boolean ready, boolean pressed, double blowAt, boolean hurt,
                          boolean flanked, double closing, float health) {

            this.ticks++;
            this.nearestSum += nearest;
            this.twoOnTop += onTop >= 2 ? 1 : 0;
            this.readyTicks += ready ? 1 : 0;
            this.backTicks += closing < -DRIFT ? 1 : 0;
            this.inTicks += closing > DRIFT ? 1 : 0;

            this.swings += pressed ? 1 : 0;

            if (blowAt >= 0.0D) {

                this.landed++;
                this.landedAtSum += blowAt;
            }

            this.taken += hurt ? 1 : 0;
            this.flanked += flanked ? 1 : 0;
            this.health = health;
        }

        private void outcome(boolean won, boolean timedOut) {

            this.fights = 1;
            this.wins = won ? 1 : 0;
            this.timeouts = timedOut ? 1 : 0;
        }

        private void add(Tally fight) {

            this.fights += fight.fights;
            this.wins += fight.wins;
            this.timeouts += fight.timeouts;
            this.ticks += fight.ticks;
            this.nearestSum += fight.nearestSum;
            this.twoOnTop += fight.twoOnTop;
            this.readyTicks += fight.readyTicks;
            this.backTicks += fight.backTicks;
            this.inTicks += fight.inTicks;
            this.swings += fight.swings;
            this.landed += fight.landed;
            this.landedAtSum += fight.landedAtSum;
            this.taken += fight.taken;
            this.flanked += fight.flanked;
        }

        private String row() {

            double ticks = Math.max(1, this.ticks);
            double fights = Math.max(1, this.fights);

            return String.format(Locale.ROOT,
                    "  %-19s %6d %7.1f %9.1f %7.0f %7.2f %10.1f %8.1f %8.1f %7.2f %7.1f %7.1f %7.1f"
                            + "   taken %.1f, flanked %.0f%%",
                    this.label, this.fights, 100.0D * this.wins / fights, 100.0D * this.timeouts / fights,
                    this.ticks / fights, this.nearestSum / ticks, 100.0D * this.twoOnTop / ticks, this.swings / fights,
                    this.landed / fights, this.landedAtSum / Math.max(1, this.landed), 100.0D * this.readyTicks / ticks,
                    100.0D * this.backTicks / ticks, 100.0D * this.inTicks / ticks,
                    this.taken / fights, 100.0D * this.flanked / Math.max(1, this.taken));
        }
    }
}
