package fi.vilpponen.mhr.run;

import com.mojang.serialization.Codec;
import fi.vilpponen.mhr.HardcoreRoguelite;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * Which run a player has been let into, remembered with the player.
 *
 * <p>This exists because the question "are you rejoining the run you were playing?" cannot be
 * answered by looking at where a player is. A run is the three vanilla dimensions, and the next run
 * is the same three dimension keys pointing at different worlds — so somebody who logged out in
 * {@code minecraft:overworld} during run 4 logs back in to find their saved dimension is
 * {@code minecraft:overworld} in run 5, indistinguishable from never having left. Treating that as
 * a reconnect keeps run 4's inventory, ender chest and experience, and the roguelite has quietly
 * handed the new run the old one's diamonds.
 *
 * <p>So the run id is written down on the player, persists with their save data, and is compared
 * against the run actually in progress. Equal means they are back where they were and keep
 * everything. Anything else — a later run, or never admitted at all — means they missed this run's
 * start boundary and have to cross it now.
 *
 * <p>Zero is "no run", which is what an unmarked player reads as, and no run ever has that id.
 */
public final class RunAdmission {
	/**
	 * Assigned by {@link #register()} and not by class loading, which is the whole point.
	 *
	 * <p>A persistent attachment has to exist before any save data is read: Fabric resolves each
	 * saved id as it loads an entity and drops the ones it does not recognise — "Skipping invalid
	 * attachments" in the log and nothing else. Registering from a static initialiser means the
	 * type appears whenever something first happens to touch this class, which on a server that
	 * restarted mid-run is *after* the player's file has been read. Their admission would be gone,
	 * the join would look like somebody who had never been admitted, and the reset would take a run
	 * they were in the middle of playing.
	 *
	 * <p>Holding it behind the call rather than beside it also means getting the order wrong throws
	 * here instead of quietly reading zero. Of the two failures, the loud one is much the better.
	 */
	/**
	 * Not in any run: never admitted to one, or admitted and since crossed back out.
	 *
	 * <p>Zero is what an unmarked player reads as, and no run ever has that id. Written explicitly
	 * on the way out of a run as well, because since the run-to-lobby boundary exists the mark
	 * answers a second question: <em>is this player still carrying a run?</em> Somebody who was
	 * offline when their run ended comes back still marked, and that is what tells the join path to
	 * finish the crossing they never made.
	 */
	public static final int NO_RUN = 0;

	private static AttachmentType<Integer> admittedRun;

	private RunAdmission() {
	}

	/** Called once from the mod initializer, before any world or player data can be loaded. */
	public static void register() {
		if (admittedRun == null) {
			admittedRun = AttachmentRegistry.createPersistent(
					Identifier.fromNamespaceAndPath(HardcoreRoguelite.MOD_ID, "admitted_run"),
					Codec.INT);
		}
	}

	/** Has {@link #register()} run? Asked by the tests, which cannot otherwise see the wiring. */
	public static boolean isRegistered() {
		return admittedRun != null;
	}

	private static AttachmentType<Integer> type() {
		if (admittedRun == null) {
			throw new IllegalStateException("RunAdmission.register() has not run, so a player's"
					+ " admitted run may already have been dropped while their save was read");
		}
		return admittedRun;
	}

	/** The run this player was let into, or {@link #NO_RUN} if they are not in one. */
	public static int of(ServerPlayer player) {
		return player.getAttachedOrElse(type(), NO_RUN);
	}

	/** Is this player still carrying a run they have not crossed back out of? */
	public static boolean isInARun(ServerPlayer player) {
		return of(player) != NO_RUN;
	}

	public static boolean isAdmittedTo(ServerPlayer player, int runId) {
		return of(player) == runId;
	}

	/** Write down that this player has crossed this run's start boundary. */
	public static void admit(ServerPlayer player, int runId) {
		player.setAttached(type(), runId);
	}

	/**
	 * Carry the mark from a player onto the one that replaced them.
	 *
	 * <p>Moving a player between dimensions here builds a fresh {@link ServerPlayer} — see
	 * {@code Lobby.moveThroughRespawn} — and the new one must not arrive looking like somebody who
	 * has never been admitted anywhere. Copied explicitly rather than left to the attachment API's
	 * own rules about what survives a respawn, because which of those apply to a dimension change
	 * is exactly the kind of thing that is true until it is not.
	 */
	public static void carryOver(ServerPlayer from, ServerPlayer to) {
		if (from != to) {
			to.setAttached(type(), of(from));
		}
	}
}
