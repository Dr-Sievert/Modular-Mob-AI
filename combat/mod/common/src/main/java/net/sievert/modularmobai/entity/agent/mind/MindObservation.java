package net.sievert.modularmobai.entity.agent.mind;

import java.io.IOException;
import java.io.Reader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.util.GsonHelper;
import net.minecraft.util.Mth;
import net.sievert.modularmobai.Constants;

/**
 * The decisions model's observation: 69 floats, assembled from one table of column names to sources.
 *
 * <p>The table below <b>is</b> the layout as far as this half of the port is concerned, and it is the whole of what
 * knows where anything goes. Every column names itself, says where it starts and how wide it is, and carries the one
 * expression that fills it; nothing else in the mod writes an offset into this vector. That is what makes a column
 * cheap to add: a block the {@code mind/} side grows — the injuries block it is about to grow — is one more line here,
 * and every line after it moves because the offsets are computed from the widths rather than typed twice.
 *
 * <p><b>The table is held against the file it came from.</b> {@code shared/models/decisions/layout.json} is the frozen
 * layout and its sha256 is the schema id stamped into {@code decisions.mbw}. When that file can be found from where the
 * game is running — which is every checkout, every game test and every training worker, and no shipped jar — this class
 * reads it as it loads and refuses to load at all if one name, one offset or one width disagrees. A layout that moves
 * is then a stack trace with the column in it on the first tick, not a model quietly reading the wrong five floats for
 * the rest of the run. Where the file is not there, the table still checks itself: contiguous offsets, no repeated
 * names, and a total that is the size the model was built for.
 *
 * <p><b>Columns that read zero, and why that is not a hole.</b> Goals, obligations, the chief, and where an agent is
 * standing are all things {@code mind/} has and the mod does not have yet — they arrive with the arbitrator and with a
 * world that has named places in it. The model handles an agent with none of them exactly as it handles a dwarf who has
 * none, which {@code mind/docs/port.md} says in as many words. They are declared here with a zero source rather than
 * left out, because a declared zero lines up with the file and a missing column does not.
 */
public final class MindObservation {

    /** The whole vector, which is the number the frozen model was built for. */
    public static final int OBS_SIZE = 69;

    /** Where the frozen layout lives, relative to the repository root, and what is read from it. */
    private static final String LAYOUT = "shared/models/decisions/layout.json";

    /** How far up from the working directory the layout is worth looking for. A deeper checkout than this is not one. */
    private static final int LOOK_UP = 10;

    private MindObservation() {}

    /** One column of the observation: what it is called in the frozen layout, how wide it is, and what fills it. */
    private record Column(String name, int at, int size, Source source) {}

    /** What fills one column. Given the mind, the row and where the column starts in it. */
    @FunctionalInterface
    private interface Source {

        void write(MindState mind, float[] into, int at);
    }

    /** A column the mod cannot fill yet; see the class comment on why it is declared rather than left out. */
    private static final Source ZERO = (mind, into, at) -> {};

