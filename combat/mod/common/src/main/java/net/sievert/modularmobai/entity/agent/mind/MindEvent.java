package net.sievert.modularmobai.entity.agent.mind;

import java.util.Locale;

import org.jetbrains.annotations.Nullable;

/**
 * <b>The event table.</b> Every state change an event causes is here and applied by {@link Events}, so the whole social
 * rule set is readable on one screen — which is the property the sim was built around and the one thing about it worth
 * keeping above all others.
 *
 * <p>It is {@code EVENT_TABLE} in {@code mind/dwarfsim/mind.py}, number for number, and the generated table in
 * {@code mind/docs/design.md} is the same numbers again. A row is six things:
 *
 * <ul>
 *   <li><b>target</b> — what whoever it was done to feels;
 *   <li><b>toward the actor</b> — how the target's view of whoever did it moves;
 *   <li><b>witness</b> — what everyone else who saw it feels;
 *   <li><b>witness toward the actor</b> — and how their view of the actor moves;
 *   <li><b>actor</b> — what whoever did it feels;
 *   <li><b>actor toward the target</b> — and how their view of the one they did it to moves.
 * </ul>
 *
 * <p>Three rules sit on top of the numbers and live in {@link Events}: anger gains scale with the receiver's temper and
 * fear gains with its cowardice, witness deltas are cut to a fraction and then scaled by how much that witness liked the
 * victim, and everything is multiplied by the caller's magnitude — a shouted threat lands about four times as hard as a
 * muttered one.
 *
 * <p><b>Which rows are here.</b> The thirteen the core ring needs: the twelve the port's stage B names plus
 * {@link #SMALLTALK}, which is what most of the interpreter's intents become. The sim's further rows — {@code IGNORE},
 * {@code RETORT}, {@code DEMAND}, {@code COMPLAIN}, {@code GOSSIP}, {@code AVOID}, {@code ACCEPT}, {@code REFUSE},
 * {@code BARGAIN}, {@code PROMISE_KEPT}, {@code BROKEN_PROMISE}, {@code PUNISH} — are things an agent <em>does</em>
 * rather than things that happen to it, so each of them arrives with the skill that performs it, which is stage C.
 * Adding one is one line here.
 */
public enum MindEvent {

    // The columns, in order: memory weight, then target / target toward actor, witness / witness toward actor,
    // actor / actor toward target. A zero means the table has nothing to say, not that something was left out.

    INSULT(0.50F,
            feel(+0.26F, 0.0F, -0.10F, 0.0F), regard(-0.10F, -0.06F, +0.11F),
            feel(+0.03F, 0.0F, 0.0F, 0.0F), regard(-0.05F, -0.02F, +0.04F),
            feel(-0.04F, 0.0F, 0.0F, 0.0F), regard(0.0F, 0.0F, +0.03F)),

    // An insult aimed at what somebody is rather than at what they did. It lands harder on the one it was aimed at and
    // leaves a mark decay takes much longer to shift; the room takes it as it takes an insult, which is the equal witness
    // row. Speaking one is a public act: the speaker's own hatred hardens rather than venting.
    SLUR(0.85F,
            feel(+0.40F, 0.0F, -0.18F, +0.05F), regard(-0.18F, -0.12F, +0.22F),
            feel(+0.03F, 0.0F, 0.0F, 0.0F), regard(-0.05F, -0.02F, +0.04F),
            feel(-0.02F, 0.0F, 0.0F, 0.0F), regard(0.0F, 0.0F, +0.05F)),

    PRAISE(0.0F,
            feel(-0.07F, 0.0F, +0.16F, 0.0F), regard(+0.07F, +0.04F, -0.07F),
            feel(0.0F, 0.0F, +0.02F, 0.0F), regard(+0.03F, +0.02F, 0.0F),
            feel(0.0F, 0.0F, +0.04F, 0.0F), regard(+0.03F, 0.0F, 0.0F)),

    SMALLTALK(0.0F,
            feel(0.0F, 0.0F, +0.05F, 0.0F), regard(+0.03F, 0.0F, 0.0F),
            feel(0.0F, 0.0F, +0.01F, 0.0F), regard(+0.01F, 0.0F, 0.0F),
            feel(0.0F, 0.0F, +0.03F, 0.0F), regard(+0.02F, 0.0F, 0.0F)),

    THREAT(0.70F,
            feel(+0.14F, +0.22F, 0.0F, 0.0F), regard(-0.14F, +0.04F, +0.09F),
            feel(0.0F, +0.08F, 0.0F, 0.0F), regard(-0.07F, +0.03F, +0.04F),
            feel(-0.02F, 0.0F, 0.0F, 0.0F), regard(0.0F, 0.0F, 0.0F)),

    ACCUSE(0.50F,
            feel(+0.17F, 0.0F, -0.05F, 0.0F), regard(-0.09F, 0.0F, +0.06F),
            feel(0.0F, 0.0F, 0.0F, 0.0F), regard(-0.04F, 0.0F, +0.02F),
            feel(+0.03F, 0.0F, 0.0F, 0.0F), regard(-0.05F, 0.0F, 0.0F)),

    APOLOGY(0.0F,
            feel(-0.22F, 0.0F, +0.07F, 0.0F), regard(+0.11F, -0.02F, -0.10F),
            feel(0.0F, 0.0F, 0.0F, 0.0F), regard(+0.03F, -0.01F, 0.0F),
            feel(-0.10F, 0.0F, +0.02F, 0.0F), regard(0.0F, 0.0F, -0.06F)),

