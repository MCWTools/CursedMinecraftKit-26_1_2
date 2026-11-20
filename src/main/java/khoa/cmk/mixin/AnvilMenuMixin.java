package khoa.cmk.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.item.enchantment.Enchantment;

/**
 * AnvilMenu's combine logic has its own separate cap, independent of
 * ItemEnchantments.Mutable's clamp (see ItemEnchantmentsMutableLevelCapMixin):
 * "if (level > enchantment.getMaxLevel()) level = enchantment.getMaxLevel();"
 * right before the result gets written via Mutable#set. Without this,
 * combining two enchanted books in an anvil would still get silently capped
 * back down to vanilla's per-enchantment max (e.g. 5 for Sharpness) even
 * though Mutable#set itself now allows up to 65536.
 *
 * Redirected to always return 65536 - same ceiling as the Mutable-class
 * cap - scoped to calls made from inside AnvilMenu only, so the
 * enchanting table and /enchant (which has its own redirect in
 * EnchantCommandMixin) are untouched by this one.
 */
@Mixin(AnvilMenu.class)
public class AnvilMenuMixin {

	@Redirect(
		method = "createResult",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/item/enchantment/Enchantment;getMaxLevel()I"
		)
	)
	private int cmk$noAnvilLevelCap(Enchantment enchantment) {
		return 65536;
	}
}
