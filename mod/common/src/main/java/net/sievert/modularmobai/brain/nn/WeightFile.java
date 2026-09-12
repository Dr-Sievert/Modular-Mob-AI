package net.sievert.modularmobai.brain.nn;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads the {@code .mbw} files the training side exports.
 *
 * <pre>
 *   offset  size  content                                   all little endian
 *   0       4     magic 'M','B','W','1'
 *   4       4     u32 format version
 *   8       4     u32 schema id        which body's observation and action layout these weights were trained against
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
 *
 * <p>The schema id is read and carried, not judged, because reading a file is a lower layer than knowing what bodies
 * exist. Which body the id names, and whether this build has that body at all, is settled the moment a brain is made of
 * these weights ({@code NeuralBrain.check}), and whether that body is the one the weights are about to drive is settled
 * again by the driver, which names both sides when it refuses.
 */
public final class WeightFile {

    private WeightFile() {}

    public static final String EXTENSION = ".mbw";

    /**
     * 2 added the four numbers that describe a shared encoder over the enemy slots; see Topology. A version 1 file still
     * reads, as a network without one, because the shapes it can describe are a subset of what 2 can, and refusing them
     * would retire every network trained before this for no reason.
     */
    public static final int VERSION = 2;
    public static final int OLDEST = 1;

    private static final byte[] MAGIC = {'M', 'B', 'W', '1'};
    private static final int HEADER_BYTES_V1 = 52;
    private static final int HEADER_BYTES = 68;

    public static WeightSet read(Path path) throws IOException {

        return read(Files.readAllBytes(path), path.getFileName().toString(), path.toAbsolutePath().toString());
    }

    /**
     * The same, for a file's bytes that came from somewhere other than a path of their own, such as a network the mod's
     * jar carries.
     *
     * @param name   what the weights are called in logs, their {@link WeightSet#id()}
     * @param source where they came from, for the message when they are refused
     */
    public static WeightSet read(byte[] bytes, String name, String source) throws IOException {

        // The shorter of the two headers, since which one this is cannot be known before the version is read.
        if (bytes.length < HEADER_BYTES_V1) {

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

        if (version < OLDEST || version > VERSION) {

            throw refuse(source, "is format version " + version + " and this build reads " + OLDEST + " to " + VERSION);
        }

        int headerBytes = version >= 2 ? HEADER_BYTES : HEADER_BYTES_V1;

        if (bytes.length < headerBytes) {

            throw refuse(source, "is shorter than the header its own version needs");
        }

        int schemaId = buffer.getInt();
        int storedHash = buffer.getInt();

        int obsDim = buffer.getInt();
        int h1 = buffer.getInt();
        int hidden = buffer.getInt();
        int h3 = buffer.getInt();
        int outDim = buffer.getInt();
        int stdDim = buffer.getInt();

        // Version 1 is exactly a version 2 file with these at zero: a network whose first layer takes the whole row.
        int slotAt = version >= 2 ? buffer.getInt() : 0;
        int slots = version >= 2 ? buffer.getInt() : 0;
        int slotStride = version >= 2 ? buffer.getInt() : 0;
        int slotEnc = version >= 2 ? buffer.getInt() : 0;

        Topology topology = new Topology(obsDim, h1, hidden, h3, outDim, stdDim, slotAt, slots, slotStride, slotEnc);

        if (topology.hash() != storedHash) {

            throw refuse(source, "has a topology hash that does not match its own dimensions, so the header is corrupt");
        }

        float obsClip = buffer.getFloat();
        int iteration = buffer.getInt();
        int count = buffer.getInt();

        if (count != topology.size()) {

            throw refuse(source, "holds " + count + " parameters but " + topology + " needs " + topology.size());
        }

        long expectedLength = headerBytes + 4L * count;

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
