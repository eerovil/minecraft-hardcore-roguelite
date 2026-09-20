package fi.vilpponen.mhr.shop.client;

import fi.vilpponen.mhr.progression.Offer;
import java.util.List;

/**
 * What the server last told this client about the shop — a client-side cache, nothing more.
 *
 * <p>The same arrangement as {@code SyncedSlotUnlocks}, and for the same reason: a connected client
 * cannot read the server's unlock or currency files, but it still has to draw prices and grey out
 * what is out of reach. It is written only by the client's payload handler and read only by the
 * screen.
 *
 * <p>No decision ever comes near it. A click sends an id and the server decides; a stale or
 * tampered copy here can make the screen look wrong, and cannot make a purchase happen.
 */
public final class SyncedShop {
	/** Empty rather than null, so a screen opened before any packet simply has nothing to draw. */
	private static volatile List<Offer> offers = List.of();
	private static volatile int currency;

	private SyncedShop() {
	}

	/** Called on the client when the server sends the shop. */
	public static void accept(int newCurrency, List<Offer> newOffers) {
		currency = newCurrency;
		offers = List.copyOf(newOffers);
	}

	/** Called on the client when it leaves a server, so nothing carries into the next one. */
	public static void forget() {
		currency = 0;
		offers = List.of();
	}

	public static List<Offer> offers() {
		return offers;
	}

	public static int currency() {
		return currency;
	}
}
