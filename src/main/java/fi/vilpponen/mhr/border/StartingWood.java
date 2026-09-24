package fi.vilpponen.mhr.border;

import fi.vilpponen.mhr.HardcoreRoguelite;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.PlayerSpawnFinder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.FallenTreeFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.storage.LevelData;

/**
 * A bounded run starts somewhere with wood, chosen rather than made.
 *
 * <p>Trees are vanilla from the first run, so almost every start already has some. But the border
 * makes the start small, and a small square of desert, glacier or ocean can hold no tree at all —
 * and with no wood there is no crafting table, no tools and no way to earn the first currency. So
 * when a run starts:
 *
 * <ol>
 *   <li><b>Found.</b> Enough wood is already somewhere inside the border. Nothing is touched.
 *   <li><b>Moved.</b> It is not, so biomes nearby that grow trees are looked up from the biome map,
 *       the way {@code /locate biome} does, nearest first (see {@link #growsTrees}). The first one
 *       whose actual land has enough wood becomes the start: the run's spawn moves there and the
 *       border is centered on it.
 *   <li><b>None.</b> Nothing natural is in reach — a superflat world, say. The start is left where
 *       and as it was, and the log says so.
 * </ol>
 *
 * <p>The world itself is never edited. A desert stays a desert: no tree is ever placed to make a
 * start viable. The seed is never changed either, so a run started on a named seed stays on it.
 *
 * <p>These are not balance numbers. They say how hard to look, not how much the player gets.
 */
public final class StartingWood {
	/**
	 * How many logs make a start viable: one for a crafting table and a wooden pickaxe (4 + 3 planks,
	 * and the sticks out of 2 more), plus room for an axe. Three logs are twelve planks.
	 */
	public static final int VIABLE_LOGS = 3;

	/**
	 * The most chunks the first look inside the border may generate: a square as wide as the Medium
	 * border. It stops at the third log and looks at the chunks nearest spawn first, so a wooded start
	 * costs a handful. The cap is for a Large border with no wood near spawn, which would otherwise
	 * generate thousands of chunks before giving up; past it, the land around the biomes inside
	 * the border that grow trees is counted instead, from the biome map.
	 */
	static final int MAX_SCAN_CHUNKS = 33 * 33;

	/**
	 * How far below the top of each column a log still counts. Deep enough for any tree's trunk
	 * under its own canopy, shallow enough that a log buried in a cave is not called reachable.
	 */
	public static final int SEARCH_DEPTH = 32;

	/**
	 * How far the spawn may move to reach trees, in blocks. Far enough that most deserts and
	 * oceans have a forest in reach; any further and "the run starts here" stops meaning much.
	 */
	static final int MOVE_RADIUS = 512;

	/**
	 * How far apart the biome map is sampled, in blocks. Each sample stands for the square cell
	 * around it, two chunks by two, and a candidate's wood is counted in its own cell only. The
	 * cells tile the land without overlapping, so no candidate pays for land another one already
	 * looked at, and none is skipped for being near one that failed.
	 */
	static final int CELL = 32;

	/**
	 * The most chunks the search for somewhere else to start may generate, all candidates together.
	 * Every biome that can grow a tree is a candidate, and an ocean or a plain can grow one, so a
	 * start at sea or on open grassland can have a candidate at nearly every cell in reach — about
	 * 800 of them, 3,200 chunks. The search goes nearest first and stops here, so the worst run start
	 * costs about as much as the first look inside a Medium border, not several times that.
	 */
	public static final int CANDIDATE_BUDGET_CHUNKS = 33 * 33;

	/** How far apart the biome map is read inside a cell, in blocks: four by four samples a cell. */
	private static final int CELL_SAMPLE = 8;

	/**
	 * What {@link #ensure} did. {@code log} is a log it counted, {@code spawn} where the run's spawn
	 * ended up, and {@code from} where it was before — the same unless the outcome is
	 * {@link Outcome#MOVED}. {@code searchedChunks} is how many chunks the search for somewhere else
	 * to start looked at, never more than {@link #CANDIDATE_BUDGET_CHUNKS}.
	 */
	public record Result(Outcome outcome, BlockPos log, BlockPos spawn, BlockPos from, int searchedChunks) {
	}

