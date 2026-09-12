package net.sievert.modularmobai.brain.nn;

/**
 * The forward pass, over a whole batch of agents that share one set of weights.
 *
 * <p>Plain loops over flat arrays. Dimensions come from the {@link Topology}, so a differently shaped network needs no
 * change here; the few percent that costs against constant loop bounds does not matter at this scale.
 *
 * <p>Each layer is written so the compiler can turn its innermost loop into vector instructions, which is most of what
 * a tick spends in here. Every weight matrix is held transposed, one array per input holding that input's weight to
 * every output, and an agent's running sums for all the outputs sit in one array too, so the innermost loop walks along
 * a whole row of outputs at once: an input, times its weights, added into every sum. Nothing in that loop reads what
 * another step of it wrote, so it runs eight outputs at a time. Four inputs go through per pass, so each sum is read and
 * written a quarter as often, and agents go through in pairs, so each weight read serves two of them.
 *
 * <p>None of that reorders a sum. Each output still starts from its bias and takes its inputs one by one, in order, a
 * multiply rounded and then an add rounded, which is exactly the arithmetic of one output at a time; the results are
 * the same to the last bit. The layout only has to be that awkward because the vectoriser in this Java only takes loops
 * whose arrays are all read from the same index: two arrays indexed from different offsets stay scalar. The two agents
 * of a pair share weights and nothing else, so the result for one agent never depends on who it was batched with, or
 * whether it was paired at all. Rollout replay depends on that.
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
 *
 * <p>The loops themselves come in two forms, and which one a process uses is settled once, when this class loads. The
 * plain ones below are written so that the compiler's own vectoriser can take their innermost loop, which it does; the
 * ones in {@link ForwardVectors} say the same arithmetic in explicit vector instructions, which is worth about 15% more
 * again. Those need {@code jdk.incubator.vector}, which the build adds wherever it compiles and runs workers, and which a
 * jar dropped into someone else's game will not have: there the class simply does not load and the plain loops run. The
 * choice is made in the static initialiser and never looked at again, so a tick pays nothing for it.
 *
 * <p>Both forms are bit for bit the same, not nearly the same, and the parity check proves it at every batch size from 1
 * to 64 rather than taking it on trust. A vector add is an add per lane, in the same order, and nothing is fused: the
 * measurements in findings.md say fusing the multiplies buys 3.5% and drifts the logits by 7e-3 relative, which is not a
 * trade anything here wants.
 */
public final class Forward {

    private Forward() {}

    /**
     * The loops each layer is made of, so that the explicit vector ones can be swapped in whole where the virtual machine
     * has them. Everything here writes only into the arrays it is handed, and every implementation has to give the same
     * bits as {@link #SCALAR}.
     */
    interface Loops {

        /** See {@link Forward#linear}. The two running sum arrays belong to the caller and are overwritten. */
        void linear(float[] src, int srcStride, float[] dst, int dstStride, float[][] rows, float[] params, int bias,
                    int agents, float[] first, float[] second);

        /** {@code values[i] = values[i] < 0 ? 0 : values[i]}, over the first {@code count} of them. */
        void relu(float[] values, int count);

        /** See {@link Forward#normalise}. */
        void normalise(float[] obs, float[] into, float[] params, int mean, int std, float clip, int in, int agents);
    }

    /** The plain loops, which every machine has and which every other form has to agree with. */
    private static final Loops SCALAR = new Loops() {

        @Override
        public void linear(float[] src, int srcStride, float[] dst, int dstStride, float[][] rows, float[] params,
                           int bias, int agents, float[] first, float[] second) {

            Forward.linear(src, srcStride, dst, dstStride, rows, params, bias, agents, first, second);
        }

        @Override
        public void relu(float[] values, int count) {

            Forward.relu(values, count);
        }

        @Override
        public void normalise(float[] obs, float[] into, float[] params, int mean, int std, float clip, int in, int agents) {

            Forward.normalise(obs, into, params, mean, std, clip, in, agents);
        }
    };

    /** The explicit vector loops where this virtual machine has the incubator module, the plain ones where it does not. */
    private static final Loops LOOPS = chooseLoops();

