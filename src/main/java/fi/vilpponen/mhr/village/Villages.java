package fi.vilpponen.mhr.village;

import fi.vilpponen.mhr.Unlock;
import fi.vilpponen.mhr.UnlockState;

/**
 * The one switch the village feature area answers to.
 *
 * <p>Everything that suppresses villages asks this, so when the shop replaces the dev command
 * there is a single place that has to learn about it.
 */
public final class Villages {
	private Villages() {
	}

	/** Whether vanilla village structures may generate in chunks being generated right now. */
	public static boolean generationEnabled() {
		return UnlockState.get().isOwned(Unlock.VILLAGE);
	}
}
