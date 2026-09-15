package net.sievert.modularmobai.entity.agent.mind;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.TridentItem;
import net.sievert.modularmobai.allegiance.Allegiance;
import net.sievert.modularmobai.brain.schema.EnemySlots;
import net.sievert.modularmobai.entity.agent.AgentMob;
import net.sievert.modularmobai.entity.agent.MobControls;

/**
 * The mind one agent carries between ticks: how it feels, what it wants, what it is like, who it knows, and what it
 * remembers.
 *
 * <p>It is the hand-written half of {@code mind/}, ported as ordinary Java from {@code mind/docs/design.md}: emotions
 * that decay toward a resting point, needs that climb, eight traits rolled once and never moved, a relationship row per
 * body it has an opinion about, and a bounded book of episodes behind those rows. {@link MindEvent} is the table of what
 * moves them and {@link Events} applies it; nothing else in the mod writes to any of this.
 *
 * <p><b>Nothing here decides anything yet.</b> Stage B is the state and the rules that move it, with the combat brain
 * still driving the body. The arbitrator that reads {@link #observe} and chooses a skill is stage C, which is why every
 * part of this is observable on its own — through {@code /mmai}, through a game test, and through the 69 columns — before
 * anything acts on it.
 *
 * <p><b>The mind's own clock.</b> The sim ticks once per decision; the game ticks twenty times a second. One
 * <b>mind tick</b> is {@link #MIND_TICK} game ticks, which is the rate the decisions model is meant to run at, so every
 * rate in {@code dwarfsim} — the decay of a feeling, the climb of a need, the fading of a memory — is carried over
 * unchanged and means the same thing it meant there. At ten game ticks an emotion's half-life is a few seconds and a
 * bitter agent's grudge is about ten minutes, which is what those numbers were tuned to feel like.
 *
 * <p><b>A training agent has no mind.</b> {@link #isAwake} is false for one, and then nothing here is ticked, no event
 * reaches it, and it is never a witness: the combat half is fought by bodies and weights and the cost of a mind is not
 * paid by a training worker. Everything in stage B is therefore exercised by agents of the kind a player meets.
 */
public final class MindState {

    /** How many game ticks make one mind tick; see the class comment. */
    public static final int MIND_TICK = 10;

    /** How long after being hit an agent still counts as under attack, in mind ticks. */
    public static final int HIT_WINDOW = 12;

    /** How far off a body has to be to be company at all. The combat view reaches four times as far; this is a room. */
    public static final double COMPANY_RANGE = 12.0D;

    /** And how many of them are looked at, nearest first, so a mind tick costs the same in a crowd as in a corridor. */
    private static final int COMPANY_CAP = 16;

    /** What the observation divides the count of bodies standing about by, which is the frozen layout's own number. */
    public static final float CROWD_SCALE = 6.0F;

    /** The inventory summary's five columns, in the layout's order. */
    public static final int INVENTORY_COLUMNS = 5;

    public static final int ORE = 0;
    public static final int GOLD = 1;
    public static final int FOOD = 2;
    public static final int DRINK = 3;
    public static final int WEAPON = 4;

    /** What counts as a lot of each, for normalising into the vector. {@code INVENTORY_SCALE} in {@code schema.py}. */
    private static final float[] INVENTORY_SCALE = {10.0F, 20.0F, 10.0F, 10.0F, 1.0F};

    private final AgentMob agent;

    private final float[] emotions = new float[Emotion.COUNT];
    private final float[] baseline = new float[Emotion.COUNT];
    private final float[] needs = new float[Need.COUNT];
    private final float[] traits = new float[Trait.COUNT];

    private float health = 1.0F;
    private final float[] inventory = new float[INVENTORY_COLUMNS];

    private final Relationships relationships = new Relationships();
    private final MemoryBook memories = new MemoryBook();

    /** The mind's own clock, in mind ticks. Everything a memory's age is measured in. */
    private long tick;

    /** Game ticks since the last mind tick. */
    private int since;

    @Nullable
    private UUID lastHitBy;

    private long lastHitTick = Long.MIN_VALUE;

