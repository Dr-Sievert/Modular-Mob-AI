package net.sievert.modularmobai.entity.agent.mind;

import java.util.Locale;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.sievert.modularmobai.entity.agent.AgentMob;

/**
 * Words in, events out. The only place in the mod that turns something said into something felt.
 *
 * <p>It is {@code mind/dwarfsim/speech.py}'s {@code hear} with the same mapping and the same arithmetic:
 *
 * <ul>
 *   <li>{@code INSULT}, {@code THREAT} and {@code ACCUSE} become those events, with aggression driving the magnitude;
 *   <li>{@code PRAISE}, {@code OFFER} and {@code APOLOGY} become {@code PRAISE}, {@code GIFT} and {@code APOLOGY};
 *   <li>{@code WARNING} becomes {@code WARNING} — fear up, trust up a little;
 *   <li>{@code REQUEST} and {@code COMMAND} are not events at all: they become a standing pull on the listener, which
 *       the arbitrator weighs by how much it trusts whoever asked;
 *   <li>everything else is {@code SMALLTALK};
 *   <li>and a line carrying a tier three term is a {@link MindEvent#SLUR} whatever else it was, which is a heavier row.
 * </ul>
 *
 * <p><b>Who it lands on.</b> The {@code addressed} head is the gate the port notes describe: a line said to the listener
 * or to the room lands on them as the <em>target</em>, and a line about a third party or muttered at nobody makes them a
 * <em>witness</em> of it instead — which is the same distinction the sim draws by retargeting the event onto whoever was
 * named. A reported threat, "I told him I'd wreck him", therefore does not frighten the person it is told to.
 *
 * <p><b>Nothing calls this yet.</b> Stage C's chat hook classifies a line with {@code InterpreterNet} and calls
 * {@link #said}; everything below it is written, and tested, now.
 */
public final class Speech {

    /** Which event each of the interpreter's thirteen intents becomes. An intent that is not here is a request. */
    private static final Map<String, MindEvent> INTENT_EVENT = Map.of(
            "GREET", MindEvent.SMALLTALK,
            "FAREWELL", MindEvent.SMALLTALK,
            "SMALLTALK", MindEvent.SMALLTALK,
            "QUESTION", MindEvent.SMALLTALK,
            "OFFER", MindEvent.GIFT,
            "PRAISE", MindEvent.PRAISE,
            "APOLOGY", MindEvent.APOLOGY,
            "INSULT", MindEvent.INSULT,
            "THREAT", MindEvent.THREAT,
            "WARNING", MindEvent.WARNING);

    /** The one intent left over from the map above, which has to be spelled the same way. */
    private static final String ACCUSE = "ACCUSE";

    /** The two that are not events at all but a pull toward doing something. */
    private static final String REQUEST = "REQUEST";
    private static final String COMMAND = "COMMAND";

    /** How long a request stays on the listener's mind, in mind ticks. */
    public static final int REQUEST_TTL = 80;

    private Speech() {}

    /**
     * One line, said by somebody to somebody, heard by the room.
     *
     * <p>This is the entry point stage C's chat hook calls. The listener is whoever it was addressed to; every other
     * agent that can perceive the speaker takes it as a witness, which {@link Events#happened} already does.
     */
    public static void said(@Nullable LivingEntity speaker, @Nullable AgentMob listener, Utterance line) {

        MindEvent kind = eventFor(line);

        if (kind == null) {

            if (listener != null && listener.mind().isAwake()) {

                listener.mind().wasAsked(speaker, line);
            }

            return;
        }

        // A line about a third party, or muttered at nobody, is not said to the listener: nobody is its target, so the
        // whole room including the listener takes it as witnesses.
        Events.happened(kind, speaker, line.toTheListener() ? listener : null, magnitudeOf(line));
    }

    /**
     * One line as a single mind takes it, which is what {@link MindState#hear} is. Used where the room has already been
     * worked out — a test, or an agent that overheard something said to somebody else.
     */
    static void hear(MindState mind, @Nullable LivingEntity speaker, Utterance line) {

        if (!mind.isAwake()) {

            return;
        }

        MindEvent kind = eventFor(line);

        if (kind == null) {

            mind.wasAsked(speaker, line);
            return;
        }

        java.util.UUID said = speaker == null ? null : speaker.getUUID();

        Events.apply(mind, kind, line.toTheListener() ? Events.Role.TARGET : Events.Role.WITNESS, said, mind.owner(),
                magnitudeOf(line));

        if (kind.isRemembered() && said != null) {

            float intensity = kind.memoryWeight() * magnitudeOf(line);

            mind.remember(kind, said, mind.owner(),
                    line.toTheListener() ? intensity : intensity * MemoryBook.WITNESS_SHARE,
                    line.toTheListener() ? Memory.Source.SUFFERED : Memory.Source.SEEN, null, 0);
        }
    }

    /**
     * Which row of the table this line is, or null where it is a request rather than an event.
     *
     * <p>A slur is what the line <em>was</em>, whatever else it was carrying: not an insult with a bad word in it and
     * not a request with one. It goes through as its own event, which is a heavier row.
     */
    @Nullable
    public static MindEvent eventFor(Utterance line) {

        if (line.isSlur()) {

            return MindEvent.SLUR;
        }

        String intent = line.intent() == null ? "" : line.intent().toUpperCase(Locale.ROOT);

        if (REQUEST.equals(intent) || COMMAND.equals(intent)) {

            return null;
        }

        if (ACCUSE.equals(intent)) {

            return MindEvent.ACCUSE;
        }

        return INTENT_EVENT.getOrDefault(intent, MindEvent.SMALLTALK);
    }

    /**
     * How hard the line lands, out of aggression, valence and urgency: {@code magnitude_of} in the sim, number for
     * number. A shouted threat lands about four times as hard as a muttered one, which is the whole reason the three
     * floats are predicted at all.
     */
    public static float magnitudeOf(Utterance line) {

        String intent = line.intent() == null ? "" : line.intent().toUpperCase(Locale.ROOT);
        boolean hostile = "INSULT".equals(intent) || "THREAT".equals(intent) || ACCUSE.equals(intent) || line.isSlur();

        float magnitude;

        if (hostile) {

            magnitude = 0.45F + 0.85F * line.aggression() + 0.35F * Math.max(0.0F, -line.valence());
        }

        else if ("WARNING".equals(intent)) {

            magnitude = 0.50F + 0.80F * line.urgency();
        }

        else {

            magnitude = 0.50F + 0.80F * Math.max(0.0F, line.valence());
        }

        if (Utterance.ADDRESSED_NONE.equals(line.addressed())) {

            // Muttering into your beard.
            magnitude *= 0.4F;
        }

        else if (Utterance.ADDRESSED_GROUP.equals(line.addressed())) {

            magnitude *= 0.8F;
        }

        return Mth.clamp(magnitude, 0.1F, 1.8F);
    }
}
