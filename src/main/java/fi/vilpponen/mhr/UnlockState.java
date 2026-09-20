package fi.vilpponen.mhr;

import fi.vilpponen.mhr.progression.Progress;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Which unlocks the player owns, and how far each repeatable one has been taken.
 *
 * <p>A view, not a store. What is owned lives in {@link Progress}, in one file with the currency,
 * because a purchase moves both and there is no such thing as half of one. This is the name the
 * rest of the mod asks by, and the place the rules about unlock ids live: what an id's ceiling is,
 * and what counts as an id this build can act on.
 *
 * <p>What is owned is keyed by id, not by enum constant. Most ids have an {@link Unlock} behind
 * them, because some Java asks whether they are owned. Some have none: a starter item is nothing
 * but an entry in the balance catalogue, and adding one should not mean adding a constant. Keying
 * on the id means the two kinds are the same kind here, in the file, and in the dev command —
 * which is the point of ids being strings in the first place.
 *
 * <p>Read from the worldgen threads. {@link Progress} is synchronized and hands out an immutable
 * map, so there is nothing to guard here.
 */
public final class UnlockState {
	private static final UnlockState INSTANCE = new UnlockState();

	private UnlockState() {
	}

	public static UnlockState get() {
		// Touching the store here means the first caller still pays for the load, exactly as before.
		Progress.get();
		return INSTANCE;
	}

	/**
	 * Throw away what is loaded and read the file again.
	 *
	 * <p>For the automated tests, where the dedicated server runs inside the client's own process,
	 * so a test that wants to know whether a purchase really reached the disk has no process
	 * boundary to cross. See {@link Progress#reloadFromFile()}.
	 *
	 * @return the state as the file on disk now says it is
	 */
	public static UnlockState reloadFromFile() {
		Progress.reloadFromFile();
		return INSTANCE;
	}

	public boolean isOwned(Unlock unlock) {
		return level(unlock) > 0;
	}

	/** @param id a stable unlock id, whether or not an {@link Unlock} constant carries it. */
	public boolean isOwned(String id) {
		return level(id) > 0;
	}

	/** How many times this unlock has been bought: zero when it is not owned at all. */
	public int level(Unlock unlock) {
		return level(unlock.id());
	}

	public int level(String id) {
		return Progress.get().level(id);
	}

	/** @return true if this changed anything. */
	public boolean set(Unlock unlock, boolean value) {
		return setLevel(unlock.id(), value ? Math.max(level(unlock), 1) : 0);
	}

	/** @return true if this changed anything. */
	public boolean set(String id, boolean value) {
		return setLevel(id, value ? Math.max(level(id), 1) : 0);
	}

	/**
	 * Set an unlock's level. Clamped to what the balance data allows the unlock to reach, so callers
	 * can hand over whatever a player typed.
	 *
	 * @return true if this changed anything.
	 * @throws fi.vilpponen.mhr.core.PersistenceException if it did not reach the disk, in which case
	 *     nothing changed at all
	 */
	public boolean setLevel(Unlock unlock, int level) {
		return setLevel(unlock.id(), level);
	}

	/** @return true if this changed anything. */
	public boolean setLevel(String id, int level) {
		int clamped = Math.clamp(level, 0, maxLevelOf(id));
		if (clamped == level(id)) {
			return false;
		}
		Progress.get().setLevel(id, clamped);
		return true;
	}

	/**
	 * How far an id can be taken.
	 *
	 * <p>A constant answers for itself, from balance data. Everything else the catalogue sells — a
	 * starter item, or an unlock whose code has not been written yet — is bought once and no more.
	 */
	private static int maxLevelOf(String id) {
		Unlock unlock = Unlock.byId(id);
		return unlock == null ? 1 : unlock.maxLevel();
	}

	public String describe() {
		Map<String, Integer> levels = Progress.get().levels();
		if (levels.isEmpty()) {
			return "(nothing)";
		}
		StringBuilder description = new StringBuilder();
		for (Map.Entry<String, Integer> entry : levels.entrySet()) {
			if (!description.isEmpty()) {
				description.append(", ");
			}
			description.append(entry.getKey());
			if (entry.getValue() > 1) {
				description.append(' ').append(entry.getValue());
			}
		}
		return description.toString();
	}

	/** The owned ids that a constant is named after. Ones with no constant are simply not here. */
	public Set<Unlock> owned() {
		Set<Unlock> owned = EnumSet.noneOf(Unlock.class);
		for (String id : Progress.get().levels().keySet()) {
			Unlock unlock = Unlock.byId(id);
			if (unlock != null) {
				owned.add(unlock);
			}
		}
		return Collections.unmodifiableSet(owned);
	}

	/** Every owned id, including the ones no {@link Unlock} constant carries. */
	public Set<String> ownedIds() {
		return Set.copyOf(Progress.get().levels().keySet());
	}
}
