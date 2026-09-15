package net.sievert.modularmobai.gametest.tests;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.IntPredicate;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.sievert.modularmobai.arena.Episode;
import net.sievert.modularmobai.brain.Brain;
import net.sievert.modularmobai.brain.BrainState;
import net.sievert.modularmobai.brain.BrainStep;
import net.sievert.modularmobai.brain.Brains;
import net.sievert.modularmobai.brain.schema.ActionSchema;
import net.sievert.modularmobai.brain.schema.Species;
import net.sievert.modularmobai.entity.ModEntities;
import net.sievert.modularmobai.entity.agent.AgentMob;
import net.sievert.modularmobai.entity.agent.mind.Emotion;
import net.sievert.modularmobai.entity.agent.mind.Events;
import net.sievert.modularmobai.entity.agent.mind.Memory;
import net.sievert.modularmobai.entity.agent.mind.MemoryBook;
import net.sievert.modularmobai.entity.agent.mind.MindEvent;
import net.sievert.modularmobai.entity.agent.mind.MindObservation;
import net.sievert.modularmobai.entity.agent.mind.MindState;
import net.sievert.modularmobai.entity.agent.mind.Need;
import net.sievert.modularmobai.entity.agent.mind.Relationship;
import net.sievert.modularmobai.entity.agent.mind.Temperament;
import net.sievert.modularmobai.entity.agent.mind.Trait;
import net.sievert.modularmobai.entity.agent.mind.Utterance;
import net.sievert.modularmobai.gametest.GameTestGroup;
import net.sievert.modularmobai.gametest.util.TestTicks;

/**
 * The mind on the entity: what an agent feels, remembers and thinks of anyone, and the one seam a changing brain opens.
 *
 * <p>Stage B of the port from {@code mind/} puts the hand-written half of the sim on the agent — emotions, needs, eight
 * traits, relationships, episodic memory and the event table that moves them — with nothing choosing anything from it
 * yet. That is deliberate: every part of it is observable on its own before the arbitrator acts on any of it, and this
 * is where it is observed.
 *
 * <p>The numbers asserted are {@code mind/dwarfsim}'s own, so a test here failing means one of two things: the Java has
 * drifted from the Python, or somebody changed the table on purpose and has not said so in
 * {@code mind/docs/design.md}. Every agent in here is given {@link Temperament#EVEN} first, because the modulators are
 * what make two agents react differently to the same event and a test that wants an exact number has to stand one of
 * them still: an even temper scales an anger gain by exactly one, and even bravery a fear gain by exactly one.
 *
 * <p><b>World agents, not training ones.</b> A training agent's mind is asleep — it costs a training worker nothing —
 * so everything here is the body a player actually meets.
 */
@GameTestGroup
public class AgentMindGameTest {

    private static final String ARENA = "arena";

    /** Long enough for every reading here; nothing in this class waits on a fight. */
    private static final int FIGHT_TICKS = 600;

    /** How close two floats have to be to be the same number. The deltas are hundredths, so this is nowhere near them. */
    private static final float EXACT = 1.0E-4F;

