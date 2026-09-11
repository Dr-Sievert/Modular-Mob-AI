package net.sievert.modularmobai.brain.nn;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/**
 * Writes one rollout shard: everything one game process did under one set of weights, for the training side to learn
 * from after the fact.
 *
 * <p>The rows are the step the game already takes once a tick, written down: who, what they could see, what they earned
 * since last time, and what they chose. The training side rebuilds each agent's trajectory from them. A row's reward
 * belongs to the action on that agent's previous row, the same convention the brain has always been fed.
 *
 * <pre>
 *   header, 16 u32 words, little endian
 *     magic 'MBR1' | version | schema id | topology hash | iteration | round | worker | worker count
 *     obsDim | actDim | hidden | final | row count | segment count | steps | reserved
 *
 *   rows, row count of them, each 4 + actDim + obsDim words
 *     u32 agent | u32 flags | f32 reward | f32 log probability | f32 action[actDim] | f32 obs[obsDim]
 *
 *   segments, segment count of them, each 1 + hidden words
 *     u32 row | f32 h0[hidden]          the hidden state going into the row that starts each segment
 * </pre>
 *
 * <p>A segment is one agent's run of rows within this shard. It starts on the agent's first row here, carrying the
 * hidden state it had going in, so the training side can replay the recurrent network from exactly where the game was
 * without the game having to store a hidden state per tick. It ends on a row flagged {@link #FLAG_DONE} when the fight
 * ended, or {@link #FLAG_TRUNCATED} when the shard was cut while the fight carried on; either way that last row holds a
 * real observation and a reward and no action.
 *
 * <p>The shard is written under a temporary name and renamed into place when closed, so whoever is watching the folder
 * only ever sees complete files.
 */
public final class RolloutWriter {

    public static final String EXTENSION = ".mbr";

    public static final int VERSION = 1;

    /** The agent's episode starts on this row. Its hidden state going in is all zeroes. */
    public static final int FLAG_NEW = 1;

    /** The fight ended; this row carries the final observation and reward, and no action. */
    public static final int FLAG_DONE = 2;

    /** The shard was cut here while the fight carried on. The observation is worth bootstrapping from. */
    public static final int FLAG_TRUNCATED = 4;

    private static final int MAGIC = 'M' | 'B' << 8 | 'R' << 16 | '1' << 24;
    private static final int HEADER_WORDS = 16;

    private final Path target;
    private final Path temporary;
    private final FileChannel channel;
    private final ByteBuffer buffer;

    private final int[] header = new int[HEADER_WORDS];
    private final int obsDim;
    private final int actDim;
    private final int hidden;
    private final int rowBytes;

    private int rows;
    private int steps;

    private int segments;
    private int[] segmentRows = new int[64];
    private float[] segmentStates;

    private boolean closed;

    public RolloutWriter(Path target, int schemaId, int topologyHash, int iteration, int round, int worker,
                         int workerCount, int obsDim, int actDim, int hidden) {

        this.target = target;
        this.temporary = target.resolveSibling(target.getFileName() + ".tmp");
        this.obsDim = obsDim;
        this.actDim = actDim;
        this.hidden = hidden;
        this.rowBytes = 4 * (4 + actDim + obsDim);
        this.segmentStates = new float[64 * hidden];

        this.header[0] = MAGIC;
        this.header[1] = VERSION;
        this.header[2] = schemaId;
        this.header[3] = topologyHash;
        this.header[4] = iteration;
        this.header[5] = round;
        this.header[6] = worker;
        this.header[7] = workerCount;
        this.header[8] = obsDim;
        this.header[9] = actDim;
        this.header[10] = hidden;

        try {

            Files.createDirectories(target.getParent());

            this.channel = FileChannel.open(this.temporary, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);

            // Room for a few hundred rows before anything is written out; the header is filled in on close, when the
            // counts are known.
            this.buffer = ByteBuffer.allocateDirect(Math.max(1 << 20, 64 * this.rowBytes)).order(ByteOrder.LITTLE_ENDIAN);
            this.buffer.position(4 * HEADER_WORDS);
        }

        catch (IOException exception) {

            throw new UncheckedIOException("Could not open a rollout shard at " + this.temporary, exception);
        }
    }

