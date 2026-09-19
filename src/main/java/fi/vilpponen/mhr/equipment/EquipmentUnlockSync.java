package fi.vilpponen.mhr.equipment;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Sends the unlocked-slot set to clients so the inventory screen can mark the locked ones.
 *
 * <p>Sent once when a player joins, and again whenever the dev command changes something. A client
 * that never hears from us — a vanilla server, or one without the mod — simply keeps whatever its
 * own config says, which is the same thing that happens in single player.
 */
public final class EquipmentUnlockSync {
	private EquipmentUnlockSync() {
	}

	/** Called from the mod initializer, on both sides: the payload type has to be known to both. */
	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(
				EquipmentUnlockPayload.TYPE, EquipmentUnlockPayload.STREAM_CODEC);

		ServerPlayConnectionEvents.JOIN.register(
				(handler, sender, server) -> sendTo(handler.player));
	}

	public static void sendTo(ServerPlayer player) {
		if (ServerPlayNetworking.canSend(player, EquipmentUnlockPayload.TYPE)) {
			ServerPlayNetworking.send(player, new EquipmentUnlockPayload(EquipmentLocks.packUnlockedBits()));
		}
	}

	/** After an unlock changes, so open inventory screens stop showing a stale lock. */
	public static void sendToAll(MinecraftServer server) {
		if (server == null) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			sendTo(player);
		}
	}
}
