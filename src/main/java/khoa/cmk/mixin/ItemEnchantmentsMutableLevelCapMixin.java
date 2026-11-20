package khoa.cmk.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Constant;

/**
 * ItemEnchantments.Mutable#set and #upgrade both do
 * "Math.min(level, 255)" - this is the actual ceiling both
 * ItemStack#enchant (used by EnchantCommand / /enchant) and AnvilMenu's
 * combine logic run through, so bumping it here raises the cap for both
 * at once. See ItemEnchantmentsLevelCapMixin for the matching constructor
 * / codec cap in the outer class.
 */
@Mixin(targets = "net.minecraft.world.item.enchantment.ItemEnchantments$Mutable")
public class ItemEnchantmentsMutableLevelCapMixin {

	@ModifyConstant(method = "set", constant = @Constant(intValue = 255))
	private int cmk$setCap(int original) {
		return 65536;
	}

	@ModifyConstant(method = "upgrade", constant = @Constant(intValue = 255))
	private int cmk$upgradeCap(int original) {
		return 65536;
	}
}
