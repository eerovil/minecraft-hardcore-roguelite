package fi.vilpponen.mhr.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The file the loop is remembered in, and what a reload makes of it.
 *
 * <p>The interesting cases are all failure ones: a save nobody has played, a file somebody edited
 * into nonsense, and a process that died between the two writes that bracket a reward. The record
 * has to come back meaning the same thing, and it must never come back meaning that a run is owed
 * a second payout.
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
	@DisplayName("nonsense in the file is reported and treated as an unplayed save")
	void corruptFile() throws IOException {
		Files.writeString(save.resolve(RunStorage.FILE_NAME), "{ this is not json");

		assertEquals(RunRecord.NEW_SAVE, new RunStorage(save).load());
	}

	@Test
	@DisplayName("missing fields fall back rather than throwing")
	void partialFile() throws IOException {
		Files.writeString(save.resolve(RunStorage.FILE_NAME), "{\"phase\":\"RUNNING\",\"runId\":4}");

		RunRecord loaded = new RunStorage(save).load();

		assertEquals(RunPhase.RUNNING, loaded.phase());
		assertEquals(4, loaded.runId());
		assertEquals(0, loaded.completedRuns());
	}

	@Test
	@DisplayName("a phase this build does not know reads as the lobby")
	void unknownPhase() throws IOException {
		Files.writeString(save.resolve(RunStorage.FILE_NAME), "{\"phase\":\"SHOPPING\",\"runId\":4}");

		assertEquals(RunPhase.LOBBY, new RunStorage(save).load().phase());
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
