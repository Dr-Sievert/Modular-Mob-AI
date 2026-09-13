package net.sievert.modularmobai.gametest.tests;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.sievert.modularmobai.arena.Episode;
import net.sievert.modularmobai.arena.Loadout;
import net.sievert.modularmobai.brain.Brains;
import net.sievert.modularmobai.entity.agent.AgentMob;
import net.sievert.modularmobai.gametest.GameTestGroup;
import net.sievert.modularmobai.gametest.league.Loadouts;

/**
 * The scripted fighter's own rules, which is the one fighter in the build that is hand written: getting itself out of
 * something that hurts, walking away from a lit creeper, and starting no draw it cannot finish.
 *
 * <p>It is worth pinning because it is the anchor. Every rating in the league is measured against it at 1500, and every
 * network in the repository was copied from it, so a rule of its own that quietly stopped working would move the scale
 * everything else is read on rather than failing anything.
 *
 * <p>See {@link Mechanics} for the arena, the brain and the rest the suite shares.
 */
@GameTestGroup
public class AgentTeacherGameTest {

    /** How long a creeper's fuse burns before it goes off, so a test can watch the teacher react and stop short of it. */
    private static final int FUSE_TICKS = 30;

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
    @GameTest(template = Mechanics.ARENA, timeoutTicks = 220)
    public static void theTeacherGetsOutOfPowderSnow(GameTestHelper helper) {

        stuckInPowderSnow(helper, 0);
    }

    @GameTest(template = Mechanics.ARENA, timeoutTicks = 220)
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

