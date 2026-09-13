package net.sievert.modularmobai.gametest.util;

import java.util.function.IntPredicate;

import net.minecraft.gametest.framework.GameTestHelper;

/**
 * Running a game test a tick at a time, which is how every test that watches the agent's body is written.
 *
 * <p>The mechanics suite and the play suite both want it, and both had their own copy of the same eight lines; the play
 * suite's said "as the mechanics suite does" in its comment, which is the sign that it belongs in neither.
 */
public final class TestTicks {

    private TestTicks() {}

    /**
     * Runs a test a tick at a time. The step is called on the tick the test is set up, with zero, and after every tick the
     * world takes from then on, with how many that makes, until it returns true.
     *
     * <p>Controls pressed in a step are what the agents act on in the next tick, so a step's checks see the tick that
     * step's number counts. A failed assertion fails the test there and then.
     */
    public static void run(GameTestHelper helper, IntPredicate step) {

        final int[] tick = {0};

        helper.onEachTick(() -> {

            if (step.test(tick[0]++)) {

                helper.succeed();
            }
        });
    }
}