    GIFT(0.80F,
            feel(-0.06F, 0.0F, +0.14F, 0.0F), regard(+0.16F, +0.04F, -0.08F),
            feel(0.0F, 0.0F, +0.02F, 0.0F), regard(+0.05F, +0.03F, 0.0F),
            feel(0.0F, 0.0F, +0.03F, 0.0F), regard(+0.04F, 0.0F, 0.0F)),

    STEAL(0.90F,
            feel(+0.34F, 0.0F, -0.08F, 0.0F), regard(-0.30F, -0.05F, +0.18F),
            feel(+0.05F, 0.0F, 0.0F, 0.0F), regard(-0.16F, -0.06F, +0.08F),
            feel(0.0F, +0.06F, +0.05F, 0.0F), regard(0.0F, 0.0F, 0.0F)),

    HIT(1.00F,
            feel(+0.33F, +0.26F, 0.0F, 0.0F), regard(-0.24F, +0.05F, +0.25F),
            feel(+0.03F, +0.11F, 0.0F, 0.0F), regard(-0.11F, +0.04F, +0.07F),
            feel(+0.04F, +0.03F, 0.0F, 0.0F), regard(0.0F, 0.0F, +0.05F)),

    // The dead take no deltas. Witnesses get the feelings below and a relationship shift toward the killer computed from
    // how they felt about the dead; see Events#killWitness.
    KILL(1.00F,
            feel(0.0F, 0.0F, 0.0F, 0.0F), regard(0.0F, 0.0F, 0.0F),
            feel(0.0F, +0.38F, -0.20F, +0.42F), regard(0.0F, 0.0F, 0.0F),
            feel(-0.15F, +0.10F, 0.0F, +0.08F), regard(0.0F, 0.0F, 0.0F)),

    // Somebody fought what was fighting you.
    HELP(0.70F,
            feel(0.0F, -0.10F, +0.08F, 0.0F), regard(+0.08F, +0.14F, -0.05F),
            feel(0.0F, -0.06F, +0.05F, 0.0F), regard(+0.06F, +0.14F, -0.04F),
            feel(0.0F, 0.0F, +0.06F, 0.0F), regard(0.0F, 0.0F, 0.0F)),

    WARNING(0.0F,
            feel(0.0F, +0.13F, 0.0F, 0.0F), regard(+0.04F, +0.02F, 0.0F),
            feel(0.0F, +0.06F, 0.0F, 0.0F), regard(+0.02F, 0.0F, 0.0F),
            feel(0.0F, 0.0F, 0.0F, 0.0F), regard(0.0F, 0.0F, 0.0F));

    public static final MindEvent[] ALL = values();

    /**
     * How loud this is when it is filed away, before the event's own magnitude scales it. Zero means it is not
     * remembered at all: praise, apologies and small talk are cheap and constant, and letting them accumulate turned
     * gratitude into a number that was always one. {@code MEMORY_WEIGHT} in {@code dwarfsim/world.py}.
     */
    private final float memoryWeight;

    private final float[] target;
    private final float[] targetToward;
    private final float[] witness;
    private final float[] witnessToward;
    private final float[] actor;
    private final float[] actorToward;

    MindEvent(float memoryWeight, float[] target, float[] targetToward, float[] witness, float[] witnessToward,
            float[] actor, float[] actorToward) {

        this.memoryWeight = memoryWeight;
        this.target = target;
        this.targetToward = targetToward;
        this.witness = witness;
        this.witnessToward = witnessToward;
        this.actor = actor;
        this.actorToward = actorToward;
    }

    public float memoryWeight() {

        return this.memoryWeight;
    }

    /** Whether anyone files this away at all; see {@link #memoryWeight}. */
    public boolean isRemembered() {

        return this.memoryWeight > 0.0F;
    }

    float[] target() {

        return this.target;
    }

    float[] targetToward() {

        return this.targetToward;
    }

    float[] witness() {

        return this.witness;
    }

    float[] witnessToward() {

        return this.witnessToward;
    }

    float[] actor() {

        return this.actor;
    }

    float[] actorToward() {

        return this.actorToward;
    }

    /**
     * Whether being on the wrong end of this is a harm, which is what a grudge is summed out of. {@code HARM_KINDS} in
     * {@code dwarfsim/memory.py}, narrowed to the rows that exist here.
     */
    public boolean isHarm() {

        return this == INSULT || this == SLUR || this == ACCUSE || this == THREAT || this == STEAL || this == HIT
                || this == KILL;
    }

    /** And whether it is a kindness, which is what gratitude is summed out of. {@code HELP_KINDS}. */
    public boolean isKindness() {

        return this == HELP || this == GIFT;
    }

    public String key() {

        return name();
    }

    /** The event of that name, however it is cased, or null for a name that is not one. */
    @Nullable
    public static MindEvent byName(String name) {

        for (MindEvent kind : ALL) {

            if (kind.name().equalsIgnoreCase(name.trim())) {

                return kind;
            }
        }

        return null;
    }

    @Override
    public String toString() {

        return name().toLowerCase(Locale.ROOT);
    }

    /** One row of feelings, in {@link Emotion}'s order, which is the observation's own. */
    private static float[] feel(float anger, float fear, float happiness, float grief) {

        return new float[] {anger, fear, happiness, grief};
    }

    /** One row of regard, in {@link Relationship.Regard}'s order. */
    private static float[] regard(float trust, float respect, float hatred) {

        return new float[] {trust, respect, hatred};
    }
}
