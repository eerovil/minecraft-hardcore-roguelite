package fi.vilpponen.mhr.animal;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntitySpawnReason;

/**
 * The one question the spawn hook asks: may the game place this entity here, by itself?
 *
 * <p>Kept separate from {@link AnimalSpecies} so the mixin stays a two-line call into normal code
 * and the rule about which spawn reasons count lives somewhere testable by reading it.
 */
public final class AnimalSpawns {
	private AnimalSpawns() {
	}

	/**
	 * False only for a locked species that the world is trying to place on its own.
	 *
	 * <p>{@link EntitySpawnReason#CHUNK_GENERATION} is new terrain being populated and
	 * {@link EntitySpawnReason#NATURAL} is the spawn tick that keeps filling it in afterwards.
	 * Every other reason — spawn eggs, {@code /summon}, breeding, structures, conversions — is
	 * somebody deliberately making an animal, and stays vanilla.
	 */
	public static boolean allowsSpawn(EntityType<?> entityType, EntitySpawnReason reason) {
		if (reason != EntitySpawnReason.NATURAL && reason != EntitySpawnReason.CHUNK_GENERATION) {
			return true;
		}
		AnimalSpecies species = AnimalSpecies.forSpawn(entityType);
		return species == null || species.isEnabled();
	}
}
