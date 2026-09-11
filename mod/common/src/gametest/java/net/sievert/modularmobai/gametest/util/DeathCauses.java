package net.sievert.modularmobai.gametest.util;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.minecraft.core.BlockPos;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.sievert.modularmobai.Constants;

/**
 * What the agents that lost by dying died of. A death the opponent dealt is the fight going against the agent; anything
 * else, such as lava, a fall, drowning or a cactus, is one it could have walked away from, and those are the deaths worth
 * finding. Each is logged with where it happened, so the ground that keeps killing agents can be looked up and watched, and
 * with the running count per cause in this worker.
 */
public final class DeathCauses {

    private DeathCauses() {}

    /** Dealt by the opponent, directly or through something it shot. */
    public static final String OPPONENT = "opponent";

    /**
     * What {@link #finish} says finished the other side when it was not the ground: the agent's own blow or arrow, one of
     * the other side finishing another, or nothing at all.
     */
    public static final String AGENT = "agent";
    public static final String SIDE = "side";
    public static final String NOTHING = "-";

    private static final Map<String, Integer> COUNTS = new TreeMap<>();

    public static synchronized void record(LivingEntity agent, LivingEntity opponent) {

        record(agent, List.of(opponent));
    }

    /** The same where the agent was up against several at once: a blow from any of them is the fight going against it. */
    public static synchronized void record(LivingEntity agent, List<? extends LivingEntity> opponents) {

        String cause = cause(agent, opponents);

        COUNTS.merge(cause, 1, Integer::sum);

        if (!OPPONENT.equals(cause)) {

            BlockPos at = agent.blockPosition();
            String biome = agent.level().getBiome(at).unwrapKey().map(key -> key.location().getPath()).orElse("?");

            Constants.LOG.info("Agent died of {} at {} {} {} in {}, not at its opponent's hand; deaths by cause so far: {}",
                    cause, at.getX(), at.getY(), at.getZ(), biome, COUNTS);
        }
    }

    /**
     * What a dead agent died of: {@link #OPPONENT}, or the damage type of whatever else it was, such as lava, fall or
     * drown, or unknown. The league writes it down with every fight lost that way.
     */
    public static String cause(LivingEntity agent, LivingEntity opponent) {

        return cause(agent, List.of(opponent));
    }

    /**
     * What finished the other side, which is the mirror of {@link #cause}: {@code agent} when the agent's own blow or arrow
     * did it, {@code -} when nothing on the other side went down at all, and otherwise what the ground did it with, as the
     * damage names it: {@code lava}, {@code fall}, {@code cactus}, {@code inFire}.
     *
     * <p>This is the number the hazard sites exist for. A fight the terrain finishes is the agent's win either way, so the
     * only way to see whether the agent has learned that the ground is a weapon is to count how often it is the ground that
     * ends the fight, and on what kind of ground. A squad counts as finished by the ground if the ground took any of them.
     */
    public static String finish(LivingEntity agent, List<? extends LivingEntity> opponents) {

        String byTheGround = null;
        boolean byTheAgent = false;
        boolean byItsOwnSide = false;

        for (LivingEntity opponent : opponents) {

            DamageSource source = opponent.isAlive() ? null : opponent.getLastDamageSource();

            if (source == null) {

                continue;
            }

            if (source.getEntity() == agent || source.getDirectEntity() == agent) {

                // The agent's own blow, or its arrow, whose owner it is.
                byTheAgent = true;
            }

            else if (opponents.contains(source.getEntity()) || opponents.contains(source.getDirectEntity())) {

                // A creeper on the other side blowing up its own mate is neither the agent's doing nor the ground's.
                byItsOwnSide = true;
            }

            else {

                byTheGround = source.getMsgId();
            }
        }

        return byTheGround != null ? byTheGround : byItsOwnSide ? SIDE : byTheAgent ? AGENT : NOTHING;
    }

    /** Whether what finished the other side was the ground rather than the agent, its own side, or nothing at all. */
    public static boolean byTheGround(String finish) {

        return !AGENT.equals(finish) && !SIDE.equals(finish) && !NOTHING.equals(finish);
    }

    /** The same against several at once: any of them, or anything any of them shot, counts as the opponent. */
    public static String cause(LivingEntity agent, List<? extends LivingEntity> opponents) {

        DamageSource source = agent.getLastDamageSource();

        if (source == null) {

            return "unknown";
        }

        for (LivingEntity opponent : opponents) {

            if (source.getEntity() == opponent || source.getDirectEntity() == opponent) {

                return OPPONENT;
            }
        }

        return source.getMsgId();
    }
}
