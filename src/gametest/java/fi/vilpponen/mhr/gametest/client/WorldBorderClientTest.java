package fi.vilpponen.mhr.gametest.client;

import fi.vilpponen.mhr.border.BorderTier;
import fi.vilpponen.mhr.core.BalanceManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one part of the world border a server cannot answer for: what the player is shown.
 *
 * <p>Everything about where a border goes and how wide it is — every tier, all three dimensions,
 * the balance reload, and both real portal transitions — is checked without a client in
 * {@link fi.vilpponen.mhr.gametest.WorldBorderGameTest}, which is the right place for it: those are
 * facts about the server's own world.
 *
 * <p>This is not. A connected player does not see the server's border; they see their client's own
 * copy of it, kept up to date by packets, and the wall is drawn from that copy. A tier bought
 * mid-run that never reached the client would leave the player walking at a wall that is no longer
 * there, or stopped by one they cannot see — and the server would look perfectly correct the whole
 * time. So the check here is the client's copy against the server's, after a tier change, with the
 * wall photographed at each tier so a human can see what the numbers are describing.
 *
 * <p>See {@code docs/dev-environment.md} for how to run this.
 */
public class WorldBorderClientTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("mhr-gametest");

	/**
	 * Where the run's spawn goes. Thousands of blocks from the origin, because a border centered on
	 * 0, 0 and a border centered on the run spawn are the same border there.
	 */
	private static final int SPAWN_X = 1450;
	private static final int SPAWN_Z = -2100;

	/** How far short of the wall the camera stands, in blocks. Close enough to fill the view. */
	private static final int CAMERA_SETBACK = 18;

	private final List<String> failures = new ArrayList<>();

	/** The first air block above the ground at the run's spawn, worked out once the world exists. */
	private int surface;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestDedicatedServerContext server = context.worldBuilder().createServer()) {
			try (TestDedicatedServerConnection connection = server.connect()) {
				connection.waitForChunksRender();
				server.runCommand("time set noon");
				server.runCommand("weather clear");

				// Wide open while the spawn is being moved: the tier is applied to wherever the run
				// begins, so until the spawn has moved the tiny border is somewhere else entirely.
				server.runCommand("mhr border infinite");
				server.runCommand("forceload add " + (SPAWN_X - 64) + " " + (SPAWN_Z - 64) + " "
						+ (SPAWN_X + 320) + " " + (SPAWN_Z + 64));
				surface = server.computeOnServer(minecraftServer -> minecraftServer.overworld()
						.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, SPAWN_X, SPAWN_Z));
				server.runCommand("setworldspawn " + SPAWN_X + " " + surface + " " + SPAWN_Z);

				scenario(context, "a-tier-change-reaches-the-connected-client",
						() -> aTierChangeReachesTheConnectedClient(context, server, connection));
			}
		}

		if (!failures.isEmpty()) {
			throw new AssertionError(failures.size() + " world-border client scenario(s) failed:\n  "
					+ String.join("\n  ", failures));
		}
		LOGGER.info("All world-border client scenarios passed.");
	}

	// --- the scenario ------------------------------------------------------------------------

	/**
	 * Buy a tier while connected, and the client's own border follows.
	 *
	 * <p>Two tiers rather than one, because a client that never updated its copy would still match
	 * the server on the first one — the border it was handed when it joined.
	 */
	private void aTierChangeReachesTheConnectedClient(ClientGameTestContext context,
			TestDedicatedServerContext server, TestDedicatedServerConnection connection) {
		// A spectator is not pushed back by the border and cannot be hurt by it, so the picture is
		// of the wall rather than of a player being shoved away from it.
		server.runCommand("gamemode spectator Player0");

		for (BorderTier tier : List.of(BorderTier.TINY, BorderTier.MEDIUM)) {
			server.runCommand("mhr border " + tier.id());
			connection.waitForClientboundPackets();
			context.waitTicks(2);
			connection.waitForClientboundPackets();

			double expected = tier.balance(BalanceManager.get()).size().orElseThrow();
			double onTheServer = server.computeOnServer(minecraftServer ->
					minecraftServer.overworld().getWorldBorder().getSize());
			double onTheClient = context.computeOnClient(client ->
					client.level.getWorldBorder().getSize());
			double centerOnTheClient = context.computeOnClient(client ->
					client.level.getWorldBorder().getCenterX());
			double centerOnTheServer = server.computeOnServer(minecraftServer ->
					minecraftServer.overworld().getWorldBorder().getCenterX());
			LOGGER.info("The {} tier is {} blocks on the server and {} on the client, centered on {}"
					+ " and {}", tier.id(), (long) onTheServer, (long) onTheClient,
					(long) centerOnTheServer, (long) centerOnTheClient);

			check(onTheServer == expected, "the " + tier.id() + " tier must be " + (long) expected
					+ " blocks across on the server, and it is " + (long) onTheServer);
			check(onTheClient == onTheServer, "the connected client must have been told the "
					+ tier.id() + " tier is " + (long) onTheServer + " blocks across, and its own copy"
					+ " of the border — the one the wall is drawn from — says " + (long) onTheClient);
			check(centerOnTheClient == centerOnTheServer, "the client's copy of the border must be"
					+ " centered where the server's is, on " + (long) centerOnTheServer + ", and it is"
					+ " centered on " + (long) centerOnTheClient);

			photographTheWall(context, server, connection, tier, "border-" + tier.id() + "-wall");
		}
	}

	/**
	 * Walks to the eastern wall of the selected tier and photographs it.
	 *
	 * <p>Nothing compares pixels: the assertions above are the proof. This is the look at the wall
	 * the docs used to send a human into the game for, and it is what a failure would leave behind.
	 */
	private void photographTheWall(ClientGameTestContext context, TestDedicatedServerContext server,
			TestDedicatedServerConnection connection, BorderTier tier, String name) {
		double edge = context.computeOnClient(client -> client.level.getWorldBorder().getMaxX());
		double fromSpawn = edge - (SPAWN_X + 0.5);
		double expected = tier.balance(BalanceManager.get()).size().orElseThrow() / 2;
		check(Math.abs(fromSpawn - expected) < 1.0,
				"the " + tier.id() + " tier's wall must stand half of worldBorder." + tier.id()
						+ ".size from the run spawn, which is " + (long) expected + " blocks, and the"
						+ " client is drawing it " + (long) fromSpawn + " blocks out");

		server.runCommand(String.format(Locale.ROOT, "tp Player0 %.1f %d %.1f -90 0",
				edge - CAMERA_SETBACK, surface + 4, SPAWN_Z + 0.5));
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
