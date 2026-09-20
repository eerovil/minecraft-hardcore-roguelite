package fi.vilpponen.mhr.shop;

/**
 * Where the shop's words come from: translation keys built out of the stable unlock id.
 *
 * <p>There is no table of display names in Java and no {@code name} key in the balance file. The id
 * already identifies the thing everywhere else, so it identifies its wording too, and the wording
 * lives in the language file where a translator can reach it. Adding a catalogue entry means adding
 * two lines to {@code en_us.json}; forgetting to is a visible untranslated key rather than a crash,
 * and the entry is still there and still buyable.
 */
public final class ShopText {
	private static final String UNLOCK_PREFIX = "mhr.shop.unlock.";

	private ShopText() {
	}

	/** The short name shown under the icon's tooltip, e.g. {@code mhr.shop.unlock.world.trees}. */
	public static String nameKey(String unlockId) {
		return UNLOCK_PREFIX + unlockId;
	}

	/** The one-line explanation, shown only on hover so it never crowds the layout out. */
	public static String descriptionKey(String unlockId) {
		return UNLOCK_PREFIX + unlockId + ".desc";
	}

	/** A row heading, e.g. {@code mhr.shop.group.ores}. */
	public static String groupKey(String groupId) {
		return "mhr.shop.group." + groupId;
	}

	/** A section heading: vanilla restoration, or Vanilla+. */
	public static String tierKey(String tierId) {
		return "mhr.shop.tier." + tierId;
	}
}
