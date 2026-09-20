package fi.vilpponen.mhr.shop;

import net.minecraft.world.item.ItemStack;

/**
 * What an offer actually hands over, as the balance in effect says right now.
 *
 * <p>Only the starter items have one: the whole of what they give is a stack in the catalogue, and
 * that stack is balance data an override is allowed to replace. So the shop must not describe it
 * from anywhere else. It used to — an icon in {@code shop-layout.json} and a name and a count
 * written into the language file — and a retuned catalogue left the screen promising sixteen bread
 * while the chest held sixty-four.
 *
 * <p>The item carries one of whatever it is, so components like enchantments come with it and the
 * screen can draw the real thing. The count is separate rather than in the stack because a starter
 * item may ask for more than a stack holds — "128 bread" is two slots' worth, and that is a number
 * to show, not a stack to build.
 *
 * @param item one of the item, for drawing and naming, or empty when the offer gives no item
 * @param count how many the chest will hold, zero when there is no item
 */
public record Reward(ItemStack item, int count) {
	/** For every offer whose effect is code rather than an item, which is most of them. */
	public static final Reward NONE = new Reward(ItemStack.EMPTY, 0);

	public boolean isSomething() {
		return !item.isEmpty();
	}
}
