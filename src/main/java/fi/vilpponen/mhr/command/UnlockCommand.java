package fi.vilpponen.mhr.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import fi.vilpponen.mhr.Unlock;
import fi.vilpponen.mhr.UnlockEffects;
import fi.vilpponen.mhr.UnlockState;
import fi.vilpponen.mhr.animal.AnimalSpecies;
import fi.vilpponen.mhr.equipment.EquipmentLocks;
import fi.vilpponen.mhr.equipment.EquipmentSlots;
import fi.vilpponen.mhr.progression.Catalogue;
import fi.vilpponen.mhr.progression.Offer;
import fi.vilpponen.mhr.progression.Wallet;
import fi.vilpponen.mhr.run.RunLifecycle;
import fi.vilpponen.mhr.starter.RunStart;
import fi.vilpponen.mhr.starter.StarterChest;
import fi.vilpponen.mhr.starter.StarterItems;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * The developer way in to progression: grants and takes away without charging for anything.
 *
 * <p>Not a stand-in for the shop — the shop exists, at {@code /mhr shop}, and every purchase made
 * there goes through {@link fi.vilpponen.mhr.progression.Purchase} and costs currency. This writes
 * ownership directly, which is what makes it useful: a scenario, or a playtest, can put progression
 * exactly where it wants it without first arranging the currency to buy it.
 *
 * <p>{@code /mhr list}, {@code /mhr unlock <id> [level]}, {@code /mhr lock <id>}, and
 * {@code /mhr starterchest [pos]} to place this run's chest again without making a new world.
 *
 * <p>Without a level, {@code unlock} buys the next one, which is the shop's own behaviour for the
 * repeatable unlocks and plain ownership for the rest.
 *
 * <p>There is one id space. A starter item takes the same {@code unlock} and {@code lock} as a
 * built-in unlock, and is written to the same save file under the same name — the only difference
 * is that nothing in Java is named after it, because the whole of it is the item stack in the
 * balance catalogue.
 */
