package fi.vilpponen.mhr.shop.client;

import fi.vilpponen.mhr.progression.Offer;
import fi.vilpponen.mhr.shop.Reward;
import java.util.List;
import java.util.Map;

/**
 * What the server last told this client about the shop — a client-side cache, nothing more.
 *
 * <p>The same arrangement as {@code SyncedSlotUnlocks}, and for the same reason: a connected client
 * cannot read the server's progression snapshot, but it still has to draw prices and grey out
 * what is out of reach. It is written only by the client's payload handler and read only by the
 * screen.
 *
 * <p>No decision ever comes near it. A click sends an id and the server decides; a stale or
 * tampered copy here can make the screen look wrong, and cannot make a purchase happen.
 */
public final class SyncedShop {
	/** Empty rather than null, so a screen opened before any packet simply has nothing to draw. */
	private static volatile List<Offer> offers = List.of();
	private static volatile Map<String, Reward> rewards = Map.of();
	private static volatile int currency;

	/**
	 * Has this server said anything at all?
	 *
	 * <p>Nothing here can tell "the player has no currency" from "nobody has told us", and the HUD
	 * has to: a client on a vanilla server would otherwise draw a purse of zero for a game that has
	 * no purse in it.
	 */
	private static volatile boolean known;

	private SyncedShop() {
	}

	/** Called on the client when the server sends the shop. */
	public static void accept(int newCurrency, List<Offer> newOffers, Map<String, Reward> newRewards) {
		currency = newCurrency;
		known = true;
		offers = List.copyOf(newOffers);
		rewards = Map.copyOf(newRewards);
	}

	/** Called on the client when it leaves a server, so nothing carries into the next one. */
	public static void forget() {
		currency = 0;
		offers = List.of();
		rewards = Map.of();
		known = false;
	}

	/** See {@link #known}. */
	public static boolean isKnown() {
		return known;
	}

	/**
	 * What an offer will actually hand over, as the server last said.
	 *
	 * <p>{@link Reward#NONE} for everything whose effect is code rather than an item, and for
	 * anything at all before the server has spoken.
	 */
	public static Reward reward(String unlockId) {
		return rewards.getOrDefault(unlockId, Reward.NONE);
	}

	public static List<Offer> offers() {
		return offers;
	}

	public static int currency() {
		return currency;
	}
}