	public enum Outcome {
		/** The border is unbounded, so nothing was looked at. */
		UNBOUNDED,
		/** Enough wood was inside the border already. Nothing was changed. */
		FOUND,
		/** There was not, so the spawn and the border moved to wooded land nearby. */
		MOVED,
		/** There was not, and no wooded land in reach had enough either. Nothing was changed. */
		NONE
	}

	private static volatile Result last;

	private StartingWood() {
	}

	/** What the most recent run start found or did, for the log and the tests. Null before one. */
	public static Result last() {
		return last;
	}

	/**
	 * Make sure the run starts with wood inside its border, if the world has any in reach. Called
	 * once the border is in place, and may move the run's spawn and the border with it.
	 */
	public static Result ensure(MinecraftServer server, ServerLevel overworld) {
		BlockPos spawn = spawnOf(server);
		Result result;
		if (overworld.getWorldBorder().getSize() >= WorldBorder.MAX_SIZE) {
			result = new Result(Outcome.UNBOUNDED, null, spawn, spawn, 0);
		} else {
			result = ensureBounded(server, overworld, spawn);
		}
		last = result;
		switch (result.outcome()) {
			case FOUND -> HardcoreRoguelite.LOGGER.info("Starting border already has wood, e.g. at {}",
					describe(result.log()));
			case MOVED -> HardcoreRoguelite.LOGGER.info(
					"Not enough wood inside the starting border, so the run's spawn moved from {} to {}, next to trees at {}"
							+ " ({} chunks searched)",
					describe(spawn), describe(result.spawn()), describe(result.log()), result.searchedChunks());
			case NONE -> HardcoreRoguelite.LOGGER.warn(
					"Not enough wood inside the starting border, and no wooded land within {} blocks of {} has"
							+ " enough either. The start is left as it was.",
					MOVE_RADIUS, describe(spawn));
			case UNBOUNDED -> {
			}
		}
		return result;
	}

	private static Result ensureBounded(MinecraftServer server, ServerLevel overworld, BlockPos spawn) {
		// The whole border counts, not just the land by spawn: a Medium or Large start with its trees
		// two hundred blocks out is still a start with trees.
		Area border = reachable(overworld);
		List<BlockPos> here = countLogs(overworld, border, spawn, VIABLE_LOGS, MAX_SCAN_CHUNKS);
		if (here.size() >= VIABLE_LOGS) {
			return new Result(Outcome.FOUND, here.get(0), spawn, spawn, 0);
		}

		LogCache logs = new LogCache(overworld, CANDIDATE_BUDGET_CHUNKS);
		Result natural = firstViable(woodedCandidates(overworld, spawn),
				candidate -> tryCandidate(server, overworld, logs, border, spawn, candidate), logs::spent);
		if (natural != null) {
			return natural;
		}

		if (!spawnOf(server).equals(spawn)) {
			moveSpawn(server, spawn);
		}
		if (logs.spent()) {
			HardcoreRoguelite.LOGGER.warn("The search for trees near {} stopped after {} chunks, its limit",
					describe(spawn), logs.scanned());
		}
		return new Result(Outcome.NONE, null, spawn, spawn, logs.scanned());
	}

	/**
	 * Try the candidates in order until one works, and answer with what it gave, or null once they
	 * have all been tried.
	 *
	 * <p>Every candidate is tried, however close it is to one that failed: each only looks at its
	 * own cell, so a neighbour can hold trees its neighbour's cell does not. What ends the search
	 * early is {@code stop}: the search has spent its chunk budget. Public so a GameTest can drive
	 * it with a made-up list rather than a world.
	 */
	public static <R> R firstViable(List<BlockPos> candidates, Function<BlockPos, R> attempt, BooleanSupplier stop) {
		for (BlockPos candidate : candidates) {
			if (stop.getAsBoolean()) {
				return null;
			}
			R result = attempt.apply(candidate);
			if (result != null) {
				return result;
			}
		}
		return null;
	}

