package net.sievert.modularmobai;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * What a game with the mod in it is set up to do, from {@code config/modular_mob_ai.properties}.
 *
 * <p>Only agents met in a real game read it: which brain drives one that was never given a brain of its own, what one
 * spawned from an egg or a bare {@code /summon} carries, and where networks are looked for by name. The training agent,
 * the arenas and everything a training worker does ignore it, so nothing a run learns from depends on a file someone may
 * have edited.
 *
 * <p>A plain properties file rather than either loader's own config system, because this is read in common code and has
 * to mean the same on both. Written with every setting and what it does the first time the game starts, and read once.
 * The system properties a development run is started with still win over it: {@code -Dmodular_mob_ai.brain} drives every
 * agent that has no brain of its own (see Brains#forAgent), and {@code -Dmodular_mob_ai.models} is searched before the
 * folder named here. That is how {@code scripts\play.ps1} points a development client at the repository's networks
 * without touching the file.
 */
public final class Config {

    private Config() {}

    public static final String FILE_NAME = Constants.MOD_ID + ".properties";

    /** What an agent with no brain of its own runs on: the best network the mod's jar carries. See Brains#named. */
    public static final String DEFAULT_BRAIN = "best";

    public static final String DEFAULT_LOADOUT = "sword";

    /** Relative to the game directory, like everything else in the file. */
    public static final String DEFAULT_MODELS = Constants.MOD_ID + "/models";

    /**
     * Whether an agent a player meets takes the drops it walks over. On, because a body that can be handed a bow and cannot
     * pick one up is a body a player has to use a command on; see {@code AgentMob#pickUpItem} for what it does with what it
     * takes. A training agent never reads this and never picks anything up whatever it says.
     */
    public static final boolean DEFAULT_PICKUP = true;

    private static final String HEADER = """
            # Modular Mob AI
            #
            # brain: what drives an agent that has not been given a brain of its own, by /mmai brain, /mmai spawn or a
            #        /summon with a BrainName. What it was given is saved with it and wins over this.
            #   best          the best trained network the mod's jar carries (the default)
            #   scripted      the hand written fighter
            #   <name>        a network by name: <models>/<name>/best.mbw or <models>/<name>.mbw, else one the jar carries
            #   <file>.mbw    a weight file, absolute or relative to the game directory
            #
            # loadout: what an agent spawned from an egg or a bare /summon carries. /mmai loadouts lists them.
            #
            # models: a folder of trained networks, searched by name before the ones the jar carries. Relative to the game
            #         directory. A models\\<name> folder from the repository can be copied in as it is.
            #
            # pickup: whether a new agent takes the items it walks over, into its hotbar and then its own pocket. Each agent
            #         keeps its own answer, saved with it: /mmai pickup <targets> on|off, or the button in its inventory
            #         screen. An agent in training never picks anything up, whatever this says.
            """;

    private static Path gameDirectory = Path.of("");
    private static final Properties VALUES = defaults();

    /**
     * Reads the file, writing it first if there is none. Each loader calls this once as the mod starts, with the folders
     * it knows the game to be using; nothing is read before then, and a game test or a tool that never calls it gets the
     * defaults.
     */
    public static synchronized void load(Path gameDir, Path configDir) {

        gameDirectory = gameDir.toAbsolutePath().normalize();
        Path file = configDir.resolve(FILE_NAME);

        try {

            if (Files.isRegularFile(file)) {

                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {

                    VALUES.load(reader);
                }
            }

            else {

                Files.createDirectories(configDir);
                Files.writeString(file, HEADER + "brain=" + DEFAULT_BRAIN + "\nloadout=" + DEFAULT_LOADOUT
                        + "\nmodels=" + DEFAULT_MODELS + "\npickup=" + DEFAULT_PICKUP + "\n", StandardCharsets.UTF_8);
            }
        }

        // A config that cannot be read is no reason to refuse to start: the defaults are what it would have said anyway.
        catch (IOException exception) {

            Constants.LOG.warn("Could not read or write {}, using the defaults", file, exception);
        }
    }

    /** Where the game runs, which relative paths in the file and in commands are taken from. */
    public static synchronized Path gameDirectory() {

        return gameDirectory;
    }

    /** What drives an agent that has no brain of its own, as a name Brains#named understands. */
    public static synchronized String brain() {

        return value("brain", DEFAULT_BRAIN);
    }

    /** The loadout an agent spawned from an egg or a bare /summon carries. */
    public static synchronized String loadout() {

        return value("loadout", DEFAULT_LOADOUT);
    }

    /**
     * Whether an agent a player meets starts out taking the drops it walks over. Only the world's default: each agent keeps
     * its own answer from there on, saved with it, and a training agent is never asked.
     */
    public static synchronized boolean pickup() {

        return Boolean.parseBoolean(value("pickup", String.valueOf(DEFAULT_PICKUP)));
    }

    /**
     * Folders to look for networks in by name, first match wins: the system property's first, then the file's. The
     * system property is how a development client finds the repository's models folder without the file saying so.
     */
    public static synchronized List<Path> modelFolders() {

        List<Path> folders = new ArrayList<>();
        String property = System.getProperty(Constants.MOD_ID + ".models");

        if (property != null && !property.isBlank()) {

            folders.add(resolve(property.trim()));
        }

        folders.add(resolve(VALUES.getProperty("models", DEFAULT_MODELS).trim()));
        return folders;
    }

    /** A path from the file or a command, taken from the game directory unless it is already absolute. */
    public static synchronized Path resolve(String path) {

        return gameDirectory.resolve(path).toAbsolutePath().normalize();
    }

    /** The file's value, or the default for one it leaves out or leaves empty. */
    private static String value(String key, String fallback) {

        String configured = VALUES.getProperty(key, fallback).trim();
        return configured.isEmpty() ? fallback : configured;
    }

    private static Properties defaults() {

        Properties properties = new Properties();
        properties.setProperty("brain", DEFAULT_BRAIN);
        properties.setProperty("loadout", DEFAULT_LOADOUT);
        properties.setProperty("models", DEFAULT_MODELS);
        properties.setProperty("pickup", String.valueOf(DEFAULT_PICKUP));
        return properties;
    }
}
