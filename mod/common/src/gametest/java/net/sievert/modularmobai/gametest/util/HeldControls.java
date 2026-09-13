package net.sievert.modularmobai.gametest.util;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.sievert.modularmobai.brain.Brain;
import net.sievert.modularmobai.brain.BrainStep;
import net.sievert.modularmobai.brain.schema.ActionSchema;
import net.sievert.modularmobai.brain.schema.Species;
import net.sievert.modularmobai.entity.agent.AgentMob;
import net.sievert.modularmobai.entity.agent.MobControls;

/**
 * A brain with nothing of its own to say: it hands each agent back whatever the test last pressed on its controls. The
 * presses still go out through the action vector and come back in through {@link ActionSchema}, as a network's would, so a
 * test reaches each of the body's rules the way a brain reaches it.
 *
 * <p>It lives here rather than inside one suite because two of them drive their agents with it: the mechanics suite, where
 * every test presses buttons by hand, and the play suite, where a scripted agent is wanted that does not fight.
 */
public final class HeldControls implements Brain {

    private static final HeldControls INSTANCE = new HeldControls();

    /** Every agent this drives, by entity id, which is what a step names its rows by. */
    private final Int2ObjectMap<AgentMob> agents = new Int2ObjectOpenHashMap<>();

    private HeldControls() {}

    public static void drive(AgentMob agent) {

        INSTANCE.agents.put(agent.getId(), agent);
        agent.brain().use(INSTANCE);
    }

    /** Everything it reads and writes is the humanoid's action vector, so that is the body it drives. */
    @Override
    public Species species() {

        return Species.HUMANOID;
    }

    @Override
    public void act(BrainStep step) {

        for (int index = 0; index < step.count; index++) {

            AgentMob agent = this.agents.get(step.agentIds[index]);

            if (agent == null) {

                continue;
            }

            MobControls pressed = agent.controls();
            int at = index * ActionSchema.ACT_DIM;

            step.actions[at + ActionSchema.MOVE_FORWARD] = pressed.moveForward;
            step.actions[at + ActionSchema.MOVE_STRAFE] = pressed.moveStrafe;
            step.actions[at + ActionSchema.AIM_YAW] = pressed.aimYaw;
            step.actions[at + ActionSchema.AIM_PITCH] = pressed.aimPitch;
            step.actions[at + ActionSchema.JUMP] = pressed.jump ? 1.0F : 0.0F;
            step.actions[at + ActionSchema.SPRINT] = pressed.sprint ? 1.0F : 0.0F;
            step.actions[at + ActionSchema.SNEAK] = pressed.sneak ? 1.0F : 0.0F;
            step.actions[at + ActionSchema.ATTACK] = pressed.attack ? 1.0F : 0.0F;
            step.actions[at + ActionSchema.USE] = pressed.use ? 1.0F : 0.0F;
            step.actions[at + ActionSchema.USE_OFFHAND] = pressed.useOffhand ? 1.0F : 0.0F;
            step.actions[at + ActionSchema.SELECTED_SLOT] = pressed.selectedSlot;
        }
    }
}
