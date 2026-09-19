package fi.vilpponen.mhr.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
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
 * <p>Unlocks are keyed by a stable string id such as {@code world.trees} or
 * {@code player.slot.helmet}. They are strings rather than enum ordinals on purpose: an id is
 * written into config files and saved state, so it has to survive constants being reordered,
 * renamed or removed. Adding an unlock later is adding a key here — no persistence, shop or
 * balance-loading change.
 *
 * @see BalanceManager for where these come from and how the override merge works
 */
public final class Balance {
	private final Map<String, Integer> advancementRewards;
	private final Map<String, UnlockBalance> unlocks;
	private final Map<String, BorderBalance> worldBorder;
	private final double mobDamageMultiplier;
	private final JsonObject source;

	Balance(Map<String, Integer> advancementRewards, Map<String, UnlockBalance> unlocks,
			Map<String, BorderBalance> worldBorder, double mobDamageMultiplier, JsonObject source) {
		this.advancementRewards = Collections.unmodifiableMap(new LinkedHashMap<>(advancementRewards));
		this.unlocks = Collections.unmodifiableMap(new LinkedHashMap<>(unlocks));
		this.worldBorder = Collections.unmodifiableMap(new LinkedHashMap<>(worldBorder));
		this.mobDamageMultiplier = mobDamageMultiplier;
		this.source = source;
	}

	/** What one unlock costs, and anything else the shop needs to know about it. */
	public record UnlockBalance(String id, int price) {
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

	/** Every border tier, smallest first if the file lists them that way. */
	public Map<String, BorderBalance> worldBorder() {
		return worldBorder;
	}

	/** How much harder than vanilla mobs hit. 1.0 is vanilla. */
	public double mobDamageMultiplier() {
		return mobDamageMultiplier;
	}

	/**
	 * Any other number in the merged balance file, by dotted path, e.g.
	 * {@code number("vanillaPlus.speed.stepPercent", 10)}.
	 *
	 * <p>The escape hatch for tuning values that do not have a typed home yet, so a new vanilla+
	 * system can be balanced from data on day one and grow a proper accessor later. The path is
	 * split on {@code .}, so it cannot reach into {@link #unlocks()} — those keys contain dots
	 * themselves. Use {@link #unlockPrice} for those.
	 *
	 * @param fallback returned when the path is absent
	 * @throws BalanceException if the path exists but does not hold a number
	 */
	public double number(String path, double fallback) {
		JsonElement found = resolve(path);
		if (found == null) {
			return fallback;
		}
		if (!(found instanceof JsonPrimitive primitive) || !primitive.isNumber()) {
			throw new BalanceException("Balance value '" + path + "' should be a number, not " + found);
		}
		return found.getAsDouble();
	}

	/** Same as {@link #number}, for a value that must be whole. */
	public int integer(String path, int fallback) {
		double value = number(path, fallback);
		if (value != Math.rint(value)) {
			throw new BalanceException("Balance value '" + path + "' should be a whole number, not " + value);
		}
		return (int) value;
	}

	/** Top-level sections present in the merged file, including ones nothing reads yet. */
	public Set<String> sections() {
		return Collections.unmodifiableSet(source.keySet());
	}

	private JsonElement resolve(String path) {
		JsonElement here = source;
		for (String part : path.split("\\.")) {
			if (!(here instanceof JsonObject object) || !object.has(part)) {
				return null;
			}
			here = object.get(part);
		}
		return here;
	}
}
