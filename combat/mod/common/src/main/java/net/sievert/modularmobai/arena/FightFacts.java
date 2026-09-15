package net.sievert.modularmobai.arena;

import java.util.Arrays;
import java.util.List;

import net.minecraft.core.Holder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Creeper;
import net.sievert.modularmobai.allegiance.Allegiance;
import net.sievert.modularmobai.brain.schema.ObservationSchema;
import net.sievert.modularmobai.entity.agent.AgentMob;

/**
 * What the game knows about a fight that no body's observation carries, written into every rollout row.
 *
 * <p>These are for the <b>critic</b>, which never leaves the training side: it is not exported, the game never runs it,
 * and no weight file mentions it, so it is allowed to know things the agent it judges cannot. What it does with them is
 * price a position less noisily, and advantage noise is the lever on everything downstream. The <b>actor never sees a
 * number from here</b> — nothing in this block reaches the observation, the schema or a weight file, and the schema id is
 * unchanged by it.
 *
 * <p>Two rules decide what belongs here.
 *
 * <ul>
 *   <li><b>Nothing that carries the answer.</b> The return is the value target, so an input holding any part of it would
 *       teach the critic to read the answer off its own inputs. No outcome, no reward, and no count of the ticks left:
 *       {@link #LIMIT} is how long this fight is given, which is fixed the moment it starts, and never how much of it is
 *       left. The observation's own clock ({@code SELF_CLOCK}) is the elapsed fraction, and elapsed is the past.</li>
 *   <li><b>Only what changes how a position should be priced.</b> Every column below is something the same position is
 *       worth differently because of, and something the observation either cannot say or says in a form the critic cannot
 *       use. The critic reads the raw row and has no encoder over the enemy slots, so a fact spread across ten slots is a
 *       fact it has to find; and a slot is filled only while the opponent is perceived — inside 32 blocks, inside the
 *       episode's bounds, and for 40 ticks of grace after that — so an opponent that walked behind a hill or teleported
 *       away reads exactly like no opponent at all. Where the observation has since grown a field for the same measurement
 *       — armour, whether the other side has engaged — the column below says what is still only here, which is the side's
 *       answer rather than one slot's.</li>
 * </ul>
 *
 * <p>The other side is described by what it <b>can do</b> rather than by which mob it is, for the same reason the enemy
 * slots are ({@code ObservationSchema}): a species number has to be learned one mob at a time, says nothing about a mob the
 * run never met, and would need a table kept in step between the shard and the trainer. Capabilities need none of that, and
 * they carry the difficulty ladder for free — a hard rung spawns a mob with more health and better armour, and that is what
 * these numbers say.
 *
 * <p>Ten floats a row, on a row that already holds 792 of observation, and the cost is per row in every shard: that is the
 * whole budget, and anything added here has to be worth more than what is already in it.
 */
public final class FightFacts {

    private FightFacts() {}

    /** How many floats one row holds. Mirrored by {@code SHARD_PRIVILEGED} in {@code trainer/mmai/model.py}. */
    public static final int SIZE = 10;

    /**
     * How many of the other side are still standing, over {@link #FOE_SCALE}. Two zombies are not twice a zombie and
     * nothing anywhere adds a squad's members up; the observation's count of enemies in range counts only what is
     * perceived, so an opponent out of view lowers it without anything having died.
     */
    public static final int FOES = 0;

    /**
     * How much of the other side is left: the health still standing over the health it has when whole, one at the start of
     * a fight and nought once every one of them is gone. The single strongest thing a value estimate can be given — a fight
     * with the opponent at a tenth of its health is nearly always a win — and it is the past, being exactly the damage the
     * agent has already dealt.
     *
     * <p>A slot's health is a fraction of that one mob's own maximum, only while that mob is perceived, and in one of ten
     * places. This is the side's, always.
     */
    public static final int FOE_HEALTH = 1;

    /** What the other side has when whole, in hearts over {@link #HEALTH_SCALE}: a zombie is 0.2 and a warden 5. */
    public static final int FOE_HEARTS = 2;

    /** The hardest blow anything still standing on the other side strikes with, over {@link #DAMAGE_SCALE}. */
    public static final int FOE_DAMAGE = 3;

    /**
     * The best armour anything still standing on the other side wears, over {@link #ARMOUR_SCALE}. A slot now says what the
     * mob in it wears ({@code ObservationSchema#ENEMY_ARMOUR}), which is where a policy needs it; this is the side's, in one
     * place, whether the mob wearing it is perceived or not. Most of what a hard rung of the difficulty ladder changes is
     * armour, so it is also most of what tells one rung's position from another's.
     */
    public static final int FOE_ARMOUR = 4;

    /**
     * How far along the fuse of the nearest lit creeper is, nought to one. The fight is about to end with a swing of three
     * or four in the reward, which is the largest single move a position can make; the slots do carry a creeper's fuse, but
     * in whichever of ten slots it happens to hold.
     */
    public static final int FOE_FUSE = 5;

    /**
     * Whether anything on the other side has the agent as its target right now, by the one rule {@link Allegiance#goesFor}.
     * A slot carries this per mob now ({@code ObservationSchema#ENEMY_TARGETS_ME}); what is still only here is the mob that
     * is out of view and walking over, which fills no slot at all. It decides whether the fight happens — an opponent that
     * never engages runs the clock out, and a timeout is paid as a loss.
     */
    public static final int WENT_FOR = 6;

