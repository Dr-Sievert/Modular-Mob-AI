package net.sievert.modularmobai.brain.nn;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorSpecies;

/**
 * The forward pass's loops written in explicit vector instructions, worth about 15% of the whole pass over the plain ones
 * in {@link Forward}. Same arithmetic, same order, the same bits: see {@link Forward} for why that is not a coincidence
 * and where it is proved.
 *
 * <p>This class exists separately so that a game without {@code jdk.incubator.vector} never loads it. {@link Forward}
 * looks it up by name, once, and keeps the plain loops when that fails; touching a species below in the static initialiser
 * is what makes a missing module fail there, at the lookup, rather than somewhere in the middle of a fight.
 *
 * <p>Every loop here is whole vectors with a scalar tail, never a mask per iteration. That is not a style preference: the
 * same loops written with masked loads and stores measured at 40% of the plain scalar loops, which is in findings.md
 * beside the rest of the measurements. The one mask in here is for the clamp in {@link #normalise}, where it picks between
 * two values already computed rather than gating memory.
 *
 * <p>Nothing is fused. {@code Math.fma} would halve the floating point operations the matrix loops issue and buys 3.5%,
 * which says those loops are waiting on the caches and not on arithmetic at all; it also drifts the logits by about 7e-3
 * relative, and a network's weights were trained against the other answer.
 */
final class ForwardVectors implements Forward.Loops {

    /**
     * Whatever width this machine does best, which is 256 bits on the desktop chips this trains on and would be 512 on a
     * server one. The layers are 19 to 384 wide, so every width divides the work sensibly and only the head, at 19, has a
     * tail worth mentioning.
     */
    private static final VectorSpecies<Float> SPECIES = FloatVector.SPECIES_PREFERRED;

    private static final int LANES = SPECIES.length();

    ForwardVectors() {}

    /**
     * {@code dst[agent][j] = bias[j] + sum over k of w[j][k] * src[agent][k]}, two agents at a time and four inputs at a
     * time, exactly as the plain loops do it.
     *
     * <p>Each lane holds one output and keeps its own running sum in the {@code first} and {@code second} arrays, so a
     * lane's sum takes its inputs in the same order a scalar sum would: bias, then this input times its weight added on,
     * then the next. Four inputs go through per pass so each sum is loaded and stored a quarter as often, and the pair of
     * agents share every weight vector they load.
     */
    @Override
    public void linear(float[] src, int srcStride, float[] dst, int dstStride, float[][] rows, float[] params, int bias,
                       int agents, float[] first, float[] second) {

        final int in = rows.length;
        final int out = rows[0].length;
        final int bound = SPECIES.loopBound(out);

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

                pair(w0, w1, w2, w3, x0, x1, x2, x3, y0, y1, y2, y3, first, second, bound, out);
            }

            for (; k < in; k++) {

                pairOne(rows[k], src[s + k], src[t + k], first, second, bound, out);
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

                four(rows[k], rows[k + 1], rows[k + 2], rows[k + 3], x0, x1, x2, x3, first, bound, out);
            }

            for (; k < in; k++) {

                one(rows[k], src[s + k], first, bound, out);
            }

