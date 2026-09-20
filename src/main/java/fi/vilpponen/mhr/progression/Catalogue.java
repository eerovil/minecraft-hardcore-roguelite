package fi.vilpponen.mhr.progression;

import fi.vilpponen.mhr.Unlock;
import fi.vilpponen.mhr.UnlockState;
import fi.vilpponen.mhr.core.Balance;
import fi.vilpponen.mhr.core.BalanceManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Everything that is for sale, at the price the balance data says and the level the player owns.
 *
 * <p>There is no product list here. The balance catalogue is the product catalogue — that is the
 * rule in {@code docs/codebase/progression.md} — so this reads {@code unlocks} in file order and
 * adds the border tiers, whose price sits in its own section next to each tier's size. Adding an
 * unlock is adding a key to {@code default-balance.json}; nothing in this file changes, and nothing
 * in the shop screen does either.
 *
 * <p>Nothing is cached, so {@code /mhr reload} reaches a changed price the next time the shop is
 * drawn or a purchase is made.
 *
 * <p>This is the layer the shop talks to. It does not know what a shop looks like, and gameplay
 * features do not know this exists.
 */
public final class Catalogue {
	private Catalogue() {
	}

	/**
	 * Everything for sale, catalogue order first and the border tiers after it.
	 *
	 * <p>The order is the balance file's own, which is a sensible order to read but is not the
	 * order the shop draws: grouping the catalogue for a player to look at is presentation, and it
	 * happens in the shop package against this list.
	 */
	public static List<Offer> offers() {
		Balance balance = BalanceManager.get();
		UnlockState state = UnlockState.get();
		List<Offer> offers = new ArrayList<>();

		for (Balance.UnlockBalance unlock : balance.unlocks().values()) {
			offers.add(offerOf(unlock.id(), unlock.price(), state));
		}
		for (Balance.BorderBalance tier : balance.worldBorder().values()) {
			String id = Balance.BORDER_UNLOCK_PREFIX + tier.id();
			offers.add(offerOf(id, tier.price(), state));
		}
		return List.copyOf(offers);
	}

	/** Empty if nothing in the balance in effect sells this id. */
	public static Optional<Offer> offer(String id) {
		Balance balance = BalanceManager.get();
		UnlockState state = UnlockState.get();

		Optional<Integer> price = balance.unlock(id).map(Balance.UnlockBalance::price);
		if (price.isEmpty()) {
			price = balance.borderByUnlockId(id).map(Balance.BorderBalance::price);
		}
		return price.map(value -> offerOf(id, value, state));
	}

	/** Whether the shop sells this id at all. */
	public static boolean sells(String id) {
		return offer(id).isPresent();
	}

	private static Offer offerOf(String id, int price, UnlockState state) {
		return new Offer(id, price, state.level(id), maxLevelOf(id));
	}

	/**
	 * How far an id can be taken.
	 *
	 * <p>A constant answers for itself, from balance data — that is where the repeatable unlocks
	 * get their ceiling. Everything else the catalogue sells is bought once, which is the same
	 * answer {@code UnlockState} clamps to, so the shop and the save file cannot disagree about
	 * what "maxed" means.
	 */
	private static int maxLevelOf(String id) {
		Unlock unlock = Unlock.byId(id);
		return unlock == null ? 1 : unlock.maxLevel();
	}
}
