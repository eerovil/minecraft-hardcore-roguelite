package fi.vilpponen.mhr.run;

import fi.vilpponen.mhr.HardcoreRoguelite;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelData;

/**
 * The room the player is in when no run is being played.
 *
 * <p>A dimension of its own, described by a datapack file this mod ships, and therefore built by
 * Minecraft's ordinary level loading like any other dimension. That is what makes it survive run
 * cleanup without any care being taken: the three run dimensions are deleted by file, and this one
 * is not among them. It also means the client already knows about it when it logs in, so arriving
 * here is an ordinary dimension change rather than something that needs a reconnect.
 *
 * <p>Its terrain is nothing at all: an empty biome with no layers, so the generator produces open
 * void and no mobs, no weather worth the name and nothing to mine. What the player stands on is
 * built by {@link LobbyIsland} rather than generated — a small skyblock island with the shop block
 * on it, and a drop off the edge that starts the next run.
 *
 * @see RunLifecycle
 * @see LobbyIsland
 */
public final class Lobby {
	public static final ResourceKey<Level> LEVEL = ResourceKey.create(
			Registries.DIMENSION, Identifier.fromNamespaceAndPath(HardcoreRoguelite.MOD_ID, "lobby"));

	/**
	 * Where the player stands: the middle of the island's top layer, one block above it.
	 *
	 * <p>High up on purpose. The whole dimension below the island is empty, and the drop is what
	 * starts a run, so there has to be room to fall through: void damage only begins 64 blocks
	 * under the dimension's floor, which for the overworld's dimension type is y = -128. Standing
	 * at y = 65 leaves nearly two hundred blocks of it.
	 */
	public static final BlockPos SPAWN = new BlockPos(0, 65, 0);

	private Lobby() {
	}

	/** @return the lobby, or null if the datapack that describes it is not loaded. */
	public static ServerLevel level(MinecraftServer server) {
		return server.getLevel(LEVEL);
	}

	public static boolean isLobby(Level level) {
		return level.dimension().equals(LEVEL);
	}

	/** Is the dimension this whole loop is built on actually here? */
	public static boolean exists(MinecraftServer server) {
		return level(server) != null;
	}

	/**
	 * Put a player in the lobby.
	 *
	 * <p>There is no fallback, deliberately. This used to drop the player into the overworld when
	 * the lobby was missing, which is the worst place it could have chosen: the overworld is the
	 * world a run start is about to delete, and the reason the players are being moved at all is to
	 * get them out of it. On the way back it was worse still — the record would go on to say the
	 * save was safely between runs while the player stood in a disposable dimension.
	 *
	 * <p>A missing lobby is not a situation to improvise around. It is caught at server start and
	 * stops the loop; reaching here without one means something removed it since.
	 *
	 * @throws IllegalStateException if the lobby dimension is not there
	 */
	public static void send(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		ServerLevel lobby = require(server);

		if (player.level().dimension().equals(LEVEL)) {
			// Already here. Moving them within the dimension needs no dimension change, and asking
			// for one would send the client off to load a world it is already in.
			player.teleportTo(SPAWN.getX() + 0.5, SPAWN.getY(), SPAWN.getZ() + 0.5);
			return;
		}

		player.teleportTo(lobby, SPAWN.getX() + 0.5, SPAWN.getY(), SPAWN.getZ() + 0.5,
				Set.of(), 0.0F, 0.0F, true);
	}

	/**
	 * The lobby, with something in it to stand on.
	 *
	 * <p>Every way into the lobby goes through here, which is the point: the dimension generates as
	 * open void, so a player who arrived before the island was built would fall out of it and start
	 * a run they never asked for. Building is a no-op once the island is there.
	 */
	private static ServerLevel require(MinecraftServer server) {
		ServerLevel lobby = level(server);
		if (lobby == null) {
			throw new IllegalStateException("there is no " + LEVEL.identifier()
					+ " dimension — is the mod's data pack loaded?");
		}
		LobbyIsland.ensure(lobby);
		return lobby;
	}

	/**
	 * Bring a player out of a finished run and into the lobby.
	 *
	 * @return the player as they are now — a different object from the one passed in.
	 * @throws IllegalStateException if the lobby dimension is not there, rather than leaving them
	 *     in a run world the record is about to describe as finished
	 */
	public static ServerPlayer returnFromRun(ServerPlayer player) {
		require(player.level().getServer());
		return moveThroughRespawn(player, LEVEL, SPAWN);
	}

	/**
	 * Take a player out of the lobby and into the run that is about to start.
	 *
	 * @return the player as they are now — a different object from the one passed in.
	 */
	public static ServerPlayer leaveForRun(ServerPlayer player, ServerLevel overworld, BlockPos spawn) {
		if (!isLobby(player.level())) {
			player.teleportTo(overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5,
					Set.of(), 0.0F, 0.0F, true);
			return player;
		}
		return moveThroughRespawn(player, overworld.dimension(), spawn);
	}

	/**
	 * Move a player across a dimension the way Minecraft's own end-credits return does.
	 *
	 * <p>Both of the lobby's doorways go through here, and it is the whole of why they work.
	 *
	 * <p>An ordinary cross-dimension teleport hands the *same* player entity to the destination.
	 * That is fine for a dimension the entity has never been in — every run's overworld is a brand
	 * new level object, so arriving in a run always works however it is done. The lobby is the same
	 * level object for the entire session, and a player who leaves it by teleport stays registered
	 * there; the next arrival collides with that stale registration, is only half added, and is
	 * never sent a single chunk. The player then stands in a lobby their client draws as empty
	 * void. Leaving through a respawn is what stops that from ever being written down: the entity
	 * that was in the lobby is destroyed rather than moved, and a fresh one is built for the
	 * destination.
	 *
	 * <p>The three lines after {@code respawn} are not garnish. Vanilla's own caller — the
	 * end-credits branch of {@code ServerGamePacketListenerImpl.handleClientCommand} — does exactly
	 * this: a respawn creates a new {@link ServerPlayer} and moves the network connection onto it,
	 * but the connection's own idea of which player it belongs to is a separate field, and a
	 * connection still pointing at the destroyed player is a player the server no longer sends
	 * anything to. Taking the returned object and stopping there is half a respawn.
	 *
	 * <p>The player's bed is forgotten first, because respawning is how they travel and a bed in
	 * the run they are leaving would otherwise decide where they land.
	 *
	 * @return the player as they are now — a different object from the one passed in.
	 */
	private static ServerPlayer moveThroughRespawn(
			ServerPlayer player, ResourceKey<Level> destination, BlockPos pos) {
		MinecraftServer server = player.level().getServer();
		player.setRespawnPosition(null, false);
		server.setRespawnData(LevelData.RespawnData.of(destination, pos, 0.0F, 0.0F));

		ServerPlayer arrived =
				server.getPlayerList().respawn(player, true, Entity.RemovalReason.CHANGED_DIMENSION);
		RunAdmission.carryOver(player, arrived);
		arrived.connection.player = arrived;
		arrived.connection.resetPosition();
		arrived.setHealth(arrived.getMaxHealth());
		return arrived;
	}

}
