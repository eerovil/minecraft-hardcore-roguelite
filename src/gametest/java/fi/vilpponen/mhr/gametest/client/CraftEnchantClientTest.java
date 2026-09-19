package fi.vilpponen.mhr.gametest.client;

import fi.vilpponen.mhr.core.BalanceManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.core.Holder;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The crafted-tool enchant, crafted for real.
 *
 * <p>These are the checks {@code docs/dev-environment.md} used to hand to a human with the words
 * *"the crafting itself needs a real client"*. A real client stands on a real crafting table,
 * right-clicks it open, carries the ingredients into the 3x3 grid one square at a time with the
 * real mouse, and reads what is in the output slot before anything takes it. That last part is the
 * whole point of the feature: it is the crafting <em>result</em> that is enchanted, not the item in
 * your hand a moment later, so a test that only looked at the inventory afterwards would pass on an
 * implementation that enchanted things at the wrong moment.
 *
 * <p>{@link fi.vilpponen.mhr.gametest.CraftEnchantGameTest} covers what needs no client — which
 * enchantments may be rolled, on what, and how high — across dozens of items in seconds. This one
 * is deliberately the small, slow half: the paths a player's hands take.
 *
 * <p>Every scenario puts the player back to nothing owned and an empty grid before it starts, so
 * one red scenario cannot make the next one lie and the order they run in does not matter.
 *
 * <p>See {@code docs/dev-environment.md} for how to run this.
 */
