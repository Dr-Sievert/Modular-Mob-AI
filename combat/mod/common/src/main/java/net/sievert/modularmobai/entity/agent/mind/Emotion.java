package net.sievert.modularmobai.entity.agent.mind;

import java.util.Locale;

/**
 * How an agent feels, and how fast each feeling fades back to where that agent rests.
 *
 * <p>The four and their order are the decisions model's, {@code shared/models/decisions/layout.json}: the first four
 * columns of the mind block are read in exactly this order, so the order here is layout and not taste. The decay rates are
 * {@code mind/dwarfsim/mind.py}'s {@code EMOTION_DECAY}, a fraction of the gap to the baseline closed per
 * {@linkplain MindState#MIND_TICK mind tick}: fear burns off fastest, grief lingers longest.
 */
public enum Emotion {

    ANGER(0.030F),
    FEAR(0.055F),
    HAPPINESS(0.020F),
    GRIEF(0.006F);

    /** Fetched once: {@code values()} hands out a fresh copy of the array on every call, and this is read every tick. */
    public static final Emotion[] ALL = values();

    public static final int COUNT = ALL.length;

    private final float decay;
    private final String key;

    Emotion(float decay) {

        this.decay = decay;
        this.key = name().charAt(0) + name().substring(1).toLowerCase(Locale.ROOT);
    }

    /** The fraction of the gap to the baseline this feeling closes each mind tick. */
    public float decay() {

        return this.decay;
    }

    /** What it is called in the saved tag, which is its name in mixed case so a person reading the NBT can tell. */
    public String key() {

        return this.key;
    }
}
