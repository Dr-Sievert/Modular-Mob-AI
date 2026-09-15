package net.sievert.modularmobai.brain;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.jetbrains.annotations.Nullable;

import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.MinecraftServer;
import net.sievert.modularmobai.Config;
import net.sievert.modularmobai.Constants;
import net.sievert.modularmobai.brain.nn.WeightFile;
import net.sievert.modularmobai.brain.nn.WeightSet;
import net.sievert.modularmobai.brain.schema.Species;
import net.sievert.modularmobai.entity.agent.AgentMob;

/**
 * Where brains come from. Each one is built once and shared by every agent it drives.
 *
 * <p>Which brain an agent gets unless something says otherwise is chosen without recompiling:
 *
 * <pre>
 *   -Dmodular_mob_ai.brain=scripted                                   the hand written fighter, needs nothing
 *   -Dmodular_mob_ai.brain=scripted -Dmodular_mob_ai.demonstrations=DIR   the same, writing down what it does
 *   -Dmodular_mob_ai.brain=neural -Dmodular_mob_ai.brain.weights=X    a trained network, most likely action
 *   ... and -Dmodular_mob_ai.demonstrations=DIR                       the same, the scripted fighter labelling each tick
 *   -Dmodular_mob_ai.brain=neural -Dmodular_mob_ai.training.run=DIR   a network being trained; see {@link TrainingRun}
 *   -Dmodular_mob_ai.brain=NAME                                       anything {@link #named} takes, such as a network's name
 * </pre>
 *
 * A run with nothing set falls back to the scripted fighter, which is what keeps the game tests runnable on their own.
 *
 * <p>That is the whole story for the training agent. An agent met in a real game can also carry a brain of its own, by
 * name, which it keeps through saving; one that has none follows the properties above when the game was started with
 * any, and otherwise the config file, whose default is the best network the mod's jar carries. See {@link #forAgent}.
 */
public final class Brains {

    private Brains() {}

    /**
     * Built on first use rather than when this class loads. Loading weights can fail, and doing it during class
     * initialisation would turn "that file is the wrong layout" into an ExceptionInInitializerError thrown from whatever
     * unrelated code happened to touch this class first.
     */
    private static Brain fallback;

    /**
     * What an agent in a real game runs on when it has no brain of its own and the game was started without one, per body:
     * the config's brain is very often {@code best}, and {@code best} is a different network for every body.
     */
    private static final Map<Species, Brain> worldDefaults = new HashMap<>();

    @Nullable
    private static ScriptedBrain scripted;

    private static final Map<Path, NeuralBrain> NETWORKS = new HashMap<>();
    private static final Map<String, NeuralBrain> BUNDLED = new HashMap<>();
    private static final List<Brain> CREATED = new ArrayList<>();

    /** What each brain handed out by name is called, for /mmai info and the log. */
    private static final Map<Brain, String> LABELS = new IdentityHashMap<>();

    /** Names an agent carried that could not be loaded here, each reported once rather than on every agent. */
    private static final Set<String> REPORTED = new HashSet<>();

    public static synchronized Brain defaultBrain() {

        if (fallback == null) {

            fallback = fromProperties();
            CREATED.add(fallback);
        }

        return fallback;
    }

    /** A network loaded from a weight file, most likely action, shared by everything that asks for the same file. */
    public static synchronized NeuralBrain network(Path weights) {

        return NETWORKS.computeIfAbsent(weights.toAbsolutePath().normalize(), path -> {

            try {

                WeightSet loaded = WeightFile.read(path);
                Constants.LOG.info("Loaded {} from iteration {}: {}", path, loaded.iteration(), loaded.topology());

                return created(NeuralBrain.deployed(loaded), path.getFileName() + " from " + path.getParent()
                        + ", iteration " + loaded.iteration());
            }

            catch (IOException exception) {

                throw new UncheckedIOException(exception);
            }
        });
    }

    /** A network the mod's jar carries, most likely action, shared like any other. See {@link Models}. */
    public static synchronized NeuralBrain bundled(String name) {

        NeuralBrain known = BUNDLED.get(name);

        if (known != null) {

            return known;
        }

        try {

            byte[] bytes = Models.bundledBytes(name);

            if (bytes == null) {

                throw new IllegalArgumentException("The mod's jar carries no network called '" + name + "'");
            }

            WeightSet loaded = WeightFile.read(bytes, name + WeightFile.EXTENSION, Models.bundledSource(name));
            Constants.LOG.info("Loaded {} from iteration {}: {}", Models.bundledSource(name), loaded.iteration(), loaded.topology());

            NeuralBrain brain = created(NeuralBrain.deployed(loaded), name + " (in the mod's jar), iteration " + loaded.iteration());
            BUNDLED.put(name, brain);
            return brain;
        }

        catch (IOException exception) {

            throw new UncheckedIOException(exception);
        }
    }

    /** The hand written fighter, one shared by every agent that was given it by name. */
    public static synchronized ScriptedBrain scripted() {

        if (scripted == null) {

            scripted = created(new ScriptedBrain(), "scripted");
        }

        return scripted;
    }

