package fi.vilpponen.mhr.mixin;

import fi.vilpponen.mhr.enchant.CraftEnchant;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Where a crafting result gets its automatic enchantment.
 *
 * <p>{@code CraftingMenu.slotChangedCraftingGrid} is the one server-side place that turns a filled
 * grid into an output stack — the crafting table and the player's own 2x2 both call it — so hooking
 * it covers every way of crafting without knowing anything about how the item is later taken.
 *
 * <p>The stack is enchanted in place after the method has put it in the result slot. The client was
 * already told about the plain version one line earlier, so the enchanted one reaches it with the
 * menu's next sync a tick later, which is not something a player can see.
 */
@Mixin(CraftingMenu.class)
public class CraftingResultEnchantMixin {
	@Inject(method = "slotChangedCraftingGrid", at = @At("RETURN"))
	private static void hardcoreRoguelite$enchantResult(AbstractContainerMenu menu, ServerLevel level,
			Player player, CraftingContainer grid, ResultContainer result,
			RecipeHolder<CraftingRecipe> recipe, CallbackInfo ci) {
		if (player instanceof ServerPlayer serverPlayer) {
			CraftEnchant.enchantCrafted(serverPlayer, result.getItem(0), level.registryAccess());
		}
	}
}
