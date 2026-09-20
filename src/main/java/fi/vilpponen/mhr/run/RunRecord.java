package fi.vilpponen.mhr.run;

/**
 * Everything the save remembers about the loop, and the only place that decides which move is legal
 * next.
 *
 * <p>This is deliberately plain Java with no Minecraft in it. The rules that matter — a run cannot
 * be started twice, a death ends a run once, a reward is not written down as given until it has
 * been given — are rules about these six numbers, so they are decided here and tested without
 * starting a game. {@link RunLifecycle} does
 * the world surgery around the answers; it does not get to form its own opinion about them.
 *
 * <p>Every transition throws {@link IllegalStateException} when the phase does not allow it, rather
 * than returning the record unchanged. A second "start run" is a bug in the caller, not a
 * no-op worth swallowing, and the coordinator turns the throw into a message for whoever asked.
 *
 * @param phase where the save is in the loop
 * @param runId the current (or most recent) run, counting from one; zero means no run has ever
 *     started in this save
 * @param seed the world seed the current run was generated from
 * @param startedAt wall-clock milliseconds when the current run started, for run statistics
 * @param completedRuns how many runs have been played to their end
 * @param rewardedRunId the last run whose reward was committed; equal to {@code runId} exactly when
 *     the current run has already been paid out
 */
public record RunRecord(
		RunPhase phase, int runId, long seed, long startedAt, int completedRuns, int rewardedRunId) {

	/** A save nobody has played yet: in the lobby, no run behind it. */
	public static final RunRecord NEW_SAVE = new RunRecord(RunPhase.LOBBY, 0, 0L, 0L, 0, 0);

	public boolean isRunning() {
		return phase == RunPhase.RUNNING;
	}

	/** True when a run has ended and its reward has not been committed yet. */
	public boolean rewardOutstanding() {
		return phase == RunPhase.ENDING_RUN && rewardedRunId != runId;
	}

	/**
	 * Claim the start of a run.
	 *
	 * <p>Called before any world is touched, so that a crash halfway through world creation is
	 * found in {@link RunPhase#CREATING_RUN} next time and cleaned up rather than left to look like
	 * a playable run.
	 */
	public RunRecord beginCreating(long seed, long now) {
		require(phase == RunPhase.LOBBY, "a run can only be started from the lobby, not from " + phase);
		return new RunRecord(RunPhase.CREATING_RUN, runId + 1, seed, now, completedRuns, rewardedRunId);
	}

	/** The worlds exist and everything a run start owes them has been done. */
	public RunRecord created() {
		require(phase == RunPhase.CREATING_RUN, "no run is being created; phase is " + phase);
		return new RunRecord(RunPhase.RUNNING, runId, seed, startedAt, completedRuns, rewardedRunId);
	}

	/**
	 * The run is over.
	 *
	 * <p>Only a {@link RunPhase#RUNNING} save can end, which is the whole of "a death ends a run
	 * only once": the second death in the same tick, or a death that arrives while the first one is
	 * still being processed, finds the phase already moved on.
	 */
	public RunRecord beginEnding() {
		require(phase == RunPhase.RUNNING, "no run is in progress; phase is " + phase);
		return new RunRecord(RunPhase.ENDING_RUN, runId, seed, startedAt, completedRuns, rewardedRunId);
	}

	/**
	 * Write down that this run's reward has been handed over.
	 *
	 * <p>Recorded as the run's own id rather than a flag, so that the record can be written before
	 * and after the payout and a crash in between is still answerable: if the id does not match,
	 * the reward has not been given and the next start gives it.
	 */
	public RunRecord rewarded() {
		require(phase == RunPhase.ENDING_RUN, "no run is ending; phase is " + phase);
		require(rewardOutstanding(), "run " + runId + " has already been rewarded");
		return new RunRecord(phase, runId, seed, startedAt, completedRuns, runId);
	}

	/** Back to the lobby, with this run counted. */
	public RunRecord returnedToLobby() {
		require(phase == RunPhase.ENDING_RUN, "no run is ending; phase is " + phase);
		require(!rewardOutstanding(), "run " + runId + " has not been rewarded yet");
		return new RunRecord(RunPhase.LOBBY, runId, seed, startedAt, completedRuns + 1, rewardedRunId);
	}

	/**
	 * Give up on a run that was being built.
	 *
	 * <p>The run id stays spent. A half-built run that is started again gets the next id, because
	 * ids are how a reward is recognised and two different attempts must not answer to the same
	 * one.
	 */
	public RunRecord abandoned() {
		require(phase == RunPhase.CREATING_RUN, "no run is being created; phase is " + phase);
		return new RunRecord(RunPhase.LOBBY, runId, seed, startedAt, completedRuns, rewardedRunId);
	}

	/**
	 * What this record means after a restart.
	 *
	 * <p>The only phase that cannot survive a restart is {@link RunPhase#CREATING_RUN}: the worlds
	 * it was building are half-made, and nobody ever played them, so the save falls back to the
	 * lobby and the next start builds again from scratch under a new id. A run that was simply
	 * being played is still being played — quitting the game is not dying — and a run that was
	 * ending is still ending, which is how a reward that was interrupted gets handed over rather
	 * than lost.
	 */
	public RunRecord recovered() {
		return phase == RunPhase.CREATING_RUN ? abandoned() : this;
	}

	public String describe() {
		return switch (phase) {
			case LOBBY -> completedRuns == 0
					? "in the lobby, no run played yet"
					: "in the lobby after " + completedRuns + " run(s)";
			case CREATING_RUN -> "building run " + runId + " (seed " + seed + ")";
			case RUNNING -> "run " + runId + " in progress (seed " + seed + ")";
			case ENDING_RUN -> "run " + runId + " ending"
					+ (rewardOutstanding() ? ", reward not committed yet" : ", reward committed");
		};
	}

	private static void require(boolean allowed, String why) {
		if (!allowed) {
			throw new IllegalStateException(why);
		}
	}
}
