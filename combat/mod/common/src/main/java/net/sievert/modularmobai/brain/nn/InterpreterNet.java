package net.sievert.modularmobai.brain.nn;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

/**
 * The mind's <b>interpreter</b>: one line of chat in, one label set out. A fastText-shaped classifier — a hashed bag of
 * word, bigram and character-trigram features summed out of one 65,536 row embedding, a twenty column side vector beside
 * it, one hidden layer, and five linear heads.
 *
 * <pre>
 *   bag       = sum over the line's buckets of emb[bucket]        32 floats
 *   x         = concat(max(bag, 0), side)                         52
 *   h         = max(x fc1W' + fc1B, 0)                            64
 *   intent    = h headIntentW' + headIntentB                      13 logits, the label is the argmax
 *   topic, addressed, sincerity                                   12, 4, 3
 *   floats    = [sigmoid, tanh, sigmoid] of the three raw outputs  aggression, valence, urgency
 * </pre>
 *
 * <p>The whole specification is {@code shared/models/interpreter/README.md}, and the weights are
 * {@code shared/models/interpreter/interpreter.mbw}, kind {@link WeightFile#KIND_CLASSIFIER}.
 *
 * <p><b>This is not a {@code Brain} and should not be made one.</b> It is not per agent and not per tick: it runs once per
 * chat message, on the server thread, and its answer is handed to whichever agent heard it. There is no agent in the loop
 * when a player presses enter, so there is nothing for {@code AgentDriver} to batch.
 *
 * <p><b>Thread confined.</b> Every working buffer belongs to this object so that a message costs no allocation after the
 * first few, which means one instance answers one thread — the server thread. Classification is microseconds, so a
 * message that ever cost more than a tick's budget should be queued, never threaded.
 *
 * <p>Three things are easy to get wrong and are written out longhand here because each one changes the hash and so every
 * number downstream of it:
 *
 * <ul>
 *   <li>lowercasing is <b>ASCII only</b>, {@code ch + 32} for {@code A}-{@code Z} and nothing else.
 *       {@code String.toLowerCase()} is locale dependent — Turkish {@code I} — and folds non-ASCII;</li>
 *   <li>characters are <b>code points</b>, never UTF-16 chars: a line with an emoji in it counts one character, not two,
 *       and its trigram windows are three code points wide;</li>
 *   <li>the hash is over the <b>UTF-8 bytes</b> of the feature string, so a non-ASCII token contributes its encoded
 *       bytes one at a time.</li>
 * </ul>
 *
 * <p>Nothing builds a {@code String} for a feature: the hash is taken straight off the code points, which is both the
 * fast way and the way that cannot pick up a platform encoding by accident.
 */
public final class InterpreterNet {

    /**
     * The layout these weights were trained against: the first four bytes of
     * {@code shared/models/interpreter/layout.json}'s sha256, read big endian, which is
     * {@code 09a397c9f8e809b303b9a1c94bf4ea32d42456273347374bd37a5d40017fbc1e}. A layout that changes moves this; see
     * {@link ScorerNet#SCHEMA_ID}.
     */
    public static final int SCHEMA_ID = 0x09a397c9;

    /** The heads, in the order the weight file carries them. */
    public static final int HEAD_INTENT = 0;
    public static final int HEAD_TOPIC = 1;
    public static final int HEAD_ADDRESSED = 2;
    public static final int HEAD_SINCERITY = 3;
    public static final int HEAD_FLOATS = 4;

    /** What each head's outputs mean. Layout, not topology: these say what an output index <i>is</i>. */
    public static final String[] INTENTS = {"GREET", "FAREWELL", "SMALLTALK", "QUESTION", "REQUEST", "COMMAND", "OFFER",
            "PRAISE", "APOLOGY", "INSULT", "THREAT", "WARNING", "ACCUSE"};

    public static final String[] TOPICS = {"FORGE", "MINE", "TREASURE", "FOOD", "DRINK", "HOME", "WEAPON", "WORK",
            "CLAN", "MONSTER", "TRADE", "NONE"};

