package fi.vilpponen.mhr.shop.client;

import fi.vilpponen.mhr.shop.Reward;
import fi.vilpponen.mhr.shop.ShopLayout;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Turns the item id {@link ShopLayout} hands out into something the screen can draw.
 *
 * <p>The one line of the presentation layer that needs the game, kept apart from the rest of it so
 * the arrangement stays testable without starting Minecraft.
 *
 * <p>An id the game does not have is drawn as a barrier rather than as nothing. A missing icon is a
 * mistake in {@code shop-layout.json} and should look like one; an invisible square would look like
 * the shop is broken instead.
 *
 * <p>An offer that hands over an item does not come here at all: it is drawn as the thing the server
 * says it will give, which is the only copy of that fact there is.
 */
public final class ShopIcons {
	private ShopIcons() {
	}

	public static ItemStack stackFor(String unlockId) {
		Reward reward = SyncedShop.reward(unlockId);
		if (reward.isSomething()) {
			return reward.item();
		}
		Identifier id = Identifier.tryParse(ShopLayout.iconId(unlockId));
		Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
		return item == null ? new ItemStack(Items.BARRIER) : new ItemStack(item);
	}
}
