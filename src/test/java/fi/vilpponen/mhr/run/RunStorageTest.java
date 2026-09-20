package fi.vilpponen.mhr.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The file the loop is remembered in, and what a reload makes of it.
 *
 * <p>The interesting cases are all failure ones: a save nobody has played, a file somebody edited
 * into nonsense, and a process that died between the two writes that bracket a reward. The record
 * has to come back meaning the same thing, it must never come back meaning that a run is owed a
 * second payout, and a record that cannot be read must not come back as a save that was never
 * played — that would throw away a run in progress, or a reward still owed.
 */
class RunStorageTest {
	@TempDir
	Path save;

	@Test
	@DisplayName("a save nobody has played reads as a new one")
	void missingFile() {
		RunRecord loaded = new RunStorage(save).load();

		assertEquals(RunRecord.NEW_SAVE, loaded);
		assertFalse(Files.exists(save.resolve(RunStorage.FILE_NAME)), "reading must not create it");
	}

	@Test
	@DisplayName("a record survives a round trip unchanged")
	void roundTrip() {
		RunStorage storage = new RunStorage(save);
		RunRecord written = new RunRecord(RunPhase.RUNNING, 3, -8_123_456_789L, 1_700_000_000_000L, 2, 2);

		assertTrue(storage.save(written));

		assertEquals(written, new RunStorage(save).load());
	}

	@Test
	@DisplayName("the file lands in the save directory under a predictable name")
	void fileLocation() {
		RunStorage storage = new RunStorage(save);
		storage.save(RunRecord.NEW_SAVE);

		assertEquals(save.resolve(RunStorage.FILE_NAME), storage.file());
		assertTrue(Files.isRegularFile(storage.file()));
	}

	@Test
	@DisplayName("writing again replaces the record rather than appending to it")
	void overwrite() {
		RunStorage storage = new RunStorage(save);
		storage.save(new RunRecord(RunPhase.RUNNING, 1, 5L, 0L, 0, 0));
		storage.save(new RunRecord(RunPhase.LOBBY, 1, 5L, 0L, 1, 1));

		RunRecord loaded = storage.load();
		assertEquals(RunPhase.LOBBY, loaded.phase());
		assertEquals(1, loaded.completedRuns());
	}

	@Test
	@DisplayName("no half-written file is left behind")
	void noTemporaryFileLeftOver() throws IOException {
		new RunStorage(save).save(RunRecord.NEW_SAVE);

		try (var entries = Files.list(save)) {
			assertEquals(java.util.List.of(RunStorage.FILE_NAME),
					entries.map(path -> path.getFileName().toString()).sorted().toList());
		}
	}

	@Test
	@DisplayName("a write that cannot happen says so rather than pretending")
	void unwritableSaveDirectory() throws IOException {
		// The lifecycle refuses to move the record on when this returns false, which is the only
		// thing standing between a full disk and a run that only the running process believes in.
		Path readOnly = save.resolve("read-only");
		Files.createDirectory(readOnly);
		Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("r-xr-xr-x"));