    /** The temperament it was given by name, or null where it rolled its own. Kept for {@code /mmai} and for saving. */
    @Nullable
    private Temperament temperament;

    /** Who the agent can see right now, refreshed once a mind tick. What the focus slots and the crowd column read. */
    private final Set<UUID> company = new HashSet<>();

    private int crowd;
    private boolean monsterHere;
    private float monsterHealth;

    public MindState(AgentMob agent) {

        this.agent = agent;

        // Something to be before anything has rolled it, so a mind read before its first tick is a mind and not zeros.
        Temperament.EVEN.apply(this.traits);
    }

    /**
     * Rolls a mind of its own: a resting point for each feeling, needs part of the way up, and eight traits. The same
     * ranges the sim rolls from, out of the entity's own random, so two agents spawned by one command are two people.
     */
    public void roll(RandomSource random) {

        this.baseline[Emotion.ANGER.ordinal()] = range(random, 0.02F, 0.12F);
        this.baseline[Emotion.FEAR.ordinal()] = range(random, 0.02F, 0.12F);
        this.baseline[Emotion.HAPPINESS.ordinal()] = range(random, 0.30F, 0.60F);
        this.baseline[Emotion.GRIEF.ordinal()] = 0.0F;

        System.arraycopy(this.baseline, 0, this.emotions, 0, Emotion.COUNT);

        for (Need need : Need.ALL) {

            this.needs[need.ordinal()] = range(random, 0.05F, 0.35F);
        }

        for (Trait trait : Trait.ALL) {

            this.traits[trait.ordinal()] = range(random, Trait.LOWEST, Trait.HIGHEST);
        }

        this.temperament = null;
    }

    /** Gives it a temperament by name instead of a roll: a whole set of traits, nothing left to chance. */
    public void become(Temperament preset) {

        preset.apply(this.traits);
        this.temperament = preset;
    }

    @Nullable
    public Temperament temperament() {

        return this.temperament;
    }

    /**
     * Whether this mind runs at all. False for a training agent, whose fights are the combat half's and who would pay
     * for a mind nothing in a training run reads; see the class comment.
     */
    public boolean isAwake() {

        return !this.agent.isTraining();
    }

    /** Whose mind this is, which is the id every relationship and every memory is keyed by. */
    public UUID owner() {

        return this.agent.getUUID();
    }

    public AgentMob agent() {

        return this.agent;
    }

