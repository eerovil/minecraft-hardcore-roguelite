package fi.vilpponen.mhr.progression;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import fi.vilpponen.mhr.HardcoreRoguelite;
import fi.vilpponen.mhr.Unlock;
import fi.vilpponen.mhr.core.AtomicFile;
import fi.vilpponen.mhr.core.PersistenceException;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Everything the player keeps between runs, in one file and one write.
 *
 * <p>Currency and what is owned are two halves of the same thing: a purchase moves both, and a
 * state where one has moved and the other has not is not a state the game should ever be in. They
 * used to live in a file each, which meant a purchase was two writes with a gap in the middle, and
 * no amount of ordering, journalling or recovery makes two writes into one. So there is one file,
 * holding both, replaced whole:
 *
 * <pre>
 * {
 *   "currency": 35,
 *   "unlocks": { "world.trees": 1, "player.craft.enchant": 2 }
 * }
 * </pre>
 *
 * <p>The rule every change here follows is <b>write, then adopt</b>. A change is built as a whole
 * new snapshot, written through {@link AtomicFile} — which leaves the file either wholly as it was
 * or wholly as it is being asked to be — and only once that has landed does this object start
 * answering with the new values. So a write that fails changes nothing at all: not the file, not
 * memory, not what the running game believes. There is nothing half-applied to notice, report or
 * recover from.
 *
 * <p>"Landed" means the atomic rename {@link AtomicFile} finishes with, and because that rename is
 * the last thing it does, a failed write is always a write that did not happen. There is one case
 * to handle and not a taxonomy of them: keep the old snapshot, and tell the caller the change did
 * not happen.
 *
 * <p>Stored in the Fabric config directory rather than in a world, because a run is disposable and
 * progression is not. See {@code docs/codebase/progression.md}.
 *
 * <p><b>Reading fails closed.</b> No file at all is a new player and starts from nothing. A file
 * that is there and cannot be read is something else entirely, and this refuses to load rather than
 * carrying on as though the player had bought nothing — because a profile that starts empty is a
 * profile the next purchase writes over, and the purchases that could not be read would be gone for
 * good. The game stops at startup with the file named, the same way a broken balance file stops it.
 *
 * <p>{@link fi.vilpponen.mhr.UnlockState} and {@link Wallet} are the two views feature code talks
 * to. Neither of them owns anything; this does.
 *
 * <p>Nothing here decides how currency is earned. That is
 * {@link fi.vilpponen.mhr.earn.AdvancementPayouts}, which pays for advancements finished inside a
 * run; this only holds the number it lands on.
 */
public final class Progress {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE_NAME = "hardcore-roguelite-progress.json";

	/** The two files this replaced. Read once, if they are there and the snapshot is not. */
	private static final String LEGACY_UNLOCKS = "hardcore-roguelite-unlocks.json";
	private static final String LEGACY_CURRENCY = "hardcore-roguelite-currency.json";

	private static final String CURRENCY = "currency";
	private static final String UNLOCKS = "unlocks";

	/**
	 * Unlock ids that have been renamed, old name to current one.
	 *
	 * <p>A profile written before the rename is migrated as it is read, so nobody loses a purchase
	 * to a refactor. Only the legacy files can still hold these: the snapshot has only ever been
	 * written in current names. An entry can be dropped once no profile that old can plausibly
	 * exist — for {@code trees}, the five {@code slot_*} names and the six bare animal names that is
	 * as soon as the mod has shipped anywhere, since they predate the namespaced ids and only ever
	 * existed in development.
	 */
	private static final Map<String, String> RENAMED_IDS = Map.ofEntries(
			Map.entry("trees", "world.trees"),
			Map.entry("cow", "world.animal.cow"),
			Map.entry("pig", "world.animal.pig"),
			Map.entry("sheep", "world.animal.sheep"),
			Map.entry("chicken", "world.animal.chicken"),
			Map.entry("horse", "world.animal.horse"),
			Map.entry("wolf", "world.animal.wolf"),
			Map.entry("slot_helmet", "player.slot.helmet"),
			Map.entry("slot_chestplate", "player.slot.chestplate"),
			Map.entry("slot_leggings", "player.slot.leggings"),
			Map.entry("slot_boots", "player.slot.boots"),
			Map.entry("slot_offhand", "player.slot.offhand"));

	private static volatile Progress instance;

	private final Path file;
	private int currency;
	private Map<String, Integer> levels = Map.of();

	private Progress(Path file) {
		this.file = file;
	}

