package net.sievert.modularmobai.brain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.server.level.ServerLevel;
import net.sievert.modularmobai.entity.AgentMob;
import net.sievert.modularmobai.entity.ModEntities;

/**
 * Steps every agent in a level through the brain, once per tick, before any of them move.
 *
 * <p>{@link #tick} is safe to call as often as you like: it remembers the game time it last ran at and does nothing on a
 * second call within the same tick. That is what lets thousands of arenas each call it from their own tick handler and
 * still produce one batch covering all of them, without a mixin into the server tick loop.
 *
 * <p>Agents that have finished are kept in the batch for exactly one more step so their final observation and terminal
 * reward reach the far side, and are dropped after that.
 */
public final class AgentDriver {

    private static final Map<ServerLevel, AgentDriver> BY_LEVEL = new WeakHashMap<>();

    /**
     * Built on first use rather than when this class loads. A remote brain opens a socket in its constructor, and doing
     * that during class initialisation would turn "the training process is not running" into an
     * ExceptionInInitializerError thrown from whatever unrelated code happened to touch this class first.
     */
    private static Brain brain;

    /**
     * Which brain a run uses, chosen without recompiling. A run with no training process behind it falls back to the
     * scripted fighter, which is what keeps the game tests runnable on their own.
     *
     * <pre>
     *   -Dmodular_mob_ai.brain=remote
     *   -Dmodular_mob_ai.brain.host=127.0.0.1
     *   -Dmodular_mob_ai.brain.port=8765
     * </pre>
     */
    private static Brain fromProperties() {

        String kind = property("modular_mob_ai.brain", "scripted");

        if (!"remote".equalsIgnoreCase(kind)) {

            return new ScriptedBrain();
        }

        String host = property("modular_mob_ai.brain.host", "127.0.0.1");
        int port = Integer.parseInt(property("modular_mob_ai.brain.port", "8765"));

        // A parallel run gives every worker its own shard index, which is what lets the far side tell one worker's agents
        // from another's when their entity ids collide.
        int workerId = Integer.getInteger("modular_mob_ai.gametest.shardIndex", 0);

        return new RemoteBrain(host, port, workerId);
    }

    /** The build always sets these, passing an empty string through for anything the user left out. */
    private static String property(String name, String fallback) {

        String value = System.getProperty(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private final AgentBatch batch = new AgentBatch(brain());
    private final List<AgentMob> stepping = new ArrayList<>();

    private long lastTick = Long.MIN_VALUE;

    private AgentDriver() {}

    /**
     * Swaps what is driving the agents. Called before a run starts, never during one: the training brain keeps state per
     * agent, and changing it mid fight would leave that state describing a fight that is no longer happening.
     */
    public static void setBrain(Brain replacement) {

        brain = replacement;
        BY_LEVEL.clear();
    }

    public static synchronized Brain brain() {

        if (brain == null) {

            brain = fromProperties();
        }

        return brain;
    }

    public static void tick(ServerLevel level) {

        BY_LEVEL.computeIfAbsent(level, ignored -> new AgentDriver()).tickOnce(level);
    }

    private void tickOnce(ServerLevel level) {

        long time = level.getGameTime();

        if (time == this.lastTick) {

            return;
        }

        this.lastTick = time;
        this.stepping.clear();

        for (AgentMob agent : level.getEntities(ModEntities.agentMob(), agent -> !agent.isRemoved() && !agent.reward().isReported())) {

            this.stepping.add(agent);
        }

        this.batch.run(this.stepping);
    }
}
