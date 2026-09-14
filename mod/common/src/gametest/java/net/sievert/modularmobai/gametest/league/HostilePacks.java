package net.sievert.modularmobai.gametest.league;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.sievert.modularmobai.Constants;

/**
 * Several of the same mob, all of them fighting, which is the fight a real world has most nights and the league had never
 * once fielded.
 *
 * <p>The league's opponent is one mob, or one of eleven chosen squads of two or three. Its crowd, {@link Bystanders}, is the
 * other thing a real world has — monsters in view that take no interest — and a quarter of the fights now stand one. Between
 * them they leave a hole with the owner's own report in it: <b>three to six hostiles that all come for the agent at once</b>.
 * Nothing in the league ever did that, and out in a game it is the ordinary case, because a monster now goes after a playable
 * agent the way it goes after a player (see the mod's HuntAgentsGoal) and a night puts several of them within thirty two
 * blocks of each other. "He still gets massively overwhelmed" is what a curriculum of one opponent buys.
 *
 * <pre>
 *   -Dmodular_mob_ai.league.hostileCrowds=0.1   the share of fights against one mob that field a pack of it; 0 for none
 * </pre>
 *
 * <p>A pack is not a new kind of thing, which is what makes it cheap: it is an {@link Opposition} of several copies of the
 * mob the fight was already drawn against, so every piece of machinery a squad already goes through carries it. They are put
 * on a side of their own, so each comes for the agent rather than for its own; the episode is given all of them, so the reward
 * pays for each exactly once and winning means every one of them down; the ground is asked for a place to stand for each; the
 * clock is the mob's own; and the replay records every one of them as an opponent. See {@code AgentLeagueGameTest}, which
 * needed no change at all for this.
 *
 * <p>What is drawn, and what is not:
 *
 * <ul>
 *   <li><b>Only against one mob.</b> Never against a squad — the eleven squads are chosen compositions, each rated for a
 *       question it asks, and multiplying one would be a different question nobody asked — and never against the scripted
 *       fighter, a published network or a checkpoint, for the same reason {@link Bystanders} leaves those alone: the anchor
 *       and the policies are what every rating in the league is measured against, and they may not move under a run that is
 *       already going.</li>
 *   <li><b>Copies, not a mixed pack.</b> A mixed pack was the other option and it is the one the squads already cover:
 *       {@code zombie+skeleton} is a body in front and a shot behind, {@code pillager+vindicator} is a patrol. What the league
 *       had no way of saying is <b>how many</b>, with nothing else changed, which is exactly what {@code 2x_zombie} and
 *       {@code 3x_silverfish} were chosen to ask on three rungs of the tier list; a pack is that question asked at every
 *       count and against every mob.</li>
 *   <li><b>Weighted small</b>, and for the reason a crowd is: {@link #RUNNING}, one over the number of extra bodies, so a
 *       pair comes up about five times as often as six of them. A flat draw over 2 to 6 would spend most of the curriculum on
 *       the counts the agent has no chance in yet, which is the mistake the bystander draw was already caught making and
 *       measured: see findings.md.</li>
 *   <li><b>Never with a crowd as well.</b> A fight is a pack or it has bystanders, never both. Two reasons, and neither is
 *       arbitrary: a pack of six with nine standing about it is fifteen bodies to tick on one worker, which is the cost of
 *       three ordinary fights; and the bystander draw is left exactly where it was, asked of every fight that is not a pack,
 *       so the mix of {@code +N_idle} players a checkpoint's evaluated rate is averaged over does not move — the objection
 *       that held a curriculum ramp back twice, see findings.md.</li>
 *   <li><b>Only what makes a pack.</b> The same narrowing a crowd gets, and for the same reasons: a monster, so each one is
 *       an enemy on sight and fills a slot; on its feet, since a flyer's pack would start stacked in the air over ground it
 *       cannot be fought on; and not the warden, which is a fight nobody wins once, let alone four times.</li>
 * </ul>
 *
 * <p>It is rated as a player of its own, {@code zombie+3_pack} — the zombie the fight was drawn against and three more of it.
 * That is the {@code +N_idle} convention with the other word on the end, and it is the right shape for the same reason: the
 * plain {@code zombie} rating has to keep meaning what it meant in every run before this one, and the trainer matchmakes over
 * the roster it is handed, which these names are not in. The trainer's {@code base()} strips the suffix to find the opponent a
 * pack is a pack of, so the kind and the cap are inherited and the tier list gets a row; see trainer/mmai/league.py.
 */
