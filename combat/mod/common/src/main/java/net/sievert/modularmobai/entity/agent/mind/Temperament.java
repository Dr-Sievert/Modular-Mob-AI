package net.sievert.modularmobai.entity.agent.mind;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.jetbrains.annotations.Nullable;

/**
 * A named set of traits, for when an agent is supposed to be somebody in particular.
 *
 * <p>An agent rolls its own traits from its random at spawn, which is what makes a crowd of them a crowd of individuals.
 * A preset is the other case: a guard who has to be brave, a bully who has to be quick tempered, a test that has to be
 * able to say what it is about. It is a whole set and not a nudge, so naming one is the whole of the answer and nothing
 * is left to the roll.
 *
 * <p>The columns are {@link Trait#ALL}'s own order: bravery, greed, temper, sociability, pride, forgiveness, loyalty,
 * suspicion. Adding a temperament is one line here and nothing else anywhere.
 */
public enum Temperament {

    //            bravery  greed  temper  social  pride  forgive  loyal  suspic
    EVEN(            0.50F, 0.50F,  0.50F,  0.50F, 0.50F,   0.50F, 0.50F, 0.50F),
    HOTHEAD(         0.70F, 0.50F,  0.90F,  0.45F, 0.75F,   0.15F, 0.40F, 0.60F),
    COWARD(          0.10F, 0.55F,  0.30F,  0.40F, 0.30F,   0.60F, 0.25F, 0.75F),
    STOIC(           0.75F, 0.30F,  0.20F,  0.35F, 0.40F,   0.80F, 0.70F, 0.35F),
    GUARD(           0.85F, 0.25F,  0.45F,  0.50F, 0.55F,   0.55F, 0.90F, 0.55F),
    MISER(           0.40F, 0.90F,  0.55F,  0.30F, 0.50F,   0.30F, 0.30F, 0.80F),
    FRIEND(          0.55F, 0.35F,  0.30F,  0.90F, 0.35F,   0.85F, 0.80F, 0.20F);

    public static final Temperament[] ALL = values();

    private final float[] traits;

    Temperament(float... traits) {

        if (traits.length != Trait.COUNT) {

            throw new IllegalStateException("A temperament is one value per trait: " + Trait.COUNT + ", not " + traits.length);
        }

        this.traits = traits;
    }

    /** Writes this temperament over whatever the agent had rolled. */
    void apply(float[] into) {

        System.arraycopy(this.traits, 0, into, 0, Trait.COUNT);
    }

    public float of(Trait trait) {

        return this.traits[trait.ordinal()];
    }

    /** The temperament of that name, however it is cased, or null for a name that is not one. */
    @Nullable
    public static Temperament byName(String name) {

        for (Temperament preset : ALL) {

            if (preset.name().equalsIgnoreCase(name.trim())) {

                return preset;
            }
        }

        return null;
    }

    /** Every name one can be asked for by, lower case, for a command's suggestions and for a refusal that says what there is. */
    public static List<String> names() {

        List<String> names = new ArrayList<>(ALL.length);

        for (Temperament preset : ALL) {

            names.add(preset.name().toLowerCase(Locale.ROOT));
        }

        return names;
    }
}
