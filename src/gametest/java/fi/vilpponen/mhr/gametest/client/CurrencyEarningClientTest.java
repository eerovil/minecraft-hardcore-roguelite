package fi.vilpponen.mhr.gametest.client;

import fi.vilpponen.mhr.progression.Progress;
import fi.vilpponen.mhr.progression.Wallet;
import fi.vilpponen.mhr.run.RunPhase;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
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
 * <p>Five of the scenarios are about the same fact from different sides, and all five are needed:
 *
 * <ul>
 *   <li><b>A crash cannot pay twice.</b> A player's advancements are saved on Minecraft's own
 *       schedule rather than the wallet's, so the scenario reads the snapshot back off disk and
 *       then revokes the advancement — which is what that record comes back as when a crash lands
 *       between the two saves — and finishes it again.
 *   <li><b>An advancement pays once.</b> {@code story/obtain_armor} is finished by any one of four
 *       criteria, so the other three are granted to an advancement that is already done. A hook
 *       that merely watched for "this call changed something and the advancement is finished" would
 *       pay four times for it.
 *   <li><b>The next run pays again.</b> Otherwise the whole economy stops after the first run,
 *       since a save's advancement record outlives the run it was earned in.
 *   <li><b>A refused write does not spend the milestone.</b> An advancement is finished once, so a
 *       payout the disk will not take has to un-earn it rather than leave it standing — otherwise
 *       the player has spent the only chance that run had to pay for it.
 *   <li><b>A stale advancement file does not either.</b> The reset that clears a run's advancements
 *       and the note saying which run the player is in are two files saved at different times, so a
 *       crash can leave the note durable and the reset not. Joining reconciles the paying
 *       advancements against the ledger rather than believing the note.
 * </ul>
 */
