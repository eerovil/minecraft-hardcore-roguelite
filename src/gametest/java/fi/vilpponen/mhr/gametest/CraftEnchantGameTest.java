package fi.vilpponen.mhr.gametest;

import fi.vilpponen.mhr.Unlock;
import fi.vilpponen.mhr.UnlockState;
import fi.vilpponen.mhr.enchant.CraftEnchant;
import fi.vilpponen.mhr.enchant.CraftEnchantBalance;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.GameType;

/**
 * The half of the crafted-tool enchant that needs no client: what may be rolled, on what, and how
 * high.
 *
 * <p>These are the fast checks. Each one builds a crafting result the way the mixin does — a fresh
 * stack handed to {@link CraftEnchant#enchantCrafted} with a real server player and real registries
 * — and then asks what came out. That covers the rules across dozens of items and every unlock
 * level in seconds, which a real client cannot do: a single craft in a real crafting screen costs
 * a second or two of ticks.
 *
 * <p>The real crafting paths — the grid, the output slot, the click and the shift-click — live in
 * {@link fi.vilpponen.mhr.gametest.client.CraftEnchantClientTest}, because only a client can press
 * them.
 *
 * <p>Every scenario sets the unlock level itself rather than inheriting whatever the last one left,
 * so they can run in any order. See {@code docs/dev-environment.md} for how to run them.
 */
public final class CraftEnchantGameTest {
	/**
	 * A spread of the things the unlock covers: every tool kind, and more than one material of
	 * each, so a tag that lost a whole family shows up here rather than in a single lucky item.
	 */
	private static final List<Item> SUPPORTED = List.of(
			Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE,
			Items.NETHERITE_PICKAXE,
			Items.WOODEN_SWORD, Items.IRON_SWORD, Items.DIAMOND_SWORD,
			Items.WOODEN_AXE, Items.IRON_AXE, Items.DIAMOND_AXE,
			Items.WOODEN_SHOVEL, Items.IRON_SHOVEL, Items.DIAMOND_SHOVEL,
			Items.WOODEN_HOE, Items.IRON_HOE, Items.DIAMOND_HOE);

	/**
	 * Things that must come out of the grid exactly as vanilla makes them.
	 *
	 * <p>Half of these are enchantable and have an enchanting-table pool of their own — a bow, a
	 * trident, a fishing rod, armour — so the only reason they stay plain is that the unlock's item
	 * tag does not name them. The other half could not be enchanted anyway, and is here so that a
	 * rule which started enchanting *everything* cannot pass.
	 */
	private static final List<Item> UNSUPPORTED = List.of(
			Items.BOW, Items.CROSSBOW, Items.TRIDENT, Items.FISHING_ROD, Items.SHEARS,
			Items.IRON_HELMET, Items.DIAMOND_CHESTPLATE, Items.SHIELD,
			Items.OAK_PLANKS, Items.CHEST, Items.TORCH, Items.STICK);

	/**
	 * How many rolls each many-rolls scenario asks for. Enough to reach the rare enchantments — Silk
	 * Touch is about one draw in eighteen.
	 *
	 * <p>They are only forty draws if the seed moves between them, which is why those loops go
	 * through {@link #craftAndTake}: the roll is seeded so that the same grid keeps giving the same
	 * answer, so forty calls that skipped the taking would be one answer repeated forty times.
	 */
	private static final int ROLLS = 40;

	// --- what comes out at all ---------------------------------------------------------------

	/** Nothing is enchanted until the unlock is bought. */
	@GameTest
	public void lockedUnlockLeavesEveryToolPlain(GameTestHelper helper) {
		UnlockState.get().setLevel(Unlock.CRAFT_ENCHANT, 0);
		ServerPlayer player = (ServerPlayer) helper.makeMockServerPlayer(GameType.SURVIVAL);

		for (Item item : SUPPORTED) {
			ItemStack result = craft(helper, player, item);
			helper.assertFalse(result.isEnchanted(),
					"a crafted " + name(item) + " must come out plain while the unlock is locked,"
							+ " but it came out as " + describe(result));
		}
		helper.succeed();
	}

	/** Buy one level and every tool kind comes out with something on it. */
	@GameTest
	public void everySupportedToolComesOutEnchanted(GameTestHelper helper) {
		UnlockState.get().setLevel(Unlock.CRAFT_ENCHANT, 1);
		ServerPlayer player = (ServerPlayer) helper.makeMockServerPlayer(GameType.SURVIVAL);

		for (Item item : SUPPORTED) {
			ItemStack result = craft(helper, player, item);
			helper.assertTrue(result.isEnchanted(),
					"a crafted " + name(item) + " must come out enchanted once the unlock is owned,"
							+ " and it came out plain");
		}
		helper.succeed();
	}

