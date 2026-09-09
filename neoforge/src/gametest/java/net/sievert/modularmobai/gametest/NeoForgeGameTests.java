package net.sievert.modularmobai.gametest;

import net.sievert.modularmobai.Constants;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.TestFunction;
import net.neoforged.neoforge.gametest.GameTestHolder;

import java.util.Collection;

@GameTestHolder(Constants.MOD_ID)
public class NeoForgeGameTests {

    @GameTestGenerator
    public static Collection<TestFunction> generateTests() {

        return ModularMobAiGameTests.generateTests();
    }
}
