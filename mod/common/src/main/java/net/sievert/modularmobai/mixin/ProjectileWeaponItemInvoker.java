package net.sievert.modularmobai.mixin;

import java.util.List;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;

/**
 * The two halves of firing a bow or a crossbow that the items keep to themselves: taking the ammunition, and letting it
 * fly. Both already work for any shooter; only the calls that start them, a bow's release and a crossbow's use, are
 * written for a player. Going through the item's own code keeps the power, the spread, multishot, piercing, Infinity and
 * whatever else an enchantment or another mod adds exactly as a player gets them.
 */
@Mixin(ProjectileWeaponItem.class)
public interface ProjectileWeaponItemInvoker {

    @Invoker("shoot")
    void modular_mob_ai$shoot(ServerLevel level, LivingEntity shooter, InteractionHand hand, ItemStack weapon,
            List<ItemStack> projectiles, float velocity, float inaccuracy, boolean critical, @Nullable LivingEntity target);

    @Invoker("draw")
    static List<ItemStack> modular_mob_ai$draw(ItemStack weapon, ItemStack ammunition, LivingEntity shooter) {

        throw new AssertionError("Replaced by the mixin");
    }
}
