package net.sievert.modularmobai.brain.schema;

import java.util.List;

import net.sievert.modularmobai.brain.nn.Heads;
import net.sievert.modularmobai.entity.agent.AgentMob;
import net.sievert.modularmobai.entity.agent.MobControls;

/**
 * A body that is a layout and nothing else: no mob, no encoder, no fights. The third one, and the proof that a body is
 * declared in one place.
 *
 * <p>The humanoid and the beast are both real fighters, and both were added before any of the machinery round them had to
 * go round more than one. This one was added by putting it in {@link Species#ALL} and writing nothing else at all, and what
 * that buys is checked rather than claimed: the parity check builds it a network and holds the game's forward pass against
 * PyTorch's on it, the build lists it among the bodies, the schema tool writes its layout, and the trainer builds an actor
 * of its width straight out of that file. None of those was told about it.
 *
 * <p>It is also the one body that is <b>unlike both of the others in shape</b>, which is what makes the check worth
 * running. It has no terrain grid and no echo, so the trainer's "ask for a block by name and cope with its absence" is
 * really exercised rather than merely written; and it has a categorical head masked by <b>a block of its own</b> rather
 * than by a hotbar, which is what proves the parity fixture reads a mask off the head table instead of knowing where the
 * humanoid keeps one.
 *
 * <h2>What a real third body would add</h2>
 *
 * <p>A mob in {@link #mobs()}, an encoder to fill its observation in, an {@code act} that writes the controls it has, and
 * a renderer in each loader's client if it is not to borrow the player model. Nothing else: that is the claim, and this is
 * the half of it that can be checked without modelling an animal.
 *
 * <p>Having no mob is a real state for a body to be in — it is what a body is before anyone models it — so it is not
 * papered over. Everything that would put one in the world refuses it by name: {@code ModEntities.training} because it
 * declares no mob an arena can fight in, and {@link #observe} and {@link #act} below because there is nothing to observe
 * or drive. A brain can still be trained for it, and would have nothing to drive.
 */
final class TestBody implements Species {

    // -----------------------------------------------------------------------------------------------------------
    // The layout. One file, because a body with no encoder has nothing to share constants with.
    // -----------------------------------------------------------------------------------------------------------

    /** Health, where it is, how fast, and whether it is on the ground: the least a fighter could be told. */
    static final int SELF_SIZE = 8;

    /** One value per choice, above zero while that choice is allowed. What the categorical head below is masked by. */
    static final int CHOICE_SIZE = 4;

    static final int SELF_OFFSET = 0;
    static final int ENEMY_OFFSET = SELF_OFFSET + SELF_SIZE;
    static final int CHOICE_OFFSET = ENEMY_OFFSET + ObservationSchema.ENEMY_SIZE;

    static final int OBS_DIM = CHOICE_OFFSET + CHOICE_SIZE;

    static final int MOVE_FORWARD = 0;
    static final int AIM_YAW = 1;
    static final int ATTACK = 2;

    /** The chosen one of {@link #CHOICE_SIZE}, as an index: a categorical head fills one action value, not its size. */
    static final int CHOICE = 3;

    static final int ACT_DIM = 4;

    static final String[] NAMES = { "moveForward", "aimYaw", "attack", "choice" };

    /**
     * Two numbers, a button and a choice of four. The choice is masked by this body's own block rather than by a hotbar,
     * which is the shape neither of the other two bodies has: the humanoid's mask is its hotbar and the beast has no
     * choice at all.
     */
    static final Heads HEADS = Heads.builder()
            .continuous("movement", AIM_YAW - MOVE_FORWARD + 1)
            .binary("buttons", 1)
            .categorical("choice", CHOICE_SIZE, CHOICE_OFFSET)
            .build();

    private final int schemaId;
    private final String json;

    TestBody() {

        this.json = Species.describe(this, List.of(
                Species.Block.of("self", SELF_OFFSET, SELF_SIZE),
                Species.Block.shaped("enemies", ENEMY_OFFSET, ObservationSchema.ENEMY_SIZE,
                        "slots", ObservationSchema.ENEMY_SLOTS, "stride", ObservationSchema.ENEMY_STRIDE),
                Species.Block.of("choice", CHOICE_OFFSET, CHOICE_SIZE)));

        this.schemaId = Species.super.schemaId();
    }

    @Override
    public String name() {

        return "test_body";
    }

    /** None. See the class comment: a body with no mob is what a body is before anyone models it. */
    @Override
    public List<Species.Mob> mobs() {

        return List.of();
    }

    @Override
    public boolean holdsItems() {

        return false;
    }

    @Override
    public int obsDim() {

        return OBS_DIM;
    }

    @Override
    public int actDim() {

        return ACT_DIM;
    }

    @Override
    public String[] actionNames() {

        return NAMES.clone();
    }

    @Override
    public Heads heads() {

        return HEADS;
    }

    /**
     * Refused by name. There is no mob of this body, so there is no agent of it either, and an encoder that quietly wrote
     * zeroes would let this be trained against nothing and look like it was working.
     */
    @Override
    public void observe(AgentMob agent, EnemySlots slots, float[] out, int base) {

        throw new UnsupportedOperationException("A " + this.name() + " has no mob and so no agent to observe: it is a layout "
                + "for the checks that walk every body. Give it a mob and an encoder to fight it");
    }

    /** Refused by name, for the same reason as {@link #observe}. */
    @Override
    public void act(float[] actions, int base, MobControls out) {

        throw new UnsupportedOperationException("A " + this.name() + " has no mob and so nothing to drive: it is a layout for "
                + "the checks that walk every body. Give it a mob and an act that writes the controls it has");
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

    static {

        Heads.Block[] blocks = HEADS.blocks();

        if (HEADS.actDim() != ACT_DIM || blocks.length != 3 || blocks[0].action() != MOVE_FORWARD
                || blocks[1].action() != ATTACK || blocks[2].action() != CHOICE) {

            throw new IllegalStateException("The test body's head table no longer lines up with its action layout");
        }
    }
}
