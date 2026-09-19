package fi.vilpponen.mhr;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Which unlocks the player owns.
 *
 * <p>Deliberately stored outside the save: unlocks are permanent across runs, and every run is a
 * new world. The file lives in the config directory, so it survives deleting worlds.
 *
 * <p>Read from the worldgen threads, so the backing set is guarded by this object's monitor.
 */
public final class UnlockState {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE_NAME = "hardcore-roguelite-unlocks.json";

	/**
	 * Unlock ids that have been renamed, old name to current one.
	 *
	 * <p>A save written before the rename is migrated on load and rewritten once, so nobody loses a
	 * purchase to a refactor. An entry can be dropped once no save that old can plausibly exist —
	 * for {@code trees} and the five {@code slot_*} names that is as soon as the mod has shipped
	 * anywhere, since they predate the namespaced ids and only ever existed in development.
	 */
	private static final Map<String, String> RENAMED_IDS = Map.of(
			"trees", "world.trees",
			"slot_helmet", "player.slot.helmet",
			"slot_chestplate", "player.slot.chestplate",
			"slot_leggings", "player.slot.leggings",
			"slot_boots", "player.slot.boots",
			"slot_offhand", "player.slot.offhand");

	private static volatile UnlockState instance;

	private final Path file;
	private final Set<Unlock> owned = EnumSet.noneOf(Unlock.class);

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
		return owned.contains(unlock);
	}

	/** @return true if this changed anything. */
	public synchronized boolean set(Unlock unlock, boolean value) {
		boolean changed = value ? owned.add(unlock) : owned.remove(unlock);
		if (changed) {
			save();
		}
		return changed;
	}

	public synchronized String describe() {
		if (owned.isEmpty()) {
			return "(nothing)";
		}
		Set<String> ids = new TreeSet<>();
		owned.forEach(unlock -> ids.add(unlock.id()));
		return String.join(", ", ids);
	}

	public synchronized Set<Unlock> owned() {
		return Collections.unmodifiableSet(EnumSet.copyOf(owned.isEmpty() ? EnumSet.noneOf(Unlock.class) : owned));
	}

	private synchronized void load() {
		if (!Files.isRegularFile(file)) {
			return;
		}
		boolean migrated = false;
		try (Reader reader = Files.newBufferedReader(file)) {
			String[] ids = GSON.fromJson(reader, String[].class);
			if (ids == null) {
				return;
			}
			for (String id : ids) {
				String current = RENAMED_IDS.getOrDefault(id, id);
				Unlock unlock = Unlock.byId(current);
				if (unlock == null) {
					HardcoreRoguelite.LOGGER.warn("Ignoring unknown unlock id '{}' in {}", id, file);
				} else {
					if (!current.equals(id)) {
						HardcoreRoguelite.LOGGER.info("Unlock '{}' is now called '{}'", id, current);
						migrated = true;
					}
					owned.add(unlock);
				}
			}
		} catch (IOException | RuntimeException e) {
			HardcoreRoguelite.LOGGER.error("Could not read {}, starting with nothing unlocked", file, e);
			return;
		}

		// Write the new names back straight away, so the migration happens once rather than on
		// every start, and so the file on disk always says what the code says.
		if (migrated) {
			save();
		}
	}

	private synchronized void save() {
		Set<String> ids = new TreeSet<>();
		owned.forEach(unlock -> ids.add(unlock.id()));
		try {
			Files.createDirectories(file.getParent());
			try (Writer writer = Files.newBufferedWriter(file)) {
				GSON.toJson(ids, writer);
			}
		} catch (IOException e) {
			HardcoreRoguelite.LOGGER.error("Could not write {}", file, e);
		}
	}
}
