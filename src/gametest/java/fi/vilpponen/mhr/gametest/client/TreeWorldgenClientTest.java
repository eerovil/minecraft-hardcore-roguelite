package fi.vilpponen.mhr.gametest.client;

import fi.vilpponen.mhr.UnlockState;
import fi.vilpponen.mhr.border.StartingWood;
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
 * Trees on real terrain, with nothing bought: a run starts in a world with vanilla trees in it, and
 * land generated from scratch has them too.
 *
 * <p>{@link fi.vilpponen.mhr.gametest.TreeGameTest} covers the fast, targeted half on the plain
 * server. It cannot cover this one, because Fabric's server GameTests run on a superflat world that
 * has no tree worldgen in the first place. So this test builds a dedicated server on a *normal*
 * overworld — the only lever in the harness that produces one.
 *
 * <p>Trees used to be an unlock, and this test used to prove they were missing until bought. They
 * are vanilla from the first run now, so what it proves is that nothing takes them away.
 *
 * <p>See {@code docs/dev-environment.md} for how to run this.
 */
public class TreeWorldgenClientTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("mhr-gametest");

	/** The retired unlock that used to gate trees, made sure of as not owned. */
	private static final String RETIRED_TREES = "world.trees";

	/**
	 * The run's seed. Named so the first scenario is about the same world every time; any seed with
	 * a tree somewhere in its smallest border would do.
	 */
	private static final long RUN_SEED = 20260924L;

	/** How far out from the middle of the patch to generate, in chunks. 5×5 is plenty of forest. */
	private static final int RADIUS_IN_CHUNKS = 2;

	/** The slice of the world worth searching. Forest canopy sits well inside this. */
	private static final int SCAN_FROM_Y = 50;
	private static final int SCAN_TO_Y = 150;

	/**
	 * Where the fresh-chunk check goes looking for its forest. Thousands of blocks out, so the patch
	 * is not land the server has already made.
	 */
	private static final BlockPos SEARCH_FROM = new BlockPos(6000, 64, 6000);

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

				server.runCommand("time set noon");
				server.runCommand("weather clear");
				// The unlock is gone, so no command takes its id any more. A save that bought it
				// still could hold it, and it must not matter either way.
				server.computeOnServer(unused -> UnlockState.get().set(RETIRED_TREES, false));

				scenario(context, "a-fresh-run-has-vanilla-trees",
						() -> aFreshRunHasVanillaTrees(context, server, connection));

				// Land far outside the border is the question now, not the run's own start.
				server.runCommand("mhr border infinite");
				scenario(context, "fresh-land-has-trees-with-nothing-bought",
						() -> freshLandHasTreesWithNothingBought(context, server, connection));
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
	 * The world a run actually begins in, on the smallest border, with nothing bought. Vanilla put
	 * wood inside that border by itself, so the fallback that guarantees some had nothing to do.
	 *
	 * <p>That second half is the point: the fallback is for starts vanilla left without wood, and a
	 * start vanilla already served must be left exactly as generated.
	 */
	private void aFreshRunHasVanillaTrees(ClientGameTestContext context,
			TestDedicatedServerContext server, TestDedicatedServerConnection connection) {
		server.runCommand("mhr border tiny");
		TestRuns.start(server, RUN_SEED);
		connection.waitForChunksRender();

		StartingWood.Result result = server.computeOnServer(unused -> StartingWood.last());
		check(result != null, "starting a run did not run the starting-wood check at all");
		LOGGER.info("Run on seed {}: starting wood {} at {}", RUN_SEED, result.outcome(), result.log());
		check(result.outcome() == StartingWood.Outcome.FOUND,
				"a run on seed " + RUN_SEED + " must find vanilla trees inside its border, and the"
						+ " fallback says " + result.outcome());
		boolean inside = server.computeOnServer(minecraftServer -> minecraftServer.overworld()
				.getWorldBorder().isWithinBounds(result.log()));
		check(inside, "the log found at " + result.log() + " must be inside the border");

		generate(server, result.log());
		int logs = count(server, result.log(), BlockTags.LOGS);
		LOGGER.info("Around the log at {}: {} logs", result.log(), logs);
		check(logs > 1, "vanilla trees are more than one log, and around " + result.log()
				+ " there are " + logs);
	}

	/**
	 * Land nobody has been to, made on the spot with nothing bought, has trees in it.
	 *
	 * <p>Thousands of blocks from spawn on purpose: these chunks did not exist until this scenario
	 * asked for them, so what is being checked is the worldgen path and not a world that happened
	 * to be made earlier.
	 */
	private void freshLandHasTreesWithNothingBought(ClientGameTestContext context,
			TestDedicatedServerContext server, TestDedicatedServerConnection connection) {
		BlockPos forest = findForest(server, SEARCH_FROM);
		generate(server, forest);
		int logs = count(server, forest, BlockTags.LOGS);
		LOGGER.info("Fresh forest at {}: {} logs", forest, logs);

		check(logs > 0, "fresh land must have trees in it with nothing bought, but the patch at "
				+ forest + " has no logs");

		look(context, server, connection, forest, "fresh-forest-trees-vanilla");
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
