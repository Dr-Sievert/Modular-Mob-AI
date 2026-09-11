package net.sievert.modularmobai.brain.schema;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
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
 *
 * <h2>Arrows in the air</h2>
 *
 * <p>A slot can also hold something that was shot at the agent. Half the league fights at a distance, and until now an
 * arrow already in the air was not in the observation at all: the agent could see the skeleton and its bow, and never the
 * shot. There is nothing else in the vector an arrow could go in, and the enemy block already carries a position, a
 * velocity and a kind, which is the whole of what there is to say about one.
 *
 * <p>Two rules keep that from spoiling what the slots mean:
 *
 * <ul>
 *   <li><b>Bodies come first and are never displaced.</b> Projectiles take only the slots nothing living wants, and a
 *       body arriving with no free slot evicts a projectile before it evicts anything alive. Otherwise a trained
 *       network's nearest enemy could quietly become an arrow two blocks away while the skeleton that fired it fell out
 *       of the view.</li>
 *   <li><b>Only what is actually coming.</b> An arrow lying in the grass, one flying past and one the agent fired itself
 *       are not threats, and a slot spent on them is a slot wasted. A projectile earns one only while it is moving, while
 *       the agent is still ahead of it, and while its line would pass close enough to hit; see {@link #incoming}.</li>
 * </ul>
 */
public final class EnemySlots {

    /**
     * Slower than this, in blocks a tick, and a projectile is not going anywhere: an arrow stuck in the ground or in a
     * block keeps its entity for a minute afterwards, and a dropped one lies where it fell.
     */
    private static final double PROJECTILE_SPEED_FLOOR = 0.15D;

    /**
     * How close to the agent a projectile's line has to pass for it to be worth a slot. A body is six tenths of a block
     * wide and a raised shield covers a cone in front, so a block and a half either side is everything that could hit and
     * a little of what a flinch is still reasonable about.
     */
    private static final double PROJECTILE_MISS = 1.5D;

    /**
     * Occupants by slot. Living for all but a projectile, which is why this is not a {@code LivingEntity[]}: an arrow is
     * an entity and nothing more.
     */
    private final Entity[] occupants = new Entity[ObservationSchema.ENEMY_SLOTS];
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

        // Bodies only. A count that grew with every arrow in the air would tell a network trained on it that it was
        // outnumbered whenever a skeleton opened fire.
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

        this.leaseProjectiles(owner, view, viewSq);
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

    /**
     * Gives whatever slots are left over to the projectiles coming at the agent, nearest first. Nothing is ever evicted
     * for one: a fight with ten bodies in view is a fight where an arrow is the least of it.
     */
    private void leaseProjectiles(LivingEntity owner, AABB view, double viewSq) {

        if (this.firstFreeSlot() < 0) {

            return;
        }

        List<Projectile> flying = owner.level().getEntitiesOfClass(Projectile.class, view,
                shot -> shot.isAlive() && owner.distanceToSqr(shot) <= viewSq && incoming(owner, shot));

        // Nearest first, so the one about to land keeps its slot when there are more shots than slots left.
        flying.sort((first, second) -> Double.compare(owner.distanceToSqr(first), owner.distanceToSqr(second)));

        for (Projectile shot : flying) {

            if (this.slotOf(shot) >= 0) {

                continue;
            }

            int slot = this.firstFreeSlot();

            if (slot < 0) {

                return;
            }

            this.occupants[slot] = shot;
            this.graceRemaining[slot] = ObservationSchema.LEASE_GRACE_TICKS;
        }
    }

    /**
     * Whether a projectile is one the agent has any reason to care about: not its own or an ally's, still travelling,
     * with the agent ahead of it rather than behind, and on a line that would pass close enough to hit.
     *
     * <p>The last two come out of the same two numbers. Along the line of flight, how far ahead of the shot the agent is
     * says whether it is still coming; across it, how far the shot would miss by says whether it is coming at the agent or
     * merely past it. Nothing here follows the arc down: over the few blocks an arrow covers before it arrives it falls a
     * fraction of the block and a half this allows.
     */
    private static boolean incoming(LivingEntity owner, Projectile shot) {

        Entity shooter = shot.getOwner();

        if (shooter == owner || shooter instanceof LivingEntity living && !Allegiance.isEnemy(owner, living)) {

            return false;
        }

        Vec3 velocity = shot.getDeltaMovement();
        double speed = velocity.length();

        if (speed < PROJECTILE_SPEED_FLOOR) {

            return false;
        }

        Vec3 toOwner = owner.getBoundingBox().getCenter().subtract(shot.position());
        double along = toOwner.dot(velocity) / speed;

        if (along <= 0.0D) {

            return false;
        }

        return toOwner.lengthSqr() - along * along <= PROJECTILE_MISS * PROJECTILE_MISS;
    }

    private void expireLeases(LivingEntity owner, double viewSq) {

        for (int slot = 0; slot < this.occupants.length; slot++) {

            Entity occupant = this.occupants[slot];

            if (occupant == null) {

                continue;
            }

            if (!occupant.isAlive() || occupant.isRemoved()) {

                this.occupants[slot] = null;
                continue;
            }

            // A projectile is let go the moment it stops coming, since a slot held for an arrow lying in the grass or one
            // that has already flown past is a slot held for nothing.
            if (occupant instanceof Projectile shot) {

                if (!incoming(owner, shot)) {

                    this.occupants[slot] = null;
                    continue;
                }
            }

            // One that has come over to the agent's side, or can no longer be fought at all, a player gone creative, is let
            // go at once rather than held while it stays close. Nothing else is: a wolf that stops targeting the agent is
            // still a wolf that just bit it.
            else if (Allegiance.allied(owner, occupant) || !owner.canAttack((LivingEntity) occupant)) {

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
     * The slot to take for a newcomer that found none free: a projectile's before anything alive, and otherwise the one
     * held by whichever body is furthest away, but only if the newcomer is actually closer. Evicting a body for one
     * further off would just churn the slots for nothing.
     */
    private int slotToEvictFor(LivingEntity owner, LivingEntity candidate) {

        int furthestShot = -1;
        double furthestShotSq = -1.0D;

        int furthest = -1;
        double furthestSq = owner.distanceToSqr(candidate);

        for (int slot = 0; slot < this.occupants.length; slot++) {

            Entity occupant = this.occupants[slot];

            if (occupant == null) {

                continue;
            }

            double distanceSq = owner.distanceToSqr(occupant);

            if (occupant instanceof Projectile) {

                if (distanceSq > furthestShotSq) {

                    furthestShotSq = distanceSq;
                    furthestShot = slot;
                }

                continue;
            }

            if (distanceSq > furthestSq) {

                furthestSq = distanceSq;
                furthest = slot;
            }
        }

        return furthestShot >= 0 ? furthestShot : furthest;
    }

    private int firstFreeSlot() {

        for (int slot = 0; slot < this.occupants.length; slot++) {

            if (this.occupants[slot] == null) {

                return slot;
            }
        }

        return -1;
    }

    private int slotOf(Entity entity) {

        for (int slot = 0; slot < this.occupants.length; slot++) {

            if (this.occupants[slot] == entity) {

                return slot;
            }
        }

        return -1;
    }

    @Nullable
    public Entity occupant(int slot) {

        return this.occupants[slot];
    }

    /** Everything alive in view, including whatever could not be given a slot. Arrows are not counted. */
    public int inRangeCount() {

        return this.inRangeCount;
    }

    /** Whether no slot is held: nothing in view, and nothing that only just stepped out of it. */
    public boolean isEmpty() {

        for (Entity occupant : this.occupants) {

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
