package fi.vilpponen.mhr.border;

import fi.vilpponen.mhr.HardcoreRoguelite;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.features.TreeFeatures;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;

/**
 * A bounded run never starts without wood.
 *
 * <p>Trees are vanilla from the first run, so almost every start already has some. But the border
 * makes the start small, and a small square of desert, badlands, ocean or superflat can hold no
 * tree at all — and with no wood there is no crafting table, no tools and no way to earn the first
 * currency. So when a run starts, the land near spawn is searched for a log, and only if there is
 * none is one oak grown there. A start that already had wood is not touched.
 *
 * <p>Only the land inside the border counts, because a log the player cannot walk to does not help.
 * An unbounded border has no such problem and is left to vanilla.
 *
 * <p>These are not balance numbers. They say how hard to look and where to plant, not how much wood
 * the player gets, and the answer is always "one tree at most, and only when there was none".
 */
public final class StartingWood {
	/**
	 * How far from spawn to look, in blocks. Covers the whole of the smallest border; on a bigger
	 * one, a log this close is enough of an answer and the rest of the world need not be generated
	 * to find another.
	 */
	static final int SEARCH_RADIUS = 64;

	/**
	 * How far below the top of each column a log still counts. Deep enough for any tree's trunk
	 * under its own canopy, shallow enough that a log buried in a cave is not called reachable.
	 */
	static final int SEARCH_DEPTH = 32;

	/**
	 * Where a fallback tree may go: at least this far from spawn, so it is not on top of the player
	 * or the starter chest, which looks three blocks around spawn for its own room...
	 */
	private static final int PLANT_MIN_DISTANCE = 6;

	/** ...and no further than this, so it is in sight when the run begins. */
	private static final int PLANT_MAX_DISTANCE = 16;

	/** How many spots to offer the vanilla oak before building one by hand. */
	private static final int PLANT_ATTEMPTS = 32;

	/** What {@link #ensure} did, and where the log it answers with is. */
	public record Result(Outcome outcome, BlockPos log) {
	}

	public enum Outcome {
		/** The border is unbounded, so nothing was looked at. */
		UNBOUNDED,
		/** Worldgen already put a log inside the border. Nothing was changed. */
		FOUND,
		/** There was none, so one tree was grown. */
		PLANTED
	}

	private static volatile Result last;

	private StartingWood() {
	}

	/** What the most recent run start found or did, for the log and the tests. Null before one. */
	public static Result last() {
		return last;
	}

	/** Make sure the run's overworld has a log inside its border, near {@code spawn}. */
	public static Result ensure(ServerLevel level, BlockPos spawn) {
		WorldBorder border = level.getWorldBorder();
		Result result;
		if (border.getSize() >= WorldBorder.MAX_SIZE) {
			result = new Result(Outcome.UNBOUNDED, null);
		} else {
			// Only columns wholly inside the border, so a log anywhere in the area is one the
			// player can walk up to. Spawn is pulled inside it first: a tree next to a spawn the
			// border does not hold would be no use to anybody.
			int minX = (int) Math.ceil(border.getMinX());
			int minZ = (int) Math.ceil(border.getMinZ());
			int maxX = Math.max(minX, (int) Math.floor(border.getMaxX()) - 1);
			int maxZ = Math.max(minZ, (int) Math.floor(border.getMaxZ()) - 1);
			BlockPos near = new BlockPos(Math.clamp(spawn.getX(), minX, maxX), spawn.getY(),
					Math.clamp(spawn.getZ(), minZ, maxZ));
			Area area = new Area(
					Math.max(near.getX() - SEARCH_RADIUS, minX), Math.max(near.getZ() - SEARCH_RADIUS, minZ),
					Math.min(near.getX() + SEARCH_RADIUS, maxX), Math.min(near.getZ() + SEARCH_RADIUS, maxZ));
			result = ensure(level, area, near);
		}
		last = result;
		if (result.outcome() == Outcome.PLANTED) {
			HardcoreRoguelite.LOGGER.info("No wood inside the starting border, so an oak was grown at {} {} {}",
					result.log().getX(), result.log().getY(), result.log().getZ());
		} else if (result.outcome() == Outcome.FOUND) {
			HardcoreRoguelite.LOGGER.info("Starting border already has wood, e.g. at {} {} {}",
					result.log().getX(), result.log().getY(), result.log().getZ());
		}
		return result;
	}

	/**
	 * The same, for any square of columns. Public so a GameTest can ask it about a patch it built
	 * rather than a whole run.
	 *
	 * <p>The area is the whole question: the caller has already cut it down to what the player can
	 * reach, so nothing outside it is looked at and the tree, if one is needed, goes inside it.
	 */
	public static Result ensure(ServerLevel level, Area area, BlockPos near) {
		BlockPos found = findLog(level, area);
		if (found != null) {
			return new Result(Outcome.FOUND, found);
		}
		return new Result(Outcome.PLANTED, plant(level, area, near));
	}

	/** A square of columns, corners included. */
	public record Area(int minX, int minZ, int maxX, int maxZ) {
		boolean contains(int x, int z) {
			return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
		}
	}

