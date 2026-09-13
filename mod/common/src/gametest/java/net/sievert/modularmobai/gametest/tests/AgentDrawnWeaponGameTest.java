package net.sievert.modularmobai.gametest.tests;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.enchantment.Enchantments;
import net.sievert.modularmobai.arena.Episode;
import net.sievert.modularmobai.arena.Loadout;
import net.sievert.modularmobai.entity.agent.AgentMob;
import net.sievert.modularmobai.gametest.GameTestGroup;
import net.sievert.modularmobai.gametest.league.Loadouts;

/**
 * A drawn weapon under a player's rules: a bow's twenty ticks and the arrow that leaves at the end of them, a crossbow's
 * wind and the load it holds, the ammunition either takes, and the slot the hand keeps while a draw is running.
 *
 * <p>Two of the three places the agent's hands are not a player's are checked here, and both are deliberate: a draw runs to
 * full once it is started, and the slot it was started in is refused to the brain until the use ends. A network chooses
 * every button and its slot afresh each tick, so without them a bow fires weak arrows, a crossbow fires none at all, and a
 * bow with a sword beside it cancels its own draw. See {@code docs/findings.md}.
 *
 * <p>See {@link Mechanics} for the arena, the brain and the rest the suite shares.
 */
@GameTestGroup
public class AgentDrawnWeaponGameTest {

    // ---------------------------------------------------------------------------------------------------------------
    // Bows
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * A bow shot from start to finish. Drawn twenty ticks, which is full power, and let go, a critical arrow leaves at a
     * player's full speed with the agent as its owner, one arrow gone from the hotbar. It flies, strikes the opponent,
     * and the health it took off is paid to the agent's episode, once.
     */
    @GameTest(template = Mechanics.ARENA, timeoutTicks = 100)
    public static void bowShotFliesHitsAndIsPaid(GameTestHelper helper) {

        AgentMob agent = Mechanics.agent(helper, new BlockPos(4, 2, 1), 0.0F, 0.0F);
        Mob opponent = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(4, 2, 6));

        Episode episode = new Episode(Mechanics.FIGHT_TICKS, null, opponent);
        agent.startEpisode(episode);
        Loadout.BOW.equip(agent);

        double[] launchedFrom = {Double.NaN};
        int[] hitAt = {-1};

