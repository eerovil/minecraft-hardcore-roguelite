package fi.vilpponen.mhr.run;

import fi.vilpponen.mhr.HardcoreRoguelite;
import fi.vilpponen.mhr.mixin.MinecraftServerAccessor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalLong;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.PlayerSpawnFinder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.VillageSiege;
import net.minecraft.world.entity.npc.CatSpawner;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTraderSpawner;
import net.minecraft.world.entity.raid.Raids;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.TicketStorage;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.PatrolSpawner;
import net.minecraft.world.level.levelgen.PhantomSpawner;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;

/**
 * Throwing away three dimensions and building three new ones, inside a server that keeps running.
 *
 * <p>This is the only part of the loop that reaches into Minecraft's internals, and it is kept
 * apart from everything else for that reason. {@link RunLifecycle} decides *whether* a run starts;
 * this decides *how* the worlds behind it are replaced. Nothing here knows about prices, unlocks or
 * the shop.
 *
 * <h2>Why the run is the vanilla three dimensions</h2>
 *
 * <p>A run is {@code minecraft:overworld}, {@code minecraft:the_nether} and {@code minecraft:the_end}
 * — the ordinary ones — and the persistent {@link Lobby} is the extra dimension instead of the
 * other way round. That is what keeps nether portals and end portals working with no code at all:
 * vanilla hard-codes which dimension each of them leads to, so a run's travel stays inside the
 * run's own three because there is nowhere else those portals can go. The lobby has no portals, so
 * the only way back into a run is {@link RunLifecycle#startRun}.
 *
 * <h2>What a new run actually replaces</h2>
 *
 * <p>Minecraft reads the seed off the server rather than off a level, so a genuinely different
 * world means replacing the server's {@link WorldGenSettings} before the levels are rebuilt.
 * Teleporting far away or moving the border would not do: unlocks change generation, and only
 * chunks that have never been generated can answer to the current ones.
 *
 * <p>The nether and the end own a directory each, which is deleted whole. The overworld's directory
 * *is* the save directory, so it is picked apart instead: its terrain, entities and points of
 * interest go, and so do the two pieces of saved data that belong to one run rather than to the
 * save — raids and chunk tickets. Everything else in the save root, including {@link RunStorage}'s
 * own file and the scoreboard, is left alone.
 */
final class RunWorlds {
	/** The three dimensions a run is made of, overworld first because the others derive from it. */
	static final List<ResourceKey<Level>> RUN_LEVELS = List.of(Level.OVERWORLD, Level.NETHER, Level.END);

	/** Per-dimension directories under a run dimension's own folder. */
	private static final List<String> RUN_DIRECTORIES = List.of("region", "entities", "poi");

	/**
	 * Saved data that belongs to the overworld's run rather than to the save.
	 *
	 * <p>These share a folder with the save-wide data because the overworld's dimension directory is
	 * the save directory, so they have to be named rather than wiped by folder. Both are re-read
	 * from disk by a freshly built level, so leaving them would carry raids and force-loaded chunks
	 * from a deleted world into a brand-new one.
	 */
	private static final List<SavedDataType<?>> RUN_SAVED_DATA = List.of(Raids.TYPE, TicketStorage.TYPE);

	private RunWorlds() {
	}

	/** A seed for the next run. Public so the dev command and the tests can ask for a specific one. */
	static long randomSeed() {
		return WorldOptions.randomSeed();
	}

	/**
	 * Replace the three run dimensions with empty ones generated from {@code seed}.
	 *
	 * <p>Must be called on the server thread with no player inside any of the three: between the
	 * teardown and the rebuild, {@code server.overworld()} does not exist.
	 *
	 * @return the run's new overworld
	 */
	static ServerLevel recreate(MinecraftServer server, long seed) {
		useSeed(server, seed);
		unload(server);
		deleteFiles(server);
		return build(server);
	}

	private static void useSeed(MinecraftServer server, long seed) {
		WorldGenSettings current = server.getWorldGenSettings();
		WorldGenSettings next =
				new WorldGenSettings(current.options().withSeed(OptionalLong.of(seed)), current.dimensions());
		((MinecraftServerAccessor) server).mhr$setWorldGenSettings(next);
		server.getDataStorage().set(WorldGenSettings.TYPE, next);
		next.setDirty();
	}

	private static void unload(MinecraftServer server) {
		Map<ResourceKey<Level>, ServerLevel> levels = ((MinecraftServerAccessor) server).mhr$levels();
		for (ResourceKey<Level> key : RUN_LEVELS) {
			ServerLevel level = levels.remove(key);
			if (level == null) {
				continue;
			}
			// Nothing is saved on the way out: every chunk of it is about to be deleted, and
			// writing it first would only mean deleting a larger file.
			level.noSave = true;
			try {
				level.close();
			} catch (IOException e) {
				HardcoreRoguelite.LOGGER.error("Could not close {} while ending a run", key.identifier(), e);
			}
		}
	}

	private static void deleteFiles(MinecraftServer server) {
		LevelStorageSource.LevelStorageAccess storage = ((MinecraftServerAccessor) server).mhr$storageSource();
		Path saveRoot = storage.getDimensionPath(Level.OVERWORLD);

		for (ResourceKey<Level> key : RUN_LEVELS) {
			Path directory = storage.getDimensionPath(key);
			if (directory.equals(saveRoot)) {
				// The overworld has no folder of its own: it is the save. Take only what is its own.
				for (String name : RUN_DIRECTORIES) {
					deleteRecursively(directory.resolve(name));
				}
				for (SavedDataType<?> type : RUN_SAVED_DATA) {
					delete(type.id().withSuffix(".dat").resolveAgainst(directory.resolve("data")));
				}
			} else {
				deleteRecursively(directory);
			}
		}
	}

