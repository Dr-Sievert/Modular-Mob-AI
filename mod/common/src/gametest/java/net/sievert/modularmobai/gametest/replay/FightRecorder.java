package net.sievert.modularmobai.gametest.replay;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

import org.jetbrains.annotations.Nullable;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.sievert.modularmobai.Constants;
import net.sievert.modularmobai.arena.Episode;
import net.sievert.modularmobai.brain.Brain;
import net.sievert.modularmobai.brain.NeuralBrain;
import net.sievert.modularmobai.brain.schema.ActionSchema;
import net.sievert.modularmobai.entity.agent.AgentMob;
import net.sievert.modularmobai.entity.agent.MobControls;
import net.sievert.modularmobai.gametest.GameTestTuning;

/**
 * Writes a fight down tick by tick, so it can be watched afterwards: the ground it was fought on, where both fighters
 * stood and which way they looked, their health, every swing and every hit, and what the agent's brain asked for on
 * each tick. The format is docs/replay-format.md.
 *
 * <pre>
 *   -Dmodular_mob_ai.replays=DIR          where the replays go; unset or empty records nothing
 *   -Dmodular_mob_ai.replays.every=N      fights 0, N, 2N, ... of each worker; every fight unless given
 * </pre>
 *
 * <p>A fight is kept in memory while it runs, a few hundred kilobytes at most, and written whole when it ends, under a
 * temporary name first and renamed into place, so nothing reading the folder ever sees half a replay.
 *
 * <p>Recording only ever watches. Nothing here throws into the tick that calls it: the first thing that goes wrong is
 * logged, recording stops for the rest of the process, and the fights carry on as if it had never been asked for.
 */
public final class FightRecorder {

    /** How a fight ended, as the arena that ran it decided. */
    public enum Outcome {

        WIN,
        LOSS,

        /** Both still standing when the time ran out. The arena scores it as a loss; someone watching wants to know. */
        TIMEOUT
    }

    private static final String PROPERTY = "modular_mob_ai.replays";

    private static final long[] SCALES = {1L, 10L, 100L, 1_000L, 10_000L};

    /** Where the replays go, or null when nobody asked for any. Read when the first fight starts. */
    @Nullable
    private static Path directory;

    private static int every = 1;
    private static int worker;

    /** The training run this process belongs to, or null outside one. */
    @Nullable
    private static String run;

    /** The number the next fight on this worker gets; -1 until the first one asks. */
    private static int nextFight = -1;

    /** Set by the first failure, after which nothing more is recorded. */
    private static boolean failed;

    private final int fight;
    private final AgentMob agent;

    @Nullable
    private final Episode episode;

    private final Track agentTrack;
    private final Track opponentTrack;

    /** The ground and the fighters as they were at the start, already written out; neither changes during a fight. */
    private final String terrain;
    private final String entities;

    @Nullable
    private final String biome;

    /** What drove the agent and on which weights, noted on the first tick, once the driver has handed it a brain. */
    private String brain = "unknown";
    private int iteration = -1;

    private int ticks;
    private final float[] action = new float[ActionSchema.ACT_DIM];
    private final StringBuilder actions = new StringBuilder();
    private final FloatArrayList rewards = new FloatArrayList();

    /** How much of the episode's total the recorded ticks already account for. */
    private float rewardSeen;

    private FightRecorder(int fight, AgentMob agent, LivingEntity opponent, int ceiling) {

        this.fight = fight;
        this.agent = agent;
        this.episode = agent.episode();
        this.agentTrack = new Track(agent);
        this.opponentTrack = new Track(opponent);

        ServerLevel level = (ServerLevel) agent.level();

        // Whatever the fighters were allowed to perceive covers the whole site: the eighty blocks of a terrain fight, the
        // box and its walls in the closed arena. A fight with no bounds gets the ground around where it started.
        AABB area = this.episode != null && this.episode.bounds() != null
                ? this.episode.bounds() : agent.getBoundingBox().inflate(16.0D, 0.0D, 16.0D);

        int west = Mth.floor(area.minX);
        int north = Mth.floor(area.minZ);
        int width = Mth.ceil(area.maxX) - west;
        int depth = Mth.ceil(area.maxZ) - north;

        StringBuilder heights = new StringBuilder(width * depth * 3);
        StringBuilder colours = new StringBuilder(width * depth * 8);
        BlockPos.MutableBlockPos top = new BlockPos.MutableBlockPos();

        for (int dz = 0; dz < depth; dz++) {

            for (int dx = 0; dx < width; dx++) {

                // The heightmap holds the first air above a column, so its highest block is the one below that: grass,
                // leaves, water, whatever someone looking down from above would see. Under a roof, the highest one below
                // the roof instead.
                int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE, west + dx, north + dz);
                top.set(west + dx, Math.min(surface, ceiling) - 1, north + dz);

                while (top.getY() > level.getMinBuildHeight() && level.getBlockState(top).isAir()) {

                    top.move(Direction.DOWN);
                }

                next(heights).append(top.getY());
                next(colours).append(level.getBlockState(top).getMapColor(level, top).col);
            }
        }

