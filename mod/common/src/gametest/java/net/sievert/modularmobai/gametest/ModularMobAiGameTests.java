package net.sievert.modularmobai.gametest;

import java.util.Collection;

import net.minecraft.gametest.framework.TestFunction;
import net.sievert.modularmobai.gametest.tests.AgentBlockGameTest;
import net.sievert.modularmobai.gametest.tests.AgentCrowdGameTest;
import net.sievert.modularmobai.gametest.tests.AgentCrowdedFightGameTest;
import net.sievert.modularmobai.gametest.tests.AgentDrawnWeaponGameTest;
import net.sievert.modularmobai.gametest.tests.AgentEnemyOrderGameTest;
import net.sievert.modularmobai.gametest.tests.AgentLeagueGameTest;
import net.sievert.modularmobai.gametest.tests.AgentMeleeGameTest;
import net.sievert.modularmobai.gametest.tests.AgentPerceptionGameTest;
import net.sievert.modularmobai.gametest.tests.AgentTeacherGameTest;
import net.sievert.modularmobai.gametest.tests.AgentVindicatorGameTest;
import net.sievert.modularmobai.gametest.tests.AgentVindicatorTerrainGameTest;
import net.sievert.modularmobai.gametest.tests.FightSetupGameTest;
import net.sievert.modularmobai.gametest.tests.PlayGameTest;
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

            // The same ground, against a different opponent in every fight.
            case "league" -> new Class<?>[] {AgentLeagueGameTest.class};

            // The same ground and the same fight, fought with nobody standing about it and with one, three and nine, and
            // watched tick by tick. A suite of its own and not a class in the league's, which is what a run runs.
            case "crowd" -> new Class<?>[] {AgentCrowdedFightGameTest.class};

            // The fifty thousand arena baseline, with no agent in it.
            case "baseline" -> new Class<?>[] {VillagerVindicatorGameTest.class};

            // The agent's body against a player's rules, one rule at a time: no fights, a minute or so all told. One class
            // per concern, all of them sharing tests/Mechanics, and what its view is allowed to hold is the same kind of
            // rule, so the crowd tests run here too. Every class has to be named here or its tests are silently not run,
            // which is why the suite prints how many it found.
            case "mechanics" -> new Class<?>[] {AgentDrawnWeaponGameTest.class, AgentMeleeGameTest.class,
                    AgentBlockGameTest.class, AgentPerceptionGameTest.class, AgentTeacherGameTest.class,
                    AgentCrowdGameTest.class, AgentEnemyOrderGameTest.class, FightSetupGameTest.class};

            // The agent in a real game: networks by name, /mmai, sides and the loadouts that keep a bow firing.
            case "play" -> new Class<?>[] {PlayGameTest.class};

            // No fights either: generates the terrain library the terrain suite reads its sites from.
            case "library" -> new Class<?>[] {TerrainLibraryGameTest.class};

            default -> new Class<?>[] {AgentVindicatorGameTest.class};
        };

        return ModularMobAiTestFunction.getTestsFrom(holders);
    }
}
