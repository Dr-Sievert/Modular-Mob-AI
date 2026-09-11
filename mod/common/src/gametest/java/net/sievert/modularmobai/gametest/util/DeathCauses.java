package net.sievert.modularmobai.gametest.util;

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

    private static final Map<String, Integer> COUNTS = new TreeMap<>();

    public static synchronized void record(LivingEntity agent, LivingEntity opponent) {

        String cause = cause(agent, opponent);

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

        DamageSource source = agent.getLastDamageSource();

        return source == null ? "unknown"
                : source.getEntity() == opponent || source.getDirectEntity() == opponent ? OPPONENT
                : source.getMsgId();
    }
}