    public static final String[] ADDRESSED = {"LISTENER", "THIRD", "GROUP", "NONE"};

    public static final String[] SINCERITY = {"SINCERE", "SARCASTIC", "JOKING"};

    /** What the caller passes for "nothing was said before this", and what an unrecognised intent becomes. */
    public static final String UNKNOWN_INTENT = "unknown";

    /** The side vector's scalar columns, before the previous intent's one-hot. */
    private static final int SIDE_SCALARS = 6;

    private static final int FNV_OFFSET_BASIS = 0x811C9DC5;
    private static final int FNV_PRIME = 0x01000193;

    /** {@code fnv1a_32("") & 0xffff}: the one bucket a line with no tokens at all uses, so a bag is never empty. */
    public static final int EMPTY_BUCKET = FNV_OFFSET_BASIS & 0xFFFF;

    private static final String PREFIX_UNIGRAM = "w:";
    private static final String PREFIX_BIGRAM = "b:";
    private static final String PREFIX_TRIGRAM = "c:";
    private static final String PREFIX_FIRST = "^first=";
    private static final String PREFIX_LAST = "$last=";

    private static final int PAD_LEFT = '<';
    private static final int PAD_RIGHT = '>';
    private static final int APOSTROPHE = '\'';

    /**
     * The 40 separators: the ASCII punctuation block without the apostrophe, the ASCII whitespace controls, and the en
     * dash, em dash and ellipsis. Everything else, digits and non-ASCII letters included, belongs to a token.
     */
    private static final boolean[] ASCII_SEPARATOR = asciiSeparators();

    private static final int EN_DASH = 0x2013;
    private static final int EM_DASH = 0x2014;
    private static final int ELLIPSIS = 0x2026;

    private static final int LEFT_SINGLE_QUOTE = 0x2018;
    private static final int RIGHT_SINGLE_QUOTE = 0x2019;
    private static final int LEFT_DOUBLE_QUOTE = 0x201C;
    private static final int RIGHT_DOUBLE_QUOTE = 0x201D;

    /**
     * Written as code point numbers rather than as characters so that nothing here depends on the encoding this file
     * happens to be read in: one wrongly decoded separator is a different tokenisation and so a different hash for every
     * line that holds one, which is exactly the class of bug parity exists to catch and a poor way to spend an afternoon.
     */
    private static boolean[] asciiSeparators() {

        boolean[] separators = new boolean[128];

        for (char ch : "!\"#$%&()*+,-./:;<=>?@[\\]^_`{|}~".toCharArray()) {

            separators[ch] = true;
        }

        // Space, tab, line feed, vertical tab, form feed, carriage return.
        for (int ch : new int[] {0x20, 0x09, 0x0A, 0x0B, 0x0C, 0x0D}) {

            separators[ch] = true;
        }

        return separators;
    }

    private final String id;
    private final ClassifierShape shape;
    private final float[] params;
    private final int bucketMask;

    /**
     * The line as code points twice over, and where each token starts and ends: {@link #raw} exactly as it arrived,
     * which is what the side vector counts, and {@link #cooked} folded and lowercased, which is what a token holds and
     * what the hash sees. Both are needed, and keeping only one is the bug that quietly halves {@code caps_share}: the
     * side vector counts the capitals of the <b>raw</b> line and a token has none left. Grown, never reallocated per call.
     */
    private int[] raw = new int[256];
    private int[] cooked = new int[256];
    private int pointCount;

    private int[] tokenStart = new int[64];
    private int[] tokenEnd = new int[64];
    private int tokenCount;

    private int[] buckets = new int[1024];
    private int bucketCount;

    private final float[] side;
    private final float[] input;
    private final float[] hidden;
    private final float[][] logits;
    private final float[] floats = new float[3];

