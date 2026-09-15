package net.sievert.modularmobai.entity.agent.mind;

import java.util.List;

import net.sievert.modularmobai.brain.nn.InterpreterNet;

/**
 * One line of chat as the interpreter read it: the shape {@link Speech} turns into an event.
 *
 * <p>It is {@code text/SCHEMA.md}'s fields — the four labels, the three floats and the names — which is exactly what
 * {@link InterpreterNet.Reading} produces, plus the two things a reading does not carry: the names the line mentions,
 * which the interpreter deliberately does not predict (an exact match over a known pool, ten lines in stage C), and the
 * worst tier of profanity anything has found in the line, which is what turns an insult into a slur.
 *
 * <p>Written now and unused now. Stage C's chat hook is one call of {@link InterpreterNet#classify} and one of
 * {@link #of}, and the whole of what a line does to an agent is already here, tested, and the same numbers the sim
 * gives.
 *
 * @param intent      one of {@link InterpreterNet#INTENTS}
 * @param topic       one of {@link InterpreterNet#TOPICS}
 * @param addressed   one of {@link InterpreterNet#ADDRESSED}: whether it was said to the listener, about a third party,
 *                    to the room, or to nobody
 * @param sincerity   one of {@link InterpreterNet#SINCERITY}
 * @param aggression  0..1
 * @param valence     -1..1
 * @param urgency     0..1
 * @param names       whoever the line named, in the order it named them; empty where it named nobody
 * @param text        the raw line, kept for the log and for anything that wants to read it again
 * @param profanityTier the worst tier of profanity found in it, 0 for none; 3 is a slur and is its own event
 */
public record Utterance(String intent, String topic, String addressed, String sincerity, float aggression,
                        float valence, float urgency, List<String> names, String text, int profanityTier) {

    public static final String ADDRESSED_LISTENER = "LISTENER";
    public static final String ADDRESSED_THIRD = "THIRD";
    public static final String ADDRESSED_GROUP = "GROUP";
    public static final String ADDRESSED_NONE = "NONE";

    /** The tier at which a term is aimed at what somebody <em>is</em>, which the table has a heavier row for. */
    public static final int SLUR_TIER = 3;

    public Utterance {

        names = List.copyOf(names);
    }

    /** What the interpreter read, with the two things it does not produce supplied by the caller. */
    public static Utterance of(InterpreterNet.Reading reading, List<String> names, String text, int profanityTier) {

        return new Utterance(reading.intent(), reading.topic(), reading.addressed(), reading.sincerity(),
                reading.aggression(), reading.valence(), reading.urgency(), names, text, profanityTier);
    }

    /** A plain line with nothing read into it, for a test or for anything speaking without a classifier to hand. */
    public static Utterance plain(String intent, String addressed, float aggression) {

        return new Utterance(intent, "NONE", addressed, "SINCERE", aggression, 0.0F, 0.0F, List.of(), "", 0);
    }

    /** Whether the line was said to whoever is listening, rather than about somebody else or to nobody. */
    public boolean toTheListener() {

        return ADDRESSED_LISTENER.equals(this.addressed) || ADDRESSED_GROUP.equals(this.addressed);
    }

    /** Whether it carries a term aimed at what somebody is. */
    public boolean isSlur() {

        return this.profanityTier >= SLUR_TIER;
    }
}
