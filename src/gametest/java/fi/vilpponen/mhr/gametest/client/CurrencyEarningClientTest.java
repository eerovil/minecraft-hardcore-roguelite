package fi.vilpponen.mhr.gametest.client;

import fi.vilpponen.mhr.progression.Wallet;
import fi.vilpponen.mhr.run.RunPhase;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Earning the currency: who pays, how often, and whether the player can see it.
 *
 * <p>Every scenario here is an accounting one, so every scenario reads the purse immediately before
 * the thing it is testing and asserts the difference afterwards. Asking "is the balance 3?" would
 * pass or fail on anything else that happened to pay in the same run — including the test player
 * picking something up — and the claim being made is about one advancement, not about the total.
 *
 * <p>This needs a real player in a real run and a real advancement file, which is why it is a
 * client test rather than a server GameTest: the run lifecycle moves a player across a boundary,
 * and the advancement record being cleared as they cross it is half of the rule. The other half is
 * that the number is on the screen, which nothing without a screen can answer.
 *
 * <p>Two of the scenarios are about the same fact from opposite sides, and both are needed:
 *
 * <ul>
 *   <li><b>An advancement pays once.</b> {@code story/obtain_armor} is finished by any one of four
 *       criteria, so the other three are granted to an advancement that is already done. A hook
 *       that merely watched for "this call changed something and the advancement is finished" would
 *       pay four times for it.
 *   <li><b>The next run pays again.</b> Otherwise the whole economy stops after the first run,
 *       since a save's advancement record outlives the run it was earned in.
 * </ul>
 */
