package net.sievert.modularmobai;


import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(Constants.MOD_ID)
public class ModularMobAiMod {

    public ModularMobAiMod(IEventBus eventBus) {

        CommonClass.init();
    }
}