package net.sievert.modularmobai.entity.agent;

import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.BlockHitResult;

/**
 * An agent's use of an item on a block. Vanilla's context reads which way to face, whether the use is a sneaking one and
 * where the user is looking off the player it was given; there is none here, so everything it would have read off the
 * player is read off the agent instead.
 *
 * <p>A block item does not place through the context it is used with but through a {@link BlockPlaceContext} it makes
 * from it, and that copy would only know a player. {@link #placing()} is the copy made from the agent, which the block
 * item is handed instead (see BlockItemMixin). Without it every block the agent put down faced north, and a torch, a
 * button or a lantern, which ask where the player is looking, threw.
 */
public final class AgentUseOnContext extends UseOnContext {

    private final AgentMob agent;

    AgentUseOnContext(AgentMob agent, InteractionHand hand, ItemStack stack, BlockHitResult hit) {

        super(agent.level(), null, hand, stack, hit);
        this.agent = agent;
    }

    public AgentMob agent() {

        return this.agent;
    }

    @Override
    public Direction getHorizontalDirection() {

        return this.agent.getDirection();
    }

    @Override
    public float getRotation() {

        return this.agent.getYRot();
    }

    @Override
    public boolean isSecondaryUseActive() {

        return this.agent.isShiftKeyDown();
    }

    /** The same use, as the placing of a block. */
    public BlockPlaceContext placing() {

        return new Placing(this.agent, this.getHand(), this.getItemInHand(), this.getHitResult());
    }

    private static final class Placing extends BlockPlaceContext {

        private final AgentMob agent;

        private Placing(AgentMob agent, InteractionHand hand, ItemStack stack, BlockHitResult hit) {

            super(agent.level(), null, hand, stack, hit);
            this.agent = agent;
        }

        @Override
        public Direction getHorizontalDirection() {

            return this.agent.getDirection();
        }

        @Override
        public float getRotation() {

            return this.agent.getYRot();
        }

        @Override
        public boolean isSecondaryUseActive() {

            return this.agent.isShiftKeyDown();
        }

        @Override
        public Direction getNearestLookingDirection() {

            return Direction.orderedByNearest(this.agent)[0];
        }

        @Override
        public Direction getNearestLookingVerticalDirection() {

            return Direction.getFacingAxis(this.agent, Direction.Axis.Y);
        }

        /**
         * Vanilla's, with the agent where the player was: the directions in order of how nearly the agent looks along
         * them, except that placing against a block rather than into it puts that block's side first.
         */
        @Override
        public Direction[] getNearestLookingDirections() {

            Direction[] directions = Direction.orderedByNearest(this.agent);

            if (this.replacingClickedOnBlock()) {

                return directions;
            }

            Direction against = this.getClickedFace().getOpposite();
            int index = 0;

            while (index < directions.length && directions[index] != against) {

                index++;
            }

            if (index > 0) {

                System.arraycopy(directions, 0, directions, 1, index);
                directions[0] = against;
            }

            return directions;
        }
    }
}
