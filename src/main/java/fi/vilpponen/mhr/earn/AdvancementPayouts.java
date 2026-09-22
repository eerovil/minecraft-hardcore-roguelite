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
import java.util.Map;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
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
 * <p><b>What has been paid for is written down where the money is</b>, in the {@link
 * fi.vilpponen.mhr.progression.Progress} snapshot, by the same write that moves it — see
 * {@link Wallet#earnOnce}. Vanilla's own advancement record looks like it would do the job and does
 * not: a player's advancements are saved on the player-save cycle, not when the wallet is written,
 * so a crash in between comes back to a credited purse and a record with no trace of what it was
 * credited for. The player finishes the milestone again and it mints the money a second time. One
 * write, holding both the balance and the note saying what it was for, is the only shape with no
 * such gap in it.
 *
 * <p>The price list is {@code currency.advancements} in the balance data, read at the moment it is
 * needed like every other tuning value. An advancement that is not listed pays nothing, which is
 * most of them.
 *
 * <p>Nothing pays in the lobby. Payment needs a player who has crossed into a run — the same mark
 * the lifecycle uses — so the shop's own between-runs world cannot be farmed.
 *
 * <p><b>The ledger is also what says a run's advancements were reset</b>, not the admission mark
 * beside them. The two are different files saved at different times, so a crash can leave a player
 * admitted to this run with the last run's completions still on disk — and nothing fires again for
 * somebody the lifecycle thinks never left. Every join therefore reconciles the paying advancements
 * against the ledger rather than assuming the reset landed. See {@code reconcile}.
 */
public final class AdvancementPayouts {
	private AdvancementPayouts() {
	}

	/** Called once from the mod initializer. */
	public static void register() {
		// The person, not the place, and after the fresh-run reset: this is about what one player's
		// advancement record says, and a player who joins a run already in progress needs it too.
		RunEvents.PLAYER_ENTERED_RUN.register((server, player, run) -> clearAdvancements(player));

		// And again on every join, because the line above is not durable on its own. Registered
		// after RunLifecycle's own join handling — the mod initializer registers that first — so
		// this sees the player where they have ended up.
		ServerPlayConnectionEvents.JOIN.register(
				(handler, sender, server) -> reconcile(handler.player));
	}

	/**
	 * Make the paying advancements say what the ledger says, for the run this player is in.
	 *
	 * <p>This exists because the reset above is not enough by itself, and the reason is worth
	 * knowing before changing either. Crossing into a run clears the player's advancements, and
	 * their admission to that run is written down beside it — but those are two different files
	 * that Minecraft saves at different times. A crash can leave the admission durable and the
	 * cleared advancements not, and the player comes back admitted to this run carrying the last
	 * one's completions. Nothing fires again for them, because as far as the lifecycle is concerned
	 * they never left, and every paying advancement they had already finished is now unearnable for
	 * the rest of the run.
	 *
	 * <p>So the admission mark is not treated as proof that the reset landed. The proof is the
	 * ledger, which is in the same file and the same write as the currency it goes with:
	 *
	 * <ul>
	 *   <li>this run has paid for it — leave it finished, so nothing is earned or announced twice;
	 *   <li>this run has not — revoke what has been obtained, so it is there to be earned again.
	 * </ul>
	 *
	 * <p>Only the advancements that pay are looked at, and "listed" is not the same as "pays": a
	 * balance override may price an existing entry at zero, and those are skipped here exactly as
	 * they are when one is finished. The rest are the fresh-world nicety the reset does, and nothing
	 * about the economy depends on them. Running this on every join makes the
	 * boundary replayable rather than once-only: doing it twice is doing it once, and doing it
	 * never is the only thing that costs anybody anything.
	 */
	private static void reconcile(ServerPlayer player) {
		int runId = RunAdmission.of(player);
		if (runId == RunAdmission.NO_RUN) {
			return;
		}
		MinecraftServer server = server(player);
		if (server == null) {
			return;
		}

		PlayerAdvancements record = player.getAdvancements();
		int givenBack = 0;
		for (Map.Entry<String, Integer> priced : BalanceManager.get().advancementRewards().entrySet()) {
			// Listed at nothing is listed at nothing: a balance override may price an existing entry
			// at zero, and one of those never pays and so never reaches the ledger. Walking it here
			// would read that absence as "this run has not paid for it" and take the player's
			// progress away on every join, for an advancement the economy is not interested in.
			if (priced.getValue() <= 0) {
				continue;
			}
			AdvancementHolder advancement = advancement(server, priced.getKey());
			if (advancement == null) {
				continue;
			}
			AdvancementProgress progress = record.getOrStartProgress(advancement);
			// Partial progress counts: half of a multi-criterion advancement carried over from the
			// last run is half of this run's work already done.
			if (!progress.hasProgress()
					|| Wallet.get().hasEarned(runId, keyFor(player, advancement))) {
				continue;
			}
			for (String criterion : completedCriteria(progress)) {
				record.revoke(advancement, criterion);
			}
			givenBack++;
		}

		if (givenBack > 0) {
			record.flushDirty(player, false);
			HardcoreRoguelite.LOGGER.info("Run {} has not paid {} for {} advancement(s) their record"
					+ " says they finished, so those are theirs to earn again", runId,
					player.getName().getString(), givenBack);
		}
	}

	private static AdvancementHolder advancement(MinecraftServer server, String id) {
		Identifier parsed = Identifier.tryParse(id);
		return parsed == null ? null : server.getAdvancements().get(parsed);
	}

	/**
	 * One advancement has just been completed by this player.
	 *
	 * <p>Called from {@code AdvancementPayoutMixin} at the exact point vanilla itself decides an
	 * advancement is newly done, which keeps an advancement finished by one of four alternative
	 * criteria from arriving here four times. Being called twice for a genuine re-completion is
	 * still possible — a crash can take the advancement record back to before it — and that is the
	 * ledger's job rather than the hook's.
	 *
	 * <p><b>A payout that cannot be written down un-earns the advancement.</b> {@link Wallet} hands
	 * the whole snapshot to the disk and refuses the change if it does not land, and leaving the
	 * completion standing would then cost the player the reward for good: an advancement is finished
	 * once, so a disk that comes back never gives that milestone another chance to pay. So the
	 * criterion that finished it is revoked — which re-registers vanilla's own triggers — and the
	 * caller cancels the award, leaving nothing behind: no currency, no vanilla reward, no toast and
	 * no announcement for something that is going to have to be earned again. Finishing it once the
	 * disk is writable then pays exactly once, like any other first time.
	 *
	 * @return false if the completion was rolled back and the award should be cancelled
	 */
	public static boolean completed(ServerPlayer player, AdvancementHolder advancement,
			String criterion) {
		int runId = RunAdmission.of(player);
		if (runId == RunAdmission.NO_RUN) {
			return true;
		}
		int reward = BalanceManager.get().advancementReward(advancement.id().toString());
		if (reward <= 0) {
			return true;
		}

		// Vanilla's own name for it — the advancement's title, or its id when it has no display.
		Component name = Advancement.name(advancement);
		boolean paid;
		try {
			paid = Wallet.get().earnOnce(runId, keyFor(player, advancement), reward);
		} catch (PersistenceException failure) {
			HardcoreRoguelite.LOGGER.error("Could not save the {} currency earned for {}, so {} has"
					+ " not been earned after all", reward, advancement.id(), advancement.id(), failure);
			player.getAdvancements().revoke(advancement, criterion);
			player.sendSystemMessage(Component.translatable("mhr.earn.not_saved", reward, name));
			return false;
		}
		if (!paid) {
			// This run has already been paid for it, and the advancement record has since lost the
			// completion — a crash between the wallet's write and the player's save is how. Saying
			// nothing is right: the player was paid and told at the time.
			HardcoreRoguelite.LOGGER.info("Run {} has already been paid for {}; not paying again",
					runId, advancement.id());
			return true;
		}

		player.sendSystemMessage(
				Component.translatable("mhr.earn.advancement", reward, name, Wallet.get().balance()));
		// The clients' copy of the purse is the shop state packet, and the HUD draws from it.
		ShopServer.sendToAll(server(player));
		return true;
	}

	/**
	 * What one payout is called in the ledger: this player, this advancement.
	 *
	 * <p>Per player rather than per run, because the purse is shared and two people in the same run
	 * each finishing the same advancement is two people's work.
	 */
	private static String keyFor(ServerPlayer player, AdvancementHolder advancement) {
		return player.getUUID() + "|" + advancement.id();
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