            System.arraycopy(first, 0, dst, agent * dstStride, out);
        }
    }

    /**
     * Four inputs and a pair of agents: each of the four weight rows is loaded once and used for both agents, which is
     * what pairing them is for. {@code sums[j] = sums[j] + w0[j] * x0 + ... + w3[j] * x3} for every output j, left to
     * right, so each sum takes its four inputs in the order a scalar sum would.
     *
     * <p>This is one small method rather than a nest inside the loop above, and that is what decides whether the vectors
     * are real at all. The compiler turns these calls into single instructions only if it can inline all of them and see
     * that no vector escapes into the heap; with all four of this class's loop nests written out inside one method it ran
     * out of room, fell back to real {@code FloatVector} objects, and cost a worker 36 kB of garbage an agent tick. See
     * findings.md for what that measured.
     */
    private static void pair(float[] w0, float[] w1, float[] w2, float[] w3, float x0, float x1, float x2, float x3,
                             float y0, float y1, float y2, float y3, float[] first, float[] second, int bound, int out) {

        int j = 0;

        for (; j < bound; j += LANES) {

            final FloatVector a0 = FloatVector.fromArray(SPECIES, w0, j);
            final FloatVector a1 = FloatVector.fromArray(SPECIES, w1, j);
            final FloatVector a2 = FloatVector.fromArray(SPECIES, w2, j);
            final FloatVector a3 = FloatVector.fromArray(SPECIES, w3, j);

            FloatVector.fromArray(SPECIES, first, j)
                    .add(a0.mul(x0)).add(a1.mul(x1)).add(a2.mul(x2)).add(a3.mul(x3))
                    .intoArray(first, j);

            FloatVector.fromArray(SPECIES, second, j)
                    .add(a0.mul(y0)).add(a1.mul(y1)).add(a2.mul(y2)).add(a3.mul(y3))
                    .intoArray(second, j);
        }

        for (; j < out; j++) {

            first[j] = first[j] + w0[j] * x0 + w1[j] * x1 + w2[j] * x2 + w3[j] * x3;
            second[j] = second[j] + w0[j] * y0 + w1[j] * y1 + w2[j] * y2 + w3[j] * y3;
        }
    }

    /** One input and a pair of agents, for the inputs left over when the count is not a multiple of four. */
    private static void pairOne(float[] weight, float x, float y, float[] first, float[] second, int bound, int out) {

        int j = 0;

        for (; j < bound; j += LANES) {

            final FloatVector a = FloatVector.fromArray(SPECIES, weight, j);

            FloatVector.fromArray(SPECIES, first, j).add(a.mul(x)).intoArray(first, j);
            FloatVector.fromArray(SPECIES, second, j).add(a.mul(y)).intoArray(second, j);
        }

        for (; j < out; j++) {

            first[j] += weight[j] * x;
            second[j] += weight[j] * y;
        }
    }

    /** Four inputs for a lone agent: the odd one out of an odd batch, whose sums are taken in exactly the same order. */
    private static void four(float[] w0, float[] w1, float[] w2, float[] w3, float x0, float x1, float x2, float x3,
                             float[] sums, int bound, int out) {

        int j = 0;

        for (; j < bound; j += LANES) {

            FloatVector.fromArray(SPECIES, sums, j)
                    .add(FloatVector.fromArray(SPECIES, w0, j).mul(x0))
                    .add(FloatVector.fromArray(SPECIES, w1, j).mul(x1))
                    .add(FloatVector.fromArray(SPECIES, w2, j).mul(x2))
                    .add(FloatVector.fromArray(SPECIES, w3, j).mul(x3))
                    .intoArray(sums, j);
        }

        for (; j < out; j++) {

            sums[j] = sums[j] + w0[j] * x0 + w1[j] * x1 + w2[j] * x2 + w3[j] * x3;
        }
    }

    /** One input times its row of weights, for the inputs left over when the count is not a multiple of four. */
    private static void one(float[] weight, float x, float[] sums, int bound, int out) {

        int j = 0;

        for (; j < bound; j += LANES) {

            FloatVector.fromArray(SPECIES, sums, j)
                    .add(FloatVector.fromArray(SPECIES, weight, j).mul(x))
                    .intoArray(sums, j);
        }

        for (; j < out; j++) {

            sums[j] += weight[j] * x;
        }
    }

    /**
     * Zeroes the negatives, and nothing else.
     *
     * <p>Written as a comparison and a blend rather than a maximum against zero, because {@code Math.max(-0.0F, 0.0F)} is
     * {@code +0.0F} while {@code -0.0F < 0.0F} is false: a maximum would quietly turn a negative zero into a positive one.
     * Nothing downstream could tell, since the two are numerically equal, but "bit for bit" has to mean what it says or it
     * is no use as a check.
     */
    @Override
    public void relu(float[] values, int count) {

        final int bound = SPECIES.loopBound(count);

        int index = 0;

        for (; index < bound; index += LANES) {

            final FloatVector v = FloatVector.fromArray(SPECIES, values, index);

            v.blend(0.0F, v.lt(0.0F)).intoArray(values, index);
        }

        for (; index < count; index++) {

            if (values[index] < 0.0F) {

                values[index] = 0.0F;
            }
        }
    }

    /**
     * {@code clamp((x - mean) / std, -clip, clip)}, in the order the plain loops and the training side both use it: a
     * minimum against the upper bound, and the lower bound written over whatever fell below it.
     *
     * <p>The mean and the standard deviation live in the weight array at their own offsets, so each of the three loads is
     * from a different index. That is exactly what the compiler's own vectoriser will not do, and why this layer is worth
     * saying by hand even though it is only a twentieth of the pass.
     */
    @Override
    public void normalise(float[] obs, float[] into, float[] w, int mean, int std, float clip, int in, int agents) {

        final int bound = SPECIES.loopBound(in);
        final float floor = -clip;

        for (int agent = 0; agent < agents; agent++) {

            final int row = agent * in;

            int k = 0;

            for (; k < bound; k += LANES) {

                final FloatVector value = FloatVector.fromArray(SPECIES, obs, row + k)
                        .sub(FloatVector.fromArray(SPECIES, w, mean + k))
                        .div(FloatVector.fromArray(SPECIES, w, std + k));

                final VectorMask<Float> below = value.lt(floor);

                value.min(clip).blend(floor, below).intoArray(into, row + k);
            }

            for (; k < in; k++) {

                float value = (obs[row + k] - w[mean + k]) / w[std + k];
                into[row + k] = value < floor ? floor : Math.min(value, clip);
            }
        }
    }
}
