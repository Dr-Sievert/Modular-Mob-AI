package net.sievert.modularmobai.brain;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.sievert.modularmobai.entity.agent.AgentMob;

/**
 * Steps every agent in a level through its brain, once per tick, before any of them move.
 *
 * <p>Each loader calls {@link #tick} at the start of every level tick, ahead of the entities. It is also safe to call
 * more often than that: it remembers the game time it last ran at and does nothing on a second call within the same
 * tick.
 *
 * <p>Agents are grouped by the brain driving them, and each group goes through its brain in one call. Two agents on the
 * same weights share a forward pass whatever kind of mob they are; two agents on different weights never do, so every
 * batch is the same shape by construction. The entities themselves never see any of this: an agent holds its own
 * memory, and this is the only thing that hands it to a brain and back.
 */
public final class AgentDriver {

    private static final Map<ServerLevel, AgentDriver> BY_LEVEL = new WeakHashMap<>();

    private static final EntityTypeTest<Entity, AgentMob> AGENTS = EntityTypeTest.forClass(AgentMob.class);

    /** One per brain ever seen here, kept so their buffers are reused tick after tick. */
    private final Map<Brain, AgentBatch> batches = new IdentityHashMap<>();

    private long lastTick = Long.MIN_VALUE;

    private AgentDriver() {}

    public static void tick(ServerLevel level) {

        BY_LEVEL.computeIfAbsent(level, ignored -> new AgentDriver()).tickOnce(level);
    }

    private void tickOnce(ServerLevel level) {

        long time = level.getGameTime();

        if (time == this.lastTick) {

            return;
        }

        this.lastTick = time;

        // By class rather than by type, so both registrations of the entity are driven.
        for (AgentMob agent : level.getEntities(AGENTS, agent -> !agent.isRemoved() && !agent.brain().isFinished())) {

            BrainState state = agent.brain();

            if (state.brain() == null) {

                state.use(Brains.defaultBrain());
            }

            this.batches.computeIfAbsent(state.brain(), AgentBatch::new).add(agent);
        }

        for (AgentBatch batch : this.batches.values()) {

            batch.run();
        }
    }
}
