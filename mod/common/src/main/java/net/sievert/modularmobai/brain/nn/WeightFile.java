package net.sievert.modularmobai.brain.nn;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Reads the {@code .mbw} files the training side exports.
 *
 * <pre>
 *   offset  size  content                                   all little endian
 *   0       4     magic 'M','B','W','1'
 *   4       4     u32 format version
 *   8       4     u32 schema id        must match the running observation and action layout
 *   12      4     u32 topology hash    CRC32 of the six dimensions below
 *   16      24    u32 obsDim, h1, hidden, h3, outDim, stdDim
 *   40      4     f32 observation clip
 *   44      4     u32 training iteration
 *   48      4     u32 parameter count
 *   52      N*4   f32 parameters, in {@link Topology}'s segment order
 * </pre>
 *
 * <p>Anything that does not add up is refused, loudly, and never coerced: no padding, no truncating, no guessing which
 * slot was meant. A network loaded against a layout it was not trained on does not crash, it reads health out of the
 * slot that used to hold something else and plays badly for reasons nobody can find. Failing the start is the kind
 * outcome. Same discipline as a DBC revision mismatch on a bus.
 */
public final class WeightFile {

    private WeightFile() {}

    public static final String EXTENSION = ".mbw";

    public static final int VERSION = 1;

    private static final byte[] MAGIC = {'M', 'B', 'W', '1'};
    private static final int HEADER_BYTES = 52;

    /**
     * @param expectedSchemaId the id of the layout the caller is going to feed the network. Pass the running schema's
     *                         id; there is no way to ask for "whatever is in the file".
     */
    public static WeightSet read(Path path, int expectedSchemaId) throws IOException {

        return read(Files.readAllBytes(path), path.getFileName().toString(), path.toAbsolutePath().toString(), expectedSchemaId);
    }

    /**
     * The same, for a file's bytes that came from somewhere other than a path of their own, such as a network the mod's
     * jar carries.
     *
     * @param name   what the weights are called in logs, their {@link WeightSet#id()}
     * @param source where they came from, for the message when they are refused
     */
    public static WeightSet read(byte[] bytes, String name, String source, int expectedSchemaId) throws IOException {

        if (bytes.length < HEADER_BYTES) {

            throw refuse(source, "is " + bytes.length + " bytes, shorter than the header");
        }

        for (int index = 0; index < MAGIC.length; index++) {

            if (bytes[index] != MAGIC[index]) {

                throw refuse(source, "is not a weight file");
            }
        }

        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        buffer.position(MAGIC.length);

        int version = buffer.getInt();

        if (version != VERSION) {

            throw refuse(source, "is format version " + version + " and this build reads version " + VERSION);
        }

        int schemaId = buffer.getInt();

        if (schemaId != expectedSchemaId) {

            throw refuse(source, String.format(Locale.ROOT,
                    "was trained against schema %08x but the game is running schema %08x. The observation or action "
                            + "layout has changed since; these weights cannot drive it", schemaId, expectedSchemaId));
        }

        int storedHash = buffer.getInt();

        Topology topology = new Topology(buffer.getInt(), buffer.getInt(), buffer.getInt(), buffer.getInt(), buffer.getInt(), buffer.getInt());

        if (topology.hash() != storedHash) {

            throw refuse(source, "has a topology hash that does not match its own dimensions, so the header is corrupt");
        }

        float obsClip = buffer.getFloat();
        int iteration = buffer.getInt();
        int count = buffer.getInt();

        if (count != topology.size()) {

            throw refuse(source, "holds " + count + " parameters but " + topology + " needs " + topology.size());
        }

        long expectedLength = HEADER_BYTES + 4L * count;

        if (bytes.length != expectedLength) {

            throw refuse(source, "is " + bytes.length + " bytes but its header says " + expectedLength);
        }

        float[] params = new float[count];
        buffer.asFloatBuffer().get(params);

        for (int index = 0; index < count; index++) {

            if (!Float.isFinite(params[index])) {

                throw refuse(source, "holds a non finite parameter at index " + index);
            }
        }

        if (!(obsClip > 0.0F) || !Float.isFinite(obsClip)) {

            throw refuse(source, "has an observation clip of " + obsClip);
        }

        return new WeightSet(name, schemaId, iteration, topology, obsClip, params);
    }

    private static IOException refuse(String source, String reason) {

        return new IOException("Refusing to load " + source + ": it " + reason);
    }
}
