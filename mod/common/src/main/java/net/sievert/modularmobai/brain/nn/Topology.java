package net.sievert.modularmobai.brain.nn;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;

/**
 * The shape of the network, and where every parameter sits in the one flat array that holds them all.
 *
 * <pre>
 *   obs[obsDim] -> normalise -> Linear obsDim to h1, ReLU -> GRU cell h1 to hidden -> Linear hidden to h3, ReLU
 *               -> Linear h3 to outDim, the raw logits the heads squash
 * </pre>
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
 *   fc1W[h1 x obsDim]  fc1B[h1]
 *   gruWih[3H x h1]  gruBih[3H]  gruWhh[3H x H]  gruBhh[3H]   gates in PyTorch's order: reset, update, new
 *   fc2W[h3 x H]  fc2B[h3]
 *   outW[outDim x h3]  outB[outDim]
 *   logStd[stdDim]                                     the spread of the continuous heads while training
 * </pre>
 *
 * <p>The normaliser lives in here on purpose. A network trained on normalised inputs and run on raw ones does not fail,
 * it just plays badly, so the statistics travel with the weights they belong to and cannot be forgotten.
 */
public record Topology(int obsDim, int h1, int hidden, int h3, int outDim, int stdDim) {

    public Topology {

        if (obsDim <= 0 || h1 <= 0 || hidden <= 0 || h3 <= 0 || outDim <= 0 || stdDim < 0) {

            throw new IllegalArgumentException("Not a usable topology: " + obsDim + ", " + h1 + ", " + hidden + ", "
                    + h3 + ", " + outDim + ", " + stdDim);
        }
    }

    public int normMean() {

        return 0;
    }

    public int normStd() {

        return this.normMean() + this.obsDim;
    }

    public int fc1W() {

        return this.normStd() + this.obsDim;
    }

    public int fc1B() {

        return this.fc1W() + this.h1 * this.obsDim;
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

        return (long) this.obsDim * this.h1
                + 3L * this.hidden * this.h1
                + 3L * this.hidden * this.hidden
                + (long) this.hidden * this.h3
                + (long) this.h3 * this.outDim;
    }

    /**
     * A CRC32 of the six dimensions as little endian integers. The training side works out the same number, and a weight
     * file whose stored hash disagrees with its own dimensions is corrupt rather than merely different.
     */
    public int hash() {

        ByteBuffer bytes = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putInt(this.obsDim).putInt(this.h1).putInt(this.hidden).putInt(this.h3).putInt(this.outDim).putInt(this.stdDim);

        CRC32 crc = new CRC32();
        crc.update(bytes.array());
        return (int) crc.getValue();
    }

    @Override
    public String toString() {

        return this.obsDim + " -> " + this.h1 + " -> GRU " + this.hidden + " -> " + this.h3 + " -> " + this.outDim
                + " (" + String.format(java.util.Locale.ROOT, "%,d", this.size()) + " parameters)";
    }
}
