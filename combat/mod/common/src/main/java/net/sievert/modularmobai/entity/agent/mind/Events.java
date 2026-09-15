package net.sievert.modularmobai.entity.agent.mind;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.sievert.modularmobai.allegiance.Allegiance;
import net.sievert.modularmobai.brain.schema.EnemySlots;
import net.sievert.modularmobai.brain.schema.ObservationSchema;
import net.sievert.modularmobai.entity.agent.AgentMob;

/**
 * What happens to an agent, turned into the deltas {@link MindEvent} says it causes.
 *
 * <p>One entry point, {@link #happened}: an event has an actor, the one it was done to, and everyone who saw it. Three
 * rules sit between the table's numbers and the state, and they are what make two agents react differently to the same
 * thing:
 *
 * <ul>
 *   <li><b>the receiver modulates it</b> — anger gains scale with temper and fear gains with cowardice, in
 *       {@link MindState#feel}, and a relationship change away from neutral is damped by the headroom left, in
 *       {@link Relationship#move};
 *   <li><b>a witness reacts by a fraction</b> — every witness delta is cut to {@link #WITNESS_FRACTION} and then
 *       multiplied by {@code 1 + likes(witness -> victim)}, so a friend of the victim reacts up to twice as hard and
 *       somebody who hated the victim barely reacts at all;
 *   <li><b>a kill makes everyone take a side</b> — {@link MindEvent#KILL} has no witness relationship row in the table
 *       because it is computed from how the witness felt about the dead.
 * </ul>
 *
 * <p><b>Who saw it.</b> A witness is an agent that <em>perceives</em> the actor, by the very rule the combat view
 * perceives a body: seen in the cone with a line of sight, heard within six blocks all round, or felt because it just
 * hit them. {@link EnemySlots#perceives} is that rule and this asks it, so there is one answer in the mod to "can that
 * agent tell what is going on over there" rather than two that can drift apart. A training agent is never a witness and
 * never a party; see {@link MindState#isAwake}.
 *
 * <p><b>What feeds each event today.</b> {@code HIT} and {@code KILL} come from the body itself, {@code AgentMob}'s own
 * {@code actuallyHurt} and {@code die}. {@code HELP} comes from an ally landing a blow on whatever is fighting the
 * agent, out of {@code LivingEntityMixin}, which is where every blow in the game already passes through. {@code GIFT}
 * comes from a stack handed over and picked up. The six that are speech — {@code INSULT}, {@code SLUR}, {@code PRAISE},
 * {@code SMALLTALK}, {@code THREAT}, {@code ACCUSE}, {@code APOLOGY}, {@code WARNING} — arrive through {@link Speech},
 * which stage C's chat hook calls with the interpreter's answer. {@code STEAL} has nothing to feed it until the mod has
 * a notion of an agent's property; that is the one hook stage B leaves open on purpose.
 */
public final class Events {

    /** How much of a delta a witness takes before sympathy scales it. */
    public static final float WITNESS_FRACTION = 0.45F;

    /** How a witness's view of the killer moves per point of how much they liked the dead, in {@code Regard} order. */
    private static final float[] KILL_WITNESS_LIKED = {-0.30F, +0.10F, +0.35F};
    private static final float[] KILL_WITNESS_HATED = {-0.05F, +0.30F, -0.10F};

    /** A witness remembers an episode less loudly than the one it happened to. */
    private static final float WITNESS_MEMORY = MemoryBook.WITNESS_SHARE;

    private Events() {}

    /** Which end of an event a mind is on. */
    public enum Role {

        TARGET,
        ACTOR,
        WITNESS
    }

    /**
     * One event, applied to everyone it reaches: whoever it was done to, whoever did it, and every agent that could see
     * it happen.
     *
     * @param kind      the row of the table
     * @param actor     whoever did it, or null for the world itself
     * @param target    whoever it was done to, or null for something done to nobody in particular
     * @param magnitude how hard it lands; 1 is the table as written, and speech passes its aggression through here
     */
    public static void happened(MindEvent kind, @Nullable LivingEntity actor, @Nullable LivingEntity target,
            float magnitude) {

        LivingEntity where = actor != null ? actor : target;

        if (where == null || !(where.level() instanceof ServerLevel level)) {

            return;
        }

        UUID actorId = actor == null ? null : actor.getUUID();
        UUID targetId = target == null ? null : target.getUUID();

        // The one it was done to, and then whoever did it. The dead take nothing, which is the KILL row saying so.
        if (target instanceof AgentMob hurt && hurt.mind().isAwake() && hurt.isAlive()) {

            apply(hurt.mind(), kind, Role.TARGET, actorId, targetId, magnitude);
        }

        if (actor instanceof AgentMob did && did.mind().isAwake() && did.isAlive()) {

            apply(did.mind(), kind, Role.ACTOR, actorId, targetId, magnitude);
        }

        List<AgentMob> witnesses = witnesses(level, where, actor, target);

        for (AgentMob watching : witnesses) {

            apply(watching.mind(), kind, Role.WITNESS, actorId, targetId, magnitude);
        }

        remember(kind, actorId, targetId, magnitude, target, witnesses);
    }

