package net.sievert.modularmobai.menu;

import org.jetbrains.annotations.Nullable;

import com.mojang.datafixers.util.Pair;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.ItemStack;
import net.sievert.modularmobai.entity.agent.AgentInventory;
import net.sievert.modularmobai.entity.agent.AgentMob;
import net.sievert.modularmobai.entity.agent.MobControls;

/**
 * What a player sees when they right click an agent with an empty hand: everything it carries, laid out like a chest with
 * their own inventory under it, and one button that decides whether it picks things up.
 *
 * <p>The server is the authority for every slot, exactly as it is for a chest. This menu holds no items of its own: its
 * slots sit on an {@link AgentInventory}, which reads and writes the mob itself, so a bow dragged into the hotbar is in the
 * agent's hand on the same tick and the network reads it on the next. What the client has is the copy vanilla's own menu
 * synchronisation sends it, and every click comes back as a packet the server resolves.
 *
 * <p>Two things arrive over vanilla's own wiring rather than over a channel of this mod's, which is why there is no
 * networking here at all and nothing to write twice for two loaders:
 *
 * <ul>
 *   <li><b>whether it picks things up</b> is a {@link DataSlot}, the same one-integer channel a furnace's burn time uses,
 *       so the button can say "on" or "off" truthfully the moment the screen opens and the moment it changes;
 *   <li><b>the button itself</b> is {@link #clickMenuButton}, which is what an enchanting table's rows and a loom's
 *       patterns are: the client sends a button number, the server decides what it means.
 * </ul>
 *
 * <p>The screen closes itself when the agent dies, is a training agent, or walks out of reach, because {@code Player#tick}
 * asks {@link #stillValid} every tick and shuts any menu that says no. Nothing here has to watch for it.
 */
public class AgentMenu extends AbstractContainerMenu {

    /** The one button: picking things up, on or off. */
    public static final int TOGGLE_PICKUP = 0;

    // Where each block of slots begins, in this menu's own numbering. The agent's half is AgentInventory's order, so a
    // menu index under PLAYER_AT is a container index and nothing has to be mapped.
    public static final int HOTBAR_AT = AgentInventory.HOTBAR_AT;
    public static final int POCKET_AT = AgentInventory.POCKET_AT;
    public static final int OFFHAND_AT = AgentInventory.OFFHAND_AT;
    public static final int ARMOUR_AT = AgentInventory.ARMOUR_AT;
    public static final int PLAYER_AT = AgentInventory.SIZE;

    // Where the slots are drawn, which the screen reads back rather than repeating. A row is eighteen pixels and the first
    // column sits eight in from the left edge, as every vanilla container does.
    public static final int COLUMN = 8;
    public static final int PITCH = 18;

    /** The armour and the off hand, along the top; the pickup button has the row under them to itself. */
    public static final int GEAR_Y = 18;

    /** The pocket: three rows of nine. */
    public static final int POCKET_Y = 62;

    /** The agent's own hotbar, set apart under the pocket because it is the only part of this the agent fights from. */
    public static final int AGENT_HOTBAR_Y = 120;

    public static final int PLAYER_Y = 156;
    public static final int PLAYER_HOTBAR_Y = 214;

    /** The empty-slot pictures vanilla already ships, in the order {@link AgentInventory#ARMOUR} is in. */
    private static final ResourceLocation[] ARMOUR_ICONS = {InventoryMenu.EMPTY_ARMOR_SLOT_HELMET,
            InventoryMenu.EMPTY_ARMOR_SLOT_CHESTPLATE, InventoryMenu.EMPTY_ARMOR_SLOT_LEGGINGS,
            InventoryMenu.EMPTY_ARMOR_SLOT_BOOTS};

    private final Container carried;

    /** The agent on the server; null on the client, which only ever sees the copy of the slots vanilla sends it. */
    @Nullable
    private final AgentMob agent;

    private final DataSlot picksUpItems;

    /** What the client builds: the same slots over a container of its own, filled in by the server's first update. */
    public AgentMenu(int containerId, Inventory playerInventory) {

        this(containerId, playerInventory, new SimpleContainer(AgentInventory.SIZE), null);
    }

    /** What the server builds, on the agent that was clicked. */
    public AgentMenu(int containerId, Inventory playerInventory, AgentMob agent) {

        this(containerId, playerInventory, new AgentInventory(agent), agent);
    }

