package net.sievert.modularmobai.brain.schema;

import java.util.List;

import net.sievert.modularmobai.brain.nn.Heads;
import net.sievert.modularmobai.entity.agent.AgentMob;
import net.sievert.modularmobai.entity.agent.MobControls;

/**
 * The player-shaped body: two hands, a hotbar of nine, and the set of controls a player has at a keyboard and mouse. Every
 * network trained so far drives this one.
 *
 * <p>Its layout is {@link ObservationSchema} and {@link ActionSchema}, which is where every offset and every field of this
 * body is written down, and its observation is filled in by {@link AgentObservation}. This class is only the descriptor
 * those three add up to: the width, the names, the heads, and the blocks the training side is told about. A second body
 * brings its own three and its own descriptor; nothing here is shared by being global, only by being called.
 */
final class Humanoid implements Species {

    /** Set once, since it is a checksum of a string that cannot change while the game runs, and it is asked for per load. */
    private final int schemaId;
    private final String json;

    Humanoid() {

        this.json = Species.describe(this, List.of(
                Species.Block.of("self", ObservationSchema.SELF_OFFSET, ObservationSchema.SELF_SIZE),
                Species.Block.of("hotbar", ObservationSchema.HOTBAR_OFFSET, ObservationSchema.HOTBAR_SIZE),
                Species.Block.of("echo", ObservationSchema.ECHO_OFFSET, ObservationSchema.ECHO_SIZE),
                Species.Block.shaped("enemies", ObservationSchema.ENEMY_OFFSET, ObservationSchema.ENEMY_SIZE,
                        "slots", ObservationSchema.ENEMY_SLOTS, "stride", ObservationSchema.ENEMY_STRIDE),
                Species.Block.shaped("terrain", ObservationSchema.TERRAIN_OFFSET, ObservationSchema.TERRAIN_SIZE,
                        "x", ObservationSchema.TERRAIN_X, "y", ObservationSchema.TERRAIN_Y, "z", ObservationSchema.TERRAIN_Z)));

        this.schemaId = Species.super.schemaId();
    }

    @Override
    public String name() {

        return "humanoid";
    }

    @Override
    public int obsDim() {

        return ObservationSchema.OBS_DIM;
    }

    @Override
    public int actDim() {

        return ActionSchema.ACT_DIM;
    }

    @Override
    public String[] actionNames() {

        return ActionSchema.NAMES.clone();
    }

    @Override
    public Heads heads() {

        return ActionSchema.HEADS;
    }

    @Override
    public void observe(AgentMob agent, EnemySlots slots, float[] out, int base) {

        AgentObservation.write(agent, slots, out, base);
    }

    @Override
    public void act(float[] actions, int base, MobControls out) {

        ActionSchema.apply(actions, base, out);
    }

    @Override
    public String describeJson() {

        return this.json;
    }

    @Override
    public int schemaId() {

        return this.schemaId;
    }

    @Override
    public String toString() {

        return this.name();
    }
}