    public InterpreterNet(MindWeights weights) {

        this.shape = weights.classifier();

        if (this.shape.headCount() != 5) {

            throw new IllegalArgumentException(weights.id() + " has " + this.shape.headCount()
                    + " heads and this layout has five: intent, topic, addressed, sincerity, floats");
        }

        expect(weights.id(), "intent", this.shape.headWidth(HEAD_INTENT), INTENTS.length);
        expect(weights.id(), "topic", this.shape.headWidth(HEAD_TOPIC), TOPICS.length);
        expect(weights.id(), "addressed", this.shape.headWidth(HEAD_ADDRESSED), ADDRESSED.length);
        expect(weights.id(), "sincerity", this.shape.headWidth(HEAD_SINCERITY), SINCERITY.length);
        expect(weights.id(), "floats", this.shape.headWidth(HEAD_FLOATS), 3);
        expect(weights.id(), "side columns", this.shape.side(), SIDE_SCALARS + INTENTS.length + 1);

        if (weights.schemaId() != SCHEMA_ID) {

            throw new IllegalArgumentException(String.format(Locale.ROOT, "%s carries schema %08x and this build reads "
                    + "%08x; the layout moved, so re-run mind's tools/mbw.py and this constant with it",
                    weights.id(), weights.schemaId(), SCHEMA_ID));
        }

        this.id = weights.id();
        this.params = weights.params();
        this.bucketMask = this.shape.buckets() - 1;

        this.side = new float[this.shape.side()];
        this.input = new float[this.shape.fc1In()];
        this.hidden = new float[this.shape.hidden()];
        this.logits = new float[this.shape.headCount()][];

        for (int head = 0; head < this.shape.headCount(); head++) {

            this.logits[head] = new float[this.shape.headWidth(head)];
        }
    }

    private static void expect(String id, String what, int got, int wanted) {

        if (got != wanted) {

            throw new IllegalArgumentException(id + " has " + got + " " + what + " and this layout has " + wanted);
        }
    }

    public static InterpreterNet load(Path path) throws IOException {

        return new InterpreterNet(WeightFile.readMind(path, WeightFile.KIND_CLASSIFIER));
    }

    public String id() {

        return this.id;
    }

    public ClassifierShape shape() {

        return this.shape;
    }

    /** What one line of chat was read as. The names are not predicted, so they are not here; see the port notes. */
    public record Reading(String intent, String topic, String addressed, String sincerity,
                          float aggression, float valence, float urgency) {
    }

    /**
     * One line of chat.
     *
     * @param text       the raw message, unmodified: no stripping, no lowercasing, no truncation
     * @param prevIntent what the agent last said to this speaker, or null; anything that is not one of {@link #INTENTS}
     *                   exactly is {@link #UNKNOWN_INTENT}, which is what every training row carried
     */
    public Reading classify(String text, String prevIntent) {

        this.run(text, prevIntent);

        return new Reading(INTENTS[argmax(this.logits[HEAD_INTENT])],
                TOPICS[argmax(this.logits[HEAD_TOPIC])],
                ADDRESSED[argmax(this.logits[HEAD_ADDRESSED])],
                SINCERITY[argmax(this.logits[HEAD_SINCERITY])],
                this.floats[0], this.floats[1], this.floats[2]);
    }

