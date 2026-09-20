package fi.vilpponen.mhr.run;

import fi.vilpponen.mhr.HardcoreRoguelite;
import fi.vilpponen.mhr.mixin.FoodDataAccessor;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.level.storage.LevelResource;

/**
 * The one thing that knows whether a run is happening, and the only thing allowed to change that.
 *
 * <p>The loop is:
 *
 * <pre>
 *   LOBBY --startRun()--> CREATING_RUN --worlds built--> RUNNING --death--> ENDING_RUN --> LOBBY
 * </pre>
 *
 * <p>Which move is legal is decided by {@link RunRecord}, which is plain data and tested on its
 * own. What the legal moves cost in Minecraft terms — deleting and rebuilding three dimensions,
 * moving players, reviving them — is arranged here. Features do not join in: they listen to {@link
 * RunEvents} and are told.
 *
 * <p>Three rules are worth stating plainly, because they are the ones a naive implementation gets
 * wrong:
 *
 * <ul>
 *   <li><b>Quitting is not dying.</b> A save left in {@link RunPhase#RUNNING} is still in that run
 *       when it is loaded again. Nothing about closing the game ends a run or starts a new one.
 *   <li><b>A death ends a run once.</b> The phase moves before anything else happens, so a second
 *       death in the same tick finds no run to end.
 *   <li><b>A reward is committed once.</b> The run whose reward has been paid is written down by
 *       id, before and after the payout, so a crash in the middle is recoverable in either
 *       direction without paying twice.
 * </ul>
 *
 * <p>The old run's worlds are deleted at the start of the next run rather than the moment the
 * player returns to the lobby. Minecraft has an overworld at all times and a great deal of it,
 * vanilla included, assumes so; deleting the three the instant a run ends would leave the server
 * without one for as long as the player browsed the shop. Deleting them immediately before their
 * replacements are built is the same guarantee — no run ever reuses another's chunks — at a moment
 * when there is no gap for anything to notice.
 */
public final class RunLifecycle {
	private static final RunLifecycle INSTANCE = new RunLifecycle();

	private MinecraftServer server;
	private RunStorage storage;
	private RunRecord record = RunRecord.NEW_SAVE;

	/**
	 * Why this save is not being played, or null when it is.
	 *
	 * <p>Set when the save cannot be played safely: the record exists and cannot be believed, or
	 * the lobby the whole loop is built on is not there. Everything that would move the loop on, or
	 * write over the file, refuses while this is set — the alternative is deciding on the player's
	 * behalf that whatever the save was doing did not happen.
	 *
	 * <p>Reads as a phrase that finishes "this save is stopped: …", because it is shown to whoever
	 * has to fix it as often as it is logged.
	 */
	private String quarantine;

	/**
	 * True when the save was stopped because its record could not be read.
	 *
	 * <p>The two causes are not equally bad. A missing lobby leaves the record intact, so the
	 * lifecycle still knows what the save was doing and a player standing where they are is safe.
	 * An unreadable record means the phase is genuinely unknown — that save might have been
	 * mid-run — and anything done to a player is a guess across the boundary the quarantine exists
	 * to preserve.
	 */
	private boolean recordUnknown;

	private RunLifecycle() {
	}

	public static RunLifecycle get() {
		return INSTANCE;
	}

