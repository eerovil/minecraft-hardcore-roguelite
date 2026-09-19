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
 *
 * <p>It is tempting to think the locked species should instead be taken out of the biome's
 * weighted list before the game draws from it, so that a locked draw is not "wasted". Two things
 * say otherwise, and both matter for the rule that locking one animal must not disturb the rest:
 *
 * <ul>
 * <li>Removing an entry renormalises the draw. Every remaining species goes from {@code w/total}
 * to {@code w/(total - w_locked)}, so locking cows would make sheep spawn more often than vanilla.
 * <li>The wasted draw costs nobody else anything. On the spawn tick the drawn species is kept for
 * the rest of that attempt, but {@code spawnCategoryForPosition} clears it at the top of each of
 * its three attempts, so a locked draw ends that attempt and no other. Chunk generation likewise
 * draws afresh every pass, and the pass count comes from a coin flip that ignores whether a pass
 * produced anything. In both, the attempt a locked animal consumes is the attempt vanilla had
 * already given to that same animal.
 * </ul>
 *
 * <p>So refusing the outcome and leaving the draw alone is the one option that keeps every other
 * mob at exactly its vanilla rate.
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
