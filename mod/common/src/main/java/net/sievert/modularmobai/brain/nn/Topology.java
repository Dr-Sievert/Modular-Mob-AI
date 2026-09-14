package net.sievert.modularmobai.brain.nn;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;

/**
 * The shape of the network, and where every parameter sits in the one flat array that holds them all.
 *
 * <pre>
 *   obs[obsDim] -> normalise -> [the attention over the enemy slots, when there is any] -> Linear fc1In to h1, ReLU
 *               -> GRU cell h1 to hidden -> Linear hidden to h3, ReLU
 *               -> Linear h3 to outDim, the raw logits the heads squash
 * </pre>
 *
 * <p>{@link #fc1In()} is the whole observation for an ordinary network, and for an {@link #attended()} one it is everything
 * outside the enemy slots plus the {@code slotHeads} slot-wide rows the heads pick out in their place.
 *
 * <p>The runtime reads its dimensions from here rather than from constants, so a wider or narrower network is a new
 * weight file and not a code change. Only one topology is live at a time, by policy; nothing in the code needs that.
 *
 * <p>The parameters are one array in a fixed segment order rather than an object graph: one allocation, one contiguous
 * stream through the cache, and byte for byte what the training side exports. Every matrix is row major, {@code
 * [out][in]}, so one output's weights are contiguous, and that is also how PyTorch stores them, so neither side
 * transposes anything.
 *
 * <pre>
 *   normMean[obsDim]  normStd[obsDim]                  the observation normaliser the network was trained behind
 *   scoreW[slotHeads x slotStride]  scoreB[slotHeads]  only when attended(); what each head ranks the enemy slots by
 *   scoreEmpty[slotHeads]                              and what it scores the empty token at
 *   fc1W[h1 x fc1In]  fc1B[h1]
 *   gruWih[3H x h1]  gruBih[3H]  gruWhh[3H x H]  gruBhh[3H]   gates in PyTorch's order: reset, update, new
 *   fc2W[h3 x H]  fc2B[h3]
 *   outW[outDim x h3]  outB[outDim]
 *   logStd[stdDim]                                     the spread of the continuous heads while training
 * </pre>
 *
 * <p>The score parameters come before the first layer here because that is the order the pass applies them in, so the file
 * is read straight through without seeking.
 *
 * <p>The normaliser lives in here on purpose. A network trained on normalised inputs and run on raw ones does not fail,
 * it just plays badly, so the statistics travel with the weights they belong to and cannot be forgotten.
 */
public record Topology(int obsDim, int h1, int hidden, int h3, int outDim, int stdDim,
                       int slotAt, int slots, int slotStride, int slotHeads) {

    /** A network with a plain first layer over the whole observation, which is what every file before version 2 holds. */
    public Topology(int obsDim, int h1, int hidden, int h3, int outDim, int stdDim) {

        this(obsDim, h1, hidden, h3, outDim, stdDim, 0, 0, 0, 0);
    }

    public Topology {

        if (obsDim <= 0 || h1 <= 0 || hidden <= 0 || h3 <= 0 || outDim <= 0 || stdDim < 0) {

            throw new IllegalArgumentException("Not a usable topology: " + obsDim + ", " + h1 + ", " + hidden + ", "
                    + h3 + ", " + outDim + ", " + stdDim);
        }

        if (slotHeads < 0 || slots < 0 || slotStride < 0 || slotAt < 0
                || (slotHeads > 0 && (slots <= 0 || slotStride <= 0 || slotAt + slots * slotStride > obsDim))) {

            throw new IllegalArgumentException("Not a usable slot attention: " + slots + " slots of " + slotStride
                    + " at " + slotAt + " read by " + slotHeads + " heads, in an observation " + obsDim + " wide");
        }
    }

    /**
     * Whether the enemy slots are attended before the first layer, rather than handed to it one slot's worth of columns at
     * a time. {@code slotHeads} is how many of them a head picks out: each head scores every occupied slot, takes a
     * softmax over them and an empty token, and hands the first layer one slot-wide row. See {@link Forward} for the
     * arithmetic and the training side's Topology for why ten slots of their own columns had to go.
     */
    public boolean attended() {

        return this.slotHeads > 0;
    }

    /** What the first layer takes: the whole observation, or everything outside the slots plus the rows that replace them. */
    public int fc1In() {

        return this.attended() ? this.obsDim - this.slots * this.slotStride + this.slotHeads * this.slotStride
                : this.obsDim;
    }

    public int normMean() {

        return 0;
    }

    public int normStd() {

        return this.normMean() + this.obsDim;
    }

    /** What each head ranks the slots by, which comes before the first layer here because it does in the pass. */
    public int scoreW() {

        return this.normStd() + this.obsDim;
    }

    public int scoreB() {

        return this.scoreW() + this.slotHeads * this.slotStride;
    }

    /** What each head scores the empty token at: one number a head, and the whole of what "nothing left" is worth to it. */
    public int scoreEmpty() {

        return this.scoreB() + this.slotHeads;
    }

    public int fc1W() {

        return this.scoreEmpty() + this.slotHeads;
    }

    public int fc1B() {

        return this.fc1W() + this.h1 * this.fc1In();
    }

    public int gruWih() {

        return this.fc1B() + this.h1;
    }

    public int gruBih() {

        return this.gruWih() + 3 * this.hidden * this.h1;
    }

    public int gruWhh() {

        return this.gruBih() + 3 * this.hidden;
    }

    public int gruBhh() {

        return this.gruWhh() + 3 * this.hidden * this.hidden;
    }

    public int fc2W() {

        return this.gruBhh() + 3 * this.hidden;
    }

    public int fc2B() {

        return this.fc2W() + this.h3 * this.hidden;
    }

    public int outW() {

        return this.fc2B() + this.h3;
    }

    public int outB() {

        return this.outW() + this.outDim * this.h3;
    }

    public int logStd() {

        return this.outB() + this.outDim;
    }

    /** How many floats the whole parameter array holds. */
    public int size() {

        return this.logStd() + this.stdDim;
    }

    /**
     * A CRC32 of the dimensions as little endian integers. The training side works out the same number, and a weight file
     * whose stored hash disagrees with its own dimensions is corrupt rather than merely different.
     *
     * <p>A network that attends nothing hashes over the original six alone, exactly as it did before the other four
     * existed. Adding a shape the format can describe must not change the identity of a shape it already described, or
     * every network trained before this would be refused for no reason at all.
     */
    public int hash() {

        boolean attended = this.attended();

        ByteBuffer bytes = ByteBuffer.allocate(attended ? 40 : 24).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putInt(this.obsDim).putInt(this.h1).putInt(this.hidden).putInt(this.h3).putInt(this.outDim).putInt(this.stdDim);

        if (attended) {

            bytes.putInt(this.slotAt).putInt(this.slots).putInt(this.slotStride).putInt(this.slotHeads);
        }

        CRC32 crc = new CRC32();
        crc.update(bytes.array());
        return (int) crc.getValue();
    }

    @Override
    public String toString() {

        String slots = this.attended()
                ? this.slots + "x" + this.slotStride + " -> " + this.slotHeads + " attended, " : "";

        return this.obsDim + " -> " + slots + this.fc1In() + " -> " + this.h1 + " -> GRU " + this.hidden + " -> "
                + this.h3 + " -> " + this.outDim
                + " (" + String.format(java.util.Locale.ROOT, "%,d", this.size()) + " parameters)";
    }
}
