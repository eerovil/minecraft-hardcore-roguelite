package fi.vilpponen.mhr.animal;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntitySpawnReason;

/**
 * The one question the spawn hooks ask: is the world making this animal by itself?
 *
 * <p>Kept separate from {@link AnimalSpecies} so the mixins stay a two-line call into normal code
 * and the rule about which spawn reasons count lives somewhere testable by reading it.
 */
public final class AnimalSpawns {
	private AnimalSpawns() {
	}

	/** False only for a locked species that the world is producing of its own accord. */
	public static boolean allowsSpawn(EntityType<?> entityType, EntitySpawnReason reason) {
		if (!isTheWorldsOwnDoing(reason)) {
			return true;
		}
		AnimalSpecies species = AnimalSpecies.forSpawn(entityType);
		return species == null || species.isEnabled();
	}

	/**
	 * The three ways the world hands you an animal nobody asked for.
	 *
	 * <p>{@link EntitySpawnReason#CHUNK_GENERATION} is new terrain being populated and
	 * {@link EntitySpawnReason#NATURAL} is the spawn tick that keeps filling it in afterwards.
	 * {@link EntitySpawnReason#JOCKEY} is the third and least obvious: a baby zombie that spawns
	 * naturally brings its own chicken into the world, built from scratch during the zombie's
	 * {@code finalizeSpawn}. Left alone that is a free way around a locked chicken, drops and
	 * breeding stock included, with no player having done anything to earn it.
	 *
	 * <p>A baby zombie climbing onto a chicken that is already there is fine and untouched — it
	 * creates nothing, and with chickens locked there is nothing for it to find.
	 *
	 * <p>Every other reason — spawn eggs, {@code /summon}, breeding, structures, conversions,
	 * buckets — is somebody deliberately making an animal, and stays vanilla.
	 */
	private static boolean isTheWorldsOwnDoing(EntitySpawnReason reason) {
		return reason == EntitySpawnReason.NATURAL
				|| reason == EntitySpawnReason.CHUNK_GENERATION
				|| reason == EntitySpawnReason.JOCKEY;
	}
}
