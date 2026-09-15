package net.sievert.modularmobai.gametest.tests;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.sievert.modularmobai.gametest.GameTestGroup;
import net.sievert.modularmobai.gametest.terrain.TerrainLibrary;

/**
 * Builds the terrain library: no fights, one test that stays open until every site of the library has been generated,
 * checked and written, see {@link TerrainLibrary}. The server ticks the building along; this only says when it is over.
 */
@GameTestGroup
public class TerrainLibraryGameTest {

    /** Only there to catch a build that has stopped getting anywhere: thousands of sites take minutes, not days. */
    private static final int TIMEOUT_TICKS = 50_000_000;

    @GameTest(template = "arena", timeoutTicks = TIMEOUT_TICKS)
    public static void buildTerrainLibrary(GameTestHelper helper) {

        helper.succeedWhen(() -> {

            if (!TerrainLibrary.done()) {

                throw new GameTestAssertException("Still generating");
            }
        });
    }
}
