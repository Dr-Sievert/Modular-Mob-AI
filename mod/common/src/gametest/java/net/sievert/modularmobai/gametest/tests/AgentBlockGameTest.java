package net.sievert.modularmobai.gametest.tests;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FurnaceBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.phys.AABB;
import net.sievert.modularmobai.entity.agent.AgentMob;
import net.sievert.modularmobai.gametest.GameTestGroup;

/**
 * Breaking a block and placing one, against the tick counts a player's own client works out. The third place the agent's
 * hands are not a player's is here and is deliberate: a crack keeps what it got to while the aim stays on the block, since a
 * network that presses attack on about half of its ticks could otherwise never finish one. See {@code docs/findings.md}.
 *
 * <p>See {@link Mechanics} for the arena, the brain and the rest the suite shares.
 */
@GameTestGroup
public class AgentBlockGameTest {

    // A player's ticks for each block, from its client: a tick's work is the tool's speed on the block over its hardness,
    // over thirty with the right tool and a hundred without, added up in floats until it reaches one.
    private static final int IRON_SHOVEL_ON_DIRT = 3;
    private static final int HAND_ON_DIRT = 15;
    private static final int HAND_ON_DIRT_IN_THE_AIR = 76;
    private static final int HAND_ON_STONE = 151;
    private static final int IRON_PICKAXE_ON_STONE = 8;

    // ---------------------------------------------------------------------------------------------------------------
    // Breaking blocks
    // ---------------------------------------------------------------------------------------------------------------

    /** An iron shovel takes dirt in a player's three ticks, and the dirt drops. */
    @GameTest(template = Mechanics.ARENA, timeoutTicks = 100)
    public static void shovelDigsDirtInAPlayersTime(GameTestHelper helper) {

        mines(helper, new ItemStack(Items.IRON_SHOVEL), Blocks.DIRT, IRON_SHOVEL_ON_DIRT, Items.DIRT);
    }

    /** Stone by hand takes a player 151 ticks, and a hand does not get stone's drop. */
    @GameTest(template = Mechanics.ARENA, timeoutTicks = 250)
    public static void handBreaksStoneSlowlyForNothing(GameTestHelper helper) {

        mines(helper, ItemStack.EMPTY, Blocks.STONE, HAND_ON_STONE, null);
    }

    /** An iron pickaxe takes stone in eight ticks, and the stone drops cobblestone. */
    @GameTest(template = Mechanics.ARENA, timeoutTicks = 100)
    public static void pickaxeBreaksStoneForCobblestone(GameTestHelper helper) {

        mines(helper, new ItemStack(Items.IRON_PICKAXE), Blocks.STONE, IRON_PICKAXE_ON_STONE, Items.COBBLESTONE);
    }

