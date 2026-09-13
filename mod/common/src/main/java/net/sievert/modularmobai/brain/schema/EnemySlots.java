package net.sievert.modularmobai.brain.schema;

import java.util.ArrayList;
import java.util.Comparator;
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
 * <h2>Which slot: the fight first, then the nearest</h2>
 *
 * <p>Slots used to be handed out in the order the level's own walk over its entity sections returned bodies, which is
 * section x ascending, then z, then y. Against one opponent that is no order at all — there is one body and it takes slot 0 —
 * so <b>every fight a network has ever been trained on put its opponent in slot 0</b>, and a network learns from that. Stand
 * a crowd of idle monsters round the same fight and the opponent holds slot 0 only when it happens to be the westernmost of
 * them, which is where a whole curriculum went: measured over the fight the trouble was reported on, one opponent and nine
 * bystanders 8 to 30 blocks off, the opponent held slot 0 on <b>none</b> of the ticks and sat in slot 5.5 on average, the aim
 * was 87 degrees off it against 14 with nobody about, and of 207 presses of attack 206 went into thin air. {@code blast7}
 * trained 2,200 iterations on a quarter of its fights crowded and its crowded win rate never moved off 31% against 80%
 * plain, because a quarter of its fights were contradicting the other three quarters at random.
 *
 * <p>So a slot goes by what the body is to this fight and not by where the world keeps it:
 *
 * <ol>
 *   <li><b>Whoever is fighting the agent</b>, which is one rule in one place, {@link #engaged}: it has taken the agent as its
 *       target ({@link Allegiance#goesFor}, the same reading the slot's own {@code ENEMY_TARGETS_ME} is written from) or it is
 *       on a team set against the agent's, which is how a squad fights. A league opponent is both from its first tick; a
 *       bystander is neither, being handed its target back on every tick until something hits it.
 *   <li><b>Then the nearest</b>, which is what the doc above always claimed and only eviction ever did.
 * </ol>
 *
 * <p>What this does not move is anything hand written. The scripted fighter never read slot 0: it walks all ten slots and
 * works out the nearest occupant from the distance in each, so the teacher's answers, the arena suite and every label a
 * demonstration carries are untouched. A fight against one opponent has one body to order, so the sort is skipped outright
 * and the plain fight costs exactly what it cost. What does change is a squad's slots, which are now nearest first among the
 * side rather than westernmost first — a change to what a network trained on the old order sees, and the reason the crowd
 * suite measures the plain case beside the crowded one.
 *
 * <h2>What the agent could see</h2>
 *
 * <p>A slot goes to a body within {@link ObservationSchema#VIEW_DISTANCE} <b>that the agent could actually see</b>, by
 * vanilla's own line of sight from its eyes to the candidate's — the test every mob's targeting already makes before it
 * picks anything. Distance alone was the whole rule, and a real game punished it: at night a view of thirty two blocks with
 * nothing else in it takes in the monsters through the wall, across the valley and in the caves below, so ten slots filled
 * with bodies that could not reach the agent and were not coming, {@code SELF_ENEMIES_IN_RANGE} read 1.5 where no training
 * fight ever put it over 0.3, and the published network stopped fighting the zombie beside it altogether. Every league fight
 * is one opponent or a squad of two or three on open ground with nothing between, so the arena and the terrain suites read
 * exactly what they always did; see findings.md.
 *
 * <p><b>Cover takes the reading away, not the slot.</b> An occupant that goes behind rock, or out past the view, keeps its
 * lease for {@link ObservationSchema#LEASE_GRACE_TICKS} — so an opponent stepping behind a tree comes back to the slot it
 * left, which is the whole reason leases exist — but for as long as it cannot be seen {@link #occupant} answers null and its
 * slot reads empty. There is no honest third answer: writing where it is now is seeing through the wall, which is the fault
 * being fixed, and freezing where it was last is telling the network a body is somewhere it has had two seconds to leave.
 * Remembering is the GRU's job, and it is better fed "gone" than a stale position. A slot that says nothing is also the
 * first one taken from, {@link #slotToEvictFor}: a newcomer in plain sight is worth more than a reservation.
 *
 * <p>Sight is asked of bodies and not of shots. A projectile has to be moving, have the agent ahead of it and be on a line
 * that would pass within a block and a half, see {@link #incoming}, and a wall in the way answers that itself: the arrow
 * stops in the wall, loses its speed and its slot on the tick after. A clip per arrow would buy a tick.
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

    /**
     * Whether this tick's walk found the occupant: within the view and, for a body, in sight of the agent's eyes. A slot
     * whose occupant it did not is still leased and reads empty, see the class comment.
     */
    private final boolean[] sighted = new boolean[ObservationSchema.ENEMY_SLOTS];

    /** What the one walk over the surroundings found, kept for the life of the view rather than allocated every tick. */
    private final List<LivingEntity> bodies = new ArrayList<>();
    private final List<Projectile> shots = new ArrayList<>();

    private int inRangeCount;

    /**
     * Whose view the order below is sorting, for the length of one call to {@link #tick}. A field, and the comparator with
     * it, because a lambda closing over the owner would be an allocation for every agent on every tick of a training run,
     * and a comparator has to know whose fight it is ranking. Never read outside that call.
     */
    @Nullable
    private LivingEntity ordering;

    /**
     * The fight first, then the nearest; see the class comment for what it is worth. Stable, which {@link List#sort} is, so
     * bodies that rank the same keep the order the walk found them in rather than swapping about from tick to tick.
     */
    private final Comparator<LivingEntity> order = (first, second) -> {

        LivingEntity owner = this.ordering;
        boolean fighting = engaged(owner, first);

        if (fighting != engaged(owner, second)) {

            return fighting ? -1 : 1;
        }

        return Double.compare(owner.distanceToSqr(first), owner.distanceToSqr(second));
    };

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

        // One walk over what is around, not two. Asking the level twice, once for bodies and once for shots, would walk the
        // same entity sections twice, and this is done for every agent on every tick of a run.
        this.bodies.clear();
        this.shots.clear();

        for (Entity other : owner.level().getEntities(owner, view, candidate -> owner.distanceToSqr(candidate) <= viewSq)) {

            if (other instanceof LivingEntity living) {

                // Sight last of the three, because it is the only one that costs a clip through the world: distance is done
                // by the walk itself and whose side it is on is a couple of field reads. Each candidate is asked once a
                // tick and the leases below read the answer off this list rather than clipping again, which is what
                // vanilla's own per tick sensing cache buys a mob.
                if (living.isAlive() && hostile(owner, living) && owner.hasLineOfSight(living)) {

                    this.bodies.add(living);
                }
            }

            else if (other instanceof Projectile shot && shot.isAlive() && incoming(owner, shot)) {

                this.shots.add(shot);
            }
        }

        // Bodies only, and only the ones actually seen: this is what SELF_ENEMIES_IN_RANGE reads, and what makes it worth
        // reading is that it counts the ones no slot was left for. A count that grew with every arrow in the air would tell
        // a network trained on it that it was outnumbered whenever a skeleton opened fire.
        this.inRangeCount = this.bodies.size();

        // Whose view the order is ranking, for the rest of this call: the sort below and the eviction further down both read
        // it, and both have to rank by the same rule or eviction would undo what the sort decided.
        this.ordering = owner;

        // Who gets a slot first is the fight's business and not the world's; see the class comment. Skipped where there is
        // nothing to order, which is the fight against one opponent and so most ticks of most runs.
        if (this.bodies.size() > 1) {

            this.bodies.sort(this.order);
        }

        this.expireLeases(owner);

        for (LivingEntity candidate : this.bodies) {

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
                this.sighted[slot] = true;
            }
        }

        this.leaseProjectiles(owner);

        this.ordering = null;
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
     * Whether that body is in this fight rather than merely in the view: it has come for the agent, or it is on a team set
     * against the agent's. This is what decides which slot it gets, see the class comment, and both halves are read off
     * {@link Allegiance} so that the order and the slot's own {@code ENEMY_TARGETS_ME} cannot come to different answers.
     *
     * <p>The team half is not redundant. A squad member whose path is blocked, or one whose own mind has just let the agent
     * go for a tick, still belongs to the side the fight is against; and an agent opponent holds no target at all, which is
     * why {@code goesFor} answers true for one. The team half is also the only thing that separates an ally's enemy from the
     * agent's on ground where somebody has set sides deliberately.
     *
     * <p>A bystander is neither: it is on no team and is handed its target back on every tick until something hits it. One
     * that has been hit fights back and so becomes engaged, which is the honest answer — it is an opponent in fact by then,
     * whatever the results call the fight.
     */
    static boolean engaged(LivingEntity owner, LivingEntity other) {

        return Allegiance.goesFor(other, owner) || Allegiance.opposed(owner, other);
    }

    /**
     * Gives whatever slots are left over to the projectiles coming at the agent, nearest first. Nothing is ever evicted
     * for one: a fight with ten bodies in view is a fight where an arrow is the least of it.
     */
    private void leaseProjectiles(LivingEntity owner) {

        if (this.shots.isEmpty() || this.firstFreeSlot() < 0) {

            return;
        }

        // Nearest first, so the one about to land keeps its slot when there are more shots than slots left.
        this.shots.sort((first, second) -> Double.compare(owner.distanceToSqr(first), owner.distanceToSqr(second)));

        for (Projectile shot : this.shots) {

            if (this.slotOf(shot) >= 0) {

                continue;
            }

            int slot = this.firstFreeSlot();

            if (slot < 0) {

                return;
            }

            this.occupants[slot] = shot;
            this.graceRemaining[slot] = ObservationSchema.LEASE_GRACE_TICKS;
            this.sighted[slot] = true;
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

    private void expireLeases(LivingEntity owner) {

        for (int slot = 0; slot < this.occupants.length; slot++) {

            Entity occupant = this.occupants[slot];

            if (occupant == null) {

                continue;
            }

            if (!occupant.isAlive() || occupant.isRemoved()) {

                this.release(slot);
                continue;
            }

            // A projectile is let go the moment it stops coming, since a slot held for an arrow lying in the grass or one
            // that has already flown past is a slot held for nothing.
            if (occupant instanceof Projectile shot) {

                if (!incoming(owner, shot)) {

                    this.release(slot);
                    continue;
                }
            }

            // One that has come over to the agent's side, or can no longer be fought at all, a player gone creative, is let
            // go at once rather than held while it stays close. Nothing else is: a wolf that stops targeting the agent is
            // still a wolf that just bit it.
            else if (occupant instanceof LivingEntity body && (Allegiance.allied(owner, body) || !owner.canAttack(body))) {

                this.release(slot);
                continue;
            }

            // Whether this tick's walk found it, which for a body means in the view and in sight. A shot that is still
            // coming has just been asked the only question there is about one.
            this.sighted[slot] = occupant instanceof Projectile || this.bodies.contains(occupant);

            if (this.sighted[slot]) {

                this.graceRemaining[slot] = ObservationSchema.LEASE_GRACE_TICKS;
                continue;
            }

            // Out of view or behind cover, and the slot is held for a moment in case it is only stepping around a corner —
            // but it reads empty meanwhile, because nothing honest can be said about where it is. See the class comment.
            if (--this.graceRemaining[slot] <= 0) {

                this.release(slot);
            }
        }
    }

    private void release(int slot) {

        this.occupants[slot] = null;
        this.graceRemaining[slot] = 0;
        this.sighted[slot] = false;
    }

    /**
     * The slot to take for a newcomer that found none free: one whose occupant cannot be seen before anything that can, a
     * projectile's before anything alive, and then the slot of whichever body the newcomer outranks by the most — by the very
     * order the slots are handed out in, so that eviction can never undo what the order decided. A body the newcomer does not
     * outrank keeps its slot, which is what stops the slots churning for nothing.
     *
     * <p>An unsighted lease goes first because it is reading empty anyway, so the network loses nothing by it and gains a
     * body it can see. That is also the answer to the one cost of holding a lease through cover: a crowd that ducked behind
     * rock cannot sit on ten slots while the fight walks up.
     *
     * <p>Ranking by the order rather than by distance alone is what the crowd needs, and it is the same rule read twice
     * instead of two rules that can disagree. With more bodies in sight than slots — a squad of three with nine standing
     * about it is twelve for ten, and a real world's night is worse — a fight walking up to a crowd that got there first used
     * to be shut out of the view altogether, however near it came, because every occupant was nearer. Worse, the first draft
     * of the fix gave the fight a bystander's slot and then watched the evicted bystander take it straight back on the same
     * tick, because the rung underneath still knew only about distance. One order, asked in both places.
     */
    private int slotToEvictFor(LivingEntity owner, LivingEntity candidate) {

        int unsighted = -1;

        int furthestShot = -1;
        double furthestShotSq = -1.0D;

        int ranksLast = -1;
        LivingEntity lastOfThem = null;

        for (int slot = 0; slot < this.occupants.length; slot++) {

            Entity occupant = this.occupants[slot];

            if (occupant == null) {

                continue;
            }

            if (!this.sighted[slot]) {

                unsighted = slot;
                continue;
            }

            if (occupant instanceof Projectile) {

                double distanceSq = owner.distanceToSqr(occupant);

                if (distanceSq > furthestShotSq) {

                    furthestShotSq = distanceSq;
                    furthestShot = slot;
                }

                continue;
            }

            LivingEntity body = (LivingEntity) occupant;

            if (this.order.compare(candidate, body) < 0 && (lastOfThem == null || this.order.compare(body, lastOfThem) > 0)) {

                lastOfThem = body;
                ranksLast = slot;
            }
        }

        return unsighted >= 0 ? unsighted : furthestShot >= 0 ? furthestShot : ranksLast;
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

    /**
     * What is in that slot to be described, or null for a slot the agent has nothing to say about: an empty one, and one
     * whose occupant is out of the view or behind cover and holding its lease on grace. Every reader gets that one answer —
     * the observation leaves the slot at zeroes, the scripted fighter does not go for it — so there is nowhere for a
     * position the agent could not have seen to leak through.
     */
    @Nullable
    public Entity occupant(int slot) {

        return this.sighted[slot] ? this.occupants[slot] : null;
    }

    /**
     * Whoever holds that slot, seen this tick or not. Nothing in the observation reads this: it is here so that a test can
     * tell a lease held through cover from a lease let go, which is the difference the grace exists to make.
     */
    @Nullable
    public Entity leaseholder(int slot) {

        return this.occupants[slot];
    }

    /** Everything alive the agent can see, including whatever could not be given a slot. Arrows are not counted. */
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
        java.util.Arrays.fill(this.sighted, false);
        this.inRangeCount = 0;
    }
}
