package fi.vilpponen.mhr.border;

import fi.vilpponen.mhr.core.Balance;
import fi.vilpponen.mhr.core.BalanceException;

/**
 * How big the world is for one run.
 *
 * <p>A run gets one of these and keeps it. There are deliberately only a few big steps rather than
 * many small ones, so moving up a tier is something you notice immediately.
 *
 * <p>Sizes are not written down here. They live in the {@code worldBorder} section of the balance
 * file, next to what each tier costs, so retuning the world is editing data rather than Java — see
 * {@code docs/balance.md}. A tier with no size is the unbounded one: the border stops being
 * something a player can ever reach.
 */
public enum BorderTier {
	TINY("tiny"),
	MEDIUM("medium"),
	LARGE("large"),
	INFINITE("infinite");

	/** The tier a run has when nothing has been unlocked yet. */
	public static final BorderTier DEFAULT = TINY;

	private final String id;

	BorderTier(String id) {
		this.id = id;
	}

	/** The short id, which is also this tier's key in the balance file. */
	public String id() {
		return id;
	}

	/**
	 * The id the shop and the saved unlocks use, e.g. {@code world.border.medium}.
	 *
	 * <p>The price sits under {@code worldBorder} rather than {@code unlocks} so a tier's size and
	 * price stay next to each other; this is the string that names the purchase everywhere else.
	 */
	public String unlockId() {
		return "world.border." + id;
	}

	/**
	 * What this tier is worth in the balance in effect.
	 *
	 * @throws BalanceException if the balance file has no such tier, which means the two lists have
	 *     drifted apart and there is no safe number to invent
	 */
	public Balance.BorderBalance balance(Balance balance) {
		return balance.border(id).orElseThrow(() -> new BalanceException(
				"Balance is missing the border tier 'worldBorder." + id + "'"));
	}

	public static BorderTier byId(String id) {
		for (BorderTier tier : values()) {
			if (tier.id.equals(id)) {
				return tier;
			}
		}
		return null;
	}
}
