package net.sievert.modularmobai.gametest.tests;

import java.util.List;
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
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.sievert.modularmobai.arena.AgentReward;
import net.sievert.modularmobai.arena.Episode;
import net.sievert.modularmobai.arena.Loadout;
import net.sievert.modularmobai.brain.Brain;
import net.sievert.modularmobai.brain.BrainStep;
import net.sievert.modularmobai.brain.schema.ActionSchema;
import net.sievert.modularmobai.entity.ModEntities;
import net.sievert.modularmobai.entity.agent.AgentMob;
import net.sievert.modularmobai.entity.agent.MobControls;
import net.sievert.modularmobai.gametest.GameTestGroup;

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
     * A crossbow let go before it is wound does not load. Wound all the way and let go, it takes an arrow and keeps it for
     * as long as it is left alone, and the next use fires it: a critical, at a player's speed, which a mob's never is.
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

    /** Letting go of attack loses the work done: the block starts over from nothing. */
    @GameTest(template = ARENA, timeoutTicks = 100)
    public static void lettingGoStartsTheBlockOver(GameTestHelper helper) {

        helper.setBlock(AT_EYE_LEVEL, Blocks.DIRT);
        AgentMob agent = agent(helper, MINER, 0.0F, 0.0F);

        // Ten ticks of the fifteen, let go, and pressed again.
        int again = SETTLE + 12;

        run(helper, tick -> {

            agent.controls().attack = tick >= SETTLE && tick < SETTLE + 10 || tick >= again;

            if (tick <= again) {

                return false;
            }

            if (!helper.getBlockState(AT_EYE_LEVEL).isAir()) {

                helper.assertTrue(tick - again < HAND_ON_DIRT, "The dirt is still standing");
                return false;
            }

            helper.assertValueEqual(tick - again, HAND_ON_DIRT, "ticks to break the dirt after starting over");
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
     * would.
     */
    private static final class HeldControls implements Brain {

        private static final HeldControls INSTANCE = new HeldControls();

        /** Every agent this drives, by entity id, which is what a step names its rows by. */
        private final Int2ObjectMap<AgentMob> agents = new Int2ObjectOpenHashMap<>();

        private static void drive(AgentMob agent) {

            INSTANCE.agents.put(agent.getId(), agent);
            agent.brain().use(INSTANCE);
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
