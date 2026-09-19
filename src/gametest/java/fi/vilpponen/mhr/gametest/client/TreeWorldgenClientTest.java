package fi.vilpponen.mhr.gametest.client;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The half of the trees unlock that only real terrain can answer: land generated from scratch comes
 * out with no trees in it, and comes back with trees once the unlock is bought.
 *
 * <p>{@link fi.vilpponen.mhr.gametest.TreeUnlockGameTest} covers the fast, targeted half on the
 * plain server. It cannot cover this one, because Fabric's server GameTests run on a superflat
 * world that has no tree worldgen to suppress in the first place. So this test builds a dedicated
 * server on a *normal* overworld — the only lever in the harness that produces one — walks out to a
 * forest nobody has been to, force-loads it, and counts the logs.
 *
 * <p>Two different forests are used, one on each side of the unlock, because worldgen only applies
 * to land generated after the change: scanning the same patch twice would answer "no trees" both
 * times and look exactly like the feature working.
 *
 * <p>See {@code docs/dev-environment.md} for how to run this.
 */
public class TreeWorldgenClientTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("mhr-gametest");

	/** How far out from the middle of the patch to generate, in chunks. 5×5 is plenty of forest. */
	private static final int RADIUS_IN_CHUNKS = 2;

	/** The slice of the world worth searching. Forest canopy sits well inside this. */
	private static final int SCAN_FROM_Y = 50;
	private static final int SCAN_TO_Y = 150;

	/**
	 * Where each half of the fresh-chunk check goes looking for its forest. Thousands of blocks out
	 * and in opposite directions, so neither patch is land the server has already made, and the two
	 * are nowhere near each other.
	 */
	private static final BlockPos LOCKED_SEARCH_FROM = new BlockPos(-6000, 64, -6000);
	private static final BlockPos UNLOCKED_SEARCH_FROM = new BlockPos(6000, 64, 6000);

	/** How far the biome search may wander, in blocks. */
	private static final int SEARCH_RADIUS = 6400;

	private final List<String> failures = new ArrayList<>();

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestDedicatedServerContext server = context.worldBuilder()
				.adjustSettings(TreeWorldgenClientTest::useNormalTerrain)
				.createServer()) {
			try (TestDedicatedServerConnection connection = server.connect()) {
				connection.waitForChunksRender();

				// A run starts on the tiny border tier, and land outside the border is not the
				// question being asked here. Widen it before generating anything.
				server.runCommand("mhr border infinite");
				server.runCommand("time set noon");
				server.runCommand("weather clear");

				scenario(context, "the-world-a-run-starts-in-has-no-trees",
						() -> theWorldARunStartsInHasNoTrees(server));
				scenario(context, "fresh-land-has-no-trees-while-locked",
						() -> freshLandHasNoTreesWhileLocked(context, server, connection));
				scenario(context, "fresh-land-has-trees-once-unlocked",
						() -> freshLandHasTreesOnceUnlocked(context, server, connection));
			}
		}

		if (!failures.isEmpty()) {
			throw new AssertionError(failures.size() + " tree-worldgen scenario(s) failed:\n  "
					+ String.join("\n  ", failures));
		}
		LOGGER.info("All tree-worldgen scenarios passed.");
	}

	// --- the scenarios ---------------------------------------------------------------------

	/**
	 * The world a run actually begins in. Nothing is force-loaded here: this is the land the server
	 * made around spawn on its own, before anything asked it a question, which is exactly what a
	 * player sees on their first morning.
	 *
	 * <p>The ground count is the control: the unlock withholds trees, not terrain, so a patch with
	 * no ground in it would make "no logs" mean nothing.
	 */
	private void theWorldARunStartsInHasNoTrees(TestDedicatedServerContext server) {
		server.runCommand("mhr lock world.trees");

		BlockPos spawn = server.computeOnServer(minecraftServer ->
				minecraftServer.getWorldData().overworldData().getRespawnData().pos());
		int logs = count(server, spawn, BlockTags.LOGS);
		int ground = count(server, spawn, BlockTags.DIRT);
		LOGGER.info("Spawn at {}: {} logs, {} ground blocks", spawn, logs, ground);

		check(ground > 0, "the land around spawn at " + spawn + " is empty, so counting zero logs"
				+ " there would prove nothing");
		check(logs == 0, "the world a run starts in must have no logs in it, but there are "
				+ logs + " around spawn at " + spawn);
	}

	/**
	 * Land nobody has been to, made on the spot while the unlock is missing, has no wood in it.
	 *
	 * <p>Thousands of blocks from spawn on purpose: these chunks did not exist until this scenario
	 * asked for them, so what is being checked is the worldgen path and not a world that happened
	 * to be made earlier.
	 */
	private void freshLandHasNoTreesWhileLocked(ClientGameTestContext context,
			TestDedicatedServerContext server, TestDedicatedServerConnection connection) {
		server.runCommand("mhr lock world.trees");

		BlockPos forest = findForest(server, LOCKED_SEARCH_FROM);
		generate(server, forest);
		int logs = count(server, forest, BlockTags.LOGS);
		int ground = count(server, forest, BlockTags.DIRT);
		LOGGER.info("Locked forest at {}: {} logs, {} ground blocks", forest, logs, ground);

		check(ground > 0, "the patch at " + forest + " did not generate at all: no ground in it,"
				+ " so counting zero logs there would prove nothing");
		check(logs == 0, "fresh land generated while world.trees is locked must have no logs in it,"
				+ " but the patch at " + forest + " has " + logs);

		look(context, server, connection, forest, "fresh-forest-trees-locked");
	}

	/** Buy the unlock, walk to land nobody has generated yet, and the trees are back. */
	private void freshLandHasTreesOnceUnlocked(ClientGameTestContext context,
			TestDedicatedServerContext server, TestDedicatedServerConnection connection) {
		server.runCommand("mhr unlock world.trees");

		BlockPos forest = findForest(server, UNLOCKED_SEARCH_FROM);
		generate(server, forest);
		int logs = count(server, forest, BlockTags.LOGS);
		LOGGER.info("Unlocked forest at {}: {} logs", forest, logs);

		check(logs > 0, "fresh land generated after world.trees is unlocked must have trees in it,"
				+ " but the patch at " + forest + " has no logs");

		look(context, server, connection, forest, "fresh-forest-trees-unlocked");
	}

	// --- the world -------------------------------------------------------------------------

	/**
	 * Turns the harness's superflat test world into an ordinary one.
	 *
	 * <p>Everything else the harness fixes for repeatability — the seed, the frozen clock and
	 * weather — is left alone, so the same two forests turn up on every run.
	 */
	private static void useNormalTerrain(WorldCreationUiState state) {
		state.setWorldType(new WorldCreationUiState.WorldTypeEntry(
				state.getSettings().worldgenLoadContext()
						.lookupOrThrow(Registries.WORLD_PRESET)
						.getOrThrow(WorldPresets.NORMAL)));
	}

	/**
	 * The middle of the nearest plain oak forest to a point, without generating anything to find it.
	 *
	 * <p>Plain {@code minecraft:forest} and nothing looser. A biome tag would also match places like
	 * a mushroom island, which has no trees in vanilla either — and "no logs in a biome that never
	 * had any" is not evidence of anything.
	 */
	private static BlockPos findForest(TestDedicatedServerContext server, BlockPos from) {
		BlockPos found = server.computeOnServer(minecraftServer -> {
			var nearest = minecraftServer.overworld().findClosestBiome3d(
					biome -> biome.is(Biomes.FOREST), from, SEARCH_RADIUS, 32, 64);
			return nearest == null ? null : nearest.getFirst();
		});
		if (found == null) {
			throw new AssertionError("No " + Biomes.FOREST + " within " + SEARCH_RADIUS
					+ " blocks of " + from + ", so there is nowhere to look for trees");
		}
		return found;
	}

	/**
	 * Generates the patch around a point and waits for every chunk of it to be there.
	 *
	 * <p>Force-loading rather than walking in: the point is that this land has never existed
	 * before, and a chunk the server has to make on the spot is exactly the worldgen path the
	 * unlock acts on.
	 */
	private static void generate(TestDedicatedServerContext server, BlockPos middle) {
		int centreChunkX = middle.getX() >> 4;
		int centreChunkZ = middle.getZ() >> 4;
		server.runCommand("forceload add "
				+ ((centreChunkX - RADIUS_IN_CHUNKS) << 4) + " "
				+ ((centreChunkZ - RADIUS_IN_CHUNKS) << 4) + " "
				+ ((((centreChunkX + RADIUS_IN_CHUNKS) << 4) + 15)) + " "
				+ ((((centreChunkZ + RADIUS_IN_CHUNKS) << 4) + 15)));
		server.waitFor(minecraftServer -> {
			ServerLevel level = minecraftServer.overworld();
			for (int x = -RADIUS_IN_CHUNKS; x <= RADIUS_IN_CHUNKS; x++) {
				for (int z = -RADIUS_IN_CHUNKS; z <= RADIUS_IN_CHUNKS; z++) {
					if (level.getChunkSource().getChunkNow(centreChunkX + x, centreChunkZ + z) == null) {
						return false;
					}
				}
			}
			return true;
		});
	}

	/** Counts the blocks in the generated patch that carry a tag. */
	private static int count(TestDedicatedServerContext server, BlockPos middle, TagKey<Block> tag) {
		int centreChunkX = middle.getX() >> 4;
		int centreChunkZ = middle.getZ() >> 4;
		return server.computeOnServer(minecraftServer -> {
			ServerLevel level = minecraftServer.overworld();
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
			int found = 0;
			for (int chunkX = -RADIUS_IN_CHUNKS; chunkX <= RADIUS_IN_CHUNKS; chunkX++) {
				for (int chunkZ = -RADIUS_IN_CHUNKS; chunkZ <= RADIUS_IN_CHUNKS; chunkZ++) {
					LevelChunk chunk = level.getChunkSource()
							.getChunkNow(centreChunkX + chunkX, centreChunkZ + chunkZ);
					if (chunk == null) {
						throw new AssertionError("Chunk " + (centreChunkX + chunkX) + ","
								+ (centreChunkZ + chunkZ) + " went away before it could be counted");
					}
					for (int x = 0; x < 16; x++) {
						for (int z = 0; z < 16; z++) {
							for (int y = SCAN_FROM_Y; y <= SCAN_TO_Y; y++) {
								pos.set(((centreChunkX + chunkX) << 4) + x, y,
										((centreChunkZ + chunkZ) << 4) + z);
								if (chunk.getBlockState(pos).is(tag)) {
									found++;
								}
							}
						}
					}
				}
			}
			return found;
		});
	}

	/**
	 * Puts the player where they can see the patch and photographs it.
	 *
	 * <p>The counts above are the proof. This is so a human can see what the counts are describing,
	 * and so a failure has a picture attached to it.
	 */
	private static void look(ClientGameTestContext context, TestDedicatedServerContext server,
			TestDedicatedServerConnection connection, BlockPos middle, String name) {
		int surface = server.computeOnServer(minecraftServer -> minecraftServer.overworld()
				.getHeight(Heightmap.Types.MOTION_BLOCKING, middle.getX(), middle.getZ()));
		// Spectator, or the player simply falls out of the sky and the picture is of whatever they
		// landed in. A spectator stays exactly where they are put.
		server.runCommand("gamemode spectator Player0");
		server.runCommand("tp Player0 " + middle.getX() + " " + (surface + 22) + " "
				+ (middle.getZ() - 28) + " 0 22");
		connection.waitForChunksRender();
		context.waitTicks(20);
		context.takeScreenshot(name);
	}

	// --- plumbing --------------------------------------------------------------------------

	/**
	 * Runs one scenario. A failure is recorded and photographed rather than ending the run, so one
	 * command shows every criterion that is red instead of only the first.
	 */
	private void scenario(ClientGameTestContext context, String name, Runnable body) {
		LOGGER.info("=== scenario {} ===", name);
		try {
			body.run();
			LOGGER.info("=== scenario {}: PASS ===", name);
		} catch (Throwable failure) {
			failures.add(name + ": " + failure.getMessage());
			LOGGER.error("=== scenario {}: FAIL === {}", name, failure.getMessage(), failure);
			try {
				context.takeScreenshot("failed-" + name);
			} catch (Throwable ignored) {
				LOGGER.warn("Could not screenshot the failure of {}", name);
			}
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
