package net.sievert.modularmobai.gametest.league;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import net.sievert.modularmobai.Constants;
import net.sievert.modularmobai.brain.Brain;
import net.sievert.modularmobai.brain.Brains;
import net.sievert.modularmobai.brain.schema.Species;

/**
 * Published networks fielded as league players: a network under {@code models\NAME\}, fought as another agent on its most
 * likely action and rated under its own name.
 *
 * <pre>
 *   -Dmodular_mob_ai.league.models=vs-copy,vs-scratch   nobody unless given (scripts\train.ps1 -LeagueModels)
 * </pre>
 *
 * <h2>Why</h2>
 *
 * <p>A run rates its own checkpoints, the mobs and squads, and the scripted fighter, and nothing else. Two lineages
 * trained from different starts therefore never meet: each has a tier list of its own, and the only player the two have in
 * common is the scripted fighter. A published network is a fixed policy that anyone can load, and the league already drives
 * frozen checkpoints as opponents, so naming one here is all it takes to put both lineages on one tier list — every run
 * that fields {@code vs-copy} rates it, and a run's own checkpoints are then a known distance from a player the other run
 * also fought.
 *
 * <h2>A player like any other, and not an anchor</h2>
 *
 * <p>A model enters where everyone enters, at {@code league_initial}, and its rating moves on its fights like a mob's does.
 * It is <b>not</b> a second anchor. The scripted fighter alone is held still, which is what makes the scale mean the same
 * from run to run; a second fixed point would say what the distance between the two of them is instead of measuring it, and
 * every rating between them would be pulled towards whatever that assumption was wrong by. Left to move, a model's rating
 * is an estimate of its strength on the same scale, earned the same way — and two runs that each rate the same model are
 * then comparable <i>and</i> checkable: if a run has {@code vs-copy} at 1900 and another at 1600, the two tier lists have
 * drifted apart and should not be read side by side.
 *
 * <p>Nothing about the scale itself moves by adding players. Every rated fight is zero sum except against the anchor, whose
 * K is zero, so a newcomer starting at the initial rating neither inflates nor deflates anyone else: it settles at its own
 * level and takes its points from the opponents it actually beats.
 *
 * <h2>Where its fights come from</h2>
 *
 * <p>A model never learns, so it belongs with the mobs and the scripted fighter rather than in the self play share. It is
 * written into {@code roster.csv} as a player of kind {@code model}, and the trainer weighs it in the fixed group with them:
 * matchmaking sends training fights towards the even fight as it does for every opponent, and the evaluation draw meets it
 * as often as anything else. The self play share stays what it was, for the run's own checkpoints alone, which is what it is
 * for — a moving pool of what the agent used to be.
 *
 * <p>Its fights count towards a checkpoint's evaluation as the scripted fighter's do, and unlike a checkpoint's: what an
 * evaluation fight has to measure is a fixed opponent, and a published network is exactly that.
 *
 * <p>It draws from every loadout, as a checkpoint does. The scripted fighter is held to melee because it is the anchor and
 * its strength may not move under a run already going; a model fielded for the first time has nothing to hold still for, and
 * the fight worth having between two networks is the one either might be armed for.
 *
 * <h2>What is refused</h2>
 *
 * <p>Naming a model that cannot be fielded stops the run rather than quietly fielding one fewer, since the point of naming
 * it is to rate it:
 *
 * <ul>
 *   <li>a name no models folder and no jar has a network for, saying what there is instead;</li>
 *   <li>a name something in the league already answers to — a mob, a squad, a rung of the ladder, the scripted fighter, or
 *       the shape a checkpoint is written in — because a rating has to belong to one player;</li>
 *   <li>a network of another <b>body</b>, naming both bodies. A network belongs to the species its layout was written for,
 *       which is what its schema id says, and a beast's brain cannot drive a humanoid at all; see docs/species.md. Caught
 *       here, before a fight is ever set up, rather than by the driver in the middle of one.</li>
 * </ul>
 */
public final class Published {

    private Published() {}

    private static final String PROPERTY = "modular_mob_ai.league.models";

