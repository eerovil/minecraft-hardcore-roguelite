package fi.vilpponen.mhr.gametest.mixin;

import fi.vilpponen.mhr.gametest.TestFaults;
import fi.vilpponen.mhr.run.RunRecord;
import fi.vilpponen.mhr.run.RunStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A disk that will not take the run record, on request.
 *
 * <p>{@code RunStorage.save} already reports a failed write by returning false, and the lifecycle
 * already treats that as a refusal to move on. What no test could do until now is cause one. This
 * makes the armed number of writes report failure without writing, which is the same thing a full
 * or read-only disk does and reaches the lifecycle through the same return value.
 *
 * <p>Test-only, and in the gametest source set for that reason. Nothing in the shipped mod can
 * reach {@link TestFaults}, so the production path is exactly the one being tested.
 *
 * @see TestFaults
 */
@Mixin(RunStorage.class)
public class RunStorageFailureMixin {
	@Inject(method = "save", at = @At("HEAD"), cancellable = true)
	private void mhr$failArmedWrites(RunRecord record, CallbackInfoReturnable<Boolean> callback) {
		if (TestFaults.takeRecordWriteFailure()) {
			callback.setReturnValue(false);
		}
	}
}
