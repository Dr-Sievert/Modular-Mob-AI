package net.sievert.modularmobai.brain;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.jetbrains.annotations.Nullable;

import net.sievert.modularmobai.Config;
import net.sievert.modularmobai.Constants;
import net.sievert.modularmobai.brain.nn.WeightFile;

/**
 * Trained networks by name, wherever the game can find them.
 *
 * <p>Two places, searched in this order:
 *
 * <pre>
 *   a models folder     NAME/best.mbw or NAME.mbw, in -Dmodular_mob_ai.models, then the config's models folder
 *   the mod's jar       /modular_mob_ai/models/NAME.mbw, built in from the repository's models\NAME\best.mbw
 * </pre>
 *
 * The jar is what makes a network work outside the development environment: the build copies every network published
 * under the repository's {@code models\} into it, with an index of what each one won, so a jar dropped into any game
 * brings its fighters along and needs no path set anywhere. A folder comes first so that a network published after the
 * jar was built, or one that was never published, can still be tried without building again.
 *
 * <p>A name is a folder name under {@code models\}: letters, digits, dots, dashes and underscores. Nothing a command
 * names this way can walk out of the folder it is looked for in; a weight file anywhere else is named by its path, which
 * has to end in {@code .mbw}, see {@link Brains#named}.
 */
public final class Models {

    private Models() {}

    /** Where the build puts the networks in the jar; see bundleModels in multiloader-loader.gradle. */
    private static final String JAR_FOLDER = "/" + Constants.MOD_ID + "/models/";

    /** What the build wrote about them: their names, which one won the most, and a line each on how it did. */
    private static final String INDEX = JAR_FOLDER + "models.properties";

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.\\-]+");

    /** What the published run folders are called. */
    private static final String PUBLISHED_WEIGHTS = "best" + WeightFile.EXTENSION;

    @Nullable
    private static Properties index;

    /** Whether this can be a network's name, rather than a path or something that would climb out of a folder. */
    public static boolean validName(String name) {

        return NAME.matcher(name).matches() && !name.endsWith(WeightFile.EXTENSION) && !name.startsWith(".");
    }

    /** The networks the jar carries, in the build's order. Empty for a jar built without any. */
    public static synchronized List<String> bundled() {

        String names = index().getProperty("models", "").trim();
        return names.isEmpty() ? List.of() : Arrays.stream(names.split(",")).map(String::trim).filter(Models::validName).toList();
    }

    /**
     * The network the jar carries for that body which won the most of its evaluation fights, which is what {@code best}
     * names; null where the jar carries none of that body. Chosen by the build from each one's model.json, so it is the same
     * pick scripts\play.ps1 makes.
     *
     * <p>Per body, because a network only fits the body its layout was written for. One global best would hand a beast's
     * network to a humanoid on any day a beast evaluated higher, and the only thing that would say so is the driver, in the
     * middle of a fight.
     */
    @Nullable
    public static synchronized String best(String species) {

        String best = index().getProperty("best." + species, "").trim();
        return best.isEmpty() ? null : best;
    }

    /**
     * Which body a network the jar carries drives, or null for one the build could say nothing about: a folder published
     * before the layout was recorded beside the weights. Such a network is still loadable by name, where its own schema id
     * decides, and is no body's {@code best}.
     */
    @Nullable
    public static synchronized String speciesOf(String name) {

        String species = index().getProperty(name + ".species", "").trim();
        return species.isEmpty() ? null : species;
    }

    /** What the build recorded about a network the jar carries, such as its win rate, or null for one it knows nothing of. */
    @Nullable
    public static synchronized String summary(String name) {

        return index().getProperty(name + ".summary");
    }

    /** A weight file for this name in one of the models folders, or null when none has one. */
    @Nullable
    public static Path onDisk(String name) {

        if (!validName(name)) {

            return null;
        }

        for (Path folder : Config.modelFolders()) {

            for (Path candidate : List.of(folder.resolve(name).resolve(PUBLISHED_WEIGHTS), folder.resolve(name + WeightFile.EXTENSION))) {

                if (Files.isRegularFile(candidate)) {

                    return candidate;
                }
            }
        }

        return null;
    }

    /** Every name a models folder has a network for, sorted. */
    public static List<String> onDiskNames() {

        TreeSet<String> names = new TreeSet<>();

        for (Path folder : Config.modelFolders()) {

            if (!Files.isDirectory(folder)) {

                continue;
            }

            try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {

                for (Path entry : entries) {

                    String file = entry.getFileName().toString();

                    if (Files.isRegularFile(entry.resolve(PUBLISHED_WEIGHTS)) && validName(file)) {

                        names.add(file);
                    }

                    else if (Files.isRegularFile(entry) && file.endsWith(WeightFile.EXTENSION)) {

                        String name = file.substring(0, file.length() - WeightFile.EXTENSION.length());

                        if (validName(name)) {

                            names.add(name);
                        }
                    }
                }
            }

            catch (IOException exception) {

                Constants.LOG.warn("Could not list the networks in {}", folder, exception);
            }
        }

        return new ArrayList<>(names);
    }

    /** The bytes of a network the jar carries, or null when it carries none by that name. */
    @Nullable
    static byte[] bundledBytes(String name) throws IOException {

        if (!validName(name)) {

            return null;
        }

        try (InputStream stream = Models.class.getResourceAsStream(JAR_FOLDER + name + WeightFile.EXTENSION)) {

            return stream == null ? null : stream.readAllBytes();
        }
    }

    /** Where a network the jar carries lives, for a message. */
    static String bundledSource(String name) {

        return "the mod's jar, " + JAR_FOLDER.substring(1) + name + WeightFile.EXTENSION;
    }

    private static Properties index() {

        if (index == null) {

            index = new Properties();

            try (InputStream stream = Models.class.getResourceAsStream(INDEX)) {

                if (stream != null) {

                    try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {

                        index.load(reader);
                    }
                }
            }

            catch (IOException exception) {

                Constants.LOG.warn("Could not read the index of the networks in the mod's jar", exception);
            }
        }

        return index;
    }
}
