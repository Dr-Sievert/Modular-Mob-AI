package net.sievert.modularmobai.arena;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;

/**
 * Every loadout a command, a save or the config can name, and the two that only a real game needs.
 *
 * <p>The presets on {@link Loadout} are what fights are trained and tested with, and they stay as they are: the bow and
 * the crossbow carry 64 arrows, one used a shot and never picked back up, which lasts a minute's fight with arrows to
 * spare since a full draw takes twenty ticks. Out in a real game there is no minute, and an agent that had shot its 64
 * would stand there holding an empty bow while every skeleton around it, whose arrows vanilla conjures out of nothing,
 * kept shooting.
 *
 * <p>So a real game gets {@code bow_infinity} and {@code crossbow_infinity}: the weapon enchanted with Infinity and a
 * single arrow, which by vanilla's own rule is never used up. That was chosen over the other two ways to stay armed:
 *
 * <ul>
 *   <li>It adds no rule of its own. The agent already fires through the item's own code, ProjectileWeaponItem#draw and
 *       #shoot, which is where vanilla applies Infinity for a player, so the arrow is spent or kept exactly as a
 *       player's would be, and the arrow that flies is one only a creative player can pick up, as a player's Infinity
 *       arrow is, so an agent is no source of free arrows either.
 *   <li>It looks to the network like what it was trained with. The observation sees a bow and a stack of arrows in the
 *       hotbar, never how many arrows or which enchantments, so a network that learned the 64 arrow bow meets the same
 *       numbers here.
 *   <li>Refilling between fights needs a between, which a real game does not have: an arena knows when its fight ends,
 *       a world does not. Picking arrows back up needs the agent to walk over to them, which nothing has ever taught
 *       it to do, so it would run dry anyway, only later.
 * </ul>
 *
 * Infinity on a crossbow is not something an enchanting table or an anvil gives, but a crossbow carrying it works for
 * anyone, a player included, and it spares the crossbow a rule of its own.
 *
 * <p>Enchantments are data in this version of the game, looked up in the world's registries, which is why these are
 * built for the world they are used in rather than held as constants.
 */
public final class Loadouts {

    private Loadouts() {}

    public static final String BOW_INFINITY = "bow_infinity";
    public static final String CROSSBOW_INFINITY = "crossbow_infinity";

    private static final List<Loadout> PRESETS = List.of(Loadout.SWORD, Loadout.SWORD_AND_SHIELD, Loadout.AXE_AND_SHIELD,
            Loadout.BOW, Loadout.CROSSBOW);

    /** Every name {@link #byName} takes, in the order a command offers them. */
    public static List<String> names() {

        List<String> names = new java.util.ArrayList<>(PRESETS.stream().map(Loadout::name).toList());
        names.add(BOW_INFINITY);
        names.add(CROSSBOW_INFINITY);
        return names;
    }

    /** The loadout by that name, built for a world with these registries, or empty for a name that is none. */
    public static Optional<Loadout> byName(String name, HolderLookup.Provider registries) {

        String wanted = name.trim().toLowerCase(Locale.ROOT);

        return switch (wanted) {

            case BOW_INFINITY -> Optional.of(infinity(BOW_INFINITY, new ItemStack(Items.BOW), registries));
            case CROSSBOW_INFINITY -> Optional.of(infinity(CROSSBOW_INFINITY, new ItemStack(Items.CROSSBOW), registries));
            default -> PRESETS.stream().filter(loadout -> loadout.name().equals(wanted)).findFirst();
        };
    }

    /** The weapon with Infinity in the first slot and one arrow in the second, where the trained loadouts keep 64. */
    private static Loadout infinity(String name, ItemStack weapon, HolderLookup.Provider registries) {

        weapon.enchant(registries.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.INFINITY), 1);
        return Loadout.of(name, ItemStack.EMPTY, weapon, new ItemStack(Items.ARROW));
    }
}
