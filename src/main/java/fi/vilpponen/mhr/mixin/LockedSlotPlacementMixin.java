package fi.vilpponen.mhr.mixin;

import fi.vilpponen.mhr.equipment.EquipmentLocks;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes the inventory screen refuse a locked slot cleanly.
 *
 * <p>Like {@link EquipmentSlotLockMixin} this is an affordance, not the rule: a write that got
 * this far would be turned away by the barrier in {@link PlayerEquipmentMixin} anyway. Without it
 * the item would appear to drop into the slot and then jump into the inventory a moment later,
 * which reads like a bug. {@link Slot#mayPlace} is what a click, a shift-click, a number-key swap
 * and a drag all consult, on both sides, so both agree about what is refusable.
 *
 * <p>Recognising the slot by its index in the player's own {@link Inventory} keeps this narrow: a
 * chest or a furnace is a different container and is never touched.
 */
@Mixin(Slot.class)
public class LockedSlotPlacementMixin {
	@Inject(method = "mayPlace", at = @At("HEAD"), cancellable = true)
	private void hardcoreRoguelite$lockSlot(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
		Slot self = (Slot) (Object) this;
		if (!(self.container instanceof Inventory inventory)) {
			return;
		}
		EquipmentSlot slot = Inventory.EQUIPMENT_SLOT_MAPPING.get(self.getContainerSlot());
		if (slot != null && !EquipmentLocks.isUnlockedForDisplay(inventory.player, slot)) {
			cir.setReturnValue(false);
		}
	}
}
