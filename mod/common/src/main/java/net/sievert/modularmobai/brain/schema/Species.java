package net.sievert.modularmobai.brain.schema;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.CRC32;

import net.sievert.modularmobai.brain.nn.Heads;
import net.sievert.modularmobai.entity.agent.AgentMob;
import net.sievert.modularmobai.entity.agent.MobControls;

/**
 * One body's schema: what an agent of that body sees, what it can be asked to do, and how the network's outputs turn into
 * that. A brain is trained for one species and drives only that species.
 *
 * <p>This used to be two classes of compile-time constants, on the assumption that there would only ever be one body.
 * There is more than one now, and a second body differs in more than its numbers: a body with no hands has no hotbar
 * block to see, no slot to choose and no use buttons to press, and no amount of masking makes those inputs mean anything.
 * So a body brings its whole schema with it, including the code that fills the observation in, and nothing outside this
 * package knows how wide an observation is without asking a species.
 *
 * <p>What is shared is shared by composition rather than by inheritance: the enemy slots, the terrain grid and the echo of
 * what the body actually did are written by helpers any species can use, and a species picks the ones its body has. See
 * {@code docs/species.md} for the steps.
 *
 * <h2>The identity of a schema</h2>
 *
 * <p>{@link #describeJson()} is the one written copy of a layout: the training side builds its network from it and never
 * writes any of it down itself, because a layout written in two places drifts the moment one is edited, and the failure is
 * silent — the network reads health out of whichever slot used to hold it and plays badly for reasons nobody can find.
 * {@link #schemaId()} is a checksum of those very bytes, stamped into every weight file and rollout shard, and it includes
 * the species name, so weights trained for one body are refused by another even where the two happen to be the same width.
 */
public interface Species {

    /** The player-shaped agent, which is what every trained network drives. */
    Species HUMANOID = new Humanoid();

    /** A body with no hands: the second one, and the proof that a body is a thing this can have more than one of. */
    Species BEAST = new Beast();

    List<Species> ALL = List.of(HUMANOID, BEAST);

    /** As it appears in a schema, a weight file and a log. Lower case, no spaces, and never changed once published. */
    String name();

    /** How many floats one agent's observation takes. */
    int obsDim();

    /** How many values one agent's action takes: one per control the body has. */
    int actDim();

    /**
     * The controls in index order, so that the far side can name them without either half writing the list down twice.
     * Exactly {@link #actDim()} of them.
     */
    String[] actionNames();

    /** How the network's outputs become those values. */
    Heads heads();

    /**
     * Fills one agent's row of the observation. Everything the body has, in the order this species' layout says, and
     * nothing left over: the row is cleared first, so a field this species does not have reads as zero rather than as
     * whatever the last agent in that row left behind.
     */
    void observe(AgentMob agent, EnemySlots slots, float[] out, int base);

    /** Hands one agent's chosen action to its body. Only the controls this species has are written. */
    void act(float[] actions, int base, MobControls out);

    /** The layout as JSON. Built with {@link #describe}, so every species describes itself in the same shape. */
    String describeJson();

    /**
     * A CRC32 of {@link #describeJson()}. Weights trained against one layout are refused by a game running any other, so a
     * rearranged block, or a network handed to the wrong body, fails the start instead of quietly feeding a network the
     * wrong numbers.
     */
    default int schemaId() {

        CRC32 crc = new CRC32();
        crc.update(this.describeJson().getBytes(StandardCharsets.UTF_8));
        return (int) crc.getValue();
    }

    /** The species of that name, for a weight file or a command that names one. */
    static Species byName(String name) {

        for (Species species : ALL) {

            if (species.name().equals(name)) {

                return species;
            }
        }

        throw new IllegalArgumentException("No such species as " + name + "; there is "
                + String.join(", ", ALL.stream().map(Species::name).toList()));
    }

