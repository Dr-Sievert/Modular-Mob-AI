package net.sievert.modularmobai.brain.schema;

import java.util.List;

import net.sievert.modularmobai.brain.nn.Heads;
import net.sievert.modularmobai.entity.agent.AgentMob;
import net.sievert.modularmobai.entity.agent.MobControls;

/**
 * A body with no hands: it moves, turns, jumps, sprints and bites.
 *
 * <p>The second body, and it exists to be as unlike the humanoid as a body can be while still being a fighter. It has no
 * hotbar block, no use buttons, no slot to choose and so no categorical head at all; its observation is 769 wide against
 * 792 and its action vector seven wide against eleven. Nothing about the machinery is special-cased for it, which is the
 * claim being made: a body brings a layout, an encoder and a descriptor, and everything else already works.
 *
 * <p>Its layout is {@link BeastSchema} and its encoder {@link BeastObservation}. See {@code docs/species.md}, which was
 * written from this one.
 */
final class Beast implements Species {

    private final int schemaId;
    private final String json;

    Beast() {

        this.json = Species.describe(this, List.of(
                Species.Block.of("self", BeastSchema.SELF_OFFSET, BeastSchema.SELF_SIZE),
                Species.Block.of("echo", BeastSchema.ECHO_OFFSET, BeastSchema.ECHO_SIZE),
                Species.Block.shaped("enemies", BeastSchema.ENEMY_OFFSET, ObservationSchema.ENEMY_SIZE,
                        "slots", ObservationSchema.ENEMY_SLOTS, "stride", ObservationSchema.ENEMY_STRIDE),
                Species.Block.shaped("terrain", BeastSchema.TERRAIN_OFFSET, ObservationSchema.TERRAIN_SIZE,
                        "x", ObservationSchema.TERRAIN_X, "y", ObservationSchema.TERRAIN_Y, "z", ObservationSchema.TERRAIN_Z),
                Species.Block.shaped("rays", BeastSchema.RAY_OFFSET, ObservationSchema.RAY_SIZE,
                        "rays", ObservationSchema.RAYS, "stride", ObservationSchema.RAY_STRIDE)));

        this.schemaId = Species.super.schemaId();
    }

    @Override
    public String name() {

        return "beast";
    }

    /**
     * One mob, the arenas' kind: nothing trains it yet, so it has no business being met in a survival world or saved into
     * one. It is the humanoid's box and the humanoid's renderer because nothing about the proof is its silhouette; giving it
     * a shape of its own is a change to these five numbers and a renderer, and to nothing else.
     */
    @Override
    public List<Species.Mob> mobs() {

        return List.of(Species.Mob.playerShaped("beast_agent", Species.Mob.Role.TRAINING));
    }

    /** No hands: what arms a fighter refuses it by name, rather than filling a hotbar it has no control to reach. */
    @Override
    public boolean holdsItems() {

        return false;
    }

    @Override
    public int obsDim() {

        return BeastSchema.OBS_DIM;
    }

    @Override
    public int actDim() {

        return BeastSchema.ACT_DIM;
    }

    @Override
    public String[] actionNames() {

        return BeastSchema.NAMES.clone();
    }

    @Override
    public Heads heads() {

        return BeastSchema.HEADS;
    }

    @Override
    public void observe(AgentMob agent, EnemySlots slots, float[] out, int base) {

        BeastObservation.write(agent, slots, out, base);
    }

    /**
     * Only the controls it has. The hotbar slot is not written at all rather than written as zero: it has no hands, and
     * leaving the field alone is how a body says a control is not its to press. The slot the entity holds stays whatever it
     * was, which for a beast is the empty slot it was born with.
     */
    @Override
    public void act(float[] actions, int base, MobControls out) {

        out.moveForward = actions[base + BeastSchema.MOVE_FORWARD];
        out.moveStrafe = actions[base + BeastSchema.MOVE_STRAFE];
        out.aimYaw = actions[base + BeastSchema.AIM_YAW];
        out.aimPitch = actions[base + BeastSchema.AIM_PITCH];

        out.jump = actions[base + BeastSchema.JUMP] >= PRESSED;
        out.sprint = actions[base + BeastSchema.SPRINT] >= PRESSED;
        out.attack = actions[base + BeastSchema.ATTACK] >= PRESSED;

        // Held at rest rather than left to whatever the last body to use this buffer asked for: nothing here can crouch or
        // use anything, and a control this species does not have must not be able to come on by accident.
        out.sneak = false;
        out.use = false;
        out.useOffhand = false;
    }

    /** Anything at or above this counts as the button being held, as it does for every body. */
    private static final float PRESSED = 0.5F;

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
