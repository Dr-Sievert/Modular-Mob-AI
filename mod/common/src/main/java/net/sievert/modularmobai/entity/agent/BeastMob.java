package net.sievert.modularmobai.entity.agent;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.Level;
import net.sievert.modularmobai.brain.schema.Species;

/**
 * A body with no hands, and the whole of what a second body costs on the entity side.
 *
 * <p>One method. Everything else an agent is — the controls, the echo of what the body actually did, the driver, the memory,
 * the arena plumbing — is the same for every body and is inherited unchanged. What differs is the schema, and the schema is
 * what {@link #species()} names: see {@link net.sievert.modularmobai.brain.schema.Beast}.
 *
 * <p>It is deliberately the humanoid's model, hitbox and renderer, with no texture and no loadout of its own, because it is
 * a proof rather than a mob anybody is meant to meet. It looks exactly like an agent standing with empty hands, and it
 * cannot pick anything up: nothing ever puts anything in its hotbar, and its schema has no control that could select a slot
 * or use one. To make a real mob of it, give it its own dimensions and a renderer, and nothing here changes.
 */
public class BeastMob extends AgentMob {

    public BeastMob(EntityType<? extends PathfinderMob> type, Level level) {

        super(type, level);
    }

    @Override
    public Species species() {

        return Species.BEAST;
    }
}
