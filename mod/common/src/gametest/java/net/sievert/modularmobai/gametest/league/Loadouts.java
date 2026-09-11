package net.sievert.modularmobai.gametest.league;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SwordItem;
import net.sievert.modularmobai.Constants;
import net.sievert.modularmobai.arena.Loadout;

/**
 * What the agent carries into a league fight, and what any agent it fights carries: one of these, drawn afresh every
 * fight, so the network learns each weapon rather than one.
 *
 * <pre>
 *   -Dmodular_mob_ai.league.loadouts=NAME,NAME   only these, by name; every one of them unless given
 * </pre>
 *
 * <p>Sword tiers, an axe, a sword with armour on, a shield with either, a bow, a crossbow, and a sword with a bow behind it
 * in the hotbar, for a fighter that has to choose. Every one is armed by {@link Loadout#equip}, the one call that arms any
 * fighter, and fought with under a player's rules. Mobs keep what they spawn with: a skeleton is a skeleton because of
 * its bow.
 *
 * <p>The scripted fighter uses everything it carries now, but as an opponent it still only ever gets something to swing,
 * see {@link #melee}: it is held at 1500 and every other rating is measured against it, so its strength has to stay put.
 */
public final class Loadouts {

    private Loadouts() {}

    private static final String PROPERTY = "modular_mob_ai.league.loadouts";

    public static final Loadout STONE_SWORD = Loadout.of("stone_sword", ItemStack.EMPTY, new ItemStack(Items.STONE_SWORD));

    public static final Loadout DIAMOND_SWORD = Loadout.of("diamond_sword", ItemStack.EMPTY, new ItemStack(Items.DIAMOND_SWORD));

    public static final Loadout AXE = Loadout.of("axe", ItemStack.EMPTY, new ItemStack(Items.IRON_AXE));

    public static final Loadout ARMOURED_SWORD = Loadout.SWORD.wearing("armoured_sword", new ItemStack(Items.IRON_HELMET),
            new ItemStack(Items.IRON_CHESTPLATE), new ItemStack(Items.IRON_LEGGINGS), new ItemStack(Items.IRON_BOOTS));

    public static final Loadout SWORD_AND_BOW = Loadout.of("sword_and_bow", ItemStack.EMPTY, new ItemStack(Items.IRON_SWORD),
            new ItemStack(Items.BOW), new ItemStack(Items.ARROW, 64));

    /** Every loadout there is, in a fixed order, which is the order a run without a trainer goes through them in. */
    public static final List<Loadout> ALL = List.of(Loadout.SWORD, STONE_SWORD, DIAMOND_SWORD, AXE, ARMOURED_SWORD,
            Loadout.SWORD_AND_SHIELD, Loadout.AXE_AND_SHIELD, Loadout.BOW, Loadout.CROSSBOW, SWORD_AND_BOW);

    /** The ones this process draws from, read once. */
    private static List<Loadout> enabled;

    public static synchronized List<Loadout> enabled() {

        if (enabled == null) {

            String named = System.getProperty(PROPERTY, "").trim();

            if (named.isEmpty()) {

                enabled = ALL;
            }

            else {

                List<String> names = Arrays.stream(named.toLowerCase(Locale.ROOT).split(",")).map(String::trim).toList();
                List<Loadout> chosen = new ArrayList<>();

                for (Loadout loadout : ALL) {

                    if (names.contains(loadout.name())) {

                        chosen.add(loadout);
                    }
                }

                if (chosen.isEmpty()) {

                    throw new IllegalArgumentException("None of '" + named + "' is a loadout; there are "
                            + ALL.stream().map(Loadout::name).toList());
                }

                enabled = List.copyOf(chosen);
                Constants.LOG.info("League fights draw from {} loadouts only: {}", enabled.size(), names);
            }
        }

        return enabled;
    }

    /** Whether the first thing in the hotbar is something to swing, which is all the scripted fighter is armed with here. */
    public static boolean melee(Loadout loadout) {

        return !loadout.hotbar().isEmpty()
                && (loadout.hotbar().get(0).getItem() instanceof SwordItem || loadout.hotbar().get(0).getItem() instanceof AxeItem);
    }
}
