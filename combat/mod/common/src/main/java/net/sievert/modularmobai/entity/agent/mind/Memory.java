package net.sievert.modularmobai.entity.agent.mind;

import java.util.Locale;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import net.minecraft.nbt.CompoundTag;

/**
 * One episode as one agent holds it: what happened, who did it to whom, how loud it was, and how it was learned.
 *
 * <p>Relationships are the running state — the three numbers the arbitrator reads. Memory is the <em>episodes</em>
 * behind those numbers: what explains a grudge, what a complaint is evidence of, and what gossip will carry in stage C.
 * Straight out of {@code mind/dwarfsim/memory.py}.
 *
 * <p>Salience is the intensity it was filed at times an exponential in its age, and the rate is set by the holder's
 * {@link Trait#FORGIVENESS}: a bitter agent's half-life is about 1,200 mind ticks and a forgiving one's about 250. That
 * one number is why two agents with identical relationship rows can hold a grudge for wildly different lengths of time.
 */
public final class Memory {

    /** The fraction of salience shed each mind tick before the forgiveness trait scales it. */
    private static final float DECAY_BASE = 0.0016F;

    /** How long a suffered provocation stays answerable, in mind ticks. Read by the reactions, which are stage C. */
    public static final int REACT_WINDOW = 45;

    /** How an episode got into a head. {@code HEARD} also carries who it was heard from. */
    public enum Source {

        /** I watched it happen to somebody else. */
        SEEN,

        /** It happened to me. */
        SUFFERED,

        /** Somebody told me, and {@link Memory#from} is who. */
        HEARD;

        public static final Source[] ALL = values();

        @Nullable
        static Source byName(String name) {

            for (Source source : ALL) {

                if (source.name().equals(name)) {

                    return source;
                }
            }

            return null;
        }
    }

    private final long tick;
    private final MindEvent kind;

    @Nullable
    private final UUID actor;

    @Nullable
    private final UUID target;

    @Nullable
    private final UUID from;

    private float intensity;
    private final Source source;
    private final int witnesses;

    /** Whether the holder has already reacted to it, so a scene is not made about the same slight twice. */
    private boolean answered;

    public Memory(long tick, MindEvent kind, @Nullable UUID actor, @Nullable UUID target, float intensity, Source source,
            @Nullable UUID from, int witnesses) {

        this.tick = tick;
        this.kind = kind;
        this.actor = actor;
        this.target = target;
        this.intensity = intensity;
        this.source = source;
        this.from = from;
        this.witnesses = witnesses;
    }

    public long tick() {

        return this.tick;
    }

    public MindEvent kind() {

        return this.kind;
    }

    @Nullable
    public UUID actor() {

        return this.actor;
    }

    @Nullable
    public UUID target() {

        return this.target;
    }

    @Nullable
    public UUID from() {

        return this.from;
    }

    public float intensity() {

        return this.intensity;
    }

    public Source source() {

        return this.source;
    }

    public int witnesses() {

        return this.witnesses;
    }

    public boolean isAnswered() {

        return this.answered;
    }

    public void answer() {

        this.answered = true;
    }

    /** Hearing the same story a second time only makes it a little louder; see {@link MemoryBook#remember}. */
    void louden(float intensity) {

        this.intensity = Math.max(this.intensity, intensity * 0.6F);
    }

    /** How loud this still is, now: the intensity it was filed at, faded by its age and by how forgiving the holder is. */
    public float salience(long now, float forgiveness) {

        long age = now - this.tick;

        if (age <= 0L) {

            return this.intensity;
        }

        float rate = DECAY_BASE * (0.35F + 1.30F * forgiveness);

        return (float) (this.intensity * Math.exp(-rate * age));
    }

    /**
     * What makes two memories the same <em>event</em>, however many heads it reaches. An episode already in a book is
     * never stored twice: without that, one piece of gossip goes round for two thousand ticks and every retelling moves
     * opinion again.
     */
    public String key() {

        return this.kind.name() + '|' + this.actor + '|' + this.target + '|' + this.tick;
    }

    public CompoundTag save() {

        CompoundTag tag = new CompoundTag();

        tag.putLong("Tick", this.tick);
        tag.putString("Kind", this.kind.name());
        tag.putFloat("Intensity", this.intensity);
        tag.putString("Source", this.source.name());
        tag.putInt("Witnesses", this.witnesses);

        if (this.actor != null) {

            tag.putUUID("Actor", this.actor);
        }

        if (this.target != null) {

            tag.putUUID("Target", this.target);
        }

        if (this.from != null) {

            tag.putUUID("From", this.from);
        }

        if (this.answered) {

            tag.putBoolean("Answered", true);
        }

        return tag;
    }

    /** One saved episode back, or null where it named an event or a source this build does not have. */
    @Nullable
    public static Memory load(CompoundTag tag) {

        MindEvent kind = MindEvent.byName(tag.getString("Kind"));
        Source source = Source.byName(tag.getString("Source"));

        if (kind == null || source == null) {

            return null;
        }

        Memory memory = new Memory(tag.getLong("Tick"), kind,
                tag.hasUUID("Actor") ? tag.getUUID("Actor") : null,
                tag.hasUUID("Target") ? tag.getUUID("Target") : null,
                tag.getFloat("Intensity"), source,
                tag.hasUUID("From") ? tag.getUUID("From") : null,
                tag.getInt("Witnesses"));

        memory.answered = tag.getBoolean("Answered");

        return memory;
    }

    @Override
    public String toString() {

        return String.format(Locale.ROOT, "<t%d %s %s->%s %s %.2f>", this.tick, this.kind, this.actor, this.target,
                this.source, this.intensity);
    }
}