public class CurrencyEarningClientTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("mhr-gametest");

	/** A one-criterion advancement near the start of a run, worth 3 in the shipped catalogue. */
	private static final String MINE_STONE = "minecraft:story/mine_stone";
	private static final int MINE_STONE_PAYS = 3;

	/** Its one criterion, for the scenarios that finish it the way a trigger does. */
	private static final String MINE_STONE_CRITERION = "get_stone";

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
				scenario(context, "a-crash-cannot-mint-the-same-payout-twice",
						() -> aCrashCannotMintTheSamePayoutTwice(context, server, player, shop));
				scenario(context, "a-payout-the-disk-refuses-leaves-the-advancement-to-be-earned-again",
						() -> aPayoutTheDiskRefusesLeavesTheAdvancementToBeEarnedAgain(
								context, server, player, shop));
				scenario(context, "the-purse-is-on-the-screen-while-the-run-is-played",
						() -> thePurseIsOnTheScreenWhileTheRunIsPlayed(context, server, player, shop));
			}

			// Its own connection, twice over: the point of it is logging back in.
			TestRuns.waitForNobodyConnected(context, server);
			scenario(context, "a-stale-advancement-file-does-not-cost-this-run-its-payouts",
					() -> aStaleAdvancementFileDoesNotCostThisRunItsPayouts(context, server));
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

	/**
	 * The crash the advancement record cannot protect against, played out.
	 *
	 * <p>A player's advancements are saved on the player-save cycle; the purse is written the moment
	 * it changes. Crash in between and the game comes back to a purse that has been paid and an
	 * advancement record with no trace of the completion — so the player finishes it again, and
	 * without a ledger it pays again.
	 *
	 * <p>That state is reproduced exactly here, and nothing has to be killed to get it: the snapshot
	 * is read back off the file, which is what a restart does, and the advancement is revoked, which
	 * is what an unsaved record comes back as. Finishing it again after that must pay nothing — and
	 * the refusal has to survive reading the file again, or it only proves something in memory
	 * remembered.
	 */
	private void aCrashCannotMintTheSamePayoutTwice(ClientGameTestContext context,
			TestDedicatedServerContext server, TestPlayer player, TestShop shop) {
		startFresh(context, server, player, shop);

		int before = purse(server);
		grant(player, MINE_STONE);
		int afterPaying = purse(server);
		check(afterPaying == before + MINE_STONE_PAYS, "setup: the first completion must pay "
				+ MINE_STONE_PAYS + ": the purse went from " + before + " to " + afterPaying);

		// The restart: everything the running game believes is thrown away and read back off disk.
		int onDisk = server.computeOnServer(unused -> Wallet.reloadFromFile().balance());
		check(onDisk == afterPaying, "setup: the payout has to be on the disk for this to be about a"
				+ " crash: the file says " + onDisk + " and the game said " + afterPaying);

		// And the advancement record, as it comes back having never been saved.
		player.command("advancement revoke Player0 only " + MINE_STONE);

		grant(player, MINE_STONE);
		int afterReplay = purse(server);

		check(afterReplay == afterPaying, "finishing it again after the record lost it must pay"
				+ " nothing: the purse went from " + afterPaying + " to " + afterReplay);
		int onDiskAfter = server.computeOnServer(unused -> Wallet.reloadFromFile().balance());
		check(onDiskAfter == afterPaying, "and the file must say the same: it holds " + onDiskAfter
				+ " rather than " + afterPaying);
	}

	/**
	 * A payout the disk refuses must not cost the player the milestone.
	 *
	 * <p>An advancement is finished once. If a failed write left the completion standing, the player
	 * would have finished the only chance that run had to pay for it, and a disk that came back a
	 * moment later would not give them another — the milestone would be spent for nothing. So a
	 * refused write un-earns it: the criterion is revoked and the award is cancelled, which leaves no
	 * currency, no vanilla reward and no announcement behind.
	 *
	 * <p>Nothing is faked here. The snapshot file has a directory put in its way, which is what makes
	 * {@code AtomicFile} fail for real, and the advancement is finished through {@code
	 * PlayerAdvancements.award} — the call a criterion trigger makes. The proof is in three parts:
	 * nothing was paid, the advancement is not recorded as done, and once the file can be written the
	 * same milestone pays once and only once.
	 */
	private void aPayoutTheDiskRefusesLeavesTheAdvancementToBeEarnedAgain(ClientGameTestContext context,
			TestDedicatedServerContext server, TestPlayer player, TestShop shop) {
		startFresh(context, server, player, shop);
		int before = purse(server);

		blockTheSnapshot(player);
		try {
			boolean awarded = finish(player, MINE_STONE, MINE_STONE_CRITERION);

			check(purse(server) == before, "a payout the disk refuses must pay nothing: the purse"
					+ " went from " + before + " to " + purse(server));
			check(!isFinished(player, MINE_STONE), MINE_STONE + " must not be left finished by a"
					+ " payout that never happened, or the player can never earn it again this run");
			check(!awarded, "and the award must report that nothing changed, since it was undone");
		} finally {
			unblockTheSnapshot(player);
		}

		boolean awardedAgain = finish(player, MINE_STONE, MINE_STONE_CRITERION);
		int afterRecovery = purse(server);

		check(awardedAgain, "once the disk will take the write, finishing it again must stick");
		check(afterRecovery == before + MINE_STONE_PAYS, "and must pay the " + MINE_STONE_PAYS
				+ " that was refused earlier: the purse went from " + before + " to " + afterRecovery);
		check(isFinished(player, MINE_STONE), "and the advancement must be finished this time");

		finish(player, MINE_STONE, MINE_STONE_CRITERION);
		check(purse(server) == afterRecovery, "and it must still pay only once: the purse went from "
				+ afterRecovery + " to " + purse(server));
		int onDisk = server.computeOnServer(unused -> Wallet.reloadFromFile().balance());
		check(onDisk == afterRecovery, "with the file agreeing: it says " + onDisk + " rather than "
				+ afterRecovery);
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
	 * The state a crash at a run boundary leaves, and what it must not cost the player.
	 *
	 * <p>Crossing into a run clears the player's advancements and writes down which run they are in.
	 * Those are two files, saved at different times, so a crash can make the admission durable and
	 * the cleared advancements not. The player comes back admitted to this run carrying the last
	 * one's completions, and nothing fires again for them — as far as the lifecycle is concerned
	 * they never left. Every paying advancement they had already finished would be unearnable for
	 * the rest of the run.
	 *
	 * <p>So the state is built here exactly as the disk would hold it, and then a real reconnect is
	 * made to walk the real join path:
	 *
	 * <ul>
	 *   <li><b>the advancement file</b> — finished, by earning it normally in this run;
	 *   <li><b>the admission</b> — this run, which it already is;
	 *   <li><b>the progression snapshot</b> — written back as the <em>previous</em> run's, which is
	 *       what it still says at the moment a new run begins and before its first payout.
	 * </ul>
	 *
	 * <p>What must happen on that join is the milestone becoming earnable again, and then paying
	 * exactly once.
	 */
	private void aStaleAdvancementFileDoesNotCostThisRunItsPayouts(ClientGameTestContext context,
			TestDedicatedServerContext server) {
		int carried;
		int runId;
		try (TestDedicatedServerConnection connection = server.connect()) {
			TestRuns.settleClient(context, connection);
			TestPlayer player = new TestPlayer(context, server, connection);
			TestShop shop = new TestShop(context, player);
			startFresh(context, server, player, shop);

			grant(player, MINE_STONE);
			carried = purse(server);
			check(carried == MINE_STONE_PAYS, "setup: this run must have paid " + MINE_STONE_PAYS
					+ " for " + MINE_STONE + ", and the purse holds " + carried);
			check(isFinished(player, MINE_STONE), "setup: and the advancement must be finished");

			runId = TestRuns.record(server).runId();
			staleLedger(player, runId, carried);
			check(purse(server) == carried, "setup: rewinding the ledger must not move the purse,"
					+ " and it holds " + purse(server) + " rather than " + carried);
		}
		TestRuns.waitForNobodyConnected(context, server);

		try (TestDedicatedServerConnection returned = server.connect()) {
			TestRuns.settleClient(context, returned);
			TestPlayer player = new TestPlayer(context, server, returned);

			check(TestRuns.admittedRunOf(server) == runId, "setup: the player must come back admitted"
					+ " to run " + runId + ", which is the half of this the crash left durable, and"
					+ " they are admitted to " + TestRuns.admittedRunOf(server));
			check(!isFinished(player, MINE_STONE), MINE_STONE + " must be given back on joining:"
					+ " this run's ledger has never paid for it, so the record saying it is finished"
					+ " is the last run's and would cost this one the payout");
			check(purse(server) == carried, "and giving it back must not move the purse: it holds "
					+ purse(server) + " rather than " + carried);

			finish(player, MINE_STONE, MINE_STONE_CRITERION);
			int afterEarningAgain = purse(server);
			check(afterEarningAgain == carried + MINE_STONE_PAYS, "and the milestone must pay when it"
					+ " is finished again: the purse went from " + carried + " to " + afterEarningAgain);

			finish(player, MINE_STONE, MINE_STONE_CRITERION);
			check(purse(server) == afterEarningAgain, "exactly once, though: the purse went from "
					+ afterEarningAgain + " to " + purse(server));
		}
	}

	/**
	 * Write the progression snapshot as it stands at the start of a run: the purse as it is, and a
	 * ledger still belonging to the run before this one.
	 *
	 * <p>Written to the file and read back, rather than poked into memory, because what is being
	 * reproduced is a durable state — the one thing that survives the crash this is about.
	 */
	private void staleLedger(TestPlayer player, int runId, int currency) {
		player.onServer(server -> {
			String uuid = live(server).getUUID().toString();
			String snapshot = """
					{
					  "currency": %d,
					  "unlocks": {},
					  "paidAdvancements": { "run": %d, "entries": ["%s|%s"] }
					}""".formatted(currency, runId - 1, uuid, MINE_STONE);
			try {
				Files.writeString(Progress.file(), snapshot, StandardCharsets.UTF_8);
			} catch (IOException e) {
				throw new UncheckedIOException("Could not write " + Progress.file(), e);
			}
			Progress.reloadFromFile();
		});
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

	/**
	 * Finish an advancement the way a criterion trigger does, rather than through the command.
	 *
	 * <p>The command is the right tool everywhere else, but a completion that gets rolled back
	 * leaves it with nothing to report and it says so as a command failure. This is the call the
	 * trigger itself makes, and its answer — did anything change — is part of what is being checked.
	 */
	private boolean finish(TestPlayer player, String advancement, String criterion) {
		return player.onServerComputing(server ->
				live(server).getAdvancements().award(holder(server, advancement), criterion));
	}

	/** Does this player's own record say the advancement is done? */
	private boolean isFinished(TestPlayer player, String advancement) {
		return player.onServerComputing(server -> live(server).getAdvancements()
				.getOrStartProgress(holder(server, advancement))
				.isDone());
	}

	private static AdvancementHolder holder(MinecraftServer server, String advancement) {
		AdvancementHolder found = server.getAdvancements().get(Identifier.parse(advancement));
		if (found == null) {
			throw new AssertionError("This game has no advancement called " + advancement);
		}
		return found;
	}

	private static ServerPlayer live(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!player.isRemoved()) {
				return player;
			}
		}
		throw new AssertionError("Nobody is connected, so there is no advancement record to ask");
	}

	/**
	 * Make writing the progression snapshot fail, by putting a non-empty directory in its way.
	 *
	 * <p>Non-empty because renaming a file over an empty directory is allowed on some filesystems,
	 * and the point is a write that genuinely cannot succeed.
	 */
	private void blockTheSnapshot(TestPlayer player) {
		player.onServer(unused -> {
			Path file = Progress.file();
			try {
				Files.deleteIfExists(file);
				Files.createDirectories(file);
				Files.writeString(file.resolve("in-the-way"), "", StandardCharsets.UTF_8);
			} catch (IOException e) {
				throw new UncheckedIOException("Could not block " + file, e);
			}
		});
	}

	private void unblockTheSnapshot(TestPlayer player) {
		player.onServer(unused -> {
			Path file = Progress.file();
			try {
				if (!Files.isDirectory(file)) {
					return;
				}
				try (var entries = Files.list(file)) {
					for (Path entry : entries.toList()) {
						Files.deleteIfExists(entry);
					}
				}
				Files.deleteIfExists(file);
			} catch (IOException e) {
				throw new UncheckedIOException("Could not unblock " + file, e);
			}
		});
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
