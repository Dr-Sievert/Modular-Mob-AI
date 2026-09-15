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
 * same weights share a forward pass; two agents on different weights never do, so every batch is the same shape by
 * construction. A brain is made for one body, so that also means a batch is one species' — and a brain offered a body it
 * was not made for is refused here, by name, rather than being handed an observation that means something else. The
 * entities themselves never see any of this: an agent holds its own memory, and this is the only thing that hands it to a
 * brain and back.
 */
public final class AgentDriver {

    private static final Map<ServerLevel, AgentDriver> BY_LEVEL = new WeakHashMap<>();

    private static final EntityTypeTest<Entity, AgentMob> AGENTS = EntityTypeTest.forClass(AgentMob.class);

    /** One per brain driving anyone here, kept so their buffers are reused tick after tick. */
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

                state.use(Brains.forAgent(agent));
            }

            Brain brain = state.brain();

            // Named both ways round, because "this is a spider's brain and the mob is a humanoid" is the mistake that will
            // actually be made, and an observation of the wrong shape is the one failure that would not look like one.
            if (brain.species() != agent.species()) {

                throw new IllegalStateException("A brain for a " + brain.species().name() + " is driving a "
                        + agent.species().name() + " agent" + (agent.brainName() == null ? "" : " (" + agent.brainName() + ")")
                        + ": a network only fits the body its layout was written for");
            }

            this.batches.computeIfAbsent(brain, AgentBatch::new).add(agent);
        }

        // A brain that drove nobody this tick is done here for now: a checkpoint whose evaluation fight is over, a frozen
        // copy the league has stopped fielding. Its batch goes with it, or this map would keep every set of weights ever
        // fought with alive for the rest of the process; one that comes back simply gets a new batch.
        this.batches.values().removeIf(batch -> !batch.run());
    }
}
