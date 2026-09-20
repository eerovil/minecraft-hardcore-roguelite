package fi.vilpponen.mhr.gametest.client;

import fi.vilpponen.mhr.UnlockState;
import fi.vilpponen.mhr.gametest.mixin.ChatComponentAccessor;
import fi.vilpponen.mhr.run.Lobby;
import fi.vilpponen.mhr.run.RunAdmission;
import fi.vilpponen.mhr.run.RunEvents;
import fi.vilpponen.mhr.run.RunPhase;
import fi.vilpponen.mhr.run.RunRecord;
import fi.vilpponen.mhr.run.RunStorage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.client.multiplayer.chat.GuiMessage;
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
 * <p>Both directions of the dimension change are asserted from the client as well as the server,
 * because "the server thinks you are in the lobby" is only half of being in the lobby. They are
 * given a long timeout rather than the harness default: see {@link #ARRIVAL_TIMEOUT}.
 *
 * <p><b>Known failure.</b> {@code checkClientSeesTheLobby} is red on the two scenarios that return
 * from a run. Everything the server owns is right — the record, the phase, the player's dimension —
 * and the client agrees it is in the lobby, but it has been sent none of the lobby's blocks, so the
 * player stands in what their client draws as empty void. The cause is in Minecraft's own
 * per-level entity bookkeeping: leaving the lobby does not untrack the player there, so the lobby's
 * lookup keeps pointing at them, and the next arrival is only half registered and never starts
 * receiving chunks. The first visit, before anyone has ever left, is fine — which is why the
 * before-any-run scenario passes. Deferring the move by a tick, moving through
 * {@code PlayerList.respawn} instead of a teleport, and force-loading the lobby's spawn chunk were
 * all tried and none of them changes it.
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

	/**
	 * Make the next run start, or the next reward, fail on purpose.
	 *
	 * <p>Both boundaries are meant to survive a listener that throws — a failed start must not leave
	 * a run that looks playable, and a failed reward must stay owed rather than be recorded as
	 * given. Neither can be asked about without a listener that actually fails, so the test brings
	 * its own and turns it on for one scenario at a time.
	 */
	private static final AtomicBoolean FAIL_RUN_START = new AtomicBoolean();
	private static final AtomicBoolean FAIL_RUN_END = new AtomicBoolean();

	private static boolean listenersRegistered;

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

			// Its own connection: the point of it is logging in again.
			TestRuns.waitForNobodyConnected(context, server);
			scenario(context, "reconnecting-from-the-lobby-during-a-run-lands-in-the-run",
					() -> reconnectingFromTheLobbyJoinsTheRun(context, server));

			TestRuns.waitForNobodyConnected(context, server);
			try (TestDedicatedServerConnection connection = server.connect()) {
				settle(context, connection);

				scenario(context, "a-run-start-that-fails-does-not-become-a-playable-run",
						() -> aFailedStartIsNotARun(server, connection));
				scenario(context, "a-reward-that-fails-stays-owed-and-is-committed-once-on-retry",
						() -> aFailedRewardIsRetriedOnce(server));
				scenario(context, "a-run-cannot-start-without-a-lobby-to-leave-from",
						() -> noLobbyMeansNoRun(server, connection));
			}

			TestRuns.waitForNobodyConnected(context, server);
			scenario(context, "quitting-inside-a-run-and-returning-to-the-next-one-keeps-nothing",
					() -> theNextRunDoesNotInheritTheLastOnes(context, server));

			TestRuns.waitForNobodyConnected(context, server);
			scenario(context, "reconnecting-to-the-same-run-keeps-everything",
					() -> theSameRunKeepsWhatYouHad(context, server));

			TestRuns.waitForNobodyConnected(context, server);
			scenario(context, "joining-a-server-with-no-lobby-does-not-fault",
					() -> joiningWithNoLobby(context, server));
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
		// Asked first, and asked here rather than anywhere later, because "before anything has used
		// it" is the whole claim. A persistent attachment registered on first use is registered
		// after the save data that needed it has already been read and its unknown ids dropped —
		// which on a server restarted mid-run costs the player the run they were playing. Nothing
		// in this process has admitted anybody yet, so the only thing that can have registered it
		// is mod initialization.
		check(server.computeOnServer(unused -> RunAdmission.isRegistered()),
				"the admitted-run attachment must be registered during mod initialization, before"
						+ " any player save data can be read, and it is not registered yet");

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
		frameTheLobby(context, server);
		checkClientSeesTheLobby(context);
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

		// The lobby outlives every run, so it is the one level that can be left holding a player
		// who has gone. Asked here, on the way out, rather than at the arrival that would suffer
		// for it — by then the damage is done and the symptom is two transitions away from the
		// cause.
		String stale = TestRuns.liveLobbyRegistrationOf(server, connection);
		check(stale.isEmpty(), "after a player leaves the lobby for a run, the lobby must not still"
				+ " hold a live registration for them, and it kept " + stale);

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
		float health = TestRuns.playerHealth(server, connection);
		check(health > 0.0F,
				"the player must arrive in the lobby alive rather than on a game-over screen, and"
						+ " they have " + health + " health");

		waitForClientIn(context, connection, Lobby.LEVEL.identifier().toString());
		frameTheLobby(context, server);
		checkClientSeesTheLobby(context);
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
		frameTheLobby(context, server);
		checkClientSeesTheLobby(context);
		context.takeScreenshot("run-lifecycle-lobby-between-runs");
	}

	/**
	 * Somebody who quit between runs, and comes back to find a run already going.
	 *
	 * <p>Their saved position is the lobby, so joining moves them out of it — and moving out of the
	 * lobby is the transition that has to go through the respawn lifecycle or the lobby is left
	 * holding them. The last two checks are the ones that would have caught it: nothing live left
	 * behind, and the lobby still usable when they come back to it.
	 */
	private void reconnectingFromTheLobbyJoinsTheRun(
			ClientGameTestContext context, TestDedicatedServerContext server) {
		check(TestRuns.phase(server) == RunPhase.LOBBY,
				"this scenario starts between runs, and the save says " + TestRuns.record(server).describe());

		// Give them something to lose first. Whatever a player is holding between runs belongs to
		// the run that has finished, and joining the next one must not bring it along.
		try (TestDedicatedServerConnection connection = server.connect()) {
			settle(context, connection);
			server.runCommand("give Player0 minecraft:diamond 5");
			server.runCommand("item replace entity Player0 enderchest.0 with minecraft:emerald 3");
			server.runCommand("xp set Player0 7 levels");
			TestRuns.settle(server);

			String carried = TestRuns.runLocalStateOf(server);
			check(!carried.equals(TestRuns.NOTHING_CARRIED),
					"this scenario has to start with something to lose, and the player has " + carried);
		}
		TestRuns.waitForNobodyConnected(context, server);

		// Started while nobody is connected, which is exactly the situation: the player is away.
		TestRuns.start(server);
		check(TestRuns.describePlayers(server).equals("nobody"),
				"this scenario needs the player to be away while the run starts, and the server has "
						+ TestRuns.describePlayers(server));
		check(TestRuns.phase(server) == RunPhase.RUNNING, "the run did not start");

		try (TestDedicatedServerConnection connection = server.connect()) {
			settle(context, connection);

			// They log in in the lobby, because that is where they left off, and are moved into the
			// run on the tick after. Waited for rather than assumed: the assertion is that they end
			// up in the run, not that it happens within one tick of the login.
			waitForClientIn(context, connection, "minecraft:overworld");

			String where = TestRuns.playerDimension(server, connection);
			check(where.equals("minecraft:overworld"),
					"a player logging in from a saved lobby position while a run is going belongs in"
							+ " the run, and they are in " + where
							+ " — the server has " + TestRuns.describePlayers(server));

			String stale = TestRuns.liveLobbyRegistrationOf(server, connection);
			check(stale.isEmpty(), "joining the run out of the lobby must not leave the lobby holding"
					+ " them, and it kept " + stale);

			// The boundary they never crossed. They were away when the run started, so joining it
			// has to take everything the last run gave them, exactly as starting one does.
			String carried = TestRuns.runLocalStateOf(server);
			check(carried.equals(TestRuns.NOTHING_CARRIED),
					"a player who joins a run that started while they were away must arrive with"
							+ " nothing from the run before, and they have " + carried);

			// And the lobby still works for them, which is what that leak used to break.
			TestRuns.end(server);
			waitForClientIn(context, connection, Lobby.LEVEL.identifier().toString());
			frameTheLobby(context, server);
			checkClientSeesTheLobby(context);
		}
	}

	/**
	 * A run whose start setup fails is not a run.
	 *
	 * <p>The record must not be left saying a run is in progress when the things a run start owes it
	 * — the starter chest, the border, whatever the shop sells next — did not all happen.
	 */
	private void aFailedStartIsNotARun(
			TestDedicatedServerContext server, TestDedicatedServerConnection connection) {
		RunRecord before = TestRuns.record(server);
		check(before.phase() == RunPhase.LOBBY,
				"this scenario starts in the lobby, and the save says " + before.describe());

		FAIL_RUN_START.set(true);
		try {
			TestRuns.start(server);
		} finally {
			FAIL_RUN_START.set(false);
		}

		RunRecord after = TestRuns.record(server);
		check(after.phase() == RunPhase.LOBBY,
				"a run whose start setup failed must not be left looking playable, and the save says "
						+ after.describe());
		check(after.completedRuns() == before.completedRuns(),
				"a run that never started is not a run played");
		check(TestRuns.playerIsInTheLobby(server, connection),
				"the player must be left in the lobby, and they are in "
						+ TestRuns.playerDimension(server, connection));

		// And the next attempt works, under an id of its own.
		TestRuns.start(server);
		RunRecord good = TestRuns.record(server);
		check(good.phase() == RunPhase.RUNNING,
				"a run must still be startable after one failed, and the save says " + good.describe());
		check(good.runId() > after.runId(),
				"the abandoned run's id must not be handed to the one that worked: abandoned "
						+ after.runId() + ", started " + good.runId());

		TestRuns.end(server);
	}

	/**
	 * A reward that could not be handed over is still owed, and the retry hands it over once.
	 *
	 * <p>Both halves matter. Recording a failed payout as done loses it for good; retrying one that
	 * did happen pays it twice. The counter is the evidence, because it only counts reward
	 * listeners that actually ran to the end.
	 */
	private void aFailedRewardIsRetriedOnce(TestDedicatedServerContext server) {
		int committedBefore = RUNS_ENDED.get();
		TestRuns.start(server);
		RunRecord run = TestRuns.record(server);

		FAIL_RUN_END.set(true);
		int reported;
		try {
			reported = TestRuns.runCommandResult(server, "mhr run end");
		} finally {
			FAIL_RUN_END.set(false);
		}

		check(reported == 0, "a run that could not be finished must not be reported as finished, and"
				+ " /mhr run end returned " + reported);

		RunRecord stuck = TestRuns.record(server);
		check(stuck.phase() == RunPhase.ENDING_RUN,
				"a run whose reward failed must stay unfinished, and the save says " + stuck.describe());
		check(stuck.rewardOutstanding(),
				"the reward must still be owed rather than written down as given");
		check(RUNS_ENDED.get() == committedBefore,
				"nothing was committed, and the reward hook reports " + RUNS_ENDED.get()
						+ " against " + committedBefore + " before");
		check(stuck.runId() == run.runId(), "it is still the same run");

		// The retry: the same run, handed over once, and said so this time.
		int retried = TestRuns.runCommandResult(server, "mhr run end");
		check(retried == 1, "the retry finished the run and must say so, and it returned " + retried);

		RunRecord done = TestRuns.record(server);
		check(done.phase() == RunPhase.LOBBY,
				"the retry must finish the run, and the save says " + done.describe());
		check(RUNS_ENDED.get() == committedBefore + 1,
				"the reward must be committed exactly once across the failure and the retry, and the"
						+ " hook has now fired " + (RUNS_ENDED.get() - committedBefore) + " time(s) for it");
		check(done.completedRuns() == run.completedRuns() + 1,
				"the run is counted once, and the record counts " + done.completedRuns());
	}

	/**
	 * With no lobby there is nowhere safe to stand, so nothing may start.
	 *
	 * <p>This is the branch that used to improvise. Without a lobby to evacuate into, the old code
	 * put the player in the overworld — the very world the run start was about to delete, and the
	 * reason they were being moved at all. So the question is not only "does it refuse" but "where
	 * is the player afterwards", and the answer has to be: exactly where they were.
	 */
	private void noLobbyMeansNoRun(
			TestDedicatedServerContext server, TestDedicatedServerConnection connection) {
		RunRecord before = TestRuns.record(server);
		check(before.phase() == RunPhase.LOBBY,
				"this scenario starts between runs, and the save says " + before.describe());
		String wasIn = TestRuns.playerDimension(server, connection);

		TestRuns.withNoLobby(server, () -> {
			TestRuns.start(server);

			RunRecord after = TestRuns.record(server);
			check(after.phase() == RunPhase.LOBBY,
					"with no lobby dimension no run may start, and the save says " + after.describe());
			check(after.runId() == before.runId(),
					"a run that was refused before it began must not spend an id: it was "
							+ before.runId() + " and is now " + after.runId());

			String nowIn = TestRuns.playerDimension(server, connection);
			check(nowIn.equals(wasIn), "a refused run must leave the player where they were, and they"
					+ " were in " + wasIn + " and are now in " + nowIn);
		});

		// And with it back, the loop works again — so the refusal was the missing lobby and not
		// something this scenario broke on its way past.
		TestRuns.start(server);
		check(TestRuns.phase(server) == RunPhase.RUNNING,
				"with the lobby back a run must start again, and the save says "
						+ TestRuns.record(server).describe());
		TestRuns.end(server);
	}

	/**
	 * Logging out inside a run and coming back to a later one.
	 *
	 * <p>The case a dimension key cannot see. This player quits in {@code minecraft:overworld}
	 * during one run; that run ends and the next replaces the overworld under the same key. On
	 * their return the saved dimension says overworld and the server says the overworld is a run
	 * dimension, and nothing in that agreement is a reason to let them keep the last run's things.
	 */
	private void theNextRunDoesNotInheritTheLastOnes(
			ClientGameTestContext context, TestDedicatedServerContext server) {
		TestRuns.start(server);
		int leftDuring = TestRuns.record(server).runId();

		try (TestDedicatedServerConnection connection = server.connect()) {
			settle(context, connection);
			check(TestRuns.playerDimension(server, connection).equals("minecraft:overworld"),
					"this scenario needs the player inside the run, and they are in "
							+ TestRuns.playerDimension(server, connection));

			server.runCommand("give Player0 minecraft:diamond 5");
			server.runCommand("item replace entity Player0 enderchest.0 with minecraft:emerald 3");
			server.runCommand("xp set Player0 7 levels");
			TestRuns.settle(server);
			check(!TestRuns.runLocalStateOf(server).equals(TestRuns.NOTHING_CARRIED),
					"this scenario has to start with something to lose");
		}
		// Quit inside the run, in the overworld — not the lobby.
		TestRuns.waitForNobodyConnected(context, server);

		TestRuns.end(server);
		TestRuns.start(server);
		int cameBackTo = TestRuns.record(server).runId();
		check(cameBackTo > leftDuring,
				"the next run must be a different run: left during " + leftDuring + ", came back to "
						+ cameBackTo);

		try (TestDedicatedServerConnection connection = server.connect()) {
			settle(context, connection);

			String carried = TestRuns.runLocalStateOf(server);
			check(carried.equals(TestRuns.NOTHING_CARRIED),
					"a player who logged out in run " + leftDuring + " and came back in run "
							+ cameBackTo + " must arrive with nothing from the old one, and they have "
							+ carried);
			check(TestRuns.playerDimension(server, connection).equals("minecraft:overworld"),
					"and they still belong in the run");
		}
	}

	/**
	 * The other half, and the reason the reset cannot simply always happen: coming back to the run
	 * you were actually playing has to leave everything alone.
	 */
	private void theSameRunKeepsWhatYouHad(
			ClientGameTestContext context, TestDedicatedServerContext server) {
		check(TestRuns.phase(server) == RunPhase.RUNNING,
				"this scenario needs a run in progress, and the save says "
						+ TestRuns.record(server).describe());

		String before;
		try (TestDedicatedServerConnection connection = server.connect()) {
			settle(context, connection);
			server.runCommand("give Player0 minecraft:diamond 5");
			server.runCommand("item replace entity Player0 enderchest.0 with minecraft:emerald 3");
			server.runCommand("xp set Player0 7 levels");
			TestRuns.settle(server);

			before = TestRuns.runLocalStateOf(server);
			check(!before.equals(TestRuns.NOTHING_CARRIED), "something to keep");
		}
		TestRuns.waitForNobodyConnected(context, server);

		try (TestDedicatedServerConnection returned = server.connect()) {
			settle(context, returned);

			String after = TestRuns.runLocalStateOf(server);
			check(after.equals(before), "coming back to the run you were playing must keep what you"
					+ " had: " + before + " before, " + after + " after");
		}
	}

	/**
	 * Somebody logs in and there is no lobby to put them in.
	 *
	 * <p>The join used to ask for the lobby unconditionally, which throws for exactly the reason
	 * the save is in trouble — so the player's placement faulted and they never heard why. The
	 * point of this scenario is that joining still works: they stay connected, and they stay where
	 * they are, which is safe precisely because no run is in progress to delete it.
	 */
	private void joiningWithNoLobby(
			ClientGameTestContext context, TestDedicatedServerContext server) {
		if (TestRuns.phase(server) != RunPhase.LOBBY) {
			TestRuns.end(server);
		}
		check(TestRuns.phase(server) == RunPhase.LOBBY,
				"this scenario starts between runs, and the save says "
						+ TestRuns.record(server).describe());

		TestRuns.withNoLobby(server, () -> {
			try (TestDedicatedServerConnection connection = server.connect()) {
				settle(context, connection);

				String where = TestRuns.playerDimension(server, connection);
				check(!where.equals("nowhere (not connected)"),
						"joining a server with no lobby must not throw the player out, and they are "
								+ where);
				check(TestRuns.phase(server) == RunPhase.LOBBY,
						"and it must not have moved the loop on: " + TestRuns.record(server).describe());

				// The part that actually distinguishes handling it from faulting on it. Asking for
				// the missing lobby throws inside the placement task, which is survivable and
				// silent — the player is left standing there with no idea anything is wrong.
				check(wasToldOnTheClient(context, "no lobby on this server"),
						"the player must be told why nothing is happening, and the messages they"
								+ " have are " + clientMessages(context));
			}
		});
		TestRuns.waitForNobodyConnected(context, server);
	}

	/** Has the client been shown a message containing this? */
	private static boolean wasToldOnTheClient(ClientGameTestContext context, String fragment) {
		return clientMessages(context).contains(fragment);
	}

	private static String clientMessages(ClientGameTestContext context) {
		return context.computeOnClient(client -> {
			StringBuilder said = new StringBuilder();
			for (GuiMessage message : ((ChatComponentAccessor) client.gui.hud.getChat()).mhr$allMessages()) {
				said.append(message.content().getString()).append(" | ");
			}
			return said.toString();
		});
	}

	// --- plumbing --------------------------------------------------------------------------

	/**
	 * Count run endings on the server, once per process.
	 *
	 * <p>{@link RunEvents} is a static registry and this test runs in the same JVM as the server it
	 * drives, so registering twice would double every number this test asserts on.
	 */
	/**
	 * How long a client may take to follow the player somewhere, in client ticks.
	 *
	 * <p>The harness's own default is 200, ten seconds, and that is not enough here. A dimension
	 * change has to survive the server's chunk-load handshake, which vanilla itself allows thirty
	 * seconds for, on a pod that renders with llvmpipe and shares its CPU with whatever else is
	 * running. Two minutes' worth is not a guess at how long it takes — it is far enough past the
	 * longest observed arrival that a failure here means the client never arrived at all.
	 */
	private static final int ARRIVAL_TIMEOUT = 20 * 120;

	/**
	 * Wait for a freshly connected client to be properly in the world.
	 *
	 * <p>Rendered chunks are not the whole of it: the "Loading terrain" screen is still up while the
	 * server waits to hear that the client has loaded, and acting on a player before that has
	 * finished races with their own arrival.
	 */
	private static void settle(ClientGameTestContext context, TestDedicatedServerConnection connection) {
		connection.waitForChunksRender();
		context.waitFor(client -> client.gui.screen() == null, ARRIVAL_TIMEOUT);
		context.waitTicks(20);
	}

	/**
	 * Assert that the client itself has arrived in a dimension and drawn it.
	 *
	 * <p>Not only evidence, though it is that too: the server deciding a player is in the lobby is
	 * half the claim and the player seeing it is the other half, so this is an assertion rather
	 * than a best-effort wait.
	 *
	 * <p>Deliberately not {@code waitForChunksRender}. That waits for every chunk in render distance
	 * to have geometry, and the lobby is one bedrock plane in an empty biome — there is nothing out
	 * there to render, and waiting for it to appear is waiting for something that never happens.
	 * What matters is that the client is in the right world with the loading screen gone.
	 */
	private static void waitForClientIn(ClientGameTestContext context,
			TestDedicatedServerConnection connection, String dimension) {
		try {
			context.waitFor(client -> client.player != null
					&& client.player.level().dimension().identifier().toString().equals(dimension)
					&& client.gui.screen() == null, ARRIVAL_TIMEOUT);
			context.waitTicks(40);
		} catch (Throwable stuck) {
			String where = context.computeOnClient(client -> client.player == null
					? "nowhere (no player)"
					: client.player.level().dimension().identifier().toString());
			String screen = context.computeOnClient(client ->
					client.gui.screen() == null ? "none" : client.gui.screen().getClass().getSimpleName());
			throw new AssertionError("The client never followed the player into " + dimension
					+ ": it is in " + where + " with screen " + screen, stuck);
		}
	}

	/**
	 * Point the camera at the lobby floor before photographing it.
	 *
	 * <p>Arriving leaves the player looking level, and the lobby's horizon is a bedrock plane under
	 * an empty biome — which photographs as most of a sky and a grey band. Looking down at a block
	 * a few paces away puts the floor in the picture, which is the thing worth seeing.
	 */
	private static void frameTheLobby(ClientGameTestContext context, TestDedicatedServerContext server) {
		server.runCommand("time set noon");
		context.getInput().lookAt(Lobby.SPAWN.below().offset(4, 0, 4));
		context.waitTicks(10);
	}

	/**
	 * Has the client actually been sent the lobby?
	 *
	 * <p>The strongest client-side claim there is, and the one that catches the failure the
	 * dimension check does not: a player can be in the lobby as far as both sides' bookkeeping is
	 * concerned while the client has received none of its blocks, in which case they are standing
	 * in what looks to them like empty void. Asked about two blocks rather than one so that a
	 * single chunk arriving does not pass for the lobby having arrived.
	 */
	private static void checkClientSeesTheLobby(ClientGameTestContext context) {
		String under = context.computeOnClient(client ->
				client.level.getBlockState(Lobby.SPAWN.below()).getBlock().toString());
		String ahead = context.computeOnClient(client ->
				client.level.getBlockState(Lobby.SPAWN.below().offset(4, 0, 4)).getBlock().toString());
		LOGGER.info("Lobby as the client has it: under the player {}, four paces on {}", under, ahead);

		check(under.contains("bedrock") && ahead.contains("bedrock"),
				"the client must have been sent the lobby's floor, and where it should be bedrock it"
						+ " has " + under + " under the player and " + ahead + " four paces on —"
						+ " the player is standing in a lobby their client draws as empty void");
	}

	private static void countRunEndings(TestDedicatedServerContext server) {
		server.runOnServer(unused -> {
			if (listenersRegistered) {
				return;
			}
			listenersRegistered = true;

			// Order matters. The failing listeners go first so that a failed reward never reaches
			// the counter — which is what makes "committed once across a failure and a retry" a
			// question the counter can answer.
			RunEvents.RUN_STARTED.register((minecraftServer, overworld, run) -> {
				if (FAIL_RUN_START.get()) {
					throw new IllegalStateException("deliberate run-start failure");
				}
			});
			RunEvents.RUN_ENDED.register((minecraftServer, run) -> {
				if (FAIL_RUN_END.get()) {
					throw new IllegalStateException("deliberate reward failure");
				}
			});
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
