package fi.vilpponen.mhr.gametest.client;

import fi.vilpponen.mhr.UnlockState;
import fi.vilpponen.mhr.border.BorderTier;
import fi.vilpponen.mhr.core.BalanceManager;
import fi.vilpponen.mhr.progression.Wallet;
import fi.vilpponen.mhr.run.Lobby;
import fi.vilpponen.mhr.run.RunPhase;
import fi.vilpponen.mhr.run.RunRecord;
import fi.vilpponen.mhr.run.RunStorage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One whole turn of the roguelite: lobby, a restricted run, a death, the shop, and a second run that
 * is visibly better for what was bought in between.
 *
 * <p>Every piece of this is covered somewhere else already — the lifecycle in {@link
 * RunLifecycleClientTest}, the screen in {@link ShopClientTest}, the chest in {@link
 * StarterChestClientTest}, the border in {@code WorldBorderGameTest}. What none of them asks is
 * whether the pieces are one loop. The seam this exists for is the one in the middle: currency
 * earned inside a run that is about to be deleted, spent on a screen in the world that is never
 * deleted, and collected in the world after that.
 *
 * <p>So the scenarios run <b>in order and depend on each other on purpose</b>, which is the one
 * place this repository's usual rule has to bend: a cycle is a sequence, and a scenario that
 * re-established its own starting point would be testing the step rather than the loop. The first
 * scenario is the only one that establishes state, and it establishes it for all of them. Each of
 * the rest checks the previous step actually happened before asking its own question, so a failure
 * names the step that broke rather than cascading as five unrelated ones.
 *
 * <p>Three claims are asked the hard way, because each has a way of passing for the wrong reason:
 *
 * <ul>
 *   <li><b>Run 2 is a different world.</b> A diamond block goes into each of run 1's three
 *       dimensions at a fixed coordinate that no generator produces. If one is still standing in run
 *       2, run 2 is playing run 1's chunks.
 *   <li><b>The purchases changed run 2 rather than merely being remembered.</b> Both are read out of
 *       the world: the starter chest is a block at run 2's spawn holding sixteen bread, and the
 *       border is the size of the world the player is standing in. Run 1 is the control for both —
 *       no chest at all, and the tiny tier — so neither can pass because a chest is placed at every
 *       run start or because the border never moves.
 *   <li><b>Nothing was bought by the test.</b> Both purchases are the cursor landing on a square and
 *       the left mouse button going down, charged against currency that came from inside run 1.
 * </ul>
 *
 * <p>Both runs are generated from a named seed. This test asserts what is standing around each
 * run's spawn — no chest in run 1, exactly one holding sixteen bread in run 2 — and a scenario
 * that asks about terrain has to say which terrain it means, or it is asking a different
 * question every time it runs. {@code /mhr run start <seed>} exists for this.
 *
 * <p>Naming them costs the freshness claim nothing, which is the thing that looks wrong at first
 * glance. "Run 2 is a different world" is never read off the argument: it is the record's own
 * seed differing, the overworld reporting that seed, and run 1's three marker blocks being gone.
 * Two distinct fixed seeds prove that exactly as well as two random ones, and prove it the same
 * way on every run.
 *
 * <p>See {@code docs/dev-environment.md} for how to run this, and {@code docs/manual-smoke-test.md}
 * for the same loop done by hand.
 */
