package fi.vilpponen.mhr.progression;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fi.vilpponen.mhr.core.PersistenceException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * One Minecraft save is one roguelite profile.
 *
 * <p>Two save directories stand in for two saves. Opening one is what a server starting on it does,
 * and closing it is what the server stopping does; the Client GameTest does the same with real
 * singleplayer saves. What has to hold is that each save answers only from its own file, that a save
 * nobody has played starts from nothing, and that a save which was played keeps what it had.
 */
class ProgressSaveScopeTest {
	private static final String TREES = "world.trees";
	private static final String VILLAGE = "world.village";
	private static final String MILESTONE = "player|minecraft:story/mine_stone";

	@TempDir
	Path saveA;

	@TempDir
	Path saveB;

	@AfterEach
	void closeWhateverIsOpen() {
		Progress.close();
	}

	@Test
	@DisplayName("two saves keep separate progression, and neither reads or writes the other's")
	void twoSavesAreTwoProfiles() throws IOException {
		assertNotEquals(saveA, saveB, "setup: the two saves need two directories");

		Progress a = Progress.open(saveA);
		assertNew(a, "save A");
		a.setCurrency(30);
		a.setLevel(TREES, 1);
		assertTrue(a.creditOnce(3, MILESTONE, 5), "setup: save A's first credit should pay");
		Progress.close();
		byte[] aOnDisk = Files.readAllBytes(saveA.resolve("hardcore-roguelite-progress.json"));

		Progress b = Progress.open(saveB);
		assertNew(b, "save B, created after save A had bought and earned");
		b.setCurrency(4);
		b.setLevel(VILLAGE, 1);
		Progress.close();
		byte[] bOnDisk = Files.readAllBytes(saveB.resolve("hardcore-roguelite-progress.json"));
		assertArrayEquals(aOnDisk, Files.readAllBytes(saveA.resolve("hardcore-roguelite-progress.json")),
				"playing save B must not touch save A's file");

		Progress reopenedA = Progress.open(saveA);
		assertEquals(35, reopenedA.currency(), "save A must come back with its own currency");
		assertEquals(Map.of(TREES, 1), reopenedA.levels(), "and only its own unlocks");
		assertTrue(reopenedA.hasPaid(3, MILESTONE), "and its own paid-advancement ledger");
		Progress.close();
		assertArrayEquals(bOnDisk, Files.readAllBytes(saveB.resolve("hardcore-roguelite-progress.json")),
				"reopening save A must not touch save B's file");

		Progress reopenedB = Progress.open(saveB);
		assertEquals(4, reopenedB.currency(), "save B must come back with its own currency");
		assertEquals(Map.of(VILLAGE, 1), reopenedB.levels(), "and only its own unlocks");
		assertFalse(reopenedB.hasPaid(3, MILESTONE), "and none of save A's ledger");
	}

	@Test
	@DisplayName("the snapshot is written at the root of the save it belongs to")
	void theSnapshotIsInTheSave() {
		Progress.open(saveA).setCurrency(1);

		assertEquals(saveA.resolve("hardcore-roguelite-progress.json"), Progress.file());
		assertTrue(Files.isRegularFile(Progress.file()), "the write should have landed in the save");
	}

	@Test
	@DisplayName("with no save open there is no progression to answer from, rather than an empty one")
	void noSaveNoProgression() {
		assertThrows(IllegalStateException.class, Progress::get);
		assertThrows(IllegalStateException.class, Progress::file);

		Progress.open(saveA);
		Progress.close();

		assertThrows(IllegalStateException.class, Progress::get,
				"closing a save must let go of it, not leave its progression answering for the next one");
	}

	@Test
	@DisplayName("a damaged snapshot stops its save opening and leaves no save open")
	void aDamagedSnapshotStopsTheSave() throws IOException {
		Progress.open(saveB).setCurrency(9);
		Progress.close();
		Path damaged = saveA.resolve("hardcore-roguelite-progress.json");
		Files.writeString(damaged, "{\"currency\": 100}");

		assertThrows(PersistenceException.class, () -> Progress.open(saveA));

		assertThrows(IllegalStateException.class, Progress::get,
				"a save that failed to open must not leave anything answering, least of all save B");
		assertEquals("{\"currency\": 100}", Files.readString(damaged), "and must leave the file alone");
	}

	private static void assertNew(Progress progress, String which) {
		assertEquals(0, progress.currency(), which + " must start with no currency");
		assertEquals(Map.of(), progress.levels(), which + " must start with nothing owned");
		assertFalse(progress.hasPaid(3, MILESTONE), which + " must start with nothing paid for");
	}
}
