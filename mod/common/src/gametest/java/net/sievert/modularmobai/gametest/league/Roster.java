package net.sievert.modularmobai.gametest.league;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.util.RandomSource;
import net.sievert.modularmobai.Constants;
import net.sievert.modularmobai.gametest.mixin.SlimeInvoker;
import net.sievert.modularmobai.gametest.terrain.TerrainSites;

/**
 * Every mob in the league, and what each needs to be a fair opponent one on one: spawned the way it would spawn on its
 * own, then made to fight the agent whatever it thinks of mobs that are not players, and kept at it for the minute.
 *
 * <pre>
 *   -Dmodular_mob_ai.league.opponents=NAME,NAME   only these, by name; every one of them unless given
 * </pre>
 *
 * <p>Every hostile mob a player meets in a fair fight is here: the ones that walk, the ones that fly, the ones that only
 * fight when provoked, and the two golems a player builds. Left out are the bosses (the ender dragon, the wither and the
 * elder guardian), the guardian, which only fights in water, the shulker, which never leaves its block, and the
 * illusioner, which never spawns in a survival world.
 *
 * <p>Every one gets the finalizeSpawn it would get spawning on its own, which is what arms it: the vindicator's axe, the
 * skeleton's bow, the piglin's golden sword or crossbow, the vex's iron sword, now and then some armour. On top of that:
 *
 * <ul>
 *   <li>Babies are grown up. A baby piglin or hoglin never attacks at all, and a baby zombie is a different fight from
 *       the zombie a rating is meant to be for; so is the one in twenty that rides a chicken, and the spider in a hundred
 *       with a skeleton on its back. A wolf, a polar bear and a bee come as cubs as often as one in twenty.</li>
 *   <li>Piglins, piglin brutes and hoglins do not turn into zombies. Out of the Nether they would after fifteen seconds,
 *       and the fight would be against something else.</li>
 *   <li>A slime or a magma cube is always the biggest there is. The smallest slime does no damage at all, and a middling
 *       one little more.</li>
 *   <li>A snow golem cannot melt. Its own aiStep burns it a heart a tick in any biome warm enough to rain, which is a
 *       third of the terrain library, and a golem that dies of the weather hands the agent a win it never fought for. It
 *       is given fire resistance for good, which is what that damage goes through.</li>
 *   <li>A slime hurts what touches it only if that is a player, so the league has it touch the agent as the game has it
 *       touch a player, see {@link SlimeInvoker}. A breeze will only fight a player or an iron golem, and fights the
 *       agent as it would a player, see {@link net.sievert.modularmobai.gametest.mixin.BreezeMixin}. A bee dies of its
 *       own sting, so in the league it never counts as having stung, see
 *       {@link net.sievert.modularmobai.gametest.mixin.BeeMixin}.</li>
 *   <li>Whatever flies starts in the air above the ground the fight was laid out on, {@link Member#height}, since a
 *       ghast is four blocks across and would otherwise start wedged in a tree. Both starting spots have open sky over
 *       them, so straight up is always clear, see {@link net.sievert.modularmobai.gametest.terrain.TerrainSites}.</li>
 * </ul>
 *
 * <p>Most mobs fight whatever their target is, and only look for one among players, so the agent is made the target and
 * made it again whenever it lapses: a mob that loses sight of its target for a few seconds lets go of it. Neutral mobs,
 * from the enderman to the wolf, are angered the same way, and calm down again after half a minute unless angered anew,
 * which they are. Mobs that think with a brain rather than goals, the piglins, hoglins, zoglin, breeze and warden, are
 * given the agent as their attack target in their memory; a piglin also has to be angry at it, or it decides the target is
 * not worth it, and a warden has its anger at the agent topped up so its own mind keeps it there between roars.
 *
 * <p>An evoker fights by calling vexes rather than by touching anything, and those are entities no fight spawned: the
 * sweep that keeps the world clear of wildlife would take each one away within the second and leave the evoker with
 * nothing but its fangs. So every vex it calls is taken into the fight as it appears, and swept up with the rest when the
 * fight is over. What the fangs and the vexes do lands on the agent all the same, but only the evoker itself counts in
 * the "hit it" column.
 *
 * <p>The warden is a benchmark, not a lesson. It has ten times the health of anything else here and kills the agent in a
 * blow or two, and the reward has no way to pay for getting away alive, so every fight against it is a loss whatever the
 * agent does. It keeps its rating, and its share of the training fights is capped at {@link Member#trainingCap} so one
 * hopeless opponent cannot crowd out the fights there is something to learn from.
 *
 * <p>League fights happen at midnight with the weather held clear, which every game test does ({@code GameTestServerMixin}),
 * and with mob griefing off, which is the league's own, see {@link League#prepareWorld}:
 * at noon the undead burn to death in the open, a spider in daylight lets its target go, and an enderman teleports away;
 * rain hurts a blaze and a snow golem and teleports an enderman; and a creeper's crater or an enderman's stolen block
 * would stay in a kept terrain world for every fight after.
 */