public class CraftEnchantClientTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("mhr-gametest");

	/** The unlock under test. Locked again before every scenario. */
	private static final List<String> CRAFT_UNLOCKS = List.of("player.craft.enchant");

	/** The crafting menu's own layout: the output, then the grid row by row. */
	private static final int RESULT_SLOT = 0;
	private static final int FIRST_GRID_SLOT = 1;
	private static final int LAST_GRID_SLOT = 9;

	/**
	 * A wooden pickaxe: three planks across the top, two sticks down the middle. Spelled out as
	 * squares rather than as a recipe id, because what is being tested is the real grid.
	 */
	private static final List<Placement> WOODEN_PICKAXE = List.of(
			new Placement(1, Items.OAK_PLANKS),
			new Placement(2, Items.OAK_PLANKS),
			new Placement(3, Items.OAK_PLANKS),
			new Placement(5, Items.STICK),
			new Placement(8, Items.STICK));

	/** A bow: sticks down the middle column, string down the right. Enchantable, and not a tool. */
	private static final List<Placement> BOW = List.of(
			new Placement(2, Items.STICK),
			new Placement(3, Items.STRING),
			new Placement(4, Items.STICK),
			new Placement(6, Items.STRING),
			new Placement(8, Items.STICK),
			new Placement(9, Items.STRING));

	private final List<String> failures = new ArrayList<>();

	/** One ingredient and the grid square it goes in. */
	private record Placement(int gridSlot, Item item) {
	}

	/** What a crafting result came out carrying: one enchantment, its level, and its own ceiling. */
	private record Roll(String enchantment, int level, int maxLevel) {
		@Override
		public String toString() {
			return enchantment + " " + level + " (of " + maxLevel + ")";
		}
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestDedicatedServerContext server = context.worldBuilder().createServer()) {
			try (TestDedicatedServerConnection connection = server.connect()) {
				connection.waitForChunksRender();
				TestPlayer player = new TestPlayer(context, server, connection);

				// Daylight and clear weather only so the screenshots are readable.
				server.runCommand("time set noon");
				server.runCommand("weather clear");

				scenario(context, "a-locked-unlock-crafts-a-plain-pickaxe",
						() -> aLockedUnlockCraftsAPlainPickaxe(context, player));
				scenario(context, "unlocking-while-connected-fills-the-output-slot",
						() -> unlockingWhileConnectedFillsTheOutputSlot(context, player));
				scenario(context, "taking-the-result-by-clicking-it",
						() -> takingTheResultByClickingIt(player));
				scenario(context, "taking-the-result-by-shift-clicking-it",
						() -> takingTheResultByShiftClickingIt(player));
				scenario(context, "the-recipe-book-gives-the-same-enchanted-item",
						() -> theRecipeBookGivesTheSameEnchantedItem(context, player));
				scenario(context, "jiggling-the-grid-is-not-a-reroll",
						() -> jigglingTheGridIsNotAReroll(player));
				scenario(context, "crafting-one-and-setting-up-the-next-is-a-new-roll",
						() -> craftingOneAndSettingUpTheNextIsANewRoll(player));
				scenario(context, "a-higher-unlock-level-rolls-higher",
						() -> aHigherUnlockLevelRollsHigher(player));
				scenario(context, "a-bow-in-the-same-grid-stays-plain",
						() -> aBowInTheSameGridStaysPlain(player));
				scenario(context, "a-balance-reload-changes-the-curve",
						() -> aBalanceReloadChangesTheCurve(player));
			}
		}

		if (!failures.isEmpty()) {
			throw new AssertionError(failures.size() + " crafted-enchant scenario(s) failed:\n  "
					+ String.join("\n  ", failures));
		}
		LOGGER.info("All crafted-enchant client scenarios passed.");
	}

	// --- the scenarios ---------------------------------------------------------------------

	/** Nothing is enchanted until the unlock is bought, and the output slot says so. */
	private void aLockedUnlockCraftsAPlainPickaxe(ClientGameTestContext context, TestPlayer player) {
		start(player);
		layOut(player, WOODEN_PICKAXE);

		ItemStack result = player.menuItem(RESULT_SLOT);
		check(result.is(Items.WOODEN_PICKAXE),
				"setup: the grid should have made a wooden pickaxe, and the output slot holds " + result);
		check(!result.isEnchanted(),
				"a wooden pickaxe crafted while the unlock is locked must come out plain, and the"
						+ " output slot holds " + describe(result));
		check(!player.clientMenuItem(RESULT_SLOT).isEnchanted(),
				"the client must be shown a plain result too, and it shows "
						+ describe(player.clientMenuItem(RESULT_SLOT)));
		showOff(player, context, "crafting-result-plain");

		takeByClick(player);
		ItemStack taken = player.inventoryStack(Items.WOODEN_PICKAXE);
		check(taken.is(Items.WOODEN_PICKAXE) && !taken.isEnchanted(),
				"the pickaxe must still be plain once it is out of the grid, and it is " + describe(taken));
		finish(player);
	}

	/**
	 * Buying the unlock reaches a client that is already standing at the table, and the very next
	 * recompute of the grid fills the output slot with an enchanted item — before anybody takes it.
	 */
	private void unlockingWhileConnectedFillsTheOutputSlot(
			ClientGameTestContext context, TestPlayer player) {
		start(player);
		layOut(player, WOODEN_PICKAXE);
		check(!player.menuItem(RESULT_SLOT).isEnchanted(), "setup: the result should start plain");

		player.command("mhr unlock player.craft.enchant");
		jiggle(player);

		ItemStack result = player.menuItem(RESULT_SLOT);
		check(result.is(Items.WOODEN_PICKAXE),
				"the grid should still be making a wooden pickaxe, and it holds " + result);
		check(result.isEnchanted(),
				"the output slot must hold the enchanted pickaxe before it is taken, and it holds "
						+ describe(result));
		Roll rolled = roll(result);
		check(rolled.level() == 1,
				"one unlock level is worth the first level of the enchantment, and the roll is " + rolled);

		ItemStack seen = player.clientMenuItem(RESULT_SLOT);
		check(seen.isEnchanted(),
				"the player has to be able to see it, and the client's copy of the output slot holds "
						+ describe(seen));
		check(describe(seen).equals(describe(result)),
				"the client shows " + describe(seen) + " where the server has " + describe(result));
		showOff(player, context, "crafting-result-enchanted");

		finish(player);
	}

	/** The plain way of taking it out: pick it up on the cursor and put it down. */
	private void takingTheResultByClickingIt(TestPlayer player) {
		start(player);
		player.command("mhr unlock player.craft.enchant");
		layOut(player, WOODEN_PICKAXE);

		String previewed = describe(player.menuItem(RESULT_SLOT));
		check(player.menuItem(RESULT_SLOT).isEnchanted(),
				"setup: the output slot should be enchanted before the click, and it is " + previewed);

		player.clickSlot(RESULT_SLOT);
		ItemStack onCursor = player.cursor();
		check(onCursor.is(Items.WOODEN_PICKAXE),
				"clicking the output slot should put the pickaxe on the cursor, and the cursor holds "
						+ onCursor);
		check(describe(onCursor).equals(previewed),
				"the pickaxe taken by a click must be the one the output slot showed, which was "
						+ previewed + ", and it is " + describe(onCursor));

		player.clickSlot(player.menuSlotForInventory(9));
		ItemStack stored = player.inventoryStack(Items.WOODEN_PICKAXE);
		check(describe(stored).equals(previewed),
				"the pickaxe in the inventory must still be " + previewed + ", and it is "
						+ describe(stored));
		check(player.reachableCount(Items.WOODEN_PICKAXE) == 1,
				"taking the result must not duplicate it, and the pickaxes are at:"
						+ player.whereItIs(Items.WOODEN_PICKAXE));
		finish(player);
	}

	/** The other way: shift-click, which never puts anything on the cursor. */
	private void takingTheResultByShiftClickingIt(TestPlayer player) {
		start(player);
		player.command("mhr unlock player.craft.enchant");
		layOut(player, WOODEN_PICKAXE);

		String previewed = describe(player.menuItem(RESULT_SLOT));
		check(player.menuItem(RESULT_SLOT).isEnchanted(),
				"setup: the output slot should be enchanted before the shift-click, and it is " + previewed);

		player.shiftClickSlot(RESULT_SLOT);

		check(player.cursor().isEmpty(),
				"a shift-click must not leave anything on the cursor, and it holds " + player.cursor());
		ItemStack stored = player.inventoryStack(Items.WOODEN_PICKAXE);
		check(describe(stored).equals(previewed),
				"the pickaxe shift-clicked into the inventory must be " + previewed + ", and it is "
						+ describe(stored));
		check(player.menuItem(RESULT_SLOT).isEmpty(),
				"the grid held one set of ingredients, so the output slot should be empty afterwards");
		check(player.reachableCount(Items.WOODEN_PICKAXE) == 1,
				"a shift-click must not duplicate the pickaxe, and they are at:"
						+ player.whereItIs(Items.WOODEN_PICKAXE));
		finish(player);
	}

	/**
	 * The third way a player gets an item out of a crafting table: let the recipe book fill the grid
	 * and take the result.
	 *
	 * <p>The recipe is chosen the way the book chooses it — by asking the client's own recipe book
	 * which entry makes a wooden pickaxe — and placed through {@code handlePlaceRecipe}, which is
	 * the method the recipe-book button calls. The mouse does not travel to the button itself:
	 * the book's own widgets are laid out by a paged component with no stable handle on a single
	 * recipe, and clicking whatever happens to be in that spot would be a test of the page layout.
	 * Everything after that call is the real path, including the server round trip.
	 */
	private void theRecipeBookGivesTheSameEnchantedItem(
			ClientGameTestContext context, TestPlayer player) {
		start(player);
		player.command("mhr unlock player.craft.enchant");
		// A recipe has to be known before the book can place it. A player learns this one by
		// picking up planks; nothing has been picked up here.
		player.command("recipe give Player0 minecraft:wooden_pickaxe");
		player.giveAt(0, Items.OAK_PLANKS, 3);
		player.giveAt(1, Items.STICK, 2);

		int recipe = context.computeOnClient(client -> {
			ContextMap slotContext = SlotDisplayContext.fromLevel(client.level);
			for (var collection : client.player.getRecipeBook().getCollections()) {
				for (RecipeDisplayEntry entry : collection.getRecipes()) {
					List<ItemStack> results = entry.resultItems(slotContext);
					if (results.size() == 1 && results.getFirst().is(Items.WOODEN_PICKAXE)) {
						return entry.id().index();
					}
				}
			}
			return -1;
		});
		check(recipe >= 0, "the client's recipe book has no entry making a wooden pickaxe");

		context.runOnClient(client -> client.gameMode.handlePlaceRecipe(
				client.player.containerMenu.containerId, new RecipeDisplayId(recipe), false));
		player.settle();

		ItemStack result = player.menuItem(RESULT_SLOT);
		check(result.is(Items.WOODEN_PICKAXE),
				"the recipe book should have filled the grid with a pickaxe recipe, and the output"
						+ " slot holds " + result);
		String previewed = describe(result);
		check(result.isEnchanted(),
				"a grid filled by the recipe book must give an enchanted result too, and it gives "
						+ previewed);

		player.shiftClickSlot(RESULT_SLOT);
		ItemStack stored = player.inventoryStack(Items.WOODEN_PICKAXE);
		check(describe(stored).equals(previewed),
				"the pickaxe taken from a recipe-book craft must be " + previewed + ", and it is "
						+ describe(stored));
		finish(player);
	}

	/**
	 * Pulling an ingredient out and putting it back recomputes the result, and must give the same
	 * one every time. Otherwise the grid is a reroll button.
	 */
	private void jigglingTheGridIsNotAReroll(TestPlayer player) {
		start(player);
		player.command("mhr unlock player.craft.enchant 2");
		layOut(player, WOODEN_PICKAXE);

		String first = describe(player.menuItem(RESULT_SLOT));
		check(player.menuItem(RESULT_SLOT).isEnchanted(),
				"setup: the output slot should be enchanted to begin with, and it is " + first);

		for (int attempt = 1; attempt <= 6; attempt++) {
			jiggle(player);
			String now = describe(player.menuItem(RESULT_SLOT));
			check(now.equals(first),
					"jiggle " + attempt + " of the grid turned " + first + " into " + now
							+ ", so taking an ingredient out and back is a free reroll");
		}
		finish(player);
	}

	/** Actually crafting one does move the roll on, so the next item is its own. */
	private void craftingOneAndSettingUpTheNextIsANewRoll(TestPlayer player) {
		start(player);
		player.command("mhr unlock player.craft.enchant 4");

		layOut(player, WOODEN_PICKAXE);
		String first = describe(player.menuItem(RESULT_SLOT));
		player.shiftClickSlot(RESULT_SLOT);

		// The draw is weighted, so two crafts in a row can land on the same enchantment by chance.
		// A seed that had stopped moving would give the same answer every single time, which is what
		// this is looking for — not a difference on any one particular craft.
		List<String> seen = new ArrayList<>(List.of(first));
		boolean moved = false;
		for (int craft = 0; craft < 12 && !moved; craft++) {
			layOut(player, WOODEN_PICKAXE);
			String now = describe(player.menuItem(RESULT_SLOT));
			seen.add(now);
			moved = !now.equals(first);
			if (!moved) {
				player.shiftClickSlot(RESULT_SLOT);
			}
		}

		check(moved, "crafting a pickaxe and setting the next one up gave " + first
				+ " every time over " + seen.size() + " crafts, so the roll is not moving on: " + seen);
		finish(player);
	}

	/**
	 * A higher unlock level lets the same enchantments roll higher — at the top level, as high as
	 * the enchantment itself goes.
	 */
	private void aHigherUnlockLevelRollsHigher(TestPlayer player) {
		start(player);
		player.command("mhr unlock player.craft.enchant 4");

		// Silk Touch only ever goes to one, so a roll of it cannot tell a maxed unlock from a bare
		// one. Craft until something with room to grow turns up.
		Roll rolled = null;
		for (int craft = 0; craft < 10 && rolled == null; craft++) {
			layOut(player, WOODEN_PICKAXE);
			Roll candidate = roll(player.menuItem(RESULT_SLOT));
			check(candidate.level() == candidate.maxLevel(),
					"a maxed unlock must roll every enchantment at its own maximum, and this one is "
							+ candidate);
			if (candidate.maxLevel() > 1) {
				rolled = candidate;
			} else {
				player.shiftClickSlot(RESULT_SLOT);
			}
		}
		check(rolled != null,
				"ten crafts in a row rolled a one-level enchantment, so nothing here proved that a"
						+ " maxed unlock rolls higher than an unmaxed one");
		finish(player);
	}

	/**
	 * The same real grid, a different recipe: a bow is enchantable and has an enchanting-table pool
	 * of its own, and it still comes out exactly as vanilla makes it.
	 */
	private void aBowInTheSameGridStaysPlain(TestPlayer player) {
		start(player);
		player.command("mhr unlock player.craft.enchant 4");

		layOut(player, BOW);
		ItemStack bow = player.menuItem(RESULT_SLOT);
		check(bow.is(Items.BOW), "setup: the grid should have made a bow, and it holds " + bow);
		check(!bow.isEnchanted(),
				"the unlock covers tools and swords, so a crafted bow must come out plain, and it is "
						+ describe(bow));
		clearGrid(player);

		// The control: with the unlock off, everything is plain and the assertion above would hold
		// for the wrong reason.
		layOut(player, WOODEN_PICKAXE);
		check(player.menuItem(RESULT_SLOT).isEnchanted(),
				"the unlock was supposed to be on: a pickaxe crafted in the same grid came out "
						+ describe(player.menuItem(RESULT_SLOT)));
		finish(player);
	}

	/**
	 * The progression curve is balance data, not code. Editing the file and running
	 * {@code mhr reload} has to land on the next item crafted, with nothing rebuilt or restarted.
	 */
	private void aBalanceReloadChangesTheCurve(TestPlayer player) {
		start(player);
		player.command("mhr unlock player.craft.enchant 1");

		layOut(player, WOODEN_PICKAXE);
		Roll shipped = roll(player.menuItem(RESULT_SLOT));
		check(shipped.level() == 1,
				"setup: the shipped curve gives level one for one unlock level, and this is " + shipped);
		clearGrid(player);

		try {
			// One unlock level worth the enchantment's whole range instead of a quarter of it.
			overrideBalance(player, "{\"vanillaPlus\": {\"craftEnchant\": {\"strengthPerLevel\": 1.0}}}");
			player.command("mhr reload");

			layOut(player, WOODEN_PICKAXE);
			Roll retuned = roll(player.menuItem(RESULT_SLOT));
			check(retuned.enchantment().equals(shipped.enchantment()),
					"setup: nothing was crafted in between, so the same enchantment should have been"
							+ " rolled. It went from " + shipped + " to " + retuned);
			check(retuned.level() == retuned.maxLevel() && retuned.level() > shipped.level(),
					"a reloaded curve must reach the next item crafted: " + shipped + " should have"
							+ " become the enchantment's own maximum, and it is " + retuned);
			clearGrid(player);
		} finally {
			removeBalanceOverride(player);
			player.command("mhr reload");
		}

		layOut(player, WOODEN_PICKAXE);
		Roll back = roll(player.menuItem(RESULT_SLOT));
		check(back.level() == 1,
				"removing the override and reloading must put the shipped curve back, and the roll is "
						+ back);
		finish(player);
	}

	/**
	 * Photographs the output slot with the pointer resting on it, so the tooltip spells out what is
	 * in there — the item's name and the enchantment under it.
	 *
	 * <p>The picture is a debugging aid and a thing to show somebody; the assertions above it are
	 * the proof. Nothing here compares pixels.
	 */
	private void showOff(TestPlayer player, ClientGameTestContext context, String name) {
		player.moveCursorToSlot(RESULT_SLOT);
		// Joining a server throws up a couple of toasts, and they sit exactly where the tooltip does.
		context.runOnClient(client -> client.gui.toastManager().clear());
		context.waitTicks(2);
		context.takeScreenshot(name);
	}

	// --- working the crafting table ---------------------------------------------------------

	/** Nothing owned, nothing carried, an empty grid and an open crafting table. */
	private void start(TestPlayer player) {
		player.reset(CRAFT_UNLOCKS);
		player.openCraftingTable();
		clearGrid(player);
	}

	/** Leaves the table the way it was found, so the next scenario starts from nothing. */
	private void finish(TestPlayer player) {
		clearGrid(player);
		player.closeInventory();
	}

	/**
	 * Carries one set of ingredients into the grid with the real mouse.
	 *
	 * <p>Each ingredient gets an inventory square of its own holding exactly one, so every move is a
	 * plain left-click pair — pick the one up, put it down — rather than a stack split. What is
	 * being tested is the result, not the player's dexterity.
	 */
	private void layOut(TestPlayer player, List<Placement> recipe) {
		for (int i = 0; i < recipe.size(); i++) {
			player.giveAt(i, recipe.get(i).item());
		}
		for (int i = 0; i < recipe.size(); i++) {
			player.clickSlot(player.menuSlotForInventory(i));
			player.clickSlot(recipe.get(i).gridSlot());
		}
		if (!player.cursor().isEmpty()) {
			throw new AssertionError("laying out the recipe left " + player.cursor() + " on the cursor");
		}
	}

	/** Pulls one ingredient out of the grid and puts it straight back, recomputing the result. */
	private void jiggle(TestPlayer player) {
		int filled = firstFilledGridSlot(player);
		player.clickSlot(filled);
		check(!player.cursor().isEmpty(), "the jiggle should have picked an ingredient up");
		player.clickSlot(filled);
		check(player.cursor().isEmpty(), "the jiggle should have put the ingredient back");
	}

	/** Takes the result onto the cursor and puts it in the inventory, the ordinary way. */
	private void takeByClick(TestPlayer player) {
		player.clickSlot(RESULT_SLOT);
		player.clickSlot(player.menuSlotForInventory(9));
	}

	/** Empties the grid back into the inventory, so nothing is left making a result. */
	private void clearGrid(TestPlayer player) {
		for (int slot = FIRST_GRID_SLOT; slot <= LAST_GRID_SLOT; slot++) {
			if (!player.menuItem(slot).isEmpty()) {
				player.shiftClickSlot(slot);
			}
		}
		ItemStack left = player.menuItem(RESULT_SLOT);
		if (!left.isEmpty()) {
			throw new AssertionError("the grid should be empty now, but the output slot holds " + left);
		}
	}

	private int firstFilledGridSlot(TestPlayer player) {
		for (int slot = FIRST_GRID_SLOT; slot <= LAST_GRID_SLOT; slot++) {
			if (!player.menuItem(slot).isEmpty()) {
				return slot;
			}
		}
		throw new AssertionError("the crafting grid is empty, so there is nothing to jiggle");
	}

	// --- the balance file --------------------------------------------------------------------

	private void overrideBalance(TestPlayer player, String json) {
		player.onServer(unused -> {
			try {
				Path file = BalanceManager.overrideFile();
				Files.createDirectories(file.getParent());
				Files.writeString(file, json, StandardCharsets.UTF_8);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		});
	}

	private void removeBalanceOverride(TestPlayer player) {
		player.onServer(unused -> {
			try {
				Files.deleteIfExists(BalanceManager.overrideFile());
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		});
	}

	// --- reading a result ----------------------------------------------------------------------

	/** What is on a stack, in a form two results can be compared by and a human can read. */
	private static String describe(ItemStack stack) {
		if (stack.isEmpty()) {
			return "nothing";
		}
		ItemEnchantments enchantments = EnchantmentHelper.getEnchantmentsForCrafting(stack);
		if (enchantments.isEmpty()) {
			return "a plain " + stack.getItem();
		}
		List<String> parts = new ArrayList<>();
		for (Holder<Enchantment> enchantment : enchantments.keySet()) {
			parts.add(enchantment.getRegisteredName() + " " + enchantments.getLevel(enchantment));
		}
		parts.sort(String::compareTo);
		return stack.getItem() + " with " + String.join(", ", parts);
	}

	/** The one enchantment a crafted result carries, its level and the enchantment's own ceiling. */
	private static Roll roll(ItemStack stack) {
		ItemEnchantments enchantments = EnchantmentHelper.getEnchantmentsForCrafting(stack);
		if (enchantments.size() != 1) {
			throw new AssertionError("a crafted result carries exactly one enchantment, and this is "
					+ describe(stack));
		}
		Holder<Enchantment> enchantment = enchantments.keySet().iterator().next();
		return new Roll(enchantment.getRegisteredName(), enchantments.getLevel(enchantment),
				enchantment.value().getMaxLevel());
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
