package fi.vilpponen.mhr;

/**
 * Everything the shop can sell that is permanent across runs.
 *
 * <p>Only {@link #TREES} does anything yet. The rest of the catalogue from the design document
 * (ores, animals, villages, armor slots, offhand, world border, status effects, start chest) gets
 * added here as each one is implemented.
 */
public enum Unlock {
	TREES("trees");

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
