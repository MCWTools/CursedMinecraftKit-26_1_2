package khoa.cmk.mixin;

import khoa.cmk.CmkFakeRecipe;

import net.minecraft.world.Container;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Optional;

/**
 * Applies /fakerecipe entries to the real 3x3 crafting grid.
 *
 * Runs at TAIL of slotsChanged(Container), i.e. after vanilla has already
 * done its own recipe matching and possibly set (or cleared) the result
 * slot. If a fake recipe matches the current grid, we overwrite the result
 * slot afterward - so a fake recipe always wins over a real vanilla
 * recipe using the same layout.
 */
@Mixin(CraftingMenu.class)
public class CraftingMenuMixin {

	@Inject(method = "slotsChanged", at = @At("TAIL"))
	private void cmk_checkFakeRecipes(Container container, CallbackInfo ci) {
		CraftingMenu self = (CraftingMenu) (Object) this;

		List<Slot> gridSlots = self.getInputGridSlots();
		if (gridSlots.size() != 9) {
			// Not a standard 3x3 crafting table grid (could be the 2x2
			// inventory crafting grid) - fake recipes are 3x3 only, skip.
			return;
		}

		Item[] currentGrid = new Item[9];
		for (int i = 0; i < 9; i++) {
			ItemStack stack = gridSlots.get(i).getItem();
			currentGrid[i] = stack.isEmpty() ? null : stack.getItem();
		}

		Optional<CmkFakeRecipe.Entry> match = CmkFakeRecipe.match(currentGrid);
		if (match.isPresent()) {
			CmkFakeRecipe.Entry entry = match.get();
			ItemStack result = new ItemStack(entry.result(), entry.quantity());
			self.getResultSlot().set(result);
		}
		// If no fake recipe matches, leave whatever vanilla already put in
		// the result slot (a real recipe result, or nothing) untouched.
	}
}
