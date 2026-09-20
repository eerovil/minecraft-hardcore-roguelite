package fi.vilpponen.mhr.border;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import fi.vilpponen.mhr.core.Balance;
import fi.vilpponen.mhr.core.BalanceManager;
import net.minecraft.network.chat.Component;

/**
 * The developer way to pick a border tier, ignoring what has been bought.
 *
 * <p>{@code /mhr border} shows the tier, {@code /mhr border <tier>} changes it.
 *
 * <p>Not a stand-in for the shop — the shop sells the tiers, and a run ordinarily gets the largest
 * one owned. This overrides that by hand for the world it is run in, which several worldgen tests
 * depend on: they generate ordinary terrain thousands of blocks from spawn, and the roguelite
 * border would otherwise be in the way. See {@code WorldBorders.select}.
 *
 * <p>Registered on its own rather than alongside the unlock command: brigadier merges two
 * registrations of the same {@code /mhr} root, so the border feature stays in one package.
 */
public final class BorderCommand {
	private BorderCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("mhr")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("border")
						.executes(BorderCommand::show)
						.then(Commands.argument("tier", StringArgumentType.word())
								.suggests((context, builder) -> {
									for (BorderTier tier : BorderTier.values()) {
										builder.suggest(tier.id());
									}
									return builder.buildFuture();
								})
								.executes(BorderCommand::set))));
	}

	private static int show(CommandContext<CommandSourceStack> context) {
		BorderTier tier = WorldBorders.selectedTier();
		context.getSource().sendSuccess(() -> Component.literal("Border tier: " + tier.id() + describe(tier)), false);
		return 1;
	}

	private static int set(CommandContext<CommandSourceStack> context) {
		String id = StringArgumentType.getString(context, "tier");
		BorderTier tier = BorderTier.byId(id);
		if (tier == null) {
			context.getSource().sendFailure(Component.literal("No such border tier: " + id));
			return 0;
		}

		WorldBorders.select(tier);
		context.getSource().sendSuccess(() -> Component.literal("Border tier: " + tier.id() + describe(tier)), true);
		return 1;
	}

	private static String describe(BorderTier tier) {
		Balance.BorderBalance balance = tier.balance(BalanceManager.get());
		return balance.isUnbounded()
				? " (no practical limit)"
				: " (" + (long) balance.size().getAsDouble() + " blocks across)";
	}
}
