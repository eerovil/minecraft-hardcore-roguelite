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
 * <p>Its terrain is one layer of bedrock over an empty biome, so there is a floor and nothing else
 * — no mobs, no weather worth the name, nothing to mine. The shop will furnish it later; this
 * issue only owes the lifecycle a safe place to stand.
 *
 * @see RunLifecycle
 */
public final class Lobby {
	public static final ResourceKey<Level> LEVEL = ResourceKey.create(
			Registries.DIMENSION, Identifier.fromNamespaceAndPath(HardcoreRoguelite.MOD_ID, "lobby"));

	/**
	 * Where the player stands.
	 *
	 * <p>One block above the floor. The lobby uses the overworld's dimension type, so its floor is
	 * the layer the flat generator puts at the bottom of that range, at y = -64.
	 */
	public static final BlockPos SPAWN = new BlockPos(0, -63, 0);

	private Lobby() {
	}

	/** @return the lobby, or null if the datapack that describes it is not loaded. */
	public static ServerLevel level(MinecraftServer server) {
		return server.getLevel(LEVEL);
	}

	public static boolean isLobby(Level level) {
		return level.dimension().equals(LEVEL);
	}

	/**
	 * Put a player in the lobby.
	 *
	 * <p>Falls back to the overworld if the lobby dimension is missing, which can only happen if
	 * the mod's own data has been stripped out. Leaving the player inside a run world that is about
	 * to be deleted would be worse than landing somewhere unintended.
	 *
	 * @return true if the player really is in the lobby now.
	 */
	public static boolean send(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		ServerLevel lobby = level(server);
		if (lobby == null) {
			HardcoreRoguelite.LOGGER.error(
					"No {} dimension — is the mod's data pack loaded? Sending {} to the overworld instead.",
					LEVEL.identifier(), player.getGameProfile().name());
			ServerLevel overworld = server.overworld();
			player.teleportTo(overworld, 0.5, overworld.getSeaLevel() + 1, 0.5, Set.of(), 0.0F, 0.0F, true);
			return false;
		}

		if (player.level().dimension().equals(LEVEL)) {
			// Already here. Moving them within the dimension needs no dimension change, and asking
			// for one would send the client off to load a world it is already in.
			player.teleportTo(SPAWN.getX() + 0.5, SPAWN.getY(), SPAWN.getZ() + 0.5);
			return true;
		}

		player.teleportTo(lobby, SPAWN.getX() + 0.5, SPAWN.getY(), SPAWN.getZ() + 0.5,
				Set.of(), 0.0F, 0.0F, true);
		return true;
	}

	/**
	 * Bring a player out of a finished run and into the lobby.
	 *
	 * <p>Respawned rather than teleported, and that is not a detail. An ordinary cross-dimension
	 * teleport hands the same player entity to the destination, which works for a dimension they
	 * have never been in — every run's overworld is a brand-new level object, so arriving there is
	 * always fine. The lobby is the same level object for the whole session and still knows this
	 * player's id from their last visit; handing it the same entity again leaves them without chunk
	 * tracking, so the server sends no terrain and the client sits on "Loading terrain" until it
	 * gives up. Respawning builds a fresh player entity and goes in through
	 * {@code addRespawnedPlayer}, which is the path vanilla itself uses to put a player into a world
	 * they have been in before.
	 *
	 * <p>Their bed is forgotten first: it was in a world that is about to stop existing.
	 *
	 * @return the player as they are now — a different object from the one passed in.
	 */
	public static ServerPlayer returnFromRun(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		if (level(server) == null) {
			send(player);
			return player;
		}

		player.setRespawnPosition(null, false);
		server.setRespawnData(LevelData.RespawnData.of(LEVEL, SPAWN, 0.0F, 0.0F));
		ServerPlayer arrived = server.getPlayerList().respawn(player, true, Entity.RemovalReason.CHANGED_DIMENSION);
		arrived.setHealth(arrived.getMaxHealth());
		return arrived;
	}

}
