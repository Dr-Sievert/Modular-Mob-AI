package net.sievert.modularmobai.gametest.league;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.SwordItem;
import net.sievert.modularmobai.Constants;
import net.sievert.modularmobai.arena.Loadout;

/**
 * What the agent carries into a league fight, and what any agent it fights carries: one of these, drawn afresh every
 * fight, so the network learns each weapon rather than one.
 *
 * <p>In a training run the agent's own is not drawn here at all. It comes with the opponent, as one pairing of the two, so
 * that a bow is handed out against what a bow can learn from; {@link Pairings} says why, and the trainer is told which
 * loadouts a run fields through roster.csv. Everywhere else — an evaluation fight, a run with no trainer, whatever the
 * agent's opponent carries — the draw is even over all of them, as it always was.
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

    /**
     * What a loadout is called in roster.csv, where the workers tell the trainer which ones they field so that it can weigh a
     * pairing of a loadout and an opponent rather than an opponent alone; see {@link Pairings} and {@link League#writeRoster}.
     */
    public static final String KIND = "loadout";

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

    /**
     * The loadout of that name among the ones this process draws from, or null for anything else: a name from a build with
     * more loadouts than this one, or one a run was told to leave out. A pairing naming it is dropped rather than refused,
     * since the trainer only hears what a run fields on its next read of roster.csv.
     */
    @Nullable
    public static Loadout named(String name) {

        for (Loadout loadout : enabled()) {

            if (loadout.name().equals(name)) {

                return loadout;
            }
        }

        return null;
    }

    /**
     * Whether this arms a fighter with nothing but something to swing, which is all the scripted fighter is armed with here.
     *
     * <p>The first thing in the hotbar is not enough to ask, which is how this went wrong: {@link #SWORD_AND_BOW} leads with
     * an iron sword and keeps a bow behind it, so a test on slot zero alone let the one fighter whose strength has to stay
     * put draw a bow in one league fight in eight. It is worth about twenty points to it — over league768's own 4,602
     * fights against it, counting only the ones where the agent carried no bow, it won 92.2% with the sword and bow against
     * 71.6% with the plain sword. So anything that shoots disqualifies a loadout however the hotbar is ordered.
     */
    public static boolean melee(Loadout loadout) {

        if (loadout.hotbar().isEmpty() || !(loadout.hotbar().get(0).getItem() instanceof SwordItem
                || loadout.hotbar().get(0).getItem() instanceof AxeItem)) {

            return false;
        }

        return loadout.hotbar().stream().noneMatch(stack -> stack.getItem() instanceof ProjectileWeaponItem);
    }

    /**
     * Whether that loadout may be drawn against that opposition at all. One rule, and only one: <b>a loadout that carries
     * nothing to shoot with is never drawn against something it can never reach</b>, a ghast or a phantom, on their own or
     * on a squad, packed or with a crowd standing about.
     *
     * <p>There is nothing in such a fight to win or to lose. A ghast drifts out of reach and a phantom climbs away again;
     * a swing at a fireball sends it back but does not kill the ghast, because vanilla forgives a ghast's own fire only for
     * a {@link net.minecraft.world.entity.player.Player}'s fireball (see findings.md). So every one of them is 2,400 ticks
     * of timeout: it drags the pairing's rating with a number that means nothing, and it spends a worker's minute on a
     * question with one answer. The evaluation draw used to hand out as many of them as of anything else, since it is even,
     * and the frontier probe kept a trickle of them in training.
     *
     * <p>Barring it moves what a checkpoint's evaluated win rate is averaged over, so it belongs at a run boundary; see
     * docs/training.md and findings.md. The mirror of this rule on the trainer's side is in trainer/mmai/league.py, which
     * gives such a pairing no share at all and so never probes it; the {@code reach} column of {@code roster.csv} is how it
     * is told which loadouts and which opponents these are, see {@link League#writeRoster}.
     *
     * @param opposition the mob or squad, or null for a fight against another agent, which is always reachable
     */
    public static boolean fights(Loadout loadout, @Nullable Opposition opposition) {

        return opposition == null || !opposition.unreachable() || !melee(loadout);
    }

    /**
     * The same rule asked by name, which is the shape a pairing from the trainer comes in: {@code sword} against
     * {@code ghast(hard)}. A loadout this process does not field, and a name that is no opposition at all — the scripted
     * fighter, a published network, a checkpoint — pass, since they are refused or allowed elsewhere on their own grounds;
     * this asks only about the one pairing the league will not draw.
     */
    public static boolean fields(String loadout, String opponent) {

        Loadout carried = named(loadout);

        return carried == null || fights(carried, Opposition.named(opponent));
    }

    /**
     * The ones of those that may be drawn against it. Where the rule leaves nothing at all — a run told
     * {@code -PleagueLoadouts=sword} meeting a ghast — the whole list is handed back rather than the fight being lost: a run
     * is allowed to field one loadout, and an opponent with nobody to fight it would drop out of the rotation and out of the
     * ratings with it.
     */
    public static List<Loadout> against(@Nullable Opposition opposition, List<Loadout> from) {

        if (opposition == null || !opposition.unreachable()) {

            return from;
        }

        List<Loadout> allowed = from.stream().filter(loadout -> fights(loadout, opposition)).toList();

        return allowed.isEmpty() ? from : allowed;
    }
}
