package net.sievert.modularmobai.gametest.tests;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.sievert.modularmobai.arena.Episode;
import net.sievert.modularmobai.entity.agent.AgentMob;
import net.sievert.modularmobai.entity.ModEntities;
import net.sievert.modularmobai.gametest.GameTestGroup;
import net.sievert.modularmobai.gametest.GameTestTuning;
import net.sievert.modularmobai.gametest.Opponents;
import net.sievert.modularmobai.gametest.RepeatGameTest;
import net.sievert.modularmobai.gametest.replay.FightRecorder;
import net.sievert.modularmobai.gametest.util.TestDurationStats;

/**
 * An agent against a vindicator, which is the first rung of the ladder that has anything to learn on it.
 *
 * <p>By default the agent is driven by the scripted brain, which plays the fight from nothing but the observation
 * vector. That makes this a test of the observation as much as of the fight: a brain that can close, aim and land hits
 * knowing only those numbers is proof the agent is being described correctly, and one that walks into walls is proof it
 * is not. The same arena is what a network trains in and is measured in; which brain drives it is chosen when the game
 * starts, not here.
 *
 * <p>The arena never drives the agent itself. The level's driver steps every agent at the start of each tick; the arena
 * only sets the fight up and says how it ended.
 *
 * <p>A run can put another mob in the vindicator's place, see {@link Opponents}.
 */
@GameTestGroup
public class AgentVindicatorGameTest {

    private static final int SIZE = 9;

    /** A structure block sits one below the structure it places, so the first air layer is at helper height two. */
    private static final int FLOOR_Y = 2;

    private static final BlockPos AGENT_POS = new BlockPos(SIZE / 2, FLOOR_Y, 2);
    private static final BlockPos OPPONENT_POS = new BlockPos(SIZE / 2, FLOOR_Y, SIZE - 3);

    /** How long a fight may run before it is called as a loss. A minute is well past any fight that is going to end. */
    private static final int FIGHT_TICKS = 1200;

    /**
     * The framework's own limit, deliberately later than the fight's. A fight that runs out of time is a loss, not a
     * broken test: the test ends it itself, reports it, and passes. If the framework's timeout ever fires, something
     * has gone wrong with that, which is exactly when a failed test is the right answer.
     */
    private static final int TIMEOUT_TICKS = FIGHT_TICKS + 40;

    private static final TestDurationStats TIME_TO_RESOLVE =
            new TestDurationStats("Agent fight length", GameTestTuning.arenasInShard(GameTestTuning.arenaCount()));

    @GameTest(template = "arena", timeoutTicks = TIMEOUT_TICKS)
    @RepeatGameTest
    public static void agentFightsVindicator(GameTestHelper helper, int arena) {

        final AgentMob agent = helper.spawn(ModEntities.trainingAgent(), AGENT_POS);
        final Mob opponent = Opponents.spawn(helper, OPPONENT_POS);

        // spawn() skips finalizeSpawn, so a vindicator would arrive empty handed and hit for far less than one that
        // spawned on its own. Running it here hands it the iron axe it is supposed to carry, as it hands a skeleton its bow
        // and a pillager its crossbow.
        opponent.finalizeSpawn(
                helper.getLevel(),
                helper.getLevel().getCurrentDifficultyAt(helper.absolutePos(OPPONENT_POS)),
                MobSpawnType.EVENT,
                null
        );

        opponent.setTarget(agent);

        // Arenas sit only a few blocks apart, so without the bounds the agent would see into its neighbours and could end
        // up chasing an opponent in the next fight over. The box is the plot the test owns, with a little slack.
        final Episode episode = new Episode(FIGHT_TICKS, new AABB(
                Vec3.atLowerCornerOf(helper.absolutePos(BlockPos.ZERO)),
                Vec3.atLowerCornerOf(helper.absolutePos(new BlockPos(SIZE, SIZE, SIZE)))).inflate(1.0D), opponent);

        agent.setHotbarItem(0, new ItemStack(Items.IRON_SWORD));
        agent.startEpisode(episode);

        // Null unless this is one of the fights asked to be written down for watching later. The box starts one above
        // the structure block, so the roof, the last of its nine layers, is at helper height nine.
        final FightRecorder replay = FightRecorder.start(agent, opponent, helper.absolutePos(new BlockPos(0, SIZE, 0)).getY());

        helper.startSequence()
                .thenWaitUntil(() -> {

                    if (replay != null) {

                        replay.tick();
                    }

                    if (agent.isAlive() && opponent.isAlive() && helper.getTick() < FIGHT_TICKS) {

                        throw new GameTestAssertException("Fight still going");
                    }
                })
                .thenExecute(() -> {

                    // Only the arena knows what winning meant, so it is the one that says so. An agent that died has
                    // already reported its own loss; one that is still standing next to a live opponent ran out of time,
                    // which is the other way to lose.
                    final boolean won = !opponent.isAlive() && agent.isAlive();

                    if (won) {

                        episode.reward().won();
                    }

                    else if (agent.isAlive()) {

                        episode.reward().lost();
                    }

                    TIME_TO_RESOLVE.record(helper.getTick(),
                            won ? TestDurationStats.Outcome.WIN : TestDurationStats.Outcome.LOSS);

                    if (replay != null) {

                        replay.finish(won ? FightRecorder.Outcome.WIN
                                : agent.isAlive() ? FightRecorder.Outcome.TIMEOUT : FightRecorder.Outcome.LOSS);
                    }
                })
                // The outcome above was decided after this tick's batch had already gone out, so the agent's final step,
                // the one flagged done and carrying its terminal reward, goes out at the start of the next tick. The
                // arena has to still be standing then, or the last one to finish would end without its brain ever
                // hearing how it ended.
                .thenIdle(1)
                .thenSucceed();
    }
}
