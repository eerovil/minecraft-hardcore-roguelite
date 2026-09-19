package fi.vilpponen.mhr.mixin;

import fi.vilpponen.mhr.equipment.EquipmentSlotRule;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerEquipment;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * The write barrier — the only place the equipment-slot locks are enforced.
 *
 * <p>{@code PlayerEquipment.set} is where every item that ends up in a player's equipment passes,
 * whichever way it got there. The incoming stack is handed to {@link EquipmentSlotRule#admit},
 * which returns it unchanged for an open slot and returns an empty stack for a locked one, after
 * giving the item back to the player. The write then proceeds normally with whatever came back, so
 * a locked slot ends up empty rather than the write being cancelled halfway.
 *
 * <p>Only players have a {@code PlayerEquipment}; mobs keep their own armor.
 */
@Mixin(PlayerEquipment.class)
public class PlayerEquipmentMixin {
	@Shadow
	@Final
	private Player player;

	// The trailing parameters are the target method's own arguments, which is how a
	// @ModifyVariable handler gets at the slot as well as the stack it is rewriting.
	@ModifyVariable(method = "set", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private ItemStack hardcoreRoguelite$admit(ItemStack incoming, EquipmentSlot slot,
			ItemStack sameStack) {
		return EquipmentSlotRule.admit(player, slot, incoming);
	}
}