	/**
	 * Everything else stays exactly as vanilla makes it, even the things that <em>could</em> be
	 * enchanted.
	 */
	@GameTest
	public void unsupportedItemsStayPlain(GameTestHelper helper) {
		UnlockState.get().setLevel(Unlock.CRAFT_ENCHANT, CraftEnchantBalance.maxUnlockLevel());
		ServerPlayer player = (ServerPlayer) helper.makeMockServerPlayer(GameType.SURVIVAL);

		for (Item item : UNSUPPORTED) {
			for (int roll = 0; roll < 4; roll++) {
				ItemStack result = craftAndTake(helper, player, item);
				helper.assertFalse(result.isEnchanted(),
						"the unlock covers tools and swords, so a crafted " + name(item)
								+ " must come out plain, but it came out as " + describe(result));
			}
		}
		helper.succeed();
	}

	// --- what may be rolled -------------------------------------------------------------------

	/**
	 * Nothing impossible ever lands: no Sharpness on a pickaxe, no treasure enchantment, no curse,
	 * and never two at once.
	 *
	 * <p>This is the scenario the manual check *"nothing impossible ever lands"* used to be. It is
	 * worth many rolls rather than one, because the draw is weighted — Silk Touch turns up about
	 * one time in eighteen, so a single roll per item would almost never look at it.
	 */
	@GameTest
	public void onlyEnchantmentsThatFitTheItemAreRolled(GameTestHelper helper) {
		UnlockState.get().setLevel(Unlock.CRAFT_ENCHANT, CraftEnchantBalance.maxUnlockLevel());
		ServerPlayer player = (ServerPlayer) helper.makeMockServerPlayer(GameType.SURVIVAL);

		for (Item item : SUPPORTED) {
			Set<String> seen = new HashSet<>();
			for (int roll = 0; roll < ROLLS; roll++) {
				ItemStack result = craftAndTake(helper, player, item);
				ItemEnchantments enchantments = EnchantmentHelper.getEnchantmentsForCrafting(result);
				seen.add(describe(result));

				helper.assertValueEqual(enchantments.size(), 1,
						"a crafted " + name(item) + " carries exactly one enchantment, and this one is "
								+ describe(result));

				for (Holder<Enchantment> enchantment : enchantments.keySet()) {
					String what = describe(result) + " on a crafted " + name(item);
					helper.assertTrue(enchantment.value().isPrimaryItem(new ItemStack(item)),
							what + " is not an enchantment the game considers a fit for it");
					helper.assertTrue(enchantment.is(EnchantmentTags.IN_ENCHANTING_TABLE),
							what + " is outside the enchanting table's own pool");
					helper.assertFalse(enchantment.is(EnchantmentTags.TREASURE),
							what + " is a treasure enchantment, which the unlock must never hand out");
					helper.assertFalse(enchantment.is(EnchantmentTags.CURSE),
							what + " is a curse");
				}
			}

			// Every tool in the list has more than one enchantment that fits it, so forty real draws
			// cannot all land on the same one. This is what says the loop above sampled forty times
			// rather than asking one seeded roll the same question forty times over.
			helper.assertTrue(seen.size() > 1,
					ROLLS + " rolls of a " + name(item) + " all came out as " + seen
							+ ", so the draw is not moving and the checks above looked at one"
							+ " enchantment rather than the whole pool");
		}
		helper.succeed();
	}

	// --- how high it rolls ---------------------------------------------------------------------

	/**
	 * The bottom of the curve. One level bought is worth a quarter of the enchantment's own range,
	 * which rounds down to the first level of everything.
	 */
	@GameTest
	public void oneUnlockLevelRollsTheWeakestLevel(GameTestHelper helper) {
		UnlockState.get().setLevel(Unlock.CRAFT_ENCHANT, 1);
		ServerPlayer player = (ServerPlayer) helper.makeMockServerPlayer(GameType.SURVIVAL);

		for (Item item : SUPPORTED) {
			for (int roll = 0; roll < ROLLS; roll++) {
				ItemStack result = craftAndTake(helper, player, item);
				for (Holder<Enchantment> enchantment : rolled(result)) {
					helper.assertValueEqual(levelOf(result, enchantment), 1,
							"the first unlock level is worth a quarter of the enchantment's range, so a"
									+ " crafted " + name(item) + " should carry a level-one enchantment"
									+ " and it carries " + describe(result));
				}
			}
		}
		helper.succeed();
	}

	/**
	 * The top of it. Every enchantment in the pool must be able to reach its own maximum by the time
	 * the unlock is maxed, which is the whole point of the curve being proportional.
	 */
	@GameTest
	public void theTopUnlockLevelRollsTheEnchantmentsOwnMaximum(GameTestHelper helper) {
		UnlockState.get().setLevel(Unlock.CRAFT_ENCHANT, CraftEnchantBalance.maxUnlockLevel());
		ServerPlayer player = (ServerPlayer) helper.makeMockServerPlayer(GameType.SURVIVAL);

		int seenAboveOne = 0;
		for (Item item : SUPPORTED) {
			for (int roll = 0; roll < ROLLS; roll++) {
				ItemStack result = craftAndTake(helper, player, item);
				for (Holder<Enchantment> enchantment : rolled(result)) {
					int max = enchantment.value().getMaxLevel();
					helper.assertValueEqual(levelOf(result, enchantment), max,
							"a maxed unlock must roll " + enchantment.getRegisteredName() + " at its own"
									+ " maximum of " + max + ", and a crafted " + name(item)
									+ " came out as " + describe(result));
					seenAboveOne += max > 1 ? 1 : 0;
				}
			}
		}

		// Silk Touch only goes to one, so "level equals the maximum" would be satisfied by a rule
		// that always rolled level one. This is what gives the assertion above its teeth.
		helper.assertTrue(seenAboveOne > 0,
				"no enchantment with more than one level turned up in " + (SUPPORTED.size() * ROLLS)
						+ " rolls, so the maximum-level check above proved nothing");
		helper.succeed();
	}

