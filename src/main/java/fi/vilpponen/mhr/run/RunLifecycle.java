package fi.vilpponen.mhr.run;

import fi.vilpponen.mhr.HardcoreRoguelite;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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

		set(record.created());

		BlockPos spawn = server.getRespawnData().pos();
		for (ServerPlayer player : players()) {
			// Leaving the lobby is a respawn, not a teleport, and the object that comes back is a
			// different one — everything after this has to use it. See Lobby.leaveForRun.
			ServerPlayer inTheRun = Lobby.leaveForRun(player, overworld, spawn);
			resetForNewRun(inTheRun);
			RunAdmission.admit(inTheRun, record.runId());
			inTheRun.sendSystemMessage(Component.literal("Run " + record.runId() + " begins."));
		}
	}

	/**
	 * Give up on a run that could not be built, and say so.
	 *
	 * <p>The run id is spent either way. That is deliberate: ids are how a reward is recognised
	 * later, and reusing the id of a run that half-existed would make two different runs
	 * indistinguishable in the record.
	 */
	private void abandonCreation(RuntimeException cause) {
		HardcoreRoguelite.LOGGER.error("Run {} could not be started; back to the lobby",
				record.runId(), cause);
		try {
			set(record.abandoned());
		} catch (RuntimeException alsoFailed) {
			HardcoreRoguelite.LOGGER.error("The abandoned run could not be written down either."
					+ " The next start will recover it.", alsoFailed);
		}

		for (ServerPlayer player : players()) {
			if (!Lobby.isLobby(player.level())) {
				Lobby.returnFromRun(player);
			}
			player.sendSystemMessage(Component.literal(
					"That run could not be started, so it has not. You are still in the lobby;"
							+ " see the server log."));
		}
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

		try {
			this.record = storage.load();
		} catch (RunStorage.UnreadableRecord unreadable) {
			// Not a new save. A save whose record cannot be read might have had a run in progress,
			// or a run owing a reward, and starting over would throw either away. Nothing moves and
			// nothing is written until somebody has looked at it.
			this.record = RunRecord.NEW_SAVE;
			this.quarantine = "its run record cannot be read";
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

	/** How many ticks to wait for a login to finish before moving the player anyway. */
	private static final int JOIN_SETTLE_TICKS = 100;

	private synchronized void placeOnJoin(ServerPlayer player, int ticksWaited) {
		if (player.isRemoved() || player.hasDisconnected()) {
			return;
		}
		HardcoreRoguelite.LOGGER.info("{} joined in {} while the save is {}",
				player.getGameProfile().name(), player.level().dimension().identifier(),
				record.describe());

		if (quarantine != null) {
			// Two different faults end up here and they cannot be handled the same way. When the
			// record is unreadable the lobby is fine and is where the player belongs; when the
			// lobby itself is what is missing, asking to go there throws for exactly the reason the
			// save was stopped, and the player would never hear why.
			if (Lobby.exists(server)) {
				Lobby.send(player);
			}
			player.sendSystemMessage(Component.literal(
					"This save is stopped: " + quarantine + ". See the server log; nothing has been"
							+ " overwritten."));
			return;
		}
		if (!record.isRunning()) {
			if (!Lobby.exists(server)) {
				// The lobby has gone since this server started, so there is nowhere to put them.
				// Leaving them where they are is the only safe answer — no run is in progress, so
				// nothing is about to delete the world they are standing in.
				HardcoreRoguelite.LOGGER.error("{} joined but there is no {} dimension to put them"
						+ " in. Is the mod's data pack still loaded?",
						player.getGameProfile().name(), Lobby.LEVEL.identifier());
				player.sendSystemMessage(Component.literal(
						"There is no lobby on this server, so the loop is stopped. See the server log."));
				return;
			}
			Lobby.send(player);
			player.sendSystemMessage(Component.literal(
					"No run in progress. Start one with /mhr run start."));
			return;
		}
		if (isRunLevel(player.level()) && RunAdmission.isAdmittedTo(player, record.runId())) {
			// Genuinely back in the run they were playing. Everything they are carrying is theirs.
			return;
		}

		// What is left is somebody who has not crossed this run's start boundary. Either they were
		// in the lobby when it started, or — and this is the one a dimension key cannot see — they
		// logged out inside the *previous* run, whose overworld has since been replaced by this
		// one's under the same key. See RunAdmission.
		//
		// Leaving the lobby is a respawn — see Lobby.leaveForRun — and a respawn has to wait for
		// the login to finish. Moving a half-placed player takes them out of the server's list and
		// the rest of the login then files the original, which nothing ever removes; the slot leaks
		// for the session.
		if (!listed(player) && ticksWaited < JOIN_SETTLE_TICKS) {
			server.execute(() -> placeOnJoin(player, ticksWaited + 1));
			return;
		}
		if (!listed(player)) {
			HardcoreRoguelite.LOGGER.warn("{} never finished joining; moving them into the run anyway",
					player.getGameProfile().name());
		}

		// Reset, and only here. This player was away when the run started, so they have never
		// crossed its start boundary and everything they are carrying belongs to the run before —
		// which is a roguelite handing a fresh run last run's diamonds. Somebody reconnecting into
		// a run they were already playing returns above, keeps their things, and must.
		ServerPlayer inTheRun =
				Lobby.leaveForRun(player, server.overworld(), server.getRespawnData().pos());
		resetForNewRun(inTheRun);
		RunAdmission.admit(inTheRun, record.runId());
		inTheRun.sendSystemMessage(Component.literal(
				"Run " + record.runId() + " started while you were away. You have joined it."));
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
	 * @return true if this mod has taken the death over, meaning vanilla must not carry on with it.
	 */
	private synchronized boolean playerDied(ServerPlayer player) {
		if (record.phase() == RunPhase.ENDING_RUN) {
			// The run this death belongs to is already over and is being wound up. Revive and say
			// nothing: a second death in the same run is not a second run ending.
			revive(player);
			return true;
		}
		if (record.isRunning() && isRunLevel(player.level())) {
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

	/** Everything a run gives a player, taken back. */
	private static void resetForNewRun(ServerPlayer player) {
		revive(player);
		player.getInventory().clearContent();
		player.getEnderChestInventory().clearContent();
		player.setExperienceLevels(0);
		player.setExperiencePoints(0);
	}

	private static void revive(ServerPlayer player) {
		player.setHealth(player.getMaxHealth());
		player.removeAllEffects();
		player.getFoodData().eat(20, 1.0F);
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
