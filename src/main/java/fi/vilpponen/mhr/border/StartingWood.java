package fi.vilpponen.mhr.border;

import fi.vilpponen.mhr.HardcoreRoguelite;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.features.TreeFeatures;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.PlayerSpawnFinder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.storage.LevelData;

/**
 * A bounded run never starts without wood.
 *
 * <p>Trees are vanilla from the first run, so almost every start already has some. But the border
 * makes the start small, and a small square of desert, badlands, ocean or superflat can hold no
 * tree at all — and with no wood there is no crafting table, no tools and no way to earn the first
 * currency. So when a run starts, three things are tried in order, and the first that works wins:
 *
 * <ol>
 *   <li><b>Found.</b> Vanilla already put a log inside the border. Nothing is touched.
 *   <li><b>Moved.</b> Vanilla put trees somewhere nearby. The run's spawn moves next to them and the
 *       border is centered there instead, so the world stays exactly as generated.
 *   <li><b>Planted.</b> Neither — a superflat world, or a desert with no forest in reach. One oak is
 *       grown a few steps from spawn.
 * </ol>
 *
 * <p>The seed is never changed. A run started on a named seed stays on that seed, which is what
 * naming it is for.
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

	/**
	 * How far the spawn may move to reach trees, in blocks. Far enough that most deserts and
	 * oceans have a forest in reach; any further and "the run starts here" stops meaning much.
	 */
	static final int MOVE_RADIUS = 512;

	/** How close together the biome search samples, in blocks. Finer finds smaller groves. */
	private static final int BIOME_STEP = 16;

	/** How far around a wooded biome's edge to look for the log itself, in blocks. */
	private static final int GROVE_RADIUS = 32;

	/**
	 * What {@link #ensure} did, where the log it answers with is, and where the run's spawn ended
	 * up — which is where it started unless the outcome is {@link Outcome#MOVED}.
	 */
	public record Result(Outcome outcome, BlockPos log, BlockPos spawn) {
	}

	public enum Outcome {
		/** The border is unbounded, so nothing was looked at. */
		UNBOUNDED,
		/** Worldgen already put a log inside the border. Nothing was changed. */
		FOUND,
		/** There was none inside, but trees nearby, so the spawn and the border moved to them. */
		MOVED,
		/** There was none in reach, so one tree was grown. */
		PLANTED
	}

	private static volatile Result last;

	private StartingWood() {
	}

	/** What the most recent run start found or did, for the log and the tests. Null before one. */
	public static Result last() {
		return last;
	}

	/**
	 * Make sure the run's overworld has a log inside its border. Called once the border is in place,
	 * and may move the run's spawn and the border with it.
	 */
	public static Result ensure(MinecraftServer server, ServerLevel overworld) {
		BlockPos spawn = spawnOf(server);
		Result result;
		if (overworld.getWorldBorder().getSize() >= WorldBorder.MAX_SIZE) {
			result = new Result(Outcome.UNBOUNDED, null, spawn);
		} else {
			result = ensureBounded(server, overworld, spawn);
		}
		last = result;
		switch (result.outcome()) {
			case FOUND -> HardcoreRoguelite.LOGGER.info("Starting border already has wood, e.g. at {}",
					describe(result.log()));
			case MOVED -> HardcoreRoguelite.LOGGER.info(
					"No wood inside the starting border, so the run's spawn moved from {} to {}, next to trees at {}",
					describe(spawn), describe(result.spawn()), describe(result.log()));
			case PLANTED -> HardcoreRoguelite.LOGGER.info(
					"No wood inside the starting border or within {} blocks, so an oak was grown at {}",
					MOVE_RADIUS, describe(result.log()));
			case UNBOUNDED -> {
			}
		}
		return result;
	}

	private static Result ensureBounded(MinecraftServer server, ServerLevel overworld, BlockPos spawn) {
		BlockPos found = findLog(overworld, reachable(overworld, spawn));
		if (found != null) {
			return new Result(Outcome.FOUND, found, spawn);
		}

		BlockPos grove = findGrove(overworld, spawn);
		if (grove != null && overworld.getWorldBorder().isWithinBounds(grove)) {
			// Further out than the first look went, on a border bigger than it, but inside all the same.
			return new Result(Outcome.FOUND, grove, spawn);
		}
		if (grove != null) {
			BlockPos moved = standingSpotNear(overworld, grove);
			server.setRespawnData(LevelData.RespawnData.of(Level.OVERWORLD, moved, 0.0F, 0.0F));
			WorldBorders.apply(server);
			// Asked again rather than assumed: the border has to hold the trees, not just the spawn.
			BlockPos log = findLog(overworld, reachable(overworld, moved));
			if (log != null) {
				return new Result(Outcome.MOVED, log, moved);
			}
			spawn = moved;
		}

		return new Result(Outcome.PLANTED, plant(overworld, reachable(overworld, spawn), clamp(overworld, spawn)),
				spawn);
	}

	/**
	 * The spawn the border is centered on. Not {@link MinecraftServer#getRespawnData()} by itself:
	 * that is the same spot pulled inside the border, and is only brought up to date once a tick.
	 */
	private static BlockPos spawnOf(MinecraftServer server) {
		return server.getWorldData().overworldData().getRespawnData().pos();
	}

	/**
	 * The columns near {@code spawn} wholly inside the border, so a log anywhere in them is one the
	 * player can walk up to. Spawn is pulled inside the border first: a tree next to a spawn the
	 * border does not hold would be no use to anybody.
	 */
	private static Area reachable(ServerLevel level, BlockPos spawn) {
		WorldBorder border = level.getWorldBorder();
		int minX = (int) Math.ceil(border.getMinX());
		int minZ = (int) Math.ceil(border.getMinZ());
		int maxX = Math.max(minX, (int) Math.floor(border.getMaxX()) - 1);
		int maxZ = Math.max(minZ, (int) Math.floor(border.getMaxZ()) - 1);
		BlockPos near = clamp(level, spawn);
		return new Area(
				Math.max(near.getX() - SEARCH_RADIUS, minX), Math.max(near.getZ() - SEARCH_RADIUS, minZ),
				Math.min(near.getX() + SEARCH_RADIUS, maxX), Math.min(near.getZ() + SEARCH_RADIUS, maxZ));
	}

	private static BlockPos clamp(ServerLevel level, BlockPos pos) {
		WorldBorder border = level.getWorldBorder();
		int minX = (int) Math.ceil(border.getMinX());
		int minZ = (int) Math.ceil(border.getMinZ());
		int maxX = Math.max(minX, (int) Math.floor(border.getMaxX()) - 1);
		int maxZ = Math.max(minZ, (int) Math.floor(border.getMaxZ()) - 1);
		return new BlockPos(Math.clamp(pos.getX(), minX, maxX), pos.getY(), Math.clamp(pos.getZ(), minZ, maxZ));
	}

	/**
	 * A log in the nearest wooded biome within {@link #MOVE_RADIUS} of spawn, or null.
	 *
	 * <p>The biome is found the way {@code /locate biome} finds one, from the biome map alone, so
	 * nothing is generated until there is somewhere worth looking. Then only the land around that
	 * spot is generated and searched, because a wooded biome is a promise of trees, not a tree.
	 */
	private static BlockPos findGrove(ServerLevel level, BlockPos spawn) {
		Pair<BlockPos, Holder<Biome>> nearest = level.findClosestBiome3d(StartingWood::isWooded,
				spawn, MOVE_RADIUS, BIOME_STEP, 64);
		if (nearest == null) {
			return null;
		}
		BlockPos at = nearest.getFirst();
		return findLog(level, new Area(at.getX() - GROVE_RADIUS, at.getZ() - GROVE_RADIUS,
				at.getX() + GROVE_RADIUS, at.getZ() + GROVE_RADIUS));
	}

	private static boolean isWooded(Holder<Biome> biome) {
		return biome.is(BiomeTags.IS_FOREST) || biome.is(BiomeTags.IS_TAIGA)
				|| biome.is(BiomeTags.IS_JUNGLE) || biome.is(BiomeTags.IS_SAVANNA);
	}

	/**
	 * Somewhere to stand next to a tree: vanilla's own spawn search over the tree's chunk and the
	 * ones around it, and the top of the trunk if none of them has anywhere better.
	 */
	private static BlockPos standingSpotNear(ServerLevel level, BlockPos log) {
		ChunkPos centre = ChunkPos.containing(log);
		for (int radius = 0; radius <= 1; radius++) {
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
						continue;
					}
					BlockPos found = PlayerSpawnFinder.getSpawnPosInChunk(level,
							new ChunkPos(centre.x() + dx, centre.z() + dz));
					if (found != null) {
						return found;
					}
				}
			}
		}
		return new BlockPos(log.getX(),
				level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, log.getX(), log.getZ()), log.getZ());
	}

	private static String describe(BlockPos pos) {
		return pos.getX() + " " + pos.getY() + " " + pos.getZ();
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
			return new Result(Outcome.FOUND, found, near);
		}
		return new Result(Outcome.PLANTED, plant(level, area, near), near);
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