    /**
     * Applies one row of the table to one mind, in the role it is on. Public because {@link Speech} needs to land a line
     * on a single listener — an overheard one makes a witness of whoever heard it and not a target — and because a test
     * should be able to ask for exactly one half of an event.
     *
     * @param magnitude how hard the event lands; a witness's own fraction and its sympathy are worked out here, so a
     *                  caller never has to know either
     */
    public static void apply(MindState mind, MindEvent kind, Role role, @Nullable UUID actor, @Nullable UUID target,
            float magnitude) {

        if (!mind.isAwake()) {

            return;
        }

        float[] feelings;
        float[] regard;
        UUID toward;
        float scale = magnitude;

        switch (role) {

            case TARGET -> {

                feelings = kind.target();
                regard = kind.targetToward();
                toward = actor;
            }

            case ACTOR -> {

                feelings = kind.actor();
                regard = kind.actorToward();
                toward = target;
            }

            default -> {

                feelings = kind.witness();
                regard = kind.witnessToward();
                toward = actor;

                // A friend of the victim takes it harder; somebody who hated the victim shrugs.
                float sympathy = target == null ? 1.0F : Math.max(0.0F, 1.0F + mind.likes(target));

                scale = magnitude * WITNESS_FRACTION * sympathy;
            }
        }

        for (Emotion emotion : Emotion.ALL) {

            mind.feel(emotion, feelings[emotion.ordinal()] * scale);
        }

        if (toward == null || toward.equals(mind.owner())) {

            return;
        }

        // A kill has no witness relationship row in the table: which way it moves depends on how the witness felt about
        // the one who died, and by how much they felt it. The magnitude alone scales it — the table below is already a
        // witness's own reaction and is not cut a second time.
        if (kind == MindEvent.KILL && role == Role.WITNESS) {

            float liked = target == null ? 0.0F : mind.likes(target);
            float[] table = liked >= 0.0F ? KILL_WITNESS_LIKED : KILL_WITNESS_HATED;
            float weight = Math.min(1.0F, Math.abs(liked) + 0.25F);

            for (Relationship.Regard field : Relationship.Regard.ALL) {

                mind.regard(toward, field, table[field.ordinal()] * weight * magnitude);
            }

            return;
        }

        for (Relationship.Regard field : Relationship.Regard.ALL) {

            mind.regard(toward, field, regard[field.ordinal()] * scale);
        }
    }

    /**
     * Everybody who files this away: the one it was done to, loudly and as something suffered, and every witness more
     * quietly and as something seen. An event with no weight is not remembered by anyone, which is praise, apologies and
     * small talk — cheap, constant, and worthless as evidence.
     */
    private static void remember(MindEvent kind, @Nullable UUID actor, @Nullable UUID target, float magnitude,
            @Nullable LivingEntity targetEntity, List<AgentMob> witnesses) {

        if (!kind.isRemembered() || actor == null) {

            return;
        }

        float intensity = kind.memoryWeight() * magnitude;
        int others = witnesses.size();

        if (targetEntity instanceof AgentMob hurt && hurt.mind().isAwake() && !actor.equals(target)) {

            hurt.mind().remember(kind, actor, target, intensity, Memory.Source.SUFFERED, null, others);
        }

        for (AgentMob watching : witnesses) {

            watching.mind().remember(kind, actor, target, intensity * WITNESS_MEMORY, Memory.Source.SEEN, null, others);
        }
    }