    /**
     * <b>The table.</b> In offset order, each column's width taken from this list and its offset computed from the
     * widths before it, so the two can never disagree.
     */
    private static final Column[] COLUMNS = table(

            column("mind.emotions", Emotion.COUNT, (mind, into, at) -> {

                for (Emotion emotion : Emotion.ALL) {

                    into[at + emotion.ordinal()] = mind.emotion(emotion);
                }
            }),

            column("mind.needs", Need.COUNT, (mind, into, at) -> {

                for (Need need : Need.ALL) {

                    into[at + need.ordinal()] = mind.need(need);
                }
            }),

            // The six the frozen layout has, in its order; the mod's two further traits are carried and not read here.
            column("mind.traits", Trait.IN_OBSERVATION, (mind, into, at) -> {

                for (int index = 0; index < Trait.IN_OBSERVATION; index++) {

                    into[at + index] = mind.trait(Trait.ALL[index]);
                }
            }),

            column("mind.health", 1, (mind, into, at) -> into[at] = mind.health()),

            column("mind.inventory", MindState.INVENTORY_COLUMNS, (mind, into, at) -> {

                for (int column = 0; column < MindState.INVENTORY_COLUMNS; column++) {

                    into[at + column] = mind.inventory(column);
                }
            }),

            // Four relationship slots of six: present, trust, respect, hatred, grudge, gratitude. Filled by salience and
            // padded with zeros, which is the same discipline the combat network's ten enemy slots keep.
            column("mind.focus", Relationships.FOCUS_SLOTS * Relationships.FOCUS_STRIDE, (mind, into, at) -> {

                List<UUID> focus = mind.focus();

                for (int slot = 0; slot < Relationships.FOCUS_SLOTS && slot < focus.size(); slot++) {

                    UUID who = focus.get(slot);
                    Relationship row = mind.relationships().find(who);
                    int base = at + slot * Relationships.FOCUS_STRIDE;

                    into[base] = 1.0F;

                    if (row != null) {

                        into[base + 1] = row.trust();
                        into[base + 2] = row.respect();
                        into[base + 3] = row.hatred();
                    }

                    into[base + 4] = mind.memories().grudge(mind.now(), mind, who);
                    into[base + 5] = mind.memories().gratitude(mind.now(), mind, who);
                }
            }),

            // Where the agent is standing, one-hot over the sim's six places. A world has no named places in it yet, so
            // this reads nowhere at all until one does; see the class comment.
            column("place", 6, ZERO),

            column("crowd", 1, (mind, into, at) -> into[at] = Math.min(1.0F, mind.crowd() / MindState.CROWD_SCALE)),
            column("monster", 1, (mind, into, at) -> into[at] = mind.monsterHere() ? 1.0F : 0.0F),
            column("monster_hp", 1, (mind, into, at) -> into[at] = mind.monsterHealth()),
            column("under_attack", 1, (mind, into, at) -> into[at] = mind.underAttack() ? 1.0F : 0.0F),

            column("hit_age", 1, (mind, into, at) ->
                    into[at] = (float) Math.min(1.0D, mind.sinceHit() / 50.0D)),

            // What fraction of the settlement is still standing. Nobody counts a world's population, and reading zero
            // would say everyone is dead, so an agent in a world reads one until something in the mod knows better.
            column("alive_fraction", 1, (mind, into, at) -> into[at] = 1.0F),

            // The sim's cheap day cycle, which the game has a real one of: the day, 0 at dawn and round again.
            column("clock", 1, (mind, into, at) ->
                    into[at] = (float) Mth.frac(mind.agent().level().getDayTime() / 24000.0D)),

            column("goals", 6, ZERO),
            column("obligations", 2, ZERO),

            column("memory", 2, (mind, into, at) -> {

                into[at] = Math.min(1.0F, mind.memories().size() / (float) MemoryBook.CAP);
                into[at + 1] = mind.memories().meanSalience(mind.now(), mind.trait(Trait.FORGIVENESS));
            }),

            column("is_chief", 1, ZERO),
            column("chief_here", 1, ZERO));

    /** What the frozen layout said when this class loaded, for the game test that holds the two together. */
    @Nullable
    private static final Path CHECKED_AGAINST = checkAgainstTheFrozenLayout();

    /**
     * Fills in one agent's observation, in the offset order the frozen layout gives.
     *
     * @param into the row to write into, which must hold {@link #OBS_SIZE} floats from {@code at}
     */
    public static void write(MindState mind, float[] into, int at) {

        if (into.length - at < OBS_SIZE) {

            throw new IllegalArgumentException("An observation is " + OBS_SIZE + " floats and there is room for "
                    + (into.length - at));
        }

        Arrays.fill(into, at, at + OBS_SIZE, 0.0F);

        for (Column column : COLUMNS) {

            column.source().write(mind, into, at + column.at());
        }
    }

    /** Where one named column starts, for a test or a readout that wants to name what it is looking at. */
    public static int offsetOf(String name) {

        for (Column column : COLUMNS) {

            if (column.name().equals(name)) {

                return column.at();
            }
        }

        throw new IllegalArgumentException("There is no observation column called '" + name + "'");
    }

    /** How wide that column is. */
    public static int widthOf(String name) {

        for (Column column : COLUMNS) {

            if (column.name().equals(name)) {

                return column.size();
            }
        }

        throw new IllegalArgumentException("There is no observation column called '" + name + "'");
    }

    /** Every column's name, in offset order. */
    public static List<String> names() {

        List<String> names = new ArrayList<>(COLUMNS.length);

        for (Column column : COLUMNS) {

            names.add(column.name());
        }

        return names;
    }

