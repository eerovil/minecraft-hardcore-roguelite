package fi.vilpponen.mhr.equipment;

/**
 * What a server last told this client about the five slots — a client-side cache, nothing more.
 *
 * <p>It exists because a connected client cannot read the server's progression snapshot but still has to
 * draw the padlocks. It is written only by the client's payload handler and read only by
 * {@link EquipmentLocks#isUnlockedForDisplay}, which consults it only when it is asked about an
 * entity on the logical client.
 *
 * <p>Enforcement never comes near it. In single player the client and the integrated server share
 * one process, and a cache that server code could read would let a stale client answer decide what
 * the server allows.
 */
public final class SyncedSlotUnlocks {
	private static volatile Integer bits;

	private SyncedSlotUnlocks() {
	}

	/** Called on the client when the server says which slots are open. */
	public static void accept(int unlockedBits) {
		bits = unlockedBits;
	}

	/** Called on the client when it leaves a server, so nothing carries into the next one. */
	public static void forget() {
		bits = null;
	}

	/** @return the last bit set received, or null if this process has never been told. */
	static Integer bits() {
		return bits;
	}
}
