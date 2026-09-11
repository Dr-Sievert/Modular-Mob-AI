package net.sievert.modularmobai.gametest.tests;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Vindicator;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.sievert.modularmobai.brain.AgentDriver;
import net.sievert.modularmobai.entity.AgentMob;
import net.sievert.modularmobai.entity.ModEntities;
import net.sievert.modularmobai.gametest.GameTestGroup;
import net.sievert.modularmobai.gametest.GameTestTuning;
import net.sievert.modularmobai.gametest.RepeatGameTest;
import net.sievert.modularmobai.gametest.util.TestDurationStats;

/**
 * An agent against a vindicator, which is the first rung of the ladder that has anything to learn on it.
 *
 * <p>The agent is driven by the scripted brain, which plays the fight from nothing but the observation vector. That
 * makes this a test of the observation as much as of the fight: a brain that can close, aim and land hits knowing only
 * those numbers is proof the agent is being described correctly, and one that walks into walls is proof it is not.
 */
@GameTestGroup
public class AgentVindicatorGameTest {

    private static final int SIZE = 9;

    /** A structure block sits one below the structure it places, so the first air layer is at helper height two. */
    private static final int FLOOR_Y = 2;

    private static final BlockPos AGENT_POS = new BlockPos(SIZE / 2, FLOOR_Y, 2);
    private static final BlockPos VINDICATOR_POS = new BlockPos(SIZE / 2, FLOOR_Y, SIZE - 3);

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
        final Vindicator vindicator = helper.spawn(EntityType.VINDICATOR, VINDICATOR_POS);

        // spawn() skips finalizeSpawn, so a vindicator would arrive empty handed and hit for far less than one that
        // spawned on its own. Running it here hands it the iron axe it is supposed to carry.
        vindicator.finalizeSpawn(
                helper.getLevel(),
                helper.getLevel().getCurrentDifficultyAt(helper.absolutePos(VINDICATOR_POS)),
                MobSpawnType.EVENT,
                null
        );

        vindicator.setTarget(agent);

        // Arenas sit only a few blocks apart, so without this the agent would see into its neighbours and could end up
        // chasing an opponent in the next fight over. The box is the plot the test owns, with a little slack.
        agent.setArenaBounds(new AABB(
                Vec3.atLowerCornerOf(helper.absolutePos(BlockPos.ZERO)),
                Vec3.atLowerCornerOf(helper.absolutePos(new BlockPos(SIZE, SIZE, SIZE)))).inflate(1.0D));

        agent.setHotbarItem(0, new ItemStack(Items.IRON_SWORD));
        agent.reward().beginEpisode(FIGHT_TICKS);

        helper.startSequence()
                .thenWaitUntil(() -> {

                    // Safe to call from every arena: it runs the batch once per game tick no matter how many callers
                    // there are, so all the agents alive in this tick go through the brain together.
                    AgentDriver.tick(helper.getLevel());

                    if (agent.isAlive() && vindicator.isAlive() && helper.getTick() < FIGHT_TICKS) {

                        throw new GameTestAssertException("Fight still going");
                    }
                })
                .thenExecute(() -> {

                    // Only the arena knows what winning meant, so it is the one that says so. An agent that died has
                    // already reported its own loss; one that is still standing next to a live opponent ran out of time,
                    // which is the other way to lose.
                    final boolean won = !vindicator.isAlive() && agent.isAlive();

                    if (won) {

                        agent.reward().won();
                    }

                    else if (agent.isAlive()) {

                        agent.reward().lost();
                    }

                    TIME_TO_RESOLVE.record(helper.getTick(),
                            won ? TestDurationStats.Outcome.WIN : TestDurationStats.Outcome.LOSS);
                })
                // The outcome above was decided after this tick's batch had already gone out, so the agent's final step,
                // the one flagged done and carrying its terminal reward, needs one more tick to be sent. Without this
                // the last arena to finish would end without the brain ever hearing how it ended.
                .thenExecuteAfter(1, () -> AgentDriver.tick(helper.getLevel()))
                .thenSucceed();
    }
}