    /** Off the ground a block takes five times the work, here one agent floating beside another standing. */
    @GameTest(template = Mechanics.ARENA, timeoutTicks = 150)
    public static void miningInTheAirIsFiveTimesSlower(GameTestHelper helper) {

        BlockPos standingTarget = new BlockPos(2, 3, 3);
        BlockPos floatingTarget = new BlockPos(6, 5, 3);

        helper.setBlock(standingTarget, Blocks.DIRT);
        helper.setBlock(floatingTarget, Blocks.DIRT);

        AgentMob standing = Mechanics.agent(helper, new BlockPos(2, 2, 2), 0.0F, 0.0F);
        AgentMob floating = Mechanics.agent(helper, new BlockPos(6, 4, 2), 0.0F, 0.0F);
        floating.setNoGravity(true);

        int[] brokenAfter = {-1, -1};

        Mechanics.run(helper, tick -> {

            if (tick == Mechanics.SETTLE) {

                standing.controls().attack = true;
                floating.controls().attack = true;
            }

            if (tick <= Mechanics.SETTLE) {

                return false;
            }

            helper.assertFalse(floating.onGround(), "The floating agent is on the ground");

            if (brokenAfter[0] < 0 && helper.getBlockState(standingTarget).isAir()) {

                brokenAfter[0] = tick - Mechanics.SETTLE;
            }

            if (brokenAfter[1] < 0 && helper.getBlockState(floatingTarget).isAir()) {

                brokenAfter[1] = tick - Mechanics.SETTLE;
            }

            if (brokenAfter[1] < 0) {

                helper.assertTrue(tick - Mechanics.SETTLE < HAND_ON_DIRT_IN_THE_AIR, "The block in the air is still standing");
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
    @GameTest(template = Mechanics.ARENA, timeoutTicks = 100)
    public static void lettingGoKeepsTheBlocksProgress(GameTestHelper helper) {

        helper.setBlock(Mechanics.AT_EYE_LEVEL, Blocks.DIRT);
        AgentMob agent = Mechanics.agent(helper, Mechanics.MINER, 0.0F, 0.0F);

        int pressed = 10;
        int again = Mechanics.SETTLE + pressed + 2;

        Mechanics.run(helper, tick -> {

            agent.controls().attack = tick >= Mechanics.SETTLE && tick < Mechanics.SETTLE + pressed || tick >= again;

            if (tick < again) {

                helper.assertFalse(helper.getBlockState(Mechanics.AT_EYE_LEVEL).isAir(), "The dirt went before it was cracked through");
                return false;
            }

            if (!helper.getBlockState(Mechanics.AT_EYE_LEVEL).isAir()) {

                helper.assertTrue(tick - again < HAND_ON_DIRT - pressed, "The dirt is still standing, so its crack was lost");
                return false;
            }

            helper.assertValueEqual(tick - again, HAND_ON_DIRT - pressed, "presses to finish the dirt off");
            return true;
        });
    }

    /** Looking at something else does lose it: the crack is kept for the block under the aim, and for no other. */
    @GameTest(template = Mechanics.ARENA, timeoutTicks = 100)
    public static void lookingAwayStartsTheBlockOver(GameTestHelper helper) {

        helper.setBlock(Mechanics.AT_EYE_LEVEL, Blocks.DIRT);
        AgentMob agent = Mechanics.agent(helper, Mechanics.MINER, 0.0F, 0.0F);

        // Ten ticks of the fifteen, then turned two whole ticks of yaw away and the same back, which lands on the block
        // again because a tick of turn is the same size either way.
        int away = Mechanics.SETTLE + 10;
        int back = away + 2;
        int again = back + 2;

        Mechanics.run(helper, tick -> {

            agent.controls().attack = tick >= Mechanics.SETTLE;
            agent.controls().aimYaw = tick >= away && tick < back ? 1.0F : tick >= back && tick < again ? -1.0F : 0.0F;

            if (tick < again) {

                return false;
            }

            if (!helper.getBlockState(Mechanics.AT_EYE_LEVEL).isAir()) {

                helper.assertTrue(tick - again <= HAND_ON_DIRT + 2, "The dirt is still standing");
                return false;
            }

            helper.assertTrue(tick - again >= HAND_ON_DIRT - 1, "The dirt kept its crack through a look somewhere else: it "
                    + "went after " + (tick - again) + " ticks, where breaking it from nothing takes " + HAND_ON_DIRT);
            return true;
        });
    }

    /** Bedrock never breaks, and hitting it costs no more than any other block does, nothing. */
    @GameTest(template = Mechanics.ARENA, timeoutTicks = 100)
    public static void bedrockNeverBreaks(GameTestHelper helper) {

        // Half a block from the arena's far wall, looking at it.
        AgentMob agent = Mechanics.agent(helper, new BlockPos(4, 2, 6), 0.0F, 0.0F);
        BlockPos wall = new BlockPos(4, 3, 8);

        Mechanics.run(helper, tick -> {

            agent.controls().attack = tick >= Mechanics.SETTLE;

            helper.assertTrue(helper.getBlockState(wall).is(Blocks.BEDROCK), "The bedrock broke");

            if (tick == Mechanics.SETTLE + 60) {

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

        helper.setBlock(Mechanics.AT_EYE_LEVEL, block);

        AgentMob agent = Mechanics.agent(helper, Mechanics.MINER, 0.0F, 0.0F);
        agent.setHotbarItem(0, tool);

        String name = block.getName().getString();

        Mechanics.run(helper, tick -> {

            agent.controls().attack = tick >= Mechanics.SETTLE;

            if (tick <= Mechanics.SETTLE) {

                return false;
            }

            int held = tick - Mechanics.SETTLE;

            if (!helper.getBlockState(Mechanics.AT_EYE_LEVEL).isAir()) {

                if (held >= playerTicks) {

                    throw new GameTestAssertException(name + " is still standing after " + held + " ticks, where a player breaks it in " + playerTicks);
                }

                return false;
            }

            helper.assertValueEqual(held, playerTicks, "ticks to break " + name);

            List<ItemEntity> drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                    new AABB(helper.absolutePos(Mechanics.AT_EYE_LEVEL)).inflate(2.0D));

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
    @GameTest(template = Mechanics.ARENA, timeoutTicks = 100)
    public static void placingUsesABlockUpAndNeverBuildsIntoAnything(GameTestHelper helper) {

        // Looking down at the floor a block and a half ahead.
        AgentMob agent = Mechanics.agent(helper, new BlockPos(4, 2, 2), 0.0F, 45.0F);
        agent.setHotbarItem(0, new ItemStack(Items.DIRT, 4));

        Mob standing = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(4, 2, 4));

        BlockPos ahead = new BlockPos(4, 2, 4);
        BlockPos underfoot = new BlockPos(4, 2, 2);

        Mechanics.run(helper, tick -> {

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
    @GameTest(template = Mechanics.ARENA, timeoutTicks = 100)
    public static void placedBlocksFaceAsForAPlayer(GameTestHelper helper) {

        // Facing west, looking down at the floor ahead.
        AgentMob agent = Mechanics.agent(helper, new BlockPos(4, 2, 4), 90.0F, 45.0F);
        agent.setHotbarItem(0, new ItemStack(Items.FURNACE));
        agent.setHotbarItem(1, new ItemStack(Items.TORCH));

        BlockPos furnace = new BlockPos(2, 2, 4);
        BlockPos torch = new BlockPos(1, 3, 4);

        Mechanics.run(helper, tick -> {

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
}
