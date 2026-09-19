package fi.vilpponen.mhr.mixin;

import fi.vilpponen.mhr.Unlock;
import fi.vilpponen.mhr.UnlockState;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.FallenTreeFeature;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No trees until the trees unlock is bought.
 *
 * <p>Stopping the features themselves rather than editing every biome's feature list also stops
 * saplings and bonemeal from growing one, which is what we want: the world simply has no trees.
 *
 * <p>Fallen trees are included because they are a free pile of logs, which would undercut the
 * whole point. Coral trees are not — they are coral, not wood.
 */
@Mixin({TreeFeature.class, FallenTreeFeature.class})
public class TreeGenerationMixin {
	@Inject(method = "place", at = @At("HEAD"), cancellable = true)
	private void hardcoreRoguelite$blockTrees(WorldGenLevel level, ChunkGenerator generator,
			RandomSource random, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		if (!UnlockState.get().isOwned(Unlock.TREES)) {
			cir.setReturnValue(false);
		}
	}
}
