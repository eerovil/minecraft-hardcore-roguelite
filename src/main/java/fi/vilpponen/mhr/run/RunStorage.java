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
 * everything the loop promises is built on being able to believe what the file says — which is also
 * why a record that cannot be believed is refused rather than quietly replaced with a fresh one.
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
	 * <p>A missing file is a save nobody has played and reads as {@link RunRecord#NEW_SAVE}. A file
	 * that exists and cannot be believed is something else entirely, and is refused.
	 *
	 * <p>That distinction is the whole of this method. Treating an unreadable record as a new save
	 * quietly throws away whatever it was describing — and the two states worth most are exactly
	 * the ones that would be lost: a {@code RUNNING} record is somebody's run in progress, and an
	 * {@code ENDING_RUN} record is a run owing a reward that the next start is supposed to hand
	 * over. Neither may be guessed at.
	 *
	 * @throws UnreadableRecord if a record exists and cannot be parsed or believed
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
			throw new UnreadableRecord(keepForInspection() + ": " + e.getMessage(), e);
		}
	}

	/**
	 * Put the unreadable record somewhere it will not be written over.
	 *
	 * <p>Whatever is wrong with it, it is the only evidence of what this save was doing, and the
	 * next thing to touch the save would otherwise overwrite it.
	 *
	 * @return a description of where it ended up, for the message somebody will have to read
	 */
	private String keepForInspection() {
		Path kept = file.resolveSibling(FILE_NAME + ".unreadable");
		if (Files.exists(kept)) {
			// The first one is the interesting one. Later attempts leave it alone.
			return file + " (an earlier copy is already at " + kept + ")";
		}
		try {
			Files.copy(file, kept);
			return file + " (copied to " + kept + ")";
		} catch (IOException e) {
			HardcoreRoguelite.LOGGER.error("Could not keep a copy of {}", file, e);
			return file.toString();
		}
	}

	/** A record that exists but cannot be acted on. */
	public static final class UnreadableRecord extends RuntimeException {
		private static final long serialVersionUID = 1L;

		UnreadableRecord(String message, Throwable cause) {
			super(message, cause);
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

	/**
	 * Turn the file's JSON into a record, or refuse.
	 *
	 * <p>Every field is required and nothing is defaulted. A record missing its run id is not a
	 * record with run id zero — it is a record this build cannot tell the truth about, and filling
	 * the gap in would produce a plausible-looking state that never existed.
	 *
	 * <p>This only turns JSON into the six values. Whether those values are a save that could
	 * exist is {@link RunRecord}'s own rule, and it throws for itself.
	 */
	private static RunRecord read(JsonObject json) {
		RunPhase phase = RunPhase.byName(required(json, "phase").getAsString());
		if (phase == null) {
			throw new IllegalStateException(
					"no such phase: " + required(json, "phase").getAsString());
		}

		// Read in the order the file writes them, so the first thing reported missing is the first
		// thing missing rather than whichever happened to be asked for first.
		int runId = wholeNumber(json, "runId");
		long seed = number(json, "seed");
		long startedAt = number(json, "startedAt");
		int completedRuns = wholeNumber(json, "completedRuns");
		int rewardedRunId = wholeNumber(json, "rewardedRunId");

		// Whether these numbers describe a save that could exist is RunRecord's question, not this
		// class's. Asking it here as well is how the two answers drifted apart in the first place:
		// the loader had some of the rules and the record had the rest.
		return new RunRecord(phase, runId, seed, startedAt, completedRuns, rewardedRunId);
	}

	private static JsonElement required(JsonObject json, String key) {
		JsonElement value = json.get(key);
		if (value == null || value.isJsonNull()) {
			throw new IllegalStateException("no " + key);
		}
		return value;
	}

	private static long number(JsonObject json, String key) {
		JsonElement value = required(json, key);
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
			throw new IllegalStateException(key + " is not a number: " + value);
		}
		return value.getAsLong();
	}

	private static int wholeNumber(JsonObject json, String key) {
		long value = number(json, key);
		if (value != (int) value) {
			throw new IllegalStateException(key + " does not fit in a run count: " + value);
		}
		return (int) value;
	}
}
