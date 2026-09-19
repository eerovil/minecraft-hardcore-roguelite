package fi.vilpponen.mhr.mixin;

import fi.vilpponen.mhr.equipment.EquipmentLocks;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ArmorSlot;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps a locked armor slot on screen instead of making it vanish.
 *
 * <p>{@code ArmorSlot.isActive} is just {@code canUseSlot}, so the moment
 * {@link EquipmentSlotLockMixin} says no the slot stops being drawn at all and the player is left
 * looking at a blank strip. A locked slot should look locked, not missing, so it stays active and
 * gets the padlock from {@code LockedSlotOverlayMixin} drawn over it. It still refuses items —
 * that is {@code mayPlace}, a different question.
 */
@Mixin(ArmorSlot.class)
public class LockedArmorSlotVisibleMixin {
	@Shadow
	@Final
	private EquipmentSlot slot;

	@Inject(method = "isActive", at = @At("HEAD"), cancellable = true)
	private void hardcoreRoguelite$stayVisible(CallbackInfoReturnable<Boolean> cir) {
		if (EquipmentLocks.isLocked(slot)) {
			cir.setReturnValue(true);
		}
	}
}
