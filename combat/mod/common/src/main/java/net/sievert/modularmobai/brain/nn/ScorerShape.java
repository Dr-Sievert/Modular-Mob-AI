package net.sievert.modularmobai.brain.nn;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;

/**
 * The shape of a plain feed-forward scorer, {@link WeightFile#KIND_SCORER}, and where every parameter sits in the one flat
 * array that holds them all.
 *
 * <pre>
 *   in[inDim] -> Linear inDim to h1, ReLU -> Linear h1 to h2, ReLU -> Linear h2 to outDim, the score
 * </pre>
 *
 * <p>{@link Topology}'s conventions, and only the ones that apply: matrices row major {@code [out][in]} so neither side
 * transposes anything, the segments in the order the pass applies them so the file is read straight through without
 * seeking, and a CRC32 of the dimensions that a corrupt header cannot agree with.
 *
 * <pre>
 *   fc1W[h1 x inDim]  fc1B[h1]  fc2W[h2 x h1]  fc2B[h2]  outW[outDim x h2]  outB[outDim]
 * </pre>
 *
 * <p>There is no normaliser and no recurrence, which is the whole reason this is a kind of its own rather than a
 * {@code Topology} with some of its segments empty: the mind's models are built on inputs that are already scaled, so
 * there are no statistics to travel with the weights and no hidden vector to carry between calls.
 */
public record ScorerShape(int inDim, int h1, int h2, int outDim) {

    /** How many of a header's shape words this kind means anything by. */
    public static final int WORDS = 4;

    public ScorerShape {

        if (inDim <= 0 || h1 <= 0 || h2 <= 0 || outDim <= 0) {

            throw new IllegalArgumentException("Not a usable scorer: " + inDim + " -> " + h1 + " -> " + h2 + " -> "
                    + outDim);
        }
    }

    /** The shape a weight file's header words describe. Words past {@link #WORDS} belong to other kinds and are ignored. */
    public static ScorerShape of(int[] words) {

        return new ScorerShape(words[0], words[1], words[2], words[3]);
    }

    public int fc1W() {

        return 0;
    }

    public int fc1B() {

        return this.fc1W() + this.h1 * this.inDim;
    }

    public int fc2W() {

        return this.fc1B() + this.h1;
    }

    public int fc2B() {

        return this.fc2W() + this.h2 * this.h1;
    }

    public int outW() {

        return this.fc2B() + this.h2;
    }

    public int outB() {

        return this.outW() + this.outDim * this.h2;
    }

    /** How many floats the whole parameter array holds. */
    public int size() {

        return this.outB() + this.outDim;
    }

    /** A CRC32 of the dimensions as little endian integers, exactly as {@link Topology#hash()} does it. */
    public int hash() {

        ByteBuffer bytes = ByteBuffer.allocate(4 * WORDS).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putInt(this.inDim).putInt(this.h1).putInt(this.h2).putInt(this.outDim);

        CRC32 crc = new CRC32();
        crc.update(bytes.array());
        return (int) crc.getValue();
    }

    @Override
    public String toString() {

        return this.inDim + " -> " + this.h1 + " -> " + this.h2 + " -> " + this.outDim
                + " (" + String.format(java.util.Locale.ROOT, "%,d", this.size()) + " parameters)";
    }
}
