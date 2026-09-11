package net.sievert.modularmobai.brain;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.sievert.modularmobai.Constants;
import net.sievert.modularmobai.brain.nn.WeightFile;
import net.sievert.modularmobai.brain.nn.WeightSet;
import net.sievert.modularmobai.brain.schema.ObservationSchema;

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
 * </pre>
 *
 * A run with nothing set falls back to the scripted fighter, which is what keeps the game tests runnable on their own.
 */
public final class Brains {

    private Brains() {}

    /**
     * Built on first use rather than when this class loads. Loading weights can fail, and doing it during class
     * initialisation would turn "that file is the wrong layout" into an ExceptionInInitializerError thrown from whatever
     * unrelated code happened to touch this class first.
     */
    private static Brain fallback;

    private static final Map<Path, NeuralBrain> NETWORKS = new HashMap<>();
    private static final List<Brain> CREATED = new ArrayList<>();

    public static synchronized Brain defaultBrain() {

        if (fallback == null) {

            fallback = fromProperties();
            CREATED.add(fallback);
        }

        return fallback;
    }

    /**
     * Swaps what drives agents that have not been handed a brain of their own. Called before a run starts, never during
     * one: agents already driven keep the brain they have.
     */
    public static synchronized void setDefault(Brain replacement) {

        fallback = replacement;
    }

    /** A network loaded from a weight file, most likely action, shared by everything that asks for the same file. */
    public static synchronized NeuralBrain network(Path weights) {

        return NETWORKS.computeIfAbsent(weights.toAbsolutePath().normalize(), path -> {

            try {

                WeightSet loaded = WeightFile.read(path, ObservationSchema.schemaId());
                Constants.LOG.info("Loaded {} from iteration {}: {}", loaded.id(), loaded.iteration(), loaded.topology());

                NeuralBrain brain = NeuralBrain.deployed(loaded);
                CREATED.add(brain);
                return brain;
            }

            catch (IOException exception) {

                throw new UncheckedIOException(exception);
            }
        });
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

            default -> throw new IllegalArgumentException("Unknown brain '" + kind + "'; expected scripted or neural. "
                    + "The remote brain is gone: networks run inside the game now, see docs/README.md");
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
        fallback = null;
    }

    /** The build always sets these, passing an empty string through for anything the user left out. */
    private static String property(String name, String fallback) {

        String value = System.getProperty(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
