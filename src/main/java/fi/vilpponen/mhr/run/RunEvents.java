package fi.vilpponen.mhr.run;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * The seam between the loop and everything a run is made of.
 *
 * <p>A feature that has to do something when a run begins — apply a border tier, put the starter
 * chest down, configure whatever the shop sold — listens here. It does not get called by {@link
 * RunLifecycle} by name, and it does not go looking for a new world by watching some unrelated
 * event and guessing. That is the point: the list of things a run start does is allowed to grow
 * without the world-management code learning about any of them.
 *
 * <p>Both fire on the server thread and neither swallows an exception. A listener that throws
 * stops the boundary it was called from: a failed run start is abandoned back to the lobby, and a
 * failed run end leaves the run owing its reward so it can be tried again. That is the opposite of
 * the usual "log it and carry on", and deliberately so — the alternative is a run that silently
 * skipped its setup, or a reward that was never handed over and never will be.
 */
public final class RunEvents {
	/**
	 * A run's worlds exist and nobody is in them yet.
	 *
	 * <p>Fired while the record still says {@link RunPhase#CREATING_RUN}. That is what makes a
	 * listener's failure survivable: nothing has been written down as a playable run, so the
	 * lifecycle can abandon it rather than leave a run that skipped whatever this listener does.
	 *
	 * <p>The overworld handed over is this run's overworld, which is a different object from the
	 * one the previous run used. A listener must not have cached the old one.
	 */
	public static final Event<RunStarted> RUN_STARTED =
			EventFactory.createArrayBacked(RunStarted.class, listeners -> (server, overworld, run) -> {
				for (RunStarted listener : listeners) {
					listener.onRunStarted(server, overworld, run);
				}
			});

	/**
	 * A run is over and its permanent reward is being committed.
	 *
	 * <p><b>This is at least once, not exactly once, and a listener that grants permanent
	 * progression must be idempotent for {@code run.runId()}.</b> The run is written down as
	 * rewarded only after the listeners return, so a process that dies in between — or a write that
	 * fails — comes back with the reward still owed and calls them again for the same run id. The
	 * lifecycle cannot close that window on its own: only the store being written to can, by
	 * recording which run it has already paid and ignoring a repeat.
	 *
	 * <p>So when currency arrives, credit it against the run id and make a second call for the same
	 * id a no-op. Throwing is the right response to a payout that genuinely failed: the run then
	 * stays unfinished and is retried, rather than being recorded as paid.
	 */
	public static final Event<RunEnded> RUN_ENDED =
			EventFactory.createArrayBacked(RunEnded.class, listeners -> (server, run) -> {
				for (RunEnded listener : listeners) {
					listener.onRunEnded(server, run);
				}
			});

	/**
	 * One player has crossed into a run: moved, reset, and about to be marked as admitted.
	 *
	 * <p>This is the hook for anything that has to be applied <em>to a player</em> at run start —
	 * the permanent status effects and other personal upgrades the design has planned. It is a
	 * separate event from {@link #RUN_STARTED} for two reasons, and both are load-bearing:
	 *
	 * <ul>
	 *   <li>It fires <em>after</em> the fresh-run reset, which clears effects, hunger and the rest.
	 *       A listener on {@code RUN_STARTED} that gave somebody a status effect would have it
	 *       wiped moments later.
	 *   <li>It fires for every player who enters the run, including one who was offline when it
	 *       started and joins later. {@code RUN_STARTED} happens once, when nobody is in the run
	 *       yet, so a late joiner would never have seen it.
	 * </ul>
	 *
	 * <p>World setup — borders, the starter chest, anything about the place rather than the person
	 * — belongs on {@link #RUN_STARTED} instead.
	 *
	 * <p>The record handed over may still say {@link RunPhase#CREATING_RUN} when the run is being
	 * started, and {@link RunPhase#RUNNING} when somebody joins one already going. Its run id is
	 * the run being entered either way, which is the part a listener should care about.
	 */
	public static final Event<PlayerEnteredRun> PLAYER_ENTERED_RUN =
			EventFactory.createArrayBacked(PlayerEnteredRun.class, listeners -> (server, player, run) -> {
				for (PlayerEnteredRun listener : listeners) {
					listener.onPlayerEnteredRun(server, player, run);
				}
			});

	private RunEvents() {
	}

	@FunctionalInterface
	public interface RunStarted {
		void onRunStarted(MinecraftServer server, ServerLevel overworld, RunRecord run);
	}

	@FunctionalInterface
	public interface PlayerEnteredRun {
		void onPlayerEnteredRun(MinecraftServer server, ServerPlayer player, RunRecord run);
	}

	@FunctionalInterface
	public interface RunEnded {
		void onRunEnded(MinecraftServer server, RunRecord run);
	}
}
