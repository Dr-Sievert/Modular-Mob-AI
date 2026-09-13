package net.sievert.modularmobai.command;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import org.jetbrains.annotations.Nullable;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;
import net.sievert.modularmobai.Config;
import net.sievert.modularmobai.allegiance.Allegiance;
import net.sievert.modularmobai.arena.Loadout;
import net.sievert.modularmobai.arena.Loadouts;
import net.sievert.modularmobai.brain.Brain;
import net.sievert.modularmobai.brain.Brains;
import net.sievert.modularmobai.brain.Models;
import net.sievert.modularmobai.entity.ModEntities;
import net.sievert.modularmobai.entity.agent.AgentMob;

/**
 * {@code /mmai}: agents in a real game, from the chat or a command block.
 *
 * <pre>
 *   /mmai spawn [loadout] [brain] [pos]     an agent where you stand, facing where you face
 *   /mmai loadout TARGETS LOADOUT           arm agents, or any mob, with a loadout
 *   /mmai brain TARGETS BRAIN               give agents a brain of their own, or default to hand them back
 *   /mmai ally TARGETS TARGETS              put them all on one side
 *   /mmai enemy TARGETS TARGETS             put the first on one side and the second on another
 *   /mmai info [TARGETS]                    what each agent runs on, carries and sides with; every agent without targets
 *   /mmai models                            the networks the game can find, and what agents default to
 *   /mmai loadouts                          every loadout by name
 * </pre>
 *
 * A brain is anything Brains#named takes: scripted, best, a network's name as published under models\, or a quoted path
 * to a weight file. Sides are vanilla teams, see {@link Allegiance}; {@code /team} works on them as well, and this only
 * saves making and joining them by hand. Everything here needs the permission a {@code /summon} does.
 */
public final class AgentCommands {

    private AgentCommands() {}

    /** What hands an agent back to whatever the game's default brain is, rather than naming a brain of its own. */
    public static final String DEFAULT_BRAIN = "default";

    /** More lines than this in one answer would only scroll the ones that matter off the screen. */
    private static final int MAX_LINES = 20;

    private static final SuggestionProvider<CommandSourceStack> LOADOUTS =
            (context, builder) -> SharedSuggestionProvider.suggest(Loadouts.names(), builder);

    private static final SuggestionProvider<CommandSourceStack> BRAINS = (context, builder) -> {

        List<String> names = new ArrayList<>(List.of(DEFAULT_BRAIN));
        names.addAll(Brains.known());
        return SharedSuggestionProvider.suggest(names, builder);
    };

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {

        dispatcher.register(Commands.literal("mmai")
                .requires(source -> source.hasPermission(2))

                .then(Commands.literal("spawn")
                        .executes(context -> spawn(context.getSource(), null, null, context.getSource().getPosition()))
                        .then(Commands.argument("loadout", StringArgumentType.word()).suggests(LOADOUTS)
                                .executes(context -> spawn(context.getSource(), string(context, "loadout"), null,
                                        context.getSource().getPosition()))
                                .then(Commands.argument("brain", StringArgumentType.string()).suggests(BRAINS)
                                        .executes(context -> spawn(context.getSource(), string(context, "loadout"),
                                                string(context, "brain"), context.getSource().getPosition()))
                                        .then(Commands.argument("pos", Vec3Argument.vec3())
                                                .executes(context -> spawn(context.getSource(), string(context, "loadout"),
                                                        string(context, "brain"), Vec3Argument.getVec3(context, "pos")))))))

                .then(Commands.literal("loadout")
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .then(Commands.argument("loadout", StringArgumentType.word()).suggests(LOADOUTS)
                                        .executes(context -> loadout(context.getSource(),
                                                EntityArgument.getEntities(context, "targets"), string(context, "loadout"))))))

                .then(Commands.literal("brain")
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .then(Commands.argument("brain", StringArgumentType.string()).suggests(BRAINS)
                                        .executes(context -> brain(context.getSource(),
                                                EntityArgument.getEntities(context, "targets"), string(context, "brain"))))))

