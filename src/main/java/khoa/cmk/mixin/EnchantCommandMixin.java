package khoa.cmk.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.core.Holder;
import net.minecraft.server.commands.EnchantCommand;

import java.util.Collection;

/**
 * Vanilla's /enchant command rejects any level above
 * Enchantment#getMaxLevel() (see EnchantCommand#enchant,
 * "level > enchantment.getMaxLevel()"). That check is the ONLY thing
 * capping the command - ItemStack#enchant / EnchantmentInstance don't
 * clamp anything further on their own.
 *
 * EnchantCommand#enchant also gates every apply behind
 * "enchantment.canEnchant(item) && EnchantmentHelper.isEnchantmentCompatible(...)"
 * - the first rejects enchants that don't belong on that item category
 * (e.g. Sharpness on a pickaxe), the second rejects combos vanilla
 * considers mutually exclusive (e.g. Breach + Density, Sharpness + Smite).
 * Only the second is redirected here - see the comment on canEnchant()
 * below for why the first is deliberately left alone.
 *
 * Redirecting the getMaxLevel() call made from inside EnchantCommand to
 * always return 255 (the actual hard ceiling - enchantment level is
 * stored as a byte in ItemEnchantments/EnchantmentInstance) removes the
 * per-enchantment limit without touching any other caller of
 * getMaxLevel() elsewhere in the game (enchanting table, loot tables,
 * etc. are untouched since the redirect is scoped to this one class).
 */
@Mixin(EnchantCommand.class)
public class EnchantCommandMixin {

	@Redirect(
		method = "enchant",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/item/enchantment/Enchantment;getMaxLevel()I"
		)
	)
	private static int cmk$noEnchantLevelLimit(Enchantment enchantment) {
		return 255;
	}

	// canEnchant(item) is intentionally NOT redirected here anymore.
	// fabric-item-api-v1 also redirects this exact call (to fire its
	// AllowEnchantingEvent), and Mixin only allows one @Redirect per
	// bytecode call site - whichever mod's mixin loses that fight fails
	// its own injection check, which crashes EnchantCommand's class
	// transform entirely and takes datapack/world loading down with it
	// (see the "Redirector ... failed injection check" + "Mixin
	// transformation of EnchantCommand failed" crash). Fabric API's
	// AllowEnchantingEvent defaults to allowed when nothing listens to
	// it, so leaving canEnchant() alone barely changes behavior in
	// practice, and isEnchantmentCompatible() below (a separate call,
	// no other mod touches it) still covers "let incompatible enchants
	// coexist" on its own.

	@Redirect(
		method = "enchant",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/item/enchantment/EnchantmentHelper;isEnchantmentCompatible(Ljava/util/Collection;Lnet/minecraft/core/Holder;)Z"
		)
	)
	private static boolean cmk$noIncompatibilityCheck(Collection<Holder<Enchantment>> enchants, Holder<Enchantment> target) {
		return true;
	}
}