    /**
     * A brain by the name a command, a save, the config or a system property gives it, for the body this process is for.
     * See {@link #named(String, Species)}.
     */
    public static synchronized Brain named(String name) {

        return named(name, Species.trained());
    }

    /**
     * A brain by the name a command, a save, the config or a system property gives it:
     *
     * <pre>
     *   scripted      the hand written fighter
     *   best          the best network the mod's jar carries for that body, by the win rate the repository recorded for it
     *   NAME          a network by name, from a models folder if one has it and otherwise from the jar, see {@link Models}
     *   FILE.mbw      a weight file, absolute or relative to the game directory
     * </pre>
     *
     * Everything that names the same network gets the same brain, so every agent on it shares one forward pass however
     * it was named: {@code best}, the network's own name, and the path of the file all three lead to end up in one batch.
     *
     * <p>The body matters for {@code best} alone, which is a different network for every body: a network only fits the body
     * its layout was written for, so the best one is the best of that body's. Every other name reaches whatever it names,
     * and a set of weights for the wrong body is then refused where every such mistake is, by its schema id.
     *
     * @throws IllegalArgumentException for a name that leads nowhere, saying what there is instead
     * @throws UncheckedIOException     for weights that are there but cannot be read, or were trained on another layout
     */
    public static synchronized Brain named(String name, Species body) {

        String spec = name.trim();
        String lower = spec.toLowerCase(Locale.ROOT);

        if (lower.equals("scripted")) {

            return scripted();
        }

        if (lower.equals("best")) {

            String best = Models.best(body.name());

            if (best == null) {

                throw new IllegalArgumentException("The mod's jar carries no trained network for a " + body.name()
                        + ", so there is no best one for it" + (Models.bundled().isEmpty() ? "" : "; it carries "
                        + describeBundled()));
            }

            spec = best;
        }

        if (lower.endsWith(WeightFile.EXTENSION)) {

            Path file = Config.resolve(spec);

            if (!Files.isRegularFile(file)) {

                throw new IllegalArgumentException("There is no weight file at " + file);
            }

            return network(file);
        }

        if (!Models.validName(spec)) {

            throw new IllegalArgumentException("'" + spec + "' is not a brain: give scripted, best, a network's name or a "
                    + WeightFile.EXTENSION + " file");
        }

        Path onDisk = Models.onDisk(spec);

        if (onDisk != null) {

            return network(onDisk);
        }

        if (Models.bundled().contains(spec)) {

            return bundled(spec);
        }

        throw new IllegalArgumentException("There is no network called '" + spec + "'. There are: " + String.join(", ", known()));
    }

    /** Every name {@link #named} takes without a path, in the order a command offers them. */
    public static List<String> known() {

        TreeSet<String> networks = new TreeSet<>(Models.bundled());
        networks.addAll(Models.onDiskNames());

        List<String> names = new ArrayList<>(List.of("scripted"));

        // best is offered while any body has one, since a command may be aimed at an agent of any of them.
        if (Species.ALL.stream().anyMatch(body -> Models.best(body.name()) != null)) {

            names.add("best");
        }

        names.addAll(networks);
        return names;
    }

    /** What the jar carries and which body each one drives, for a refusal that has to say what there is instead. */
    private static String describeBundled() {

        return Models.bundled().stream()
                .map(name -> Models.speciesOf(name) == null ? name + " (of no body it recorded)"
                        : name + " (a " + Models.speciesOf(name) + "'s)")
                .collect(Collectors.joining(", "));
    }

    /**
     * What an agent runs on when the driver first meets it.
     *
     * <ol>
     *   <li>The brain it was given by name, which it keeps through saving, if that can be loaded here. One that cannot,
     *       a network another game had and this one lacks, falls through to the next, and is reported once.
     *   <li>For the training agent, whatever the game was started with, see {@link #defaultBrain}: the arenas and the
     *       training workers are never touched by the config.
     *   <li>For an agent in a real game, whatever the game was started with too, when it was started with anything;
     *       that is how scripts\play.ps1 drives every agent in a development client with the network it was given.
     *       Otherwise the config's brain, best unless someone changed it.
     * </ol>
     */
    public static synchronized Brain forAgent(AgentMob agent) {

        String own = agent.brainName();

        if (own != null) {

            try {

                return named(own);
            }

            catch (RuntimeException exception) {

                if (REPORTED.add(own)) {

                    Constants.LOG.warn("An agent's brain '{}' cannot be loaded here, so it gets the default instead: {}", own,
                            exception.getMessage());
                }
            }
        }

        return agent.isTraining() ? defaultBrain() : worldDefault(agent.species());
    }

