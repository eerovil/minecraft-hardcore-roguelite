package fi.vilpponen.mhr.mixin;

import fi.vilpponen.mhr.animal.AnimalSpawns;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No free chicken under a naturally spawned baby zombie.
 *
 * <p>A chicken jockey does not go through the spawn checks the rest of natural spawning uses. The
 * zombie passes its own check, and then builds the chicken itself inside {@code finalizeSpawn} —
 * so {@code SpawnPlacements.checkSpawnRules} never sees it, and a locked chicken would walk into
 * the world underneath a zombie, drops and breeding stock included.
 *
 * <p>{@code Zombie.finalizeSpawn} already copes with getting nothing back: it null-checks the new
 * chicken and skips the whole jockey block. So returning null is the quiet answer rather than a
 * special case, and the zombie just arrives on foot.
 *
 * <p>Zombie is the only thing in the game that builds one of our six species this way. The other
 * jockeys — spider and skeleton, strider and zombified piglin, drowned and zombie nautilus — make
 * mobs this feature does not own, and the species check leaves them alone.
 */
@Mixin(EntityType.class)
public class JockeyAnimalMixin {
	@Inject(method = "create(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/EntitySpawnReason;)Lnet/minecraft/world/entity/Entity;",
			at = @At("HEAD"), cancellable = true)
	private void hardcoreRoguelite$blockLockedJockey(Level level, EntitySpawnReason reason,
			CallbackInfoReturnable<Entity> cir) {
		if (!AnimalSpawns.allowsSpawn((EntityType<?>) (Object) this, reason)) {
			cir.setReturnValue(null);
		}
	}
}
