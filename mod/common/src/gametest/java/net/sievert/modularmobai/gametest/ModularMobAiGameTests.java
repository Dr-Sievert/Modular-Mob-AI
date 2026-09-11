package net.sievert.modularmobai.gametest;

import java.util.Collection;

import net.minecraft.gametest.framework.TestFunction;
import net.sievert.modularmobai.gametest.tests.AgentMechanicsGameTest;
import net.sievert.modularmobai.gametest.tests.AgentVindicatorGameTest;
import net.sievert.modularmobai.gametest.tests.AgentVindicatorTerrainGameTest;
import net.sievert.modularmobai.gametest.tests.TerrainLibraryGameTest;
import net.sievert.modularmobai.gametest.tests.VillagerVindicatorGameTest;

public class ModularMobAiGameTests {

    /**
     * Builds the test functions for the suite the run asked for, see {@link GameTestTuning#suite()}. One suite per run,
     * because the terrain one needs the world generated differently from the start.
     *
     * @return The test functions to hand to the game test framework.
     */
    public static Collection<TestFunction> generateTests() {

        final Class<?>[] holders = switch (GameTestTuning.suite()) {

            case "terrain" -> new Class<?>[] {AgentVindicatorTerrainGameTest.class};

            // The fifty thousand arena baseline, with no agent in it.
            case "baseline" -> new Class<?>[] {VillagerVindicatorGameTest.class};

            // The agent's body against a player's rules, one rule at a time: no fights, a minute or so all told.
            case "mechanics" -> new Class<?>[] {AgentMechanicsGameTest.class};

            // No fights either: generates the terrain library the terrain suite reads its sites from.
            case "library" -> new Class<?>[] {TerrainLibraryGameTest.class};

            default -> new Class<?>[] {AgentVindicatorGameTest.class};
        };

        return ModularMobAiTestFunction.getTestsFrom(holders);
    }
}
