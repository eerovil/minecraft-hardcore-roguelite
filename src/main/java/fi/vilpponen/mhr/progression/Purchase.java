package fi.vilpponen.mhr.progression;

import fi.vilpponen.mhr.HardcoreRoguelite;
import fi.vilpponen.mhr.UnlockState;
import fi.vilpponen.mhr.core.PersistenceException;

/**
 * Buying one thing, once. The only place in the mod where currency turns into ownership.
 *
 * <p>{@code docs/codebase/progression.md} asks a purchase to make one authoritative decision and to
 * make it in one place: resolve the id, read the price and the level, check the currency, deduct it
 * exactly once, advance ownership exactly once, and persist both. That is this method, and the shop
 * screen's click is the only thing in the game that reaches it.
 *
 * <p>{@code /mhr unlock} deliberately does not come through here. It is a development adapter that
 * grants ownership directly and charges nothing, so that a scenario can put progression where it
 * needs it without first arranging the currency for it. Anything that is supposed to cost something
 * goes through this method; if a second caller ever appears that does, it comes here too.
 *
 * <p>What makes that simple is that {@link Progress} persists currency and ownership together: this
 * works out what the whole of progression should look like afterwards and asks for it in one write,
 * which either lands or does not. There is no ordering to get right here and nothing half-applied
 * to recover from — see {@link Progress} for why.
 *
 * <p>The method holds this class's monitor and {@link Progress} holds its own, so two purchases
 * cannot interleave and neither can a purchase and a dev command.
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
		 * nothing was granted, because the snapshot that would have said otherwise never landed.
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

	/** Buy the next level of one id. */
	public static synchronized Result buy(String id) {
		Offer offer = Catalogue.offer(id).orElse(null);
		if (offer == null) {
			return new Result(Outcome.NOT_FOR_SALE, id, 0, UnlockState.get().level(id), Wallet.get().balance());
		}
		if (offer.isMaxed()) {
			return result(Outcome.ALREADY_MAXED, offer);
		}

		Progress progress = Progress.get();
		synchronized (progress) {
			if (progress.currency() < offer.price()) {
				return result(Outcome.TOO_EXPENSIVE, offer);
			}
			// One level, and never past the ceiling the maxed check above has already cleared, so
			// what is written is exactly what was sold.
			int level = offer.level() + 1;
			int balance = progress.currency() - offer.price();

			try {
				progress.buy(id, level, balance);
			} catch (PersistenceException e) {
				HardcoreRoguelite.LOGGER.error("Refusing to sell '{}': progression could not be written", id, e);
				return result(Outcome.NOT_SAVED, offer);
			}

			HardcoreRoguelite.LOGGER.info("Bought '{}' for {}: now level {}/{}, {} left",
					id, offer.price(), level, offer.maxLevel(), balance);
			return new Result(Outcome.BOUGHT, id, offer.price(), level, balance);
		}
	}

	private static Result result(Outcome outcome, Offer offer) {
		return new Result(outcome, offer.id(), offer.price(), offer.level(), Wallet.get().balance());
	}
}
