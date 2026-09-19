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
 * Nothing goes into a locked equipment slot from the inventory screen.
 *
 * <p>This is aimed at the offhand. Unlike the armor slots it takes any item at all and so has no
 * check of its own to hook — the plain {@link Slot#mayPlace} is what a click, a shift-click, a
 * number-key swap and a drag all consult. Armor slots override {@code mayPlace} and are already
 * stopped by {@link EquipmentSlotLockMixin}; this is a second line for them rather than the first.
 *
 * <p>This covers the inventory screen only. The swap-hands key bypasses it entirely and is handled
 * by {@link LockedOffhandSwapMixin}, and anything already sitting in a locked slot is emptied out
 * by {@code LockedSlotEvacuation}. Between the three, a locked offhand stays empty, so there is
 * never an offhand item to place or use with — which is the behaviour the design asks for.
 *
 * <p>Recognising the slot by its index in the player's own {@link Inventory} keeps this narrow: a
 * chest or a furnace is a different container and is never touched.
 */
@Mixin(Slot.class)
public class LockedSlotPlacementMixin {
	@Inject(method = "mayPlace", at = @At("HEAD"), cancellable = true)
	private void hardcoreRoguelite$lockSlot(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
		Slot self = (Slot) (Object) this;
		if (!(self.container instanceof Inventory)) {
			return;
		}
		EquipmentSlot slot = Inventory.EQUIPMENT_SLOT_MAPPING.get(self.getContainerSlot());
		if (slot != null && EquipmentLocks.isLocked(slot)) {
			cir.setReturnValue(false);
		}
	}
}
