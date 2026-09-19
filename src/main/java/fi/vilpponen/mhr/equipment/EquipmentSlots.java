package fi.vilpponen.mhr.equipment;

import net.minecraft.server.MinecraftServer;

/**
 * Where the equipment-slot feature is wired up, and the only part of it the rest of the mod calls.
 *
 * <p>Two jobs, and it is worth being clear about which is which. {@link EquipmentSlotRule} is the
 * rule — it decides what may sit in a slot, and it is enforced in one place, the write barrier on
 * {@code PlayerEquipment.set}. {@link EquipmentUnlockSync} is only telling clients what the server
 * decided, so they can draw the padlocks.
 */
public final class EquipmentSlots {
	private EquipmentSlots() {
	}

	public static void register() {
		EquipmentUnlockSync.register();
		EquipmentSlotRule.register();
	}

	/** Call after the owned unlocks change: clients are told, and occupied slots are re-checked. */
	public static void onUnlocksChanged(MinecraftServer server) {
		EquipmentUnlockSync.sendToAll(server);
		EquipmentSlotRule.normalizeAll(server);
	}
}