        this.terrain = "{\"x\":" + west + ",\"z\":" + north + ",\"width\":" + width + ",\"depth\":" + depth
                + ",\n\"height\":[" + heights + "],\n\"color\":[" + colours + "]}";

        StringBuilder described = new StringBuilder();
        describe(described, "agent", agent);
        described.append(',');
        describe(described, "opponent", opponent);
        this.entities = described.toString();

        top.set(west + width / 2, 0, north + depth / 2);
        top.setY(level.getHeight(Heightmap.Types.WORLD_SURFACE, top.getX(), top.getZ()));

        this.biome = level.getBiome(top).unwrapKey().map(key -> key.location().toString()).orElse(null);
    }

    /**
     * Counts a fight that is about to start, and starts recording it when it is one of the ones asked for. Called for
     * every fight, recorded or not, since the count is what picks every Nth.
     *
     * @return what to tick and finish, or null when this fight is not being recorded
     */
    @Nullable
    public static FightRecorder start(AgentMob agent, LivingEntity opponent) {

        return start(agent, opponent, Integer.MAX_VALUE);
    }

    /**
     * The same, for a fight under a roof. Seen from above, a closed box is nothing but its lid, and the game test
     * framework lays a sheet of barriers over that; what is worth drawing is the floor and the walls underneath.
     *
     * @param ceiling the height of the roof; the ground is drawn from the blocks below it
     */
    @Nullable
    public static synchronized FightRecorder start(AgentMob agent, LivingEntity opponent, int ceiling) {

        if (failed) {

            return null;
        }

        try {

            if (nextFight < 0) {

                configure();
            }

            int fight = nextFight++;

            return directory != null && fight % every == 0 ? new FightRecorder(fight, agent, opponent, ceiling) : null;
        }

        catch (RuntimeException exception) {

            fail("Could not start recording a fight", exception);
            return null;
        }
    }

    /**
     * Writes down the tick that just ran. Called once a tick while the fight is on, after the fighters have moved.
     */
    public void tick() {

        // Until the agent's brain has stepped it there is nothing it asked for to write down. In the closed arena the
        // first call comes on the very tick the fighters were spawned, before either has moved.
        if (failed || this.agent.brain().steps() == 0) {

            return;
        }

        try {

            if (this.ticks == 0) {

                Brain driving = this.agent.brain().brain();

                this.brain = describe(driving);
                this.iteration = driving instanceof NeuralBrain neural ? neural.weights().iteration() : -1;
            }

            this.agentTrack.frame();
            this.opponentTrack.frame();

            // What the brain asked for, before the body applied any of its limits. The buttons and the slot were already
            // decided when the controls were filled in, but a brain only ever sends them as zero or one and as an index.
            MobControls controls = this.agent.controls();

            this.action[ActionSchema.MOVE_FORWARD] = controls.moveForward;
            this.action[ActionSchema.MOVE_STRAFE] = controls.moveStrafe;
            this.action[ActionSchema.AIM_YAW] = controls.aimYaw;
            this.action[ActionSchema.AIM_PITCH] = controls.aimPitch;
            this.action[ActionSchema.JUMP] = controls.jump ? 1.0F : 0.0F;
            this.action[ActionSchema.SPRINT] = controls.sprint ? 1.0F : 0.0F;
            this.action[ActionSchema.SNEAK] = controls.sneak ? 1.0F : 0.0F;
            this.action[ActionSchema.ATTACK] = controls.attack ? 1.0F : 0.0F;
            this.action[ActionSchema.USE] = controls.use ? 1.0F : 0.0F;
            this.action[ActionSchema.USE_OFFHAND] = controls.useOffhand ? 1.0F : 0.0F;
            this.action[ActionSchema.SELECTED_SLOT] = controls.selectedSlot;

            next(this.actions).append('[');

            for (int index = 0; index < ActionSchema.ACT_DIM; index++) {

                number(index == 0 ? this.actions : this.actions.append(','), this.action[index], 3);
            }

            this.actions.append(']');

            // Read off the running total rather than taken, which would steal the tick's reward from the brain.
            if (this.episode != null) {

                float total = this.episode.reward().episodeTotal();
                this.rewards.add(total - this.rewardSeen);
                this.rewardSeen = total;
            }

            this.ticks++;
        }

        catch (RuntimeException exception) {

            fail("Could not record a tick of fight " + this.fight, exception);
        }
    }

    /**
     * Writes the replay, once the arena has said how the fight ended. Whatever the ending paid, the win or the loss on
     * time, was paid after the last tick was written down, and is added to it.
     */
    public void finish(Outcome outcome) {

        if (failed || this.ticks == 0) {

            return;
        }

        try {

            if (this.episode != null) {

                int last = this.rewards.size() - 1;
                this.rewards.set(last, this.rewards.getFloat(last) + this.episode.reward().episodeTotal() - this.rewardSeen);
            }

            Path target = directory.resolve(String.format(Locale.ROOT, "w%02d-f%06d.json", worker, this.fight));
            Path temporary = target.resolveSibling(target.getFileName() + ".tmp");

            Files.createDirectories(directory);
            Files.writeString(temporary, this.json(outcome), StandardCharsets.UTF_8);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }

        catch (IOException | RuntimeException exception) {

            fail("Could not write the replay of fight " + this.fight, exception);
        }
    }

    private String json(Outcome outcome) {

        StringBuilder out = new StringBuilder(this.terrain.length() + this.actions.length() * 3);

        out.append("{\"format\":\"mmai-replay\",\"version\":1");
        out.append(",\"run\":");
        string(out, run);
        out.append(",\"iteration\":").append(this.iteration < 0 ? "null" : String.valueOf(this.iteration));
        out.append(",\"brain\":");
        string(out, this.brain);
        out.append(",\"worker\":").append(worker);
        out.append(",\"fight\":").append(this.fight);
        out.append(",\"biome\":");
        string(out, this.biome);
        out.append(",\"outcome\":\"").append(outcome.name().toLowerCase(Locale.ROOT)).append('"');
        out.append(",\"ticks\":").append(this.ticks);
        out.append(",\"tickRate\":").append(SharedConstants.TICKS_PER_SECOND);

        out.append(",\n\"terrain\":").append(this.terrain);
        out.append(",\n\"entities\":[").append(this.entities).append(']');

        out.append(",\n\"frames\":[\n");
        this.agentTrack.write(out);
        out.append(",\n");
        this.opponentTrack.write(out);
        out.append(']');

        out.append(",\n\"actions\":{\"names\":[");

        for (int index = 0; index < ActionSchema.ACT_DIM; index++) {

            string(index == 0 ? out : out.append(','), ActionSchema.NAMES[index]);
        }

        out.append("],\n\"values\":[").append(this.actions).append("]}");

        if (this.episode != null) {

            out.append(",\n\"reward\":[");

            for (int tick = 0; tick < this.rewards.size(); tick++) {

                number(tick == 0 ? out : out.append(','), this.rewards.getFloat(tick), 4);
            }

            out.append(']');
        }

        return out.append("}\n").toString();
    }

    // ---------------------------------------------------------------------------------------------------------------

    private static void configure() {

        String folder = property(PROPERTY, "");
        String training = property("modular_mob_ai.training.run", "");

        worker = GameTestTuning.shardIndex();
        every = Math.max(1, Integer.parseInt(property(PROPERTY + ".every", "1")));
        run = training.isEmpty() ? null : Path.of(training).getFileName().toString();
        directory = folder.isEmpty() ? null : Path.of(folder).toAbsolutePath();
        nextFight = directory == null ? 0 : firstFight(directory);

        if (directory != null) {

            Constants.LOG.info("Recording fights {}, {}, ... of worker {} to {}", nextFight, nextFight + every, worker, directory);
        }
    }

    /**
     * Where this worker's numbering starts: after the last replay it already left in the folder, so a training run that
     * goes on round after round with fresh processes, a run that was resumed, or a second test into the same folder
     * never writes over an earlier one. Rounded up to a multiple of N, so the first fight of every process is recorded.
     */
    private static int firstFight(Path folder) {

        String prefix = String.format(Locale.ROOT, "w%02d-f", worker);
        int next = 0;

        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, prefix + "*.json")) {

            for (Path file : files) {

                String name = file.getFileName().toString();

                try {

                    next = Math.max(next, Integer.parseInt(name, prefix.length(), name.length() - ".json".length(), 10) + 1);
                }

                catch (NumberFormatException ignored) {

                    // Named like a replay, numbered like something else; not one of ours.
                }
            }
        }

        catch (IOException exception) {

            // No folder yet, so nothing to carry on from.
        }

        return (next + every - 1) / every * every;
    }

    /**
     * What drove the agent, in a word. A network is either being trained, when this process belongs to a training run,
     * or deployed. Any other brain is named after its class, so the scripted fighter is "scripted" and a recording of
     * one for a network to copy is "demonstration".
     */
    private static String describe(@Nullable Brain driving) {

        if (driving instanceof NeuralBrain) {

            return run != null ? "training" : "neural";
        }

        return driving == null ? "unknown" : driving.getClass().getSimpleName().replace("Brain", "").toLowerCase(Locale.ROOT);
    }

    private static void describe(StringBuilder out, String role, LivingEntity fighter) {

        out.append("{\"role\":\"").append(role).append("\",\"type\":\"").append(EntityType.getKey(fighter.getType()));
        number(out.append("\",\"width\":"), fighter.getBbWidth(), 2);
        number(out.append(",\"height\":"), fighter.getBbHeight(), 2);
        number(out.append(",\"maxHealth\":"), fighter.getMaxHealth(), 1);
        out.append('}');
    }

    private static synchronized void fail(String message, Exception exception) {

        if (!failed) {

            failed = true;
            Constants.LOG.warn("{}; no more fights will be recorded in this process", message, exception);
        }
    }

    /** The build always sets these, passing an empty string through for anything the user left out. */
    private static String property(String name, String fallback) {

        String value = System.getProperty(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /** Starts the next value of an array that is being written out as text. */
    private static StringBuilder next(StringBuilder array) {

        return array.isEmpty() ? array : array.append(',');
    }

    /** Rounded to so many places and written as short as it goes, 12.5 rather than 12.50 and 3 rather than 3.00. */
    private static void number(StringBuilder out, double value, int places) {

        out.append(BigDecimal.valueOf(Math.round(value * SCALES[places]), places).stripTrailingZeros().toPlainString());
    }

    private static void string(StringBuilder out, @Nullable String value) {

        if (value == null) {

            out.append("null");
            return;
        }

        out.append('"');

        for (int index = 0; index < value.length(); index++) {

            char character = value.charAt(index);

            if (character == '"' || character == '\\') {

                out.append('\\');
            }

            out.append(character);
        }

        out.append('"');
    }

    /** One fighter's frames, each field its own array as the format has it, written out as text a tick at a time. */
    private static final class Track {

        private final LivingEntity fighter;

        private final StringBuilder x = new StringBuilder();
        private final StringBuilder y = new StringBuilder();
        private final StringBuilder z = new StringBuilder();
        private final StringBuilder yaw = new StringBuilder();
        private final StringBuilder pitch = new StringBuilder();
        private final StringBuilder health = new StringBuilder();
        private final StringBuilder swing = new StringBuilder();
        private final StringBuilder hurt = new StringBuilder();

        private float lastHealth;
        private int lastTickCount;

        private Track(LivingEntity fighter) {

            this.fighter = fighter;
            this.lastHealth = fighter.getHealth();
            this.lastTickCount = fighter.tickCount;
        }

        private void frame() {

            boolean alive = this.fighter.isAlive();
            float now = alive ? this.fighter.getHealth() : 0.0F;

            // A fighter that wandered off into chunks that never tick stands frozen mid swing; only one that actually
            // ticked can have started a new one.
            boolean ticked = this.fighter.tickCount != this.lastTickCount;

            number(next(this.x), this.fighter.getX(), 2);
            number(next(this.y), this.fighter.getY(), 2);
            number(next(this.z), this.fighter.getZ(), 2);
            number(next(this.yaw), Mth.wrapDegrees(this.fighter.getYHeadRot()), 1);
            number(next(this.pitch), this.fighter.getXRot(), 1);

            // Never rounded down to nothing while it still stands: four swings just short of full strength leave a
            // vindicator a sliver of health, and 0 is for the dead.
            number(next(this.health), alive ? Math.max(0.1D, now) : 0.0D, 1);
            next(this.swing).append(ticked && swung(this.fighter) ? 1 : 0);

            // Taking damage is losing health, however it came: a hit, a fall, a fire, a hit landing in the cooldown after
            // another. Consistent with the health column beside it, which is what anyone checking it will compare.
            next(this.hurt).append(now < this.lastHealth ? 1 : 0);

            this.lastHealth = now;
            this.lastTickCount = this.fighter.tickCount;
        }

        private void write(StringBuilder out) {

            out.append("{\"x\":[").append(this.x).append("],\"y\":[").append(this.y).append("],\"z\":[").append(this.z)
                    .append("],\n\"yaw\":[").append(this.yaw).append("],\n\"pitch\":[").append(this.pitch)
                    .append("],\n\"health\":[").append(this.health).append("],\"swing\":[").append(this.swing)
                    .append("],\"hurt\":[").append(this.hurt).append("]}");
        }

        /**
         * Whether the fighter started an attack swing on the tick that just ran. A monster's swing animation starts at -1
         * and only moves on at the start of its next tick, so one still at -1 swung during this one. The agent's never
         * moves on at all, since a server only advances it for monsters and players, so the agent is asked instead.
         */
        private static boolean swung(LivingEntity fighter) {

            if (fighter instanceof AgentMob agent) {

                return agent.executed().attacked;
            }

            return fighter.swinging && fighter.swingTime < 0;
        }
    }
}
