package net.sievert.modularmobai.brain;

import java.util.Arrays;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

import net.minecraft.util.Mth;
import net.sievert.modularmobai.brain.schema.ActionSchema;
import net.sievert.modularmobai.brain.schema.Species;
import net.sievert.modularmobai.brain.schema.AgentObservation;
import net.sievert.modularmobai.brain.schema.ObservationSchema;
import net.sievert.modularmobai.entity.agent.AgentMob;
import net.sievert.modularmobai.entity.agent.MobControls;

/**
 * A hand written fighter, useful twice over.
 *
 * <p>First as a test: it plays the fight from nothing but the observation vector, never touching the entity it is
 * driving. If this can close, aim and land hits knowing only those numbers, the observation is describing the world
 * correctly. If it walks into walls or swings at nothing, the observation is wrong and no amount of training would have
 * fixed it, it would only have hidden it.
 *
 * <p>Second as an opponent and as the teacher: this is the rung of the ladder between the vanilla mobs and self play, and
 * the network is started by copying it. That second use is why it uses everything it carries rather than only a sword.
 * Reinforcement learning only improves what it samples, and a network seeded from a teacher that never pressed use never
 * pressed use either: over the last 400 league fights of the first league run it held use on 0 of 79,724 ticks, fired no
 * arrows and raised no shield, and a bow needs twenty ticks of held use before the first arrow ever flies. Nothing in the
 * reward can find that by accident, so the teacher has to show it.
 *
 * <p>It fights the way the reach allows. A sword reaches three blocks from the eyes, so a mob's middle can be about three
 * and a quarter blocks away and still be hit; a mob's own melee reaches under a block and a half head on, two across a
 * diagonal. So it wants to stand in the band between, with nothing between its eyes and the target's, and swing the
 * moment the target arrives with the cooldown recovered, backing away while the cooldown recovers.
 *
 * <p>Getting to such a place is a search, not a reflex. Every tick it finds, over the terrain grid it is given, every spot
 * it could walk to, stepping up what a jump clears and down what it can drop, and heads for the nearest one in the band
 * with a clear line, or failing that as close to its target as the ground allows. Walking straight at a target with a
 * tree in the way, it used to stand behind the trunk and sidestep while the target walked round it, and neither landed a
 * blow for the whole minute.
 *
 * <p>The one thing that comes before the fight is being in something that hurts. The search keeps the agent off those
 * cells, but a blow knocks a body about a block and does not ask the ground first, and powder snow is a trap it does not
 * leave on its own: a body in it cannot jump out and freezes where it stands. So a hazard under the agent's own feet sends
 * it out by the shortest way there is, and where walking gets it nowhere it breaks the block instead.
 *
 * <h2>How a blow is thrown</h2>
 *
 * <p>Vanilla gives one swing three shapes and lets a fighter pick at most one of them. Sprinting into it adds a point of
 * knockback; falling into it adds half again the damage, and only if the fighter is not sprinting; standing still with a
 * sword sweeps everything else in reach, and either of the other two cancels that. So every blow is a choice, and this
 * one chooses:
 *
 * <ul>
 *   <li><b>Into a hazard.</b> The push goes exactly along the agent's own look, so a hazard directly behind the target is
 *       a hazard the target can be knocked into, and the ground kills it. That is the best blow there is against the
 *       league's heaviest, and it is what the terrain grid's hazard mark is for: lava, fire, magma, and the edge of a drop
 *       nothing survives.</li>
 *   <li><b>For the knockback</b> otherwise, whenever what is in front of the agent is worth having further off: a reach
 *       longer than a man's, empty hands that have not swung yet, or health already spent.</li>
 *   <li><b>For the critical</b> when none of that applies, by leaving the ground exactly as many ticks ahead of a full
 *       cooldown as a jump spends coming down. An earlier attempt at this guessed that gap and gave it up; this one reads
 *       the cooldown's own rate off two ticks of the observation, so it holds for a sword and an axe alike.</li>
 * </ul>
 *
 * <h2>Everything else it carries</h2>
 *
 * <p>A drawn weapon, a shield and a lit creeper each need something the observation does not carry: whether the weapon
 * drawing is a bow or a crossbow, whether there is a shield in the off hand at all, whether the thing in front of it has
 * ever swung. So this brain keeps a little state per agent, keyed by the agent id the step carries, and works the rest out
 * from what the body reports back:
 *
 * <ul>
 *   <li><b>Bow or crossbow.</b> Both read as one item category, because the layout has one for a drawn weapon, and how far
 *       either has charged is in the echo, so neither has to be named to be used: hold until it reads charged, then let
 *       go. What is left over is told apart by what a deliberate release does. A wound crossbow loads and fires nothing,
 *       so the press after that sends the bolt and leaves the hands free, where a bow has already fired and starts drawing
 *       again. One cycle settles it, and neither weapon loses a shot to the question.</li>
 *   <li><b>Ammunition.</b> A bow with nothing to fire does not so much as come up, so a press that resolves and leaves
 *       the hands with nothing charged says the quiver is out, and the fighter goes back to swinging for the rest of the
 *       fight.</li>
 *   <li><b>A shield.</b> The self block says whether the off hand is in use but not what is in it, so the first raise is
 *       also the question: press it, and if the off hand comes up there is a shield there. If it does not, there is none
 *       to raise, unless one has come up before, in which case an axe has just knocked it aside and it will be back in
 *       five seconds.</li>
 * </ul>
 *
 * <p>Both of those last two read an answer out of a press, and a press this brain asks for does not always happen: as a
 * teacher it labels a student's fight, and there the body is doing what the student said. So neither concludes anything
 * until the press is known to have landed, which the use cooldown says outright, since any press with the cooldown clear
 * sets it to full. Without that the teacher would decide on the first tick of the first fight that it had no arrows and no
 * shield, and would never show a student either again.
 *
 * <p>A network copying this has to carry the same few things in its own memory, which it has 128 numbers of. That is the
 * price of a weapon needing twenty ticks of commitment, and it is why a release is decided by the charge and the aim rather
 * than by a clock: both are in the observation, so what the record shows is a fighter letting go because it is charged and
 * on target, which is a rule that can be read off what the network sees.
 */
public final class ScriptedBrain implements Brain {

    /** Closer than this and it backs away: out of a mob's reach even across the diagonal, with a little to spare. */
    private static final float BACK_OFF_RANGE = 2.4F;

    /** Farther than this and it closes in. Between the two it holds its ground and lets the target come. */
    private static final float CLOSE_IN_RANGE = 3.0F;

    /** Eye to eye, the farthest a swing at the middle of a mob still meets its box. */
    private static final float SWING_RANGE = 3.2F;

    /** How far off the target the aim may be and still be worth swinging. */
    private static final float SWING_CONE_DEGREES = 20.0F;

    private static final float SPRINT_RANGE = 6.0F;

    /**
     * The cooldown as the observation reads it, when a swing lands at full strength. The observation is taken half a tick
     * before the swing resolves, so this is a little short of one.
     */
    private static final float FULL_STRENGTH = 0.96F;

    /** A player's eyes above its feet, where a swing starts from. */
    private static final double EYE_HEIGHT = 1.62D;

    /** Where in the echo of last tick's controls a swing, a swing that landed, and the charge of a use are written. */
    private static final int ECHO_ATTACKED = 7;
    private static final int ECHO_HIT = 8;
    private static final int ECHO_USE_PROGRESS = 19;

    private static final int X = ObservationSchema.TERRAIN_X;
    private static final int Z = ObservationSchema.TERRAIN_Z;
    private static final int CENTRE = ObservationSchema.TERRAIN_RADIUS_XZ;
    private static final int FEET = ObservationSchema.TERRAIN_RADIUS_Y;

    /** Where it can stand: one level below its feet, level with them, or one above, which the grid has room around. */
    private static final int LEVELS = 3;

    private static final int[] STEP_X = {1, -1, 0, 0, 1, 1, -1, -1};
    private static final int[] STEP_Z = {0, 0, 1, -1, 1, -1, 1, -1};

    // The search's working space, reused for every agent on every tick. A brain is only ever stepped from the server
    // thread.
    private final int[] depth = new int[X * Z * LEVELS];
    private final int[] parent = new int[X * Z * LEVELS];
    private final int[] queue = new int[X * Z * LEVELS];
    private final boolean[] dropped = new boolean[X * Z * LEVELS];

    /**
     * How far below it a target has to be, eye to eye, before dropping down to it is worth considering, and how far at
     * most. A fall costs a heart for every block past the third, so five blocks is about one heart.
     */
    private static final double MIN_DROP = 1.5D;
    private static final double MAX_DROP = 5.5D;

    /**
     * How far it will fall to reach a target that is stuck below and not coming up. A fall costs one health for every
     * block past the third, so nine leaves fourteen of twenty, still more than a vindicator's axe takes in one blow:
     * dropping in costs no more blows to die than staying up did.
     */
    private static final double MAX_DROP_TO_STUCK = 9.0D;

    /** Slower than this, in the observation's velocity units, a target is standing still rather than coming. */
    private static final float STILL_SPEED = 0.1F;