    /**
     * The pass, leaving the buckets, the side vector and every head's pre-softmax logits where {@link #buckets()},
     * {@link #side()} and {@link #logits(int)} can be read off. That is what the parity check holds against the frozen
     * answer sheet, in that order, because each stage is downstream of the last.
     */
    public void run(String text, String prevIntent) {

        this.tokenize(text);
        this.hashFeatures();
        this.sideFeatures(prevIntent);

        final float[] w = this.params;
        final int dim = this.shape.dim();
        final int emb = this.shape.emb();

        // The bag: the rows this line's buckets name, summed, repeats counted every time. One flat array indexed by
        // bucket, never rows of objects -- it is 8 MB and one row of it is read per feature.
        for (int k = 0; k < dim; k++) {

            this.input[k] = 0.0F;
        }

        for (int index = 0; index < this.bucketCount; index++) {

            final int row = emb + this.buckets[index] * dim;

            for (int k = 0; k < dim; k++) {

                this.input[k] += w[row + k];
            }
        }

        for (int k = 0; k < dim; k++) {

            if (this.input[k] < 0.0F) {

                this.input[k] = 0.0F;
            }
        }

        System.arraycopy(this.side, 0, this.input, dim, this.side.length);

        linear(w, this.shape.fc1W(), this.shape.fc1B(), this.input, this.input.length, this.hidden, true);

        for (int head = 0; head < this.logits.length; head++) {

            linear(w, this.shape.headW(head), this.shape.headB(head), this.hidden, this.hidden.length,
                    this.logits[head], false);
        }

        // The three continuous outputs are the only ones that are squashed, and they are squashed here rather than in
        // classify() so that the parity check measures the very expression the game runs.
        final float[] raw = this.logits[HEAD_FLOATS];

        this.floats[0] = Forward.sigmoid(raw[0]);
        this.floats[1] = Forward.tanh(raw[1]);
        this.floats[2] = Forward.sigmoid(raw[2]);
    }

    /** {@code out[j] = bias[j] + sum over k of w[j][k] * in[k]}, row major, {@link Forward}'s arithmetic exactly. */
    private static void linear(float[] w, int weights, int bias, float[] in, int inDim, float[] out, boolean relu) {

        for (int j = 0; j < out.length; j++) {

            final int row = weights + j * inDim;
            float sum = w[bias + j];

            for (int k = 0; k < inDim; k++) {

                sum += w[row + k] * in[k];
            }

            out[j] = relu && sum < 0.0F ? 0.0F : sum;
        }
    }

    private static int argmax(float[] values) {

        int best = 0;

        for (int index = 1; index < values.length; index++) {

            if (values[index] > values[best]) {

                best = index;
            }
        }

        return best;
    }

    // -- what the parity check reads ------------------------------------------------------------------------------

    /** The bucket list of the last line, in feature order with repeats kept. Valid for {@link #bucketCount()} entries. */
    public int[] buckets() {

        return this.buckets;
    }

    public int bucketCount() {

        return this.bucketCount;
    }

    /** The last line's side vector, 20 floats in the frozen order. */
    public float[] side() {

        return this.side;
    }

    /** One head's pre-softmax logits from the last line. {@link #HEAD_FLOATS} is before its three activations. */
    public float[] logits(int head) {

        return this.logits[head];
    }

    /** The last line's aggression, valence and urgency, after their sigmoid, tanh and sigmoid. */
    public float[] floats() {

        return this.floats;
    }

    // -- tokenizer -------------------------------------------------------------------------------------------------

    /**
     * The raw line into code points and tokens, in the four steps the specification gives: fold the curly quotes, split
     * on separators, lowercase ASCII, strip apostrophes from both ends of a token. A token that becomes empty is dropped.
     *
     * <p>The line is kept as code points as well, because the side vector counts characters of the <b>raw</b> line and
     * has to count them the way Python's {@code len} does.
     */
    private void tokenize(String text) {

        final int length = text.length();

        // All four are sized up front, from the one bound that covers them: a line of n UTF-16 units has at most n code
        // points and so at most n tokens. Growing a token array from inside addToken would be a bug -- ensure does not
        // copy, because nothing it grows holds anything worth keeping at the moment it is called.
        this.raw = ensure(this.raw, length);
        this.cooked = ensure(this.cooked, length);
        this.tokenStart = ensure(this.tokenStart, length);
        this.tokenEnd = ensure(this.tokenEnd, length);

        this.pointCount = 0;
        this.tokenCount = 0;

        int start = -1;

        for (int index = 0; index < length; ) {

            final int point = text.codePointAt(index);
            index += Character.charCount(point);

            final int folded = fold(point);

            this.raw[this.pointCount] = point;
            this.cooked[this.pointCount] = lowerAscii(folded);

            if (separator(folded)) {

                if (start >= 0) {

                    this.addToken(start, this.pointCount);
                    start = -1;
                }
            }

            else if (start < 0) {

                start = this.pointCount;
            }

            this.pointCount++;
        }

        if (start >= 0) {

            this.addToken(start, this.pointCount);
        }
    }

