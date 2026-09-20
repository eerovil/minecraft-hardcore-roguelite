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
	private static final AttachmentType<Integer> ADMITTED_RUN = AttachmentRegistry.createPersistent(
			Identifier.fromNamespaceAndPath(HardcoreRoguelite.MOD_ID, "admitted_run"), Codec.INT);

	private RunAdmission() {
	}

	/** The run this player was let into, or zero if they have never been let into one. */
	public static int of(ServerPlayer player) {
		return player.getAttachedOrElse(ADMITTED_RUN, 0);
	}

	public static boolean isAdmittedTo(ServerPlayer player, int runId) {
		return of(player) == runId;
	}

	/** Write down that this player has crossed this run's start boundary. */
	public static void admit(ServerPlayer player, int runId) {
		player.setAttached(ADMITTED_RUN, runId);
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
			to.setAttached(ADMITTED_RUN, of(from));
		}
	}
}
