package fi.vilpponen.mhr.gametest.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fi.vilpponen.mhr.core.Balance;
import fi.vilpponen.mhr.core.BalanceManager;
import fi.vilpponen.mhr.starter.ChestLoad;
import fi.vilpponen.mhr.starter.RunStart;
import fi.vilpponen.mhr.starter.StarterChest;
import fi.vilpponen.mhr.starter.StarterItems;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The starter chest, checked on a plain dedicated server with no client in sight.
 *
 * <p>These are the exact checks: what goes in the chest, how many chests that takes, and what
 * happens when the catalogue asks for more than can fit. Each one calls {@link RunStart#grant},
 * which is the single call both the first-join hook and {@code /mhr starterchest} come down to, so
 * what is exercised here is the delivery itself rather than a test-only shortcut. The half only a
 * real client can answer — that joining a fresh run is what triggers it, once and only once — lives
 * in {@link fi.vilpponen.mhr.gametest.client.StarterChestClientTest}.
 *
 * <p>Everything is one test method on purpose, for the same reason the animal test is: the unlocks
 * and the balance in effect are one file each for the whole server, and GameTest runs a batch's
 * tests side by side in the same world, so two methods retuning the catalogue would step on each
 * other. Inside the method the scenarios are strictly sequential, every one of them starts by
 * locking every starter item and emptying the box, and the balance override is taken away again
 * whatever happens, so the order does not matter and nothing leaks into the tests around it.
 *
 * <p>See {@code docs/dev-environment.md} for how to run them.
 */
public class StarterChestGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("mhr-gametest");

	/** The test structure is 8x8x8. The floor is laid at y=0 and the run starts at y=1. */
	private static final int SIZE = 8;

	/** Where the player is imagined to be standing when the run begins. */
	private static final BlockPos RUN_START = new BlockPos(4, 1, 4);

	/**
	 * The starter item every count-dependent scenario retunes.
	 *
	 * <p>A pickaxe on purpose: it does not stack, so "count" and "slots used" are the same number
	 * and a scenario about 28 slots is one line of JSON rather than arithmetic about bread.
	 */
	private static final String PICKAXE = "starter.stone_pickaxe";
	private static final String PICKAXE_ITEM = "minecraft:stone_pickaxe";

	@GameTest(maxTicks = 2000)
	public void theStarterChestDeliversWhatWasBought(GameTestHelper helper) {
		List<String> failures = new ArrayList<>();

		try {
			scenario(failures, "nothing-owned-places-no-chest",
					() -> nothingOwnedPlacesNoChest(helper));
			scenario(failures, "one-purchase-puts-a-chest-at-the-run-start",
					() -> onePurchasePutsAChestAtTheRunStart(helper));
			scenario(failures, "the-chest-holds-exactly-what-was-bought",
					() -> theChestHoldsExactlyWhatWasBought(helper));
			scenario(failures, "an-enchanted-starter-item-keeps-its-components",
					() -> anEnchantedStarterItemKeepsItsComponents(helper));
			scenario(failures, "more-than-27-slots-makes-a-double-chest",
					() -> moreThan27SlotsMakesADoubleChest(helper));
			scenario(failures, "a-double-chest-delivers-all-54-slots",
					() -> aDoubleChestDeliversAll54Slots(helper));
			scenario(failures, "past-54-slots-is-reported-and-nothing-vanishes-quietly",
					() -> past54SlotsIsReported(helper));
			scenario(failures, "an-override-and-a-reload-change-what-the-chest-holds",
					() -> anOverrideAndAReloadChangeTheContents(helper));
		} finally {
			// Whatever went wrong above, the next test in this run must find the bundled catalogue
			// and nothing owned.
			removeOverride(helper);
			lockEveryStarterItem(helper);
		}

		if (!failures.isEmpty()) {
			throw new AssertionError(failures.size() + " starter-chest scenario(s) failed:\n  "
					+ String.join("\n  ", failures));
		}
		LOGGER.info("All starter-chest server scenarios passed.");
		helper.succeed();
	}

	// --- the scenarios ---------------------------------------------------------------------

	/** Nothing bought is not an empty chest: it is no chest, and no block touched at all. */
	private void nothingOwnedPlacesNoChest(GameTestHelper helper) {
		start(helper);

		StarterChest.Placement placement = grant(helper);

		check(placement == null, "with no starter items owned nothing should be placed, but a chest"
				+ " was reported " + (placement == null ? "" : RunStart.describe(placement)));
		check(chestsInTheBox(helper).isEmpty(),
				"with no starter items owned there must be no chest anywhere near the run start,"
						+ " and there are " + chestsInTheBox(helper).size());
	}

	/** One purchase, one chest, next to where the run started, holding exactly that purchase. */
	private void onePurchasePutsAChestAtTheRunStart(GameTestHelper helper) {
		start(helper);
		buy(helper, "starter.bread");

		StarterChest.Placement placement = grant(helper);

		check(placement != null, "buying a starter item must put a chest at the run start, and"
				+ " nothing was placed");
		check(!placement.doubleChest(), "16 bread is one slot, so it must not take a double chest");
		check(isChest(helper, placement.pos()),
				"the reported position " + placement.pos() + " does not hold a chest, it holds "
						+ helper.getLevel().getBlockState(placement.pos()));
		check(placement.pos().closerThan(helper.absolutePos(RUN_START), 4.0),
				"the chest must land next to where the run started, and " + placement.pos()
						+ " is not near " + helper.absolutePos(RUN_START));
		check(chestsInTheBox(helper).size() == 1,
				"one purchase must make exactly one chest, and there are "
						+ chestsInTheBox(helper).size());
		checkContents(helper, placement, Map.of("minecraft:bread", 16));
	}

	/**
	 * Several purchases, and the chest holds all of them and nothing else.
	 *
	 * <p>The expected load is written out here rather than asked of the catalogue, so that a
	 * catalogue edit that changes what a purchase hands over shows up as a red test instead of
	 * quietly agreeing with itself.
	 */
	private void theChestHoldsExactlyWhatWasBought(GameTestHelper helper) {
		start(helper);
		buy(helper, "starter.bread", "starter.logs", "starter.torches", "starter.stone_pickaxe",
				"starter.iron_sword");

		StarterChest.Placement placement = grant(helper);

		check(placement != null, "five purchases must put a chest at the run start, and nothing was"
				+ " placed");
		check(!placement.doubleChest(), "five purchases are five slots, so one chest is enough");
		checkContents(helper, placement, Map.of(
				"minecraft:bread", 16,
				"minecraft:oak_log", 32,
				"minecraft:torch", 32,
				"minecraft:stone_pickaxe", 1,
				"minecraft:iron_sword", 1));
		check(occupiedSlots(helper, placement) == 5,
				"five purchases of different things must take five slots, and they took "
						+ occupiedSlots(helper, placement));
	}

	/**
	 * An enchanted purchase arrives enchanted.
	 *
	 * <p>This is the components check, and the catalogue's own enchanted pickaxe is the one to make
	 * it with: an item stack goes through a codec, a chest slot and a block entity between the
	 * balance file and the player, and a stage that dropped the components would still hand over a
	 * perfectly ordinary diamond pickaxe and look like it worked.
	 */
	private void anEnchantedStarterItemKeepsItsComponents(GameTestHelper helper) {
		start(helper);
		buy(helper, "starter.efficient_pickaxe");

		StarterChest.Placement placement = grant(helper);

		check(placement != null, "buying the enchanted pickaxe must put a chest at the run start");
		List<ItemStack> contents = contentsOf(helper, placement);
		check(contents.size() == 1, "one purchase must be one stack, and the chest holds "
				+ describe(contents));

		ItemStack pickaxe = contents.getFirst();
		check(pickaxe.is(Items.DIAMOND_PICKAXE),
				"the chest should hold a diamond pickaxe, and it holds " + describe(contents));
		check(pickaxe.isEnchanted(),
				"the enchanted starter pickaxe must arrive enchanted, and it is plain");

		ItemEnchantments enchantments =
				pickaxe.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
		int efficiency = 0;
		for (var held : enchantments.keySet()) {
			if (held.is(Enchantments.EFFICIENCY)) {
				efficiency = enchantments.getLevel(held);
			}
		}
		check(efficiency == 3, "the catalogue sells the pickaxe with Efficiency 3 and the chest"
				+ " holds one with Efficiency " + efficiency);
	}

	/** Past 27 filled slots the chest has to be a double one, and both halves have to be real. */
	private void moreThan27SlotsMakesADoubleChest(GameTestHelper helper) {
		start(helper);
		sellPickaxes(helper, 30);

		StarterChest.Placement placement = grant(helper);

		check(placement != null, "30 pickaxes must put a chest at the run start");
		check(placement.doubleChest(), "30 slots does not fit one chest, so it must be a double one");
		check(placement.omitted().isEmpty(),
				"30 slots fit a double chest, so nothing should have been left out, and "
						+ StarterChest.describe(placement.omitted()) + " was");

		BlockPos other = otherHalf(helper, placement.pos());
		check(other != null, "a double chest must have a second half, and the block at "
				+ placement.pos() + " is not joined to one");
		check(isChest(helper, other), "the second half at " + other + " is not a chest, it is "
				+ helper.getLevel().getBlockState(other));
		check(other.getY() == placement.pos().getY(),
				"both halves have to sit at the same height, and they are at "
						+ placement.pos().getY() + " and " + other.getY());

		check(slotsUsed(helper, placement.pos()) == ChestLoad.SINGLE_CHEST_SLOTS,
				"the first half should be full at " + ChestLoad.SINGLE_CHEST_SLOTS + " slots, and it"
						+ " holds " + slotsUsed(helper, placement.pos()));
		check(slotsUsed(helper, other) == 3,
				"the second half should hold the remaining 3, and it holds " + slotsUsed(helper, other));
		checkContents(helper, placement, Map.of(PICKAXE_ITEM, 30));
	}

	/** 54 is the ceiling, and everything up to it is delivered rather than rounded off. */
	private void aDoubleChestDeliversAll54Slots(GameTestHelper helper) {
		start(helper);
		sellPickaxes(helper, ChestLoad.DOUBLE_CHEST_SLOTS);

		StarterChest.Placement placement = grant(helper);

		check(placement != null, "54 pickaxes must put a chest at the run start");
		check(placement.doubleChest(), "54 slots is a double chest");
		check(placement.omitted().isEmpty(),
				"54 slots is exactly what a double chest holds, so nothing should have been left"
						+ " out, and " + StarterChest.describe(placement.omitted()) + " was");
		check(occupiedSlots(helper, placement) == ChestLoad.DOUBLE_CHEST_SLOTS,
				"all 54 slots must be delivered, and " + occupiedSlots(helper, placement)
						+ " arrived");
		checkContents(helper, placement, Map.of(PICKAXE_ITEM, ChestLoad.DOUBLE_CHEST_SLOTS));
	}

	/**
	 * More than 54 slots is a broken catalogue, and it has to be said out loud.
	 *
	 * <p>The chest is still placed and still full — the run is not worth ruining over a bad balance
	 * file — but the leftover is named, and the arithmetic has to add up: what arrived plus what was
	 * reported missing is what was asked for. An overflow that reported nothing would be a purchase
	 * gone silently, which is the failure this whole path exists to avoid.
	 */
	private void past54SlotsIsReported(GameTestHelper helper) {
		start(helper);
		int asked = ChestLoad.DOUBLE_CHEST_SLOTS + 6;
		sellPickaxes(helper, asked);

		StarterChest.Placement placement = grant(helper);

		check(placement != null, "an overflowing catalogue must still put the chest down");
		check(placement.doubleChest(), "an overflowing catalogue still fills a double chest");
		check(occupiedSlots(helper, placement) == ChestLoad.DOUBLE_CHEST_SLOTS,
				"a double chest holds 54 slots and this one holds " + occupiedSlots(helper, placement));

		check(!placement.omitted().isEmpty(),
				"asking for " + asked + " slots must report the overflow, and nothing was reported");
		long leftOut = 0;
		for (ChestLoad.Omitted entry : placement.omitted()) {
			leftOut += entry.count();
		}
		check(leftOut == asked - ChestLoad.DOUBLE_CHEST_SLOTS,
				"what did not fit must be reported item for item: " + asked + " asked for, "
						+ ChestLoad.DOUBLE_CHEST_SLOTS + " delivered, but only " + leftOut
						+ " reported missing (" + StarterChest.describe(placement.omitted()) + ")");
		check(StarterChest.describe(placement.omitted()).contains("Stone Pickaxe"),
				"the overflow has to name the item a human would recognise, and it says '"
						+ StarterChest.describe(placement.omitted()) + "'");
	}

	/**
	 * Retuning a starter item in the config override and reloading changes what the chest holds.
	 *
	 * <p>The whole reason starter items are balance data rather than code is that they can be
	 * retuned without a rebuild, so this is the feature and not a detail of it. It also proves the
	 * item is read at the point of use: the same server that built a chest of bread a moment ago
	 * builds one of cooked beef after {@code /mhr reload}, with nothing restarted.
	 */
	private void anOverrideAndAReloadChangeTheContents(GameTestHelper helper) {
		start(helper);
		buy(helper, "starter.bread");
		checkContents(helper, grant(helper), Map.of("minecraft:bread", 16));

		try {
			overrideItem(helper, "starter.bread", "{\"id\":\"minecraft:cooked_beef\",\"count\":5}");
			start(helper);
			buy(helper, "starter.bread");

			checkContents(helper, grant(helper), Map.of("minecraft:cooked_beef", 5));
		} finally {
			removeOverride(helper);
		}

		start(helper);
		buy(helper, "starter.bread");
		checkContents(helper, grant(helper), Map.of("minecraft:bread", 16),
				"taking the override away again must bring the bundled catalogue back");
	}

	// --- the chest ---------------------------------------------------------------------------

	/** Place the chest the way both the join hook and {@code /mhr starterchest} do. */
	private static StarterChest.Placement grant(GameTestHelper helper) {
		return RunStart.grant(helper.getLevel(), helper.absolutePos(RUN_START), Direction.NORTH);
	}

	/** Every stack in the chest, both halves of a double one, in slot order. */
	private static List<ItemStack> contentsOf(GameTestHelper helper, StarterChest.Placement placement) {
		List<ItemStack> stacks = new ArrayList<>(stacksIn(helper, placement.pos()));
		BlockPos other = otherHalf(helper, placement.pos());
		if (other != null) {
			stacks.addAll(stacksIn(helper, other));
		}
		return stacks;
	}

	private static List<ItemStack> stacksIn(GameTestHelper helper, BlockPos pos) {
		List<ItemStack> stacks = new ArrayList<>();
		if (!(helper.getLevel().getBlockEntity(pos) instanceof ChestBlockEntity chest)) {
			throw new AssertionError("No chest to read at " + pos + ", the block there is "
					+ helper.getLevel().getBlockState(pos));
		}
		for (int slot = 0; slot < chest.getContainerSize(); slot++) {
			ItemStack stack = chest.getItem(slot);
			if (!stack.isEmpty()) {
				stacks.add(stack.copy());
			}
		}
		return stacks;
	}

	/** The other half of a double chest, or null when this one stands alone. */
	private static BlockPos otherHalf(GameTestHelper helper, BlockPos pos) {
		BlockState state = helper.getLevel().getBlockState(pos);
		if (!state.is(Blocks.CHEST) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
			return null;
		}
		return ChestBlock.getConnectedBlockPos(pos, state);
	}

	private static int slotsUsed(GameTestHelper helper, BlockPos pos) {
		return stacksIn(helper, pos).size();
	}

	private static int occupiedSlots(GameTestHelper helper, StarterChest.Placement placement) {
		return contentsOf(helper, placement).size();
	}

	private static boolean isChest(GameTestHelper helper, BlockPos pos) {
		return helper.getLevel().getBlockState(pos).is(Blocks.CHEST);
	}

	/** Every chest block standing in the test box, so "no chest" can be checked as well as "a chest". */
	private static List<BlockPos> chestsInTheBox(GameTestHelper helper) {
		List<BlockPos> found = new ArrayList<>();
		for (int x = 0; x < SIZE; x++) {
			for (int y = 0; y < SIZE; y++) {
				for (int z = 0; z < SIZE; z++) {
					BlockPos pos = helper.absolutePos(new BlockPos(x, y, z));
					if (helper.getLevel().getBlockState(pos).is(Blocks.CHEST)) {
						found.add(pos);
					}
				}
			}
		}
		return found;
	}

	private static void checkContents(
			GameTestHelper helper, StarterChest.Placement placement, Map<String, Integer> expected) {
		checkContents(helper, placement, expected, "the chest holds the wrong things");
	}

	/**
	 * What is in the chest, item by item and count by count, with nothing extra.
	 *
	 * <p>Totals per item rather than slot by slot, because how a load is split across slots is
	 * {@link ChestLoad}'s business and is checked by its own unit tests; what matters to a player
	 * opening the chest is that everything bought is in there and nothing else is.
	 */
	private static void checkContents(GameTestHelper helper, StarterChest.Placement placement,
			Map<String, Integer> expected, String why) {
		check(placement != null, why + ": no chest was placed at all");
		List<ItemStack> contents = contentsOf(helper, placement);
		Map<String, Integer> actual = new LinkedHashMap<>();
		for (ItemStack stack : contents) {
			actual.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(),
					Integer::sum);
		}
		check(actual.equals(expected),
				why + ": expected " + expected + " and the chest holds " + actual
						+ " (" + describe(contents) + ")");
	}

	private static String describe(List<ItemStack> stacks) {
		StringBuilder text = new StringBuilder();
		for (ItemStack stack : stacks) {
			if (!text.isEmpty()) {
				text.append(", ");
			}
			text.append(stack.getCount()).append('x').append(stack.getHoverName().getString());
		}
		return text.isEmpty() ? "nothing" : text.toString();
	}

	// --- the box and the catalogue -----------------------------------------------------------

	/** Nothing owned, nothing standing: what every scenario starts from. */
	private static void start(GameTestHelper helper) {
		lockEveryStarterItem(helper);
		clearTheBox(helper);
	}

	/** A stone floor with clear air above it, so a chest has somewhere to stand and nowhere to hide. */
	private static void clearTheBox(GameTestHelper helper) {
		for (int x = 0; x < SIZE; x++) {
			for (int z = 0; z < SIZE; z++) {
				helper.setBlock(x, 0, z, Blocks.STONE);
				for (int y = 1; y < SIZE; y++) {
					helper.setBlock(x, y, z, Blocks.AIR);
				}
			}
		}
	}

	private static void buy(GameTestHelper helper, String... ids) {
		for (String id : ids) {
			command(helper, "mhr unlock " + id);
		}
	}

	private static void lockEveryStarterItem(GameTestHelper helper) {
		for (String id : StarterItems.ids()) {
			command(helper, "mhr lock " + id);
		}
	}

	/** Retune the pickaxe to hand over {@code count} of them, and buy it. */
	private static void sellPickaxes(GameTestHelper helper, int count) {
		overrideItem(helper, PICKAXE, "{\"id\":\"" + PICKAXE_ITEM + "\",\"count\":" + count + "}");
		buy(helper, PICKAXE);
	}

	/**
	 * Write a config override for one starter item's stack and reload the balance.
	 *
	 * <p>The reload is the real {@code /mhr reload}, and it is checked rather than assumed: a
	 * refused override leaves the running game on the balance it had, which would make every
	 * scenario below it fail somewhere much less obvious.
	 */
	private static void overrideItem(GameTestHelper helper, String id, String itemJson) {
		JsonObject wanted = JsonParser.parseString(itemJson).getAsJsonObject();
		Path file = BalanceManager.overrideFile();
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file,
					"{\"unlocks\": {\"" + id + "\": {\"item\": " + itemJson + "}}}",
					StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not write the balance override " + file, e);
		}
		command(helper, "mhr reload");

		JsonObject inEffect = BalanceManager.get().unlock(id)
				.flatMap(Balance.UnlockBalance::item)
				.orElseThrow(() -> new AssertionError(
						"After the override, " + id + " has no item in the balance in effect"));
		check(wanted.equals(inEffect),
				"'mhr reload' did not pick the override up: " + id + " should now hand over "
						+ wanted + " and the balance in effect still says " + inEffect);
	}

	/** Take the override away again and go back to the bundled catalogue. */
	private static void removeOverride(GameTestHelper helper) {
		Path file = BalanceManager.overrideFile();
		try {
			Files.deleteIfExists(file);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not remove the balance override " + file, e);
		}
		command(helper, "mhr reload");
	}

	// --- plumbing --------------------------------------------------------------------------

	/** The dev command, through the real command dispatcher, as the console would run it. */
	private static void command(GameTestHelper helper, String command) {
		ServerLevel level = helper.getLevel();
		MinecraftServer server = level.getServer();
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
	}

	/**
	 * Runs one scenario. A failure is recorded rather than ending the run, so one command shows
	 * every criterion that is red instead of only the first.
	 */
	private static void scenario(List<String> failures, String name, Runnable body) {
		LOGGER.info("=== scenario {} ===", name);
		try {
			body.run();
			LOGGER.info("=== scenario {}: PASS ===", name);
		} catch (Throwable failure) {
			failures.add(name + ": " + failure.getMessage());
			LOGGER.error("=== scenario {}: FAIL === {}", name, failure.getMessage(), failure);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
