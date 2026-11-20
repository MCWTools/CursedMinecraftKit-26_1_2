package khoa.cmk.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.mojang.serialization.DataResult;

import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Unit;
import net.minecraft.world.item.ItemStack;

/**
 * ItemStack#validateComponents rejects any stack that has both MAX_DAMAGE
 * and a MAX_STACK_SIZE > 1 ("Item cannot be both damageable and
 * stackable"). This runs both server-side (validatedStreamCodec, used by
 * ServerboundSetCreativeModeSlotPacket among others) and on save/load, so a
 * cursed item left in this state throws DecoderException the moment the
 * client sends ANY packet touching that slot - including a plain
 * pickup/drag in the creative inventory, not just /stack itself.
 *
 * /stack already strips MAX_DAMAGE/DAMAGE when it runs, but nothing stops a
 * player from re-adding damage data afterward, or recreating the same
 * combo some other cursed way. Rather than chase every path that could
 * recreate the conflict, this mixin removes only that one check at its
 * source - HEAD-injects and cancels with success ONLY when it sees the
 * exact damageable+stackable combo (mirroring the vanilla condition
 * exactly), letting every other validation (container/bundle/charged-
 * projectiles size checks) run untouched when the combo isn't present.
 */
@Mixin(ItemStack.class)
public class ItemStackValidateComponentsMixin {

	@Inject(method = "validateComponents", at = @At("HEAD"), cancellable = true)
	private static void cmk$allowDamageableStackable(DataComponentMap components, CallbackInfoReturnable<DataResult<?>> cir) {
		if (components.has(DataComponents.MAX_DAMAGE) && components.getOrDefault(DataComponents.MAX_STACK_SIZE, 1) > 1) {
			cir.setReturnValue(DataResult.success(Unit.INSTANCE));
		}
	}
}
