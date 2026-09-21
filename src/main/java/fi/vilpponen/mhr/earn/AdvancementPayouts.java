package fi.vilpponen.mhr.earn;

import fi.vilpponen.mhr.HardcoreRoguelite;
import fi.vilpponen.mhr.core.BalanceManager;
import fi.vilpponen.mhr.core.PersistenceException;
import fi.vilpponen.mhr.progression.Wallet;
import fi.vilpponen.mhr.run.RunAdmission;
import fi.vilpponen.mhr.run.RunEvents;
import fi.vilpponen.mhr.shop.ShopServer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;

/**
 * Where currency comes from: finishing an advancement inside a run pays what the balance file says
 * it is worth.
 *
 * <p>This is the answer to what used to be the blocking open question. The two halves of it are:
 *
 * <ul>
 *   <li><b>An advancement pays the moment it is completed</b>, not at the end of the run. A run that
 *       goes badly still keeps everything it banked before it went wrong, so dying is the end of the
 *       run rather than the end of the reward — which matters in a game whose whole premise is that
 *       you are going to die.
 *   <li><b>Each run can earn the same advancement again</b>, because a run is a fresh world and a
 *       fresh world has no advancements in it. The player's advancement record is cleared as they
 *       cross into a run, exactly as starting another vanilla world would clear it.
 * </ul>
 *
 * <p>Those two together mean the paying ledger is vanilla's own: an advancement is completed once
 * per run, so it pays once per run, and nothing here has to remember what it has already paid for.
 * There is no second store to keep in step with the advancement file, and no window in which a
 * crash could pay twice.
 *
 * <p>The price list is {@code currency.advancements} in the balance data, read at the moment it is
 * needed like every other tuning value. An advancement that is not listed pays nothing, which is
 * most of them.
 *
 * <p>Nothing pays in the lobby. Payment needs a player who has crossed into a run — the same mark
 * the lifecycle uses — so the shop's own between-runs world cannot be farmed.
 */
public final class AdvancementPayouts {
	private AdvancementPayouts() {
	}

	/** Called once from the mod initializer. */
	public static void register() {
		// The person, not the place, and after the fresh-run reset: this is about what one player's
		// advancement record says, and a player who joins a run already in progress needs it too.
		RunEvents.PLAYER_ENTERED_RUN.register((server, player, run) -> clearAdvancements(player));
	}

	/**
	 * One advancement has just been completed by this player.
	 *
	 * <p>Called from {@code AdvancementPayoutMixin} at the exact point vanilla itself decides an
	 * advancement is newly done, so "again" cannot happen: a completed advancement grants no further
	 * progress, and the next run starts from a cleared record.
	 *
	 * <p>A payout that cannot be written down is not a payout. {@link Wallet} hands the whole
	 * snapshot to the disk and refuses the change if it does not land, so the player is told rather
	 * than being quietly given nothing — and the advancement stays earned, since taking it back
	 * would be a second lie on top of the first.
	 */
	public static void completed(ServerPlayer player, AdvancementHolder advancement) {
		if (!RunAdmission.isInARun(player)) {
			return;
		}
		int reward = BalanceManager.get().advancementReward(advancement.id().toString());
		if (reward <= 0) {
			return;
		}

		// Vanilla's own name for it — the advancement's title, or its id when it has no display.
		Component name = Advancement.name(advancement);
		try {
			Wallet.get().earn(reward);
		} catch (PersistenceException failure) {
			HardcoreRoguelite.LOGGER.error("Could not save the {} currency earned for {}",
					reward, advancement.id(), failure);
			player.sendSystemMessage(Component.translatable("mhr.earn.not_saved", reward, name));
			return;
		}

		player.sendSystemMessage(
				Component.translatable("mhr.earn.advancement", reward, name, Wallet.get().balance()));
		// The clients' copy of the purse is the shop state packet, and the HUD draws from it.
		ShopServer.sendToAll(server(player));
	}

	/**
	 * Take this player's advancements away, because they are entering a new world.
	 *
	 * <p>Not a nerf and not a reset of progression: permanent progression is currency and unlocks,
	 * and neither is touched. This is the roguelite's one save standing in for a new world each run
	 * — a vanilla player starting their next hardcore world has no advancements either, and the
	 * run that is about to start should be earnable in exactly the same way as the last one.
	 */
	private static void clearAdvancements(ServerPlayer player) {
		MinecraftServer server = server(player);
		if (server == null) {
			return;
		}
		PlayerAdvancements record = player.getAdvancements();
		for (AdvancementHolder advancement : server.getAdvancements().getAllAdvancements()) {
			AdvancementProgress progress = record.getOrStartProgress(advancement);
			if (!progress.hasProgress()) {
				continue;
			}
			for (String criterion : completedCriteria(progress)) {
				record.revoke(advancement, criterion);
			}
		}
		record.flushDirty(player, false);
	}

	/** A copy, because revoking walks back into the progress this would otherwise be iterating. */
	private static List<String> completedCriteria(AdvancementProgress progress) {
		List<String> criteria = new ArrayList<>();
		for (String criterion : progress.getCompletedCriteria()) {
			criteria.add(criterion);
		}
		return criteria;
	}

	private static MinecraftServer server(ServerPlayer player) {
		return player.level().getServer();
	}
}
