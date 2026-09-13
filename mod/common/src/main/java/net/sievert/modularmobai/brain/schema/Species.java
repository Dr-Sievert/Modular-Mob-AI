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
 * <h2>The one place a body is declared</h2>
 *
 * <p>{@link #ALL} is the whole register of bodies, and a body is declared by adding it there and nowhere else. What each
 * declaration carries is everything the rest of the mod would otherwise have to know by heart: the layout, the encoder, the
 * controls, {@link #holdsItems()} and {@link #mobs()}. The loaders register the mobs {@link #mobs()} names, the parity
 * check goes round {@link #ALL}, the build asks this for the bodies there are, and what arms a fighter asks
 * {@link #holdsItems()} rather than assuming a hotbar. A body that lacks what something needs is refused by name, which is
 * the whole of why these are declared rather than inferred: a body with no hands cannot be handed a sword by accident, and
 * a body with no mob cannot be spawned into an arena by accident, because both stop with a message saying which body and
 * what it has not got.
 *
 * <p>Nothing here may touch a class of the game's. The build reads a layout by running the schema tool as a plain Java
 * program with no Minecraft on its class path, so a mob is declared by its id and its dimensions and turned into an
 * {@code EntityType} by {@code ModEntities}, which is the first place allowed to mention one.
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

    /**
     * Every body there is, and the one place a body is declared. Everything that goes round the bodies goes round this:
     * the loaders' registrations, the parity check, the build's list, and every message that says what bodies there are.
     */
    List<Species> ALL = checked(List.of(HUMANOID, BEAST));

    /** As it appears in a schema, a weight file and a log. Lower case, no spaces, and never changed once published. */
    String name();

    /**
     * Whether this body can hold anything: a hotbar to see, a slot to choose, hands to swing what is in them.
     *
     * <p>Asked rather than assumed by everything that arms a fighter, so that a body with no hands is refused a loadout by
     * name instead of being handed a sword it has no control that could swing. A mob's inventory is the game's and takes
     * whatever is put in it whatever the body is, which is exactly why this cannot be read off the entity: the sword would
     * go in, the fight would be lost, and nothing would say why.
     */
    boolean holdsItems();

    /**
     * The mobs the game registers for this body, in the order they are registered. Two for the humanoid — the one a player
     * meets and the one the arenas fight in — one for the beast, and none at all for a body that is a layout and nothing
     * else.
     *
     * <p>Declared here rather than in the loaders because a mob per loader per body is three edits that can each be
     * forgotten separately, and forgetting one leaves a body that trains on Fabric and cannot be spawned on NeoForge. See
     * {@code ModEntities}, which is what turns these into entity types.
     */
    List<Mob> mobs();

    /**
     * One mob of one body: the path of its id, what the world is to do with it, and the box it stands in.
     *
     * <p>No class of the game's, on purpose: see the note on {@link Species} about the schema tool's class path.
     *
     * @param path      the path of the id, {@code agent_mob}, which is also what its lang key and its egg are named after
     * @param role      whether a player meets this one or an arena fights in it
     * @param width     the bounding box, in blocks
     * @param height    the bounding box, in blocks
     * @param eyeHeight where this body looks from, which is where its aim ray starts and what its enemy slots measure to
     */
    record Mob(String path, Role role, float width, float height, float eyeHeight) {

        /**
         * What the world is to do with a mob. The two differ in nothing a network can see: same body, same controls, same
         * observation, so a network trained against one drives the other.
         */
        public enum Role {

            /** A mob a player meets: saved with the world, summonable, and given a spawn egg. */
            WORLD,

            /**
             * What the arenas spawn. Never saved, because a suite of fifty thousand arenas would otherwise serialise every
             * agent on every world save for no reason, and never summonable, because it has no business in a survival world.
             */
            TRAINING
        }

        /** A player's box and eye height, which is what both of the humanoid's use and what a proof of a second body borrows. */
        public static Mob playerShaped(String path, Role role) {

            return new Mob(path, role, 0.6F, 1.8F, 1.62F);
        }
    }

    /** This body's mob of that role, or null where it has none: what says whether it can be met, or fought in an arena. */
    default Mob mob(Mob.Role role) {

        for (Mob mob : this.mobs()) {

            if (mob.role() == role) {

                return mob;
            }
        }

        return null;
    }

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

    /**
     * Which body this process's agents are: the one a training run trains, an evaluation evaluates and a league fields.
     *
     * <p>The build passes the answer it wrote the run's {@code schema.json} for ({@code -Pspecies}) on to the game as
     * {@code modular_mob_ai.species}, so that the two halves of a run cannot be about different bodies. A process started
     * with neither gets the humanoid, which is what the arenas have always fought in.
     */
    static Species trained() {

        String named = System.getProperty("modular_mob_ai.species", "").trim();

        return named.isEmpty() ? HUMANOID : byName(named);
    }

    /**
     * The register, with the mistakes a declaration can make caught as it loads rather than whenever the thing it broke is
     * next used. Two bodies sharing an id would have one silently overwrite the other's registration, and two sharing a
     * schema id would each load the other's weights, which is the one failure that does not look like one.
     */
    static List<Species> checked(List<Species> all) {

        Map<String, String> paths = new LinkedHashMap<>();
        Map<Integer, String> ids = new LinkedHashMap<>();

        for (Species species : all) {

            String clash = ids.put(species.schemaId(), species.name());

            if (clash != null) {

                throw new IllegalStateException(String.format(Locale.ROOT, "%s and %s both have schema %08x, so either "
                        + "one's weights would drive the other; a schema id carries the species name, so two bodies can "
                        + "only collide by being declared twice", clash, species.name(), species.schemaId()));
            }

            for (Mob mob : species.mobs()) {

                String owner = paths.put(mob.path(), species.name());

                if (owner != null) {

                    throw new IllegalStateException("The mob " + mob.path() + " is declared by both " + owner + " and "
                            + species.name() + ", and one id is one mob");
                }
            }

            if (species.mobs().stream().filter(mob -> mob.role() == Mob.Role.WORLD).count() > 1
                    || species.mobs().stream().filter(mob -> mob.role() == Mob.Role.TRAINING).count() > 1) {

                throw new IllegalStateException(species.name() + " declares two mobs of one role, and everything that asks "
                        + "for a body's mob asks for one of each");
            }
        }

        return all;
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
     *   {"species":"humanoid","obsDim":792,"actDim":11,
     *    "blocks":[{"name":"self","offset":0,"size":24}, ...],
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
