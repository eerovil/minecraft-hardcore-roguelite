package fi.vilpponen.mhr.enchant;

import fi.vilpponen.mhr.HardcoreRoguelite;
import fi.vilpponen.mhr.core.Balance;
import fi.vilpponen.mhr.core.BalanceManager;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;

/**
 * What the crafted-tool enchant is worth, and what it may touch — all of it data.
 *
 * <p>Nothing here is a number. The two knobs come from {@code default-balance.json}, checked as the
 * balance loads and read again at the moment they are used, so a balance edit plus
 * {@code /mhr reload} changes them mid-game while a bad one never becomes the balance in effect.
 * The two sets — which items count and which enchantments can turn up — are tags, so a datapack can
 * widen or replace either without the mod being rebuilt.
 *
 * <p>See {@code docs/balance.md}.
 */
public final class CraftEnchantBalance {
	/**
	 * Which crafted items are eligible. Shipped as {@code hardcore_roguelite:craft_enchantable},
	 * which points at the vanilla tool tags, so a new pickaxe material is covered on its own and a
	 * datapack can add bows or armour without touching Java.
	 */
	public static final TagKey<Item> SUPPORTED_ITEMS = TagKey.create(Registries.ITEM,
			Identifier.fromNamespaceAndPath(HardcoreRoguelite.MOD_ID, "craft_enchantable"));

	/**
	 * What may be rolled. Shipped as {@code hardcore_roguelite:craft_enchant_pool}, which points at
	 * the enchanting table's own pool — so treasure enchantments and curses are out — and is equally
	 * a datapack's to change.
	 */
	static final TagKey<Enchantment> POOL = TagKey.create(Registries.ENCHANTMENT,
			Identifier.fromNamespaceAndPath(HardcoreRoguelite.MOD_ID, "craft_enchant_pool"));

	private CraftEnchantBalance() {
	}

	/** Whether a crafted item is one of the kinds the unlock covers at all. */
	public static boolean supports(ItemStack stack) {
		return stack.is(SUPPORTED_ITEMS);
	}

	/** How many times the unlock can be bought. */
	public static int maxUnlockLevel() {
		return BalanceManager.get().craftEnchant().maxUnlockLevel();
	}

	/**
	 * The level curve: how high an enchantment may roll at a given unlock level.
	 *
	 * <p>Both numbers behind it are read here rather than kept, so an edit to the balance file plus
	 * {@code /mhr reload} lands on the next item crafted.
	 */
	public static int enchantmentLevel(int unlockLevel, int maxEnchantmentLevel) {
		Balance.CraftEnchantBalance tuning = BalanceManager.get().craftEnchant();
		return enchantmentLevel(unlockLevel, maxEnchantmentLevel,
				tuning.maxUnlockLevel(), tuning.strengthPerLevel());
	}

	/**
	 * The curve itself, with the two balance numbers handed in.
	 *
	 * <p>Each unlock level is worth {@code strengthPerLevel} of the enchantment's own maximum, so the
	 * curve is proportional rather than a fixed table — enchantments differ in how high they go,
	 * Efficiency reaching 5, Fortune 3, Silk Touch 1, and every one of them should run the full range
	 * of its own levels by the time the unlock is maxed. Never less than 1: a rolled enchantment
	 * always does something.
	 *
	 * <p>Shipped at four levels worth a quarter each; halving the levels and doubling the strength is
	 * a two-line edit to {@code default-balance.json}.
	 */
	static int enchantmentLevel(int unlockLevel, int maxEnchantmentLevel, int maxUnlockLevel,
			double strengthPerLevel) {
		int owned = Math.clamp(unlockLevel, 0, maxUnlockLevel);
		int scaled = (int) Math.floor(maxEnchantmentLevel * strengthPerLevel * owned);
		return Math.clamp(scaled, 1, maxEnchantmentLevel);
	}
}
