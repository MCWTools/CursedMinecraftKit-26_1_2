package khoa.cmk.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Constant;

import net.minecraft.world.item.enchantment.ItemEnchantments;

/**
 * The real level cap isn't a byte anywhere - ItemEnchantments stores levels
 * as a plain int - it's 255 hardcoded in a few places across this class and
 * its inner Mutable class:
 *
 *  - constructor validation: "level >= 0 && level <= 255"
 *  - LEVEL_CODEC = Codec.intRange(1, 255) (used on save/network sync)
 *  - Mutable#set / Mutable#upgrade: Math.min(level, 255)  (see
 *    ItemEnchantmentsMutableLevelCapMixin - separate mixin, separate class)
 *
 * Mutable#set/upgrade are what BOTH EnchantCommand (via ItemStack#enchant)
 * and AnvilMenu (book/item combining) ultimately call, so raising the cap
 * there covers /enchant and anvil combining at once. The constructor check
 * and the codec's intRange (both in THIS class) are bumped too so an item
 * saved/synced with a level above 255 doesn't get rejected or silently
 * clamped back down on the way through.
 *
 * Raised to 65536 (2^16) rather than left unbounded - keeps well inside int
 * range with headroom for level*multiplier math elsewhere in the game
 * without touching those call sites individually.
 */
@Mixin(ItemEnchantments.class)
public class ItemEnchantmentsLevelCapMixin {

	@ModifyConstant(method = "<init>", constant = @Constant(intValue = 255))
	private int cmk$ctorCap(int original) {
		return 65536;
	}

	@ModifyConstant(method = "<clinit>", constant = @Constant(intValue = 255))
	private static int cmk$codecCap(int original) {
		return 65536;
	}
}
