package net.sievert.modularmobai.gametest.tests;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.sievert.modularmobai.Constants;
import net.sievert.modularmobai.arena.Episode;
import net.sievert.modularmobai.arena.Loadout;
import net.sievert.modularmobai.brain.schema.EnemySlots;
import net.sievert.modularmobai.brain.schema.ObservationSchema;
import net.sievert.modularmobai.brain.schema.Species;
import net.sievert.modularmobai.entity.ModEntities;
import net.sievert.modularmobai.entity.agent.AgentMob;
import net.sievert.modularmobai.gametest.GameTestGroup;
import net.sievert.modularmobai.gametest.GameTestTuning;
import net.sievert.modularmobai.gametest.RepeatGameTest;
import net.sievert.modularmobai.gametest.league.League;
import net.sievert.modularmobai.gametest.league.Loadouts;
import net.sievert.modularmobai.gametest.league.Opposition;
import net.sievert.modularmobai.gametest.league.Roster;
import net.sievert.modularmobai.gametest.terrain.TerrainSites;
import net.sievert.modularmobai.gametest.util.DeathCauses;

/**
 * What the fighter does about things shot at it, on natural ground, watched shot by shot rather than fight by fight.
 *
 * <p>Half the league fights at a distance and the bench says only who won. What it cannot say is <b>what happened to the
 * shots</b>: how many were fired at the agent at all, how many of them landed on it, how many were stopped by a shield,
 * how many were sent back, and whether the feet ever moved out of the way. Those five columns are what a rule about
 * projectiles is worth, and every one of them can move without a win rate moving at all — a fight against a ghast eight
 * blocks up is a timeout whatever the agent does about the fireballs.
 *
 * <pre>
 *   scripts\test.ps1 -Shots                                  the scripted fighter
 *   scripts\test.ps1 -Shots -Weights models\blast8\best.mbw   a network instead
 * </pre>
 *
 * <p>Four opponents, drawn in turn, one of each shape of shot the league fires: a <b>skeleton</b> and a <b>stray</b> for
 * the arrow, which nothing deflects and only the feet or a shield answer; a <b>pillager</b> for the bolt, which is faster
 * and flatter; and a <b>ghast</b> for the fireball, which is the one shot a swing sends back. Each on the hard rung, and
 * each on its own league clock and its own league distance, so a row here is the fight the bench is fighting.
 *
 * <p>The loadout turns over every time the opponents have been all the way round, so each opponent meets each loadout
 * equally often and the second table — the same fights by what the agent was carrying — is not a sample of the first. It
 * matters more here than anywhere: a shield is a whole answer to an arrow and four loadouts in ten carry nothing to raise.
 *
 * <p><b>Nothing here is asserted</b>, exactly as in {@link AgentPackFightGameTest} and {@link AgentCrowdedFightGameTest}
 * and for the same reason: a slot fights one fight after another out of a shared queue, which is {@code succeedWhen} and
 * not {@code onEachTick}, and the framework reads a failed assertion there as "not finished yet" and runs the whole thing
 * again next tick. What is wanted is numbers to read. The rules these numbers were used to write are held as rules in
 * {@link AgentTeacherGameTest}, in the mechanics suite, where an assertion fails a test.
 *
 * <p>Every column is read off the world rather than off the brain, so a network driving the agent is measured exactly as
 * the scripted fighter is. A sidestep in particular is not a flag anybody sets: it is the agent's own feet moving across
 * an arriving shot's line, which is what a sidestep <i>is</i> and is the only definition a network could be held to.
 */
@GameTestGroup
public class AgentShotsGameTest {

    /** The framework's own limit on a whole slot, only there to catch a slot that has stopped working. */
    private static final int SLOT_TIMEOUT_TICKS = 50_000_000;

    /**
     * The four opponents, fought in turn on the hard rung. One of each shape of shot: two archers, because a stray's arrow
     * slows what it hits and a skeleton's does not; a crossbow, whose bolt is faster and flatter than an arrow; and the
     * one thing in the league whose shot a swing can send back.
     */
    private static final String[] OPPONENTS = {"skeleton(hard)", "stray(hard)", "pillager(hard)", "ghast(hard)"};

    /** What the agent carries, drawn in turn over every loadout the league fields. */
    private static final List<String> LOADOUTS = Loadouts.enabled().stream().map(Loadout::name).toList();

    /** How many fights of each opponent are printed one line a tick, and how many ticks of each. */
    private static final int TRACED_FIGHTS = 1;
    private static final int TRACED_TICKS = 400;

    /** Below this, in blocks a tick, the agent's feet are not going anywhere worth calling a step. */
    private static final double STEPPING = 0.05D;

