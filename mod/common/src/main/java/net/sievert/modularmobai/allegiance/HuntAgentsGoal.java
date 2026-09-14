package net.sievert.modularmobai.allegiance;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.sievert.modularmobai.entity.agent.AgentMob;

/**
 * Sends a hostile mob after a playable agent the way vanilla sends it after a player.
 *
 * <p>Vanilla's hostiles look for a target among players, villagers, iron golems and turtles, and an agent is none of those.
 * So out in a real game <b>nothing came for an agent at all</b> unless somebody set sides by hand: a player could stand an
 * agent in a field of zombies and every one of them would walk past it. That is what the owner met and reported as "if I
 * spawn the agent after the mobs are already there, it doesn't seem to work" — the agent wandered, because no slot in its
 * view ever read {@code ENEMY_TARGETS_ME}, and a network trained on the league has only ever been shown opponents that come
 * for it. Setting {@code /mmai enemy} afterwards worked, and this is why: a team is what woke {@link OtherTeamTargetGoal}.
 * The evidence, tick by tick, is in {@code PlayGameTest.aCrowdAlreadyStandingIsFoughtWhicheverWayRound} and findings.md.
 *
 * <p>A player-shaped mob a player has to defend is the whole point of the thing, so the fix is to make a monster treat an
 * agent as it treats a player, and nothing more than that:
 *
 * <ul>
 *   <li><b>Only monsters.</b> Given to a mob that is one of vanilla's own {@link net.minecraft.world.entity.monster.Enemy},
 *       which is the marker vanilla's hostiles carry and the same rule {@link Allegiance#isEnemy} reads from the agent's
 *       side. A cow does not start hunting agents because an agent is not a player to a cow either.</li>
 *   <li><b>Only the agent a player meets.</b> A training agent is left alone, {@link AgentMob#isTraining}. An arena hands
 *       out every target it wants — an opponent is provoked on every tick of a league fight, a bystander is unprovoked on
 *       every tick of one — so a goal reaching into that would be a second hand on the same wheel: it would quietly turn a
 *       quarter of the league's fights, the crowded ones, into fights with extra opponents nobody rates and the reward does
 *       not pay for, which is the one fault the league's Bystanders exists to prevent. So every training fight is byte for
 *       byte the fight it was, and what changes is the game.</li>
 *   <li><b>On vanilla's own terms.</b> {@link NearestAttackableTargetGoal} with {@code mustSee}, at the priority vanilla's
 *       player goal sits at, so the reach is the mob's own follow range, the line of sight is the one vanilla's targeting
 *       already asks for, and an ally is refused by {@code TargetingConditions} as it always was. Nothing here says anything
 *       about who wins: the mob's own attack goals do the fighting, so a monster with no attack picks an agent and does
 *       nothing about it.</li>
 * </ul>
 *
 * <p><b>What this does not reproduce.</b> A few hostiles ask more of a player than "is it there": a spider only picks one in
 * the dark, and a mob vanilla drives with a brain rather than with goals — a warden, a piglin, a hoglin — reads none of these
 * goals at all. So a spider will come for an agent in daylight where it would leave a player alone, and a warden still has to
 * be angered. Both are documented rather than special-cased: the first is a mob being keener than vanilla by one condition,
 * the second is vanilla's own machinery and is what {@code Roster} already works around for the league.
 *
 * <p>It also makes the crowd in a real game <b>hostile</b> rather than idle, which is the shape the league did not train: the
 * league's HostilePacks is what was added to close it.
 */
public final class HuntAgentsGoal extends NearestAttackableTargetGoal<AgentMob> {

    /** Beside vanilla's own goal for players, which is where a mob's interest in a player-shaped body belongs. */
    public static final int PRIORITY = 2;

    public HuntAgentsGoal(Mob mob) {

        super(mob, AgentMob.class, true, agent -> agent instanceof AgentMob playable && !playable.isTraining());
    }
}
