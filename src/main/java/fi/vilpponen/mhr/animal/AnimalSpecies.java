package fi.vilpponen.mhr.animal;

import fi.vilpponen.mhr.Unlock;
import fi.vilpponen.mhr.UnlockState;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;

/**
 * The passive animals that are sold one species at a time.
 *
 * <p>This is the whole per-species API. The shop, once it exists, only needs {@link #isEnabled()}
 * and {@link #setEnabled(boolean)}; the spawn hook only needs {@link #forSpawn(EntityType)}.
 *
 * <p>Nothing here touches drops, breeding, recipes or combat — a locked species is exactly a
 * vanilla animal that natural spawning never picks.
 */
public enum AnimalSpecies {
	COW(Unlock.ANIMAL_COW, EntityTypes.COW),
	PIG(Unlock.ANIMAL_PIG, EntityTypes.PIG),
	SHEEP(Unlock.ANIMAL_SHEEP, EntityTypes.SHEEP),
	CHICKEN(Unlock.ANIMAL_CHICKEN, EntityTypes.CHICKEN),
	HORSE(Unlock.ANIMAL_HORSE, EntityTypes.HORSE),
	WOLF(Unlock.ANIMAL_WOLF, EntityTypes.WOLF);

	/**
	 * Built once, when this enum is first touched. That is the first natural spawn check, long
	 * after the entity registry is up, so reading {@link EntityTypes} from here is safe.
	 */
	private static final Map<EntityType<?>, AnimalSpecies> BY_TYPE = byType();

	private final Unlock unlock;
	private final EntityType<?> entityType;

	AnimalSpecies(Unlock unlock, EntityType<?> entityType) {
		this.unlock = unlock;
		this.entityType = entityType;
	}

	/** The species natural spawning is about to place, or null for anything else. */
	public static AnimalSpecies forSpawn(EntityType<?> entityType) {
		return BY_TYPE.get(entityType);
	}

	public Unlock unlock() {
		return unlock;
	}

	public EntityType<?> entityType() {
		return entityType;
	}

	public boolean isEnabled() {
		return UnlockState.get().isOwned(unlock);
	}

	/** @return true if this changed anything. */
	public boolean setEnabled(boolean enabled) {
		return UnlockState.get().set(unlock, enabled);
	}

	private static Map<EntityType<?>, AnimalSpecies> byType() {
		Map<EntityType<?>, AnimalSpecies> map = new HashMap<>();
		for (AnimalSpecies species : values()) {
			map.put(species.entityType, species);
		}
		return Map.copyOf(map);
	}
}
