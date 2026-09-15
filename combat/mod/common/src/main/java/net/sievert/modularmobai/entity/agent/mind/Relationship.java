package net.sievert.modularmobai.entity.agent.mind;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;

/**
 * How one agent feels about one other body: three numbers that move separately, and decay toward nothing.
 *
 * <ul>
 *   <li><b>trust</b>, -1 to 1 — would I leave my ore with them;
 *   <li><b>respect</b>, -1 to 1 — do they matter, are they dangerous, did they stand at the gate;
 *   <li><b>hatred</b>, 0 to 1 — would I swing at them.
 * </ul>
 *
 * <p>They are three and not one because they move in different directions at once: being hit costs trust <em>and</em>
 * buys respect, which is the whole reason a mob that beats an agent and then leaves it alone is treated warily rather
 * than simply hated. Straight out of {@code mind/dwarfsim/mind.py}.
 *
 * <p><b>Diminishing returns</b> are in {@link #move}, and they are not decoration: a change that pushes a number away
 * from neutral is scaled by the headroom left, so the hundredth kind word is worth almost nothing and nothing pins at 1
 * and stays there. A change back toward neutral is never damped. Without it the sim converged on everybody adoring
 * everybody inside three hundred ticks.
 */
public final class Relationship {

    /** The fraction shed each mind tick. A half-life of about 460 mind ticks: a grudge outlives the quarrel, not forever. */
    public static final float DECAY = 0.0015F;

    /** Under this, in every field, a row says nothing and is worth dropping rather than saving. */
    public static final float NEUTRAL = 0.002F;

    private float trust;
    private float respect;
    private float hatred;

    public float trust() {

        return this.trust;
    }

    public float respect() {

        return this.respect;
    }

    public float hatred() {

        return this.hatred;
    }

    /**
     * One number for "how well disposed am I toward them", -1 to 1, which is what scales a witness's reaction: a friend
     * of the victim takes an insult to them twice as hard, and somebody who hated the victim barely reacts at all.
     */
    public float likes() {

        return Mth.clamp(this.trust * 0.7F + this.respect * 0.3F - this.hatred, -1.0F, 1.0F);
    }

    /**
     * Moves one field by that much, damped by the headroom left when it pushes away from neutral.
     *
     * @return whether anything actually moved, which is what a delta log would report
     */
    public boolean move(Regard field, float amount) {

        if (amount == 0.0F) {

            return false;
        }

        float before = switch (field) {

            case TRUST -> this.trust;
            case RESPECT -> this.respect;
            case HATRED -> this.hatred;
        };

        // Away from neutral is damped by what is left; back toward it never is.
        float moved = (amount > 0.0F) == (before >= 0.0F) ? amount * Math.max(0.0F, 1.0F - Math.abs(before)) : amount;
        float after = field == Regard.HATRED ? Mth.clamp(before + moved, 0.0F, 1.0F) : Mth.clamp(before + moved, -1.0F, 1.0F);

        if (after == before) {

            return false;
        }

        switch (field) {

            case TRUST -> this.trust = after;
            case RESPECT -> this.respect = after;
            case HATRED -> this.hatred = after;
        }

        return true;
    }

    /** One mind tick of forgetting: every field a shade nearer nothing. */
    void decay() {

        this.trust *= 1.0F - DECAY;
        this.respect *= 1.0F - DECAY;
        this.hatred *= 1.0F - DECAY;
    }

    /** Whether this row now says nothing at all, and so is not worth a slot or a tag. */
    public boolean isNeutral() {

        return Math.abs(this.trust) < NEUTRAL && Math.abs(this.respect) < NEUTRAL && this.hatred < NEUTRAL;
    }

    /** How much this row wants a focus slot before the world has its say; see {@link Relationships#focus}. */
    float weight() {

        return this.hatred * 2.0F + Math.abs(this.trust) + Math.abs(this.respect) * 0.5F;
    }

    public CompoundTag save() {

        CompoundTag tag = new CompoundTag();

        tag.putFloat("Trust", this.trust);
        tag.putFloat("Respect", this.respect);
        tag.putFloat("Hatred", this.hatred);

        return tag;
    }

    public static Relationship load(CompoundTag tag) {

        Relationship row = new Relationship();

        row.trust = Mth.clamp(tag.getFloat("Trust"), -1.0F, 1.0F);
        row.respect = Mth.clamp(tag.getFloat("Respect"), -1.0F, 1.0F);
        row.hatred = Mth.clamp(tag.getFloat("Hatred"), 0.0F, 1.0F);

        return row;
    }

    @Override
    public String toString() {

        return String.format(java.util.Locale.ROOT, "trust %.2f respect %.2f hatred %.2f", this.trust, this.respect, this.hatred);
    }

    /** Which of the three a delta is about. The order is the focus slot's own, after its present flag. */
    public enum Regard {

        TRUST,
        RESPECT,
        HATRED;

        public static final Regard[] ALL = values();
    }
}
