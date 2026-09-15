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
 *   12      4     u32 shape hash       CRC32 of the dimensions below
 *   16      24    u32 obsDim, h1, hidden, h3, outDim, stdDim
 *   40      16    u32 slotAt, slots, slotStride, slotHeads   version 2 and later; all zero for a network that attends nothing
 *   56      4     f32 observation clip
 *   60      4     u32 training iteration
 *   64      4     u32 parameter count
 *   68      4     u32 kind             version 4 and later; 0, the recurrent actor, for every earlier file
 *   72      N*4   f32 parameters, in the segment order of whatever shape the kind names
 * </pre>
 *
 * <p>A version 1 file has none of those four slot numbers, so its header stops after the six dimensions: the clip, the
 * iteration and the count sit at 40, 44 and 48, and the parameters begin at 52. It reads as a file whose four slot numbers
 * are zero, which is exactly the shape it describes.
 *
 * <p>The <b>kind</b> says what the ten shape words mean and which pass the parameters are for. It was appended rather than
 * given a place among the dimensions precisely so that nothing before it moves: every file ever published reads byte for
 * byte as it did, and a reader of an older version simply never sees the word.
 *
 * <pre>
 *   0  the recurrent actor this mod trains, {@link Topology}, {@link Forward}  -- versions 1 to 3 are all this kind
 *   1  a plain feed-forward scorer, {@link ScorerShape}
 *   2  an embedding-bag classifier, {@link ClassifierShape}
 * </pre>
 *
 * <p>{@link #read} is the actor and only the actor: it refuses another kind by name rather than reading a classifier's
 * embedding as a normaliser. {@link #readMind} is the other two, and asks for the kind it wants, so nothing is ever
 * decided by what a file happens to say it is.
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
     * 2 added four numbers describing a shared max-pooling encoder over the enemy slots; 3 keeps the four words where they
     * are and gives the last of them a new meaning, {@code slotHeads}, the attention that replaced that encoder — see
     * {@link Topology} and {@link Forward}. Versions 1 and 2 still read wherever those four numbers are zero, which is
     * every network ever published: the shapes they describe are a subset of what 3 describes, and refusing them would
     * retire the whole of {@code models\} for no reason.
     *
     * <p>A version 2 file whose fourth number is <b>not</b> zero is the one thing that is refused rather than read. It is a
     * max-pooled network, a shape this build no longer has a pass for, and reading its parameters as an attention's would
     * be a network that loads and plays nonsense. Nothing was lost by dropping it: no pooled network was ever published or
     * kept.
     *
     * <p>4 appends the {@code kind} word, and nothing before it moves: a version 1, 2 or 3 file is read exactly as it was,
     * as {@link #KIND_ACTOR}.
     */
    public static final int VERSION = 4;
    public static final int OLDEST = 1;

    /** The recurrent actor this mod trains: {@link Topology}, {@link Forward}, a hidden vector per agent. */
    public static final int KIND_ACTOR = 0;

    /** A plain feed-forward scorer: {@link ScorerShape}. The mind's decisions model. */
    public static final int KIND_SCORER = 1;

    /** An embedding-bag classifier: {@link ClassifierShape}. The mind's interpreter. */
    public static final int KIND_CLASSIFIER = 2;

    /** How many shape words a header carries, whatever the kind means by them. */
    public static final int SHAPE_WORDS = 10;

    private static final byte[] MAGIC = {'M', 'B', 'W', '1'};
    private static final int HEADER_BYTES_V1 = 52;
    private static final int HEADER_BYTES_V2 = 68;
    private static final int HEADER_BYTES = 72;

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

        Header header = header(bytes, source);

        if (header.kind != KIND_ACTOR) {

            throw refuse(source, "is a kind " + header.kind + " weight file and this asked for the recurrent actor");
        }

        int[] words = header.words;

        // The one file that is refused by name rather than read: version 2 wrote a max-pooling encoder's width into that
        // last word, and the parameters behind it are one matrix over the slots and not a set of scores. Same number, other
        // network; see VERSION.
        if (header.version == 2 && words[9] > 0) {

            throw refuse(source, "is a max-pooled network, which this build no longer reads");
        }

        Topology topology = new Topology(words[0], words[1], words[2], words[3], words[4], words[5],
                words[6], words[7], words[8], words[9]);

        if (topology.hash() != header.storedHash) {

            throw refuse(source, "has a topology hash that does not match its own dimensions, so the header is corrupt");
        }

        if (header.count != topology.size()) {

            throw refuse(source, "holds " + header.count + " parameters but " + topology + " needs " + topology.size());
        }

        float[] params = header.parameters(bytes, source);

        if (!(header.obsClip > 0.0F) || !Float.isFinite(header.obsClip)) {

            throw refuse(source, "has an observation clip of " + header.obsClip);
        }

        return new WeightSet(name, header.schemaId, header.iteration, topology, header.obsClip, params);
    }

    /**
     * One of the kinds that is not the actor, and which one has to be said rather than discovered: a caller that wants a
     * scorer and is handed a classifier has been handed the wrong file, and that is a refusal and not a surprise.
     *
     * @param kind {@link #KIND_SCORER} or {@link #KIND_CLASSIFIER}
     */
    public static MindWeights readMind(Path path, int kind) throws IOException {

        return readMind(Files.readAllBytes(path), kind, path.getFileName().toString(), path.toAbsolutePath().toString());
    }

    public static MindWeights readMind(byte[] bytes, int kind, String name, String source) throws IOException {

        Header header = header(bytes, source);

        if (header.kind != kind) {

            throw refuse(source, "is a kind " + header.kind + " weight file and this asked for kind " + kind);
        }

        // The shape's own arithmetic, so the count and the hash are worked out in exactly one place per kind and a reader
        // and a pass can never disagree about where a segment starts.
        int size;
        int hash;
        String shape;

        switch (kind) {

            case KIND_SCORER -> {

                ScorerShape scorer = ScorerShape.of(header.words);
                size = scorer.size();
                hash = scorer.hash();
                shape = scorer.toString();
            }

            case KIND_CLASSIFIER -> {

                ClassifierShape classifier = ClassifierShape.of(header.words);
                size = classifier.size();
                hash = classifier.hash();
                shape = classifier.toString();
            }

            default -> throw refuse(source, "was asked for as kind " + kind + ", which this build has no shape for");
        }

        if (hash != header.storedHash) {

            throw refuse(source, "has a shape hash that does not match its own dimensions, so the header is corrupt");
        }

        if (header.count != size) {

            throw refuse(source, "holds " + header.count + " parameters but " + shape + " needs " + size);
        }

        return new MindWeights(name, kind, header.schemaId, header.iteration, header.words,
                header.parameters(bytes, source));
    }

    /**
     * Everything before the parameters, read once and checked once, whatever the kind. What the shape words mean is the
     * kind's business and is settled by the caller above; what they have to be — present, self consistent with the length
     * of the file — is the format's, and is settled here.
     */
    private record Header(int version, int kind, int schemaId, int storedHash, int[] words, float obsClip,
                          int iteration, int count, int headerBytes) {

        private float[] parameters(byte[] bytes, String source) throws IOException {

            long expectedLength = this.headerBytes + 4L * this.count;

            if (bytes.length != expectedLength) {

                throw refuse(source, "is " + bytes.length + " bytes but its header says " + expectedLength);
            }

            ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            buffer.position(this.headerBytes);

            float[] params = new float[this.count];
            buffer.asFloatBuffer().get(params);

            for (int index = 0; index < this.count; index++) {

                if (!Float.isFinite(params[index])) {

                    throw refuse(source, "holds a non finite parameter at index " + index);
                }
            }

            return params;
        }
    }

    private static Header header(byte[] bytes, String source) throws IOException {

        // The shortest of the headers, since which one this is cannot be known before the version is read.
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

        int headerBytes = version >= 4 ? HEADER_BYTES : version >= 2 ? HEADER_BYTES_V2 : HEADER_BYTES_V1;

        if (bytes.length < headerBytes) {

            throw refuse(source, "is shorter than the header its own version needs");
        }

        int schemaId = buffer.getInt();
        int storedHash = buffer.getInt();

        int[] words = new int[SHAPE_WORDS];

        for (int index = 0; index < 6; index++) {

            words[index] = buffer.getInt();
        }

        // Version 1 is exactly a later file with these at zero: a network whose first layer takes the whole row.
        for (int index = 6; index < SHAPE_WORDS; index++) {

            words[index] = version >= 2 ? buffer.getInt() : 0;
        }

        float obsClip = buffer.getFloat();
        int iteration = buffer.getInt();
        int count = buffer.getInt();

        // Every file before 4 is the one kind the format had.
        int kind = version >= 4 ? buffer.getInt() : KIND_ACTOR;

        return new Header(version, kind, schemaId, storedHash, words, obsClip, iteration, count, headerBytes);
    }

    private static IOException refuse(String source, String reason) {

        return new IOException("Refusing to load " + source + ": it " + reason);
    }
}
