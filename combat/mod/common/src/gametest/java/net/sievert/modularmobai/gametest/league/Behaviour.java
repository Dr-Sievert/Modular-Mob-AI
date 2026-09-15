package net.sievert.modularmobai.gametest.league;

import java.util.Locale;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.sievert.modularmobai.entity.agent.AgentMob;
import net.sievert.modularmobai.entity.agent.ExecutedControls;
import net.sievert.modularmobai.entity.agent.MobControls;

/**
 * What the agent did with its hands over one fight: which item it held longest, how often it swapped weapon, how many uses
 * it began, and how many arrows or bolts it loosed. Written down with the fight, so the viewer can say what a network
 * actually does with a loadout rather than only whether it won with it.
 *
 * <p>Every number comes from {@link ExecutedControls}, what the body actually did, and not from what the network asked for.
 * A use pressed with an empty hand does nothing and is not a use; a slot chosen that holds the same kind of item is not a
 * swap, because it does not restart the attack cooldown either; and a bow let go of too early sends no arrow and is not a
 * shot. Those are the same distinctions the network itself is fed, which is the point: this says what happened.
 *
 * <p>Counted tick by tick over the fight rather than sampled at the end, because none of it survives the tick it happened
 * on. One of these belongs to one fight, and {@link #tick} is called once per tick while it runs.
 *
 * <p>The first league run is what this is for. A network that had never pressed use held it on 0 of 79,724 ticks and fired
 * no arrows over 400 fights, and nothing short of reading replays by hand said so; see docs/training.md.
 */
public final class Behaviour {

    /** What a fight with nothing in the agent's hands, or nothing to say, is written down as. */
    public static final String NOTHING = "-";

    /** How long each hotbar slot was held, and what was in it when it was first held, which is what named it. */
    private final int[] heldTicks = new int[MobControls.HOTBAR_SIZE];
    private final String[] heldItem = new String[MobControls.HOTBAR_SIZE];

    private int swaps;
    private int uses;
    private int shots;

    /** Whether an item was in use on the tick before, so that a use is counted once and not on every tick it lasts. */
    private boolean using;

    /** The tick that just ran, as the body recorded it. Called once a tick while the fight is on. */
    public void tick(AgentMob agent) {

        ExecutedControls executed = agent.executed();

        int slot = Mth.clamp(executed.selectedSlot, 0, MobControls.HOTBAR_SIZE - 1);
        this.heldTicks[slot]++;

        // What was in the slot the first time it was held. Read once rather than every tick, since the hotbar is the same
        // hotbar all fight: what changes in it is a stack running down, an arrow spent or a shield worn, and the item a slot
        // holds is what the slot is for.
        if (this.heldItem[slot] == null) {

            this.heldItem[slot] = item(agent.getHotbarItem(slot));
        }

        this.swaps += executed.swappedWeapon ? 1 : 0;
        this.shots += executed.shotFired ? 1 : 0;

        boolean inUse = executed.using || executed.usingOffhand;

        this.uses += inUse && !this.using ? 1 : 0;
        this.using = inUse;
    }

    /**
     * The item the agent held for more of the fight than any other, as the game names it: {@code iron_sword}, {@code bow},
     * or {@link #NOTHING} for a fight it kept an empty slot held. Ties go to the lower slot, which is the hand a loadout
     * arms first.
     */
    public String weapon() {

        int held = -1;

        for (int slot = 0; slot < this.heldTicks.length; slot++) {

            if (this.heldTicks[slot] > 0 && (held < 0 || this.heldTicks[slot] > this.heldTicks[held])) {

                held = slot;
            }
        }

        return held < 0 || this.heldItem[held] == null ? NOTHING : this.heldItem[held];
    }

    /** Ticks on which a swap changed the kind of item held, which is what restarts a player's attack cooldown. */
    public int swaps() {

        return this.swaps;
    }

    /** Uses begun: a bow drawn, a shield raised, a block placed. Not ticks, so holding a shield up all fight is one. */
    public int uses() {

        return this.uses;
    }

    /** Arrows and bolts that actually left, which is a bow let go of with something in the string, or a crossbow fired. */
    public int shots() {

        return this.shots;
    }

    private static String item(ItemStack stack) {

        return stack.isEmpty() ? NOTHING : BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().toLowerCase(Locale.ROOT);
    }
}