		try {
			assertFalse(new RunStorage(readOnly).save(RunRecord.NEW_SAVE));
		} finally {
			Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("rwxr-xr-x"));
		}
	}

	@Test
	@DisplayName("a failed write leaves the last good record readable")
	void failedWriteKeepsTheOldRecord() throws IOException {
		RunStorage storage = new RunStorage(save);
		RunRecord good = new RunRecord(RunPhase.RUNNING, 1, 5L, 0L, 0, 0);
		assertTrue(storage.save(good));

		// A directory where the temporary file wants to be: the write fails, the old file stands.
		Files.createDirectory(save.resolve(RunStorage.FILE_NAME + ".tmp"));

		assertFalse(storage.save(new RunRecord(RunPhase.LOBBY, 1, 5L, 0L, 1, 1)));
		assertEquals(good, storage.load());
	}

	@Test
	@DisplayName("nonsense in the file is refused rather than read as an unplayed save")
	void corruptFile() throws IOException {
		Files.writeString(save.resolve(RunStorage.FILE_NAME), "{ this is not json");

		assertThrows(RunStorage.UnreadableRecord.class, () -> new RunStorage(save).load());
	}

	@Test
	@DisplayName("a missing field is refused rather than filled in")
	void partialFile() throws IOException {
		Files.writeString(save.resolve(RunStorage.FILE_NAME), "{\"phase\":\"RUNNING\",\"runId\":4}");

		RunStorage.UnreadableRecord thrown =
				assertThrows(RunStorage.UnreadableRecord.class, () -> new RunStorage(save).load());
		assertTrue(thrown.getMessage().contains("seed"), thrown.getMessage());
	}

	@Test
	@DisplayName("every field is required")
	void eachFieldIsRequired() throws IOException {
		String complete = "{\"phase\":\"RUNNING\",\"runId\":2,\"seed\":5,\"startedAt\":1,"
				+ "\"completedRuns\":1,\"rewardedRunId\":1}";
		assertEquals(RunPhase.RUNNING, load(complete).phase(), "the complete record must read");

		for (String field : List.of("phase", "runId", "seed", "startedAt", "completedRuns",
				"rewardedRunId")) {
			String without = removeField(complete, field);
			assertThrows(RunStorage.UnreadableRecord.class, () -> load(without),
					"a record with no " + field + " must be refused");
		}
	}

	@Test
	@DisplayName("a field that is not a number is refused")
	void nonNumericField() {
		assertThrows(RunStorage.UnreadableRecord.class, () -> load(
				"{\"phase\":\"LOBBY\",\"runId\":\"two\",\"seed\":5,\"startedAt\":1,"
						+ "\"completedRuns\":1,\"rewardedRunId\":1}"));
	}

	@Test
	@DisplayName("a phase this build does not know is refused rather than read as the lobby")
	void unknownPhase() {
		RunStorage.UnreadableRecord thrown = assertThrows(RunStorage.UnreadableRecord.class,
				() -> load("{\"phase\":\"SHOPPING\",\"runId\":1,\"seed\":5,\"startedAt\":1,"
						+ "\"completedRuns\":0,\"rewardedRunId\":0}"));
		assertTrue(thrown.getMessage().contains("SHOPPING"), thrown.getMessage());
	}

	@Test
	@DisplayName("a saved run that says it was already rewarded is refused")
	void aRunningRecordCannotBeAlreadyRewarded() {
		// Parse-valid and quietly ruinous: rewardOutstanding would answer false when the run ends,
		// so its real payout would never be handed over.
		assertThrows(RunStorage.UnreadableRecord.class, () -> load(
				"{\"phase\":\"RUNNING\",\"runId\":2,\"seed\":5,\"startedAt\":1,"
						+ "\"completedRuns\":1,\"rewardedRunId\":2}"));

		assertThrows(RunStorage.UnreadableRecord.class, () -> load(
				"{\"phase\":\"CREATING_RUN\",\"runId\":2,\"seed\":5,\"startedAt\":1,"
						+ "\"completedRuns\":1,\"rewardedRunId\":2}"));
	}

	@Test
	@DisplayName("more runs completed than ever started is refused")
	void moreCompletedThanStarted() {
		assertThrows(RunStorage.UnreadableRecord.class, () -> load(
				"{\"phase\":\"LOBBY\",\"runId\":1,\"seed\":5,\"startedAt\":1,"
						+ "\"completedRuns\":4,\"rewardedRunId\":1}"));
	}

	@Test
	@DisplayName("a record that contradicts itself is refused")
	void contradictoryRecord() {
		// Playing run 0, which never existed.
		assertThrows(RunStorage.UnreadableRecord.class, () -> load(
				"{\"phase\":\"RUNNING\",\"runId\":0,\"seed\":5,\"startedAt\":1,"
						+ "\"completedRuns\":0,\"rewardedRunId\":0}"));

		// Rewarded for a run that has not started.
		assertThrows(RunStorage.UnreadableRecord.class, () -> load(
				"{\"phase\":\"LOBBY\",\"runId\":1,\"seed\":5,\"startedAt\":1,"
						+ "\"completedRuns\":1,\"rewardedRunId\":4}"));

		// Counts that cannot be counts.
		assertThrows(RunStorage.UnreadableRecord.class, () -> load(
				"{\"phase\":\"LOBBY\",\"runId\":-2,\"seed\":5,\"startedAt\":1,"
						+ "\"completedRuns\":0,\"rewardedRunId\":0}"));
	}

	@Test
	@DisplayName("a corrupt record does not erase a run in progress")
	void corruptionCannotEraseARunningRun() throws IOException {
		// The file said RUNNING and then went bad. Nothing may decide the run did not happen: that
		// is somebody's run in progress, and a fresh save would delete its worlds on the next start.
		new RunStorage(save).save(new RunRecord(RunPhase.RUNNING, 3, 11L, 1L, 2, 2));
		corrupt();

		assertThrows(RunStorage.UnreadableRecord.class, () -> new RunStorage(save).load());
	}

	@Test
	@DisplayName("a corrupt record does not erase a run that still owes its reward")
	void corruptionCannotEraseAnUnrewardedEnding() throws IOException {
		// Worse than losing a run: reading this as a new save skips the recovery that hands the
		// reward over, and the payout is gone for good.
		new RunStorage(save).save(new RunRecord(RunPhase.ENDING_RUN, 3, 11L, 1L, 2, 2));
		corrupt();

		assertThrows(RunStorage.UnreadableRecord.class, () -> new RunStorage(save).load());
	}

	@Test
	@DisplayName("the unreadable record is kept rather than written over")
	void theUnreadableRecordIsKept() throws IOException {
		Files.writeString(save.resolve(RunStorage.FILE_NAME), "{ not json");

		assertThrows(RunStorage.UnreadableRecord.class, () -> new RunStorage(save).load());

		Path kept = save.resolve(RunStorage.FILE_NAME + ".unreadable");
		assertTrue(Files.isRegularFile(kept), "the only evidence of what the save was doing");
		assertEquals("{ not json", Files.readString(kept));
	}

	private RunRecord load(String json) throws IOException {
		Files.writeString(save.resolve(RunStorage.FILE_NAME), json);
		return new RunStorage(save).load();
	}

	private void corrupt() throws IOException {
		Path record = save.resolve(RunStorage.FILE_NAME);
		Files.writeString(record, Files.readString(record).replace('{', '['));
	}

	private static String removeField(String json, String field) {
		return json.replaceAll(",?\\s*\"" + field + "\"\\s*:\\s*(\"[^\"]*\"|-?\\d+)", "")
				.replace("{,", "{");
	}

	@Test
	@DisplayName("loading a save that was quit mid-run keeps the run")
	void quittingIsNotDying() {
		RunStorage storage = new RunStorage(save);
		storage.save(new RunRecord(RunPhase.RUNNING, 2, 11L, 1L, 1, 1));

		RunRecord loaded = storage.load();

		assertTrue(loaded.isRunning());
		assertEquals(2, loaded.runId());
		assertEquals(1, loaded.completedRuns(), "quitting does not complete a run");
	}

	@Test
	@DisplayName("loading a save that died while building a run drops back to the lobby")
	void interruptedCreationRecovers() {
		RunStorage storage = new RunStorage(save);
		storage.save(new RunRecord(RunPhase.CREATING_RUN, 2, 11L, 1L, 1, 1));

		RunRecord loaded = storage.load();

		assertEquals(RunPhase.LOBBY, loaded.phase());
		assertEquals(1, loaded.completedRuns(), "a run that was never played is not a run played");
	}

	@Test
	@DisplayName("loading a save that died while ending a run still owes the reward")
	void interruptedEndingKeepsTheDebt() {
		new RunStorage(save).save(new RunRecord(RunPhase.ENDING_RUN, 2, 11L, 1L, 1, 1));

		RunRecord loaded = new RunStorage(save).load();

		assertEquals(RunPhase.ENDING_RUN, loaded.phase());
		assertTrue(loaded.rewardOutstanding());
	}

	@Test
	@DisplayName("loading a save that died just after paying does not pay again")
	void interruptedEndingAfterPaymentOwesNothing() {
		new RunStorage(save).save(new RunRecord(RunPhase.ENDING_RUN, 2, 11L, 1L, 1, 2));

		assertFalse(new RunStorage(save).load().rewardOutstanding());
	}
}
