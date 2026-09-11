package net.sievert.modularmobai.arena;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

/**
 * A fight an arena has put an agent in: who it is fighting, what it is being paid, and the plot it is allowed to see
 * into.
 *
 * <p>Owned by whatever set the fight up, and only attached to the agent. The agent reports what happens to its body,
 * damage dealt and taken and dying, and the arena decides what winning meant. An agent met in an ordinary world has no
 * episode at all: nobody is paying it and nothing bounds what it sees.
 */
public final class Episode {

    private final AgentReward reward = new AgentReward();

    @Nullable
    private final AABB bounds;

    @Nullable
    private final LivingEntity opponent;

    /**
     * @param maxTicks the arena's own time limit, which is what fast and slow are measured against
     * @param bounds   the region the agent may perceive, or null for no limit. Arenas in a suite sit close enough
     *                 together that an unbounded view would see straight into the neighbours.
     * @param opponent the one thing hurting pays for, or null to pay for hurting anything. Out on real terrain there are
     *                 animals to hit, and an agent paid for those would learn to farm them instead of fighting.
     */
    public Episode(int maxTicks, @Nullable AABB bounds, @Nullable LivingEntity opponent) {

        this.bounds = bounds;
        this.opponent = opponent;
        this.reward.beginEpisode(maxTicks);
    }

    public AgentReward reward() {

        return this.reward;
    }

    @Nullable
    public AABB bounds() {

        return this.bounds;
    }

    /** Whether hurting this target is what the agent is here for. */
    public boolean pays(LivingEntity target) {

        return this.opponent == null || target == this.opponent;
    }
}
