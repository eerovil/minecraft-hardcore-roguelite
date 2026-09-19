package fi.vilpponen.mhr.enchant;

import fi.vilpponen.mhr.Unlock;
import fi.vilpponen.mhr.UnlockState;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;

/**
 * Newly crafted tools come out enchanted once the {@link Unlock#CRAFT_ENCHANT} unlock is bought.
 *
 * <p>This runs on the crafting <em>result</em>, before the player takes it, so the enchantment is
 * visible in the output slot and it does not matter whether the item is clicked out, shift-clicked
 * or picked up by the recipe book — there is one place where a crafting result is built.
 *
 * <p>Nothing here is a list of items and enchantments. The pool is a tag, filtered by what the game
 * says fits the item, so an enchantment that cannot go on a pickaxe can never be rolled for one.
 * Which items count, what may be rolled and how the levels scale all live in
 * {@link CraftEnchantBalance}, and none of it is written down in Java.
 */
public final class CraftEnchant {
	private CraftEnchant() {
	}

	/**
	 * Enchant a crafting result in place, if it is eligible and the unlock is owned.
	 *
	 * <p>Called every time the result is recomputed, which happens on every change to the grid. The
	 * roll is therefore seeded rather than free-running: while the same ingredients sit in the grid
	 * the answer stays the same, so the preview does not flicker and taking the item out and putting
	 * it back is not a way to reroll. The seed moves on once an item is actually crafted.
	 */
	public static void enchantCrafted(ServerPlayer player, ItemStack result, RegistryAccess registries) {
		int unlockLevel = UnlockState.get().level(Unlock.CRAFT_ENCHANT);
		if (unlockLevel <= 0 || result.isEmpty() || result.isEnchanted() || !result.isEnchantable()) {
			return;
		}
		if (!CraftEnchantBalance.supports(result)) {
			return;
		}

		List<Holder<Enchantment>> candidates = candidates(result, registries);
		if (candidates.isEmpty()) {
			return;
		}

		RandomSource random = RandomSource.create(seed(player, result));
		Holder<Enchantment> chosen = pick(candidates, random);
		int level = CraftEnchantBalance.enchantmentLevel(unlockLevel, chosen.value().getMaxLevel());
		result.enchant(chosen, level);
	}

	/** Every enchantment in the configured pool that the game considers a normal fit for this item. */
	static List<Holder<Enchantment>> candidates(ItemStack stack, RegistryAccess registries) {
		List<Holder<Enchantment>> candidates = new ArrayList<>();
		for (Holder<Enchantment> holder : registries.lookupOrThrow(Registries.ENCHANTMENT)
				.getTagOrEmpty(CraftEnchantBalance.POOL)) {
			Enchantment enchantment = holder.value();
			// isPrimaryItem is the enchanting table's own question: not merely allowed on the item
			// (an anvil would take more), but the natural fit for it.
			if (enchantment.isPrimaryItem(stack) && enchantment.getWeight() > 0) {
				candidates.add(holder);
			}
		}
		return candidates;
	}

	/**
	 * A weighted draw using the enchantments' own rarity weights, so Efficiency turns up often and
	 * Silk Touch rarely, the way the enchanting table already feels.
	 */
	static Holder<Enchantment> pick(List<Holder<Enchantment>> candidates, RandomSource random) {
		int total = 0;
		for (Holder<Enchantment> candidate : candidates) {
			total += candidate.value().getWeight();
		}

		int roll = random.nextInt(total);
		for (Holder<Enchantment> candidate : candidates) {
			roll -= candidate.value().getWeight();
			if (roll < 0) {
				return candidate;
			}
		}
		return candidates.getLast();
	}

	private static long seed(ServerPlayer player, ItemStack result) {
		// The crafted-item statistic is what makes the next one a different roll: it only moves when
		// an item really leaves the output slot.
		int crafted = player.getStats().getValue(Stats.ITEM_CRAFTED, result.getItem());
		long item = BuiltInRegistries.ITEM.getId(result.getItem());
		return player.getUUID().getLeastSignificantBits() * 31L + crafted * 1000003L + item;
	}
}