public class ProgressionCycleClientTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("mhr-gametest");

	/** Somewhere no generator puts a diamond block, in every dimension involved. */
	private static final BlockPos MARK = new BlockPos(0, 100, 0);
	private static final BlockPos LOBBY_MARK = new BlockPos(0, -60, 0);
	private static final BlockState MARKER = Blocks.DIAMOND_BLOCK.defaultBlockState();

	/** How far from the run's spawn to go looking for the starter chest. It lands within three. */
	private static final int CHEST_SEARCH_RADIUS = 10;

	/**
	 * The two purchases, chosen because each one is visible in the world rather than only in a file.
	 *
	 * <p>The bread becomes a chest at the next run's spawn; the border tier becomes the size of the
	 * next run's world. Both are cheap, so one run's worth of test currency covers them with change
	 * left over to prove the purse was charged and not emptied.
	 */
	private static final String BREAD = "starter.bread";
	private static final String MEDIUM_BORDER = "world.border.medium";

	/** What sixteen bread looks like in a chest. From the bundled catalogue. */
	private static final Map<String, Integer> BREAD_IN_A_CHEST = Map.of("minecraft:bread", 16);

	/** How much a run pays in this test. Enough for both purchases and some change. */
	private static final int EARNED_IN_RUN_ONE = 20;

	/**
	 * The two worlds this test is about, named so they are the same two every run.
	 *
	 * <p>Distinct, because run 2 differing from run 1 is one of the things being proved. Arbitrary
	 * otherwise — nothing here wants a particular biome, only the same one twice.
	 */
	private static final long RUN_ONE_SEED = 45_000_001L;
	private static final long RUN_TWO_SEED = 45_000_002L;

	private final List<String> failures = new ArrayList<>();

	/** What each step hands to the next. A cycle is a sequence; see the class comment. */
	private boolean lobbyReached;
	private boolean runOneRan;
	private boolean backInTheLobby;
	private boolean purchasesMade;
	private long firstSeed;
	private double runOneBorder;
	private int purseAfterBuying;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestDedicatedServerContext server = context.worldBuilder().createServer()) {
			try (TestDedicatedServerConnection connection = server.connect()) {
				TestRuns.settleClient(context, connection);
				TestPlayer player = new TestPlayer(context, server, connection);
				TestShop shop = new TestShop(context, player);

				scenario(context, "the-cycle-starts-in-the-lobby-with-an-empty-profile",
						() -> theCycleStartsInTheLobby(context, server, connection, shop));
				scenario(context, "run-one-is-as-restricted-as-an-empty-profile-makes-it",
						() -> runOneIsRestricted(context, server, connection));
				scenario(context, "dying-ends-the-run-and-leaves-the-currency-behind",
						() -> dyingEndsTheRunAndKeepsTheCurrency(context, server, connection));
				scenario(context, "the-shop-turns-that-currency-into-permanent-unlocks",
						() -> theShopTurnsCurrencyIntoUnlocks(context, shop));
				scenario(context, "run-two-is-a-fresh-world-that-has-what-was-bought",
						() -> runTwoIsFreshAndBetter(context, server, connection, shop));
				scenario(context, "progression-outlived-both-runs-and-the-runs-did-not",
						() -> progressionOutlivedBothRuns(context, server, connection));
			}
		}

		if (!failures.isEmpty()) {
			throw new AssertionError(failures.size() + " progression-cycle scenario(s) failed:\n  "
					+ String.join("\n  ", failures));
		}
		LOGGER.info("All progression-cycle client scenarios passed.");
	}

	// --- the cycle -----------------------------------------------------------------------------

	/**
	 * Step one: a save nobody has played, and a profile that has bought nothing.
	 *
	 * <p>The profile is emptied here rather than assumed empty. Permanent progression lives outside
	 * every world and is shared by every client test in this process, so whichever of them ran first
	 * may have left purchases behind — and this whole cycle is about the difference between owning
	 * something and not.
	 *
	 * <p>The lobby is marked here too, before any run has existed, so the block that proves the lobby
	 * survived cannot have been put there by anything a run did.
	 */
	private void theCycleStartsInTheLobby(ClientGameTestContext context,
			TestDedicatedServerContext server, TestDedicatedServerConnection connection, TestShop shop) {
		shop.resetProgression();

		check(shop.balanceOnServer() == 0,
				"the cycle starts with an empty purse, and it holds " + shop.balanceOnServer());
		check(!shop.ownedOnServer(BREAD) && !shop.ownedOnServer(MEDIUM_BORDER),
				"and with nothing bought, and it owns " + ownedIds(server));

		RunRecord record = TestRuns.record(server);
		check(record.phase() == RunPhase.LOBBY,
				"a save nobody has played must be between runs, and it says " + record.describe());
		check(record.runId() == 0 && record.completedRuns() == 0,
				"no run has happened yet, and the record says " + record.describe());

		check(TestRuns.playerIsInTheLobby(server, connection),
				"a player joining with no run in progress must arrive in the lobby, and they are in "
						+ TestRuns.playerDimension(server, connection));

		TestRuns.mark(server, Lobby.LEVEL, LOBBY_MARK, MARKER);
		lobbyReached = true;
	}

	/**
	 * Step two: the run an empty profile buys, which is a run missing the things nobody has bought.
	 *
	 * <p>Both restrictions are read out of the world rather than out of the unlock file, and both are
	 * the control for the matching assertion in run 2. A test that only asked run 2 "is there a chest"
	 * would pass just as happily against a mod that put a chest at every run start.
	 *
	 * <p>The currency is granted through {@code /mhr currency give}, which is the supported stand-in:
	 * nothing in gameplay pays out yet, deliberately, because how currency is earned is still an open
	 * design question. What the cycle needs from it is only that the money arrives while the run that
	 * is about to be destroyed is the one in progress.
	 */
	private void runOneIsRestricted(ClientGameTestContext context,
			TestDedicatedServerContext server, TestDedicatedServerConnection connection) {
		check(lobbyReached, "the cycle never reached the lobby, so there is no run to start");

		TestRuns.start(server, RUN_ONE_SEED);
		connection.waitForChunksRender();

		RunRecord record = TestRuns.record(server);
		check(record.phase() == RunPhase.RUNNING && record.runId() == 1,
				"the first run must be run 1 and in progress, and the record says " + record.describe());
		// The terrain assertions below are about one particular world, so the run has to have
		// taken the seed it was handed rather than rolled its own.
		check(record.seed() == RUN_ONE_SEED,
				"run 1 must be generated from the seed the test named, " + RUN_ONE_SEED
						+ ", and the record says " + record.seed());
		check(TestRuns.playerDimension(server, connection).equals("minecraft:overworld"),
				"a run starts in its overworld, and the player is in "
						+ TestRuns.playerDimension(server, connection));

		// Restriction one: no starter items bought, so no chest — not an empty chest, none at all.
		BlockPos spawn = TestRuns.runSpawn(server);
		List<BlockPos> chests = chestsNear(server, Level.OVERWORLD, spawn);
		check(chests.isEmpty(), "with no starter item bought there must be no starter chest at the"
				+ " run's spawn " + spawn + ", and there are " + chests.size() + " at " + chests);

		// Restriction two: no border tier bought, so the run is fenced into the tiny one.
		runOneBorder = borderSize(server);
		double tiny = tierSize(context, BorderTier.TINY.id());
		check(Math.abs(runOneBorder - tiny) < 1.0,
				"with no border tier bought the run must be fenced into the tiny tier, " + tiny
						+ " across, and the world is " + runOneBorder + " across");

		// The three worlds this run owns, marked so run 2 can be asked whether it reused them.
		for (var key : List.of(Level.OVERWORLD, Level.NETHER, Level.END)) {
			check(server.computeOnServer(minecraftServer -> minecraftServer.getLevel(key) != null),
					"a run needs its own " + key.identifier() + ", and there is none");
			TestRuns.mark(server, key, MARK, MARKER);
		}

		// What the run gives the player, all of which is the run's and none of which may outlive it.
		server.runCommand("give Player0 minecraft:diamond 5");
		server.runCommand("give Player0 minecraft:chest 2");
		server.runCommand("item replace entity Player0 enderchest.0 with minecraft:emerald 3");
		server.runCommand("xp set Player0 7 levels");
		server.runCommand("spawnpoint Player0 ~ ~ ~");
		TestRuns.settle(server);

		String carried = TestRuns.runLocalStateOf(server);
		check(!carried.equals(TestRuns.NOTHING_CARRIED),
				"setup: the run has to give the player something to lose, and they have " + carried);

		// And the one thing it gives them that is not the run's: the money.
		server.runCommand("mhr currency give " + EARNED_IN_RUN_ONE);
		TestRuns.settle(server);
		int purse = server.computeOnServer(unused -> Wallet.get().balance());
		check(purse == EARNED_IN_RUN_ONE,
				"the run must have paid " + EARNED_IN_RUN_ONE + ", and the purse holds " + purse);

		firstSeed = record.seed();
		LOGGER.info("Run 1: seed {}, spawn {}, border {} across", firstSeed, spawn, runOneBorder);
		TestRuns.waitForClientIn(context, Level.OVERWORLD.identifier().toString());
		context.takeScreenshot("cycle-run-one-restricted");
		runOneRan = true;
	}

	/**
	 * Step three: the death, which is the whole of the hardcore rule and the whole of the meta step.
	 *
	 * <p>Killed with the ordinary command so the path under test is the one a real death takes, and
	 * the two halves that make the loop a loop are asked either side of it: the run does not follow
	 * the player into the lobby, and the money does.
	 *
	 * <p>The lobby is asked as well as the player. A strip that dropped the run's items on the lobby
	 * floor instead of destroying them would satisfy "the player arrived with nothing" and lose the
	 * point — the lobby is the one world that is never deleted, so anything of a run's that reaches it
	 * is there for good.
	 */
	private void dyingEndsTheRunAndKeepsTheCurrency(ClientGameTestContext context,
			TestDedicatedServerContext server, TestDedicatedServerConnection connection) {
		check(runOneRan, "run 1 never started, so there is no death to test");

		server.runCommand("kill Player0");
		TestRuns.waitForPhase(context, server, RunPhase.LOBBY);

		RunRecord record = TestRuns.record(server);
		check(record.phase() == RunPhase.LOBBY,
				"a death must put the save back between runs, and it says " + record.describe());
		check(record.completedRuns() == 1,
				"the run that just ended must be counted once, and the record counts "
						+ record.completedRuns());

		check(TestRuns.playerIsInTheLobby(server, connection),
				"a death must return the player to the lobby rather than to a game-over screen, and"
						+ " they are in " + TestRuns.playerDimension(server, connection));
		float health = TestRuns.playerHealth(server, connection);
		check(health > 0.0F, "and they must arrive alive, and they have " + health + " health");

		String carried = TestRuns.runLocalStateOf(server);
		check(carried.equals(TestRuns.NOTHING_CARRIED),
				"nothing the run gave them may arrive in the world that is never deleted, and they"
						+ " arrived with " + carried);
		String lobby = TestRuns.runLeftoversInTheLobby(server);
		check(lobby.equals(TestRuns.LOBBY_UNTOUCHED),
				"nor may it have been dropped on the way in — emptying a player onto the lobby floor is"
						+ " not emptying anybody. The lobby holds " + lobby);

		// The half that has to survive, read through the file rather than out of memory.
		int purse = server.computeOnServer(unused -> Wallet.reloadFromFile().balance());
		check(purse == EARNED_IN_RUN_ONE,
				"what the run paid must outlive the run: the purse should still hold "
						+ EARNED_IN_RUN_ONE + " and the file says " + purse);

		TestRuns.waitForClientIn(context, Lobby.LEVEL.identifier().toString());
		backInTheLobby = true;
	}

	/**
	 * Step four: the meta step. A real screen, a real mouse, and money that came out of a dead run.
	 *
	 * <p>Nothing here calls a purchase helper or writes an unlock. Both buys are the cursor landing on
	 * the square the player can see and the left button going down, which is the only part of this
	 * cycle that a server test could not have done.
	 */
	private void theShopTurnsCurrencyIntoUnlocks(ClientGameTestContext context, TestShop shop) {
		check(backInTheLobby, "the run never ended, so there is no shop step to take");

		int breadPrice = shop.priceOf(BREAD);
		int borderPrice = shop.priceOf(MEDIUM_BORDER);
		int before = shop.balanceOnServer();
		check(before >= breadPrice + borderPrice,
				"setup: one run's pay should cover both purchases, and " + before + " does not cover "
						+ (breadPrice + borderPrice));

		shop.open();
		check(shop.currencyOnScreen() == before,
				"the screen must be showing the money the run paid, and it shows "
						+ shop.currencyOnScreen() + " against " + before);

		shop.clickSquare(BREAD);
		check(shop.ownedOnServer(BREAD),
				"clicking an affordable square must buy it, and the server does not own " + BREAD);
		check(shop.balanceOnServer() == before - breadPrice,
				"and must charge exactly " + breadPrice + ": the purse went from " + before + " to "
						+ shop.balanceOnServer());

		shop.clickSquare(MEDIUM_BORDER);
		check(shop.ownedOnServer(MEDIUM_BORDER),
				"and the second square must buy its tier, and the server does not own " + MEDIUM_BORDER);

		purseAfterBuying = shop.balanceOnServer();
		check(purseAfterBuying == before - breadPrice - borderPrice,
				"two purchases must charge twice and no more: the purse went from " + before + " to "
						+ purseAfterBuying + " against an expected "
						+ (before - breadPrice - borderPrice));
		check(purseAfterBuying > 0,
				"setup: there should be change left over, so the next step is asking about a purse that"
						+ " was charged rather than one that was emptied");
		check(shop.currencyOnScreen() == purseAfterBuying,
				"and the player must be shown the new total at once: the screen says "
						+ shop.currencyOnScreen() + " and the server says " + purseAfterBuying);
		check(shop.levelOnScreen(BREAD) == 1 && shop.levelOnScreen(MEDIUM_BORDER) == 1,
				"and both squares must show as owned, and they show " + shop.levelOnScreen(BREAD)
						+ " and " + shop.levelOnScreen(MEDIUM_BORDER));

		context.takeScreenshot("cycle-shop-after-buying-with-run-one-pay");
		shop.close();
		purchasesMade = true;
	}

	/**
	 * Step five: the payoff. A brand-new world, and it is a better world than the last one.
	 *
	 * <p>"Fresh" and "better" are separate claims and both are asked. Fresh is run 1's block being
	 * gone from all three dimensions and the seed having changed. Better is the two purchases showing
	 * up as things in the world — a chest with bread in it, and a bigger fence — measured against what
	 * run 1 had, which was neither.
	 */
	private void runTwoIsFreshAndBetter(ClientGameTestContext context,
			TestDedicatedServerContext server, TestDedicatedServerConnection connection, TestShop shop) {
		check(purchasesMade, "nothing was bought, so there is nothing for run 2 to be better for");

		TestRuns.start(server, RUN_TWO_SEED);
		connection.waitForChunksRender();

		RunRecord record = TestRuns.record(server);
		check(record.phase() == RunPhase.RUNNING && record.runId() == 2,
				"the second run must be run 2 and in progress, and the record says " + record.describe());

		check(record.seed() == RUN_TWO_SEED,
				"run 2 must be generated from the seed the test named, " + RUN_TWO_SEED
						+ ", and the record says " + record.seed());

		// Fresh: a different seed, and none of run 1's chunks under any of the three dimension
		// keys. Asked of the record rather than taken from the argument — the point is that the
		// loop really moved to another world, not that the test asked it to.
		check(record.seed() != firstSeed,
				"each run must be generated from a fresh seed, and run 2 reused " + firstSeed);
		long overworldSeed = server.computeOnServer(minecraftServer ->
				minecraftServer.overworld().getSeed());
		check(overworldSeed == record.seed(),
				"and run 2's overworld must be generated from run 2's seed: the record says "
						+ record.seed() + " and the world says " + overworldSeed);
		for (var key : List.of(Level.OVERWORLD, Level.NETHER, Level.END)) {
			check(!TestRuns.isMarked(server, key, MARK, MARKER),
					"run 1's block is still standing in " + key.identifier()
							+ ", so run 2 is playing run 1's chunks");
		}

		// Better, one: the bread bought in the lobby is a chest at this run's spawn.
		BlockPos spawn = TestRuns.runSpawn(server);
		List<BlockPos> chests = chestsNear(server, Level.OVERWORLD, spawn);
		check(chests.size() == 1, "the starter item bought between runs must arrive as exactly one"
				+ " chest at run 2's spawn " + spawn + ", and there are " + chests.size()
				+ " at " + chests);
		Map<String, Integer> contents = contentsOf(server, chests.getFirst());
		check(contents.equals(BREAD_IN_A_CHEST),
				"and it must hold what was bought, " + BREAD_IN_A_CHEST + ", and it holds " + contents);

		// Better, two: the tier bought in the lobby is the size of the world underfoot.
		double medium = tierSize(context, BorderTier.MEDIUM.id());
		double now = borderSize(server);
		check(Math.abs(now - medium) < 1.0,
				"the border tier bought between runs must be the size of run 2's world, " + medium
						+ " across, and it is " + now + " across");
		check(now > runOneBorder,
				"and that must be an improvement on run 1, which was " + runOneBorder + " across");

		check(TestRuns.playerDimension(server, connection).equals("minecraft:overworld"),
				"and the player must be in it, and they are in "
						+ TestRuns.playerDimension(server, connection));

		LOGGER.info("Run 2: seed {}, spawn {}, border {} across, chest {}",
				record.seed(), spawn, now, contents);
		TestRuns.waitForClientIn(context, Level.OVERWORLD.identifier().toString());
		lookAt(context, server, connection, chests.getFirst());
		context.takeScreenshot("cycle-run-two-with-what-was-bought");
	}

	/**
	 * Step six: the accounting, read off the disk rather than out of memory.
	 *
	 * <p>The dedicated server shares this process with the client, so asking the live objects would be
	 * asking the same objects that were written. Both permanent files are reloaded instead: the
	 * progression snapshot, which must still hold both purchases and the change, and the save's run
	 * record, which must have survived having two worlds deleted under it.
	 *
	 * <p>The lobby is asked last, because it is the one thing in the save that was never supposed to
	 * move, and the block put in it before run 1 existed is the only witness to that.
	 */
	private void progressionOutlivedBothRuns(ClientGameTestContext context,
			TestDedicatedServerContext server, TestDedicatedServerConnection connection) {
		check(purchasesMade, "nothing was bought, so there is nothing to have survived");

		int purse = server.computeOnServer(unused -> Wallet.reloadFromFile().balance());
		check(purse == purseAfterBuying,
				"the change from the purchases must be on the disk: it should be " + purseAfterBuying
						+ " and the file says " + purse);
		boolean bothOwned = server.computeOnServer(unused -> {
			UnlockState owned = UnlockState.reloadFromFile();
			return owned.isOwned(BREAD) && owned.isOwned(MEDIUM_BORDER);
		});
		check(bothOwned, "both purchases must outlive the worlds they were bought between, and the"
				+ " file says the profile owns " + ownedIds(server));

		RunRecord onDisk = server.computeOnServer(minecraftServer ->
				new RunStorage(minecraftServer.getWorldPath(LevelResource.ROOT)).load());
		check(onDisk.runId() == 2 && onDisk.completedRuns() == 1,
				"the save's own record must have survived deleting two runs' worlds, and it says "
						+ onDisk.describe());

		check(TestRuns.isMarked(server, Lobby.LEVEL, LOBBY_MARK, MARKER),
				"the block put in the lobby before run 1 existed must still be there after two runs"
						+ " were built and one deleted, and it is not");
	}

	// --- looking at the world ------------------------------------------------------------------

	/** Every chest block standing near a point. */
	private static List<BlockPos> chestsNear(TestDedicatedServerContext server,
			ResourceKey<Level> dimension, BlockPos middle) {
		return server.computeOnServer(minecraftServer -> {
			ServerLevel level = minecraftServer.getLevel(dimension);
			List<BlockPos> found = new ArrayList<>();
			for (BlockPos pos : BlockPos.betweenClosed(
					middle.offset(-CHEST_SEARCH_RADIUS, -CHEST_SEARCH_RADIUS, -CHEST_SEARCH_RADIUS),
					middle.offset(CHEST_SEARCH_RADIUS, CHEST_SEARCH_RADIUS, CHEST_SEARCH_RADIUS))) {
				if (level.getBlockState(pos).is(Blocks.CHEST)) {
					found.add(pos.immutable());
				}
			}
			return found;
		});
	}

	/** What is in the chest at this position, item by item. */
	private static Map<String, Integer> contentsOf(
			TestDedicatedServerContext server, BlockPos pos) {
		return server.computeOnServer(minecraftServer -> {
			ServerLevel level = minecraftServer.overworld();
			Map<String, Integer> contents = new LinkedHashMap<>();
			if (!(level.getBlockEntity(pos) instanceof ChestBlockEntity chest)) {
				throw new AssertionError("No chest to read at " + pos + ", the block there is "
						+ level.getBlockState(pos));
			}
			for (int slot = 0; slot < chest.getContainerSize(); slot++) {
				ItemStack stack = chest.getItem(slot);
				if (!stack.isEmpty()) {
					contents.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
							stack.getCount(), Integer::sum);
				}
			}
			return contents;
		});
	}

	/** How big the world the player is standing in is, as the server has it. */
	private static double borderSize(TestDedicatedServerContext server) {
		return server.computeOnServer(minecraftServer ->
				minecraftServer.overworld().getWorldBorder().getSize());
	}

	/** How big the catalogue says a tier is, so the assertion is not a hard-coded number. */
	private static double tierSize(ClientGameTestContext context, String tierId) {
		return context.computeOnClient(client -> BalanceManager.get()
				.border(tierId).orElseThrow().size().getAsDouble());
	}

	/** Which ids the profile owns, for a failure message worth reading. */
	private static String ownedIds(TestDedicatedServerContext server) {
		return server.computeOnServer(unused -> UnlockState.get().ownedIds().toString());
	}

	/**
	 * Stands the player two blocks from something and points them at it, for the evidence shot.
	 *
	 * <p>Two blocks and not three: vanilla's reach is shorter than the three blocks the starter chest
	 * may be placed away, and the same position is used to see it and to reach it.
	 */
	private static void lookAt(ClientGameTestContext context, TestDedicatedServerContext server,
			TestDedicatedServerConnection connection, BlockPos target) {
		server.runCommand("time set noon");
		server.runCommand("weather clear");
		server.runCommand("execute in minecraft:overworld run tp Player0 " + (target.getX() + 0.5)
				+ " " + target.getY() + " " + (target.getZ() + 2.5));
		connection.waitForChunksRender();
		context.waitTicks(5);
		context.getInput().lookAt(target);
		context.waitTicks(5);
	}

	// --- plumbing ------------------------------------------------------------------------------

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
