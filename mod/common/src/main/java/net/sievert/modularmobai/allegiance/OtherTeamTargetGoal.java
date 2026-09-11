package net.sievert.modularmobai.allegiance;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;

/**
 * Sends a vanilla mob on a team after the nearest member of another team it can see, whatever that is.
 *
 * <p>Vanilla already keeps a mob off its allies: every target goal asks TargetingConditions, which refuses anything the
 * mob is allied to, and a target that joins its team is dropped. What vanilla has no goal for is going after a member of
 * another team when that is not a player, a villager or a golem: a zombie on the red team walks straight past a
 * vindicator on the blue one, and ignores an agent altogether. This is that goal. It is given to every mob that finds
 * its way with a path (see MobMixin), and it does nothing at all while the mob is on no team, which is every mob in
 * every training fight: it does not so much as draw a random number then, so no fight plays out differently for it.
 *
 * <p>It only chooses the target. Going after it is up to the mob's own attack goals, the zombie's melee, the skeleton's
 * bow, the creeper's fuse, so a mob with none, a cow on a team, picks a target and does nothing about it. Mobs that
 * vanilla drives with its newer Brain rather than with goals, piglins, hoglins, wardens, villagers and a few others,
 * keep their own rules. It sits at priority two, beside the goal that sends a mob after players, so a mob that has been
 * hit still turns on whoever hit it first.
 */
public final class OtherTeamTargetGoal extends NearestAttackableTargetGoal<LivingEntity> {

    /** Beside vanilla's own goal for players, and after the one for whoever hit the mob. */
    public static final int PRIORITY = 2;

    public OtherTeamTargetGoal(Mob mob) {

        super(mob, LivingEntity.class, true, target -> Allegiance.opposed(mob, target));
    }

    @Override
    public boolean canUse() {

        return this.mob.getTeam() != null && super.canUse();
    }

    /** A mob taken off its team stops going after the other side, as vanilla's own goals stop when a target joins it. */
    @Override
    public boolean canContinueToUse() {

        return this.mob.getTeam() != null && super.canContinueToUse();
    }
}
