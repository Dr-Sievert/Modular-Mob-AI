package net.sievert.modularmobai.gametest.league;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.GameRules;
import net.sievert.modularmobai.Constants;
import net.sievert.modularmobai.arena.Loadout;
import net.sievert.modularmobai.brain.Brain;
import net.sievert.modularmobai.brain.Brains;
import net.sievert.modularmobai.brain.NeuralBrain;
import net.sievert.modularmobai.brain.ScriptedBrain;
import net.sievert.modularmobai.brain.nn.WeightFile;
import net.sievert.modularmobai.brain.schema.ObservationSchema;
import net.sievert.modularmobai.gametest.Evaluation;
import net.sievert.modularmobai.gametest.GameTestTuning;

/**
 * Who each league fight is between, and how every one of them ended.
 *
 * <p>A league run's agent fights every hostile mob there is, squads of several of them at once, the scripted fighter, and
 * frozen copies of itself, with a different loadout from one fight to the next. What it meets in its training fights is
 * the trainer's call: it weighs every opponent by how close the agent is to an even fight against it, keeps a share for
 * each so none is forgotten, and writes the shares down here; see trainer/mmai/league.py. This side only draws from them,
 * fights, and writes down how each fight went. The ratings, the tier list and when the run is done are worked out over
 * there.
 *
 * <pre>
 *   runs/RUN/league/roster.csv        written here: opponent,kind,cap for every opponent this build fields that is not a checkpoint
 *   runs/RUN/league/matchmaking.csv   written by the trainer: opponent,share and more, read here whenever it changes
 *   runs/RUN/league/results/wNN.csv   appended here: iteration,kind,opponent,loadout,opponent_loadout,outcome,ticks,cause
 * </pre>
 *
 * <p>An opponent is a mob or a squad of mobs by the name {@link Opposition} gives it, {@code scripted}, or a checkpoint of
 * the run as {@code iteration-000125},
 * whose weights play it on their most likely action, frozen, with nothing recorded: only the agent learns. An evaluation
 * fight, the one in ten {@link Evaluation} hands to a checkpoint, draws its opponent evenly from everyone instead of by the
 * shares, so a checkpoint is measured against all of them alike, and its fights are the ones the trainer rates. Those
 * against a mob or the scripted fighter also go into the checkpoint's evaluation, the win rate that decides the best
 * weights and when the run is done; those against another checkpoint only into the ratings, since what they measure
 * moves as the pool does.
 *
 * <p>Outside a training run there is nobody to draw shares from, so every worker goes round every opponent in turn, the
 * scripted fighter included, and a network driving the agents also fights a frozen copy of itself. That is what a quick
 * look with {@code scripts\test.ps1 -League} and an evaluation with {@code scripts\eval.ps1 -Suite league} see, and each
 * prints what happened against every opponent once its fights are done.
 */
public final class League {

    private League() {}

    /** The one opponent that is an agent and not a checkpoint: the hand written fighter. */
    public static final String SCRIPTED = "scripted";

    /** A frozen copy of the network driving the agents, in a run with no checkpoints to draw from. */
    public static final String SELF = "self";

    /** How a checkpoint is named in the files: iteration-000125. */
    private static final String CHECKPOINT = "iteration-";

    /** Midnight, when no undead burns, every spider is hostile and no enderman is chased off by the light. */
    private static final long MIDNIGHT = 18000L;

    /**
     * One fight's pairing.
     *
     * @param opponent        its name in the results
     * @param opposition      the mob or squad of mobs it is, or null for another agent
     * @param brain           what drives it when it is an agent, or null for mobs
     * @param loadout         what the agent carries
     * @param opponentLoadout what an agent opponent carries, or null for mobs, which keep what they spawn with
     * @param evaluation      the checkpoint playing the agent's side instead of the training brain, or null
     */
    public record Matchup(String opponent, @Nullable Opposition opposition, @Nullable Brain brain, Loadout loadout,
                          @Nullable Loadout opponentLoadout, @Nullable Evaluation.Assignment evaluation) {

        /** How many mobs are on the other side, which is how many places to stand the fight's ground needs. */
        public int mobs() {

            return this.opposition == null ? 1 : this.opposition.mobs().size();
        }
    }

