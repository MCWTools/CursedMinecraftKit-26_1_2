package khoa.cmk;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Undo/redo history for /cmkfill, per player.
 *
 * Each entry is a snapshot of every block that a single /cmkfill call
 * actually changed - old state (for undo) and new state (for redo) at
 * every touched position. Only positions whose block actually changed are
 * recorded, same as vanilla /fill's own "affected blocks" count.
 *
 * Capped at 10 entries per player; pushing an 11th drops the oldest.
 *
 * Undo/redo themselves are NEVER recorded as new fill entries - that's
 * what would make /cmkundofill loop on itself. undo pops from
 * undoStack and pushes the same snapshot onto redoStack (unchanged);
 * redo does the reverse. A fresh /cmkfill clears the redo stack, exactly
 * like a normal editor undo/redo model - once you make a new change,
 * the old redo branch is gone.
 */
public final class CmkFillHistory {
	private CmkFillHistory() {}

	private static final int MAX_HISTORY = 10;

	public record BlockChange(BlockPos pos, BlockState oldState, BlockState newState) {}

	public record FillSnapshot(List<BlockChange> changes) {}

	private static final java.util.Map<java.util.UUID, Deque<FillSnapshot>> UNDO_STACKS = new java.util.HashMap<>();
	private static final java.util.Map<java.util.UUID, Deque<FillSnapshot>> REDO_STACKS = new java.util.HashMap<>();

	/** Call after a /cmkfill completes. Records the change and clears redo history. */
	public static void recordFill(java.util.UUID player, FillSnapshot snapshot) {
		if (snapshot.changes().isEmpty()) return;

		Deque<FillSnapshot> undo = UNDO_STACKS.computeIfAbsent(player, k -> new ArrayDeque<>());
		undo.push(snapshot);
		while (undo.size() > MAX_HISTORY) {
			undo.removeLast();
		}

		// A new fill invalidates whatever could have been redone.
		Deque<FillSnapshot> redo = REDO_STACKS.get(player);
		if (redo != null) redo.clear();
	}

	/** Pops the most recent undoable snapshot, or null if there is none. */
	public static FillSnapshot popUndo(java.util.UUID player) {
		Deque<FillSnapshot> undo = UNDO_STACKS.get(player);
		if (undo == null || undo.isEmpty()) return null;
		return undo.pop();
	}

	/** Pushes a snapshot onto the redo stack after it was undone. Not a new fill. */
	public static void pushRedo(java.util.UUID player, FillSnapshot snapshot) {
		Deque<FillSnapshot> redo = REDO_STACKS.computeIfAbsent(player, k -> new ArrayDeque<>());
		redo.push(snapshot);
		while (redo.size() > MAX_HISTORY) {
			redo.removeLast();
		}
	}

	/** Pops the most recent redoable snapshot, or null if there is none. */
	public static FillSnapshot popRedo(java.util.UUID player) {
		Deque<FillSnapshot> redo = REDO_STACKS.get(player);
		if (redo == null || redo.isEmpty()) return null;
		return redo.pop();
	}

	/** Pushes a snapshot back onto the undo stack after it was redone. Not a new fill. */
	public static void pushUndo(java.util.UUID player, FillSnapshot snapshot) {
		Deque<FillSnapshot> undo = UNDO_STACKS.computeIfAbsent(player, k -> new ArrayDeque<>());
		undo.push(snapshot);
		while (undo.size() > MAX_HISTORY) {
			undo.removeLast();
		}
	}

	/** Builds the inverse snapshot (old<->new swapped) for undo/redo application. */
	public static FillSnapshot inverse(FillSnapshot snapshot) {
		List<BlockChange> inverted = new ArrayList<>(snapshot.changes().size());
		for (BlockChange change : snapshot.changes()) {
			inverted.add(new BlockChange(change.pos(), change.newState(), change.oldState()));
		}
		return new FillSnapshot(inverted);
	}
}
