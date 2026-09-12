package net.sievert.modularmobai.gametest.tests;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

import org.jetbrains.annotations.Nullable;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FurnaceBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.sievert.modularmobai.arena.AgentReward;
import net.sievert.modularmobai.arena.Episode;
import net.sievert.modularmobai.arena.Loadout;
import net.sievert.modularmobai.brain.Brain;
import net.sievert.modularmobai.brain.Brains;
import net.sievert.modularmobai.brain.BrainStep;
import net.sievert.modularmobai.brain.schema.ActionSchema;
import net.sievert.modularmobai.brain.schema.AgentObservation;
import net.sievert.modularmobai.brain.schema.ObservationSchema;
import net.sievert.modularmobai.brain.schema.Species;
import net.sievert.modularmobai.entity.ModEntities;
import net.sievert.modularmobai.entity.agent.AgentMob;
import net.sievert.modularmobai.entity.agent.MobControls;
import net.sievert.modularmobai.gametest.GameTestGroup;
import net.sievert.modularmobai.gametest.league.Loadouts;
import net.sievert.modularmobai.gametest.terrain.PouredHazards;

/**
 * The agent's body against a player's rules, a rule or two to a test: bows, crossbows, shields and axes, what an item in
 * use does to moving and attacking, breaking and placing blocks, and what a hit is paid.
 *
 * <p>Nothing here is a fight. Every agent is driven by {@link HeldControls}, which hands it back whatever the test pressed
 * on its controls, out through the action vector and back in as a network's actions come, so each rule is reached the
 * way a brain would reach it. The numbers asserted are a player's, worked out from vanilla's own code, and a few of them
 * differ from the ones usually quoted: stone takes a player 151 ticks by hand, not 150, because the client adds the work
 * up a tick at a time in floats.
 *
 * <p>Each test is a script, a step for every tick, that runs in the arena's closed box: a bedrock floor at height one,
 * room from two to eight, and walls round an inside seven blocks across, from one to seven.
 *
 * <p>Run with {@code -Psuite=mechanics}, see {@link net.sievert.modularmobai.gametest.GameTestTuning#suite()}.
 */
@GameTestGroup
public class AgentMechanicsGameTest {

    private static final String ARENA = "arena";

    /** How long a fight may run, for the episodes the reward is checked against. */
    private static final int FIGHT_TICKS = 1200;

    /**
     * How long a freshly spawned body is given to come to rest before anything is pressed. It is not on the ground until
     * it has moved once, and a block is broken five times slower by anyone who is not.
     */
    private static final int SETTLE = 5;

    /** Where a block is broken from: an agent's eyes are level with it, half a block off. */
    private static final BlockPos MINER = new BlockPos(4, 2, 2);
    private static final BlockPos AT_EYE_LEVEL = new BlockPos(4, 3, 3);

    // A player's ticks for each block, from its client: a tick's work is the tool's speed on the block over its hardness,
    // over thirty with the right tool and a hundred without, added up in floats until it reaches one.
    private static final int IRON_SHOVEL_ON_DIRT = 3;
    private static final int HAND_ON_DIRT = 15;
    private static final int HAND_ON_DIRT_IN_THE_AIR = 76;
    private static final int HAND_ON_STONE = 151;
    private static final int IRON_PICKAXE_ON_STONE = 8;

    /** Where the echo carries how far a use has charged; see AgentObservation#writeEcho. */
    private static final int ECHO_USE_PROGRESS = 19;

    // ---------------------------------------------------------------------------------------------------------------
    // Bows
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * A bow shot from start to finish. Drawn twenty ticks, which is full power, and let go, a critical arrow leaves at a
     * player's full speed with the agent as its owner, one arrow gone from the hotbar. It flies, strikes the opponent,
     * and the health it took off is paid to the agent's episode, once.
     */
    @GameTest(template = ARENA, timeoutTicks = 100)
    public static void bowShotFliesHitsAndIsPaid(GameTestHelper helper) {

        AgentMob agent = agent(helper, new BlockPos(4, 2, 1), 0.0F, 0.0F);
        Mob opponent = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(4, 2, 6));

        Episode episode = new Episode(FIGHT_TICKS, null, opponent);
        agent.startEpisode(episode);
        Loadout.BOW.equip(agent);

        double[] launchedFrom = {Double.NaN};
        int[] hitAt = {-1};