    // ---------------------------------------------------------------------------------------------------------------
    // The state, and what it survives
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Everything a mind holds comes back out of a save: what it feels, what it wants, what it is, who it has an opinion
     * of and what it remembers about them, down to the clock its memories fade against.
     *
     * <p>Held here rather than left to the play suite's own saving test because a mind is the first thing on the agent
     * that is <b>worth</b> saving and worthless if it is not: an agent that forgets who hit it every time the world
     * reloads has no memory at all, however good the table that filled it is.
     */
    @GameTest(template = ARENA)
    public static void aMindSurvivesBeingSavedAndLoaded(GameTestHelper helper) {

        AgentMob agent = agent(helper, new BlockPos(2, 2, 2));
        MindState mind = agent.mind();

        mind.become(Temperament.HOTHEAD);
        mind.feel(Emotion.GRIEF, 0.40F);

        UUID known = UUID.nameUUIDFromBytes("somebody".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        mind.regard(known, Relationship.Regard.TRUST, -0.30F);
        mind.regard(known, Relationship.Regard.HATRED, 0.40F);
        mind.remember(MindEvent.HIT, known, mind.owner(), 0.80F, Memory.Source.SUFFERED, null, 2);
        mind.struckBy(known);

        float grief = mind.emotion(Emotion.GRIEF);
        float hunger = mind.need(Need.HUNGER);

        CompoundTag saved = agent.saveWithoutId(new CompoundTag());
        AgentMob loaded = ModEntities.agentMob().create(helper.getLevel());

        loaded.load(saved);

        MindState back = loaded.mind();

        helper.assertValueEqual(back.temperament(), Temperament.HOTHEAD, "the temperament after loading");
        near(helper, back.trait(Trait.TEMPER), Temperament.HOTHEAD.of(Trait.TEMPER), "temper after loading");
        near(helper, back.trait(Trait.SUSPICION), Temperament.HOTHEAD.of(Trait.SUSPICION), "suspicion after loading");
        near(helper, back.emotion(Emotion.GRIEF), grief, "grief after loading");
        near(helper, back.need(Need.HUNGER), hunger, "hunger after loading");

        Relationship row = back.relationships().find(known);

        helper.assertTrue(row != null, "The relationship was lost in saving");
        near(helper, row.trust(), -0.30F, "trust after loading");
        near(helper, row.hatred(), 0.40F, "hatred after loading");

        helper.assertValueEqual(back.memories().size(), 1, "memories after loading");

        Memory remembered = back.memories().about(known);

        helper.assertTrue(remembered != null, "The episode was lost in saving");
        helper.assertValueEqual(remembered.kind(), MindEvent.HIT, "what the episode was");
        helper.assertValueEqual(remembered.source(), Memory.Source.SUFFERED, "how the episode was learned");
        near(helper, remembered.intensity(), 0.80F, "how loud the episode was");

        // And the one thing a save could drop without any of the above noticing: who hit it, which is what the hit
        // window, the focus slots and every reaction are read off.
        helper.assertTrue(back.underAttack(), "The agent forgot it had just been hit");
        helper.assertValueEqual(back.lastHitBy(), known, "who last hit it, after loading");

        helper.succeed();
    }

    /**
     * Feelings fade toward where that agent rests, needs climb, and opinions fade toward nothing — every mind tick, at
     * the rates {@code dwarfsim/mind.py} sets.
     *
     * <p>Asked of {@link MindState#decay} directly rather than by waiting: a mind tick is ten game ticks and a
     * relationship's half-life is four hundred and sixty of them, which is a test nobody would run.
     */
    @GameTest(template = ARENA)
    public static void feelingsFadeNeedsClimbAndOpinionsCool(GameTestHelper helper) {

        AgentMob agent = agent(helper, new BlockPos(2, 2, 2));
        MindState mind = agent.mind();

        mind.become(Temperament.EVEN);
        mind.feel(Emotion.ANGER, 0.50F);
        mind.feel(Emotion.GRIEF, 0.50F);

        UUID known = UUID.nameUUIDFromBytes("cooling".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        mind.regard(known, Relationship.Regard.HATRED, 0.50F);

        float anger = mind.emotion(Emotion.ANGER);
        float grief = mind.emotion(Emotion.GRIEF);
        float hunger = mind.need(Need.HUNGER);
        float hatred = mind.relationships().of(known).hatred();

        mind.decay(1);

        // Anger closes 3% of the gap to the baseline in a tick and grief 0.6%: the quarrel is over long before the loss is.
        near(helper, mind.emotion(Emotion.ANGER), anger + (mind.baseline(Emotion.ANGER) - anger) * 0.030F, "anger after a tick");
        near(helper, mind.emotion(Emotion.GRIEF), grief + (mind.baseline(Emotion.GRIEF) - grief) * 0.006F, "grief after a tick");
        near(helper, mind.need(Need.HUNGER), hunger + Need.HUNGER.rate(), "hunger after a tick");
        near(helper, mind.relationships().of(known).hatred(), hatred * (1.0F - Relationship.DECAY), "hatred after a tick");

        // And a hundred ticks later the anger is all but gone while the grief is most of the way still there.
        mind.decay(99);

        helper.assertTrue(mind.emotion(Emotion.ANGER) - mind.baseline(Emotion.ANGER) < 0.05F,
                "A hundred mind ticks on, the anger is still " + mind.emotion(Emotion.ANGER) + " over a resting "
                        + mind.baseline(Emotion.ANGER));
        helper.assertTrue(mind.emotion(Emotion.GRIEF) > 0.25F,
                "A hundred mind ticks on, the grief is already down to " + mind.emotion(Emotion.GRIEF));

        // A row that has decayed to nothing is dropped, which is what keeps an agent that has lived in a world for a
        // month from carrying a month of opinions.
        mind.regard(known, Relationship.Regard.HATRED, -1.0F);
        mind.decay(1);

        helper.assertTrue(mind.relationships().find(known) == null, "An opinion that says nothing is still being carried");
        helper.succeed();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The event table
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * An insult: the one it was aimed at is angry with whoever said it, trusts them less and hates them a little, and the
     * numbers are the table's as written. The actor vents a little of its own anger, which is the row that makes an
     * insult worth anything to the one throwing it.
     */
    @GameTest(template = ARENA)
    public static void anInsultRaisesAngerAndLowersTrust(GameTestHelper helper) {

        AgentMob said = agent(helper, new BlockPos(2, 2, 2));
        AgentMob heard = agent(helper, new BlockPos(4, 2, 2));

        even(said, heard);

        float anger = heard.mind().emotion(Emotion.ANGER);
        float actorAnger = said.mind().emotion(Emotion.ANGER);

        Events.happened(MindEvent.INSULT, said, heard, 1.0F);

        near(helper, heard.mind().emotion(Emotion.ANGER), anger + 0.26F, "the anger an insult raises");

        Relationship of = heard.mind().relationships().find(said.getUUID());

        helper.assertTrue(of != null, "The one insulted has no opinion of the one who said it");
        near(helper, of.trust(), -0.10F, "the trust an insult costs");
        near(helper, of.respect(), -0.06F, "the respect an insult costs");
        near(helper, of.hatred(), 0.11F, "the hatred an insult buys");

        near(helper, said.mind().emotion(Emotion.ANGER), Math.max(0.0F, actorAnger - 0.04F),
                "the anger the one who said it vents");

        // Twice as hard a word lands twice as hard, which is what a magnitude is for: it is what speech passes its
        // aggression through.
        AgentMob again = agent(helper, new BlockPos(6, 2, 2));

        even(again);

        float before = again.mind().emotion(Emotion.ANGER);

        Events.apply(again.mind(), MindEvent.INSULT, Events.Role.TARGET, said.getUUID(), again.getUUID(), 2.0F);
        near(helper, again.mind().emotion(Emotion.ANGER), before + 0.52F, "the anger an insult twice as hard raises");

        helper.succeed();
    }

    /**
     * A blow leaves a grudge, and the grudge is the size of the blow. Both halves matter: a scratch that is remembered
     * as loudly as a mauling makes every slight worth the same, which is the failure mode the magnitude exists against.
     */
    @GameTest(template = ARENA)
    public static void aHitLeavesAGrudgeScaledByTheDamage(GameTestHelper helper) {

        AgentMob mauled = agent(helper, new BlockPos(2, 2, 2));
        AgentMob scratched = agent(helper, new BlockPos(6, 2, 2));

        even(mauled, scratched);

        Mob attacker = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(4, 2, 4));

        // A quarter of the health bar is the table as written.
        Events.struck(mauled, attacker, mauled.getMaxHealth() * 0.25F);

        Memory hard = suffered(mauled.mind(), attacker.getUUID());

        helper.assertTrue(hard != null, "A blow left no memory at all");
        near(helper, hard.intensity(), 1.00F, "how loud a blow that took a quarter of the bar is remembered");
        helper.assertValueEqual(hard.source(), Memory.Source.SUFFERED, "how the one who was hit learned of it");

        // Being hit costs trust and buys respect at once, which is the whole reason the three numbers are three. Read
        // before the second blow, because the second is one this agent watches and a witness moves its opinion too.
        Relationship of = mauled.mind().relationships().find(attacker.getUUID());

        helper.assertTrue(of != null, "The one hit has no opinion of what hit it");
        near(helper, of.trust(), -0.24F, "the trust a blow costs");
        near(helper, of.respect(), 0.05F, "the respect a blow buys");
        near(helper, of.hatred(), 0.25F, "the hatred a blow buys");

        helper.assertTrue(mauled.mind().underAttack(), "The agent does not know it is under attack");
        helper.assertValueEqual(mauled.mind().lastHitBy(), attacker.getUUID(), "who it thinks hit it");

        // An eighth of the bar is half the blow, and is remembered at half the loudness.
        Events.struck(scratched, attacker, scratched.getMaxHealth() * 0.125F);

        Memory light = suffered(scratched.mind(), attacker.getUUID());

        helper.assertTrue(light != null, "A scratch left no memory at all");
        near(helper, light.intensity(), 0.50F, "how loud a scratch is remembered");

        // The two are four blocks apart, so the first also watched the second being hit, and a witness remembers an
        // episode at three fifths of what the one it happened to remembers it at. Two memories about the same zombie
        // and they say different things, which is the difference between suffering something and seeing it.
        Memory watched = seen(mauled.mind(), attacker.getUUID());

        helper.assertTrue(watched != null, "An agent four blocks away saw nothing of the blow");
        near(helper, watched.intensity(), 0.50F * MemoryBook.WITNESS_SHARE, "how loudly a witness remembers a scratch");

        // And the derived number the observation reads follows the loudness, both softened to 0..1 the same way.
        float mauledGrudge = mauled.mind().memories().grudge(mauled.mind().now(), mauled.mind(), attacker.getUUID());
        float scratchedGrudge = scratched.mind().memories().grudge(scratched.mind().now(), scratched.mind(), attacker.getUUID());

        helper.assertTrue(mauledGrudge > scratchedGrudge,
                "The mauled agent holds " + mauledGrudge + " against the zombie and the scratched one " + scratchedGrudge);

        helper.succeed();
    }

    /**
     * Who saw it. An agent that perceives what happened takes a fraction of it; one that cannot takes none of it at all,
     * and takes none of it whatever is going on three blocks away from it.
     *
     * <p>The one that cannot is across the room and looking the other way, which is the commonest way to miss something
     * in a world and the one that would be quietly wrong if witnesses were gathered by distance: the cone is what makes
     * turning your head worth anything, and the mind reads the same perception the combat view does rather than a second
     * one that could drift from it.
     */
    @GameTest(template = ARENA)
    public static void aWitnessWhoSeesItReactsAndOneWhoCannotDoesNot(GameTestHelper helper) {

        AgentMob said = agent(helper, new BlockPos(1, 2, 1));
        AgentMob heard = agent(helper, new BlockPos(2, 2, 1));

        // Three blocks off, which is inside hearing: perceived whichever way it is looking.
        AgentMob watching = agent(helper, new BlockPos(4, 2, 1));

        // The far corner, eight and a half blocks off and facing away down the plot, so the insult is behind it and too
        // far to hear.
        AgentMob away = agent(helper, new BlockPos(7, 2, 7));

        even(said, heard, watching, away);

        float watchingAnger = watching.mind().emotion(Emotion.ANGER);
        float awayAnger = away.mind().emotion(Emotion.ANGER);

        Events.happened(MindEvent.INSULT, said, heard, 1.0F);

        // A witness takes 45% of the row, and the sympathy of somebody with no opinion of the victim is exactly one.
        near(helper, watching.mind().emotion(Emotion.ANGER), watchingAnger + 0.03F * Events.WITNESS_FRACTION,
                "the anger a witness takes from an insult to somebody else");

        Relationship theirs = watching.mind().relationships().find(said.getUUID());

        helper.assertTrue(theirs != null, "A witness formed no opinion of the one who said it");
        near(helper, theirs.trust(), -0.05F * Events.WITNESS_FRACTION, "the trust a witness loses in the one who said it");

        helper.assertValueEqual(watching.mind().memories().size(), 1, "what a witness remembers of it");

        Memory seen = watching.mind().memories().about(said.getUUID());

        helper.assertTrue(seen != null, "A witness remembered nothing about the one who said it");
        helper.assertValueEqual(seen.source(), Memory.Source.SEEN, "how a witness learned of it");
        near(helper, seen.intensity(), MindEvent.INSULT.memoryWeight() * MemoryBook.WITNESS_SHARE,
                "how loudly a witness remembers it");

        // And the one that could not see it has neither felt nor remembered anything.
        near(helper, away.mind().emotion(Emotion.ANGER), awayAnger, "the anger of an agent that saw nothing");
        helper.assertTrue(away.mind().relationships().find(said.getUUID()) == null,
                "An agent that saw nothing formed an opinion about it anyway");
        helper.assertValueEqual(away.mind().memories().size(), 0, "what an agent that saw nothing remembers");

        helper.succeed();
    }

    /**
     * A line of chat, mapped onto the table exactly as {@code dwarfsim/speech.py} maps it — including the one thing the
     * {@code addressed} head is for: a threat <em>said to you</em> frightens you, and a threat reported about somebody
     * else is overheard. Without that gate, "I told him I'd wreck him" terrifies the person it is told to.
     *
     * <p>Nothing calls this yet: stage C's chat hook is one call of the interpreter and one of this. It is written and
     * held now so that stage C is a hook and not a port.
     */
    @GameTest(template = ARENA)
    public static void aThreatSaidToYouLandsHarderThanOneOverheard(GameTestHelper helper) {

        AgentMob speaker = agent(helper, new BlockPos(2, 2, 2));
        AgentMob told = agent(helper, new BlockPos(4, 2, 2));
        AgentMob overhearing = agent(helper, new BlockPos(6, 2, 2));

        even(speaker, told, overhearing);

        float toldFear = told.mind().emotion(Emotion.FEAR);
        float overheardFear = overhearing.mind().emotion(Emotion.FEAR);

        // Aggression one, said to the listener: a magnitude of 0.45 + 0.85, which is the sim's own arithmetic.
        Utterance atYou = Utterance.plain("THREAT", Utterance.ADDRESSED_LISTENER, 1.0F);
        Utterance aboutSomebodyElse = Utterance.plain("THREAT", Utterance.ADDRESSED_THIRD, 1.0F);

        told.mind().hear(speaker, atYou);
        overhearing.mind().hear(speaker, aboutSomebodyElse);

        float magnitude = 0.45F + 0.85F;

        near(helper, told.mind().emotion(Emotion.FEAR), toldFear + 0.22F * magnitude, "the fear a threat said to you raises");
        near(helper, overhearing.mind().emotion(Emotion.FEAR), overheardFear + 0.08F * magnitude * Events.WITNESS_FRACTION,
                "the fear a threat about somebody else raises");

        // A request is not an event at all: it is a standing pull, which the arbitrator will weigh by how much the agent
        // trusts whoever asked.
        float before = told.mind().emotion(Emotion.HAPPINESS);

        told.mind().hear(speaker, Utterance.plain("REQUEST", Utterance.ADDRESSED_LISTENER, 0.0F));

        near(helper, told.mind().emotion(Emotion.HAPPINESS), before, "what a request does to how an agent feels");
        helper.assertTrue(told.mind().request() != null, "A request left nothing on the listener's mind");
        helper.assertValueEqual(told.mind().request().from(), speaker.getUUID(), "who the request came from");

        helper.succeed();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The observation
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * The 77 columns, in the order the frozen layout gives them, filled from the mind that is carrying them.
     *
     * <p>The first assertion is the one that matters most and is not about any agent: this build's table of columns was
     * held against {@code shared/models/decisions/layout.json} as it loaded. A layout that moves is then a refusal to
     * start with the column in it, rather than a model reading the wrong five floats for the rest of the run — and this
     * is what says the check actually ran rather than quietly finding no file to run against.
     */
    @GameTest(template = ARENA, timeoutTicks = 200)
    public static void theObservationIsTheFrozenLayout(GameTestHelper helper) {

        helper.assertTrue(MindObservation.checkedAgainst() != null,
                "The observation table was never held against shared/models/decisions/layout.json: it could not be found "
                        + "from " + System.getProperty("user.dir"));

        AgentMob agent = agent(helper, new BlockPos(2, 2, 2));
        AgentMob friend = agent(helper, new BlockPos(3, 2, 2));

        even(agent, friend);

        MindState mind = agent.mind();

        mind.feel(Emotion.ANGER, 0.40F);
        mind.regard(friend.getUUID(), Relationship.Regard.TRUST, 0.60F);
        mind.struckBy(friend.getUUID());

        // One mind tick, so the agent has looked about it: the crowd, the company and what it is carrying are all read
        // from the world, and until it has looked they are what a fresh mind holds.
        run(helper, tick -> {

            if (tick < MindState.MIND_TICK + 2) {

                return false;
            }

            float[] row = new float[MindObservation.OBS_SIZE];

            mind.observe(row);

            near(helper, row[MindObservation.offsetOf("mind.emotions") + Emotion.ANGER.ordinal()],
                    mind.emotion(Emotion.ANGER), "the anger column");
            near(helper, row[MindObservation.offsetOf("mind.needs") + Need.THIRST.ordinal()],
                    mind.need(Need.THIRST), "the thirst column");
            near(helper, row[MindObservation.offsetOf("mind.traits") + Trait.BRAVERY.ordinal()],
                    Temperament.EVEN.of(Trait.BRAVERY), "the bravery column");
            near(helper, row[MindObservation.offsetOf("mind.health")], 1.0F, "the health column");

            // The focus slots: the one body it has an opinion of and has just been hit by is the first of them, and the
            // three behind it are empty, present flag and all.
            int focus = MindObservation.offsetOf("mind.focus");

            near(helper, row[focus], 1.0F, "the present flag of the first focus slot");

            // Against the row it came from rather than against the number it was set to: an opinion cools a little every
            // mind tick, and what the column has to be is what the relationship says now.
            near(helper, row[focus + 1], mind.relationships().of(friend.getUUID()).trust(),
                    "the trust in the first focus slot");

            for (int slot = 1; slot < 4; slot++) {

                near(helper, row[focus + slot * 6], 0.0F, "the present flag of focus slot " + slot);
            }

            near(helper, row[MindObservation.offsetOf("crowd")], 1.0F / MindState.CROWD_SCALE, "the crowd column");
            near(helper, row[MindObservation.offsetOf("under_attack")], 1.0F, "the under attack column");
            near(helper, row[MindObservation.offsetOf("alive_fraction")], 1.0F, "the alive fraction column");

            // And the columns the mod has nothing to fill yet read zero rather than being left out of the table, which
            // is what keeps every column after them at the offset the frozen layout gives it.
            for (String empty : List.of("place", "goals", "obligations", "is_chief", "chief_here", "condition")) {

                int at = MindObservation.offsetOf(empty);

                for (int column = 0; column < MindObservation.widthOf(empty); column++) {

                    near(helper, row[at + column], 0.0F, "the " + empty + " column");
                }
            }

            return true;
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The seam
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * <b>The one thing in the mod the mind had to open.</b> An agent's memory of a fight belongs to the brain that is
     * remembering it, not to the agent, so a spell under another brain leaves it exactly where it was.
     *
     * <p>{@code BrainState.use} used to clear the hidden vector on any change of brain, which was right while the only
     * switch in the mod was between a scripted teacher and a network that never shared an agent. From the arbitrator on,
     * an agent switches constantly — fight, flee, work, fight again — and each switch would have wiped the combat
     * network's 128 floats in the middle of the fight they were about.
     *
     * <p>What is asserted is the strongest reading of it: the <b>same array</b>, holding the <b>same numbers</b>, and
     * the brain carrying on counting from where it stopped. A stub stands in for the network because
     * {@link Brain#hiddenSize} is the whole of what {@link BrainState} knows about any brain, and a stub says what the
     * numbers should be.
     */
    @GameTest(template = ARENA, timeoutTicks = 300)
    public static void theCombatMemorySurvivesASpellUnderAnotherBrain(GameTestHelper helper) {

        AgentMob agent = agent(helper, new BlockPos(2, 2, 2));

        // A fight: an opponent that has come for it, and an episode, so that nothing restarts the agent's memory for
        // standing about with nobody in view.
        Mob opponent = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(6, 2, 6));

        opponent.setTarget(agent);
        agent.startEpisode(new Episode(FIGHT_TICKS, bounds(helper), List.of(opponent)));
        agent.brain().use(REMEMBERING);

        final float[][] held = new float[1][];
        final int[] stepsWhenSwitched = new int[1];

        run(helper, tick -> {

            opponent.setTarget(agent);

            // Twenty ticks of fighting, so there is a memory worth keeping.
            if (tick == 20) {

                float[] memory = agent.brain().memoryOf(REMEMBERING);

                helper.assertTrue(memory != null, "The remembering brain kept no memory at all");
                helper.assertTrue(memory[0] > 0.0F, "The remembering brain never advanced its memory");

                held[0] = memory;
                stepsWhenSwitched[0] = (int) memory[0];

                agent.brain().use(Brains.scripted());
            }

            // Twenty ticks under a brain with no memory of its own, and then back.
            if (tick > 20 && tick < 41) {

                helper.assertTrue(agent.brain().memoryOf(REMEMBERING) == held[0],
                        "The network's memory was replaced while the agent was under another brain");

                helper.assertValueEqual((int) held[0][0], stepsWhenSwitched[0],
                        "the network's memory while the agent was under another brain");

                return false;
            }

            if (tick == 41) {

                agent.brain().use(REMEMBERING);
                return false;
            }

            if (tick < 50) {

                return false;
            }

            // Back on the network: the same array, and it has carried on counting from where it stopped rather than
            // starting again at nothing.
            helper.assertTrue(agent.brain().memoryOf(REMEMBERING) == held[0],
                    "The network was handed a different memory when the agent came back to it");

            helper.assertTrue(held[0][0] > stepsWhenSwitched[0],
                    "The network did not carry on from its memory: " + held[0][0] + " against " + stepsWhenSwitched[0]);

            // The scripted fighter has no memory of its own, so nothing was ever kept for it.
            helper.assertTrue(agent.brain().memoryOf(Brains.scripted()) == null,
                    "A brain with no memory was given one anyway");

            // And the start of a fight still clears what every brain remembers, which is the one thing that must.
            agent.brain().reset();

            helper.assertTrue(agent.brain().memoryOf(REMEMBERING) == held[0],
                    "Resetting replaced the memory instead of clearing it");

            near(helper, held[0][0], 0.0F, "the network's memory after the start of a fresh episode");

            return true;
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The furniture
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * A brain that presses nothing and remembers how many times it has been asked. It stands in for the combat network
     * in the seam test: what {@link BrainState} knows about any brain is how many floats of memory it wants, and this
     * wants eight of them and says what should be in them.
     */
    private static final Brain REMEMBERING = new Brain() {

        @Override
        public Species species() {

            return Species.HUMANOID;
        }

        @Override
        public int hiddenSize() {

            return 8;
        }

        @Override
        public void act(BrainStep step) {

            Arrays.fill(step.actions, 0, step.count * ActionSchema.ACT_DIM, 0.0F);

            for (int index = 0; index < step.count; index++) {

                step.hidden[index * this.hiddenSize()] += 1.0F;
            }
        }
    };

    /** An agent of the kind a player meets, standing where it was put and looking down the plot. */
    private static AgentMob agent(GameTestHelper helper, BlockPos feet) {

        AgentMob agent = helper.spawn(ModEntities.agentMob(), feet);

        agent.setYRot(0.0F);
        agent.setYHeadRot(0.0F);
        agent.setYBodyRot(0.0F);
        agent.setXRot(0.0F);

        return agent;
    }

    /**
     * Stands the modulators still. Temper scales an anger gain and bravery a fear gain, and a rolled agent's are drawn
     * from a range, so a test that wants the table's own number has to say what the agent is like first.
     */
    private static void even(AgentMob... agents) {

        for (AgentMob agent : agents) {

            agent.mind().become(Temperament.EVEN);
        }
    }

    /** The episode that agent holds about something done <b>to it</b> by that body, or null where it holds none. */
    private static Memory suffered(MindState mind, UUID actor) {

        return held(mind, actor, Memory.Source.SUFFERED);
    }

    /** And the one it holds about that body doing something to somebody else. */
    private static Memory seen(MindState mind, UUID actor) {

        return held(mind, actor, Memory.Source.SEEN);
    }

    private static Memory held(MindState mind, UUID actor, Memory.Source source) {

        for (Memory memory : mind.memories().all()) {

            if (actor.equals(memory.actor()) && memory.source() == source) {

                return memory;
            }
        }

        return null;
    }

    private static void near(GameTestHelper helper, float measured, float expected, String what) {

        helper.assertTrue(Math.abs(measured - expected) < EXACT,
                "The " + what + " is " + measured + " and should be " + expected);
    }

    /** The plot this test owns, with a little slack: the box an agent in it is allowed to see into. */
    private static AABB bounds(GameTestHelper helper) {

        return new AABB(Vec3.atLowerCornerOf(helper.absolutePos(BlockPos.ZERO)),
                Vec3.atLowerCornerOf(helper.absolutePos(new BlockPos(9, 9, 9)))).inflate(1.0D);
    }

    private static void run(GameTestHelper helper, IntPredicate step) {

        TestTicks.run(helper, step);
    }
}
