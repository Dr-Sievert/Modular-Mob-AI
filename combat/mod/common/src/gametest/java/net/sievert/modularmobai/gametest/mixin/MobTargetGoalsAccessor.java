package net.sievert.modularmobai.gametest.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;

/**
 * A mob's own target goals, which it keeps to itself, so that a test can ask <b>which</b> of them handed it its target.
 *
 * <p>There for one question that had been open in findings.md for weeks: a vanilla mob does sometimes go after an agent with
 * nothing having touched it and no team anywhere, and "what does it was not found". Guessing from the outside cannot settle
 * that — the answer is in the goal selector, which knows exactly which goal is running — so
 * {@code PlayGameTest.aCrowdIsFoughtWhicheverWayRoundItWasSpawned} reads it and writes the name into the log the first time a
 * body comes for the agent. Read only, and nothing is ever asserted about it: what goals a vanilla mob carries is vanilla's
 * business, and a suite that pinned them would fail on the next update for no fault of this mod's.
 */
@Mixin(Mob.class)
public interface MobTargetGoalsAccessor {

    @Accessor("targetSelector")
    GoalSelector modular_mob_ai$targetSelector();
}
