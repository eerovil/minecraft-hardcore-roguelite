package fi.vilpponen.mhr.equipment;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * Empties any locked equipment slot that somehow has something in it.
 *
 * <p>The other hooks all stop an item from <em>entering</em> a locked slot, which is not the same
 * as the slot being empty. A slot can be unlocked, filled, and locked again — {@code /mhr lock} is
 * a real thing the dev command can do, and the shop will one day have its own reasons. It can also
 * be filled by an operator with {@code /item replace}. In all of those the player would be left
 * wearing armor, or holding a shield, through a slot that is supposed to be shut.
 *
 * <p>So rather than guard every way an offhand item can be used, the slot is emptied. Checking it
 * once a tick catches every route in one place, including ones that do not exist yet, and costs
 * five stack reads per player.
 *
 * <p>Nothing is lost: the stack goes back into the player's own inventory, and only falls at their
 * feet if there is no room for it.
 */
public final class LockedSlotEvacuation {
	private LockedSlotEvacuation() {
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(LockedSlotEvacuation::sweep);
	}

	private static void sweep(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			// A dead player is in the middle of dropping everything anyway; leave that alone.
			if (player.isAlive()) {
				evacuate(player);
			}
		}
	}

	private static void evacuate(ServerPlayer player) {
		for (EquipmentSlot slot : EquipmentLocks.lockableSlots()) {
			if (EquipmentLocks.isUnlocked(slot)) {
				continue;
			}
			ItemStack stack = player.getItemBySlot(slot);
			if (stack.isEmpty()) {
				continue;
			}
			ItemStack removed = stack.copy();
			player.setItemSlot(slot, ItemStack.EMPTY);
			// Fills matching stacks, then a free slot, then drops at the player's feet.
			player.getInventory().placeItemBackInInventory(removed, Prediction.SERVER_ONLY);
		}
	}
}
