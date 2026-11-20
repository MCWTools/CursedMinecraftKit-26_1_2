package khoa.cmk;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Holds runtime-registered "fake recipes": a 3x3 layout (slots 1-9, left to
 * right, top to bottom) mapped to a result item + quantity. Matching happens
 * in CraftingMenuMixin, which runs on every slotsChanged() and, on a match,
 * force-fills the result slot before/instead of vanilla recipe matching.
 *
 * A null entry in grid[] means "this slot must be empty". Slots the user
 * didn't type in /fakerecipe create are left null - that's intentional,
 * not an error.
 */
public final class CmkFakeRecipe {
	private CmkFakeRecipe() {}

	public record Entry(int id, Item result, int quantity, Item[] grid) {}

	private static final List<Entry> ENTRIES = new ArrayList<>();
	private static final AtomicInteger NEXT_ID = new AtomicInteger(1);

	/** grid must have exactly 9 elements, slot index 0-8, null = must be empty. Returns the new entry's id. */
	public static int register(Item result, int quantity, Item[] grid) {
		if (grid.length != 9) {
			throw new IllegalArgumentException("Fake recipe grid must have exactly 9 slots");
		}
		int id = NEXT_ID.getAndIncrement();
		ENTRIES.add(new Entry(id, result, quantity, grid));
		return id;
	}

	public static List<Entry> all() {
		return List.copyOf(ENTRIES);
	}

	public static boolean delete(int id) {
		return ENTRIES.removeIf(e -> e.id() == id);
	}

	public static void clear() {
		ENTRIES.clear();
	}

	/**
	 * Checks a live 3x3 crafting grid (as Items, in slot order) against all
	 * registered fake recipes. Returns the matching entry (result +
	 * quantity), or empty. Later-registered recipes take priority on
	 * overlapping layouts.
	 */
	public static Optional<Entry> match(Item[] currentGrid) {
		if (currentGrid.length != 9) return Optional.empty();
		for (int idx = ENTRIES.size() - 1; idx >= 0; idx--) {
			Entry entry = ENTRIES.get(idx);
			boolean matches = true;
			for (int i = 0; i < 9; i++) {
				Item expected = entry.grid()[i];
				Item actual = currentGrid[i];
				boolean expectedEmpty = expected == null || expected == Items.AIR;
				boolean actualEmpty = actual == null || actual == Items.AIR;
				if (expectedEmpty != actualEmpty) { matches = false; break; }
				if (!expectedEmpty && expected != actual) { matches = false; break; }
			}
			if (matches) return Optional.of(entry);
		}
		return Optional.empty();
	}

	/** Parses a string item id like "minecraft:diamond" or "air" into an Item. */
	public static Optional<Item> parseItem(String raw) {
		if (raw == null || raw.equalsIgnoreCase("air") || raw.equalsIgnoreCase("empty")) {
			return Optional.of(Items.AIR);
		}
		Identifier id = Identifier.tryParse(raw);
		if (id == null) return Optional.empty();
		return BuiltInRegistries.ITEM.getOptional(id);
	}
}
