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
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.world.level.storage.LevelResource;

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
 *   "unlocks": { "world.village": 1, "player.craft.enchant": 2 },
 *   "paidAdvancements": { "run": 7, "entries": ["&lt;player uuid&gt;|minecraft:story/mine_stone"] }
 * }
 * </pre>
 *
 * <p>The third field is there for the same reason as the first two. Currency is earned from things
 * Minecraft records in its own files on its own schedule, so "have I already paid for this?" has to
 * be answered from the same write that moved the money — see {@link #creditOnce}.
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
 * <p><b>One save is one roguelite profile.</b> The file lives at the root of the Minecraft save,
 * next to {@code level.dat} and the run record, and is bound to it when a server starts and let go
 * when it stops. The run's overworld, nether and end are thrown away between runs; the save root is
 * not, so progression outlives every run in it. A different save is a different profile and starts
 * from nothing, and nothing is ever read from, or copied out of, the installation's config
 * directory. See {@code docs/codebase/progression.md}.
 *
 * <p><b>Reading fails closed.</b> No file at all is a new profile and starts from nothing. A file
 * that is there and cannot be read is something else entirely, and this refuses to load rather than
 * carrying on as though the player had bought nothing — because a profile that starts empty is a
 * profile the next purchase writes over, and the purchases that could not be read would be gone for
 * good. The save stops opening with the file named, the same way a broken balance file stops the
 * game.
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

	private static final String CURRENCY = "currency";
	private static final String UNLOCKS = "unlocks";

	/**
	 * The ledger of what has already been paid for in the run being played.
	 *
	 * <pre>
	 * "paidAdvancements": { "run": 7, "entries": ["&lt;player uuid&gt;|minecraft:story/mine_stone"] }
	 * </pre>
	 *
	 * <p>Optional on read, unlike the other two fields, and that is not a relaxation of the
	 * fail-closed rule: a profile written before this existed has none, and an absent ledger means
	 * "nothing has been paid for in a run yet", which is the one reading that cannot cost anybody
	 * anything. A ledger that is <em>there</em> and the wrong shape is damaged like everything else.
	 */
	private static final String PAID = "paidAdvancements";
	private static final String PAID_RUN = "run";
	private static final String PAID_ENTRIES = "entries";

	/** The snapshot of the save that is open, or null when no save is. */
	private static volatile Path openFile;

	private static volatile Progress instance;

	private final Path file;
	private int currency;
	private Map<String, Integer> levels = Map.of();

	/** The run the ledger below belongs to, or 0 for "no run has been paid for yet". */
	private int paidRun;

	/** What has already been paid for in that run. Empty for any other run. */
	private Set<String> paidKeys = Set.of();

	private Progress(Path file) {
		this.file = file;
	}

	/**
	 * Bind progression to a save's lifetime: read when its server starts, let go when it stops.
	 *
	 * <p>{@code SERVER_STARTING} is before the server loads a single level, so nothing in the save —
	 * worldgen included — can ask what is owned before the answer is this save's.
	 */
	public static void register() {
		ServerLifecycleEvents.SERVER_STARTING.register(server -> open(server.getWorldPath(LevelResource.ROOT)));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> close());
	}

	/**
	 * Make this save's snapshot the one every view answers from, and read it now.
	 *
	 * <p>Read eagerly so that a damaged file stops the save opening, with the file named, rather than
	 * the first purchase finding out.
	 *
	 * @throws PersistenceException if the save has a snapshot that cannot be read; no save is then
	 *     open, so nothing can be bought on top of it
	 */
	public static Progress open(Path saveRoot) {
		synchronized (Progress.class) {
			if (openFile != null) {
				HardcoreRoguelite.LOGGER.warn("Opening progression in {} while {} was still open",
						saveRoot, openFile);
			}
			openFile = saveRoot.resolve(FILE_NAME).toAbsolutePath().normalize();
			instance = null;
			try {
				Progress opened = get();
				HardcoreRoguelite.LOGGER.info("Progression for this save is {}. Unlocked: {}. Currency: {}.",
						openFile, opened.levels(), opened.currency());
				return opened;
			} catch (RuntimeException unreadable) {
				openFile = null;
				throw unreadable;
			}
		}
	}

	/** Let go of the save. Until the next one opens there is no progression to ask about. */
	public static void close() {
		synchronized (Progress.class) {
			openFile = null;
			instance = null;
		}
	}

	/**
	 * The open save's progression.
	 *
	 * @throws IllegalStateException if no save is open. Answering "nothing owned" instead would be a
	 *     profile nobody has, and the next write would put it somewhere.
	 */
	public static Progress get() {
		Progress local = instance;
		if (local == null) {
			synchronized (Progress.class) {
				local = instance;
				if (local == null) {
					local = new Progress(file());
					local.load();
					instance = local;
				}
			}
		}
		return local;
	}

	/**
	 * Throw away what is loaded and read the open save's file again.
	 *
	 * <p>Nothing in the game needs this: one open save is one profile from start to stop, and every
	 * change is written through as it is made. It exists for the automated tests, where the
	 * dedicated server runs inside the client's own process — so a test that wants to know whether a
	 * purchase really reached the disk has no process boundary to cross and has to ask for one.
	 * Between two runs is the honest place to call it, because that is where a real player would
	 * have quit the game.
	 */
	public static Progress reloadFromFile() {
		synchronized (Progress.class) {
			instance = null;
			return get();
		}
	}

	/**
	 * Where the open save's snapshot lives. Public so a test can get in the way of it on purpose.
	 *
	 * @throws IllegalStateException if no save is open
	 */
	public static Path file() {
		Path local = openFile;
		if (local == null) {
			throw new IllegalStateException("No Minecraft save is open, so there is no roguelite"
					+ " progression to read. Progression belongs to a save; see"
					+ " docs/codebase/progression.md.");
		}
		return local;
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
		commit(currencyLeft, next, paidRun, paidKeys);
	}

	/**
	 * @throws PersistenceException if it did not reach the disk, in which case nothing changed
	 */
	public synchronized void setCurrency(int amount) {
		commit(Math.max(0, amount), levels, paidRun, paidKeys);
	}

	/** Has this key already been paid for in this run? */
	public synchronized boolean hasPaid(int runId, String key) {
		return runId == paidRun && paidKeys.contains(key);
	}

	/**
	 * Pay for something once in a run, and write down that it has been paid for, together.
	 *
	 * <p>The together is the whole of it. Earning is triggered by things Minecraft keeps in its own
	 * files — a player's advancements are saved on the player-save cycle, not when this is written —
	 * so "have I paid for this already?" cannot be asked of them: a crash between the two saves
	 * leaves a credited purse and a record with no trace of what it was credited for, and the same
	 * milestone mints the money again on the way back. So the answer lives here, in the snapshot the
	 * currency is in, written in the one commit that moves the currency. Either both landed or
	 * neither did.
	 *
	 * <p>The ledger belongs to one run. A credit from a different run replaces it rather than
	 * growing it, which is both the "every run earns the same milestones again" rule and the reason
	 * this cannot grow without bound. Run ids are never reused — a run abandoned while it was being
	 * built spends its id — so an old entry can never be mistaken for a current one.
	 *
	 * @param runId the run being played, which must not be {@code 0}
	 * @param key what is being paid for, unique within a run and the caller's to compose
	 * @return false if this key has already been paid for in this run, in which case nothing was
	 *     written and nothing was charged
	 * @throws PersistenceException if it did not reach the disk, in which case nothing changed
	 */
	public synchronized boolean creditOnce(int runId, String key, int amount) {
		if (amount < 0) {
			throw new IllegalArgumentException("Cannot credit a negative amount: " + amount);
		}
		if (hasPaid(runId, key)) {
			return false;
		}
		// A different run starts the ledger again; the same run adds to it.
		Set<String> nextKeys = runId == paidRun ? new TreeSet<>(paidKeys) : new TreeSet<>();
		nextKeys.add(key);
		commit(add(currency, amount), levels, runId, nextKeys);
		return true;
	}

	/** Currency, added without wrapping round into a debt. */
	private static int add(int total, int amount) {
		return (int) Math.min(Integer.MAX_VALUE, (long) total + amount);
	}

	/**
	 * Set one unlock's level, already clamped by whoever knows its ceiling.
	 *
	 * @throws PersistenceException if it did not reach the disk, in which case nothing changed
	 */
	public synchronized void setLevel(String id, int level) {
		Map<String, Integer> next = new TreeMap<>(levels);
		put(next, id, level);
		commit(currency, next, paidRun, paidKeys);
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
	private synchronized void commit(int nextCurrency, Map<String, Integer> nextLevels,
			int nextPaidRun, Set<String> nextPaidKeys) {
		JsonObject root = new JsonObject();
		root.addProperty(CURRENCY, nextCurrency);
		JsonObject unlocks = new JsonObject();
		for (Map.Entry<String, Integer> entry : nextLevels.entrySet()) {
			unlocks.addProperty(entry.getKey(), entry.getValue());
		}
		root.add(UNLOCKS, unlocks);

		JsonObject paid = new JsonObject();
		paid.addProperty(PAID_RUN, nextPaidRun);
		JsonArray entries = new JsonArray();
		for (String key : nextPaidKeys) {
			entries.add(key);
		}
		paid.add(PAID_ENTRIES, entries);
		root.add(PAID, paid);

		try {
			AtomicFile.write(file, GSON.toJson(root));
		} catch (IOException e) {
			throw new PersistenceException("Could not write " + file, e);
		}

		currency = nextCurrency;
		levels = Collections.unmodifiableMap(new TreeMap<>(nextLevels));
		paidRun = nextPaidRun;
		paidKeys = Collections.unmodifiableSet(new TreeSet<>(nextPaidKeys));
	}

	/** No file is a save that has never bought or earned anything, and nothing is written until it does. */
	private synchronized void load() {
		if (Files.isRegularFile(file)) {
			readSnapshot();
		}
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
			readPaidLedger(json);
		} catch (IOException | RuntimeException e) {
			throw failedToRead(file, e);
		}
	}

	/**
	 * The ledger, which a profile written before it existed simply does not have.
	 *
	 * <p>Absent is a real answer here and nowhere else in this file: it says nothing has been paid
	 * for in a run yet, and the worst that reading it wrongly could do is pay for a milestone once
	 * more. Absent currency or absent unlocks are the opposite — they claim purchases never
	 * happened, and the next write makes that permanent. A ledger that is present and the wrong
	 * shape is damaged, like everything else.
	 *
	 * @throws PersistenceException if it is there and is not a run and a list of keys
	 */
	private void readPaidLedger(JsonObject json) {
		paidRun = 0;
		paidKeys = Set.of();
		JsonElement value = json.get(PAID);
		if (value == null || value.isJsonNull()) {
			return;
		}
		if (!value.isJsonObject()) {
			throw malformed("'" + PAID + "' should be an object and is " + value);
		}
		JsonObject ledger = value.getAsJsonObject();
		int run = wholeNumber(ledger, PAID_RUN);
		JsonElement entries = ledger.get(PAID_ENTRIES);
		if (!(entries instanceof JsonArray list)) {
			throw malformed("'" + PAID + "." + PAID_ENTRIES + "' should be a list and is " + entries);
		}
		Set<String> keys = new TreeSet<>();
		for (JsonElement entry : list) {
			if (!(entry instanceof JsonPrimitive primitive) || !primitive.isString()) {
				throw malformed("'" + PAID + "." + PAID_ENTRIES + "' should hold only keys and holds "
						+ entry);
			}
			keys.add(primitive.getAsString());
		}
		paidRun = Math.max(0, run);
		paidKeys = Collections.unmodifiableSet(keys);
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

	/** The id-to-level map out of a JSON object, with known levels clamped. */
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
		Unlock unlock = Unlock.byId(id);
		int kept = level;
		if (unlock != null) {
			kept = Math.clamp(level, 0, unlock.maxLevel());
			if (kept != level) {
				HardcoreRoguelite.LOGGER.warn("Clamping unlock '{}' level {} to {}", id, level, kept);
			}
		}
		if (kept > 0) {
			levels.put(id, kept);
		}
	}

}
