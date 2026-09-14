package net.sievert.modularmobai.menu;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * The agent's inventory on screen: its armour and off hand along the top, a button that says whether it picks things up,
 * its pocket, its hotbar, and the player's own inventory under all of it, laid out as a chest is.
 *
 * <p>Client code, in common, beside the menu it is the face of, the way {@code AgentMobRenderer} sits beside the mob. Each
 * loader does nothing with it but point vanilla's menu-to-screen table at it.
 *
 * <p><b>It draws its own panel</b> rather than blitting one of the game's container textures. A chest's texture is a fixed
 * picture of six rows of nine with the player's inventory in a fixed place under them, and this screen is not that shape:
 * it has a row of five, a button, and two grids set apart. Stretching that picture to fit would look worse than the four
 * rectangles a slot actually is, and drawing it here keeps the whole layout in one file, in the colours vanilla's own
 * panels use, with no asset of Mojang's copied into this repository.
 */
public class AgentScreen extends AbstractContainerScreen<AgentMenu> {

    private static final int WIDTH = 176;
    private static final int HEIGHT = 239;

    private static final int BUTTON_X = 8;
    private static final int BUTTON_Y = 38;
    private static final int BUTTON_WIDTH = 160;
    private static final int BUTTON_HEIGHT = 20;

    /** Where the player's own inventory is named, eight pixels under the agent's hotbar. */
    private static final int INVENTORY_LABEL_Y = 145;

    // Vanilla's own panel colours, which is what makes a drawn panel look like a chest's rather than like a mod's.
    private static final int PANEL = 0xFFC6C6C6;
    private static final int PANEL_LIGHT = 0xFFFFFFFF;
    private static final int PANEL_DARK = 0xFF555555;
    private static final int SLOT = 0xFF8B8B8B;
    private static final int SLOT_SHADOW = 0xFF373737;

    private Button pickup;

    public AgentScreen(AgentMenu menu, Inventory playerInventory, Component title) {

        super(menu, playerInventory, title);

        this.imageWidth = WIDTH;
        this.imageHeight = HEIGHT;
        this.inventoryLabelY = INVENTORY_LABEL_Y;
    }

    @Override
    protected void init() {

        super.init();

        this.pickup = Button.builder(this.pickupLabel(), button -> this.togglePickup())
                .bounds(this.leftPos + BUTTON_X, this.topPos + BUTTON_Y, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build();

        this.addRenderableWidget(this.pickup);
    }

    /**
     * Follows the flag rather than remembering what was clicked, so the button tells the truth when two players have the
     * same agent open and when {@code /mmai pickup} changes it from under one of them. The value arrives over the menu's
     * data slot, which is vanilla's own one-integer channel; see {@link AgentMenu}.
     */
    @Override
    protected void containerTick() {

        super.containerTick();

        if (this.pickup != null) {

            this.pickup.setMessage(this.pickupLabel());
        }
    }

    private Component pickupLabel() {

        return Component.literal("Picks up items: " + (this.menu.picksUpItems() ? "on" : "off"));
    }

    /**
     * Vanilla's own button channel, the one an enchanting table's rows go down: the client says which button, the server
     * decides what it meant. No packet of this mod's, and so nothing to write twice for two loaders.
     */
    private void togglePickup() {

        if (this.minecraft != null && this.minecraft.gameMode != null) {

            this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, AgentMenu.TOGGLE_PICKUP);
        }
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {

        int left = this.leftPos;
        int top = this.topPos;

        graphics.fill(left, top, left + WIDTH, top + HEIGHT, PANEL);
        graphics.fill(left, top, left + WIDTH - 1, top + 1, PANEL_LIGHT);
        graphics.fill(left, top, left + 1, top + HEIGHT - 1, PANEL_LIGHT);
        graphics.fill(left + WIDTH - 1, top, left + WIDTH, top + HEIGHT, PANEL_DARK);
        graphics.fill(left, top + HEIGHT - 1, left + WIDTH, top + HEIGHT, PANEL_DARK);

        // Every slot the menu has, wherever the menu put it, so the panel can never disagree with what is clickable.
        for (Slot slot : this.menu.slots) {

            cell(graphics, left + slot.x - 1, top + slot.y - 1);
        }
    }

    /** One slot's sunken square: the face, a shadow along the top and left, a highlight along the bottom and right. */
    private static void cell(GuiGraphics graphics, int x, int y) {

        graphics.fill(x, y, x + 18, y + 18, SLOT);
        graphics.fill(x, y, x + 17, y + 1, SLOT_SHADOW);
        graphics.fill(x, y, x + 1, y + 17, SLOT_SHADOW);
        graphics.fill(x + 17, y + 1, x + 18, y + 18, PANEL_LIGHT);
        graphics.fill(x + 1, y + 17, x + 18, y + 18, PANEL_LIGHT);
    }
}