	// --- when the roll moves on -----------------------------------------------------------------

	/**
	 * The same grid gives the same answer. Recomputing a result — which happens on every change to
	 * the crafting grid — must never be a free reroll.
	 */
	@GameTest
	public void recomputingTheSameResultDoesNotReroll(GameTestHelper helper) {
		UnlockState.get().setLevel(Unlock.CRAFT_ENCHANT, CraftEnchantBalance.maxUnlockLevel());
		ServerPlayer player = (ServerPlayer) helper.makeMockServerPlayer(GameType.SURVIVAL);

		for (Item item : SUPPORTED) {
			String first = describe(craft(helper, player, item));
			for (int again = 0; again < 20; again++) {
				helper.assertValueEqual(describe(craft(helper, player, item)), first,
						"jiggling the grid must not reroll a crafted " + name(item));
			}
		}
		helper.succeed();
	}

	/**
	 * Actually crafting one does move the roll on.
	 *
	 * <p>The draw is weighted, so two rolls in a row can land on the same enchantment by chance.
	 * What is asserted is that a different one turns up <em>within</em> a run of crafts: the odds of
	 * twenty rolls all matching are about one in three million, and a seed that stopped moving would
	 * fail every time rather than rarely.
	 */
	@GameTest
	public void craftingOneMovesTheRollOn(GameTestHelper helper) {
		UnlockState.get().setLevel(Unlock.CRAFT_ENCHANT, CraftEnchantBalance.maxUnlockLevel());
		ServerPlayer player = (ServerPlayer) helper.makeMockServerPlayer(GameType.SURVIVAL);

		for (Item item : SUPPORTED) {
			String first = describe(craft(helper, player, item));
			boolean moved = false;
			for (int crafts = 0; crafts < 20 && !moved; crafts++) {
				take(player, item);
				moved = !describe(craft(helper, player, item)).equals(first);
			}
			helper.assertTrue(moved,
					"crafting a " + name(item) + " twenty times over always gave " + first
							+ ", so the roll is not moving on when an item is taken");
		}
		helper.succeed();
	}

	// --- plumbing --------------------------------------------------------------------------------

	/**
	 * One crafting result, built the way {@code CraftingResultEnchantMixin} builds it: a fresh stack
	 * of the item, enchanted in place before anybody takes it.
	 */
	private static ItemStack craft(GameTestHelper helper, ServerPlayer player, Item item) {
		ItemStack result = new ItemStack(item);
		CraftEnchant.enchantCrafted(player, result, helper.getLevel().registryAccess());
		return result;
	}

	/**
	 * One crafting result, and then taken out of the slot.
	 *
	 * <p>This is the independent draw. {@link #craft} on its own is the same roll every time while
	 * nothing is crafted — deliberately, that is the no-free-reroll rule — so a loop that wants
	 * different answers has to take each one before asking for the next.
	 */
	private static ItemStack craftAndTake(GameTestHelper helper, ServerPlayer player, Item item) {
		ItemStack result = craft(helper, player, item);
		take(player, item);
		return result;
	}

	/**
	 * Takes the result out of the output slot, as far as the roll is concerned.
	 *
	 * <p>The crafted-item statistic is the only thing the seed moves with, and vanilla awards it
	 * when a result really leaves the slot.
	 */
	private static void take(ServerPlayer player, Item item) {
		player.awardStat(Stats.ITEM_CRAFTED.get(item), 1);
	}

	private static Iterable<Holder<Enchantment>> rolled(ItemStack stack) {
		return EnchantmentHelper.getEnchantmentsForCrafting(stack).keySet();
	}

	private static int levelOf(ItemStack stack, Holder<Enchantment> enchantment) {
		return EnchantmentHelper.getEnchantmentsForCrafting(stack).getLevel(enchantment);
	}

	/** What is on a stack, in a form two rolls can be compared by and a human can read. */
	private static String describe(ItemStack stack) {
		ItemEnchantments enchantments = EnchantmentHelper.getEnchantmentsForCrafting(stack);
		if (enchantments.isEmpty()) {
			return "plain";
		}
		List<String> parts = new ArrayList<>();
		for (Holder<Enchantment> enchantment : enchantments.keySet()) {
			parts.add(enchantment.getRegisteredName() + " " + enchantments.getLevel(enchantment));
		}
		parts.sort(String::compareTo);
		return String.join(", ", parts);
	}

	private static String name(Item item) {
		return BuiltInRegistries.ITEM.getKey(item).toString();
	}
}
