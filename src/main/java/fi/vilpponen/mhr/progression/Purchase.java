package fi.vilpponen.mhr.progression;

import fi.vilpponen.mhr.HardcoreRoguelite;
import fi.vilpponen.mhr.UnlockState;

/**
 * Buying one thing, once. The only place in the mod where currency turns into ownership.
 *
 * <p>{@code docs/codebase/progression.md} asks a purchase to make one authoritative decision and to
 * make it in one place: resolve the id, read the price and the level, check the currency, deduct it
 * exactly once, advance ownership exactly once, and persist both. That is this method, and the shop
 * screen and the dev command both go through it rather than writing either side themselves.
 *
 * <p>The two writes are ordered deliberately. Currency comes out first, because
 * {@link Wallet#spend} is the one operation that both checks and deducts under the same lock — a
 * separate "can I afford it?" followed by a deduction is the shape that lets two clicks pay once.
 * Ownership goes up second, and if that somehow does not happen the currency goes straight back,
 * so there is no path where a player pays for nothing. The whole thing holds this class's monitor,
 * so two purchases cannot interleave.
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
		TOO_EXPENSIVE
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
		Offer offer = Catalogue.offer(id).orElse(null);
		if (offer == null) {
			return new Result(Outcome.NOT_FOR_SALE, id, 0, UnlockState.get().level(id), Wallet.get().balance());
		}
		if (offer.isMaxed()) {
			return result(Outcome.ALREADY_MAXED, offer);
		}

		Wallet wallet = Wallet.get();
		if (!wallet.spend(offer.price())) {
			return result(Outcome.TOO_EXPENSIVE, offer);
		}

		if (!UnlockState.get().setLevel(id, offer.level() + 1)) {
			// Unreachable unless the catalogue and the save file disagree about the ceiling, which
			// would be a bug in one of them. Paying for nothing is the one outcome this operation is
			// not allowed to have, so the currency goes back and the attempt is reported as refused.
			wallet.earn(offer.price());
			HardcoreRoguelite.LOGGER.error(
					"Refunded {} for '{}': the catalogue says level {} of {} is available and the unlock"
							+ " state would not take it.",
					offer.price(), id, offer.level() + 1, offer.maxLevel());
			return result(Outcome.ALREADY_MAXED, offer);
		}

		Offer after = Catalogue.offer(id).orElse(offer);
		HardcoreRoguelite.LOGGER.info("Bought '{}' for {}: now level {}/{}, {} left",
				id, offer.price(), after.level(), after.maxLevel(), wallet.balance());
		return new Result(Outcome.BOUGHT, id, offer.price(), after.level(), wallet.balance());
	}

	private static Result result(Outcome outcome, Offer offer) {
		return new Result(outcome, offer.id(), offer.price(), offer.level(), Wallet.get().balance());
	}
}