    /**
     * One token, once its bounds are known. The apostrophes come off both ends here rather than while reading, because
     * that is where the specification puts them: {@code 'hello'} is {@code hello} and {@code don't} is untouched.
     *
     * <p>The bounds are into {@link #cooked}, the folded and lowercased line, so a curly apostrophe at the end of a token
     * comes off exactly as an ASCII one does.
     */
    private void addToken(int from, int to) {

        while (from < to && this.cooked[from] == APOSTROPHE) {

            from++;
        }

        while (to > from && this.cooked[to - 1] == APOSTROPHE) {

            to--;
        }

        if (from >= to) {

            return;
        }

        this.tokenStart[this.tokenCount] = from;
        this.tokenEnd[this.tokenCount] = to;
        this.tokenCount++;
    }

    private static int fold(int point) {

        return switch (point) {

            case LEFT_SINGLE_QUOTE, RIGHT_SINGLE_QUOTE -> '\'';
            case LEFT_DOUBLE_QUOTE, RIGHT_DOUBLE_QUOTE -> '"';
            default -> point;
        };
    }

    private static boolean separator(int point) {

        return point < 128 ? ASCII_SEPARATOR[point] : point == EN_DASH || point == EM_DASH || point == ELLIPSIS;
    }

    private static int lowerAscii(int point) {

        return point >= 'A' && point <= 'Z' ? point + 32 : point;
    }

    // -- features --------------------------------------------------------------------------------------------------

    /**
     * Every feature string of this line, hashed, in the frozen order: unigrams, bigrams, trigrams, the first word, the
     * last word. The bag is a sum, so the order is documentation — but the parity file lists the buckets in it, and a
     * mismatch there is the first thing to look at, so it is kept.
     *
     * <p>A line with no tokens at all emits no feature strings and uses {@link #EMPTY_BUCKET} instead.
     */
    private void hashFeatures() {

        this.bucketCount = 0;

        if (this.tokenCount == 0) {

            this.buckets = ensure(this.buckets, 1);
            this.buckets[this.bucketCount++] = EMPTY_BUCKET;
            return;
        }

        // Unigrams, bigrams, one trigram per code point of every token, and the two position features.
        int points = 0;

        for (int token = 0; token < this.tokenCount; token++) {

            points += this.tokenEnd[token] - this.tokenStart[token];
        }

        this.buckets = ensure(this.buckets, 2 * this.tokenCount + points + 2);

        for (int token = 0; token < this.tokenCount; token++) {

            this.add(this.token(hash(PREFIX_UNIGRAM), token));
        }

        for (int token = 0; token + 1 < this.tokenCount; token++) {

            int h = this.token(hash(PREFIX_BIGRAM), token);
            h = byteOf(h, ' ');
            this.add(this.token(h, token + 1));
        }

        for (int token = 0; token < this.tokenCount; token++) {

            final int from = this.tokenStart[token];
            final int to = this.tokenEnd[token];

            // The windows of "<token>", three code points each, so a one-character token yields exactly "<a>".
            for (int window = from - 1; window + 2 <= to; window++) {

                int h = hash(PREFIX_TRIGRAM);

                for (int at = window; at < window + 3; at++) {

                    h = point(h, at < from ? PAD_LEFT : at >= to ? PAD_RIGHT : this.cooked[at]);
                }

                this.add(h);
            }
        }

        this.add(this.token(hash(PREFIX_FIRST), 0));
        this.add(this.token(hash(PREFIX_LAST), this.tokenCount - 1));
    }

    private void add(int hash) {

        this.buckets[this.bucketCount++] = hash & this.bucketMask;
    }