public final class Roster {

    private Roster() {}

    private static final String PROPERTY = "modular_mob_ai.league.opponents";

    /** How big a slime or a magma cube is made: the biggest a natural one comes. */
    private static final int SLIME_SIZE = 4;

    /** How long a piglin's anger lasts once it is angered, and how often it is angered again well before that runs out. */
    private static final long ANGER_TICKS = 600L;
    private static final int ANGER_AGAIN_TICKS = 20;

    /**
     * How far a warden's anger at the agent is pushed up, and how often. A warden picks its target by anger rather than by
     * seeing anything, and its anger drains a point every few seconds, so being handed the target is not enough on its own:
     * topped up, the warden's own mind keeps coming back to the agent between roars. Silent, since a roar's sound would be
     * played every time.
     */
    private static final int WARDEN_ANGER = 35;
    private static final int WARDEN_ANGER_AGAIN_TICKS = 40;

    /**
     * The largest share of a run's training fights the warden may take. There is nothing to learn from an opponent that
     * cannot be beaten, and with the roster this long an even spread of the floor alone would hand it about seven fights in
     * a thousand; this leaves it two, and gives the rest to opponents the agent has a chance against. It has to stay above
     * zero: an opponent with no share at all drops out of the evaluation draw as well, and its rating with it.
     */
    private static final double WARDEN_TRAINING_CAP = 0.002D;

    /** How far a flyer starts above the ground the fight was laid out on. A ghast is four blocks across, hence the room. */
    private static final int GHAST_HEIGHT = 8;
    private static final int PHANTOM_HEIGHT = 6;
    private static final int VEX_HEIGHT = 3;
    private static final int HOVER_HEIGHT = 2;

    /**
     * How long a fight against each kind of opponent is given, and how far apart it starts.
     *
     * <p>A melee fight is over in a few hundred ticks or it is not going to happen, and a minute has always been the clock.
     * A fight against something that shoots is a different shape: the agent has to cross the ground while it is being shot
     * at, and if it has a bow of its own there are twenty ticks in every shot, so ninety seconds. Something that flies
     * cannot be reached at all until the agent shoots, and a ghast drifts as it fires, so two minutes.
     *
     * <p>Room matters for the same reason. Melee starts seven to eleven blocks apart, which is a couple of seconds of
     * walking. Twenty is a bow's own range and far enough that the agent has to cover ground under fire, or kite something
     * away, without either fighter ever being near the edge of the site. More room than the site holds is not a matchup's
     * to ask for, see {@link net.sievert.modularmobai.gametest.GameTestTuning#siteRadius()}.
     *
     * <p>The clock is also what fast and slow are paid against, so a win in six hundred ticks pays more of the speed bonus
     * in a two minute fight than in a one minute one. That is the intended reading: fast for the fight it was.
     */
    public static final int MELEE_TICKS = 1200;
    private static final int RANGED_TICKS = 1800;
    private static final int FLYING_TICKS = 2400;
    private static final int MELEE_START = 0;
    private static final int RANGED_START = 20;