    /** How near an arriving shot's line has to pass for a step across it to be a step out of its way. */
    private static final double WOULD_HIT = 1.0D;

    /** How long before it arrives a shot is one the feet could still do anything about. */
    private static final double ARRIVING_TICKS = 12.0D;

    /** Which fight this process is on, so the opponents and the loadouts go round however many slots are running. */
    private static int drawn;

    /** What every fight against each opponent came to, and with each loadout, printed once they are all done. */
    private static final Tally[] TALLIES = new Tally[OPPONENTS.length];
    private static final Tally[] BY_LOADOUT = new Tally[LOADOUTS.size()];

    /** How many fights are still to come, so the summary is printed by whichever slot finishes the last of them. */
    private static int outstanding = -1;

    @GameTest(template = "arena", timeoutTicks = SLOT_TIMEOUT_TICKS)
    @RepeatGameTest(slots = true)
    public static void shotsAreWatchedShotByShot(GameTestHelper helper, int slot) {

        final Fights fights = new Fights(helper);

        helper.succeedWhen(() -> {

            fights.tick();

            if (!fights.done()) {

                throw new GameTestAssertException("Still fighting");
            }
        });
    }

    private static synchronized int next() {

        if (outstanding < 0) {

            outstanding = GameTestTuning.arenasInShard(GameTestTuning.arenaCount());

            for (int at = 0; at < TALLIES.length; at++) {

                TALLIES[at] = new Tally(OPPONENTS[at]);
            }

            for (int at = 0; at < BY_LOADOUT.length; at++) {

                BY_LOADOUT[at] = new Tally(LOADOUTS.get(at));
            }
        }

        return drawn++;
    }

    private static int opponentOf(int fight) {

        return fight % OPPONENTS.length;
    }

    private static int loadoutOf(int fight) {

        return fight / OPPONENTS.length % LOADOUTS.size();
    }

    /** Adds one finished fight to its opponent's tally and its loadout's, and prints them all when the last is in. */
    private static synchronized void fought(int fight, Tally tally) {

        TALLIES[opponentOf(fight)].add(tally);
        BY_LOADOUT[loadoutOf(fight)].add(tally);

        if (--outstanding > 0) {

            return;
        }

        System.out.println("========= What the fighter did about what was shot at it =========");
        System.out.println(Tally.HEADER);

        for (Tally by : TALLIES) {

            System.out.println(by.row());
        }

        System.out.println("  ----- the same fights by what the agent was carrying -----");

        for (Tally by : BY_LOADOUT) {

            System.out.println(by.row());
        }

        System.out.println("  shots at it: shots that were coming and took an enemy slot, a fight; hit: how many of them "
                + "took health off it, and lost: how much; shield: times the shield went up with a shot in the air; "
                + "sent back: shots it took over with a swing; aside: times its feet went across an arriving shot's line; "
                + "left: the health it ended on.");
        System.out.println("=".repeat(70));
    }

    /** One slot: fight after fight off the shared queue, each watched shot by shot. */
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
        private int clock = Roster.MELEE_TICKS;

        /** Which fight of the whole sitting this is, which says both its opponent and its loadout, and how it has gone. */
        private int which;
        private Tally tally;

        private long started;
        private boolean traced;

        /**
         * Every shot that has held one of the agent's slots this fight, and whether it has already been counted as sent
         * back. By identity, because a projectile is not equal to anything but itself and is gone the moment it lands.
         */
        private final Map<Projectile, Boolean> seen = new IdentityHashMap<>();

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

                    // A shooting match's room, which is the league's own twenty blocks: the whole question here is what
                    // the agent does while it crosses ground under fire.
                    Opposition wanted = opposition(this.which);

                    this.site = TerrainSites.claim(this.level, 1, wanted.start(), false);

