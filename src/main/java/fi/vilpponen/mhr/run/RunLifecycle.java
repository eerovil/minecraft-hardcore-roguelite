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

		long chosen = seed.orElseGet(RunWorlds::randomSeed);
		// Written down before a single file is touched, so a crash during world creation is found
		// as CREATING_RUN next time and recovered to the lobby rather than left half playable.
		set(record.beginCreating(chosen, System.currentTimeMillis()));
		HardcoreRoguelite.LOGGER.info("Starting run {} on seed {}", record.runId(), chosen);

		// Nobody may be standing in a world that is about to be deleted.
		for (ServerPlayer player : players()) {
			if (!Lobby.isLobby(player.level())) {
				Lobby.send(player);
			}
		}

		ServerLevel overworld = RunWorlds.recreate(server, chosen);
		set(record.created());

		// Unlocks are applied to the finished worlds before anybody arrives in them.
		fire(() -> RunEvents.RUN_STARTED.invoker().onRunStarted(server, overworld, record),
				"a run-start listener");

		BlockPos spawn = server.getRespawnData().pos();
		for (ServerPlayer player : players()) {
			// Leaving the lobby is a respawn, not a teleport, and the object that comes back is a
			// different one — everything after this has to use it. See Lobby.leaveForRun.
			ServerPlayer inTheRun = Lobby.leaveForRun(player, overworld, spawn);
			resetForNewRun(inTheRun);
			inTheRun.sendSystemMessage(Component.literal("Run " + record.runId() + " begins."));
		}
	}

	/**
	 * End the current run, as a death does.
	 *
	 * <p>Separate from {@link #playerDied} so the dev command can end a run without killing anybody
	 * and so recovery can finish a run that a crash interrupted.
	 *
	 * @throws IllegalStateException if no run is in progress
	 */
	public synchronized void endRun(String reason) {
		set(record.beginEnding());
		HardcoreRoguelite.LOGGER.info("Run {} is over: {}", record.runId(), reason);
		finishEnding();
	}

	/**
	 * Commit the reward and put everybody back in the lobby.
	 *
	 * <p>Also the recovery path for a save that was loaded in {@link RunPhase#ENDING_RUN}, which is
	 * why every step of it asks the record whether it still needs doing.
	 */
	private synchronized void finishEnding() {
		if (record.rewardOutstanding()) {
			fire(() -> RunEvents.RUN_ENDED.invoker().onRunEnded(server, record), "a run-end listener");
			set(record.rewarded());
		}

		for (ServerPlayer player : players()) {
			ServerPlayer inTheLobby = isRunLevel(player.level()) ? Lobby.returnFromRun(player) : player;
			revive(inTheLobby);
			inTheLobby.sendSystemMessage(Component.literal(
					"Run " + record.runId() + " is over. You are back in the lobby."));
		}

		set(record.returnedToLobby());
	}

	// --- hooks -----------------------------------------------------------------------------

	private synchronized void serverStarted(MinecraftServer started) {
		this.server = started;
		this.storage = new RunStorage(started.getWorldPath(LevelResource.ROOT));
		this.record = storage.load();
		HardcoreRoguelite.LOGGER.info("Run lifecycle: {}", record.describe());

		// load() has already recovered CREATING_RUN back to the lobby. ENDING_RUN is the one phase
		// that still has work owing: a run that was being finished when the process died.
		if (record.phase() == RunPhase.ENDING_RUN) {
			HardcoreRoguelite.LOGGER.info("Finishing run {}, interrupted last time", record.runId());
			finishEnding();
		} else {
			storage.save(record);
		}
	}

	private synchronized void serverStopped(MinecraftServer stopped) {
		this.server = null;
		this.storage = null;
		this.record = RunRecord.NEW_SAVE;
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
		server.execute(() -> placeOnJoin(player));
	}

	private synchronized void placeOnJoin(ServerPlayer player) {
		if (player.isRemoved()) {
			return;
		}
		if (!record.isRunning()) {
			Lobby.send(player);
			player.sendSystemMessage(Component.literal(
					"No run in progress. Start one with /mhr run start."));
			return;
		}
		if (!isRunLevel(player.level())) {
			ServerLevel overworld = server.overworld();
			BlockPos spawn = server.getRespawnData().pos();
			player.teleportTo(overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5,
					Set.of(), 0.0F, 0.0F, true);
		}
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
			set(record.beginEnding());
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

	private void set(RunRecord next) {
		this.record = next;
		if (storage != null) {
			storage.save(next);
		}
	}

	/**
	 * Run a listener list without letting it take the loop down with it.
	 *
	 * <p>A feature that throws while reacting to a run boundary is a bug in that feature. Letting
	 * it propagate would leave the record saying one thing and the worlds another, which is the one
	 * state this design exists to avoid.
	 */
	private static void fire(Runnable listeners, String what) {
		try {
			listeners.run();
		} catch (RuntimeException e) {
			HardcoreRoguelite.LOGGER.error("{} failed; the run continues without it", what, e);
		}
	}
}
