package net.sievert.modularmobai.gametest.league;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.level.Level;
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
 *   -Dmodular_mob_ai.league.opponents=NAME,NAME      only these, by name, squads included; every one of them unless given
 *   -Dmodular_mob_ai.league.difficulties=normal,hard which rungs of the ladder a run with no trainer goes round
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
 *
 * <h2>The difficulty ladder</h2>
 *
 * <p>Every opponent has three rungs, and the name carries which: {@code zombie} on normal, {@code zombie(easy)} and
 * {@code zombie(hard)} on the others. A rung is a player of its own for the same reason a squad is, so a run can be
 * winning 90% against a zombie and 40% against a hard one and the tier list says both.
 *
 * <p>What a rung changes is the {@link DifficultyInstance} the mob's own finalizeSpawn is handed, which is the game's own
 * knob and what arms a mob: on hard it is likelier to spawn in armour, likelier to have that armour and its weapon
 * enchanted, and a zombie draws higher rolls of the bonus health, damage and follow range every zombie rolls for; a
 * spider gets a potion effect it never gets below hard. On easy all of that thins out. Nothing else about the fight
 * changes: the same mob, the same site, the same clock.
 *
 * <p>The handful of things vanilla decides mid fight from the level's own difficulty setting rather than from a spawn,
 * a husk's hunger and a zombie's reinforcements among them, stay on normal for every fight on every rung. Difficulty
 * there is a property of the whole level and fifty fights share one level, so there is nowhere to put a per fight answer.
 * That also means a normal fight is byte for byte the fight it was before the ladder existed, which is what keeps the
 * ratings already earned worth something.
 *
 * <p>Which rungs a run meets is not decided here. A training run's trainer opens one for an opponent when the agent's
 * evaluated win rate says there is nothing left to learn on the rung it is on, or nothing to be learned at all yet; see
 * trainer/mmai/league.py. A run with no trainer goes round every rung the build has enabled, normal and hard by default,
 * so the quick look with {@code scripts\test.ps1 -League} fights both.
 */
public record Opposition(String name, List<Roster.Member> mobs, Difficulty difficulty) {

    /** Whether this is a squad rather than a single mob, which is what the trainer files its rating under. */
    public String kind() {

        return this.mobs.size() > 1 ? "squad" : "mob";
    }

    /** The largest share of a run's training fights it may take: no more than the most capped mob on it allows. */
    public double trainingCap() {

        return this.mobs.stream().mapToDouble(Roster.Member::trainingCap).min().orElse(1.0D);
    }

    /**
     * How long this fight is given, and how far apart it starts: whatever the mob on it that wants most asks for, since a
     * squad with a skeleton in it is a shooting match whoever else is standing there.
     */
    public int ticks() {

        return this.mobs.stream().mapToInt(Roster.Member::ticks).max().orElse(Roster.MELEE_TICKS);
    }

    public int start() {

        return this.mobs.stream().mapToInt(Roster.Member::start).max().orElse(0);
    }

    /**
     * Whether nothing but a shot can finish this fight: <b>any</b> mob on it that never comes within reach, since one left
     * standing is the clock running out however well the rest of it went. So {@code ghast} and {@code phantom+zombie} are
     * both of them, and a loadout that carries nothing to shoot with is never drawn against either; see
     * {@link Roster.Member#unreachable} and {@link Loadouts#fights}.
     */
    public boolean unreachable() {

        return this.mobs.stream().anyMatch(Roster.Member::unreachable);
    }

    /**
     * What this fight's mobs are spawned with, which is {@link Level#getCurrentDifficultyAt} with the rung's difficulty in
     * place of the level's own: the same day time, the same inhabited time and the same moon, since those are the fight's
     * ground and not its difficulty.
     */
    public DifficultyInstance spawnDifficulty(Level level, BlockPos at) {

        long inhabited = 0L;
        float moon = 0.0F;

        if (level.hasChunkAt(at)) {

            moon = level.getMoonBrightness();
            inhabited = level.getChunkAt(at).getInhabitedTime();
        }

        return new DifficultyInstance(this.difficulty, level.getDayTime(), inhabited, moon);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Who there is to fight
    // ---------------------------------------------------------------------------------------------------------------

    private static final String PROPERTY = "modular_mob_ai.league.opponents";
    private static final String DIFFICULTIES = "modular_mob_ai.league.difficulties";

    /**
     * One rung of the ladder: what a run calls it, what it puts on the end of an opponent's name, and the difficulty it
     * spawns mobs at. Normal adds nothing, so every name the league had before the ladder means exactly what it did.
     */
    private record Rung(String name, String suffix, Difficulty difficulty) {}

    /** The rungs, in the order a rotation goes through an opponent's, so a mob stands beside its harder self. */
    private static final List<Rung> RUNGS = List.of(
            new Rung("normal", "", Difficulty.NORMAL),
            new Rung("hard", "(hard)", Difficulty.HARD),
            new Rung("easy", "(easy)", Difficulty.EASY));

    /** The rungs a run with no trainer goes round unless it names others: normal, and hard to prove the rung works. */
    private static final String DEFAULT_RUNGS = "normal,hard";

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

                found.put(member.name(), new Opposition(member.name(), List.of(member), Difficulty.NORMAL));
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

                found.put(name, new Opposition(name, List.copyOf(mobs), Difficulty.NORMAL));
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

    /**
     * Everyone there is to fight that is not an agent, mobs first and then squads, in a fixed order, each on normal. This
     * is what the trainer is told the build fields: the harder and easier rungs are the trainer's to open when the agent is
     * ready for them, not something to matchmake over from the first fight.
     */
    public static List<String> fielded() {

        return List.copyOf(all().keySet());
    }

    /**
     * Everyone a run with no trainer goes round: every opponent on every rung the build has enabled, an opponent's rungs
     * together. There is no win rate to open a rung by without a trainer, so a quick look fights them all.
     */
    public static List<String> rotation() {

        List<Rung> rungs = enabled();
        List<String> names = new ArrayList<>(all().size() * rungs.size());

        for (String name : all().keySet()) {

            for (Rung rung : rungs) {

                names.add(name + rung.suffix());
            }
        }

        return List.copyOf(names);
    }

    /** The rungs this process goes round in a run with no trainer. */
    private static List<Rung> enabled() {

        // The build always sets the property, empty when nothing was asked for, so blank means the default rather than none.
        String asked = System.getProperty(DIFFICULTIES, "").trim();
        List<String> wanted = Arrays.stream((asked.isEmpty() ? DEFAULT_RUNGS : asked).toLowerCase(Locale.ROOT).split(","))
                .map(String::trim).toList();

        List<Rung> rungs = RUNGS.stream().filter(rung -> wanted.contains(rung.name())).toList();

        return rungs.isEmpty() ? List.of(RUNGS.get(0)) : rungs;
    }

    /**
     * What that name is a fight against, or null when this process fields nobody of that name. A name ending in a rung's
     * own suffix is that opponent on that rung, whether the rung is one this process would go round or not: the trainer
     * names the rungs it has opened, and the workers field whatever it names.
     */
    @Nullable
    public static Opposition named(String name) {

        for (Rung rung : RUNGS) {

            if (rung.suffix().isEmpty() || !name.endsWith(rung.suffix())) {

                continue;
            }

            Opposition base = all().get(name.substring(0, name.length() - rung.suffix().length()));

            return base == null ? null : new Opposition(name, base.mobs(), rung.difficulty());
        }

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