	/** Called once from the mod initializer. */
	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(INSTANCE::serverStarted);
		ServerLifecycleEvents.SERVER_STOPPED.register(INSTANCE::serverStopped);
		ServerPlayConnectionEvents.JOIN.register(
				(handler, sender, server) -> INSTANCE.playerJoined(handler.player));
		ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) ->
				!(entity instanceof ServerPlayer player) || !INSTANCE.playerDied(player));
	}

	public synchronized RunRecord record() {
		return record;
	}

	public boolean isRunning() {
		return record().isRunning();
	}

	/** Is this one of the three dimensions the current run is played in? */
	public static boolean isRunLevel(ServerLevel level) {
		return RunWorlds.RUN_LEVELS.contains(level.dimension());
	}

	// --- the loop --------------------------------------------------------------------------

	/**
	 * Build a new run and put everybody in it. This is the "start next run" action the lobby needs.
	 *
	 * @param seed the world seed to generate from, or empty for a fresh random one. Tests and the
	 *     dev command name a seed; the game never does.
	 * @throws IllegalStateException if a run is already in progress or being built
	 */
	public synchronized void startRun(OptionalLong seed) {
		if (server == null) {
			throw new IllegalStateException("no server is running");
		}
		refuseIfQuarantined();
		if (!Lobby.exists(server)) {
			// Asked now, not only at server start. This is the last moment before anything is
			// destroyed, and a run with nowhere to come back to must not begin: the players would
			// be evacuated into the very world about to be deleted, and the run that ended would
			// have no lobby to return them to.
			throw new IllegalStateException("there is no " + Lobby.LEVEL.identifier()
					+ " dimension to come back to, so no run may start");
		}

		long chosen = seed.orElseGet(RunWorlds::randomSeed);
		// Written down before a single file is touched, so a crash during world creation is found
		// as CREATING_RUN next time and recovered to the lobby rather than left half playable.
		set(record.beginCreating(chosen, System.currentTimeMillis()));
		HardcoreRoguelite.LOGGER.info("Starting run {} on seed {}", record.runId(), chosen);

		// Nobody may be standing in a world that is about to be deleted. Checked afterwards as well
		// as attempted, because "we asked them to leave" and "they left" are different claims and
		// RunWorlds is entitled to the second one.
		for (ServerPlayer player : players()) {
			if (!Lobby.isLobby(player.level())) {
				Lobby.send(player);
			}
		}
		for (ServerPlayer player : players()) {
			if (isRunLevel(player.level())) {
				abandonCreation(new IllegalStateException(player.getGameProfile().name()
						+ " is still in " + player.level().dimension().identifier()));
				throw new IllegalStateException("run " + record.runId() + " cannot be built while"
						+ " somebody is still in a world it would delete");
			}
		}

		// Everything a run needs before it counts as one happens while the record still says
		// CREATING_RUN. A crash in here, or a run-start listener that fails, therefore leaves a
		// phase that the next start recovers to the lobby — never a RUNNING save that quietly
		// skipped its starter chest, its border or whatever the shop sells next.
		ServerLevel overworld;
		try {
			overworld = RunWorlds.recreate(server, chosen);
			RunEvents.RUN_STARTED.invoker().onRunStarted(server, overworld, record);
		} catch (RuntimeException failed) {
			abandonCreation(failed);
			throw new IllegalStateException(
					"run " + record.runId() + " could not be built: " + failed, failed);
		}

		// Still CREATING_RUN. Putting the players in is part of starting a run, not something that
		// happens to a run already started: if one of them cannot be given what this run owes them,
		// the run has not started.
		//
		// The commit is inside the same block, and that is the point rather than tidiness. By the
		// time it runs, everybody is standing in the run — so a write that failed outside this block
		// would leave the save saying CREATING_RUN with people playing a run it does not admit to.
		// In that state a death is not recognised as this run's and falls through to vanilla's
		// hardcore game over, which is the one outcome the whole loop exists to prevent. Failing
		// here therefore rolls back exactly like a failed entry does.
		try {
			BlockPos spawn = server.getRespawnData().pos();
			for (ServerPlayer player : players()) {
				enterRun(player, overworld, spawn, "Run " + record.runId() + " begins.");
			}
			set(record.created());
		} catch (RuntimeException failed) {
			abandonCreation(failed);
			throw new IllegalStateException(
					"run " + record.runId() + " could not be started: " + failed, failed);
		}
	}

	/**
	 * One player crossing into a run — the only way anybody does.
	 *
	 * <p>Both ways in come through here: the loop that starts a run with people connected, and the
	 * join of somebody who was away when it started. They used to be two copies of the same four
	 * steps, and the last several rounds of review found the same bug in each of them separately.
	 *
	 * <p>The order is the interesting part. The move first, because everything after it is about a
	 * player who is in the run. Then the reset, which takes back whatever the last run gave them.
	 * Only then the upgrades, because the reset clears status effects and would wipe anything
	 * applied before it. Admission last, so a failure anywhere above leaves them un-admitted and
	 * the next join does all of this again rather than assuming it happened.
	 *
	 * <p>Leaving the lobby is a respawn rather than a teleport, so the object that comes back is a
	 * different one and everything after it has to use that. See {@code Lobby.leaveForRun}.
	 *
	 * @return the player as they are now
	 */
	private ServerPlayer enterRun(
			ServerPlayer player, ServerLevel overworld, BlockPos spawn, String message) {
		ServerPlayer inTheRun = Lobby.leaveForRun(player, overworld, spawn);
		resetForNewRun(inTheRun);
		RunEvents.PLAYER_ENTERED_RUN.invoker().onPlayerEnteredRun(server, inTheRun, record);
		RunAdmission.admit(inTheRun, record.runId());
		inTheRun.sendSystemMessage(Component.literal(message));
		return inTheRun;
	}

	/**
	 * Give up on a run that could not be built, and say so.
	 *
	 * <p>The run id is spent either way. That is deliberate: ids are how a reward is recognised
	 * later, and reusing the id of a run that half-existed would make two different runs
	 * indistinguishable in the record.
	 *
	 * <p>The order here is the other half of the transaction the commit belongs to. Getting
	 * everybody out comes first and writing LOBBY comes last, because LOBBY is not a description —
	 * it is a permission. The next start deletes the three run worlds without asking anybody
	 * whether they are standing in one, on the strength of that word. So it may only be written
	 * once evacuation has been <em>proven</em>, not merely attempted, and if it cannot be proven
	 * the save stops where it is instead. A true unfinished phase is recoverable; a false LOBBY is
	 * somebody being deleted along with the world they are in.
	 */
	private void abandonCreation(RuntimeException cause) {
		HardcoreRoguelite.LOGGER.error("Run {} could not be started; back to the lobby",
				record.runId(), cause);

		RuntimeException stillInside = null;
		for (ServerPlayer player : players()) {
			if (Lobby.isLobby(player.level())) {
				continue;
			}
			try {
				Lobby.returnFromRun(player);
			} catch (RuntimeException failed) {
				HardcoreRoguelite.LOGGER.error("{} could not be got out of run {}",
						player.getGameProfile().name(), record.runId(), failed);
				stillInside = failed;
			}
		}
		// Asked afterwards as well as attempted, for the same reason the start does: "we moved them"
		// and "they are out" are different claims, and only the second one may be written down.
		for (ServerPlayer player : players()) {
			if (isRunLevel(player.level())) {
				stillInside = new IllegalStateException(player.getGameProfile().name() + " is still in "
						+ player.level().dimension().identifier());
			}
		}
		if (stillInside != null) {
			stop("run " + record.runId() + " could not be started, and somebody could not be got out"
					+ " of it", stillInside);
			return;
		}

		try {
			set(record.abandoned());
		} catch (RuntimeException alsoFailed) {
			stop("run " + record.runId() + " could not be started, and giving it up could not be"
					+ " written down", alsoFailed);
			return;
		}

		tellEverybody("That run could not be started, so it has not. You are still in the lobby;"
				+ " see the server log.");
	}

	/**
	 * Stop the save, because it is in a state nothing may be built on.
	 *
	 * <p>The record is deliberately left saying whatever it says — {@link RunPhase#CREATING_RUN},
	 * most often — rather than being moved somewhere more comfortable. An unfinished phase that is
	 * true can be recovered by the next server start; a tidy-looking one that is false cannot be
	 * told from a save that is genuinely fine.
	 *
	 * <p>Everything that would move the loop on, or write over the record, refuses from here until
	 * somebody has looked at it and restarted the server. That is a real cost, and it is the
	 * smaller one.
	 */
	private void stop(String why, Throwable cause) {
		this.quarantine = why;
		HardcoreRoguelite.LOGGER.error("This save is stopped: {}. No run will start or end, and"
				+ " nothing will be written over the run record, until somebody has looked at it"
				+ " and restarted the server.", why, cause);
		tellEverybody("This save is stopped: " + why + ". See the server log; nothing has been"
				+ " written over.");
	}

	/**
	 * End the current run, as a death does.
	 *
	 * <p>Separate from {@link #playerDied} so the dev command can end a run without killing anybody
	 * and so recovery can finish a run that a crash interrupted.
	 *
	 * @return true if the run is finished and the save is back in the lobby. False means it is not
	 *     — the reward could not be handed over, or somebody could not be got out of the run — and
	 *     it stays in {@link RunPhase#ENDING_RUN} to be tried again.
	 * @throws IllegalStateException if no run is in progress
	 */
	public synchronized boolean endRun(String reason) {
		refuseIfQuarantined();
		if (record.phase() == RunPhase.ENDING_RUN) {
			// Already over, and stuck: a previous attempt could not commit the reward or could not
			// write the record. Retry the part that did not finish rather than refusing, so a save
			// in this state has a way out that is not "restart the server".
			HardcoreRoguelite.LOGGER.info("Run {} was already ending; trying to finish it again",
					record.runId());
			return finishEnding();
		}

		set(record.beginEnding());
		HardcoreRoguelite.LOGGER.info("Run {} is over: {}", record.runId(), reason);
		return finishEnding();
	}

	/**
	 * Commit the reward and put everybody back in the lobby.
	 *
	 * <p>Also the recovery path for a save that was loaded in {@link RunPhase#ENDING_RUN}, which is
	 * why every step of it asks the record whether it still needs doing.
	 *
	 * @return true if the save reached the lobby. Every early return here is a run left unfinished
	 *     on purpose, and callers have to be able to tell that from success — otherwise the command
	 *     that asked for it reports the opposite of what happened.
	 */
	private synchronized boolean finishEnding() {
		if (!Lobby.exists(server)) {
			HardcoreRoguelite.LOGGER.error("Run {} cannot be finished: there is no lobby to return"
					+ " to. The run stays unfinished and will be tried again.", record.runId());
			return false;
		}
		if (record.rewardOutstanding() && !commitReward()) {
			// Still owed. The run stays in ENDING_RUN, which is the one phase the next server start
			// finishes on its own, and /mhr run end retries it in the meantime.
			return false;
		}

		for (ServerPlayer player : players()) {
			ServerPlayer inTheLobby = isRunLevel(player.level()) ? Lobby.returnFromRun(player) : player;
			revive(inTheLobby);
			inTheLobby.sendSystemMessage(Component.literal(
					"Run " + record.runId() + " is over. You are back in the lobby."));
		}

		// Only now. The record saying the save is between runs has to mean nobody is left standing
		// in a run world — those worlds are deleted at the next start, and a player still in one
		// would be deleted along with it.
		for (ServerPlayer player : players()) {
			if (isRunLevel(player.level())) {
				HardcoreRoguelite.LOGGER.error("Run {} cannot be finished: {} is still in {}."
						+ " The run stays unfinished and will be tried again.", record.runId(),
						player.getGameProfile().name(), player.level().dimension().identifier());
				tellEverybody("This run could not be finished — somebody is still in it."
						+ " It will be tried again.");
				return false;
			}
		}

		set(record.returnedToLobby());
		return true;
	}

	/**
	 * Hand this run's reward over, once.
	 *
	 * <p>"Once" is a promise the lifecycle cannot keep on its own, and pretending otherwise is how
	 * permanent progression gets paid twice. What is guaranteed here is *at least* once: the run is
	 * only written down as rewarded after the listeners have returned, so a process that dies
	 * between the payout and that write comes back with the reward still owed and calls them again.
	 * The other side of that window is a listener that throws — which is not swallowed, because a
	 * payout that failed must be retried rather than recorded as done.
	 *
	 * <p>So a listener that grants permanent progression has to be idempotent for a given run id.
	 * See {@link RunEvents#RUN_ENDED}, which says so where somebody writing one will read it.
	 *
	 * @return true if the run is now recorded as rewarded
	 */
	private boolean commitReward() {
		try {
			RunEvents.RUN_ENDED.invoker().onRunEnded(server, record);
		} catch (RuntimeException failed) {
			HardcoreRoguelite.LOGGER.error("Run {}'s reward could not be committed. The run stays"
					+ " unfinished and will be tried again.", record.runId(), failed);
			tellEverybody("This run's reward could not be handed over yet. Nothing has been lost —"
					+ " it will be tried again.");
			return false;
		}

		try {
			set(record.rewarded());
			return true;
		} catch (RuntimeException failed) {
			// The payout happened and the note saying so did not. The next attempt will call the
			// listeners again, which is exactly why they have to be idempotent by run id.
			HardcoreRoguelite.LOGGER.error("Run {} was rewarded but that could not be written down."
					+ " The reward will be offered again and must be recognised as the same one.",
					record.runId(), failed);
			return false;
		}
	}

	private void tellEverybody(String message) {
		for (ServerPlayer player : players()) {
			player.sendSystemMessage(Component.literal(message));
		}
	}

	// --- hooks -----------------------------------------------------------------------------

	private synchronized void serverStarted(MinecraftServer started) {
		this.server = started;
		this.storage = new RunStorage(started.getWorldPath(LevelResource.ROOT));
		this.quarantine = null;
		this.recordUnknown = false;

		try {
			this.record = storage.load();
		} catch (RunStorage.UnreadableRecord unreadable) {
			// Not a new save. A save whose record cannot be read might have had a run in progress,
			// or a run owing a reward, and starting over would throw either away. Nothing moves and
			// nothing is written until somebody has looked at it.
			this.record = RunRecord.NEW_SAVE;
			this.quarantine = "its run record cannot be read";
			this.recordUnknown = true;
			HardcoreRoguelite.LOGGER.error("This save's run record cannot be read, so the loop is"
					+ " stopped: no run will start or end, and nothing will be written over it."
					+ " Fix or remove {}", storage.file(), unreadable);
			return;
		}

		if (!Lobby.exists(started)) {
			// The same class of fault as an unreadable record, and the same answer. Without the
			// lobby there is nowhere safe to put anybody, and every move this loop makes assumes
			// there is: a run start would evacuate players into the world it is about to delete.
			this.quarantine = "there is no " + Lobby.LEVEL.identifier() + " dimension";
			HardcoreRoguelite.LOGGER.error("This save has no lobby dimension, so the loop is"
					+ " stopped: no run will start or end. Is the mod's data pack loaded?");
			return;
		}

		HardcoreRoguelite.LOGGER.info("Run lifecycle: {}", record.describe());

		// load() has already recovered CREATING_RUN back to the lobby. ENDING_RUN is the one phase
		// that still has work owing: a run that was being finished when the process died.
		if (record.phase() == RunPhase.ENDING_RUN) {
			HardcoreRoguelite.LOGGER.info("Finishing run {}, interrupted last time", record.runId());
			finishEnding();
		} else if (!storage.save(record)) {
			HardcoreRoguelite.LOGGER.error("Could not write {}. The loop will refuse to start or end"
					+ " a run until it can.", storage.file());
		}
	}

	private synchronized void serverStopped(MinecraftServer stopped) {
		this.server = null;
		this.storage = null;
		this.record = RunRecord.NEW_SAVE;
		this.quarantine = null;
		this.recordUnknown = false;
	}

	/**
	 * Where a joining player belongs.
	 *
	 * <p>No run means the lobby, whatever the player's saved position says — a save that has never
	 * been played starts here too. A run in progress means the run, and a player who somehow saved
	 * in the lobby during one is put back into it rather than left stranded.
	 */
	private synchronized void playerJoined(ServerPlayer player) {
		// Next tick, not now. This event fires part-way through a player being put into a level,
		// and moving them to another dimension from inside that leaves the dimension they arrive in
		// holding two of them — after which the server never sends that player any chunks and their
		// client sits on "Loading terrain" for ever.
		server.execute(() -> placeOnJoin(player, 0));
	}

	/** What a brand-new Minecraft player's hunger is, and so what a fresh run's is. */
	private static final int VANILLA_FOOD_LEVEL = 20;
	private static final float VANILLA_SATURATION = 5.0F;

	/** How many ticks to wait for a login to finish before moving the player anyway. */
	private static final int JOIN_SETTLE_TICKS = 100;

	private synchronized void placeOnJoin(ServerPlayer player, int ticksWaited) {
		if (player.isRemoved() || player.hasDisconnected()) {
			return;
		}
		HardcoreRoguelite.LOGGER.info("{} joined in {} while the save is {}",
				player.getGameProfile().name(), player.level().dimension().identifier(),
				record.describe());

		RunArrival arrival = RunArrival.decide(quarantine != null, recordUnknown, record.isRunning(),
				isRunLevel(player.level()), RunAdmission.isAdmittedTo(player, record.runId()));

		// Taking a player to another dimension has to wait for their login to finish. This event
		// fires part-way through one, and a player moved out of the level the rest of the login is
		// about to add them to ends up added twice and tracked properly nowhere: the server sends
		// them no chunks and their client sits on "Loading terrain" until it gives up. On an idle
		// server the login is usually done by the time this runs, which is why the race only shows
		// up when something else has made the tick slow.
		//
		// Only the arrivals that genuinely change dimension wait. Landing in the lobby a player is
		// already standing in, or leaving them where they are, needs no chunks it has not got.
		if (changesDimension(arrival, player)) {
			if (!listed(player) && ticksWaited < JOIN_SETTLE_TICKS) {
				server.execute(() -> placeOnJoin(player, ticksWaited + 1));
				return;
			}
			if (!listed(player)) {
				HardcoreRoguelite.LOGGER.warn("{} never finished joining; placing them anyway",
						player.getGameProfile().name());
			}
		}

		switch (arrival) {
			case REFUSED -> {
				// Turned away rather than relocated. Nobody knows what this save was doing, so
				// anything done to this player is a guess — and letting them in changes their save
				// while turning them away does not. See RunArrival.
				player.connection.disconnect(Component.literal(
						"This save is stopped: " + quarantine + ". Nothing has been overwritten —"
								+ " see the server log, repair or remove the run record, and restart."));
				return;
			}
			case TOLD_AND_LEFT_ALONE -> {
				// The lobby is missing but the record is intact, so the save still knows what it
				// was doing. Standing where they are is safe: nothing can start, so nothing will
				// delete the world they are in.
				player.sendSystemMessage(Component.literal(
						"This save is stopped: " + quarantine + ". See the server log; nothing has"
								+ " been overwritten."));
				return;
			}
			case TO_THE_LOBBY -> {
				if (!Lobby.exists(server)) {
					// The lobby has gone since this server started, so there is nowhere to put
					// them. Leaving them where they are is the only safe answer — no run is in
					// progress, so nothing is about to delete the world they are standing in.
					HardcoreRoguelite.LOGGER.error("{} joined but there is no {} dimension to put"
							+ " them in. Is the mod's data pack still loaded?",
							player.getGameProfile().name(), Lobby.LEVEL.identifier());
					player.sendSystemMessage(Component.literal(
							"There is no lobby on this server, so the loop is stopped. See the"
									+ " server log."));
					return;
				}
				Lobby.send(player);
				player.sendSystemMessage(Component.literal(
						"No run in progress. Start one with /mhr run start."));
				return;
			}
			case LEFT_WHERE_THEY_ARE -> {
				// Genuinely back in the run they were playing. Everything they are carrying is
				// theirs.
				return;
			}
			case INTO_THE_RUN -> {
				// Handled below, because it is the one that has to wait for the login to finish.
			}
		}

		// They have not crossed this run's start boundary. Either they were in the lobby when it
		// started, or — and this is the one a dimension key cannot see — they logged out inside the
		// *previous* run, whose overworld has since been replaced by this one's under the same key.
		// See RunAdmission.
		try {
			enterRun(player, server.overworld(), server.getRespawnData().pos(),
					"Run " + record.runId() + " started while you were away. You have joined it.");
		} catch (RuntimeException failed) {
			// Unlike a run start, this run is already going and shared. One player's entry failing
			// is not a reason to end everybody's run, so it is reported rather than propagated.
			HardcoreRoguelite.LOGGER.error("{} could not be put into run {}",
					player.getGameProfile().name(), record.runId(), failed);
			takeBackOut(player.getUUID());
		}
	}

	/**
	 * Undo a half-finished entry: get this player back out of the run they did not manage to join.
	 *
	 * <p>Leaving them there used to look survivable. It is not. {@code enterRun} can fail after the
	 * move and the reset and before the admission, which leaves somebody standing in the live run
	 * world with none of what the run owes them — playing a run that was never started for them,
	 * and, until admission became the authority on membership, able to end everybody else's by
	 * dying in it.
	 *
	 * <p>Looked up by id rather than taken from the caller's variable, because leaving the lobby is
	 * a respawn: the object the {@code catch} is holding may be the destroyed one, and moving that
	 * moves nobody. If getting them out fails as well, they are disconnected — a player who cannot
	 * be put anywhere safe must not be left in the one place that is unsafe.
	 */
	private void takeBackOut(UUID id) {
		ServerPlayer live = server.getPlayerList().getPlayer(id);
		if (live == null || !isRunLevel(live.level())) {
			return;
		}
		try {
			ServerPlayer inTheLobby = Lobby.returnFromRun(live);
			inTheLobby.sendSystemMessage(Component.literal(
					"Something went wrong joining this run, so you have not joined it. See the"
							+ " server log; rejoining will try again."));
		} catch (RuntimeException alsoFailed) {
			HardcoreRoguelite.LOGGER.error("{} could not be got back out of run {} either;"
					+ " disconnecting them rather than leaving them in it",
					live.getGameProfile().name(), record.runId(), alsoFailed);
			live.connection.disconnect(Component.literal(
					"Something went wrong joining this run and you could not be put back in the"
							+ " lobby. See the server log; rejoining will try again."));
		}
	}

	/**
	 * Will placing this player actually take them to a different dimension?
	 *
	 * <p>The distinction matters only because of the login race above, and it is drawn as narrowly
	 * as the race is. Waiting on an arrival that moves nobody would hold up every ordinary join by
	 * as long as the login takes, which other tests in this repository notice.
	 */
	private static boolean changesDimension(RunArrival arrival, ServerPlayer player) {
		return switch (arrival) {
			// Always: leaving the lobby for a run goes through a respawn, which rebuilds the player.
			case INTO_THE_RUN -> true;
			// Only from somewhere else. Already in the lobby is a move within one dimension.
			case TO_THE_LOBBY -> !Lobby.isLobby(player.level());
			case REFUSED, TOLD_AND_LEFT_ALONE, LEFT_WHERE_THEY_ARE -> false;
		};
	}

	/**
	 * Stop, if this save's record could not be read.
	 *
	 * @throws IllegalStateException always, when the save is quarantined
	 */
	private void refuseIfQuarantined() {
		if (quarantine != null) {
			throw new IllegalStateException("this save is stopped and nothing may start, end or be"
					+ " written over it: " + quarantine);
		}
	}

	/** What the loop is doing, or why it is not. */
	public synchronized String describe() {
		return quarantine == null ? record.describe() : "stopped: " + quarantine;
	}

	/** Is this the very object the server has connected, rather than one that merely matches it? */
	private boolean listed(ServerPlayer player) {
		for (ServerPlayer connected : server.getPlayerList().getPlayers()) {
			if (connected == player) {
				return true;
			}
		}
		return false;
	}

	/**
	 * A player has run out of health.
	 *
	 * <p>Whether a death ends the run is decided by {@link RunAdmission}, not by which dimension
	 * the player is standing in. Those two used to be treated as the same question and they are
	 * not: being in {@code minecraft:overworld} says the run's world is underfoot, while being
	 * admitted says this player crossed this run's start boundary and is actually playing it.
	 * Anyone else in there — an entry that failed part-way, an operator who teleported in — has a
	 * death that is theirs alone, and ending a shared run on it would let somebody who never joined
	 * it finish it for everybody.
	 *
	 * @return true if this mod has taken the death over, meaning vanilla must not carry on with it.
	 */
	private synchronized boolean playerDied(ServerPlayer player) {
		if (record.phase() == RunPhase.ENDING_RUN) {
			// The run this death belongs to is already over and is being wound up. Revive and say
			// nothing: a second death in the same run is not a second run ending.
			revive(player);
			return true;
		}
		if (record.isRunning() && isRunLevel(player.level())
				&& RunAdmission.isAdmittedTo(player, record.runId())) {
			revive(player);
			// The phase moves now, so nothing else can end this run. The rest waits for the next
			// tick: vanilla is in the middle of the damage that would have killed this player, and
			// moving them to another dimension from inside that leaves their client still rendering
			// the world it was told to leave.
			try {
				set(record.beginEnding());
			} catch (RuntimeException failed) {
				// The save cannot be written, so this run has not ended and must not be treated as
				// though it had. The death is still taken over: a disk error is no reason to hand
				// the player a hardcore game-over screen.
				HardcoreRoguelite.LOGGER.error("{} died but the run could not be ended; they are"
						+ " alive where they fell", player.getGameProfile().name(), failed);
				player.sendSystemMessage(Component.literal(
						"That death could not be recorded, so the run has not ended. See the server log."));
				return true;
			}
			HardcoreRoguelite.LOGGER.info("Run {} is over: death of {}",
					record.runId(), player.getGameProfile().name());
			server.execute(this::finishEnding);
			return true;
		}
		if (isRunLevel(player.level())) {
			// In a run world, and this death is not the current run ending. Either no run is being
			// played — a start that could not be finished or rolled back, a save stopped part-way
			// through one — or one is and this player never joined it. Both leave the death with no
			// run to end, and in neither is a hardcore game-over screen the right answer: the first
			// would turn a recoverable fault into a lost world, and the second would hand somebody
			// a game over for a run they are not in.
			HardcoreRoguelite.LOGGER.warn("{} died in {} without being admitted to the run in"
					+ " progress; the save is {}. Reviving them rather than ending anything",
					player.getGameProfile().name(),
					player.level().dimension().identifier(), describe());
			revive(player);
			return true;
		}
		if (Lobby.isLobby(player.level())) {
			// There is nothing in the lobby that should be able to kill anybody, and a hardcore
			// game-over screen between runs would be the loop breaking rather than the game
			// working. Whatever it was, it is not a run ending.
			HardcoreRoguelite.LOGGER.warn("{} died in the lobby; reviving them",
					player.getGameProfile().name());
			revive(player);
			Lobby.send(player);
			return true;
		}
		return false;
	}

	// --- player state ----------------------------------------------------------------------

	/**
	 * Everything a run gives a player, taken back.
	 *
	 * <p>This is the whole of the fresh-run boundary for a player, and the list is the interesting
	 * part. Anything a run can give somebody that would still mean something in the next one
	 * belongs here — the run's dimensions are deleted, but the player is not, so whatever is
	 * written on them is what survives.
	 *
	 * <p>The respawn point is the one that does not look like an item. A bed slept in during run 4
	 * is a position in a world that no longer exists, and because the next run reuses the same
	 * dimension keys it does not read as stale: it points at whatever run 5 generated there. Left
	 * alone it is a free teleport into the new run's terrain on the player's first death.
	 */
	private static void resetForNewRun(ServerPlayer player) {
		revive(player);
		player.getInventory().clearContent();
		player.getEnderChestInventory().clearContent();
		player.setExperienceLevels(0);
		player.setExperiencePoints(0);
		player.setRespawnPosition(null, false);
	}

	/**
	 * Hunger as a brand-new player has it, which is not the same as a full one.
	 *
	 * <p>This used to be {@code eat(20, 1.0F)}, which is the ordinary "I have eaten something"
	 * operation and drives saturation up to the food level — twenty, where a new player starts with
	 * five. High saturation is a real advantage, it is what the design has pencilled in as a
	 * Vanilla+ convenience upgrade, and starting every run with it gives away something nobody has
	 * bought. Exhaustion and the tick timer go back to zero too: a run that ended with the hunger
	 * bar about to drop should not hand the next one that head start.
	 */
	private static void resetHunger(ServerPlayer player) {
		FoodData hunger = player.getFoodData();
		hunger.setFoodLevel(VANILLA_FOOD_LEVEL);
		hunger.setSaturation(VANILLA_SATURATION);
		((FoodDataAccessor) hunger).mhr$setExhaustionLevel(0.0F);
		((FoodDataAccessor) hunger).mhr$setTickTimer(0);
	}

	private static void revive(ServerPlayer player) {
		player.setHealth(player.getMaxHealth());
		player.removeAllEffects();
		resetHunger(player);
		player.clearFire();
		player.resetFallDistance();
		player.setAirSupply(player.getMaxAirSupply());
	}

	// --- plumbing --------------------------------------------------------------------------

	private List<ServerPlayer> players() {
		return List.copyOf(server.getPlayerList().getPlayers());
	}

	/**
	 * Move the record on — on the disk first, and only then in memory.
	 *
	 * <p>That order is the whole point. What this object believes can never be ahead of what the
	 * save says, so a write that fails cannot leave a run that only this process thinks happened.
	 *
	 * @throws IllegalStateException if the record could not be written
	 */
	private void set(RunRecord next) {
		refuseIfQuarantined();
		if (storage != null && !storage.save(next)) {
			throw new IllegalStateException("could not write the run record to " + storage.file());
		}
		this.record = next;
	}
}