        AgentMob agent = Mechanics.agent(helper, feet, 0.0F, 0.0F);
        Mob opponent = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(4, 2, 6));

        agent.startEpisode(new Episode(Mechanics.FIGHT_TICKS, Mechanics.bounds(helper), opponent));
        Loadout.SWORD.equip(agent);

        // The scripted fighter, not the test's own presses: what is being checked is what it decides to do about the snow.
        agent.brain().use(Brains.scripted());

        Mechanics.run(helper, tick -> {

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
     * The teacher starts no draw it cannot finish. A draw is twenty ticks at a fifth of walking pace and there is no way
     * out of one, so a draw begun at something that arrives first is twenty ticks spent shooting at a thing that is already
     * swinging. A vindicator six blocks off covers that in twenty five ticks, which is why
     * nothing is drawn at it here — and it is what the distance alone could not say, since six blocks is past the range
     * a fighter with something to shoot used to shoot from.
     *
     * <p>It swings instead, which is the other half of the check: a rule that simply stopped the teacher doing anything
     * would pass the first assertion and fail this one.
     */
    @GameTest(template = Mechanics.ARENA, timeoutTicks = 160)
    public static void theTeacherStartsNoDrawItCannotFinish(GameTestHelper helper) {

        AgentMob agent = Mechanics.agent(helper, new BlockPos(4, 2, 1), 0.0F, 0.0F);
        Mob opponent = helper.spawn(EntityType.VINDICATOR, new BlockPos(4, 2, 7));

        // spawn() skips finalizeSpawn, and a vindicator without the axe it is supposed to carry is not the mob whose
        // speed is being reasoned about: an empty handed one reads as something that might be a creeper.
        opponent.finalizeSpawn(helper.getLevel(),
                helper.getLevel().getCurrentDifficultyAt(helper.absolutePos(new BlockPos(4, 2, 7))),
                MobSpawnType.EVENT, null);

        opponent.setTarget(agent);

        agent.startEpisode(new Episode(Mechanics.FIGHT_TICKS, Mechanics.bounds(helper), opponent));
        Loadouts.SWORD_AND_BOW.equip(agent);
        agent.brain().use(Brains.scripted());

        int[] draws = {0};
        int[] shots = {0};
        int[] swings = {0};
        boolean[] using = {false};

        Mechanics.run(helper, tick -> {

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
    @GameTest(template = Mechanics.ARENA, timeoutTicks = 160)
    public static void theTeacherDrawsAtWhatShootsBack(GameTestHelper helper) {

        AgentMob agent = Mechanics.agent(helper, new BlockPos(4, 2, 1), 0.0F, 0.0F);
        Mob opponent = helper.spawnWithNoFreeWill(EntityType.SKELETON, new BlockPos(4, 2, 7));

        opponent.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));

        agent.startEpisode(new Episode(Mechanics.FIGHT_TICKS, Mechanics.bounds(helper), opponent));
        Loadouts.SWORD_AND_BOW.equip(agent);
        agent.brain().use(Brains.scripted());

        int[] shots = {0};

        Mechanics.run(helper, tick -> {

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

    // ---------------------------------------------------------------------------------------------------------------
    // What the teacher does about a lit creeper
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * The teacher backs off a lit creeper on the fuse field alone. The creeper stands five blocks off, which is past the
     * three the old guess waited for, and it never moves or swings: so the only thing in the observation that can have sent
     * the teacher backwards is {@code ENEMY_EXPLODES} and {@code ENEMY_FUSE}, which it never used to read at all.
     *
     * <p>Five blocks is also where a fighter with a sword would otherwise close in — the band it wants is about three — so
     * the assertion cuts both ways: walking away is a decision, and the distance the teacher ends at says which way it went.
     */
    @GameTest(template = Mechanics.ARENA, timeoutTicks = 100)
    public static void theTeacherBacksOffALitCreeperOnTheFuseAlone(GameTestHelper helper) {

        AgentMob agent = Mechanics.agent(helper, new BlockPos(4, 2, 2), 0.0F, 0.0F);
        Creeper creeper = helper.spawnWithNoFreeWill(EntityType.CREEPER, new BlockPos(4, 2, 7));

        agent.startEpisode(new Episode(Mechanics.FIGHT_TICKS, Mechanics.bounds(helper), creeper));
        Loadout.SWORD.equip(agent);
        agent.brain().use(Brains.scripted());

        int light = 2;
        double[] opened = {0.0D};
        double[] nearest = {Double.MAX_VALUE};

        Mechanics.run(helper, tick -> {

            if (tick == light) {

                opened[0] = agent.distanceTo(creeper);

                helper.assertTrue(opened[0] > 3.05D,
                        "The creeper stands " + opened[0] + " blocks off, inside the range the old guess fired at");

                creeper.ignite();
                return false;
            }

            if (tick > light) {

                nearest[0] = Math.min(nearest[0], agent.distanceTo(creeper));
            }

            // Well short of the fuse, since a creeper's blast reaches six blocks and the point is what the teacher does
            // about it rather than what it survives.
            if (tick == light + FUSE_TICKS - 8) {

                double ended = agent.distanceTo(creeper);

                helper.assertTrue(ended > opened[0] + 0.8D,
                        "The teacher ended " + ended + " blocks from a lit creeper it started " + opened[0] + " from");

                helper.assertTrue(nearest[0] > opened[0] - 0.2D,
                        "The teacher closed to " + nearest[0] + " blocks of a lit creeper on its way");

                helper.assertTrue(creeper.isAlive() && agent.isAlive(), "The creeper went off before the check");
                return true;
            }

            return false;
        });
    }

    /**
     * With two creepers it walks away from the <b>lit</b> one, and not from the one it is fighting. The unlit creeper is
     * nearer, so it is the target and everything the old guess looked at was about it; the lit one is further off and on the
     * opposite side, so the two answers point opposite ways along one axis and where the teacher ends up says which it took.
     *
     * <p>This is the fight the old guess lost outright: it was evaluated on the single target slot, so a second creeper was
     * never reasoned about however close it came or however far along its fuse was. Read on the target, the old rule fired
     * on the unlit creeper two blocks away and sent the teacher <em>into</em> the lit one.
     */
    @GameTest(template = Mechanics.ARENA, timeoutTicks = 100)
    public static void withTwoCreepersTheTeacherFleesTheLitOne(GameTestHelper helper) {

        AgentMob agent = Mechanics.agent(helper, new BlockPos(4, 2, 4), 0.0F, 0.0F);

        // The nearer of the two, so it is what the fighter takes for its target, and it is left unlit. The lit one is due
        // east of the agent and the target due west, so walking away from either is a step along x and the two disagree.
        Creeper target = helper.spawnWithNoFreeWill(EntityType.CREEPER, new BlockPos(2, 2, 4));
        Creeper lit = helper.spawnWithNoFreeWill(EntityType.CREEPER, new BlockPos(7, 2, 4));

        agent.startEpisode(new Episode(Mechanics.FIGHT_TICKS, Mechanics.bounds(helper), target));
        Loadout.SWORD.equip(agent);
        agent.brain().use(Brains.scripted());

        int light = 2;
        double[] opened = new double[3];

        Mechanics.run(helper, tick -> {

            if (tick == light) {

                opened[0] = agent.distanceTo(target);
                opened[1] = agent.distanceTo(lit);
                opened[2] = agent.getX();

                helper.assertTrue(opened[0] < opened[1],
                        "The unlit creeper is not the nearer of the two: " + opened[0] + " against " + opened[1]);

                lit.ignite();
                return false;
            }

            if (tick == light + FUSE_TICKS - 8) {

                double fromLit = agent.distanceTo(lit);

                helper.assertTrue(fromLit > opened[1] + 0.5D,
                        "The teacher ended " + fromLit + " from the lit creeper, having started " + opened[1] + " off");

                // West, which is away from the lit creeper and towards the target. Fleeing the target instead would have
                // been the same step east, so this is the one number the two answers cannot share.
                helper.assertTrue(agent.getX() < opened[2] - 0.5D,
                        "The teacher went to x " + agent.getX() + " from " + opened[2] + ", which is not away from the lit one");

                helper.assertTrue(lit.isAlive() && agent.isAlive(), "The creeper went off before the check");
                return true;
            }

            return false;
        });
    }
}
