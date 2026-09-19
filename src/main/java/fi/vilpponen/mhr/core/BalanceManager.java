package fi.vilpponen.mhr.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Loads the balance numbers, and hands out the current {@link Balance}.
 *
 * <p>Two layers. {@code default-balance.json} ships inside the jar and always exists. On top of it
 * sits an optional {@code hardcore-roguelite-balance.json} in the usual Fabric config directory,
 * which is deep-merged over the defaults: it only has to name the values it wants to change, and
 * anything it leaves out keeps the bundled value. That is what makes a local tuning file two lines
 * long instead of a copy of the whole catalogue.
 *
 * <p>A broken override is never ignored. At startup it stops the mod loading with a message naming
 * the file and the problem, because balance that silently fell back to defaults would play like a
 * perfectly normal run and nobody would notice. {@link #reload()} is gentler: it keeps the balance
 * already in effect and reports the problem to whoever ran the command, so a typo mid-playtest
 * does not disturb the running game.
 *
 * <p>Read from worldgen and server threads, so the current snapshot is held in a volatile field and
 * is replaced whole rather than edited. Callers should read {@link #balance()} each time they need
 * a number rather than caching one, and then a reload reaches them for free.
 */
public final class BalanceManager {
	/** The bundled defaults, inside the jar. */
	public static final String DEFAULT_RESOURCE = "/default-balance.json";
	/** The optional override, in the Fabric config directory. */
	public static final String OVERRIDE_FILE_NAME = "hardcore-roguelite-balance.json";

	private static final Set<String> UNLOCK_KEYS = Set.of("price", "item");
	private static final Set<String> BORDER_KEYS = Set.of("size", "price");
	private static final Set<String> CURRENCY_KEYS = Set.of("advancements");
	private static final Set<String> DIFFICULTY_KEYS = Set.of("mobDamageMultiplier");
	private static final Set<String> VANILLA_PLUS_KEYS = Set.of("craftEnchant");
	private static final Set<String> CRAFT_ENCHANT_KEYS = Set.of("maxUnlockLevel", "strengthPerLevel");

	private static volatile Balance current;
	private static final List<Check> CHECKS = new CopyOnWriteArrayList<>();

	private BalanceManager() {
	}

	/**
	 * A check on a candidate balance that this layer cannot make on its own.
	 *
	 * <p>Everything in a balance file is a number or a name to {@code core}, which is what keeps it
	 * free of the game. Some values mean more than that somewhere else — a starter item's stack has
	 * to name an item the registries actually have — and finding that out a run later, with the bad
	 * balance already in effect, is exactly the silent failure this layer exists to prevent.
	 *
	 * <p>So a feature registers what it knows how to check, and it runs on the candidate before the
	 * snapshot is swapped in: at startup a failure stops the game, on reload it leaves the running
	 * game on the balance it already had.
	 */
	@FunctionalInterface
	public interface Check {
		/** @throws BalanceException if this candidate must not become the balance in effect. */
		void check(Balance candidate);
	}

	/** Register a check. It applies from the next load or reload; see also {@link #recheck}. */
	public static void addCheck(Check check) {
		CHECKS.add(check);
	}

	/** Undo {@link #addCheck}, so a test's check does not outlive it. */
	static void removeCheck(Check check) {
		CHECKS.remove(check);
	}

	/**
	 * Run every check against the balance already in effect.
	 *
	 * <p>Worth doing right after registering one: a check that needs the server's registries
	 * cannot exist until the server is up, by which time a balance has been loaded and nothing
	 * would look at it again until the next reload.
	 *
	 * @throws BalanceException if the balance in effect does not pass
	 */
	public static void recheck() {
		Balance inEffect = get();
		for (Check check : CHECKS) {
			check.check(inEffect);
		}
	}

	/** Where a local override would live, whether or not it exists. */
	public static Path overrideFile() {
		return FabricLoader.getInstance().getConfigDir().resolve(OVERRIDE_FILE_NAME);
	}

	/**
	 * The numbers in effect right now.
	 *
	 * @throws BalanceException if nothing has loaded yet and loading fails
	 */
	public static Balance get() {
		Balance local = current;
		if (local == null) {
			synchronized (BalanceManager.class) {
				local = current;
				if (local == null) {
					local = read();
					current = local;
				}
			}
		}
		return local;
	}

	/**
	 * Load at startup, failing loudly on a broken override.
	 *
	 * @throws BalanceException with a message naming the file and the problem
	 */
	public static synchronized Balance load() {
		current = read();
		return current;
	}

	/**
	 * Re-read both layers and swap the result in, or change nothing.
	 *
	 * <p>Values are only swapped on success, so a malformed override leaves the running game on the
	 * balance it already had.
	 *
	 * @throws BalanceException if the override cannot be read; the previous balance stays in effect
	 */
	public static synchronized Balance reload() {
		Balance reloaded = read();
		current = reloaded;
		return reloaded;
	}

	private static Balance read() {
		JsonObject merged = readBundledDefaults();
		Path override = overrideFile();
		if (Files.isRegularFile(override)) {
			merged = applyOverride(merged, readOverride(override), override.toString());
		}
		return bindAndCheck(merged);
	}

	/** Check an override against the bundled catalogue and fold it in. Returns {@code defaults}. */
	static JsonObject applyOverride(JsonObject defaults, JsonObject over, String where) {
		checkOverrideKeys(defaults, over, where);
		merge(defaults, over);
		return defaults;
	}

	/**
	 * Bind a candidate and put it through every registered {@link Check}.
	 *
	 * <p>Both happen before the caller can swap it in, which is the whole point: a throw here
	 * leaves {@link #current} alone, so a bad reload costs the running game nothing.
	 */
	static Balance bindAndCheck(JsonObject merged) {
		Balance candidate = bind(merged);
		for (Check check : CHECKS) {
			check.check(candidate);
		}
		return candidate;
	}

	private static JsonObject readBundledDefaults() {
		try (InputStream in = BalanceManager.class.getResourceAsStream(DEFAULT_RESOURCE)) {
			if (in == null) {
				throw new BalanceException("The mod jar is missing " + DEFAULT_RESOURCE);
			}
			try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
				return asObject(JsonParser.parseReader(reader), DEFAULT_RESOURCE);
			}
		} catch (IOException | JsonParseException e) {
			throw new BalanceException("Could not read the bundled " + DEFAULT_RESOURCE, e);
		}
	}

	private static JsonObject readOverride(Path file) {
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			return asObject(JsonParser.parseReader(reader), file.toString());
		} catch (IOException e) {
			throw new BalanceException("Could not read the balance override " + file, e);
		} catch (JsonParseException e) {
			throw new BalanceException("The balance override " + file + " is not valid JSON: " + e.getMessage(), e);
		}
	}

	private static JsonObject asObject(JsonElement parsed, String where) {
		if (parsed == null || !parsed.isJsonObject()) {
			throw new BalanceException(where + " should contain a JSON object");
		}
		return parsed.getAsJsonObject();
	}

	/**
	 * The override may change what the bundled file has, and nothing else.
	 *
	 * <p>The bundled file is the catalogue — of unlocks, of paying advancements, of border tiers, and
	 * of every section and value name there is. So it is also the schema, and the override is checked
	 * against it all the way down from the root. A key the bundled file does not have is a typo, and
	 * a typo merges perfectly: {@code world.ore.diamod} becomes an entry nothing reads while the real
	 * diamond keeps its price, and {@code dificulty} becomes a section nothing reads while mobs go on
	 * hitting exactly as hard as before. A balance change that appears to work, does nothing, and says
	 * nothing is the one failure this layer exists to prevent.
	 *
	 * <p>Shape counts too, not just the names: the override has to look like the bundled file all the
	 * way down, so only the values differ. Left to {@code bind}, a section it does not traverse could
	 * have an object replaced by a number, survive a reload, and only blow up later when the feature
	 * reading it happened to be asked a question. Checking here means a bad override is refused while
	 * the game is still on the balance it had.
	 *
	 * <p>So there is no extension point here, which is the point: a new tuning value goes in
	 * {@code default-balance.json} first, where the rest of the catalogue already lives, and the
	 * override tunes it afterwards. A config file cannot invent one.
	 *
	 * <p>One value is not walked into: a starter item's {@code item}. See {@link Where#isOpaque}.
	 */
	static void checkOverrideKeys(JsonObject defaults, JsonObject over, String where) {
		checkKeys(defaults, over, "", where, Where.ROOT);
	}

	/**
	 * Whereabouts in the file a walk has got to.
	 *
	 * <p>Only one question is asked of it, but it has to be asked structurally rather than by the
	 * look of the path: unlock ids contain dots themselves, so {@code unlocks.starter.bread.item}
	 * read as text could be an unlock called {@code starter.bread} with an item, or one called
	 * {@code starter.bread.item}. Counting the levels down from the root cannot be fooled either
	 * way.
	 */
	private enum Where {
		ROOT, UNLOCKS, UNLOCK, ANYWHERE;

		Where child(String key) {
			return switch (this) {
				case ROOT -> "unlocks".equals(key) ? UNLOCKS : ANYWHERE;
				case UNLOCKS -> UNLOCK;
				default -> ANYWHERE;
			};
		}

		/**
		 * Is this child one value rather than a little tree of them?
		 *
		 * <p>A starter item's {@code item} is a vanilla item stack, and this layer has no business
		 * knowing what is allowed inside one. Walking into it did real damage in both directions:
		 * the key check refused an override adding {@code components} to an item that had none,
		 * because the bundled file had no such key to match, and the merge folded a new components
		 * object into the old one, so a component could be changed but never removed. Treating the
		 * stack as a single value makes an override say what the item now is, whole. What is
		 * allowed inside it is the game's own codec's business, and is checked against the
		 * registries by {@link #addCheck} before any of this takes effect.
		 */
		boolean isOpaque(String key) {
			return this == UNLOCK && "item".equals(key);
		}
	}

	private static void checkKeys(JsonObject known, JsonObject over, String prefix, String where, Where at) {
		for (Map.Entry<String, JsonElement> entry : over.entrySet()) {
			String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
			JsonElement expected = known.get(entry.getKey());
			if (expected == null) {
				String closest = closestTo(entry.getKey(), known.keySet());
				String hint = closest != null
						? " Did you mean '" + closest + "'?"
						// Without the leading slash: as a message it names a file someone edits, not
						// the classpath resource the constant is.
						: " A value the override is to change has to exist in " + DEFAULT_RESOURCE.substring(1)
								+ " first.";
				throw new BalanceException("The balance override " + where + " sets '" + path
						+ "', which the bundled balance does not have." + hint);
			}

			JsonElement actual = entry.getValue();
			if (!sameKind(expected, actual)) {
				throw new BalanceException("The balance override " + where + " sets '" + path + "' to "
						+ actual + ", but the bundled balance has " + kindOf(expected) + " there.");
			}
			if (expected.isJsonObject() && !at.isOpaque(entry.getKey())) {
				checkKeys(expected.getAsJsonObject(), actual.getAsJsonObject(), path, where, at.child(entry.getKey()));
			}
		}
	}

	/** Two values the merge can swap for each other without changing the file's shape. */
	private static boolean sameKind(JsonElement expected, JsonElement actual) {
		return kindOf(expected).equals(kindOf(actual));
	}

	private static String kindOf(JsonElement element) {
		if (element.isJsonObject()) {
			return "an object";
		}
		if (element.isJsonArray()) {
			return "a list";
		}
		if (element.isJsonNull()) {
			return "null";
		}
		JsonPrimitive primitive = element.getAsJsonPrimitive();
		if (primitive.isNumber()) {
			return "a number";
		}
		return primitive.isBoolean() ? "a true/false" : "a string";
	}

	/** The known name within a typo or two of this one, or null if nothing is close enough. */
	private static String closestTo(String id, Set<String> known) {
		String best = null;
		int bestDistance = Integer.MAX_VALUE;
		for (String candidate : known) {
			int distance = editDistance(id, candidate);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = candidate;
			}
		}
		return bestDistance <= Math.max(1, Math.min(3, id.length() / 4)) ? best : null;
	}

	/** Plain Levenshtein distance, only ever run on a handful of short ids while reporting an error. */
	private static int editDistance(String a, String b) {
		int[] previous = new int[b.length() + 1];
		int[] row = new int[b.length() + 1];
		for (int j = 0; j <= b.length(); j++) {
			previous[j] = j;
		}
		for (int i = 1; i <= a.length(); i++) {
			row[0] = i;
			for (int j = 1; j <= b.length(); j++) {
				int substitute = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
				row[j] = Math.min(substitute, Math.min(previous[j] + 1, row[j - 1] + 1));
			}
			int[] swap = previous;
			previous = row;
			row = swap;
		}
		return previous[b.length()];
	}

	/**
	 * Deep-merge {@code over} into {@code base}.
	 *
	 * <p>Objects are merged key by key so an override can name one price without repeating its
	 * neighbours. Anything else — a number, a string, an array — replaces what was there.
	 *
	 * <p>Shape-blind on purpose: what each section is meant to look like is {@link #bind}'s to know,
	 * and {@link #checkOverrideKeys} has already rejected keys that do not exist.
	 *
	 * <p>The one exception is the value {@link Where#isOpaque} names, which is replaced whole like
	 * a number is. An override that gives a starter item an {@code item} is saying what that item
	 * now is, not adding to what it was.
	 */
	private static void merge(JsonObject base, JsonObject over) {
		merge(base, over, Where.ROOT);
	}

	private static void merge(JsonObject base, JsonObject over, Where at) {
		for (Map.Entry<String, JsonElement> entry : over.entrySet()) {
			JsonElement existing = base.get(entry.getKey());
			boolean mergeable = existing != null && existing.isJsonObject() && entry.getValue().isJsonObject()
					&& !at.isOpaque(entry.getKey());
			if (mergeable) {
				merge(existing.getAsJsonObject(), entry.getValue().getAsJsonObject(), at.child(entry.getKey()));
			} else {
				base.add(entry.getKey(), entry.getValue());
			}
		}
	}

	/**
	 * Turn the merged file into a snapshot, checking every value on the way.
	 *
	 * <p>Package-private rather than private so a test can hand it a file and see what it refuses.
	 */
	static Balance bind(JsonObject merged) {
		JsonObject currency = object(merged, "currency", "currency", CURRENCY_KEYS);
		JsonObject advancements = object(currency, "advancements", "currency.advancements", null);
		Map<String, Integer> rewards = new LinkedHashMap<>();
		for (String id : advancements.keySet()) {
			rewards.put(id, wholeNumber(advancements, id, "currency.advancements." + id, 0));
		}

		JsonObject unlockSection = object(merged, "unlocks", "unlocks", null);
		Map<String, Balance.UnlockBalance> unlocks = new LinkedHashMap<>();
		for (String id : unlockSection.keySet()) {
			String path = "unlocks." + id;
			JsonObject entry = object(unlockSection, id, path, UNLOCK_KEYS);
			// The item stack itself is not balance's to understand — it is handed on to the game's
			// own item codec, which is what makes an enchanted pickaxe no harder to sell than bread.
			// The count is the exception, because it is the one part of a stack that is a balance
			// number: it is checked here, with every other number in the file, so 0 or 16.5 is
			// refused rather than quietly rounded into a different amount later. There is no upper
			// bound — "128 bread" means two slots of bread, which is the chest's business, not a
			// stack-size error.
			Optional<JsonObject> item = Optional.empty();
			if (entry.has("item")) {
				JsonObject stack = object(entry, "item", path + ".item", null);
				if (stack.has("count")) {
					wholeNumber(stack, "count", path + ".item.count", 1);
				}
				item = Optional.of(stack);
			}
			unlocks.put(id,
					new Balance.UnlockBalance(id, wholeNumber(entry, "price", path + ".price", 0), item));
		}

		JsonObject borderSection = object(merged, "worldBorder", "worldBorder", null);
		Map<String, Balance.BorderBalance> borders = new LinkedHashMap<>();
		for (String id : borderSection.keySet()) {
			String path = "worldBorder." + id;
			JsonObject entry = object(borderSection, id, path, BORDER_KEYS);
			OptionalDouble size = entry.has("size")
					? OptionalDouble.of(positiveNumber(entry, "size", path + ".size"))
					: OptionalDouble.empty();
			borders.put(id, new Balance.BorderBalance(id, size, wholeNumber(entry, "price", path + ".price", 0)));
		}

		JsonObject difficulty = object(merged, "difficulty", "difficulty", DIFFICULTY_KEYS);
		double mobDamage = positiveNumber(difficulty, "mobDamageMultiplier", "difficulty.mobDamageMultiplier");

		JsonObject vanillaPlus = object(merged, "vanillaPlus", "vanillaPlus", VANILLA_PLUS_KEYS);
		JsonObject craftEnchantSection = object(vanillaPlus, "craftEnchant", "vanillaPlus.craftEnchant",
				CRAFT_ENCHANT_KEYS);
		Balance.CraftEnchantBalance craftEnchant = new Balance.CraftEnchantBalance(
				wholeNumber(craftEnchantSection, "maxUnlockLevel", "vanillaPlus.craftEnchant.maxUnlockLevel", 1),
				positiveNumber(craftEnchantSection, "strengthPerLevel",
						"vanillaPlus.craftEnchant.strengthPerLevel"));

		return new Balance(rewards, unlocks, borders, mobDamage, craftEnchant, merged);
	}

	/**
	 * @param key the key to read out of {@code parent}, which for an unlock is a dotted id
	 * @param path where that key sits in the file, for the error message
	 * @param allowedKeys the only keys this object may contain, or null when the keys are ids the
	 *     balance file is free to invent
	 */
	private static JsonObject object(JsonObject parent, String key, String path, Set<String> allowedKeys) {
		JsonElement found = parent.get(key);
		if (found == null) {
			throw new BalanceException("Balance is missing the '" + path + "' section");
		}
		if (!found.isJsonObject()) {
			throw new BalanceException("Balance section '" + path + "' should be an object, not " + found);
		}
		JsonObject object = found.getAsJsonObject();
		if (allowedKeys != null) {
			for (String name : object.keySet()) {
				if (!allowedKeys.contains(name)) {
					throw new BalanceException("Balance section '" + path + "' has an unknown key '" + name
							+ "'; it takes " + String.join(", ", allowedKeys));
				}
			}
		}
		return object;
	}

	private static int wholeNumber(JsonObject parent, String key, String path, int min) {
		double value = number(parent, key, path);
		int whole = Numbers.toInt(value, path);
		if (whole < min) {
			throw new BalanceException("Balance value '" + path + "' should be at least " + min + ", not " + whole);
		}
		return whole;
	}

	private static double positiveNumber(JsonObject parent, String key, String path) {
		double value = number(parent, key, path);
		if (!(value > 0)) {
			throw new BalanceException("Balance value '" + path + "' should be greater than 0, not " + value);
		}
		return value;
	}

	private static double number(JsonObject parent, String key, String path) {
		JsonElement found = parent.get(key);
		if (found == null) {
			throw new BalanceException("Balance is missing the value '" + path + "'");
		}
		if (!(found instanceof JsonPrimitive primitive) || !primitive.isNumber()) {
			throw new BalanceException("Balance value '" + path + "' should be a number, not " + found);
		}
		return Numbers.finite(found.getAsDouble(), path);
	}
}
