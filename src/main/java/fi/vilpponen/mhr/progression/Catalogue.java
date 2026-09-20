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
 *
 * <p>It is also where "what is worth selling" lives, as opposed to "what is owned". The world
 * border tiers are the one case so far where those differ — see {@link #levelOf}.
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
		return new Offer(id, price, levelOf(id, state), maxLevelOf(id));
	}

	/**
	 * How far an unlock has been taken, as the shop should sell it.
	 *
	 * <p>Ordinarily that is simply what the player owns. The world border tiers are the exception,
	 * because they are steps rather than choices: the run gets the largest tier owned, so once a
	 * bigger one is bought a smaller one cannot change anything about the world. Selling it anyway
	 * takes currency for nothing at all, and the shop deliberately lets tiers be bought in any
	 * order, so buying Large first made Medium a trap.
	 *
	 * <p>Owning a tier therefore satisfies every tier no bigger than it. Nothing is written to the
	 * snapshot to arrange that — what the player bought stays what is recorded, and this is the view
	 * of it the shop sells from.
	 */
	private static int levelOf(String id, UnlockState state) {
		int owned = state.level(id);
		return owned > 0 || satisfiedByABiggerTier(id, state) ? Math.max(owned, 1) : 0;
	}

	/**
	 * Whether a border tier is already covered by a bigger one the player owns.
	 *
	 * <p>Bigger by size, which is the ordering the balance data itself carries and the one that
	 * makes the smaller purchase pointless — rather than the order the tiers happen to be listed
	 * in, or the {@code BorderTier} constants, which are the border feature's business and not the
	 * catalogue's. An unbounded tier is bigger than everything.
	 *
	 * <p>False for every id that is not a border tier, which is all of them but four.
	 */
	private static boolean satisfiedByABiggerTier(String id, UnlockState state) {
		Balance balance = BalanceManager.get();
		Balance.BorderBalance wanted = balance.borderByUnlockId(id).orElse(null);
		if (wanted == null) {
			return false;
		}
		for (Balance.BorderBalance tier : balance.worldBorder().values()) {
			if (state.isOwned(Balance.BORDER_UNLOCK_PREFIX + tier.id()) && atLeastAsBigAs(tier, wanted)) {
				return true;
			}
		}
		return false;
	}

	private static boolean atLeastAsBigAs(Balance.BorderBalance owned, Balance.BorderBalance wanted) {
		if (owned.isUnbounded()) {
			return true;
		}
		if (wanted.isUnbounded()) {
			return false;
		}
		return owned.size().getAsDouble() >= wanted.size().getAsDouble();
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
