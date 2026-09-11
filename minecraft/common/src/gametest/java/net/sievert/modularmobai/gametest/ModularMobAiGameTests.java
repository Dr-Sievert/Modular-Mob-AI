package net.sievert.modularmobai.gametest;

import net.sievert.modularmobai.gametest.tests.AgentVindicatorGameTest;
import net.minecraft.gametest.framework.TestFunction;

import java.util.Collection;

public class ModularMobAiGameTests {

    private static final Class<?>[] TEST_HOLDERS = {
            // Swap this for VillagerVindicatorGameTest.class to go back to the fifty thousand arena baseline run.
            AgentVindicatorGameTest.class
    };

    /**
     * Builds the test functions for every registered holder.
     *
     * @return The test functions to hand to the game test framework.
     */
    public static Collection<TestFunction> generateTests() {

        return ModularMobAiTestFunction.getTestsFrom(TEST_HOLDERS);
    }
}
