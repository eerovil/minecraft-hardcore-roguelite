package fi.vilpponen.mhr.gametest.client;

import fi.vilpponen.mhr.gametest.mixin.ContainerScreenAccessor;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * One connected test player, and the few things the equipment tests do to it.
 *
 * <p>Everything here is either a real client input — a key the player would press, a square the
 * mouse would land on — or a question asked of the server. Nothing writes an equipment slot
 * directly; that would test the test rather than the mod.
 */
final class TestPlayer {
	/** The head armor square. InventoryMenu adds the four armor slots head-first. */
	static final int HEAD_SLOT = InventoryMenu.ARMOR_SLOT_START;

	private final ClientGameTestContext context;
	private final TestServerContext server;
	private final TestServerConnection connection;

	TestPlayer(ClientGameTestContext context, TestServerContext server, TestServerConnection connection) {
		this.context = context;
		this.server = server;
		this.connection = connection;
	}

	// --- talking to the server -------------------------------------------------------------

	/** Runs a server command and waits until both sides have seen what it did. */
	void command(String command) {
		server.runCommand(command);
		settle();
	}

	/** Lets every packet in flight arrive, both ways. */
	void settle() {
		connection.waitForServerboundPackets();
		connection.waitForClientboundPackets();
		context.waitTicks(2);
		connection.waitForClientboundPackets();
	}

	/** What the server has in an equipment slot. The only authority on whether a slot is in use. */
	ItemStack equipped(EquipmentSlot slot) {
		return server.computeOnServer(unused -> connection.getServerPlayer().getItemBySlot(slot).copy());
	}

	/**
	 * Every copy of an item the player could still reach: inventory, equipment, the stack on the
	 * cursor and anything lying on the ground nearby.
	 *
	 * <p>This is the item-conservation check. A refused helmet has to be somewhere; the point of
	 * counting all four places at once is that "not equipped" is never allowed to mean "destroyed".
	 */
	int reachableCount(Item item) {
		return server.computeOnServer(unused -> {
			ServerPlayer player = connection.getServerPlayer();
			int count = 0;

			NonNullList<ItemStack> items = player.getInventory().getNonEquipmentItems();
			for (ItemStack stack : items) {
				count += stack.is(item) ? stack.getCount() : 0;
			}
			for (EquipmentSlot slot : EquipmentSlot.values()) {
				ItemStack stack = player.getItemBySlot(slot);
				count += stack.is(item) ? stack.getCount() : 0;
			}
			ItemStack carried = player.containerMenu.getCarried();
			count += carried.is(item) ? carried.getCount() : 0;

			List<ItemEntity> dropped = player.level().getEntitiesOfClass(
					ItemEntity.class, player.getBoundingBox().inflate(16.0));
			for (ItemEntity entity : dropped) {
				ItemStack stack = entity.getItem();
				count += stack.is(item) ? stack.getCount() : 0;
			}
			return count;
		});
	}

