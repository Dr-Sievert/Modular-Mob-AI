package net.sievert.modularmobai.entity.agent;

import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Everything an agent carries, as one flat container: its nine hotbar slots, the pocket behind them, the off hand and the
 * four armour slots. It is what {@code AgentMenu} hangs slots on, and the only thing that reaches into the agent's own
 * storage from outside.
 *
 * <p>It holds no items of its own. Every read and write goes straight to the agent, so a stack a player drags in is on the
 * mob the moment it lands, and the hotbar's rule — that the main hand is a view of whichever slot is held — keeps working
 * because the agent is the one doing the setting. There is nothing to keep in step and nothing to write back on closing.
 *
 * <p>The order is the order the screen reads in, and it is also the order the observation would: the hotbar first, since
 * that is the part of this the agent actually fights from and the part the network sees.
 */
public final class AgentInventory implements Container {

    /** Where each part starts. The hotbar is {@code MobControls.HOTBAR_SIZE} long and the pocket {@code INVENTORY_SIZE}. */
    public static final int HOTBAR_AT = 0;
    public static final int POCKET_AT = HOTBAR_AT + MobControls.HOTBAR_SIZE;
    public static final int OFFHAND_AT = POCKET_AT + AgentMob.INVENTORY_SIZE;
    public static final int ARMOUR_AT = OFFHAND_AT + 1;

    /** Head, chest, legs, feet, which is the order they are shown in and the order a player's own screen uses. */
    public static final EquipmentSlot[] ARMOUR =
            {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    public static final int SIZE = ARMOUR_AT + ARMOUR.length;

    /** How far a player may stand from an agent and still have its screen open, as a chest's eight blocks. */
    private static final double REACH = 8.0D;

    private final AgentMob agent;

    public AgentInventory(AgentMob agent) {

        this.agent = agent;
    }

    public AgentMob agent() {

        return this.agent;
    }

    @Override
    public int getContainerSize() {

        return SIZE;
    }

    @Override
    public boolean isEmpty() {

        for (int slot = 0; slot < SIZE; slot++) {

            if (!this.getItem(slot).isEmpty()) {

                return false;
            }
        }

        return true;
    }

    @Override
    public ItemStack getItem(int slot) {

        if (slot < POCKET_AT) {

            return this.agent.getHotbarItem(slot);
        }

        if (slot < OFFHAND_AT) {

            return this.agent.getInventoryItem(slot - POCKET_AT);
        }

        if (slot == OFFHAND_AT) {

            return this.agent.getItemBySlot(EquipmentSlot.OFFHAND);
        }

        return this.agent.getItemBySlot(ARMOUR[Math.floorMod(slot - ARMOUR_AT, ARMOUR.length)]);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {

        if (slot < POCKET_AT) {

            this.agent.setHotbarItem(slot, stack);
        }

        else if (slot < OFFHAND_AT) {

            this.agent.setInventoryItem(slot - POCKET_AT, stack);
        }

        else if (slot == OFFHAND_AT) {

            this.agent.setItemSlot(EquipmentSlot.OFFHAND, stack);
        }

        else {

            this.agent.setItemSlot(ARMOUR[Math.floorMod(slot - ARMOUR_AT, ARMOUR.length)], stack);
        }
    }

    @Override
    public ItemStack removeItem(int slot, int count) {

        ItemStack held = this.getItem(slot);

        if (held.isEmpty() || count <= 0) {

            return ItemStack.EMPTY;
        }

        ItemStack taken = held.split(count);

        // Split leaves the stack in place when it does not empty it, so the agent is told either way: the hotbar's main
        // hand view is written from the setter and would otherwise be a tick behind, or wrong.
        this.setItem(slot, held.isEmpty() ? ItemStack.EMPTY : held);

        return taken;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {

        ItemStack held = this.getItem(slot).copy();

        this.setItem(slot, ItemStack.EMPTY);

        return held;
    }

    @Override
    public void setChanged() {

        // A stack the menu changed in place, rather than through setItem: the main hand is a view of the held slot and has
        // to follow it. Cheap, and the alternative is a hand holding a stack that is no longer there.
        this.agent.setHotbarItem(this.agent.getSelectedSlot(), this.agent.getHotbarItem(this.agent.getSelectedSlot()));
    }

    /**
     * Whether the screen may stay open: the agent alive, in the player's world, within reach, and not a training one — an
     * arena's agent has no screen at all, and by the time one could be asked for it the fight would already be a different
     * fight. The server closes the screen on its own the moment this turns false; see {@code Player#tick}.
     */
    @Override
    public boolean stillValid(Player player) {

        return this.agent.isAlive() && !this.agent.isTraining() && !player.isSpectator()
                && this.agent.level() == player.level() && player.distanceToSqr(this.agent) <= REACH * REACH;
    }

    @Override
    public void clearContent() {

        for (int slot = 0; slot < SIZE; slot++) {

            this.setItem(slot, ItemStack.EMPTY);
        }
    }
}
