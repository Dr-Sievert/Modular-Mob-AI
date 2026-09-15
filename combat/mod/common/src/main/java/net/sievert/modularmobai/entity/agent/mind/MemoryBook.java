package net.sievert.modularmobai.entity.agent.mind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * The bounded list of episodes one agent keeps, and the two numbers the observation reads off it.
 *
 * <p>At most {@link #CAP} are held; over that the faintest is forgotten, which is the only thing that keeps an agent
 * that has lived in a world for a month from carrying a month of slights. An episode already in the book is never stored
 * twice — a second telling only makes it a little louder and moves no opinion at all.
 *
 * <p>Two derived numbers are what anything downstream actually reads, both softened to 0..1 with {@code x / (x + 1)} so
 * that a hundred small slights cannot outweigh every other term the way a raw sum would:
 *
 * <ul>
 *   <li><b>grudge(who)</b> — summed salience of harms {@code who} did to me, plus harms done to bodies I like, weighted
 *       by how much I like them. Hurting my friend is hurting me; hurting my enemy is not;
 *   <li><b>gratitude(who)</b> — the same sum over helps and gifts.
 * </ul>
 *
 * <p>Both are cached for the mind tick they were computed on, because a focus slot asks for six of them in a row.
 */
public final class MemoryBook {

    /** Episodes per agent. Over this, the faintest is forgotten. The frozen layout divides the count by it. */
    public static final int CAP = 64;

    /** Under this a memory is too faint to be worth summing at all. */
    private static final float AUDIBLE = 0.01F;

    /** A witness remembers it less loudly than the one it was done to. */
    public static final float WITNESS_SHARE = 0.60F;

    private final List<Memory> items = new ArrayList<>();
    private final Set<String> keys = new HashSet<>();

    private final Map<UUID, Float> grudges = new HashMap<>();
    private final Map<UUID, Float> gratitudes = new HashMap<>();

    private long cachedAt = Long.MIN_VALUE;

    public int size() {

        return this.items.size();
    }

    public List<Memory> all() {

        return java.util.Collections.unmodifiableList(this.items);
    }

    public boolean knows(String key) {

        return this.keys.contains(key);
    }

    /**
     * Files one episode away, evicting the faintest if the book is full.
     *
     * @return the memory as it is now held, which is the one already there where this was a second telling
     */
    public Memory remember(long now, float forgiveness, Memory memory) {

        String key = memory.key();

        if (this.keys.contains(key)) {

            for (Memory held : this.items) {

                if (held.key().equals(key)) {

                    held.louden(memory.intensity());
                    this.cachedAt = Long.MIN_VALUE;

                    return held;
                }
            }
        }

        this.items.add(memory);
        this.keys.add(key);

        if (this.items.size() > CAP) {

            this.forgetTheFaintest(now, forgiveness);
        }

        this.cachedAt = Long.MIN_VALUE;

        return memory;
    }

    private void forgetTheFaintest(long now, float forgiveness) {

        int faintest = -1;
        float least = Float.MAX_VALUE;

        for (int index = 0; index < this.items.size(); index++) {

            float salience = this.items.get(index).salience(now, forgiveness);

            if (salience < least) {

                least = salience;
                faintest = index;
            }
        }

        if (faintest >= 0) {

            this.keys.remove(this.items.remove(faintest).key());
        }
    }

    /** How much the holder holds against that body, 0..1. */
    public float grudge(long now, MindState holder, UUID who) {

        this.recompute(now, holder);

        float summed = this.grudges.getOrDefault(who, 0.0F);

        return summed / (summed + 1.0F);
    }

    /** And how much it owes them, 0..1. */
    public float gratitude(long now, MindState holder, UUID who) {

        this.recompute(now, holder);

        float summed = this.gratitudes.getOrDefault(who, 0.0F);

        return summed / (summed + 1.0F);
    }

    /** The mean salience of everything held, which is the second of the observation's two memory columns. */
    public float meanSalience(long now, float forgiveness) {

        if (this.items.isEmpty()) {

            return 0.0F;
        }

        float summed = 0.0F;

        for (Memory memory : this.items) {

            summed += memory.salience(now, forgiveness);
        }

        return Math.min(1.0F, summed / this.items.size());
    }

    /**
     * Recent things done to me that I have not answered yet, freshest first. Nothing in stage B reads this; the graded
     * reactions that do are stage C, and it is here because it is the query the book exists to answer and writing it
     * beside the rules it depends on is cheaper than writing it later from memory.
     */
    public List<Memory> openProvocations(long now, MindState holder) {

        List<Memory> open = new ArrayList<>();
        float forgiveness = holder.trait(Trait.FORGIVENESS);

        for (Memory memory : this.items) {

            if (memory.isAnswered() || memory.source() != Memory.Source.SUFFERED || !memory.kind().isHarm()) {

                continue;
            }

            if (memory.actor() == null || memory.actor().equals(holder.owner())) {

                continue;
            }

            if (now - memory.tick() > Memory.REACT_WINDOW) {

                // Too old to be worth a scene, and marked so it is never walked again.
                memory.answer();
                continue;
            }

            open.add(memory);
        }

        open.sort((first, second) -> {

            int bySalience = Float.compare(second.salience(now, forgiveness), first.salience(now, forgiveness));
            return bySalience != 0 ? bySalience : Long.compare(first.tick(), second.tick());
        });

        return open;
    }

    /**
     * Sums salience into per-body grudge and gratitude, once per mind tick.
     *
     * <p>A harm counts fully if it was done to me, by a quarter if it was done to nobody in particular, and by how much
     * I like the victim otherwise.
     */
    private void recompute(long now, MindState holder) {

        if (this.cachedAt == now) {

            return;
        }

        this.grudges.clear();
        this.gratitudes.clear();

        UUID me = holder.owner();
        float forgiveness = holder.trait(Trait.FORGIVENESS);

        for (Memory memory : this.items) {

            UUID actor = memory.actor();

            if (actor == null || actor.equals(me)) {

                continue;
            }

            float salience = memory.salience(now, forgiveness);

            if (salience < AUDIBLE) {

                continue;
            }

            boolean harm = memory.kind().isHarm();

            if (!harm && !memory.kind().isKindness()) {

                continue;
            }

            UUID target = memory.target();
            float weight;

            if (target != null && target.equals(me)) {

                weight = 1.0F;
            }

            else if (target == null) {

                weight = harm ? 0.25F : 0.20F;
            }

            else {

                float likes = holder.likes(target);
                weight = Math.max(0.0F, likes) * (harm ? 0.8F : 0.5F);
            }

            if (weight <= 0.0F) {

                continue;
            }

            Map<UUID, Float> into = harm ? this.grudges : this.gratitudes;

            into.merge(actor, salience * weight, Float::sum);
        }

        this.cachedAt = now;
    }

    /** Drops the cache, for anything that changes a relationship the sums are weighted by. */
    void invalidate() {

        this.cachedAt = Long.MIN_VALUE;
    }

    public ListTag save() {

        ListTag list = new ListTag();

        for (Memory memory : this.items) {

            list.add(memory.save());
        }

        return list;
    }

    public void load(ListTag list) {

        this.items.clear();
        this.keys.clear();
        this.cachedAt = Long.MIN_VALUE;

        for (int index = 0; index < list.size() && this.items.size() < CAP; index++) {

            Tag held = list.get(index);

            if (!(held instanceof CompoundTag tag)) {

                continue;
            }

            Memory memory = Memory.load(tag);

            if (memory == null || !this.keys.add(memory.key())) {

                continue;
            }

            this.items.add(memory);
        }
    }

    /** The loudest few, for {@code /mmai} and for a test that wants to say what an agent is carrying. */
    public List<Memory> loudest(long now, float forgiveness, int most) {

        List<Memory> ranked = new ArrayList<>(this.items);

        ranked.sort((first, second) -> Float.compare(second.salience(now, forgiveness), first.salience(now, forgiveness)));

        return ranked.subList(0, Math.min(most, ranked.size()));
    }

    /** The loudest episode about that body, or null where there is none. Read by {@code /mmai} and the tests. */
    @Nullable
    public Memory about(UUID who) {

        Memory loudest = null;

        for (Memory memory : this.items) {

            if (who.equals(memory.actor()) && (loudest == null || memory.intensity() > loudest.intensity())) {

                loudest = memory;
            }
        }

        return loudest;
    }
}
