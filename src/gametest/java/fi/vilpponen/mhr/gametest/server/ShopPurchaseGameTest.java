package fi.vilpponen.mhr.gametest.server;

import fi.vilpponen.mhr.Unlock;
import fi.vilpponen.mhr.UnlockState;
import fi.vilpponen.mhr.core.BalanceManager;
import fi.vilpponen.mhr.progression.Catalogue;
import fi.vilpponen.mhr.progression.Offer;
import fi.vilpponen.mhr.progression.Purchase;
import fi.vilpponen.mhr.progression.PurchaseJournal;
import fi.vilpponen.mhr.progression.Wallet;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Buying things: what it costs, what it grants, and what it refuses.
 *
 * <p>These are the checks that need no client at all. Every one goes through {@link Purchase},
 * which is the single operation the shop screen's click and the dev command both end up in, and
 * every one accounts for currency on both sides rather than asserting one field: a purchase has to
 * take exactly the price and grant exactly one level, and a refusal has to take nothing at all. The
 * half only a real client can answer — that a real click on a real square reaches this — is in
 * {@link fi.vilpponen.mhr.gametest.client.ShopClientTest}.
 *
 * <p>One test method on purpose, like the starter-chest and border ones: the unlock file, the purse
 * and the balance override are one each for the whole server, and GameTest runs a batch's tests
 * side by side in the same world. Inside the method the scenarios are strictly sequential and each
 * begins by putting progression back to nothing, so the order does not matter and nothing leaks
 * into the tests around it.
 *
 * <p>Nothing here buys a border tier. Owning one resizes the world the moment it is bought, and
 * these scenarios share a world with every other server test in the batch; that path is proven in
 * the client test, which has a dedicated server to itself.
 */
