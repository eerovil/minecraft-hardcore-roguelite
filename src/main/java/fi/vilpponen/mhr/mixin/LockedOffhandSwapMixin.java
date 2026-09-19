package fi.vilpponen.mhr.mixin;

import fi.vilpponen.mhr.equipment.EquipmentLocks;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.EquipmentSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The swap-hands key does not go through a locked offhand either.
 *
 * <p>Pressing F never touches the inventory screen: the client just sends a player-action packet,
 * and the server swaps the two held stacks directly. So it slips past the slot check in
 * {@link LockedSlotPlacementMixin} and would be a way to get an item into a locked offhand and
 * then use it.
 *
 * <p>The whole action is dropped rather than half of it. Cancelling only the write into the
 * offhand would leave the main hand holding whatever the empty offhand had, which throws the
 * player's item away. Here neither hand is touched and nothing is lost.
 *
 * <p>Injected after {@code resetLastActionTime}, which is the packet handler's own "this is a real
 * action from a live player" point: past the thread hand-off, before the switch on the action. The
 * vanilla client does not predict the swap locally — it only sends the packet — so refusing it
 * server-side leaves nothing out of step on the client.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class LockedOffhandSwapMixin {
	@Inject(
			method = "handlePlayerAction",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/server/level/ServerPlayer;resetLastActionTime()V",
					shift = At.Shift.AFTER),
			cancellable = true)
	private void hardcoreRoguelite$blockOffhandSwap(ServerboundPlayerActionPacket packet,
			CallbackInfo ci) {
		if (packet.getAction() == ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND
				&& EquipmentLocks.isLocked(EquipmentSlot.OFFHAND)) {
			ci.cancel();
		}
	}
}