    /** How far from an evoker its vexes are looked for, which is well inside its own site and nowhere near the next. */
    private static final double VEX_REACH = 16.0D;

    /** Readies a mob for a fair fight, after the finalizeSpawn that gave it what it spawns with. */
    @FunctionalInterface
    interface Preparation {

        void prepare(Mob mob, RandomSource random);
    }

    /** Keeps a mob on the agent: called as the fight starts and on every tick after. */
    @FunctionalInterface
    interface Provocation {

        void provoke(Mob mob, LivingEntity agent);
    }

    /**
     * One kind of mob in the league.
     *
     * @param name        what it is called in the results and the ratings: the entity's own name
     * @param spawnData   what its finalizeSpawn is handed, or null for what it would pick itself
     * @param height      how far above the ground it starts, for whatever flies; zero for whatever walks
     * @param ticks        how long a fight against it is given
     * @param start       how far away it starts, or zero for the ordinary seven to eleven blocks
     * @param trainingCap the largest share of a run's training fights it may take, 1 for no cap at all
     */
    public record Member(String name, EntityType<? extends Mob> type, @Nullable Supplier<SpawnGroupData> spawnData,
                         Preparation preparation, Provocation provocation, int height, int ticks, int start,
                         double trainingCap) {

        /**
         * The same mob, starting that far up in the air, with a flyer's clock and a flyer's room: it cannot be reached in
         * melee at all, so the fight is a shooting match or it is nothing.
         */
        public Member flyingAt(int height) {

            return new Member(this.name, this.type, this.spawnData, this.preparation, this.provocation, height, FLYING_TICKS,
                    RANGED_START, this.trainingCap);
        }

        /** The same mob with a shooting match's clock and room, for one that fights from a distance on the ground. */
        public Member ranged() {

            return new Member(this.name, this.type, this.spawnData, this.preparation, this.provocation, this.height,
                    RANGED_TICKS, RANGED_START, this.trainingCap);
        }

        /** The same mob, given at most that share of a run's training fights. */
        public Member cappedAt(double share) {

            return new Member(this.name, this.type, this.spawnData, this.preparation, this.provocation, this.height,
                    this.ticks, this.start, share);
        }

        /** What its finalizeSpawn is handed, or null for what it would pick itself. */
        @Nullable
        public SpawnGroupData groupData() {

            return this.spawnData == null ? null : this.spawnData.get();
        }

        /** Grows it up, keeps it from turning, sizes it, and takes away anything riding it or ridden by it. */
        public void prepare(Mob mob, RandomSource random) {

            for (Entity passenger : List.copyOf(mob.getPassengers())) {

                passenger.discard();
            }

            mob.stopRiding();
            this.preparation.prepare(mob, random);
        }

        /** Makes it go for the agent, if it is not already. */
        public void provoke(Mob mob, LivingEntity agent) {

            this.provocation.provoke(mob, agent);
        }
    }

    private static final Preparation AS_SPAWNED = (mob, random) -> {};

    /** Neither a baby nor a chicken jockey, for every kind of zombie. */
    private static final Supplier<SpawnGroupData> GROWN_ZOMBIE = () -> new Zombie.ZombieGroupData(false, false);

    /** Grown up, for an animal that comes as a cub one time in twenty: a wolf, a polar bear, a bee. */
    private static final Supplier<SpawnGroupData> GROWN_ANIMAL = () -> new AgeableMob.AgeableMobGroupData(false);