	/** Puts the player back to nothing owned, nothing worn, nothing carried, empty inventory. */
	void reset(List<String> unlockIds) {
		for (String id : unlockIds) {
			server.runCommand("mhr lock " + id);
		}
		server.runOnServer(unused -> {
			ServerPlayer player = connection.getServerPlayer();
			for (EquipmentSlot slot : EquipmentSlot.values()) {
				player.setItemSlot(slot, ItemStack.EMPTY);
			}
			player.getInventory().clearContent();
			player.containerMenu.setCarried(ItemStack.EMPTY);
			player.inventoryMenu.broadcastChanges();
		});
		server.runOnServer(unused -> {
			ServerPlayer player = connection.getServerPlayer();
			player.level().getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(16.0))
					.forEach(ItemEntity::discard);
		});
		settle();
	}

	/** Gives the player one item, in the selected hotbar square, the way a starting kit would. */
	void giveInHand(Item item) {
		server.runOnServer(unused -> {
			ServerPlayer player = connection.getServerPlayer();
			player.getInventory().setSelectedSlot(0);
			player.getInventory().setItem(0, new ItemStack(item));
			player.inventoryMenu.broadcastChanges();
		});
		settle();
	}

	/**
	 * Selects the hotbar square holding this item by pressing its number key.
	 *
	 * <p>Used after a refused swap-hands, where the rule has handed the item back to whichever
	 * free square it found. Picking it up again is a number key, same as a player would.
	 */
	void putInHand(Item item) {
		int index = server.computeOnServer(unused -> {
			Inventory inventory = connection.getServerPlayer().getInventory();
			for (int i = 0; i < Inventory.getSelectionSize(); i++) {
				if (inventory.getItem(i).is(item)) {
					return i;
				}
			}
			return -1;
		});
		if (index < 0) {
			throw new AssertionError("Expected " + item + " somewhere in the hotbar, and it is not");
		}
		context.getInput().pressKey(options -> options.keyHotbarSlots[index]);
		settle();
	}

	// --- real client input -----------------------------------------------------------------

	TestInput input() {
		return context.getInput();
	}

	/** Presses the inventory key and waits for the screen, exactly as a player would open it. */
	void openInventory() {
		context.getInput().pressKey(options -> options.keyInventory);
		context.waitForScreen(InventoryScreen.class);
		context.waitTicks(3);
	}

	/** Presses the inventory key again, which is how vanilla closes it. */
	void closeInventory() {
		context.getInput().pressKey(options -> options.keyInventory);
		context.waitFor(client -> client.screen == null);
		settle();
	}

	/** Presses the swap-hands key. Only does anything when no screen is open. */
	void pressSwapHands() {
		context.getInput().pressKey(options -> options.keySwapOffhand);
		settle();
	}

	/**
	 * Left-clicks a slot of the open inventory screen with the real mouse.
	 *
	 * <p>The cursor is moved to the middle of the square and the click is only sent once the screen
	 * agrees that is the square under the pointer, so a layout change can never turn this into a
	 * click on something else that happens to pass.
	 */
	void clickSlot(int slotIndex) {
		moveCursorToSlot(slotIndex);
		context.getInput().pressMouse(0);
		settle();
	}

	/** Moves the mouse onto a slot and checks the screen agrees. */
	void moveCursorToSlot(int slotIndex) {
		double[] position = context.computeOnClient(client -> {
			AbstractContainerScreen<?> screen = containerScreen(client.screen);
			ContainerScreenAccessor accessor = (ContainerScreenAccessor) screen;
			Slot slot = screen.getMenu().getSlot(slotIndex);
			double scale = client.getWindow().getGuiScale();
			return new double[] {
					(accessor.mhr$leftPos() + slot.x + 8) * scale,
					(accessor.mhr$topPos() + slot.y + 8) * scale,
			};
		});
		context.getInput().setCursorPos(position[0], position[1]);
		context.waitTicks(2);

		int hovered = context.computeOnClient(client -> {
			ContainerScreenAccessor accessor = (ContainerScreenAccessor) containerScreen(client.screen);
			Slot slot = accessor.mhr$hoveredSlot();
			return slot == null ? -1 : slot.index;
		});
		int wanted = context.computeOnClient(client ->
				containerScreen(client.screen).getMenu().getSlot(slotIndex).index);
		if (hovered != wanted) {
			throw new AssertionError("Wanted the cursor on menu slot " + slotIndex
					+ " (container index " + wanted + ") at window position "
					+ position[0] + "," + position[1] + ", but the screen says it is on "
					+ (hovered == -1 ? "nothing" : "container index " + hovered));
		}
	}

	/** The menu index of the first slot holding this item, or -1. */
	int findSlotWithItem(Item item) {
		return context.computeOnClient(client -> {
			AbstractContainerScreen<?> screen = containerScreen(client.screen);
			NonNullList<Slot> slots = screen.getMenu().slots;
			for (int i = 0; i < slots.size(); i++) {
				if (slots.get(i).getItem().is(item)) {
					return i;
				}
			}
			return -1;
		});
	}

	/** What this client believes about a slot's padlock — the synced copy, not the server's file. */
	boolean clientThinksUnlocked(EquipmentSlot slot) {
		return context.computeOnClient(client ->
				fi.vilpponen.mhr.equipment.EquipmentLocks.isUnlockedForDisplay(client.player, slot));
	}

	private static AbstractContainerScreen<?> containerScreen(Object screen) {
		if (screen instanceof AbstractContainerScreen<?> container) {
			return container;
		}
		throw new AssertionError("Expected an inventory screen to be open, but it is " + screen);
	}
}
