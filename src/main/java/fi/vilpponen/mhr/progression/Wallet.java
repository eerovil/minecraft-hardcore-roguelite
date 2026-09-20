package fi.vilpponen.mhr.progression;

import fi.vilpponen.mhr.core.PersistenceException;

/**
 * How much currency the player has to spend in the shop.
 *
 * <p>A view, not a store. The total lives in {@link Progress}, in one file with what is owned,
 * because a purchase moves both and a state where one moved and the other did not is not a state
 * the game should be in.
 *
 * <p><b>What this deliberately does not do is earn anything.</b> How currency is paid out — what it
 * is paid for, whether a run that ends badly pays, whether the total is visible during a run — is
 * still the biggest open design question in {@code docs/open-questions.md}, and the balance file's
 * {@code currency.advancements} table is a price list waiting for the rule rather than the rule
 * itself. Until that is decided, the only things that move this number are the shop spending it and
 * the dev command granting it.
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