public class ShopPurchaseGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("mhr-gametest");

	/** A plain on/off unlock whose effect is worldgen, so buying it here changes nothing alive. */
	private static final String TREES = "world.trees";

	/** The one repeatable unlock in the shipped catalogue. */
	private static final String ENCHANT = "player.craft.enchant";

	@GameTest(maxTicks = 400)
	public void theShopChargesForWhatItGrants(GameTestHelper helper) {
		List<String> failures = new ArrayList<>();

		try {
			scenario(failures, "with-nothing-to-spend-nothing-can-be-bought",
					this::withNothingToSpendNothingCanBeBought);
			scenario(failures, "a-purchase-takes-the-price-once-and-grants-one-level",
					this::aPurchaseTakesThePriceOnceAndGrantsOneLevel);
			scenario(failures, "a-penny-short-buys-nothing-and-costs-nothing",
					this::aPennyShortBuysNothingAndCostsNothing);
			scenario(failures, "buying-something-already-owned-is-refused-and-free",
					this::buyingSomethingAlreadyOwnedIsRefusedAndFree);
			scenario(failures, "nothing-sells-an-id-the-catalogue-does-not-have",
					this::nothingSellsAnIdTheCatalogueDoesNotHave);
			scenario(failures, "a-repeatable-unlock-climbs-to-its-ceiling-and-stops",
					this::aRepeatableUnlockClimbsToItsCeilingAndStops);
			scenario(failures, "a-purchase-is-still-there-after-the-files-are-read-again",
					this::aPurchaseIsStillThereAfterTheFilesAreReadAgain);
			scenario(failures, "the-price-charged-is-the-one-in-the-balance-data",
					() -> thePriceChargedIsTheOneInTheBalanceData(helper));
			scenario(failures, "a-finished-purchase-leaves-no-record-behind",
					this::aFinishedPurchaseLeavesNoRecordBehind);
			scenario(failures, "a-purchase-the-disk-will-not-take-is-refused-and-costs-nothing",
					this::aPurchaseTheDiskWillNotTakeIsRefused);
			scenario(failures, "a-purchase-that-was-cut-off-is-finished-on-the-next-start",
					this::aPurchaseThatWasCutOffIsFinishedOnTheNextStart);
			scenario(failures, "a-purchase-cut-off-after-the-currency-landed-is-not-charged-twice",
					this::aPurchaseCutOffAfterTheCurrencyLandedIsNotChargedTwice);
		} finally {
			removeOverride(helper);
			unblockTheRecord();
			clearTheRecord();
			reset();
		}

		if (!failures.isEmpty()) {
			throw new AssertionError(failures.size() + " shop purchase scenario(s) failed:\n  "
					+ String.join("\n  ", failures));
		}
		LOGGER.info("All shop purchase server scenarios passed.");
		helper.succeed();
	}

	// --- the scenarios ---------------------------------------------------------------------

	/** An empty purse is a refusal, not a free unlock. */
	private void withNothingToSpendNothingCanBeBought() {
		reset();

		Purchase.Result result = Purchase.buy(TREES);

		check(result.outcome() == Purchase.Outcome.TOO_EXPENSIVE,
				"with nothing to spend, buying " + TREES + " should be refused as too expensive, and it was "
						+ result.outcome());
		check(!owns(TREES), TREES + " must not be owned after a refused purchase");
		check(balance() == 0, "a refused purchase must leave the purse alone, and it holds " + balance());
	}

	/** The conservation check: the price leaves the purse exactly once and one level arrives. */
	private void aPurchaseTakesThePriceOnceAndGrantsOneLevel() {
		reset();
		int price = priceOf(TREES);
		// Deliberately more than the price, so "the purse was emptied" cannot pass for "the price was
		// taken" — those are the same number when you start with exactly enough.
		int before = price + 7;
		Wallet.get().set(before);

		Purchase.Result result = Purchase.buy(TREES);

		check(result.bought(), "with " + before + " to spend, " + TREES + " at " + price
				+ " should have been bought, and the answer was " + result.outcome());
		check(level(TREES) == 1, "one purchase must grant exactly one level, and the level is " + level(TREES));
		check(balance() == before - price, "one purchase must take exactly " + price + ": the purse went from "
				+ before + " to " + balance());
		check(result.balance() == balance(),
				"the result must report the purse as it now is: it says " + result.balance()
						+ " and the purse holds " + balance());
	}

	/** One short of the price is a refusal, and a refusal costs nothing. */
	private void aPennyShortBuysNothingAndCostsNothing() {
		reset();
		int price = priceOf(TREES);
		Wallet.get().set(price - 1);

		Purchase.Result result = Purchase.buy(TREES);

		check(result.outcome() == Purchase.Outcome.TOO_EXPENSIVE,
				"one short of " + price + " should be refused, and the answer was " + result.outcome());
		check(!owns(TREES), "a refused purchase must not grant the unlock");
		check(balance() == price - 1,
				"a refused purchase must take nothing, and the purse went from " + (price - 1) + " to " + balance());
	}

	/** Clicking something you already own does not quietly charge you for it again. */
	private void buyingSomethingAlreadyOwnedIsRefusedAndFree() {
		reset();
		int price = priceOf(TREES);
		Wallet.get().set(price * 3);
		check(Purchase.buy(TREES).bought(), "setup: the first purchase should succeed");
		int after = balance();

		Purchase.Result second = Purchase.buy(TREES);

		check(second.outcome() == Purchase.Outcome.ALREADY_MAXED,
				"buying an owned unlock again should be refused as already owned, and it was " + second.outcome());
		check(level(TREES) == 1, "a refused second purchase must not raise the level past 1, and it is "
				+ level(TREES));
		check(balance() == after,
				"a refused second purchase must not charge again: the purse went from " + after + " to " + balance());
	}

	/** An id nothing sells is refused before any money moves, whatever it looks like. */
	private void nothingSellsAnIdTheCatalogueDoesNotHave() {
		reset();
		Wallet.get().set(10_000);

		Purchase.Result result = Purchase.buy("world.ore.unobtainium");

		check(result.outcome() == Purchase.Outcome.NOT_FOR_SALE,
				"an id the catalogue does not have should be refused as not for sale, and it was "
						+ result.outcome());
		check(balance() == 10_000, "refusing an unknown id must cost nothing, and the purse holds " + balance());
		check(!UnlockState.get().isOwned("world.ore.unobtainium"),
				"refusing an unknown id must not write it into the save file");
	}

	/**
	 * The repeatable one: every level costs the same, the ceiling comes from balance data, and the
	 * purchase after the ceiling is refused rather than silently charged.
	 */
	private void aRepeatableUnlockClimbsToItsCeilingAndStops() {
		reset();
		int price = priceOf(ENCHANT);
		int max = Unlock.CRAFT_ENCHANT.maxLevel();
		check(max > 1, "setup: " + ENCHANT + " is supposed to be repeatable, and its maximum is " + max);
		int before = price * (max + 2);
		Wallet.get().set(before);

		for (int wanted = 1; wanted <= max; wanted++) {
			Purchase.Result result = Purchase.buy(ENCHANT);
			check(result.bought(), "level " + wanted + " of " + max + " should be buyable, and the answer was "
					+ result.outcome());
			check(level(ENCHANT) == wanted,
					"after buying " + wanted + " level(s) the level should be " + wanted + " and it is "
							+ level(ENCHANT));
		}

		Purchase.Result past = Purchase.buy(ENCHANT);

		check(past.outcome() == Purchase.Outcome.ALREADY_MAXED,
				"buying past the ceiling should be refused as maxed, and it was " + past.outcome());
		check(level(ENCHANT) == max, "the level must stay at " + max + " and it is " + level(ENCHANT));
		check(balance() == before - price * max,
				"exactly " + max + " levels should have been charged for: the purse went from " + before
						+ " to " + balance() + ", which is " + ((before - balance()) / price) + " level(s)");
	}

	/**
	 * Permanent means on disk. Both files are read again from scratch, which is the nearest thing
	 * to quitting the game that a test sharing the server's process can do.
	 */
	private void aPurchaseIsStillThereAfterTheFilesAreReadAgain() {
		reset();
		int price = priceOf(TREES);
		Wallet.get().set(price + 5);
		check(Purchase.buy(TREES).bought(), "setup: the purchase should succeed");

		UnlockState.reloadFromFile();
		Wallet.reloadFromFile();

		check(owns(TREES), TREES + " was bought and must still be owned after the files are read again");
		check(balance() == 5,
				"the currency spent must have reached the disk too, and after re-reading the purse holds "
						+ balance());
	}

	/**
	 * The price is balance data, not a number in Java: retune it, reload, and the next purchase
	 * charges the new one.
	 */
	private void thePriceChargedIsTheOneInTheBalanceData(GameTestHelper helper) {
		reset();
		int original = priceOf(TREES);
		int retuned = original + 41;
		writeOverride(helper, "{\"unlocks\": {\"" + TREES + "\": {\"price\": " + retuned + "}}}");

		check(priceOf(TREES) == retuned,
				"'mhr reload' should have picked the new price up, and the shop still offers " + priceOf(TREES));

		Wallet.get().set(original);
		Purchase.Result tooLittle = Purchase.buy(TREES);
		check(tooLittle.outcome() == Purchase.Outcome.TOO_EXPENSIVE,
				"the old price must no longer be enough, and the answer was " + tooLittle.outcome());

		Wallet.get().set(retuned);
		Purchase.Result enough = Purchase.buy(TREES);
		check(enough.bought(), "the new price should be enough, and the answer was " + enough.outcome());
		check(balance() == 0, "the new price should have been charged in full, and the purse holds " + balance());

		removeOverride(helper);
		check(priceOf(TREES) == original,
				"taking the override away should restore the bundled price, and the shop offers " + priceOf(TREES));
	}

	/**
	 * The ordinary state of the commit record is not to exist: a purchase that finished has nothing
	 * left to finish.
	 */
	private void aFinishedPurchaseLeavesNoRecordBehind() {
		reset();
		Wallet.get().set(priceOf(TREES));

		check(Purchase.buy(TREES).bought(), "setup: the purchase should succeed");

		check(!PurchaseJournal.isPending(),
				"a purchase that finished must not leave a record behind, and one is still there");
		check(!Files.exists(PurchaseJournal.file()),
				"the record file itself must be gone, and " + PurchaseJournal.file() + " still exists");
	}

	/**
	 * The commit point is a write, and a write can fail. When it does, the answer is a refusal —
	 * not an unlock nobody paid for, and not currency taken for nothing.
	 */
	private void aPurchaseTheDiskWillNotTakeIsRefused() {
		reset();
		int price = priceOf(TREES);
		Wallet.get().set(price + 4);
		blockTheRecord();

		try {
			Purchase.Result result = Purchase.buy(TREES);

			check(result.outcome() == Purchase.Outcome.NOT_SAVED,
					"a purchase whose record cannot be written should be refused as unsaveable, and the"
							+ " answer was " + result.outcome());
			check(!owns(TREES), "a refused purchase must not grant the unlock");
			check(balance() == price + 4,
					"a refused purchase must not charge: the purse went from " + (price + 4) + " to "
							+ balance());
		} finally {
			unblockTheRecord();
		}

		// And the same purchase goes through once the disk will take it, so the refusal above was
		// the write failing rather than anything else about the purchase.
		check(Purchase.buy(TREES).bought(), "with the disk working again the same purchase should succeed");
		check(owns(TREES) && balance() == 4, "and it should charge exactly once");
	}

	/**
	 * The crash this whole arrangement exists for: the record landed, neither file did, and the
	 * process stopped. Starting again has to produce the purchase, not lose it.
	 */
	private void aPurchaseThatWasCutOffIsFinishedOnTheNextStart() {
		reset();
		int price = priceOf(TREES);
		Wallet.get().set(price + 6);

		// Exactly what a session that stopped immediately after the commit point leaves behind.
		commitRecord(TREES, 1, 6);
		UnlockState.reloadFromFile();
		Wallet.reloadFromFile();
		check(!owns(TREES), "setup: before recovery the unlock file should still say nothing is owned");
		check(balance() == price + 6, "setup: and the purse should still be untouched");

		PurchaseJournal.recover();

		check(owns(TREES), "the interrupted purchase must be finished, and " + TREES + " is still not owned");
		check(balance() == 6,
				"and the currency must be taken exactly once: the purse holds " + balance() + " rather than 6");
		check(!PurchaseJournal.isPending(), "a finished recovery must take its record away");

		// It has to survive being read off the disk, and running recovery again must not charge a
		// second time — which is what makes the record safe to replay.
		UnlockState.reloadFromFile();
		Wallet.reloadFromFile();
		PurchaseJournal.recover();
		check(owns(TREES) && balance() == 6,
				"recovery must be safe to repeat, and afterwards the purse holds " + balance()
						+ " with " + TREES + (owns(TREES) ? " owned" : " not owned"));
	}

	/**
	 * The other half of the same window: the currency reached the disk and the unlock did not. The
	 * record says what the total should be, not what to subtract, so finishing it cannot charge
	 * again.
	 */
	private void aPurchaseCutOffAfterTheCurrencyLandedIsNotChargedTwice() {
		reset();
		int price = priceOf(TREES);
		Wallet.get().set(price + 2);

		commitRecord(TREES, 1, 2);
		Wallet.get().set(2);
		UnlockState.reloadFromFile();
		check(!owns(TREES), "setup: the unlock half should be the one still missing");

		PurchaseJournal.recover();

		check(owns(TREES), "the missing half must be filled in, and " + TREES + " is still not owned");
		check(balance() == 2,
				"the half that had already landed must not be charged again: the purse holds " + balance()
						+ " rather than 2");
	}

	// --- plumbing --------------------------------------------------------------------------

	/** Leave behind what a session cut off just after the commit point would have left. */
	private static void commitRecord(String id, int level, int balance) {
		try {
			PurchaseJournal.commit(new PurchaseJournal.Record(id, level, balance));
		} catch (IOException e) {
			throw new UncheckedIOException("Could not write the purchase record", e);
		}
	}

	private static void clearTheRecord() {
		try {
			Files.deleteIfExists(PurchaseJournal.file());
		} catch (IOException e) {
			throw new UncheckedIOException("Could not remove the purchase record", e);
		}
	}

	/**
	 * Make the commit point fail, by putting a directory where the record has to go.
	 *
	 * <p>A non-empty one, because renaming a file over an empty directory is allowed on some
	 * filesystems and the point is that the write cannot succeed.
	 */
	private static void blockTheRecord() {
		clearTheRecord();
		try {
			Path blocker = PurchaseJournal.file();
			Files.createDirectories(blocker);
			Files.writeString(blocker.resolve("in-the-way"), "", StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not block the purchase record", e);
		}
	}

	private static void unblockTheRecord() {
		Path blocker = PurchaseJournal.file();
		try {
			if (Files.isDirectory(blocker)) {
				try (var entries = Files.list(blocker)) {
					for (Path entry : entries.toList()) {
						Files.deleteIfExists(entry);
					}
				}
				Files.deleteIfExists(blocker);
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Could not unblock the purchase record", e);
		}
	}

	/** Nothing owned, nothing to spend. Every scenario starts here. */
	private static void reset() {
		clearTheRecord();
		for (Offer offer : Catalogue.offers()) {
			UnlockState.get().setLevel(offer.id(), 0);
		}
		Wallet.get().set(0);
	}

	private static int priceOf(String id) {
		return Catalogue.offer(id)
				.orElseThrow(() -> new AssertionError("The catalogue does not sell " + id))
				.price();
	}

	private static int level(String id) {
		return UnlockState.get().level(id);
	}

	private static boolean owns(String id) {
		return UnlockState.get().isOwned(id);
	}

	private static int balance() {
		return Wallet.get().balance();
	}

	private static void writeOverride(GameTestHelper helper, String json) {
		Path file = BalanceManager.overrideFile();
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, json, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not write the balance override " + file, e);
		}
		command(helper, "mhr reload");
	}

	private static void removeOverride(GameTestHelper helper) {
		Path file = BalanceManager.overrideFile();
		try {
			Files.deleteIfExists(file);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not remove the balance override " + file, e);
		}
		command(helper, "mhr reload");
	}

	/** The dev command, through the real command dispatcher, as the console would run it. */
	private static void command(GameTestHelper helper, String command) {
		helper.getLevel().getServer().getCommands()
				.performPrefixedCommand(helper.getLevel().getServer().createCommandSourceStack(), command);
	}

	/**
	 * Runs one scenario. A failure is recorded rather than ending the run, so one command shows
	 * every criterion that is red instead of only the first.
	 */
	private static void scenario(List<String> failures, String name, Runnable body) {
		LOGGER.info("=== scenario {} ===", name);
		try {
			body.run();
			LOGGER.info("=== scenario {}: PASS ===", name);
		} catch (Throwable failure) {
			failures.add(name + ": " + failure.getMessage());
			LOGGER.error("=== scenario {}: FAIL === {}", name, failure.getMessage(), failure);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
