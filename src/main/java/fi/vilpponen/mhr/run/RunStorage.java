package fi.vilpponen.mhr.run;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fi.vilpponen.mhr.HardcoreRoguelite;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Where the loop's own state is written down: one small JSON file in the save directory, next to
 * {@code level.dat}.
 *
 * <p>Not in the world's saved data, on purpose. The overworld, the nether and the end are thrown
 * away and rebuilt between runs, and the run record is precisely the thing that has to outlive
 * that. The save directory root does outlive it, and unlike the cross-run unlock file this belongs
 * to one save rather than to the installation — two saves are two separate loops.
 *
 * <p>Written whole, to a temporary file, then moved into place. Half a record is worse than none:
 * the reward-exactly-once rule is built on being able to believe what the file says.
 *
 * <p>Plain Java on a {@link Path}, with no Minecraft in it, so the reload and recovery behaviour is
 * testable without a game.
 */
public final class RunStorage {
	public static final String FILE_NAME = "hardcore-roguelite-run.json";

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private final Path file;

	public RunStorage(Path saveRoot) {
		this.file = saveRoot.resolve(FILE_NAME);
	}

	public Path file() {
		return file;
	}

	/**
	 * Read the record, already {@linkplain RunRecord#recovered() recovered}.
	 *
	 * <p>A missing file is a save that has never been played and reads as {@link
	 * RunRecord#NEW_SAVE}. An unreadable one is reported loudly and also reads as a new save: the
	 * permanent progression lives elsewhere and is not at risk, so the worst case is one lost run,
	 * which is better than refusing to start or acting on numbers we cannot trust.
	 */
	public RunRecord load() {
		if (!Files.isRegularFile(file)) {
			return RunRecord.NEW_SAVE;
		}
		try (Reader reader = Files.newBufferedReader(file)) {
			JsonElement root = JsonParser.parseReader(reader);
			if (root == null || !root.isJsonObject()) {
				throw new IllegalStateException("not a JSON object");
			}
			return read(root.getAsJsonObject()).recovered();
		} catch (IOException | RuntimeException e) {
			HardcoreRoguelite.LOGGER.error(
					"Could not read {}. Treating this save as one that has never been played:"
							+ " permanent progression is not stored here and is unaffected.",
					file, e);
			return RunRecord.NEW_SAVE;
		}
	}

	/** @return true if the record reached the disk. */
	public boolean save(RunRecord record) {
		JsonObject json = new JsonObject();
		json.addProperty("phase", record.phase().name());
		json.addProperty("runId", record.runId());
		json.addProperty("seed", record.seed());
		json.addProperty("startedAt", record.startedAt());
		json.addProperty("completedRuns", record.completedRuns());
		json.addProperty("rewardedRunId", record.rewardedRunId());

		Path temporary = file.resolveSibling(FILE_NAME + ".tmp");
		try {
			Files.createDirectories(file.getParent());
			try (Writer writer = Files.newBufferedWriter(temporary)) {
				GSON.toJson(json, writer);
			}
			move(temporary, file);
			return true;
		} catch (IOException e) {
			HardcoreRoguelite.LOGGER.error("Could not write {}", file, e);
			return false;
		}
	}

	private static void move(Path from, Path to) throws IOException {
		try {
			Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static RunRecord read(JsonObject json) {
		return new RunRecord(
				RunPhase.byName(string(json, "phase")),
				(int) number(json, "runId"),
				number(json, "seed"),
				number(json, "startedAt"),
				(int) number(json, "completedRuns"),
				(int) number(json, "rewardedRunId"));
	}

	private static String string(JsonObject json, String key) {
		JsonElement value = json.get(key);
		return value == null || value.isJsonNull() ? "" : value.getAsString();
	}

	private static long number(JsonObject json, String key) {
		JsonElement value = json.get(key);
		return value == null || value.isJsonNull() ? 0L : value.getAsLong();
	}
}
