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
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
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

	private static final Set<String> UNLOCK_KEYS = Set.of("price");
	private static final Set<String> BORDER_KEYS = Set.of("size", "price");
	private static final Set<String> CURRENCY_KEYS = Set.of("advancements");
	private static final Set<String> DIFFICULTY_KEYS = Set.of("mobDamageMultiplier");

	private static volatile Balance current;

	private BalanceManager() {
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
			JsonObject over = readOverride(override);
			checkOverrideIds(merged, over, override.toString());
			merge(merged, over);
		}
		return bind(merged);
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
	 * Every id the override names must already be in the catalogue.
	 *
	 * <p>The three id maps — {@code unlocks}, {@code currency.advancements} and {@code worldBorder} —
	 * are lists of things that exist, and the bundled file is the list. Left unchecked, an override
	 * saying {@code world.ore.diamod} would merge cleanly into a brand new entry nothing reads,
	 * while the real diamond kept its bundled price: a balance change that appears to work, does
	 * nothing, and says nothing. That is the exact failure this layer exists to prevent, and the one
	 * a typo makes most easily.
	 *
	 * <p>Only these maps are checked. A new top-level section is still free to appear, because that
	 * is how a vanilla+ system gets tuning values before it has a typed accessor — see
	 * {@link Balance#number}. Adding a genuinely new unlock means adding it to
	 * {@code default-balance.json}, which is where the catalogue belongs anyway.
	 */
	static void checkOverrideIds(JsonObject defaults, JsonObject over, String where) {
		checkIds(childObject(defaults, "unlocks"), childObject(over, "unlocks"), "unlocks", "unlock", where);
		checkIds(childObject(childObject(defaults, "currency"), "advancements"),
				childObject(childObject(over, "currency"), "advancements"),
				"currency.advancements", "advancement", where);
		checkIds(childObject(defaults, "worldBorder"), childObject(over, "worldBorder"),
				"worldBorder", "border tier", where);
	}

	/** The named child if it is an object, or null — a wrong-shaped one is bind's to complain about. */
	private static JsonObject childObject(JsonObject parent, String key) {
		if (parent == null) {
			return null;
		}
		JsonElement found = parent.get(key);
		return found != null && found.isJsonObject() ? found.getAsJsonObject() : null;
	}

	private static void checkIds(JsonObject known, JsonObject over, String section, String noun, String where) {
		if (known == null || over == null) {
			return;
		}
		for (String id : over.keySet()) {
			if (known.has(id)) {
				continue;
			}
			String closest = closestTo(id, known.keySet());
			String hint = closest != null
					? " Did you mean '" + closest + "'?"
					// Without the leading slash: as a message it is the name of a file someone edits,
					// not the classpath resource the constant is.
					: " A new " + noun + " goes in " + DEFAULT_RESOURCE.substring(1) + ", not in the override.";
			throw new BalanceException("The balance override " + where + " sets '" + section + "." + id
					+ "', but there is no such " + noun + "." + hint);
		}
	}

	/** The known id within a typo or two of this one, or null if nothing is close enough. */
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
	 * and {@link #checkOverrideIds} has already rejected ids that do not exist.
	 */
	private static void merge(JsonObject base, JsonObject over) {
		for (Map.Entry<String, JsonElement> entry : over.entrySet()) {
			JsonElement existing = base.get(entry.getKey());
			if (existing != null && existing.isJsonObject() && entry.getValue().isJsonObject()) {
				merge(existing.getAsJsonObject(), entry.getValue().getAsJsonObject());
			} else {
				base.add(entry.getKey(), entry.getValue());
			}
		}
	}

	private static Balance bind(JsonObject merged) {
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
			unlocks.put(id, new Balance.UnlockBalance(id, wholeNumber(entry, "price", path + ".price", 0)));
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

		return new Balance(rewards, unlocks, borders, mobDamage, merged);
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
