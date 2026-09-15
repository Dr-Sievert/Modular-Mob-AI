package net.sievert.modularmobai.allegiance;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import net.sievert.modularmobai.entity.agent.AgentMob;

/**
 * Who fights whom: vanilla's scoreboard teams, and the few rules vanilla leaves out.
 *
 * <p>Sides are ordinary teams, the ones {@code /team add} and {@code /team join} make, and allies are what vanilla's own
 * {@link Entity#isAlliedTo} says they are: the same team, a tamed animal and its owner's side, illagers among
 * themselves while none of them has a team. Nothing here keeps a list of its own, so a team set up by hand, by a
 * command block, by {@code /mmai ally} or by a game test through this class is the same thing, and vanilla's targeting,
 * which already never picks an ally, keeps working as it always has. What this adds is what vanilla lacks:
 *
 * <ul>
 *   <li>who the agent counts as an enemy, {@link #isEnemy}: never an ally, always a member of another team, and
 *       otherwise what it always fought. That decides what takes a slot in its view, so it is the enemy the
 *       observation describes and the one both the network and the scripted fighter go after;
 *   <li>whether one of them is coming for the other, {@link #goesFor}: its own target, and an agent always, since an agent
 *       fights from a network and keeps no target. The enemy slots, the critic and the league all ask this one;
 *   <li>vanilla mobs on a team go after members of other teams, whatever they are, see {@link OtherTeamTargetGoal},
 *       where vanilla would only ever send them after players, villagers and golems;
 *   <li>friendly fire: a team with it off keeps the agent from hurting its own side and its own side from hurting it,
 *       as vanilla does for players and nobody else, see LivingEntityMixin;
 *   <li>teams of the mod's own for when nobody named one, {@link #newTeam}: numbered, coloured, friendly fire off.
 * </ul>
 *
 * <p>For game tests and the arenas, a 2v1 is two calls: {@code side(agentA, agentB)} and {@code side(vindicator)}, then
 * {@link #disband} on both once the fight is over. Teams live on the server's scoreboard and are saved with the world,
 * so an arena that makes them has to take them down again, or a training run leaves thousands behind.
 */
public final class Allegiance {

    private Allegiance() {}

    /** The start of the name of every team this class makes, followed by a number. */
    public static final String TEAM_PREFIX = "mmai_";

    /** Each new team takes the next of these, so the sides on screen, their name tags and their glow, tell apart. */
    private static final ChatFormatting[] COLOURS = {ChatFormatting.RED, ChatFormatting.BLUE, ChatFormatting.GREEN,
            ChatFormatting.GOLD, ChatFormatting.AQUA, ChatFormatting.LIGHT_PURPLE, ChatFormatting.YELLOW,
            ChatFormatting.DARK_AQUA, ChatFormatting.DARK_RED, ChatFormatting.DARK_GREEN};

    // ---------------------------------------------------------------------------------------------------------------
    // Who is on whose side
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Whether the two are on the same side, by vanilla's own rule asked both ways round. Vanilla's rule is not quite
     * symmetric: a tamed wolf counts its owner's allies as its own, but they do not count the wolf.
     */
    public static boolean allied(Entity a, Entity b) {

        return a == b || a.isAlliedTo(b) || b.isAlliedTo(a);
    }

    /** Whether both are on teams, and the teams are not allied: two sides somebody deliberately set against each other. */
    public static boolean opposed(Entity a, Entity b) {

        return a.getTeam() != null && b.getTeam() != null && !allied(a, b);
    }

    /**
     * Whether the agent counts this one as an enemy, which is what gives it a slot in the agent's view.
     *
     * <ol>
     *   <li>Never an ally, whatever it is: a zombie on the agent's team is a friend.
     *   <li>Never what vanilla says cannot be fought: a player in creative or spectator mode, any player on peaceful, and
     *       anything invulnerable, as no vanilla mob would pick them either.
     *   <li>Always a member of a team opposed to the agent's, whatever it is: a cow on the other side is an enemy.
     *   <li>Otherwise what the agent always fought: monsters, players, other agents, and anything that has chosen it as
     *       its target. Animals and villagers never are, so they never push a real threat out of a slot or teach the
     *       agent to square up to a sheep.
     * </ol>
     *
     * With no teams anywhere, which is every training fight, this is exactly the rule the networks were trained with.
     */
    public static boolean isEnemy(LivingEntity self, LivingEntity other) {

        if (allied(self, other) || !self.canAttack(other)) {

            return false;
        }

        return opposed(self, other)
                || other instanceof Enemy
                || other instanceof Player
                || other instanceof AgentMob
                || (other instanceof Mob mob && mob.getTarget() == self);
    }

    /**
     * Whether that one is coming for this one right now. Vanilla's own {@code getTarget}, which answers for a mob that thinks
     * with goals and for one that thinks with a brain alike, since a brain's memory of its target is what that mob's
     * {@code getTarget} reads. Always true of an agent, which fights from a network and holds no target at all — the league
     * counts one as having gone for the agent from its first tick for the same reason.
     *
     * <p>One rule in one place because three things ask it: an enemy slot's {@code ENEMY_TARGETS_ME}, which is what the
     * network reads; the critic's {@code FightFacts#WENT_FOR}; and the league's "went for" column. An observation and a
     * privileged float disagreeing about whether the fight had started would be a bug nobody could see.
     *
     * <p>Asked of something already counted an enemy, {@link #isEnemy}. It says nothing about sides itself: an ally that has
     * somehow taken the agent as its target would answer yes.
     */
    public static boolean goesFor(LivingEntity other, LivingEntity self) {

        return other instanceof AgentMob || other instanceof Mob mob && mob.getTarget() == self;
    }

