package net.sievert.modularmobai.menu;

import org.jetbrains.annotations.Nullable;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.MenuType;
import net.sievert.modularmobai.Constants;

/**
 * The screens the mod opens, as the game registers them. One so far: the agent's inventory.
 *
 * <p>Built the way {@code ModEntities} is built, and for the same reason. What the menu <b>is</b> — its slots, its rules,
 * what its button does — is common code and written once. Only the act of making a {@link MenuType} differs between the
 * loaders, because vanilla's constructor for one is not public and each loader opens it its own way: Fabric widens it, and
 * NeoForge has {@code IMenuTypeExtension.create}. So each loader builds the type and hands it back here, and everything
 * else asks {@link #agentMenu()}.
 */
public final class ModMenus {

    private ModMenus() {}

    public static final ResourceLocation AGENT_MENU_ID = Constants.id("agent_menu");

    @Nullable
    private static MenuType<AgentMenu> agentMenu;

    /** Called by each loader as it registers the type, before any menu is opened. */
    public static void acceptAgentMenu(MenuType<AgentMenu> type) {

        agentMenu = type;
    }

    /**
     * The agent's inventory screen.
     *
     * @throws IllegalStateException for a build whose loader never registered it, which is a mistake in that loader's
     *                               initialiser rather than anything a player can do. A null here would come back as a
     *                               crash inside vanilla's menu machinery with nothing in it naming this mod.
     */
    public static MenuType<AgentMenu> agentMenu() {

        if (agentMenu == null) {

            throw new IllegalStateException("The agent's menu type was asked for before this loader registered it; see "
                    + "ModMenus and each loader's mod initialiser");
        }

        return agentMenu;
    }
}