    private AgentMenu(int containerId, Inventory playerInventory, Container carried, @Nullable AgentMob agent) {

        super(ModMenus.agentMenu(), containerId);

        checkContainerSize(carried, AgentInventory.SIZE);

        this.carried = carried;
        this.agent = agent;

        // The agent's hotbar, which is what the network chooses between every tick and the only part of this it fights
        // from. First in the menu so that a shift click from the player's side lands where it is of some use.
        for (int slot = 0; slot < MobControls.HOTBAR_SIZE; slot++) {

            this.addSlot(new Slot(carried, HOTBAR_AT + slot, COLUMN + slot * PITCH, AGENT_HOTBAR_Y));
        }

        for (int row = 0; row < AgentMob.INVENTORY_SIZE / 9; row++) {

            for (int column = 0; column < 9; column++) {

                this.addSlot(new Slot(carried, POCKET_AT + row * 9 + column, COLUMN + column * PITCH,
                        POCKET_Y + row * PITCH));
            }
        }

        this.addSlot(new GearSlot(carried, OFFHAND_AT, COLUMN + 4 * PITCH, GEAR_Y, EquipmentSlot.OFFHAND,
                InventoryMenu.EMPTY_ARMOR_SLOT_SHIELD));

        for (int piece = 0; piece < AgentInventory.ARMOUR.length; piece++) {

            this.addSlot(new GearSlot(carried, ARMOUR_AT + piece, COLUMN + piece * PITCH, GEAR_Y,
                    AgentInventory.ARMOUR[piece], ARMOUR_ICONS[piece]));
        }

        for (int row = 0; row < 3; row++) {

            for (int column = 0; column < 9; column++) {

                this.addSlot(new Slot(playerInventory, 9 + row * 9 + column, COLUMN + column * PITCH,
                        PLAYER_Y + row * PITCH));
            }
        }

        for (int column = 0; column < 9; column++) {

            this.addSlot(new Slot(playerInventory, column, COLUMN + column * PITCH, PLAYER_HOTBAR_Y));
        }

        this.picksUpItems = this.addDataSlot(agent == null ? DataSlot.standalone() : new DataSlot() {

            @Override
            public int get() {

                return agent.picksUpItems() ? 1 : 0;
            }

            @Override
            public void set(int value) {

                agent.setPicksUpItems(value != 0);
            }
        });
    }

    /** Whether the agent is taking what it walks over, as the screen has been told it. */
    public boolean picksUpItems() {

        return this.picksUpItems.get() != 0;
    }

    /**
     * The button. The only one there is, and it flips the agent's own pickup flag, which is saved with it.
     *
     * <p>Vanilla has already asked {@link #stillValid} of the player who sent this, so nothing here has to check the reach
     * again; what it does check is that this menu has an agent at all, since the client's copy has none.
     */
    @Override
    public boolean clickMenuButton(Player player, int id) {

        if (id != TOGGLE_PICKUP || this.agent == null) {

            return false;
        }

        this.agent.setPicksUpItems(!this.agent.picksUpItems());

        return true;
    }

    @Override
    public boolean stillValid(Player player) {

        return this.carried.stillValid(player);
    }

    /**
     * Shift click: off the agent it goes to the player, and into the agent it goes to the slot it is made for if that is
     * empty, then to the hotbar, then to the pocket. Never to the off hand or the armour unless the item is for it, which
     * is {@link GearSlot}'s rule and the reason a sword shift clicked in lands in the hotbar rather than in the off hand.
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {

        Slot slot = this.slots.get(index);

        if (!slot.hasItem()) {

            return ItemStack.EMPTY;
        }

        ItemStack held = slot.getItem();
        ItemStack before = held.copy();

        if (index < PLAYER_AT) {

            if (!this.moveItemStackTo(held, PLAYER_AT, this.slots.size(), true)) {

                return ItemStack.EMPTY;
            }
        }

        else {

            int gear = gearSlotFor(held);
            boolean moved = gear >= 0 && !this.slots.get(gear).hasItem() && this.moveItemStackTo(held, gear, gear + 1, false);

            if (!held.isEmpty()) {

                moved |= this.moveItemStackTo(held, HOTBAR_AT, OFFHAND_AT, false);
            }

            if (!moved) {

                return ItemStack.EMPTY;
            }
        }

        if (held.isEmpty()) {

            slot.setByPlayer(ItemStack.EMPTY);
        }

        else {

            slot.setChanged();
        }

        slot.onTake(player, held);

        return before;
    }

    /** Which of this menu's gear slots an item is made for, or -1 for anything that goes in a hand or a pocket. */
    private static int gearSlotFor(ItemStack stack) {

        EquipmentSlot wanted = wornIn(stack);

        if (wanted == EquipmentSlot.OFFHAND) {

            return OFFHAND_AT;
        }

        for (int piece = 0; piece < AgentInventory.ARMOUR.length; piece++) {

            if (AgentInventory.ARMOUR[piece] == wanted) {

                return ARMOUR_AT + piece;
            }
        }

        return -1;
    }

    /**
     * Where the item itself says it is worn, which is the same question {@code LivingEntity#getEquipmentSlotForItem} asks
     * and is asked here without a body, since the client's copy of this menu has none.
     */
    private static EquipmentSlot wornIn(ItemStack stack) {

        Equipable equipable = Equipable.get(stack);

        return equipable == null ? EquipmentSlot.MAINHAND : equipable.getEquipmentSlot();
    }

    /** The off hand and the four armour slots: one piece each, and only the piece that belongs there. */
    private static final class GearSlot extends Slot {

        private final EquipmentSlot wanted;
        private final ResourceLocation empty;

        private GearSlot(Container container, int slot, int x, int y, EquipmentSlot wanted, ResourceLocation empty) {

            super(container, slot, x, y);

            this.wanted = wanted;
            this.empty = empty;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {

            // The off hand takes anything, as a player's does — a shield is the point of it, but a torch or a totem is a
            // player's business. Armour takes only what is worn there.
            return this.wanted == EquipmentSlot.OFFHAND || wornIn(stack) == this.wanted;
        }

        @Override
        public int getMaxStackSize() {

            return this.wanted == EquipmentSlot.OFFHAND ? super.getMaxStackSize() : 1;
        }

        @Override
        public Pair<ResourceLocation, ResourceLocation> getNoItemIcon() {

            return Pair.of(InventoryMenu.BLOCK_ATLAS, this.empty);
        }
    }
}
