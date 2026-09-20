package fi.vilpponen.mhr.run;

/**
 * What should happen to a player who has just joined, decided before anything is done to them.
 *
 * <p>Separated from {@link RunLifecycle} so the rule can be asked without a server, because one of
 * its cases is a rule about *not* acting and that is the hardest kind to prove by watching. A save
 * whose record could not be read has an unknown phase — it may have had a run in progress — and
 * every helpful-looking thing that could be done to a player there is a guess across the boundary
 * the stop exists to preserve. Moving them to the lobby is the worst of them: the lobby is
 * permanent, so a run's items carried into it stay, and a saved position there makes the repaired
 * run look like one this player was never admitted to.
 *
 * <p>Plain Java with no Minecraft in it, so the table below is the whole rule and the test is a
 * table too.
 */
public enum RunArrival {
	/** Turned away with the reason. Their save is not touched at all. */
	REFUSED,

	/** Told what is wrong and left exactly where they are. */
	TOLD_AND_LEFT_ALONE,

	/** No run is in progress, so they belong in the lobby. */
	TO_THE_LOBBY,

	/** A run is in progress that they have not crossed the start boundary of. */
	INTO_THE_RUN,

	/** Back in the run they were already playing. Nothing to do. */
	LEFT_WHERE_THEY_ARE;

	/**
	 * @param stopped the save is quarantined and nothing may move the loop on
	 * @param recordUnknown the reason it is stopped is that the record could not be read, so the
	 *     phase is not known — as opposed to a missing lobby, where it is
	 * @param running a run is in progress
	 * @param inARunWorld the player is in one of the run's three dimensions
	 * @param admittedToThisRun the player crossed *this* run's start boundary
	 */
	public static RunArrival decide(boolean stopped, boolean recordUnknown, boolean running,
			boolean inARunWorld, boolean admittedToThisRun) {
		if (stopped) {
			return recordUnknown ? REFUSED : TOLD_AND_LEFT_ALONE;
		}
		if (!running) {
			return TO_THE_LOBBY;
		}
		return inARunWorld && admittedToThisRun ? LEFT_WHERE_THEY_ARE : INTO_THE_RUN;
	}

	/** Does acting on this change where the player is, and so what their save says? */
	public boolean movesThePlayer() {
		return this == TO_THE_LOBBY || this == INTO_THE_RUN;
	}
}
