package fi.vilpponen.mhr;

import fi.vilpponen.mhr.core.BalanceManager;

/**
 * Everything the shop can sell that is permanent across runs.
 *
 * <p>Only {@link #TREES}, {@link #VILLAGE}, the ores, the six passive animals, the five equipment
 * slots and {@link #CRAFT_ENCHANT} do anything yet. The rest of the catalogue from the design
 * document (world border, status effects) gets added here as each one is implemented.
 *
 * <p>Not everything the shop sells needs a constant here. A constant exists so Java can name one
 * unlock and ask whether it is owned; a starter item has nothing to name, because the whole of it
 * is the item stack in the balance file. Those live under {@code starter.} in the catalogue and
 * are owned by id alone — see {@code fi.vilpponen.mhr.starter} and {@link UnlockState}.
 *
 * <p>The id is one string used everywhere: the key in {@code default-balance.json}, what is
 * recorded when the unlock is bought, and what the dev command takes. It is namespaced —
 * {@code world.} for things missing from the world, {@code player.} for things missing from the
 * player — so the shop can group the catalogue without a second table saying which is which. See
 * {@code docs/balance.md}.
 *
 * <p>Renaming one is a save migration rather than a refactor, so pick it once. Where progression is
 * stored and how a renamed id is carried across is
 * {@link fi.vilpponen.mhr.progression.Progress}'s, and the rules are in
 * {@code docs/codebase/progression.md}.
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

	/**
	 * The six passive animals, one unlock each, because the design document sells them a species
	 * at a time. See {@code fi.vilpponen.mhr.animal}.
	 */
	ANIMAL_COW("world.animal.cow"),
	ANIMAL_PIG("world.animal.pig"),
	ANIMAL_SHEEP("world.animal.sheep"),
	ANIMAL_CHICKEN("world.animal.chicken"),
	ANIMAL_HORSE("world.animal.horse"),
	ANIMAL_WOLF("world.animal.wolf"),

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