    /**
     * Every agent that could see it happen, which is every awake agent within the view that perceives the actor — or the
     * one it was done to, where the world itself did it. One query of the level per event, over the box the view reaches
     * into, and events are rare: a blow, a death, a line of chat.
     */
    private static List<AgentMob> witnesses(ServerLevel level, LivingEntity where, @Nullable LivingEntity actor,
            @Nullable LivingEntity target) {

        AABB box = new AABB(where.position(), where.position()).inflate(ObservationSchema.VIEW_DISTANCE);
        Entity seen = actor != null ? actor : target;
        List<AgentMob> watching = new ArrayList<>();

        for (AgentMob agent : level.getEntitiesOfClass(AgentMob.class, box)) {

            if (agent == actor || agent == target || !agent.isAlive() || !agent.mind().isAwake()) {

                continue;
            }

            if (seen != null && !EnemySlots.perceives(agent, seen)) {

                continue;
            }

            watching.add(agent);
        }

        return watching;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // What feeds the table, from what the game already knows
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * The agent was struck. Called from {@code AgentMob#actuallyHurt}, which is where the health that actually got
     * through is known, so the blow lands on the mind as hard as it landed on the body.
     *
     * <p>The magnitude is the damage against a quarter of the agent's health: a blow that takes a quarter of the bar is
     * the table as written, a scratch is a fraction of it and a mauling is twice it. That is what makes a grudge scale
     * with what was done rather than with how many times it was done.
     */
    public static void struck(AgentMob agent, @Nullable Entity by, float damage) {

        if (!agent.mind().isAwake() || damage <= 0.0F) {

            return;
        }

        float quarter = Math.max(1.0F, agent.getMaxHealth() * 0.25F);
        float magnitude = Mth.clamp(damage / quarter, 0.15F, 2.0F);

        if (by instanceof LivingEntity hitter && hitter != agent) {

            agent.mind().struckBy(hitter.getUUID());
            happened(MindEvent.HIT, hitter, agent, magnitude);
            return;
        }

        // Fallen, drowned, burned: nobody did it, so there is nobody to blame and nothing to remember. The fear and the
        // anger are still the agent's, so the table's target row lands with no actor behind it.
        apply(agent.mind(), MindEvent.HIT, Role.TARGET, null, agent.getUUID(), magnitude);
    }

    /**
     * The agent died. The dead take no deltas — the KILL row says so — but everyone who saw it does, and they take a
     * side: whoever liked the one who died hates the killer for it, and whoever hated them respects the killer warily.
     */
    public static void died(AgentMob agent, @Nullable Entity by) {

        if (!agent.mind().isAwake()) {

            return;
        }

        happened(MindEvent.KILL, by instanceof LivingEntity killer ? killer : null, agent, 1.0F);
    }

    /**
     * A blow landed somewhere in the world. Two field reads on nearly every one of them, which is what this has to cost:
     * it is called from the one place every swing, arrow, bolt and sweep in the game arrives at.
     *
     * <p>What it is looking for is <b>help</b>: somebody hitting whatever is fighting an agent. The lookup is the cheap
     * way round — not "is there an agent near the victim", which would be a query of the world on every blow struck
     * anywhere, but "is the victim a mob whose target is an agent", which is a field. An ally of that agent landing the
     * blow is the help; the agent hitting back itself is not.
     */
    public static void blowLanded(LivingEntity hurt, @Nullable Entity by) {

        if (!(hurt instanceof Mob fighting) || !(by instanceof LivingEntity helper)) {

            return;
        }

        if (!(fighting.getTarget() instanceof AgentMob helped) || helped == helper) {

            return;
        }

        if (!helped.mind().isAwake() || !helped.isAlive() || !Allegiance.allied(helped, helper)) {

            return;
        }

        happened(MindEvent.HELP, helper, helped, 1.0F);
    }

    /**
     * A stack was handed over and taken. Called from {@code AgentMob#pickUpItem} for a drop that somebody threw, which
     * is what handing something to a mob looks like from inside the game: there is no other gesture for it.
     *
     * <p>What is deliberately not a gift is a drop nobody threw — an agent walking over what a zombie left behind owes
     * the zombie nothing — and what an agent dropped itself.
     */
    public static void given(AgentMob agent, @Nullable Entity from, int items) {

        if (!agent.mind().isAwake() || items <= 0 || !(from instanceof LivingEntity giver) || giver == agent) {

            return;
        }

        // A stack of one and a stack of sixty are not the same gift, but the difference is not sixty times: a gift is
        // mostly the gesture. Half again at a full stack.
        float magnitude = Mth.clamp(0.75F + items / 128.0F, 0.75F, 1.5F);

        happened(MindEvent.GIFT, giver, agent, magnitude);
    }
}