	/** One candidate: a start at it if its land has the wood, or null to go on to the next. */
	private static Result tryCandidate(MinecraftServer server, ServerLevel overworld, LogCache logs,
			Area border, BlockPos spawn, BlockPos candidate) {
		// A biome that grows trees is a promise of trees, not a tree, so its land is counted before
		// anything moves. Only this one cell is generated to find out.
		Area cell = cellOf(candidate);
		if (border.contains(candidate)) {
			// Inside the border already, past where the first look stopped: trees here mean the
			// start is fine as it is, and moving would only take the player away from them.
			List<BlockPos> inside = logs.in(border.intersect(cell), VIABLE_LOGS);
			return inside.size() >= VIABLE_LOGS
					? new Result(Outcome.FOUND, inside.get(0), spawn, spawn, logs.scanned())
					: null;
		}
		List<BlockPos> cellLogs = logs.in(cell, VIABLE_LOGS);
		if (cellLogs.size() < VIABLE_LOGS) {
			return null;
		}

		BlockPos moved = standingSpotNear(overworld, cellLogs.get(0));
		moveSpawn(server, moved);
		// Counted again inside the border the move produced, rather than assumed: the border has
		// to hold the trees, not just the spawn.
		List<BlockPos> inside = countLogs(overworld, reachable(overworld), moved, VIABLE_LOGS, MAX_SCAN_CHUNKS);
		return inside.size() >= VIABLE_LOGS
				? new Result(Outcome.MOVED, inside.get(0), moved, spawn, logs.scanned())
				: null;
	}

	private static void moveSpawn(MinecraftServer server, BlockPos to) {
		server.setRespawnData(LevelData.RespawnData.of(Level.OVERWORLD, to, 0.0F, 0.0F));
		WorldBorders.apply(server);
	}

	/**
	 * The spawn the border is centered on. Not {@link MinecraftServer#getRespawnData()} by itself:
	 * that is the same spot pulled inside the border, and is only brought up to date once a tick.
	 */
	private static BlockPos spawnOf(MinecraftServer server) {
		return server.getWorldData().overworldData().getRespawnData().pos();
	}

	/**
	 * Every column wholly inside the border, so a log anywhere in them is one the player can walk
	 * up to.
	 */
	private static Area reachable(ServerLevel level) {
		WorldBorder border = level.getWorldBorder();
		int minX = (int) Math.ceil(border.getMinX());
		int minZ = (int) Math.ceil(border.getMinZ());
		return new Area(minX, minZ, Math.max(minX, (int) Math.floor(border.getMaxX()) - 1),
				Math.max(minZ, (int) Math.floor(border.getMaxZ()) - 1));
	}

	/**
	 * The middle of every cell within {@link #MOVE_RADIUS} of spawn with a biome in it that grows
	 * trees, nearest first. The cells are lined up with chunks, so each is exactly four of them.
	 *
	 * <p>Read from the biome map alone, the way {@code /locate biome} reads it, so nothing is
	 * generated to make the list. Ties are broken by position, so the order depends on the world and
	 * nothing else.
	 */
	private static List<BlockPos> woodedCandidates(ServerLevel level, BlockPos spawn) {
		int quartY = QuartPos.fromBlock(level.getSeaLevel());
		Map<Biome, Boolean> grows = new IdentityHashMap<>();
		List<BlockPos> candidates = new ArrayList<>();
		int baseX = Math.floorDiv(spawn.getX(), CELL) * CELL + CELL / 2;
		int baseZ = Math.floorDiv(spawn.getZ(), CELL) * CELL + CELL / 2;
		for (int dx = -MOVE_RADIUS; dx <= MOVE_RADIUS; dx += CELL) {
			for (int dz = -MOVE_RADIUS; dz <= MOVE_RADIUS; dz += CELL) {
				if (dx * dx + dz * dz > MOVE_RADIUS * MOVE_RADIUS) {
					continue;
				}
				BlockPos centre = new BlockPos(baseX + dx, level.getSeaLevel(), baseZ + dz);
				if (cellGrowsTrees(level, cellOf(centre), quartY, grows)) {
					candidates.add(centre);
				}
			}
		}
		candidates.sort(Comparator.comparingInt((BlockPos pos) -> horizontalDistance(pos, spawn))
				.thenComparingInt(BlockPos::getX)
				.thenComparingInt(BlockPos::getZ));
		return candidates;
	}

