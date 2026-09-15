package net.sievert.modularmobai.entity;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.sievert.modularmobai.Constants;
import net.sievert.modularmobai.brain.schema.Species;
import net.sievert.modularmobai.entity.agent.AgentMob;

/**
 * The bodies, as the game registers them: one entity type per mob per body, built from what the bodies themselves declare.
 *
 * <p>Nothing here names a body. The list comes from {@link Species#ALL} and the shape of each mob from
 * {@link Species#mobs()}, so a body declared there is registered on both loaders, given the same attributes and the same
 * renderer, and can be asked for by {@link #world} or {@link #training} without a line being added here or in either
 * loader. That is the whole point: a mob per loader per body used to be three edits that could each be forgotten
 * separately, and forgetting one left a body that trained on Fabric and could not be spawned on NeoForge.
 *
 * <p>One entity class does for every body. Which body a mob is, is a property of its entity type rather than of its Java
 * class — {@link #speciesOf} is what {@link AgentMob#species()} asks — because a class per body is a file whose only
 * content is its own name, and a body that forgot to write one would quietly have been a humanoid.
 *
 * <p>A body that declares no mob at all is a layout and nothing else. It is not registered, and everything that would
 * spawn one refuses it by name: see {@link #training}.
 */
public final class ModEntities {

    private ModEntities() {}

    /** Wide enough that an arena never stops updating its agents, which is what every body here wants. */
    private static final int TRACKING_RANGE = 10;

    /** One mob of one body, as the loaders need it: the id, the builder, and which body it belongs to. */
    public record Registration(Species species, Species.Mob mob, ResourceLocation id, EntityType.Builder<AgentMob> builder) {

        /** What the loaders call {@code build} with, and what the lang file and any spawn egg are named after. */
        public String path() {

            return this.mob().path();
        }
    }

    private static final List<Registration> REGISTRATIONS = declared();

    /** The mobs every body declares, in declaration order: what each loader registers, one by one. */
    public static List<Registration> registrations() {

        return REGISTRATIONS;
    }

    private static List<Registration> declared() {

        List<Registration> found = new ArrayList<>();

        for (Species species : Species.ALL) {

            for (Species.Mob mob : species.mobs()) {

                EntityType.Builder<AgentMob> builder = EntityType.Builder
                        .of(AgentMob::new, MobCategory.CREATURE)
                        .sized(mob.width(), mob.height())
                        .eyeHeight(mob.eyeHeight())
                        .clientTrackingRange(TRACKING_RANGE);

                if (mob.role() == Species.Mob.Role.TRAINING) {

                    builder = builder.noSave().noSummon();
                }

                found.add(new Registration(species, mob, Constants.id(mob.path()), builder));
            }
        }

        return List.copyOf(found);
    }

    private static final Map<String, EntityType<AgentMob>> BY_PATH = new LinkedHashMap<>();

    /** Which body an entity type is. Identity keyed, since an entity type is one object for the life of the game. */
    private static final Map<EntityType<?>, Species> BODIES = new IdentityHashMap<>();

    /** Called by each loader as it registers one of {@link #registrations}, in any order. */
    public static void accept(Registration registration, EntityType<AgentMob> type) {

        BY_PATH.put(registration.path(), type);
        BODIES.put(type, registration.species());
    }

    /** Which body a mob of this type is, or null for anything that is not an agent of any body. */
    @Nullable
    public static Species speciesOf(EntityType<?> type) {

        return BODIES.get(type);
    }

    /**
     * Whether this type is the kind an arena fights in rather than the kind a player meets, for any body. Asked by the mob
     * itself as it is built, so it goes by the id rather than by the registration, which on NeoForge is not handed over
     * until setup: the id is what the body declared and is known before the game starts.
     */
    public static boolean isTraining(EntityType<?> type) {

        ResourceLocation id = EntityType.getKey(type);

        for (Registration registration : REGISTRATIONS) {

            if (registration.id().equals(id)) {

                return registration.mob().role() == Species.Mob.Role.TRAINING;
            }
        }

        return false;
    }

    /** The mob of this body a player meets, or a refusal naming the body: see {@link #of}. */
    public static EntityType<AgentMob> world(Species species) {

        return of(species, Species.Mob.Role.WORLD);
    }

    /** The mob of this body the arenas fight in, or a refusal naming the body: see {@link #of}. */
    public static EntityType<AgentMob> training(Species species) {

        return of(species, Species.Mob.Role.TRAINING);
    }

    /**
     * One body's mob of that role.
     *
     * @throws IllegalStateException naming the body, for one that declares no such mob, and for one asked for before its
     *                               loader registered it. A body with a layout and no mob is a real thing to have — it is
     *                               what a body is before anyone models it — and the answer to being asked to spawn one has
     *                               to say which body and what it has not got, rather than a null nobody traces back.
     */
    public static EntityType<AgentMob> of(Species species, Species.Mob.Role role) {

        Species.Mob mob = species.mob(role);

        if (mob == null) {

            throw new IllegalStateException(String.format(Locale.ROOT, "A %s has no mob %s can spawn: it declares %s. A body "
                            + "with a layout and no mob of its own cannot be fought, only checked; give it one in its "
                            + "Species.mobs(), or use a body that has one", species.name(),
                    role == Species.Mob.Role.WORLD ? "a player meets" : "an arena fights in",
                    species.mobs().isEmpty() ? "none at all" : species.mobs().stream().map(Species.Mob::path).toList()));
        }

        EntityType<AgentMob> type = BY_PATH.get(mob.path());

        if (type == null) {

            throw new IllegalStateException("The " + species.name() + "'s " + mob.path() + " was read before its loader "
                    + "registered it");
        }

        return type;
    }

    /** The humanoid's shipped mob, which is the one a player meets and the only one with a spawn egg. */
    public static EntityType<AgentMob> agentMob() {

        return world(Species.HUMANOID);
    }

    /** The humanoid's arena mob, which is what every suite and every training worker has always fought in. */
    public static EntityType<AgentMob> trainingAgent() {

        return training(Species.HUMANOID);
    }
}