        run(helper, tick -> {

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
                    paidExactly(helper, episode, opponent);
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

                paidExactly(helper, episode, opponent);
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
     * <p>This is the body's one deliberate departure from a player's hands, and it is what makes a drawn weapon learnable
     * at all: see AgentMob#drawingToFull. Letting go is still the agent's, once the draw is full â€” which is what the shot
     * above does, and why it can hold the aim before it looses.
     */
    @GameTest(template = ARENA, timeoutTicks = 100)
    public static void onePressDrawsToFullAndLooses(GameTestHelper helper) {

        AgentMob agent = agent(helper, new BlockPos(4, 2, 1), 0.0F, 0.0F);
        Loadout.BOW.equip(agent);

        run(helper, tick -> {

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
    @GameTest(template = ARENA, timeoutTicks = 100)
    public static void bowWithoutArrowsDoesNotDraw(GameTestHelper helper) {

        AgentMob agent = agent(helper, new BlockPos(4, 2, 2), 0.0F, 0.0F);
        agent.setHotbarItem(0, new ItemStack(Items.BOW));

        run(helper, tick -> {

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
    @GameTest(template = ARENA, timeoutTicks = 120)
    public static void bowTakesTheArrowAPlayersWould(GameTestHelper helper) {

        AgentMob agent = agent(helper, new BlockPos(4, 2, 1), 0.0F, 0.0F);
        Mob target = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(4, 2, 6));

        agent.setHotbarItem(0, new ItemStack(Items.BOW));
        agent.setHotbarItem(5, new ItemStack(Items.ARROW, 16));
        agent.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SPECTRAL_ARROW));

        run(helper, tick -> {

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
    @GameTest(template = ARENA, timeoutTicks = 150)
    public static void crossbowChargesHoldsItsLoadAndFires(GameTestHelper helper) {

        AgentMob agent = agent(helper, new BlockPos(4, 2, 1), 0.0F, 0.0F);
        Mob target = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(4, 2, 6));
        Loadout.CROSSBOW.equip(agent);

        ItemStack crossbow = agent.getHotbarItem(0);

        run(helper, tick -> {

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
    @GameTest(template = ARENA, timeoutTicks = 100)
    public static void crossbowMultishotLoadsThreeForOneArrow(GameTestHelper helper) {

        AgentMob agent = agent(helper, new BlockPos(4, 2, 1), 0.0F, 0.0F);

        ItemStack crossbow = new ItemStack(Items.CROSSBOW);
        crossbow.enchant(enchantment(helper, Enchantments.MULTISHOT), 1);

        agent.setHotbarItem(0, crossbow);
        agent.setHotbarItem(1, new ItemStack(Items.ARROW, 16));

        run(helper, tick -> {

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
    // Shields and axes
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * A raised shield stops a blow from the front and an arrow from the front, and wears for both as a player's does. A
     * blow from behind gets through.
     */
    @GameTest(template = ARENA, timeoutTicks = 100)
    public static void shieldBlocksWhatComesFromTheFront(GameTestHelper helper) {

        AgentMob agent = agent(helper, new BlockPos(4, 2, 2), 0.0F, 0.0F);
        Loadout.SWORD_AND_SHIELD.equip(agent);

        Mob front = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(4, 2, 3));
        Mob behind = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(4, 2, 1));

        int[] worn = {0};

        run(helper, tick -> {

            if (tick == 0) {

                agent.controls().useOffhand = true;
                return false;
            }

            // Held up for five ticks, the raise vanilla gives every shield before it blocks anything.
            if (tick == 8) {

                helper.assertTrue(agent.isBlocking(), "The shield is up");
                helper.assertFalse(front.doHurtTarget(agent), "A zombie's blow went through a raised shield");
                helper.assertValueEqual(agent.getHealth(), agent.getMaxHealth(), "health after a blocked blow");
                helper.assertTrue(agent.isUsingItem(), "A zombie knocked the shield aside");

                worn[0] = agent.getOffhandItem().getDamageValue();
                helper.assertTrue(worn[0] > 0, "The shield did not wear");

                front.discard();

                Vec3 from = helper.absoluteVec(new Vec3(4.5D, 3.3D, 6.5D));
                Arrow arrow = new Arrow(helper.getLevel(), from.x, from.y, from.z, new ItemStack(Items.ARROW), null);
                arrow.shoot(0.0D, 0.0D, -1.0D, 1.5F, 0.0F);
                helper.getLevel().addFreshEntity(arrow);
                return false;
            }

            if (tick == 20) {

                helper.assertValueEqual(agent.getHealth(), agent.getMaxHealth(), "health after an arrow at the shield");
                helper.assertValueEqual(agent.getArrowCount(), 0, "arrows stuck in the agent");
                helper.assertTrue(agent.getOffhandItem().getDamageValue() > worn[0], "The shield did not wear from the arrow");
                helper.assertTrue(behind.doHurtTarget(agent), "A blow from behind was blocked");
                helper.assertTrue(agent.getHealth() < agent.getMaxHealth(), "A blow from behind did no harm");
                return true;
            }

            return false;
        });
    }

    /**
     * An axe knocks the agent's raised shield aside, as it does a player's: the shield drops, and it will not come up
     * again for a hundred ticks, however the use is held.
     */
    @GameTest(template = ARENA, timeoutTicks = 200)
    public static void axeKnocksTheAgentsShieldAside(GameTestHelper helper) {

        AgentMob agent = agent(helper, new BlockPos(4, 2, 2), 0.0F, 0.0F);
        Loadout.SWORD_AND_SHIELD.equip(agent);

        Mob vindicator = helper.spawnWithNoFreeWill(EntityType.VINDICATOR, new BlockPos(4, 2, 3));
        vindicator.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));

        int[] hitAt = {-1};

        run(helper, tick -> {

            agent.controls().useOffhand = true;

            if (tick == 8) {

                helper.assertTrue(agent.isBlocking(), "The shield is up");
                vindicator.doHurtTarget(agent);
                vindicator.discard();

                helper.assertValueEqual(agent.getHealth(), agent.getMaxHealth(), "health after an axe at the shield");
                helper.assertFalse(agent.isUsingItem(), "The axe did not knock the shield aside");
                helper.assertTrue(agent.itemCooldowns().isOnCooldown(Items.SHIELD), "The shield is not cooling down");

                hitAt[0] = tick;
                return false;
            }

            if (hitAt[0] < 0) {

                return false;
            }

            int down = tick - hitAt[0];

            if (agent.isUsingItem()) {

                // A hundred ticks, and then up to one use interval before the held button tries again.
                helper.assertTrue(down >= AgentMob.SHIELD_DISABLED_TICKS, "The shield came back up after " + down + " ticks");
                helper.assertTrue(down <= AgentMob.SHIELD_DISABLED_TICKS + AgentMob.USE_INTERVAL + 1,
                        "The shield took " + down + " ticks to come back up");
                return true;
            }

            helper.assertTrue(down <= AgentMob.SHIELD_DISABLED_TICKS + AgentMob.USE_INTERVAL + 1, "The shield never came back up");
            return false;
        });
    }

    /** The agent's own axe knocks aside a shield raised against it, here another agent's. */
    @GameTest(template = ARENA, timeoutTicks = 100)
    public static void agentsAxeKnocksAShieldAside(GameTestHelper helper) {

        AgentMob attacker = agent(helper, new BlockPos(4, 2, 2), 0.0F, 0.0F);
        AgentMob defender = agent(helper, new BlockPos(4, 2, 4), 180.0F, 0.0F);

        Loadout.AXE_AND_SHIELD.equip(attacker);
        Loadout.SWORD_AND_SHIELD.equip(defender);

        run(helper, tick -> {

            defender.controls().useOffhand = true;
            attacker.controls().attack = tick == 25;

            if (tick == 25) {

                helper.assertTrue(defender.isBlocking(), "The defender's shield is up");
            }

            if (tick == 26) {

                helper.assertTrue(attacker.executed().attacked, "The attacker swung");
                helper.assertValueEqual(defender.getHealth(), defender.getMaxHealth(), "the defender's health");
                helper.assertFalse(defender.isUsingItem(), "The axe did not knock the shield aside");
                helper.assertTrue(defender.itemCooldowns().isOnCooldown(Items.SHIELD), "The defender's shield is not cooling down");
                return true;
            }

            return false;
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // An item in use
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Walking with a bow drawn goes at a fifth of the pace of walking with nothing in use, and a sprint asked for along
     * with the draw never starts.
     */
    @GameTest(template = ARENA, timeoutTicks = 100)
    public static void usingAnItemSlowsToAFifth(GameTestHelper helper) {

        AgentMob walker = agent(helper, new BlockPos(2, 2, 1), 0.0F, 0.0F);
        AgentMob drawer = agent(helper, new BlockPos(6, 2, 1), 0.0F, 0.0F);
        Loadout.BOW.equip(drawer);

        double[] from = new double[2];

        run(helper, tick -> {

            if (tick == 0) {

                walker.controls().moveForward = 1.0F;
                drawer.controls().moveForward = 1.0F;
                drawer.controls().sprint = true;
                drawer.controls().use = true;
                return false;
            }

            helper.assertTrue(drawer.isUsingItem(), "The bow is being drawn");
            helper.assertFalse(drawer.isSprinting(), "A sprint started while drawing a bow");

            if (tick == 6) {

                from[0] = walker.getZ();
                from[1] = drawer.getZ();
            }

            if (tick == 16) {

                double pace = (drawer.getZ() - from[1]) / (walker.getZ() - from[0]);
                helper.assertTrue(Math.abs(pace - 0.2D) < 0.02D, "Drawing a bow walks at " + pace + " of the pace, not a fifth");
                return true;
            }

            return false;
        });
    }

    /**
     * Attack does nothing while an item is in use, not even on the tick the item is let go, which a player's client
     * swallows too. The tick after, the same held attack lands.
     */
    @GameTest(template = ARENA, timeoutTicks = 100)
    public static void noAttackingWhileAnItemIsInUse(GameTestHelper helper) {

        AgentMob agent = agent(helper, new BlockPos(4, 2, 2), 0.0F, 0.0F);
        Loadout.SWORD_AND_SHIELD.equip(agent);

        Mob target = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(4, 2, 4));

        run(helper, tick -> {

            agent.controls().useOffhand = tick < 20;
            agent.controls().attack = tick >= 6;

            if (tick > 6 && tick <= 21) {

                helper.assertValueEqual(target.getHealth(), target.getMaxHealth(), "the target's health at tick " + tick);
                helper.assertFalse(agent.executed().attacked, "The agent swung with a shield up");
            }

            if (tick == 21) {

                helper.assertFalse(agent.isUsingItem(), "The shield was not let go");
            }

            if (tick == 22) {

                helper.assertTrue(target.getHealth() < target.getMaxHealth(), "The attack did not land once the shield was down");
                return true;
            }

            return false;
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Breaking blocks
    // ---------------------------------------------------------------------------------------------------------------

    /** An iron shovel takes dirt in a player's three ticks, and the dirt drops. */
    @GameTest(template = ARENA, timeoutTicks = 100)
    public static void shovelDigsDirtInAPlayersTime(GameTestHelper helper) {

        mines(helper, new ItemStack(Items.IRON_SHOVEL), Blocks.DIRT, IRON_SHOVEL_ON_DIRT, Items.DIRT);
    }

    /** Stone by hand takes a player 151 ticks, and a hand does not get stone's drop. */
    @GameTest(template = ARENA, timeoutTicks = 250)
    public static void handBreaksStoneSlowlyForNothing(GameTestHelper helper) {

        mines(helper, ItemStack.EMPTY, Blocks.STONE, HAND_ON_STONE, null);
    }

    /** An iron pickaxe takes stone in eight ticks, and the stone drops cobblestone. */
    @GameTest(template = ARENA, timeoutTicks = 100)
    public static void pickaxeBreaksStoneForCobblestone(GameTestHelper helper) {

        mines(helper, new ItemStack(Items.IRON_PICKAXE), Blocks.STONE, IRON_PICKAXE_ON_STONE, Items.COBBLESTONE);
    }

    /** Off the ground a block takes five times the work, here one agent floating beside another standing. */
    @GameTest(template = ARENA, timeoutTicks = 150)
    public static void miningInTheAirIsFiveTimesSlower(GameTestHelper helper) {

        BlockPos standingTarget = new BlockPos(2, 3, 3);
        BlockPos floatingTarget = new BlockPos(6, 5, 3);

        helper.setBlock(standingTarget, Blocks.DIRT);
        helper.setBlock(floatingTarget, Blocks.DIRT);

        AgentMob standing = agent(helper, new BlockPos(2, 2, 2), 0.0F, 0.0F);
        AgentMob floating = agent(helper, new BlockPos(6, 4, 2), 0.0F, 0.0F);
        floating.setNoGravity(true);

        int[] brokenAfter = {-1, -1};

        run(helper, tick -> {

            if (tick == SETTLE) {

                standing.controls().attack = true;
                floating.controls().attack = true;
            }

            if (tick <= SETTLE) {

                return false;
            }

            helper.assertFalse(floating.onGround(), "The floating agent is on the ground");

            if (brokenAfter[0] < 0 && helper.getBlockState(standingTarget).isAir()) {

                brokenAfter[0] = tick - SETTLE;
            }

            if (brokenAfter[1] < 0 && helper.getBlockState(floatingTarget).isAir()) {

                brokenAfter[1] = tick - SETTLE;
            }

            if (brokenAfter[1] < 0) {

                helper.assertTrue(tick - SETTLE < HAND_ON_DIRT_IN_THE_AIR, "The block in the air is still standing");
                return false;
            }

            helper.assertValueEqual(brokenAfter[0], HAND_ON_DIRT, "ticks to break dirt by hand on the ground");
            helper.assertValueEqual(brokenAfter[1], HAND_ON_DIRT_IN_THE_AIR, "ticks to break dirt by hand in the air");
            return true;
        });
    }

    /**
     * Letting go of attack keeps the work done, as long as the aim stays on the block: the crack waits where it got to and
     * the presses that follow finish it. Ten of the fifteen ticks, two ticks off, and the block goes on the fifth press
     * after that rather than the fifteenth.
     *
     * <p>A player's client throws the crack away the instant the button comes up. This is the deliberate difference, and
     * the reason is the same as the committed draw's: a network holds attack for eight ticks or more on about one hold in
     * twenty, so a crack it has to hold unbroken is a crack it can never finish, and every block it should dig itself out
     * of â€” powder snow, a cobweb â€” stays where it is. See AgentMob#continueDestroying.
     */
    @GameTest(template = ARENA, timeoutTicks = 100)
    public static void lettingGoKeepsTheBlocksProgress(GameTestHelper helper) {

        helper.setBlock(AT_EYE_LEVEL, Blocks.DIRT);
        AgentMob agent = agent(helper, MINER, 0.0F, 0.0F);

        int pressed = 10;
        int again = SETTLE + pressed + 2;

        run(helper, tick -> {

            agent.controls().attack = tick >= SETTLE && tick < SETTLE + pressed || tick >= again;

            if (tick < again) {

                helper.assertFalse(helper.getBlockState(AT_EYE_LEVEL).isAir(), "The dirt went before it was cracked through");
                return false;
            }

            if (!helper.getBlockState(AT_EYE_LEVEL).isAir()) {

                helper.assertTrue(tick - again < HAND_ON_DIRT - pressed, "The dirt is still standing, so its crack was lost");
                return false;
            }

            helper.assertValueEqual(tick - again, HAND_ON_DIRT - pressed, "presses to finish the dirt off");
            return true;
        });
    }

    /** Looking at something else does lose it: the crack is kept for the block under the aim, and for no other. */
    @GameTest(template = ARENA, timeoutTicks = 100)
    public static void lookingAwayStartsTheBlockOver(GameTestHelper helper) {

        helper.setBlock(AT_EYE_LEVEL, Blocks.DIRT);
        AgentMob agent = agent(helper, MINER, 0.0F, 0.0F);

        // Ten ticks of the fifteen, then turned two whole ticks of yaw away and the same back, which lands on the block
        // again because a tick of turn is the same size either way.
        int away = SETTLE + 10;
        int back = away + 2;
        int again = back + 2;

        run(helper, tick -> {

            agent.controls().attack = tick >= SETTLE;
            agent.controls().aimYaw = tick >= away && tick < back ? 1.0F : tick >= back && tick < again ? -1.0F : 0.0F;

            if (tick < again) {

                return false;
            }

            if (!helper.getBlockState(AT_EYE_LEVEL).isAir()) {

                helper.assertTrue(tick - again <= HAND_ON_DIRT + 2, "The dirt is still standing");
                return false;
            }

            helper.assertTrue(tick - again >= HAND_ON_DIRT - 1, "The dirt kept its crack through a look somewhere else: it "
                    + "went after " + (tick - again) + " ticks, where breaking it from nothing takes " + HAND_ON_DIRT);
            return true;
        });
    }

    /** Bedrock never breaks, and hitting it costs no more than any other block does, nothing. */
    @GameTest(template = ARENA, timeoutTicks = 100)
    public static void bedrockNeverBreaks(GameTestHelper helper) {

        // Half a block from the arena's far wall, looking at it.
        AgentMob agent = agent(helper, new BlockPos(4, 2, 6), 0.0F, 0.0F);
        BlockPos wall = new BlockPos(4, 3, 8);

        run(helper, tick -> {

            agent.controls().attack = tick >= SETTLE;

            helper.assertTrue(helper.getBlockState(wall).is(Blocks.BEDROCK), "The bedrock broke");

            if (tick == SETTLE + 60) {

                helper.assertValueEqual(agent.getAttackStrengthScale(0.0F), 1.0F, "attack strength after hitting bedrock");
                return true;
            }

            return false;
        });
    }

    /**
     * Holds attack on a block at eye level straight ahead until it breaks, and checks it took as many ticks as it takes a
     * player, and dropped what it drops for one.
     *
     * @param drop what should fall out, or null for a block that has to give nothing at all
     */
    private static void mines(GameTestHelper helper, ItemStack tool, Block block, int playerTicks, @Nullable Item drop) {

        helper.setBlock(AT_EYE_LEVEL, block);

        AgentMob agent = agent(helper, MINER, 0.0F, 0.0F);
        agent.setHotbarItem(0, tool);

        String name = block.getName().getString();

        run(helper, tick -> {

            agent.controls().attack = tick >= SETTLE;

            if (tick <= SETTLE) {

                return false;
            }

            int held = tick - SETTLE;

            if (!helper.getBlockState(AT_EYE_LEVEL).isAir()) {

                if (held >= playerTicks) {

                    throw new GameTestAssertException(name + " is still standing after " + held + " ticks, where a player breaks it in " + playerTicks);
                }

                return false;
            }

            helper.assertValueEqual(held, playerTicks, "ticks to break " + name);

            List<ItemEntity> drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                    new AABB(helper.absolutePos(AT_EYE_LEVEL)).inflate(2.0D));

            if (drop == null) {

                helper.assertTrue(drops.isEmpty(), name + " dropped something for a tool that should get nothing");
            }

            else {

                helper.assertTrue(drops.size() == 1 && drops.get(0).getItem().is(drop), name + " did not drop " + drop);
            }

            return true;
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The attack cooldown, and what a press costs
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * What a press of attack costs, which is the whole reason the cooldown is worth waiting for: a swing that meets
     * nothing at all restarts it, and one that meets a block leaves it exactly where it was.
     *
     * <p>Both halves are a player's, from Minecraft#startAttack: a miss restarts the ticker, a block starts cracking
     * instead and never touches it. The pair is pinned here because the difference between them is what decides whether
     * holding the button down is expensive or free, and a run's recorded fights are read against it. Measured over the
     * league's replays, about half the agent's presses meet a block and cost nothing, a fifth meet thin air and cost the
     * cooldown, and an eighth land on the opponent.
     */
    @GameTest(template = ARENA, timeoutTicks = 100)
    public static void aSwingAtAirCostsTheCooldownAndOneAtABlockDoesNot(GameTestHelper helper) {

        // Level, with nothing in front of it: the wall ahead is five and a half blocks off, further than the four and a
        // half a block is reached at, so a swing from here meets nothing whatever.
        AgentMob agent = agent(helper, MINER, 0.0F, 0.0F);
        Loadout.SWORD.equip(agent);

        int air = 20;

        // Then the same sword on dirt at eye level, for fewer presses than the fifteen ticks it takes, so the block is
        // still standing when the cooldown is read.
        int block = air + 20;
        int presses = 10;

        run(helper, tick -> {

            agent.controls().attack = tick == air || tick >= block && tick < block + presses;

            if (tick == air) {

                helper.assertValueEqual(agent.getAttackStrengthScale(0.0F), 1.0F, "attack strength before the swing at air");
            }

            // The ticker restarts inside the tick and is advanced at the end of it, as vanilla advances a player's after
            // resolving its attack, so one tick of the sword's twelve and a half is back by the time this reads it.
            if (tick == air + 1) {

                helper.assertValueEqual(agent.getAttackStrengthScale(0.0F),
                        1.0F / agent.getCurrentItemAttackStrengthDelay(), "attack strength after a swing at thin air");

                helper.setBlock(AT_EYE_LEVEL, Blocks.DIRT);
            }

            if (tick == block) {

                helper.assertValueEqual(agent.getAttackStrengthScale(0.0F), 1.0F, "attack strength recovered before the block");
            }

            if (tick == block + presses) {

                helper.assertFalse(helper.getBlockState(AT_EYE_LEVEL).isAir(), "The dirt broke before the cooldown was read");
                helper.assertValueEqual(agent.getAttackStrengthScale(0.0F), 1.0F,
                        "attack strength after " + presses + " swings into a block");
                return true;
            }

            return false;
        });
    }

    /**
     * Holding attack down is strictly worse than waiting for the cooldown, which is what makes spamming it a mistake
     * rather than a trick. Two blows thirteen ticks apart take more health off than fourteen presses in a row do.
     *
     * <p>Two of a player's rules make that so, and neither is the agent's. Damage goes with the square of the cooldown,
     * so a swing a tick after the last carries a fifth of the weapon. And a blow gives a body twenty ticks of hurt
     * immunity, in the first half of which a follow-up that is not greater than the blow that started it is thrown out
     * whole, and after which it lands but only for what it exceeds. So the presses in between reach a body still
     * flinching from the first and are worth nothing.
     */
    @GameTest(template = ARENA, timeoutTicks = 140)
    public static void holdingAttackTakesLessHealthThanWaitingForTheCooldown(GameTestHelper helper) {

        AgentMob agent = agent(helper, new BlockPos(4, 2, 2), 0.0F, 0.0F);
        Loadout.SWORD.equip(agent);

        // Far enough apart that a sweep at one never reaches the other, and both held where they stand: a blow throws a
        // body two and a half blocks back, which would take the second one out of reach and make this a test of walking.
        Mob waitedFor = heldStill(helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(4, 2, 4)));
        Mob spammedAt = heldStill(helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(2, 2, 2)));

        // Two blows thirteen ticks apart: twelve ticks is the sword's cooldown, and the second lands after the first
        // one's immunity has run down enough to take a fresh blow.
        int first = 20;
        int second = first + 13;
        int turn = second + 14;

        // Then the same fourteen ticks, every one of them pressed, once the cooldown is full again.
        int held = turn + 13;
        int last = held + 14;

        // The health each has left, and how many separate blows took any off it, counted rather than assumed: a blow
        // thrown out inside the immunity window takes nothing, and a blow that missed is worth knowing about.
        float[] left = {waitedFor.getHealth(), spammedAt.getHealth()};
        int[] landed = new int[2];

        run(helper, tick -> {

            agent.controls().attack = tick == first || tick == second || tick >= held && tick < last;

            for (int side = 0; side < 2; side++) {

                Mob mob = side == 0 ? waitedFor : spammedAt;

                if (mob.getHealth() < left[side]) {

                    landed[side]++;
                    left[side] = mob.getHealth();
                }
            }

            if (tick == turn) {

                // Round to the west, where the other one stands.
                agent.setYRot(90.0F);
                agent.setYHeadRot(90.0F);
            }

            if (tick == held) {

                helper.assertValueEqual(agent.getAttackStrengthScale(0.0F), 1.0F, "attack strength before the held presses");
            }

            if (tick == last) {

                float waitedLost = waitedFor.getMaxHealth() - left[0];
                float spammedLost = spammedAt.getMaxHealth() - left[1];

                String what = "holding it down took " + spammedLost + " health in " + landed[1] + " blows out of "
                        + (last - held) + " presses, where waiting took " + waitedLost + " in " + landed[0] + " out of 2";

                helper.assertValueEqual(landed[0], 2, "blows landed by the two that waited for the cooldown");
                helper.assertTrue(spammedLost > 0.0F, "The held presses landed nothing at all: " + what);
                helper.assertTrue(spammedLost < 0.75F * waitedLost,
                        "Holding attack down was not three quarters of waiting or less: " + what);
                return true;
            }

            return false;
        });
    }

    /**
     * A body that no blow can shift, by the attribute a warden has most of. Nothing else about being hit changes: the
     * damage, the hurt immunity and the flinch are all still a player's.
     */
    private static Mob heldStill(Mob mob) {

        AttributeInstance resistance = mob.getAttribute(Attributes.KNOCKBACK_RESISTANCE);

        if (resistance != null) {

            resistance.setBaseValue(1.0D);
        }

        return mob;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Placing blocks
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * A block goes down where it is aimed and uses one up, but never into anything standing there, the agent itself
     * included.
     */
    @GameTest(template = ARENA, timeoutTicks = 100)
    public static void placingUsesABlockUpAndNeverBuildsIntoAnything(GameTestHelper helper) {

        // Looking down at the floor a block and a half ahead.
        AgentMob agent = agent(helper, new BlockPos(4, 2, 2), 0.0F, 45.0F);
        agent.setHotbarItem(0, new ItemStack(Items.DIRT, 4));

        Mob standing = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(4, 2, 4));

        BlockPos ahead = new BlockPos(4, 2, 4);
        BlockPos underfoot = new BlockPos(4, 2, 2);

        run(helper, tick -> {

            agent.controls().use = tick == 0 || tick == 10 || tick == 20;

            if (tick == 1) {

                helper.assertTrue(helper.getBlockState(ahead).isAir(), "A block was put down inside a zombie");
                helper.assertValueEqual(agent.getHotbarItem(0).getCount(), 4, "blocks after placing into a zombie");
                standing.discard();
            }

            if (tick == 11) {

                helper.assertTrue(agent.executed().usedOnBlock, "The use did not go to the block");
                helper.assertTrue(helper.getBlockState(ahead).is(Blocks.DIRT), "No block went down where it was aimed");
                helper.assertValueEqual(agent.getHotbarItem(0).getCount(), 3, "blocks after placing one");

                // Straight down, at the floor under its own feet.
                agent.setXRot(90.0F);
            }

            if (tick == 21) {

                helper.assertTrue(helper.getBlockState(underfoot).isAir(), "The agent built a block into itself");
                helper.assertValueEqual(agent.getHotbarItem(0).getCount(), 3, "blocks after placing into itself");
                return true;
            }

            return false;
        });
    }

    /**
     * Blocks face the way they would for a player standing where the agent stands: a furnace turns to face it, and a torch
     * aimed at a wall goes on the wall.
     */
    @GameTest(template = ARENA, timeoutTicks = 100)
    public static void placedBlocksFaceAsForAPlayer(GameTestHelper helper) {

        // Facing west, looking down at the floor ahead.
        AgentMob agent = agent(helper, new BlockPos(4, 2, 4), 90.0F, 45.0F);
        agent.setHotbarItem(0, new ItemStack(Items.FURNACE));
        agent.setHotbarItem(1, new ItemStack(Items.TORCH));

        BlockPos furnace = new BlockPos(2, 2, 4);
        BlockPos torch = new BlockPos(1, 3, 4);

        run(helper, tick -> {

            agent.controls().use = tick == 0 || tick == 10;
            agent.controls().selectedSlot = tick < 5 ? 0 : 1;

            if (tick == 1) {

                helper.assertTrue(helper.getBlockState(furnace).is(Blocks.FURNACE), "No furnace went down");
                helper.assertValueEqual(helper.getBlockState(furnace).getValue(FurnaceBlock.FACING), Direction.EAST, "the furnace's facing");

                // Level, at the wall beyond the furnace.
                agent.setXRot(0.0F);
            }

            if (tick == 11) {

                helper.assertTrue(helper.getBlockState(torch).is(Blocks.WALL_TORCH), "No torch went on the wall");
                helper.assertValueEqual(helper.getBlockState(torch).getValue(WallTorchBlock.FACING), Direction.EAST, "the torch's facing");
                return true;
            }

            return false;
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // What it is paid
    // ---------------------------------------------------------------------------------------------------------------

    /** A sword blow on the opponent is paid once, for the health it took; a blow on anything else is not paid at all. */
    @GameTest(template = ARENA, timeoutTicks = 100)
    public static void swordBlowIsPaidOnceAndOnlyOnTheOpponent(GameTestHelper helper) {

        AgentMob agent = agent(helper, new BlockPos(4, 2, 2), 0.0F, 0.0F);

        // Far enough apart that a sweep at one never reaches the other.
        Mob opponent = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(4, 2, 4));
        Mob bystander = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(2, 2, 2));

        Episode episode = new Episode(FIGHT_TICKS, null, opponent);
        agent.startEpisode(episode);
        Loadout.SWORD.equip(agent);

        run(helper, tick -> {

            agent.controls().attack = tick == 20 || tick == 45;

            if (tick == 21) {

                helper.assertTrue(opponent.getHealth() < opponent.getMaxHealth(), "The blow missed the opponent");
                paidExactly(helper, episode, opponent);

                // Round to the bystander, to the west.
                agent.setYRot(90.0F);
                agent.setYHeadRot(90.0F);
            }

            if (tick == 46) {

                helper.assertTrue(bystander.getHealth() < bystander.getMaxHealth(), "The blow missed the bystander");
                paidExactly(helper, episode, opponent);
                return true;
            }

            return false;
        });
    }

    /**
     * The episode holds exactly what the opponent's lost health is worth, which is what it holds when every hit is paid
     * once and nothing else is paid at all.
     */
    private static void paidExactly(GameTestHelper helper, Episode episode, Mob opponent) {

        float lost = opponent.getMaxHealth() - opponent.getHealth();
        float owed = AgentReward.DEALT_WEIGHT * lost / Math.max(1.0F, opponent.getMaxHealth());
        float paid = episode.reward().episodeTotal();

        helper.assertTrue(Math.abs(paid - owed) < 1.0E-5F, "The agent was paid " + paid + " for " + lost + " health, not " + owed);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // What the agent sees of its own use, and of what is shot at it
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * The charge of whatever is in use, as the echo carries it. It is the item's own reckoning and not a count of ticks: a
     * bow reads the power its arrow would leave at, which is not linear in the draw, and a crossbow the fraction of its
     * wind that is in. Both reach one at the moment letting go is worth it, which is what lets a fighter work either
     * without knowing which it holds, and both read nothing with the hands free.
     */
    @GameTest(template = ARENA, timeoutTicks = 160)
    public static void useProgressIsTheItemsOwnCharge(GameTestHelper helper) {

        AgentMob agent = agent(helper, new BlockPos(4, 2, 2), 0.0F, 0.0F);
        Loadout.BOW.equip(agent);

        // A draw starts on the tick after the press, so a step's number is one more than the ticks drawn by then.
        int crossbowFrom = 40;

        run(helper, tick -> {

            agent.controls().use = tick < 21 || tick >= crossbowFrom && tick < crossbowFrom + 26;

            if (tick == 1) {

                helper.assertTrue(agent.isUsingItem(), "The bow is being drawn");
                charged(helper, agent, 0.0F, "a draw of no ticks");
            }

            // A player's bow: the power of a draw of t ticks is (f * f + 2f) / 3 for f = t / 20, capped at one.
            if (tick == 11) {

                charged(helper, agent, 0.4166667F, "half a draw");
            }

            if (tick == 21) {

                charged(helper, agent, 1.0F, "a full draw");
            }

            if (tick == 23) {

                helper.assertFalse(agent.isUsingItem(), "The bow is let go");
                charged(helper, agent, 0.0F, "empty hands");

                Loadout.CROSSBOW.equip(agent);
            }

            // A crossbow winds in twenty five ticks and its charge is the plain fraction of them.
            if (tick == crossbowFrom + 11) {

                charged(helper, agent, 10.0F / 25.0F, "ten ticks of a wind");
            }

            if (tick == crossbowFrom + 26) {

                charged(helper, agent, 1.0F, "a full wind");
                return true;
            }

            return false;
        });
    }

    /** What the echo says the charge is, straight out of the observation the network reads, and out of the record behind it. */
    private static void charged(GameTestHelper helper, AgentMob agent, float expected, String what) {

        float[] observation = new float[ObservationSchema.OBS_DIM];
        AgentObservation.write(agent, agent.brain().enemySlots(), observation, 0);

        float echoed = observation[ObservationSchema.ECHO_OFFSET + ECHO_USE_PROGRESS];

        helper.assertTrue(Math.abs(agent.executed().useProgress - expected) < 1.0E-4F,
                "The body made " + what + " out to be charged " + agent.executed().useProgress + ", not " + expected);
        helper.assertTrue(Math.abs(echoed - expected) < 1.0E-4F,
                "The echo carries " + echoed + " for " + what + ", not " + expected);
    }

    /**
     * Only a shot actually coming at the agent takes an enemy slot, and never one a body wants. Four arrows are put in the
     * air at once: one on its way to the agent, one crossing well wide of it, one lying still where it fell, and one the
     * agent fired itself. Exactly the first of them is worth knowing about.
     *
     * <p>The mob keeps the slot it had. That is the whole point of the rule: a network that learned to fight whatever is
     * nearest would turn and swing at an arrow a block away while the skeleton that fired it stood off and shot again.
     */
    @GameTest(template = ARENA, timeoutTicks = 120)
    public static void onlyShotsComingAtTheAgentTakeASlot(GameTestHelper helper) {

        AgentMob agent = agent(helper, new BlockPos(4, 2, 2), 0.0F, 0.0F);
        Mob shooter = helper.spawnWithNoFreeWill(EntityType.SKELETON, new BlockPos(4, 2, 6));

        // Bounded, so the arenas either side of this one are not in the agent's view; see AgentVindicatorGameTest.
        agent.startEpisode(new Episode(FIGHT_TICKS, bounds(helper), shooter));
        Loadout.BOW.equip(agent);

        Arrow[] arrows = new Arrow[4];

        run(helper, tick -> {

            if (tick == 2) {

                helper.assertTrue(agent.brain().enemySlots().occupant(0) == shooter, "The skeleton never took a slot");

                // Straight at the agent, four blocks off, and slow enough to still be in the air a moment later.
                arrows[0] = shoot(helper, shooter, 4.5D, 3.0D, 6.5D, 0.0D, 0.0D, -0.5D);

                // Across the arena rather than at it: it would pass three blocks wide, which nothing could hit from.
                arrows[1] = shoot(helper, shooter, 1.5D, 3.0D, 6.5D, 0.5D, 0.0D, 0.0D);

                // Lying where it fell, which is where an arrow spends most of its life.
                arrows[2] = shoot(helper, shooter, 6.5D, 3.0D, 5.5D, 0.0D, 0.0D, 0.0D);

                // The agent's own, on its way to the skeleton. Nobody flinches at their own arrow.
                arrows[3] = shoot(helper, agent, 4.5D, 3.0D, 3.5D, 0.0D, 0.0D, 0.5D);
                return false;
            }

            if (tick == 4) {

                helper.assertTrue(agent.brain().enemySlots().occupant(0) == shooter, "The arrows pushed the skeleton out of its slot");

                int slot = slotOf(agent, arrows[0]);
                helper.assertTrue(slot > 0, "The arrow on its way to the agent took no slot");

                for (int other = 1; other < arrows.length; other++) {

                    helper.assertTrue(slotOf(agent, arrows[other]) < 0,
                            "An arrow that was crossing, still or the agent's own took slot " + slotOf(agent, arrows[other]));
                }

                helper.assertValueEqual(agent.brain().enemySlots().inRangeCount(), 1, "bodies in range");

                float[] observation = new float[ObservationSchema.OBS_DIM];
                AgentObservation.write(agent, agent.brain().enemySlots(), observation, 0);

                int at = ObservationSchema.enemyOffset(slot);

                helper.assertTrue(observation[at + ObservationSchema.ENEMY_PRESENT] > 0.5F, "The arrow's slot reads empty");
                helper.assertTrue(AgentObservation.isProjectileKind(observation[at + ObservationSchema.ENEMY_KIND]),
                        "The arrow reads as a body, kind " + observation[at + ObservationSchema.ENEMY_KIND]);
                helper.assertValueEqual(observation[at + ObservationSchema.ENEMY_HEALTH], 0.0F, "an arrow's health");
                helper.assertValueEqual(observation[at + ObservationSchema.ENEMY_MAIN_HAND], 0.0F, "what an arrow holds");
                helper.assertValueEqual(observation[at + ObservationSchema.ENEMY_SWINGING], 0.0F, "an arrow swinging");

                int mob = ObservationSchema.enemyOffset(0);

                helper.assertTrue(observation[mob + ObservationSchema.ENEMY_KIND] == AgentObservation.KIND_MONSTER,
                        "The skeleton stopped reading as a monster");
                return true;
            }

            return false;
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The clock and the quiver
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * A short clock, so that a test can watch one run all the way out. A real fight's is 1,200 ticks and a league
     * matchup's is its own, which is the whole reason the field is a fraction of this fight's limit rather than of a
     * constant.
     */
    private static final int SHORT_CLOCK = 40;

    /**
     * The self block says how much of this fight's clock has run and how much is left in the quiver, which are the two
     * things the agent was being paid by and could not see.
     *
     * <p>The clock is the reward's own count: running it out is a loss and a win pays a bonus that scales with what is
     * left, so the same position is worth about +3 early and -2 at the buzzer, and neither the critic nor the policy had
     * anything to tell those apart by. The quiver is what the teacher has to infer from a use press that produced nothing,
     * which is an inference a network sampling a button cannot make.
     *
     * <p>Two agents, because both fields have an answer for a body they do not apply to: the swordsman carries nothing
     * that shoots and is in no fight, so it reads no arrows and a clock that never moves.
     */
    @GameTest(template = ARENA, timeoutTicks = 200)
    public static void theClockRunsUpAndTheQuiverRunsDown(GameTestHelper helper) {

        AgentMob archer = agent(helper, new BlockPos(4, 2, 1), 0.0F, 0.0F);
        AgentMob swordsman = agent(helper, new BlockPos(2, 2, 1), 0.0F, 0.0F);
        Mob opponent = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(4, 2, 6));

        archer.startEpisode(new Episode(SHORT_CLOCK, bounds(helper), opponent));
        Loadout.BOW.equip(archer);
        Loadout.SWORD.equip(swordsman);

        float[] ran = {0.0F};

        run(helper, tick -> {

            // One press is one arrow: the draw runs to full on its own, see onePressDrawsToFullAndLooses.
            archer.controls().use = tick == 1;

            float clock = selfField(archer, ObservationSchema.SELF_CLOCK);
            float arrows = selfField(archer, ObservationSchema.SELF_ARROWS);

            // This fight's own ticks over this fight's own limit, which is what the reward is charging against.
            helper.assertValueEqual(clock,
                    Math.min(1.0F, archer.episode().reward().elapsedTicks() / (float) SHORT_CLOCK), "the clock");
            helper.assertTrue(clock >= ran[0], "The clock went backwards, " + ran[0] + " to " + clock);
            ran[0] = clock;

            // What is in the hotbar, as a fraction of the 64 a bow loadout carries.
            helper.assertValueEqual(arrows, archer.getHotbarItem(1).getCount() / ObservationSchema.ARROW_SCALE, "arrows left");

            // A body with nothing that shoots, and no fight for its clock to run out of.
            helper.assertValueEqual(selfField(swordsman, ObservationSchema.SELF_ARROWS), 0.0F, "a swordsman's arrows");
            helper.assertValueEqual(selfField(swordsman, ObservationSchema.SELF_CLOCK), 0.0F, "the clock of no fight");

            if (tick == 0) {

                helper.assertValueEqual(clock, 0.0F, "the clock on the first tick");
                helper.assertValueEqual(arrows, 1.0F, "a full quiver");
            }

            // Well past the twenty ticks a full draw takes, and past half of a forty tick clock.
            if (tick == 30) {

                helper.assertValueEqual(archer.getHotbarItem(1).getCount(), 63, "arrows left in the hotbar");
                helper.assertTrue(arrows < 1.0F, "The quiver still reads full after a shot");
                helper.assertTrue(clock > 0.5F, "Half the clock has gone and it reads " + clock);
            }

            if (tick == SHORT_CLOCK + 10) {

                helper.assertValueEqual(clock, 1.0F, "the clock once the fight's time is up");
                return true;
            }

            return false;
        });
    }

    /** One field of an agent's self block, out of the observation a network would be handed. */
    private static float selfField(AgentMob agent, int field) {

        float[] observation = new float[ObservationSchema.OBS_DIM];
        AgentObservation.write(agent, agent.brain().enemySlots(), observation, 0);

        return observation[ObservationSchema.SELF_OFFSET + field];
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Telling one opponent from another
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * A slot says what the thing in it can do, which is what lets one mob be told from another at all. Before these
     * fields a zombie and a warden filled a slot identically — both "monster", both at a health of 1, both empty handed —
     * and the league showed what that cost: every ordinary mob beaten 75 to 98% and 0% against the warden and against two
     * creepers.
     */
    @GameTest(template = ARENA, timeoutTicks = 100)
    public static void aSlotSaysWhatTheOpponentCanDo(GameTestHelper helper) {

        AgentMob agent = agent(helper, new BlockPos(4, 2, 1), 0.0F, 0.0F);
        Mob zombie = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(3, 2, 4));
        Mob skeleton = helper.spawnWithNoFreeWill(EntityType.SKELETON, new BlockPos(5, 2, 4));

        run(helper, tick -> {

            if (tick < 2) {

                return false;
            }

            float[] observation = new float[ObservationSchema.OBS_DIM];
            AgentObservation.write(agent, agent.brain().enemySlots(), observation, 0);

            int zombieAt = ObservationSchema.enemyOffset(slotOf(agent, zombie));
            int skeletonAt = ObservationSchema.enemyOffset(slotOf(agent, skeleton));

            // Hearts, not a fraction: twenty of them, whole.
            helper.assertValueEqual(observation[zombieAt + ObservationSchema.ENEMY_MAX_HEALTH],
                    20.0F / ObservationSchema.HEALTH_SCALE, "a zombie's max health");
            helper.assertValueEqual(observation[zombieAt + ObservationSchema.ENEMY_HEALTH_LEFT],
                    20.0F / ObservationSchema.HEALTH_SCALE, "a zombie's health left");

            // What tells these two apart without either having moved: one shoots, the other hits harder up close.
            helper.assertValueEqual(observation[skeletonAt + ObservationSchema.ENEMY_SHOOTS], 1.0F, "a skeleton shoots");
            helper.assertValueEqual(observation[zombieAt + ObservationSchema.ENEMY_SHOOTS], 0.0F, "a zombie shoots");
            helper.assertTrue(observation[zombieAt + ObservationSchema.ENEMY_DAMAGE]
                    > observation[skeletonAt + ObservationSchema.ENEMY_DAMAGE], "a zombie hits harder than a skeleton");

            for (int at : new int[] {zombieAt, skeletonAt}) {

                helper.assertValueEqual(observation[at + ObservationSchema.ENEMY_FLIES], 0.0F, "either of them flying");
                helper.assertValueEqual(observation[at + ObservationSchema.ENEMY_EXPLODES], 0.0F, "either of them exploding");
                helper.assertValueEqual(observation[at + ObservationSchema.ENEMY_FUSE], 0.0F, "either of them with a fuse");
                helper.assertTrue(observation[at + ObservationSchema.ENEMY_WIDTH] > 0.0F, "a body with no width");
                helper.assertTrue(observation[at + ObservationSchema.ENEMY_HEIGHT] > 0.0F, "a body with no height");
                helper.assertTrue(observation[at + ObservationSchema.ENEMY_SPEED] > 0.0F, "a body that cannot move");
                helper.assertValueEqual(observation[at + ObservationSchema.ENEMY_KNOCKBACK_RESISTANCE], 0.0F,
                        "what a zombie or a skeleton shrugs off");
            }

            return true;
        });
    }

    /** A creeper says it explodes, and says how far along its fuse is, which the teacher used to have to guess. */
    @GameTest(template = ARENA, timeoutTicks = 100)
    public static void aCreeperSaysItExplodesAndHowCloseItIs(GameTestHelper helper) {

        AgentMob agent = agent(helper, new BlockPos(4, 2, 2), 0.0F, 0.0F);
        Creeper creeper = helper.spawnWithNoFreeWill(EntityType.CREEPER, new BlockPos(4, 2, 4));

        run(helper, tick -> {

            if (tick == 2) {

                creeper.ignite();
                return false;
            }

            if (tick < 8) {

                return false;
            }

            float[] observation = new float[ObservationSchema.OBS_DIM];
            AgentObservation.write(agent, agent.brain().enemySlots(), observation, 0);
            int at = ObservationSchema.enemyOffset(slotOf(agent, creeper));

            helper.assertValueEqual(observation[at + ObservationSchema.ENEMY_EXPLODES], 1.0F, "a creeper exploding");
            helper.assertTrue(observation[at + ObservationSchema.ENEMY_FUSE] > 0.0F,
                    "a lit creeper's fuse reads " + observation[at + ObservationSchema.ENEMY_FUSE]);
            helper.assertTrue(observation[at + ObservationSchema.ENEMY_FUSE] < 1.0F, "the fuse is already spent");
            return true;
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Lava poured for a fight, and taken away again
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * A poured pool is nine blocks of lava, and every block of ground it touched is exactly as it was once it is drained.
     * That second part is the one that matters: a site hosts a hundred fights and the ground under it is a hard-linked
     * library shared between workers, so a pool left behind by one fight would still be there for the other ninety-nine, and
     * the library would rot a pool at a time.
     *
     * <p>Where a pool goes is not checked here. That needs open ground with a heightmap that means something, and this
     * arena is a closed bedrock box; the live run is what exercises the search. See {@link PouredHazards}.
     */
    @GameTest(template = ARENA, timeoutTicks = 100)
    public static void pouredLavaIsLavaAndLeavesNothingBehind(GameTestHelper helper) {

        ServerLevel level = helper.getLevel();

        // The floor of the arena, which is what a body in here stands on.
        BlockPos ground = helper.absolutePos(new BlockPos(4, 1, 4));

        // Everything the pool can reach: two blocks of margin either way, one below for the ground it makes solid, two
        // above for the plants it clears.
        AABB box = new AABB(ground.offset(-4, -3, -4)).minmax(new AABB(ground.offset(4, 4, 4)));
        Map<BlockPos, BlockState> before = new HashMap<>();

        BlockPos.betweenClosedStream(box).forEach(at -> before.put(at.immutable(), level.getBlockState(at)));

        PouredHazards.Pool pool = PouredHazards.pourAt(level, ground);

        int lava = 0;

        for (BlockPos at : before.keySet()) {

            lava += level.getBlockState(at).is(Blocks.LAVA) ? 1 : 0;
        }

        helper.assertValueEqual(lava, 9, "blocks of poured lava");

        PouredHazards.drain(level, pool);

        for (Map.Entry<BlockPos, BlockState> entry : before.entrySet()) {

            BlockState now = level.getBlockState(entry.getKey());

            helper.assertTrue(now == entry.getValue(), "Draining left " + now + " at " + entry.getKey()
                    + " where there had been " + entry.getValue());
        }

        helper.succeed();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Getting out of something that hurts
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * The teacher gets itself out of powder snow, with a zombie right there to fight. A body in powder snow cannot jump out
     * and freezes where it stands, and over 4,000 fights on the terrain library that was 14 of the teacher's 15 deaths that
     * were not the vindicator's doing: it keeps off the stuff, and then a blow knocks it in.
     *
     * <p>Both ways out are covered. One block of it is walked out of, since powder snow only takes a tenth off a body's
     * speed sideways. A patch too wide to step clear of in one is broken out of instead, which is the same escape and what
     * a cobweb or a berry bush would need.
     */
    @GameTest(template = ARENA, timeoutTicks = 220)
    public static void theTeacherGetsOutOfPowderSnow(GameTestHelper helper) {

        stuckInPowderSnow(helper, 0);
    }

    @GameTest(template = ARENA, timeoutTicks = 220)
    public static void theTeacherBreaksOutOfPowderSnow(GameTestHelper helper) {

        stuckInPowderSnow(helper, 1);
    }

    /**
     * An agent in the middle of a square of powder snow this many blocks either side, driven by the scripted fighter with a
     * zombie to fight, which has to be out of it before the fight.
     */
    private static void stuckInPowderSnow(GameTestHelper helper, int radius) {

        BlockPos feet = new BlockPos(4, 2, 3);

        for (int x = -radius; x <= radius; x++) {

            for (int z = -radius; z <= radius; z++) {

                helper.setBlock(feet.offset(x, 0, z), Blocks.POWDER_SNOW);
            }
        }

        AgentMob agent = agent(helper, feet, 0.0F, 0.0F);
        Mob opponent = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(4, 2, 6));

        agent.startEpisode(new Episode(FIGHT_TICKS, bounds(helper), opponent));
        Loadout.SWORD.equip(agent);

        // The scripted fighter, not the test's own presses: what is being checked is what it decides to do about the snow.
        agent.brain().use(Brains.scripted());

        run(helper, tick -> {

            if (tick == 1) {

                helper.assertTrue(inPowderSnow(helper, agent), "The agent did not start in the snow");
            }

            // Long enough to walk a block, or to break two of them at eight ticks each with a few to spare for turning.
            if (tick == 60) {

                helper.assertFalse(inPowderSnow(helper, agent), "The agent is still in powder snow after 60 ticks");
                helper.assertTrue(agent.isAlive(), "The agent died getting out");
                return true;
            }

            return false;
        });
    }

    /**
     * Whether the block the agent's feet are in, or the one its head is in, is powder snow. Straight out of the level at
     * the agent's own position, since the agent knows where it is in the world and turning that back into a position in the
     * test would only be a rotation to get wrong.
     */
    private static boolean inPowderSnow(GameTestHelper helper, AgentMob agent) {

        BlockPos feet = agent.blockPosition();

        return helper.getLevel().getBlockState(feet).is(Blocks.POWDER_SNOW)
                || helper.getLevel().getBlockState(feet.above()).is(Blocks.POWDER_SNOW);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // What the teacher does with a bow behind a sword
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * The teacher starts no draw it cannot finish. A draw is twenty ticks at a fifth of walking pace and changing slot is
     * the only way out of one, so a draw begun at something that arrives first is a draw thrown away: no arrow, and the
     * movement gone for as long as it lasted. A vindicator six blocks off covers that in twenty five ticks, which is why
     * nothing is drawn at it here — and it is what the distance alone could not say, since six blocks is past the range
     * a fighter with something to shoot used to shoot from.
     *
     * <p>It swings instead, which is the other half of the check: a rule that simply stopped the teacher doing anything
     * would pass the first assertion and fail this one.
     */
    @GameTest(template = ARENA, timeoutTicks = 160)
    public static void theTeacherStartsNoDrawItCannotFinish(GameTestHelper helper) {

        AgentMob agent = agent(helper, new BlockPos(4, 2, 1), 0.0F, 0.0F);
        Mob opponent = helper.spawn(EntityType.VINDICATOR, new BlockPos(4, 2, 7));

        // spawn() skips finalizeSpawn, and a vindicator without the axe it is supposed to carry is not the mob whose
        // speed is being reasoned about: an empty handed one reads as something that might be a creeper.
        opponent.finalizeSpawn(helper.getLevel(),
                helper.getLevel().getCurrentDifficultyAt(helper.absolutePos(new BlockPos(4, 2, 7))),
                MobSpawnType.EVENT, null);

        opponent.setTarget(agent);

        agent.startEpisode(new Episode(FIGHT_TICKS, bounds(helper), opponent));
        Loadouts.SWORD_AND_BOW.equip(agent);
        agent.brain().use(Brains.scripted());

        int[] draws = {0};
        int[] shots = {0};
        int[] swings = {0};
        boolean[] using = {false};

        run(helper, tick -> {

            draws[0] += agent.executed().using && !using[0] ? 1 : 0;
            using[0] = agent.executed().using;
            shots[0] += agent.executed().shotFired ? 1 : 0;
            swings[0] += agent.executed().attackHit ? 1 : 0;

            helper.assertValueEqual(shots[0], draws[0], "arrows loosed against draws begun, by tick " + tick);

            // Long enough for the vindicator to cross six blocks and for a blow to land, and for a doomed draw to have
            // been begun and given up several times over.
            if (tick == 120) {

                helper.assertTrue(swings[0] > 0, "The teacher landed no blow in 120 ticks");
                return true;
            }

            return false;
        });
    }

    /**
     * The teacher still draws where a draw is the right answer. A skeleton standing six blocks off shoots back, and
     * closing on something that shoots is no answer to it, so the draw goes up and the arrow leaves. This is the assertion
     * that stops the rule above being satisfied by never drawing at all.
     */
    @GameTest(template = ARENA, timeoutTicks = 160)
    public static void theTeacherDrawsAtWhatShootsBack(GameTestHelper helper) {

        AgentMob agent = agent(helper, new BlockPos(4, 2, 1), 0.0F, 0.0F);
        Mob opponent = helper.spawnWithNoFreeWill(EntityType.SKELETON, new BlockPos(4, 2, 7));

        opponent.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));

        agent.startEpisode(new Episode(FIGHT_TICKS, bounds(helper), opponent));
        Loadouts.SWORD_AND_BOW.equip(agent);
        agent.brain().use(Brains.scripted());

        int[] shots = {0};

        run(helper, tick -> {

            shots[0] += agent.executed().shotFired ? 1 : 0;

            // A draw is twenty ticks, and the shot goes once the aim is on; sixty leaves room for the swap to the bow and
            // for the aim to come round.
            if (tick == 60) {

                helper.assertTrue(shots[0] > 0, "The teacher loosed nothing at a skeleton six blocks off in 60 ticks");
                return true;
            }

            return false;
        });
    }

    /** An arrow in the air from wherever, with whatever velocity, as if somebody had loosed it. */
    private static Arrow shoot(GameTestHelper helper, Mob shooter, double x, double y, double z, double vx, double vy, double vz) {

        Vec3 at = helper.absoluteVec(new Vec3(x, y, z));
        Arrow arrow = new Arrow(helper.getLevel(), at.x, at.y, at.z, new ItemStack(Items.ARROW), null);

        arrow.setOwner(shooter);
        arrow.setDeltaMovement(vx, vy, vz);
        helper.getLevel().addFreshEntity(arrow);

        return arrow;
    }

    /** Which of the agent's enemy slots this entity holds, or -1 for none. */
    private static int slotOf(AgentMob agent, Entity of) {

        for (int slot = 0; slot < ObservationSchema.ENEMY_SLOTS; slot++) {

            if (agent.brain().enemySlots().occupant(slot) == of) {

                return slot;
            }
        }

        return -1;
    }

    /** The plot this test owns, with a little slack: the box an agent in it is allowed to see into. */
    private static AABB bounds(GameTestHelper helper) {

        return new AABB(Vec3.atLowerCornerOf(helper.absolutePos(BlockPos.ZERO)),
                Vec3.atLowerCornerOf(helper.absolutePos(new BlockPos(9, 9, 9)))).inflate(1.0D);
    }

    // ---------------------------------------------------------------------------------------------------------------

    /** A training agent standing on a block, looking the given way, with nothing but the test's presses to drive it. */
    private static AgentMob agent(GameTestHelper helper, BlockPos feet, float yaw, float pitch) {

        AgentMob agent = helper.spawn(ModEntities.trainingAgent(), feet);

        agent.setYRot(yaw);
        agent.setYHeadRot(yaw);
        agent.setYBodyRot(yaw);
        agent.setXRot(pitch);

        HeldControls.drive(agent);
        return agent;
    }

    private static Holder<Enchantment> enchantment(GameTestHelper helper, ResourceKey<Enchantment> key) {

        return helper.getLevel().registryAccess().registryOrThrow(Registries.ENCHANTMENT).getHolderOrThrow(key);
    }

    /**
     * Runs a test a tick at a time. The step is called on the tick the test is set up, with zero, and after every tick
     * the world takes from then on, with how many that makes, until it returns true. Controls pressed in a step are what
     * the agents act on in the next tick, so a step's checks see the tick that step's number counts. A failed assertion
     * fails the test there and then.
     */
    private static void run(GameTestHelper helper, IntPredicate step) {

        int[] tick = {0};

        helper.onEachTick(() -> {

            if (step.test(tick[0]++)) {

                helper.succeed();
            }
        });
    }

    /**
     * A brain with nothing of its own to say: it hands each agent back whatever the test last pressed on its controls.
     * The presses still go out through the action vector and come back in through {@link ActionSchema}, as a network's
     * would. The play suite drives its agents with it too.
     */
    static final class HeldControls implements Brain {

        private static final HeldControls INSTANCE = new HeldControls();

        /** Every agent this drives, by entity id, which is what a step names its rows by. */
        private final Int2ObjectMap<AgentMob> agents = new Int2ObjectOpenHashMap<>();

        static void drive(AgentMob agent) {

            INSTANCE.agents.put(agent.getId(), agent);
            agent.brain().use(INSTANCE);
        }

        /** Everything it reads and writes is the humanoid's action vector, so that is the body it drives. */
        @Override
        public Species species() {

            return Species.HUMANOID;
        }

        @Override
        public void act(BrainStep step) {

            for (int index = 0; index < step.count; index++) {

                AgentMob agent = this.agents.get(step.agentIds[index]);

                if (agent == null) {

                    continue;
                }

                MobControls pressed = agent.controls();
                int at = index * ActionSchema.ACT_DIM;

                step.actions[at + ActionSchema.MOVE_FORWARD] = pressed.moveForward;
                step.actions[at + ActionSchema.MOVE_STRAFE] = pressed.moveStrafe;
                step.actions[at + ActionSchema.AIM_YAW] = pressed.aimYaw;
                step.actions[at + ActionSchema.AIM_PITCH] = pressed.aimPitch;
                step.actions[at + ActionSchema.JUMP] = pressed.jump ? 1.0F : 0.0F;
                step.actions[at + ActionSchema.SPRINT] = pressed.sprint ? 1.0F : 0.0F;
                step.actions[at + ActionSchema.SNEAK] = pressed.sneak ? 1.0F : 0.0F;
                step.actions[at + ActionSchema.ATTACK] = pressed.attack ? 1.0F : 0.0F;
                step.actions[at + ActionSchema.USE] = pressed.use ? 1.0F : 0.0F;
                step.actions[at + ActionSchema.USE_OFFHAND] = pressed.useOffhand ? 1.0F : 0.0F;
                step.actions[at + ActionSchema.SELECTED_SLOT] = pressed.selectedSlot;
            }
        }
    }
}