	/**
	 * Whether the biome's own worldgen places trees: a tree or a fallen tree anywhere among its
	 * features, however deeply it sits inside a random selector.
	 *
	 * <p>Asked of the biome rather than of a list of biome tags, so no biome that grows trees — cherry
	 * grove, mangrove swamp, plains with its odd oak, one a datapack adds — is ruled out before its
	 * land is counted. Growing some trees is not the same as having enough of them where it matters,
	 * which is why a candidate's real logs are still counted before the spawn moves. Even the ocean
	 * says yes — it can grow the odd tree on an island — which is what
	 * {@link #CANDIDATE_BUDGET_CHUNKS} is for. Public so a GameTest can ask it about biomes by name.
	 */
	public static boolean growsTrees(Biome biome) {
		Set<Feature> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		for (var step : biome.getGenerationSettings().features()) {
			for (Holder<PlacedFeature> placed : step) {
				if (placesTrees(placed.value().feature(), seen)) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean placesTrees(Holder<Feature> holder, Set<Feature> seen) {
		Feature feature = holder.value();
		if (!seen.add(feature)) {
			return false;
		}
		if (feature instanceof TreeFeature || feature instanceof FallenTreeFeature) {
			return true;
		}
		return feature.getSubFeatures().anyMatch(sub -> placesTrees(sub, seen));
	}

	/**
	 * Whether any part of the cell is a biome that grows trees, from a biome sample every
	 * {@link #CELL_SAMPLE} blocks across it. Not only its middle: the edge of a wooded plain can
	 * reach into a cell whose middle is badlands, and its trees are as good as any.
	 */
	private static boolean cellGrowsTrees(ServerLevel level, Area cell, int quartY, Map<Biome, Boolean> grows) {
		for (int x = cell.minX() + CELL_SAMPLE / 2; x <= cell.maxX(); x += CELL_SAMPLE) {
			for (int z = cell.minZ() + CELL_SAMPLE / 2; z <= cell.maxZ(); z += CELL_SAMPLE) {
				Holder<Biome> biome = level.getUncachedNoiseBiome(QuartPos.fromBlock(x), quartY, QuartPos.fromBlock(z));
				if (grows.computeIfAbsent(biome.value(), StartingWood::growsTrees)) {
					return true;
				}
			}
		}
		return false;
	}

	/** The cell a candidate stands for: the {@link #CELL}-wide square it is the middle of. */
	private static Area cellOf(BlockPos candidate) {
		int minX = candidate.getX() - CELL / 2;
		int minZ = candidate.getZ() - CELL / 2;
		return new Area(minX, minZ, minX + CELL - 1, minZ + CELL - 1);
	}

	private static int horizontalDistance(BlockPos a, BlockPos b) {
		int dx = a.getX() - b.getX();
		int dz = a.getZ() - b.getZ();
		return (int) Math.sqrt((double) dx * dx + (double) dz * dz);
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
	 * The logs of each chunk the candidate search has looked at, counted once, and how many chunks
	 * that has been. Once {@code budget} chunks have been looked at, no new one is: the search is
	 * {@link #spent} and ends.
	 */
	private static final class LogCache {
		private final ServerLevel level;
		private final int budget;
		private final Map<Long, List<BlockPos>> byChunk = new HashMap<>();

		LogCache(ServerLevel level, int budget) {
			this.level = level;
			this.budget = budget;
		}

		int scanned() {
			return byChunk.size();
		}

		boolean spent() {
			return byChunk.size() >= budget;
		}

		/** Up to {@code want} logs inside {@code area}, from as many of its chunks as the budget allows. */
		List<BlockPos> in(Area area, int want) {
			List<BlockPos> found = new ArrayList<>();
			for (int chunkX = area.minX() >> 4; chunkX <= area.maxX() >> 4; chunkX++) {
				for (int chunkZ = area.minZ() >> 4; chunkZ <= area.maxZ() >> 4; chunkZ++) {
					ChunkPos chunk = new ChunkPos(chunkX, chunkZ);
					List<BlockPos> logs = byChunk.get(chunk.pack());
					if (logs == null) {
						if (spent()) {
							return found;
						}
						logs = scan(chunk);
						byChunk.put(chunk.pack(), logs);
					}
					for (BlockPos log : logs) {
						if (area.contains(log)) {
							found.add(log);
							if (found.size() >= want) {
								return found;
							}
						}
					}
				}
			}
			return found;
		}

		private List<BlockPos> scan(ChunkPos chunk) {
			int x = chunk.x() << 4;
			int z = chunk.z() << 4;
			return countLogs(level, new Area(x, z, x + 15, z + 15), new BlockPos(x, 0, z), Integer.MAX_VALUE);
		}
	}

	/** A rectangle of columns, corners included. */
	public record Area(int minX, int minZ, int maxX, int maxZ) {
		boolean contains(BlockPos pos) {
			return pos.getX() >= minX && pos.getX() <= maxX && pos.getZ() >= minZ && pos.getZ() <= maxZ;
		}

		Area intersect(Area other) {
			return new Area(Math.max(minX, other.minX), Math.max(minZ, other.minZ),
					Math.min(maxX, other.maxX), Math.min(maxZ, other.maxZ));
		}
	}

	/**
	 * Up to {@code want} logs near the top of the columns in {@code area}, looking at the chunks
	 * nearest {@code centre} first so a wooded start is answered without generating the rest.
	 *
	 * <p>Public so a GameTest can ask it about a patch it built rather than a whole run.
	 */
	public static List<BlockPos> countLogs(ServerLevel level, Area area, BlockPos centre, int want) {
		return countLogs(level, area, centre, want, Integer.MAX_VALUE);
	}

	/** The same, looking at no more than {@code maxChunks} chunks, the nearest ones. */
	static List<BlockPos> countLogs(ServerLevel level, Area area, BlockPos centre, int want, int maxChunks) {
		List<ChunkPos> chunks = new ArrayList<>();
		for (int chunkX = area.minX() >> 4; chunkX <= area.maxX() >> 4; chunkX++) {
			for (int chunkZ = area.minZ() >> 4; chunkZ <= area.maxZ() >> 4; chunkZ++) {
				chunks.add(new ChunkPos(chunkX, chunkZ));
			}
		}
		int centreX = centre.getX() >> 4;
		int centreZ = centre.getZ() >> 4;
		chunks.sort(Comparator.comparingInt((ChunkPos chunk) ->
						Math.max(Math.abs(chunk.x() - centreX), Math.abs(chunk.z() - centreZ)))
				.thenComparingInt(ChunkPos::x)
				.thenComparingInt(ChunkPos::z));

		List<BlockPos> found = new ArrayList<>();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (ChunkPos chunk : chunks.subList(0, Math.min(chunks.size(), maxChunks))) {
			// Generates the chunk if it is not there yet. Worldgen has to have answered before
			// "there is no tree" means anything.
			level.getChunk(chunk.x(), chunk.z());
			int fromX = Math.max(area.minX(), chunk.x() << 4);
			int toX = Math.min(area.maxX(), (chunk.x() << 4) + 15);
			int fromZ = Math.max(area.minZ(), chunk.z() << 4);
			int toZ = Math.min(area.maxZ(), (chunk.z() << 4) + 15);
			for (int x = fromX; x <= toX; x++) {
				for (int z = fromZ; z <= toZ; z++) {
					int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
					int bottom = Math.max(level.getMinY(), top - SEARCH_DEPTH);
					for (int y = top; y >= bottom; y--) {
						if (level.getBlockState(pos.set(x, y, z)).is(BlockTags.LOGS)) {
							found.add(pos.immutable());
							if (found.size() >= want) {
								return found;
							}
						}
					}
				}
			}
		}
		return found;
	}
}
