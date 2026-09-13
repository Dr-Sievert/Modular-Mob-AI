package net.sievert.modularmobai.brain.nn;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;

/**
 * The shape of the network, and where every parameter sits in the one flat array that holds them all.
 *
 * <pre>
 *   obs[obsDim] -> normalise -> [the slot encoder, when there is one] -> Linear fc1In to h1, ReLU
 *               -> GRU cell h1 to hidden -> Linear hidden to h3, ReLU
 *               -> Linear h3 to outDim, the raw logits the heads squash
 * </pre>
 *
 * <p>{@link #fc1In()} is the whole observation for an ordinary network, and for a {@link #pooled()} one it is everything
 * outside the enemy slots plus the {@code slotEnc} features that replace them.
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
 *   slotW[slotEnc x slotStride]  slotB[slotEnc]        only when pooled(); the shared encoder over the enemy slots
 *   fc1W[h1 x fc1In]  fc1B[h1]
 *   gruWih[3H x h1]  gruBih[3H]  gruWhh[3H x H]  gruBhh[3H]   gates in PyTorch's order: reset, update, new
 *   fc2W[h3 x H]  fc2B[h3]
 *   outW[outDim x h3]  outB[outDim]
 *   logStd[stdDim]                                     the spread of the continuous heads while training
 * </pre>
 *
 * <p>The slot encoder comes before the first layer here because that is the order the pass applies it in, so the file is
 * read straight through without seeking.
 *
 * <p>The normaliser lives in here on purpose. A network trained on normalised inputs and run on raw ones does not fail,
 * it just plays badly, so the statistics travel with the weights they belong to and cannot be forgotten.
 */
public record Topology(int obsDim, int h1, int hidden, int h3, int outDim, int stdDim,
                       int slotAt, int slots, int slotStride, int slotEnc) {

    /** A network with a plain first layer over the whole observation, which is what every file before version 2 holds. */
    public Topology(int obsDim, int h1, int hidden, int h3, int outDim, int stdDim) {

        this(obsDim, h1, hidden, h3, outDim, stdDim, 0, 0, 0, 0);
    }

    public Topology {

        if (obsDim <= 0 || h1 <= 0 || hidden <= 0 || h3 <= 0 || outDim <= 0 || stdDim < 0) {

            throw new IllegalArgumentException("Not a usable topology: " + obsDim + ", " + h1 + ", " + hidden + ", "
                    + h3 + ", " + outDim + ", " + stdDim);
        }

        if (slotEnc < 0 || slots < 0 || slotStride < 0 || slotAt < 0
                || (slotEnc > 0 && (slots <= 0 || slotStride <= 0 || slotAt + slots * slotStride > obsDim))) {

            throw new IllegalArgumentException("Not a usable slot encoder: " + slots + " slots of " + slotStride
                    + " at " + slotAt + " into " + slotEnc + ", in an observation " + obsDim + " wide");
        }
    }

    /**
     * Whether the enemy slots are encoded and pooled before the first layer, rather than handed to it one number at a
     * time. See the training side's Topology for why one shared matrix over ten slots is worth a change to the format.
     */
    public boolean pooled() {

        return this.slotEnc > 0;
    }

    /** What the first layer takes: the whole observation, or everything outside the slots plus the features that replace them. */
    public int fc1In() {

        return this.pooled() ? this.obsDim - this.slots * this.slotStride + this.slotEnc : this.obsDim;
    }

    public int normMean() {

        return 0;
    }

    public int normStd() {

        return this.normMean() + this.obsDim;
    }

    /** The shared encoder over the slots, which comes before the first layer here because it does in the pass. */
    public int slotW() {

        return this.normStd() + this.obsDim;
    }

    public int slotB() {

        return this.slotW() + this.slotEnc * this.slotStride;
    }

    public int fc1W() {

        return this.slotB() + this.slotEnc;
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

    /** Multiply adds per agent per tick, which is what the tick budget is spent on. */
    public long macsPerStep() {

        return (long) this.slots * this.slotStride * this.slotEnc
                + (long) this.fc1In() * this.h1
                + 3L * this.hidden * this.h1
                + 3L * this.hidden * this.hidden
                + (long) this.hidden * this.h3
                + (long) this.h3 * this.outDim;
    }

    /**
     * A CRC32 of the dimensions as little endian integers. The training side works out the same number, and a weight file
     * whose stored hash disagrees with its own dimensions is corrupt rather than merely different.
     *
     * <p>A network with no slot encoder hashes over the original six alone, exactly as it did before the other four
     * existed. Adding a shape the format can describe must not change the identity of a shape it already described, or
     * every network trained before this would be refused for no reason at all.
     */
    public int hash() {

        boolean pooled = this.pooled();

        ByteBuffer bytes = ByteBuffer.allocate(pooled ? 40 : 24).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putInt(this.obsDim).putInt(this.h1).putInt(this.hidden).putInt(this.h3).putInt(this.outDim).putInt(this.stdDim);

        if (pooled) {

            bytes.putInt(this.slotAt).putInt(this.slots).putInt(this.slotStride).putInt(this.slotEnc);
        }

        CRC32 crc = new CRC32();
        crc.update(bytes.array());
        return (int) crc.getValue();
    }

    @Override
    public String toString() {

        String slots = this.pooled() ? this.slots + "x" + this.slotStride + " -> " + this.slotEnc + " pooled, " : "";

        return this.obsDim + " -> " + slots + this.fc1In() + " -> " + this.h1 + " -> GRU " + this.hidden + " -> "
                + this.h3 + " -> " + this.outDim
                + " (" + String.format(java.util.Locale.ROOT, "%,d", this.size()) + " parameters)";
    }
}
