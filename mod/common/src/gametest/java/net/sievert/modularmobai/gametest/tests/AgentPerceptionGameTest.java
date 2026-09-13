package net.sievert.modularmobai.gametest.tests;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.sievert.modularmobai.arena.Episode;
import net.sievert.modularmobai.arena.FightFacts;
import net.sievert.modularmobai.arena.Loadout;
import net.sievert.modularmobai.brain.schema.AgentObservation;
import net.sievert.modularmobai.brain.schema.ObservationSchema;
import net.sievert.modularmobai.entity.agent.AgentMob;
import net.sievert.modularmobai.gametest.GameTestGroup;
import net.sievert.modularmobai.gametest.league.Loadouts;

/**
 * What the agent sees of all of it: how far its own use has charged, which of the arrows in the air take an enemy slot, the
 * clock and the arrows left, and what a slot says about the mob in it.
 *
 * <p>Four of these readings are held against the same numbers the critic's privileged facts carry, field by field
 * ({@code FightFacts}). They are worked out from different calls on purpose, so the test is that the two agree where they
 * are supposed to and differ only where that is deliberate.
 *
 * <p>See {@link Mechanics} for the arena, the brain and the rest the suite shares.
 */
@GameTestGroup
public class AgentPerceptionGameTest {

    /** Where the echo carries how far a use has charged; see AgentObservation#writeEcho. */
    private static final int ECHO_USE_PROGRESS = 19;

