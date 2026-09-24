package fi.vilpponen.mhr.border;

import fi.vilpponen.mhr.HardcoreRoguelite;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.PlayerSpawnFinder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.levelgen.Heightmap;
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
 *   <li><b>Moved.</b> It is not, so wooded biomes nearby are looked up from the biome map, the way
 *       {@code /locate biome} does, nearest first. The first one whose actual land has enough wood
 *       becomes the start: the run's spawn moves there and the border is centered on it.
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
	 * generate thousands of chunks before giving up; past it, the wooded biomes inside the border are
	 * counted instead, from the biome map.
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

	/** How far apart the biome map is sampled, in blocks. */
	private static final int BIOME_STEP = 32;

	/**
	 * A candidate this close to one already turned down is skipped, so each look covers new land
	 * rather than the edge of the same unlucky patch.
	 */
	private static final int CANDIDATE_SPACING = 96;

	/** How many candidates to try before giving up. Each one generates some land to count in. */
	private static final int MAX_CANDIDATES = 8;

	/** How far around a candidate its wood is counted before the spawn is moved to it, in blocks. */
	private static final int GROVE_RADIUS = 32;

	/**
	 * What {@link #ensure} did. {@code log} is a log it counted, {@code spawn} where the run's spawn
	 * ended up, and {@code from} where it was before — the same unless the outcome is
	 * {@link Outcome#MOVED}.
	 */
	public record Result(Outcome outcome, BlockPos log, BlockPos spawn, BlockPos from) {
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
			result = new Result(Outcome.UNBOUNDED, null, spawn, spawn);
		} else {
			result = ensureBounded(server, overworld, spawn);
		}
		last = result;
		switch (result.outcome()) {
			case FOUND -> HardcoreRoguelite.LOGGER.info("Starting border already has wood, e.g. at {}",
					describe(result.log()));
			case MOVED -> HardcoreRoguelite.LOGGER.info(
					"Not enough wood inside the starting border, so the run's spawn moved from {} to {}, next to trees at {}",
					describe(spawn), describe(result.spawn()), describe(result.log()));
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
			return new Result(Outcome.FOUND, here.get(0), spawn, spawn);
		}

		List<BlockPos> tried = new ArrayList<>();
		for (BlockPos candidate : woodedCandidates(overworld, spawn)) {
			if (tried.size() >= MAX_CANDIDATES) {
				break;
			}
			if (tried.stream().anyMatch(other -> horizontalDistance(other, candidate) < CANDIDATE_SPACING)) {
				continue;
			}
			tried.add(candidate);

			// A wooded biome is a promise of trees, not a tree, so its land is counted before anything
			// moves. Only this little patch is generated to find out.
			Area grove = new Area(candidate.getX() - GROVE_RADIUS, candidate.getZ() - GROVE_RADIUS,
					candidate.getX() + GROVE_RADIUS, candidate.getZ() + GROVE_RADIUS);
			if (border.contains(candidate)) {
				// Inside the border already, past where the first look stopped: trees here mean the
				// start is fine as it is, and moving would only take the player away from them.
				List<BlockPos> inside = countLogs(overworld, border.intersect(grove), candidate, VIABLE_LOGS,
						Integer.MAX_VALUE);
				if (inside.size() >= VIABLE_LOGS) {
					return new Result(Outcome.FOUND, inside.get(0), spawn, spawn);
				}
				continue;
			}
			List<BlockPos> groveLogs = countLogs(overworld, grove, candidate, VIABLE_LOGS);
			if (groveLogs.size() < VIABLE_LOGS) {
				continue;
			}

			BlockPos moved = standingSpotNear(overworld, groveLogs.get(0));
			moveSpawn(server, moved);
			// Counted again inside the border the move produced, rather than assumed: the border has
			// to hold the trees, not just the spawn.
			List<BlockPos> inside = countLogs(overworld, reachable(overworld), moved, VIABLE_LOGS,
					MAX_SCAN_CHUNKS);
			if (inside.size() >= VIABLE_LOGS) {
				return new Result(Outcome.MOVED, inside.get(0), moved, spawn);
			}
		}

		if (!spawnOf(server).equals(spawn)) {
			moveSpawn(server, spawn);
		}
		return new Result(Outcome.NONE, null, spawn, spawn);
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
	 * Every spot within {@link #MOVE_RADIUS} of spawn whose biome grows trees, nearest first.
	 *
	 * <p>Read from the biome map alone, the way {@code /locate biome} reads it, so nothing is
	 * generated to make the list. Ties are broken by position, so the order depends on the world and
	 * nothing else.
	 */
	private static List<BlockPos> woodedCandidates(ServerLevel level, BlockPos spawn) {
		int quartY = QuartPos.fromBlock(level.getSeaLevel());
		List<BlockPos> candidates = new ArrayList<>();
		for (int dx = -MOVE_RADIUS; dx <= MOVE_RADIUS; dx += BIOME_STEP) {
			for (int dz = -MOVE_RADIUS; dz <= MOVE_RADIUS; dz += BIOME_STEP) {
				if (dx * dx + dz * dz > MOVE_RADIUS * MOVE_RADIUS) {
					continue;
				}
				int x = spawn.getX() + dx;
				int z = spawn.getZ() + dz;
				Holder<Biome> biome = level.getUncachedNoiseBiome(QuartPos.fromBlock(x), quartY, QuartPos.fromBlock(z));
				if (isWooded(biome)) {
					candidates.add(new BlockPos(x, level.getSeaLevel(), z));
				}
			}
		}
		candidates.sort(Comparator.comparingInt((BlockPos pos) -> horizontalDistance(pos, spawn))
				.thenComparingInt(BlockPos::getX)
				.thenComparingInt(BlockPos::getZ));
		return candidates;
	}

	private static boolean isWooded(Holder<Biome> biome) {
		return biome.is(BiomeTags.IS_FOREST) || biome.is(BiomeTags.IS_TAIGA)
				|| biome.is(BiomeTags.IS_JUNGLE) || biome.is(BiomeTags.IS_SAVANNA);
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
