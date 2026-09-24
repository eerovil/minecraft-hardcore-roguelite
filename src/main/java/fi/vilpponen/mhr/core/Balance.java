package fi.vilpponen.mhr.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Every number gameplay balance is allowed to care about, as one immutable snapshot.
 *
 * <p>This is the whole point of the {@code core} package: feature code asks this object what
 * something costs or how hard something hits, and never writes the number down itself. Retuning
 * the game is then editing {@code default-balance.json} or the config override, not editing Java.
 *
 * <p>Unlocks are keyed by a stable string id such as {@code world.village} or
 * {@code player.slot.helmet}. They are strings rather than enum ordinals on purpose: an id is
 * written into config files and saved state, so it has to survive constants being reordered,
 * renamed or removed. Adding an unlock later is adding a key here — no persistence, shop or
 * balance-loading change.
 *
 * @see BalanceManager for where these come from and how the override merge works
 */
public final class Balance {
	/**
	 * What a border tier's unlock id starts with, e.g. {@code world.border.medium}.
	 *
	 * <p>A tier's price and size live under {@code worldBorder} rather than {@code unlocks}, so a
	 * tier's two numbers stay next to each other. This is the one string that turns the short tier
	 * id in that section into the id the shop sells and the save file records, and it lives here so
	 * that nothing outside {@code core} has to know how the two are spelled.
	 */
	public static final String BORDER_UNLOCK_PREFIX = "world.border.";

	private final Map<String, Integer> advancementRewards;
	private final Map<String, UnlockBalance> unlocks;
	private final Map<String, BorderBalance> worldBorder;
	private final double mobDamageMultiplier;
	private final CraftEnchantBalance craftEnchant;
	private final JsonObject source;

	Balance(Map<String, Integer> advancementRewards, Map<String, UnlockBalance> unlocks,
			Map<String, BorderBalance> worldBorder, double mobDamageMultiplier,
			CraftEnchantBalance craftEnchant, JsonObject source) {
		this.advancementRewards = Collections.unmodifiableMap(new LinkedHashMap<>(advancementRewards));
		this.unlocks = Collections.unmodifiableMap(new LinkedHashMap<>(unlocks));
		this.worldBorder = Collections.unmodifiableMap(new LinkedHashMap<>(worldBorder));
		this.mobDamageMultiplier = mobDamageMultiplier;
		this.craftEnchant = craftEnchant;
		this.source = source;
	}

	/**
	 * What one unlock costs, and anything else the shop needs to know about it.
	 *
	 * @param item for a starter item, the stack it puts in the run-start chest, written in the same
	 *     shape {@code /give} and loot tables use. Empty for every other unlock — an unlock whose
	 *     effect is code rather than an item. This is what keeps starter items in the one catalogue
	 *     instead of a second one: the id, the price and the contents sit together, and the override
	 *     file can retune any of the three.
	 */
	public record UnlockBalance(String id, int price, Optional<JsonObject> item) {
		public UnlockBalance {
			// Defensive copy in both directions: Balance is an immutable snapshot shared across
			// threads, and JsonObject is not immutable.
			item = item.map(JsonObject::deepCopy);
		}

		@Override
		public Optional<JsonObject> item() {
			return item.map(JsonObject::deepCopy);
		}
	}

	/**
	 * One step of the world border.
	 *
	 * @param size edge-to-edge width in blocks, or empty for a border that is never in the way
	 */
	public record BorderBalance(String id, OptionalDouble size, int price) {
		public boolean isUnbounded() {
			return size.isEmpty();
		}
	}

	/**
	 * The crafted-tool enchant, the first repeatable unlock.
	 *
	 * @param maxUnlockLevel how many times {@code player.craft.enchant} can be bought, at least once
	 * @param strengthPerLevel what one of those levels is worth as a fraction of an enchantment's own
	 *     maximum, so the two multiply to 1.0 when the top level is meant to reach it
	 */
	public record CraftEnchantBalance(int maxUnlockLevel, double strengthPerLevel) {
	}

	/**
	 * Currency for completing a vanilla advancement, or 0 for one that pays nothing.
	 *
	 * @param advancementId the full id, e.g. {@code minecraft:story/mine_diamond}
	 */
	public int advancementReward(String advancementId) {
		return advancementRewards.getOrDefault(advancementId, 0);
	}

	/** Every advancement that pays, in file order. */
	public Map<String, Integer> advancementRewards() {
		return advancementRewards;
	}

	/** Empty if nothing in the balance file sells this id. */
	public Optional<UnlockBalance> unlock(String unlockId) {
		return Optional.ofNullable(unlocks.get(unlockId));
	}

	/** Empty if nothing in the balance file sells this id. */
	public OptionalInt unlockPrice(String unlockId) {
		UnlockBalance unlock = unlocks.get(unlockId);
		return unlock == null ? OptionalInt.empty() : OptionalInt.of(unlock.price());
	}

