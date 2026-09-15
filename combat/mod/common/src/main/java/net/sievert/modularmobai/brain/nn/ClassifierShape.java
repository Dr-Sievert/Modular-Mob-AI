package net.sievert.modularmobai.brain.nn;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.zip.CRC32;

/**
 * The shape of an embedding-bag classifier, {@link WeightFile#KIND_CLASSIFIER}: a hashed bag of features summed out of one
 * big embedding, a dense side vector concatenated onto it, one hidden layer, and any number of linear heads over that
 * hidden layer.
 *
 * <pre>
 *   bag  = sum over the text's buckets of emb[bucket]        dim floats
 *   x    = concat(max(bag, 0), side)                         dim + side floats
 *   h    = max(x fc1W' + fc1B, 0)                            hidden floats
 *   head = h headW' + headB                                  one per head, its own width
 * </pre>
 *
 * <p>{@link Topology}'s conventions again: matrices row major {@code [out][in]}, segments in the order the pass applies
 * them, a CRC32 of the dimensions. The embedding is all of the parameters and is read one row at a time, so it stays part
 * of the one flat array and is indexed, never turned into rows of objects.
 *
 * <pre>
 *   emb[buckets x dim]
 *   fc1W[hidden x (dim + side)]  fc1B[hidden]
 *   headW[width x hidden]  headB[width]        for each head, in order
 * </pre>
 *
 * <p>The head <b>widths</b> are here because the parameter count depends on them and a file whose count disagrees with
 * its own shape has to be refused. What each head's outputs <i>mean</i> — the label lists — is layout and lives with the
 * schema, not here, exactly as an action head's names do.
 */
public record ClassifierShape(int buckets, int dim, int side, int hidden, int[] heads) {

    /** The shape words before the head widths: buckets, dim, side, hidden. */
    public static final int FIXED_WORDS = 4;

    public ClassifierShape {

        if (buckets <= 0 || (buckets & (buckets - 1)) != 0) {

            throw new IllegalArgumentException("A bucket count has to be a positive power of two, not " + buckets);
        }

        if (dim <= 0 || side < 0 || hidden <= 0 || heads.length == 0) {

            throw new IllegalArgumentException("Not a usable classifier: " + buckets + " buckets of " + dim + ", "
                    + side + " side columns, " + hidden + " hidden, " + heads.length + " heads");
        }

        heads = heads.clone();

        for (int width : heads) {

            if (width <= 0) {

                throw new IllegalArgumentException("A head of " + width + " outputs is not a head");
            }
        }
    }

    /**
     * The shape a weight file's header words describe: four fixed words, then the head widths, zero terminated. A header
     * has room for {@code words.length - FIXED_WORDS} heads and this build's five fit with one word to spare.
     */
    public static ClassifierShape of(int[] words) {

        int count = 0;

        while (FIXED_WORDS + count < words.length && words[FIXED_WORDS + count] > 0) {

            count++;
        }

        return new ClassifierShape(words[0], words[1], words[2], words[3],
                Arrays.copyOfRange(words, FIXED_WORDS, FIXED_WORDS + count));
    }

    /** How many of a header's shape words this shape means anything by, and so how many its hash covers. */
    public int words() {

        return FIXED_WORDS + this.heads.length;
    }

    @Override
    public int[] heads() {

        return this.heads.clone();
    }

    public int headCount() {

        return this.heads.length;
    }

    public int headWidth(int head) {

        return this.heads[head];
    }

    /** What the hidden layer takes: the embedding sum and the side vector, in that order. */
    public int fc1In() {

        return this.dim + this.side;
    }

    public int emb() {

        return 0;
    }

    public int fc1W() {

        return this.emb() + this.buckets * this.dim;
    }

    public int fc1B() {

        return this.fc1W() + this.hidden * this.fc1In();
    }

    public int headW(int head) {

        int at = this.fc1B() + this.hidden;

        for (int earlier = 0; earlier < head; earlier++) {

            at += this.heads[earlier] * this.hidden + this.heads[earlier];
        }

        return at;
    }

    public int headB(int head) {

        return this.headW(head) + this.heads[head] * this.hidden;
    }

    /** How many floats the whole parameter array holds. */
    public int size() {

        return this.headB(this.heads.length - 1) + this.heads[this.heads.length - 1];
    }

    /** A CRC32 of the dimensions as little endian integers, exactly as {@link Topology#hash()} does it. */
    public int hash() {

        ByteBuffer bytes = ByteBuffer.allocate(4 * this.words()).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putInt(this.buckets).putInt(this.dim).putInt(this.side).putInt(this.hidden);

        for (int width : this.heads) {

            bytes.putInt(width);
        }

        CRC32 crc = new CRC32();
        crc.update(bytes.array());
        return (int) crc.getValue();
    }

    @Override
    public boolean equals(Object other) {

        return other instanceof ClassifierShape shape && shape.buckets == this.buckets && shape.dim == this.dim
                && shape.side == this.side && shape.hidden == this.hidden && Arrays.equals(shape.heads, this.heads);
    }

    @Override
    public int hashCode() {

        return ((this.buckets * 31 + this.dim) * 31 + this.side) * 31 + this.hidden + Arrays.hashCode(this.heads);
    }

    @Override
    public String toString() {

        return this.buckets + "x" + this.dim + " + " + this.side + " -> " + this.hidden + " -> "
                + Arrays.toString(this.heads)
                + " (" + String.format(java.util.Locale.ROOT, "%,d", this.size()) + " parameters)";
    }
}
