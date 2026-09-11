package net.sievert.modularmobai.gametest.league;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.util.RandomSource;
import net.sievert.modularmobai.Constants;
import net.sievert.modularmobai.gametest.mixin.SlimeInvoker;

/**
 * Every mob in the league, and what each needs to be a fair opponent one on one: spawned the way it would spawn on its
 * own, then made to fight the agent whatever it thinks of mobs that are not players, and kept at it for the minute.
 *
 * <pre>
 *   -Dmodular_mob_ai.league.opponents=NAME,NAME   only these, by name; every one of them unless given
 * </pre>
 *
 * <p>Nearly every hostile mob that fights on the ground is here, and three that fight only when provoked, since a player
 * who hits one has a fight on their hands: an enderman, a piglin, and a zombified piglin. Left out are the bosses (the
 * ender dragon, the wither, the warden and the elder guardian), whatever flies (the ghast, the blaze, the phantom and the
 * vex) or lives in water (the guardian), and three that cannot really fight one on one: the evoker fights by summoning
 * vexes, which fly; the shulker never leaves its block; and the illusioner never spawns in a survival world. Animals and
 * golems that only defend themselves, wolves, bears and iron golems, are not hostile mobs at all.
 *
 * <p>Every one gets the finalizeSpawn it would get spawning on its own, which is what arms it: the vindicator's axe, the
 * skeleton's bow, the piglin's golden sword or crossbow, now and then some armour. On top of that:
 *
 * <ul>
 *   <li>Babies are grown up. A baby piglin or hoglin never attacks at all, and a baby zombie is a different fight from
 *       the zombie a rating is meant to be for; so is the one in twenty that rides a chicken, and the spider in a hundred
 *       with a skeleton on its back.</li>
 *   <li>Piglins, piglin brutes and hoglins do not turn into zombies. Out of the Nether they would after fifteen seconds,
 *       and the fight would be against something else.</li>
 *   <li>A slime or a magma cube is always the biggest there is. The smallest slime does no damage at all, and a middling
 *       one little more.</li>
 *   <li>A slime hurts what touches it only if that is a player, so the league has it touch the agent as the game has it
 *       touch a player, see {@link SlimeInvoker}. A breeze will only fight a player or an iron golem, and fights the
 *       agent as it would a player, see {@link net.sievert.modularmobai.gametest.mixin.BreezeMixin}.</li>
 * </ul>
 *
 * <p>Most mobs fight whatever their target is, and only look for one among players, so the agent is made the target and
 * made it again whenever it lapses: a mob that loses sight of its target for a few seconds lets go of it. Neutral mobs
 * are angered the same way, and calm down again after half a minute unless angered anew, which they are. Mobs that think
 * with a brain rather than goals, the piglins, hoglins, zoglin and breeze, are given the agent as their attack target in
 * their memory; a piglin also has to be angry at it, or it decides the target is not worth it.
 *
 * <p>League fights happen at midnight with mob griefing off, see {@link League#prepareWorld}: at noon the undead burn to
 * death in the open, a spider in daylight lets its target go, and an enderman teleports away; and a creeper's crater or an
 * enderman's stolen block would stay in a kept terrain world for every fight after.
 */
public final class Roster {

    private Roster() {}

    private static final String PROPERTY = "modular_mob_ai.league.opponents";

    /** How big a slime or a magma cube is made: the biggest a natural one comes. */
    private static final int SLIME_SIZE = 4;

    /** How long a piglin's anger lasts once it is angered, and how often it is angered again well before that runs out. */
    private static final long ANGER_TICKS = 600L;
    private static final int ANGER_AGAIN_TICKS = 20;

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
     * @param name      what it is called in the results and the ratings: the entity's own name
     * @param spawnData what its finalizeSpawn is handed, or null for what it would pick itself
     */
    public record Member(String name, EntityType<? extends Mob> type, @Nullable Supplier<SpawnGroupData> spawnData,
                         Preparation preparation, Provocation provocation) {

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

    /** Every mob in the league, in the order a run without a trainer goes through them. */
    public static final List<Member> ALL = List.of(
            member("zombie", EntityType.ZOMBIE, GROWN_ZOMBIE, AS_SPAWNED, Roster::target),
            member("husk", EntityType.HUSK, GROWN_ZOMBIE, AS_SPAWNED, Roster::target),
            member("drowned", EntityType.DROWNED, GROWN_ZOMBIE, AS_SPAWNED, Roster::target),
            member("zombie_villager", EntityType.ZOMBIE_VILLAGER, GROWN_ZOMBIE, AS_SPAWNED, Roster::target),
            member("skeleton", EntityType.SKELETON, null, AS_SPAWNED, Roster::target),
            member("stray", EntityType.STRAY, null, AS_SPAWNED, Roster::target),
            member("bogged", EntityType.BOGGED, null, AS_SPAWNED, Roster::target),
            member("wither_skeleton", EntityType.WITHER_SKELETON, null, AS_SPAWNED, Roster::target),
            member("spider", EntityType.SPIDER, null, AS_SPAWNED, Roster::target),
            member("cave_spider", EntityType.CAVE_SPIDER, null, AS_SPAWNED, Roster::target),
            member("creeper", EntityType.CREEPER, null, AS_SPAWNED, Roster::target),
            member("vindicator", EntityType.VINDICATOR, null, AS_SPAWNED, Roster::target),
            member("pillager", EntityType.PILLAGER, null, AS_SPAWNED, Roster::target),
            member("witch", EntityType.WITCH, null, AS_SPAWNED, Roster::target),
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
            member("breeze", EntityType.BREEZE, null, AS_SPAWNED, Roster::attack));

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

    /** The member of that name this process fields, or null. */
    @Nullable
    public static Member named(String name) {

        for (Member member : fielded()) {

            if (member.name().equals(name)) {

                return member;
            }
        }

        return null;
    }

    private static Member member(String name, EntityType<? extends Mob> type, @Nullable Supplier<SpawnGroupData> spawnData,
                                 Preparation preparation, Provocation provocation) {

        return new Member(name, type, spawnData, preparation, provocation);
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
}
