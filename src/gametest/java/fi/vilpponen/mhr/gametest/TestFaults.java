package fi.vilpponen.mhr.gametest;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Faults a test can arm on things that cannot be made to fail by asking nicely.
 *
 * <p>Most of the run lifecycle's failure paths can be driven by a listener that throws, and the
 * tests do exactly that. Writing the record is the exception: it fails when the disk does, and a
 * test has no honest way to make a disk fail underneath a live dedicated server. Yet the ordering
 * around that write is precisely what the transaction boundary is made of — the record is committed
 * while every player is already standing in the new run, and the rollback must not write LOBBY
 * until they are out of it. Neither claim can be believed without a write that actually fails.
 *
 * <p>So the test arms a countdown here and a mixin in {@code fi.vilpponen.mhr.gametest.mixin} turns
 * the next few writes into failures. This class holds only the counter, because a mixin class
 * cannot be referred to from ordinary code.
 *
 * <p>Lives in the gametest source set and ships in no jar anybody plays.
 */
public final class TestFaults {
	/** How many more calls to {@code RunStorage.save} must report that they did not write. */
	private static final AtomicInteger FAILING_WRITES = new AtomicInteger();

	private TestFaults() {
	}

	/** Make the next {@code count} record writes fail, in order. */
	public static void failTheNextRecordWrites(int count) {
		FAILING_WRITES.set(count);
	}

	/** Stop failing writes, whatever is left of the countdown. Safe to call when none is armed. */
	public static void stopFailingRecordWrites() {
		FAILING_WRITES.set(0);
	}

	/** How many armed failures have not been used yet. Zero means the fault is spent. */
	public static int recordWriteFailuresLeft() {
		return FAILING_WRITES.get();
	}

	/**
	 * Asked by the mixin on every record write.
	 *
	 * @return true if this write must report failure
	 */
	public static boolean takeRecordWriteFailure() {
		return FAILING_WRITES.getAndUpdate(left -> Math.max(0, left - 1)) > 0;
	}
}
