package fi.vilpponen.mhr.gametest.client;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The equipment-slot checks that used to need a human in a Minecraft client.
 *
 * <p>A real client connects to a real dedicated server and a real player presses real keys and
 * clicks real squares. Nothing writes an equipment slot behind the mod's back, so what these
 * scenarios prove is what a player would see.
 *
 * <p>Each scenario puts the player back to nothing-owned before it starts, so a failure early on
 * does not make a later one lie, and the order they run in does not matter. Every one of them ends
 * by counting the item: the whole rule is that a refused piece of gear is refused *and* still
 * reachable, so "not equipped" is never allowed to mean "destroyed".
 *
 * <p>See {@code docs/dev-environment.md} for how to run this.
 */
public class EquipmentLockClientTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("mhr-gametest");

	private static final List<String> SLOT_UNLOCKS = List.of(
			"player.slot.helmet",
			"player.slot.chestplate",
			"player.slot.leggings",
			"player.slot.boots",
			"player.slot.offhand");

	private final List<String> failures = new ArrayList<>();

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestDedicatedServerContext server = context.worldBuilder().createServer()) {
			try (TestDedicatedServerConnection connection = server.connect()) {
				connection.waitForChunksRender();
				TestPlayer player = new TestPlayer(context, server, connection);

				scenario(context, "locked-helmet-slot-refuses-a-helmet",
						() -> lockedHelmetSlotRefusesAHelmet(context, player));
				scenario(context, "unlock-while-connected-then-equip",
						() -> unlockWhileConnectedThenEquip(context, player));
				scenario(context, "offhand-swap-hands-locked-then-unlocked",
						() -> offhandSwapHands(player));
				scenario(context, "locking-an-occupied-slot-empties-it",
						() -> lockingAnOccupiedSlotEmptiesIt(player));
			}

			scenario(context, "reconnect-keeps-a-locked-slot-empty",
					() -> reconnectKeepsALockedSlotEmpty(context, server));
		}

		if (!failures.isEmpty()) {
			throw new AssertionError(failures.size() + " equipment-lock scenario(s) failed:\n  "
					+ String.join("\n  ", failures));
		}
		LOGGER.info("All equipment-lock client scenarios passed.");
	}

	// --- the scenarios ---------------------------------------------------------------------

	/**
	 * A locked helmet slot refuses a helmet clicked into it, and the helmet survives.
	 */
	private void lockedHelmetSlotRefusesAHelmet(ClientGameTestContext context, TestPlayer player) {
		player.reset(SLOT_UNLOCKS);
		player.giveInHand(Items.IRON_HELMET);

		check(player.equipped(EquipmentSlot.HEAD).isEmpty(), "the head slot should start empty");

		player.openInventory();
		check(!player.clientThinksUnlocked(EquipmentSlot.HEAD),
				"the client should have been told the helmet slot is locked, so it can draw the padlock");
		context.takeScreenshot("inventory-helmet-slot-locked");

		int helmetSlot = player.findSlotWithItem(Items.IRON_HELMET);
		check(helmetSlot >= 0, "the helmet should be somewhere in the open inventory screen");
		player.clickSlot(helmetSlot);
		check(player.cursor().is(Items.IRON_HELMET),
				"clicking the helmet should pick it up onto the cursor, but the cursor holds "
						+ player.cursor());
		player.clickSlot(TestPlayer.HEAD_SLOT);
		player.closeInventory();

		check(player.equipped(EquipmentSlot.HEAD).isEmpty(),
				"a locked helmet slot must stay empty after a click that tries to fill it");
		check(player.reachableCount(Items.IRON_HELMET) == 1,
				"the refused helmet must still exist exactly once, but there are "
						+ player.reachableCount(Items.IRON_HELMET) + ":" + player.whereItIs(Items.IRON_HELMET));

		// The click above is turned away three times over — the slot says it cannot be filled, the
		// player says it has no such slot, and the write barrier refuses the write. That is good for
		// a player and bad for a test: it would still pass with the barrier gone. `/item replace`
		// writes the slot directly, past both of the polite refusals, so this last pair of
		// assertions is red the moment the barrier itself stops working.
		player.command("item replace entity Player0 armor.head with minecraft:iron_helmet");
		check(player.equipped(EquipmentSlot.HEAD).isEmpty(),
				"a locked helmet slot must refuse a direct write too, but it holds "
						+ player.equipped(EquipmentSlot.HEAD));
		check(player.reachableCount(Items.IRON_HELMET) == 2,
				"the directly written helmet must be handed back rather than destroyed, and the"
						+ " helmets are at:" + player.whereItIs(Items.IRON_HELMET));
	}

	/**
	 * Unlocking while the inventory is open reaches the client, and the helmet then goes on.
	 */
	private void unlockWhileConnectedThenEquip(ClientGameTestContext context, TestPlayer player) {
		player.reset(SLOT_UNLOCKS);
		player.giveInHand(Items.IRON_HELMET);

		player.openInventory();
		check(!player.clientThinksUnlocked(EquipmentSlot.HEAD), "the helmet slot should start locked");

		player.command("mhr unlock player.slot.helmet");
		check(player.clientThinksUnlocked(EquipmentSlot.HEAD),
				"the open inventory should have been told at once that the helmet slot is now open");
		context.takeScreenshot("inventory-helmet-slot-unlocked");

		int helmetSlot = player.findSlotWithItem(Items.IRON_HELMET);
		check(helmetSlot >= 0, "the helmet should be somewhere in the open inventory screen");
		player.clickSlot(helmetSlot);
		check(player.cursor().is(Items.IRON_HELMET),
				"clicking the helmet should pick it up onto the cursor, but the cursor holds "
						+ player.cursor());
		player.clickSlot(TestPlayer.HEAD_SLOT);
		player.closeInventory();

		check(player.equipped(EquipmentSlot.HEAD).is(Items.IRON_HELMET),
				"an unlocked helmet slot must accept the helmet, but it holds "
						+ player.equipped(EquipmentSlot.HEAD) + " and the helmet is at:"
						+ player.whereItIs(Items.IRON_HELMET));
		check(player.reachableCount(Items.IRON_HELMET) == 1,
				"equipping must not duplicate the helmet, but it is at:" + player.whereItIs(Items.IRON_HELMET));
	}

	/**
	 * The swap-hands key: refused while the offhand is locked, vanilla again once it is not.
	 */
	private void offhandSwapHands(TestPlayer player) {
		player.reset(SLOT_UNLOCKS);
		player.giveInHand(Items.SHIELD);

		player.pressSwapHands();
		check(player.equipped(EquipmentSlot.OFFHAND).isEmpty(),
				"a locked offhand must stay empty when the swap-hands key is pressed");
		check(player.reachableCount(Items.SHIELD) == 1,
				"the refused shield must be neither lost nor duplicated, but there are "
						+ player.reachableCount(Items.SHIELD) + ":" + player.whereItIs(Items.SHIELD));

		player.command("mhr unlock player.slot.offhand");
		player.putInHand(Items.SHIELD);
		player.pressSwapHands();

		check(player.equipped(EquipmentSlot.OFFHAND).is(Items.SHIELD),
				"an unlocked offhand must swap normally, but it holds "
						+ player.equipped(EquipmentSlot.OFFHAND));
		check(player.reachableCount(Items.SHIELD) == 1,
				"the swap must not duplicate the shield, but it is at:" + player.whereItIs(Items.SHIELD));
	}

	/**
	 * Locking a slot that is in use empties it on the spot and hands the item back.
	 */
	private void lockingAnOccupiedSlotEmptiesIt(TestPlayer player) {
		player.reset(SLOT_UNLOCKS);
		player.command("mhr unlock player.slot.offhand");
		player.giveInHand(Items.SHIELD);
		player.pressSwapHands();
		check(player.equipped(EquipmentSlot.OFFHAND).is(Items.SHIELD),
				"setup: the shield should be in the offhand before the slot is locked");

		player.command("mhr lock player.slot.offhand");

		check(player.equipped(EquipmentSlot.OFFHAND).isEmpty(),
				"locking an occupied slot must empty it immediately");
		check(player.reachableCount(Items.SHIELD) == 1,
				"the shield must come back to the player, but there are "
						+ player.reachableCount(Items.SHIELD) + ":" + player.whereItIs(Items.SHIELD));
	}

	/**
	 * The rule outlives the connection: a slot locked while the player is away is empty when they
	 * come back, and the gear that was in it is in their inventory.
	 */
	private void reconnectKeepsALockedSlotEmpty(
			ClientGameTestContext context, TestDedicatedServerContext server) {
		try (TestDedicatedServerConnection connection = server.connect()) {
			connection.waitForChunksDownload();
			TestPlayer player = new TestPlayer(context, server, connection);
			player.reset(SLOT_UNLOCKS);
			player.command("mhr unlock player.slot.helmet");
			player.giveInHand(Items.IRON_HELMET);

			player.openInventory();
			int helmetSlot = player.findSlotWithItem(Items.IRON_HELMET);
			check(helmetSlot >= 0, "the helmet should be somewhere in the open inventory screen");
			player.clickSlot(helmetSlot);
			player.clickSlot(TestPlayer.HEAD_SLOT);
			player.closeInventory();
			check(player.equipped(EquipmentSlot.HEAD).is(Items.IRON_HELMET),
					"setup: the helmet should be worn before the player logs out, but it is at:"
							+ player.whereItIs(Items.IRON_HELMET));
		}

		// Nobody is connected now, which is exactly the case the JOIN handler exists for.
		server.runCommand("mhr lock player.slot.helmet");

		try (TestDedicatedServerConnection connection = server.connect()) {
			connection.waitForChunksDownload();
			TestPlayer player = new TestPlayer(context, server, connection);
			player.settle();

			ItemStack head = player.equipped(EquipmentSlot.HEAD);
			check(head.isEmpty(),
					"a slot locked while the player was away must be empty on rejoin, but it holds " + head);
			check(player.reachableCount(Items.IRON_HELMET) == 1,
					"the helmet must survive the reconnect exactly once, but there are "
							+ player.reachableCount(Items.IRON_HELMET) + ":"
							+ player.whereItIs(Items.IRON_HELMET));
			check(!player.clientThinksUnlocked(EquipmentSlot.HEAD),
					"a rejoining client must be told the helmet slot is locked");
			context.takeScreenshot("reconnect-helmet-slot-locked");
		}
	}

	// --- plumbing --------------------------------------------------------------------------

	/**
	 * Runs one scenario. A failure is recorded and photographed rather than ending the run, so one
	 * command shows every criterion that is red instead of only the first.
	 */
	private void scenario(ClientGameTestContext context, String name, Runnable body) {
		LOGGER.info("=== scenario {} ===", name);
		try {
			body.run();
			LOGGER.info("=== scenario {}: PASS ===", name);
		} catch (Throwable failure) {
			failures.add(name + ": " + failure.getMessage());
			LOGGER.error("=== scenario {}: FAIL === {}", name, failure.getMessage(), failure);
			try {
				context.takeScreenshot("failed-" + name);
			} catch (Throwable ignored) {
				LOGGER.warn("Could not screenshot the failure of {}", name);
			}
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
