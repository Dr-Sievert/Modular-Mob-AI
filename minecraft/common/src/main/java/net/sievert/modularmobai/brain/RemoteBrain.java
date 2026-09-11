package net.sievert.modularmobai.brain;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

/**
 * A brain that lives in another process, reached over a socket.
 *
 * <p>This is what drives an agent while it is being trained: the network stays in Python, where it is being learned, and
 * the game asks it for actions once a tick. One round trip per tick is affordable because the whole batch goes in one
 * message; a round trip per agent would not be.
 *
 * <p>Everything is little endian so the far side can read a message straight into an array without rearranging it.
 *
 * <h2>The conversation</h2>
 * On connect, the game sends a greeting carrying the observation layout as JSON. The far side does not write that layout
 * down anywhere of its own, which is what stops the two halves quietly disagreeing about which number means what. It
 * answers with one byte: zero to accept, anything else to refuse.
 *
 * <p>Then, once a tick, the game sends a step and waits for the actions:
 *
 * <pre>
 *   STEP     u8 type=1, u32 count, i32[count] ids, u8[count] flags, f32[count] rewards, f32[count*OBS] observations
 *   ACTIONS  u8 type=2, u32 count, f32[count*ACT] actions
 *   BYE      u8 type=0
 * </pre>
 */
public final class RemoteBrain implements Brain {

    private static final int MAGIC = 0x4D4D4149;
    private static final short VERSION = 1;

    private static final byte TYPE_BYE = 0;
    private static final byte TYPE_STEP = 1;
    private static final byte TYPE_ACTIONS = 2;

    private final SocketChannel channel;

    private ByteBuffer outgoing = allocate(64 * 1024);
    private ByteBuffer incoming = allocate(64 * 1024);

    /**
     * @param workerId which shard of a parallel run this is. Entity ids only mean anything within one process, so the far
     *                 side has to pair them with this to tell one worker's agent seven from another's.
     */
    public RemoteBrain(String host, int port, int workerId) {

        try {

            this.channel = SocketChannel.open(new InetSocketAddress(host, port));
            this.channel.setOption(java.net.StandardSocketOptions.TCP_NODELAY, true);

            this.greet(workerId);
        }

        catch (IOException exception) {

            throw new UncheckedIOException("Could not reach the training process at " + host + ":" + port, exception);
        }
    }

    private void greet(int workerId) throws IOException {

        byte[] schema = ObservationSchema.describeJson().getBytes(StandardCharsets.UTF_8);

        ByteBuffer hello = allocate(18 + schema.length);
        hello.putInt(MAGIC);
        hello.putShort(VERSION);
        hello.putInt(workerId);
        hello.putInt(schema.length);
        hello.put(schema);
        hello.flip();

        this.writeFully(hello);

        ByteBuffer reply = allocate(1);
        this.readFully(reply, 1);

        byte status = reply.get(0);

        if (status != 0) {

            throw new IOException("The training process refused the connection with status " + status
                    + ", which usually means it disagrees with the observation layout");
        }
    }

    @Override
    public void act(BrainStep step) {

        int count = step.count;

        if (count == 0) {

            return;
        }

        int payload = 1 + 4 + count * (4 + 1 + 4 + 4 * ObservationSchema.OBS_DIM);

        if (this.outgoing.capacity() < payload) {

            this.outgoing = allocate(payload);
        }

        this.outgoing.clear();
        this.outgoing.put(TYPE_STEP);
        this.outgoing.putInt(count);

        for (int index = 0; index < count; index++) {

            this.outgoing.putInt(step.agentIds[index]);
        }

        this.outgoing.put(step.flags, 0, count);

        for (int index = 0; index < count; index++) {

            this.outgoing.putFloat(step.rewards[index]);
        }

        // One bulk copy rather than a value at a time: the observations are the overwhelming majority of the message.
        this.outgoing.asFloatBuffer().put(step.observations, 0, count * ObservationSchema.OBS_DIM);
        this.outgoing.position(this.outgoing.position() + 4 * count * ObservationSchema.OBS_DIM);
        this.outgoing.flip();

        int expected = 1 + 4 + 4 * count * ActionSchema.ACT_DIM;

        if (this.incoming.capacity() < expected) {

            this.incoming = allocate(expected);
        }

        try {

            this.writeFully(this.outgoing);
            this.readFully(this.incoming, expected);
        }

        catch (IOException exception) {

            throw new UncheckedIOException("Lost the training process mid step", exception);
        }

        if (this.incoming.get(0) != TYPE_ACTIONS) {

            throw new IllegalStateException("Expected actions, got message type " + this.incoming.get(0));
        }

        int returned = this.incoming.getInt(1);

        if (returned != count) {

            throw new IllegalStateException("Asked for " + count + " actions and got " + returned);
        }

        this.incoming.position(5);
        this.incoming.asFloatBuffer().get(step.actions, 0, count * ActionSchema.ACT_DIM);
    }

    @Override
    public void close() {

        try (SocketChannel closing = this.channel) {

            ByteBuffer bye = allocate(1);
            bye.put(TYPE_BYE).flip();
            this.writeFully(bye);
        }

        catch (IOException ignored) {

            // Going away anyway.
        }
    }

    private void writeFully(ByteBuffer buffer) throws IOException {

        while (buffer.hasRemaining()) {

            if (this.channel.write(buffer) < 0) {

                throw new IOException("The training process closed the connection");
            }
        }
    }

    private void readFully(ByteBuffer buffer, int bytes) throws IOException {

        buffer.clear();
        buffer.limit(bytes);

        while (buffer.hasRemaining()) {

            if (this.channel.read(buffer) < 0) {

                throw new IOException("The training process closed the connection");
            }
        }

        buffer.rewind();
    }

    private static ByteBuffer allocate(int bytes) {

        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }
}