    /** Remembers the hidden state going into the next row written, which starts a segment. */
    public void beginSegment(float[] state, int base) {

        if (this.segments == this.segmentRows.length) {

            this.segmentRows = Arrays.copyOf(this.segmentRows, this.segments * 2);
            this.segmentStates = Arrays.copyOf(this.segmentStates, this.segments * 2 * this.hidden);
        }

        this.segmentRows[this.segments] = this.rows;
        System.arraycopy(state, base, this.segmentStates, this.segments * this.hidden, this.hidden);
        this.segments++;
    }

    /**
     * A row an action was taken on.
     *
     * @param newEpisode whether the agent's episode starts here
     */
    public void step(int agent, boolean newEpisode, float reward, float logProb, float[] actions, int actionBase,
                     float[] obs, int obsBase) {

        this.row(agent, newEpisode ? FLAG_NEW : 0, reward, logProb, actions, actionBase, obs, obsBase);
        this.steps++;
    }

    /** The last row of a segment: an observation and a reward, and nothing chosen. */
    public void end(int agent, boolean done, float reward, float[] obs, int obsBase) {

        this.row(agent, done ? FLAG_DONE : FLAG_TRUNCATED, reward, 0.0F, null, 0, obs, obsBase);
    }

    private void row(int agent, int flags, float reward, float logProb, float[] actions, int actionBase,
                     float[] obs, int obsBase) {

        if (this.buffer.remaining() < this.rowBytes) {

            this.drain();
        }

        this.buffer.putInt(agent);
        this.buffer.putInt(flags);
        this.buffer.putFloat(reward);
        this.buffer.putFloat(logProb);

        for (int index = 0; index < this.actDim; index++) {

            this.buffer.putFloat(actions == null ? 0.0F : actions[actionBase + index]);
        }

        this.buffer.asFloatBuffer().put(obs, obsBase, this.obsDim);
        this.buffer.position(this.buffer.position() + 4 * this.obsDim);

        this.rows++;
    }

    /** Actions taken so far, which is what the per iteration quota counts. */
    public int steps() {

        return this.steps;
    }

    /**
     * Writes the segment table and the header, and renames the shard into place.
     *
     * @param last whether this game process is done for good, so the training side stops waiting for it
     */
    public void close(boolean last) {

        if (this.closed) {

            return;
        }

        this.closed = true;

        try (FileChannel out = this.channel) {

            for (int segment = 0; segment < this.segments; segment++) {

                if (this.buffer.remaining() < 4 * (1 + this.hidden)) {

                    this.drain();
                }

                this.buffer.putInt(this.segmentRows[segment]);
                this.buffer.asFloatBuffer().put(this.segmentStates, segment * this.hidden, this.hidden);
                this.buffer.position(this.buffer.position() + 4 * this.hidden);
            }

            this.drain();

            this.header[11] = last ? 1 : 0;
            this.header[12] = this.rows;
            this.header[13] = this.segments;
            this.header[14] = this.steps;

            ByteBuffer head = ByteBuffer.allocate(4 * HEADER_WORDS).order(ByteOrder.LITTLE_ENDIAN);

            for (int word : this.header) {

                head.putInt(word);
            }

            head.flip();

            // Its own slot at the front of the file, left empty until now, when the counts are known.
            while (head.hasRemaining()) {

                out.write(head, head.position());
            }

            out.force(false);
        }

        catch (IOException exception) {

            throw new UncheckedIOException("Could not finish the rollout shard at " + this.temporary, exception);
        }

        try {

            Files.move(this.temporary, this.target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }

        catch (IOException exception) {

            throw new UncheckedIOException("Could not move the rollout shard into place at " + this.target, exception);
        }
    }

    private void drain() {

        this.buffer.flip();

        try {

            while (this.buffer.hasRemaining()) {

                this.channel.write(this.buffer);
            }
        }

        catch (IOException exception) {

            throw new UncheckedIOException("Could not write to the rollout shard at " + this.temporary, exception);
        }

        this.buffer.clear();
    }

    @Override
    public String toString() {

        return this.target.getFileName() + " (" + this.rows + " rows, " + this.steps + " steps)";
    }
}
