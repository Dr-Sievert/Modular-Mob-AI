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

/**
 * What the agent is up against in one league fight: one mob, or a squad of several at once.
 *
 * <p>A league opponent used to be one mob, so its name was the mob's. Now it is whatever the fight is against, and the
 * name says which: {@code zombie} for one, {@code 2x_zombie} for two of them, {@code zombie+skeleton} for one of each.
 * Everything downstream treats that name as a player of its own, which is the point: <b>difficulty is not additive</b>. Two
 * zombies are not twice a zombie, and a zombie with a skeleton behind it is a different fight from either, so each
 * composition earns its own rating, its own share of the training fights and its own row in the tables. Nothing anywhere
 * adds a squad's members up.
 *
 * <pre>
 *   -Dmodular_mob_ai.league.opponents=NAME,NAME   only these, by name, squads included; every one of them unless given
 * </pre>
 *
 * <h2>Which squads</h2>
 *
 * <p>Every pair of thirty seven mobs is over six hundred fights to rate, and most of them would say nothing that another
 * pair did not. So the squads are chosen rather than generated, each for a question it is the cleanest way to ask:
 *
 * <ul>
 *   <li><b>The same mob twice or three times</b>, at three points of the tier list: {@code 2x_zombie} at the bottom,
 *       {@code 2x_vindicator} where the agent already wins nearly every fight, {@code 3x_silverfish} where the opponent is
 *       barely an opponent on its own and a swarm underfoot together. What a second and a third body cost, with nothing
 *       else changed.</li>
 *   <li><b>Two of the same at range</b>, {@code 2x_skeleton}: crossfire from two directions, which no amount of circling
 *       one of them answers.</li>
 *   <li><b>A body in front and a shot from behind it</b>, {@code zombie+skeleton} and {@code witch+zombie}: the pair a
 *       player meets every night, and the same shape with potions instead of arrows.</li>
 *   <li><b>What the game itself sends together</b>, {@code pillager+vindicator}: an illager patrol, a crossbow behind an
 *       axe.</li>
 *   <li><b>Two that are quicker than the agent</b>, {@code spider+cave_spider}: both climb, one poisons.</li>
 *   <li><b>Two that hit hardest</b>, {@code 2x_wither_skeleton}: reach on the agent's sword, twice over.</li>
 *   <li><b>Two that end the fight by ending themselves</b>, {@code 2x_creeper}: one blast sets off the other.</li>
 *   <li><b>One in the air and one on the ground</b>, {@code phantom+zombie}: the first fight that wants a bow and a
 *       shield in the same minute.</li>
 * </ul>
 *
 * <p>The warden is in no squad. One of it is already a fight nobody wins, and a second opponent beside it would only be a
 * second way to say so.
 *
 * <h2>Sides</h2>
 *
 * <p>A squad fights as a side: the agent on one team and the squad on another, through {@link
 * net.sievert.modularmobai.allegiance.Allegiance}, so every one of them goes for the agent rather than wandering off
 * after each other, and the agent sees every one of them as an enemy whatever kind of mob it is. A fight against one mob
 * is set up exactly as it always was, with no teams anywhere, so the ratings the roster already has still mean what they
 * did.
 */
public record Opposition(String name, List<Roster.Member> mobs) {

    /** Whether this is a squad rather than a single mob, which is what the trainer files its rating under. */
    public String kind() {

        return this.mobs.size() > 1 ? "squad" : "mob";
    }

    /** The largest share of a run's training fights it may take: no more than the most capped mob on it allows. */
    public double trainingCap() {

        return this.mobs.stream().mapToDouble(Roster.Member::trainingCap).min().orElse(1.0D);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Who there is to fight
    // ---------------------------------------------------------------------------------------------------------------

    private static final String PROPERTY = "modular_mob_ai.league.opponents";

    /**
     * Every squad, each written as the mobs standing on it; the name follows from that, see {@link #squadName}, so a
     * squad can never be named one thing and made of another. The reasons for each are in the class comment above.
     */
    private static final List<List<String>> SQUADS = List.of(
            List.of("zombie", "zombie"),
            List.of("vindicator", "vindicator"),
            List.of("silverfish", "silverfish", "silverfish"),
            List.of("skeleton", "skeleton"),
            List.of("zombie", "skeleton"),
            List.of("witch", "zombie"),
            List.of("pillager", "vindicator"),
            List.of("spider", "cave_spider"),
            List.of("wither_skeleton", "wither_skeleton"),
            List.of("creeper", "creeper"),
            List.of("phantom", "zombie"));

    /** Everyone this process fields, by name, in the order a run without a trainer goes through them; read once. */
    @Nullable
    private static Map<String, Opposition> fielded;

    private static synchronized Map<String, Opposition> all() {

        if (fielded == null) {

            List<String> named = names();
            Map<String, Opposition> found = new LinkedHashMap<>();

            for (Roster.Member member : Roster.fielded()) {

                found.put(member.name(), new Opposition(member.name(), List.of(member)));
            }

            for (List<String> squad : SQUADS) {

                String name = squadName(squad);

                // A squad is fielded when it is named, or when nothing is named and every mob on it is fielded: asking for
                // 2x_zombie alone should give you that squad, and asking for zombie alone should not.
                if (named.isEmpty() ? !found.keySet().containsAll(squad) : !named.contains(name)) {

                    continue;
                }

                List<Roster.Member> mobs = new ArrayList<>(squad.size());

                for (String member : squad) {

                    mobs.add(Roster.any(member));
                }

                found.put(name, new Opposition(name, List.copyOf(mobs)));
            }

            // Not Map.copyOf, which keeps nothing of the order these were put in, and the order is what a run with no
            // trainer goes round in.
            fielded = Collections.unmodifiableMap(found);

            if (!named.isEmpty()) {

                Constants.LOG.info("League fights field {} opponents by name: {}", fielded.size(), fielded.keySet());
            }
        }

        return fielded;
    }

    /** The names this process was told to field, lower case, or empty for all of them. */
    private static List<String> names() {

        String named = System.getProperty(PROPERTY, "").trim();

        if (named.isEmpty()) {

            return List.of();
        }

        return Arrays.stream(named.toLowerCase(Locale.ROOT).split(",")).map(String::trim).filter(name -> !name.isEmpty()).toList();
    }

    /** Everyone there is to fight that is not an agent, mobs first and then squads, in a fixed order. */
    public static List<String> fielded() {

        return List.copyOf(all().keySet());
    }

    /** What that name is a fight against, or null when this process fields nobody of that name. */
    @Nullable
    public static Opposition named(String name) {

        return all().get(name);
    }

    /**
     * What a squad is called: each run of the same mob as {@code 2x_zombie} or, on its own, just {@code zombie}, joined by
     * plus signs. So two zombies are {@code 2x_zombie}, a zombie and a skeleton {@code zombie+skeleton}, and two zombies
     * with a skeleton {@code 2x_zombie+skeleton}. No spaces and no commas: these names travel through command lines,
     * comma separated files and a system property.
     */
    static String squadName(List<String> squad) {

        StringBuilder name = new StringBuilder();

        for (int at = 0; at < squad.size(); ) {

            int same = at + 1;

            while (same < squad.size() && squad.get(same).equals(squad.get(at))) {

                same++;
            }

            if (!name.isEmpty()) {

                name.append('+');
            }

            int count = same - at;

            if (count > 1) {

                name.append(count).append('x').append('_');
            }

            name.append(squad.get(at));
            at = same;
        }

        return name.toString();
    }
}
