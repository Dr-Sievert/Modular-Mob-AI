package net.sievert.modularmobai.gametest;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.sievert.modularmobai.Constants;

/**
 * What the agents fight: a vindicator, unless the run names another mob.
 *
 * <pre>
 *   -Dmodular_mob_ai.gametest.opponent=ID     any mob's entity type id, such as minecraft:skeleton; a vindicator unless given
 * </pre>
 *
 * <p>For trying the fights against something new before anything is built around it, such as an archer whose arrows a
 * replay can be checked against. Whatever an opponent fights with, a skeleton's bow or a pillager's crossbow, it gets the
 * way the vindicator gets its axe: from the finalizeSpawn the arenas run on it, which is what spawning on its own would
 * have done.
 */
public final class Opponents {

    private Opponents() {}

    private static final String PROPERTY = "modular_mob_ai.gametest.opponent";

    /** What the agents fight when the run names nothing else. */
    public static final EntityType<?> DEFAULT = EntityType.VINDICATOR;

    /** Read when the first fight asks, and the same for every fight after it. */
    @Nullable
    private static EntityType<?> type;

    /** The kind of mob the agents fight in this process. */
    public static synchronized EntityType<?> type() {

        if (type == null) {

            String named = System.getProperty(PROPERTY);

            type = named == null || named.isBlank() ? DEFAULT : EntityType.byString(named.trim()).orElseThrow(() ->
                    new IllegalArgumentException("There is no entity type '" + named.trim() + "' for -D" + PROPERTY + " to name"));

            if (type != DEFAULT) {

                Constants.LOG.info("The agents fight {} rather than {}", EntityType.getKey(type), EntityType.getKey(DEFAULT));
            }
        }

        return type;
    }

    /** A new opponent, not yet placed or added to the level, or null when the game will not create one. */
    @Nullable
    public static Mob create(ServerLevel level) {

        Entity created = type().create(level);
        return created == null ? null : mob(created);
    }

    /** A new opponent spawned into a test's plot, the way the test helper spawns anything. */
    public static Mob spawn(GameTestHelper helper, BlockPos pos) {

        return mob(helper.spawn(type(), pos));
    }

    /** Only a mob can be armed and given a target, so anything else is taken away again before it joins the fight. */
    private static Mob mob(Entity created) {

        if (created instanceof Mob mob) {

            return mob;
        }

        created.discard();
        throw new IllegalArgumentException(EntityType.getKey(created.getType()) + " is not a mob, so it cannot be the agents' opponent");
    }
}
