package net.sievert.modularmobai.gametest.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Slime;

/**
 * How a slime or a magma cube hurts what it touches, which it keeps to itself. A slime hurts a player when the player
 * walks into it and an iron golem when the golem does, and nothing else at all: a slime would chase the agent round a
 * league fight for the whole minute and never land a thing. The league has it touch the agent the way the game has it
 * touch a player, through these, see {@link net.sievert.modularmobai.gametest.league.Roster}. Everything else about the
 * hit is still the slime's own: its size, its damage, its reach and whether it can see its target.
 */
@Mixin(Slime.class)
public interface SlimeInvoker {

    @Invoker("isDealsDamage")
    boolean modular_mob_ai$isDealsDamage();

    @Invoker("dealDamage")
    void modular_mob_ai$dealDamage(LivingEntity target);
}