    /**
     * Which body this run's agents are, and so which body a published network has to have been trained for. The build
     * passes on the same answer it wrote the run's schema.json for ({@code -Pspecies}); a process started with neither gets
     * the humanoid, which is the body the training agent is and every network published so far drives.
     */
    private static final String SPECIES = "modular_mob_ai.species";

    /** What kind of player a published model is, in roster.csv and in the ratings. */
    public static final String KIND = "model";

    /** How a checkpoint is named, which no model may be called: a rating has to belong to one player. */
    private static final String CHECKPOINT = "iteration-\\d+";

    /** The models this process fields, by name, in the order they were asked for; read and loaded once. */
    @Nullable
    private static Map<String, Brain> fielded;

    /** Every model this process fields, in the order named. Empty unless a run asked for any. */
    public static synchronized List<String> fielded() {

        return List.copyOf(all().keySet());
    }

    /** Whether that name is a model this process fields, which is what makes it a player rather than a mob. */
    public static synchronized boolean fields(String name) {

        return all().containsKey(name);
    }

    /** What drives the agent playing that model, or null when this process fields no model of that name. */
    @Nullable
    public static synchronized Brain brain(String name) {

        return all().get(name);
    }

    private static Map<String, Brain> all() {

        if (fielded == null) {

            Map<String, Brain> found = new LinkedHashMap<>();

            for (String name : names()) {

                found.put(name, load(name));
            }

            // Not Map.copyOf, which keeps nothing of the order these were named in, and that order is what a run with no
            // trainer goes round them in.
            fielded = Collections.unmodifiableMap(found);

            if (!fielded.isEmpty()) {

                Constants.LOG.info("League fights field {} published network(s) as rated players: {}", fielded.size(),
                        fielded.keySet());
            }
        }

        return fielded;
    }

    /** The names this process was told to field, in order, with anything named twice kept once. */
    private static List<String> names() {

        String named = System.getProperty(PROPERTY, "").trim();

        if (named.isEmpty()) {

            return List.of();
        }

        List<String> names = new ArrayList<>();

        for (String name : Arrays.stream(named.split(",")).map(String::trim).filter(name -> !name.isEmpty()).toList()) {

            if (!names.contains(name)) {

                names.add(name);
            }
        }

        return names;
    }

    /**
     * The brain a named model plays on: its published weights, on their most likely action, frozen. Refused, loudly, for
     * every reason a name might not be one player of this league's; see the class comment.
     */
    private static Brain load(String name) {

        if (name.matches(CHECKPOINT) || name.equals(League.SCRIPTED) || name.equals(League.SELF)
                || Opposition.named(name) != null) {

            throw new IllegalArgumentException("'" + name + "' is already a league player of another kind, so a published "
                    + "network cannot be rated under it: an opponent's name is one player. Publish it under another name, or "
                    + "field it by one");
        }

        Brain brain;

        try {

            brain = Brains.named(name);
        }

        catch (RuntimeException exception) {

            throw new IllegalArgumentException("The league was asked to field the published network '" + name + "', which "
                    + "cannot be loaded: " + exception.getMessage(), exception);
        }

        Species body = body();

        if (brain.species() != body) {

            // Named both ways round, as everything that refuses the wrong body is: "this is a beast's network and the
            // league's agents are humanoids" is the mistake that will actually be made, and a network handed to the wrong
            // body is the one failure that would not look like one. See docs/species.md.
            throw new IllegalArgumentException(String.format(Locale.ROOT, "The published network '%s' was trained for a %s "
                    + "(%s), and this league's agents are %ss: a network only fits the body its layout was written for, so it "
                    + "cannot be fielded here. The bodies this build knows are %s", name, brain.species().name(),
                    Brains.describe(brain), body.name(),
                    String.join(", ", Species.ALL.stream().map(Species::name).toList())));
        }

        return brain;
    }

    private static Species body() {

        String named = System.getProperty(SPECIES, "").trim();

        return named.isEmpty() ? Species.HUMANOID : Species.byName(named);
    }
}
