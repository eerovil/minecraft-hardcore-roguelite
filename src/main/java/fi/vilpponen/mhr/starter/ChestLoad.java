package fi.vilpponen.mhr.starter;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;

/**
 * Turning a pile of starter items into chest slots, and saying what did not fit.
 *
 * <p>Capacity is counted in slots after stacking, not in items: 64 bread is one slot, two stone
 * pickaxes are two. One chest is 27 slots and a double chest is 54, and 54 is the hard ceiling —
 * there is no third chest.
 *
 * <p>Going over that ceiling is a broken catalogue, not a situation to paper over, so this says
 * exactly what was left out. {@link StarterChest} is what shouts about it.
 *
 * <p>Nothing past the ceiling is ever built. The catalogue puts no upper bound on a count — "128
 * bread" is a shopping list and two slots of bread — so a mistyped one can legitimately ask for
 * millions, and assembling millions of stacks only to throw all but 54 away would take the server
 * down instead of telling anyone what was wrong. So the leftover comes back as a number per item
 * rather than as objects, and the work here is bounded by the 54 slots whatever the file says.
 *
 * <p>Nothing here touches the world, which is also what makes the arithmetic easy to reason about.
 */
public record ChestLoad(List<ItemStack> slots, List<Omitted> omitted) {
	public static final int SINGLE_CHEST_SLOTS = 27;
	public static final int DOUBLE_CHEST_SLOTS = 54;

	/**
	 * Something the chest had no room for.
	 *
	 * @param example one of them, for its name — the count is carried separately precisely so that
	 *     a huge one costs one object rather than a million.
	 * @param count   how many were left out. A {@code long} because two legal counts can add up to
	 *     more than an {@code int} holds.
	 */
	public record Omitted(ItemStack example, long count) {
	}

	/** Merge what can be merged, split what is over a stack, and stop at 54 slots. */
	public static ChestLoad of(List<ItemStack> stacks) {
		List<ItemStack> merged = new ArrayList<>();
		List<Omitted> omitted = new ArrayList<>();

		for (ItemStack stack : stacks) {
			if (stack.isEmpty()) {
				continue;
			}
			long left = add(merged, stack);
			if (left > 0) {
				omit(omitted, stack, left);
			}
		}

		return new ChestLoad(List.copyOf(merged), List.copyOf(omitted));
	}

	/** @return true if the load needs both halves of a double chest. */
	public boolean needsDoubleChest() {
		return slots.size() > SINGLE_CHEST_SLOTS;
	}

	public boolean isEmpty() {
		return slots.isEmpty();
	}

	/** @return true if more was asked for than 54 slots can hold. */
	public boolean hasOverflow() {
		return !omitted.isEmpty();
	}

	/**
	 * Pour one stack in, topping up part-full stacks of the same thing first and starting new ones
	 * as each fills, until either it is all in or the chest is full. A stack of 100 bread becomes
	 * 64 + 36 the same way the game would split it.
	 *
	 * @return how many were left over, which is 0 in every case but a full chest.
	 */
	private static long add(List<ItemStack> into, ItemStack stack) {
		int max = Math.max(1, stack.getOrDefault(DataComponents.MAX_STACK_SIZE, 1));
		long left = stack.getCount();

		if (max > 1) {
			for (ItemStack existing : into) {
				if (left <= 0) {
					return 0;
				}
				if (!ItemStack.isSameItemSameComponents(existing, stack)) {
					continue;
				}
				int room = max - existing.getCount();
				if (room > 0) {
					int moved = (int) Math.min(room, left);
					existing.setCount(existing.getCount() + moved);
					left -= moved;
				}
			}
		}

		// The bound that matters: one new stack per free slot, never one per stack asked for.
		while (left > 0 && into.size() < DOUBLE_CHEST_SLOTS) {
			int take = (int) Math.min(max, left);
			into.add(stack.copyWithCount(take));
			left -= take;
		}
		return left;
	}

	/** Record what was left out, adding to the same item's tally if it already has one. */
	private static void omit(List<Omitted> omitted, ItemStack stack, long count) {
		for (int i = 0; i < omitted.size(); i++) {
			Omitted already = omitted.get(i);
			if (ItemStack.isSameItemSameComponents(already.example(), stack)) {
				omitted.set(i, new Omitted(already.example(), already.count() + count));
				return;
			}
		}
		omitted.add(new Omitted(stack.copyWithCount(1), count));
	}
}
