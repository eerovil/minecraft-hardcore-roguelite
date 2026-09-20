package fi.vilpponen.mhr.progression;

import fi.vilpponen.mhr.core.PersistenceException;

/**
 * How much currency the player has to spend in the shop.
 *
 * <p>A view over {@link Progress}, which owns everything permanent. What this adds is the two
 * questions currency gets asked — how much is there, and is it enough — and the one way it is set.
 *
 * <p><b>Nothing here earns it.</b> See {@link Progress} for why that is still open.
 */
public final class Wallet {
	private static final Wallet INSTANCE = new Wallet();

	private Wallet() {
	}

	public static Wallet get() {
		Progress.get();
		return INSTANCE;
	}

	/** See {@link Progress#reloadFromFile()}. */
	public static Wallet reloadFromFile() {
		Progress.reloadFromFile();
		return INSTANCE;
	}

	public int balance() {
		return Progress.get().currency();
	}

	public boolean canAfford(int price) {
		return price <= balance();
	}

	/** Put currency in. Negative amounts are not an earning rule, they are a bug, so they throw. */
	public void earn(int amount) {
		if (amount < 0) {
			throw new IllegalArgumentException("Cannot earn a negative amount: " + amount);
		}
		set(balance() + amount);
	}

	/**
	 * Set the total outright.
	 *
	 * <p>There is deliberately no "spend" beside this. A purchase does not move the currency on its
	 * own at all — it hands {@link Progress} the total it should be left with and the level it
	 * should own, and those are written together.
	 *
	 * @throws PersistenceException if it did not reach the disk, in which case nothing changed
	 */
	public void set(int amount) {
		Progress.get().setCurrency(amount);
	}
}
