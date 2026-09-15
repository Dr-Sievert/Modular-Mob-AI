package net.sievert.modularmobai.gametest.league;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

import net.minecraft.util.RandomSource;

/**
 * What a training fight is drawn as: one loadout against one opponent, with the share of the fights the trainer wants that
 * pairing to have. This is the table in {@code runs/RUN/league/pairs.csv} and the draw from it, and nothing else; who wrote
 * it and why each share is what it is belong to trainer/mmai/league.py.
 *
 * <p><b>Why the pairing and not the opponent.</b> The loadout used to be drawn here, by itself, after the trainer had
 * chosen the opponent. So a bow was handed out against a creeper it should kite exactly as often as against a ghast it
 * cannot reach, and what the run learned about drawing a bow was averaged over the matchups where a bow is the answer and
 * the matchups where it is hopeless: measured on a league run, the ranged loadouts won about 40% of their fights and the
 * melee ones far more. Drawing the pairing itself spends the fights where a loadout can still learn something, and hands a
 * loadout that is losing more of the matchups it is losing.
 *
 * <p>The table is loadouts times opponents, ten against 62 at the start of a run and against 184 once every rung of the
 * difficulty ladder is open, so 620 to 1,840 pairings and a few tens of kilobytes of file. It is read once whenever the
 * trainer writes a new one, not per fight.
 *
 * <p>Only the training fights are drawn from it. An evaluation fight draws its opponent evenly and its loadout evenly,
 * because every rating in the league is measured on those; see {@link League}.
 */
public final class Pairings {

    /** A loadout the agent carries against an opponent it meets, both by the names the league writes in its results. */
    public record Pairing(String loadout, String opponent) {}

    /** No table at all: what a run has before the trainer has written one, and what a run with no trainer always has. */
    public static final Pairings NONE = new Pairings(List.of(), new double[0]);

    private final List<Pairing> pairings;

    /** The shares added up along the list, so a draw is one uniform number and a walk to the first entry past it. */
    private final double[] cumulative;

    private Pairings(List<Pairing> pairings, double[] cumulative) {

        this.pairings = pairings;
        this.cumulative = cumulative;
    }

    /**
     * The table as those lines say, which are pairs.csv: a header, then {@code loadout,opponent,share} and whatever the
     * trainer writes beside them.
     *
     * <p>A pairing this process cannot field is dropped rather than refused, because both sides of the table are the
     * trainer's idea of what the workers field and a run is allowed to be told to field fewer: {@code -PleagueLoadouts=bow}
     * narrows the loadouts, {@code -PleagueOpponents} the opponents, and the trainer only learns of it on its next read of
     * roster.csv. What is left still adds up to whatever it adds up to, and the draw takes its shares in proportion.
     *
     * @param opponents whether this process fields an opponent of that name
     * @param loadouts  whether this process arms the agent with a loadout of that name
     * @param pairs     whether this process would field the two of them together, which is the one rule that looks at both:
     *                  a loadout carrying nothing that shoots is never drawn against something it cannot reach, see
     *                  {@link Loadouts#fights}. The trainer gives such a pairing no share at all, so nothing should reach
     *                  here; a build that meets an older trainer's table drops it the same way it drops a loadout it does
     *                  not field
     */
    public static Pairings parse(List<String> lines, Predicate<String> opponents, Predicate<String> loadouts,
                                 BiPredicate<String, String> pairs) {

        List<Pairing> found = new ArrayList<>();
        List<Double> shares = new ArrayList<>();

        for (String line : lines) {

            String[] parts = line.split(",");

            if (parts.length < 3 || parts[0].trim().equals("loadout")) {

                continue;
            }

            String loadout = parts[0].trim();
            String opponent = parts[1].trim();
            double share;

            try {

                share = Double.parseDouble(parts[2].trim());
            }

            catch (NumberFormatException exception) {

                continue;
            }

            if (!(share > 0.0D) || !loadouts.test(loadout) || !opponents.test(opponent)
                    || !pairs.test(loadout, opponent)) {

                continue;
            }

            found.add(new Pairing(loadout, opponent));
            shares.add(share);
        }

        if (found.isEmpty()) {

            return NONE;
        }

        double[] running = new double[shares.size()];
        double sum = 0.0D;

        for (int index = 0; index < running.length; index++) {

            sum += shares.get(index);
            running[index] = sum;
        }

        return new Pairings(List.copyOf(found), running);
    }

    /** One pairing, drawn in proportion to the shares, or null when there is no table to draw from. */
    @Nullable
    public Pairing draw(RandomSource random) {

        if (this.pairings.isEmpty()) {

            return null;
        }

        double point = random.nextDouble() * this.total();

        for (int index = 0; index < this.cumulative.length; index++) {

            if (point < this.cumulative[index]) {

                return this.pairings.get(index);
            }
        }

        return this.pairings.get(this.pairings.size() - 1);
    }

    public boolean isEmpty() {

        return this.pairings.isEmpty();
    }

    public List<Pairing> pairings() {

        return this.pairings;
    }

    /** What the shares add up to, which is one where nothing was capped and nothing dropped, and less where something was. */
    public double total() {

        return this.cumulative.length == 0 ? 0.0D : this.cumulative[this.cumulative.length - 1];
    }

    /** One pairing's own share, as the trainer wrote it. */
    public double share(int index) {

        return index == 0 ? this.cumulative[0] : this.cumulative[index] - this.cumulative[index - 1];
    }
}