public final class HostilePacks {

    private HostilePacks() {}

    private static final String PROPERTY = "modular_mob_ai.league.hostileCrowds";

    /**
     * What share of the fights against one mob field a pack of it instead. A tenth, which is deliberately less than the
     * quarter a crowd of bystanders gets: a pack is the hardest fight in the league at every count above two, it costs the
     * most per fight of anything here, and the plain fight against one opponent is still what the agent has to be able to
     * win. A run that wants more of it says so.
     */
    private static final double SHARE = 0.1D;

    /** How many fight, when a pack is drawn at all: a pair at the fewest, six at the most, which is ten slots all but full. */
    private static final int FEWEST = 2;
    private static final int MOST = 6;

    /**
     * How the size is drawn once a fight is having a pack at all: a weight of one over the number of <b>extra</b> bodies, so a
     * pair comes up five times as often as six of them and every size between is somewhere in order. Running totals rather
     * than the weights themselves, so one draw of the random source answers it.
     *
     * <p>The same skew a crowd's size is drawn with, {@link Bystanders}, and for the same measured reason. A flat draw over the bystander counts
     * spent five crowded fights in nine on the five counts the agent won about a third of, and four thousand iterations of a
     * plateau said nothing was coming out of them; weighting it small moved the spend onto the counts where the rate was still
     * moving without closing the tail. A pack is harder than a crowd of the same size at every count, so a flat draw here
     * would make the same mistake worse. Six is still drawn about one pack in eleven, so {@code +5_pack} keeps being rated and
     * keeps being trained on: a count that stops being drawn is a row a run is judged on that quietly stops being fed.
     */
    private static final double[] RUNNING = running();

    /** What the count is written after, so a name says what the fight was: zombie+3_pack. */
    private static final String SUFFIX = "_pack";

    private static double share = Double.NaN;

    @Nullable
    private static List<Roster.Member> packing;

    /**
     * How many of that mob the next fight against it fields, and nought for the ordinary fight against one. Drawn per fight
     * rather than settled per opponent, so an opponent is met both ways and the plain rating and the pack's are both fed from
     * the same draw.
     *
     * <p>Two draws, as a crowd has: whether this fight is a pack at all, which is the share a run turns up and down, and then
     * how many of them, which is weighted small. Keeping them apart is what lets the skew be changed without touching what a
     * run asked for.
     *
     * @param member the mob the fight was already drawn against, which is what the pack is made of
     * @return how many fight, at least {@link #FEWEST}, or nought for no pack at all
     */
    public static synchronized int wanted(Roster.Member member, RandomSource random) {

        double asked = share();

        // packing() for the one line a run's log gets about its packs; what this fight is allowed is the rule itself, asked of
        // the member rather than of a list it might or might not be the same instance as.
        if (!(asked > 0.0D) || packing().isEmpty() || !packs(member) || random.nextDouble() >= asked) {

            return 0;
        }

        double drawn = random.nextDouble() * RUNNING[RUNNING.length - 1];

        for (int index = 0; index < RUNNING.length; index++) {

            if (drawn < RUNNING[index]) {

                return FEWEST + index;
            }
        }

        // Only a double landing exactly on the total gets here, which the draw's own half-open range says it cannot; the most
        // is the honest answer to it rather than an exception about the last bit of a mantissa.
        return MOST;
    }

