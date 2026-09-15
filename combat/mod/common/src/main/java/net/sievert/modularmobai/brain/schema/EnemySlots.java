package net.sievert.modularmobai.brain.schema;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.sievert.modularmobai.allegiance.Allegiance;

/**
 * What the agent perceives, and the fixed place in the observation each of them keeps.
 *
 * <p>Without this the enemies would be described in whatever order the world happened to return them, so the same fight
 * would look different from one tick to the next and the network would have to learn that slot two means nothing in
 * particular. A slot is held for one body for as long as the agent is aware of it, so slot two means the same opponent for
 * the whole fight.
 *
 * <h2>Perceiving like a player: in front, near, or having just been hit</h2>
 *
 * <p>The view used to be the full circle: every hostile within {@link ObservationSchema#VIEW_DISTANCE} that the agent had a
 * line of sight to, in every direction at once. That is not a player's view and it cost twice over. A body at the agent's back
 * read exactly like one in front of it, so <b>turning was never worth anything</b> and there was nothing for the aim to be
 * for; and a flat world with a thousand mobs on it put a slot's worth of work into everything within thirty two blocks
 * whatever the agent was doing. A body is now perceived three ways, and any one of them is enough:
 *
 * <ol>
 *   <li><b>Seen</b>: inside the cone of {@link ObservationSchema#VIEW_CONE_DEGREES} about the agent's aim <em>and</em> with a
 *       line of sight from its eyes, vanilla's own {@code hasLineOfSight}, which is the test every mob's targeting already
 *       makes. The cone is on the yaw alone, so anything overhead or below is in it.</li>
 *   <li><b>Heard</b>: within {@link ObservationSchema#HEARING_DISTANCE}, all round, through anything. A player hears the
 *       zombie behind them, and this is what keeps the cone from blinding the agent in the melee where being surrounded is the
 *       whole difficulty.</li>
 *   <li><b>Felt</b>: it is what last hurt the agent, {@code getLastHurtByMob}. Whoever hits you, you know about.</li>
 * </ol>
 *
 * <p>Sides come first and are unchanged: an ally never takes a slot whatever it is, a member of another team always does, see
 * {@link Allegiance#isEnemy}.
 *
 * <h2>The lease is the memory</h2>
 *
 * <p>There used to be two mechanisms with one shape. A <b>lease</b> kept a slot for a body for a grace after it went out of
 * sight, so an opponent stepping behind a tree came back to the slot it left — but the slot read <b>empty</b> the whole time,
 * on the argument that a position two seconds old is a lie and remembering is the GRU's job. That was the right call while the
 * view was a full circle, because the only way to leave it was to go behind something. With a cone it is the wrong one: the
 * commonest way to stop perceiving a body is now that <b>the agent turned its head</b>, and a model where looking away from
 * the zombie in front of you deletes it is not a player's either.
 *
 * <p>So the lease and the grace are one thing now, the <b>memory</b>, {@link ObservationSchema#MEMORY_TICKS}. A slot the agent
 * has stopped perceiving goes on reading the body's <b>last known</b> position, velocity and heading, with the present flag
 * still on, until the window runs out; every tick it is seen, heard or felt starts the window again. The slot is let go when
 * the window expires, when the body dies, or when it stops being an enemy at all.
 *
 * <p>What that costs, said plainly: for up to three seconds a slot can describe a body that has moved. It cannot describe one
 * through a wall, which was the fault the sight rule was added for — a hidden body's reading is frozen where it was last
 * perceived, never where it is now, which {@code aWallKeepsTheLastKnownPlaceAndThenForgetsIt} holds by moving the body while
 * it is hidden and reading the slot. What is <b>not</b> frozen is the rest of the block: health, hands, whether it is swinging
 * and whether it has the agent as its target read live. That is a deliberate line rather than an oversight — the geometry is
 * what the agent aims and steps by, and freezing the whole block would mean remembering a mob's hands and its health as well,
 * which is a second snapshot to keep in step for no measured gain.
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
 * <p>A slot then keeps its body, which is what the memory is for — with <b>one</b> exception, {@link #promoteTheEngaged}: a
 * body that becomes the fight after it was given a slot is moved in front of the bodies that are only in the view. Handing out
 * slots by the order is not enough on its own, because in a real game the order's own first key changes after the fact: a
 * crowd that nobody in has engaged the agent yet is all ranked the same, so the slots go out nearest first, and the one that
 * then comes for the agent used to keep whichever slot the walk-up had given it for the rest of the fight. Engagement moves a
 * slot; distance never does.
 *
 * <p>What this does not move is anything hand written. The scripted fighter never read slot 0, and it never reads an entity
 * either: it works entirely off the observation row, walking all ten slots and taking the nearest occupant from the distance
 * in each. So the teacher inherits every rule here and none of it had to be written twice.
 *
 * <h2>What it costs, and why it does not grow with the world</h2>
 *
 * <p>The one dear thing in here is a clip through the world, and the point of the order above is that most candidates never
 * reach one. Per agent per tick: <b>one</b> query of the level over the range's box, which is proportional to what is near the
 * agent and not to what is on the map; a side test and a dot product for each of those, both a few field reads; and a clip for
 * at most {@link #SIGHT_CLIPS_PER_TICK} of the ones that passed the cone, nearest first. A body heard or felt needs no clip at
 * all — it is perceived whatever is between. {@link #clips()} counts them for the test that holds this, and is read nowhere
 * else.
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
 *       the agent is still ahead of it, and while its line would pass close enough to hit; see {@link #incoming}. A shot is
 *       not asked to be in the cone: an arrow coming at the back of the agent's head is the one a flinch is for, and nothing
 *       is clipped for it either — the arrow stops in a wall, loses its speed, and its slot with it on the tick after.</li>
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
     * How many bodies may be clipped for a line of sight in one tick, nearest first. This is the ceiling that keeps the cost
     * of perceiving flat as the world fills up: twice the ten slots, so on any tick where more than twenty bodies stand in
     * front of the agent the furthest of them wait a tick for their clip, and the ten slots were never going to hold them
     * anyway. Measured on three hundred zombies round one agent on flat ground, it is the difference between twenty clips a
     * tick and three hundred.
     */
    private static final int SIGHT_CLIPS_PER_TICK = 2 * ObservationSchema.ENEMY_SLOTS;

    /**
     * Occupants by slot. Living for all but a projectile, which is why this is not a {@code LivingEntity[]}: an arrow is
     * an entity and nothing more.
     */
    private final Entity[] occupants = new Entity[ObservationSchema.ENEMY_SLOTS];

    /** How many ticks of memory each slot has left; see the class comment. Refreshed on every tick its body is perceived. */
    private final int[] remembering = new int[ObservationSchema.ENEMY_SLOTS];

    /** Whether this tick's walk perceived the occupant: seen in the cone, heard, or felt. */
    private final boolean[] perceived = new boolean[ObservationSchema.ENEMY_SLOTS];

    /**
     * What the slot knows about where its body is: the eye position, the velocity, the head yaw and the pitch as of the last
     * tick the agent perceived it. On a tick it is perceived these are this tick's; on a tick it is not they are the memory,
     * and the observation is written from them either way. Four readings and not the whole block, which the class comment
     * says why.
     */
    private final Vec3[] seenAt = new Vec3[ObservationSchema.ENEMY_SLOTS];
    private final Vec3[] seenMoving = new Vec3[ObservationSchema.ENEMY_SLOTS];
    private final float[] seenYaw = new float[ObservationSchema.ENEMY_SLOTS];
    private final float[] seenPitch = new float[ObservationSchema.ENEMY_SLOTS];

    /** What the one walk over the surroundings found, kept for the life of the view rather than allocated every tick. */
    private final List<LivingEntity> bodies = new ArrayList<>();
    private final List<Projectile> shots = new ArrayList<>();

    /** Candidates inside the cone that still want a clip, nearest first; only the first few ever get one. */
    private final List<LivingEntity> looking = new ArrayList<>();

    private int awareCount;

    /**
     * What perceiving cost this agent on its last tick: clips through the world, and nanoseconds over the whole of it. Kept per
     * view rather than globally because the question a player asks is what <b>this</b> agent is costing, which is what
     * {@code /mmai info} prints and what the horde tests measure. Reading the clock twice a tick is a few tens of nanoseconds
     * against the tens of microseconds the walk itself takes.
     */
    private int lastClips;
    private long lastNanos;

    /**
     * Clips through the world asked for since the game started, which is the one cost in here that could grow with the
     * world. Only a test reads it, {@code theCostOfPerceivingDoesNotGrowWithTheCrowd}; nothing in a fight does, and it is
     * deliberately not synchronised, since a count that cost a lock would be a cost of its own.
     */
    private static long clips;

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

        long started = System.nanoTime();
        long clipsBefore = clips;

        this.perceive(owner, bounds);

        this.lastNanos = System.nanoTime() - started;
        this.lastClips = (int) (clips - clipsBefore);
    }

    /** The whole of perceiving, timed by {@link #tick} so that a horde test and {@code /mmai info} can say what it cost. */
    private void perceive(LivingEntity owner, @Nullable AABB bounds) {

        AABB view = owner.getBoundingBox().inflate(ObservationSchema.VIEW_DISTANCE);

        if (bounds != null) {

            view = view.intersect(bounds);
        }

        double viewSq = ObservationSchema.VIEW_DISTANCE * ObservationSchema.VIEW_DISTANCE;
        double hearingSq = ObservationSchema.HEARING_DISTANCE * ObservationSchema.HEARING_DISTANCE;

        // The middle of the cone is the aim, which is the very direction the observation's own "forward" is measured along, so
        // the cone and the slot cannot disagree about what is in front.
        float yaw = owner.getYRot() * ((float) Math.PI / 180.0F);
        double lookX = -Mth.sin(yaw);
        double lookZ = Mth.cos(yaw);
        double cosHalfCone = Math.cos(Math.toRadians(ObservationSchema.VIEW_CONE_DEGREES / 2.0D));

        LivingEntity hurtBy = owner.getLastHurtByMob();

        // One walk over what is around, not two. Asking the level twice, once for bodies and once for shots, would walk the
        // same entity sections twice, and this is done for every agent on every tick of a run.
        this.bodies.clear();
        this.shots.clear();
        this.looking.clear();

        for (Entity other : owner.level().getEntities(owner, view, candidate -> owner.distanceToSqr(candidate) <= viewSq)) {

            if (other instanceof LivingEntity living) {

                if (!living.isAlive() || !hostile(owner, living)) {

                    continue;
                }

                // Heard or felt needs nothing else: no cone, no clip, through anything. Whoever hits the agent, and whoever is
                // close enough to touch it, is perceived however it is standing.
                if (living == hurtBy || owner.distanceToSqr(living) <= hearingSq) {

                    this.bodies.add(living);
                }

                // Otherwise it has to be in front. The dot product is a couple of multiplications and rules out everything
                // behind the agent before anything is clipped through the world.
                else if (inCone(owner, living, lookX, lookZ, cosHalfCone)) {

                    this.looking.add(living);
                }
            }

            else if (other instanceof Projectile shot && shot.isAlive() && incoming(owner, shot)) {

                this.shots.add(shot);
            }
        }

        this.see(owner);

        // How many are in this fight: everything perceived this tick that is {@link #engaged}, the ones no slot was left for
        // included, and further down the bodies still being remembered. This is what SELF_ENEMIES_IN_RANGE reads. One call of
        // engaged per body, against the log of them the sort below already pays, and it is the same reading the order uses so
        // the count and the slots cannot come to different answers about who is fighting.
        this.awareCount = 0;

        for (LivingEntity body : this.bodies) {

            if (engaged(owner, body)) {

                this.awareCount++;
            }
        }

        // Whose view the order is ranking, for the rest of this call: the sort below and the eviction further down both read
        // it, and both have to rank by the same rule or eviction would undo what the sort decided.
        this.ordering = owner;

        // Who gets a slot first is the fight's business and not the world's; see the class comment. Skipped where there is
        // nothing to order, which is the fight against one opponent and so most ticks of most runs.
        if (this.bodies.size() > 1) {

            this.bodies.sort(this.order);
        }

        this.refreshMemories(owner);

        for (LivingEntity candidate : this.bodies) {

            if (this.slotOf(candidate) >= 0) {

                continue;
            }

            int slot = this.firstFreeSlot();

            if (slot < 0) {

                slot = this.slotToEvictFor(owner, candidate);
            }

            if (slot >= 0) {

                this.take(slot, candidate);
            }
        }

        this.promoteTheEngaged(owner);
        this.leaseProjectiles(owner);

        this.ordering = null;
    }

    /**
     * The clips, for the nearest few candidates that are in front of the agent. Everything that passed the cone is a candidate
     * and everything that got a clip and a line of sight is perceived; the rest wait a tick, which is what keeps this bounded
     * however many bodies stand in front of the agent. Nearest first, because the ten slots go to the nearest anyway.
     */
    private void see(LivingEntity owner) {

        if (this.looking.isEmpty()) {

            return;
        }

        if (this.looking.size() > 1) {

            this.looking.sort((first, second) -> Double.compare(owner.distanceToSqr(first), owner.distanceToSqr(second)));
        }

        int asked = Math.min(this.looking.size(), SIGHT_CLIPS_PER_TICK);

        for (int at = 0; at < asked; at++) {

            LivingEntity candidate = this.looking.get(at);
            clips++;

            if (owner.hasLineOfSight(candidate)) {

                this.bodies.add(candidate);
            }
        }
    }

    /**
     * Whether that body is inside the cone the agent is looking down, which is the first of the three ways of perceiving one
     * and the only one that costs anything after it. Measured on the yaw alone, so a body overhead or underfoot is inside it;
     * see {@link ObservationSchema#VIEW_CONE_DEGREES}.
     *
     * <p>A body the agent is standing in, or one directly above it, has no horizontal direction to compare — the vector is
     * nothing at all — and is counted as inside rather than left to a division by zero.
     */
    private static boolean inCone(LivingEntity owner, LivingEntity other, double lookX, double lookZ, double cosHalfCone) {

        double toX = other.getX() - owner.getX();
        double toZ = other.getZ() - owner.getZ();
        double flat = Math.sqrt(toX * toX + toZ * toZ);

        if (flat < 1.0e-4D) {

            return true;
        }

        return (toX * lookX + toZ * lookZ) / flat >= cosHalfCone;
    }

    /**
     * Moves the bodies that are in this fight in front of the bodies that are merely in the view, among the slots those
     * bodies already hold. A slot otherwise keeps its body, and that is the point of the memory; this is the single exception,
     * and the reason is that the thing the order is built on can change after a slot is handed out.
     *
     * <p>What went wrong without it was reported from a real game: stand an agent in front of a crowd nobody in which has
     * engaged it, and every one of them is ranked the same — not engaged — so the slots go out nearest first. The moment one
     * of them does come for the agent, that body is the fight, and it stayed in whatever slot the walk-up had given it,
     * because the assignment loop above only ever ranks a body that holds no slot. So a network whose one reliable habit is
     * that slot 0 is the fight was handed a fight in slot 4 for the whole of it, which is exactly the fault the order was
     * written to remove, arriving by the other door. Measured on six zombies eight blocks off in plain sight, the one that
     * engaged held slot 3 for 200 ticks and the aim never came onto it; with this pass it is in slot 0 on the tick after it
     * engages. See findings.md.
     *
     * <p>Only engagement moves a slot, never distance. Distance drifts every tick and a view that re-sorted on it would
     * churn under the network for nothing, which is what the slots being stable is for; engagement is a state change that
     * happens once or twice in a fight. So this is the comparator's first key and deliberately not its second: among the
     * engaged, and among the rest, the slots keep the order they were handed out in.
     *
     * <p>Empty slots and the ones holding a shot are left exactly where they are, so nothing here can displace a projectile
     * lease or fill a gap a newcomer is about to take. That means a body dying in slot 0 leaves slot 0 empty with the rest of
     * the fight behind it, and <b>that is deliberate</b>: it is exactly what a squad fight has always looked like once its
     * first member goes down, so it is a shape every network has trained on, where shuffling the whole view up a slot on every
     * death is not.
     */
    private void promoteTheEngaged(LivingEntity owner) {

        int bodies = 0;
        boolean unengagedFirst = false;
        boolean worthDoing = false;

        // One pass to find out whether anything is out of order at all, which on the fight against one opponent and on the
        // squad fight — every tick of most training runs — is the whole of the cost.
        for (int slot = 0; slot < this.occupants.length; slot++) {

            if (!(this.occupants[slot] instanceof LivingEntity body)) {

                continue;
            }

            bodies++;

            if (engaged(owner, body)) {

                worthDoing |= unengagedFirst;
            }

            else {

                unengagedFirst = true;
            }
        }

        if (!worthDoing) {

            return;
        }

        // The body slots, in slot order, and the bodies in them: the engaged ones in the order they were in, then the rest
        // in the order they were in, written back into the same slots with everything each slot knows about its body.
        int[] slots = new int[bodies];
        LivingEntity[] order = new LivingEntity[bodies];
        int[] left = new int[bodies];
        boolean[] seen = new boolean[bodies];
        Vec3[] at = new Vec3[bodies];
        Vec3[] moving = new Vec3[bodies];
        float[] yaw = new float[bodies];
        float[] pitch = new float[bodies];

        int index = 0;
        int taken = 0;

        for (int slot = 0; slot < this.occupants.length; slot++) {

            if (this.occupants[slot] instanceof LivingEntity) {

                slots[index++] = slot;
            }
        }

        for (int pass = 0; pass < 2; pass++) {

            for (int which = 0; which < bodies; which++) {

                int slot = slots[which];
                LivingEntity body = (LivingEntity) this.occupants[slot];

                if (engaged(owner, body) == (pass == 0)) {

                    order[taken] = body;
                    left[taken] = this.remembering[slot];
                    seen[taken] = this.perceived[slot];
                    at[taken] = this.seenAt[slot];
                    moving[taken] = this.seenMoving[slot];
                    yaw[taken] = this.seenYaw[slot];
                    pitch[taken] = this.seenPitch[slot];
                    taken++;
                }
            }
        }

        for (int which = 0; which < bodies; which++) {

            int slot = slots[which];

            this.occupants[slot] = order[which];
            this.remembering[slot] = left[which];
            this.perceived[slot] = seen[which];
            this.seenAt[slot] = at[which];
            this.seenMoving[slot] = moving[which];
            this.seenYaw[slot] = yaw[which];
            this.seenPitch[slot] = pitch[which];
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

    /**
     * Whether that body is in this fight rather than merely in the view: it has come for the agent, or it is on a team set
     * against the agent's. This is what decides which slot it gets, see the class comment, and it is also the whole of what
     * {@link #inRangeCount} counts; both halves are read off {@link Allegiance} so that the order, the count and the slot's
     * own {@code ENEMY_TARGETS_ME} cannot come to different answers.
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

            this.take(slot, shot);
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

    /**
     * Every held slot brought up to this tick: what is still perceived has its reading and its memory refreshed, what is not
     * keeps the reading it had and spends a tick of memory, and what is gone is let go.
     */
    private void refreshMemories(LivingEntity owner) {

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
            // that has already flown past is a slot held for nothing. There is no memory of a shot: an arrow that has stopped
            // being a threat is not a thing to keep track of, it is a stick on the ground.
            if (occupant instanceof Projectile shot) {

                if (incoming(owner, shot)) {

                    this.take(slot, shot);
                }

                else {

                    this.release(slot);
                }

                continue;
            }

            // One that has come over to the agent's side, or can no longer be fought at all, a player gone creative, is let
            // go at once rather than remembered. Nothing else is: a wolf that stops targeting the agent is still a wolf that
            // just bit it.
            if (occupant instanceof LivingEntity body && (Allegiance.allied(owner, body) || !owner.canAttack(body))) {

                this.release(slot);
                continue;
            }

            if (this.bodies.contains(occupant)) {

                this.take(slot, occupant);
                continue;
            }

            // Not perceived this tick: the slot goes on saying where the body was when it last was, and spends a tick of its
            // memory. When that runs out the agent has lost track of it and the slot is free.
            this.perceived[slot] = false;

            if (--this.remembering[slot] <= 0) {

                this.release(slot);
            }

            // A body the agent is remembering counts in the fight if it is still in the fight, asked of the body now rather
            // than of the memory: what is remembered is where it was, never whose side it is on.
            else if (occupant instanceof LivingEntity remembered && engaged(owner, remembered)) {

                this.awareCount++;
            }
        }
    }

    /** Hands a slot to that entity, or brings the one it already holds up to this tick: perceived now, and a full memory. */
    private void take(int slot, Entity perceivedNow) {

        this.occupants[slot] = perceivedNow;
        this.remembering[slot] = ObservationSchema.MEMORY_TICKS;
        this.perceived[slot] = true;

        this.seenAt[slot] = perceivedNow.getEyePosition();
        this.seenMoving[slot] = perceivedNow.getDeltaMovement();
        this.seenYaw[slot] = perceivedNow instanceof LivingEntity living ? living.getYHeadRot() : perceivedNow.getYRot();
        this.seenPitch[slot] = perceivedNow.getXRot();
    }

    private void release(int slot) {

        this.occupants[slot] = null;
        this.remembering[slot] = 0;
        this.perceived[slot] = false;
        this.seenAt[slot] = null;
        this.seenMoving[slot] = null;
        this.seenYaw[slot] = 0.0F;
        this.seenPitch[slot] = 0.0F;
    }

    /**
     * The slot to take for a newcomer that found none free: one the agent is only remembering before anything it can perceive,
     * a projectile's before anything alive, and then the slot of whichever body the newcomer outranks by the most — by the very
     * order the slots are handed out in, so that eviction can never undo what the order decided. A body the newcomer does not
     * outrank keeps its slot, which is what stops the slots churning for nothing.
     *
     * <p>A remembered slot goes first because a body in front of the agent now is worth more than one it is trying to keep
     * track of, and because that is the answer to the one cost of a memory: a crowd that ducked behind rock cannot sit on ten
     * slots while the fight walks up.
     *
     * <p>Ranking by the order rather than by distance alone is what the crowd needs, and it is the same rule read twice
     * instead of two rules that can disagree. With more bodies perceived than slots — a squad of three with nine standing
     * about it is twelve for ten, and a real world's night is worse — a fight walking up to a crowd that got there first used
     * to be shut out of the view altogether, however near it came, because every occupant was nearer. Worse, the first draft
     * of the fix gave the fight a bystander's slot and then watched the evicted bystander take it straight back on the same
     * tick, because the rung underneath still knew only about distance. One order, asked in both places.
     */
    private int slotToEvictFor(LivingEntity owner, LivingEntity candidate) {

        int onlyRemembered = -1;

        int furthestShot = -1;
        double furthestShotSq = -1.0D;

        int ranksLast = -1;
        LivingEntity lastOfThem = null;

        for (int slot = 0; slot < this.occupants.length; slot++) {

            Entity occupant = this.occupants[slot];

            if (occupant == null) {

                continue;
            }

            if (!this.perceived[slot]) {

                onlyRemembered = slot;
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

        return onlyRemembered >= 0 ? onlyRemembered : furthestShot >= 0 ? furthestShot : ranksLast;
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
     * What is in that slot to be described, or null for one the agent has nothing to say about. A slot the agent is
     * remembering answers with its body, and {@link #seenAt} says where the agent last knew it to be: the memory is the whole
     * point, and a slot that went blank the moment the agent turned its head would be the fault this was built to fix.
     */
    @Nullable
    public Entity occupant(int slot) {

        return this.occupants[slot];
    }

    /**
     * Whether that slot is being read from memory rather than from this tick's perception: the body is not in the cone, not
     * within hearing, and has not just hit the agent. Nothing in the observation reads this — the network is told the last
     * known place with the present flag on, as a player's own memory tells them — it is here so a test can tell a body being
     * perceived from one being remembered, which is the difference the window exists to make.
     */
    public boolean remembering(int slot) {

        return this.occupants[slot] != null && !this.perceived[slot];
    }

    /**
     * Where the agent last knew that slot's body to be, its eye position, or null for an empty slot. This and the three
     * below are what the observation's geometry is written from, rather than the entity itself, which is what keeps a
     * remembered body from being read through a wall.
     */
    @Nullable
    public Vec3 seenAt(int slot) {

        return this.seenAt[slot];
    }

    @Nullable
    public Vec3 seenMoving(int slot) {

        return this.seenMoving[slot];
    }

    public float seenYaw(int slot) {

        return this.seenYaw[slot];
    }

    public float seenPitch(int slot) {

        return this.seenPitch[slot];
    }

    /**
     * How many bodies are <b>in this fight</b>: of everything the agent perceived this tick, the ones no slot was left for
     * included, and every body it is still remembering, the ones that are {@link #engaged} — coming for the agent, or on a
     * team set against it. An idle bystander counts nought however close it stands.
     *
     * <p>It used to count everything the agent was aware of, and that was the same fault as the slots by another door. In
     * every plain, squad and self-play fight every body in the view was engaged, so on the fights every network learned from
     * this reads exactly what it always did — the field's own distribution is untouched and the weights that read it stay
     * valid. What changes is the case none of those fights had: a crowd of idle monsters used to push the field seven
     * standard deviations out of anything training had ever shown it, nine bystanders reading 1.0 against a mean of 0.11.
     *
     * <p>Arrows are not counted either — a count that grew with every shot in the air would tell a network that it was
     * outnumbered whenever a skeleton opened fire.
     */
    public int inRangeCount() {

        return this.awareCount;
    }

    /** Whether no slot is held: nothing perceived, and nothing still being remembered. */
    public boolean isEmpty() {

        for (Entity occupant : this.occupants) {

            if (occupant != null) {

                return false;
            }
        }

        return true;
    }

    public void clear() {

        for (int slot = 0; slot < this.occupants.length; slot++) {

            this.release(slot);
        }

        this.awareCount = 0;
    }

    /** See {@link #clips}: for the test that holds the cost of perceiving, and nothing else. */
    public static long clips() {

        return clips;
    }

    /** Clips through the world this view asked for on its last tick; see {@link #lastClips}. */
    public int lastClips() {

        return this.lastClips;
    }

    /** How long perceiving took this view on its last tick, in microseconds; see {@link #lastClips}. */
    public double lastMicros() {

        return this.lastNanos / 1000.0D;
    }
}
