package fi.vilpponen.mhr.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The order the lobby's old floor is swept in, without a game.
 *
 * <p>There is one interesting thing about that order and it is easy to get wrong twice. The
 * migration has no upgrade file: what says "this lobby still has the old floor" is a block of that
 * floor, the one under the spawn. It is therefore a block the sweep is also clearing, and clearing
 * it in its turn writes down "already upgraded" while most of the floor is still standing. A server
 * stopped in that window — or a sweep that throws — leaves the rest of the plane behind for good,
 * because nothing is left to say it was ever owed.
 *
 * <p>So the marker goes last. That is the fix, and this is where it is asked about: an interrupted
 * sweep is not something the gameplay harness can stage, and "which position was touched last" is
 * plain arithmetic that needs no world to be true. See {@link LobbyFloorSweep#offsets}.
 */
@DisplayName("Sweeping the lobby's old floor")
class LobbyFloorSweepTest {
	/** Small enough to check by hand, and the shape is the same at every radius. */
	private static final int RADIUS = 2;

	@Test
	@DisplayName("the marker is the last position swept, so an interrupted upgrade is still owed")
	void theMarkerIsLast() {
		int[] offsets = LobbyFloorSweep.offsets(RADIUS);

		assertEquals(0, offsets[offsets.length - 2], "the last x must be the marker's");
		assertEquals(0, offsets[offsets.length - 1], "the last z must be the marker's");
	}

	@Test
	@DisplayName("and it is not swept anywhere else on the way")
	void theMarkerIsSweptOnlyOnce() {
		int[] offsets = LobbyFloorSweep.offsets(RADIUS);

		int atTheMarker = 0;
		for (int i = 0; i < offsets.length; i += 2) {
			if (offsets[i] == 0 && offsets[i + 1] == 0) {
				atTheMarker++;
			}
		}
		assertEquals(1, atTheMarker, "the marker must be visited once, and it is visited "
				+ atTheMarker + " times — visiting it early is what leaves a half-swept floor");
	}

	@Test
	@DisplayName("every position of the old floor is swept, and none twice")
	void theWholeFloorIsCovered() {
		int[] offsets = LobbyFloorSweep.offsets(RADIUS);
		int side = 2 * RADIUS + 1;

		assertEquals(2 * side * side, offsets.length,
				"the sweep must cover the whole square exactly once");

		Set<String> seen = new HashSet<>();
		for (int i = 0; i < offsets.length; i += 2) {
			int x = offsets[i];
			int z = offsets[i + 1];
			assertTrue(Math.abs(x) <= RADIUS && Math.abs(z) <= RADIUS,
					"the sweep must stay inside its radius, and it reached " + x + "," + z);
			assertTrue(seen.add(x + "," + z), "the sweep must not repeat " + x + "," + z);
		}
		assertEquals(side * side, seen.size(), "and must leave nothing out");
	}
}
