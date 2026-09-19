package fi.vilpponen.mhr.equipment;

import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * The one rule: nothing stays in a locked equipment slot.
 *
 * <p>Every write into a player's equipment in 26.3 goes through
 * {@code PlayerEquipment.set(EquipmentSlot, ItemStack)} — the inventory screen writes there through
 * {@code Inventory.setItem}, {@code LivingEntity.setItemSlot} writes there directly, and that in
 * turn is where the swap-hands packet, right-click-to-equip, a dispenser and {@code /item replace}
 * all end up. So {@link #admit} is asked once, at that one place, and an entry path nobody has
 * thought of yet is covered without its own guard.
 *
 * <p>Two things do not pass through {@code set}: reading a player from disk and the respawn copy
 * both replace the backing map wholesale ({@code EntityEquipment.setAll}). Those, and a slot going
 * from unlocked to locked, are handled by {@link #normalize} — which does not reimplement anything,
 * it simply offers what is already in the slot back to the same rule.
 *
 * <p>Nothing is destroyed. A refused stack goes into a free inventory slot, or falls at the
 * player's feet when there is no free slot.
 */
public final class EquipmentSlotRule {
	private EquipmentSlotRule() {
	}

	public static void register() {
		ServerPlayerEvents.JOIN.register(EquipmentSlotRule::normalize);
		ServerPlayerEvents.AFTER_RESPAWN.register(
				(oldPlayer, newPlayer, alive) -> normalize(newPlayer));
	}

	/**
	 * What may actually be stored in this slot.
	 *
	 * @return {@code incoming} when the slot is open, or an empty stack when it is locked — in
	 *         which case {@code incoming} has already been handed back to the player.
	 */
	public static ItemStack admit(Player player, EquipmentSlot slot, ItemStack incoming) {
		if (incoming.isEmpty() || EquipmentLocks.isUnlocked(slot)) {
			return incoming;
		}
		// The client's copy of a player follows the server; it does not get a vote.
		if (!(player instanceof ServerPlayer serverPlayer)) {
			return incoming;
		}
		giveBack(serverPlayer, incoming.copy());
		return ItemStack.EMPTY;
	}

	/** Re-applies the rule to slots that were filled before they were locked. */
	public static void normalizeAll(MinecraftServer server) {
		if (server == null) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			normalize(player);
		}
	}

	private static void normalize(ServerPlayer player) {
		for (EquipmentSlot slot : EquipmentLocks.lockableSlots()) {
			if (EquipmentLocks.isUnlocked(slot)) {
				continue;
			}
			ItemStack held = player.getItemBySlot(slot);
			if (!held.isEmpty()) {
				// Offer the contents back to the rule. It refuses them, hands them to the player
				// and leaves the slot empty — the same code path as any other rejected write.
				player.setItemSlot(slot, held);
			}
		}
	}

	/**
	 * Into a free slot, or onto the ground. Deliberately never merged into a part-full stack.
	 *
	 * <p>Some callers write the equipment slot before clearing wherever the item came from — the
	 * swap-hands key reads the main hand, writes the offhand, and only then empties the main hand.
	 * Merging at that moment would find the item still sitting in its old slot and stack it onto
	 * itself, and the caller's own clear would then wipe both halves. A free slot cannot collide
	 * with a stack that is about to be cleared.
	 */
	private static void giveBack(ServerPlayer player, ItemStack stack) {
		Inventory inventory = player.getInventory();
		if (inventory != null) {
			int free = inventory.getFreeSlot();
			if (free != -1 && inventory.add(free, stack)) {
				return;
			}
		}
		player.drop(stack, false, Prediction.SERVER_ONLY);
	}
}