    /**
     * The chance a fight that is having a pack at all fields that many: about 44% for a pair, down to about 9% for six, and
     * nought for a size the draw cannot produce. Public for the same reason {@link #share()} is — a test that wrote the
     * weights out a second time would be a test of its own copy of them.
     */
    public static double chance(int fighting) {

        if (fighting < FEWEST || fighting > MOST) {

            return 0.0D;
        }

        int index = fighting - FEWEST;
        double below = index == 0 ? 0.0D : RUNNING[index - 1];

        return (RUNNING[index] - below) / RUNNING[RUNNING.length - 1];
    }

    /** The running totals of one over the number of extra bodies, from {@link #FEWEST} to {@link #MOST}. */
    private static double[] running() {

        double[] totals = new double[MOST - FEWEST + 1];
        double running = 0.0D;

        for (int fighting = FEWEST; fighting <= MOST; fighting++) {

            running += 1.0D / (fighting - 1);
            totals[fighting - FEWEST] = running;
        }

        return totals;
    }

    /**
     * The share this process was told, read once: what the build passed, or a tenth. Public because a test that asserted
     * against a tenth would fail a run that had been told something else and was doing exactly as it was told.
     */
    public static synchronized double share() {

        if (Double.isNaN(share)) {

            String asked = System.getProperty(PROPERTY, "").trim();

            try {

                share = asked.isEmpty() ? SHARE : Math.max(0.0D, Math.min(1.0D, Double.parseDouble(asked)));
            }

            catch (NumberFormatException exception) {

                share = SHARE;
            }
        }

        return share;
    }

    /** What a fight against that many of one mob is called: {@code zombie+3_pack}, and the plain name for the ordinary one. */
    public static String name(String opponent, int fighting) {

        return fighting <= 1 ? opponent : opponent + "+" + (fighting - 1) + SUFFIX;
    }

    /**
     * The same opposition with that many of its mob on it, all of them fighting. Everything that makes them a side, provokes
     * each of them, pays for each of them once and waits for the last of them down is the squad machinery they now go through;
     * see {@code AgentLeagueGameTest}.
     *
     * <p>The rung is the one the fight was drawn on, so a pack of a hard zombie is four hard zombies: how hard the mobs on one
     * side spawn is a property of the opponent and not of how many there are.
     */
    public static Opposition pack(Opposition opposition, int fighting) {

        Roster.Member member = opposition.mobs().get(0);
        List<Roster.Member> mobs = new ArrayList<>(fighting);

        for (int on = 0; on < fighting; on++) {

            mobs.add(member);
        }

        return new Opposition(name(opposition.name(), fighting), List.copyOf(mobs), opposition.difficulty());
    }

    /** The roster mobs a pack can be made of, read once; see the class comment for why it is narrower than the roster. */
    private static synchronized List<Roster.Member> packing() {

        if (packing == null) {

            packing = Roster.fielded().stream().filter(HostilePacks::packs).toList();

            Constants.LOG.info("League fights field a pack of the same mob, all of them fighting, on {} of the {} mobs at a "
                            + "share of {}, {} to {} of them and {} on average: {}", packing.size(), Roster.fielded().size(),
                    share(), FEWEST, MOST, String.format(java.util.Locale.ROOT, "%.2f", mean()), weighting());
        }

        return packing;
    }

    /** How many fight in the average pack, so a run's own log says what its packs cost rather than what the code meant. */
    private static double mean() {

        double mean = 0.0D;

        for (int fighting = FEWEST; fighting <= MOST; fighting++) {

            mean += fighting * chance(fighting);
        }

        return mean;
    }

    /** What the skew comes out as, per size, for the same log. */
    private static String weighting() {

        StringBuilder said = new StringBuilder();

        for (int fighting = FEWEST; fighting <= MOST; fighting++) {

            said.append(fighting == FEWEST ? "" : ", ").append(fighting).append(':')
                    .append(Math.round(chance(fighting) * 100.0D)).append('%');
        }

        return said.toString();
    }

    /** Whether that mob makes a pack: a monster, so each takes a slot on sight; on its feet; and not the warden. */
    private static boolean packs(Roster.Member member) {

        return member.type().getCategory() == MobCategory.MONSTER && member.height() == 0
                && member.type() != EntityType.WARDEN;
    }
}
