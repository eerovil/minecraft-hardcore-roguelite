package fi.vilpponen.mhr.run;

/**
 * The order in which the lobby's old floor is taken out — and nothing else.
 *
 * <p>A class of its own, with no Minecraft in it at all, because the one thing worth asserting
 * about that order can then be asserted without a game. {@link LobbyIsland} touches blocks and
 * registries, so loading it outside a running Minecraft fails before a test can ask it anything;
 * see the note on the {@code test} source set in {@code build.gradle}.
 *
 * <p><b>What the order is for.</b> The migration has no upgrade file. What says "this lobby still
 * has the old floor" is a block <em>of</em> that floor, the one under the spawn. So the marker is a
 * position the sweep is also clearing, and clearing it in its turn would write down "already
 * upgraded" while most of the plane was still standing — a server stopped, or a sweep thrown, in
 * that window would leave the rest behind for good, with nothing to say it was ever owed. The
 * marker therefore goes last, and the claim "nothing is touched after the marker" is what
 * {@code LobbyFloorSweepTest} holds on to.
 *
 * @see LobbyIsland
 */
final class LobbyFloorSweep {
	private LobbyFloorSweep() {
	}

	/**
	 * Every {@code (x, z)} offset of the old floor, interleaved, the marker's own {@code (0, 0)}
	 * last.
	 *
	 * @param radius how far out from the marker to go, in blocks
	 * @return {@code 2 * (2 * radius + 1)^2} integers: {@code x}, then {@code z}, and so on
	 */
	static int[] offsets(int radius) {
		int side = 2 * radius + 1;
		int[] offsets = new int[2 * side * side];
		int at = 0;
		for (int x = -radius; x <= radius; x++) {
			for (int z = -radius; z <= radius; z++) {
				if (x == 0 && z == 0) {
					// Skipped here and appended below. This is the whole of what this class is.
					continue;
				}
				offsets[at++] = x;
				offsets[at++] = z;
			}
		}
		offsets[at++] = 0;
		offsets[at] = 0;
		return offsets;
	}
}
