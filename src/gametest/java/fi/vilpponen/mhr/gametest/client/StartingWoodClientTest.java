package fi.vilpponen.mhr.gametest.client;

import fi.vilpponen.mhr.border.StartingWood;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A run whose world has no trees anywhere is left as generated: no tree is ever made up to fix it.
 *
 * <p>The harness's own world is superflat: grass to the horizon and not one tree in it, in vanilla
 * as much as here, and no wooded biome to move to either. Starting a run there is the one way to
 * reach the case where nothing natural is in reach, and the answer has to be to leave the world
 * alone rather than grow a tree that does not belong in it. The seed that *does* have trees nearby
 * is {@link TreeWorldgenClientTest}'s.
 *
 * <p>The land inside the border is scanned for logs rather than only asking the check what it did,
 * so "it says it grew nothing" and "there is nothing" are separate claims.
 *
 * <p>See {@code docs/dev-environment.md} for how to run this.
 */
public class StartingWoodClientTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("mhr-gametest");

	/** Any seed: superflat has no trees on any of them. Named so the run is the same every time. */
	private static final long SEED = 60L;

	private final List<String> failures = new ArrayList<>();

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestDedicatedServerContext server = context.worldBuilder().createServer()) {
			try (TestDedicatedServerConnection connection = server.connect()) {
				connection.waitForChunksRender();
				server.runCommand("time set noon");
				server.runCommand("weather clear");

				scenario(context, "a-start-with-no-trees-in-reach-is-left-as-generated",
						() -> aStartWithNoTreesInReachIsLeftAsGenerated(context, server, connection));
			}
		}

		if (!failures.isEmpty()) {
			throw new AssertionError(failures.size() + " starting-wood scenario(s) failed:\n  "
					+ String.join("\n  ", failures));
		}
		LOGGER.info("All starting-wood scenarios passed.");
	}

	private void aStartWithNoTreesInReachIsLeftAsGenerated(ClientGameTestContext context,
			TestDedicatedServerContext server, TestDedicatedServerConnection connection) {
		// The smallest border, which is the one a first run gets, established rather than assumed.
		server.runCommand("mhr border tiny");
		TestRuns.start(server, SEED);
		connection.waitForChunksRender();

		StartingWood.Result result = server.computeOnServer(unused -> StartingWood.last());
		check(result != null, "starting a run did not run the starting-wood check at all");
		check(result.outcome() == StartingWood.Outcome.NONE,
				"superflat has no trees and nowhere wooded to move to, and the check says " + result.outcome());
		check(result.spawn().equals(result.from()),
				"with nowhere to go the spawn must stay put, and it moved from " + result.from() + " to " + result.spawn());
		BlockPos spawn = server.computeOnServer(minecraftServer ->
				minecraftServer.getWorldData().overworldData().getRespawnData().pos());
		check(spawn.equals(result.from()), "the run's spawn is " + spawn + ", not the " + result.from()
				+ " it started at");

		Scan scan = scanInsideTheBorder(server);
		LOGGER.info("Inside a {}-wide border: {} logs over {} columns", scan.size(), scan.logs(), scan.columns());
		check(scan.columns() > 0, "the scan looked at no land at all, so counting logs proves nothing");
		check(scan.logs() == 0, "no tree may be made up where the world has none, and the border holds "
				+ scan.logs() + " logs");

		look(context, server, connection);
	}

	/** Every log near the top of every column inside the overworld's border. */
	private static Scan scanInsideTheBorder(TestDedicatedServerContext server) {
		return server.computeOnServer(minecraftServer -> {
			ServerLevel overworld = minecraftServer.overworld();
			WorldBorder border = overworld.getWorldBorder();
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
			int logs = 0;
			int columns = 0;
			for (int x = (int) Math.floor(border.getMinX()); x < Math.ceil(border.getMaxX()); x++) {
				for (int z = (int) Math.floor(border.getMinZ()); z < Math.ceil(border.getMaxZ()); z++) {
					if (!border.isWithinBounds(x + 0.5, z + 0.5)) {
						continue;
					}
					overworld.getChunk(x >> 4, z >> 4);
					columns++;
					int top = overworld.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
					for (int y = overworld.getMinY(); y < top; y++) {
						if (overworld.getBlockState(pos.set(x, y, z)).is(BlockTags.LOGS)) {
							logs++;
						}
					}
				}
			}
			return new Scan((long) border.getSize(), columns, logs);
		});
	}

	private record Scan(long size, int columns, int logs) {
	}

	/** Stands at spawn and photographs the flat land around it, so a person can see what was counted. */
	private static void look(ClientGameTestContext context, TestDedicatedServerContext server,
			TestDedicatedServerConnection connection) {
		BlockPos spawn = TestRuns.runSpawn(server);
		int surface = server.computeOnServer(minecraftServer -> minecraftServer.overworld()
				.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawn.getX(), spawn.getZ()));
		server.runCommand("gamemode spectator Player0");
		server.runCommand("tp Player0 " + spawn.getX() + " " + (surface + 6) + " " + spawn.getZ() + " 0 15");
		connection.waitForChunksRender();
		context.waitTicks(20);
		context.takeScreenshot("flat-world-start-left-as-generated");
	}

	// --- plumbing --------------------------------------------------------------------------

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
