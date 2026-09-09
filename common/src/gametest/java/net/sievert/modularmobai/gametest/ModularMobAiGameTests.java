package net.sievert.modularmobai.gametest;

import net.sievert.modularmobai.gametest.tests.VillagerVindicatorGameTest;
import net.minecraft.gametest.framework.TestFunction;

import java.util.Collection;

public class ModularMobAiGameTests {

    private static final Class<?>[] TEST_HOLDERS = {
            VillagerVindicatorGameTest.class
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