                .then(Commands.literal("ally")
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .then(Commands.argument("others", EntityArgument.entities())
                                        .executes(context -> ally(context.getSource(),
                                                EntityArgument.getEntities(context, "targets"),
                                                EntityArgument.getEntities(context, "others"))))))

                .then(Commands.literal("enemy")
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .then(Commands.argument("others", EntityArgument.entities())
                                        .executes(context -> enemy(context.getSource(),
                                                EntityArgument.getEntities(context, "targets"),
                                                EntityArgument.getEntities(context, "others"))))))

                .then(Commands.literal("info")
                        .executes(context -> info(context.getSource(), context.getSource().getLevel()
                                .getEntities(EntityTypeTest.forClass(AgentMob.class), agent -> true)))
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .executes(context -> info(context.getSource(), EntityArgument.getEntities(context, "targets")))))

                .then(Commands.literal("models")
                        .executes(context -> models(context.getSource())))

                .then(Commands.literal("loadouts")
                        .executes(context -> loadouts(context.getSource()))));
    }

    // ---------------------------------------------------------------------------------------------------------------

    /**
     * A new agent in the world, the way an egg or /summon makes one, facing the way whoever ran the command faces. It
     * carries the loadout named, or the config's; it runs on the brain named, which it keeps through saving, or on the
     * game's default when none is.
     */
    private static int spawn(CommandSourceStack source, @Nullable String loadoutName, @Nullable String brainName, Vec3 pos)
            throws CommandSyntaxException {

        ServerLevel level = source.getLevel();
        Loadout loadout = loadoutName == null ? null : loadout(loadoutName, level);
        AgentMob agent = ModEntities.agentMob().create(level);

        if (agent == null) {

            throw failure("The agent could not be created");
        }

        float yaw = source.getRotation().y;

        agent.moveTo(pos.x, pos.y, pos.z, yaw, 0.0F);
        agent.setYHeadRot(yaw);
        agent.setYBodyRot(yaw);

        // Armed before finalizeSpawn, which arms an agent that arrives with nothing with the config's loadout.
        if (loadout != null) {

            agent.equip(loadout);
        }

        agent.finalizeSpawn(level, level.getCurrentDifficultyAt(agent.blockPosition()), MobSpawnType.COMMAND, null);

        if (brainName != null) {

            setBrain(agent, brainName);
        }

        if (!level.tryAddFreshEntityWithPassengers(agent)) {

            throw failure("The agent could not be added to the world");
        }

        source.sendSuccess(() -> Component.literal("Spawned an agent with " + (agent.loadoutName() == null ? "nothing"
                : agent.loadoutName()) + ", running on " + brainOf(agent)), true);
        return 1;
    }

    /** Agents get the whole hotbar; any other mob takes the first item in its main hand and the off hand as it is. */
    private static int loadout(CommandSourceStack source, Collection<? extends Entity> targets, String name)
            throws CommandSyntaxException {

        Loadout loadout = loadout(name, source.getLevel());
        int armed = 0;

        for (Entity target : targets) {

            if (target instanceof AgentMob agent) {

                agent.equip(loadout);
                armed++;
            }

            // Players keep their own inventory: a loadout replaces what a fighter holds, and a player's is theirs.
            else if (target instanceof Mob mob) {

                loadout.equip(mob);
                armed++;
            }
        }

        if (armed == 0) {

            throw failure("None of those is an agent or a mob to arm");
        }

        int count = armed;
        source.sendSuccess(() -> Component.literal("Armed " + count + " with " + loadout.name()), true);
        return armed;
    }

    private static int brain(CommandSourceStack source, Collection<? extends Entity> targets, String name)
            throws CommandSyntaxException {

        List<AgentMob> agents = agents(targets);

        if (agents.isEmpty()) {

            throw failure("None of those is an agent");
        }

        for (AgentMob agent : agents) {

            setBrain(agent, name);
        }

        AgentMob first = agents.get(0);
        source.sendSuccess(() -> Component.literal(agents.size() + (agents.size() == 1 ? " agent runs" : " agents run")
                + " on " + brainOf(first)), true);
        return agents.size();
    }

    private static int ally(CommandSourceStack source, Collection<? extends Entity> targets, Collection<? extends Entity> others)
            throws CommandSyntaxException {

        List<Entity> everyone = new ArrayList<>(targets);

        for (Entity other : others) {

            if (!everyone.contains(other)) {

                everyone.add(other);
            }
        }

        PlayerTeam team = Allegiance.ally(everyone);
        source.sendSuccess(() -> Component.literal("Put " + everyone.size() + " on team " + team.getName()), true);
        return everyone.size();
    }

    private static int enemy(CommandSourceStack source, Collection<? extends Entity> targets, Collection<? extends Entity> others)
            throws CommandSyntaxException {

        List<PlayerTeam> teams;

        try {

            teams = Allegiance.enemy(targets, others);
        }

        catch (IllegalArgumentException exception) {

            throw failure(exception.getMessage());
        }

        source.sendSuccess(() -> Component.literal("Set " + targets.size() + " on team " + teams.get(0).getName()
                + " against " + others.size() + " on team " + teams.get(1).getName()), true);
        return targets.size() + others.size();
    }

    private static int info(CommandSourceStack source, Collection<? extends Entity> targets) {

        if (targets.isEmpty()) {

            source.sendSuccess(() -> Component.literal("No agents"), false);
            return 0;
        }

        int shown = 0;

        for (Entity target : targets) {

            if (shown++ == MAX_LINES) {

                int more = targets.size() - MAX_LINES;
                source.sendSuccess(() -> Component.literal("... and " + more + " more"), false);
                break;
            }

            StringBuilder line = new StringBuilder(target.getName().getString())
                    .append(String.format(Locale.ROOT, " at %.1f %.1f %.1f", target.getX(), target.getY(), target.getZ()));

            if (target instanceof AgentMob agent) {

                line.append(": ").append(brainOf(agent))
                        .append(agent.brainName() == null ? " (the default)" : " (its own)")
                        .append(", carries ").append(agent.loadoutName() == null ? "what it was given" : agent.loadoutName())
                        .append(String.format(Locale.ROOT, ", health %.1f", agent.getHealth()));
            }

            Team team = target.getTeam();
            line.append(team == null ? ", no team" : ", team " + team.getName());

            source.sendSuccess(() -> Component.literal(line.toString()), false);
        }

        return targets.size();
    }

    private static int models(CommandSourceStack source) {

        String best = Models.best();

        source.sendSuccess(() -> Component.literal("Brains: " + String.join(", ", Brains.known())), false);
        source.sendSuccess(() -> Component.literal("In the mod's jar: " + (Models.bundled().isEmpty() ? "none"
                : String.join(", ", Models.bundled())) + (best == null ? "" : "; best is " + best
                + (Models.summary(best) == null ? "" : ", " + Models.summary(best)))), false);
        source.sendSuccess(() -> Component.literal("In folders: " + (Models.onDiskNames().isEmpty() ? "none"
                : String.join(", ", Models.onDiskNames())) + ", from " + Config.modelFolders()), false);

        String fallback;

        try {

            fallback = Brains.describe(Brains.worldDefault());
        }

        catch (RuntimeException exception) {

            fallback = "nothing it can load: " + exception.getMessage();
        }

        String described = fallback;
        source.sendSuccess(() -> Component.literal("Agents with no brain of their own run on " + described), false);
        return 1;
    }

    private static int loadouts(CommandSourceStack source) {

        source.sendSuccess(() -> Component.literal("Loadouts: " + String.join(", ", Loadouts.names())), false);
        return Loadouts.names().size();
    }

    // ---------------------------------------------------------------------------------------------------------------

    private static Loadout loadout(String name, ServerLevel level) throws CommandSyntaxException {

        return Loadouts.byName(name, level.registryAccess()).orElseThrow(() ->
                failure("There is no loadout '" + name + "'. There are: " + String.join(", ", Loadouts.names())));
    }

    /** A brain by name, or back to the default; anything that leads nowhere is refused before the agent changes. */
    private static void setBrain(AgentMob agent, String name) throws CommandSyntaxException {

        try {

            agent.setBrainName(DEFAULT_BRAIN.equalsIgnoreCase(name.trim()) ? null : name);
        }

        catch (RuntimeException exception) {

            throw failure(exception.getMessage());
        }
    }

    /** What the agent runs on now, or will from its next tick. */
    private static String brainOf(AgentMob agent) {

        Brain brain = agent.brain().brain();

        try {

            return Brains.describe(brain != null ? brain : Brains.forAgent(agent));
        }

        catch (RuntimeException exception) {

            return "nothing it can load: " + exception.getMessage();
        }
    }

    private static List<AgentMob> agents(Collection<? extends Entity> targets) {

        List<AgentMob> agents = new ArrayList<>();

        for (Entity target : targets) {

            if (target instanceof AgentMob agent) {

                agents.add(agent);
            }
        }

        return agents;
    }

    private static String string(CommandContext<CommandSourceStack> context, String name) {

        return StringArgumentType.getString(context, name);
    }

    private static CommandSyntaxException failure(String message) {

        return new SimpleCommandExceptionType(Component.literal(message)).create();
    }
}