    // ---------------------------------------------------------------------------------------------------------------
    // How a blow is thrown: knockback, a critical, or into a hazard
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * A blow thrown while sprinting carries a whole point of extra knockback, which is half a block of impulse and about a
     * block and a half of travel once the target's own momentum is spent. A sprint only ever lasts the tick it is asked
     * for here, since the body reads the control fresh every tick and the blow itself cancels the sprint, so every blow is
     * its own sprint and there is nothing to reset: a player has to let the key go and press it again, and the agent's
     * equivalent is simply asking for it on the tick it swings.
     *
     * <p>Vanilla only lets a sprint start while pushing forward, so a sprint blow is also a step forward. That is what it
     * costs: the fighter closes on the target as it hits, and the knockback has to pay for that as well.
     */
    private static final float SPRINT_BLOW_FORWARD = 1.0F;

    /**
     * A critical is worth half again as much damage, which against a vindicator's twenty four health is three blows
     * instead of four, and vanilla only grants one to a body that is falling, is not on the ground and is not sprinting.
     * So a critical and a sprint blow are two ways to throw the same swing, never one blow with both.
     *
     * <p>An earlier attempt at this timed the jump by the clock and gave it up: the fall rarely lined up with the target
     * walking into reach, one hit in ten landed as a critical, and it won no more fights (see docs/findings.md). What is
     * different here is that the cooldown is not guessed. The observation says how far it has recovered, and the rate it
     * recovers at is the difference between two ticks of that, so the fighter knows how many ticks from a full swing it
     * is, whatever it is holding, and jumps exactly that far ahead of one.
     */
    private static final int CRIT_JUMP_EARLIEST = 7;
    private static final int CRIT_JUMP_LATEST = 9;

    /**
     * A player's jump is in the air for about twelve ticks and its fall distance only rises once it is past the top,
     * around the seventh, which is the window a critical can land in. Asking again inside that window would do nothing
     * anyway, since a jump only leaves the ground.
     */
    private static final int CRIT_JUMP_COOLDOWN = 14;

    /** Below this a difference in the cooldown between two ticks is noise rather than the rate it recovers at. */
    private static final float STRENGTH_RATE_FLOOR = 0.01F;

    /**
     * How far past the target a hazard can be and still be somewhere a sprint blow puts it. A point of extra knockback is
     * half a block of impulse, and what the target keeps of it carries it about a block and a half further, so two and a
     * half blocks is the far edge of what one blow reaches and a little to spare for a target already walking that way.
     */
    private static final double HAZARD_PUSH = 2.5D;

    /** How finely the ground beyond the target is walked while looking for somewhere it could be knocked into. */
    private static final double HAZARD_STEP = 0.25D;

    /** The scale the observation puts velocities on, so the numbers in the enemy block come back as blocks a tick. */
    private static final double VELOCITY_SCALE = 0.5D;

    // ---------------------------------------------------------------------------------------------------------------
    // Bows and crossbows
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * When a drawn weapon is charged, as the echo reads it. The body writes the item's own reckoning there: a bow reads one
     * at the twenty ticks that make a full power arrow, a crossbow at the twenty five its wind takes. So one number says
     * "let go now" for either of them, and the fighter does not have to know which it is holding to know when.
     */
    private static final float FULL_CHARGE = 0.999F;

    /** A full draw's arrow, and a bolt, in blocks a tick, and what happens to either every tick: see AbstractArrow. */
    private static final double ARROW_SPEED = 3.0D;
    private static final double BOLT_SPEED = 3.15D;
    private static final double ARROW_DRAG = 0.99D;
    private static final double ARROW_GRAVITY = 0.05D;

    /** An arrow leaves a tenth of a block below the eyes, which is where the shot is aimed from. */
    private static final double ARROW_DROP_AT_LAUNCH = 0.1D;

    /** No shot is worth more than this many ticks in the air; nothing inside the view distance is that far off. */
    private static final int MAX_FLIGHT_TICKS = 120;

    /** How steeply a shot is ever thrown. The arc to anything in view is a few degrees, so this only bounds the search. */
    private static final double MAX_ELEVATION = Math.PI / 4.0D;

    /** How near the shot has to be before it is loosed. Under a degree is past what a player's own spread allows anyway. */
    private static final float FIRE_CONE_DEGREES = 3.0F;

    /**
     * Farther than this and a fighter with something to shoot shoots instead of closing in. Inside it a sword does more
     * per tick than a bow needing twenty of them, so the bow goes away, unless there is nothing to swing.
     */
    private static final float SHOOT_RANGE = 5.0F;

    /**
     * A draw already under way is worth finishing even as the target closes, since a full arrow is most of a sword's blow
     * and nearly in hand. Inside this the draw is dropped for the sword instead: changing slots cancels a draw without
     * firing, so the arrow is kept rather than thrown away.
     */
    private static final float ABANDON_DRAW_RANGE = 2.6F;

    // ---------------------------------------------------------------------------------------------------------------
    // The shield
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * How close something has to be for a shield to be worth holding up: a little past a swing's own reach, which also
     * covers a ravager's, the longest in the league at about four blocks from its middle.
     *
     * <p>Not a block further. A shield takes the movement keys down to a fifth, so a fighter that holds one up while it
     * still has ground to cover never covers it. The shield is for the reach; the answer to an archer further off is to
     * close the distance.
     */
    private static final float BLOCK_RANGE = 4.2F;

    /**
     * Eye to eye, how far off something can be and still have reached the agent with a swing. A mob's melee reach goes
     * with its width: about one and a half blocks for anything man sized, and four for a ravager, which is nearly two
     * blocks wide. So a swing that landed from further off than this came from something with a reach of its own, and
     * that is worth a raised shield even in a fight that is going well: a blocked ravager is stunned for two seconds, and
     * it carries no axe to knock the shield aside with.
     */
    private static final float LONG_REACH_DISTANCE = 2.6F;

    /**
     * How close something already in the air has to be before the shield goes up for it. A full drawn arrow covers three
     * blocks a tick, so twelve is four ticks, which is time enough for a press to resolve and no longer than it has to be:
     * a shield up for the whole flight of every shot is a fighter that never covers the ground to the archer.
     */
    private static final float BLOCK_SHOT_RANGE = 12.0F;

    // ---------------------------------------------------------------------------------------------------------------
    // Creepers
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * A creeper lights its fuse within three blocks of its target and stands still while it burns, for thirty ticks, and
     * it is the one thing in the league that fights with nothing in its hands and never swings. So an empty handed
     * opponent that has never swung and has stopped coming this close is one about to go off.
     *
     * <p>Three blocks and not one more, because it is the creeper's own figure. Everything else with empty hands stops at
     * its own reach and swings from there, and a ravager's reach is four blocks: read any wider and a ravager waiting out
     * its own cooldown is taken for a lit creeper, walked away from before it ever swings, and never seen to swing at
     * all.
     */
    private static final float FUSE_RANGE = 3.05F;

    /**
     * How many ticks it has to have spent not coming any closer. Whether it is coming, rather than whether it is moving
     * at all: a creeper that has just been hit slides backwards from the blow for a dozen ticks, and waiting for that to
     * settle would spend half the fuse standing in the blast.
     */
    private static final int FUSE_STILL_TICKS = 3;

    /**
     * How far away is far enough. A creeper's fuse only winds back down past seven blocks, and its blast still takes ten
     * health at four, so nothing short of this is worth walking back from.
     */
    private static final float FUSE_SAFE_RANGE = 7.5F;

    // ---------------------------------------------------------------------------------------------------------------
    // What one agent is in the middle of
    // ---------------------------------------------------------------------------------------------------------------

    /** Which of the two drawn weapons an agent turned out to be holding. */
    private static final int WEAPON_UNKNOWN = 0;
    private static final int WEAPON_BOW = 1;
    private static final int WEAPON_CROSSBOW = 2;

    /** Nothing in the hands, a draw under way, a bolt held ready, or the one tick press that sends it. */
    private static final int DRAW_IDLE = 0;
    private static final int DRAW_DRAWING = 1;
    private static final int DRAW_LOADED = 2;
    private static final int DRAW_FIRING = 3;

    /** Whether the off hand holds a shield, which the observation does not say until one has been raised. */
    private static final int SHIELD_UNKNOWN = 0;
    private static final int SHIELD_NONE = 1;
    private static final int SHIELD_CARRIED = 2;

    /**
     * What a fighter knows about itself that the observation leaves out. One of these per agent, kept for as long as the
     * agent is being driven.
     */
    private static final class Fighter {

        private int weapon = WEAPON_UNKNOWN;
        private int draw = DRAW_IDLE;

        /** Whether a press of use went out on the tick before, which is what makes the next tick's answer meaningful. */
        private boolean asked;

        /** Whether the drawn weapon has anything left to fire. Nothing puts arrows back, so this lasts the fight. */
        private boolean spent;

        private int shield = SHIELD_UNKNOWN;

        /** Set on the ticks a press went out, which is what makes the next tick's answer meaningful. */
        private boolean askedShield;

        /** Ticks to wait before trying the shield again, after an axe knocked it aside. */
        private int shieldWait;

