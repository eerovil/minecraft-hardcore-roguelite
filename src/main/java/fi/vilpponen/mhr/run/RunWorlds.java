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
import net.minecraft.world.RandomSequences;
import net.minecraft.world.entity.ai.village.VillageSiege;
import net.minecraft.world.entity.npc.CatSpawner;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTraderSpawner;
import net.minecraft.world.entity.raid.Raids;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.Level;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.level.TicketStorage;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.PatrolSpawner;
import net.minecraft.world.level.levelgen.PhantomSpawner;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.saveddata.WanderingTraderData;
import net.minecraft.world.level.saveddata.WeatherData;
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

	/**
	 * Every clock the game keeps, all of which measure one run.
	 *
	 * <p>Named rather than walked over the registry, because a clock a datapack adds is not
	 * necessarily one this mod is entitled to reset.
	 */
	private static final List<ResourceKey<WorldClock>> RUN_CLOCKS =
			List.of(WorldClocks.OVERWORLD, WorldClocks.THE_END);

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
		requireEveryStem(server);
		useSeed(server, seed);
		IOException leftovers = firstOf(unload(server), deleteFiles(server));

		// Before the rebuild, because this is what the new levels read as they are constructed.
		resetServerRunState(server);

		// Rebuilt whatever happened. The server has to have an overworld before anything else runs,
		// including the code that is about to abandon this run.
		ServerLevel overworld = build(server);

		// After the rebuild, because setting a clock tells the connected players, and telling them
		// reads the game rules off the overworld — which does not exist between the two.
		resetClocks(server);

		if (leftovers != null) {
			throw new IllegalStateException("the last run's worlds could not be closed and deleted,"
					+ " so what has just been built is not a fresh run and must not be played as one",
					leftovers);
		}
		return overworld;
	}

	/**
	 * Refuse before anything is destroyed, if this save cannot describe all three run dimensions.
	 *
	 * <p>A {@link LevelStem} is the recipe a dimension is built from, and all three of a run's come
	 * out of the save's own registry. One can be missing — a world preset that does not define it,
	 * a datapack that has been removed since the save was made — and that is a save this mod cannot
	 * run: a run is the overworld, the nether and the end, and the portals between them are vanilla
	 * code pointing at fixed dimension keys with nowhere else to go.
	 *
	 * <p>The check has to be here, at the top, and the reason is the ordering rather than the
	 * condition. It used to be a null test inside the rebuild, which was too late to act on: by
	 * then the seed had been replaced and the old run's worlds were closed and deleted, so the only
	 * choices left were to carry on without a dimension or to stop with nothing to go back to. It
	 * logged and carried on, and {@code startRun} then wrote the run down as playable with a
	 * missing third of it. Asked before the first destructive step, the honest answer is available:
	 * nothing has happened yet, so nothing has to be undone.
	 *
	 * <p>Called twice on the way into a run, and both are wanted. {@link RunLifecycle#startRun}
	 * asks before it writes the record, so a refused start does not even spend a run id — the save
	 * is left byte for byte as it was. {@code recreate} asks again as its own first act, so the
	 * guarantee belongs to this class rather than to whoever remembered to check.
	 *
	 * @throws IllegalStateException naming the dimension, before a single file is touched
	 */
	static void requireEveryStem(MinecraftServer server) {
		Registry<LevelStem> stems = server.registryAccess().lookupOrThrow(Registries.LEVEL_STEM);
		for (ResourceKey<Level> key : RUN_LEVELS) {
			if (stems.getValue(stemKeyFor(key)) == null) {
				throw new IllegalStateException("this save has no " + key.identifier()
						+ " dimension to build, and a run is all three of them. Nothing has been"
						+ " deleted. Is a data pack or world preset missing?");
			}
		}
	}

	private static ResourceKey<LevelStem> stemKeyFor(ResourceKey<Level> key) {
		return ResourceKey.create(Registries.LEVEL_STEM, key.identifier());
	}

	/**
	 * Put back the run state Minecraft keeps on the server rather than in the levels.
	 *
	 * <p>Replacing the three {@link ServerLevel} objects is not the whole of a fresh run, because
	 * since 26.1 a good deal of what one run accumulates does not live in a level at all. It lives
	 * on {@link MinecraftServer}, in {@code <save>/data/}, and it would have carried straight
	 * through: run 2 opening in run 1's thunderstorm, at run 1's time of day, with a trader due in
	 * a hundred ticks and the loot tables carrying on the sequence run 1 left them in.
	 *
	 * <h2>Which server state is one run's, and which is the save's</h2>
	 *
	 * <p>This was found a piece at a time, so here is the rule instead of another list. Something
	 * belongs to <b>one run</b> when ordinary play inside a run is what produces it. Something
	 * belongs to <b>the save</b> when only an operator, a datapack or this mod's own progression
	 * can have made it — because then the lobby may own it just as easily as the run did, and this
	 * class has no way to tell which. A roguelite run boundary is not a save wipe.
	 *
	 * <p>Per run, and reset here:
	 *
	 * <ul>
	 *   <li>{@link WeatherData} — rain and thunder countdowns, which a run rolls through as it is
	 *       played.
	 *   <li>{@link WanderingTraderData} — the spawn delay and chance, which ratchet over a
	 *       playthrough.
	 *   <li>{@link RandomSequences} — the named loot-table random streams. These advance every time
	 *       somebody opens a chest, and the state is stored rather than the seed, so a new world
	 *       seed does <em>not</em> reseed a sequence that already exists. Left alone, run 2's first
	 *       chest continues run 1's roll.
	 *   <li>The world clocks, in {@link #resetClocks}, which have to wait for the rebuild.
	 * </ul>
	 *
	 * <p>Per save, deliberately left alone: game rules (server-global in 26.3 and shared with the
	 * lobby), the scoreboard, custom boss bars, stopwatches, command storage and scheduled events —
	 * every one of those exists only because somebody ran a command or shipped a datapack, and none
	 * of them can be shown to have been for the run rather than the lobby. Also left: the structure
	 * template library, the save's version and brand metadata, and permanent progression, which is
	 * the whole point of the loop.
	 *
	 * <p>One known gap, listed rather than guessed at: filled maps and their id counter are
	 * server-global too, so a map drawn in run 1 still holds run 1's terrain. Nothing can reference
	 * one afterwards — map items live in inventories and ender chests, and both are emptied at the
	 * player boundary — and there is no cache-invalidation API on the saved-data storage to drop
	 * the orphaned files with. It is written up in {@code docs/codebase/run-lifecycle.md}.
	 *
	 * <p>This half runs before the levels are rebuilt, because that is when they read it: a new
	 * {@code ServerLevel} takes the weather in its constructor, and the overworld's wandering
	 * trader spawner reads its own saved data as it is built. The clocks are the other way round —
	 * see {@link #resetClocks} — which is why they are not in here.
	 */
	private static void resetServerRunState(MinecraftServer server) {
		// Mutated rather than replaced: the server holds this object for its lifetime, so putting a
		// fresh one in the data storage would leave it using the old one.
		WeatherData weather = server.getWeatherData();
		weather.setRaining(false);
		weather.setRainTime(0);
		weather.setThundering(false);
		weather.setThunderTime(0);
		weather.setClearWeatherTime(0);
		weather.setDirty();

		// Read fresh by the spawner that build() is about to construct, so replacing it is enough.
		// SavedDataStorage.set marks what it is given dirty, so this reaches the disk on its own —
		// which is worth saying out loud, because the two resets around it do not get that for free.
		server.getDataStorage().set(WanderingTraderData.TYPE, new WanderingTraderData());

		// The server keeps this one in a final field, so it has to be emptied in place rather than
		// replaced. And emptying it does not mark it dirty — vanilla only ever clears sequences in
		// a running world that goes on to re-roll them, and re-rolling is what marks it. A server
		// stopped between the reset and the next chest would write the old sequences back out.
		RandomSequences sequences = server.getRandomSequences();
		sequences.clear();
		sequences.setDirty();
	}

	/** Back to the first morning, for every clock the game has. */
	private static void resetClocks(MinecraftServer server) {
		ServerClockManager clocks = server.clockManager();
		for (ResourceKey<WorldClock> clock : RUN_CLOCKS) {
			clocks.setTotalTicks(server.registryAccess().getOrThrow(clock), 0L);
		}
	}

	private static void useSeed(MinecraftServer server, long seed) {
		WorldGenSettings current = server.getWorldGenSettings();
		WorldGenSettings next =
				new WorldGenSettings(current.options().withSeed(OptionalLong.of(seed)), current.dimensions());
		((MinecraftServerAccessor) server).mhr$setWorldGenSettings(next);
		server.getDataStorage().set(WorldGenSettings.TYPE, next);
		next.setDirty();
	}

	/**
	 * Take the three run levels off the server and close them.
	 *
	 * <p>A close that fails is reported rather than logged and stepped over, for the same reason a
	 * failed delete is: it means the old run's files may still be held open, and deleting and
	 * rebuilding underneath that is exactly the half-cleaned state the loop is supposed to refuse.
	 *
	 * @return the first failure, with any others suppressed under it, or null if everything closed
	 */
	private static IOException unload(MinecraftServer server) {
		Map<ResourceKey<Level>, ServerLevel> levels = ((MinecraftServerAccessor) server).mhr$levels();
		IOException failure = null;

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
				failure = firstOf(failure, e);
			}
		}
		return failure;
	}

	/**
	 * Delete everything the finished run owned.
	 *
	 * <p>Every failure is reported rather than logged and stepped over. A run world that could not
	 * be deleted is a run world the next one would generate on top of, which is the one thing a
	 * fresh run must not be, so the caller has to hear about it.
	 *
	 * @return the first failure, with any others suppressed under it, or null if everything went
	 */
	private static IOException deleteFiles(MinecraftServer server) {
		LevelStorageSource.LevelStorageAccess storage = ((MinecraftServerAccessor) server).mhr$storageSource();
		Path saveRoot = storage.getDimensionPath(Level.OVERWORLD);
		IOException failure = null;

		for (ResourceKey<Level> key : RUN_LEVELS) {
			Path directory = storage.getDimensionPath(key);
			if (directory.equals(saveRoot)) {
				// The overworld has no folder of its own: it is the save. Take only what is its own.
				for (String name : RUN_DIRECTORIES) {
					failure = firstOf(failure, deleteRecursively(directory.resolve(name)));
				}
				for (SavedDataType<?> type : RUN_SAVED_DATA) {
					failure = firstOf(failure,
							delete(type.id().withSuffix(".dat").resolveAgainst(directory.resolve("data"))));
				}
			} else {
				failure = firstOf(failure, deleteRecursively(directory));
			}
		}
		return failure;
	}

	private static IOException firstOf(IOException kept, IOException next) {
		if (kept == null) {
			return next;
		}
		if (next != null) {
			kept.addSuppressed(next);
		}
		return kept;
	}

	private static IOException delete(Path path) {
		try {
			Files.deleteIfExists(path);
			return null;
		} catch (IOException e) {
			HardcoreRoguelite.LOGGER.error("Could not delete {} from the finished run", path, e);
			return e;
		}
	}

	private static IOException deleteRecursively(Path root) {
		if (!Files.exists(root)) {
			return null;
		}
		try (Stream<Path> walk = Files.walk(root)) {
			// Deepest first, so a directory is always empty by the time it is reached.
			for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		} catch (IOException e) {
			HardcoreRoguelite.LOGGER.error("Could not delete {} from the finished run", root, e);
			return e;
		}

		// Walking can finish without throwing and still leave something behind — a directory that
		// refused, or a file written under us. Deleted means nothing is left.
		if (Files.exists(root)) {
			IOException left = new IOException(root + " is still there after deleting it");
			HardcoreRoguelite.LOGGER.error("Could not delete {} from the finished run", root, left);
			return left;
		}
		return null;
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
			// Present, because recreate() refused the whole start if it was not — which is the only
			// point at which a missing one can still be acted on. Skipping it here would build a
			// run with two thirds of its dimensions and let the caller call that playable.
			LevelStem stem = stems.getValue(stemKeyFor(key));
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
