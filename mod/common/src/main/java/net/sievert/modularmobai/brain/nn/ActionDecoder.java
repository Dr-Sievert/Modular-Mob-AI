package net.sievert.modularmobai.brain.nn;

import java.util.random.RandomGenerator;

/**
 * Turns one agent's logits into its action values, walking the {@link Heads} table.
 *
 * <p>Deployment takes the most likely action. Training has to sample, or the policy never tries anything it does not
 * already believe in; same weights, different decoder mode. When sampling, the log probability of what was chosen is
 * returned, summed over every block, because the training side needs it for the PPO ratio and would otherwise have to
 * guess what the game rolled.
 *
 * <pre>
 *   continuous   mean = tanh(logit), a ~ Normal(mean, exp(logStd))    the raw sample is kept; the body clamps it
 *   binary       p = sigmoid(logit), a ~ Bernoulli(p)
 *   categorical  p = softmax over the allowed choices, a ~ Categorical(p), written as the index
 * </pre>
 *
 * <p>Masking is a hard guarantee applied before the softmax: a choice that is not allowed gets probability exactly zero,
 * whatever the weights say. Here that is an empty hotbar slot. A row where nothing at all is allowed is left unmasked,
 * since something has to be picked; the training side does the same.
 */
public final class ActionDecoder {

    private ActionDecoder() {}

    private static final double HALF_LOG_TWO_PI = 0.5D * Math.log(2.0D * Math.PI);

    /**
     * @param random where to sample from, or null to take the most likely action
     * @return the log probability of the chosen action when sampling, zero otherwise
     */
    public static float decode(Heads heads, WeightSet weights, float[] logits, int logitBase,
                               float[] obs, int obsBase, float[] actions, int actionBase, RandomGenerator random) {

        final float[] w = weights.params();
        final int stdBase = weights.topology().logStd();

        double logProb = 0.0D;

        for (int index = 0; index < heads.count(); index++) {

            Heads.Block block = heads.block(index);
            int from = logitBase + block.logit();
            int to = actionBase + block.action();

            switch (block.kind()) {

                case CONTINUOUS -> {

                    for (int i = 0; i < block.size(); i++) {

                        float mean = Forward.tanh(logits[from + i]);

                        if (random == null) {

                            actions[to + i] = mean;
                            continue;
                        }

                        float logStd = w[stdBase + block.std() + i];
                        float value = mean + (float) Math.exp(logStd) * (float) random.nextGaussian();

                        actions[to + i] = value;
                        logProb += gaussian(value, mean, logStd);
                    }
                }

                case BINARY -> {

                    for (int i = 0; i < block.size(); i++) {

                        float logit = logits[from + i];

                        if (random == null) {

                            actions[to + i] = logit > 0.0F ? 1.0F : 0.0F;
                            continue;
                        }

                        float value = random.nextFloat() < Forward.sigmoid(logit) ? 1.0F : 0.0F;

                        actions[to + i] = value;
                        logProb += bernoulli(logit, value);
                    }
                }

                case CATEGORICAL -> {

                    boolean masked = anyAllowed(block, obs, obsBase);
                    int choice = random == null
                            ? argmax(block, logits, from, obs, obsBase, masked)
                            : sample(block, logits, from, obs, obsBase, masked, random);

                    actions[to] = choice;

                    if (random != null) {

                        logProb += logits[from + choice] - logSumExp(block, logits, from, obs, obsBase, masked);
                    }
                }
            }
        }

        return (float) logProb;
    }

    /**
     * The log probability of actions that were already chosen, for checking this side against the training side. Uses
     * exactly the formulas {@link #decode} samples with.
     */
    public static float logProb(Heads heads, WeightSet weights, float[] logits, int logitBase,
                                float[] obs, int obsBase, float[] actions, int actionBase) {

        final float[] w = weights.params();
        final int stdBase = weights.topology().logStd();

        double logProb = 0.0D;

        for (int index = 0; index < heads.count(); index++) {

            Heads.Block block = heads.block(index);
            int from = logitBase + block.logit();
            int to = actionBase + block.action();

            switch (block.kind()) {

                case CONTINUOUS -> {

                    for (int i = 0; i < block.size(); i++) {

                        logProb += gaussian(actions[to + i], Forward.tanh(logits[from + i]), w[stdBase + block.std() + i]);
                    }
                }

                case BINARY -> {

                    for (int i = 0; i < block.size(); i++) {

                        logProb += bernoulli(logits[from + i], actions[to + i]);
                    }
                }

                case CATEGORICAL -> {

                    boolean masked = anyAllowed(block, obs, obsBase);
                    int choice = Math.round(actions[to]);

                    logProb += logits[from + choice] - logSumExp(block, logits, from, obs, obsBase, masked);
                }
            }
        }

        return (float) logProb;
    }

    private static double gaussian(float value, float mean, float logStd) {

        double z = (value - mean) / Math.exp(logStd);
        return -0.5D * z * z - logStd - HALF_LOG_TWO_PI;
    }

    /** {@code value * logit - softplus(logit)}, which is log p for a one and log (1 - p) for a zero, without the overflow. */
    private static double bernoulli(float logit, float value) {

        double softplus = Math.max(logit, 0.0D) + Math.log1p(Math.exp(-Math.abs((double) logit)));
        return value * (double) logit - softplus;
    }

    private static boolean allowed(Heads.Block block, float[] obs, int obsBase, int choice, boolean masked) {

        return !masked || obs[obsBase + block.mask() + choice] > 0.0F;
    }

    /** Whether the mask applies at all: it does not when the block has none, or when it would rule out everything. */
    private static boolean anyAllowed(Heads.Block block, float[] obs, int obsBase) {

        if (block.mask() < 0) {

            return false;
        }

        for (int choice = 0; choice < block.size(); choice++) {

            if (obs[obsBase + block.mask() + choice] > 0.0F) {

                return true;
            }
        }

        return false;
    }

    private static double logSumExp(Heads.Block block, float[] logits, int from, float[] obs, int obsBase, boolean masked) {

        double max = Double.NEGATIVE_INFINITY;

        for (int choice = 0; choice < block.size(); choice++) {

            if (allowed(block, obs, obsBase, choice, masked)) {

                max = Math.max(max, logits[from + choice]);
            }
        }

        double sum = 0.0D;

        for (int choice = 0; choice < block.size(); choice++) {

            if (allowed(block, obs, obsBase, choice, masked)) {

                sum += Math.exp(logits[from + choice] - max);
            }
        }

        return max + Math.log(sum);
    }

    private static int argmax(Heads.Block block, float[] logits, int from, float[] obs, int obsBase, boolean masked) {

        int best = -1;

        for (int choice = 0; choice < block.size(); choice++) {

            if (allowed(block, obs, obsBase, choice, masked) && (best < 0 || logits[from + choice] > logits[from + best])) {

                best = choice;
            }
        }

        return best;
    }

    private static int sample(Heads.Block block, float[] logits, int from, float[] obs, int obsBase, boolean masked,
                              RandomGenerator random) {

        double lse = logSumExp(block, logits, from, obs, obsBase, masked);
        double roll = random.nextDouble();
        int last = -1;

        for (int choice = 0; choice < block.size(); choice++) {

            if (!allowed(block, obs, obsBase, choice, masked)) {

                continue;
            }

            last = choice;
            roll -= Math.exp(logits[from + choice] - lse);

            if (roll < 0.0D) {

                return choice;
            }
        }

        // Only reachable through rounding, when the probabilities summed to a hair under one.
        return last;
    }
}
