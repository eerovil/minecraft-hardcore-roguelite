package fi.vilpponen.mhr.progression;

import fi.vilpponen.mhr.core.PersistenceException;

/**
 * How much currency the player has to spend in the shop.
 *
 * <p>A view over {@link Progress}, which owns everything permanent. What this adds is the two
 * questions currency gets asked — how much is there, and is it enough — and the one way it is set.
 *
 * <p>What earns it is {@link fi.vilpponen.mhr.earn.AdvancementPayouts}, which calls
 * {@link #earn(int)} when a run finishes an advancement the balance data prices.
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
	 * Earn something a run can only be paid for once, and record that it has been.
	 *
	 * <p>This is what gameplay earns through, and {@link #earn(int)} is not: an earning rule reacts
	 * to something the game records in its own files, and those are not written when this one is. A
	 * crash in between would otherwise let the same milestone mint the money twice on the way back.
	 * So the money and the note saying what it was for go into one write — see
	 * {@link Progress#creditOnce}.
	 *
	 * @param runId the run being played
	 * @param key what is being paid for, unique within a run and the caller's to compose
	 * @return false if this run has already been paid for this key, in which case nothing changed
	 * @throws PersistenceException if it did not reach the disk, in which case nothing changed
	 */
	public boolean earnOnce(int runId, String key, int amount) {
		if (amount < 0) {
			throw new IllegalArgumentException("Cannot earn a negative amount: " + amount);
		}
		return Progress.get().creditOnce(runId, key, amount);
	}

	/** Has this run already been paid for this key? */
	public boolean hasEarned(int runId, String key) {
		return Progress.get().hasPaid(runId, key);
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
