package fi.vilpponen.mhr.mixin;

import fi.vilpponen.mhr.equipment.EquipmentLocks;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets vanilla decline a locked slot politely, instead of trying and being refused.
 *
 * <p>This is not what makes the rule true — {@code EquipmentSlotRule} is, at the write barrier in
 * {@link PlayerEquipmentMixin}. This only tells vanilla in advance, through its own
 * "this entity has no such slot" gate, so that right-clicking a helmet leaves it in your hand and
 * a dispenser keeps it rather than both pushing an item that would be handed straight back.
 *
 * <p>Only players are affected. Zombies keep their helmets.
 */
@Mixin(LivingEntity.class)
public class EquipmentSlotLockMixin {
	@Inject(method = "canUseSlot", at = @At("HEAD"), cancellable = true)
	private void hardcoreRoguelite$lockSlot(EquipmentSlot slot, CallbackInfoReturnable<Boolean> cir) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (self instanceof Player && !EquipmentLocks.isUnlockedForDisplay(self, slot)) {
			cir.setReturnValue(false);
		}
	}
}
