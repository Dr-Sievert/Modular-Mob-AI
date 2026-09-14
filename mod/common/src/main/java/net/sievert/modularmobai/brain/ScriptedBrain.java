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
 * <h2>Against a pack</h2>
 *
 * <p>Everything above is one fight against one body, and against several it was the whole of what this fighter did: it took
 * the nearest, stood in the band, and traded, which is how a good player dies. So there is a second fight in here, and it is
 * reached only when <b>two or more bodies are actually in the fight</b> and near enough to be in it, which a fight against one
 * opponent never is. It is six rules and they are all the same rule:
 *
 * <ul>
 *   <li><b>Give ground instead of trading, in a cycle.</b> A body walking backwards covers 0.216 blocks a tick and a zombie
 *       covers 0.154, so backing away keeps a pack in front of the agent and strings it out into the one-on-one fight it
 *       already wins. It backs away from the pack as <i>one direction</i> — the sum of the ways to each of them, weighted by
 *       how near each is — rather than from any one of them. But only <b>while the swing is cooling</b>: a fighter that backs
 *       away for as long as there is a pack outpaces it, never lets anything arrive and never lands anything, which is
 *       measured and written down at {@link #PACK_STANDOFF}. So it steps back in a few ticks before the cooldown fills, since
 *       the ground given up has to be covered before a blow can land, and gives it again once the blow has gone. See
 *       {@link #sizeUp}, {@link #giveGround} and {@link #stepsIn}.</li>
 *   <li><b>Kite the pack it can outwalk, and only that one.</b> The cycle above is what a fighter does against bodies it
 *       cannot simply outpace. Against ones it can it should never step in at all: a body walking backwards covers 0.216
 *       blocks a tick and a zombie covers 0.154, a sword reaches three blocks and a zombie's blow about two, so a fighter
 *       that holds the band between those two is striking from ground the zombie cannot answer from. That is one number off
 *       the slots, the fastest {@code ENEMY_SPEED} in the fight, and where it says so the fighter gives ground the moment
 *       one comes inside {@link #KITE_EDGE} whether or not the swing is ready, holds still while the swing is ready and lets
 *       the body walk into reach, and does not close. Only while they are coming, which {@link #KITE_PATIENCE} bounds: the
 *       same retreat held against a pack that has stopped arriving is the one measured at {@link #PACK_STANDOFF} and thrown
 *       out. Against a vindicator's 0.24 none of it is reached.</li>
 *   <li><b>The feet answer to the pack even while the hands are drawing.</b> A bow or a crossbow takes the movement keys
 *       down to a fifth for twenty ticks, and this fighter used to pick its weapon before it had counted the fight at all:
 *       a drawn weapon in a pack was a fighter standing still in the middle of four bodies, landing nothing. The pack is
 *       sized up before the weapon is chosen now, a draw in a pack is begun only where {@link #finishesInTime} says it will
 *       be full before the nearest arrives, and the step away while drawing is {@link #giveGround} and not
 *       {@link #retreat}.</li>
 *   <li><b>Keep them in front.</b> The agent perceives through a hundred degree cone about its aim, so a body it turns its
 *       back on is one it stops seeing. The aim goes on the middle of the pack for as long as the feet are giving ground, and
 *       on the body only while the fighter is stepping in to strike, since a swing goes where the eyes are: the eyes and the
 *       feet say the same thing on every tick.</li>
 *   <li><b>Run when it is hopeless.</b> Not as despair but as arithmetic: what the pack takes off it in the time it would
 *       take to cut the pack down, against the health it has left. See {@link #hopeless}. It then sprints along the clearest
 *       way out rather than dying in the middle, and the pack strings out behind it, which is the same string-out the
 *       backing away buys, bought late.</li>
 *   <li><b>And the shield needed nothing.</b> The rule that already raises it — inside reach, while the swing cools, in a
 *       fight that has cost health — is the pack rule as well, and narrowing it there for the sake of the footwork is worse.
 *       See {@link #hold}, where the numbers are.</li>
 * </ul>
 *
 * <p>Two fights are deliberately left out of it. Nothing that shoots and nothing that flies counts towards a pack at all:
 * walking backwards from an archer is walking backwards while being shot, and nothing swung reaches a flyer, so those are
 * the ranged rules' fights and they are exactly what they were. And a lit fuse still comes first, since a creeper is the one
 * body worth walking away from whatever else is standing there.
 *
 * <h2>Everything else it carries</h2>
 *
 * <p>A drawn weapon and a shield each need something the observation does not carry: whether the weapon drawing is a bow or
 * a crossbow, and whether there is a shield in the off hand at all. So this brain keeps a little state per agent, keyed by
 * the agent id the step carries, and works the rest out from what the body reports back:
 *
 * <ul>
 *   <li><b>Bow or crossbow.</b> Both read as one item category, because the layout has one for a drawn weapon, and how far
 *       either has charged is in the echo, so neither has to be named to be used: hold until it reads charged, then let
 *       go. A draw is only begun at something that cannot be here before it is full, which the slot's own speed says; see
 *       {@link #finishesInTime}. What is left over is told apart by what a deliberate release does. A wound crossbow loads and fires nothing,
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
 * <p>A lit creeper used to be on that list and is not any more: every slot now says whether what holds it explodes and how
 * far along its fuse has burned, so the fighter reads it rather than guessing it from empty hands and a mob that stopped
 * coming, and it reads every slot rather than only its target. See {@link #flees}.
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

    /**
     * How many ticks before the swing is ready the step back in begins. The ground given up has to be covered again before
     * the blow can land, and a body walks 0.216 blocks a tick, so four ticks is most of the block between the standoff and
     * the band a swing reaches from: start on the tick the cooldown fills and the fighter arrives four ticks late every
     * cycle, which over a fight is blows not thrown and a clock that runs out.
     */
    private static final int PACK_STEP_IN_TICKS = 4;

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
     *
     * <p>Far enough is necessary and not sufficient: see {@link #finishesInTime}. A draw at something that arrives
     * before it is full is a draw given up, and this distance alone said nothing about what was coming.
     */
    private static final float SHOOT_RANGE = 5.0F;

    /**
     * A draw already under way is worth finishing even as the target closes, since a full arrow is most of a sword's blow
     * and nearly in hand. Inside this the bow is given up for the sword instead, which is now a swap and no longer a
     * cancel: the body keeps the slot until the draw is charged, so the arrow goes and the sword comes up behind it. See
     * AgentMob#drawHoldsTheSlot.
     */
    private static final float ABANDON_DRAW_RANGE = 2.6F;

    /**
     * How long a draw takes to reach the charge worth letting go of: twenty ticks for a bow, which is a full power arrow,
     * and twenty five for a crossbow's wind. Until a release has said which of the two is in hand the longer is assumed,
     * since a draw begun on the wrong figure is a draw that does not finish.
     */
    private static final int BOW_DRAW_TICKS = 20;
    private static final int CROSSBOW_WIND_TICKS = 25;

    /**
     * How far a mob travels in a tick for each point of the movement speed attribute a slot carries. Measured off the
     * recorded league fights rather than worked out from vanilla's friction, over two thousand replays: a zombie, whose
     * attribute is 0.23, covers 0.154 blocks a tick, and a vindicator and a piglin brute, both 0.35, cover 0.239 and
     * 0.240. That is one constant to within two percent.
     *
     * <p>Some things close faster than they walk — an enderman teleports, a ravager charges, a wolf sprints — by up to
     * half again. That is what the velocity in the slot is for: whichever of the two is faster is what the target is
     * credited with, so a mob already coming is believed over its own attribute, and a mob knocked backwards or standing
     * still is still credited with what it could do next tick.
     */
    private static final double BLOCKS_A_TICK_PER_SPEED = 0.67D;

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
    // A pack: several bodies all coming at once
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * How many bodies in the fight make it a pack. Two, because two is already more than one blow can answer: the fighter
     * trades with the nearest and the second walks round the back of it while the cooldown runs.
     *
     * <p>It is also what keeps a plain fight out of all of this. One opponent can never be two, so a fight against one body
     * reaches none of the rules below however it goes, which is what the arena check's 54 ticks say every time it runs.
     */
    private static final int PACK_FIGHTERS = 2;

    /**
     * How near a body has to be to count towards that: six blocks, which is about two seconds of a zombie's walk and the
     * same distance the agent hears at all round. Further off than that it is not in the fight yet, whatever it intends,
     * and a fighter that backed away from a pack still crossing the ground would never close on anything.
     */
    private static final float PACK_RANGE = 6.0F;

    /**
     * How near the nearest has to come before the aim leaves the middle of the pack and goes on the body itself. A tick of
     * a walk past a swing's own reach, so the aim is already there when the body arrives rather than swinging round after it.
     */
    private static final float PACK_AIM_RANGE = SWING_RANGE + 1.0F;

    /**
     * How far off the nearest of the pack has to be before giving ground stops being worth anything. It is the number that
     * makes this a cycle rather than a retreat, and it was measured rather than chosen.
     *
     * <p>Backing away for as long as two bodies were in the fight was the first thing tried, and it is exactly wrong: a body
     * walking backwards covers 0.216 blocks a tick against a zombie's 0.154, so it never lets anything arrive, never lands
     * a blow, and runs the clock out instead. Over 342 pack fights on one bench it turned 6.1% timeouts into 23.4% and took
     * the win rate from 59.4% to 43.6%. So ground is given while the swing is cooling and something is near enough to be
     * worth giving it from, and the moment the swing is ready the fighter closes and takes it. Four blocks is a step and a
     * half outside a swing's own reach: far enough that nothing can strike from there, near enough that one step in is a
     * blow.
     */
    private static final float PACK_STANDOFF = 4.0F;

    /**
     * How far a body walking backwards covers in a tick, which is the one number the whole kite turns on. A zombie, a husk,
     * a drowned and a zombie villager all walk 0.154; a vindicator and a piglin brute cover 0.24 and a spider more. So
     * against the first four a fighter that never lets one inside its own reach is never touched, and against the rest the
     * same rule is a fighter walking slowly backwards into whatever is behind it.
     */
    private static final double BACKPEDAL = 0.216D;

    /**
     * Under this, in blocks a tick, everything in the pack is slower than a backpedal and the standoff below is worth
     * holding. It is the backpedal with a margin taken off it rather than the backpedal itself: the gap between the two
     * kinds of body is wide — 0.154 against 0.24 — and a threshold in the middle of that gap cannot be crossed by a body
     * sliding downhill or knocked along the ground.
     *
     * <p>Read off the slot's own {@code ENEMY_SPEED} and not off the velocity in it. The attribute is the same number every
     * tick, so the fighter cannot change its mind about which fight it is in twice a second, and the things in the league
     * that close faster than they walk — an enderman's teleport, a ravager's charge, a wolf's sprint — all carry an
     * attribute far above this anyway, so nothing is let through by reading the steady number.
     */
    private static final double KITE_PACE = BACKPEDAL - 0.026D;

    /**
     * Where the nearest of a slow pack is held. Inside this the fighter gives ground whether or not its swing is ready;
     * outside it, with the swing ready, it stands still and lets the body walk into {@link #SWING_RANGE}.
     *
     * <p>It is a little outside a mob's own reach — anything man sized strikes from a block and a half head on and two
     * across a diagonal — and a little inside a sword's, so the band between this and {@link #SWING_RANGE} is ground the
     * fighter can strike from and nothing in the pack can strike back from.
     */
    private static final float KITE_EDGE = 2.6F;

    /**
     * How many ticks the nearest of a slow pack may go without closing before the fighter stops waiting and walks up to it.
     * Holding ground is only worth anything against a pack that is coming; against one that has stopped — stuck on a ledge,
     * sliding backwards from the blow that just landed, or simply not pathing — it is the retreat that was measured at
     * {@link #PACK_STANDOFF} and turned 6.1% timeouts into 23.4%.
     *
     * <p>Twenty ticks, which is a second, and comfortably longer than the dozen a body spends sliding away from a blow.
     */
    private static final int KITE_PATIENCE = 20;

    /**
     * How far the second nearest has to be for a sprint blow to be worth its step forward. A sprint is forward only, so the
     * blow is thrown from about a block nearer than the agent stands, and anything man sized strikes from a block and a half,
     * two across a diagonal: four and a half blocks leaves the spot the blow is thrown from outside the second body's reach.
     * Nearer than that the point of extra knockback is bought by walking into a second set of hands.
     */
    private static final float PACK_SPRINT_CLEAR = 4.5F;

    /**
     * How near a hazard or a drop along a ray makes a step that way a step to take only when there is nothing else. The
     * grid already refuses to walk onto either inside its own four blocks; this is what the rays add out to thirty two, so
     * that backing away from a pack towards a ravine turns into stepping sideways along it instead.
     */
    private static final float PACK_CLEAR_BEHIND = 6.0F;

    /** Below this share of its health the fighter asks whether the fight is winnable at all; see {@link #hopeless}. */
    private static final float PACK_HOPELESS_HEALTH = 0.5F;

    /** A vanilla melee goal swings once every twenty ticks, which is what turns the pack's damage into a rate. */
    private static final double MOB_BLOW_TICKS = 20.0D;

    /** Twenty points of armour take four fifths off a blow, and the self block gives armour as a share of twenty. */
    private static final double ARMOUR_SOAKS = 0.8D;

    /** The humanoid's health when whole, which the self block's own health is a fraction of. */
    private static final double AGENT_HEALTH = 20.0D;

    // What the pack came to on this tick. Working space like the search's arrays above and for the same reason: it is
    // written by sizeUp before anything reads it and never outlives the tick, so it is not the fighter's state and does not
    // have to be checked against the body the way everything in Fighter does.
    private int inTheFight;
    private float threatForward;
    private float threatRight;
    private float nearestInFight;
    private float secondNearest;
    private float packHealth;
    private float packDamage;

    /** The fastest thing in the pack, in blocks a tick, which is what says whether a kite is possible at all. */
    private double packPace;

    /** Which slot the nearest of the pack is in, so what it is doing can be asked of what was watched about it. */
    private int nearestSlot;

    // ---------------------------------------------------------------------------------------------------------------
    // Creepers
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * The fallback's range, for a body whose slots say nothing about exploding; see {@link #flees}. A creeper lights its
     * fuse within three blocks of its target and stands still while it burns, for thirty ticks, and it is the one thing in
     * the league that fights with nothing in its hands and never swings. So an empty handed opponent that has never swung
     * and has stopped coming this close is one about to go off.
     *
     * <p>Three blocks and not one more, because it is the creeper's own figure. Everything else with empty hands stops at
     * its own reach and swings from there, and a ravager's reach is four blocks: read any wider and a ravager waiting out
     * its own cooldown is taken for a lit creeper, walked away from before it ever swings, and never seen to swing at
     * all.
     */
    private static final float FUSE_RANGE = 3.05F;

    /**
     * The fallback's patience. How many ticks it has to have spent not coming any closer, and whether it is coming rather
     * than whether it is moving at all: a creeper that has just been hit slides backwards from the blow for a dozen ticks,
     * and waiting for that to settle would spend half the fuse standing in the blast.
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

        /** The slot holding the lit thing it is walking away from, or -1: it keeps going until that one is well clear. */
        private int fleeingFrom = -1;

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
            this.fleeingFrom = -1;
            this.lastStrength = 0.0F;
            this.strengthRate = 0.0F;
            this.sinceJump = CRIT_JUMP_COOLDOWN;
        }

        /** Nothing in sight: whatever was drawing runs to full and looses on its own, and there is nothing to flee. */
        private void idle() {

            this.draw = this.draw == DRAW_LOADED ? DRAW_LOADED : DRAW_IDLE;
            this.asked = false;
            this.askedShield = false;
            this.fleeingFrom = -1;
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
        watch(me, o, obs);

        // Positions arrive in the agent's own frame, already scaled down by the view distance.
        float forward = o[target + ObservationSchema.ENEMY_FORWARD];
        float right = o[target + ObservationSchema.ENEMY_RIGHT];
        float up = o[target + ObservationSchema.ENEMY_UP];
        float distance = o[target + ObservationSchema.ENEMY_DISTANCE] * (float) ObservationSchema.VIEW_DISTANCE;

        // Back from the agent's frame to the world's, in blocks: forward runs along minus sine, cosine and right along
        // minus cosine, minus sine. The grid puts the agent in the middle of its own column.
        double view = ObservationSchema.VIEW_DISTANCE;
        double targetX = gridX(o, target, sin, cos);
        double targetZ = gridZ(o, target, sin, cos);
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

        // How many bodies are actually in this fight, read before anything decides what to do about them: a drawn weapon
        // is one of the things a pack changes, and the count used to be taken after the fighter had already committed to
        // standing still for twenty ticks.
        int inFight = this.sizeUp(me, o, obs);
        boolean crowded = inFight >= PACK_FIGHTERS;

        boolean shooting = shoots(me, o, target, ranged, melee, distance, clear, crowded);

        // Which slot is about to go off, which need not be the one being fought: two creepers, and the fighter walks away
        // from the lit one while the other is still its target.
        int lit = shooting ? -1 : this.flees(me, slot, o, obs, target, distance);
        boolean fleeing = lit >= 0;

        // Anything with empty hands that has not swung yet might be a creeper, so while the swing is still cooling it is
        // held at the three blocks a creeper needs to light its fuse, rather than let inside the usual band. It costs
        // nothing against the rest of them, since an empty handed mob reaches a block and a half, two across a diagonal:
        // the fighter steps in to swing and back out to wait, and is hitting something that cannot reach it.
        boolean mayExplode = emptyHanded(o, target) && !me.hasSwung(slot);
        float backOff = mayExplode && strength < FULL_STRENGTH ? FUSE_RANGE : BACK_OFF_RANGE;

        // Where the pack lies, as one direction, back in the world's frame the grid is written in. Worked out before the
        // shot as well as before the swing, because the feet answer to the pack whichever the hands are doing.
        double threatX = -sin * this.threatForward - cos * this.threatRight;
        double threatZ = cos * this.threatForward - sin * this.threatRight;

        int start = state(CENTRE, 0, CENTRE);

        // Where to look. A swing goes where the eyes are; a shot has to be thrown ahead of the target and above it, by
        // whatever the arrow falls over the ground it has to cover and whatever the target covers while it flies.
        if (shooting) {

            this.shoot(me, o, a, obs, act, ranged, target, forward, right, up);

            // A drawn weapon takes the movement keys down to a fifth, so there is no walking out of trouble while it is
            // up: it holds its ground, steps away from anything already on top of it, and walks up to find a line when
            // the one it has is blocked.
            //
            // In a pack the step away is the pack's and not the target's, which is the same rule the swinging fighter
            // already has and for the same reason: the way the nearest of several lies is rarely the way they all do, and
            // backing away from the one being shot at walks into the one that is not.
            if (crowded && this.nearestInFight <= PACK_STANDOFF) {

                this.walkTowards(this.giveGround(o, obs, threatX, threatZ, false), a, act, sin, cos, grounded);
            }

            else if (distance < BACK_OFF_RANGE) {

                this.walkTowards(this.retreat(o, obs, targetX, targetZ), a, act, sin, cos, grounded);
            }

            else if (!clear) {

                this.walkTowards(this.approach(o, obs, targetX, targetEye, targetZ, false, false), a, act, sin, cos, grounded);
            }

            return;
        }

        // Several bodies all coming at once, which is a different fight from one body twice over and the one this fighter
        // used to stand in the middle of and trade in. Everything that follows from it is gated on there being two of them
        // in the fight, so a fight against one opponent reaches none of it. Never while walking away from a lit fuse: that
        // already has the feet and is already the right answer, whatever else is standing there.
        boolean pack = !fleeing && crowded;

        // The step away is worked out before the aim rather than down with the rest of the footwork, because a fight being
        // run from is a fight the agent has to look the way it is running: a sprint is forward only.
        boolean running = pack && this.hopeless(me, o, obs);
        int packStep = pack ? this.giveGround(o, obs, threatX, threatZ, running) : start;

        // Nowhere to go is not hopeless, it is cornered, and a cornered fighter turns and fights rather than running on the
        // spot with its back to the pack.
        running = running && packStep != start;

        // Whether this pack can be kited at all, which is one number: everything in it slower than a backpedal. Against
        // four zombies it is, and a fighter that holds the band between a mob's reach and a sword's is never touched; against
        // two vindicators it is not, and the same rule would be walking slowly backwards while being hit. Never while the
        // fight is being run from, which has the feet already, and never against a pack that has stopped coming, which is
        // the retreat that ran the clock out at PACK_STANDOFF wearing a different hat. See KITE_PACE.
        boolean kiting = pack && !running && this.packPace < KITE_PACE && this.nearestSlot >= 0
                && me.holdingBack[this.nearestSlot] < KITE_PATIENCE;

        // The cycle: ground is given while the swing is cooling and something is near enough to be worth giving it from,
        // and the moment the swing is ready the fighter closes and takes it. See PACK_STANDOFF for what happens to a
        // fighter that simply backs away instead, which is the first thing this was and the wrong thing.
        //
        // Against a pack it can outwalk there is one more reason to give ground and it does not wait for the cooldown: a
        // body inside KITE_EDGE is a body that can strike, and the whole of the kite is never letting one get there. So the
        // fighter backs off whenever one does, holds where it is while the swing is ready and the body walks in, and swings
        // the tick that body crosses a sword's own reach.
        boolean giving = pack && (running
                || !stepsIn(me, strength) && this.nearestInFight <= PACK_STANDOFF
                || kiting && this.nearestInFight <= KITE_EDGE);

        // Turning right is a rising yaw, and a target off to the right has a positive right component, so the error and
        // the control share a sign and no correction is needed.
        float yawError = (float) Math.toDegrees(Mth.atan2(right, forward));

        // Where the eyes actually go, which is the target's own bearing in every fight against one body. Against a pack it
        // is the middle of them while none of them is in reach — the agent perceives through a hundred degree cone about its
        // aim, so looking at the middle of a pack is what keeps the ones at its edges from walking round behind it and out of
        // the observation altogether — and it is the way out while the fight is being run from.
        float lookError = yawError;

        if (running) {

            lookError = bearing(packStep, sin, cos);
        }

        else if (pack && !(stepsIn(me, strength) && distance <= PACK_AIM_RANGE)) {

            lookError = (float) Math.toDegrees(Mth.atan2(this.threatRight, this.threatForward));
        }

        a[act + ActionSchema.AIM_YAW] = Mth.clamp(lookError / MobControls.MAX_AIM_YAW_PER_TICK, -1.0F, 1.0F);

        float horizontal = (float) Math.sqrt(forward * forward + right * right) * (float) ObservationSchema.VIEW_DISTANCE;
        float wantedPitch = (float) -Math.toDegrees(Mth.atan2(up * (float) ObservationSchema.VIEW_DISTANCE, horizontal));
        float pitch = o[self + ObservationSchema.SELF_PITCH] * 90.0F;

        a[act + ActionSchema.AIM_PITCH] =
                Mth.clamp((wantedPitch - pitch) / MobControls.MAX_AIM_PITCH_PER_TICK, -1.0F, 1.0F);

        // Whatever it swings with, held ready. A draw that was under way is not cancelled by asking for another slot: the
        // body holds the slot until the draw is charged, looses, and swaps then. See AgentMob#drawHoldsTheSlot.
        a[act + ActionSchema.SELECTED_SLOT] = Math.max(0, melee);

        if (me.draw == DRAW_DRAWING) {

            me.draw = DRAW_IDLE;
            me.asked = false;
        }

        int next = start;

        boolean retreating = giving || fleeing || distance < backOff;

        // Somewhere to stand that puts a hazard directly behind the target, which turns one blow into the whole fight.
        // Every such spot is already inside the band with a clear line, so wanting one never argues with the footwork.
        int shove = retreating ? -1 : this.hazardSpot(o, obs, distance, targetX, targetEye, targetZ);
        boolean linedUp = shove == start;

        if (retreating) {

            if (pack) {

                // Away from all of them at once, which is the one direction the two below cannot give: the way the nearest
                // of a pack lies is rarely the way the pack does, and backing away from one body walks into another.
                next = packStep;
            }

            else {

                // Away from the lit one where there is one, and away from the target otherwise. The two are the same slot in
                // every single-creeper fight and different in the one that used to be lost.
                int from = fleeing ? obs + ObservationSchema.enemyOffset(lit) : target;

                next = this.retreat(o, obs, gridX(o, from, sin, cos), gridZ(o, from, sin, cos));
            }
        }

        else if (shove >= 0) {

            next = shove;
        }

        else if (!kiting && distance > CLOSE_IN_RANGE || !clear) {

            float targetSpeed = Math.abs(o[target + ObservationSchema.ENEMY_VELOCITY_FORWARD])
                    + Math.abs(o[target + ObservationSchema.ENEMY_VELOCITY_RIGHT]);

            next = this.approach(o, obs, targetX, targetEye, targetZ, swungIntoBlock, targetSpeed < STILL_SPEED);
        }

        if (next != start) {

            this.walkTowards(next, a, act, sin, cos, grounded);

            // A fight being run from is sprinted out of whatever the distance, which is the whole difference between
            // running and backing away: backing away is done facing the pack and so at walking pace, and running is not.
            if ((running || distance > SPRINT_RANGE)
                    && a[act + ActionSchema.MOVE_FORWARD] >= MobControls.SPRINT_FORWARD_THRESHOLD) {

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
        boolean busy = this.hold(me, o, a, obs, act, slot, target, distance, strength, incomingDistance(o, obs),
                fleeing || running);

        if (busy) {

            return;
        }

        // Whether this blow wants to carry knockback rather than the damage a critical adds. A hazard behind the target is
        // the whole fight; a reach longer than a man's, empty hands that have not swung yet, and health already gone are
        // each a reason to want whatever is in front of the agent further away than it is. Never while backing away, since
        // a sprint is forward only: a player cannot sprint out of a fight either.
        //
        // In a pack that last rule is inverted rather than dropped. The knockback is the whole point there — a body a blow
        // puts a block and a half back is a body out of the ring for as long as it takes to walk in again — so the only
        // question is what the step forward costs, and that is the second nearest body: see PACK_SPRINT_CLEAR.
        boolean shoving = pack
                ? !running && this.secondNearest > PACK_SPRINT_CLEAR
                : !retreating
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
        //
        // Never in a pack, where something is given up for it: a body in the air keeps the momentum it left the ground with
        // for a dozen ticks and steers with a fortieth of a step, and the dozen ticks the footwork is given up for are the
        // dozen the second and third bodies need to walk round the back of it.
        if (!shoving && !pack && aimed && grounded && this.jumpsForCrit(me, strength)) {

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

    /**
     * Whether the swing is near enough to ready for the fighter to start closing on the pack again. It is the cooldown's own
     * rate that says how near, which {@link #time} already reads off two ticks of the observation, so this is as true of an
     * axe's twenty ticks as of a sword's twelve and a half.
     */
    private static boolean stepsIn(Fighter me, float strength) {

        return strength >= FULL_STRENGTH
                || me.strengthRate > 0.0F && (1.0F - strength) / me.strengthRate <= PACK_STEP_IN_TICKS;
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
     * What the fighter has to remember about the bodies round it: whether each has ever swung, whether it swung from
     * further off than anything man sized reaches, and how long it has gone without coming any closer.
     *
     * <p>Velocities arrive in the agent's own frame, and the agent is looking at what it is fighting, so the forward
     * component is how fast the thing is going the way the agent faces: negative is coming at it, positive is going away.
     * Knocked back by a blow, a mob slides away for a dozen ticks, and that counts as not coming rather than as movement.
     *
     * <p>Every slot, not only the one being fought. A swing is what says a body is in the fight before it has ever taken the
     * agent as its target, and in a pack the one being fought is one of several; against a single opponent there is nothing
     * in the other nine slots but arrows, which never swing, so this reads exactly as it always did. A slot that empties
     * forgets what was in it, since the order the slots are handed out in is the fight's and not the bodies', so the next
     * thing to hold that slot is a different body and none of this is true of it.
     */
    private static void watch(Fighter me, float[] o, int obs) {

        for (int slot = 0; slot < ObservationSchema.ENEMY_SLOTS; slot++) {

            int at = obs + ObservationSchema.enemyOffset(slot);

            if (o[at + ObservationSchema.ENEMY_PRESENT] < 0.5F) {

                me.swung &= ~(1 << slot);
                me.longReach &= ~(1 << slot);
                me.holdingBack[slot] = 0;
                continue;
            }

            if (o[at + ObservationSchema.ENEMY_SWINGING] > 0.5F) {

                me.swung |= 1 << slot;

                if (o[at + ObservationSchema.ENEMY_DISTANCE] * ObservationSchema.VIEW_DISTANCE > LONG_REACH_DISTANCE) {

                    me.longReach |= 1 << slot;
                }
            }

            boolean coming = o[at + ObservationSchema.ENEMY_VELOCITY_FORWARD] < -STILL_SPEED;

            me.holdingBack[slot] = coming ? (byte) 0 : (byte) Math.min(Byte.MAX_VALUE, me.holdingBack[slot] + 1);
        }
    }

    /**
     * Whether to shoot rather than close in. Something to shoot with, a line to shoot along, and a draw that will be full
     * before the target gets here are the whole of it, beyond a sword doing more inside its own reach than a bow needing
     * twenty ticks: a fighter with nothing to swing shoots at any distance, since punching with a bow is worth one damage
     * against an arrow's six.
     */
    private static boolean shoots(Fighter me, float[] o, int target, int ranged, int melee, float distance, boolean clear,
            boolean crowded) {

        if (ranged < 0) {

            return false;
        }

        // A draw or a load already in hand is finished and fired, even as the target closes, as long as it cannot be put
        // to better use: an arrow nearly drawn is most of a sword's blow away.
        boolean holding = me.draw != DRAW_IDLE;

        if (melee < 0) {

            // Nothing to swing, so a shot is worth taking at any distance: punching with a bow is one damage against an
            // arrow's six. But a pack is the one thing that answers that, and it was the widest hole in this fighter:
            // over 300 pack fights on the harness a bow landed 0.0 blows a fight and took 2.5, and a crossbow 0.0 and 3.0,
            // against a sword's 8.7 and 0.5. It was standing still at a fifth of walking pace, twenty ticks at a time,
            // while four bodies walked onto it. So in a pack a draw is begun only where it can be finished — the same
            // question finishesInTime already asks of a fighter that has a sword to fall back on — and what it does with
            // the ticks it no longer spends drawing is give ground, which is what the rest of this fighter would do.
            return holding || clear && (!crowded || finishesInTime(me, o, target, distance));
        }

        if (holding) {

            return distance > ABANDON_DRAW_RANGE;
        }

        return clear && distance > SHOOT_RANGE && finishesInTime(me, o, target, distance);
    }

    /**
     * Whether a draw begun now would be full before the target could interrupt it. A draw is twenty ticks of standing at a
     * fifth of walking pace and there is no way out of one, so a draw begun at something that arrives inside those twenty
     * ticks is twenty ticks spent shooting at a thing that is already swinging, and before the slot was committed it was
     * worse still: the draw was given up and there was no arrow at the end of it either. The distance alone never said
     * that. Over 400 of the teacher's own recorded sword and bow fights, of the draws it gave up before twenty ticks, 79%
     * were begun between five and seven and a half blocks — the band that every walker in the league crosses in less than
     * a draw.
     *
     * <p>It costs the opening draw very little. Fights start a median nine blocks apart; a zombie has to be past five and a
     * half and the fastest thing that walks, a vindicator, past seven and a third, so the arrow the teacher opens with is
     * still there. What goes is the second draw it used to start the moment a blow knocked something back past five blocks.
     * The exception is the first draw of a fight, judged on a crossbow's twenty five ticks because nothing has said yet
     * which weapon this is, which asks eight and a half blocks of a vindicator: that one is given up where the fight starts
     * close.
     *
     * <p>Something that shoots back or flies is worth a draw at any distance past a sword's own reach. Closing is no answer
     * to either: an archer twelve blocks off will not come, and nothing swung reaches a flyer.
     */
    private static boolean finishesInTime(Fighter me, float[] o, int target, float distance) {

        if (o[target + ObservationSchema.ENEMY_SHOOTS] > 0.5F || o[target + ObservationSchema.ENEMY_FLIES] > 0.5F) {

            return true;
        }

        double walks = o[target + ObservationSchema.ENEMY_SPEED] * ObservationSchema.SPEED_SCALE * BLOCKS_A_TICK_PER_SPEED;
        double coming = -o[target + ObservationSchema.ENEMY_VELOCITY_FORWARD] * VELOCITY_SCALE;
        double ticks = me.weapon == WEAPON_BOW ? BOW_DRAW_TICKS : CROSSBOW_WIND_TICKS;

        return distance - Math.max(walks, coming) * ticks > ABANDON_DRAW_RANGE;
    }

    /**
     * Where whatever holds an enemy slot stands in the terrain grid, along x and along z. Positions arrive in the agent's
     * own frame, already divided by the view distance: forward runs along minus sine, cosine and right along minus cosine,
     * minus sine, and the grid puts the agent in the middle of its own column.
     */
    private static double gridX(float[] o, int at, float sin, float cos) {

        return CENTRE + 0.5D
                + (-sin * o[at + ObservationSchema.ENEMY_FORWARD] - cos * o[at + ObservationSchema.ENEMY_RIGHT])
                        * ObservationSchema.VIEW_DISTANCE;
    }

    private static double gridZ(float[] o, int at, float sin, float cos) {

        return CENTRE + 0.5D
                + (cos * o[at + ObservationSchema.ENEMY_FORWARD] - sin * o[at + ObservationSchema.ENEMY_RIGHT])
                        * ObservationSchema.VIEW_DISTANCE;
    }

    /**
     * Which slot holds something about to go off and worth walking away from, or -1 for nothing. Two fields say it
     * outright: {@code ENEMY_EXPLODES}, and {@code ENEMY_FUSE}, which is how far along the fuse has burned. So this asks
     * them, and it asks <b>every occupied slot</b> rather than only the one being fought: the lit one is walked away from
     * whether or not it is the target, which is the fight this used to lose outright — a second creeper coming up behind
     * while the first was being swung at was never reasoned about at all.
     *
     * <p>Once it starts walking away it keeps going until that one is well clear, since a fuse only winds back down past
     * seven blocks, and it stops if the thing leaves the view.
     *
     * <p>The guess underneath is kept only for a body whose slots say nothing about exploding whatever: only a creeper in
     * the league fights with empty hands and never swings, and only a creeper stops coming once it is next to its target.
     * <b>It was written before those two fields existed and it is strictly worse than they are.</b> It reasons about one
     * slot; it cannot tell a lit creeper from an unlit one standing still; and it waits for three blocks, which is 0.8
     * blocks inside a creeper's own fuse range, so the fighter was still in the blast when it went off. Over 81 recorded
     * single-creeper fights that was 41 draws, ending on 9.2 health of 20 where a win ended on 19.9. The fields are the
     * truth; this is what is left when there are none.
     *
     * @return the slot to walk away from, or -1
     */
    private int flees(Fighter me, int slot, float[] o, int obs, int target, float distance) {

        // Already walking away from one, and it is neither clear nor gone: keep going.
        if (me.fleeingFrom >= 0 && explodes(o, obs, me.fleeingFrom)
                && slotDistance(o, obs, me.fleeingFrom) < FUSE_SAFE_RANGE) {

            return me.fleeingFrom;
        }

        me.fleeingFrom = nearestLit(o, obs);

        // A slot that says it explodes has said everything there is to say, lit or not, so the guess is never reached
        // where the fields speak.
        if (me.fleeingFrom >= 0 || anyExplodes(o, obs)) {

            return me.fleeingFrom;
        }

        me.fleeingFrom = distance < FUSE_RANGE && emptyHanded(o, target) && !me.hasSwung(slot)
                && me.holdingBack[slot] >= FUSE_STILL_TICKS ? slot : -1;

        return me.fleeingFrom;
    }

    /** The nearest slot whose occupant goes off and has its fuse burning, near enough to be worth leaving, or -1. */
    private static int nearestLit(float[] o, int obs) {

        int best = -1;
        float bestDistance = FUSE_SAFE_RANGE;

        for (int slot = 0; slot < ObservationSchema.ENEMY_SLOTS; slot++) {

            if (!explodes(o, obs, slot)
                    || o[obs + ObservationSchema.enemyOffset(slot) + ObservationSchema.ENEMY_FUSE] <= 0.0F) {

                continue;
            }

            float distance = slotDistance(o, obs, slot);

            if (distance < bestDistance) {

                bestDistance = distance;
                best = slot;
            }
        }

        return best;
    }

    /** Whether a slot is occupied by something that goes off, whether or not its fuse is burning yet. */
    private static boolean explodes(float[] o, int obs, int slot) {

        int at = obs + ObservationSchema.enemyOffset(slot);

        return o[at + ObservationSchema.ENEMY_PRESENT] > 0.5F && o[at + ObservationSchema.ENEMY_EXPLODES] > 0.5F;
    }

    /** Whether anything in view says it goes off, which is what tells a silent slot from an unlit creeper. */
    private static boolean anyExplodes(float[] o, int obs) {

        for (int slot = 0; slot < ObservationSchema.ENEMY_SLOTS; slot++) {

            if (explodes(o, obs, slot)) {

                return true;
            }
        }

        return false;
    }

    /** How far off whatever holds a slot is, in blocks. */
    private static float slotDistance(float[] o, int obs, int slot) {

        return o[obs + ObservationSchema.enemyOffset(slot) + ObservationSchema.ENEMY_DISTANCE]
                * (float) ObservationSchema.VIEW_DISTANCE;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // A pack
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Reads the pack off the slots: how many bodies are actually in the fight and near enough to be in it, which way they
     * lie as one direction, how far off the second nearest of them is, and what there is of them to cut down and to be hurt
     * by. Everything it finds goes into this brain's own working space, since none of it outlives the tick.
     *
     * <p><b>In the fight</b> is the slot's own {@code ENEMY_TARGETS_ME}, or a body that has been seen to swing, which is the
     * same "it has come for me" the observation's count of the fight is made of. A body that is merely standing there is not
     * a pack however many of it there are, which is what keeps the league's idle bystanders out of all of this.
     *
     * <p><b>Near enough</b> is {@link #PACK_RANGE}, and it has to be something that must reach the agent to hurt it: a
     * flyer and anything that shoots count for nothing here, because backing away from an archer is backing away while being
     * shot and nothing swung reaches a flyer. Those are the ranged rules' fights and this leaves them exactly as they were.
     *
     * <p>The direction is the sum of the ways to each of them, each a unit vector weighted by how near it is, so the body
     * about to land a blow counts for more than the one still crossing the ground. Where two of them stand on opposite sides
     * that sum cancels, and there the nearest is the whole answer: backing away from the pack has no meaning, but backing
     * away from the one that is about to hit still does.
     *
     * @return how many bodies are in the fight, of which two is a pack
     */
    private int sizeUp(Fighter me, float[] o, int obs) {

        this.inTheFight = 0;
        this.threatForward = 0.0F;
        this.threatRight = 0.0F;
        this.nearestInFight = Float.MAX_VALUE;
        this.secondNearest = Float.MAX_VALUE;
        this.packHealth = 0.0F;
        this.packDamage = 0.0F;
        this.packPace = 0.0D;
        this.nearestSlot = -1;

        float nearestForward = 0.0F;
        float nearestRight = 0.0F;
        float sumForward = 0.0F;
        float sumRight = 0.0F;

        for (int slot = 0; slot < ObservationSchema.ENEMY_SLOTS; slot++) {

            int at = obs + ObservationSchema.enemyOffset(slot);

            if (o[at + ObservationSchema.ENEMY_PRESENT] < 0.5F
                    || AgentObservation.isProjectileKind(o[at + ObservationSchema.ENEMY_KIND])
                    || o[at + ObservationSchema.ENEMY_SHOOTS] > 0.5F
                    || o[at + ObservationSchema.ENEMY_FLIES] > 0.5F) {

                continue;
            }

            if (o[at + ObservationSchema.ENEMY_TARGETS_ME] < 0.5F && !me.hasSwung(slot)) {

                continue;
            }

            float distance = o[at + ObservationSchema.ENEMY_DISTANCE] * (float) ObservationSchema.VIEW_DISTANCE;

            if (distance > PACK_RANGE) {

                continue;
            }

            this.inTheFight++;
            this.packHealth += o[at + ObservationSchema.ENEMY_HEALTH_LEFT] * ObservationSchema.HEALTH_SCALE;
            this.packDamage += o[at + ObservationSchema.ENEMY_DAMAGE] * ObservationSchema.DAMAGE_SCALE;

            // The fastest of them, which is what decides whether the ground the fighter gives can ever be got back. One
            // body quicker than a backpedal is enough to settle it, so this is a maximum and not an average.
            this.packPace = Math.max(this.packPace,
                    o[at + ObservationSchema.ENEMY_SPEED] * ObservationSchema.SPEED_SCALE * BLOCKS_A_TICK_PER_SPEED);

            float forward = o[at + ObservationSchema.ENEMY_FORWARD];
            float right = o[at + ObservationSchema.ENEMY_RIGHT];

            if (distance < this.nearestInFight) {

                this.secondNearest = this.nearestInFight;
                this.nearestInFight = distance;
                this.nearestSlot = slot;
                nearestForward = forward;
                nearestRight = right;
            }

            else if (distance < this.secondNearest) {

                this.secondNearest = distance;
            }

            float across = (float) Math.hypot(forward, right);

            if (across > 1.0E-6F) {

                float weight = 1.0F / Math.max(distance, 1.0F);

                sumForward += weight * forward / across;
                sumRight += weight * right / across;
            }
        }

        float length = (float) Math.hypot(sumForward, sumRight);

        if (length <= 1.0E-6F) {

            sumForward = nearestForward;
            sumRight = nearestRight;
            length = (float) Math.hypot(sumForward, sumRight);
        }

        if (length > 1.0E-6F) {

            this.threatForward = sumForward / length;
            this.threatRight = sumRight / length;
        }

        return this.inTheFight;
    }

    /**
     * The step that puts ground between the agent and the pack: of the spots one step away that the grid says a body can
     * stand on, the one that goes most directly against the threat.
     *
     * <p>The grid is most of the safety already. Its search only ever visits somewhere with room for a body, ground under it
     * and nothing in it that hurts, and it is not allowed to step off an edge here, so a hazard, a drop and a wall inside its
     * own four blocks are all refused before this ever scores anything. What the rays add is the four blocks out to thirty
     * two: a direction whose ray reports lava or a ledge inside {@link #PACK_CLEAR_BEHIND} is scored down rather than
     * forbidden, so it is taken only when there is nothing better, and what "nothing better" comes out as is the step
     * sideways along the edge rather than over it.
     *
     * @param far whether this is a fight being run from rather than backed out of, which is what puts how clear a way is
     *            into the choice at all: one step cares only about the next block, a run cares about the next thirty
     * @return the spot to step to, or the one the agent stands on when the grid offers nowhere
     */
    private int giveGround(float[] o, int obs, double threatX, double threatZ, boolean far) {

        int reached = this.search(o, obs, false);
        int start = state(CENTRE, 0, CENTRE);

        int best = start;
        double bestScore = -Double.MAX_VALUE;

        for (int i = 0; i < reached; i++) {

            int s = this.queue[i];

            if (this.depth[s] != 1 || this.dropped[s]) {

                continue;
            }

            int dx = s % X - CENTRE;
            int dz = (s / X) % Z - CENTRE;
            double length = Math.hypot(dx, dz);

            if (length < 1.0E-6D) {

                continue;
            }

            // One where the step goes straight away from the pack, nought where it goes across them, less where it goes in.
            double score = -(dx * threatX + dz * threatZ) / length;

            int ray = obs + ObservationSchema.RAY_OFFSET + rayFor(dx, dz) * ObservationSchema.RAY_STRIDE;
            float clear = PACK_CLEAR_BEHIND / (float) ObservationSchema.RAY_REACH;

            if (o[ray + ObservationSchema.RAY_HAZARD] < clear || o[ray + ObservationSchema.RAY_DROP] < clear) {

                // Past the whole range a clear step can score, so this is only ever taken when every way out is like it.
                score -= 2.0D;
            }

            if (far) {

                score += Math.min(o[ray + ObservationSchema.RAY_WALL],
                        Math.min(o[ray + ObservationSchema.RAY_HAZARD], o[ray + ObservationSchema.RAY_DROP]));
            }

            if (score > bestScore) {

                bestScore = score;
                best = s;
            }
        }

        return best;
    }

    /**
     * Which of the eight rays runs the way a step goes. They are world aligned and one every forty five degrees, which is
     * exactly the eight steps the grid search takes, so every step has one of them and no step falls between two.
     */
    private static int rayFor(int dx, int dz) {

        return (int) Math.round(Math.atan2(dz, dx) / (Math.PI / 4.0D)) & (ObservationSchema.RAYS - 1);
    }

    /** How far off the agent's aim a spot in the grid lies, in degrees, which is what the aim control is given. */
    private static float bearing(int spot, float sin, float cos) {

        double dx = spot % X - CENTRE;
        double dz = (spot / X) % Z - CENTRE;

        return (float) Math.toDegrees(Mth.atan2(dx * -cos + dz * -sin, dx * -sin + dz * cos));
    }

    /**
     * Whether the fight is already lost, which is the one thing worth running from rather than trading through. It is
     * arithmetic and not despair, off the same slots everything else here reads, and deliberately only three numbers:
     *
     * <ul>
     *   <li><b>how long the agent has</b>: its own health, against what one round of the pack's blows takes off it — every
     *       engaged slot's own {@code ENEMY_DAMAGE}, less the share its armour stops — spread over the twenty ticks a
     *       vanilla melee goal waits between swings;</li>
     *   <li><b>how long the pack has</b>: the health standing in those same slots, over what the agent's own blow takes off,
     *       at one blow every cooldown — and the cooldown's rate is already known, read off two ticks of the observation by
     *       {@link #time}, which is why this holds for an axe as well as a sword;</li>
     *   <li><b>and nothing else.</b> No armour on their side, no knockback buying time, no hope of the ground finishing one.
     *       Every one of those makes the fight go better than this reckons, which is the right way round for a rule whose
     *       answer is to stop fighting.</li>
     * </ul>
     *
     * <p>Only ever under half health, so a fight that has barely started is never called off, and only once the cooldown's
     * rate has been seen, which is two ticks.
     */
    private boolean hopeless(Fighter me, float[] o, int obs) {

        int self = obs + ObservationSchema.SELF_OFFSET;
        float health = o[self + ObservationSchema.SELF_HEALTH];

        if (health >= PACK_HOPELESS_HEALTH || me.strengthRate <= 0.0F || this.packDamage <= 0.0F) {

            return false;
        }

        // A bare fist takes one, which is what a hotbar with nothing to swing in it comes to.
        double blow = Math.max(1.0D, o[self + ObservationSchema.SELF_WEAPON_DAMAGE] * ObservationSchema.DAMAGE_SCALE);
        double ticksToClear = this.packHealth / blow / me.strengthRate;

        double taken = this.packDamage * (1.0D - ARMOUR_SOAKS * o[self + ObservationSchema.SELF_ARMOUR]) / MOB_BLOW_TICKS;
        double ticksToFall = health * AGENT_HEALTH / Math.max(taken, 1.0E-4D);

        return ticksToFall < ticksToClear;
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
     * <p><b>A pack changes nothing here, and that was measured rather than assumed.</b> The obvious worry is that a shield
     * takes the movement keys down to a fifth while moving is the whole of what keeps several bodies from surrounding one,
     * and that every reason this rule has — hurt, within reach, the swing cooling — is true at once in a pack, so the shield
     * would go up and stay up. Narrowing it there to a body with its arm actually up was tried and is worse: over 342 pack
     * fights on one bench the narrow rule won 64.3% against this one's 65.2%, and on the two loadouts that carry a shield at
     * all it was 65.0% and 70.0% against 71.7% and 73.3%. A shield up between blows is worth more than the ground it costs,
     * because the ground is given back on the next tick and the blow is not.
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