public class CurrencyEarningClientTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("mhr-gametest");

	/** A one-criterion advancement near the start of a run, worth 3 in the shipped catalogue. */
	private static final String MINE_STONE = "minecraft:story/mine_stone";
	private static final int MINE_STONE_PAYS = 3;

	/** Four criteria, any one of which finishes it. Worth 10, once. */
	private static final String OBTAIN_ARMOR = "minecraft:story/obtain_armor";
	private static final int OBTAIN_ARMOR_PAYS = 10;
	private static final String FIRST_ARMOUR_PIECE = "iron_helmet";
	private static final String SECOND_ARMOUR_PIECE = "iron_boots";

	/** In the game and not in the price list, which is true of most advancements. */
	private static final String UNPRICED = "minecraft:husbandry/root";

	private final List<String> failures = new ArrayList<>();

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestDedicatedServerContext server = context.worldBuilder().createServer()) {
			try (TestDedicatedServerConnection connection = server.connect()) {
				TestRuns.settleClient(context, connection);
				TestPlayer player = new TestPlayer(context, server, connection);
				TestShop shop = new TestShop(context, player);

				scenario(context, "an-advancement-pays-what-the-balance-table-says",
						() -> anAdvancementPaysWhatTheBalanceTableSays(context, server, player, shop));
				scenario(context, "an-advancement-finished-once-pays-once",
						() -> anAdvancementFinishedOncePaysOnce(context, server, player, shop));
				scenario(context, "an-advancement-the-table-does-not-list-pays-nothing",
						() -> anAdvancementTheTableDoesNotListPaysNothing(context, server, player, shop));
				scenario(context, "nothing-is-earned-outside-a-run",
						() -> nothingIsEarnedOutsideARun(context, server, player, shop));
				scenario(context, "the-next-run-earns-the-same-advancements-again",
						() -> theNextRunEarnsTheSameAdvancementsAgain(context, server, player, shop));
				scenario(context, "the-purse-is-on-the-screen-while-the-run-is-played",
						() -> thePurseIsOnTheScreenWhileTheRunIsPlayed(context, server, player, shop));
			}
		}

		if (!failures.isEmpty()) {
			throw new AssertionError(failures.size() + " currency earning scenario(s) failed:\n  "
					+ String.join("\n  ", failures));
		}
		LOGGER.info("All currency earning scenarios passed.");
	}

	/** The amount is the balance file's, and it reaches the permanent snapshot rather than memory. */
	private void anAdvancementPaysWhatTheBalanceTableSays(ClientGameTestContext context,
			TestDedicatedServerContext server, TestPlayer player, TestShop shop) {
		startFresh(context, server, player, shop);

		int before = purse(server);
		grant(player, MINE_STONE);
		int after = purse(server);

		check(after == before + MINE_STONE_PAYS, "finishing " + MINE_STONE + " must pay the "
				+ MINE_STONE_PAYS + " the balance table prices it at: the purse went from "
				+ before + " to " + after);
		int onDisk = server.computeOnServer(unused -> Wallet.reloadFromFile().balance());
		check(onDisk == after, "and it must be in the progression snapshot, not only in memory:"
				+ " the file says " + onDisk + " and the running game says " + after);
	}

	/** Both ways an advancement can be finished twice, and neither pays twice. */
	private void anAdvancementFinishedOncePaysOnce(ClientGameTestContext context,
			TestDedicatedServerContext server, TestPlayer player, TestShop shop) {
		startFresh(context, server, player, shop);

		int before = purse(server);
		grant(player, MINE_STONE);
		int afterFirst = purse(server);
		grant(player, MINE_STONE);
		int afterSecond = purse(server);
		check(afterSecond == afterFirst, "granting " + MINE_STONE + " again must pay nothing:"
				+ " the purse went from " + afterFirst + " to " + afterSecond);

		// The real trap: the other three criteria of an advancement one criterion already finished.
		grant(player, OBTAIN_ARMOR, FIRST_ARMOUR_PIECE);
		int afterArmour = purse(server);
		check(afterArmour == afterSecond + OBTAIN_ARMOR_PAYS, "finishing " + OBTAIN_ARMOR
				+ " must pay " + OBTAIN_ARMOR_PAYS + ": the purse went from " + afterSecond
				+ " to " + afterArmour);

		grant(player, OBTAIN_ARMOR, SECOND_ARMOUR_PIECE);
		int afterMoreArmour = purse(server);
		check(afterMoreArmour == afterArmour, "a second criterion of an advancement that is already"
				+ " finished must pay nothing: the purse went from " + afterArmour + " to "
				+ afterMoreArmour);

		check(afterMoreArmour == before + MINE_STONE_PAYS + OBTAIN_ARMOR_PAYS,
				"and the whole scenario must have paid exactly "
						+ (MINE_STONE_PAYS + OBTAIN_ARMOR_PAYS) + ": the purse went from " + before
						+ " to " + afterMoreArmour);
	}

	private void anAdvancementTheTableDoesNotListPaysNothing(ClientGameTestContext context,
			TestDedicatedServerContext server, TestPlayer player, TestShop shop) {
		startFresh(context, server, player, shop);

		int before = purse(server);
		grant(player, UNPRICED);
		int after = purse(server);

		check(after == before, UNPRICED + " is not in the price list and must pay nothing:"
				+ " the purse went from " + before + " to " + after);
	}

	/** The lobby is where the shop is. An advancement finished there is not a run's work. */
	private void nothingIsEarnedOutsideARun(ClientGameTestContext context,
			TestDedicatedServerContext server, TestPlayer player, TestShop shop) {
		startFresh(context, server, player, shop);
		endTheRun(context, server);

		int before = purse(server);
		grant(player, MINE_STONE);
		int after = purse(server);

		check(after == before, "an advancement finished in the lobby must pay nothing:"
				+ " the purse went from " + before + " to " + after);
	}

	/**
	 * The one that makes the economy an economy: a run is a new world, so its advancements are
	 * earnable again. Without this a profile could only ever be paid for each advancement once.
	 */
	private void theNextRunEarnsTheSameAdvancementsAgain(ClientGameTestContext context,
			TestDedicatedServerContext server, TestPlayer player, TestShop shop) {
		startFresh(context, server, player, shop);

		int before = purse(server);
		grant(player, MINE_STONE);
		int afterRunOne = purse(server);
		check(afterRunOne == before + MINE_STONE_PAYS, "run 1 must pay for " + MINE_STONE
				+ ": the purse went from " + before + " to " + afterRunOne);

		endTheRun(context, server);
		startARun(context, server);

		grant(player, MINE_STONE);
		int afterRunTwo = purse(server);
		check(afterRunTwo == afterRunOne + MINE_STONE_PAYS, "and run 2 must pay for it again,"
				+ " because run 2 is a new world: the purse went from " + afterRunOne + " to "
				+ afterRunTwo);
	}

	/** What the player can see: the same number, on the client, without opening anything. */
	private void thePurseIsOnTheScreenWhileTheRunIsPlayed(ClientGameTestContext context,
			TestDedicatedServerContext server, TestPlayer player, TestShop shop) {
		startFresh(context, server, player, shop);

		grant(player, MINE_STONE);
		grant(player, OBTAIN_ARMOR, FIRST_ARMOUR_PIECE);
		player.settle();

		int onTheServer = purse(server);
		check(onTheServer == MINE_STONE_PAYS + OBTAIN_ARMOR_PAYS, "the run must have paid "
				+ (MINE_STONE_PAYS + OBTAIN_ARMOR_PAYS) + " and the server says " + onTheServer);
		check(shop.currencyOnScreen() == onTheServer, "and the client must have been told at once,"
				+ " with no screen open: the client says " + shop.currencyOnScreen()
				+ " and the server says " + onTheServer);

		// The HUD draws that number, so the shot is of a client that has just been paid twice.
		context.takeScreenshot("currency-hud-during-a-run");
	}

	/**
	 * The starting point every scenario establishes for itself: nothing owned, nothing in the purse,
	 * no advancements, and a run in progress.
	 */
	private void startFresh(ClientGameTestContext context, TestDedicatedServerContext server,
			TestPlayer player, TestShop shop) {
		if (TestRuns.phase(server) == RunPhase.RUNNING) {
			endTheRun(context, server);
		}
		shop.resetProgression();
		startARun(context, server);
		// After the run has started, so this is the record the run itself will be earning against.
		player.command("advancement revoke Player0 everything");
	}

	private void startARun(ClientGameTestContext context, TestDedicatedServerContext server) {
		TestRuns.start(server);
		TestRuns.waitForPhase(context, server, RunPhase.RUNNING);
		TestRuns.settle(server);
	}

	private void endTheRun(ClientGameTestContext context, TestDedicatedServerContext server) {
		TestRuns.end(server);
		TestRuns.waitForPhase(context, server, RunPhase.LOBBY);
		TestRuns.settle(server);
	}

	private void grant(TestPlayer player, String advancement) {
		player.command("advancement grant Player0 only " + advancement);
	}

	private void grant(TestPlayer player, String advancement, String criterion) {
		player.command("advancement grant Player0 only " + advancement + " " + criterion);
	}

	private int purse(TestDedicatedServerContext server) {
		return server.computeOnServer(unused -> Wallet.get().balance());
	}

	private void scenario(ClientGameTestContext context, String name, Runnable body) {
		LOGGER.info("=== scenario {} ===", name);
		try {
			body.run();
			LOGGER.info("=== scenario {} PASS ===", name);
		} catch (Throwable failure) {
			LOGGER.error("=== scenario {} FAIL: {} ===", name, failure.getMessage());
			failures.add(name + ": " + failure.getMessage());
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
