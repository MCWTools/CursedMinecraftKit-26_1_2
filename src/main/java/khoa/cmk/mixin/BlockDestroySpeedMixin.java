package khoa.cmk.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;

import khoa.cmk.CmkState;

/**
 * BlockStateBase#getDestroySpeed just returns the "destroySpeed" field baked
 * in from Properties#strength at block-registration time (see Blocks.BEDROCK
 * -> strength(-1.0f, 3600000.0f) vs Blocks.OBSIDIAN -> strength(50.0f,
 * 1200.0f)). -1.0f is the vanilla "unbreakable" sentinel: every caller
 * (progress calc, explosion checks, etc.) treats it as "cannot be destroyed by
 * digging" - see BlockBehaviour#getDestroyProgress checking
 * "destroySpeed == -1.0f".
 *
 * Rather than touch each block's registered Properties (which would require
 * per-block mixins), this injects at the tail of the shared getDestroySpeed
 * accessor and overwrites any -1.0f result with obsidian's hardness (50.0f)
 * whenever the toggle is on. Blast resistance (the second strength() arg,
 * "explosionResistance") is untouched - only mining hardness changes.
 */
@Mixin(targets = "net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase")
public class BlockDestroySpeedMixin {

	// Obsidian's hardness (Blocks.OBSIDIAN -> strength(50.0f, ...)).
	private static final float OBSIDIAN_HARDNESS = 0.5f;

	@Inject(method = "getDestroySpeed", at = @At("RETURN"), cancellable = true)
	private void cmk$allBedrockBreakable(BlockGetter level, BlockPos pos, CallbackInfoReturnable<Float> cir) {
		if (CmkState.allBedrockBreakable && cir.getReturnValue() == -1.0f) {
			cir.setReturnValue(OBSIDIAN_HARDNESS);
		}
	}
}
