package net.sievert.modularmobai.gametest.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.entity.animal.Bee;

@Mixin(Bee.class)
public abstract class BeeMixin {

    // A bee stings once and then dies of it: its own aiStep rolls for death every five ticks after it has stung, and over a
    // minute it takes that roll about three times in four, so most league fights against a bee would have been handed to the
    // agent by a bee that killed itself. Stinging also puts its attack goal to sleep for good, so the rest of the fight is a
    // bee fleeing rather than a bee fighting, and a rating of that says nothing about either side.
    //
    // Here a bee never counts as having stung. Both of those follow from the one flag, so it goes on stinging for as long as
    // it lives and lives for as long as the agent lets it, which is the fight a rating is meant to be for. Everything else
    // about the bee is its own: its damage, its poison, its speed and how it flies.
    @Inject(method = "hasStung", at = @At("HEAD"), cancellable = true)
    private void modular_mob_ai$neverDiesOfItsOwnSting(CallbackInfoReturnable<Boolean> cir) {

        cir.setReturnValue(false);
    }
}