	public static Progress get() {
		Progress local = instance;
		if (local == null) {
			synchronized (Progress.class) {
				local = instance;
				if (local == null) {
					local = new Progress(FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME));
					local.load();
					instance = local;
				}
			}
		}
		return local;
	}

	/**
	 * Throw away what is loaded and read the file again.
	 *
	 * <p>Nothing in the game needs this: one process is one player's progression from launch to
	 * exit, and every change is written through as it is made. It exists for the automated tests,
	 * where the dedicated server runs inside the client's own process — so a test that wants to know
	 * whether a purchase really reached the disk has no process boundary to cross and has to ask for
	 * one. Between two runs is the honest place to call it, because that is where a real player
	 * would have quit the game.
	 */
	public static Progress reloadFromFile() {
		synchronized (Progress.class) {
			instance = null;
			return get();
		}
	}

	/** Where the snapshot lives. Public so a test can get in the way of it on purpose. */
	public static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
	}

	public synchronized int currency() {
		return currency;
	}

	public synchronized int level(String id) {
		return levels.getOrDefault(id, 0);
	}

	/** Every owned id and how far it has been taken, sorted, as a copy. */
	public synchronized Map<String, Integer> levels() {
		return levels;
	}

	/**
	 * A purchase: the currency left and the level reached, committed together.
	 *
	 * <p>This is the whole reason the two halves share a file. One write, so there is no moment at
	 * which one of them has happened and the other has not.
	 *
	 * @throws PersistenceException if it did not reach the disk, in which case nothing changed
	 */
	public synchronized void buy(String id, int level, int currencyLeft) {
		Map<String, Integer> next = new TreeMap<>(levels);
		put(next, id, level);
		commit(currencyLeft, next);
	}

	/**
	 * @throws PersistenceException if it did not reach the disk, in which case nothing changed
	 */
	public synchronized void setCurrency(int amount) {
		commit(Math.max(0, amount), levels);
	}

	/**
	 * Set one unlock's level, already clamped by whoever knows its ceiling.
	 *
	 * @throws PersistenceException if it did not reach the disk, in which case nothing changed
	 */
	public synchronized void setLevel(String id, int level) {
		Map<String, Integer> next = new TreeMap<>(levels);
		put(next, id, level);
		commit(currency, next);
	}

	private static void put(Map<String, Integer> levels, String id, int level) {
		if (level <= 0) {
			levels.remove(id);
		} else {
			levels.put(id, level);
		}
	}

	/**
	 * Write the whole snapshot, and adopt it only if the write worked.
	 *
	 * <p>The order is the entire point. Changing memory first and writing afterwards is what leaves
	 * a running game believing something the disk has never heard of.
	 */
	private synchronized void commit(int nextCurrency, Map<String, Integer> nextLevels) {
		JsonObject root = new JsonObject();
		root.addProperty(CURRENCY, nextCurrency);
		JsonObject unlocks = new JsonObject();
		for (Map.Entry<String, Integer> entry : nextLevels.entrySet()) {
			unlocks.addProperty(entry.getKey(), entry.getValue());
		}
		root.add(UNLOCKS, unlocks);

		try {
			AtomicFile.write(file, GSON.toJson(root));
		} catch (IOException e) {
			throw new PersistenceException("Could not write " + file, e);
		}

		currency = nextCurrency;
		levels = Collections.unmodifiableMap(new TreeMap<>(nextLevels));
	}

	private synchronized void load() {
		if (Files.isRegularFile(file)) {
			readSnapshot();
			return;
		}
		migrateFromTheOldFiles();
	}

	/**
	 * @throws PersistenceException if the file is there and cannot be read. Starting empty would be
	 *     a lie that the next purchase makes permanent.
	 */
	private synchronized void readSnapshot() {
		try (Reader reader = Files.newBufferedReader(file)) {
			JsonElement root = JsonParser.parseReader(reader);
			if (root == null || !root.isJsonObject()) {
				throw malformed("it is not an object");
			}
			JsonObject json = root.getAsJsonObject();

			// Both fields are required, both are checked here, and this is the only place that says
			// what a snapshot is. A file missing one of them is a damaged file, not a player who
			// happens to own nothing: reading it as an empty profile is how the next purchase writes
			// that emptiness over something that could have been repaired.
			currency = Math.max(0, wholeNumber(json, CURRENCY));
			levels = Collections.unmodifiableMap(levelsIn(object(json, UNLOCKS)));
		} catch (IOException | RuntimeException e) {
			throw failedToRead(file, e);
		}
	}

	/** @throws PersistenceException unless the field is there and holds a whole number. */
	private int wholeNumber(JsonObject json, String field) {
		JsonElement value = json.get(field);
		if (value == null) {
			throw malformed("it has no '" + field + "'");
		}
		if (!(value instanceof JsonPrimitive primitive) || !primitive.isNumber()) {
			throw malformed("'" + field + "' should be a whole number and is " + value);
		}
		double raw = primitive.getAsDouble();
		if (!Double.isFinite(raw) || raw != Math.rint(raw) || Math.abs(raw) > Integer.MAX_VALUE) {
			throw malformed("'" + field + "' should be a whole number and is " + value);
		}
		return (int) raw;
	}

	/** @throws PersistenceException unless the field is there and holds an object. */
	private JsonObject object(JsonObject json, String field) {
		JsonElement value = json.get(field);
		if (value == null) {
			throw malformed("it has no '" + field + "'");
		}
		if (!value.isJsonObject()) {
			throw malformed("'" + field + "' should be an object and is " + value);
		}
		return value.getAsJsonObject();
	}

	private PersistenceException malformed(String what) {
		return new PersistenceException("Progression in " + file + " is damaged: " + what
				+ ". The game is stopping rather than starting as though nothing had ever been bought."
				+ " Fix or move the file and start again.", null);
	}

	/**
	 * One message for every way progression can be unreadable, and one thing to do about it.
	 *
	 * <p>Deliberately fatal. The file still holds whatever it holds, so the player's purchases are
	 * where they were and a fixed file brings them back; the one outcome that cannot be undone is
	 * playing on from nothing and then buying something.
	 */
	private static PersistenceException failedToRead(Path file, Throwable cause) {
		if (cause instanceof PersistenceException already) {
			return already;
		}
		return new PersistenceException(
				"Could not read " + file + ", so the game is stopping rather than starting as though"
						+ " nothing had ever been bought. Fix or move the file and start again.", cause);
	}

	/**
	 * Take what the two old files said and write it as one snapshot.
	 *
	 * <p>Runs once, the first time a profile written by an older build is loaded. The old files are
	 * left where they are: the snapshot is what is read from now on, and a purchase that has already
	 * been paid for is not something to risk on a tidy-up.
	 */
	private synchronized void migrateFromTheOldFiles() {
		Path directory = file.getParent();
		Path oldUnlocks = directory.resolve(LEGACY_UNLOCKS);
		Path oldCurrency = directory.resolve(LEGACY_CURRENCY);
		if (!Files.isRegularFile(oldUnlocks) && !Files.isRegularFile(oldCurrency)) {
			// A player who has never bought anything. Nothing to carry over, and nothing is written
			// until they do.
			return;
		}

		// Every source that is there has to be read whole before anything is written. A source that
		// is present and unreadable used to count as empty, which turned a file that could have been
		// repaired into a snapshot saying those purchases never happened.
		Map<String, Integer> read = readLegacyUnlocks(oldUnlocks);
		int total = readLegacyCurrency(oldCurrency);

		HardcoreRoguelite.LOGGER.info("Moving progression into one file: {} unlock(s) and {} currency from {}",
				read.size(), total, directory);
		commit(total, read);
	}

	/** @throws PersistenceException if the file is there and cannot be read whole. */
	private static Map<String, Integer> readLegacyUnlocks(Path oldUnlocks) {
		Map<String, Integer> read = new TreeMap<>();
		if (!Files.isRegularFile(oldUnlocks)) {
			return read;
		}
		try (Reader reader = Files.newBufferedReader(oldUnlocks)) {
			JsonElement root = JsonParser.parseReader(reader);
			if (root == null || root.isJsonNull()) {
				return read;
			}
			if (root.isJsonArray()) {
				// The shape from before unlocks had levels: a bare list of the ids owned.
				for (JsonElement id : (JsonArray) root) {
					take(read, id.getAsString(), 1);
				}
				return read;
			}
			read.putAll(levelsIn(root.getAsJsonObject()));
		} catch (IOException | RuntimeException e) {
			throw failedToRead(oldUnlocks, e);
		}
		return read;
	}

	/** @throws PersistenceException if the file is there and cannot be read whole. */
	private static int readLegacyCurrency(Path oldCurrency) {
		if (!Files.isRegularFile(oldCurrency)) {
			return 0;
		}
		try (Reader reader = Files.newBufferedReader(oldCurrency)) {
			JsonElement root = JsonParser.parseReader(reader);
			if (root == null || root.isJsonNull()) {
				return 0;
			}
			JsonElement total = root.getAsJsonObject().get("balance");
			return total == null ? 0 : Math.max(0, total.getAsInt());
		} catch (IOException | RuntimeException e) {
			throw failedToRead(oldCurrency, e);
		}
	}

	/** The id-to-level map out of a JSON object, with renames applied and known levels clamped. */
	private static Map<String, Integer> levelsIn(JsonObject json) {
		Map<String, Integer> read = new TreeMap<>();
		for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
			take(read, entry.getKey(), entry.getValue().getAsInt());
		}
		return read;
	}

	/**
	 * Take one id and level out of a file.
	 *
	 * <p>An id this build cannot act on is still kept. A purchase is permanent, and this build not
	 * knowing what to do with one is a fact about this build, not about the purchase: pull a starter
	 * item out of the catalogue for a release and put it back in the next, and the player still owns
	 * it, where dropping it on load would have quietly spent their currency for them. It is not
	 * clamped either, because nothing here knows what its ceiling would be. Everything that acts on
	 * an unlock asks for the one it cares about by id, so one that resolves to nothing never matches.
	 */
	private static void take(Map<String, Integer> levels, String id, int level) {
		String current = RENAMED_IDS.getOrDefault(id, id);
		if (!current.equals(id)) {
			HardcoreRoguelite.LOGGER.info("Unlock '{}' is now called '{}'", id, current);
		}

		Unlock unlock = Unlock.byId(current);
		int kept = level;
		if (unlock != null) {
			kept = Math.clamp(level, 0, unlock.maxLevel());
			if (kept != level) {
				HardcoreRoguelite.LOGGER.warn("Clamping unlock '{}' level {} to {}", current, level, kept);
			}
		}
		if (kept > 0) {
			levels.put(current, kept);
		}
	}

}
