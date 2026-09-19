package fi.vilpponen.mhr;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumMap;
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
	private final Map<Unlock, Integer> levels = new EnumMap<>(Unlock.class);

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

	/** How many times this unlock has been bought: zero when it is not owned at all. */
	public synchronized int level(Unlock unlock) {
		return levels.getOrDefault(unlock, 0);
	}

	/** @return true if this changed anything. */
	public synchronized boolean set(Unlock unlock, boolean value) {
		return setLevel(unlock, value ? Math.max(level(unlock), 1) : 0);
	}

	/**
	 * Set an unlock's level. Clamped to what the balance data allows the unlock to reach, so callers
	 * can hand over whatever a player typed.
	 *
	 * @return true if this changed anything.
	 */
	public synchronized boolean setLevel(Unlock unlock, int level) {
		int clamped = Math.clamp(level, 0, unlock.maxLevel());
		if (clamped == level(unlock)) {
			return false;
		}
		if (clamped == 0) {
			levels.remove(unlock);
		} else {
			levels.put(unlock, clamped);
		}
		save();
		return true;
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

	public synchronized Set<Unlock> owned() {
		Set<Unlock> owned = EnumSet.noneOf(Unlock.class);
		owned.addAll(levels.keySet());
		return Collections.unmodifiableSet(owned);
	}

	private synchronized Map<String, Integer> sortedById() {
		Map<String, Integer> byId = new TreeMap<>();
		levels.forEach((unlock, level) -> byId.put(unlock.id(), level));
		return byId;
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

	/** @return true if the id had been renamed since the file was written. */
	private synchronized boolean put(String id, int level) {
		String current = RENAMED_IDS.getOrDefault(id, id);
		Unlock unlock = Unlock.byId(current);
		if (unlock == null) {
			HardcoreRoguelite.LOGGER.warn("Ignoring unknown unlock id '{}' in {}", id, file);
			return false;
		}
		boolean renamed = !current.equals(id);
		if (renamed) {
			HardcoreRoguelite.LOGGER.info("Unlock '{}' is now called '{}'", id, current);
		}

		int clamped = Math.clamp(level, 0, unlock.maxLevel());
		if (clamped != level) {
			HardcoreRoguelite.LOGGER.warn("Clamping unlock '{}' level {} to {} in {}", current, level, clamped, file);
		}
		if (clamped > 0) {
			levels.put(unlock, clamped);
		}
		return renamed;
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
