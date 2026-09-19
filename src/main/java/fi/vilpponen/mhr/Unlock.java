package fi.vilpponen.mhr;

/**
 * Everything the shop can sell that is permanent across runs.
 *
 * <p>Only {@link #TREES} and the five equipment slots do anything yet. The rest of the catalogue
 * from the design document (ores, animals, villages, world border, status effects, start chest)
 * gets added here as each one is implemented.
 *
 * <p>The id is one string used everywhere: it is the key in {@code default-balance.json}, the value
 * written to the unlock save file, and what the dev command takes. It is namespaced — {@code world.}
 * for things missing from the world, {@code player.} for things missing from the player — so the
 * shop can group the catalogue without a second table saying which is which. See
 * {@code docs/balance.md}. Renaming one is a save migration, so pick it once: {@link UnlockState}
 * carries the list of old names that still have to be understood.
 */
public enum Unlock {
	TREES("world.trees"),

	/** The five equipment slots, one unlock each. See {@code fi.vilpponen.mhr.equipment}. */
	SLOT_HELMET("player.slot.helmet"),
	SLOT_CHESTPLATE("player.slot.chestplate"),
	SLOT_LEGGINGS("player.slot.leggings"),
	SLOT_BOOTS("player.slot.boots"),
	SLOT_OFFHAND("player.slot.offhand");

	private final String id;

	Unlock(String id) {
		this.id = id;
	}

	public String id() {
		return id;
	}

	public static Unlock byId(String id) {
		for (Unlock unlock : values()) {
			if (unlock.id.equals(id)) {
				return unlock;
			}
		}
		return null;
	}
}
