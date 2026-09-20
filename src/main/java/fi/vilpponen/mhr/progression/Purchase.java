package fi.vilpponen.mhr.progression;

import fi.vilpponen.mhr.HardcoreRoguelite;
import fi.vilpponen.mhr.UnlockState;
import fi.vilpponen.mhr.core.PersistenceException;
import java.io.IOException;

/**
 * Buying one thing, once. The only place in the mod where currency turns into ownership.
 *
 * <p>{@code docs/codebase/progression.md} asks a purchase to make one authoritative decision and to
 * make it in one place: resolve the id, read the price and the level, check the currency, deduct it
 * exactly once, advance ownership exactly once, and persist both. That is this method, and the shop
 * screen and the dev command both go through it rather than writing either side themselves.
 *
 * <p>Only one purchase may be outstanding at a time. There is one commit record, so a second
 * purchase committed while the first is unfinished would write over the only note of the first —
 * losing that unlock while keeping both prices. So every purchase settles whatever is outstanding
 * before it commits anything, and refuses if it cannot: a shop that says no for a moment is better
 * than a shop that quietly eats a purchase.
 *
 * <p>The awkward part is that the two halves live in two files, and no ordering of two writes is
 * safe on its own: a crash between them leaves either a free unlock or currency spent on nothing.
 * So neither write is the commit point. {@link PurchaseJournal} is — a third small file holding the
 * whole intended outcome, written atomically before either of the other two is touched. Before it
 * lands the purchase can still be refused; after it lands the purchase is the player's, and if the
 * game stops before both files catch up, the next start finishes the job.
 *
 * <p>Deciding what to charge and writing the journal both happen while holding the wallet's own
 * monitor, so the total written into the record is the total the wallet is about to have — a
 * separate "can I afford it?" followed by a deduction is the shape that lets two clicks pay once.
 * The whole method holds this class's monitor as well, so two purchases cannot interleave.
 *
 * <p>There are deliberately no refunds, no dynamic prices, no prerequisites and no per-run
 * ownership. The design says every option is visible and buyable from the start if it is
 * affordable, and at a fixed price.
 */
public final class Purchase {
	/** Why a purchase did or did not happen. Exactly one of these is true of any attempt. */
	public enum Outcome {
		BOUGHT,
		/** Nothing in the balance catalogue sells that id. */
		NOT_FOR_SALE,
		/** Owned already, or a repeatable one that is at its maximum level. */
		ALREADY_MAXED,
		/** Real, affordable one day, not today. */
		TOO_EXPENSIVE,
		/**
		 * Nothing is wrong with the purchase; the disk would not take it. Nothing was charged and
		 * nothing was granted, because the commit record never landed.
		 */
		NOT_SAVED
	}

	/**
	 * What happened, and what the player's progression looks like now.
	 *
	 * @param level the level owned after the attempt, so a refused attempt reports the old one
	 * @param balance the currency left after the attempt, likewise
	 */
	public record Result(Outcome outcome, String id, int price, int level, int balance) {
		public boolean bought() {
			return outcome == Outcome.BOUGHT;
		}
	}

	private Purchase() {
	}

	/** Buy the next level of one id. See the class comment for the order the two writes happen in. */
	public static synchronized Result buy(String id) {
		// Before anything else, including reading the price: settling can change what is owned and
		// what is left to spend, and an offer read before it would be describing the state the last
		// purchase was interrupted in.
		boolean settled = PurchaseJournal.settle();

		Offer offer = Catalogue.offer(id).orElse(null);
		if (offer == null) {
			return new Result(Outcome.NOT_FOR_SALE, id, 0, UnlockState.get().level(id), Wallet.get().balance());
		}
		if (!settled) {
			// The record for the earlier purchase is still the only note of it. Committing this one
			// would replace it, so this one does not happen.
			HardcoreRoguelite.LOGGER.error(
					"Refusing to sell '{}': an earlier purchase is still waiting to be written down", id);
			return result(Outcome.NOT_SAVED, offer);
		}
		if (offer.isMaxed()) {
			return result(Outcome.ALREADY_MAXED, offer);
		}

		Wallet wallet = Wallet.get();
		// One level, and never past the ceiling the maxed check above has already cleared — so what
		// goes in the record is exactly what the unlock file will accept, and the two cannot end up
		// disagreeing about what was bought.
		int level = offer.level() + 1;

		synchronized (wallet) {
			if (!wallet.canAfford(offer.price())) {
				return result(Outcome.TOO_EXPENSIVE, offer);
			}
			int balance = wallet.balance() - offer.price();

			try {
				PurchaseJournal.commit(new PurchaseJournal.Record(id, level, balance));
			} catch (IOException e) {
				// Before the commit point, so nothing has moved and the player still has their
				// currency. Saying so is the whole point of this outcome existing.
				HardcoreRoguelite.LOGGER.error("Refusing to sell '{}': the purchase could not be written down", id, e);
				return result(Outcome.NOT_SAVED, offer);
			}

			// Past the commit point. Both of these put the new value in memory before writing it, so
			// the running game is right either way, and a write that fails leaves the record behind
			// for the next start to finish.
			apply(wallet, id, level, balance);
		}

		HardcoreRoguelite.LOGGER.info("Bought '{}' for {}: now level {}/{}, {} left",
				id, offer.price(), level, offer.maxLevel(), Wallet.get().balance());
		return new Result(Outcome.BOUGHT, id, offer.price(), level, Wallet.get().balance());
	}

	/**
	 * Put both halves where the record says they belong, and take the record away once they are
	 * there.
	 *
	 * <p>A failed write is logged rather than thrown, because by this point refusing would be a lie:
	 * the purchase is committed and the next start will finish it. What the failure costs is the
	 * record staying behind, which is exactly what makes that possible.
	 */
	private static void apply(Wallet wallet, String id, int level, int balance) {
		boolean written = true;
		try {
			wallet.set(balance);
		} catch (PersistenceException e) {
			written = false;
			HardcoreRoguelite.LOGGER.error("Bought '{}', and the currency did not reach the disk."
					+ " It will be put right on the next start.", id, e);
		}
		try {
			UnlockState.get().setLevel(id, level);
		} catch (PersistenceException e) {
			written = false;
			HardcoreRoguelite.LOGGER.error("Bought '{}', and the unlock did not reach the disk."
					+ " It will be put right on the next start.", id, e);
		}
		if (written) {
			PurchaseJournal.done();
		}
	}

	private static Result result(Outcome outcome, Offer offer) {
		return new Result(outcome, offer.id(), offer.price(), offer.level(), Wallet.get().balance());
	}
}
