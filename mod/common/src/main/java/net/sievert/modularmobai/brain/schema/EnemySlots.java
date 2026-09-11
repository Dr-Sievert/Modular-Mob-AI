package net.sievert.modularmobai.brain.schema;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.sievert.modularmobai.allegiance.Allegiance;

/**
 * Hands each opponent a fixed place in the observation and keeps it there.
 *
 * <p>Without this the enemies would be described in whatever order the world happened to return them, so the same fight
 * would look different from one tick to the next and the network would have to learn that slot two means nothing in
 * particular. A lease fixes an opponent to one slot for as long as it is around, so slot two means the same opponent for
 * the whole fight.
 *
 * <p>Leases survive an opponent briefly leaving the view, expire when it dies or stays away, and the nearest opponents
 * win the slots when there are more opponents than slots. Anything that could not be given a slot still shows up in the
 * count, so the agent knows it is outnumbered even when it cannot see by whom.
 */
public final class EnemySlots {

    private final LivingEntity[] occupants = new LivingEntity[ObservationSchema.ENEMY_SLOTS];
    private final int[] graceRemaining = new int[ObservationSchema.ENEMY_SLOTS];

    private int inRangeCount;

    /**
     * @param owner  the agent doing the looking
     * @param bounds the region the agent is allowed to see into, or null for no limit. Arenas in a suite sit close
     *               enough together that an unbounded view would see straight into its neighbours.
     */
    public void tick(LivingEntity owner, @Nullable AABB bounds) {

        AABB view = owner.getBoundingBox().inflate(ObservationSchema.VIEW_DISTANCE);

        if (bounds != null) {

            view = view.intersect(bounds);
        }

        double viewSq = ObservationSchema.VIEW_DISTANCE * ObservationSchema.VIEW_DISTANCE;

        List<LivingEntity> candidates = owner.level().getEntitiesOfClass(LivingEntity.class, view,
                other -> other != owner && other.isAlive() && hostile(owner, other) && owner.distanceToSqr(other) <= viewSq);

        this.inRangeCount = candidates.size();

        this.expireLeases(owner, viewSq);

        for (LivingEntity candidate : candidates) {

            if (this.slotOf(candidate) >= 0) {

                continue;
            }

            int slot = this.firstFreeSlot();

            if (slot < 0) {

                slot = this.slotToEvictFor(owner, candidate);
            }

            if (slot >= 0) {

                this.occupants[slot] = candidate;
                this.graceRemaining[slot] = ObservationSchema.LEASE_GRACE_TICKS;
            }
        }
    }

    /**
     * What counts as an enemy: monsters, players, other agents, and anything that has chosen this agent as its target.
     * Cows, villagers and the rest of what lives on real terrain are left out, so they never push a real threat out of a
     * slot or teach the agent to square up to a sheep. Teams come first: an ally never takes a slot, whatever it is, and
     * a member of another team always does. See {@link Allegiance#isEnemy}, which is the whole rule.
     *
     * <p>This is the one place the agent's enemies are chosen. Only what holds a slot is described in the observation,
     * so an ally standing next to the agent is not in it at all, and the enemy slots, the nearest one the scripted
     * fighter goes for included, only ever hold the other side.
     */
    static boolean hostile(LivingEntity owner, LivingEntity other) {

        return Allegiance.isEnemy(owner, other);
    }

    private void expireLeases(LivingEntity owner, double viewSq) {

        for (int slot = 0; slot < this.occupants.length; slot++) {

            LivingEntity occupant = this.occupants[slot];

            if (occupant == null) {

                continue;
            }

            // One that has come over to the agent's side, or can no longer be fought at all, a player gone creative, is let
            // go at once rather than held while it stays close. Nothing else is: a wolf that stops targeting the agent is
            // still a wolf that just bit it.
            if (!occupant.isAlive() || occupant.isRemoved() || Allegiance.allied(owner, occupant) || !owner.canAttack(occupant)) {

                this.occupants[slot] = null;
                continue;
            }

            if (owner.distanceToSqr(occupant) <= viewSq) {

                this.graceRemaining[slot] = ObservationSchema.LEASE_GRACE_TICKS;
                continue;
            }

            // Out of view, but the slot is held for a moment in case it is only stepping around a corner.
            if (--this.graceRemaining[slot] <= 0) {

                this.occupants[slot] = null;
            }
        }
    }

    /**
     * The slot held by whichever leaseholder is furthest away, but only if the newcomer is actually closer. Evicting for
     * something further off would just churn the slots for nothing.
     */
    private int slotToEvictFor(LivingEntity owner, LivingEntity candidate) {

        int furthest = -1;
        double furthestSq = owner.distanceToSqr(candidate);

        for (int slot = 0; slot < this.occupants.length; slot++) {

            LivingEntity occupant = this.occupants[slot];

            if (occupant == null) {

                continue;
            }

            double distanceSq = owner.distanceToSqr(occupant);

            if (distanceSq > furthestSq) {

                furthestSq = distanceSq;
                furthest = slot;
            }
        }

        return furthest;
    }

    private int firstFreeSlot() {

        for (int slot = 0; slot < this.occupants.length; slot++) {

            if (this.occupants[slot] == null) {

                return slot;
            }
        }

        return -1;
    }

    private int slotOf(LivingEntity entity) {

        for (int slot = 0; slot < this.occupants.length; slot++) {

            if (this.occupants[slot] == entity) {

                return slot;
            }
        }

        return -1;
    }

    @Nullable
    public LivingEntity occupant(int slot) {

        return this.occupants[slot];
    }

    /** Everything in view, including whatever could not be given a slot. */
    public int inRangeCount() {

        return this.inRangeCount;
    }

    /** Whether no slot is held: nothing in view, and nothing that only just stepped out of it. */
    public boolean isEmpty() {

        for (LivingEntity occupant : this.occupants) {

            if (occupant != null) {

                return false;
            }
        }

        return true;
    }

    public void clear() {

        java.util.Arrays.fill(this.occupants, null);
        java.util.Arrays.fill(this.graceRemaining, 0);
        this.inRangeCount = 0;
    }
}