    /** Every mob in the league, in the order a run without a trainer goes through them. */
    public static final List<Member> ALL = List.of(
            member("zombie", EntityType.ZOMBIE, GROWN_ZOMBIE, AS_SPAWNED, Roster::target),
            member("husk", EntityType.HUSK, GROWN_ZOMBIE, AS_SPAWNED, Roster::target),
            member("drowned", EntityType.DROWNED, GROWN_ZOMBIE, AS_SPAWNED, Roster::target),
            member("zombie_villager", EntityType.ZOMBIE_VILLAGER, GROWN_ZOMBIE, AS_SPAWNED, Roster::target),
            member("skeleton", EntityType.SKELETON, null, AS_SPAWNED, Roster::target).ranged(),
            member("stray", EntityType.STRAY, null, AS_SPAWNED, Roster::target).ranged(),
            member("bogged", EntityType.BOGGED, null, AS_SPAWNED, Roster::target).ranged(),
            member("wither_skeleton", EntityType.WITHER_SKELETON, null, AS_SPAWNED, Roster::target),
            member("spider", EntityType.SPIDER, null, AS_SPAWNED, Roster::target),
            member("cave_spider", EntityType.CAVE_SPIDER, null, AS_SPAWNED, Roster::target),
            member("creeper", EntityType.CREEPER, null, AS_SPAWNED, Roster::target),
            member("vindicator", EntityType.VINDICATOR, null, AS_SPAWNED, Roster::target),
            member("pillager", EntityType.PILLAGER, null, AS_SPAWNED, Roster::target).ranged(),
            member("witch", EntityType.WITCH, null, AS_SPAWNED, Roster::target).ranged(),
            member("ravager", EntityType.RAVAGER, null, AS_SPAWNED, Roster::target),
            member("enderman", EntityType.ENDERMAN, null, AS_SPAWNED, Roster::target),
            member("silverfish", EntityType.SILVERFISH, null, AS_SPAWNED, Roster::target),
            member("endermite", EntityType.ENDERMITE, null, AS_SPAWNED, Roster::target),
            member("slime", EntityType.SLIME, null, Roster::biggest, Roster::touch),
            member("magma_cube", EntityType.MAGMA_CUBE, null, Roster::biggest, Roster::touch),
            member("zombified_piglin", EntityType.ZOMBIFIED_PIGLIN, GROWN_ZOMBIE, AS_SPAWNED, Roster::target),
            member("piglin", EntityType.PIGLIN, null, Roster::grownPiglin, Roster::anger),
            member("piglin_brute", EntityType.PIGLIN_BRUTE, null, Roster::unturning, Roster::anger),
            member("hoglin", EntityType.HOGLIN, null, Roster::grownHoglin, Roster::attack),
            member("zoglin", EntityType.ZOGLIN, null, AS_SPAWNED, Roster::attack),
            member("breeze", EntityType.BREEZE, null, AS_SPAWNED, Roster::attack).ranged(),
            member("evoker", EntityType.EVOKER, null, AS_SPAWNED, Roster::summon).ranged(),
            member("blaze", EntityType.BLAZE, null, AS_SPAWNED, Roster::target).flyingAt(HOVER_HEIGHT),
            member("ghast", EntityType.GHAST, null, AS_SPAWNED, Roster::target).flyingAt(GHAST_HEIGHT),
            member("phantom", EntityType.PHANTOM, null, AS_SPAWNED, Roster::target).flyingAt(PHANTOM_HEIGHT),
            member("vex", EntityType.VEX, null, AS_SPAWNED, Roster::target).flyingAt(VEX_HEIGHT),
            member("bee", EntityType.BEE, GROWN_ANIMAL, Roster::grown, Roster::enrage).flyingAt(HOVER_HEIGHT),
            member("wolf", EntityType.WOLF, GROWN_ANIMAL, Roster::grown, Roster::enrage),
            member("polar_bear", EntityType.POLAR_BEAR, GROWN_ANIMAL, Roster::grown, Roster::enrage),
            member("iron_golem", EntityType.IRON_GOLEM, null, AS_SPAWNED, Roster::enrage),
            member("snow_golem", EntityType.SNOW_GOLEM, null, Roster::unmelting, Roster::target).ranged(),
            member("warden", EntityType.WARDEN, null, AS_SPAWNED, Roster::rouse).cappedAt(WARDEN_TRAINING_CAP));

