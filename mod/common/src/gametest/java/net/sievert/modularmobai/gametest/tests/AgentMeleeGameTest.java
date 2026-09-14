package net.sievert.modularmobai.gametest.tests;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.LargeFireball;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.sievert.modularmobai.arena.Episode;
import net.sievert.modularmobai.arena.Loadout;
import net.sievert.modularmobai.entity.agent.AgentMob;
import net.sievert.modularmobai.gametest.GameTestGroup;

/**
 * A blow and a block: what a shield stops and from which side, what an axe does to one on either side of the fight, what
 * holding an item in use costs in movement and in swings, what a press of attack costs, and what a hit that lands is paid.
 *
 * <p>And what a swing at a ghast's fireball does, which belongs here because it is a swing: a player's own rule about what
 * the press meets, not a rule about projectiles.
 *
 * <p>See {@link Mechanics} for the arena, the brain and the rest the suite shares.
 */
@GameTestGroup
public class AgentMeleeGameTest {

    // ---------------------------------------------------------------------------------------------------------------
    // Shields and axes
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * A raised shield stops a blow from the front and an arrow from the front, and wears for both as a player's does. A
     * blow from behind gets through.
     */
    @GameTest(template = Mechanics.ARENA, timeoutTicks = 100)
    public static void shieldBlocksWhatComesFromTheFront(GameTestHelper helper) {

        AgentMob agent = Mechanics.agent(helper, new BlockPos(4, 2, 2), 0.0F, 0.0F);
        Loadout.SWORD_AND_SHIELD.equip(agent);

        Mob front = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(4, 2, 3));
        Mob behind = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(4, 2, 1));

        int[] worn = {0};

        Mechanics.run(helper, tick -> {

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
    @GameTest(template = Mechanics.ARENA, timeoutTicks = 200)
    public static void axeKnocksTheAgentsShieldAside(GameTestHelper helper) {

        AgentMob agent = Mechanics.agent(helper, new BlockPos(4, 2, 2), 0.0F, 0.0F);
        Loadout.SWORD_AND_SHIELD.equip(agent);

        Mob vindicator = helper.spawnWithNoFreeWill(EntityType.VINDICATOR, new BlockPos(4, 2, 3));
        vindicator.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));

        int[] hitAt = {-1};

        Mechanics.run(helper, tick -> {

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
    @GameTest(template = Mechanics.ARENA, timeoutTicks = 100)
    public static void agentsAxeKnocksAShieldAside(GameTestHelper helper) {

        AgentMob attacker = Mechanics.agent(helper, new BlockPos(4, 2, 2), 0.0F, 0.0F);
        AgentMob defender = Mechanics.agent(helper, new BlockPos(4, 2, 4), 180.0F, 0.0F);

        Loadout.AXE_AND_SHIELD.equip(attacker);
        Loadout.SWORD_AND_SHIELD.equip(defender);

        Mechanics.run(helper, tick -> {

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
    @GameTest(template = Mechanics.ARENA, timeoutTicks = 100)
    public static void usingAnItemSlowsToAFifth(GameTestHelper helper) {

        AgentMob walker = Mechanics.agent(helper, new BlockPos(2, 2, 1), 0.0F, 0.0F);
        AgentMob drawer = Mechanics.agent(helper, new BlockPos(6, 2, 1), 0.0F, 0.0F);
        Loadout.BOW.equip(drawer);

        double[] from = new double[2];

        Mechanics.run(helper, tick -> {

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
    @GameTest(template = Mechanics.ARENA, timeoutTicks = 100)
    public static void noAttackingWhileAnItemIsInUse(GameTestHelper helper) {

        AgentMob agent = Mechanics.agent(helper, new BlockPos(4, 2, 2), 0.0F, 0.0F);
        Loadout.SWORD_AND_SHIELD.equip(agent);

        Mob target = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(4, 2, 4));

        Mechanics.run(helper, tick -> {

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
    @GameTest(template = Mechanics.ARENA, timeoutTicks = 100)
    public static void aSwingAtAirCostsTheCooldownAndOneAtABlockDoesNot(GameTestHelper helper) {

        // Level, with nothing in front of it: the wall ahead is five and a half blocks off, further than the four and a
        // half a block is reached at, so a swing from here meets nothing whatever.
        AgentMob agent = Mechanics.agent(helper, Mechanics.MINER, 0.0F, 0.0F);
        Loadout.SWORD.equip(agent);

        int air = 20;

        // Then the same sword on dirt at eye level, for fewer presses than the fifteen ticks it takes, so the block is
        // still standing when the cooldown is read.
        int block = air + 20;
        int presses = 10;

        Mechanics.run(helper, tick -> {

            agent.controls().attack = tick == air || tick >= block && tick < block + presses;

            if (tick == air) {

                helper.assertValueEqual(agent.getAttackStrengthScale(0.0F), 1.0F, "attack strength before the swing at air");
            }

            // The ticker restarts inside the tick and is advanced at the end of it, as vanilla advances a player's after
            // resolving its attack, so one tick of the sword's twelve and a half is back by the time this reads it.
            if (tick == air + 1) {

                helper.assertValueEqual(agent.getAttackStrengthScale(0.0F),
                        1.0F / agent.getCurrentItemAttackStrengthDelay(), "attack strength after a swing at thin air");

                helper.setBlock(Mechanics.AT_EYE_LEVEL, Blocks.DIRT);
            }

            if (tick == block) {

                helper.assertValueEqual(agent.getAttackStrengthScale(0.0F), 1.0F, "attack strength recovered before the block");
            }

            if (tick == block + presses) {

                helper.assertFalse(helper.getBlockState(Mechanics.AT_EYE_LEVEL).isAir(), "The dirt broke before the cooldown was read");
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
    @GameTest(template = Mechanics.ARENA, timeoutTicks = 140)
    public static void holdingAttackTakesLessHealthThanWaitingForTheCooldown(GameTestHelper helper) {

        AgentMob agent = Mechanics.agent(helper, new BlockPos(4, 2, 2), 0.0F, 0.0F);
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

        Mechanics.run(helper, tick -> {

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
    // A ghast's fireball
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * A swing at a ghast's fireball sends it back rather than hurting it, which is a player's own rule and not a favour to
     * the agent: {@code Player#attack} asks it before it looks at the damage at all, deflects along the swinger's look,
     * takes the projectile over, and ends the swing there.
     *
     * <p>Which is why the cooldown is read here as well. Vanilla restarts the ticker before that branch, so a deflection
     * costs a press exactly what a landed blow costs, and the swing is spent: this is the assertion that would catch a
     * deflection made free.
     *
     * <p>Where the swing used to go is the reason the test exists. {@code Fireball#hurt} answers {@code false} to
     * everything, so the press reached the damage, was refused and did nothing whatever — over a hundred recorded ghast
     * fights, 489 fireballs and not one sent back. Nothing was wrong with the aim: {@code Projectile#isPickable} is true
     * for the {@code redirectable_projectile} tag, with a whole block of pick radius besides.
     */
    @GameTest(template = Mechanics.ARENA, timeoutTicks = 100)
    public static void aSwingSendsAFireballBackAndSpendsThePress(GameTestHelper helper) {

        AgentMob agent = Mechanics.agent(helper, new BlockPos(4, 2, 1), 0.0F, 0.0F);
        Loadout.SWORD.equip(agent);

        int swing = Mechanics.SETTLE + 10;
        LargeFireball[] shot = new LargeFireball[1];

        Mechanics.run(helper, tick -> {

            agent.controls().attack = tick == swing;

            if (tick == swing) {

                // Level with the eyes, a block and a half out, drifting in slowly: near enough for the aim to find and slow
                // enough that a tick has not carried it anywhere.
                shot[0] = Mechanics.fireball(helper, null, 4.5D, 3.12D, 3.2D, 0.0D, 0.0D, -0.25D);

                helper.assertTrue(shot[0].getDeltaMovement().z < 0.0D, "The fireball is not coming at the agent");
                helper.assertValueEqual(agent.getAttackStrengthScale(0.0F), 1.0F, "attack strength before the swing");
                return false;
            }

            if (tick == swing + 1) {

                LargeFireball fireball = shot[0];

                helper.assertTrue(fireball.isAlive(), "The fireball went off rather than being sent back");
                helper.assertTrue(agent.executed().attacked, "No swing went out");
                helper.assertTrue(agent.executed().attackHit, "The swing found nothing: the fireball was not under the aim");
                helper.assertValueEqual(agent.executed().attackDamage, 0.0F, "damage dealt to a fireball");

                Vec3 sent = fireball.getDeltaMovement();
                Vec3 look = agent.getLookAngle().normalize();

                helper.assertTrue(sent.z > 0.0D, "The fireball is still coming at the agent, at " + sent);
                helper.assertTrue(sent.normalize().distanceTo(look) < 1.0E-3D,
                        "The fireball went " + sent.normalize() + " and not along the agent's look, " + look);

                helper.assertTrue(fireball.getOwner() == agent,
                        "The fireball's owner is " + fireball.getOwner() + " and not the agent that sent it back");

                // A player's deflection restarts the ticker before the branch that returns, so the press costs what a
                // landed blow costs. One tick of the sword's twelve and a half is back by the time this reads it.
                helper.assertValueEqual(agent.getAttackStrengthScale(0.0F),
                        1.0F / agent.getCurrentItemAttackStrengthDelay(), "attack strength after a deflected swing");
                return true;
            }

            return false;
        });
    }

    /**
     * A fireball sent back is a weapon and not only a dodge: it is the agent's own projectile from the deflection on, and
     * what it reaches it hurts, for the six a fireball takes on the way in and the blast behind that.
     *
     * <p>The zombie stands past the agent's own reach, so it is never what the swing was aimed at: the only thing under the
     * aim is the fireball, and the only thing that carries the blow four blocks further is the deflection.
     */
    @GameTest(template = Mechanics.ARENA, timeoutTicks = 100)
    public static void aDeflectedFireballHurtsWhatItIsSentInto(GameTestHelper helper) {

        AgentMob agent = Mechanics.agent(helper, new BlockPos(4, 2, 1), 0.0F, 0.0F);
        Loadout.SWORD.equip(agent);

        // Four blocks from the eyes, a block past the three a swing reaches, and dead in the line the deflection throws.
        Mob opponent = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(4, 2, 5));

        int swing = Mechanics.SETTLE + 10;

        Mechanics.run(helper, tick -> {

            agent.controls().attack = tick == swing;

            if (tick == swing) {

                Mechanics.fireball(helper, null, 4.5D, 3.12D, 3.2D, 0.0D, 0.0D, -0.25D);
                helper.assertValueEqual(opponent.getHealth(), opponent.getMaxHealth(), "the zombie's health before the swing");
                return false;
            }

            // Deflected on the tick after the press, then about a block a tick, so five leaves room to spare.
            if (tick == swing + 5) {

                helper.assertTrue(helper.getEntities(EntityType.FIREBALL).isEmpty(), "The fireball never went off");

                helper.assertTrue(opponent.getHealth() < opponent.getMaxHealth(),
                        "The zombie still has all " + opponent.getHealth() + " of its health: the fireball never reached it");

                helper.assertTrue(agent.isAlive(), "The agent died to the fireball it sent back");
                return true;
            }

            return false;
        });
    }

    /**
     * And what it does <b>not</b> buy, which is the thing a reader of the rule above would assume it did: a ghast is not
     * killed by its own fireball coming back, because the agent is not a {@link net.minecraft.world.entity.player.Player}.
     *
     * <p>A ghast is fire immune, so a fireball's damage is refused outright, and vanilla's one exception is written as a
     * type test: {@code Ghast#isInvulnerableTo} and {@code Ghast#hurt} both go through {@code isReflectedFireball}, which
     * asks whether the fireball's owner {@code instanceof Player} and nothing else. The deflection hands the fireball to the
     * agent, which is a {@code PathfinderMob}, so the thousand damage a player's reflection deals is never reached and the
     * blast cannot make it up: a power-one explosion reaches two blocks and a ghast is four wide, so its middle is always
     * further off than that from wherever the fireball met its face.
     *
     * <p>This is pinned rather than fixed. Making it work would take a mixin on a vanilla mob's invulnerability, which is
     * the owner's call and not a test's; what a test can do is make sure nobody plans a ghast matchup around a kill that
     * does not happen. See findings.md. What the deflection is worth against a ghast is everything above: the six and the
     * blast that were coming at the agent go somewhere else instead.
     */
    @GameTest(template = Mechanics.ARENA, timeoutTicks = 100)
    public static void aGhastIsSparedItsOwnFireballUnlessAPlayerSentItBack(GameTestHelper helper) {

        AgentMob agent = Mechanics.agent(helper, new BlockPos(4, 2, 1), 0.0F, 0.0F);
        Loadout.SWORD.equip(agent);

        // Four blocks wide and four high, so the far half of the box is ghast: its near face stands four blocks out, half a
        // block past the fireball, and one tick of flight is enough to reach it.
        Ghast ghast = helper.spawnWithNoFreeWill(EntityType.GHAST, new Vec3(4.5D, 2.0D, 6.0D));

        int swing = Mechanics.SETTLE + 10;

        Mechanics.run(helper, tick -> {

            agent.controls().attack = tick == swing;

            if (tick == swing) {

                Mechanics.fireball(helper, ghast, 4.5D, 3.12D, 3.2D, 0.0D, 0.0D, -0.25D);
                return false;
            }

            if (tick == swing + 5) {

                // The fireball did go back and did go off on the ghast: this is not a test that nothing happened.
                helper.assertTrue(helper.getEntities(EntityType.FIREBALL).isEmpty(),
                        "The fireball never went off, so nothing here says anything about the ghast");

                helper.assertValueEqual(ghast.getHealth(), ghast.getMaxHealth(),
                        "the ghast's health after its own fireball came back");

                helper.assertTrue(agent.isAlive(), "The agent died to the fireball it sent back");
                return true;
            }

            return false;
        });
    }

    /**
     * Nothing else moves. The tag holds the fireball and the two wind charges and nothing besides, so a swing at an arrow
     * does exactly what it always did: the arrow is not pickable, the aim passes straight through it, and the press is a
     * swing at thin air that costs the whole cooldown and touches nothing.
     */
    @GameTest(template = Mechanics.ARENA, timeoutTicks = 100)
    public static void aSwingAtAnArrowDeflectsNothing(GameTestHelper helper) {

        AgentMob agent = Mechanics.agent(helper, new BlockPos(4, 2, 1), 0.0F, 0.0F);
        Mob shooter = helper.spawnWithNoFreeWill(EntityType.SKELETON, new BlockPos(1, 2, 7));
        Loadout.SWORD.equip(agent);

        int swing = Mechanics.SETTLE + 10;
        Arrow[] shot = new Arrow[1];

        Mechanics.run(helper, tick -> {

            agent.controls().attack = tick == swing;

            if (tick == swing) {

                // Where the fireball stood, on the same slow drift at the agent.
                shot[0] = Mechanics.shoot(helper, shooter, 4.5D, 3.4D, 3.2D, 0.0D, 0.0D, -0.25D);
                return false;
            }

            if (tick == swing + 1) {

                Arrow arrow = shot[0];

                helper.assertTrue(arrow.getDeltaMovement().z < 0.0D,
                        "The arrow was sent back, at " + arrow.getDeltaMovement());

                helper.assertTrue(arrow.getOwner() == shooter,
                        "The arrow's owner became " + arrow.getOwner() + " rather than staying the skeleton's");

                helper.assertTrue(agent.executed().attacked, "No swing went out");
                helper.assertFalse(agent.executed().attackHit, "The swing landed on the arrow");

                // A swing at thin air, which is what a swing at an arrow has always been, and it costs the whole cooldown.
                helper.assertValueEqual(agent.getAttackStrengthScale(0.0F),
                        1.0F / agent.getCurrentItemAttackStrengthDelay(), "attack strength after a swing at an arrow");
                return true;
            }

            return false;
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // What it is paid
    // ---------------------------------------------------------------------------------------------------------------

    /** A sword blow on the opponent is paid once, for the health it took; a blow on anything else is not paid at all. */
    @GameTest(template = Mechanics.ARENA, timeoutTicks = 100)
    public static void swordBlowIsPaidOnceAndOnlyOnTheOpponent(GameTestHelper helper) {

        AgentMob agent = Mechanics.agent(helper, new BlockPos(4, 2, 2), 0.0F, 0.0F);

        // Far enough apart that a sweep at one never reaches the other.
        Mob opponent = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(4, 2, 4));
        Mob bystander = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(2, 2, 2));

        Episode episode = new Episode(Mechanics.FIGHT_TICKS, null, opponent);
        agent.startEpisode(episode);
        Loadout.SWORD.equip(agent);

        Mechanics.run(helper, tick -> {

            agent.controls().attack = tick == 20 || tick == 45;

            if (tick == 21) {

                helper.assertTrue(opponent.getHealth() < opponent.getMaxHealth(), "The blow missed the opponent");
                Mechanics.paidExactly(helper, episode, opponent);

                // Round to the bystander, to the west.
                agent.setYRot(90.0F);
                agent.setYHeadRot(90.0F);
            }

            if (tick == 46) {

                helper.assertTrue(bystander.getHealth() < bystander.getMaxHealth(), "The blow missed the bystander");
                Mechanics.paidExactly(helper, episode, opponent);
                return true;
            }

            return false;
        });
    }
}