    /** The run's league folder, or null outside a training run or once something has gone wrong with it. */
    @Nullable
    private static Path directory;
    private static boolean resolved;
    private static boolean prepared;

    private static int worker;
    private static int workers = 1;

    /** Pairings handed out, and fights recorded, by this process. */
    private static long fights;
    private static long recorded;

    /** The matchmaking as last read, and when its file last changed, so it is only read again when it does. */
    private static long matchmakingModified = Long.MIN_VALUE;
    private static List<String> drawn = List.of();
    private static double[] cumulative = new double[0];

    /** Frozen checkpoints by iteration, kept only while the matchmaking names them. */
    private static final Map<Integer, Brain> frozen = new HashMap<>();

    @Nullable
    private static ScriptedBrain scripted;

    /** What became of the fights against each opponent, and with each loadout, for the summary once they are all done. */
    private static final Map<String, Tally> tallies = new LinkedHashMap<>();
    private static final Map<String, Tally> loadoutTallies = new LinkedHashMap<>();

    /**
     * Midnight for good, clear weather and no mob griefing, once per process as the first league fight starts. See
     * {@link Roster} for why: the undead would burn at noon, rain would hurt a blaze and a snow golem and teleport an
     * enderman, and a creeper's crater would stay in a kept world.
     *
     * <p>The weather is worth turning off rather than trusting: a freshly generated world starts clear, but a worker
     * fights for hours of game time, and the first storm to roll in would be a different fight for every mob the weather
     * touches, for as long as it lasted.
     */
    public static synchronized void prepareWorld(ServerLevel level) {

        if (prepared) {

            return;
        }

        prepared = true;

        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, level.getServer());
        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, level.getServer());
        level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(false, level.getServer());
        level.setDayTime(MIDNIGHT);
        level.setWeatherParameters(0, 0, false, false);

        Constants.LOG.info("League fights: {} opponents of {} mobs and squads, the scripted fighter{}, {} loadouts; midnight "
                + "and clear for good, no mob griefing", Opposition.fielded().size(), Roster.fielded().size(),
                directory != null ? " and the run's checkpoints" : "", Loadouts.enabled().size());
    }

    /** The pairing for the next fight, which is an evaluation when it is handed one. */
    public static synchronized Matchup next(@Nullable Evaluation.Assignment evaluation, RandomSource random) {

        if (!resolved) {

            resolve();
        }

        long fight = fights++;
        List<String> fixed = fixed();

        String name;

        if (directory == null) {

            // Every worker starts on a different opponent and all of them go round in step, and round the loadouts at the
            // same time, so a short run still sees every opponent and every loadout, and a long one every pairing.
            name = fixed.get((int) ((worker + fight * workers) % fixed.size()));
        }

        else {

            refresh();
            name = evaluation != null ? evenly(evaluation.iteration(), random) : draw(random);
        }

        Matchup matchup = matchup(name, evaluation, random, fight);

        // A checkpoint whose weights will not load is not worth stopping a fight over; a mob always can be fielded.
        return matchup != null ? matchup : matchup(fixed.get(random.nextInt(fixed.size())), evaluation, random, fight);
    }

    /**
     * Writes down how a fight ended: {@code win}, {@code loss}, {@code timeout} with both still standing, or
     * {@code draw}, when the opponent went without being killed, as a creeper that blew itself up does.
     *
     * @param landed   whether the opponent hurt the agent at any point
     * @param targeted whether the opponent went for the agent at any point
     * @param cause    what the agent died of when it died, as {@link net.sievert.modularmobai.gametest.util.DeathCauses}
     *                 names it, and {@code -} when it did not
     */
    public static synchronized void record(Matchup matchup, String outcome, long ticks, boolean landed, boolean targeted,
                                           String cause) {

        if (directory != null) {

            write(matchup, outcome, ticks, cause);
        }

        // Only a mob or the scripted fighter holds still enough to judge a checkpoint by, see the class comment.
        if (matchup.evaluation() != null && !matchup.opponent().startsWith(CHECKPOINT)) {

            Evaluation.record(matchup.evaluation(), outcome, ticks);
        }

        tallies.computeIfAbsent(matchup.opponent(), ignored -> new Tally()).add(outcome, landed, targeted);
        loadoutTallies.computeIfAbsent(matchup.loadout().name(), ignored -> new Tally()).add(outcome, landed, targeted);

        if (++recorded == GameTestTuning.arenasInShard(GameTestTuning.arenaCount())) {

            summarise();
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Who
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Every opponent that is not a checkpoint: the mobs and squads, the scripted fighter, and outside a training run, the
     * network.
     */
    private static List<String> fixed() {

        List<String> names = new ArrayList<>(Opposition.fielded());

        names.add(SCRIPTED);

        if (directory == null && selfWeights() != null) {

            names.add(SELF);
        }

        return names;
    }

    /** Drawn by the trainer's shares, or evenly from the fixed opponents until it has written any. */
    private static String draw(RandomSource random) {

        if (drawn.isEmpty()) {

            List<String> fixed = fixed();
            return fixed.get(random.nextInt(fixed.size()));
        }

        double point = random.nextDouble() * cumulative[cumulative.length - 1];

        for (int index = 0; index < cumulative.length; index++) {

            if (point < cumulative[index]) {

                return drawn.get(index);
            }
        }

        return drawn.get(drawn.size() - 1);
    }

    /** Evenly from everyone the trainer names, or the fixed opponents until it names any, but never the checkpoint itself. */
    private static String evenly(int iteration, RandomSource random) {

        List<String> everyone = new ArrayList<>(drawn.isEmpty() ? fixed() : drawn);
        everyone.remove(checkpointName(iteration));

        return everyone.get(random.nextInt(everyone.size()));
    }

    @Nullable
    private static Matchup matchup(String name, @Nullable Evaluation.Assignment evaluation, RandomSource random, long fight) {

        List<Loadout> loadouts = Loadouts.enabled();

        // The scripted fighter can only swing, on either side; see Loadouts#melee.
        List<Loadout> agentLoadouts = scriptedAgents() ? melee(loadouts) : loadouts;
        Loadout loadout = directory == null
                ? agentLoadouts.get((int) (fight % agentLoadouts.size()))
                : agentLoadouts.get(random.nextInt(agentLoadouts.size()));

        Opposition opposition = Opposition.named(name);

        if (opposition != null) {

            return new Matchup(name, opposition, null, loadout, null, evaluation);
        }

        Brain brain;
        List<Loadout> opponentLoadouts = loadouts;

        if (name.equals(SCRIPTED)) {

            brain = scripted();
            opponentLoadouts = melee(loadouts);
        }

        else if (name.equals(SELF)) {

            brain = Brains.network(selfWeights());
        }

        else if (name.startsWith(CHECKPOINT)) {

            brain = directory == null ? null : checkpoint(Integer.parseInt(name.substring(CHECKPOINT.length())));
        }

        else {

            brain = null;
        }

        if (brain == null) {

            return null;
        }

        return new Matchup(name, null, brain, loadout, opponentLoadouts.get(random.nextInt(opponentLoadouts.size())), evaluation);
    }

    private static List<Loadout> melee(List<Loadout> loadouts) {

        List<Loadout> melee = loadouts.stream().filter(Loadouts::melee).toList();
        return melee.isEmpty() ? List.of(Loadout.SWORD) : melee;
    }

    /** Whether the scripted fighter drives the agents themselves, recorded for copying or not, as it does by default. */
    private static boolean scriptedAgents() {

        String brain = System.getProperty("modular_mob_ai.brain", "").trim();
        return brain.isEmpty() || brain.equalsIgnoreCase("scripted");
    }

    /** The weights driving the agents in a run with no trainer, or null when it is not a network driving them. */
    @Nullable
    private static Path selfWeights() {

        String weights = System.getProperty("modular_mob_ai.brain.weights", "").trim();
        return scriptedAgents() || weights.isEmpty() ? null : Path.of(weights);
    }

    private static ScriptedBrain scripted() {

        if (scripted == null) {

            scripted = new ScriptedBrain();
        }

        return scripted;
    }

    @Nullable
    private static Brain checkpoint(int iteration) {

        Brain brain = frozen.get(iteration);

        if (brain != null) {

            return brain;
        }

        Path file = directory.getParent().resolve("weights").resolve(String.format(Locale.ROOT, "%06d%s", iteration, WeightFile.EXTENSION));

        try {

            brain = NeuralBrain.deployed(WeightFile.read(file, ObservationSchema.schemaId()));
            frozen.put(iteration, brain);
            return brain;
        }

        catch (IOException | RuntimeException exception) {

            Constants.LOG.warn("Could not load checkpoint {} to fight: {}", iteration, exception.toString());
            return null;
        }
    }

    private static String checkpointName(int iteration) {

        return String.format(Locale.ROOT, "%s%06d", CHECKPOINT, iteration);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Files
    // ---------------------------------------------------------------------------------------------------------------

    private static void resolve() {

        resolved = true;
        worker = GameTestTuning.shardIndex();
        workers = GameTestTuning.shardCount();

        String run = System.getProperty("modular_mob_ai.training.run", "").trim();

        if (run.isEmpty()) {

            return;
        }

        try {

            directory = Files.createDirectories(Path.of(run).resolve("league"));
            Files.createDirectories(directory.resolve("results"));
            writeRoster();
        }

        catch (IOException exception) {

            fail("Could not make the league folder", exception);
        }
    }

    /**
     * Tells the trainer who this build fields, so it can weigh them before any of them has fought, and how large a share of
     * the training fights each may take: one that cannot be beaten at all, the warden, has that capped here rather than in
     * the trainer, since it is the mob that knows, see {@link Roster.Member#trainingCap}.
     */
    private static void writeRoster() throws IOException {

        StringBuilder out = new StringBuilder("opponent,kind,cap\n");

        for (String name : Opposition.fielded()) {

            Opposition opposition = Opposition.named(name);
            out.append(String.format(Locale.ROOT, "%s,%s,%.5f\n", name, opposition.kind(), opposition.trainingCap()));
        }

        out.append(SCRIPTED).append(",scripted,1.00000\n");

        Path target = directory.resolve("roster.csv");
        Path temporary = directory.resolve(String.format(Locale.ROOT, "roster.w%02d.tmp", worker));

        Files.writeString(temporary, out.toString(), StandardCharsets.UTF_8);

        try {

            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }

        catch (IOException exception) {

            // Every worker writes the same, and the trainer may be reading it this very moment; one of them gets through.
            Files.deleteIfExists(temporary);
        }
    }

    /** Reads the matchmaking again when its file has changed, and lets go of any checkpoint it no longer names. */
    private static void refresh() {

        Path file = directory.resolve("matchmaking.csv");

        try {

            if (!Files.isRegularFile(file)) {

                return;
            }

            long modified = Files.getLastModifiedTime(file).toMillis();

            if (modified == matchmakingModified) {

                return;
            }

            List<String> names = new ArrayList<>();
            List<Double> shares = new ArrayList<>();
            Set<Integer> named = new HashSet<>();

            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {

                String[] parts = line.split(",");

                if (parts.length < 2 || parts[0].equals("opponent")) {

                    continue;
                }

                String name = parts[0].trim();
                double share = Double.parseDouble(parts[1].trim());

                boolean known = Opposition.named(name) != null || name.equals(SCRIPTED) || name.matches(CHECKPOINT + "\\d+");

                if (!known || !(share > 0.0D)) {

                    continue;
                }

                if (name.startsWith(CHECKPOINT)) {

                    named.add(Integer.parseInt(name.substring(CHECKPOINT.length())));
                }

                names.add(name);
                shares.add(share);
            }

            double[] running = new double[shares.size()];
            double sum = 0.0D;

            for (int index = 0; index < running.length; index++) {

                sum += shares.get(index);
                running[index] = sum;
            }

            drawn = List.copyOf(names);
            cumulative = running;
            matchmakingModified = modified;
            frozen.keySet().retainAll(named);
        }

        catch (IOException | RuntimeException exception) {

            // Caught mid write, most likely; the next fight looks again.
            Constants.LOG.debug("Could not read the league's matchmaking yet: {}", exception.toString());
        }
    }

    private static void write(Matchup matchup, String outcome, long ticks, String cause) {

        int iteration = matchup.evaluation() != null ? matchup.evaluation().iteration()
                : Brains.defaultBrain() instanceof NeuralBrain neural ? neural.weights().iteration() : -1;

        String line = String.format(Locale.ROOT, "%d,%s,%s,%s,%s,%s,%d,%s%n", iteration, matchup.evaluation() != null ? "eval" : "train",
                matchup.opponent(), matchup.loadout().name(), matchup.opponentLoadout() == null ? "-" : matchup.opponentLoadout().name(),
                outcome, ticks, cause);

        try {

            Files.writeString(directory.resolve("results").resolve(String.format(Locale.ROOT, "w%02d.csv", worker)), line,
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }

        catch (IOException exception) {

            fail("Could not write a league result", exception);
        }
    }

    /**
     * Prints what became of the fights against every opponent and with every loadout, and hands the same to the build
     * when this is one worker of a parallel run, which adds the workers up. Loadouts go in the file as loadout:NAME.
     */
    private static void summarise() {

        String header = "========= League fights over " + recorded + " runs =========";
        StringBuilder file = new StringBuilder();

        System.out.println(header);
        print("opponent", tallies, "", file);
        print("agent's loadout", loadoutTallies, "loadout:", file);
        System.out.println("  hit it: fights in which the opponent hurt the agent; went for: in which it kept the agent as its target");
        System.out.println("=".repeat(header.length()));

        String stats = GameTestTuning.statsFile();

        if (stats == null) {

            return;
        }

        try {

            Files.writeString(Path.of(stats + ".league"), file.toString(), StandardCharsets.UTF_8);
        }

        catch (IOException exception) {

            Constants.LOG.warn("Could not write the league summary: {}", exception.toString());
        }
    }

    /** One table of the summary, printed, and added to what the build is handed with each name under the prefix. */
    private static void print(String title, Map<String, Tally> table, String prefix, StringBuilder file) {

        System.out.println(String.format(Locale.ROOT, "  %-24s %6s %7s %7s %9s %7s %6s %9s", title, "fights", "won %", "lost %",
                "timeout %", "draw %", "hit it", "went for"));

        for (Map.Entry<String, Tally> entry : table.entrySet()) {

            Tally tally = entry.getValue();
            double fights = Math.max(1, tally.fights);

            System.out.println(String.format(Locale.ROOT, "  %-24s %6d %7.1f %7.1f %9.1f %7.1f %6d %9d", entry.getKey(), tally.fights,
                    100.0D * tally.wins / fights, 100.0D * tally.losses / fights, 100.0D * tally.timeouts / fights,
                    100.0D * tally.draws / fights, tally.landed, tally.targeted));

            file.append(String.format(Locale.ROOT, "%s%s %d %d %d %d %d %d %d%n", prefix, entry.getKey(), tally.fights, tally.wins,
                    tally.losses, tally.timeouts, tally.draws, tally.landed, tally.targeted));
        }
    }

    private static void fail(String message, Exception exception) {

        Constants.LOG.warn("{}; no more league results will be written in this process", message, exception);
        directory = null;
    }

    /** How the fights against one opponent went. */
    private static final class Tally {

        private int fights;
        private int wins;
        private int losses;
        private int timeouts;
        private int draws;
        private int landed;
        private int targeted;

        private void add(String outcome, boolean hurtTheAgent, boolean wentForIt) {

            this.fights++;

            switch (outcome) {

                case "win" -> this.wins++;
                case "loss" -> this.losses++;
                case "timeout" -> this.timeouts++;
                default -> this.draws++;
            }

            this.landed += hurtTheAgent ? 1 : 0;
            this.targeted += wentForIt ? 1 : 0;
        }
    }
}
