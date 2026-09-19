package fi.vilpponen.mhr;

import fi.vilpponen.mhr.core.BalanceManager;

/**
 * Everything the shop can sell that is permanent across runs.
 *
 * <p>Only {@link #TREES}, {@link #VILLAGE}, the ores, the five equipment slots and
 * {@link #CRAFT_ENCHANT} do anything yet. The rest of the catalogue from the design document
 * (animals, world border, status effects, start chest) gets added here as each one is implemented.
 *
 * <p>The id is one string used everywhere: it is the key in {@code default-balance.json}, the value
 * written to the unlock save file, and what the dev command takes. It is namespaced — {@code world.}
 * for things missing from the world, {@code player.} for things missing from the player — so the
 * shop can group the catalogue without a second table saying which is which. See
 * {@code docs/balance.md}. Renaming one is a save migration, so pick it once: {@link UnlockState}
 * carries the list of old names that still have to be understood.
 *
 * <p>Most unlocks are simply owned or not. A repeatable one can be bought several times and gets
 * stronger each time; {@link UnlockState} remembers how far the player has taken it, and how far
 * that can go is a balance number rather than a constant here.
 */
public enum Unlock {
	TREES("world.trees"),

	/** Village structures in newly generated land. See {@code fi.vilpponen.mhr.village}. */
	VILLAGE("world.village"),

	/** The ores, one unlock each. See {@code fi.vilpponen.mhr.ore}. */
	COAL("world.ore.coal"),
	IRON("world.ore.iron"),
	COPPER("world.ore.copper"),
	GOLD("world.ore.gold"),
	REDSTONE("world.ore.redstone"),
	LAPIS("world.ore.lapis"),
	DIAMOND("world.ore.diamond"),

	/** The five equipment slots, one unlock each. See {@code fi.vilpponen.mhr.equipment}. */
	SLOT_HELMET("player.slot.helmet"),
	SLOT_CHESTPLATE("player.slot.chestplate"),
	SLOT_LEGGINGS("player.slot.leggings"),
	SLOT_BOOTS("player.slot.boots"),
	SLOT_OFFHAND("player.slot.offhand"),

	/**
	 * Vanilla+, and repeatable. Newly crafted tools come out enchanted, harder at every level. See
	 * {@code fi.vilpponen.mhr.enchant}.
	 */
	CRAFT_ENCHANT("player.craft.enchant", "vanillaPlus.craftEnchant.maxUnlockLevel");

	private final String id;
	private final String maxLevelPath;

	Unlock(String id) {
		this(id, null);
	}

	/**
	 * @param maxLevelPath where the balance file says how many times this can be bought, or null for
	 *     an unlock that is simply owned or not
	 */
	Unlock(String id, String maxLevelPath) {
		this.id = id;
		this.maxLevelPath = maxLevelPath;
	}

	public String id() {
		return id;
	}

	/**
	 * How many times this can be bought. One for the plain on/off unlocks.
	 *
	 * <p>Read from the balance data every time rather than kept here, so lengthening a curve is a
	 * balance edit and {@code /mhr reload} reaches it.
	 */
	public int maxLevel() {
		return maxLevelPath == null ? 1 : BalanceManager.get().integer(maxLevelPath);
	}

	/** Whether buying it again does anything. */
	public boolean isRepeatable() {
		return maxLevelPath != null;
	}

	public static Unlock byId(String id) {
		for (Unlock unlock : values()) {
			if (unlock.id.equals(id)) {
				return unlock;
			}
		}
		return null;
	}
}
