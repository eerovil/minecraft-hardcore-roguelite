package fi.vilpponen.mhr.mixin;

import fi.vilpponen.mhr.ore.Ore;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.AbstractOreFeature;
import net.minecraft.world.level.levelgen.feature.BlockReplacement;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import net.minecraft.world.level.levelgen.feature.ScatteredOreFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Locked ores do not generate.
 *
 * <p>These two features are how every vanilla ore vein is placed, and in 26.3 a feature carries the
 * blocks it places, so the decision can be made from the feature alone: if a vein would place a
 * locked ore, the placement is skipped and the stone stays stone. Everything the same features place
 * that is not one of our ores — emerald, quartz, ancient debris, gilded blackstone, the dirt and
 * gravel blobs — is left exactly as vanilla.
 *
 * <p>A vein whose targets mix ores is suppressed if any of them is locked. Vanilla never mixes, and
 * for anything that does, the rule that a locked ore must not appear wins.
 */
@Mixin({OreFeature.class, ScatteredOreFeature.class})
public class OreGenerationMixin {
	@Inject(method = "place", at = @At("HEAD"), cancellable = true)
	private void hardcoreRoguelite$blockLockedOres(WorldGenLevel level, ChunkGenerator generator,
			RandomSource random, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		AbstractOreFeature self = (AbstractOreFeature) (Object) this;
		for (BlockReplacement replacement : self.targetStates()) {
			if (Ore.isSuppressed(replacement.state())) {
				cir.setReturnValue(false);
				return;
			}
		}
	}
}