                    if (this.site != null) {

                        this.holding = false;
                        this.phase = this.begin(wanted);
                    }
                }

                case FIGHTING -> {

                    this.watch();

                    if (this.opponent.isAlive() && this.agent.isAlive()) {

                        this.member.provoke(this.opponent, this.agent);
                    }

                    if (!this.agent.isAlive() || !this.opponent.isAlive()
                            || this.helper.getTick() - this.started >= this.clock) {

                        this.finish();
                        this.phase = Phase.ENDING;
                    }
                }

                case ENDING -> {

                    if (this.agent.brain().isFinished() || this.agent.isRemoved()) {

                        TerrainSites.release(this.level, this.site, false, this.agent, this.opponent);

                        this.seen.clear();
                        this.phase = Phase.IDLE;
                    }
                }

                case DONE -> {
                }
            }
        }

        private Phase begin(Opposition opposition) {

            this.member = opposition.mobs().get(0);
            this.clock = opposition.ticks();
            this.tally = new Tally(OPPONENTS[opponentOf(this.which)]);
            this.traced = TALLIES[opponentOf(this.which)].fights < TRACED_FIGHTS;

            this.agent = ModEntities.training(Species.trained()).create(this.level);
            Mob mob = this.member.type().create(this.level);

            if (this.agent == null || mob == null) {

                throw new IllegalStateException("Could not make the fighters for a shooting match");
            }

            this.opponent = mob;

            BlockPos ground = this.site.opponents().get(0);

            place(this.agent, this.site.agent());

            // Whatever flies starts that far up, which a site's open sky always leaves clear, and the fight's own patch of
            // sky grows by as much, so a ghast drifting up is still something the agent is allowed to see.
            place(this.opponent, ground.above(this.member.height()));

            AABB bounds = this.member.height() > 0
                    ? this.site.bounds().expandTowards(0.0D, this.member.height(), 0.0D) : this.site.bounds();

            this.opponent.finalizeSpawn(this.level, opposition.spawnDifficulty(this.level, ground), MobSpawnType.EVENT,
                    this.member.groupData());
            this.member.prepare(this.opponent, this.level.getRandom());

            this.level.addFreshEntity(this.opponent);
            this.level.addFreshEntity(this.agent);

            Loadouts.named(LOADOUTS.get(loadoutOf(this.which))).equip(this.agent);
            this.agent.startEpisode(new Episode(this.clock, bounds, List.of(this.opponent)));

            this.member.provoke(this.opponent, this.agent);

            this.seen.clear();
            this.started = this.helper.getTick();

            return Phase.FIGHTING;
        }

        /** What was in the air this tick, and what the agent did about it. */
        private void watch() {

            EnemySlots view = this.agent.brain().enemySlots();

            Projectile nearest = null;
            double nearestAway = Double.MAX_VALUE;
            int inTheAir = 0;

            for (int slot = 0; slot < ObservationSchema.ENEMY_SLOTS; slot++) {

                if (!(view.occupant(slot) instanceof Projectile shot)) {

                    continue;
                }

                inTheAir++;

                // Counted the first time it holds a slot: one shot is one shot however many ticks it flies for.
                Boolean sentBack = this.seen.get(shot);

                if (sentBack == null) {

                    this.seen.put(shot, Boolean.FALSE);
                    this.tally.shot();
                    sentBack = Boolean.FALSE;
                }

                // A deflection is written in the shot's own owner: vanilla's deflect hands the projectile to whoever
                // swung at it, so a shot the agent owns is a shot the agent sent back, whatever it was before.
                if (!sentBack && shot.getOwner() == this.agent) {

                    this.seen.put(shot, Boolean.TRUE);
                    this.tally.sentBack();
                }

                double away = this.agent.distanceTo(shot);

                if (away < nearestAway) {

                    nearestAway = away;
                    nearest = shot;
                }
            }

            // How far off the nearest shot's line the agent stands, how long before it gets here, and which way its own
            // feet are going across that line. All three off the world, so a network is measured the same way.
            double miss = -1.0D;
            double arriving = -1.0D;
            boolean aside = false;

            if (nearest != null) {

                Vec3 flight = nearest.getDeltaMovement();
                double speed = flight.length();

                if (speed > 1.0E-4D) {

                    Vec3 towards = this.agent.getBoundingBox().getCenter().subtract(nearest.position());
                    Vec3 way = flight.scale(1.0D / speed);

                    double along = towards.dot(way);

                    miss = Math.sqrt(Math.max(0.0D, towards.lengthSqr() - along * along));
                    arriving = along / speed;

                    Vec3 feet = this.agent.getDeltaMovement();
                    double across = Math.abs(feet.x * way.z - feet.z * way.x);
                    double with = Math.abs(feet.x * way.x + feet.z * way.z);

                    aside = arriving >= 0.0D && arriving <= ARRIVING_TICKS && miss <= WOULD_HIT
                            && across > STEPPING && across > with;
                }
            }

            // A blow taken, and whether a shot threw it: health that went down this tick with the agent's own last damage
            // source saying what it was. A fireball counts twice over — the shot and the blast it leaves — and both are
            // damage the agent took off something that was fired at it.
            float health = this.agent.getHealth();
            boolean hurt = this.tally.ticks > 0 && health < this.tally.health;
            DamageSource source = this.agent.getLastDamageSource();

            boolean byShot = hurt && source != null
                    && (source.is(DamageTypeTags.IS_PROJECTILE) || source.is(DamageTypeTags.IS_EXPLOSION)
                            || source.getDirectEntity() instanceof Projectile);

            this.tally.tick(inTheAir, this.agent.isBlocking(), aside, byShot, byShot ? this.tally.health - health : 0.0F,
                    health);

            if (this.traced && this.tally.ticks <= TRACED_TICKS && (inTheAir > 0 || hurt)) {

                Constants.LOG.info(String.format(Locale.ROOT,
                        "shots=%-28s tick=%3d air=%d near=%5.2fb miss=%5.2f in=%5.1ft size=%4.2f block=%s aside=%s "
                                + "press=%s hurt=%s health=%.1f",
                        OPPONENTS[opponentOf(this.which)] + " " + LOADOUTS.get(loadoutOf(this.which)),
                        this.tally.ticks, inTheAir, nearest == null ? -1.0D : nearestAway, miss, arriving,
                        nearest == null ? -1.0F : nearest.getBbWidth(), this.agent.isBlocking() ? "y" : "n",
                        aside ? "y" : "n", this.agent.executed().attacked ? "y" : "n", byShot ? "shot" : hurt ? "y" : "-",
                        health));
            }
        }

        private void finish() {

            boolean standing = this.agent.isAlive();
            boolean won = standing && this.opponent.isDeadOrDying();
            boolean timedOut = standing && this.opponent.isAlive();

            // Scored exactly as the league scores it, and this is not bookkeeping: the reward going terminal is what tells
            // the driver the agent has taken its last step, and a fight that never says so leaves its slot waiting for a
            // step that never comes and the suite hanging after its last fight.
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

            this.tally.outcome(won, timedOut);
            fought(this.which, this.tally);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------

    /** The opposition this fight is against, by name, refused outright rather than quietly fought against something else. */
    private static Opposition opposition(int fight) {

        Opposition one = Opposition.named(OPPONENTS[opponentOf(fight)]);

        if (one == null) {

            throw new IllegalStateException("This build does not field the " + OPPONENTS[opponentOf(fight)]
                    + " these fights are about");
        }

        return one;
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

    /** What the fights against one opponent came to, and what one fight came to, which are added up the same way. */
    private static final class Tally {

        private static final String HEADER = String.format(Locale.ROOT,
                "  %-19s %6s %7s %9s %7s %11s %6s %7s %7s %10s %6s %6s",
                "opponent", "fights", "won %", "timed out", "ticks", "shots at it", "hit", "lost", "shield", "sent back",
                "aside", "left");

        private final String label;

        private int fights;
        private int wins;
        private int timeouts;

        private int ticks;

        /** Ticks with at least one shot of theirs in the air and coming, which is what the columns below are about. */
        private int airTicks;

        private int shots;
        private int hits;
        private float lost;
        private int raises;
        private int sentBack;
        private int asides;

        private boolean blocking;
        private boolean stepping;

        /** The agent's health as the tick before left it, which is how a blow taken is seen without a damage listener. */
        private float health;

        private Tally(String label) {

            this.label = label;
        }

        private void shot() {

            this.shots++;
        }

        private void sentBack() {

            this.sentBack++;
        }

        private void tick(int inTheAir, boolean blocking, boolean aside, boolean hurt, float lost, float health) {

            this.ticks++;
            this.airTicks += inTheAir > 0 ? 1 : 0;

            // Rising edges, both of them: a shield held up for a second is one raise and a step across a line is one step,
            // however many ticks either lasts.
            this.raises += blocking && !this.blocking && inTheAir > 0 ? 1 : 0;
            this.asides += aside && !this.stepping ? 1 : 0;

            this.blocking = blocking;
            this.stepping = aside;

            this.hits += hurt ? 1 : 0;
            this.lost += lost;
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
            this.airTicks += fight.airTicks;
            this.shots += fight.shots;
            this.hits += fight.hits;
            this.lost += fight.lost;
            this.raises += fight.raises;
            this.sentBack += fight.sentBack;
            this.asides += fight.asides;

            // The health a fight ended on, averaged over the fights rather than carried from the last of them.
            this.health += fight.health;
        }

        private String row() {

            double fights = Math.max(1, this.fights);

            return String.format(Locale.ROOT,
                    "  %-19s %6d %7.1f %9.1f %7.0f %11.2f %6.2f %7.2f %7.2f %10.2f %6.2f %6.1f   air %.0f%%",
                    this.label, this.fights, 100.0D * this.wins / fights, 100.0D * this.timeouts / fights,
                    this.ticks / fights, this.shots / fights, this.hits / fights, this.lost / fights,
                    this.raises / fights, this.sentBack / fights, this.asides / fights, this.health / fights,
                    100.0D * this.airTicks / Math.max(1, this.ticks));
        }
    }
}
