package net.sievert.modularmobai.command;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.jetbrains.annotations.Nullable;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
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
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
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
import net.sievert.modularmobai.brain.schema.EnemySlots;
import net.sievert.modularmobai.brain.schema.Species;
import net.sievert.modularmobai.entity.ModEntities;
import net.sievert.modularmobai.entity.agent.AgentMob;

/**
 * {@code /mmai}: agents in a real game, from the chat or a command block.
 *
 * <pre>
 *   /mmai spawn [loadout] [brain] [pos]     an agent where you stand, facing where you face
 *   /mmai loadout TARGETS LOADOUT           arm agents, or any mob, with a loadout
 *   /mmai brain TARGETS BRAIN               give agents a brain of their own, or default to hand them back
 *   /mmai pickup TARGETS on|off             whether they take the items they walk over
 *   /mmai ally TARGETS TARGETS              put them all on one side
 *   /mmai enemy TARGETS TARGETS             put the first on one side and the second on another
 *   /mmai horde MOB COUNT [RADIUS]          that many of one mob in a ring round you, set against every agent near them
 *   /mmai info [TARGETS]                    what each agent runs on, carries, picks up and sides with, and what it last
 *                                           cost to see
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

    /**
     * How far from the caller a horde stands by default, and the most of it there may be. Twenty four blocks is inside the
     * thirty two an agent perceives and far enough to watch the whole thing walk in. Two thousand is the ceiling because that is
     * the size the horde suite measures the cost at, and because a command that can hang a server on a typo is a bad command.
     */
    private static final int HORDE_RADIUS = 24;
    private static final int MOST_OF_A_HORDE = 2_000;

    private static final SimpleCommandExceptionType HORDE_WANTS_A_MOB =
            new SimpleCommandExceptionType(Component.literal("A horde has to be made of mobs, and that is not one"));

    private static final SimpleCommandExceptionType HORDE_FOUND_NO_GROUND =
            new SimpleCommandExceptionType(Component.literal("Nowhere round here to stand a horde on"));

    private static final SuggestionProvider<CommandSourceStack> LOADOUTS =
            (context, builder) -> SharedSuggestionProvider.suggest(Loadouts.names(), builder);

    /**
     * Every entity kind the game knows, for the horde. Vanilla's own are offered by their short name, since that is what
     * anybody types, and a mod's by the whole id; the command takes either. Whether one is a mob at all is settled when one is
     * made rather than guessed from the name, so nothing here has to keep a list of what fights.
     */
    private static final SuggestionProvider<CommandSourceStack> MOBS = (context, builder) -> SharedSuggestionProvider.suggest(
            BuiltInRegistries.ENTITY_TYPE.keySet().stream()
                    .map(id -> "minecraft".equals(id.getNamespace()) ? id.getPath() : id.toString()).sorted().toList(), builder);

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

                .then(Commands.literal("pickup")
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .then(Commands.literal("on")
                                        .executes(context -> pickup(context.getSource(),
                                                EntityArgument.getEntities(context, "targets"), true)))
                                .then(Commands.literal("off")
                                        .executes(context -> pickup(context.getSource(),
                                                EntityArgument.getEntities(context, "targets"), false)))))

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

                .then(Commands.literal("horde")
                        .then(Commands.argument("mob", StringArgumentType.string()).suggests(MOBS)
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, MOST_OF_A_HORDE))
                                        .executes(context -> horde(context.getSource(), string(context, "mob"),
                                                IntegerArgumentType.getInteger(context, "count"), HORDE_RADIUS))
                                        .then(Commands.argument("radius", IntegerArgumentType.integer(2, HORDE_RADIUS * 4))
                                                .executes(context -> horde(context.getSource(), string(context, "mob"),
                                                        IntegerArgumentType.getInteger(context, "count"),
                                                        IntegerArgumentType.getInteger(context, "radius")))))))

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

    /**
     * Whether these agents take the items they walk over, kept through saving. The same flag the button in an agent's
     * inventory screen flips, and the world's default for a new one is the config's {@code pickup}.
     *
     * <p>A training agent would refuse it anyway (see {@code AgentMob#setPicksUpItems}), but none can be reached from here:
     * an arena's agent is never in a world a command runs in.
     */
    private static int pickup(CommandSourceStack source, Collection<? extends Entity> targets, boolean picksUp)
            throws CommandSyntaxException {

        List<AgentMob> agents = agents(targets);

        if (agents.isEmpty()) {

            throw failure("None of those is an agent");
        }

        for (AgentMob agent : agents) {

            agent.setPicksUpItems(picksUp);
        }

        source.sendSuccess(() -> Component.literal(agents.size() + (agents.size() == 1 ? " agent " : " agents ")
                + (picksUp ? "now pick" : "no longer pick") + (agents.size() == 1 ? "s" : "") + " items up"), true);

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

    /**
     * A horde: that many of one mob, standing in a ring round whoever ran the command, on the ground, with their own minds, and
     * set against every agent in sight of them as {@code /mmai enemy} would.
     *
     * <p>There to make the thing the agent is being built for reachable by hand. A player who wants to see what a network does
     * when it is surrounded should not have to write a hundred {@code /summon}s and a {@code /team join} for each; and the same
     * arrangement is what {@code scripts\test.ps1 -Horde} measures, so what is seen on screen and what is measured are the same
     * fight. The sides are set outright rather than left to the mobs noticing, because a horde that trickles in a few at a time
     * is a different thing from a horde, and the default rules bring them along anyway.
     *
     * <p>Each one is placed on the highest ground at its spot, so a ring on a hillside follows the hill, and any that finds
     * nowhere to stand is not spawned rather than dropped inside rock — the count in the answer is what really stood up.
     */
    private static int horde(CommandSourceStack source, String mobName, int count, int radius) throws CommandSyntaxException {

        ServerLevel level = source.getLevel();
        Vec3 middle = source.getPosition();

        ResourceLocation id = ResourceLocation.tryParse(mobName.contains(":") ? mobName : "minecraft:" + mobName);
        EntityType<?> type = id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);

        if (type == null) {

            throw HORDE_WANTS_A_MOB.create();
        }

        // Made once to find out whether it is a mob at all, rather than trusting the name: a horde of item frames is not a
        // thing this can do anything with, and finding out on the first of a thousand is better than on the last.
        if (!(type.create(level) instanceof Mob probe)) {

            throw HORDE_WANTS_A_MOB.create();
        }

        probe.discard();

        List<Mob> horde = new ArrayList<>(count);
        double spacing = Math.max(1.0D, 2.0D * Math.PI * radius / Math.max(1, count));

        for (int at = 0; at < count; at++) {

            // Round the caller, and out onto further rings once one is full, so a big horde is a crowd and not a stack.
            int perRing = Math.max(1, (int) (2.0D * Math.PI * radius / spacing));
            double ring = radius + (at / perRing) * 2.0D;
            double angle = (at % perRing) * (Math.PI * 2.0D / perRing);

            BlockPos spot = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    BlockPos.containing(middle.x + Math.cos(angle) * ring, middle.y, middle.z + Math.sin(angle) * ring));

            if (!(type.create(level) instanceof Mob mob)) {

                continue;
            }

            mob.moveTo(spot.getX() + 0.5D, spot.getY(), spot.getZ() + 0.5D, (float) Math.toDegrees(angle) + 90.0F, 0.0F);
            mob.finalizeSpawn(level, level.getCurrentDifficultyAt(spot), MobSpawnType.COMMAND, null);
            mob.setPersistenceRequired();

            level.addFreshEntity(mob);
            horde.add(mob);
        }

        if (horde.isEmpty()) {

            throw HORDE_FOUND_NO_GROUND.create();
        }

        // Set against every agent near enough to be in the fight, which is what makes it a horde rather than scenery. An agent
        // already on a team keeps it and the horde goes on another, which is what Allegiance#enemy does.
        double reach = radius * 2.0D;
        List<AgentMob> agents = level.getEntities(EntityTypeTest.forClass(AgentMob.class),
                new AABB(middle, middle).inflate(reach), agent -> agent.isAlive());

        if (!agents.isEmpty()) {

            Allegiance.enemy(agents, horde);
        }

        final int stood = horde.size();
        final int sided = agents.size();

        source.sendSuccess(() -> Component.literal("Stood up " + stood + " " + id.getPath()
                + (sided == 0 ? " and found no agent to set them against" : " against " + sided + " agent"
                + (sided == 1 ? "" : "s"))), true);

        return stood;
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
                        .append(agent.picksUpItems() ? " and picks up" : " and picks nothing up")
                        .append(String.format(Locale.ROOT, ", health %.1f", agent.getHealth()));

                // What it cost this agent to perceive on its last tick, which is the number to look at when a world with a
                // thousand mobs in it starts to feel slow: how many clips through the world it asked for and how long the
                // whole of it took. Beside them, how many bodies are in its fight — coming for it or on a side against it,
                // which is what the observation counts and so not the same as how many it can see. See EnemySlots.
                EnemySlots view = agent.brain().enemySlots();

                line.append(String.format(Locale.ROOT, ", %d in the fight, %d clips in %.0f us", view.inRangeCount(),
                        view.lastClips(), view.lastMicros()));
            }

            Team team = target.getTeam();
            line.append(team == null ? ", no team" : ", team " + team.getName());

            source.sendSuccess(() -> Component.literal(line.toString()), false);
        }

        return targets.size();
    }

    private static int models(CommandSourceStack source) {

        source.sendSuccess(() -> Component.literal("Brains: " + String.join(", ", Brains.known())), false);

        // Which body each one drives, and one best per body: a network only fits the body its layout was written for, so
        // "best" is a different network for a humanoid and for anything else. A network published before the layout was
        // recorded beside the weights says no body, and is no body's best.
        source.sendSuccess(() -> Component.literal("In the mod's jar: " + (Models.bundled().isEmpty() ? "none"
                : Models.bundled().stream().map(name -> name + " (" + (Models.speciesOf(name) == null ? "body unrecorded"
                        : "a " + Models.speciesOf(name) + "'s") + ")").collect(Collectors.joining(", ")))), false);

        for (Species body : Species.ALL) {

            String best = Models.best(body.name());

            if (best != null) {

                source.sendSuccess(() -> Component.literal("The best " + body.name() + " is " + best
                        + (Models.summary(best) == null ? "" : ", " + Models.summary(best))), false);
            }
        }

        source.sendSuccess(() -> Component.literal("In folders: " + (Models.onDiskNames().isEmpty() ? "none"
                : String.join(", ", Models.onDiskNames())) + ", from " + Config.modelFolders()), false);

        // One line per body a player can meet, since what drives an agent with no brain of its own is that body's default.
        for (Species body : Species.ALL) {

            if (body.mob(Species.Mob.Role.WORLD) == null) {

                continue;
            }

            String fallback;

            try {

                fallback = Brains.describe(Brains.worldDefault(body));
            }

            catch (RuntimeException exception) {

                fallback = "nothing it can load: " + exception.getMessage();
            }

            String described = fallback;
            source.sendSuccess(() -> Component.literal(body.name() + " agents with no brain of their own run on "
                    + described), false);
        }

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
