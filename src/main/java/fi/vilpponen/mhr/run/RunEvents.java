package fi.vilpponen.mhr.run;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * The seam between the loop and everything a run is made of.
 *
 * <p>A feature that has to do something when a run begins — apply a border tier, put the starter
 * chest down, configure whatever the shop sold — listens here. It does not get called by {@link
 * RunLifecycle} by name, and it does not go looking for a new world by watching some unrelated
 * event and guessing. That is the point: the list of things a run start does is allowed to grow
 * without the world-management code learning about any of them.
 *
 * <p>Both events fire on the server thread, once per run, after the state has been written down —
 * so a listener that crashes cannot leave the record disagreeing with the worlds.
 */
public final class RunEvents {
	/**
	 * A run's worlds exist and the players are in them.
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
	 * <p>Fired exactly once per run even across a crash: see {@link RunRecord#rewarded()}. This is
	 * where currency earned during a run should be credited when that exists.
	 */
	public static final Event<RunEnded> RUN_ENDED =
			EventFactory.createArrayBacked(RunEnded.class, listeners -> (server, run) -> {
				for (RunEnded listener : listeners) {
					listener.onRunEnded(server, run);
				}
			});

	private RunEvents() {
	}

	@FunctionalInterface
	public interface RunStarted {
		void onRunStarted(MinecraftServer server, ServerLevel overworld, RunRecord run);
	}

	@FunctionalInterface
	public interface RunEnded {
		void onRunEnded(MinecraftServer server, RunRecord run);
	}
}