        Mechanics.run(helper, tick -> {

            if (tick == 0) {

                agent.controls().use = true;
                return false;
            }

            if (tick < 20) {

                helper.assertTrue(agent.isUsingItem() && agent.executed().using, "The bow is being drawn");
                return false;
            }

            if (tick == 20) {

                // Nineteen ticks drawn so far. The tick the release lands on counts the twentieth before letting go, and
                // twenty is full power.
                helper.assertValueEqual(agent.getTicksUsingItem(), 19, "ticks drawn");
                agent.controls().use = false;
                return false;
            }

            if (tick == 21) {

                List<Arrow> arrows = helper.getEntities(EntityType.ARROW);
                helper.assertValueEqual(arrows.size(), 1, "arrows loosed");

                Arrow arrow = arrows.get(0);
                double speed = arrow.getDeltaMovement().length();

                helper.assertTrue(arrow.getOwner() == agent, "The arrow's owner is the agent");
                helper.assertTrue(arrow.isCritArrow(), "A fully drawn arrow is a critical");
                helper.assertTrue(Math.abs(speed - 3.0D) < 0.1D, "A fully drawn arrow leaves at three blocks a tick, not " + speed);
                helper.assertValueEqual(agent.getHotbarItem(1).getCount(), 63, "arrows left in the hotbar");
                helper.assertFalse(agent.isUsingItem(), "The bow is let go");

                launchedFrom[0] = arrow.getZ();
                return false;
            }

            if (hitAt[0] < 0) {

                if (opponent.getHealth() < opponent.getMaxHealth()) {

                    hitAt[0] = tick;
                    Mechanics.paidExactly(helper, episode, opponent);
                    return false;
                }

                List<Arrow> arrows = helper.getEntities(EntityType.ARROW);

                helper.assertTrue(!arrows.isEmpty() && arrows.get(0).getZ() > launchedFrom[0] + 1.0D,
                        "The arrow flies on towards the opponent");
                helper.assertTrue(tick < 30, "The arrow never reached the opponent");
                return false;
            }

            // Nothing more comes in for a hit already paid for.
            if (tick == hitAt[0] + 5) {

                Mechanics.paidExactly(helper, episode, opponent);
                helper.assertValueEqual(agent.getHealth(), agent.getMaxHealth(), "the agent's health");
                return true;
            }

            return false;
        });
    }

    /**
     * One press draws all the way and looses at full power. The button is down for a single tick and up for every tick
     * after it, and the arrow that leaves twenty ticks later is the same critical a held button would have sent.
     *
     * <p>This is one of the body's deliberate departures from a player's hands, and it is what makes a drawn weapon learnable
     * at all: see AgentMob#drawingToFull. Letting go is still the agent's, once the draw is full â€” which is what the shot
     * above does, and why it can hold the aim before it looses.
     */
    @GameTest(template = Mechanics.ARENA, timeoutTicks = 100)
    public static void onePressDrawsToFullAndLooses(GameTestHelper helper) {

        AgentMob agent = Mechanics.agent(helper, new BlockPos(4, 2, 1), 0.0F, 0.0F);
        Loadout.BOW.equip(agent);

        Mechanics.run(helper, tick -> {

            agent.controls().use = tick == 0;

            if (tick > 0 && tick < 20) {

                helper.assertTrue(agent.isUsingItem() && agent.executed().using, "The draw stopped when the button came up");
                helper.assertTrue(helper.getEntities(EntityType.ARROW).isEmpty(), "An arrow left before the draw was full");
            }

            List<Arrow> arrows = helper.getEntities(EntityType.ARROW);

            if (arrows.isEmpty()) {

                helper.assertTrue(tick < 24, "One press never loosed an arrow");
                return false;
            }

            Arrow arrow = arrows.get(0);
            double speed = arrow.getDeltaMovement().length();

            helper.assertTrue(arrow.isCritArrow(), "One press sent less than a full draw");
            helper.assertTrue(Math.abs(speed - 3.0D) < 0.1D, "The arrow left at " + speed + ", not a full draw's three");
            helper.assertValueEqual(agent.getHotbarItem(1).getCount(), 63, "arrows left in the hotbar");
            helper.assertFalse(agent.isUsingItem(), "The bow is still drawn after the shot");
            return true;
        });
    }

    /** No arrow, no draw: holding use on a bow with nothing to fire does not so much as raise it. */
    @GameTest(template = Mechanics.ARENA, timeoutTicks = 100)
    public static void bowWithoutArrowsDoesNotDraw(GameTestHelper helper) {

        AgentMob agent = Mechanics.agent(helper, new BlockPos(4, 2, 2), 0.0F, 0.0F);
        agent.setHotbarItem(0, new ItemStack(Items.BOW));

        Mechanics.run(helper, tick -> {

            agent.controls().use = tick < 25;

            if (tick > 0) {

                helper.assertFalse(agent.isUsingItem() || agent.executed().using, "A bow with no arrows is drawn");
            }

            if (tick == 30) {

                helper.assertTrue(helper.getEntities(EntityType.ARROW).isEmpty(), "An arrow came from nowhere");
                return true;
            }

            return false;
        });
    }

    /**
     * Arrows are found where a player's are: the off hand first, then the hotbar. What an arrow carries goes with it, so a
     * spectral arrow makes what it hits glow.
     */
    @GameTest(template = Mechanics.ARENA, timeoutTicks = 120)
    public static void bowTakesTheArrowAPlayersWould(GameTestHelper helper) {

        AgentMob agent = Mechanics.agent(helper, new BlockPos(4, 2, 1), 0.0F, 0.0F);
        Mob target = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(4, 2, 6));

        agent.setHotbarItem(0, new ItemStack(Items.BOW));
        agent.setHotbarItem(5, new ItemStack(Items.ARROW, 16));
        agent.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SPECTRAL_ARROW));

        Mechanics.run(helper, tick -> {

            agent.controls().use = tick < 20 || tick >= 35 && tick < 55;

            if (tick == 21) {

                helper.assertValueEqual(helper.getEntities(EntityType.SPECTRAL_ARROW).size(), 1, "spectral arrows loosed");
                helper.assertTrue(helper.getEntities(EntityType.ARROW).isEmpty(), "A plain arrow went before the one in hand");
                helper.assertTrue(agent.getOffhandItem().isEmpty(), "The off hand's arrow was the one used");
                helper.assertValueEqual(agent.getHotbarItem(5).getCount(), 16, "arrows left in the hotbar");
            }

            if (tick == 30) {

                helper.assertTrue(target.hasEffect(MobEffects.GLOWING), "The spectral arrow's glow was lost on the way");
            }

            if (tick == 56) {

                helper.assertValueEqual(helper.getEntities(EntityType.ARROW).size(), 1, "plain arrows loosed");
                helper.assertValueEqual(agent.getHotbarItem(5).getCount(), 15, "arrows left in the hotbar");
                return true;
            }

            return false;
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Crossbows
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * A crossbow let go before it is wound keeps winding, since a draw runs to full once it is started. It is not loaded
     * while it winds, and no arrow is spent. Wound all the way and let go, it takes an arrow and keeps it for as long as
     * it is left alone, and the next use fires it: a critical, at a player's speed, which a mob's never is.
     */
    @GameTest(template = Mechanics.ARENA, timeoutTicks = 150)
    public static void crossbowChargesHoldsItsLoadAndFires(GameTestHelper helper) {

        AgentMob agent = Mechanics.agent(helper, new BlockPos(4, 2, 1), 0.0F, 0.0F);
        Mob target = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(4, 2, 6));
        Loadout.CROSSBOW.equip(agent);

        ItemStack crossbow = agent.getHotbarItem(0);

        Mechanics.run(helper, tick -> {

            // Ten ticks of the twenty five a crossbow takes to wind, then thirty, then a single press.
            agent.controls().use = tick < 10 || tick >= 15 && tick < 45 || tick == 86;

            if (tick == 11) {

                helper.assertTrue(agent.isUsingItem(), "A crossbow let go early stopped winding");
                helper.assertFalse(CrossbowItem.isCharged(crossbow), "A crossbow let go early is loaded");
                helper.assertValueEqual(agent.getHotbarItem(1).getCount(), 64, "arrows after letting go early");
            }

            if (tick == 46) {

                helper.assertTrue(CrossbowItem.isCharged(crossbow), "A wound crossbow is not loaded");
                helper.assertValueEqual(agent.getHotbarItem(1).getCount(), 63, "arrows after loading");
                helper.assertTrue(helper.getEntities(EntityType.ARROW).isEmpty(), "Loading a crossbow fired it");
            }

            if (tick == 86) {

                helper.assertTrue(CrossbowItem.isCharged(crossbow), "A crossbow left alone lost its load");
            }

            if (tick == 87) {

                List<Arrow> arrows = helper.getEntities(EntityType.ARROW);
                helper.assertValueEqual(arrows.size(), 1, "bolts fired");

                Arrow bolt = arrows.get(0);
                double speed = bolt.getDeltaMovement().length();

                helper.assertTrue(bolt.getOwner() == agent, "The bolt's owner is the agent");
                helper.assertTrue(bolt.isCritArrow(), "A player's crossbow bolt is a critical");
                helper.assertTrue(Math.abs(speed - 3.15D) < 0.1D, "A crossbow bolt leaves at 3.15 blocks a tick, not " + speed);
                helper.assertFalse(CrossbowItem.isCharged(crossbow), "The crossbow is still loaded after firing");
            }

            if (tick > 87 && target.getHealth() < target.getMaxHealth()) {

                return true;
            }

            helper.assertTrue(tick < 100, "The bolt never reached the target");
            return false;
        });
    }

    /** Multishot loads three bolts for the one arrow a player's crossbow takes, and fires all three. */
    @GameTest(template = Mechanics.ARENA, timeoutTicks = 100)
    public static void crossbowMultishotLoadsThreeForOneArrow(GameTestHelper helper) {

        AgentMob agent = Mechanics.agent(helper, new BlockPos(4, 2, 1), 0.0F, 0.0F);

        ItemStack crossbow = new ItemStack(Items.CROSSBOW);
        crossbow.enchant(Mechanics.enchantment(helper, Enchantments.MULTISHOT), 1);

        agent.setHotbarItem(0, crossbow);
        agent.setHotbarItem(1, new ItemStack(Items.ARROW, 16));

        Mechanics.run(helper, tick -> {

            agent.controls().use = tick < 30 || tick == 35;

            if (tick == 31) {

                ChargedProjectiles loaded = crossbow.get(DataComponents.CHARGED_PROJECTILES);

                helper.assertTrue(loaded != null && loaded.getItems().size() == 3, "A multishot crossbow loads three");
                helper.assertValueEqual(agent.getHotbarItem(1).getCount(), 15, "arrows after loading");
            }

            if (tick == 36) {

                helper.assertValueEqual(helper.getEntities(EntityType.ARROW).size(), 3, "bolts fired");
                return true;
            }

            return false;
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The slot a draw was started in
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Where the sword and bow loadout the league fights with keeps each thing, see {@code league/Loadouts.SWORD_AND_BOW}.
     * The crossbow test below lays its hotbar out the same way, so every test here flips between the same two slots.
     */
    private static final int MELEE_SLOT = 0;
    private static final int DRAWN_SLOT = 1;
    private static final int ARROW_SLOT = 2;

    /**
     * The step the other slot is first asked for on: eight ticks into the draw, which is clear of both ends, so the draw is
     * well under way and the arrow is nowhere near away. A draw begins on the tick after the press, so the step is two more.
     */
    private static final int FLIP_AT = 10;

    /**
     * A slot asked for in the middle of a draw waits for the arrow. The hotbar is the league's sword and bow, the sword is
     * asked for eight ticks into the draw, and it is asked for on every tick after that, the way a brain asks for a control
     * afresh every tick. This is where the arrows were being lost: changing slot stops a use outright, with no release and
     * so no arrow, and the slot head has twenty chances to do it. The hand keeps the bow, the arrow leaves at full power,
     * and the sword comes up on the tick behind it. See AgentMob#drawHoldsTheSlot.
     *
     * <p>It opens on the case the rule must leave alone: with the hands free, the slot asked for is held on the very next
     * tick and nothing waits for anything.
     */
    @GameTest(template = Mechanics.ARENA, timeoutTicks = 100)
    public static void aSlotFlipMidDrawWaitsForTheArrow(GameTestHelper helper) {

        AgentMob agent = Mechanics.agent(helper, new BlockPos(4, 2, 1), 0.0F, 0.0F);
        Loadouts.SWORD_AND_BOW.equip(agent);

        int[] loosedAt = {-1};

        Mechanics.run(helper, tick -> {

            if (tick == 0) {

                helper.assertValueEqual(agent.getSelectedSlot(), MELEE_SLOT, "the slot an agent starts in");
                agent.controls().selectedSlot = DRAWN_SLOT;
                return false;
            }

            if (tick == 1) {

                helper.assertValueEqual(agent.getSelectedSlot(), DRAWN_SLOT, "the slot held with the hands free");
                helper.assertTrue(agent.getMainHandItem().is(Items.BOW), "The bow never came up");

                agent.controls().use = true;
                return false;
            }

            // One press is one arrow: the draw runs to full on its own, see onePressDrawsToFullAndLooses.
            agent.controls().use = false;

            if (tick >= FLIP_AT) {

                agent.controls().selectedSlot = MELEE_SLOT;
            }

            if (loosedAt[0] < 0) {

                if (tick == FLIP_AT) {

                    helper.assertValueEqual(agent.getTicksUsingItem(), FLIP_AT - 2, "ticks drawn when the sword was asked for");
                }

                // True on the tick the arrow leaves as well: the slot is applied before the hands are, so the swap cannot
                // get in front of the shot.
                helper.assertValueEqual(agent.getSelectedSlot(), DRAWN_SLOT, "the slot held while the draw ran");
                helper.assertValueEqual(agent.executed().selectedSlot, DRAWN_SLOT, "the slot the echo reports while the draw ran");

                List<Arrow> arrows = helper.getEntities(EntityType.ARROW);

                if (arrows.isEmpty()) {

                    helper.assertTrue(agent.isUsingItem(), "The draw was dropped, on tick " + tick);
                    helper.assertTrue(tick < 24, "A draw with a slot flip in it never loosed an arrow");
                    return false;
                }

                // The arrow left in this tick, and the slot was applied earlier in the same tick, so the swap is one tick
                // behind the shot rather than in front of it.
                Arrow arrow = arrows.get(0);
                double speed = arrow.getDeltaMovement().length();

                helper.assertTrue(arrow.isCritArrow(), "The flip cost the arrow its full draw");
                helper.assertTrue(Math.abs(speed - 3.0D) < 0.1D, "The arrow left at " + speed + ", not a full draw's three");
                helper.assertValueEqual(agent.getHotbarItem(ARROW_SLOT).getCount(), 63, "arrows left in the hotbar");

                loosedAt[0] = tick;
                return false;
            }

            // The first tick after the shot, and the slot that was waiting is held.
            helper.assertValueEqual(agent.getSelectedSlot(), MELEE_SLOT, "the slot held once the arrow was away");
            helper.assertTrue(agent.getMainHandItem().is(Items.IRON_SWORD), "The sword never came up behind the arrow");
            return true;
        });
    }

    /**
     * The same for a crossbow's wind, which is worth its own test because a crossbow loses more: it fires nothing at all
     * short of a full wind, so a flip eight ticks in costs the whole twenty five and the bolt with it. The wind finishes,
     * the bolt is loaded, and the sword comes up behind it.
     *
     * <p>And then the other half of the rule: a <b>loaded</b> crossbow is not held to its slot. Its bolt is in the item and
     * not in the hand, so putting it away loaded costs nothing and there is nothing to wait for.
     */
    @GameTest(template = Mechanics.ARENA, timeoutTicks = 100)
    public static void aSlotFlipMidWindWaitsForTheLoad(GameTestHelper helper) {

        AgentMob agent = Mechanics.agent(helper, new BlockPos(4, 2, 1), 0.0F, 0.0F);

        agent.setHotbarItem(MELEE_SLOT, new ItemStack(Items.IRON_SWORD));
        agent.setHotbarItem(DRAWN_SLOT, new ItemStack(Items.CROSSBOW));
        agent.setHotbarItem(ARROW_SLOT, new ItemStack(Items.ARROW, 64));

        ItemStack crossbow = agent.getHotbarItem(DRAWN_SLOT);
        boolean[] loaded = {false};

        Mechanics.run(helper, tick -> {

            if (tick == 0) {

                agent.controls().selectedSlot = DRAWN_SLOT;
                return false;
            }

            if (tick == 1) {

                helper.assertValueEqual(agent.getSelectedSlot(), DRAWN_SLOT, "the slot held with the hands free");
                agent.controls().use = true;
                return false;
            }

            agent.controls().use = false;

            if (tick >= FLIP_AT) {

                agent.controls().selectedSlot = MELEE_SLOT;
            }

            if (!loaded[0]) {

                // True on the tick the bolt is taken as well: the slot is applied before the hands are.
                helper.assertValueEqual(agent.getSelectedSlot(), DRAWN_SLOT, "the slot held while the wind ran");
                helper.assertTrue(helper.getEntities(EntityType.ARROW).isEmpty(), "Loading a crossbow fired it");

                if (!CrossbowItem.isCharged(crossbow)) {

                    helper.assertTrue(agent.isUsingItem(), "The wind was dropped, on tick " + tick);
                    helper.assertTrue(tick < 32, "A wind with a slot flip in it never loaded the crossbow");
                    return false;
                }

                helper.assertValueEqual(agent.getHotbarItem(ARROW_SLOT).getCount(), 63, "arrows after loading");

                loaded[0] = true;
                return false;
            }

            // Loaded, so nothing is waiting on the hands any more and the slot that was asked for is held.
            helper.assertValueEqual(agent.getSelectedSlot(), MELEE_SLOT, "the slot held once the crossbow was loaded");
            helper.assertTrue(agent.getMainHandItem().is(Items.IRON_SWORD), "The sword never came up behind the load");
            helper.assertTrue(CrossbowItem.isCharged(crossbow), "A crossbow put away loaded lost its bolt");
            return true;
        });
    }

    /**
     * The slot is handed back by the use ending and not by the arrow going. The quiver is emptied while the draw runs, so
     * the release finds nothing to send: no arrow, no shot recorded, and the slot the brain has been asking for still lands
     * on the tick after the hands come free. Without this the rule could strand a hand on a weapon that will never fire.
     */
    @GameTest(template = Mechanics.ARENA, timeoutTicks = 100)
    public static void aDrawThatSendsNothingStillHandsTheSlotBack(GameTestHelper helper) {

        AgentMob agent = Mechanics.agent(helper, new BlockPos(4, 2, 1), 0.0F, 0.0F);
        Loadouts.SWORD_AND_BOW.equip(agent);

        boolean[] shot = {false};
        int[] endedAt = {-1};

        Mechanics.run(helper, tick -> {

            if (tick == 0) {

                agent.controls().selectedSlot = DRAWN_SLOT;
                return false;
            }

            if (tick == 1) {

                agent.controls().use = true;
                return false;
            }

            agent.controls().use = false;
            shot[0] |= agent.executed().shotFired;

            if (tick == FLIP_AT) {

                agent.controls().selectedSlot = MELEE_SLOT;
                agent.setHotbarItem(ARROW_SLOT, ItemStack.EMPTY);
                return false;
            }

            if (endedAt[0] < 0) {

                if (agent.isUsingItem()) {

                    helper.assertValueEqual(agent.getSelectedSlot(), DRAWN_SLOT, "the slot held while the draw ran");
                    helper.assertTrue(tick < 30, "The draw never ended");
                    return false;
                }

                // The draw reached full and the release found nothing to send. The slot was applied earlier in this tick,
                // while the hands were still busy, so it is the next tick that has to hold the sword.
                helper.assertFalse(shot[0], "A bow with an empty quiver recorded a shot");
                helper.assertTrue(helper.getEntities(EntityType.ARROW).isEmpty(), "An arrow came from an empty quiver");

                endedAt[0] = tick;
                return false;
            }

            helper.assertValueEqual(agent.getSelectedSlot(), MELEE_SLOT, "the slot held after a draw that sent nothing");
            helper.assertTrue(agent.getMainHandItem().is(Items.IRON_SWORD), "The sword never came up after a draw that sent nothing");
            return true;
        });
    }
}
