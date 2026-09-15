package net.sievert.modularmobai.brain.nn;

import java.util.ArrayList;
import java.util.List;

/**
 * How the network's raw outputs become an action vector, written down as data rather than as convention.
 *
 * <p>The outputs split into independent blocks. Blocks never compete with each other, so an agent can move, jump,
 * attack and change weapon on the same tick; exclusivity only exists inside a categorical block, where exactly one
 * choice is made. Squashing, masking, sampling, log probabilities and the training loss all walk this table, so adding a
 * block later is one more row here and one more loss term, not a hunt through the code.
 *
 * <p>The table travels to the training side inside the schema, which is what keeps the two halves reading the outputs
 * the same way.
 */
public final class Heads {

    public enum Kind {

        /** Gaussian around {@code tanh(logit)}. One output, one action value, each. */
        CONTINUOUS,

        /** Held or not, from {@code sigmoid(logit)}. One output, one action value, each. */
        BINARY,

        /** One of {@code size} choices, from a masked softmax. {@code size} outputs, one action value: the index. */
        CATEGORICAL
    }

    /**
     * @param kind   how the block is squashed and sampled
     * @param size   how many outputs the block reads
     * @param action where the block's values start in the action vector
     * @param logit  where the block's outputs start in the network's output
     * @param std    where the block's spreads start in the log standard deviation segment, continuous blocks only
     * @param mask   for a categorical block, the observation offset of one value per choice, a choice being allowed
     *               only while its value is above zero; -1 for no mask
     */
    public record Block(String name, Kind kind, int size, int action, int logit, int std, int mask) {

        /** How many action values the block fills. */
        public int actionWidth() {

            return this.kind == Kind.CATEGORICAL ? 1 : this.size;
        }
    }

    private final Block[] blocks;
    private final int logitDim;
    private final int actDim;
    private final int stdDim;

    private Heads(Block[] blocks, int logitDim, int actDim, int stdDim) {

        this.blocks = blocks;
        this.logitDim = logitDim;
        this.actDim = actDim;
        this.stdDim = stdDim;
    }

    public Block[] blocks() {

        return this.blocks.clone();
    }

    Block block(int index) {

        return this.blocks[index];
    }

    int count() {

        return this.blocks.length;
    }

    /** How many outputs the network needs, which is its topology's {@code outDim}. */
    public int logitDim() {

        return this.logitDim;
    }

    public int actDim() {

        return this.actDim;
    }

    /** How many continuous values need a spread, which is the topology's {@code stdDim}. */
    public int stdDim() {

        return this.stdDim;
    }

    /** The table as a JSON array, for the schema the training side reads. */
    public String describeJson() {

        StringBuilder json = new StringBuilder(256).append('[');

        for (int index = 0; index < this.blocks.length; index++) {

            Block block = this.blocks[index];

            json.append(index == 0 ? "{" : ",{")
                    .append("\"name\":\"").append(block.name()).append('"')
                    .append(",\"kind\":\"").append(block.kind().name().toLowerCase(java.util.Locale.ROOT)).append('"')
                    .append(",\"size\":").append(block.size())
                    .append(",\"action\":").append(block.action())
                    .append(",\"logit\":").append(block.logit())
                    .append(",\"std\":").append(block.std())
                    .append(",\"mask\":").append(block.mask())
                    .append('}');
        }

        return json.append(']').toString();
    }

    public static Builder builder() {

        return new Builder();
    }

    /** Lays the blocks out one after another, in both the outputs and the action vector, in the order they are added. */
    public static final class Builder {

        private final List<Block> blocks = new ArrayList<>();
        private int logit;
        private int action;
        private int std;

        public Builder continuous(String name, int size) {

            return this.add(new Block(name, Kind.CONTINUOUS, size, this.action, this.logit, this.std, -1));
        }

        public Builder binary(String name, int size) {

            return this.add(new Block(name, Kind.BINARY, size, this.action, this.logit, -1, -1));
        }

        /** @param mask see {@link Block#mask()} */
        public Builder categorical(String name, int size, int mask) {

            return this.add(new Block(name, Kind.CATEGORICAL, size, this.action, this.logit, -1, mask));
        }

        private Builder add(Block block) {

            this.blocks.add(block);
            this.logit += block.size();
            this.action += block.actionWidth();

            if (block.kind() == Kind.CONTINUOUS) {

                this.std += block.size();
            }

            return this;
        }

        public Heads build() {

            return new Heads(this.blocks.toArray(new Block[0]), this.logit, this.action, this.std);
        }
    }
}
