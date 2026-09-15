package net.sievert.modularmobai.entity.agent.mind;

import java.util.Locale;

import org.jetbrains.annotations.Nullable;

/**
 * What an agent is like, rolled once when it is born and never moved again.
 *
 * <p><b>The first six are the layout's.</b> {@code shared/models/decisions/layout.json} gives the mind block six trait
 * columns, in the order {@code bravery, greed, temper, sociability, pride, forgiveness}, so those six come first here and
 * {@link MindObservation} writes them straight through by ordinal. The two after them, {@link #LOYALTY} and
 * {@link #SUSPICION}, are the mod's own: they are carried, saved and rolled like the rest and are simply not among the
 * columns the frozen scorer was trained on. When a later layout gives them columns they are already here, in a fixed
 * order, with a save behind them.
 *
 * <p>Three of them do work in stage B. <b>temper</b> scales every gain of anger (x0.6 to x1.4) and <b>bravery</b> scales
 * every gain of fear (x1.4 down to x0.6), which is what makes two agents react differently to the same blow, and
 * <b>forgiveness</b> sets how fast an episode's salience fades, which is what lets two agents with identical
 * relationship numbers hold a grudge for very different lengths of time. <b>pride</b> is how much a slight in front of
 * other people costs and <b>greed</b>, <b>sociability</b>, <b>loyalty</b> and <b>suspicion</b> are read by the skills,
 * which is stage C.
 */
public enum Trait {

    BRAVERY,
    GREED,
    TEMPER,
    SOCIABILITY,
    PRIDE,
    FORGIVENESS,
    LOYALTY,
    SUSPICION;

    public static final Trait[] ALL = values();

    public static final int COUNT = ALL.length;

    /** How many of them the decisions model reads, which is the first six; see the class comment. */
    public static final int IN_OBSERVATION = 6;

    /** The narrowest and widest a rolled trait may be, as {@code MindState.random} rolls them in the sim. */
    public static final float LOWEST = 0.1F;
    public static final float HIGHEST = 0.9F;

    private final String key;

    Trait() {

        this.key = name().charAt(0) + name().substring(1).toLowerCase(Locale.ROOT);
    }

    public String key() {

        return this.key;
    }

    /** The trait of that name, however it is cased, or null for a name that is not one. */
    @Nullable
    public static Trait byName(String name) {

        for (Trait trait : ALL) {

            if (trait.name().equalsIgnoreCase(name.trim())) {

                return trait;
            }
        }

        return null;
    }
}
