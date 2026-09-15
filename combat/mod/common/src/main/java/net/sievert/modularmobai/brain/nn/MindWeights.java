package net.sievert.modularmobai.brain.nn;

import java.util.Locale;

/**
 * One loaded weight file that is not the combat mod's recurrent actor: the mind's models, and anything else the format
 * grows a kind for.
 *
 * <p>The actor has {@link WeightSet}, whose {@link Topology} is the only shape it can have. These have a kind word and
 * ten shape words instead, and what those words mean is the kind's business — {@link ScorerShape} and
 * {@link ClassifierShape} read them. Immutable and shared by reference, like a {@code WeightSet}: nothing at runtime
 * writes to {@code params}.
 *
 * @param id       where the weights came from, for logs
 * @param kind     {@link WeightFile#KIND_SCORER} or {@link WeightFile#KIND_CLASSIFIER}
 * @param schemaId the layout these weights were trained against; for a mind model, the first four bytes of its
 *                 {@code layout.json}'s sha256, read big endian
 * @param words    the header's ten shape words, whatever this kind means by them
 * @param params   the parameters, in the shape's segment order
 */
public record MindWeights(String id, int kind, int schemaId, int iteration, int[] words, float[] params) {

    public MindWeights {

        words = words.clone();
    }

    @Override
    public int[] words() {

        return this.words.clone();
    }

    public ScorerShape scorer() {

        return ScorerShape.of(this.expect(WeightFile.KIND_SCORER));
    }

    public ClassifierShape classifier() {

        return ClassifierShape.of(this.expect(WeightFile.KIND_CLASSIFIER));
    }

    private int[] expect(int kind) {

        if (this.kind != kind) {

            throw new IllegalStateException(String.format(Locale.ROOT, "%s is a kind %d weight file, asked for as a "
                    + "kind %d one", this.id, this.kind, kind));
        }

        return this.words;
    }
}
