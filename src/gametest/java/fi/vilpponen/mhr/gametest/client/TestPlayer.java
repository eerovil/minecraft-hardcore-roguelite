package fi.vilpponen.mhr.gametest.client;

import fi.vilpponen.mhr.gametest.mixin.ContainerScreenAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

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

	// GLFW's own numbers for a mouse button going down and coming back up, and for the shift
	// modifier it hands over with them. LWJGL is not on this source set's compile path, so they are
	// spelled out rather than imported.
	private static final int BUTTON_UP = 0;
	private static final int BUTTON_DOWN = 1;
	private static final int SHIFT_HELD = 0x0001;

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
	/**
	 * The same places, spelled out. A count that is wrong is only useful if you can see where the
	 * extra copy is, so every failed conservation check prints this.
	 */
	String whereItIs(Item item) {
		return server.computeOnServer(unused -> {
			ServerPlayer player = connection.getServerPlayer();
			StringBuilder where = new StringBuilder();

			NonNullList<ItemStack> items = player.getInventory().getNonEquipmentItems();
			for (int i = 0; i < items.size(); i++) {
				if (items.get(i).is(item)) {
					where.append(" inventory[").append(i).append("]x").append(items.get(i).getCount());
				}
			}
			for (EquipmentSlot slot : countableEquipment()) {
				ItemStack stack = player.getItemBySlot(slot);
				if (stack.is(item)) {
					where.append(' ').append(slot.getName()).append('x').append(stack.getCount());
				}
			}
			ItemStack carried = player.containerMenu.getCarried();
			if (carried.is(item)) {
				where.append(" cursorx").append(carried.getCount());
			}
			for (ItemEntity entity : player.level().getEntitiesOfClass(
					ItemEntity.class, player.getBoundingBox().inflate(16.0))) {
				if (entity.getItem().is(item)) {
					where.append(" groundx").append(entity.getItem().getCount());
				}
			}
			return where.isEmpty() ? " nowhere" : where.toString();
		});
	}

	int reachableCount(Item item) {
		return server.computeOnServer(unused -> {
			ServerPlayer player = connection.getServerPlayer();
			int count = 0;

			NonNullList<ItemStack> items = player.getInventory().getNonEquipmentItems();
			for (ItemStack stack : items) {
				count += stack.is(item) ? stack.getCount() : 0;
			}
			for (EquipmentSlot slot : countableEquipment()) {
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

	/**
	 * The equipment slots worth counting separately.
	 *
	 * <p>The main hand is left out on purpose: it is not a place of its own, it is whichever hotbar
	 * square is selected, and it is already in {@code getNonEquipmentItems()}. Counting both is how
	 * a perfectly conserved item comes out as two.
	 */
	private static List<EquipmentSlot> countableEquipment() {
		List<EquipmentSlot> slots = new ArrayList<>();
		for (EquipmentSlot slot : EquipmentSlot.values()) {
			if (slot != EquipmentSlot.MAINHAND) {
				slots.add(slot);
			}
		}
		return slots;
	}

	/** What is on the cursor, as the server sees it. */
	ItemStack cursor() {
		return server.computeOnServer(unused -> connection.getServerPlayer().containerMenu.getCarried().copy());
	}

	/** Puts the player back to nothing owned, nothing worn, nothing carried, empty inventory. */
	void reset(List<String> unlockIds) {
		// A scenario that failed halfway may have left a screen open. Close it before anything
		// else, so one red scenario cannot make the next one red for a different reason.
		context.setScreen(() -> null);
		context.waitTicks(2);
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
		context.waitFor(client -> client.gui.screen() == null);
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
		// MOUSE_BUTTON_LEFT, not 0. 26.3 gets its input from SDL, where the left button is 1;
		// pressing 0 presses a button that does not exist and nothing happens at all.
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		settle();
	}

	/**
	 * Left-clicks a slot with shift held, which is the "send it straight across" move — out of the
	 * crafting output and into the inventory without anything ever sitting on the cursor.
	 */
	void shiftClickSlot(int slotIndex) {
		moveCursorToSlot(slotIndex);
		// 26.3 reads shift off the mouse event itself rather than asking the keyboard, and the test
		// harness's own pressMouse always sends a modifier-less event — holding the shift key and
		// clicking would be an ordinary click. So the button goes in through MouseHandler.onButton,
		// which is the same door the operating system's mouse callback comes through, carrying the
		// modifier a real shift-click would have carried.
		context.runOnClient(client -> client.mouseHandler.onButton(client.getWindow().handle(),
				new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, SHIFT_HELD),
				BUTTON_DOWN));
		context.waitTick();
		context.runOnClient(client -> client.mouseHandler.onButton(client.getWindow().handle(),
				new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, SHIFT_HELD),
				BUTTON_UP));
		settle();
	}

	/** Moves the mouse onto a slot and checks the screen agrees. */
	void moveCursorToSlot(int slotIndex) {
		double[] position = context.computeOnClient(client -> {
			AbstractContainerScreen<?> screen = containerScreen(client.gui.screen());
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

		// Compared by position in the menu rather than by the slot's own container index, because
		// those are not unique: a crafting menu's result slot and the first grid square are both
		// index 0 of their own container, and confusing the two is exactly the mistake this check
		// exists to catch.
		int hovered = context.computeOnClient(client -> {
			AbstractContainerScreen<?> screen = containerScreen(client.gui.screen());
			Slot slot = ((ContainerScreenAccessor) screen).mhr$hoveredSlot();
			return slot == null ? -1 : screen.getMenu().slots.indexOf(slot);
		});
		if (hovered != slotIndex) {
			throw new AssertionError("Wanted the cursor on menu slot " + slotIndex
					+ " at window position " + position[0] + "," + position[1]
					+ ", but the screen says it is on "
					+ (hovered == -1 ? "nothing" : "menu slot " + hovered));
		}
	}

	/**
	 * Opens a real crafting table, the way a player does: stand on one and right-click it.
	 *
	 * <p>The table is put under the player's own feet and the player is turned to look straight
	 * down at it. That is the one aim that cannot be blocked by scenery, so the right-click lands on
	 * the table or the test fails waiting for the screen — it can never quietly hit something else.
	 */
	void openCraftingTable() {
		BlockPos feet = server.computeOnServer(unused -> connection.getServerPlayer().blockPosition());
		int x = feet.getX();
		int y = feet.getY();
		int z = feet.getZ();

		boolean alreadyThere = server.computeOnServer(unused -> {
			ServerPlayer player = connection.getServerPlayer();
			return player.level().getBlockState(player.blockPosition().below()).is(Blocks.CRAFTING_TABLE);
		});
		// Named explicitly, because a server command runs in the overworld unless it is told
		// otherwise and the player is normally standing in the lobby. Without this the table is
		// built at the player's coordinates in a dimension they are not in — which reads as "that
		// position is not loaded" when the overworld has nobody near those coordinates, and as a
		// table nobody can reach when it does happen to be loaded.
		String here = "execute in " + server.computeOnServer(unused ->
				connection.getServerPlayer().level().dimension().identifier().toString()) + " run ";
		if (!alreadyThere) {
			server.runCommand(here + "fill " + x + " " + y + " " + z + " " + x + " " + (y + 1) + " " + z
					+ " minecraft:air");
			server.runCommand(here + "setblock " + x + " " + (y - 1) + " " + z
					+ " minecraft:crafting_table");
		}
		// Yaw 0, pitch 90: straight down at the block being stood on.
		server.runCommand(here + "tp Player0 " + (x + 0.5) + " " + y + " " + (z + 0.5) + " 0 90");
		settle();

		// MOUSE_BUTTON_RIGHT is the use key while no screen is open. SDL numbers the buttons from
		// one, the same reason the left button is 1 and not 0.
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
		context.waitForScreen(CraftingScreen.class);
		context.waitTicks(3);
		settle();
	}

	/** Puts one item in a numbered inventory square, so a test can click a known square. */
	void giveAt(int inventoryIndex, Item item) {
		giveAt(inventoryIndex, item, 1);
	}

	/** The same, with a count — for the paths that take their ingredients out of a stack. */
	void giveAt(int inventoryIndex, Item item, int count) {
		server.runOnServer(unused -> {
			ServerPlayer player = connection.getServerPlayer();
			player.getInventory().setItem(inventoryIndex, new ItemStack(item, count));
			player.containerMenu.broadcastChanges();
		});
		settle();
	}

	/** The first stack in the player's own inventory holding this item, as the server has it. */
	ItemStack inventoryStack(Item item) {
		return server.computeOnServer(unused -> {
			for (ItemStack stack : connection.getServerPlayer().getInventory().getNonEquipmentItems()) {
				if (stack.is(item)) {
					return stack.copy();
				}
			}
			return ItemStack.EMPTY;
		});
	}

	/** Runs something on the server thread and waits for both sides to catch up. */
	void onServer(Consumer<MinecraftServer> action) {
		server.runOnServer(action::accept);
		settle();
	}

	/** Where a numbered inventory square shows up in the menu that is open now. */
	int menuSlotForInventory(int inventoryIndex) {
		int found = context.computeOnClient(client -> {
			AbstractContainerScreen<?> screen = containerScreen(client.gui.screen());
			NonNullList<Slot> slots = screen.getMenu().slots;
			for (int i = 0; i < slots.size(); i++) {
				Slot slot = slots.get(i);
				if (slot.container instanceof Inventory && slot.getContainerSlot() == inventoryIndex) {
					return i;
				}
			}
			return -1;
		});
		if (found < 0) {
			throw new AssertionError("The open screen has no square for inventory slot " + inventoryIndex);
		}
		return found;
	}

	/** What the server has in a slot of the open menu. The authority on what a slot really holds. */
	ItemStack menuItem(int slotIndex) {
		return server.computeOnServer(unused ->
				connection.getServerPlayer().containerMenu.getSlot(slotIndex).getItem().copy());
	}

	/** What this client has been told is in that slot — what the player can actually see. */
	ItemStack clientMenuItem(int slotIndex) {
		return context.computeOnClient(client ->
				containerScreen(client.gui.screen()).getMenu().getSlot(slotIndex).getItem().copy());
	}

	/** The menu index of the first slot holding this item, or -1. */
	int findSlotWithItem(Item item) {
		return context.computeOnClient(client -> {
			AbstractContainerScreen<?> screen = containerScreen(client.gui.screen());
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
