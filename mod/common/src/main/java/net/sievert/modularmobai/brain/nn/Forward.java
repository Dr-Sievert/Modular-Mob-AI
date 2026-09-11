package net.sievert.modularmobai.brain.nn;

/**
 * The forward pass, over a whole batch of agents that share one set of weights.
 *
 * <p>Plain loops over flat arrays. Dimensions come from the {@link Topology}, so a differently shaped network needs no
 * change here; the few percent that costs against constant loop bounds does not matter at this scale.
 *
 * <p>The batch loop sits inside each layer rather than outside the whole network. Each weight row is loaded once and
 * used for four agents at a time, which keeps the row in the first level cache and gives the processor four
 * independent sums to overlap. Every sum still runs in the same order, bias first and then input by input, whether an
 * agent lands in a block of four or in the remainder, so the result for one agent never depends on who it was batched
 * with. Rollout replay depends on that.
 *
 * <p>The GRU follows PyTorch's {@code nn.GRUCell} exactly, gates in the order reset, update, new:
 *
 * <pre>
 *   r  = sigmoid(W_ir x + b_ir + W_hr h + b_hr)
 *   z  = sigmoid(W_iz x + b_iz + W_hz h + b_hz)
 *   n  = tanh(W_in x + b_in + r * (W_hn h + b_hn))
 *   h' = (1 - z) * n + z * h
 * </pre>
 *
 * The reset gate multiplies the whole of {@code W_hn h + b_hn}, hidden bias included. Getting that wrong is the most
 * common port bug there is, and the parity test exists to catch it.
 */
public final class Forward {

    private Forward() {}

    /** Working space for one batch, grown when a batch outgrows it and reused for every tick after. */
    public static final class Scratch {

        private float[] input = new float[0];
        private float[] layer1 = new float[0];
        private float[] gatesIn = new float[0];
        private float[] gatesHidden = new float[0];
        private float[] layer3 = new float[0];

        private void ensure(Topology topology, int agents) {

            if (this.input.length >= agents * topology.obsDim() && this.layer1.length >= agents * topology.h1()
                    && this.gatesIn.length >= agents * 3 * topology.hidden() && this.layer3.length >= agents * topology.h3()) {

                return;
            }

            // Grown with headroom, so a batch that creeps up by one agent at a time does not reallocate every tick.
            int capacity = Math.max(16, agents + (agents >> 1));

            this.input = new float[capacity * topology.obsDim()];
            this.layer1 = new float[capacity * topology.h1()];
            this.gatesIn = new float[capacity * 3 * topology.hidden()];
            this.gatesHidden = new float[capacity * 3 * topology.hidden()];
            this.layer3 = new float[capacity * topology.h3()];
        }
    }

    /**
     * @param obs    {@code agents * obsDim} raw observations, agent by agent. Normalised on the way in, never written.
     * @param hidden {@code agents * hidden} recurrent state, read and then overwritten with the next state.
     * @param logits {@code agents * outDim}, filled with the raw head outputs. Squashing and masking come after.
     */
    public static void forward(WeightSet weights, float[] obs, float[] hidden, float[] logits, int agents, Scratch scratch) {

        if (agents <= 0) {

            return;
        }

        final Topology topology = weights.topology();
        final float[] w = weights.params();

        final int in = topology.obsDim();
        final int h1 = topology.h1();
        final int h = topology.hidden();
        final int h3 = topology.h3();
        final int out = topology.outDim();

        scratch.ensure(topology, agents);

        normalise(weights, obs, scratch.input, agents);

        linear(scratch.input, in, scratch.layer1, h1, w, topology.fc1W(), topology.fc1B(), in, h1, agents);
        relu(scratch.layer1, agents * h1);

        linear(scratch.layer1, h1, scratch.gatesIn, 3 * h, w, topology.gruWih(), topology.gruBih(), h1, 3 * h, agents);
        linear(hidden, h, scratch.gatesHidden, 3 * h, w, topology.gruWhh(), topology.gruBhh(), h, 3 * h, agents);

        final float[] gi = scratch.gatesIn;
        final float[] gh = scratch.gatesHidden;

        for (int agent = 0; agent < agents; agent++) {

            final int gates = agent * 3 * h;
            final int state = agent * h;

            for (int j = 0; j < h; j++) {

                float reset = sigmoid(gi[gates + j] + gh[gates + j]);
                float update = sigmoid(gi[gates + h + j] + gh[gates + h + j]);
                float candidate = tanh(gi[gates + 2 * h + j] + reset * gh[gates + 2 * h + j]);

                hidden[state + j] = (1.0F - update) * candidate + update * hidden[state + j];
            }
        }

        linear(hidden, h, scratch.layer3, h3, w, topology.fc2W(), topology.fc2B(), h, h3, agents);
        relu(scratch.layer3, agents * h3);

        linear(scratch.layer3, h3, logits, out, w, topology.outW(), topology.outB(), h3, out, agents);
    }

    /** {@code clamp((x - mean) / std, -clip, clip)}, the same operations in the same order as the training side. */
    private static void normalise(WeightSet weights, float[] obs, float[] into, int agents) {

        final Topology topology = weights.topology();
        final float[] w = weights.params();
        final int in = topology.obsDim();
        final int mean = topology.normMean();
        final int std = topology.normStd();
        final float clip = weights.obsClip();

        for (int agent = 0; agent < agents; agent++) {

            final int row = agent * in;

            for (int k = 0; k < in; k++) {

                float value = (obs[row + k] - w[mean + k]) / w[std + k];
                into[row + k] = value < -clip ? -clip : Math.min(value, clip);
            }
        }
    }

    /**
     * {@code dst[agent][j] = bias[j] + sum over k of w[j][k] * src[agent][k]}, for every agent in the batch.
     *
     * @param srcStride how far apart consecutive agents' inputs are in {@code src}
     * @param dstStride how far apart consecutive agents' outputs are in {@code dst}
     */
    private static void linear(float[] src, int srcStride, float[] dst, int dstStride,
                               float[] w, int weights, int bias, int in, int out, int agents) {

        for (int j = 0; j < out; j++) {

            final int row = weights + j * in;
            final float b = w[bias + j];

            int agent = 0;

            for (; agent + 4 <= agents; agent += 4) {

                final int s0 = agent * srcStride;
                final int s1 = s0 + srcStride;
                final int s2 = s1 + srcStride;
                final int s3 = s2 + srcStride;

                float a0 = b;
                float a1 = b;
                float a2 = b;
                float a3 = b;

                for (int k = 0; k < in; k++) {

                    final float weight = w[row + k];

                    a0 += weight * src[s0 + k];
                    a1 += weight * src[s1 + k];
                    a2 += weight * src[s2 + k];
                    a3 += weight * src[s3 + k];
                }

                dst[agent * dstStride + j] = a0;
                dst[(agent + 1) * dstStride + j] = a1;
                dst[(agent + 2) * dstStride + j] = a2;
                dst[(agent + 3) * dstStride + j] = a3;
            }

            for (; agent < agents; agent++) {

                final int s = agent * srcStride;
                float a = b;

                for (int k = 0; k < in; k++) {

                    a += w[row + k] * src[s + k];
                }

                dst[agent * dstStride + j] = a;
            }
        }
    }

    private static void relu(float[] values, int count) {

        for (int index = 0; index < count; index++) {

            if (values[index] < 0.0F) {

                values[index] = 0.0F;
            }
        }
    }

    static float sigmoid(float x) {

        return 1.0F / (1.0F + (float) Math.exp(-x));
    }

    static float tanh(float x) {

        return (float) Math.tanh(x);
    }
}
