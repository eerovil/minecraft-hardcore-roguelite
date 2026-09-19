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
 * A locked armor slot is a slot the player cannot use.
 *
 * <p>{@code canUseSlot} is vanilla's own "this entity has no such slot" gate, and everything that
 * equips armor already goes through it: clicking or shift-clicking in the inventory
 * ({@code ArmorSlot.mayPlace} → {@code isEquippableInSlot} → here), right-clicking a piece in hand
 * ({@code Equippable.swapWithEquipmentSlot}), and a dispenser ({@code canEquipWithDispenser}). So
 * one hook covers all of them, and anything vanilla adds later that respects the gate is covered
 * too.
 *
 * <p>Only players are affected. Zombies keep their helmets.
 *
 * <p>The offhand is not handled here: its inventory slot accepts anything and never asks, so it
 * gets its own hook in {@link LockedSlotPlacementMixin}.
 */
@Mixin(LivingEntity.class)
public class EquipmentSlotLockMixin {
	@Inject(method = "canUseSlot", at = @At("HEAD"), cancellable = true)
	private void hardcoreRoguelite$lockSlot(EquipmentSlot slot, CallbackInfoReturnable<Boolean> cir) {
		if ((Object) this instanceof Player && EquipmentLocks.isLocked(slot)) {
			cir.setReturnValue(false);
		}
	}
}