    /** The mind's own clock, in mind ticks: what a memory's age and the hit window are measured in. */
    public long now() {

        return this.tick;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The state itself
    // ---------------------------------------------------------------------------------------------------------------

    public float emotion(Emotion which) {

        return this.emotions[which.ordinal()];
    }

    public float baseline(Emotion which) {

        return this.baseline[which.ordinal()];
    }

    public float need(Need which) {

        return this.needs[which.ordinal()];
    }

    public float trait(Trait which) {

        return this.traits[which.ordinal()];
    }

    /** The health fraction as the mind last read it, which is what the observation carries. */
    public float health() {

        return this.health;
    }

    /** One column of the inventory summary, already scaled to 0..1; see {@link #ORE} and the rest. */
    public float inventory(int column) {

        return this.inventory[column];
    }

    public Relationships relationships() {

        return this.relationships;
    }

    public MemoryBook memories() {

        return this.memories;
    }

    /** How well disposed this mind is toward that body, -1 to 1. Zero for one it has no opinion of. */
    public float likes(UUID who) {

        Relationship row = this.relationships.find(who);

        return row == null ? 0.0F : row.likes();
    }

    /** Whoever last hurt the agent, or null where nothing has inside the window. */
    @Nullable
    public UUID lastHitBy() {

        return this.underAttack() ? this.lastHitBy : null;
    }

    /** Whether the agent was hit inside the last {@link #HIT_WINDOW} mind ticks. */
    public boolean underAttack() {

        return this.lastHitBy != null && this.tick - this.lastHitTick <= HIT_WINDOW;
    }

    /** How long ago the agent was last hit, in mind ticks, or a long time where it never was. */
    public long sinceHit() {

        return this.lastHitTick == Long.MIN_VALUE ? Long.MAX_VALUE : this.tick - this.lastHitTick;
    }

    /** How many bodies were standing with the agent when the mind last looked. */
    public int crowd() {

        return this.crowd;
    }

    public boolean monsterHere() {

        return this.monsterHere;
    }

    public float monsterHealth() {

        return this.monsterHealth;
    }

    /** Whether the agent could see that body when the mind last looked; what the focus slots rank by. */
    public boolean inCompany(UUID who) {

        return this.company.contains(who);
    }

    /** Who fills the four focus slots this mind tick, most salient first. */
    public List<UUID> focus() {

        return this.relationships.focus(this.company::contains, this.lastHitBy());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // What moves it
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Moves one feeling, modulated by the traits that own it: anger gains scale with temper, fear gains with cowardice,
     * and a loss is never modulated. The one place a feeling changes.
     *
     * @return whether anything actually moved
     */
    public boolean feel(Emotion which, float amount) {

        float moved = amount;

        if (moved > 0.0F) {

            if (which == Emotion.ANGER) {

                moved *= 0.6F + 0.8F * this.trait(Trait.TEMPER);
            }

            else if (which == Emotion.FEAR) {

                moved *= 1.4F - 0.8F * this.trait(Trait.BRAVERY);
            }
        }

        if (moved == 0.0F) {

            return false;
        }

        float before = this.emotions[which.ordinal()];
        float after = Mth.clamp(before + moved, 0.0F, 1.0F);

        this.emotions[which.ordinal()] = after;

        return after != before;
    }

    /** Moves one field of how this mind sees that body, with the diminishing returns {@link Relationship#move} applies. */
    public boolean regard(UUID who, Relationship.Regard field, float amount) {

        if (who.equals(this.owner())) {

            return false;
        }

        boolean moved = this.relationships.of(who).move(field, amount);

        if (moved) {

            // A grudge is weighted by how much the holder likes the victim, so an opinion that moves invalidates the sums.
            this.memories.invalidate();
        }

        return moved;
    }

    /** Files one episode away, at the loudness the event table gives it. */
    public Memory remember(MindEvent kind, @Nullable UUID actor, @Nullable UUID target, float intensity,
            Memory.Source source, @Nullable UUID from, int witnesses) {

        return this.memories.remember(this.tick, this.trait(Trait.FORGIVENESS),
                new Memory(this.tick, kind, actor, target, intensity, source, from, witnesses));
    }

    /**
     * A standing pull toward doing something, left by a request or an order that carried no structured ask.
     *
     * <p>Not saved: it lives for {@link Speech#REQUEST_TTL} mind ticks, which is less than a minute, and a world that
     * reloads inside one is not a case worth a tag. The arbitrator weighs it by how much the agent trusts whoever asked,
     * which is stage C's {@code request_pull} term.
     */
    public record Request(@Nullable UUID from, String topic, float urgency, long until) {}

    @Nullable
    private Request request;

    /** Whatever the agent was last asked to do and has not run out of patience with, or null. */
    @Nullable
    public Request request() {

        return this.request != null && this.tick <= this.request.until() ? this.request : null;
    }

    /** Somebody asked for something. {@code REQUEST} and {@code COMMAND} are not events; they are this. */
    void wasAsked(@Nullable LivingEntity speaker, Utterance line) {

        this.request = new Request(speaker == null ? null : speaker.getUUID(), line.topic(), line.urgency(),
                this.tick + Speech.REQUEST_TTL);
    }

    /** Whoever just hurt the agent, which is what the hit window and the focus slots read. */
    public void struckBy(@Nullable UUID who) {

        if (who == null || who.equals(this.owner())) {

            return;
        }

        this.lastHitBy = who;
        this.lastHitTick = this.tick;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The tick
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * One game tick of mind. Nine ticks in ten this is a decrement and a return; on the tenth the agent looks about it,
     * every feeling moves a little toward where that agent rests, every need climbs, and every opinion fades a shade.
     */
    public void tick() {

        if (!this.isAwake()) {

            return;
        }

        if (++this.since < MIND_TICK) {

            return;
        }

        this.since = 0;
        this.tick++;

        this.look();
        this.refresh();
        this.decay(1);
    }

    /**
     * Emotions toward the baseline, needs upward, relationships toward neutral: {@code MindState.decay} in the sim,
     * applied that many mind ticks over. Public because a test that wants an hour of drift should not have to wait one.
     */
    public void decay(int mindTicks) {

        for (int pass = 0; pass < mindTicks; pass++) {

            for (Emotion emotion : Emotion.ALL) {

                int at = emotion.ordinal();
                float value = this.emotions[at];

                this.emotions[at] = Mth.clamp(value + (this.baseline[at] - value) * emotion.decay(), 0.0F, 1.0F);
            }

            for (Need need : Need.ALL) {

                int at = need.ordinal();

                this.needs[at] = Mth.clamp(this.needs[at] + need.rate(), 0.0F, 1.0F);
            }

            this.relationships.decay();
        }

        this.memories.invalidate();
    }

    /** Reads the body: how hurt it is and what it is carrying, which are two blocks of the observation. */
    private void refresh() {

        float max = this.agent.getMaxHealth();

        this.health = max <= 0.0F ? 0.0F : Mth.clamp(this.agent.getHealth() / max, 0.0F, 1.0F);

        this.summarise();
    }

    /**
     * Who is standing with the agent, by the same rule the combat view perceives a body: seen in the cone with a line of
     * sight, heard close by, or felt because it just hit the agent. One query of the level per mind tick, over a room's
     * worth of the world rather than the combat view's thirty two blocks, and at most {@link #COMPANY_CAP} bodies are
     * looked at, so the cost of standing in a crowd is the same as the cost of standing in a corridor.
     */
    private void look() {

        this.company.clear();
        this.crowd = 0;
        this.monsterHere = false;
        this.monsterHealth = 0.0F;

        if (!(this.agent.level() instanceof net.minecraft.server.level.ServerLevel level)) {

            return;
        }

        List<LivingEntity> near = level.getEntitiesOfClass(LivingEntity.class,
                this.agent.getBoundingBox().inflate(COMPANY_RANGE),
                body -> body != this.agent && body.isAlive());

        if (near.size() > COMPANY_CAP) {

            near = new ArrayList<>(near);
            near.sort(java.util.Comparator.comparingDouble(this.agent::distanceToSqr));
            near = near.subList(0, COMPANY_CAP);
        }

        double nearestMonster = Double.MAX_VALUE;

        for (LivingEntity body : near) {

            if (!EnemySlots.perceives(this.agent, body)) {

                continue;
            }

            this.company.add(body.getUUID());

            // A monster is what is here to be fought; everything else is who is here. The sim's crowd column counts the
            // dwarves in the room, not the troll in it, and the two columns beside it are the troll.
            if (body instanceof Monster && Allegiance.isEnemy(this.agent, body)) {

                this.monsterHere = true;

                double distance = this.agent.distanceToSqr(body);

                if (distance < nearestMonster) {

                    nearestMonster = distance;
                    this.monsterHealth = body.getMaxHealth() <= 0.0F ? 0.0F
                            : Mth.clamp(body.getHealth() / body.getMaxHealth(), 0.0F, 1.0F);
                }
            }

            else {

                this.crowd++;
            }
        }
    }

    /** What the agent is carrying, in the five columns the layout names. Hotbar and pocket both, as a pack is a pack. */
    private void summarise() {

        float ore = 0.0F;
        float gold = 0.0F;
        float food = 0.0F;
        float drink = 0.0F;
        float weapon = 0.0F;

        for (int slot = 0; slot < MobControls.HOTBAR_SIZE + AgentMob.INVENTORY_SIZE; slot++) {

            ItemStack stack = slot < MobControls.HOTBAR_SIZE ? this.agent.getHotbarItem(slot)
                    : this.agent.getInventoryItem(slot - MobControls.HOTBAR_SIZE);

            if (stack.isEmpty()) {

                continue;
            }

            int count = stack.getCount();

            if (isOre(stack)) {

                ore += count;
            }

            if (isTreasure(stack)) {

                gold += count;
            }

            if (stack.has(DataComponents.FOOD)) {

                food += count;
            }

            if (isDrink(stack)) {

                drink += count;
            }

            weapon = Math.max(weapon, quality(stack));
        }

        this.inventory[ORE] = Math.min(1.0F, ore / INVENTORY_SCALE[ORE]);
        this.inventory[GOLD] = Math.min(1.0F, gold / INVENTORY_SCALE[GOLD]);
        this.inventory[FOOD] = Math.min(1.0F, food / INVENTORY_SCALE[FOOD]);
        this.inventory[DRINK] = Math.min(1.0F, drink / INVENTORY_SCALE[DRINK]);
        this.inventory[WEAPON] = Math.min(1.0F, weapon / INVENTORY_SCALE[WEAPON]);
    }

    /** Raw metal and the stone it came out of: what a dwarf would call ore. */
    private static boolean isOre(ItemStack stack) {

        return stack.is(Items.RAW_IRON) || stack.is(Items.RAW_COPPER) || stack.is(Items.RAW_GOLD)
                || stack.is(Items.COAL) || stack.is(Items.IRON_INGOT) || stack.is(Items.COPPER_INGOT)
                || stack.is(Items.REDSTONE) || stack.is(Items.LAPIS_LAZULI);
    }

    /** And what it would count as wealth. */
    private static boolean isTreasure(ItemStack stack) {

        return stack.is(Items.GOLD_INGOT) || stack.is(Items.GOLD_NUGGET) || stack.is(Items.GOLD_BLOCK)
                || stack.is(Items.EMERALD) || stack.is(Items.DIAMOND) || stack.is(Items.NETHERITE_INGOT);
    }

    /** Ale, or the nearest thing a world without a tavern in it has. */
    private static boolean isDrink(ItemStack stack) {

        return stack.is(Items.POTION) || stack.is(Items.HONEY_BOTTLE) || stack.is(Items.MILK_BUCKET)
                || stack.is(Items.MUSHROOM_STEW) || stack.is(Items.BEETROOT_SOUP) || stack.is(Items.RABBIT_STEW);
    }

    /**
     * How good a weapon that is, 0 to 1. A tier's own attack bonus, which is what separates a wooden sword from a
     * netherite one, and a flat reading for the things that have no tier: a drawn weapon is worth a good sword and a
     * trident a little more.
     */
    private static float quality(ItemStack stack) {

        Item item = stack.getItem();

        if (item instanceof TridentItem) {

            return 0.7F;
        }

        if (item instanceof BowItem || item instanceof CrossbowItem) {

            return 0.6F;
        }

        if (!(item instanceof SwordItem) && !(item instanceof AxeItem)) {

            return 0.0F;
        }

        if (item instanceof TieredItem tiered) {

            // Wood 0.2 through netherite 1.0, which is the bonus a tier adds to a swing over the one below nothing.
            return Mth.clamp((tiered.getTier().getAttackDamageBonus() + 1.0F) / 5.0F, 0.0F, 1.0F);
        }

        return 0.4F;
    }

    private static float range(RandomSource random, float lowest, float highest) {

        return lowest + random.nextFloat() * (highest - lowest);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // What it is for
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Fills in the decisions model's 69 observation columns, in the offset order
     * {@code shared/models/decisions/layout.json} gives them. See {@link MindObservation}, which is where the table of
     * columns to sources lives and where it is held against that file.
     */
    public void observe(float[] into) {

        MindObservation.write(this, into, 0);
    }

    /**
     * One utterance heard, mapped onto the event table exactly as {@code mind/dwarfsim/speech.py} maps it. Stage C's
     * chat hook classifies a line and calls this; nothing in stage B produces an {@link Utterance}, which is why this is
     * written now and tested now rather than written then.
     */
    public void hear(@Nullable LivingEntity speaker, Utterance said) {

        Speech.hear(this, speaker, said);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Saving
    // ---------------------------------------------------------------------------------------------------------------

    public CompoundTag save() {

        CompoundTag tag = new CompoundTag();

        CompoundTag feelings = new CompoundTag();
        CompoundTag resting = new CompoundTag();

        for (Emotion emotion : Emotion.ALL) {

            feelings.putFloat(emotion.key(), this.emotions[emotion.ordinal()]);
            resting.putFloat(emotion.key(), this.baseline[emotion.ordinal()]);
        }

        CompoundTag wants = new CompoundTag();

        for (Need need : Need.ALL) {

            wants.putFloat(need.key(), this.needs[need.ordinal()]);
        }

        CompoundTag like = new CompoundTag();

        for (Trait trait : Trait.ALL) {

            like.putFloat(trait.key(), this.traits[trait.ordinal()]);
        }

        tag.put("Emotions", feelings);
        tag.put("Baseline", resting);
        tag.put("Needs", wants);
        tag.put("Traits", like);
        tag.put("Relationships", this.relationships.save());
        tag.put("Memories", this.memories.save());
        tag.putLong("Tick", this.tick);

        if (this.temperament != null) {

            tag.putString("Temperament", this.temperament.name());
        }

        if (this.lastHitBy != null && this.lastHitTick != Long.MIN_VALUE) {

            tag.putUUID("LastHitBy", this.lastHitBy);
            tag.putLong("LastHitTick", this.lastHitTick);
        }

        return tag;
    }

    /**
     * Reads a saved mind back.
     *
     * <p>A mind that fails to load is a <b>fresh</b> mind and never a half loaded one: anything missing from the tag
     * keeps what the roll gave it, and a relationship or a memory naming something this build does not have is dropped
     * rather than guessed at. The body, the crowd and the company are not saved — they are read from the world on the
     * first mind tick after loading, which is a fraction of a second later.
     */
    public void load(CompoundTag tag) {

        if (tag.contains("Emotions", Tag.TAG_COMPOUND)) {

            CompoundTag feelings = tag.getCompound("Emotions");
            CompoundTag resting = tag.getCompound("Baseline");

            for (Emotion emotion : Emotion.ALL) {

                if (feelings.contains(emotion.key())) {

                    this.emotions[emotion.ordinal()] = Mth.clamp(feelings.getFloat(emotion.key()), 0.0F, 1.0F);
                }

                if (resting.contains(emotion.key())) {

                    this.baseline[emotion.ordinal()] = Mth.clamp(resting.getFloat(emotion.key()), 0.0F, 1.0F);
                }
            }
        }

        if (tag.contains("Needs", Tag.TAG_COMPOUND)) {

            CompoundTag wants = tag.getCompound("Needs");

            for (Need need : Need.ALL) {

                if (wants.contains(need.key())) {

                    this.needs[need.ordinal()] = Mth.clamp(wants.getFloat(need.key()), 0.0F, 1.0F);
                }
            }
        }

        if (tag.contains("Traits", Tag.TAG_COMPOUND)) {

            CompoundTag like = tag.getCompound("Traits");

            for (Trait trait : Trait.ALL) {

                if (like.contains(trait.key())) {

                    this.traits[trait.ordinal()] = Mth.clamp(like.getFloat(trait.key()), 0.0F, 1.0F);
                }
            }
        }

        if (tag.contains("Relationships", Tag.TAG_LIST)) {

            this.relationships.load(tag.getList("Relationships", Tag.TAG_COMPOUND));
        }

        if (tag.contains("Memories", Tag.TAG_LIST)) {

            this.memories.load(tag.getList("Memories", Tag.TAG_COMPOUND));
        }

        this.tick = tag.getLong("Tick");
        this.temperament = tag.contains("Temperament", Tag.TAG_STRING) ? Temperament.byName(tag.getString("Temperament")) : null;

        if (tag.hasUUID("LastHitBy")) {

            this.lastHitBy = tag.getUUID("LastHitBy");
            this.lastHitTick = tag.getLong("LastHitTick");
        }

        this.memories.invalidate();
    }
}