    /**
     * Looks for the explicit vector loops once, by name, and falls back to the plain ones on anything at all going wrong.
     * By name, because naming {@link ForwardVectors} in a field type or a {@code new} would have this class refuse to load
     * at all where the module is missing, and the whole point is that the mod still runs there. {@code ForwardVectors}
     * touches a vector species in its own static initialiser, so a missing module fails here rather than later.
     *
     * <p>{@code -Dmodular_mob_ai.brain.vectors=false} keeps the plain loops on a machine that does have the module, which
     * is how the two were measured against each other a round at a time, and is the way out if some virtual machine's own
     * vector support ever turns out to be wrong. Read here and nowhere else, so a tick never asks.
     */
    private static Loops chooseLoops() {

        if ("false".equalsIgnoreCase(System.getProperty("modular_mob_ai.brain.vectors", "true"))) {

            return SCALAR;
        }

        try {

            return (Loops) Class.forName(Forward.class.getPackageName() + ".ForwardVectors")
                    .getDeclaredConstructor().newInstance();
        }

        catch (Throwable ignored) {

            // A machine without jdk.incubator.vector on its module path, which is every game that did not start from this
            // build. Nothing is wrong; the arithmetic is the same either way, only slower.
            return SCALAR;
        }
    }

    /** Whether this process is using the explicit vector loops. For the parity check and for a line in a log. */
    public static boolean vectorised() {

        return LOOPS != SCALAR;
    }

    /**
     * How long every pass so far has taken, and over how many agent ticks, so that a worker can say at the end of its run
     * what the pass actually cost it. Two {@code System.nanoTime} calls per tick per brain, some tens of nanoseconds
     * against the hundreds of microseconds the pass itself takes.
     *
     * <p>Worth carrying because a pass timed on its own measures something else. Running it in a loop keeps the 1.3 MB of
     * weights in the second level cache, where the arithmetic is what is left to save; in a real tick the server thread
     * has ticked chunks and entities and written 634 floats an agent in between, and the weights are cold every time. The
     * two numbers came out a fifth apart, and this is the one a run is paid in. See findings.md.
     *
     * <p>Plain longs, read and written from the one server thread that does the passes. A client with several levels could
     * in principle lose a count off the end; it is a measurement, not bookkeeping.
     */
    private static long nanos;

    private static long agentTicks;

    /** Nanoseconds the pass has taken in this process, over {@link #agentTicks} agent ticks. */
    public static long nanos() {

        return nanos;
    }

    public static long agentTicks() {

        return agentTicks;
    }

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

        /** The running sums of a pair of agents for whichever layer is being worked out, each as wide as the widest layer. */
        private float[] sums = new float[0];
        private float[] pairedSums = new float[0];

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
            this.pairedSums = new float[this.sums.length];
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

        final long started = System.nanoTime();

        forward(weights, obs, hidden, logits, agents, scratch, LOOPS);