    /** The ones this process fields, read once. */
    private static List<Member> fielded;

    public static synchronized List<Member> fielded() {

        if (fielded == null) {

            String named = System.getProperty(PROPERTY, "").trim();

            if (named.isEmpty()) {

                fielded = ALL;
            }

            else {

                List<String> names = Arrays.stream(named.toLowerCase(Locale.ROOT).split(",")).map(String::trim).toList();
                List<Member> chosen = new ArrayList<>();

                for (Member member : ALL) {

                    if (names.contains(member.name())) {

                        chosen.add(member);
                    }
                }

                fielded = List.copyOf(chosen);
                Constants.LOG.info("League fights field {} of the {} mobs: {}", fielded.size(), ALL.size(),
                        fielded.stream().map(Member::name).toList());
            }
        }

        return fielded;
    }

    /**
     * The member of that name whether this process fields it on its own or not, for the squads, whose members are named in
     * code: asking for one squad by name should not also need every mob on it named. There being no such mob is a mistake
     * in the squad rather than anything a run can ask for, so it is refused rather than quietly dropped. What a league
     * opponent's name means is {@link Opposition#named}, which is what everything else asks.
     */
    public static Member any(String name) {

        for (Member member : ALL) {

            if (member.name().equals(name)) {

                return member;
            }
        }

        throw new IllegalArgumentException("There is no league mob called '" + name + "'");
    }

    private static Member member(String name, EntityType<? extends Mob> type, @Nullable Supplier<SpawnGroupData> spawnData,
                                 Preparation preparation, Provocation provocation) {

        return new Member(name, type, spawnData, preparation, provocation, 0, MELEE_TICKS, MELEE_START, 1.0D);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Preparations
    // ---------------------------------------------------------------------------------------------------------------

    private static void biggest(Mob mob, RandomSource random) {

        ((Slime) mob).setSize(SLIME_SIZE, true);
    }

    private static void unturning(Mob mob, RandomSource random) {

        ((AbstractPiglin) mob).setImmuneToZombification(true);
    }

    /**
     * A piglin that came out a baby is grown up and given what a grown one spawns with, a crossbow or a golden sword, one
     * or the other at even odds.
     */
    private static void grownPiglin(Mob mob, RandomSource random) {

        Piglin piglin = (Piglin) mob;
        piglin.setImmuneToZombification(true);

        if (piglin.isBaby()) {

            piglin.setBaby(false);
            piglin.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(random.nextBoolean() ? Items.CROSSBOW : Items.GOLDEN_SWORD));
        }
    }

    private static void grownHoglin(Mob mob, RandomSource random) {

        Hoglin hoglin = (Hoglin) mob;
        hoglin.setImmuneToZombification(true);
        hoglin.setBaby(false);
    }

    /** Grown up, for an animal whose group data lets one through as a cub anyway. */
    private static void grown(Mob mob, RandomSource random) {

        ((AgeableMob) mob).setBaby(false);
    }

