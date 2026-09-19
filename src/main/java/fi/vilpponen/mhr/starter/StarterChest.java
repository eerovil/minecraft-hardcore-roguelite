package fi.vilpponen.mhr.starter;

import fi.vilpponen.mhr.HardcoreRoguelite;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

/**
 * Builds the chest the player finds at the start of a run and fills it.
 *
 * <p>This is the only place in the mod that knows a chest is a chest. What goes in it is decided
 * by {@link StarterItems} from the balance catalogue and {@link ChestLoad} from arithmetic; when
 * it happens is decided by {@link RunStart}. Splitting it that way is what keeps starter-item
 * balance a data edit rather than a code change.
 *
 * <p>One chest up to 27 filled slots, a double chest up to 54, and never a third. Asking for more
 * than 54 is a broken catalogue: the chest is still placed and still full, and the leftovers are
 * named in the log and in chat rather than quietly thrown away. They are not lost either — the
 * unlocks that produced them are permanent, so shrinking the catalogue brings them back next run.
 */
public final class StarterChest {
	/** How far from the player we are willing to look for somewhere to put it. */
	private static final int SEARCH_RADIUS = 3;

	/**
	 * Which heights to try, in order, relative to the player's feet.
	 *
	 * <p>Their own level first, then one step down, since that is where the ground is when they
	 * are standing on a slope, then outwards. Kept short on purpose: past three blocks it stops
	 * being "next to you" and starts being a chest you have to go looking for.
	 */
	private static final int[] VERTICAL_ORDER = {0, -1, 1, -2, 2, -3, 3};

	private StarterChest() {
	}

	/** What happened, so the caller can tell the player and the log without repeating the work. */
	public record Placement(BlockPos pos, boolean doubleChest, List<ChestLoad.Omitted> omitted) {
	}

	/**
	 * Place and fill the chest.
	 *
	 * @param facing which way the chest's front points. Must be horizontal.
	 * @return null when there was nothing to put in it, in which case no block is touched.
	 */
	public static Placement place(ServerLevel level, BlockPos near, Direction facing, List<ItemStack> stacks) {
		ChestLoad load = ChestLoad.of(stacks);
		if (load.isEmpty()) {
			return null;
		}

		BlockState left = Blocks.CHEST.defaultBlockState()
				.setValue(ChestBlock.FACING, facing)
				.setValue(ChestBlock.TYPE, load.needsDoubleChest() ? ChestType.LEFT : ChestType.SINGLE);
		Direction toOther = load.needsDoubleChest() ? ChestBlock.getConnectedDirection(left) : null;

		BlockPos pos = findSpot(level, near, toOther);
		BlockPos other = toOther == null ? null : pos.relative(toOther);

		support(level, pos);
		level.setBlock(pos, left, 3);
		if (other != null) {
			support(level, other);
			level.setBlock(other, left.setValue(ChestBlock.TYPE, ChestType.RIGHT), 3);
		}

		fill(level, pos, load.slots(), 0);
		if (other != null) {
			fill(level, other, load.slots(), ChestLoad.SINGLE_CHEST_SLOTS);
		}

		if (load.hasOverflow()) {
			HardcoreRoguelite.LOGGER.error(
					"Starter items do not fit: a double chest holds {} slots. Left out of the chest: {}."
							+ " Remove starter items from the balance catalogue until they fit.",
					ChestLoad.DOUBLE_CHEST_SLOTS,
					describe(load.omitted()));
		}

		return new Placement(pos, other != null, load.omitted());
	}

	/** A readable tally of what was left out, for the log and for chat. */
	public static String describe(List<ChestLoad.Omitted> omitted) {
		StringBuilder text = new StringBuilder();
		for (ChestLoad.Omitted entry : omitted) {
			if (!text.isEmpty()) {
				text.append(", ");
			}
			text.append(entry.count()).append('x').append(entry.example().getHoverName().getString());
		}
		return text.toString();
	}

	/**
	 * Somewhere the player can walk to, with room for the second half if there is one.
	 *
	 * <p>A small box around where they are standing, searched nearest first, staying at their own
	 * level where it can and only then looking a few blocks up or down for a slope or a step.
	 *
	 * <p>Their level is the whole point. An earlier version took each column's surface from the
	 * heightmap, which is only the same place when the run starts out in the open: begin in a cave
	 * and the chest appeared on the hillside overhead, and in the Nether it went on the bedrock
	 * roof — and since that block is usually replaceable, the search was perfectly happy with it
	 * and never fell back to anything nearer. The surface is still chosen when the surface is
	 * where the player is, because then it is inside this box like any other spot.
	 *
	 * <p>If nothing in the box works — a one-block tunnel, the inside of a structure — it gives up
	 * and takes the player's own feet. That is still next to them, which is what matters; a chest
	 * in an awkward spot beats no chest at all.
	 */
	private static BlockPos findSpot(ServerLevel level, BlockPos near, Direction toOther) {
		for (int radius = 1; radius <= SEARCH_RADIUS; radius++) {
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
						continue;
					}
					for (int dy : VERTICAL_ORDER) {
						BlockPos candidate = new BlockPos(near.getX() + dx, near.getY() + dy, near.getZ() + dz);
						if (fits(level, candidate, toOther)) {
							return candidate;
						}
					}
				}
			}
		}
		HardcoreRoguelite.LOGGER.warn(
				"No clear spot for the starter chest within {} blocks of {}; putting it at the player's feet",
				SEARCH_RADIUS, near);
		return near;
	}

	/**
	 * Is there room here, and can it be got at?
	 *
	 * <p>The block above has to be clear too, or the chest is walled into the ceiling and cannot
	 * be opened. {@link #support} deals with what is underneath.
	 */
	private static boolean fits(ServerLevel level, BlockPos pos, Direction toOther) {
		if (!isClear(level, pos)) {
			return false;
		}
		// Both halves have to sit at the same y, so the neighbour has to be free at that y too.
		return toOther == null || isClear(level, pos.relative(toOther));
	}

	private static boolean isClear(ServerLevel level, BlockPos pos) {
		return level.getBlockState(pos).canBeReplaced() && level.getBlockState(pos.above()).canBeReplaced();
	}

	/** Keep the chest off thin air, so it does not hang over a ravine looking like a bug. */
	private static void support(ServerLevel level, BlockPos pos) {
		BlockPos below = pos.below();
		if (level.getBlockState(below).canBeReplaced()) {
			level.setBlock(below, Blocks.DIRT.defaultBlockState(), 3);
		}
	}

	/** Copy one chest's worth of the load into the block entity at {@code pos}. */
	private static void fill(ServerLevel level, BlockPos pos, List<ItemStack> slots, int from) {
		if (!(level.getBlockEntity(pos) instanceof ChestBlockEntity chest)) {
			HardcoreRoguelite.LOGGER.error("Placed a starter chest at {} but found no chest there to fill", pos);
			return;
		}
		int count = Math.min(chest.getContainerSize(), slots.size() - from);
		for (int i = 0; i < count; i++) {
			chest.setItem(i, slots.get(from + i));
		}
		chest.setChanged();
	}
}