    /**
     * The layout file this build was actually held against as it loaded, or null where none could be found — which is
     * a shipped jar, and is what the game test asserts is <b>not</b> the case in a checkout.
     */
    @Nullable
    public static Path checkedAgainst() {

        return CHECKED_AGAINST;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Building the table, and holding it against the file
    // ---------------------------------------------------------------------------------------------------------------

    private static Column column(String name, int size, Source source) {

        // The offset is filled in by table(), which is the only thing that knows the order.
        return new Column(name, -1, size, source);
    }

    /** Lays the columns out end to end and refuses a table that does not add up to the model's own width. */
    private static Column[] table(Column... declared) {

        Column[] laid = new Column[declared.length];
        List<String> seen = new ArrayList<>(declared.length);
        int at = 0;

        for (int index = 0; index < declared.length; index++) {

            Column column = declared[index];

            if (seen.contains(column.name())) {

                throw new IllegalStateException("Two observation columns are both called '" + column.name() + "'");
            }

            seen.add(column.name());
            laid[index] = new Column(column.name(), at, column.size(), column.source());
            at += column.size();
        }

        if (at != OBS_SIZE) {

            throw new IllegalStateException("The observation columns add up to " + at + " floats and the decisions model "
                    + "reads " + OBS_SIZE + "; see " + LAYOUT);
        }

        return laid;
    }

    /**
     * Reads the frozen layout, if it can be found, and refuses to load if the table above disagrees with it about one
     * name, one offset or one width.
     *
     * @return the file it was checked against, or null where there was none to check against
     */
    @Nullable
    private static Path checkAgainstTheFrozenLayout() {

        Path file = findLayout();

        if (file == null) {

            return null;
        }

        JsonObject layout;

        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {

            layout = GsonHelper.parse(reader);
        }

        catch (IOException | RuntimeException failure) {

            // A file that is there and cannot be read is worth saying out loud, but it is not worth refusing to start
            // over: the table has already checked itself, and the parity check reads the same file from the other side.
            Constants.LOG.warn("The frozen observation layout at {} could not be read, so this build's own table is all "
                    + "that has been checked: {}", file, failure.toString());

            return null;
        }

        JsonObject observation = GsonHelper.getAsJsonObject(layout, "observation");
        int size = GsonHelper.getAsInt(observation, "size");

        if (size != OBS_SIZE) {

            throw new IllegalStateException("The frozen layout " + file + " has an observation of " + size
                    + " floats and this build assembles " + OBS_SIZE);
        }

        List<Column> frozen = flatten(GsonHelper.getAsJsonArray(observation, "blocks"));

        if (frozen.size() != COLUMNS.length) {

            throw new IllegalStateException("The frozen layout " + file + " has " + frozen.size()
                    + " observation columns and this build has " + COLUMNS.length + ": " + names() + " against "
                    + frozen.stream().map(Column::name).toList());
        }

        for (int index = 0; index < frozen.size(); index++) {

            Column theirs = frozen.get(index);
            Column ours = COLUMNS[index];

            if (!theirs.name().equals(ours.name()) || theirs.at() != ours.at() || theirs.size() != ours.size()) {

                throw new IllegalStateException("The frozen layout " + file + " says column " + index + " is '"
                        + theirs.name() + "' at " + theirs.at() + " for " + theirs.size() + " floats, and this build has '"
                        + ours.name() + "' at " + ours.at() + " for " + ours.size());
            }
        }

        Constants.LOG.info("The mind's observation table agrees with the frozen layout at {}: {} columns, {} floats",
                file, COLUMNS.length, OBS_SIZE);

        return file;
    }

    /** The layout's blocks as leaf columns: a block with parts becomes {@code block.part}, one entry each. */
    private static List<Column> flatten(JsonArray blocks) {

        List<Column> flat = new ArrayList<>();

        for (JsonElement element : blocks) {

            JsonObject block = element.getAsJsonObject();
            String name = GsonHelper.getAsString(block, "name");
            int at = GsonHelper.getAsInt(block, "at");

            if (!block.has("parts")) {

                flat.add(new Column(name, at, GsonHelper.getAsInt(block, "size"), ZERO));
                continue;
            }

            for (JsonElement part : GsonHelper.getAsJsonArray(block, "parts")) {

                JsonObject inner = part.getAsJsonObject();

                flat.add(new Column(name + '.' + GsonHelper.getAsString(inner, "name"),
                        at + GsonHelper.getAsInt(inner, "at"), GsonHelper.getAsInt(inner, "size"), ZERO));
            }
        }

        return flat;
    }

    /**
     * Looks for the frozen layout up the tree from where this is running: the working directory first, which is the
     * repository root for every script and every Gradle task, and then from wherever these classes were loaded, which
     * covers a game test server started from somewhere else. A shipped jar finds nothing, which is not a failure.
     */
    @Nullable
    private static Path findLayout() {

        for (Path start : startingPoints()) {

            Path at = start;

            for (int up = 0; up < LOOK_UP && at != null; up++, at = at.getParent()) {

                Path candidate = at.resolve(LAYOUT);

                if (Files.isRegularFile(candidate)) {

                    return candidate;
                }
            }
        }

        return null;
    }

    private static List<Path> startingPoints() {

        List<Path> starts = new ArrayList<>(2);

        try {

            starts.add(Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize());
        }

        catch (RuntimeException ignored) {

            // A working directory that cannot be made into a path is not worth a word; the next start covers it.
        }

        try {

            java.security.CodeSource source = MindObservation.class.getProtectionDomain().getCodeSource();

            if (source != null && source.getLocation() != null) {

                starts.add(Path.of(source.getLocation().toURI()).toAbsolutePath().normalize());
            }
        }

        catch (URISyntaxException | RuntimeException ignored) {

            // Loaded from somewhere that is not a file, which is a jar in a game. Nothing to walk up from.
        }

        return starts;
    }
}
