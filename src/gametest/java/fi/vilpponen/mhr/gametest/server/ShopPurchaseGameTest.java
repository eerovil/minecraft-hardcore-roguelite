package fi.vilpponen.mhr.gametest.server;

import fi.vilpponen.mhr.Unlock;
import fi.vilpponen.mhr.UnlockState;
import fi.vilpponen.mhr.core.BalanceManager;
import fi.vilpponen.mhr.core.PersistenceException;
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
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.storage.LevelResource;
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
 * <p>One test method on purpose, like the starter-chest and border ones: the progression snapshot
 * and the balance override are one each for the whole server, and GameTest runs a batch's tests
 * side by side in the same world. Inside the method the scenarios are strictly sequential and each
 * begins by putting progression back to nothing, so the order does not matter and nothing leaks
 * into the tests around it.
 *
 * <p>Currency and what is owned are one file, so the scenarios that used to be about two writes
 * getting out of step are now about one write either happening or not.
 *
 * <p>The last three scenarios are about the other operation that writes that file — earning, which
 * pays a run for a milestone once and records that it has. They live here rather than in a class of
 * their own for the same reason everything else does: the snapshot is one per server, GameTest runs
 * a batch side by side, and two tests writing it at once would fail each other. What a real
 * advancement finishing looks like end to end is
 * {@code fi.vilpponen.mhr.gametest.client.CurrencyEarningClientTest}'s.
 *
 * <p>Nothing here buys a border tier. Owning one resizes the world the moment it is bought, and
 * these scenarios share a world with every other server test in the batch; that path is proven in
 * the client test, which has a dedicated server to itself.
 */