    /**
     * A snow golem that cannot melt. Its own aiStep takes a heart off it every tick in a biome warm enough to rain, and
     * another in water or rain, and that damage is fire damage, which fire resistance turns away for good. Without it a
     * third of the library's sites would kill the golem on their own and pay the agent for a fight it never had.
     */
    private static void unmelting(Mob mob, RandomSource random) {

        mob.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, MobEffectInstance.INFINITE_DURATION, 0, false, false));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Provocations
    // ---------------------------------------------------------------------------------------------------------------

    /** A mob that goes by its goals fights its target, and a neutral one is angered by having one. */
    private static void target(Mob mob, LivingEntity agent) {

        if (mob.getTarget() != agent) {

            mob.setTarget(agent);
        }
    }

    /**
     * A slime fights its target like any mob, and hurts it the way it hurts a player: whenever the two touch, within a
     * block of the player's sides and half a block of its head and feet, as Player#aiStep finds what it has walked into.
     */
    private static void touch(Mob mob, LivingEntity agent) {

        target(mob, agent);

        SlimeInvoker slime = (SlimeInvoker) mob;

        if (slime.modular_mob_ai$isDealsDamage() && agent.getBoundingBox().inflate(1.0D, 0.5D, 1.0D).intersects(mob.getBoundingBox())) {

            slime.modular_mob_ai$dealDamage(agent);
        }
    }

    /**
     * A mob that thinks with a brain fights whatever its memory holds as its attack target. One that has not been able to
     * reach its target for ten seconds gives up on it, and would give up again the moment it was handed it back, so its
     * memory of having tried is let go at the same time, as a piglin's is when something angers it afresh.
     */
    private static void attack(Mob mob, LivingEntity agent) {

        Brain<?> brain = mob.getBrain();

        if (brain.getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null) != agent) {

            brain.eraseMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
            brain.setMemory(MemoryModuleType.ATTACK_TARGET, agent);
        }
    }

    /**
     * A piglin only keeps a target it is angry at, and lets go of any other on the next tick, so it is angered as well,
     * and angered again well before the anger would run out.
     */
    private static void anger(Mob mob, LivingEntity agent) {

        Brain<?> brain = mob.getBrain();
        boolean angry = brain.getMemory(MemoryModuleType.ANGRY_AT).filter(agent.getUUID()::equals).isPresent();

        if (!angry || mob.tickCount % ANGER_AGAIN_TICKS == 0) {

            brain.setMemoryWithExpiry(MemoryModuleType.ANGRY_AT, agent.getUUID(), ANGER_TICKS);
        }

        attack(mob, agent);
    }

    /**
     * A mob that fights whatever it is angry at as well as whatever its target is: a wolf, a polar bear, a bee, an iron
     * golem. Each goes for a player only once something has provoked it, and a bee will not so much as swing unless it is
     * angry, so the anger is renewed along with the target rather than left to run out after half a minute.
     */
    private static void enrage(Mob mob, LivingEntity agent) {

        target(mob, agent);

        NeutralMob neutral = (NeutralMob) mob;

        if (neutral.getRemainingPersistentAngerTime() <= ANGER_AGAIN_TICKS) {

            neutral.setRemainingPersistentAngerTime((int) ANGER_TICKS);
            neutral.setPersistentAngerTarget(agent.getUUID());
        }
    }

    /**
     * A warden, which thinks with a brain and picks what to fight by how angry it is rather than by what it can see. Being
     * handed the attack target gets it fighting at once; the anger, topped up before it can drain away, is what keeps its
     * own mind on the agent through a roar, a sniff or a dig.
     */
    private static void rouse(Mob mob, LivingEntity agent) {

        Warden warden = (Warden) mob;

        if (warden.tickCount % WARDEN_ANGER_AGAIN_TICKS == 0) {

            warden.increaseAngerAt(agent, WARDEN_ANGER, false);
        }

        attack(mob, agent);
    }

    /**
     * An evoker, which never touches its target: it calls vexes and raises fangs under it. The vexes are entities no fight
     * spawned, so the sweep for wildlife would take each one away within the second; every one is taken into the fight as
     * it appears instead, and sent after the agent, which is what its own copy of its summoner's target would do anyway.
     * Whatever it left behind goes when the site is cleaned up after the fight.
     */
    private static void summon(Mob mob, LivingEntity agent) {

        target(mob, agent);

        List<Vex> called = mob.level().getEntitiesOfClass(Vex.class, mob.getBoundingBox().inflate(VEX_REACH),
                vex -> !vex.getTags().contains(TerrainSites.TAG));

        for (Vex vex : called) {

            vex.addTag(TerrainSites.TAG);
            vex.setTarget(agent);
        }
    }
}
