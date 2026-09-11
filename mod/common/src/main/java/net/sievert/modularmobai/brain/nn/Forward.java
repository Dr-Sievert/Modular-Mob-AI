package net.sievert.modularmobai.brain.nn;

/**
 * The forward pass, over a whole batch of agents that share one set of weights.
 *
 * <p>Plain loops over flat arrays. Dimensions come from the {@link Topology}, so a differently shaped network needs no
 * change here; the few percent that costs against constant loop bounds does not matter at this scale.
 *
 * <p>Each layer runs one agent at a time and is written so the compiler can turn its innermost loop into vector
 * instructions, which is most of what a tick spends in here. Every weight matrix is held transposed, one array per
 * input holding that input's weight to every output, and the running sums of all the outputs sit in one array too, so
 * the innermost loop walks along a whole row of outputs at once: an input, times its weights, added into every sum.
 * Nothing in that loop reads what another step of it wrote, so it runs eight outputs at a time. Four inputs go through
 * per pass, so each sum is read and written a quarter as often.
 *
 * <p>None of that reorders a sum. Each output still starts from its bias and takes its inputs one by one, in order, a
 * multiply rounded and then an add rounded, which is exactly the arithmetic of one output at a time; the results are
 * the same to the last bit. The layout only has to be that awkward because the vectoriser in this Java only takes loops
 * whose arrays are all read from the same index: two arrays indexed from different offsets stay scalar. Agents go
 * through one at a time, so the result for one agent never depends on who it was batched with. Rollout replay depends
 * on that.
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

    /**
     * Working space for one batch, grown when a batch outgrows it and reused for every tick after, and the weights laid
     * out the way the loops read them.
     */
    public static final class Scratch {

        private float[] input = new float[0];
        private float[] layer1 = new float[0];
        private float[] gatesIn = new float[0];
        private float[] gatesHidden = new float[0];
        private float[] layer3 = new float[0];

        /** One agent's running sums for whichever layer is being worked out, as wide as the widest layer. */
        private float[] sums = new float[0];

        /**
         * Each weight matrix transposed, {@code [in][out]}: one array per input, holding its weight to every output. Laid
         * out from {@link #laidOut} and again whenever the weights are swapped, which in training is once an iteration: a
         * megabyte and a half copied, against the thousands of ticks that follow.
         */
        private float[][] fc1;
        private float[][] gruIh;
        private float[][] gruHh;
        private float[][] fc2;
        private float[][] head;
        private WeightSet laidOut;

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

        private void layOut(WeightSet weights) {

            if (this.laidOut == weights) {

                return;
            }

            Topology topology = weights.topology();
            float[] w = weights.params();

            this.fc1 = transpose(w, topology.fc1W(), topology.obsDim(), topology.h1());
            this.gruIh = transpose(w, topology.gruWih(), topology.h1(), 3 * topology.hidden());
            this.gruHh = transpose(w, topology.gruWhh(), topology.hidden(), 3 * topology.hidden());
            this.fc2 = transpose(w, topology.fc2W(), topology.hidden(), topology.h3());
            this.head = transpose(w, topology.outW(), topology.h3(), topology.outDim());

            this.sums = new float[Math.max(Math.max(topology.h1(), 3 * topology.hidden()), Math.max(topology.h3(), topology.outDim()))];
            this.laidOut = weights;
        }

        /** The row major {@code [out][in]} matrix at {@code from}, as one array per input. */
        private static float[][] transpose(float[] w, int from, int in, int out) {

            float[][] rows = new float[in][out];

            for (int j = 0; j < out; j++) {

                for (int k = 0; k < in; k++) {

                    rows[k][j] = w[from + j * in + k];
                }
            }

            return rows;
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
        scratch.layOut(weights);

        normalise(weights, obs, scratch.input, agents);

        linear(scratch.input, in, scratch.layer1, h1, scratch.fc1, w, topology.fc1B(), agents, scratch.sums);
        relu(scratch.layer1, agents * h1);

        linear(scratch.layer1, h1, scratch.gatesIn, 3 * h, scratch.gruIh, w, topology.gruBih(), agents, scratch.sums);
        linear(hidden, h, scratch.gatesHidden, 3 * h, scratch.gruHh, w, topology.gruBhh(), agents, scratch.sums);

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

        linear(hidden, h, scratch.layer3, h3, scratch.fc2, w, topology.fc2B(), agents, scratch.sums);
        relu(scratch.layer3, agents * h3);

        linear(scratch.layer3, h3, logits, out, scratch.head, w, topology.outB(), agents, scratch.sums);
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
     * @param rows      the weights transposed, one array per input
     * @param bias      where the layer's biases start in {@code params}
     * @param sums      at least as wide as the layer, overwritten
     */
    private static void linear(float[] src, int srcStride, float[] dst, int dstStride, float[][] rows,
                               float[] params, int bias, int agents, float[] sums) {

        final int in = rows.length;
        final int out = rows[0].length;

        for (int agent = 0; agent < agents; agent++) {

            final int s = agent * srcStride;

            System.arraycopy(params, bias, sums, 0, out);

            int k = 0;

            for (; k + 4 <= in; k += 4) {

                final float x0 = src[s + k];
                final float x1 = src[s + k + 1];
                final float x2 = src[s + k + 2];
                final float x3 = src[s + k + 3];

                final float[] w0 = rows[k];
                final float[] w1 = rows[k + 1];
                final float[] w2 = rows[k + 2];
                final float[] w3 = rows[k + 3];

                // Left to right, so each sum still takes these four inputs one after another.
                for (int j = 0; j < out; j++) {

                    sums[j] = sums[j] + w0[j] * x0 + w1[j] * x1 + w2[j] * x2 + w3[j] * x3;
                }
            }

            for (; k < in; k++) {

                final float x = src[s + k];
                final float[] weight = rows[k];

                for (int j = 0; j < out; j++) {

                    sums[j] += weight[j] * x;
                }
            }

            System.arraycopy(sums, 0, dst, agent * dstStride, out);
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
