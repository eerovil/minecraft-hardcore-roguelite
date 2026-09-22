package fi.vilpponen.mhr.mixin;

import fi.vilpponen.mhr.earn.AdvancementPayouts;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tells the earning rule when an advancement has just been completed.
 *
 * <p>Hooked at the point vanilla hands over the advancement's own rewards, which is inside the one
 * branch {@code award} takes when an advancement goes from unfinished to finished. That is the
 * whole reason for injecting there rather than at the return: {@code award} is called again for
 * every later criterion of an advancement that is already done — several vanilla advancements have
 * four alternative criteria and are finished by the first — and a hook at the return would have to
 * work out for itself which of those calls was the completion. Here the question is already
 * answered, by vanilla, once.
 *
 * <p>A hook, not a feature: what a completion is worth, and whether it is worth anything at all,
 * belongs to {@link AdvancementPayouts}.
 */
@Mixin(PlayerAdvancements.class)
public class AdvancementPayoutMixin {
	@Shadow
	private ServerPlayer player;

	@Inject(method = "award", cancellable = true, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/advancements/AdvancementRewards;grant"
					+ "(Lnet/minecraft/server/level/ServerPlayer;)V"))
	private void hardcoreRoguelite$payForAdvancement(AdvancementHolder advancement, String criterion,
			CallbackInfoReturnable<Boolean> callback) {
		if (!AdvancementPayouts.completed(player, advancement, criterion)) {
			// The payout could not be written down, so the completion has been revoked and this
			// award is being undone. Stopping here is the rest of undoing it: vanilla is about to
			// hand over the advancement's own rewards, pop a toast and announce it in chat, and none
			// of that should happen for something the player still has to earn. False is the honest
			// answer too — after the rollback, nothing about their progress changed.
			callback.setReturnValue(false);
		}
	}
}
