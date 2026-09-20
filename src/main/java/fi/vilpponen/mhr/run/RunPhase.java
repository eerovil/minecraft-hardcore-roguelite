package fi.vilpponen.mhr.run;

/**
 * Where a save is in the roguelite loop.
 *
 * <p>Four values rather than a pair of booleans, because the two in-between ones are exactly where
 * a crash or a quit is dangerous: half a run has been built, or a run is over but its reward has
 * not been handed out yet. Writing them down is what lets the next start finish the job instead of
 * guessing. See {@link RunRecord} for the transitions and {@code docs/codebase/run-lifecycle.md}
 * for the product rules behind them.
 */
public enum RunPhase {
	/** No run. The player is in the lobby, and the run worlds may not exist at all. */
	LOBBY,

	/** Between "start" and the new worlds being ready. Nothing may be played in this phase. */
	CREATING_RUN,

	/** A run is being played. Quitting here is not death: the next start resumes this run. */
	RUNNING,

	/** The run is over. Its reward may or may not have been committed yet; the record says which. */
	ENDING_RUN;

	/**
	 * @return the phase with this name, or null if no phase has it.
	 *
	 * <p>Null rather than a plausible default on purpose. A saved record whose phase this build
	 * cannot read is a record that cannot be acted on at all — guessing "lobby" would throw away
	 * whatever run it was describing.
	 */
	public static RunPhase byName(String name) {
		for (RunPhase phase : values()) {
			if (phase.name().equalsIgnoreCase(name)) {
				return phase;
			}
		}
		return null;
	}
}
