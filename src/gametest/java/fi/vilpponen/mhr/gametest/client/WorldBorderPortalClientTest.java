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
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The half of the world border that only a real journey can answer: a portal built inside the
 * border you are allowed to be in has to come out inside the border at the other end.
 *
 * <p>{@link fi.vilpponen.mhr.gametest.WorldBorderGameTest} covers where each border is put and how
 * wide it is, which is arithmetic and takes no time at all. It cannot cover this, because a portal
 * transition is not a calculation: the player stands in the portal for a while, vanilla decides
 * where the far side goes — and vanilla will happily drag that destination back inside a badly
 * placed border, so a wrong border does not announce itself. It has to be walked through.
 *
 * <p>So this builds a dedicated server, builds a real nether portal fifty blocks from the run's
 * spawn but still inside the {@code tiny} border, and sends the connected player through it. The
 * assertions are all server-side — where the player ended up and what the border there covers. The
 * client is here because it is the only traveller the harness has, and because the screenshots of
 * the border wall are the picture the docs used to ask a human to go and look at.
 *
 * <p>See {@code docs/dev-environment.md} for how to run this.
 */
public class WorldBorderPortalClientTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("mhr-gametest");

	/**
	 * Where the nether portal goes. Thousands of blocks from the origin, because a border centered
	 * on 0, 0 and a border centered on the run spawn are the same border there, and this test would
	 * pass either way.
	 */
	private static final int PORTAL_X = 1500;
	private static final int PORTAL_Z = -2100;

	/**
	 * The run's spawn, fifty blocks west of the portal. Not the portal itself, on purpose: the
	 * promise is that a portal built <em>anywhere</em> inside the allowed area is safe, and a portal
	 * standing exactly on the center would be safe even if only the center had been thought about.
	 */
	private static final int SPAWN_X = PORTAL_X - 50;
	private static final int SPAWN_Z = PORTAL_Z;

	/** How long a dimension change may take, in ticks. A player waits in the portal first. */
	private static final int TRAVEL_TIMEOUT_TICKS = 1200;

	/**
	 * How far from the portal's own coordinates, scaled, the arrival may be.
	 *
	 * <p>Vanilla builds the far-side portal near the scaled position but not exactly on it, so some
	 * slack is needed. It has to stay small, because the failure this catches is a border in the
	 * wrong place: vanilla clamps a destination into the border it is given, so a nether border
	 * left on the raw overworld coordinates does not strand anybody — it quietly lands them
	 * hundreds of blocks from where the portal maths says, which is a trap of a different kind and
	 * is invisible to an "is it inside the border" check on its own.
	 */
	private static final double ARRIVAL_SLACK = 32.0;

	private final List<String> failures = new ArrayList<>();

	/** The first air block above the ground at the portal, worked out once the world exists. */
	private int surface;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestDedicatedServerContext server = context.worldBuilder().createServer()) {
			try (TestDedicatedServerConnection connection = server.connect()) {
				connection.waitForChunksRender();
				server.runCommand("time set noon");
				server.runCommand("weather clear");

				// Wide open while the portal is being built: the run's spawn has not moved yet, so
				// the tiny border is still somewhere else entirely and everything here is outside it.
				server.runCommand("mhr border infinite");
				buildTheRunsPortal(server);

				scenario(context, "the-border-wall-stands-at-the-tier-size",
						() -> theBorderWallStandsAtTheTierSize(context, server, connection));
				scenario(context, "a-nether-portal-inside-the-border-lands-inside-the-nether-border",
						() -> aNetherPortalLandsInsideTheNetherBorder(context, server, connection));
				scenario(context, "a-real-end-transition-lands-inside-the-end-border",
						() -> anEndTransitionLandsInsideTheEndBorder(context, server, connection));
			}
		}

		if (!failures.isEmpty()) {
			throw new AssertionError(failures.size() + " world-border scenario(s) failed:\n  "
					+ String.join("\n  ", failures));
		}
		LOGGER.info("All world-border scenarios passed.");
	}

	// --- the scenarios ---------------------------------------------------------------------

	/**
	 * What the tiers look like from inside. The wall is the one part of this feature a player meets
	 * with their eyes, so each tier is photographed from eighteen blocks short of its own edge — which
	 * is a different place on each tier, and that is the point.
	 *
	 * <p>The numbers underneath are the proof: how far from the run's spawn each wall turned out to
	 * stand, checked against half the width the balance file gives that tier.
	 */
	private void theBorderWallStandsAtTheTierSize(ClientGameTestContext context,
			TestDedicatedServerContext server, TestDedicatedServerConnection connection) {
		// A spectator is not pushed back by the border and cannot be hurt by it, so the picture is
		// of the wall rather than of a player being shoved away from it.
		server.runCommand("gamemode spectator Player0");
		double tiny = photographTheWall(context, server, connection, BorderTier.TINY, "border-tiny-wall");
		double medium = photographTheWall(context, server, connection, BorderTier.MEDIUM,
				"border-medium-wall");

		LOGGER.info("The eastern wall stands {} blocks from the run spawn on tiny, {} on medium",
				(long) tiny, (long) medium);
		check(medium > tiny, "the medium tier must put the wall further out than the tiny one,"
				+ " but it went from " + (long) tiny + " to " + (long) medium + " blocks from spawn");
	}

	/**
	 * Walks to the eastern wall of whatever tier is selected, photographs it, and says how far from
	 * the run's spawn it turned out to be.
	 *
	 * <p>The number is checked against the balance file rather than against a number written down
	 * here, so this is the same fact the server scenarios assert — only this time with the wall
	 * itself in the picture, which is what the docs used to send a human into the game to see.
	 */
	private double photographTheWall(ClientGameTestContext context, TestDedicatedServerContext server,
			TestDedicatedServerConnection connection, BorderTier tier, String name) {
		server.runCommand("mhr border " + tier.id());
		double edge = server.computeOnServer(minecraftServer ->
				minecraftServer.overworld().getWorldBorder().getMaxX());
		double fromSpawn = edge - (SPAWN_X + 0.5);
		double expected = tier.balance(BalanceManager.get()).size().orElseThrow() / 2;
		check(Math.abs(fromSpawn - expected) < 1.0,
				"the " + tier.id() + " tier's wall must stand half of worldBorder." + tier.id()
						+ ".size from the run spawn, which is " + (long) expected + " blocks, and it is "
						+ (long) fromSpawn);

		server.runCommand(String.format(Locale.ROOT, "tp Player0 %.1f %d %.1f -90 0",
				edge - 18, surface + 4, SPAWN_Z + 0.5));
		connection.waitForChunksRender();
		context.waitTicks(20);
		context.takeScreenshot(name);
		return fromSpawn;
	}

	/**
	 * The real journey. A portal built inside the overworld border, a player who stands in it, and
	 * then two questions about where they came out: is it inside the nether border, and is it where
	 * the portal maths says it should be.
	 *
	 * <p>Both are needed. Vanilla clamps a portal destination into whatever border it is given, so
	 * a nether border in the wrong place still answers "inside" — while quietly putting the player
	 * a long way from the portal they walked into.
	 */
	private void aNetherPortalLandsInsideTheNetherBorder(ClientGameTestContext context,
			TestDedicatedServerContext server, TestDedicatedServerConnection connection) {
		server.runCommand("mhr border tiny");

		BlockPos doorway = new BlockPos(PORTAL_X + 1, surface, PORTAL_Z);
		check(server.computeOnServer(minecraftServer ->
						minecraftServer.overworld().getWorldBorder().isWithinBounds(doorway)),
				"this scenario is meant to send a player through a portal that is inside the"
						+ " overworld border, and the portal at " + doorway + " is not");

		// Creative rather than survival only so the wait in the portal is a few ticks rather than
		// four seconds. Everything about where the portal comes out is the same either way.
		server.runCommand("gamemode creative Player0");
		server.runCommand(String.format(Locale.ROOT, "tp Player0 %.1f %d %.1f -90 0",
				PORTAL_X + 1.5, surface, PORTAL_Z + 0.5));
		waitForArrival(server, Level.NETHER,
				"a player standing in a nether portal must be taken to the nether");

		Vec3 arrival = server.computeOnServer(minecraftServer -> player(minecraftServer).position());
		double scale = server.computeOnServer(minecraftServer ->
				level(minecraftServer, Level.NETHER).dimensionType().coordinateScale());
		double expectedX = (PORTAL_X + 1.5) / scale;
		double expectedZ = (PORTAL_Z + 0.5) / scale;
		double drift = Math.hypot(arrival.x() - expectedX, arrival.z() - expectedZ);
		LOGGER.info("Nether arrival at {}, {} — the 1:{} mapping of the portal is {}, {} ({} blocks away)",
				(long) arrival.x(), (long) arrival.z(), (long) scale,
				(long) expectedX, (long) expectedZ, (long) drift);

		check(server.computeOnServer(minecraftServer ->
						level(minecraftServer, Level.NETHER).getWorldBorder().isWithinBounds(arrival)),
				"a player who walked through a portal inside the overworld border must arrive inside"
						+ " the nether border, and " + (long) arrival.x() + ", " + (long) arrival.z()
						+ " is outside it");
		check(drift <= ARRIVAL_SLACK,
				"the arrival must be where the 1:" + (long) scale + " portal mapping puts the portal,"
						+ " but it came out " + (long) drift + " blocks away from " + (long) expectedX
						+ ", " + (long) expectedZ + " — which is what a nether border in the wrong"
						+ " place looks like, because vanilla drags the destination inside it");

		connection.waitForChunksRender();
		context.waitTicks(20);
		context.takeScreenshot("border-nether-arrival");
	}

	/**
	 * The end takes everybody to the same place whatever portal they left from, so what is being
	 * checked is that the place it takes them to is covered — the obsidian platform a hundred
	 * blocks east of the island, which is further out than the tiny tier is wide.
	 */
	private void anEndTransitionLandsInsideTheEndBorder(ClientGameTestContext context,
			TestDedicatedServerContext server, TestDedicatedServerConnection connection) {
		server.runCommand("mhr border tiny");
		goHome(server, connection);

		BlockPos portal = new BlockPos(SPAWN_X + 2, surface, SPAWN_Z + 2);
		server.runCommand("setblock " + portal.getX() + " " + portal.getY() + " " + portal.getZ()
				+ " minecraft:end_portal");
		// Into the portal block rather than onto it. An end portal is a shallow slab with a
		// collision shape, so a player dropped from above lands on its lid and stands there
		// forever — which is not what a portal room does, because there you walk in from the side
		// at floor level.
		server.runCommand(String.format(Locale.ROOT, "tp Player0 %.1f %d %.1f 0 0",
				portal.getX() + 0.5, portal.getY(), portal.getZ() + 0.5));
		waitForArrival(server, Level.END,
				"a player standing in a real end portal must be taken to the end");

		Vec3 arrival = server.computeOnServer(minecraftServer -> player(minecraftServer).position());
		LOGGER.info("End arrival at {}, {}", (long) arrival.x(), (long) arrival.z());

		check(server.computeOnServer(minecraftServer ->
						level(minecraftServer, Level.END).getWorldBorder().isWithinBounds(arrival)),
				"a real end transition must land inside the end border, and " + (long) arrival.x()
						+ ", " + (long) arrival.z() + " is outside it");
		check(server.computeOnServer(minecraftServer -> level(minecraftServer, Level.END)
						.getWorldBorder().isWithinBounds(ServerLevel.END_SPAWN_POINT)),
				"the obsidian arrival platform at " + ServerLevel.END_SPAWN_POINT + " must be inside"
						+ " the end border whatever tier the run is on");

		connection.waitForChunksRender();
		context.waitTicks(20);
		context.takeScreenshot("border-end-arrival");
	}

	// --- the world ---------------------------------------------------------------------------

	/**
	 * Builds the run: a nether portal out in fresh land, and the run's spawn fifty blocks west of
	 * it. The spawn is moved with the vanilla command, which is what makes the border move — the
	 * tier is applied to wherever the run begins, so there is nothing else to tell it.
	 */
	private void buildTheRunsPortal(TestDedicatedServerContext server) {
		server.runCommand("forceload add " + (PORTAL_X - 64) + " " + (PORTAL_Z - 64) + " "
				+ (PORTAL_X + 64) + " " + (PORTAL_Z + 64));
		surface = server.computeOnServer(minecraftServer -> minecraftServer.overworld()
				.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, PORTAL_X + 1, PORTAL_Z));

		// A four-by-five obsidian frame with a two-by-three doorway in it, which is the portal
		// every player builds.
		server.runCommand("fill " + PORTAL_X + " " + (surface - 1) + " " + PORTAL_Z + " "
				+ (PORTAL_X + 3) + " " + (surface + 3) + " " + PORTAL_Z + " minecraft:obsidian");
		server.runCommand("fill " + (PORTAL_X + 1) + " " + surface + " " + PORTAL_Z + " "
				+ (PORTAL_X + 2) + " " + (surface + 2) + " " + PORTAL_Z
				+ " minecraft:nether_portal[axis=x]");
		server.runCommand("setworldspawn " + SPAWN_X + " " + surface + " " + SPAWN_Z);
		LOGGER.info("Run spawn at {}, {}; portal doorway at {}, {}, both on y {}",
				SPAWN_X, SPAWN_Z, PORTAL_X + 1, PORTAL_Z, surface);
	}

	/**
	 * Waits for the player to turn up in another dimension, and says where they are stuck if they
	 * never do. "Timed out waiting for predicate" on its own is the one failure message in this
	 * file that would send somebody back to the game to find out what happened.
	 */
	private static void waitForArrival(TestDedicatedServerContext server, ResourceKey<Level> dimension,
			String what) {
		try {
			server.waitFor(minecraftServer -> dimensionOf(minecraftServer).equals(dimension),
					TRAVEL_TIMEOUT_TICKS);
		} catch (Throwable timedOut) {
			String where = server.computeOnServer(minecraftServer -> {
				Vec3 position = player(minecraftServer).position();
				return dimensionOf(minecraftServer).identifier() + " at " + (long) position.x() + ", "
						+ position.y() + ", " + (long) position.z();
			});
			throw new AssertionError(what + ", but " + TRAVEL_TIMEOUT_TICKS
					+ " ticks later they are still in " + where);
		}
	}

	/** Puts the player back in the overworld, on the run's spawn, whatever dimension they are in. */
	private void goHome(TestDedicatedServerContext server, TestDedicatedServerConnection connection) {
		server.runCommand("execute in minecraft:overworld run tp Player0 " + (SPAWN_X + 0.5) + " "
				+ surface + " " + (SPAWN_Z + 0.5));
		// A portal just used leaves a cooldown behind, and a player still on it walks through the
		// next one without going anywhere.
		server.waitFor(minecraftServer -> !player(minecraftServer).isOnPortalCooldown()
				&& dimensionOf(minecraftServer).equals(Level.OVERWORLD), TRAVEL_TIMEOUT_TICKS);
		connection.waitForChunksRender();
	}

	private static ServerPlayer player(MinecraftServer server) {
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		if (players.isEmpty()) {
			throw new AssertionError("The test player is not connected any more");
		}
		return players.get(0);
	}

	private static ResourceKey<Level> dimensionOf(MinecraftServer server) {
		return player(server).level().dimension();
	}

	private static ServerLevel level(MinecraftServer server, ResourceKey<Level> dimension) {
		ServerLevel level = server.getLevel(dimension);
		if (level == null) {
			throw new AssertionError("This server has no " + dimension.identifier()
					+ ", so nothing can arrive there");
		}
		return level;
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