        nanos += System.nanoTime() - started;
        agentTicks += Math.max(0, agents);
    }

    /**
     * The pass with the plain loops, whatever this machine chose for itself: what the parity check holds the vector loops
     * against, at every batch size from 1 to 64. Nothing else has any reason to call it.
     */
    public static void forwardScalar(WeightSet weights, float[] obs, float[] hidden, float[] logits, int agents, Scratch scratch) {

        forward(weights, obs, hidden, logits, agents, scratch, SCALAR);
    }

    private static void forward(WeightSet weights, float[] obs, float[] hidden, float[] logits, int agents, Scratch scratch,
                                Loops loops) {

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

        final float[] first = scratch.sums;
        final float[] second = scratch.pairedSums;

        loops.normalise(obs, scratch.input, w, topology.normMean(), topology.normStd(), weights.obsClip(), in, agents);

        loops.linear(scratch.input, in, scratch.layer1, h1, scratch.fc1, w, topology.fc1B(), agents, first, second);
        loops.relu(scratch.layer1, agents * h1);

        loops.linear(scratch.layer1, h1, scratch.gatesIn, 3 * h, scratch.gruIh, w, topology.gruBih(), agents, first, second);
        loops.linear(hidden, h, scratch.gatesHidden, 3 * h, scratch.gruHh, w, topology.gruBhh(), agents, first, second);

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

        loops.linear(hidden, h, scratch.layer3, h3, scratch.fc2, w, topology.fc2B(), agents, first, second);
        loops.relu(scratch.layer3, agents * h3);

        loops.linear(scratch.layer3, h3, logits, out, scratch.head, w, topology.outB(), agents, first, second);
    }

    /** {@code clamp((x - mean) / std, -clip, clip)}, the same operations in the same order as the training side. */
    static void normalise(float[] obs, float[] into, float[] w, int mean, int std, float clip, int in, int agents) {

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
     * @param first     the running sums of the first agent of a pair, as wide as the widest layer
     * @param second    the same for the second, unused when a lone agent is left over
     */
    static void linear(float[] src, int srcStride, float[] dst, int dstStride, float[][] rows,
                       float[] params, int bias, int agents, float[] first, float[] second) {

        final int in = rows.length;
        final int out = rows[0].length;

        int agent = 0;

        for (; agent + 2 <= agents; agent += 2) {

            final int s = agent * srcStride;
            final int t = s + srcStride;

            System.arraycopy(params, bias, first, 0, out);
            System.arraycopy(params, bias, second, 0, out);

            int k = 0;

            for (; k + 4 <= in; k += 4) {

                final float x0 = src[s + k];
                final float x1 = src[s + k + 1];
                final float x2 = src[s + k + 2];
                final float x3 = src[s + k + 3];

                final float y0 = src[t + k];
                final float y1 = src[t + k + 1];
                final float y2 = src[t + k + 2];
                final float y3 = src[t + k + 3];

                final float[] w0 = rows[k];
                final float[] w1 = rows[k + 1];
                final float[] w2 = rows[k + 2];
                final float[] w3 = rows[k + 3];

                // Left to right, so each sum still takes these four inputs one after another.
                for (int j = 0; j < out; j++) {

                    first[j] = first[j] + w0[j] * x0 + w1[j] * x1 + w2[j] * x2 + w3[j] * x3;
                    second[j] = second[j] + w0[j] * y0 + w1[j] * y1 + w2[j] * y2 + w3[j] * y3;
                }
            }

            for (; k < in; k++) {

                final float x = src[s + k];
                final float y = src[t + k];
                final float[] weight = rows[k];

                for (int j = 0; j < out; j++) {

                    first[j] += weight[j] * x;
                    second[j] += weight[j] * y;
                }
            }

            System.arraycopy(first, 0, dst, agent * dstStride, out);
            System.arraycopy(second, 0, dst, (agent + 1) * dstStride, out);
        }

        // The odd one out, if there is one: the same sums in the same order, just on its own.
        for (; agent < agents; agent++) {

            final int s = agent * srcStride;

            System.arraycopy(params, bias, first, 0, out);

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

                for (int j = 0; j < out; j++) {

                    first[j] = first[j] + w0[j] * x0 + w1[j] * x1 + w2[j] * x2 + w3[j] * x3;
                }
            }

            for (; k < in; k++) {

                final float x = src[s + k];
                final float[] weight = rows[k];

                for (int j = 0; j < out; j++) {

                    first[j] += weight[j] * x;
                }
            }

            System.arraycopy(first, 0, dst, agent * dstStride, out);
        }
    }

    static void relu(float[] values, int count) {

        for (int index = 0; index < count; index++) {

            if (values[index] < 0.0F) {

                values[index] = 0.0F;
            }
        }
    }

    static float sigmoid(float x) {

        return 1.0F / (1.0F + (float) Math.exp(-x));
    }

    /**
     * The hyperbolic tangent of the GRU's candidate, worked out from {@code Math.exp} as {@code 2 * sigmoid(2x) - 1}
     * rather than from {@code Math.tanh}.
     *
     * <p>{@code Math.tanh} is the one thing in the pass that no layout buys anything back from. It is not an intrinsic:
     * it is {@code StrictMath}'s software {@code expm1}, and it measured 30.2 ns a call against 14.8 ns for this, pinned
     * to one core. The cell does one per unit per agent, 128 an agent tick, and in a worker that came to 3.2 us off an
     * agent tick: the whole pass went from 14.7 and 14.3 us to 11.6 and 11.1 over four rounds of 6,000 fights, and the
     * worker from 23.1k arena ticks a second of its server thread to 27.0k: a fifth of the pass, for one library call.
     *
     * <p>It is not the same bits, and that is said out loud rather than glossed over. Over eight million floats from -40
     * to 40 exactly one came out with a different bit from {@code Math.tanh}'s, the worst absolute difference anywhere was
     * 1.1e-16 and the worst relative one 7.4e-08, against a parity check that allows 1e-5. Two edges differ in a way worth
     * knowing: a denormal argument comes back as zero instead of itself, and negative zero comes back as positive zero.
     * Both are differences of about 1e-30 in a number the network then multiplies by a weight and adds to a sum, so
     * nothing downstream can tell; what would tell is the arena suite's twenty fights no longer taking exactly 54 ticks,
     * and they still do.
     *
     * <p>Written as {@code 2 / (1 + e) - 1} and not as {@code (1 - e) / (1 + e)}: the second is prettier and gives NaN for
     * any argument below about -355, where {@code e} overflows to infinity and the division is infinity over infinity. The
     * first gives -1 there, which is the answer. The whole of it is worked out in double and rounded once at the end, so
     * the subtraction of one does not eat the precision of a small tangent the way it would in float.
     */
    static float tanh(float x) {

        final double e = Math.exp(-2.0D * (double) x);

        return (float) (2.0D / (1.0D + e) - 1.0D);
    }
}
