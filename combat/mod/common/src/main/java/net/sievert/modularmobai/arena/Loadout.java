package net.sievert.modularmobai.arena;

import java.util.List;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.sievert.modularmobai.entity.agent.AgentMob;
import net.sievert.modularmobai.entity.agent.MobControls;

/**
 * What a fighter carries into a fight: a hotbar's worth of items, something in the off hand, and maybe armour.
 *
 * <p>An agent gets all of it, slot for slot, the way a player would lay out a kit. A mob has no hotbar, only its hands,
 * so it takes the first item into its main hand and the off hand as it is. That is all a vanilla fighter needs: a
 * skeleton's bow and a pillager's crossbow never run out of arrows, and a mob never raises a shield. Armour goes on
 * whoever it is given to, each piece in the slot it is made for, and a loadout without any leaves alone whatever the
 * fighter already wears.
 *
 * <p>Equipping copies every stack, so one loadout can arm any number of fighters without any of them using up another's
 * arrows.
 *
 * @param name    what to call it in a log or a replay
 * @param hotbar  the hotbar from slot zero, at most {@link MobControls#HOTBAR_SIZE} stacks; slots past the end are empty
 * @param offhand what goes in the off hand, or an empty stack
 * @param armour  what is worn, a piece for each slot at most, or nothing
 */
public record Loadout(String name, List<ItemStack> hotbar, ItemStack offhand, List<ItemStack> armour) {

    /** What every agent has fought with so far. */
    public static final Loadout SWORD = of("sword", ItemStack.EMPTY, new ItemStack(Items.IRON_SWORD));

    public static final Loadout SWORD_AND_SHIELD = of("sword_and_shield", new ItemStack(Items.SHIELD), new ItemStack(Items.IRON_SWORD));

    public static final Loadout AXE_AND_SHIELD = of("axe_and_shield", new ItemStack(Items.SHIELD), new ItemStack(Items.IRON_AXE));

    public static final Loadout BOW = of("bow", ItemStack.EMPTY, new ItemStack(Items.BOW), new ItemStack(Items.ARROW, 64));

    public static final Loadout CROSSBOW = of("crossbow", ItemStack.EMPTY, new ItemStack(Items.CROSSBOW), new ItemStack(Items.ARROW, 64));

    public Loadout {

        if (hotbar.size() > MobControls.HOTBAR_SIZE) {

            throw new IllegalArgumentException("A hotbar holds " + MobControls.HOTBAR_SIZE + " stacks, not " + hotbar.size());
        }

        hotbar = List.copyOf(hotbar);
        armour = List.copyOf(armour);
    }

    public static Loadout of(String name, ItemStack offhand, ItemStack... hotbar) {

        return new Loadout(name, List.of(hotbar), offhand, List.of());
    }

    /** The same, under another name, with armour on as well. */
    public Loadout wearing(String name, ItemStack... pieces) {

        return new Loadout(name, this.hotbar, this.offhand, List.of(pieces));
    }

    /**
     * Arms a fighter with this, replacing whatever it held before.
     *
     * @throws IllegalArgumentException naming the body, for an agent of a body that holds nothing. A mob's inventory is the
     *                                 game's and takes whatever is put in it, so a body with no hands would be handed a
     *                                 sword it has no control that could swing, lose every fight it was sent into, and say
     *                                 nothing about why. This is the one call that arms any fighter, so it is the one place
     *                                 that has to ask. See {@code Species#holdsItems} and {@code docs/species.md}.
     */
    public void equip(LivingEntity fighter) {

        if (fighter instanceof AgentMob agent && !agent.species().holdsItems()) {

            throw new IllegalArgumentException("A " + agent.species().name() + " cannot be armed with the " + this.name
                    + " loadout: it has no hands, no hotbar to see and no control that could use what is in one. A body that "
                    + "is to fight with weapons needs a schema that can hold them");
        }

        if (fighter instanceof AgentMob agent) {

            for (int slot = 0; slot < MobControls.HOTBAR_SIZE; slot++) {

                agent.setHotbarItem(slot, slot < this.hotbar.size() ? this.hotbar.get(slot).copy() : ItemStack.EMPTY);
            }
        }

        else {

            fighter.setItemSlot(EquipmentSlot.MAINHAND, this.hotbar.isEmpty() ? ItemStack.EMPTY : this.hotbar.get(0).copy());
        }

        fighter.setItemSlot(EquipmentSlot.OFFHAND, this.offhand.copy());

        for (ItemStack piece : this.armour) {

            fighter.setItemSlot(fighter.getEquipmentSlotForItem(piece), piece.copy());
        }
    }
}
