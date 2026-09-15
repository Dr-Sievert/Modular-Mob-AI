package net.sievert.modularmobai.brain.nn;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

/**
 * The mind's <b>decisions</b> model: one observation and one proposed action in, one score out. The arbitrator scores
 * every candidate its skills proposed and takes the highest, or samples among them.
 *
 * <pre>
 *   x     = observation ++ candidate                         142 floats
 *   h     = max(x fc1W' + fc1B, 0)                           64
 *   h     = max(h fc2W' + fc2B, 0)                           64
 *   score = (h outW' + outB)[0]
 * </pre>
 *
 * <p>Six arrays, two matrix products and a dot; no normaliser, no embedding, no recurrence. The specification is
 * {@code shared/models/decisions/README.md} and the weights are {@code shared/models/decisions/decisions.mbw}, kind
 * {@link WeightFile#KIND_SCORER}.
 *
 * <p><b>One decision, many candidates.</b> The observation is the same for every candidate of one decision, so the first
 * layer's weight is split in two at column {@link #OBSERVATION}: {@link #observe} works the observation half out once and
 * {@link #scoreObserved} adds each candidate's half to it. A decision is eight to thirty candidates, so that is most of
 * the first layer saved, and it is what the numpy reference does, which is why parity holds to the same tolerance either
 * way. {@link #score} is the two together for a caller that has only one candidate.
 *
 * <p>Nothing is allocated per call: the three working vectors belong to this object, so a scorer is one agent's at a time
 * and not shared between threads. In stage C this becomes a {@code Brain} whose batch is ragged — every agent's candidates
 * stacked into one matrix — and the seam for that is already here: {@code observe} then {@code scoreObserved} per row.
 *
 * <p>Plain loops over flat arrays in {@link Forward}'s style, and its arithmetic: every output starts from its bias and
 * takes its inputs one at a time, a multiply rounded and then an add rounded. Nothing here is transposed or vectorised,
 * because 13,377 parameters against the combat network's 350,000 is not where a tick goes, and the layout Forward needs
 * for its vectoriser would be one more thing to keep honest for no measurable gain.
 */
public final class ScorerNet {

    /** The observation block of the input, {@code dwarfsim-v2}: the first columns of the first layer. */
    public static final int OBSERVATION = 77;

    /** The candidate block: 22 skill one-hot columns then 43 raw term values. */
    public static final int CANDIDATE = 65;

    /**
     * The layout these weights were trained against: the first four bytes of
     * {@code shared/models/decisions/layout.json}'s sha256, read big endian, which is
     * {@code e13153f5ec0edf2d443b25d6a7a4942444c539fc639c874739e19d613c8c68b7}. A layout that changes moves this, and the
     * weight file is then refused rather than read into the wrong columns; the parity check holds this constant against
     * {@code shared/models/MANIFEST.json} so the two cannot drift apart quietly. It moved once already, when the injury
     * block grew the observation from 69 floats to 77 and {@code impairment} grew the candidate from 64 to 65: that is
     * the mechanism working, not a nuisance.
     */
    public static final int SCHEMA_ID = 0xe13153f5;

    private final String id;
    private final ScorerShape shape;
    private final float[] params;

    /** The first layer's running sums with the observation half already in, so a decision pays for that once. */
    private final float[] observed;

    private final float[] layer1;
    private final float[] layer2;

    public ScorerNet(MindWeights weights) {

        this.shape = weights.scorer();

        if (this.shape.inDim() != OBSERVATION + CANDIDATE) {

            throw new IllegalArgumentException(weights.id() + " takes " + this.shape.inDim() + " inputs and this layout "
                    + "is " + OBSERVATION + " observation columns and " + CANDIDATE + " candidate ones");
        }

        if (this.shape.outDim() != 1) {

            throw new IllegalArgumentException(weights.id() + " has " + this.shape.outDim() + " outputs and a score is one");
        }

        if (weights.schemaId() != SCHEMA_ID) {

            throw new IllegalArgumentException(String.format(Locale.ROOT, "%s carries schema %08x and this build reads "
                    + "%08x; the layout moved, so re-run mind's tools/mbw.py and this constant with it",
                    weights.id(), weights.schemaId(), SCHEMA_ID));
        }

        this.id = weights.id();
        this.params = weights.params();
        this.observed = new float[this.shape.h1()];
        this.layer1 = new float[this.shape.h1()];
        this.layer2 = new float[this.shape.h2()];
    }

    public static ScorerNet load(Path path) throws IOException {

        return new ScorerNet(WeightFile.readMind(path, WeightFile.KIND_SCORER));
    }

    public String id() {

        return this.id;
    }

    public ScorerShape shape() {

        return this.shape;
    }

    /** One candidate, from cold: the observation half and then the candidate's. */
    public float score(float[] observation, float[] candidate) {

        this.observe(observation);
        return this.scoreObserved(candidate);
    }

    /**
     * The half of the first layer that every candidate of this decision shares: the bias plus the observation's columns.
     * Call once, then {@link #scoreObserved} per candidate.
     */
    public void observe(float[] observation) {

        if (observation.length < OBSERVATION) {

            throw new IllegalArgumentException("An observation is " + OBSERVATION + " floats, not " + observation.length);
        }

        final float[] w = this.params;
        final int fc1W = this.shape.fc1W();
        final int fc1B = this.shape.fc1B();
        final int in = this.shape.inDim();
        final int out = this.shape.h1();

        for (int j = 0; j < out; j++) {

            final int row = fc1W + j * in;
            float sum = w[fc1B + j];

            for (int k = 0; k < OBSERVATION; k++) {

                sum += w[row + k] * observation[k];
            }

            this.observed[j] = sum;
        }
    }

    /** One candidate against the observation {@link #observe} was last given. */
    public float scoreObserved(float[] candidate) {

        if (candidate.length < CANDIDATE) {

            throw new IllegalArgumentException("A candidate is " + CANDIDATE + " floats, not " + candidate.length);
        }

        final float[] w = this.params;
        final int in = this.shape.inDim();
        final int h1 = this.shape.h1();
        final int h2 = this.shape.h2();

        final int fc1W = this.shape.fc1W();

        for (int j = 0; j < h1; j++) {

            final int row = fc1W + j * in + OBSERVATION;
            float sum = this.observed[j];

            for (int k = 0; k < CANDIDATE; k++) {

                sum += w[row + k] * candidate[k];
            }

            this.layer1[j] = sum < 0.0F ? 0.0F : sum;
        }

        final int fc2W = this.shape.fc2W();
        final int fc2B = this.shape.fc2B();

        for (int j = 0; j < h2; j++) {

            final int row = fc2W + j * h1;
            float sum = w[fc2B + j];

            for (int k = 0; k < h1; k++) {

                sum += w[row + k] * this.layer1[k];
            }

            this.layer2[j] = sum < 0.0F ? 0.0F : sum;
        }

        final int outW = this.shape.outW();
        float score = w[this.shape.outB()];

        for (int k = 0; k < h2; k++) {

            score += w[outW + k] * this.layer2[k];
        }

        return score;
    }
}