    /**
     * What one of the agent's own blows takes off, over {@link #DAMAGE_SCALE}: the other half of how fast this fight can
     * end. The self block carries this now as well ({@code ObservationSchema#SELF_WEAPON_DAMAGE}), and the two are worked out
     * differently on purpose: that one adds up the item's own modifiers, so it is right on the first row and follows a swap
     * at once, and this one is the attribute, which is a tick behind the hand.
     *
     * <p>On the very first row of a fight this and {@link #FOE_DAMAGE} therefore read the bare body: vanilla applies a held
     * item's attribute modifiers when it notices the equipment change, which is the body's first tick, so a fight recorded as
     * 0.05 then 0.30 is an agent that was handed an iron sword. That is what the attribute says on that tick, and one row of
     * several hundred — and a row the critic is allowed to price late, where the policy choosing whether to swing is not.
     */
    public static final int OWN_DAMAGE = 7;

    /**
     * The armour the agent is wearing, over {@link #ARMOUR_SCALE}. The self block reads the same call for its own
     * ({@code ObservationSchema#SELF_ARMOUR}), so the two agree to the point; it is kept here because the critic reads the raw
     * row and pricing what a blow will cost the agent is what armour is for.
     */
    public static final int OWN_ARMOUR = 8;

    /**
     * How long this fight is given, in ticks over {@link #TICK_SCALE}. Legitimate where a countdown would not be: it is
     * fixed before the first tick and says nothing about how much is left. The observation's clock is a fraction of
     * <i>this</i> limit, so tick 600 reads 0.5 in a melee fight and 0.25 against a ghast, and the speed bonus a win pays is
     * measured against the limit too. It also says, coarsely, what kind of fight this is: a matchup asks for 1,200 ticks
     * against something it can reach, 1,800 against something that shoots and 2,400 against something that flies.
     */
    public static final int LIMIT = 9;

    /**
     * Deliberately the scales an enemy slot uses, so a number means the same here as it does there — and read from there
     * rather than copied, since two literals that have to agree eventually do not. Each was the same number written twice.
     */
    private static final float HEALTH_SCALE = ObservationSchema.HEALTH_SCALE;
    private static final float DAMAGE_SCALE = ObservationSchema.DAMAGE_SCALE;
    private static final float ARMOUR_SCALE = ObservationSchema.ARMOUR_SCALE;

    /** The enemy slots an observation holds, so a count of the other side reads on the same scale as enemies in range. */
    private static final float FOE_SCALE = ObservationSchema.ENEMY_SLOTS;

    /** A fight's minute, so a matchup given longer than one reads above one. See {@link AgentReward#DEFAULT_MAX_TICKS}. */
    private static final float TICK_SCALE = AgentReward.DEFAULT_MAX_TICKS;

    /**
     * One agent's row. Everything about the other side comes from the episode, which is what knows who that is; what the
     * agent itself carries is written whether it is in a fight or not, and an agent met out in a world with no episode has
     * no other side and no clock, so the rest reads nought.
     *
     * @param out  the destination buffer, which may hold a whole batch
     * @param base where this agent's row starts in that buffer
     */
    public static void write(AgentMob agent, float[] out, int base) {

        Arrays.fill(out, base, base + SIZE, 0.0F);

        out[base + OWN_DAMAGE] = attribute(agent, Attributes.ATTACK_DAMAGE) / DAMAGE_SCALE;
        out[base + OWN_ARMOUR] = agent.getArmorValue() / ARMOUR_SCALE;

        Episode episode = agent.episode();

        if (episode == null) {

            return;
        }

        out[base + LIMIT] = episode.reward().maxTicks() / TICK_SCALE;

        List<LivingEntity> opponents = episode.opponents();
        float left = 0.0F;
        float whole = 0.0F;
        int standing = 0;

        for (LivingEntity opponent : opponents) {

            whole += opponent.getMaxHealth();

            // Gone counts as gone whether its health ran out or it took itself away, which is what a creeper that blew
            // itself up does: its health never reached zero and there is nothing left to fight.
            if (!opponent.isAlive()) {

                continue;
            }

            standing++;
            left += opponent.getHealth();

            out[base + FOE_DAMAGE] = Math.max(out[base + FOE_DAMAGE],
                    attribute(opponent, Attributes.ATTACK_DAMAGE) / DAMAGE_SCALE);

            out[base + FOE_ARMOUR] = Math.max(out[base + FOE_ARMOUR], opponent.getArmorValue() / ARMOUR_SCALE);

            if (opponent instanceof Creeper creeper) {

                out[base + FOE_FUSE] = Math.max(out[base + FOE_FUSE], creeper.getSwelling(1.0F));
            }

            if (Allegiance.goesFor(opponent, agent)) {

                out[base + WENT_FOR] = 1.0F;
            }
        }

        out[base + FOES] = standing / FOE_SCALE;
        out[base + FOE_HEALTH] = whole > 0.0F ? left / whole : 0.0F;
        out[base + FOE_HEARTS] = whole / HEALTH_SCALE;
    }

    /** An attribute's value, or nought where the body has no such attribute at all, which asking outright would throw on. */
    private static float attribute(LivingEntity living, Holder<Attribute> attribute) {

        return living.getAttributes().hasAttribute(attribute) ? (float) living.getAttributeValue(attribute) : 0.0F;
    }
}
