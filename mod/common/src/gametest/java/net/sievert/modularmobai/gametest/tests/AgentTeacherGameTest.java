package net.sievert.modularmobai.gametest.tests;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

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
import net.sievert.modularmobai.Constants;
import net.sievert.modularmobai.arena.Episode;
import net.sievert.modularmobai.arena.Loadout;
import net.sievert.modularmobai.brain.Brains;
import net.sievert.modularmobai.brain.schema.ObservationSchema;
import net.sievert.modularmobai.entity.agent.AgentMob;
import net.sievert.modularmobai.gametest.GameTestGroup;
import net.sievert.modularmobai.gametest.league.Loadouts;

/**
 * The scripted fighter's own rules, which is the one fighter in the build that is hand written: getting itself out of
 * something that hurts, walking away from a lit creeper, starting no draw it cannot finish, and what it does about several
 * bodies coming at it at once.
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


    // ---------------------------------------------------------------------------------------------------------------
    // What the teacher does about a pack
    // ---------------------------------------------------------------------------------------------------------------

    /** How long a pack fight is watched: a sword's cooldown is twelve and a half ticks, so this is a dozen whole cycles. */
    private static final int PACK_TICKS = 160;

    /** Half the cone the agent perceives through, which is the whole of what "in front of it" means. */
    private static final double HALF_CONE = ObservationSchema.VIEW_CONE_DEGREES / 2.0D;

    /** Surrounded: two bodies this close at once, which is inside the reach of anything man sized. */
    private static final double ON_TOP = 2.0D;

    /**
     * Either side of {@code ScriptedBrain#PACK_SPRINT_CLEAR}, the four and a half blocks a second body has to be past for
     * the step into a sprint blow to be worth taking. Half a block is left out of each, which is more than anything in the
     * league covers in the one tick between the observation the rule read and the blow that followed it.
     */
    private static final double SECOND_IN_REACH = 4.0D;
    private static final double SECOND_CLEAR = 5.0D;

    /**
     * Three bodies in the fight at once, spread over a right angle, and the teacher gives ground and keeps all three where it
     * can see them instead of standing among them and facing one.
     *
     * <p>They are held where they are put, and their target is put back on the agent every tick. That is the point of the
     * test rather than a convenience: the only thing that can have moved the teacher is <b>the three of them being in the
     * fight</b>, since nothing is coming at it, nothing swings at it and nothing ever hits it. Three zombies walking in would
     * have shown the same behaviour and proved nothing about which rule produced it.
     *
     * <p>Four things are asked, and they are what the pack rule is for:
     *
     * <ul>
     *   <li>it <b>keeps them in front</b>: the aim goes to the middle of the three rather than onto one of them, so all of
     *       them sit inside the hundred degree cone the agent perceives through. A body outside that cone is a body that is
     *       not in the observation at all, so this is not a nicety;</li>
     *   <li>it <b>keeps its distance</b>: the nearest of them stands further off on an average tick than the band a sword
     *       wants, because ground is given while the swing cools instead of the fighter holding the band and waiting in it;</li>
     *   <li>it is <b>not surrounded</b>, two of them inside two blocks on few ticks, which is two of them able to strike;</li>
     *   <li>and it <b>still lands blows</b>, which is the half that stops all of this being satisfied by walking backwards.
     *       Giving ground is a cycle and not a retreat: the swing comes back, the fighter steps in and takes it. A version
     *       that only backed away was written, measured and thrown out; see {@code ScriptedBrain#PACK_STANDOFF}.</li>
     * </ul>
     *
     * <p>Every threshold here is measured off this same fight with the rule turned off, which the line the run prints says
     * outright. With it off the teacher has <b>1.63</b> of the three inside its view cone on an average tick and holds the
     * nearest <b>3.13</b> blocks off; with it on those are <b>2.18</b> and <b>3.49</b>. The thresholds sit between the two
     * rather than under the second, so a tuning that gives a little of it back does not fail this.
     */
    @GameTest(template = Mechanics.ARENA, timeoutTicks = 400)
    public static void theTeacherGivesGroundToAPackInsteadOfStandingInIt(GameTestHelper helper) {

        fightAPack(helper, true, watched -> {

            helper.assertFalse(watched.died, "The teacher died to three zombies, having landed " + watched.landed);

            helper.assertTrue(watched.inFront() > 1.9D,
                    "Only " + round(watched.inFront()) + " of the three were inside the agent's own view cone on an average "
                            + "tick, against 1.63 with the rule off and 2.18 with it on: it faced one of them and not the three");

            helper.assertTrue(watched.standoff() > 3.30D,
                    "The nearest of the three stood " + round(watched.standoff()) + " blocks off on an average tick, against "
                            + "3.13 with the rule off and 3.49 with it on: it held the band rather than giving ground");

            helper.assertTrue(watched.share(watched.twoOnTop) < 0.25D,
                    "Two of the three were within " + ON_TOP + " blocks on " + percent(watched.share(watched.twoOnTop))
                            + " of the ticks: the teacher was surrounded");

            helper.assertTrue(watched.landed > 0,
                    "The teacher landed no blow on the three in " + PACK_TICKS + " ticks; it only backed away");
        });
    }

    /**
     * And the ground it gives costs it next to nothing against a pack that is actually coming. Three zombies walk in, and the
     * teacher goes on swinging and going on landing: every claim in the test above can be had by a fighter that has simply
     * stopped fighting, and this is what says it has not.
     *
     * <p>It is a floor and not a match, and the exact numbers are worth writing down rather than rounding off. With the rule
     * turned off this fight is <b>12 swings and 12 landed</b>; with it on it is <b>9 and 9</b>. Three blows of a dozen is what
     * the standoff costs here, and what it buys is in docs/findings.md: over 1,134 pack fights on one bench, 57.5% won and
     * 35.5% lost against 61.3% and 30.2%. A version of the rule that gave ground for as long as two bodies were in the fight
     * landed next to nothing and ran the clock out instead, which is the collapse this floor is set to catch.
     */
    @GameTest(template = Mechanics.ARENA, timeoutTicks = 400)
    public static void aPackCostsTheTeacherNoBlows(GameTestHelper helper) {

        fightAPack(helper, false, watched -> {

            helper.assertFalse(watched.died, "The teacher died to three zombies, having landed " + watched.landed);

            helper.assertTrue(watched.swings >= 7,
                    "The teacher swung " + watched.swings + " times at three zombies walking in, well short of the 12 it swings "
                            + "with the pack rule turned off and the 9 it swings with it on");

            helper.assertTrue(watched.landed >= 7,
                    "The teacher landed " + watched.landed + " blows on three zombies walking in, well short of the 12 it lands "
                            + "with the pack rule turned off and the 9 it lands with it on");
        });
    }

    /**
     * And against a pack it can outwalk it holds the edge of its own reach: the blows land out where a zombie cannot answer
     * them, and next to nothing comes back.
     *
     * <p>This is the half of the pack rule that the standoff above cannot say. A fighter that keeps the nearest of three at
     * a good average distance can still be doing it by walking in, trading at arm's length and walking out again, and the
     * average would read the same. Where a blow <b>lands</b> cannot be averaged away: a sword reaches three blocks from the
     * eyes and anything man sized reaches a block and a half head on, two across a diagonal, so a blow landed at two and a
     * half blocks or more is one thrown from ground the zombie could not have struck from.
     *
     * <p>Measured on the pack harness over 900 fights, five packs and ten loadouts
     * ({@code scripts	est.ps1 -Pack}, see docs/testing.md): against three hard zombies the teacher's blows landed a mean
     * <b>2.86</b> blocks off before the kite and <b>2.98</b> after, and it took <b>0.7</b> blows a fight and then
     * <b>0.9</b>. In this arena, three plain zombies walking in, the same two numbers are 2.80 and 0. The thresholds are
     * set under both, so a tuning that gives a little of it back does not fail this; what they catch is a fighter that has
     * gone back to standing in the middle of a pack, where a blow lands at two blocks and several come back.
     */
    @GameTest(template = Mechanics.ARENA, timeoutTicks = 400)
    public static void theTeacherStrikesAPackFromTheEdgeOfItsReach(GameTestHelper helper) {

        fightAPack(helper, false, watched -> {

            helper.assertFalse(watched.died, "The teacher died to three zombies, having landed " + watched.landed);

            helper.assertTrue(watched.landed >= 5,
                    "The teacher landed only " + watched.landed + " blows on three zombies walking in, too few for where "
                            + "they landed to mean anything");

            helper.assertTrue(watched.blowAt() >= 2.4D,
                    "The teacher's blows landed a mean " + round(watched.blowAt()) + " blocks off, inside the two and a "
                            + "half a zombie's own reach ends at: it is trading in the band rather than holding the edge "
                            + "of its reach");

            helper.assertTrue(watched.taken < 3,
                    "The teacher was hit " + watched.taken + " times by three zombies it walks faster than, against the "
                            + "none it takes when the kite is working");
        });
    }

    /**
     * A blow thrown at a pack carries the knockback when the second nearest body is far enough off that the step into the
     * blow is not a step into a second set of hands, and does not when it is near.
     *
     * <p>A sprint is forward only, so a sprint blow is thrown from about a block nearer than the fighter stands. That is
     * what it costs and it is the whole of the rule: past {@code ScriptedBrain#PACK_SPRINT_CLEAR} the spot the blow is
     * thrown from is outside the second body's own reach, and inside it the extra point of knockback is bought by walking
     * into something that swings. The two tests are one rule read both ways round, which is the only way to tell the rule
     * from a fighter that simply always sprints.
     *
     * <p>Vindicators rather than zombies, and held where they are put: this is about which shape the blow is thrown in, and
     * a pack that walks would move the second one across the threshold mid fight and make the answer a matter of timing.
     */
    @GameTest(template = Mechanics.ARENA, timeoutTicks = 400)
    public static void aClearSecondBodyBuysTheKnockbackBlowInAPack(GameTestHelper helper) {

        // Three blocks due north, and the second seven blocks off to the east: well past the four and a half the rule asks
        // for, so every blow thrown at the first should carry the knockback.
        fightAPack(helper, true, EntityType.VINDICATOR,
                new BlockPos[] {new BlockPos(4, 2, 7), new BlockPos(11, 2, 4)}, watched -> {

                    helper.assertTrue(watched.landed >= 3,
                            "The teacher landed only " + watched.landed + " blows on the near vindicator, too few to say "
                                    + "anything about how they were thrown");

                    helper.assertTrue(watched.knockedWithSecondClear >= 3,
                            "Only " + watched.knockedWithSecondClear + " of the teacher's " + watched.landed + " blows "
                                    + "carried the sprint knockback with the second body five blocks or more clear: the "
                                    + "opener is not being thrown");
                });
    }

    @GameTest(template = Mechanics.ARENA, timeoutTicks = 400)
    public static void aSecondBodyInReachTakesTheKnockbackBlowAway(GameTestHelper helper) {

        // The same near body, with the second one three blocks the other side of the agent: inside the four and a half, so
        // the step forward would be a step into its reach and the knockback is given up for it.
        fightAPack(helper, true, EntityType.VINDICATOR,
                new BlockPos[] {new BlockPos(4, 2, 7), new BlockPos(4, 2, 1)}, watched -> {

                    helper.assertTrue(watched.landed >= 3,
                            "The teacher landed only " + watched.landed + " blows on the near vindicator, too few to say "
                                    + "anything about how they were thrown");

                    helper.assertTrue(watched.knockedWithSecondInReach == 0,
                            "The teacher threw " + watched.knockedWithSecondInReach + " of its " + watched.landed
                                    + " blows sprinting while a second body stood inside four blocks of it, which is a "
                                    + "step into that body's reach");
                });
    }

    /**
     * One fight against three zombies, all of them in the fight with the agent, watched tick by tick and handed to the verdict
     * when it is over. They stand over a right angle from the agent and at different distances, so that where the pack lies as
     * one direction is nowhere near where the nearest of it lies: that difference is the whole of what the rule is, and an
     * arrangement that put them in a line would have had both answers agree.
     *
     * @param held whether they are held where they are put, which is what makes the teacher's own decision the only thing in
     *             the measurement, or left to walk in, which is what says the decision costs no blows
     */
    private static void fightAPack(GameTestHelper helper, boolean held, Consumer<Watched> verdict) {

        // Due north of the agent, off to the north east, and due east: three blocks, three and a sixth, and four and a
        // quarter. The nearest is the one straight ahead, so a fighter reading the nearest slot alone faces north.
        fightAPack(helper, held, EntityType.ZOMBIE,
                new BlockPos[] {new BlockPos(4, 2, 7), new BlockPos(7, 2, 5), new BlockPos(7, 2, 7)}, verdict);
    }

    /**
     * The same fight against whatever is named, standing wherever it is put. The three zombies above are one arrangement of
     * it; a pair of vindicators with one of them well clear is another, and that one is about which shape a blow is thrown
     * in rather than about the footwork.
     */
    private static void fightAPack(GameTestHelper helper, boolean held, EntityType<? extends Mob> type, BlockPos[] where,
            Consumer<Watched> verdict) {

        AgentMob agent = Mechanics.agent(helper, new BlockPos(4, 2, 4), 0.0F, 0.0F);

        List<Mob> pack = new ArrayList<>(where.length);

        for (BlockPos feet : where) {

            pack.add(held ? helper.spawnWithNoFreeWill(type, feet) : helper.spawn(type, feet));
        }

        agent.startEpisode(new Episode(Mechanics.FIGHT_TICKS, Mechanics.bounds(helper), List.copyOf(pack)));
        Loadout.SWORD.equip(agent);
        agent.brain().use(Brains.scripted());

        Watched watched = new Watched();

        Mechanics.run(helper, tick -> {

            for (Mob zombie : pack) {

                // Put back every tick: a zombie that has let go of its target is not in the fight, and how many are in the
                // fight is exactly what the rule turns on.
                if (zombie.isAlive()) {

                    zombie.setTarget(agent);
                }
            }

            watched.tick(agent, pack);

            if (tick == PACK_TICKS || !agent.isAlive()) {

                // Said out loud, because every threshold above is measured off this line with the rule on and off, and a
                // threshold whose measurement cannot be repeated is a threshold nobody can move.
                Constants.LOG.info(String.format(Locale.ROOT,
                        "A %s pack of %d: %d ticks, %.2f of them in front on an average tick, two inside %.1f blocks on "
                                + "%s, the nearest %.2f blocks off, from the middle of them %.2f to %.2f, %d swings, "
                                + "%d landed at %.2f blocks, %d of them knocking back, %d blows taken",
                        held ? "held" : "walking", pack.size(), watched.ticks, watched.inFront(), ON_TOP,
                        percent(watched.share(watched.twoOnTop)), watched.standoff(), watched.opened, watched.ended,
                        watched.swings, watched.landed, watched.blowAt(), watched.knocked, watched.taken));

                verdict.accept(watched);
                return true;
            }

            return false;
        });
    }

    private static String percent(double share) {

        return Math.round(share * 100.0D) + "%";
    }

    private static String round(double blocks) {

        return String.format(Locale.ROOT, "%.2f", blocks);
    }

    /** What one pack fight came to: where the pack was on each tick, and what the teacher did about it. */
    private static final class Watched {

        private int ticks;

        /** How many of the pack were inside the agent's own view cone, added up over the ticks. */
        private int inFrontSum;

        /** Ticks with two of them inside {@link #ON_TOP} blocks at once, which is two of them able to strike. */
        private int twoOnTop;

        /** The distances to the nearest of the pack, added up, which is the standoff giving ground buys. */
        private double nearestSum;

        /** How far the agent was from the middle of the pack on the first tick and on the last. */
        private double opened;
        private double ended;

        private int swings;
        private int landed;

        /** How far off each blow landed, added up, which is the whole of what holding the edge of the reach means. */
        private double landedAtSum;

        /** Blows that carried the sprint knockback, which in a pack is the opener a clear second body buys. */
        private int knocked;

        /**
         * The same blows split by where the second nearest body actually stood when each landed, which is what the rule
         * turns on and what the arrangement of a test cannot promise on its own: both bodies are held where they are put,
         * but the agent walks, so which of them is second and how far off it is are things that change during the fight.
         *
         * <p>The band between the two leaves a blow's worth of movement out of both: the brain decides on the tick before
         * the blow lands and nothing in the league covers half a block in a tick.
         */
        private int knockedWithSecondInReach;
        private int knockedWithSecondClear;

        /** Blows taken, seen as health that went down, and the health the tick before, which is how they are seen. */
        private int taken;
        private float health;

        /** Whether the pack put the agent down, which the arrangements that claim survival ask about themselves. */
        private boolean died;

        private void tick(AgentMob agent, List<Mob> pack) {

            this.died |= !agent.isAlive();

            int alive = 0;
            int inFront = 0;
            int onTop = 0;
            double nearest = Double.MAX_VALUE;
            double middleX = 0.0D;
            double middleZ = 0.0D;

            for (Mob zombie : pack) {

                if (!zombie.isAlive()) {

                    continue;
                }

                double away = agent.distanceTo(zombie);

                alive++;
                nearest = Math.min(nearest, away);
                inFront += Mechanics.aimError(agent, zombie) <= HALF_CONE ? 1 : 0;
                onTop += away <= ON_TOP ? 1 : 0;
                middleX += zombie.getX();
                middleZ += zombie.getZ();
            }

            this.swings += agent.executed().attacked ? 1 : 0;
            this.landed += agent.executed().attackHit ? 1 : 0;
            this.knocked += agent.executed().attackSprintKnockback ? 1 : 0;

            if (agent.executed().attackSprintKnockback) {

                double second = secondNearest(agent, pack);

                this.knockedWithSecondInReach += second <= SECOND_IN_REACH ? 1 : 0;
                this.knockedWithSecondClear += second >= SECOND_CLEAR ? 1 : 0;
            }

            if (agent.executed().attackHit && agent.getLastHurtMob() != null) {

                this.landedAtSum += agent.distanceTo(agent.getLastHurtMob());
            }

            this.taken += this.health > 0.0F && agent.getHealth() < this.health ? 1 : 0;
            this.health = agent.getHealth();

            if (alive == 0) {

                return;
            }

            this.ticks++;
            this.inFrontSum += inFront;
            this.twoOnTop += onTop >= 2 ? 1 : 0;
            this.nearestSum += nearest;

            this.ended = Math.hypot(middleX / alive - agent.getX(), middleZ / alive - agent.getZ());
            this.opened = this.ticks == 1 ? this.ended : this.opened;
        }

        /** How many of the pack were in front of the agent on an average tick. */
        private double inFront() {

            return this.inFrontSum / (double) Math.max(1, this.ticks);
        }

        private double share(int of) {

            return of / (double) Math.max(1, this.ticks);
        }

        /** How far off the second nearest body still standing is, or a number past anything the rule looks at. */
        private static double secondNearest(AgentMob agent, List<Mob> pack) {

            double nearest = Double.MAX_VALUE;
            double second = Double.MAX_VALUE;

            for (Mob mob : pack) {

                if (!mob.isAlive()) {

                    continue;
                }

                double away = agent.distanceTo(mob);

                if (away < nearest) {

                    second = nearest;
                    nearest = away;
                }

                else if (away < second) {

                    second = away;
                }
            }

            return second;
        }

        /** How far the nearest of the pack was on an average tick, which is the whole of what a standoff is. */
        private double standoff() {

            return this.nearestSum / Math.max(1, this.ticks);
        }

        /** How far off its blows landed on average: the edge of its own reach, or the middle of the pack. */
        private double blowAt() {

            return this.landedAtSum / Math.max(1, this.landed);
        }
    }
}
