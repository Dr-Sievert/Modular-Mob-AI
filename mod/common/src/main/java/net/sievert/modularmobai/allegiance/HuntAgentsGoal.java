package net.sievert.modularmobai.allegiance;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
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

    /**
     * How long a piglin's anger at the agent lasts once this goal picks it. A piglin keeps only a target it is angry at and
     * lets any other go on the next tick, so handing it the target without the anger is handing it nothing; the league found
     * that out first and does the same thing, see its Roster. Half a minute, and the goal comes round again long before that.
     */
    private static final int ANGER_TICKS = 600;

    public HuntAgentsGoal(Mob mob) {

        super(mob, AgentMob.class, true, agent -> agent instanceof AgentMob playable && !playable.isTraining());
    }

    /**
     * Takes the agent as the target, and for a mob that thinks with a brain rather than with goals, puts it where that mob
     * actually looks for one.
     *
     * <p>This is the whole of what the audit of "how does each hostile acquire a player" found, and it is why this is one goal
     * and not a mixin per mob:
     *
     * <ul>
     *   <li><b>Goals</b> — the great majority of the roster, zombies to pillagers — acquire a player through a
     *       {@code NearestAttackableTargetGoal<Player>} and act on {@code Mob#getTarget}. This class <em>is</em> that goal with
     *       the agent in the player's place, so they are covered by existing beside vanilla's own.</li>
     *   <li><b>Being hurt</b> is already type blind on both sides: {@code HurtByTargetGoal} and a brain's {@code HURT_BY} take
     *       whatever hit them, so anything the agent strikes fights back, and a goal mob's alert brings its own kind with it.
     *       Nothing was needed for that at all.</li>
     *   <li><b>Brains</b> — the piglins, the hoglins, the zoglin, the breeze, the warden — read
     *       {@code MemoryModuleType.ATTACK_TARGET}, and the memories vanilla fills from a sensor are <b>typed to
     *       {@code Player}</b>: {@code NEAREST_VISIBLE_ATTACKABLE_PLAYER} is a {@code MemoryModuleType<Player>} filled from the
     *       level's player list. An agent cannot be put in one without every behaviour that reads it back as a player failing,
     *       so there is no "make the sensor see the agent" to be had. What there is, and what this does, is to write the target
     *       the brain reads. The goal selector ticks for a brain mob exactly as for any other, so one goal still covers both
     *       kinds of mind.</li>
     * </ul>
     *
     * <p><b>The warden is the exception and stays one.</b> It picks what to fight by how angry it is rather than by seeing
     * anything, and its anger drains, so a target handed to it is dropped again; it has to be angered and angered again, which
     * is a thing to do to an opponent in an arena and not a thing a mod should do to a player's world on its own. A warden left
     * alone will therefore ignore an agent until something wakes it, which is what it does to a player standing still.
     */
    @Override
    public void start() {

        super.start();

        LivingEntity taken = this.target;
        Brain<?> brain = this.mob.getBrain();

        if (taken == null || !brain.checkMemory(MemoryModuleType.ATTACK_TARGET, MemoryStatus.REGISTERED)) {

            return;
        }

        // A piglin decides a target it is not angry at is not worth it, and drops it on the next tick.
        if (this.mob instanceof AbstractPiglin) {

            brain.setMemoryWithExpiry(MemoryModuleType.ANGRY_AT, taken.getUUID(), ANGER_TICKS);
        }

        brain.eraseMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
        brain.setMemory(MemoryModuleType.ATTACK_TARGET, taken);
    }
}
