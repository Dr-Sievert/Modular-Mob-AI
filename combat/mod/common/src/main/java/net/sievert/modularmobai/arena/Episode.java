package net.sievert.modularmobai.arena;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

    /** Who hurting pays for; empty pays for hurting anything at all. */
    private final List<LivingEntity> opponents;

    /**
     * @param maxTicks the arena's own time limit, which is what fast and slow are measured against
     * @param bounds   the region the agent may perceive, or null for no limit. Arenas in a suite sit close enough
     *                 together that an unbounded view would see straight into the neighbours.
     * @param opponent the one thing hurting pays for, or null to pay for hurting anything. Out on real terrain there are
     *                 animals to hit, and an agent paid for those would learn to farm them instead of fighting.
     */
    public Episode(int maxTicks, @Nullable AABB bounds, @Nullable LivingEntity opponent) {

        this(maxTicks, bounds, opponent == null ? List.of() : List.of(opponent));
    }

    /**
     * The same for a fight against several at once, where hurting any of them is what the agent is here for. A squad in the
     * league is one fight with one reward, so every blow on any of them pays the same as a blow on a single opponent would.
     *
     * @param opponents everything hurting pays for; empty to pay for hurting anything
     */
    public Episode(int maxTicks, @Nullable AABB bounds, List<? extends LivingEntity> opponents) {

        this.bounds = bounds;
        this.opponents = new ArrayList<>(opponents);
        this.reward.beginEpisode(maxTicks);
    }

    /**
     * Takes one more body into the fight, for the one thing in the league that makes new ones in the middle of one: a slime
     * or a magma cube, which leaves two to four smaller copies of itself behind when it dies. Those are as much the fight as
     * the body that left them — a player who kills a big slime has not finished until the last of them is down — so they join
     * the other side, and from then on the reward pays for hurting one and the arena waits for the last of them.
     *
     * <p>A fight with no side at all pays for hurting anything and has nothing to join: that is an agent out on real terrain
     * with animals about, and narrowing it to one body mid fight would be the opposite of what that is for. Joining the same
     * body twice would pay twice for one blow, the fault a side of copies invites, so it is refused here rather than at every
     * call site.
     */
    public void join(LivingEntity opponent) {

        if (!this.opponents.isEmpty() && !this.opponents.contains(opponent)) {

            this.opponents.add(opponent);
        }
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

        return this.opponents.isEmpty() || this.opponents.contains(target);
    }

    /**
     * The other side of this fight, whether any of it is still standing or not, and empty where the agent is paid for
     * hurting anything at all. This is what {@link FightFacts} describes to the critic: who the agent is actually up
     * against, rather than what it happens to be able to see. It can grow while the fight runs, see {@link #join}, so a
     * caller that walks it by index has to ask for its size each time round.
     */
    public List<LivingEntity> opponents() {

        return Collections.unmodifiableList(this.opponents);
    }
}
