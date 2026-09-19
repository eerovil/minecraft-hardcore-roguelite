package fi.vilpponen.mhr.mixin;

import fi.vilpponen.mhr.animal.AnimalSpawns;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.level.ServerLevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No cows, pigs, sheep, chickens, horses or wolves until that species is bought.
 *
 * <p>{@code SpawnPlacements.checkSpawnRules} is where both halves of natural spawning meet:
 * {@code NaturalSpawner.spawnMobsForChunkGeneration} asks it when new terrain is populated, and
 * {@code NaturalSpawner.canSpawnMobAt} asks it on the spawn tick afterwards. Saying no here is
 * the same answer the game already gives when a biome is wrong for a mob, so nothing downstream
 * needs to know the mod exists.
 */
@Mixin(SpawnPlacements.class)
public class AnimalSpawnMixin {
	@Inject(method = "checkSpawnRules", at = @At("HEAD"), cancellable = true)
	private static void hardcoreRoguelite$blockLockedAnimals(EntityType<?> entityType,
			ServerLevelAccessor level, EntitySpawnReason reason, BlockPos pos, RandomSource random,
			CallbackInfoReturnable<Boolean> cir) {
		if (!AnimalSpawns.allowsSpawn(entityType, reason)) {
			cir.setReturnValue(false);
		}
	}
}
