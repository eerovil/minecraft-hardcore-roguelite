package fi.vilpponen.mhr;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fi.vilpponen.mhr.core.BalanceManager;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Which unlocks the player owns, and how far each repeatable one has been taken.
 *
 * <p>Deliberately stored outside the save: unlocks are permanent across runs, and every run is a
 * new world. The file lives in the config directory, so it survives deleting worlds.
 *
 * <p>The file is a JSON object of unlock id to level, where a missing id means the unlock is not
 * owned. A plain list of ids, which is what the mod wrote before unlocks had levels, still reads and
 * counts as level one each; it is rewritten in the current shape the first time it is read.
 *
 * <p>What is owned is keyed by id, not by enum constant. Most ids have an {@link Unlock} behind
 * them, because some Java asks whether they are owned. Some have none: a starter item is nothing
 * but an entry in the balance catalogue, and adding one should not mean adding a constant. Keying
 * on the id means the two kinds are the same kind here, in the file, and in the dev command —
 * which is the point of ids being strings in the first place.
 *
 * <p>Read from the worldgen threads, so the backing map is guarded by this object's monitor.
 */
public final class UnlockState {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE_NAME = "hardcore-roguelite-unlocks.json";

	/**
	 * Unlock ids that have been renamed, old name to current one.
	 *
	 * <p>A save written before the rename is migrated on load and rewritten once, so nobody loses a
	 * purchase to a refactor. An entry can be dropped once no save that old can plausibly exist —
	 * for {@code trees}, the five {@code slot_*} names and the six bare animal names that is as
	 * soon as the mod has shipped anywhere, since they predate the namespaced ids and only ever
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

	private static volatile UnlockState instance;

	private final Path file;
	private final Map<String, Integer> levels = new TreeMap<>();

	private UnlockState(Path file) {
		this.file = file;
	}

	public static UnlockState get() {
		UnlockState local = instance;
		if (local == null) {
			synchronized (UnlockState.class) {
				local = instance;
				if (local == null) {
					local = new UnlockState(FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME));
					local.load();
					instance = local;
				}
			}
		}
		return local;
	}

	public synchronized boolean isOwned(Unlock unlock) {
		return level(unlock) > 0;
	}

	/** @param id a stable unlock id, whether or not an {@link Unlock} constant carries it. */
	public synchronized boolean isOwned(String id) {
		return level(id) > 0;
	}

	/** How many times this unlock has been bought: zero when it is not owned at all. */
	public synchronized int level(Unlock unlock) {
		return level(unlock.id());
	}

	public synchronized int level(String id) {
		return levels.getOrDefault(id, 0);
	}

	/** @return true if this changed anything. */
	public synchronized boolean set(Unlock unlock, boolean value) {
		return setLevel(unlock.id(), value ? Math.max(level(unlock), 1) : 0);
	}

	/** @return true if this changed anything. */
	public synchronized boolean set(String id, boolean value) {
		return setLevel(id, value ? Math.max(level(id), 1) : 0);
	}

	/**
	 * Set an unlock's level. Clamped to what the balance data allows the unlock to reach, so callers
	 * can hand over whatever a player typed.
	 *
	 * @return true if this changed anything.
	 */
	public synchronized boolean setLevel(Unlock unlock, int level) {
		return setLevel(unlock.id(), level);
	}

	/** @return true if this changed anything. */
	public synchronized boolean setLevel(String id, int level) {
		int clamped = Math.clamp(level, 0, maxLevelOf(id));
		if (clamped == level(id)) {
			return false;
		}
		if (clamped == 0) {
			levels.remove(id);
		} else {
			levels.put(id, clamped);
		}
		save();
		return true;
	}

	/**
	 * How far an id can be taken.
	 *
	 * <p>A constant answers for itself, from balance data. Everything else the catalogue sells — a
	 * starter item, or an unlock whose code has not been written yet — is bought once and no more.
	 * An id that is in neither is not clamped at all: see {@link #put}.
	 */
	private static int maxLevelOf(String id) {
		Unlock unlock = Unlock.byId(id);
		return unlock == null ? 1 : unlock.maxLevel();
	}

	public synchronized String describe() {
		if (levels.isEmpty()) {
			return "(nothing)";
		}
		StringBuilder description = new StringBuilder();
		for (Map.Entry<String, Integer> entry : sortedById().entrySet()) {
			if (!description.isEmpty()) {
				description.append(", ");
			}
			description.append(entry.getKey());
			if (entry.getValue() > 1) {
				description.append(' ').append(entry.getValue());
			}
		}
		return description.toString();
	}