    /**
     * Whether a team keeps this attacker from hurting this victim, the way vanilla keeps a player from hurting another on
     * its team while that team has friendly fire off. Vanilla only ever asks that between two players; this asks it
     * whenever an agent is either one, so allies set up with {@code /mmai ally} cannot hurt each other with a stray
     * swing or arrow, and a player cannot hurt their own agent.
     */
    public static boolean sparedByFriendlyFire(LivingEntity victim, @Nullable Entity attacker) {

        if (attacker == null || attacker == victim || !(attacker instanceof AgentMob || victim instanceof AgentMob)) {

            return false;
        }

        Team team = attacker.getTeam();
        return team != null && team.isAlliedTo(victim.getTeam()) && !team.isAllowFriendlyFire();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Making sides
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * A fresh team of the mod's own: the first free name of {@code mmai_1}, {@code mmai_2} and on, a colour of its own,
     * and friendly fire off, since the point of putting two on one side is that they do not hurt each other.
     */
    public static PlayerTeam newTeam(Scoreboard scoreboard) {

        int number = 1;

        while (scoreboard.getPlayerTeam(TEAM_PREFIX + number) != null) {

            number++;
        }

        PlayerTeam team = scoreboard.addPlayerTeam(TEAM_PREFIX + number);
        team.setColor(COLOURS[(number - 1) % COLOURS.length]);
        team.setAllowFriendlyFire(false);
        return team;
    }

    /** Everyone given on one new team of their own, off whatever teams they were on. */
    public static PlayerTeam side(Entity... members) {

        return side(Arrays.asList(members));
    }

    public static PlayerTeam side(Collection<? extends Entity> members) {

        if (members.isEmpty()) {

            throw new IllegalArgumentException("A side needs someone on it");
        }

        PlayerTeam team = newTeam(members.iterator().next().level().getScoreboard());

        for (Entity member : members) {

            join(member, team);
        }

        return team;
    }

    /** Puts the entity on the team, taking it off any other first, as {@code /team join} does. */
    public static PlayerTeam join(Entity entity, PlayerTeam team) {

        entity.level().getScoreboard().addPlayerToTeam(entity.getScoreboardName(), team);
        return team;
    }

    /** The same by the team's name, making a plain vanilla team of that name if there is none yet. */
    public static PlayerTeam join(Entity entity, String teamName) {

        Scoreboard scoreboard = entity.level().getScoreboard();
        PlayerTeam team = scoreboard.getPlayerTeam(teamName);

        return join(entity, team != null ? team : scoreboard.addPlayerTeam(teamName));
    }

    /** Takes the entity off its team, if it is on one. */
    public static void leave(Entity entity) {

        Scoreboard scoreboard = entity.level().getScoreboard();

        if (scoreboard.getPlayersTeam(entity.getScoreboardName()) != null) {

            scoreboard.removePlayerFromTeam(entity.getScoreboardName());
        }
    }

    /**
     * Puts everyone given on one side. The side is the team the first of them already on one is on, so allying someone
     * with a player on a team of their own adds them to it; if none of them is on a team, it is a new one.
     */
    public static PlayerTeam ally(Collection<? extends Entity> members) {

        if (members.isEmpty()) {

            throw new IllegalArgumentException("Nobody to ally");
        }

        PlayerTeam team = members.stream().map(Allegiance::teamOf).filter(Objects::nonNull).findFirst()
                .orElseGet(() -> newTeam(members.iterator().next().level().getScoreboard()));

        for (Entity member : members) {

            join(member, team);
        }

        return team;
    }

    /**
     * Sets two groups against each other: every one of the first on one team, every one of the second on another that is
     * not allied to it. Each group keeps a team one of its members is already on where that works, so a player's own team
     * stays theirs, and gets a new one where it does not.
     *
     * @return the first group's team, then the second's
     */
    public static List<PlayerTeam> enemy(Collection<? extends Entity> first, Collection<? extends Entity> second) {

        if (first.isEmpty() || second.isEmpty()) {

            throw new IllegalArgumentException("Enemies need someone on both sides");
        }

        for (Entity entity : first) {

            if (second.contains(entity)) {

                throw new IllegalArgumentException(entity.getScoreboardName() + " cannot be its own enemy");
            }
        }

        Scoreboard scoreboard = first.iterator().next().level().getScoreboard();

        PlayerTeam firstTeam = first.stream().map(Allegiance::teamOf).filter(Objects::nonNull).findFirst()
                .orElseGet(() -> newTeam(scoreboard));

        PlayerTeam secondTeam = second.stream().map(Allegiance::teamOf)
                .filter(found -> found != null && found != firstTeam)
                .findFirst().orElseGet(() -> newTeam(scoreboard));

        for (Entity member : first) {

            join(member, firstTeam);
        }

        for (Entity member : second) {

            join(member, secondTeam);
        }

        List<PlayerTeam> teams = new ArrayList<>(2);
        teams.add(firstTeam);
        teams.add(secondTeam);
        return teams;
    }

    /** Takes a team off the scoreboard, and with it every membership in it. */
    public static void disband(PlayerTeam team) {

        Scoreboard scoreboard = team.getScoreboard();

        if (scoreboard.getPlayerTeam(team.getName()) == team) {

            scoreboard.removePlayerTeam(team);
        }
    }

    @Nullable
    private static PlayerTeam teamOf(Entity entity) {

        return entity.level().getScoreboard().getPlayersTeam(entity.getScoreboardName());
    }
}
