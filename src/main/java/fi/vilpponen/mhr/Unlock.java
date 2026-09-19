package fi.vilpponen.mhr;

/**
 * Everything the shop can sell that is permanent across runs.
 *
 * <p>Only {@link #TREES} and the five equipment slots do anything yet. The rest of the catalogue
 * from the design document (ores, animals, villages, world border, status effects, start chest)
 * gets added here as each one is implemented.
 */
public enum Unlock {
	TREES("trees"),

	/** The five equipment slots, one unlock each. See {@code fi.vilpponen.mhr.equipment}. */
	SLOT_HELMET("slot_helmet"),
	SLOT_CHESTPLATE("slot_chestplate"),
	SLOT_LEGGINGS("slot_leggings"),
	SLOT_BOOTS("slot_boots"),
	SLOT_OFFHAND("slot_offhand");

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