public final class UnlockCommand {
	private UnlockCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("mhr")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("list")
						.executes(UnlockCommand::list))
				.then(Commands.literal("unlock")
						.then(idArgument()
								.executes(UnlockCommand::buyNextLevel)
								.then(Commands.argument("level", IntegerArgumentType.integer(0))
										.executes(UnlockCommand::setGivenLevel))))
				.then(Commands.literal("lock")
						.then(idArgument().executes(context -> apply(context, 0))))
				.then(Commands.literal("starterchest")
						.executes(context -> starterChest(context, null))
						.then(Commands.argument("pos", BlockPosArgument.blockPos())
								.executes(context -> starterChest(context, BlockPosArgument.getSpawnablePos(context, "pos"))))));
	}

	private static RequiredArgumentBuilder<CommandSourceStack, String> idArgument() {
		return Commands.argument("id", StringArgumentType.word())
				.suggests((context, builder) -> {
					// The catalogue is the product list, so it already holds every id a constant is
					// named after as well as the ones nothing is named after.
					for (Offer offer : Catalogue.offers()) {
						builder.suggest(offer.id());
					}
					return builder.buildFuture();
				});
	}

	private static int list(CommandContext<CommandSourceStack> context) {
		UnlockState state = UnlockState.get();
		StringBuilder lines = new StringBuilder("Unlocks:");
		for (Unlock unlock : Unlock.values()) {
			int level = state.level(unlock);
			lines.append("\n  ")
					.append(level > 0 ? "[owned] " : "[locked] ")
					.append(unlock.id());
			if (unlock.isRepeatable()) {
				lines.append(" (level ").append(level).append('/').append(unlock.maxLevel()).append(')');
			}
		}
		// Everything else the catalogue sells: starter items and border tiers today, whatever is
		// added to default-balance.json tomorrow.
		List<Offer> catalogueOnly = new ArrayList<>();
		for (Offer offer : Catalogue.offers()) {
			if (Unlock.byId(offer.id()) == null) {
				catalogueOnly.add(offer);
			}
		}
		lines.append("\nCatalogue only:");
		if (catalogueOnly.isEmpty()) {
			lines.append("\n  (nothing in the balance catalogue)");
		}
		for (Offer offer : catalogueOnly) {
			lines.append("\n  ")
					.append(offer.isOwned() ? "[owned] " : "[locked] ")
					.append(offer.id());
		}
		lines.append("\nCurrency: ").append(Wallet.get().balance());
		// Where the starter-chest line used to be. That reported a per-world "already given" flag,
		// and this branch retires the flag: a run now has an explicit beginning, so the chest is
		// placed once by construction rather than once per world. The run state says the same thing
		// and more.
		lines.append("\nRun lifecycle: ").append(RunLifecycle.get().describe());
		String message = lines.toString();
		context.getSource().sendSuccess(() -> Component.literal(message), false);
		return Unlock.values().length + catalogueOnly.size();
	}

	/**
	 * Place the chest again, so it can be looked at without making a new world.
	 *
	 * <p>Next to you when you run it in game. With an explicit position it also works from the
	 * server console, which is the only way to see the chest at all on a server with no client
	 * attached.
	 */
	private static int starterChest(CommandContext<CommandSourceStack> context, BlockPos pos) {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = source.getPlayer();
		if (pos == null && player == null) {
			source.sendFailure(Component.literal("Give a position, or run it as a player to get it next to you."));
			return 0;
		}

		StarterChest.Placement placement = RunStart.grant(
				source.getLevel(),
				pos != null ? pos : player.blockPosition(),
				player != null ? player.getDirection().getOpposite() : Direction.NORTH);
		if (placement == null) {
			source.sendFailure(Component.literal("No starter items are owned, so there is nothing to put in a chest."));
			return 0;
		}

		String where = RunStart.describe(placement);
		source.sendSuccess(() -> Component.literal("Starter chest " + where + "."), true);
		RunStart.warnAboutOverflow(placement, message -> source.sendSuccess(() -> message, false));
		return 1;
	}

	/** {@code /mhr unlock <id>} — buy the next level, which for a plain unlock means owning it. */
	private static int buyNextLevel(CommandContext<CommandSourceStack> context) {
		String id = StringArgumentType.getString(context, "id");
		return apply(context, UnlockState.get().level(id) + 1);
	}

	private static int setGivenLevel(CommandContext<CommandSourceStack> context) {
		return apply(context, IntegerArgumentType.getInteger(context, "level"));
	}

	private static int apply(CommandContext<CommandSourceStack> context, int level) {
		String id = StringArgumentType.getString(context, "id");
		Unlock unlock = Unlock.byId(id);
		if (unlock == null) {
			return applyToCatalogueId(context, id, level);
		}

		boolean changed = UnlockState.get().setLevel(unlock, level);
		if (changed) {
			// Tell the clients, re-apply the slot rule to anyone already wearing something, and
			// resize the world if a border tier was what changed.
			UnlockEffects.applyAll(context.getSource().getServer());
		}
		int owned = UnlockState.get().level(unlock);
		String verb = owned > 0 ? "Unlocked " : "Locked ";
		String at = unlock.isRepeatable() ? " at level " + owned + "/" + unlock.maxLevel() : "";
		String note = changed ? "" : " (no change)";
		// Three different answers, and giving the wrong one sends you looking in the wrong place
		// for the change you just paid for. An equipment slot and a crafted enchant are yours the
		// moment you buy them. A worldgen unlock is stuck with the terrain that already exists. An
		// animal is in between: natural spawning re-asks every time, so land you have already walked
		// starts or stops producing that species at once, while the animals already alive stay put.
		String suffix;
		if (EquipmentLocks.isSlotUnlock(unlock) || unlock == Unlock.CRAFT_ENCHANT) {
			suffix = ".";
		} else if (AnimalSpecies.isAnimalUnlock(unlock)) {
			suffix = " — new terrain and later natural spawns follow this at once;"
					+ " mobs already in the world stay.";
		} else {
			suffix = " — worldgen changes apply to new chunks only.";
		}
		context.getSource().sendSuccess(() -> Component.literal(verb + unlock.id() + at + note + suffix), true);
		return changed ? 1 : 0;
	}

	/** The same thing for an id the catalogue sells and no constant is named after. */
	private static int applyToCatalogueId(CommandContext<CommandSourceStack> context, String id, int level) {
		if (!Catalogue.sells(id)) {
			context.getSource().sendFailure(Component.literal("No such unlock: " + id));
			return 0;
		}

		boolean changed = UnlockState.get().setLevel(id, level);
		if (changed) {
			UnlockEffects.applyAll(context.getSource().getServer());
		}
		String verb = UnlockState.get().isOwned(id) ? "Unlocked " : "Locked ";
		String note = changed ? "" : " (no change)";
		String suffix = StarterItems.isStarterItem(id)
				? " — it lands in the chest at the start of the next run."
				: ".";
		context.getSource().sendSuccess(() -> Component.literal(verb + id + note + suffix), true);
		return changed ? 1 : 0;
	}
}