	/** The first log near the top of any column in the area, or null. */
	private static BlockPos findLog(ServerLevel level, Area area) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int chunkX = area.minX() >> 4; chunkX <= area.maxX() >> 4; chunkX++) {
			for (int chunkZ = area.minZ() >> 4; chunkZ <= area.maxZ() >> 4; chunkZ++) {
				// Generates the chunk if it is not there yet. Worldgen has to have answered before
				// "there is no tree" means anything.
				level.getChunk(chunkX, chunkZ);
				for (int x = Math.max(area.minX(), chunkX << 4); x <= Math.min(area.maxX(), (chunkX << 4) + 15); x++) {
					for (int z = Math.max(area.minZ(), chunkZ << 4); z <= Math.min(area.maxZ(), (chunkZ << 4) + 15); z++) {
						int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
						int bottom = Math.max(level.getMinY(), top - SEARCH_DEPTH);
						for (int y = top; y >= bottom; y--) {
							if (level.getBlockState(pos.set(x, y, z)).is(BlockTags.LOGS)) {
								return pos.immutable();
							}
						}
					}
				}
			}
		}
		return null;
	}

	/**
	 * Grow one tree near {@code near}, and answer with a log of it.
	 *
	 * <p>A vanilla oak first, on dry ground, so the fallback looks like the rest of the world and
	 * drops saplings like any other tree. Only if no spot takes one is a small oak built by hand,
	 * which always works: it is the promise, the vanilla one is the preference.
	 */
	private static BlockPos plant(ServerLevel level, Area area, BlockPos near) {
		List<int[]> spots = spots(area, near);
		if (spots.isEmpty()) {
			// Nothing of the area is within reach of spawn. Only possible when asked about an area
			// spawn is not in, so the nearest corner of it will have to do.
			spots = List.of(new int[] {
					Math.clamp(near.getX(), area.minX(), area.maxX()),
					Math.clamp(near.getZ(), area.minZ(), area.maxZ())});
		}

		Feature oak = level.registryAccess().lookupOrThrow(Registries.FEATURE).getValueOrThrow(TreeFeatures.OAK);
		for (int i = 0; i < Math.min(PLANT_ATTEMPTS, spots.size()); i++) {
			int[] spot = spots.get(i);
			BlockPos base = new BlockPos(spot[0],
					level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spot[0], spot[1]), spot[1]);
			if (!level.getFluidState(base.below()).isEmpty() || !level.getFluidState(base).isEmpty()) {
				continue;
			}
			RandomSource random = RandomSource.create(level.getSeed() ^ base.asLong());
			if (oak.place(level, level.getChunkSource().getGenerator(), random, base)
					&& level.getBlockState(base).is(BlockTags.LOGS)) {
				return base;
			}
		}

		int[] spot = spots.get(0);
		return build(level, spot[0], spot[1]);
	}

	/**
	 * The columns a tree may go in, best first: inside the area, ideally a few steps
	 * from spawn, nearest first. Ties are broken by position so the order never depends on anything
	 * but the world.
	 */
	private static List<int[]> spots(Area area, BlockPos near) {
		List<int[]> spots = new ArrayList<>();
		for (int dx = -PLANT_MAX_DISTANCE; dx <= PLANT_MAX_DISTANCE; dx++) {
			for (int dz = -PLANT_MAX_DISTANCE; dz <= PLANT_MAX_DISTANCE; dz++) {
				int x = near.getX() + dx;
				int z = near.getZ() + dz;
				if (area.contains(x, z)) {
					spots.add(new int[] {x, z});
				}
			}
		}
		spots.sort(Comparator
				.comparingInt((int[] spot) -> distance(spot, near) < PLANT_MIN_DISTANCE ? 1 : 0)
				.thenComparingInt(spot -> {
					int dx = spot[0] - near.getX();
					int dz = spot[1] - near.getZ();
					return dx * dx + dz * dz;
				})
				.thenComparingInt(spot -> spot[0])
				.thenComparingInt(spot -> spot[1]));
		return spots;
	}

	private static int distance(int[] spot, BlockPos near) {
		return Math.max(Math.abs(spot[0] - near.getX()), Math.abs(spot[1] - near.getZ()));
	}

	/**
	 * A small oak put together block by block: a trunk from solid ground up through whatever is on
	 * top of it — water included — and a cap of leaves. Answers with the bottom log.
	 */
	private static BlockPos build(ServerLevel level, int x, int z) {
		int ground = level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z);
		int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		int top = Math.max(ground, surface) + 3;

		level.setBlock(new BlockPos(x, ground - 1, z), Blocks.DIRT.defaultBlockState(), 3);
		BlockState log = Blocks.OAK_LOG.defaultBlockState();
		for (int y = ground; y <= top; y++) {
			level.setBlock(new BlockPos(x, y, z), log, 3);
		}

		BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE, 1);
		for (int y = top - 1; y <= top + 1; y++) {
			int reach = y == top + 1 ? 1 : 2;
			for (int dx = -reach; dx <= reach; dx++) {
				for (int dz = -reach; dz <= reach; dz++) {
					BlockPos pos = new BlockPos(x + dx, y, z + dz);
					if (level.getBlockState(pos).isAir()) {
						level.setBlock(pos, leaves, 3);
					}
				}
			}
		}
		return new BlockPos(x, ground, z);
	}
}