        /** Whether the off hand has ever come up, which tells a shield knocked aside from no shield at all. */
        private boolean shieldSeen;

        /** Enemy slots whose occupant has swung at some point: a bitmask, one bit a slot. */
        private int swung;

        /** Slots whose occupant swung from further off than anything man sized can reach. */
        private int longReach;

        /** How long the occupant of each slot has gone without coming any closer. */
        private final byte[] holdingBack = new byte[ObservationSchema.ENEMY_SLOTS];

        /** Whether it is walking away from something it takes for a lit creeper, which it does until it is well clear. */
        private boolean fleeing;

        /** The attack cooldown last tick, and how much of it comes back in a tick, which is one over the weapon's delay. */
        private float lastStrength;
        private float strengthRate;

        /** Ticks since it jumped for a critical, so it does not ask again while it is still in the air. */
        private int sinceJump = CRIT_JUMP_COOLDOWN;

        /** The brain step this was last asked about, so an agent nothing ever finished can be let go of. */
        private long seen;

        /** A fresh fight: the body's cooldowns and hands all start again, so nothing at all carries over. */
        private void reset() {

            this.weapon = WEAPON_UNKNOWN;
            this.draw = DRAW_IDLE;
            this.asked = false;
            this.spent = false;
            this.shield = SHIELD_UNKNOWN;
            this.askedShield = false;
            this.shieldWait = 0;
            this.shieldSeen = false;
            this.swung = 0;
            this.longReach = 0;
            Arrays.fill(this.holdingBack, (byte) 0);
            this.fleeing = false;
            this.lastStrength = 0.0F;
            this.strengthRate = 0.0F;
            this.sinceJump = CRIT_JUMP_COOLDOWN;
        }

        /** Nothing in sight: a draw is cancelled by the slot it is held in going away, and there is nothing to flee. */
        private void idle() {

            this.draw = this.draw == DRAW_LOADED ? DRAW_LOADED : DRAW_IDLE;
            this.asked = false;
            this.askedShield = false;
            this.fleeing = false;
        }

        private boolean hasSwung(int slot) {

            return (this.swung & 1 << slot) != 0;
        }

        private boolean reachesFar(int slot) {

            return (this.longReach & 1 << slot) != 0;
        }
    }

    /**
     * What each agent is in the middle of, by the agent id its step carries. Rows are not agents and an agent that dies
     * shifts every row after it up one, so the id is the only thing that follows one fighter.
     */
    private final Int2ObjectMap<Fighter> fighters = new Int2ObjectOpenHashMap<>();

    private long steps;

    /**
     * How often the fighters are swept for agents that have gone. A fight's last step is normally flagged done, and that
     * is where a fighter is let go, but an agent that won a fight it was not being paid for, a league opponent, is simply
     * taken out of the world with no last step at all. Without a sweep those would pile up for the life of the process.
     */
    private static final int SWEEP_EVERY = 4096;
    private static final int SWEEP_AFTER_STEPS = 1200;

    /**
     * A player-shaped fighter: every field it reads and every control it presses is the humanoid's, so it drives that body
     * and no other. A second body wanting a hand written teacher writes its own.
     */
    @Override
    public Species species() {

        return Species.HUMANOID;
    }

    @Override
    public void act(BrainStep step) {

        this.steps++;

        Arrays.fill(step.actions, 0, step.count * ActionSchema.ACT_DIM, 0.0F);

        for (int index = 0; index < step.count; index++) {

            int id = step.agentIds[index];

            if ((step.flags[index] & BrainStep.FLAG_DONE) != 0) {

                this.fighters.remove(id);
                continue;
            }

            Fighter fighter = this.fighters.get(id);

            if (fighter == null) {

                fighter = new Fighter();
                this.fighters.put(id, fighter);
            }

            else if ((step.flags[index] & BrainStep.FLAG_NEW) != 0) {

                fighter.reset();
            }

            fighter.seen = this.steps;

            this.actFor(step, fighter, index * ObservationSchema.OBS_DIM, index * ActionSchema.ACT_DIM);
        }

        if (this.steps % SWEEP_EVERY == 0) {

            this.sweep();
        }
    }

    /** Lets go of every fighter that has not been asked about for longer than a fight lasts. */
    private void sweep() {

        ObjectIterator<Int2ObjectMap.Entry<Fighter>> entries = this.fighters.int2ObjectEntrySet().iterator();

        while (entries.hasNext()) {

            if (this.steps - entries.next().getValue().seen > SWEEP_AFTER_STEPS) {

                entries.remove();
            }
        }
    }