    private int token(int hash, int token) {

        for (int at = this.tokenStart[token]; at < this.tokenEnd[token]; at++) {

            hash = point(hash, this.cooked[at]);
        }

        return hash;
    }

    /** FNV-1a over the UTF-8 bytes of an ASCII prefix, from the offset basis. */
    private static int hash(String prefix) {

        int h = FNV_OFFSET_BASIS;

        for (int index = 0; index < prefix.length(); index++) {

            h = byteOf(h, prefix.charAt(index));
        }

        return h;
    }

    /** One code point's UTF-8 bytes folded in, so nothing ever builds a {@code String} to hash it. */
    private static int point(int hash, int point) {

        if (point < 0x80) {

            return byteOf(hash, point);
        }

        if (point < 0x800) {

            hash = byteOf(hash, 0xC0 | (point >> 6));
            return byteOf(hash, 0x80 | (point & 0x3F));
        }

        if (point < 0x10000) {

            hash = byteOf(hash, 0xE0 | (point >> 12));
            hash = byteOf(hash, 0x80 | ((point >> 6) & 0x3F));
            return byteOf(hash, 0x80 | (point & 0x3F));
        }

        hash = byteOf(hash, 0xF0 | (point >> 18));
        hash = byteOf(hash, 0x80 | ((point >> 12) & 0x3F));
        hash = byteOf(hash, 0x80 | ((point >> 6) & 0x3F));
        return byteOf(hash, 0x80 | (point & 0x3F));
    }

    private static int byteOf(int hash, int value) {

        return (hash ^ (value & 0xFF)) * FNV_PRIME;
    }

    /**
     * The twenty side columns, all of them off the <b>raw</b> line: the word count the bag's own tokenizer gave, the
     * character count in code points, the share of ASCII letters that are capitals, the counts of {@code !} and
     * {@code ?}, whether two full stops are ever adjacent, and the fourteen-way one-hot of the previous line's intent.
     */
    private void sideFeatures(String prevIntent) {

        int letters = 0;
        int capitals = 0;
        int bangs = 0;
        int questions = 0;
        boolean ellipsis = false;
        boolean previousDot = false;

        for (int index = 0; index < this.pointCount; index++) {

            final int point = this.raw[index];

            if (point >= 'A' && point <= 'Z') {

                letters++;
                capitals++;
            }

            else if (point >= 'a' && point <= 'z') {

                letters++;
            }

            if (point == '!') {

                bangs++;
            }

            else if (point == '?') {

                questions++;
            }

            if (point == '.') {

                ellipsis |= previousDot;
                previousDot = true;
            }

            else {

                previousDot = false;
            }
        }

        java.util.Arrays.fill(this.side, 0.0F);

        this.side[0] = (float) Math.log1p(this.tokenCount);
        this.side[1] = (float) Math.log1p(this.pointCount);
        this.side[2] = letters == 0 ? 0.0F : (float) ((double) capitals / (double) letters);
        this.side[3] = bangs;
        this.side[4] = questions;
        this.side[5] = ellipsis ? 1.0F : 0.0F;
        this.side[SIDE_SCALARS + previousIntent(prevIntent)] = 1.0F;
    }

    /** Where the previous intent's one-hot goes: its place in {@link #INTENTS}, or the {@code unknown} column after them. */
    private static int previousIntent(String prevIntent) {

        if (prevIntent != null) {

            for (int index = 0; index < INTENTS.length; index++) {

                if (INTENTS[index].equals(prevIntent)) {

                    return index;
                }
            }
        }

        return INTENTS.length;
    }

    /**
     * A buffer at least this wide, grown with headroom so that a long line is paid for once and every line after it
     * allocates nothing. <b>It does not copy</b>: everything it grows is about to be overwritten from index zero, and
     * every caller has to be somewhere that is true.
     */
    private static int[] ensure(int[] array, int wanted) {

        return array.length >= wanted ? array : new int[Math.max(wanted, array.length * 2)];
    }
}
