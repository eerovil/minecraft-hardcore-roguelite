package fi.vilpponen.mhr.equipment.client;

import fi.vilpponen.mhr.equipment.EquipmentUnlockPayload;
import fi.vilpponen.mhr.equipment.SyncedSlotUnlocks;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * Keeps the client's copy of the unlocked-slot set up to date, so the inventory screen knows what
 * to mark as locked. Nothing here decides anything — the server does.
 */
public final class EquipmentLockClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(EquipmentUnlockPayload.TYPE,
				(payload, context) -> SyncedSlotUnlocks.accept(payload.unlockedBits()));

		ClientPlayConnectionEvents.DISCONNECT.register(
				(handler, client) -> SyncedSlotUnlocks.forget());
	}
}