    private void actFor(BrainStep step, Fighter me, int obs, int act) {

        float[] o = step.observations;
        float[] a = step.actions;

        int self = obs + ObservationSchema.SELF_OFFSET;
        boolean grounded = o[self + ObservationSchema.SELF_ON_GROUND] > 0.5F;

        // Swimming up is the same key as jumping, and an agent that stops pressing it in deep water drowns, whether or not
        // there is anything in sight.
        boolean inWater = o[self + ObservationSchema.SELF_IN_WATER] > 0.5F;
        a[act + ActionSchema.JUMP] = inWater ? 1.0F : 0.0F;

        // Which way the agent is facing, which is what turns a grid direction into a movement key.
        float sin = o[self + ObservationSchema.SELF_AIM_SIN];
        float cos = o[self + ObservationSchema.SELF_AIM_COS];

        // Standing in something that hurts comes before the fight, whatever the fight is doing. The planner keeps the
        // agent off hazards, but a blow knocks it onto them, and powder snow is the one it does not come back from on its
        // own: a body in it cannot jump out, and it freezes where it stands.
        if (inHazard(o, obs)) {

            this.getOut(o, a, obs, act, sin, cos, grounded);
            return;
        }

        int slot = nearestEnemy(o, obs);

        if (slot < 0) {

            me.idle();
            return;
        }

        int target = obs + ObservationSchema.enemyOffset(slot);
        watch(me, slot, o, target);

        // Positions arrive in the agent's own frame, already scaled down by the view distance.
        float forward = o[target + ObservationSchema.ENEMY_FORWARD];
        float right = o[target + ObservationSchema.ENEMY_RIGHT];
        float up = o[target + ObservationSchema.ENEMY_UP];
        float distance = o[target + ObservationSchema.ENEMY_DISTANCE] * (float) ObservationSchema.VIEW_DISTANCE;

        // Back from the agent's frame to the world's, in blocks: forward runs along minus sine, cosine and right along
        // minus cosine, minus sine. The grid puts the agent in the middle of its own column.
        double view = ObservationSchema.VIEW_DISTANCE;
        double targetX = CENTRE + 0.5D + (-sin * forward - cos * right) * view;
        double targetZ = CENTRE + 0.5D + (cos * forward - sin * right) * view;
        double targetEye = FEET + EYE_HEIGHT + up * view;

        // The game says more than the grid does. A swing last tick that hit nothing yet left the cooldown standing met a
        // block on the way, since only a swing at thin air costs the cooldown, and the grid, which only knows which block
        // the agent is in and not where in it, can call that same line clear. Left at that, it stood where it was and
        // clicked at the leaves every tick for the rest of the minute.
        float strength = o[self + ObservationSchema.SELF_ATTACK_STRENGTH];
        int echo = obs + ObservationSchema.ECHO_OFFSET;
        boolean swungIntoBlock = o[echo + ECHO_ATTACKED] > 0.5F && o[echo + ECHO_HIT] < 0.5F && strength >= FULL_STRENGTH;

        time(me, strength);

        boolean clear = !swungIntoBlock && !blocked(o, obs, CENTRE, 0, CENTRE, targetX, targetEye, targetZ);

        // What it is carrying, and so what it can do about whatever is in front of it.
        int melee = meleeSlot(o, obs);
        int ranged = me.spent ? -1 : slotHolding(o, obs, AgentObservation.ITEM_RANGED);

        boolean shooting = shoots(me, ranged, melee, distance, clear);
        boolean fleeing = !shooting && this.flees(me, slot, o, target, distance);

        // Anything with empty hands that has not swung yet might be a creeper, so while the swing is still cooling it is
        // held at the three blocks a creeper needs to light its fuse, rather than let inside the usual band. It costs
        // nothing against the rest of them, since an empty handed mob reaches a block and a half, two across a diagonal:
        // the fighter steps in to swing and back out to wait, and is hitting something that cannot reach it.
        boolean mayExplode = emptyHanded(o, target) && !me.hasSwung(slot);
        float backOff = mayExplode && strength < FULL_STRENGTH ? FUSE_RANGE : BACK_OFF_RANGE;

        // Where to look. A swing goes where the eyes are; a shot has to be thrown ahead of the target and above it, by
        // whatever the arrow falls over the ground it has to cover and whatever the target covers while it flies.
        if (shooting) {

            this.shoot(me, o, a, obs, act, ranged, target, forward, right, up);

            // A drawn weapon takes the movement keys down to a fifth, so there is no walking out of trouble while it is
            // up: it holds its ground, steps away from anything already on top of it, and walks up to find a line when
            // the one it has is blocked.
            if (distance < BACK_OFF_RANGE) {

                this.walkTowards(this.retreat(o, obs, targetX, targetZ), a, act, sin, cos, grounded);
            }

            else if (!clear) {

                this.walkTowards(this.approach(o, obs, targetX, targetEye, targetZ, false, false), a, act, sin, cos, grounded);
            }

            return;
        }

        // Turning right is a rising yaw, and a target off to the right has a positive right component, so the error and
        // the control share a sign and no correction is needed.
        float yawError = (float) Math.toDegrees(Mth.atan2(right, forward));
        a[act + ActionSchema.AIM_YAW] = Mth.clamp(yawError / MobControls.MAX_AIM_YAW_PER_TICK, -1.0F, 1.0F);

        float horizontal = (float) Math.sqrt(forward * forward + right * right) * (float) ObservationSchema.VIEW_DISTANCE;
        float wantedPitch = (float) -Math.toDegrees(Mth.atan2(up * (float) ObservationSchema.VIEW_DISTANCE, horizontal));
        float pitch = o[self + ObservationSchema.SELF_PITCH] * 90.0F;

        a[act + ActionSchema.AIM_PITCH] =
                Mth.clamp((wantedPitch - pitch) / MobControls.MAX_AIM_PITCH_PER_TICK, -1.0F, 1.0F);

        // Whatever it swings with, held ready. A draw that was under way is cancelled by the slot changing under it,
        // which is the one way of letting a nocked arrow go without firing it.
        a[act + ActionSchema.SELECTED_SLOT] = Math.max(0, melee);

        if (me.draw == DRAW_DRAWING) {

            me.draw = DRAW_IDLE;
            me.asked = false;
        }

        int start = state(CENTRE, 0, CENTRE);
        int next = start;

        boolean retreating = fleeing || distance < backOff;

        // Somewhere to stand that puts a hazard directly behind the target, which turns one blow into the whole fight.
        // Every such spot is already inside the band with a clear line, so wanting one never argues with the footwork.
        int shove = retreating ? -1 : this.hazardSpot(o, obs, distance, targetX, targetEye, targetZ);
        boolean linedUp = shove == start;

        if (retreating) {

            next = this.retreat(o, obs, targetX, targetZ);
        }

        else if (shove >= 0) {

            next = shove;
        }

        else if (distance > CLOSE_IN_RANGE || !clear) {

            float targetSpeed = Math.abs(o[target + ObservationSchema.ENEMY_VELOCITY_FORWARD])
                    + Math.abs(o[target + ObservationSchema.ENEMY_VELOCITY_RIGHT]);

            next = this.approach(o, obs, targetX, targetEye, targetZ, swungIntoBlock, targetSpeed < STILL_SPEED);
        }

        if (next != start) {

            this.walkTowards(next, a, act, sin, cos, grounded);

            if (distance > SPRINT_RANGE && a[act + ActionSchema.MOVE_FORWARD] >= MobControls.SPRINT_FORWARD_THRESHOLD) {

                a[act + ActionSchema.SPRINT] = 1.0F;
            }
        }

        // Asked to move on the tick before and did not: the grid missed something, a corner or an edge of a block the
        // body is wider than. A jump gets it over most of those.
        if (grounded && stuck(o, obs)) {

            a[act + ActionSchema.JUMP] = 1.0F;
        }

        // The shield goes up while there is nothing better to do with the hands, and comes down to strike. Nothing can be
        // swung while anything is in use, not even on the tick it is let go, so a fighter holding one up has no attack.
        boolean busy = this.hold(me, o, a, obs, act, slot, target, distance, strength, incomingDistance(o, obs), fleeing);

        if (busy) {

            return;
        }

        // Whether this blow wants to carry knockback rather than the damage a critical adds. A hazard behind the target is
        // the whole fight; a reach longer than a man's, empty hands that have not swung yet, and health already gone are
        // each a reason to want whatever is in front of the agent further away than it is. Never while backing away, since
        // a sprint is forward only: a player cannot sprint out of a fight either.
        boolean shoving = !retreating
                && (linedUp || me.reachesFar(slot) || mayExplode || o[self + ObservationSchema.SELF_HEALTH] < 1.0F);

        // Swinging wide costs the whole cooldown, so the swing waits until the target is actually in front of it. It also
        // waits for the cooldown to come all the way back: damage goes with the square of it, so a swing at nine tenths
        // does barely more than five of a sword's six, and a vindicator then takes five hits instead of four. A swing
        // that meets a block on the way costs nothing, as a player's does, and clears grass and ferns out of the way, so a
        // line the grid says is blocked is no reason to hold back; it only decides where to walk.
        boolean aimed = distance <= SWING_RANGE && Math.abs(yawError) < SWING_CONE_DEGREES;

        if (aimed && strength >= FULL_STRENGTH) {

            a[act + ActionSchema.ATTACK] = 1.0F;

            if (shoving) {

                // A sprint is forward only, so the blow steps into the target as it lands. Full deflection, since anything
                // short of four fifths is not a sprint at all.
                a[act + ActionSchema.MOVE_FORWARD] = SPRINT_BLOW_FORWARD;
                a[act + ActionSchema.SPRINT] = 1.0F;
            }

            return;
        }

        // Not sprinting, not shoving, and the cooldown is a jump's fall away from full: leave the ground now and the swing
        // lands while falling, which is a critical. Nothing is given up for it, since the swing was not ready anyway.
        if (!shoving && aimed && grounded && this.jumpsForCrit(me, strength)) {

            a[act + ActionSchema.JUMP] = 1.0F;
            a[act + ActionSchema.SPRINT] = 0.0F;
            me.sinceJump = 0;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Timing a blow by the cooldown
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Keeps track of how fast the attack cooldown comes back, which is one over the delay of whatever is held and is the
     * difference between two ticks of it. Nothing else says it: an axe takes twenty ticks to a sword's twelve and a half,
     * and the observation carries how far the cooldown has recovered but never how long that takes.
     */
    private static void time(Fighter me, float strength) {

        float risen = strength - me.lastStrength;

        if (risen > STRENGTH_RATE_FLOOR && strength < 1.0F) {

            me.strengthRate = risen;
        }

        me.lastStrength = strength;
        me.sinceJump = Math.min(CRIT_JUMP_COOLDOWN, me.sinceJump + 1);
    }

    /**
     * Whether now is the tick to leave the ground so that the swing lands on the way down. A player's jump is about twelve
     * ticks in the air and its fall distance only rises past the seventh, so the swing has to be ready somewhere in that
     * window and not before it: jump too early and the fighter is back on the ground with the cooldown still short, too
     * late and the swing goes out flat footed.
     */
    private static boolean jumpsForCrit(Fighter me, float strength) {

        if (me.strengthRate <= 0.0F || me.sinceJump < CRIT_JUMP_COOLDOWN) {

            return false;
        }

        double ticks = (1.0D - strength) / me.strengthRate;

        return ticks >= CRIT_JUMP_EARLIEST && ticks <= CRIT_JUMP_LATEST;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Knocking something into a hazard
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Somewhere to stand from which a sprint blow sends the target into something that will finish it: lava, fire, a magma
     * block, or the edge of a drop the grid marks as one nothing survives. A mob the ground kills is still the agent's
     * win, and against the league's heaviest, a hundred health behind three quarters knockback resistance, it is worth far
     * more than the four blows it saves against a vindicator.
     *
     * <p>The push goes exactly along the agent's own look, since that is how vanilla throws it, and the agent looks at what
     * it is fighting. So lining up is a matter of standing where the agent, the target and the hazard fall on one line,
     * which is what this searches the reachable spots for.
     *
     * @return the first step towards such a spot, the spot the agent already stands on when it is one, or -1 for none
     */
    private int hazardSpot(float[] o, int obs, float distance, double targetX, double targetEye, double targetZ) {

        // Two cheap questions before the expensive one. The search over the grid is the costliest thing this brain does and
        // it already runs once or twice a tick, so it is not run a third time for a target too far off for any spot in the
        // band to reach, or on ground with nothing on it that could hurt anybody.
        if (distance > SWING_RANGE + (float) HAZARD_PUSH || !anyHazard(o, obs)) {

            return -1;
        }

        int reached = this.search(o, obs, false);
        int best = -1;
        int bestDepth = Integer.MAX_VALUE;

        for (int i = 0; i < reached; i++) {

            int s = this.queue[i];

            if (this.dropped[s] || this.depth[s] >= bestDepth) {

                continue;
            }

            int x = s % X;
            int level = s / (X * Z) - 1;
            int z = (s / X) % Z;

            double across = Math.hypot(targetX - (x + 0.5D), targetZ - (z + 0.5D));
            double reach = Math.hypot(across, targetEye - (FEET + level + EYE_HEIGHT));

            // Only ever from inside the band it fights in anyway: a spot it would have to walk out of reach to take is a
            // spot that costs more than the blow is worth.
            if (reach < BACK_OFF_RANGE || reach > SWING_RANGE - 0.1D || across < 0.5D) {

                continue;
            }

            if (!hazardBeyond(o, obs, targetX, targetEye, targetZ, (targetX - (x + 0.5D)) / across,
                    (targetZ - (z + 0.5D)) / across)) {

                continue;
            }

            if (blocked(o, obs, x, level, z, targetX, targetEye, targetZ)) {

                continue;
            }

            best = s;
            bestDepth = this.depth[s];
        }

        return best < 0 ? -1 : this.firstStep(best);
    }

    /**
     * Whether the ground just past the target, along the way it would be pushed, is somewhere a body does not come back
     * from. Walked a quarter block at a time out to as far as one blow carries.
     *
     * <p>Two things count. A hazard where the target's own feet or body would end up is one: lava, fire, a magma block, a
     * cactus. Nothing under it out to the bottom of the grid is the other, with the bottom cell itself marked a hazard,
     * which is how the grid says a fall from there is more than eight blocks or ends in something worse; that is a ravine,
     * and it is what the terrain has most of.
     */
    private static boolean hazardBeyond(float[] o, int obs, double targetX, double targetEye, double targetZ,
            double dx, double dz) {

        // Which row of the grid the target's feet are in, taken as a body of the agent's own height, which everything in
        // the league is within a block of. The row below is checked as well, so half a block either way changes nothing.
        int feet = Mth.clamp((int) Math.floor(targetEye - EYE_HEIGHT), 1, ObservationSchema.TERRAIN_Y - 1);

        for (double t = HAZARD_STEP; t <= HAZARD_PUSH; t += HAZARD_STEP) {

            int x = (int) Math.floor(targetX + dx * t);
            int z = (int) Math.floor(targetZ + dz * t);

            if (x < 0 || z < 0 || x >= X || z >= Z) {

                return false;
            }

            if (cell(o, obs, x, feet, z) >= AgentObservation.HAZARD) {

                return true;
            }

            if (cell(o, obs, x, feet, z) >= AgentObservation.SOLID) {

                // A wall between the target and the hazard: the push stops there, whatever is behind it.
                return false;
            }

            if (cell(o, obs, x, feet - 1, z) >= AgentObservation.HAZARD) {

                return true;
            }

            if (nothingUnder(o, obs, x, feet, z)) {

                return true;
            }
        }

        return false;
    }

    /** Whether anything in the grid at all hurts a body, which is the one thing worth searching the ground for a line on. */
    private static boolean anyHazard(float[] o, int obs) {

        for (int cell = 0; cell < ObservationSchema.TERRAIN_SIZE; cell++) {

            if (o[obs + ObservationSchema.TERRAIN_OFFSET + cell] >= AgentObservation.HAZARD) {

                return true;
            }
        }

        return false;
    }

    /**
     * Whether a column is open all the way from under the target's feet to the bottom of the grid, with that bottom cell
     * marked a hazard. The bottom row is two blocks under the agent's own feet and reads as a hazard when what is below it
     * is a fall of more than eight blocks or something that kills: that is the one thing in the grid that says a ravine.
     */
    private static boolean nothingUnder(float[] o, int obs, int x, int feet, int z) {

        for (int y = feet - 1; y > 0; y--) {

            if (cell(o, obs, x, y, z) >= AgentObservation.SOLID) {

                return false;
            }
        }

        return cell(o, obs, x, 0, z) >= AgentObservation.HAZARD;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Getting out of something that hurts
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Whether the agent is in, or standing on, something that is hurting it. The grid marks those cells, and the planner
     * never walks onto one, but a blow knocks a body about a block and the ground is not asked first.
     *
     * <p>Three of them are traps rather than wounds: powder snow, a cobweb and a sweet berry bush all hold a body where it
     * is, and powder snow is the one that kills. A body in it cannot jump out and freezes where it stands, which is what
     * every one of these deaths was: over 4,000 fights on the terrain library the teacher lost 15 fights to something other
     * than the vindicator, and 14 of them were freezing.
     */
    private static boolean inHazard(float[] o, int obs) {

        return cell(o, obs, CENTRE, FEET, CENTRE) >= AgentObservation.HAZARD
                || cell(o, obs, CENTRE, FEET + 1, CENTRE) >= AgentObservation.HAZARD
                || cell(o, obs, CENTRE, FEET - 1, CENTRE) >= AgentObservation.HAZARD;
    }

    /**
     * Leaves it, by the shortest way out rather than the way the fight is. Walking is tried first and is nearly always
     * enough: powder snow only takes a tenth off a body's speed sideways, however firmly it holds it down. Where walking
     * gets nowhere, or there is nowhere within the grid to walk to, it breaks its way out instead, which it can do under a
     * player's rules and which these three blocks are cheap to do it to: powder snow gives way in eight ticks, a cobweb in
     * eight to a sword, a berry bush at a touch.
     */
    private void getOut(float[] o, float[] a, int obs, int act, float sin, float cos, boolean grounded) {

        int start = state(CENTRE, 0, CENTRE);
        int out = this.escape(o, obs);

        if (out != start) {

            this.walkTowards(out, a, act, sin, cos, grounded);

            // A jump is no help: a body in powder snow cannot leave the ground, and a web or a bush is not something to
            // jump over. So the only answer to a step that did not happen is to take the block away.
            if (!stuck(o, obs)) {

                return;
            }
        }

        this.digOut(o, a, obs, act);
    }

    /**
     * The nearest spot it can stand on that is not a hazard, as the first step towards it, or where it stands when the grid
     * holds none.
     *
     * <p>The ordinary search is already exactly this: every spot it visits has room for a body with ground under it and
     * nothing in it that hurts, and it visits them nearest first. The one thing that changes when the agent is standing in
     * a hazard is what it is looking for, which is the first of them rather than one in reach of the target.
     */
    private int escape(float[] o, int obs) {

        int reached = this.search(o, obs, false);
        int start = state(CENTRE, 0, CENTRE);

        for (int i = 0; i < reached; i++) {

            int s = this.queue[i];

            if (s != start && !this.dropped[s]) {

                return this.firstStep(s);
            }
        }

        return start;
    }

    /**
     * Breaks the block it is stuck in, by looking down its own column and holding the attack, the way a player breaks
     * anything, with whatever it swings with in hand, since a sword goes through a cobweb fifteen times faster than a fist.
     *
     * <p>Straight down is the whole of the aim, and it needs no choosing. Looking down its own column, the first thing a
     * ray from the eyes meets is whatever the agent is standing in or on, since everything above that in the column is what
     * the body itself occupies: the snow round its legs, the web round its chest, the magma under its feet. There is
     * nothing else down there to hit by mistake.
     */
    private void digOut(float[] o, float[] a, int obs, int act) {

        int self = obs + ObservationSchema.SELF_OFFSET;

        a[act + ActionSchema.SELECTED_SLOT] = Math.max(0, meleeSlot(o, obs));

        float pitch = o[self + ObservationSchema.SELF_PITCH] * 90.0F;
        a[act + ActionSchema.AIM_PITCH] = Mth.clamp((90.0F - pitch) / MobControls.MAX_AIM_PITCH_PER_TICK, -1.0F, 1.0F);

        // Only once it is looking down. A swing that meets nothing at all costs the whole attack cooldown, and sixty degrees
        // of pitch a tick puts the aim there inside two.
        if (pitch > 90.0F - SWING_CONE_DEGREES) {

            a[act + ActionSchema.ATTACK] = 1.0F;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // What it is carrying
    // ---------------------------------------------------------------------------------------------------------------

    /** The first hotbar slot holding something of this kind, or -1. */
    private static int slotHolding(float[] o, int obs, float kind) {

        for (int slot = 0; slot < ObservationSchema.HOTBAR_SIZE; slot++) {

            if (AgentObservation.isItem(o[obs + ObservationSchema.HOTBAR_OFFSET + slot], kind)) {

                return slot;
            }
        }

        return -1;
    }

    /** What it swings with: a sword if it has one, an axe if it has not, and -1 for empty hands. */
    private static int meleeSlot(float[] o, int obs) {

        int sword = slotHolding(o, obs, AgentObservation.ITEM_SWORD);
        return sword >= 0 ? sword : slotHolding(o, obs, AgentObservation.ITEM_AXE);
    }

    /** Whether whatever is in this enemy slot fights at a distance, which is worth a raised shield on its own. */
    private static boolean shootsBack(float[] o, int target) {

        return AgentObservation.isItem(o[target + ObservationSchema.ENEMY_MAIN_HAND], AgentObservation.ITEM_RANGED)
                || AgentObservation.isItem(o[target + ObservationSchema.ENEMY_OFF_HAND], AgentObservation.ITEM_RANGED);
    }

    /** Whether it carries nothing at all, which in the league means it fights by touching, charging or exploding. */
    private static boolean emptyHanded(float[] o, int target) {

        return AgentObservation.isItem(o[target + ObservationSchema.ENEMY_MAIN_HAND], AgentObservation.ITEM_NONE)
                && AgentObservation.isItem(o[target + ObservationSchema.ENEMY_OFF_HAND], AgentObservation.ITEM_NONE);
    }

    /**
     * What the fighter has to remember about the thing in front of it: whether it has ever swung, whether it swung from
     * further off than anything man sized reaches, and how long it has gone without coming any closer.
     *
     * <p>Velocities arrive in the agent's own frame, and the agent is looking at what it is fighting, so the forward
     * component is how fast the thing is going the way the agent faces: negative is coming at it, positive is going away.
     * Knocked back by a blow, a mob slides away for a dozen ticks, and that counts as not coming rather than as movement.
     */
    private static void watch(Fighter me, int slot, float[] o, int target) {

        if (o[target + ObservationSchema.ENEMY_SWINGING] > 0.5F) {

            me.swung |= 1 << slot;

            if (o[target + ObservationSchema.ENEMY_DISTANCE] * ObservationSchema.VIEW_DISTANCE > LONG_REACH_DISTANCE) {

                me.longReach |= 1 << slot;
            }
        }

        boolean coming = o[target + ObservationSchema.ENEMY_VELOCITY_FORWARD] < -STILL_SPEED;

        me.holdingBack[slot] = coming ? (byte) 0 : (byte) Math.min(Byte.MAX_VALUE, me.holdingBack[slot] + 1);
    }

    /**
     * Whether to shoot rather than close in. Something to shoot with and a line to shoot along are the whole of it,
     * beyond a sword doing more inside its own reach than a bow needing twenty ticks: a fighter with nothing to swing
     * shoots at any distance, since punching with a bow is worth one damage against an arrow's six.
     */
    private static boolean shoots(Fighter me, int ranged, int melee, float distance, boolean clear) {

        if (ranged < 0) {

            return false;
        }

        // A draw or a load already in hand is finished and fired, even as the target closes, as long as it cannot be put
        // to better use: an arrow nearly drawn is most of a sword's blow away.
        boolean holding = me.draw != DRAW_IDLE;

        if (melee < 0) {

            return clear || holding;
        }

        if (holding) {

            return distance > ABANDON_DRAW_RANGE;
        }

        return clear && distance > SHOOT_RANGE;
    }

    /**
     * Whether to walk away from something about to explode. Only a creeper in the league fights with empty hands and
     * never swings, and only a creeper stands still once it is next to its target: everything else that comes that close
     * is swinging, hopping or charging. Once it starts walking away it keeps going until it is well clear, since the fuse
     * only winds back down past seven blocks.
     */
    private boolean flees(Fighter me, int slot, float[] o, int target, float distance) {

        if (me.fleeing) {

            me.fleeing = distance < FUSE_SAFE_RANGE;
            return me.fleeing;
        }

        me.fleeing = distance < FUSE_RANGE && emptyHanded(o, target) && !me.hasSwung(slot)
                && me.holdingBack[slot] >= FUSE_STILL_TICKS;

        return me.fleeing;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Working a drawn weapon
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Throws the aim where the shot has to go and then works the weapon: how far above the target the arc has to start,
     * and how far ahead of the target the arc has to land.
     *
     * <p>Twice over, because the two answers depend on each other: where the target will be when a shot at where it is
     * now would arrive, and then where it will be when a shot at that would.
     */
    private void shoot(Fighter me, float[] o, float[] a, int obs, int act, int ranged, int target, float forward,
            float right, float up) {

        int self = obs + ObservationSchema.SELF_OFFSET;
        double view = ObservationSchema.VIEW_DISTANCE;
        double speed = me.weapon == WEAPON_CROSSBOW ? BOLT_SPEED : ARROW_SPEED;

        double aheadOf = forward * view;
        double rightOf = right * view;
        double flight = Math.hypot(aheadOf, rightOf) / speed;
        double elevation = 0.0D;

        for (int pass = 0; pass < 2; pass++) {

            aheadOf = forward * view + o[target + ObservationSchema.ENEMY_VELOCITY_FORWARD] * VELOCITY_SCALE * flight;
            rightOf = right * view + o[target + ObservationSchema.ENEMY_VELOCITY_RIGHT] * VELOCITY_SCALE * flight;

            double aboveBy = up * view + ARROW_DROP_AT_LAUNCH
                    + o[target + ObservationSchema.ENEMY_VELOCITY_UP] * VELOCITY_SCALE * flight;

            double across = Math.hypot(aheadOf, rightOf);

            elevation = elevationFor(across, aboveBy, speed);
            flight = flightTicks(elevation, across, speed);
        }

        float shotYaw = (float) Math.toDegrees(Mth.atan2(rightOf, aheadOf));
        float shotPitch = (float) -Math.toDegrees(elevation) - o[self + ObservationSchema.SELF_PITCH] * 90.0F;

        a[act + ActionSchema.AIM_YAW] = Mth.clamp(shotYaw / MobControls.MAX_AIM_YAW_PER_TICK, -1.0F, 1.0F);
        a[act + ActionSchema.AIM_PITCH] = Mth.clamp(shotPitch / MobControls.MAX_AIM_PITCH_PER_TICK, -1.0F, 1.0F);

        this.work(me, o, a, obs, act, ranged,
                Math.abs(shotYaw) < FIRE_CONE_DEGREES && Math.abs(shotPitch) < FIRE_CONE_DEGREES);
    }

    /**
     * Holds the drawn weapon, and lets it go when the shot is on.
     *
     * <p>Nothing here counts ticks of its own. How far a draw has come is in the echo, written by the body from the item's
     * own reckoning, and whether the body is using anything at all is in the self block, so the state below is only ever
     * what the observation cannot say: which of the two weapons this turned out to be, and whether a bolt is held ready.
     *
     * <p>That matters most when this brain is not the one driving. As a teacher it labels a student's fight, and a press it
     * asks for there never happens: a state machine that assumed its own presses had gone out would conclude, on the first
     * tick of the first fight, that the quiver was empty, and would then never show the student a bow again. So a press is
     * only ever concluded from once it is known to have landed, and the cooldown says that: any press with the cooldown
     * clear sets it to full, so a cooldown that did not move says nobody pressed anything.
     */
    private void work(Fighter me, float[] o, float[] a, int obs, int act, int ranged, boolean aimed) {

        int self = obs + ObservationSchema.SELF_OFFSET;
        boolean using = o[self + ObservationSchema.SELF_USING] > 0.5F;
        boolean cooling = o[self + ObservationSchema.SELF_USE_COOLDOWN] > 0.0F;
        float charge = o[obs + ObservationSchema.ECHO_OFFSET + ECHO_USE_PROGRESS];

        // A shield still up from a moment ago has the hands, and both hands share one use cooldown: a press on the main
        // hand while the off hand is in use does not resolve at all. Nothing is pressed until it has come down, which it
        // does on this very tick, since nothing here asks for it.
        boolean offHandBusy = o[self + ObservationSchema.SELF_USING_OFFHAND] > 0.5F;

        boolean answered = me.asked && cooling;
        boolean ignored = me.asked && !cooling;
        me.asked = false;

        a[act + ActionSchema.SELECTED_SLOT] = ranged;

        if (me.draw == DRAW_FIRING) {

            // The press before this one either sent a bolt, which leaves the hands free, or started a bow drawing. That
            // is the whole difference between the two weapons, and it only has to be seen once.
            if (using) {

                me.weapon = WEAPON_BOW;
                me.draw = DRAW_DRAWING;
            }

            else if (ignored) {

                // Nobody pressed anything, so whatever was loaded is loaded still.
                me.draw = DRAW_LOADED;
            }

            else {

                me.weapon = me.weapon == WEAPON_UNKNOWN ? WEAPON_CROSSBOW : me.weapon;
                me.draw = DRAW_IDLE;
                return;
            }
        }

        if (me.draw == DRAW_DRAWING) {

            if (using) {

                // A bow at full draw stays there until the shot is on: holding costs nothing and loses no power, and a
                // release decided by the aim is a release a network can learn from what it sees. A crossbow's wind is let
                // go the moment it is in, or the body reaches the end of the use and the wind is wasted.
                boolean release = charge >= FULL_CHARGE && (me.weapon != WEAPON_BOW || aimed);

                if (!release) {

                    a[act + ActionSchema.USE] = 1.0F;
                    return;
                }

                // Letting go fires a bow and loads a crossbow. Which of the two it was is settled by the next press.
                me.draw = me.weapon == WEAPON_BOW ? DRAW_IDLE : DRAW_LOADED;
                return;
            }

            // Asked for a draw and nothing is drawing. A press that resolved and left the hands with nothing charged is a
            // weapon with nothing to fire: a bow with an empty quiver does not so much as come up. A press that never went
            // out says nothing at all, and is simply asked again.
            me.spent = me.spent || answered && charge <= 0.0F && !offHandBusy;
            me.draw = DRAW_IDLE;
        }

        if (me.draw == DRAW_LOADED) {

            if (!aimed || cooling || offHandBusy) {

                return;
            }

            a[act + ActionSchema.USE] = 1.0F;
            me.asked = true;
            me.draw = DRAW_FIRING;
            return;
        }

        if (me.draw == DRAW_IDLE && !cooling && !offHandBusy) {

            a[act + ActionSchema.USE] = 1.0F;
            me.asked = true;
            me.draw = DRAW_DRAWING;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The shield
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Holds the shield up while there is nothing better to do with the hands, and asks the off hand the first time
     * whether there is a shield in it at all.
     *
     * <p>It goes up against anything inside reach while the swing is still cooling, but only where the fight gives a
     * reason for it: something with a bow in its hands that has got this close, something whose reach is longer than a
     * man's, which in the league means a ravager, or a fight that has already cost health. A shield takes the movement
     * keys down to a fifth, and footwork is what wins the fights the fighter already wins untouched, so it stays out of
     * those. Where it does go up it pays twice over: a ravager that is blocked is stunned for two seconds, and it carries
     * no axe to knock the shield aside with.
     *
     * <p>It also goes up for a shot already in the air, whatever the distance to whoever fired it, and that one is not a
     * guess: only something actually on its way to the agent holds a slot. It stays down while the swing is ready and the
     * target is in reach, since a blow landed is worth more than an arrow stopped, and it comes back up straight after.
     *
     * @return whether the hands are busy, and so whether the swing has to wait
     */
    private boolean hold(Fighter me, float[] o, float[] a, int obs, int act, int slot, int target, float distance,
            float strength, float incoming, boolean fleeing) {

        int self = obs + ObservationSchema.SELF_OFFSET;
        boolean up = o[self + ObservationSchema.SELF_USING_OFFHAND] > 0.5F;

        if (me.shieldWait > 0) {

            me.shieldWait--;
        }

        // A press with the cooldown clear sets it to full, so a cooldown that moved is the proof the press went out. Without
        // that proof nothing is concluded: while this brain is only labelling somebody else's fight its presses never
        // happen, and a shield written off on the first tick of a fight is a shield the student is never shown.
        if (me.askedShield && !up && o[self + ObservationSchema.SELF_USE_COOLDOWN] > 0.0F) {

            // The press resolved and the off hand stayed down. Either there is nothing in it, or an axe has just knocked
            // aside the shield that was, and no shield comes up again for five seconds.
            if (me.shieldSeen) {

                me.shieldWait = AgentMob.SHIELD_DISABLED_TICKS;
            }

            else {

                me.shield = SHIELD_NONE;
            }
        }

        me.askedShield = false;
        me.shieldSeen |= up;

        if (up) {

            me.shield = SHIELD_CARRIED;
        }

        // Health short of full is the whole of "this fight is going badly": it says the opponent can reach the agent, and
        // it is the one thing about a fight the observation says outright.
        boolean hurt = o[self + ObservationSchema.SELF_HEALTH] < 1.0F;
        boolean worthIt = hurt || me.reachesFar(slot) || shootsBack(o, target);

        // A blow is only worth waiting for while the swing is still cooling; a shot is worth stopping whenever nothing can
        // be hit anyway, which out past a swing's reach is always.
        boolean againstBlows = distance <= BLOCK_RANGE && worthIt && strength < FULL_STRENGTH;
        boolean againstShots = incoming <= BLOCK_SHOT_RANGE && distance > SWING_RANGE;

        boolean wanted = !fleeing && me.shield != SHIELD_NONE && me.shieldWait <= 0 && (againstBlows || againstShots);

        if (!wanted) {

            // Letting go on the tick the swing is ready costs that tick's attack, which the body swallows whatever is
            // pressed, so the swing lands on the next one.
            return up;
        }

        a[act + ActionSchema.USE_OFFHAND] = 1.0F;

        if (!up && o[self + ObservationSchema.SELF_USE_COOLDOWN] <= 0.0F) {

            // A press with the cooldown clear resolves this tick, so the answer arrives on the next one.
            me.askedShield = true;
        }

        return true;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Where a thrown arrow lands
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * The angle above the horizon to loose at, for a target so far away across and so far up, by halving the arc between
     * the flattest and the steepest shot until the two agree. Bisection rather than a formula because the arrow is not a
     * parabola: it loses a hundredth of its speed every tick as well as falling, and over twenty ticks that is a quarter
     * of its range.
     */
    private static double elevationFor(double across, double rise, double speed) {

        if (across < 1.0E-4D) {

            return rise > 0.0D ? MAX_ELEVATION : -MAX_ELEVATION;
        }

        double low = -MAX_ELEVATION;
        double high = MAX_ELEVATION;

        for (int halving = 0; halving < 20; halving++) {

            double middle = 0.5D * (low + high);

            if (heightAfter(middle, across, speed) < rise) {

                low = middle;
            }

            else {

                high = middle;
            }
        }

        return 0.5D * (low + high);
    }

    /**
     * How high an arrow loosed at this angle is by the time it has covered so much ground, walked a tick at a time the
     * way the arrow itself is: it moves at the speed it has, and only then loses a hundredth of it and a twentieth of a
     * block of height.
     */
    private static double heightAfter(double elevation, double across, double speed) {

        double horizontal = speed * Math.cos(elevation);
        double vertical = speed * Math.sin(elevation);
        double covered = 0.0D;
        double height = 0.0D;

        for (int tick = 0; tick < MAX_FLIGHT_TICKS; tick++) {

            double next = covered + horizontal;

            if (next >= across) {

                double part = (across - covered) / Math.max(1.0E-9D, horizontal);
                return height + vertical * part;
            }

            covered = next;
            height += vertical;

            horizontal *= ARROW_DRAG;
            vertical = vertical * ARROW_DRAG - ARROW_GRAVITY;
        }

        return height;
    }

    /** How long that shot is in the air before it gets there, which is how far ahead of the target to aim. */
    private static double flightTicks(double elevation, double across, double speed) {

        double horizontal = speed * Math.cos(elevation);
        double covered = 0.0D;

        for (int tick = 0; tick < MAX_FLIGHT_TICKS; tick++) {

            double next = covered + horizontal;

            if (next >= across) {

                return tick + (across - covered) / Math.max(1.0E-9D, horizontal);
            }

            covered = next;
            horizontal *= ARROW_DRAG;
        }

        return MAX_FLIGHT_TICKS;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Where to go
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * The first step towards the nearest spot it can reach that is in the band with a clear line to the target, or,
     * where there is none in the grid, towards the reachable spot closest to the target without going inside the band.
     */
    private int approach(float[] o, int obs, double targetX, double targetEye, double targetZ, boolean notHere,
            boolean targetStuck) {

        // A target well below can be dropped down to, as long as the fall does little more harm than a missed swing. One
        // stuck at the bottom of a pit, that cannot come up and will not be reached any other way, is worth a deeper fall:
        // down to where what the fall takes still leaves more than one blow from a vindicator can.
        double below = FEET + EYE_HEIGHT - targetEye;
        double deepest = targetStuck ? MAX_DROP_TO_STUCK : MAX_DROP;
        int reached = this.search(o, obs, below >= MIN_DROP && below <= deepest);

        int best = -1;
        int bestDepth = Integer.MAX_VALUE;
        int closest = -1;
        double closestDistance = Double.MAX_VALUE;

        for (int i = 0; i < reached; i++) {

            int s = this.queue[i];

            // Where it stands has just been shown not to work, whatever the grid makes of it.
            if (notHere && i == 0) {

                continue;
            }

            int x = s % X;
            int level = s / (X * Z) - 1;
            int z = (s / X) % Z;

            // Eye to eye, the way a swing reaches, and not across the ground: a target three blocks down the slope
            // beneath its feet is not in reach however close it stands. Where a drop lands is not known, only that it is
            // somewhere near the target's own level, so there the ground distance is all there is to go on.
            double across = Math.hypot(targetX - (x + 0.5D), targetZ - (z + 0.5D));
            double reach = this.dropped[s] ? across : Math.hypot(across, targetEye - (FEET + level + EYE_HEIGHT));

            if (!this.dropped[s] && reach >= BACK_OFF_RANGE && reach <= SWING_RANGE - 0.1D && this.depth[s] < bestDepth
                    && !blocked(o, obs, x, level, z, targetX, targetEye, targetZ)) {

                best = s;
                bestDepth = this.depth[s];
            }

            double shortfall = Math.max(reach, BACK_OFF_RANGE) + 0.05D * this.depth[s];

            if (shortfall < closestDistance) {

                closest = s;
                closestDistance = shortfall;
            }
        }

        return this.firstStep(best >= 0 ? best : closest);
    }

    /** The neighbouring spot, one step away, that puts the most ground between it and the target. */
    private int retreat(float[] o, int obs, double targetX, double targetZ) {

        int reached = this.search(o, obs, false);
        int start = state(CENTRE, 0, CENTRE);

        int best = start;
        double bestDistance = Math.hypot(targetX - (CENTRE + 0.5D), targetZ - (CENTRE + 0.5D));

        for (int i = 0; i < reached; i++) {

            int s = this.queue[i];

            if (this.depth[s] != 1) {

                continue;
            }

            double away = Math.hypot(targetX - (s % X + 0.5D), targetZ - ((s / X) % Z + 0.5D));

            if (away > bestDistance) {

                best = s;
                bestDistance = away;
            }
        }

        return best;
    }

    /**
     * Every spot it can walk to from where it stands, breadth first, leaving each one's distance in steps and the spot it
     * was reached from. Returns how many were reached; they are the first entries of the queue, nearest first.
     *
     * <p>Allowed to, it also steps off edges whose ground is too far down for the grid to see. Where it lands is unknown,
     * so such a spot is marked as a drop and nothing is searched beyond it.
     */
    private int search(float[] o, int obs, boolean mayDrop) {

        Arrays.fill(this.depth, -1);
        Arrays.fill(this.dropped, false);

        int start = state(CENTRE, 0, CENTRE);
        this.depth[start] = 0;
        this.parent[start] = start;
        this.queue[0] = start;

        int head = 0;
        int tail = 1;

        while (head < tail) {

            int s = this.queue[head++];

            if (this.dropped[s]) {

                continue;
            }

            int x = s % X;
            int z = (s / X) % Z;
            int level = s / (X * Z) - 1;

            for (int d = 0; d < STEP_X.length; d++) {

                int nx = x + STEP_X[d];
                int nz = z + STEP_Z[d];

                if (nx < 0 || nz < 0 || nx >= X || nz >= Z) {

                    continue;
                }

                // Across a diagonal both sides have to be open, or the body, wider than a gap between two corners,
                // snags on them.
                if (d >= 4 && (!open(o, obs, x + STEP_X[d], level, z) || !open(o, obs, x, level, z + STEP_Z[d]))) {

                    continue;
                }

                boolean found = false;

                for (int to = level - 1; to <= level + 1 && !found; to++) {

                    if (to < -1 || to > 1 || !standable(o, obs, nx, to, nz)) {

                        continue;
                    }

                    // Stepping up needs the room to jump from where it stands.
                    if (to > level && !open(o, obs, x, level + 1, z)) {

                        continue;
                    }

                    found = true;
                    tail = this.visit(state(nx, to, nz), s, tail, false);
                }

                // Open all the way down past the bottom of the grid: a drop of three blocks or more.
                if (!found && mayDrop && level == 0 && open(o, obs, nx, 0, nz)
                        && passable(cell(o, obs, nx, FEET - 1, nz))
                        && passable(cell(o, obs, nx, FEET - 2, nz))) {

                    tail = this.visit(state(nx, -1, nz), s, tail, true);
                }
            }
        }

        return tail;
    }

    private int visit(int n, int from, int tail, boolean drop) {

        if (this.depth[n] >= 0) {

            return tail;
        }

        this.depth[n] = this.depth[from] + 1;
        this.parent[n] = from;
        this.dropped[n] = drop;
        this.queue[tail] = n;

        return tail + 1;
    }

    /** Walks the search back from a spot to the one it is first reached through from where the agent stands. */
    private int firstStep(int s) {

        int start = state(CENTRE, 0, CENTRE);

        if (s < 0) {

            return start;
        }

        while (this.parent[s] != start && this.parent[s] != s) {

            s = this.parent[s];
        }

        return s;
    }

    /** Presses whatever moves it from the middle of its own column towards the middle of the next one. */
    private void walkTowards(int next, float[] a, int act, float sin, float cos, boolean grounded) {

        double dx = next % X - CENTRE;
        double dz = (next / X) % Z - CENTRE;

        // Nowhere to go: a retreat with its back to a wall answers with the spot it already stands on, and dividing by
        // the larger of two zeroes would hand the body a pair of not-a-numbers to walk along.
        if (dx == 0.0D && dz == 0.0D) {

            return;
        }

        // Into the agent's own frame: forward along its look, and strafing positive to the left, which is minus right.
        double ahead = dx * -sin + dz * cos;
        double rightward = dx * -cos + dz * -sin;
        double scale = Math.max(Math.abs(ahead), Math.abs(rightward));

        a[act + ActionSchema.MOVE_FORWARD] = (float) (ahead / scale);
        a[act + ActionSchema.MOVE_STRAFE] = (float) (-rightward / scale);

        if (grounded && next / (X * Z) - 1 > 0) {

            a[act + ActionSchema.JUMP] = 1.0F;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Reading the grid
    // ---------------------------------------------------------------------------------------------------------------

    private static int state(int x, int level, int z) {

        return ((level + 1) * Z + z) * X + x;
    }

    private static float cell(float[] o, int obs, int x, int y, int z) {

        return y < 0 || y >= ObservationSchema.TERRAIN_Y ? AgentObservation.SOLID : o[obs + ObservationSchema.terrainOffset(x, y, z)];
    }

    /** Air or water: somewhere a body can be. Neither solid nor a hazard, which reads above solid. */
    private static boolean passable(float cell) {

        return cell >= AgentObservation.EMPTY && cell < AgentObservation.SOLID;
    }

    /** Room for a body standing at this level: nothing solid, and nothing that hurts, where its legs and head would be. */
    private static boolean open(float[] o, int obs, int x, int level, int z) {

        return passable(cell(o, obs, x, FEET + level, z)) && passable(cell(o, obs, x, FEET + level + 1, z));
    }

    /**
     * Somewhere to stand: room for a body, and ground under it or water to float in. A hazard is never ground: the top of
     * a lava lake, a magma block or a cactus reads as a hazard, not as solid, which is what keeps the planner off them.
     */
    private static boolean standable(float[] o, int obs, int x, int level, int z) {

        float under = cell(o, obs, x, FEET + level - 1, z);
        float at = cell(o, obs, x, FEET + level, z);

        boolean ground = under >= AgentObservation.SOLID && under < AgentObservation.HAZARD;

        return open(o, obs, x, level, z) && (ground || at >= AgentObservation.FLUID);
    }

    /**
     * Whether a block stands on the straight line from the eyes of a body standing at a spot to the target's eyes, walked
     * through the grid a quarter block at a time. Positions are in grid units, with the agent's own column at the middle.
     */
    private static boolean blocked(float[] o, int obs, int fromX, int level, int fromZ, double toX, double toEye, double toZ) {

        double x0 = fromX + 0.5D;
        double y0 = FEET + level + EYE_HEIGHT;
        double z0 = fromZ + 0.5D;

        double dx = toX - x0;
        double dy = toEye - y0;
        double dz = toZ - z0;

        int steps = (int) Math.ceil(Math.sqrt(dx * dx + dy * dy + dz * dz) / 0.25D);

        for (int i = 1; i < steps; i++) {

            double t = i / (double) steps;
            int x = (int) Math.floor(x0 + dx * t);
            int y = (int) Math.floor(y0 + dy * t);
            int z = (int) Math.floor(z0 + dz * t);

            if (x < 0 || y < 0 || z < 0 || x >= X || y >= ObservationSchema.TERRAIN_Y || z >= Z) {

                continue;
            }

            if (o[obs + ObservationSchema.terrainOffset(x, y, z)] >= AgentObservation.SOLID) {

                return true;
            }
        }

        return false;
    }

    /** It pressed to move on the tick before, and hardly moved. */
    private static boolean stuck(float[] o, int obs) {

        int echo = obs + ObservationSchema.ECHO_OFFSET;
        int self = obs + ObservationSchema.SELF_OFFSET;

        boolean pressed = Math.abs(o[echo]) > 0.3F || Math.abs(o[echo + 1]) > 0.3F;
        float speed = Math.abs(o[self + ObservationSchema.SELF_VELOCITY_FORWARD]) + Math.abs(o[self + ObservationSchema.SELF_VELOCITY_RIGHT]);

        return pressed && speed < 0.02F;
    }

    /**
     * The closest body in an enemy slot, or -1 when nothing is in view. A slot can also hold something shot at the agent,
     * and an arrow a block away is nearer than anything that fired it: what to fight is only ever a body.
     */
    private static int nearestEnemy(float[] o, int obs) {

        int best = -1;
        float bestDistance = Float.MAX_VALUE;

        for (int slot = 0; slot < ObservationSchema.ENEMY_SLOTS; slot++) {

            int at = obs + ObservationSchema.enemyOffset(slot);

            if (o[at + ObservationSchema.ENEMY_PRESENT] < 0.5F
                    || AgentObservation.isProjectileKind(o[at + ObservationSchema.ENEMY_KIND])) {

                continue;
            }

            float distance = o[at + ObservationSchema.ENEMY_DISTANCE];

            if (distance < bestDistance) {

                bestDistance = distance;
                best = slot;
            }
        }

        return best;
    }

    /**
     * How far off the nearest thing shot at the agent is, in blocks, or a number past anything in view when nothing is
     * coming. Only what is on its way is ever in a slot, so the distance is the whole question: an arrow flies three
     * blocks a tick, and a shield that goes up while one is still twenty blocks out is a shield held up for nothing.
     */
    private static float incomingDistance(float[] o, int obs) {

        float best = (float) ObservationSchema.VIEW_DISTANCE * 2.0F;

        for (int slot = 0; slot < ObservationSchema.ENEMY_SLOTS; slot++) {

            int at = obs + ObservationSchema.enemyOffset(slot);

            if (o[at + ObservationSchema.ENEMY_PRESENT] < 0.5F
                    || !AgentObservation.isProjectileKind(o[at + ObservationSchema.ENEMY_KIND])) {

                continue;
            }

            best = Math.min(best, o[at + ObservationSchema.ENEMY_DISTANCE] * (float) ObservationSchema.VIEW_DISTANCE);
        }

        return best;
    }
}