	/** The owned ids that a constant is named after. Ones with no constant are simply not here. */
	public synchronized Set<Unlock> owned() {
		Set<Unlock> owned = EnumSet.noneOf(Unlock.class);
		for (String id : levels.keySet()) {
			Unlock unlock = Unlock.byId(id);
			if (unlock != null) {
				owned.add(unlock);
			}
		}
		return Collections.unmodifiableSet(owned);
	}

	/** Every owned id, including the ones no {@link Unlock} constant carries. */
	public synchronized Set<String> ownedIds() {
		return Set.copyOf(levels.keySet());
	}

	private synchronized Map<String, Integer> sortedById() {
		return new TreeMap<>(levels);
	}

	private synchronized void load() {
		if (!Files.isRegularFile(file)) {
			return;
		}
		boolean migrated;
		try (Reader reader = Files.newBufferedReader(file)) {
			JsonElement root = JsonParser.parseReader(reader);
			if (root == null || root.isJsonNull()) {
				return;
			}
			// A list of ids is the shape from before unlocks had levels, and is worth rewriting on
			// its own, so the file on disk always says what the code says.
			migrated = root.isJsonArray()
					? readOwnedIds(root.getAsJsonArray())
					: readLevels(root.getAsJsonObject());
		} catch (IOException | RuntimeException e) {
			HardcoreRoguelite.LOGGER.error("Could not read {}, starting with nothing unlocked", file, e);
			return;
		}

		// Write the current shape and the current names back straight away, so a migration happens
		// once rather than on every start.
		if (migrated) {
			save();
		}
	}

	/** The shape from before unlocks had levels: a bare list of the ids owned. */
	private synchronized boolean readOwnedIds(JsonArray ids) {
		for (JsonElement id : ids) {
			put(id.getAsString(), 1);
		}
		return true;
	}

	/** @return true if anything read had to be migrated. */
	private synchronized boolean readLevels(JsonObject byId) {
		boolean migrated = false;
		for (Map.Entry<String, JsonElement> entry : byId.entrySet()) {
			migrated |= put(entry.getKey(), entry.getValue().getAsInt());
		}
		return migrated;
	}

	/**
	 * Take one id and level out of the file.
	 *
	 * <p>An id this build cannot act on is still kept, and still written back. A purchase is
	 * permanent, and this build not knowing what to do with one is a fact about this build, not
	 * about the purchase: pull a starter item out of the catalogue for a release and put it back in
	 * the next, and the player still owns it, where dropping it on load would have quietly spent
	 * their currency for them. It is not clamped either, because nothing here knows what its
	 * ceiling would be. Everything that acts on an unlock asks for the one it cares about by id, so
	 * one that resolves to nothing simply never matches.
	 *
	 * @return true if the id had been renamed since the file was written.
	 */
	private synchronized boolean put(String id, int level) {
		String current = RENAMED_IDS.getOrDefault(id, id);
		boolean renamed = !current.equals(id);
		if (renamed) {
			HardcoreRoguelite.LOGGER.info("Unlock '{}' is now called '{}'", id, current);
		}

		int kept = level;
		if (isKnown(current)) {
			kept = Math.clamp(level, 0, maxLevelOf(current));
			if (kept != level) {
				HardcoreRoguelite.LOGGER.warn("Clamping unlock '{}' level {} to {} in {}", current, level, kept, file);
			}
		} else {
			HardcoreRoguelite.LOGGER.warn(
					"Nothing in this build sells unlock '{}', which {} says is owned."
							+ " Keeping it: a purchase is permanent, and it works again if it comes back.",
					current, file);
		}

		if (kept > 0) {
			levels.put(current, kept);
		}
		return renamed;
	}

	/** Is this an id anything in this build can act on — a constant, or something the shop sells? */
	private static boolean isKnown(String id) {
		return Unlock.byId(id) != null || BalanceManager.get().unlock(id).isPresent();
	}

	private synchronized void save() {
		try {
			Files.createDirectories(file.getParent());
			try (Writer writer = Files.newBufferedWriter(file)) {
				GSON.toJson(sortedById(), writer);
			}
		} catch (IOException e) {
			HardcoreRoguelite.LOGGER.error("Could not write {}", file, e);
		}
	}
}