	private static void delete(Path path) {
		try {
			Files.deleteIfExists(path);
		} catch (IOException e) {
			HardcoreRoguelite.LOGGER.error("Could not delete {} from the finished run", path, e);
		}
	}

	private static void deleteRecursively(Path root) {
		if (!Files.exists(root)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(root)) {
			// Deepest first, so a directory is always empty by the time it is reached.
			for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		} catch (IOException e) {
			HardcoreRoguelite.LOGGER.error("Could not delete {} from the finished run", root, e);
		}
	}

	/**
	 * Build the three levels again, the way {@code MinecraftServer.createLevels} does.
	 *
	 * <p>Deliberately the same shape as vanilla's version, including the overworld's custom spawners
	 * and the derived level data the other two share with it, so that a run world is an ordinary
	 * world in every respect a feature could notice.
	 */
	private static ServerLevel build(MinecraftServer server) {
		MinecraftServerAccessor access = (MinecraftServerAccessor) server;
		Map<ResourceKey<Level>, ServerLevel> levels = access.mhr$levels();
		LevelStorageSource.LevelStorageAccess storage = access.mhr$storageSource();

		ServerLevelData overworldData = server.getWorldData().overworldData();
		boolean isDebug = server.getWorldData().isDebugWorld();
		Registry<LevelStem> stems = server.registryAccess().lookupOrThrow(Registries.LEVEL_STEM);
		long seed = server.getWorldGenSettings().options().seed();
		long biomeZoomSeed = BiomeManager.obfuscateSeed(seed);

		List<CustomSpawner> overworldSpawners = List.of(
				new PhantomSpawner(),
				new PatrolSpawner(),
				new CatSpawner(),
				new VillageSiege(),
				new WanderingTraderSpawner(server.getDataStorage()));

		ServerLevel overworld = new ServerLevel(server, access.mhr$executor(), storage, overworldData,
				Level.OVERWORLD, stems.getValue(LevelStem.OVERWORLD), isDebug, biomeZoomSeed,
				overworldSpawners, true);
		levels.put(Level.OVERWORLD, overworld);
		adopt(server, overworld);

		for (ResourceKey<Level> key : RUN_LEVELS) {
			if (key.equals(Level.OVERWORLD)) {
				continue;
			}
			ResourceKey<LevelStem> stemKey = ResourceKey.create(Registries.LEVEL_STEM, key.identifier());
			LevelStem stem = stems.getValue(stemKey);
			if (stem == null) {
				HardcoreRoguelite.LOGGER.error("No level stem for {}; this run has no such dimension",
						key.identifier());
				continue;
			}
			ServerLevel level = new ServerLevel(server, access.mhr$executor(), storage,
					new DerivedLevelData(server.getWorldData(), overworldData), key, stem, isDebug,
					biomeZoomSeed, List.of(), false);
			levels.put(key, level);
			adopt(server, level);
		}

		chooseSpawn(server, overworld);
		return overworld;
	}

	private static void adopt(MinecraftServer server, ServerLevel level) {
		level.getWorldBorder().setAbsoluteMaxSize(server.getAbsoluteMaxWorldSize());
		server.getPlayerList().addWorldborderListener(level);
	}

	/**
	 * Find somewhere in the new overworld worth arriving at.
	 *
	 * <p>Vanilla only does this for a world that has never been initialised, and by the time a
	 * second run starts the save has been initialised for a long time — so the spawn has to be
	 * recomputed here or every run after the first would begin at the first run's coordinates, in
	 * terrain that no longer exists.
	 *
	 * <p>The same spiral as vanilla's, over a smaller area: a handful of chunks around the
	 * generator's origin, falling back to the surface height there when none of them offers a
	 * standable spot.
	 */
	private static void chooseSpawn(MinecraftServer server, ServerLevel overworld) {
		ServerChunkCache chunks = overworld.getChunkSource();
		ChunkPos origin = chunks.getGenerator().getOrigin(chunks.randomState());

		BlockPos found = null;
		for (ChunkPos candidate : spiral(origin, 2)) {
			found = PlayerSpawnFinder.getSpawnPosInChunk(overworld, candidate);
			if (found != null) {
				break;
			}
		}
		if (found == null) {
			BlockPos corner = origin.getWorldPosition();
			int height = Math.max(chunks.getGenerator().getSpawnHeight(overworld), overworld.getMinY() + 1);
			found = new BlockPos(corner.getX() + 8, height, corner.getZ() + 8);
		}

		// setRespawnData, and only setRespawnData: it writes the level data, tells the clients and
		// recomputes the server's effective spawn — and it short-circuits when the value has not
		// changed, so setting the level data first would leave the effective spawn pointing at the
		// last run's coordinates.
		server.setRespawnData(LevelData.RespawnData.of(Level.OVERWORLD, found, 0.0F, 0.0F));
		HardcoreRoguelite.LOGGER.info("Run overworld spawn is {} {} {} (seed {})",
				found.getX(), found.getY(), found.getZ(), overworld.getSeed());
	}

	/** The chunks within {@code radius} of {@code centre}, nearest first. */
	private static List<ChunkPos> spiral(ChunkPos centre, int radius) {
		return IntStream.rangeClosed(-radius, radius)
				.boxed()
				.flatMap(dx -> IntStream.rangeClosed(-radius, radius)
						.mapToObj(dz -> new ChunkPos(centre.x() + dx, centre.z() + dz)))
				.sorted(Comparator.comparingInt(pos ->
						Math.abs(pos.x() - centre.x()) + Math.abs(pos.z() - centre.z())))
				.toList();
	}
}
