package net.sievert.modularmobai.entity.agent.mind;

import java.util.Locale;

/**
 * What an agent wants seen to, and how fast each climbs with nothing done about it.
 *
 * <p>The four and their order are the decisions model's, {@code shared/models/decisions/layout.json}, and the rates are
 * {@code mind/dwarfsim/mind.py}'s {@code NEED_RATES} per {@linkplain MindState#MIND_TICK mind tick}. Nothing in stage B
 * drops one: eating, drinking, resting and talking are skills, and skills arrive with the arbitrator in stage C. Until
 * then a need climbs to one and stays there, which is the correct reading of an agent that has never been fed.
 */
public enum Need {

    HUNGER(0.0035F),
    THIRST(0.0050F),
    FATIGUE(0.0030F),
    SOCIAL(0.0035F);

    public static final Need[] ALL = values();

    public static final int COUNT = ALL.length;

    private final float rate;
    private final String key;

    Need(float rate) {

        this.rate = rate;
        this.key = name().charAt(0) + name().substring(1).toLowerCase(Locale.ROOT);
    }

    /** How much this need rises each mind tick. */
    public float rate() {

        return this.rate;
    }

    public String key() {

        return this.key;
    }
}