public class ShopPurchaseGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("mhr-gametest");

	/** A plain on/off unlock whose effect is worldgen, so buying it here changes nothing alive. */
	private static final String COAL = "world.ore.coal";

	/** Trees used to be sold. They are vanilla from the first run now, and nothing sells them. */
	private static final String RETIRED_TREES = "world.trees";

	/** The one repeatable unlock in the shipped catalogue. */
	private static final String ENCHANT = "player.craft.enchant";

	/** A second plain unlock, for the scenarios that need two purchases to be told apart. */
	private static final String VILLAGE = "world.village";

	/** A run id and one thing paid for in it, for the ledger scenarios. */
	private static final int RUN = 4;
	private static final String MILESTONE = "11111111-2222-3333-4444-555555555555|minecraft:story/mine_stone";

	/** Three of the four border tiers, smallest first. The run gets the largest one owned. */
	private static final String TINY_BORDER = "world.border.tiny";
	private static final String MEDIUM_BORDER = "world.border.medium";
	private static final String LARGE_BORDER = "world.border.large";
	private static final String UNBOUNDED_BORDER = "world.border.infinite";

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
			scenario(failures, "trees-are-not-for-sale",
					this::treesAreNotForSale);
			scenario(failures, "a-repeatable-unlock-climbs-to-its-ceiling-and-stops",
					this::aRepeatableUnlockClimbsToItsCeilingAndStops);
			scenario(failures, "a-purchase-is-still-there-after-the-snapshot-is-read-again",
					this::aPurchaseIsStillThereAfterTheSnapshotIsReadAgain);
			scenario(failures, "the-price-charged-is-the-one-in-the-balance-data",
					() -> thePriceChargedIsTheOneInTheBalanceData(helper));
			scenario(failures, "both-halves-of-a-purchase-reach-the-disk-together",
					this::bothHalvesOfAPurchaseReachTheDiskTogether);
			scenario(failures, "a-purchase-the-disk-will-not-take-changes-nothing-at-all",
					this::aPurchaseTheDiskWillNotTakeChangesNothingAtAll);
			scenario(failures, "a-refused-write-leaves-the-previous-progression-whole",
					this::aRefusedWriteLeavesThePreviousProgressionWhole);
			scenario(failures, "a-smaller-border-tier-cannot-be-charged-for-once-a-bigger-one-is-owned",
					this::aSmallerBorderTierCannotBeChargedForOnceABiggerOneIsOwned);
			scenario(failures, "progression-lives-in-the-save-and-not-in-the-installation",
					() -> progressionLivesInTheSaveAndNotInTheInstallation(helper));
			scenario(failures, "an-unreadable-snapshot-stops-rather-than-starting-empty",
					this::anUnreadableSnapshotStopsRatherThanStartingEmpty);
			scenario(failures, "a-snapshot-missing-a-field-is-damaged-rather-than-empty",
					this::aSnapshotMissingAFieldIsDamagedRatherThanEmpty);
			scenario(failures, "a-run-is-paid-once-for-a-milestone-even-after-a-restart",
					this::aRunIsPaidOnceForAMilestoneEvenAfterARestart);
			scenario(failures, "the-next-run-is-paid-for-the-same-milestone-again",
					this::theNextRunIsPaidForTheSameMilestoneAgain);
			scenario(failures, "a-credit-the-disk-will-not-take-pays-nothing-and-remembers-nothing",
					this::aCreditTheDiskWillNotTakePaysNothingAndRemembersNothing);
		} finally {
			removeOverride(helper);
			unblock(Progress.file());
			startFromNothing();
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

		Purchase.Result result = Purchase.buy(COAL);

		check(result.outcome() == Purchase.Outcome.TOO_EXPENSIVE,
				"with nothing to spend, buying " + COAL + " should be refused as too expensive, and it was "
						+ result.outcome());
		check(!owns(COAL), COAL + " must not be owned after a refused purchase");
		check(balance() == 0, "a refused purchase must leave the purse alone, and it holds " + balance());
	}

	/** The conservation check: the price leaves the purse exactly once and one level arrives. */
	private void aPurchaseTakesThePriceOnceAndGrantsOneLevel() {
		reset();
		int price = priceOf(COAL);
		// Deliberately more than the price, so "the purse was emptied" cannot pass for "the price was
		// taken" — those are the same number when you start with exactly enough.
		int before = price + 7;
		Wallet.get().set(before);

		Purchase.Result result = Purchase.buy(COAL);

		check(result.bought(), "with " + before + " to spend, " + COAL + " at " + price
				+ " should have been bought, and the answer was " + result.outcome());
		check(level(COAL) == 1, "one purchase must grant exactly one level, and the level is " + level(COAL));
		check(balance() == before - price, "one purchase must take exactly " + price + ": the purse went from "
				+ before + " to " + balance());
		check(result.balance() == balance(),
				"the result must report the purse as it now is: it says " + result.balance()
						+ " and the purse holds " + balance());
	}

	/** One short of the price is a refusal, and a refusal costs nothing. */
	private void aPennyShortBuysNothingAndCostsNothing() {
		reset();
		int price = priceOf(COAL);
		Wallet.get().set(price - 1);

		Purchase.Result result = Purchase.buy(COAL);

		check(result.outcome() == Purchase.Outcome.TOO_EXPENSIVE,
				"one short of " + price + " should be refused, and the answer was " + result.outcome());
		check(!owns(COAL), "a refused purchase must not grant the unlock");
		check(balance() == price - 1,
				"a refused purchase must take nothing, and the purse went from " + (price - 1) + " to " + balance());
	}

	/** Clicking something you already own does not quietly charge you for it again. */
	private void buyingSomethingAlreadyOwnedIsRefusedAndFree() {
		reset();
		int price = priceOf(COAL);
		Wallet.get().set(price * 3);
		check(Purchase.buy(COAL).bought(), "setup: the first purchase should succeed");
		int after = balance();

		Purchase.Result second = Purchase.buy(COAL);

		check(second.outcome() == Purchase.Outcome.ALREADY_MAXED,
				"buying an owned unlock again should be refused as already owned, and it was " + second.outcome());
		check(level(COAL) == 1, "a refused second purchase must not raise the level past 1, and it is "
				+ level(COAL));
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
	 * Trees are vanilla from the first run, so the catalogue has nothing to sell for them — not a
	 * free entry, not a hidden one — and asking to buy the old id is refused like any unknown one.
	 */
	private void treesAreNotForSale() {
		reset();
		Wallet.get().set(10_000);

		check(!Catalogue.sells(RETIRED_TREES), "the catalogue must not sell " + RETIRED_TREES + " any more");
		check(Catalogue.offers().stream().noneMatch(offer -> offer.id().contains("tree")),
				"nothing in the catalogue may sell trees, and it has "
						+ Catalogue.offers().stream().map(Offer::id).filter(id -> id.contains("tree")).toList());

		Purchase.Result result = Purchase.buy(RETIRED_TREES);

		check(result.outcome() == Purchase.Outcome.NOT_FOR_SALE,
				"buying " + RETIRED_TREES + " should be refused as not for sale, and it was " + result.outcome());
		check(balance() == 10_000, "refusing it must cost nothing, and the purse holds " + balance());
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
	 * Permanent means on disk. The snapshot is read again from scratch, which is the nearest thing
	 * to quitting the game that a test sharing the server's process can do.
	 *
	 * <p>One read, not two. Both halves of a purchase live in the one file, and {@code UnlockState}
	 * and {@code Wallet} are views of it — so re-reading it once is what puts both of them back on
	 * whatever the disk says.
	 */
	private void aPurchaseIsStillThereAfterTheSnapshotIsReadAgain() {
		reset();
		int price = priceOf(COAL);
		Wallet.get().set(price + 5);
		check(Purchase.buy(COAL).bought(), "setup: the purchase should succeed");

		Progress.reloadFromFile();

		check(owns(COAL), COAL + " was bought and must still be owned after the snapshot is read again");
		check(balance() == 5,
				"the currency spent must have reached the disk in the same file, and after re-reading the"
						+ " purse holds " + balance());
	}

	/**
	 * The price is balance data, not a number in Java: retune it, reload, and the next purchase
	 * charges the new one.
	 */
	private void thePriceChargedIsTheOneInTheBalanceData(GameTestHelper helper) {
		reset();
		int original = priceOf(COAL);
		int retuned = original + 41;
		writeOverride(helper, "{\"unlocks\": {\"" + COAL + "\": {\"price\": " + retuned + "}}}");

		check(priceOf(COAL) == retuned,
				"'mhr reload' should have picked the new price up, and the shop still offers " + priceOf(COAL));

		Wallet.get().set(original);
		Purchase.Result tooLittle = Purchase.buy(COAL);
		check(tooLittle.outcome() == Purchase.Outcome.TOO_EXPENSIVE,
				"the old price must no longer be enough, and the answer was " + tooLittle.outcome());

		Wallet.get().set(retuned);
		Purchase.Result enough = Purchase.buy(COAL);
		check(enough.bought(), "the new price should be enough, and the answer was " + enough.outcome());
		check(balance() == 0, "the new price should have been charged in full, and the purse holds " + balance());

		removeOverride(helper);
		check(priceOf(COAL) == original,
				"taking the override away should restore the bundled price, and the shop offers " + priceOf(COAL));
	}

	/**
	 * The conservation check that the single snapshot exists for: after a purchase, the file itself
	 * says both halves moved, and it says so having been written once.
	 */
	private void bothHalvesOfAPurchaseReachTheDiskTogether() {
		reset();
		int price = priceOf(COAL);
		Wallet.get().set(price + 11);

		check(Purchase.buy(COAL).bought(), "setup: the purchase should succeed");

		// Read from the disk rather than from memory: memory would agree even if nothing was written.
		Progress.reloadFromFile();
		check(owns(COAL), COAL + " must be owned in the file, and it is not");
		check(balance() == 11,
				"and the currency must have moved in the same file: it holds " + balance() + " rather than 11");
	}

	/**
	 * A write that cannot happen changes nothing: not the disk, not memory, not what the running
	 * game believes. This is what the single snapshot buys — there is no half of it to be left in.
	 */
	private void aPurchaseTheDiskWillNotTakeChangesNothingAtAll() {
		reset();
		int price = priceOf(COAL);
		Wallet.get().set(price + 4);
		block(Progress.file());

		try {
			Purchase.Result result = Purchase.buy(COAL);

			check(result.outcome() == Purchase.Outcome.NOT_SAVED,
					"a purchase that cannot be written should be refused as unsaveable, and the answer was "
							+ result.outcome());
			check(!owns(COAL), "the running game must not think it owns something it could not write");
			check(balance() == price + 4,
					"and must not think it paid: the purse holds " + balance() + " rather than " + (price + 4));
		} finally {
			unblock(Progress.file());
		}

		// The same purchase goes through once the disk will take it, so the refusal was the write
		// failing rather than anything else about the purchase.
		check(Purchase.buy(COAL).bought(), "with the disk working again the same purchase should succeed");
		check(owns(COAL) && balance() == 4, "and it should charge exactly once");
	}

	/**
	 * The other half of the same promise: a refused write leaves the snapshot that was already there
	 * exactly as it was, so a restart finds the progression the player last successfully had.
	 */
	private void aRefusedWriteLeavesThePreviousProgressionWhole() {
		reset();
		int coalPrice = priceOf(COAL);
		int villagePrice = priceOf(VILLAGE);
		Wallet.get().set(coalPrice + villagePrice + 7);
		check(Purchase.buy(COAL).bought(), "setup: the first purchase should succeed");
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
		check(owns(COAL), "the purchase that did succeed must still be in the file, and " + COAL
				+ " is not owned");
		check(!owns(VILLAGE), "the purchase that did not must not be, and " + VILLAGE + " is owned");
		check(balance() == after,
				"and the currency must be what the last successful write left: " + balance() + " rather than "
						+ after);
	}

	/**
	 * The border tiers are steps, and the run gets the largest one owned. So once Large is bought,
	 * Medium and Tiny cannot change anything about the world — and the shop lets tiers be bought in
	 * any order, which made buying Large first turn Medium into a trap that took the price and did
	 * nothing.
	 *
	 * <p>Nothing here applies a tier to the running world: {@code Purchase} writes the snapshot and
	 * the border is only re-read when something fires the unlock effects, which this does not. The
	 * half that needs a real world is in the client test, which has a server to itself.
	 */
	private void aSmallerBorderTierCannotBeChargedForOnceABiggerOneIsOwned() {
		reset();
		int largePrice = priceOf(LARGE_BORDER);
		int before = largePrice + priceOf(MEDIUM_BORDER) + priceOf(UNBOUNDED_BORDER) + 5;
		Wallet.get().set(before);

		// The rule runs one way only. Owning a smaller tier must leave every bigger one for sale,
		// or "buy the cheap one first" would quietly close the ladder.
		check(Purchase.buy(TINY_BORDER).bought(), "setup: the smallest tier should be buyable");
		check(!offer(MEDIUM_BORDER).isMaxed(),
				"owning the smallest tier must not satisfy a bigger one, and " + MEDIUM_BORDER
						+ " shows as owned");
		check(!offer(LARGE_BORDER).isMaxed(),
				"nor any of the ones above it, and " + LARGE_BORDER + " shows as owned");
		check(!offer(UNBOUNDED_BORDER).isMaxed(),
				"nor the unbounded one, and " + UNBOUNDED_BORDER + " shows as owned");

		check(Purchase.buy(LARGE_BORDER).bought(), "setup: the large tier should be buyable");
		int after = balance();
		check(after == before - largePrice - priceOf(TINY_BORDER),
				"setup: and should cost exactly " + largePrice);

		// The shop must stop offering the smaller ones at all, rather than offering them and then
		// refusing: a price shown is a price a player will click.
		check(offer(MEDIUM_BORDER).isMaxed(),
				"a tier smaller than the one owned must show as owned, and " + MEDIUM_BORDER + " does not");
		check(offer(TINY_BORDER).isMaxed(),
				"and so must every tier below it, and " + TINY_BORDER + " does not");

		Purchase.Result medium = Purchase.buy(MEDIUM_BORDER);

		check(medium.outcome() == Purchase.Outcome.ALREADY_MAXED,
				"buying a smaller tier must be refused as already owned, and the answer was "
						+ medium.outcome());
		check(balance() == after,
				"and must cost nothing: the purse went from " + after + " to " + balance());
		check(!owns(MEDIUM_BORDER),
				"nothing should be written to the snapshot either — what was bought is what is recorded");

		// A bigger tier is still worth selling, which is what stops this from being "border tiers
		// are unbuyable once you own any of them".
		check(!offer(UNBOUNDED_BORDER).isMaxed(),
				"a tier bigger than the one owned must still be for sale");
		check(Purchase.buy(UNBOUNDED_BORDER).bought(), "and must still be buyable");
		check(offer(LARGE_BORDER).isMaxed(),
				"after which the tier that used to be the biggest is covered by it in turn");

		reset();
	}

	// --- where it lives, and loading --------------------------------------------------------

	/**
	 * One save is one profile. The snapshot is at the root of this save, and a profile left in the
	 * installation's config directory by an older build is neither read nor copied in: it cannot be
	 * told which save it belonged to, so it belongs to none.
	 */
	private void progressionLivesInTheSaveAndNotInTheInstallation(GameTestHelper helper) {
		startFromNothing();
		Path saveRoot = helper.getLevel().getServer().getWorldPath(LevelResource.ROOT)
				.toAbsolutePath().normalize();
		Path snapshot = Progress.file().toAbsolutePath().normalize();
		check(snapshot.equals(saveRoot.resolve("hardcore-roguelite-progress.json")),
				"the snapshot must be at the root of the save, next to the run record, and it is "
						+ snapshot);

		Path installation = FabricLoader.getInstance().getConfigDir()
				.resolve("hardcore-roguelite-progress.json");
		write(installation, "{\"currency\": 999, \"unlocks\": {\"" + COAL + "\": 1},"
				+ " \"paidAdvancements\": {\"run\": " + RUN + ", \"entries\": [\"" + MILESTONE + "\"]}}");
		try {
			Progress.reloadFromFile();
			check(balance() == 0 && !owns(COAL) && !Wallet.get().hasEarned(RUN, MILESTONE),
					"a profile in the installation's config directory must not be read, and this save"
							+ " came back with " + balance() + " currency"
							+ (owns(COAL) ? " and " + COAL : ""));

			// And the save's own file is still the one that answers, or the check above would pass
			// just as well with nothing read from anywhere.
			Wallet.get().set(priceOf(COAL));
			Purchase.Result bought = Purchase.buy(COAL);
			check(bought.bought(), "setup: the purchase should have gone through, and it was " + bought.outcome());
			Progress.reloadFromFile();
			check(owns(COAL), "a purchase must be read back from the save's own snapshot");
			check(read(installation).contains("999"),
					"and must not have been written into the installation's config directory");
		} finally {
			delete(installation);
		}

		startFromNothing();
	}

	/**
	 * The one that matters most: a snapshot that is there and cannot be read must stop the game
	 * rather than carry on as though nothing had ever been bought — because a profile that starts
	 * empty is a profile the next purchase writes over.
	 */
	private void anUnreadableSnapshotStopsRatherThanStartingEmpty() {
		startFromNothing();
		String damaged = "{\"currency\": 100, \"unlocks\": {\"" + COAL;
		write(Progress.file(), damaged);

		check(refusesToLoad(), "an unreadable snapshot must refuse to load, and it loaded");
		check(damaged.equals(read(Progress.file())),
				"and must leave the file exactly as it found it, so it can still be repaired");

		startFromNothing();
	}

	/**
	 * Readable JSON is not the same as a snapshot.
	 *
	 * <p>{@code {"currency": 100}} parses perfectly and used to load as a player who owns nothing —
	 * at which point the next purchase wrote that invented emptiness over a file that still had the
	 * unlocks in it. What a snapshot is gets decided in one place, where it is read, and a file that
	 * is not one is damaged rather than empty.
	 */
	private void aSnapshotMissingAFieldIsDamagedRatherThanEmpty() {
		refusesAndKeeps("no unlocks at all", "{\"currency\": 100}");
		refusesAndKeeps("no currency at all", "{\"unlocks\": {\"" + COAL + "\": 1}}");
		refusesAndKeeps("unlocks that are not an object", "{\"currency\": 100, \"unlocks\": []}");
		refusesAndKeeps("currency that is not a number", "{\"currency\": \"lots\", \"unlocks\": {}}");
		refusesAndKeeps("a currency that is not whole", "{\"currency\": 1.5, \"unlocks\": {}}");

		// And the shape that is right is still accepted, or the four above would pass just as well
		// with everything refused.
		startFromNothing();
		write(Progress.file(), "{\"currency\": 7, \"unlocks\": {\"" + COAL + "\": 1}}");
		Progress.reloadFromFile();
		check(owns(COAL) && balance() == 7,
				"a well-formed snapshot must still load, and it came back with " + balance()
						+ " and " + COAL + (owns(COAL) ? " owned" : " not owned"));

		startFromNothing();
	}

	private void refusesAndKeeps(String what, String contents) {
		startFromNothing();
		write(Progress.file(), contents);

		check(refusesToLoad(), "a snapshot with " + what + " must refuse to load, and it loaded");
		check(contents.equals(read(Progress.file())),
				"and must leave the file exactly as it found it, so it can still be repaired");
	}

	// --- earning, and paying for a milestone exactly once -------------------------------------

	/**
	 * The gap this closes: a player's advancements are saved on the player-save cycle, and the purse
	 * is written the moment it changes. Crash in between and the game comes back with the money paid
	 * and no record of what it was paid for, and the same milestone can be finished — and paid for —
	 * again.
	 *
	 * <p>So the note saying what has been paid for is in the snapshot, written by the same commit as
	 * the balance. Reading the snapshot again from scratch is this process's nearest thing to a
	 * restart, and the second credit has to be refused on the strength of the <em>file</em> rather
	 * than anything still in memory.
	 */
	private void aRunIsPaidOnceForAMilestoneEvenAfterARestart() {
		startFromNothing();
		reset();

		check(Wallet.get().earnOnce(RUN, MILESTONE, 10),
				"setup: the first credit should pay");
		check(balance() == 10, "and should leave 10 in the purse, which holds " + balance());

		Progress.reloadFromFile();

		check(balance() == 10, "the credit must be on the disk: after reading the snapshot again the"
				+ " purse holds " + balance());
		check(Wallet.get().hasEarned(RUN, MILESTONE),
				"and so must the note saying what it was for, which is what a restart has to find");
		check(!Wallet.get().earnOnce(RUN, MILESTONE, 10),
				"a second credit for the same milestone in the same run must be refused");
		check(balance() == 10,
				"and must pay nothing: the purse went from 10 to " + balance());

		reset();
	}

	/** The other side of the same rule: a new run is a new world, and earns it all again. */
	private void theNextRunIsPaidForTheSameMilestoneAgain() {
		startFromNothing();
		reset();

		check(Wallet.get().earnOnce(RUN, MILESTONE, 10), "setup: run " + RUN + " should be paid");
		check(Wallet.get().earnOnce(RUN + 1, MILESTONE, 10),
				"the next run must be paid for the same milestone, and it was refused");
		check(balance() == 20,
				"so two runs must have paid 10 each, and the purse holds " + balance());
		check(!Wallet.get().hasEarned(RUN, MILESTONE),
				"and the previous run's ledger must be gone rather than kept for ever");

		reset();
	}

	/** A credit is one write like every other, so a write that fails leaves neither half behind. */
	private void aCreditTheDiskWillNotTakePaysNothingAndRemembersNothing() {
		startFromNothing();
		reset();
		block(Progress.file());

		boolean refused = false;
		try {
			Wallet.get().earnOnce(RUN, MILESTONE, 10);
		} catch (PersistenceException expected) {
			refused = true;
		}
		check(refused, "a credit that cannot be written must say so rather than report success");
		check(balance() == 0, "and must not move the purse, which holds " + balance());

		unblock(Progress.file());

		check(Wallet.get().earnOnce(RUN, MILESTONE, 10),
				"and must not have been written down either: the same credit has to pay once the"
						+ " disk will take it");
		check(balance() == 10, "and the purse holds " + balance() + " rather than 10");

		reset();
	}

	// --- plumbing --------------------------------------------------------------------------

	/** @return true if reading progression refused rather than inventing an empty profile. */
	private static boolean refusesToLoad() {
		try {
			Progress.reloadFromFile();
			return false;
		} catch (PersistenceException expected) {
			return true;
		}
	}

	/** No snapshot, nothing loaded: the state a save that has never bought anything is in. */
	private static void startFromNothing() {
		// Raw file work, not through Progress: after a refused load there is nothing to ask.
		delete(Progress.file());
		Progress.reloadFromFile();
	}

	private static void write(Path file, String contents) {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, contents, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not write " + file, e);
		}
	}

	private static String read(Path file) {
		try {
			return Files.readString(file, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not read " + file, e);
		}
	}

	private static void delete(Path file) {
		try {
			Files.deleteIfExists(file);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not remove " + file, e);
		}
	}

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

	private static Offer offer(String id) {
		return Catalogue.offer(id)
				.orElseThrow(() -> new AssertionError("The catalogue does not sell " + id));
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
