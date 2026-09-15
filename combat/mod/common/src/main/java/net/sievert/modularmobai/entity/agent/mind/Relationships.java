package net.sievert.modularmobai.entity.agent.mind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

/**
 * Everyone an agent has an opinion about, keyed by the one thing that survives a save: the entity's UUID.
 *
 * <p>A player is a row like any other and starts neutral, which is {@code PLAYER_ID} in {@code dwarfsim/schema.py} said
 * in the mod's own terms: nothing here knows or cares whether the id belongs to a player, another agent or a zombie.
 *
 * <p><b>Four of them reach the observation.</b> The decisions model reads four focus slots of six floats, filled by
 * salience and padded with zeros — the same idea as the ten enemy slots the combat network reads, and the same weakness
 * applies: if they ever grow past four, attend them rather than giving each its own columns. {@link #focus} is the one
 * place that ordering lives.
 *
 * <p><b>Bounded.</b> A world runs for months and an agent meets thousands of bodies. Rows decay toward nothing, a row
 * that has decayed to nothing is dropped, and past {@link #CAP} rows the faintest goes whatever it says — so the cost of
 * a long-lived agent is bounded by a number in this file and not by how many mobs have walked past it.
 */
public final class Relationships {

    /** How many bodies an agent keeps an opinion about at once. Past this the faintest row is forgotten. */
    public static final int CAP = 32;

    /** How many of them reach the observation, which is the frozen layout's own number. */
    public static final int FOCUS_SLOTS = 4;

    /** What one focus slot is worth in floats: present, trust, respect, hatred, grudge, gratitude. */
    public static final int FOCUS_STRIDE = 6;

    /** How much standing in front of the agent is worth when the focus slots are handed out. */
    private static final float PRESENT_WEIGHT = 1.5F;

    /** And how much having just hit it is worth, which is more than anything an opinion can be. */
    private static final float STRUCK_WEIGHT = 2.0F;

    /** Insertion ordered so that two agents with the same rows hand out the same slots, save after save. */
    private final Map<UUID, Relationship> rows = new LinkedHashMap<>();

    /** Whose rows fill the focus slots this tick, most salient first. Reused: this is rebuilt every mind tick. */
    private final List<UUID> focus = new ArrayList<>(FOCUS_SLOTS);

    /** The row for that body, made neutral if there was none. */
    public Relationship of(UUID who) {

        Relationship row = this.rows.get(who);

        if (row == null) {

            row = new Relationship();
            this.rows.put(who, row);
            this.forgetTheFaintest();
        }

        return row;
    }

    /** The row for that body, or null where the agent has no opinion of it at all. */
    @Nullable
    public Relationship find(UUID who) {

        return this.rows.get(who);
    }

    public int size() {

        return this.rows.size();
    }

    public Map<UUID, Relationship> all() {

        return java.util.Collections.unmodifiableMap(this.rows);
    }

    /** One mind tick of forgetting, and the rows that have forgotten everything are dropped. */
    void decay() {

        this.rows.values().removeIf(row -> {

            row.decay();
            return row.isNeutral();
        });
    }

    /**
     * Who fills the four focus slots, most salient first.
     *
     * <p>The ordering is {@code arbitrator.focus_ids}': what the agent feels about them, plus a fixed bonus for standing
     * in front of it and a larger one for having just hit it. Ties are broken by the id, so the same rows in the same
     * company give the same slots every time rather than swapping about with the map's own order.
     *
     * @param here     whether that body is one the agent can see right now
     * @param struckMe whoever hit the agent inside the hit window, or null
     */
    List<UUID> focus(Predicate<UUID> here, @Nullable UUID struckMe) {

        this.focus.clear();

        if (this.rows.isEmpty()) {

            return this.focus;
        }

        List<UUID> ranked = new ArrayList<>(this.rows.keySet());

        ranked.sort((first, second) -> {

            int byWeight = Float.compare(this.weightOf(second, here, struckMe), this.weightOf(first, here, struckMe));
            return byWeight != 0 ? byWeight : first.compareTo(second);
        });

        for (int slot = 0; slot < FOCUS_SLOTS && slot < ranked.size(); slot++) {

            this.focus.add(ranked.get(slot));
        }

        return this.focus;
    }

    private float weightOf(UUID who, Predicate<UUID> here, @Nullable UUID struckMe) {

        Relationship row = this.rows.get(who);
        float weight = row == null ? 0.0F : row.weight();

        if (here.test(who)) {

            weight += PRESENT_WEIGHT;
        }

        if (who.equals(struckMe)) {

            weight += STRUCK_WEIGHT;
        }

        return weight;
    }

    /** Past the cap, the row that says least goes; it is the same rule the memory book uses on its own oldest. */
    private void forgetTheFaintest() {

        if (this.rows.size() <= CAP) {

            return;
        }

        UUID faintest = null;
        float least = Float.MAX_VALUE;

        for (Map.Entry<UUID, Relationship> entry : this.rows.entrySet()) {

            float weight = entry.getValue().weight();

            if (weight < least) {

                least = weight;
                faintest = entry.getKey();
            }
        }

        if (faintest != null) {

            this.rows.remove(faintest);
        }
    }

    public ListTag save() {

        ListTag list = new ListTag();

        for (Map.Entry<UUID, Relationship> entry : this.rows.entrySet()) {

            CompoundTag row = entry.getValue().save();

            row.putUUID("Who", entry.getKey());
            list.add(row);
        }

        return list;
    }

    public void load(ListTag list) {

        this.rows.clear();

        for (int index = 0; index < list.size() && this.rows.size() < CAP; index++) {

            CompoundTag row = list.getCompound(index);

            if (!row.hasUUID("Who")) {

                continue;
            }

            this.rows.put(row.getUUID("Who"), Relationship.load(row));
        }
    }
}