    // ---------------------------------------------------------------------------------------------------------------
    // What the agent sees of its own use, and of what is shot at it
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * The charge of whatever is in use, as the echo carries it. It is the item's own reckoning and not a count of ticks: a
     * bow reads the power its arrow would leave at, which is not linear in the draw, and a crossbow the fraction of its
     * wind that is in. Both reach one at the moment letting go is worth it, which is what lets a fighter work either
     * without knowing which it holds, and both read nothing with the hands free.
     */
    @GameTest(template = Mechanics.ARENA, timeoutTicks = 160)
    public static void useProgressIsTheItemsOwnCharge(GameTestHelper helper) {

        AgentMob agent = Mechanics.agent(helper, new BlockPos(4, 2, 2), 0.0F, 0.0F);
        Loadout.BOW.equip(agent);

        // A draw starts on the tick after the press, so a step's number is one more than the ticks drawn by then.
        int crossbowFrom = 40;

        Mechanics.run(helper, tick -> {

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
    @GameTest(template = Mechanics.ARENA, timeoutTicks = 120)
    public static void onlyShotsComingAtTheAgentTakeASlot(GameTestHelper helper) {

        AgentMob agent = Mechanics.agent(helper, new BlockPos(4, 2, 2), 0.0F, 0.0F);
        Mob shooter = helper.spawnWithNoFreeWill(EntityType.SKELETON, new BlockPos(4, 2, 6));

        // Bounded, so the arenas either side of this one are not in the agent's view; see AgentVindicatorGameTest.
        agent.startEpisode(new Episode(Mechanics.FIGHT_TICKS, Mechanics.bounds(helper), shooter));
        Loadout.BOW.equip(agent);

        Arrow[] arrows = new Arrow[4];

        Mechanics.run(helper, tick -> {

            if (tick == 2) {

                helper.assertTrue(agent.brain().enemySlots().occupant(0) == shooter, "The skeleton never took a slot");

                // Straight at the agent, four blocks off, and slow enough to still be in the air a moment later.
                arrows[0] = Mechanics.shoot(helper, shooter, 4.5D, 3.0D, 6.5D, 0.0D, 0.0D, -0.5D);

                // Across the arena rather than at it: it would pass three blocks wide, which nothing could hit from.
                arrows[1] = Mechanics.shoot(helper, shooter, 1.5D, 3.0D, 6.5D, 0.5D, 0.0D, 0.0D);

                // Lying where it fell, which is where an arrow spends most of its life.
                arrows[2] = Mechanics.shoot(helper, shooter, 6.5D, 3.0D, 5.5D, 0.0D, 0.0D, 0.0D);

                // The agent's own, on its way to the skeleton. Nobody flinches at their own arrow.
                arrows[3] = Mechanics.shoot(helper, agent, 4.5D, 3.0D, 3.5D, 0.0D, 0.0D, 0.5D);
                return false;
            }

            if (tick == 4) {

                helper.assertTrue(agent.brain().enemySlots().occupant(0) == shooter, "The arrows pushed the skeleton out of its slot");

                int slot = Mechanics.slotOf(agent, arrows[0]);
                helper.assertTrue(slot > 0, "The arrow on its way to the agent took no slot");

                for (int other = 1; other < arrows.length; other++) {

                    helper.assertTrue(Mechanics.slotOf(agent, arrows[other]) < 0,
                            "An arrow that was crossing, still or the agent's own took slot " + Mechanics.slotOf(agent, arrows[other]));
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
    @GameTest(template = Mechanics.ARENA, timeoutTicks = 200)
    public static void theClockRunsUpAndTheQuiverRunsDown(GameTestHelper helper) {

        AgentMob archer = Mechanics.agent(helper, new BlockPos(4, 2, 1), 0.0F, 0.0F);
        AgentMob swordsman = Mechanics.agent(helper, new BlockPos(2, 2, 1), 0.0F, 0.0F);
        Mob opponent = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(4, 2, 6));

        archer.startEpisode(new Episode(SHORT_CLOCK, Mechanics.bounds(helper), opponent));
        Loadout.BOW.equip(archer);
        Loadout.SWORD.equip(swordsman);

        float[] ran = {0.0F};

        Mechanics.run(helper, tick -> {

            // One press is one arrow: the draw runs to full on its own, see onePressDrawsToFullAndLooses.
            archer.controls().use = tick == 1;

            float clock = Mechanics.selfField(archer, ObservationSchema.SELF_CLOCK);
            float arrows = Mechanics.selfField(archer, ObservationSchema.SELF_ARROWS);

            // This fight's own ticks over this fight's own limit, which is what the reward is charging against.
            helper.assertValueEqual(clock,
                    Math.min(1.0F, archer.episode().reward().elapsedTicks() / (float) SHORT_CLOCK), "the clock");
            helper.assertTrue(clock >= ran[0], "The clock went backwards, " + ran[0] + " to " + clock);
            ran[0] = clock;

            // What is in the hotbar, as a fraction of the 64 a bow loadout carries.
            helper.assertValueEqual(arrows, archer.getHotbarItem(1).getCount() / ObservationSchema.ARROW_SCALE, "arrows left");

            // A body with nothing that shoots, and no fight for its clock to run out of.
            helper.assertValueEqual(Mechanics.selfField(swordsman, ObservationSchema.SELF_ARROWS), 0.0F, "a swordsman's arrows");
            helper.assertValueEqual(Mechanics.selfField(swordsman, ObservationSchema.SELF_CLOCK), 0.0F, "the clock of no fight");

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

    // ---------------------------------------------------------------------------------------------------------------
    // Telling one opponent from another
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * A slot says what the thing in it can do, which is what lets one mob be told from another at all. Before these
     * fields a zombie and a warden filled a slot identically — both "monster", both at a health of 1, both empty handed —
     * and the league showed what that cost: every ordinary mob beaten 75 to 98% and 0% against the warden and against two
     * creepers.
     */
    @GameTest(template = Mechanics.ARENA, timeoutTicks = 100)
    public static void aSlotSaysWhatTheOpponentCanDo(GameTestHelper helper) {

        AgentMob agent = Mechanics.agent(helper, new BlockPos(4, 2, 1), 0.0F, 0.0F);
        Mob zombie = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(3, 2, 4));
        Mob skeleton = helper.spawnWithNoFreeWill(EntityType.SKELETON, new BlockPos(5, 2, 4));

        Mechanics.run(helper, tick -> {

            if (tick < 2) {

                return false;
            }

            float[] observation = new float[ObservationSchema.OBS_DIM];
            AgentObservation.write(agent, agent.brain().enemySlots(), observation, 0);

            int zombieAt = ObservationSchema.enemyOffset(Mechanics.slotOf(agent, zombie));
            int skeletonAt = ObservationSchema.enemyOffset(Mechanics.slotOf(agent, skeleton));

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
    @GameTest(template = Mechanics.ARENA, timeoutTicks = 100)
    public static void aCreeperSaysItExplodesAndHowCloseItIs(GameTestHelper helper) {

        AgentMob agent = Mechanics.agent(helper, new BlockPos(4, 2, 2), 0.0F, 0.0F);
        Creeper creeper = helper.spawnWithNoFreeWill(EntityType.CREEPER, new BlockPos(4, 2, 4));

        Mechanics.run(helper, tick -> {

            if (tick == 2) {

                creeper.ignite();
                return false;
            }

            if (tick < 8) {

                return false;
            }

            float[] observation = new float[ObservationSchema.OBS_DIM];
            AgentObservation.write(agent, agent.brain().enemySlots(), observation, 0);
            int at = ObservationSchema.enemyOffset(Mechanics.slotOf(agent, creeper));

            helper.assertValueEqual(observation[at + ObservationSchema.ENEMY_EXPLODES], 1.0F, "a creeper exploding");
            helper.assertTrue(observation[at + ObservationSchema.ENEMY_FUSE] > 0.0F,
                    "a lit creeper's fuse reads " + observation[at + ObservationSchema.ENEMY_FUSE]);
            helper.assertTrue(observation[at + ObservationSchema.ENEMY_FUSE] < 1.0F, "the fuse is already spent");
            return true;
        });
    }

    /**
     * What a slot says about the armour the body in it wears and about whether it has actually come for the agent, and that
     * both agree with the privileged facts the critic is handed.
     *
     * <p>Neither was in the observation. A body in iron takes about half the damage a bare one does from the same swing and
     * read as the same body with the same empty hands; and whether the other side has engaged decides whether the fight
     * happens at all, where the only proxy was which way it faced. The zombie takes the agent and lets it go again inside
     * one tick, so that nothing whatever can have moved between the two readings, and the facing comes out the same to the
     * digit while the new field flips: that is the whole of what facing could not say.
     *
     * <p>The armour is asserted on a second agent rather than on the zombie, because vanilla's own spawn rolls a zombie a
     * piece of armour and a bonus to the attribute besides, and a test of a number wants a number nobody rolled for.
     */
    @GameTest(template = Mechanics.ARENA, timeoutTicks = 100)
    public static void aSlotSaysWhatItWearsAndWhoItIsAfter(GameTestHelper helper) {

        AgentMob agent = Mechanics.agent(helper, new BlockPos(4, 2, 1), 0.0F, 0.0F);
        AgentMob other = Mechanics.agent(helper, new BlockPos(6, 2, 5), 0.0F, 0.0F);
        Mob zombie = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(2, 2, 5));

        Loadouts.ARMOURED_SWORD.equip(other);

        // On no team, so each agent counts the other an enemy, and the other agent is the whole of the side: with one body
        // on it, the best armour anything on that side wears is that body's own, which is what lets the two be compared.
        agent.startEpisode(new Episode(Mechanics.FIGHT_TICKS, Mechanics.bounds(helper), other));

        Mechanics.run(helper, tick -> {

            if (tick < 2) {

                return false;
            }

            float[] observation = new float[ObservationSchema.OBS_DIM];
            AgentObservation.write(agent, agent.brain().enemySlots(), observation, 0);

            float[] told = new float[FightFacts.SIZE];
            FightFacts.write(agent, told, 0);

            int wearing = ObservationSchema.enemyOffset(Mechanics.slotOf(agent, other));
            int mob = ObservationSchema.enemyOffset(Mechanics.slotOf(agent, zombie));

            // A full set of iron: two, six, five and two, out of the twenty a body tops out at.
            helper.assertValueEqual(observation[wearing + ObservationSchema.ENEMY_ARMOUR],
                    15.0F / ObservationSchema.ARMOUR_SCALE, "what a body in iron wears");

            // The same measurement as the critic's, so neither can drift: both read getArmorValue over the same twenty.
            helper.assertValueEqual(observation[wearing + ObservationSchema.ENEMY_ARMOUR], told[FightFacts.FOE_ARMOUR],
                    "the slot's armour against the critic's");
            helper.assertValueEqual(observation[mob + ObservationSchema.ENEMY_ARMOUR],
                    zombie.getArmorValue() / ObservationSchema.ARMOUR_SCALE, "what the zombie wears");

            // An agent is always coming: it fights from a network and holds no target for anything to read.
            helper.assertValueEqual(observation[wearing + ObservationSchema.ENEMY_TARGETS_ME], 1.0F, "another agent");
            helper.assertValueEqual(told[FightFacts.WENT_FOR], 1.0F, "the critic's went for");

            // The flip, inside one tick: the target is set, read, let go and read again, with no tick in between for
            // anything to move in.
            zombie.setTarget(agent);
            AgentObservation.write(agent, agent.brain().enemySlots(), observation, 0);

            helper.assertValueEqual(observation[mob + ObservationSchema.ENEMY_TARGETS_ME], 1.0F,
                    "the mob that took the agent");

            float facingSin = observation[mob + ObservationSchema.ENEMY_FACING_SIN];
            float facingCos = observation[mob + ObservationSchema.ENEMY_FACING_COS];

            zombie.setTarget(null);
            AgentObservation.write(agent, agent.brain().enemySlots(), observation, 0);

            helper.assertValueEqual(observation[mob + ObservationSchema.ENEMY_TARGETS_ME], 0.0F,
                    "the mob that let the agent go");

            // Unmoved to the digit either side of it, which is exactly why facing was not enough.
            helper.assertValueEqual(observation[mob + ObservationSchema.ENEMY_FACING_SIN], facingSin,
                    "the way it faces, having let go");
            helper.assertValueEqual(observation[mob + ObservationSchema.ENEMY_FACING_COS], facingCos,
                    "the way it faces, having let go");

            return true;
        });
    }

    /**
     * What the agent sees of its own armour and of the weapon in its hand. The hotbar says "a sword" for stone, iron and
     * diamond alike, and the armoured loadout read exactly like the plain one.
     *
     * <p>The two are read differently on purpose, and the first tick is where the difference shows. The weapon is added up
     * from the item's own modifiers, so it is right from the first row and follows a slot change at once; the armour is
     * {@code getArmorValue}, the same call the damage a blow gets through is worked out from and the same one the critic
     * reads, which vanilla catches up on when it notices the equipment change — the body's own first tick. See
     * {@code ObservationSchema#SELF_WEAPON_DAMAGE}.
     */
    @GameTest(template = Mechanics.ARENA, timeoutTicks = 100)
    public static void theAgentSeesItsArmourAndWhatItIsHolding(GameTestHelper helper) {

        AgentMob plain = Mechanics.agent(helper, new BlockPos(2, 2, 1), 0.0F, 0.0F);
        AgentMob armoured = Mechanics.agent(helper, new BlockPos(6, 2, 1), 0.0F, 0.0F);

        // Two swords, because a swap is the thing the attribute cannot follow: an iron sword takes off six and a stone one
        // five, and both are the one hotbar category "sword".
        plain.setHotbarItem(0, new ItemStack(Items.IRON_SWORD));
        plain.setHotbarItem(1, new ItemStack(Items.STONE_SWORD));
        Loadouts.ARMOURED_SWORD.equip(armoured);

        Mechanics.run(helper, tick -> {

            // Asked for from the third tick on; a press is acted on in the tick after the step that made it.
            plain.controls().selectedSlot = tick >= 2 ? 1 : 0;

            float damage = Mechanics.selfField(plain, ObservationSchema.SELF_WEAPON_DAMAGE);
            float worn = Mechanics.selfField(armoured, ObservationSchema.SELF_ARMOUR);

            float[] told = new float[FightFacts.SIZE];
            FightFacts.write(armoured, told, 0);

            helper.assertValueEqual(Mechanics.selfField(plain, ObservationSchema.SELF_ARMOUR), 0.0F, "the armour of a body with none");
            helper.assertValueEqual(worn, told[FightFacts.OWN_ARMOUR], "its own armour against the critic's");

            if (tick == 0) {

                // The row nothing has ticked on yet. The iron sword is in the hand and the field says so, while the
                // attribute still says a bare fist: this is the tick the two readings differ on, and the reason for the one
                // that is not the attribute.
                helper.assertValueEqual(damage, 6.0F / ObservationSchema.DAMAGE_SCALE, "an iron sword on the first row");
                helper.assertValueEqual((float) plain.getAttributeValue(Attributes.ATTACK_DAMAGE), 1.0F,
                        "the attribute on the first row");
                helper.assertValueEqual(worn, 0.0F, "armour vanilla has not noticed yet");
                return false;
            }

            if (tick == 1) {

                // Vanilla has noticed the equipment by now, and from here the two agree.
                helper.assertValueEqual(damage, 6.0F / ObservationSchema.DAMAGE_SCALE, "an iron sword");
                helper.assertValueEqual(damage,
                        (float) plain.getAttributeValue(Attributes.ATTACK_DAMAGE) / ObservationSchema.DAMAGE_SCALE,
                        "the weapon against the attribute");

                // A full set of iron: two, six, five and two.
                helper.assertValueEqual(worn, 15.0F / ObservationSchema.ARMOUR_SCALE, "a set of iron armour");
                return false;
            }

            // The swap asked for on tick 2 is in the hand by tick 3, and the field follows it there.
            if (tick >= 3) {

                helper.assertValueEqual(plain.getSelectedSlot(), 1, "the slot it swapped to");
                helper.assertValueEqual(damage, 5.0F / ObservationSchema.DAMAGE_SCALE, "a stone sword, after the swap");
                return true;
            }

            return false;
        });
    }
}
