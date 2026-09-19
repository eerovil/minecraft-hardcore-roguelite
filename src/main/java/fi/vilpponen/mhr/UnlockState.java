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
		try (Reader reader = Files.newBufferedReader(file)) {
			String[] ids = GSON.fromJson(reader, String[].class);
			if (ids == null) {
				return;
			}
			for (String id : ids) {
				Unlock unlock = Unlock.byId(id);
				if (unlock == null) {
					HardcoreRoguelite.LOGGER.warn("Ignoring unknown unlock id '{}' in {}", id, file);
				} else {
					owned.add(unlock);
				}
			}
		} catch (IOException | RuntimeException e) {
			HardcoreRoguelite.LOGGER.error("Could not read {}, starting with nothing unlocked", file, e);
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