	/** Everything the shop can sell apart from the border tiers, in file order. */
	public Map<String, UnlockBalance> unlocks() {
		return unlocks;
	}

	/**
	 * One border tier by its short id, e.g. {@code medium}.
	 *
	 * <p>A tier's unlock id is {@code world.border.} plus this id, but its price lives here rather
	 * than under {@code unlocks} so a tier's size and price stay next to each other.
	 */
	public Optional<BorderBalance> border(String tierId) {
		return Optional.ofNullable(worldBorder.get(tierId));
	}

	/**
	 * The same tier, looked up by the id the shop and the save file use rather than the short one.
	 *
	 * <p>Empty for any other id, so this doubles as "is this string a border tier at all?".
	 */
	public Optional<BorderBalance> borderByUnlockId(String unlockId) {
		return unlockId.startsWith(BORDER_UNLOCK_PREFIX)
				? border(unlockId.substring(BORDER_UNLOCK_PREFIX.length()))
				: Optional.empty();
	}

	/** Every border tier, smallest first if the file lists them that way. */
	public Map<String, BorderBalance> worldBorder() {
		return worldBorder;
	}

	/** How much harder than vanilla mobs hit. 1.0 is vanilla. */
	public double mobDamageMultiplier() {
		return mobDamageMultiplier;
	}

	/**
	 * The crafted-tool enchant's two numbers, already checked.
	 *
	 * <p>Typed rather than read by path, so a level that is not whole or a strength of zero is caught
	 * while the balance is loading — at startup it stops the mod, on reload it leaves the running
	 * game on the balance it had — instead of throwing at the moment somebody crafts a pickaxe.
	 */
	public CraftEnchantBalance craftEnchant() {
		return craftEnchant;
	}

	/**
	 * Any other number in the merged balance file, by dotted path, e.g.
	 * {@code number("vanillaPlus.speed.stepPercent")}.
	 *
	 * <p>The escape hatch for tuning values that do not have a typed home yet, so a new vanilla+
	 * system can be balanced from data on day one and grow a proper accessor later. The value goes
	 * in {@code default-balance.json} alongside the code that reads it.
	 *
	 * <p>There is deliberately no fallback argument. A fallback is a balance number written in Java,
	 * and the whole point of this layer is that there are none: a path the data does not have is a
	 * mistake in one of the two, not a cue to invent a value. The override already has its safe
	 * fallback — it is merged over the bundled file, so anything it leaves out keeps the bundled
	 * value — and a second default behind that would only hide a missing one.
	 *
	 * <p>The path is split on {@code .}, so it cannot reach into {@link #unlocks()} — those keys
	 * contain dots themselves. Use {@link #unlockPrice} for those.
	 *
	 * @throws BalanceException if the path is absent, does not hold a number, or runs into a value
	 *     that is not an object on the way down
	 */
	public double number(String path) {
		JsonElement found = resolve(path);
		if (found == null) {
			throw new BalanceException("Balance is missing the value '" + path + "'");
		}
		if (!(found instanceof JsonPrimitive primitive) || !primitive.isNumber()) {
			throw new BalanceException("Balance value '" + path + "' should be a number, not " + found);
		}
		return Numbers.finite(found.getAsDouble(), path);
	}

	/**
	 * Same as {@link #number}, for a value that must be whole.
	 *
	 * @throws BalanceException if the value is fractional, or too big to be an {@code int}
	 */
	public int integer(String path) {
		return Numbers.toInt(number(path), path);
	}

	/** Top-level sections present in the merged file, including ones nothing reads yet. */
	public Set<String> sections() {
		return Collections.unmodifiableSet(source.keySet());
	}

	/**
	 * Walk a dotted path, or null if nothing is there.
	 *
	 * <p>A key that is simply absent is not an error — that is what the caller's fallback is for.
	 * Running into something that is not an object part-way down is a different thing entirely: the
	 * file says {@code vanillaPlus.speed} is 12 while the code expects it to hold
	 * {@code stepPercent}, and one of the two is wrong. Returning the fallback there would hide a
	 * real mistake behind a plausible number, which is the one thing this layer is not allowed to
	 * do.
	 */
	private JsonElement resolve(String path) {
		String[] parts = path.split("\\.");
		JsonElement here = source;
		for (int i = 0; i < parts.length; i++) {
			if (!(here instanceof JsonObject object)) {
				String prefix = String.join(".", Arrays.copyOfRange(parts, 0, i));
				throw new BalanceException("Balance section '" + prefix + "' should be an object, not " + here
						+ ", so '" + path + "' cannot be read");
			}
			if (!object.has(parts[i])) {
				return null;
			}
			here = object.get(parts[i]);
		}
		return here;
	}
}
