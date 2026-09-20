package fi.vilpponen.mhr.gametest.server;

import fi.vilpponen.mhr.Unlock;
import fi.vilpponen.mhr.UnlockState;
import fi.vilpponen.mhr.core.BalanceManager;
import fi.vilpponen.mhr.progression.Catalogue;
import fi.vilpponen.mhr.progression.Offer;
import fi.vilpponen.mhr.progression.Purchase;
import fi.vilpponen.mhr.progression.Progress;
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
 * <p>Currency and what is owned are one file, so the scenarios that used to be about two writes
 * getting out of step are now about one write either happening or not.
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

	/** A second plain unlock, for the scenarios that need two purchases to be told apart. */
	private static final String VILLAGE = "world.village";

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
			scenario(failures, "both-halves-of-a-purchase-reach-the-disk-together",
					this::bothHalvesOfAPurchaseReachTheDiskTogether);
			scenario(failures, "a-purchase-the-disk-will-not-take-changes-nothing-at-all",
					this::aPurchaseTheDiskWillNotTakeChangesNothingAtAll);
			scenario(failures, "a-refused-write-leaves-the-previous-progression-whole",
					this::aRefusedWriteLeavesThePreviousProgressionWhole);
		} finally {
			removeOverride(helper);
			unblock(Progress.file());
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
	 * The conservation check that the single snapshot exists for: after a purchase, the file itself
	 * says both halves moved, and it says so having been written once.
	 */
	private void bothHalvesOfAPurchaseReachTheDiskTogether() {
		reset();
		int price = priceOf(TREES);
		Wallet.get().set(price + 11);

		check(Purchase.buy(TREES).bought(), "setup: the purchase should succeed");

		// Read from the disk rather than from memory: memory would agree even if nothing was written.
		Progress.reloadFromFile();
		check(owns(TREES), TREES + " must be owned in the file, and it is not");
		check(balance() == 11,
				"and the currency must have moved in the same file: it holds " + balance() + " rather than 11");
	}

	/**
	 * A write that cannot happen changes nothing: not the disk, not memory, not what the running
	 * game believes. This is what the single snapshot buys — there is no half of it to be left in.
	 */
	private void aPurchaseTheDiskWillNotTakeChangesNothingAtAll() {
		reset();
		int price = priceOf(TREES);
		Wallet.get().set(price + 4);
		block(Progress.file());

		try {
			Purchase.Result result = Purchase.buy(TREES);

			check(result.outcome() == Purchase.Outcome.NOT_SAVED,
					"a purchase that cannot be written should be refused as unsaveable, and the answer was "
							+ result.outcome());
			check(!owns(TREES), "the running game must not think it owns something it could not write");
			check(balance() == price + 4,
					"and must not think it paid: the purse holds " + balance() + " rather than " + (price + 4));
		} finally {
			unblock(Progress.file());
		}

		// The same purchase goes through once the disk will take it, so the refusal was the write
		// failing rather than anything else about the purchase.
		check(Purchase.buy(TREES).bought(), "with the disk working again the same purchase should succeed");
		check(owns(TREES) && balance() == 4, "and it should charge exactly once");
	}

	/**
	 * The other half of the same promise: a refused write leaves the snapshot that was already there
	 * exactly as it was, so a restart finds the progression the player last successfully had.
	 */
	private void aRefusedWriteLeavesThePreviousProgressionWhole() {
		reset();
		int treesPrice = priceOf(TREES);
		int villagePrice = priceOf(VILLAGE);
		Wallet.get().set(treesPrice + villagePrice + 7);
		check(Purchase.buy(TREES).bought(), "setup: the first purchase should succeed");
		int after = balance();

		// Not a directory this time: the snapshot has to stay readable, because the point is what is
		// still in it afterwards. A directory where the temporary file goes stops the write just as
		// dead and leaves the real file alone.
		Path temporary = Progress.file().resolveSibling(Progress.file().getFileName() + ".tmp");
		block(temporary);
		try {
			check(Purchase.buy(VILLAGE).outcome() == Purchase.Outcome.NOT_SAVED,
					"the second purchase should be refused while the snapshot cannot be written");
		} finally {
			unblock(temporary);
		}

		Progress.reloadFromFile();
		check(owns(TREES), "the purchase that did succeed must still be in the file, and " + TREES
				+ " is not owned");
		check(!owns(VILLAGE), "the purchase that did not must not be, and " + VILLAGE + " is owned");
		check(balance() == after,
				"and the currency must be what the last successful write left: " + balance() + " rather than "
						+ after);
	}

	// --- plumbing --------------------------------------------------------------------------

	/**
	 * Make a write to this path fail, by putting a directory in its way.
	 *
	 * <p>A non-empty one, because renaming a file over an empty directory is allowed on some
	 * filesystems and the point is that the write cannot succeed.
	 */
	private static void block(Path path) {
		try {
			Files.deleteIfExists(path);
			Files.createDirectories(path);
			Files.writeString(path.resolve("in-the-way"), "", StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not block " + path, e);
		}
	}

	private static void unblock(Path path) {
		try {
			if (!Files.isDirectory(path)) {
				return;
			}
			try (var entries = Files.list(path)) {
				for (Path entry : entries.toList()) {
					Files.deleteIfExists(entry);
				}
			}
			Files.deleteIfExists(path);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not unblock " + path, e);
		}
	}

	/** Nothing owned, nothing to spend. Every scenario starts here. */
	private static void reset() {
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
