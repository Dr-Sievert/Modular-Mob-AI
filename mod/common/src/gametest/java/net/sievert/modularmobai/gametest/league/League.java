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
import java.util.Random;
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
import net.sievert.modularmobai.brain.nn.WeightSet;
import net.sievert.modularmobai.gametest.Evaluation;
import net.sievert.modularmobai.gametest.GameTestTuning;
import net.sievert.modularmobai.gametest.util.DeathCauses;

/**
 * Who each league fight is between, and how every one of them ended.
 *
 * <p>A league run's agent fights every hostile mob there is, squads of several of them at once, the scripted fighter,
 * published networks a run names, and frozen copies of itself, with a different loadout from one fight to the next. What it
 * meets in its training fights is the trainer's call: it weighs every <b>pairing</b> of a loadout and an opponent by how
 * close the agent is to an even fight in it, keeps a share for each so none is forgotten, and writes the shares down here;
 * see trainer/mmai/league.py and {@link Pairings}, which says why the pairing and not the opponent alone. This side only
 * draws from them, fights, and writes down how each fight went. The ratings, the tier list and when the run is done are
 * worked out over there.
 *
 * <pre>
 *   runs/RUN/league/roster.csv        written here: opponent,kind,cap,reach for every opponent this build fields that is
 *                                     not a checkpoint, and every loadout it arms the agent with
 *   runs/RUN/league/pairs.csv         written by the trainer: loadout,opponent,share and more, what a training fight is drawn from
 *   runs/RUN/league/matchmaking.csv   written by the trainer: opponent,share and more, which is the same table added up per
 *                                     opponent; what the checkpoints in the pool are read from, and what a run whose trainer
 *                                     writes no pairings falls back to
 *   runs/RUN/league/results/wNN.csv   appended here, a line a fight, see {@link #write}
 * </pre>
 *
 * <p>A share of the fights against a mob or a squad also has a crowd of monsters standing about it taking no interest in the
 * fight, which is the shape a real world has and no league fight had; see {@link Bystanders}. They are not the other side and
 * the reward does not know they are there, but the fight is written down under a name of its own, {@code zombie+3_idle}, so
 * that the plain {@code zombie} rating keeps meaning what it meant.
 *
 * <p>A smaller share of the fights against <b>one</b> mob field several of it instead, all of them fighting: {@code
 * zombie+3_pack}, see {@link HostilePacks}. That is the other half of the same hole — a crowd that takes no interest was one
 * shape a real world has, and three to six hostiles that all come at once is the other, and it is the one the owner reported
 * being overwhelmed by. A pack is an {@link Opposition} of copies, so it is the squad machinery throughout, and a fight is
 * never a pack and crowded both.
 *
 * <p>An opponent is a mob, a squad of mobs or a rung of the difficulty ladder by the name {@link Opposition} gives it,
 * {@code scripted}, a published network a run named, see {@link Published}, or a checkpoint of the run as
 * {@code iteration-000125}, whose weights play it on their most likely action, frozen, with nothing recorded: only the agent
 * learns. An evaluation fight, the one in ten {@link Evaluation} hands to a checkpoint, draws its opponent evenly from
 * everyone instead of by the shares, so a checkpoint is measured against all of them alike, and its fights are the ones the
 * trainer rates. Those against anything that holds still — a mob, the scripted fighter, a published network — also go into
 * the checkpoint's evaluation, the win rate that decides the best weights and when the run is done; those against another
 * checkpoint only into the ratings, since what they measure moves as the pool does.
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

    /**
     * What the {@code reach} column of roster.csv says about a row: that this loadout carries nothing to shoot with, that
     * nothing but a shot can ever touch this opponent, or neither. The two together are the one pairing the league refuses
     * to draw, and this column is how the trainer is told which rows they are; see {@link #writeRoster} and
     * {@link Loadouts#fights}.
     */
    private static final String MELEE = "melee";
    private static final String UNREACHABLE = "unreachable";
    private static final String REACHED = "-";

    /**
     * What share of the fights ask for ground with something on it worth knocking an opponent into: lava, an edge, a
     * cactus patch. The terrain is a weapon, a fight the ground finishes is already the agent's win, and a hundred health
     * of iron golem goes into a lava lake as easily as a zombie does; but a quarter, not all of it, because the plain melee
     * on plain ground is still the fight the agent has to be able to win, and because a run that only ever saw hazards
     * would learn to hunt for them instead of to fight.
     *
     * <p>Only a fraction of the library's sites have anything, so a fight that asks and finds none takes ordinary ground:
     * what comes out of this is a ceiling on the share, and the results say what it really was.
     *
     * <pre>
     *   -Dmodular_mob_ai.league.hazards=0.25   the share; 0 for none at all
     * </pre>
     */
    private static final String HAZARDS = "modular_mob_ai.league.hazards";
    private static final double HAZARD_SHARE = 0.25D;

    private static double hazardShare = Double.NaN;

    /**
     * One fight's pairing.
     *
     * @param opponent        its name in the results
     * @param opposition      the mob or squad of mobs it is, or null for another agent
     * @param brain           what drives it when it is an agent, or null for mobs
     * @param loadout         what the agent carries
     * @param opponentLoadout what an agent opponent carries, or null for mobs, which keep what they spawn with
     * @param evaluation      the checkpoint playing the agent's side instead of the training brain, or null
     * @param bystanders      how many monsters stand about the fight taking no interest in it, and nought for none. They are
     *                        not the other side and are not paid for; the count is in {@code opponent} because a fight with a
     *                        crowd in it is a player of its own, see {@link Bystanders}
     */
    public record Matchup(String opponent, @Nullable Opposition opposition, @Nullable Brain brain, Loadout loadout,
                          @Nullable Loadout opponentLoadout, @Nullable Evaluation.Assignment evaluation, int bystanders) {

        /** How many mobs are on the other side, which is how many places to stand the fight's ground needs. */
        public int mobs() {

            return this.opposition == null ? 1 : this.opposition.mobs().size();
        }

        /**
         * How long this fight is given, and how far apart it starts. A fight against another agent keeps a melee fight's
         * minute and a melee fight's ground whatever loadout the draw handed it, so what the scripted fighter and the
         * checkpoints are rated on does not move under them.
         */
        public int ticks() {

            return this.opposition == null ? Roster.MELEE_TICKS : this.opposition.ticks();
        }

        public int start() {

            return this.opposition == null ? 0 : this.opposition.start();
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

    /** The pairings a training fight is drawn from, and when their file last changed; empty until the trainer writes any. */
    private static long pairsModified = Long.MIN_VALUE;
    private static Pairings pairings = Pairings.NONE;

    /**
     * Frozen checkpoints by iteration, kept only while the matchmaking names them: the ones that take their most likely
     * action, for the rated fights, and the ones that sample as the learner does, for the training fights. Two of each
     * because the same weights are wanted both ways and a brain settles which it is when it is built; see
     * {@link #checkpoint}.
     */
    private static final Map<Integer, Brain> frozen = new HashMap<>();
    private static final Map<Integer, Brain> exploring = new HashMap<>();

    @Nullable
    private static ScriptedBrain scripted;

    /**
     * What became of the fights against each opponent, with each loadout, and on each kind of ground, for the summary once
     * they are all done; and of those, how many the ground itself finished rather than the agent.
     */
    private static final Map<String, Tally> tallies = new LinkedHashMap<>();
    private static final Map<String, Tally> loadoutTallies = new LinkedHashMap<>();
    private static final Map<String, Tally> siteTallies = new LinkedHashMap<>();

    /**
     * No mob griefing and no fire spreading, once per process as the first league fight starts. The league wants both for
     * reasons of its own: see {@link Roster}, where a creeper's crater would stay in a kept world.
     *
     * <p>Midnight and clear weather, which the league wants just as much — the undead would burn at noon, and rain would
     * hurt a blaze and a snow golem and teleport an enderman — are no longer asked for here. Every game test now runs at
     * midnight in clear weather with both clocks stopped, because a plot's roof does not keep the sun off its first tick;
     * see {@code GameTestServerMixin}. Turning the weather off rather than trusting it is still the point: a freshly
     * generated world starts clear, but a worker fights for hours of game time, and the first storm to roll in would be a
     * different fight for every mob the weather touches, for as long as it lasted.
     */
    public static synchronized void prepareWorld(ServerLevel level) {

        if (prepared) {

            return;
        }

        prepared = true;

        level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(false, level.getServer());

        // Fire stays where it is put. Lava is poured next to a quarter of the fights on ground that has none
        // (PouredHazards), and the sites are a hard-linked library shared between workers that hosts a hundred fights
        // apiece: one pool beside a birch forest, left to spread for a minute, and the site is gone for good and for
        // everyone. Nothing about a fight depends on fire spreading, and everything that makes lava lethal — the damage, the
        // burning — is untouched by this rule.
        level.getGameRules().getRule(GameRules.RULE_DOFIRETICK).set(false, level.getServer());

        // Asked for here, before the first fight is drawn, so that a published network a run cannot field — one of another
        // body, or one by a name something else already answers to — stops the worker now rather than mid fight.
        List<String> models = Published.fielded();

        Constants.LOG.info("League fights: {} opponents of {} mobs and squads, the scripted fighter{}{}, {} loadouts; midnight "
                + "and clear for good, no mob griefing", Opposition.fielded().size(), Roster.fielded().size(),
                models.isEmpty() ? "" : ", the published networks " + models,
                directory != null ? " and the run's checkpoints" : "", Loadouts.enabled().size());
    }

    /**
     * Whether the next fight should look for ground with something on it worth knocking an opponent into. Drawn per fight
     * rather than settled per opponent, so every opponent is met on both kinds of ground and the ratings stay comparable.
     */
    public static synchronized boolean wantsHazards(RandomSource random) {

        if (Double.isNaN(hazardShare)) {

            String asked = System.getProperty(HAZARDS, "").trim();

            try {

                hazardShare = asked.isEmpty() ? HAZARD_SHARE : Math.max(0.0D, Math.min(1.0D, Double.parseDouble(asked)));
            }

            catch (NumberFormatException exception) {

                hazardShare = HAZARD_SHARE;
            }
        }

        return hazardShare > 0.0D && random.nextDouble() < hazardShare;
    }

    /** The pairing for the next fight, which is an evaluation when it is handed one. */
    public static synchronized Matchup next(@Nullable Evaluation.Assignment evaluation, RandomSource random) {

        if (!resolved) {

            resolve();
        }

        long fight = fights++;
        List<String> fixed = fixed();

        String name;

        // What the pairing drawn says the agent carries, or null wherever the loadout is not the draw's to say: outside a
        // training run, in an evaluation fight, and in a run whose trainer writes no pairings.
        Loadout loadout = null;

        if (directory == null) {

            // Every worker starts on a different opponent and all of them go round in step, and round the loadouts at the
            // same time, so a short run still sees every opponent and every loadout, and a long one every pairing.
            name = fixed.get((int) ((worker + fight * workers) % fixed.size()));
        }

        else {

            refresh();

            // An evaluation is drawn evenly over the opponents and evenly over the loadouts, because every rating in the
            // league is measured on those fights: weighing them would move the scale under a run that is already going.
            Pairings.Pairing pairing = evaluation != null ? null : pairings.draw(random);

            if (pairing != null) {

                name = pairing.opponent();
                loadout = Loadouts.named(pairing.loadout());
            }

            else {

                name = evaluation != null ? evenly(evaluation.iteration(), random) : draw(random);
            }
        }

        Matchup matchup = matchup(name, loadout, evaluation, random, fight);

        // A checkpoint whose weights will not load is not worth stopping a fight over; a mob always can be fielded. The
        // loadout the pairing asked for is kept, since it is the agent's own half of the draw and nothing about it failed.
        Matchup drawn = matchup != null ? matchup
                : matchup(fixed.get(random.nextInt(fixed.size())), loadout, evaluation, random, fight);

        // A share of the fights against one mob field several of it, all of them fighting, which is the shape a real world has
        // most nights and the league never had; see HostilePacks. A pack never also stands a crowd — fifteen bodies on one
        // worker is the cost of three fights — and the bystander draw is left exactly where it was for every fight that is not
        // one, so the mix of +N_idle players a checkpoint's rate is averaged over does not move.
        Matchup packed = packed(drawn, random);

        return packed != drawn ? packed : crowded(drawn, random);
    }

    /**
     * The same fight with several of its mob on the other side instead of one, on a share of the fights against a single mob:
     * {@code zombie+3_pack}, a player of its own, see {@link HostilePacks}. Unchanged for everything else — a squad, whose
     * composition was chosen to ask one question, and the scripted fighter, a published network or a checkpoint, which are
     * what every rating in the league is measured against.
     *
     * <p>Nothing downstream of here knows a pack from a squad, which is the point: it is an {@link Opposition} of several
     * copies, so the side, the provocation, the reward, the ground, the clock and the replay are the squad machinery that was
     * already there.
     */
    private static Matchup packed(Matchup matchup, RandomSource random) {

        Opposition opposition = matchup.opposition();

        if (opposition == null || opposition.mobs().size() != 1) {

            return matchup;
        }

        int fighting = HostilePacks.wanted(opposition.mobs().get(0), random);

        return fighting <= 1 ? matchup
                : new Matchup(HostilePacks.name(matchup.opponent(), fighting), HostilePacks.pack(opposition, fighting),
                matchup.brain(), matchup.loadout(), matchup.opponentLoadout(), matchup.evaluation(), 0);
    }

    /**
     * The same fight with a share of them given a crowd of monsters standing about it, which the agent can see and which take
     * no interest in it, see {@link Bystanders}. The crowd goes in the opponent's name, since a fight with one is a player of
     * its own: the plain {@code zombie} rating has to keep meaning what it meant in every run before this one.
     *
     * <p>Only against a mob or a squad. A fight against the scripted fighter is the one every rating in the league is
     * measured against, held at 1500, and a fight against a checkpoint or a published network is a policy against a policy;
     * changing any of those under a run already going would move the scale rather than add to the curriculum.
     */
    private static Matchup crowded(Matchup matchup, RandomSource random) {

        if (matchup.opposition() == null) {

            return matchup;
        }

        int standing = Bystanders.wanted(random);

        return standing <= 0 ? matchup : new Matchup(Bystanders.name(matchup.opponent(), standing), matchup.opposition(),
                matchup.brain(), matchup.loadout(), matchup.opponentLoadout(), matchup.evaluation(), standing);
    }

    /**
     * Writes down how a fight ended: {@code win}, {@code loss}, {@code timeout} with both still standing, or
     * {@code draw}, when the opponent went without being killed, as a creeper that blew itself up does.
     *
     * @param landed   whether the opponent hurt the agent at any point
     * @param targeted whether the opponent went for the agent at any point
     * @param cause    what the agent died of when it died, as {@link net.sievert.modularmobai.gametest.util.DeathCauses}
     *                 names it, and {@code -} when it did not
     * @param site     what was on the ground it was fought on, see {@link net.sievert.modularmobai.gametest.terrain.SiteHazards}
     * @param finish   what finished the other side: {@code agent}, or what the terrain did it with, {@code lava} or
     *                 {@code fall}, and {@code -} when nothing was finished at all
     * @param did      what the agent did with its hands over the fight, see {@link Behaviour}
     * @param replay   what this fight's replay is called, or {@code -} when it was not one of the recorded ones
     */
    public static synchronized void record(Matchup matchup, String outcome, long ticks, boolean landed, boolean targeted,
                                           String cause, String site, String finish, Behaviour did, String replay) {

        if (directory != null) {

            write(matchup, outcome, ticks, cause, site, finish, did, replay);
        }

        // Only an opponent that holds still is worth judging a checkpoint by: a mob, the scripted fighter, a published
        // network. Another checkpoint is not, see the class comment.
        if (matchup.evaluation() != null && !matchup.opponent().startsWith(CHECKPOINT)) {

            Evaluation.record(matchup.evaluation(), outcome, ticks, matchup.opponent());
        }

        boolean byTheGround = DeathCauses.byTheGround(finish);

        tallies.computeIfAbsent(matchup.opponent(), ignored -> new Tally()).add(outcome, landed, targeted, byTheGround);
        loadoutTallies.computeIfAbsent(matchup.loadout().name(), ignored -> new Tally()).add(outcome, landed, targeted, byTheGround);
        siteTallies.computeIfAbsent(site, ignored -> new Tally()).add(outcome, landed, targeted, byTheGround);

        if (++recorded == GameTestTuning.arenasInShard(GameTestTuning.arenaCount())) {

            summarise();
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Who
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Every opponent that is not a checkpoint: the mobs and squads, the scripted fighter, the published networks a run
     * named, and outside a training run, the network driving the agents.
     *
     * <p>In a training run this is the opponents on normal, which is what the trainer was told the build fields and what it
     * falls back to before it has weighed anybody. Outside one there is no trainer to open a rung of the difficulty ladder,
     * so the rotation goes round every rung the build has enabled instead, and a quick look fights all of them.
     */
    private static List<String> fixed() {

        List<String> names = new ArrayList<>(directory == null ? Opposition.rotation() : Opposition.fielded());

        names.add(SCRIPTED);
        names.addAll(Published.fielded());

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

    /**
     * The fight against that opponent, with what the draw said the agent carries, or null for a loadout of this side's own
     * choosing: the next in the rotation outside a training run, and an even draw in one.
     */
    @Nullable
    private static Matchup matchup(String name, @Nullable Loadout carried, @Nullable Evaluation.Assignment evaluation,
                                   RandomSource random, long fight) {

        List<Loadout> loadouts = Loadouts.enabled();
        Opposition opposition = Opposition.named(name);

        // Whatever drives the agent gets every loadout there is, bar one pairing: nothing that carries no shot is drawn
        // against a ghast or a phantom, which it could neither kill nor be killed by. See Loadouts#fights. The rotation
        // counts through what is left rather than through all of them, so a run with no trainer still meets every loadout
        // that has a fight here, and an evaluation still draws its opponent evenly and its loadout evenly within that.
        List<Loadout> drawn = Loadouts.against(opposition, loadouts);

        // The scripted fighter draws a bow and raises a shield now, so a run of it that left those out would not be a
        // measurement of the teacher the network copies.
        Loadout loadout = carried;

        if (loadout == null) {

            loadout = directory == null ? drawn.get((int) (fight % drawn.size()))
                    : drawn.get(random.nextInt(drawn.size()));
        }

        if (opposition != null) {

            return new Matchup(name, opposition, null, loadout, null, evaluation, 0);
        }

        Brain brain;
        List<Loadout> opponentLoadouts = loadouts;

        if (name.equals(SCRIPTED)) {

            // The one player whose strength has to stay where it is: every rating in the league is measured against the
            // scripted fighter, held at 1500, so handing it the bow it can now draw would move the whole scale under a run
            // that is already going. Melee only, as it has always fought here.
            brain = scripted();
            opponentLoadouts = melee(loadouts);
        }

        else if (name.equals(SELF)) {

            brain = Brains.network(selfWeights());
        }

        else if (Published.fields(name)) {

            // A published network, on its most likely action, frozen: a fixed policy like the scripted fighter, and rated
            // under its own name. It draws from every loadout, unlike the anchor; see Published.
            brain = Published.brain(name);
        }

        else if (name.startsWith(CHECKPOINT)) {

            brain = directory == null ? null
                    : checkpoint(Integer.parseInt(name.substring(CHECKPOINT.length())), evaluation != null);
        }

        else {

            brain = null;
        }

        if (brain == null) {

            return null;
        }

        return new Matchup(name, null, brain, loadout, opponentLoadouts.get(random.nextInt(opponentLoadouts.size())),
                evaluation, 0);
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

    /**
     * A frozen copy of the agent from an earlier iteration, to fight.
     *
     * <p>Whether it explores is the whole difference between a mirror match and a handicap. In a training fight it samples,
     * exactly as the agent learning against it does, so the two sides are the same policy played the same way and the
     * fight is the even one that self play is for: deployed against a sampling learner it won 74 to 90% of them, and a
     * fifth of every run's fights go to this pool. In a rated fight it takes its most likely action, on both sides, because
     * a rating is a statement about the finished policy and the scale would move under every run if that changed.
     *
     * <p>Its exploration is drawn from a generator of its own rather than from the fight's, which would pull draws out from
     * under the site the fight is on and the mobs in it, and two runs of the same weights would stop agreeing. Seeded by
     * the iteration, so which way a given frozen copy jitters is at least the same question every time it is asked.
     *
     * @param rated whether this fight is one the ratings are worked out from
     */
    @Nullable
    private static Brain checkpoint(int iteration, boolean rated) {

        Map<Integer, Brain> pool = rated ? frozen : exploring;
        Brain brain = pool.get(iteration);

        if (brain != null) {

            return brain;
        }

        Path file = directory.getParent().resolve("weights").resolve(String.format(Locale.ROOT, "%06d%s", iteration, WeightFile.EXTENSION));

        try {

            WeightSet weights = WeightFile.read(file);
            brain = rated ? NeuralBrain.deployed(weights) : NeuralBrain.exploring(weights, new Random(iteration));
            pool.put(iteration, brain);
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
     *
     * <p>A published network goes in with the mobs and the scripted fighter rather than with the checkpoints, because it
     * never learns: the trainer weighs the fixed group by how even each fight is, and keeps the self play share for the
     * run's own moving pool. Its kind is what tells the tier list what it is, see {@link Published}.
     *
     * <p>The loadouts the agent is armed with go in the same file, under the kind {@code loadout}, because a fight is drawn
     * as a pairing of one of them with an opponent and the trainer has no other way of knowing which this build fields: a run
     * told {@code -PleagueLoadouts=bow,crossbow} draws from two. They carry no cap: a cap is on an opponent, and holds down
     * everything the agent might carry against it at once.
     *
     * <p>The last column, {@code reach}, is the other thing only the game knows: which loadouts carry nothing that shoots
     * ({@code melee}) and which opponents nothing but a shot can ever touch ({@code unreachable}), so that the trainer can
     * refuse to pair the two. A trainer too old to read it pairs them as it always did; a build too old to write it leaves
     * the column off and the trainer bars nothing. See {@link Loadouts#fights} and trainer/mmai/league.py.
     */
    private static void writeRoster() throws IOException {

        StringBuilder out = new StringBuilder("opponent,kind,cap,reach\n");

        for (String name : Opposition.fielded()) {

            Opposition opposition = Opposition.named(name);
            out.append(String.format(Locale.ROOT, "%s,%s,%.5f,%s\n", name, opposition.kind(), opposition.trainingCap(),
                    opposition.unreachable() ? UNREACHABLE : REACHED));
        }

        out.append(SCRIPTED).append(",scripted,1.00000,").append(REACHED).append('\n');

        for (String name : Published.fielded()) {

            out.append(String.format(Locale.ROOT, "%s,%s,1.00000,%s\n", name, Published.KIND, REACHED));
        }

        for (Loadout loadout : Loadouts.enabled()) {

            out.append(String.format(Locale.ROOT, "%s,%s,1.00000,%s\n", loadout.name(), Loadouts.KIND,
                    Loadouts.melee(loadout) ? MELEE : REACHED));
        }

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

    /** Reads the matchmaking and the pairings again when their files have changed. */
    private static void refresh() {

        refreshMatchmaking();
        refreshPairings();
    }

    /**
     * Reads the pairings again when their file has changed. A trainer that writes none, or takes the file away, leaves the
     * table empty and the loadout to the even draw it always was; see {@link Pairings}.
     */
    private static void refreshPairings() {

        Path file = directory.resolve("pairs.csv");

        try {

            if (!Files.isRegularFile(file)) {

                pairings = Pairings.NONE;
                pairsModified = Long.MIN_VALUE;
                return;
            }

            long modified = Files.getLastModifiedTime(file).toMillis();

            if (modified == pairsModified) {

                return;
            }

            pairings = Pairings.parse(Files.readAllLines(file, StandardCharsets.UTF_8), League::fieldsOpponent,
                    name -> Loadouts.named(name) != null, Loadouts::fields);

            pairsModified = modified;
        }

        catch (IOException | RuntimeException exception) {

            // Caught mid write, most likely; the next fight looks again.
            Constants.LOG.debug("Could not read the league's pairings yet: {}", exception.toString());
        }
    }

    /** Whether this process can field an opponent of that name, whatever kind of thing it is. */
    private static boolean fieldsOpponent(String name) {

        return Opposition.named(name) != null || name.equals(SCRIPTED) || Published.fields(name)
                || name.matches(CHECKPOINT + "\\d+");
    }

    /** Reads the matchmaking again when its file has changed, and lets go of any checkpoint it no longer names. */
    private static void refreshMatchmaking() {

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

                if (!fieldsOpponent(name) || !(share > 0.0D)) {

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
            exploring.keySet().retainAll(named);
        }

        catch (IOException | RuntimeException exception) {

            // Caught mid write, most likely; the next fight looks again.
            Constants.LOG.debug("Could not read the league's matchmaking yet: {}", exception.toString());
        }
    }

    /**
     * One line a fight, appended to this worker's own file:
     *
     * <pre>
     *   iteration,kind,opponent,loadout,opponent_loadout,outcome,ticks,cause,site,finish,weapon,swaps,uses,shots,replay
     * </pre>
     *
     * <p>The columns grow to the right and never move, because a run's file is appended to across builds: a worker resumed
     * after a column was added leaves the older lines exactly as they were, and both the trainer and the viewer read a short
     * line as one that simply does not say those things. The last five are the newest — what the agent held and did with it,
     * and which replay is of this very fight, so a row in the viewer can open it.
     */
    private static void write(Matchup matchup, String outcome, long ticks, String cause, String site, String finish,
                              Behaviour did, String replay) {

        int iteration = matchup.evaluation() != null ? matchup.evaluation().iteration()
                : Brains.defaultBrain() instanceof NeuralBrain neural ? neural.weights().iteration() : -1;

        String line = String.format(Locale.ROOT, "%d,%s,%s,%s,%s,%s,%d,%s,%s,%s,%s,%d,%d,%d,%s%n", iteration,
                matchup.evaluation() != null ? "eval" : "train", matchup.opponent(), matchup.loadout().name(),
                matchup.opponentLoadout() == null ? "-" : matchup.opponentLoadout().name(), outcome, ticks, cause, site, finish,
                did.weapon(), did.swaps(), did.uses(), did.shots(), replay);

        try {

            Files.writeString(directory.resolve("results").resolve(String.format(Locale.ROOT, "w%02d.csv", worker)), line,
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }

        catch (IOException exception) {

            fail("Could not write a league result", exception);
        }
    }

    /**
     * Prints what became of the fights against every opponent, with every loadout and on every kind of ground, and hands
     * the same to the build when this is one worker of a parallel run, which adds the workers up. Loadouts go in the file
     * as loadout:NAME and kinds of ground as site:NAME.
     */
    private static void summarise() {

        String header = "========= League fights over " + recorded + " runs =========";
        StringBuilder file = new StringBuilder();

        System.out.println(header);
        print("opponent", tallies, "", file);
        print("agent's loadout", loadoutTallies, "loadout:", file);
        print("ground", siteTallies, "site:", file);
        System.out.println("  hit it: fights in which the opponent hurt the agent; went for: in which it kept the agent as its target;");
        System.out.println("  ground: wins in which the ground finished the opponent rather than the agent, which is the whole point of");
        System.out.println("  fighting on lava and cliff edges at all");
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

        System.out.println(String.format(Locale.ROOT, "  %-28s %6s %7s %7s %9s %7s %6s %9s %7s", title, "fights", "won %", "lost %",
                "timeout %", "draw %", "hit it", "went for", "ground"));

        for (Map.Entry<String, Tally> entry : table.entrySet()) {

            Tally tally = entry.getValue();
            double fights = Math.max(1, tally.fights);

            System.out.println(String.format(Locale.ROOT, "  %-28s %6d %7.1f %7.1f %9.1f %7.1f %6d %9d %7d", entry.getKey(),
                    tally.fights, 100.0D * tally.wins / fights, 100.0D * tally.losses / fights, 100.0D * tally.timeouts / fights,
                    100.0D * tally.draws / fights, tally.landed, tally.targeted, tally.finished));

            file.append(String.format(Locale.ROOT, "%s%s %d %d %d %d %d %d %d %d%n", prefix, entry.getKey(), tally.fights, tally.wins,
                    tally.losses, tally.timeouts, tally.draws, tally.landed, tally.targeted, tally.finished));
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

        /** Of the wins, the ones the ground finished rather than the agent: a fall, a lava lake, a cactus. */
        private int finished;

        private void add(String outcome, boolean hurtTheAgent, boolean wentForIt, boolean groundFinishedIt) {

            this.fights++;

            switch (outcome) {

                case "win" -> this.wins++;
                case "loss" -> this.losses++;
                case "timeout" -> this.timeouts++;
                default -> this.draws++;
            }

            this.landed += hurtTheAgent ? 1 : 0;
            this.targeted += wentForIt ? 1 : 0;
            this.finished += groundFinishedIt ? 1 : 0;
        }
    }
}
