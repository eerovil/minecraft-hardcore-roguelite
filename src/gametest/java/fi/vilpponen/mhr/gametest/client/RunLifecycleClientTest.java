package fi.vilpponen.mhr.gametest.client;

import fi.vilpponen.mhr.UnlockState;
import fi.vilpponen.mhr.run.Lobby;
import fi.vilpponen.mhr.run.RunEvents;
import fi.vilpponen.mhr.run.RunPhase;
import fi.vilpponen.mhr.run.RunRecord;
import fi.vilpponen.mhr.run.RunStorage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The whole loop, once round twice: lobby, run, death, lobby, another run.
 *
 * <p>This is the scenario the issue asks for, and it is a client test because nearly every claim in
 * it is about a real player in a real dimension on a real dedicated server. A server GameTest runs
 * inside a controlled world that this feature would delete out from under it, and has no player to
 * put in a lobby or to kill.
 *
 * <p>The three claims that are easy to fake and are therefore asked the hard way:
 *
 * <ul>
 *   <li><b>The worlds are genuinely new.</b> A diamond block is put in each of the run's three
 *       dimensions at a fixed coordinate. No generator makes one there. If any of them is still
 *       standing in the next run, that run reused the last one's chunks.
 *   <li><b>The lobby is not.</b> A diamond block is put in the lobby the same way, before any run
 *       exists, and has to be there at the end.
 *   <li><b>The reward is committed once.</b> A listener on {@link RunEvents#RUN_ENDED} counts, and
 *       the second death and the second {@code /mhr run end} have to leave the count alone.
 * </ul>
 *
 * <p>The seeds are not fixed. "Each new run uses a fresh seed" is the criterion, so naming one
 * would test the opposite of what is wanted; the assertion is that two runs' seeds differ and that
 * each run's overworld reports the seed the record claims for it.
 *
 * <p><b>Known failure.</b> Two scenarios here are red, and for the same reason: when a run ends,
 * the server puts the player in the lobby and every server-side assertion about that passes, but
 * the client never follows — it stays in the world it was in, and
 * {@code waitForClientIn} says so. The other direction is fine: entering a run, which is where the
 * destination is a level object the client has never seen, works every time. The difference points
 * at the lobby being the same {@code ServerLevel} across the whole session. Moving the player with
 * an ordinary cross-dimension teleport and with {@code PlayerList.respawn} both behave the same
 * way, so it is not the entity-add path. This is left as an assertion rather than a warning so the
 * gap cannot be mistaken for a passing feature.
 *
 * <p>See {@code docs/dev-environment.md} for how to run this.
 */
public class RunLifecycleClientTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("mhr-gametest");

	/** Somewhere no generator puts a diamond block, in every dimension involved. */
	private static final BlockPos MARK = new BlockPos(0, 100, 0);
	private static final BlockPos LOBBY_MARK = new BlockPos(0, -60, 0);
	private static final BlockState MARKER = Blocks.DIAMOND_BLOCK.defaultBlockState();

	/** A permanent purchase, made before the first run, that has to outlive both of them. */
	private static final String PURCHASE = "world.trees";

	/** How many times a run has been reported as ended, counted on the server. */
	private static final AtomicInteger RUNS_ENDED = new AtomicInteger();
	private static boolean counting;

	private final List<String> failures = new ArrayList<>();

	private long firstSeed;
	private BlockPos firstSpawn;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestDedicatedServerContext server = context.worldBuilder().createServer()) {
			countRunEndings(server);
			server.runCommand("mhr unlock " + PURCHASE);

			try (TestDedicatedServerConnection connection = server.connect()) {
				settle(context, connection);

				scenario(context, "a-save-with-no-run-puts-the-player-in-the-lobby",
						() -> startsInTheLobby(context, server, connection));
				scenario(context, "starting-a-run-builds-three-fresh-dimensions",
						() -> startingARunBuildsAWorld(context, server, connection));
				scenario(context, "travelling-to-the-nether-stays-inside-this-run",
						() -> theNetherBelongsToThisRun(server, connection));
				scenario(context, "quitting-mid-run-is-not-a-death",
						() -> quittingIsNotDying(context, server, connection));
			}

			// The reconnect above closed the connection; everything from here needs a player again.
			try (TestDedicatedServerConnection connection = server.connect()) {
				settle(context, connection);

				scenario(context, "death-ends-the-run-and-returns-the-player-to-the-lobby",
						() -> deathReturnsToTheLobby(context, server, connection));
				scenario(context, "a-run-cannot-be-ended-twice",
						() -> aRunEndsOnlyOnce(context, server, connection));
				scenario(context, "the-next-run-is-a-genuinely-different-world",
						() -> theNextRunIsANewWorld(context, server, connection));
				scenario(context, "permanent-progression-survives-run-cleanup",
						() -> progressionSurvives(server));
				scenario(context, "the-lobby-survived-both-runs",
						() -> theLobbySurvived(context, server, connection));
			}
		}

		if (!failures.isEmpty()) {
			throw new AssertionError(failures.size() + " run-lifecycle scenario(s) failed:\n  "
					+ String.join("\n  ", failures));
		}
		LOGGER.info("All run-lifecycle client scenarios passed.");
	}

	// --- the scenarios ---------------------------------------------------------------------

	/**
	 * A save nobody has played begins between runs, not in a world.
	 *
	 * <p>The lobby is also marked here, before any run has ever existed, so that the block proving
	 * it survived cannot have been put there by anything a run did.
	 */
	private void startsInTheLobby(ClientGameTestContext context, TestDedicatedServerContext server,
			TestDedicatedServerConnection connection) {
		RunRecord record = TestRuns.record(server);
		check(record.phase() == RunPhase.LOBBY,
				"a save nobody has played must be in the lobby, and it says " + record.describe());
		check(record.runId() == 0 && record.completedRuns() == 0,
				"no run has happened yet, but the record says " + record.describe());

		check(TestRuns.playerIsInTheLobby(server, connection),
				"a player joining with no run in progress must arrive in the lobby, and they are in "
						+ TestRuns.playerDimension(server, connection));

		String clientDimension = context.computeOnClient(client ->
				client.player.level().dimension().identifier().toString());
		check(clientDimension.equals(Lobby.LEVEL.identifier().toString()),
				"the client must agree it is in the lobby, and it thinks it is in " + clientDimension);

		boolean floor = server.computeOnServer(minecraftServer -> minecraftServer
				.getLevel(Lobby.LEVEL)
				.getBlockState(Lobby.SPAWN.below())
				.is(Blocks.BEDROCK));
		check(floor, "the lobby must have generated a floor under its spawn, and there is none");

		TestRuns.mark(server, Lobby.LEVEL, LOBBY_MARK, MARKER);
		context.takeScreenshot("run-lifecycle-lobby-before-any-run");
	}

	/**
	 * Starting a run replaces the three run dimensions and moves the player into them.
	 *
	 * <p>Each one is marked on the way past. Those marks are what the later scenario asks about.
	 */
	private void startingARunBuildsAWorld(ClientGameTestContext context,
			TestDedicatedServerContext server, TestDedicatedServerConnection connection) {
		TestRuns.start(server);
		connection.waitForChunksRender();

		RunRecord record = TestRuns.record(server);
		check(record.phase() == RunPhase.RUNNING,
				"after starting a run the save must be running one, and it says " + record.describe());
		check(record.runId() == 1, "the first run must be run 1, and it is run " + record.runId());

		long overworldSeed = server.computeOnServer(minecraftServer ->
				minecraftServer.overworld().getSeed());
		check(overworldSeed == record.seed(), "the run's overworld must be generated from the seed"
				+ " the record claims: record says " + record.seed() + ", the world says "
				+ overworldSeed);

		check(!TestRuns.playerIsInTheLobby(server, connection),
				"starting a run must take the player out of the lobby, and they are still in it");
		String dimension = TestRuns.playerDimension(server, connection);
		check(dimension.equals("minecraft:overworld"),
				"a run starts in its overworld, and the player is in " + dimension);

		for (var key : List.of(Level.OVERWORLD, Level.NETHER, Level.END)) {
			boolean exists = server.computeOnServer(minecraftServer ->
					minecraftServer.getLevel(key) != null);
			check(exists, "a run needs its own " + key.identifier() + ", and there is none");
			TestRuns.mark(server, key, MARK, MARKER);
		}

		firstSeed = record.seed();
		firstSpawn = TestRuns.runSpawn(server);
		LOGGER.info("Run 1: seed {}, spawn {}", firstSeed, firstSpawn);
		waitForClientIn(context, connection, "minecraft:overworld");
		context.takeScreenshot("run-lifecycle-first-run-overworld");
	}

	/**
	 * A portal's worth of travel: arriving in the nether is arriving in <em>this</em> run's nether,
	 * and is not itself a new run.
	 *
	 * <p>The teleport stands in for walking through a portal. What it is really asking is whether
	 * the nether the player reaches is the one this run built — the mark says it is — and whether
	 * changing dimension disturbs the loop, which it must not.
	 */
	private void theNetherBelongsToThisRun(
			TestDedicatedServerContext server, TestDedicatedServerConnection connection) {
		server.runCommand("execute in minecraft:the_nether run tp Player0 0 100 0");
		TestRuns.settle(server);

		String dimension = TestRuns.playerDimension(server, connection);
		check(dimension.equals("minecraft:the_nether"),
				"the player should be in this run's nether and is in " + dimension);
		check(TestRuns.isMarked(server, Level.NETHER, MARK, MARKER),
				"the nether the player reached must be the one this run built, and the mark this run"
						+ " put in it is not there");

		RunRecord record = TestRuns.record(server);
		check(record.phase() == RunPhase.RUNNING && record.runId() == 1,
				"changing dimension is not a run boundary, and the record now says "
						+ record.describe());

		server.runCommand("execute in minecraft:overworld run tp Player0 "
				+ firstSpawn.getX() + " " + firstSpawn.getY() + " " + firstSpawn.getZ());
		TestRuns.settle(server);
	}

	/**
	 * Closing the game during a run leaves the run alone.
	 *
	 * <p>Asked in two places, because they are two different claims. The running server must still
	 * say the run is in progress after a real disconnect and reconnect — and the file on disk must
	 * say so too, because that is what a restarted server would read. {@link
	 * fi.vilpponen.mhr.run.RunStorage} and {@link RunRecord} are unit-tested on what they then do
	 * with it; this harness cannot restart a dedicated server against the same save.
	 */
	private void quittingIsNotDying(ClientGameTestContext context,
			TestDedicatedServerContext server, TestDedicatedServerConnection connection) {
		RunRecord onDisk = server.computeOnServer(minecraftServer ->
				new RunStorage(minecraftServer.getWorldPath(LevelResource.ROOT)).load());
		check(onDisk.phase() == RunPhase.RUNNING && onDisk.runId() == 1,
				"the file a restart would read must say the run is still in progress, and it says "
						+ onDisk.describe());
		check(onDisk.completedRuns() == 0,
				"quitting mid-run must not count as a completed run, and the file says "
						+ onDisk.completedRuns());
		check(RUNS_ENDED.get() == 0,
				"nothing has ended a run yet, but the end hook has fired " + RUNS_ENDED.get()
						+ " time(s)");
	}

	/**
	 * The death that ends a run: no game-over, no lost save, just the lobby.
	 *
	 * <p>Killed with the ordinary command rather than by calling the lifecycle, so the path under
	 * test is the one an actual death takes.
	 */
	private void deathReturnsToTheLobby(ClientGameTestContext context,
			TestDedicatedServerContext server, TestDedicatedServerConnection connection) {
		RunRecord before = TestRuns.record(server);
		check(before.phase() == RunPhase.RUNNING,
				"reconnecting must not have ended the run, and the record says " + before.describe());
		check(!TestRuns.playerIsInTheLobby(server, connection),
				"reconnecting during a run must put the player back in the run, and they are in the"
						+ " lobby");

		server.runCommand("kill Player0");
		TestRuns.waitForPhase(context, server, RunPhase.LOBBY);

		RunRecord after = TestRuns.record(server);
		check(after.phase() == RunPhase.LOBBY,
				"a death must put the save back in the lobby, and it says " + after.describe());
		check(after.completedRuns() == 1,
				"the run that just ended must be counted once, and the record counts "
						+ after.completedRuns());
		check(RUNS_ENDED.get() == 1,
				"the run-end hook must fire exactly once per run, and it has fired "
						+ RUNS_ENDED.get() + " time(s)");

		check(TestRuns.playerIsInTheLobby(server, connection),
				"a death must return the player to the lobby, and they are in "
						+ TestRuns.playerDimension(server, connection));
		float health = server.computeOnServer(unused -> connection.getServerPlayer().getHealth());
		check(health > 0.0F,
				"the player must arrive in the lobby alive rather than on a game-over screen, and"
						+ " they have " + health + " health");

		waitForClientIn(context, connection, Lobby.LEVEL.identifier().toString());
		context.takeScreenshot("run-lifecycle-lobby-after-death");
	}

	/**
	 * The second death, and the second attempt to end a run, both have to find nothing to do.
	 *
	 * <p>The dangerous version of this bug pays the reward twice, so what is counted is the hook
	 * rather than the phase.
	 */
	private void aRunEndsOnlyOnce(ClientGameTestContext context,
			TestDedicatedServerContext server, TestDedicatedServerConnection connection) {
		server.runCommand("kill Player0");
		TestRuns.settle(server);
		context.waitTicks(10);
		server.runCommand("mhr run end");
		TestRuns.settle(server);

		RunRecord record = TestRuns.record(server);
		check(record.phase() == RunPhase.LOBBY,
				"neither a second death nor a second end may move the save, and it says "
						+ record.describe());
		check(record.completedRuns() == 1,
				"the same run must not be counted twice, and the record counts "
						+ record.completedRuns());
		check(RUNS_ENDED.get() == 1,
				"the reward must be committed once per run, and the end hook has fired "
						+ RUNS_ENDED.get() + " time(s)");
		check(TestRuns.playerIsInTheLobby(server, connection),
				"dying in the lobby must leave the player in the lobby, alive");
	}

	/** Starting again builds a different world, and none of the old one survives into it. */
	private void theNextRunIsANewWorld(ClientGameTestContext context,
			TestDedicatedServerContext server, TestDedicatedServerConnection connection) {
		TestRuns.start(server);
		connection.waitForChunksRender();

		RunRecord record = TestRuns.record(server);
		check(record.phase() == RunPhase.RUNNING && record.runId() == 2,
				"the second run must be run 2 and in progress, and the record says "
						+ record.describe());
		check(record.seed() != firstSeed,
				"each run must be generated from a fresh seed, and run 2 reused " + firstSeed);

		long overworldSeed = server.computeOnServer(minecraftServer ->
				minecraftServer.overworld().getSeed());
		check(overworldSeed == record.seed(),
				"run 2's overworld must be generated from run 2's seed: record says " + record.seed()
						+ ", the world says " + overworldSeed);

		for (var key : List.of(Level.OVERWORLD, Level.NETHER, Level.END)) {
			check(!TestRuns.isMarked(server, key, MARK, MARKER),
					"run 1's block is still standing in " + key.identifier()
							+ ", so run 2 is playing run 1's chunks");
		}

		check(!TestRuns.playerIsInTheLobby(server, connection),
				"starting the next run must take the player into it");
		LOGGER.info("Run 2: seed {}, spawn {}", record.seed(), TestRuns.runSpawn(server));
		waitForClientIn(context, connection, "minecraft:overworld");
		context.takeScreenshot("run-lifecycle-second-run-overworld");
	}

	/**
	 * What was bought before run 1 is still owned after run 1's world was deleted.
	 *
	 * <p>Read back through the file rather than out of memory, because the dedicated server shares
	 * this process with the client and would otherwise be answering from the same object that was
	 * written — see {@link UnlockState#reloadFromFile()}.
	 */
	private void progressionSurvives(TestDedicatedServerContext server) {
		boolean owned = server.computeOnServer(unused -> UnlockState.reloadFromFile().isOwned(PURCHASE));
		check(owned, "permanent progression must outlive the worlds it was bought before, and "
				+ PURCHASE + " is not owned any more");

		RunRecord onDisk = server.computeOnServer(minecraftServer ->
				new RunStorage(minecraftServer.getWorldPath(LevelResource.ROOT)).load());
		check(onDisk.completedRuns() == 1 && onDisk.runId() == 2,
				"the save's own record must have survived deleting the run worlds, and it says "
						+ onDisk.describe());
	}

	/** The lobby, its floor and the block put in it before run 1 are all still there. */
	private void theLobbySurvived(ClientGameTestContext context, TestDedicatedServerContext server,
			TestDedicatedServerConnection connection) {
		boolean exists = server.computeOnServer(minecraftServer ->
				minecraftServer.getLevel(Lobby.LEVEL) != null);
		check(exists, "the lobby must survive every run reset, and it is gone");

		check(TestRuns.isMarked(server, Lobby.LEVEL, LOBBY_MARK, MARKER),
				"the block put in the lobby before run 1 must still be there after two runs were"
						+ " deleted, and it is not");

		boolean floor = server.computeOnServer(minecraftServer -> minecraftServer
				.getLevel(Lobby.LEVEL)
				.getBlockState(Lobby.SPAWN.below())
				.is(Blocks.BEDROCK));
		check(floor, "the lobby floor must still be there");

		TestRuns.end(server);
		check(TestRuns.playerIsInTheLobby(server, connection),
				"ending the run must bring the player back to the lobby");
		waitForClientIn(context, connection, Lobby.LEVEL.identifier().toString());
		context.takeScreenshot("run-lifecycle-lobby-between-runs");
	}

	// --- plumbing --------------------------------------------------------------------------

	/**
	 * Count run endings on the server, once per process.
	 *
	 * <p>{@link RunEvents} is a static registry and this test runs in the same JVM as the server it
	 * drives, so registering twice would double every number this test asserts on.
	 */
	/**
	 * Wait for a freshly connected client to be properly in the world.
	 *
	 * <p>Rendered chunks are not the whole of it: the "Loading terrain" screen is still up while the
	 * server waits to hear that the client has loaded, and acting on a player before that has
	 * finished races with their own arrival.
	 */
	private static void settle(ClientGameTestContext context, TestDedicatedServerConnection connection) {
		connection.waitForChunksRender();
		context.waitFor(client -> client.gui.screen() == null);
		context.waitTicks(20);
	}

	/**
	 * Assert that the client itself has arrived in a dimension and drawn it.
	 *
	 * <p>Not only evidence. The server deciding a player is in the lobby is half the claim; the
	 * other half is the player seeing it. This currently fails for the run-to-lobby direction —
	 * see the note on {@link #runTest} — and is deliberately left as an assertion rather than a
	 * warning so that the gap stays visible.
	 */
	private static void waitForClientIn(ClientGameTestContext context,
			TestDedicatedServerConnection connection, String dimension) {
		try {
			context.waitFor(client -> client.player != null
					&& client.player.level().dimension().identifier().toString().equals(dimension));
			connection.waitForChunksRender();
			context.waitFor(client -> client.gui.screen() == null);
			context.waitTicks(20);
		} catch (Throwable stuck) {
			String where = context.computeOnClient(client -> client.player == null
					? "nowhere (no player)"
					: client.player.level().dimension().identifier().toString());
			String screen = context.computeOnClient(client ->
					client.gui.screen() == null ? "none" : client.gui.screen().getClass().getSimpleName());
			throw new AssertionError("The client never followed the player into " + dimension
					+ ": it is still in " + where + " with screen " + screen, stuck);
		}
	}

	private static void countRunEndings(TestDedicatedServerContext server) {
		server.runOnServer(unused -> {
			if (counting) {
				return;
			}
			counting = true;
			RunEvents.RUN_ENDED.register((minecraftServer, run) -> RUNS_ENDED.incrementAndGet());
		});
	}

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
