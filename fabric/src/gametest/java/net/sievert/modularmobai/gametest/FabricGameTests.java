package net.sievert.modularmobai.gametest;

import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.TestFunction;

import java.util.Collection;

public class FabricGameTests {

    @GameTestGenerator
    public static Collection<TestFunction> generateTests() {

        return ModularMobAiGameTests.generateTests();
    }
}
