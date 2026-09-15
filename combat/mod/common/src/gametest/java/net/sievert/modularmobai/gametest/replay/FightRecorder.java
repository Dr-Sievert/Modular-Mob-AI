package net.sievert.modularmobai.gametest.replay;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.jetbrains.annotations.Nullable;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.sievert.modularmobai.Constants;
import net.sievert.modularmobai.arena.Episode;
import net.sievert.modularmobai.brain.Brain;
import net.sievert.modularmobai.brain.NeuralBrain;
import net.sievert.modularmobai.brain.schema.ActionSchema;
import net.sievert.modularmobai.entity.agent.AgentMob;
import net.sievert.modularmobai.entity.agent.MobControls;
import net.sievert.modularmobai.gametest.GameTestTuning;

/**
 * Writes a fight down tick by tick, so it can be watched afterwards: the blocks of the ground it was fought on, where both
 * fighters stood and which way they looked, their health, every swing and every hit, every arrow or anything else shot or
 * thrown and where it came down, and what the agent's brain asked for on each tick. The format is docs/replay-format.md.
 *
 * <pre>
 *   -Dmodular_mob_ai.replays=DIR          where the replays go; unset or empty records nothing
 *   -Dmodular_mob_ai.replays.every=N      fights 0, N, 2N, ... of each worker; every fight unless given
 * </pre>
 *
 * <p>A fight is kept in memory while it runs, a few hundred kilobytes at most, and written whole when it ends, under a
 * temporary name first and renamed into place, so nothing reading the folder ever sees half a replay.
 *
 * <p><b>A fight may hold any number of bodies besides the agent</b>, and the format has always said so: index 0 is the agent,
 * the rest follow in the order they were handed over. It used to hold exactly one, which is why a squad fight and a fight
 * with a crowd standing about it were recorded as nothing at all — a recording missing most of what the agent could see would
 * show it losing to an empty field. That cost two thousand crowded training fights a run with nobody ever able to look at
 * one, and the crowd is precisely where the trouble was. Everything besides the agent is written the same way: its own frames,
 * its own health, its own swings, and a role — {@code opponent} for the other side and {@code idle} for a monster standing
 * about taking no interest, which the viewer draws in a colour of its own.
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
        TIMEOUT,

        /**
         * The opponent went without being killed, as a creeper that blew itself up does, and the agent is still standing.
         * Scored as a loss as well; only league fights end this way.
         */
        DRAW
    }

    private static final String PROPERTY = "modular_mob_ai.replays";

    private static final long[] SCALES = {1L, 10L, 100L, 1_000L, 10_000L};

    /**
     * How far above and below the fight's bounds a projectile is still followed. An arrow lobbed at a distant target climbs
     * well above anything the fighters were allowed to perceive, and is worth watching all the way down.
     */
    private static final double AIRSPACE_PADDING = 16.0D;

    /** How far outside a fighter's box a projectile's path still meets it, the margin the game's own hit test allows. */
    private static final double HIT_MARGIN = 0.3D;

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

    /**
     * Every body the replay follows, the agent first and then whatever was handed over in the order it was handed over,
     * which is the order the format gives {@code entities} and {@code frames}.
     */
    private final List<Track> tracks = new ArrayList<>();

    /** The blocks of the site and the fighters as they were at the start, already written out, to be written whole at the end. */
    private final String blocks;
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

    /**
     * Where projectiles are followed: over the ground the replay draws, from well below the fight's bounds to well above
     * them. One is picked up on the first tick it is inside, and let go of if it leaves.
     */
    private final AABB airspace;

    /** Every projectile seen in flight, in the order they were first seen, and those of them still flying. */
    private final List<Flight> flights = new ArrayList<>();
    private final List<Flight> flying = new ArrayList<>();

    /** The id of every projectile already picked up, so none is ever followed twice. */
    private final IntOpenHashSet seen = new IntOpenHashSet();

    private FightRecorder(int fight, AgentMob agent, List<? extends LivingEntity> opponents,
                          List<? extends LivingEntity> idle, int ceiling) {

        this.fight = fight;
        this.agent = agent;
        this.episode = agent.episode();

        ServerLevel level = (ServerLevel) agent.level();

        // Whatever the fighters were allowed to perceive covers the whole site: the eighty blocks of a terrain fight from
        // well below them to well above, the box and its walls in the closed arena. A fight with no bounds gets the ground
        // around where it started.
        AABB area = this.episode != null && this.episode.bounds() != null ? this.episode.bounds()
                : agent.getBoundingBox().inflate(16.0D, 0.0D, 16.0D).expandTowards(0.0D, 24.0D, 0.0D).expandTowards(0.0D, -16.0D, 0.0D);

        this.airspace = area.inflate(0.0D, AIRSPACE_PADDING, 0.0D);
        this.blocks = SiteBlocks.write(level, area, ceiling);

        // The agent first and then everything else in the order it was handed over, which is the order of both "entities" and
        // "frames": the other side, and then the monsters standing about taking no interest, which are drawn apart from it.
        StringBuilder described = new StringBuilder();

        this.follow(described, "agent", agent);

        for (LivingEntity opponent : opponents) {

            this.follow(described, "opponent", opponent);
        }

        for (LivingEntity standing : idle) {

            this.follow(described, "idle", standing);
        }

        this.entities = described.toString();

        Vec3 centre = area.getCenter();
        BlockPos top = BlockPos.containing(centre.x, 0.0D, centre.z);

        this.biome = level.getBiome(top.atY(level.getHeight(Heightmap.Types.WORLD_SURFACE, top.getX(), top.getZ())))
                .unwrapKey().map(key -> key.location().toString()).orElse(null);
    }

    /** Adds one body to what the replay follows and describes it in the same breath, so the two lists cannot fall apart. */
    private void follow(StringBuilder described, String role, LivingEntity body) {

        if (!this.tracks.isEmpty()) {

            described.append(',');
        }

        this.tracks.add(new Track(body));
        describe(described, role, body);
    }

    /**
     * Counts a fight that is about to start, and starts recording it when it is one of the ones asked for. Called for
     * every fight, recorded or not, since the count is what picks every Nth.
     *
     * @return what to tick and finish, or null when this fight is not being recorded
     */
    @Nullable
    public static FightRecorder start(AgentMob agent, LivingEntity opponent) {

        return start(agent, List.of(opponent), List.of(), Integer.MAX_VALUE);
    }

    /**
     * The same, for a fight under a roof. Seen from above, a closed box is nothing but its lid, and the game test
     * framework lays a sheet of barriers over that; what is worth drawing is the floor and the walls underneath.
     *
     * @param ceiling the height of the roof; only the blocks below it are written down
     */
    @Nullable
    public static FightRecorder start(AgentMob agent, LivingEntity opponent, int ceiling) {

        return start(agent, List.of(opponent), List.of(), ceiling);
    }

    /**
     * The whole of it: a fight against a side of any size, with any number of monsters standing about it taking no interest.
     * A squad fight and a crowded one are recorded like any other, which they were not until now; see the class comment.
     *
     * @param opponents the other side, in the order the fight set them up, which the replay keeps
     * @param idle      the monsters standing about it, which are no part of the fight and are drawn apart from it
     * @param ceiling   the height of a roof over the fight; {@link Integer#MAX_VALUE} for open sky
     */
    @Nullable
    public static synchronized FightRecorder start(AgentMob agent, List<? extends LivingEntity> opponents,
                                                   List<? extends LivingEntity> idle, int ceiling) {

        if (failed) {

            return null;
        }

        try {

            if (nextFight < 0) {

                configure();
            }

            int fight = nextFight++;

            return directory != null && fight % every == 0
                    ? new FightRecorder(fight, agent, opponents, idle, ceiling) : null;
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

            for (Track track : this.tracks) {

                track.frame();
            }

            // After the fighters, who by then know whether they were hurt, which is what tells a projectile that went into
            // one of them from one that just vanished.
            this.followProjectiles();

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
     * Moves every projectile in flight on by the tick that just ran, ends the flights that are over, and picks up any
     * projectile that has just appeared. A fight nobody shoots in costs one search a tick, which finds nothing.
     */
    private void followProjectiles() {

        this.flying.removeIf(flight -> !this.advance(flight));

        for (Projectile projectile : this.agent.level().getEntitiesOfClass(Projectile.class, this.airspace,
                projectile -> !this.seen.contains(projectile.getId()) && this.airspace.contains(projectile.position()))) {

            Flight flight = new Flight(projectile, this.indexOf(projectile.getOwner()), this.ticks);

            this.seen.add(projectile.getId());
            this.flights.add(flight);
            this.flying.add(flight);
        }
    }

    /**
     * Writes down where a projectile got to on the tick that just ran, or how its flight ended.
     *
     * @return whether it is still in flight, to be followed again on the next tick
     */
    private boolean advance(Flight flight) {

        Projectile projectile = flight.projectile;
        Track struck = this.struck(projectile);

        if (struck != null) {

            // Into a fighter, which ends the flight whatever the projectile does next: an arrow is gone, a trident bounces
            // off, a piercing arrow flies on.
            flight.frame(impact(struck.fighter, flight.last, projectile.position()));
            flight.end(this.ticks, this.indexOf(struck.fighter));
            return false;
        }

        if (projectile.isRemoved()) {

            // Gone without hurting either fighter: into something else, or it simply burst, as a snowball does on the
            // ground.
            flight.frame(projectile.position());
            flight.end(this.ticks, -1);
            return false;
        }

        if (projectile.position().equals(flight.last)) {

            // An arrow that has come to rest in a block never moves again, so one that did not move at all came to rest on
            // the tick before, which is already written down. Following it for the minute it lingers would add nothing.
            flight.end(this.ticks - 1, -1);
            return false;
        }

        if (!this.airspace.contains(projectile.position())) {

            return false;
        }

        flight.frame(projectile.position());
        return true;
    }

    /**
     * The fighter a projectile went into on this tick, if any: one that lost health on this tick, to damage the projectile
     * itself dealt. Tied to the damage rather than to the impact, so every hit is also a hurt tick in that fighter's
     * frames. One that struck a fighter without hurting it, a snowball say, or an arrow that glanced off one still in its
     * cooldown, did not hit it.
     */
    @Nullable
    private Track struck(Projectile projectile) {

        for (Track track : this.tracks) {

            if (track.hurtBy(projectile)) {

                return track;
            }
        }

        return null;
    }

    /**
     * Where a projectile's last move met a fighter it went into. The rest of the move carries an arrow on through, so its
     * own position is past the fighter: this is where its path entered the fighter's box, or, when the fighter stepped out
     * of the way of that path during the tick, the point of the box nearest to where the move began.
     */
    private static Vec3 impact(LivingEntity fighter, Vec3 from, Vec3 to) {

        AABB body = fighter.getBoundingBox().inflate(HIT_MARGIN);

        return body.clip(from, to).orElseGet(() -> new Vec3(Mth.clamp(from.x, body.minX, body.maxX),
                Mth.clamp(from.y, body.minY, body.maxY), Mth.clamp(from.z, body.minZ, body.maxZ)));
    }

    /** Where an entity is in the replay's list of them: the agent first, then the rest as handed over, and -1 for anything else. */
    private int indexOf(@Nullable Entity entity) {

        for (int index = 0; index < this.tracks.size(); index++) {

            if (this.tracks.get(index).fighter == entity) {

                return index;
            }
        }

        return -1;
    }

    /**
     * What this fight's replay is called, which is settled when the recording starts rather than when it is written. The
     * league writes it down beside the fight, so a row of the tier list can open the very fight it is a row about; see
     * {@link net.sievert.modularmobai.gametest.league.League#record}.
     */
    public String name() {

        return String.format(Locale.ROOT, "w%02d-f%06d.json", worker, this.fight);
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

            Path target = directory.resolve(this.name());
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

        StringBuilder out = new StringBuilder(this.blocks.length() + this.actions.length() * 3);

        out.append("{\"format\":\"mmai-replay\",\"version\":2");
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

        out.append(",\n\"blocks\":").append(this.blocks);
        out.append(",\n\"entities\":[").append(this.entities).append(']');

        out.append(",\n\"frames\":[\n");

        for (int index = 0; index < this.tracks.size(); index++) {

            if (index > 0) {

                out.append(",\n");
            }

            this.tracks.get(index).write(out);
        }

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

        // Only when there were any, so a fight nobody shot in is written exactly as it always was.
        if (!this.flights.isEmpty()) {

            out.append(",\n\"projectiles\":[\n");

            for (int index = 0; index < this.flights.size(); index++) {

                this.flights.get(index).write(index == 0 ? out : out.append(",\n"));
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

        /** Whether it lost health on the tick just written down. */
        private boolean justHurt;

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
            this.justHurt = now < this.lastHealth;
            next(this.hurt).append(this.justHurt ? 1 : 0);

            this.lastHealth = now;
            this.lastTickCount = this.fighter.tickCount;
        }

        /** Whether it lost health on the tick just written down, to damage the given projectile dealt. */
        private boolean hurtBy(Projectile projectile) {

            // Only asked once it has just lost health, so the damage it remembers last is this tick's.
            DamageSource source = this.justHurt ? this.fighter.getLastDamageSource() : null;
            return source != null && source.getDirectEntity() == projectile;
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

    /**
     * One projectile's flight, written out as text a tick at a time like a fighter's frames: what it was, who shot it,
     * where it was on every tick it flew, and how the flight ended.
     */
    private static final class Flight {

        private final Projectile projectile;
        private final String type;
        private final int owner;
        private final int start;

        private final StringBuilder x = new StringBuilder();
        private final StringBuilder y = new StringBuilder();
        private final StringBuilder z = new StringBuilder();

        /** Where the last frame put it: where the next one moves it on from, and where the flight ended once it has. */
        private Vec3 last;

        /** The tick it ended on, -1 while it has not, and the fighter it went into, -1 for none. */
        private int endTick = -1;
        private int hit = -1;

        private Flight(Projectile projectile, int owner, int start) {

            this.projectile = projectile;
            this.type = EntityType.getKey(projectile.getType()).toString();
            this.owner = owner;
            this.start = start;
            this.frame(projectile.position());
        }

        private void frame(Vec3 at) {

            number(next(this.x), at.x, 2);
            number(next(this.y), at.y, 2);
            number(next(this.z), at.z, 2);

            this.last = at;
        }

        private void end(int tick, int hit) {

            this.endTick = tick;
            this.hit = hit;
        }

        private void write(StringBuilder out) {

            out.append("{\"type\":\"").append(this.type).append("\",\"owner\":").append(this.owner)
                    .append(",\"start\":").append(this.start).append(",\"x\":[").append(this.x)
                    .append("],\"y\":[").append(this.y).append("],\"z\":[").append(this.z).append(']');

            if (this.endTick >= 0) {

                out.append(",\"end\":{\"tick\":").append(this.endTick).append(",\"hit\":").append(this.hit);
                number(out.append(",\"x\":"), this.last.x, 2);
                number(out.append(",\"y\":"), this.last.y, 2);
                number(out.append(",\"z\":"), this.last.z, 2);
                out.append('}');
            }

            out.append('}');
        }
    }
}