    /**
     * What drives an agent in a real game that has no brain of its own: whatever the game was started with, when that
     * was anything, and otherwise the config's brain. A config naming something that cannot be loaded is reported and
     * leaves the agents to the scripted fighter, rather than leaving them standing still or the game refusing to start.
     *
     * <p>One answer per body, because the config's brain is very often {@code best} and {@code best} is a different network
     * for every body. One answer for all of them would have handed every agent in the world whichever body's network
     * evaluated highest, and the driver would have refused the rest one at a time, mid tick.
     */
    public static synchronized Brain worldDefault(Species body) {

        if (!property("modular_mob_ai.brain", "").isEmpty()) {

            return defaultBrain();
        }

        Brain known = worldDefaults.get(body);

        if (known == null) {

            String configured = Config.brain();

            try {

                known = named(configured, body);
            }

            catch (RuntimeException exception) {

                Constants.LOG.error("The config's brain '{}' cannot be loaded for a {}, so its agents get the scripted "
                        + "fighter: {}", configured, body.name(), exception.getMessage());
                known = scripted();
            }

            worldDefaults.put(body, known);
        }

        return known;
    }

    /**
     * Called by each loader once a server is up: loads what agents in this world run on by default, and says so in the
     * log, so a config naming something that cannot be loaded shows at the start rather than when the first agent turns
     * up, and the log says which network is driving before anyone asks. Game test servers, which are the arenas and
     * every training worker, are left alone: none of this applies there, and a training brain starts when its first
     * agent does.
     */
    public static void serverStarted(MinecraftServer server) {

        if (server instanceof GameTestServer) {

            return;
        }

        // One line per body a player can actually meet in a world. Every other body is spawned by an arena and never meets
        // this, so saying anything about it here would be noise about something no agent in this world will ever be.
        for (Species body : Species.ALL) {

            if (body.mob(Species.Mob.Role.WORLD) == null) {

                continue;
            }

            try {

                Constants.LOG.info("{} agents with no brain of their own run on {}", body.name(),
                        describe(worldDefault(body)));
            }

            catch (RuntimeException exception) {

                Constants.LOG.error("{} agents with no brain of their own have nothing they can run on", body.name(),
                        exception);
            }
        }
    }

    /** What a brain is, for a person: which network and iteration, or the scripted fighter. */
    public static synchronized String describe(@Nullable Brain brain) {

        if (brain == null) {

            return "none yet";
        }

        String label = LABELS.get(brain);

        if (label != null) {

            return label;
        }

        return brain instanceof NeuralBrain network ? network.weights().id() + ", iteration " + network.weights().iteration()
                : brain.getClass().getSimpleName();
    }

    private static <T extends Brain> T created(T brain, String label) {

        CREATED.add(brain);
        LABELS.put(brain, label);
        return brain;
    }

    private static Brain fromProperties() {

        String kind = property("modular_mob_ai.brain", "scripted").toLowerCase(Locale.ROOT);

        return switch (kind) {

            case "scripted" -> {

                // Recording what the scripted fighter does, for a network to learn to copy before training starts.
                String demonstrations = property("modular_mob_ai.demonstrations", "");
                float noise = Float.parseFloat(property("modular_mob_ai.demonstrations.noise", "0"));

                yield demonstrations.isEmpty() ? new ScriptedBrain()
                        : new DemonstrationBrain(new ScriptedBrain(), Path.of(demonstrations), noise);
            }

            case "neural" -> {

                if (TrainingRun.configured()) {

                    yield NeuralBrain.training(TrainingRun.fromProperties());
                }

                String weights = property("modular_mob_ai.brain.weights", "");

                if (weights.isEmpty()) {

                    throw new IllegalArgumentException("A neural brain needs weights: set modular_mob_ai.brain.weights");
                }

                // A copy of the scripted fighter at work, with the scripted fighter saying what it would have done instead.
                String demonstrations = property("modular_mob_ai.demonstrations", "");
                float noise = Float.parseFloat(property("modular_mob_ai.demonstrations.noise", "0"));

                yield demonstrations.isEmpty() ? network(Path.of(weights))
                        : new DemonstrationBrain(network(Path.of(weights)), new ScriptedBrain(), Path.of(demonstrations), noise);
            }

            // Anything else names a brain the way a command does, a network by name or a weight file, so a game can be
            // started with -Dmodular_mob_ai.brain=blast. What names nothing is still a mistake worth stopping for.
            default -> {

                try {

                    yield named(property("modular_mob_ai.brain", ""));
                }

                catch (IllegalArgumentException exception) {

                    throw new IllegalArgumentException("Unknown brain '" + kind + "'; expected scripted, neural or a network: "
                            + exception.getMessage() + ". The remote brain is gone: networks run inside the game now, see "
                            + "docs/architecture.md", exception);
                }
            }
        };
    }

    /**
     * Lets every brain flush what it has, when the game is done with them. A training worker that never got to drive an
     * agent still has to tell the training side it is finished, so its brain is made now just to be closed.
     */
    public static synchronized void close() {

        if (fallback == null && TrainingRun.configured() && "neural".equalsIgnoreCase(property("modular_mob_ai.brain", ""))) {

            defaultBrain();
        }

        for (Brain brain : CREATED) {

            brain.close();
        }

        CREATED.clear();
        NETWORKS.clear();
        BUNDLED.clear();
        LABELS.clear();
        REPORTED.clear();
        fallback = null;
        worldDefaults.clear();
        scripted = null;
    }

    /** The build always sets these, passing an empty string through for anything the user left out. */
    private static String property(String name, String fallback) {

        String value = System.getProperty(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