    /** The species whose layout has that id, or null: what says whether a weight file can drive anything here at all. */
    static Species bySchemaId(int schemaId) {

        for (Species species : ALL) {

            if (species.schemaId() == schemaId) {

                return species;
            }
        }

        return null;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Describing a layout
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * One named run of the observation, and whatever else the training side needs to know to make sense of it: an enemy
     * block says how many slots of what stride, a terrain block says its three dimensions. The facts are written in the
     * order they are added, because the schema id is a checksum of the bytes and has to be the same every time.
     */
    record Block(String name, int offset, int size, Map<String, Integer> facts) {

        public static Block of(String name, int offset, int size) {

            return new Block(name, offset, size, Map.of());
        }

        /** {@code shaped("terrain", 229, 405, "x", 9, "y", 5, "z", 9)}: pairs of a name and a number, in that order. */
        public static Block shaped(String name, int offset, int size, Object... facts) {

            if (facts.length % 2 != 0) {

                throw new IllegalArgumentException("A block's facts are pairs of a name and a number");
            }

            Map<String, Integer> ordered = new LinkedHashMap<>();

            for (int index = 0; index < facts.length; index += 2) {

                ordered.put((String) facts[index], (Integer) facts[index + 1]);
            }

            return new Block(name, offset, size, ordered);
        }
    }

    /**
     * The JSON every species describes itself with, so that the training side has one parser rather than one per body.
     *
     * <pre>
     *   {"species":"humanoid","obsDim":634,"actDim":11,
     *    "blocks":[{"name":"self","offset":0,"size":20}, ...],
     *    "actions":["moveForward", ...],"logits":19,"heads":[...]}
     * </pre>
     *
     * <p>The blocks are a list rather than an object keyed by name, because which blocks a body has is the thing that
     * varies: a body with no hands has no hotbar, and a reader that asks for one by name would have to know in advance
     * which bodies have which. They are in offset order and have to cover the observation exactly, which is checked here
     * rather than left to the far side to notice.
     */
    static String describe(Species species, List<Block> blocks) {

        int covered = 0;

        for (Block block : blocks) {

            if (block.offset() != covered) {

                throw new IllegalStateException(String.format(Locale.ROOT,
                        "%s's block %s starts at %d, but the blocks before it end at %d: they have to be in order and "
                                + "leave no gap", species.name(), block.name(), block.offset(), covered));
            }

            covered += block.size();
        }

        if (covered != species.obsDim()) {

            throw new IllegalStateException(String.format(Locale.ROOT,
                    "%s's blocks cover %d values but its observation is %d wide", species.name(), covered, species.obsDim()));
        }

        if (species.actionNames().length != species.actDim() || species.heads().actDim() != species.actDim()) {

            throw new IllegalStateException(String.format(Locale.ROOT,
                    "%s names %d controls and its heads fill %d, but its action vector is %d wide", species.name(),
                    species.actionNames().length, species.heads().actDim(), species.actDim()));
        }

        StringBuilder json = new StringBuilder(768);

        json.append("{\"species\":\"").append(species.name()).append('"');
        json.append(",\"obsDim\":").append(species.obsDim());
        json.append(",\"actDim\":").append(species.actDim());
        json.append(",\"blocks\":[");

        for (int index = 0; index < blocks.size(); index++) {

            Block block = blocks.get(index);

            json.append(index == 0 ? "" : ",");
            json.append("{\"name\":\"").append(block.name()).append('"');
            json.append(",\"offset\":").append(block.offset());
            json.append(",\"size\":").append(block.size());

            block.facts().forEach((fact, value) -> json.append(",\"").append(fact).append("\":").append(value));

            json.append('}');
        }

        json.append("],\"actions\":[");

        Heads heads = species.heads();
        String[] names = species.actionNames();

        for (int index = 0; index < names.length; index++) {

            json.append(index == 0 ? "\"" : ",\"").append(names[index]).append('"');
        }

        json.append("],\"logits\":").append(heads.logitDim());
        json.append(",\"heads\":").append(heads.describeJson());
        json.append('}');

        return json.toString();
    }
}
