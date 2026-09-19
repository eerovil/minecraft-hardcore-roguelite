package fi.vilpponen.mhr.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import fi.vilpponen.mhr.HardcoreRoguelite;
import fi.vilpponen.mhr.core.Balance;
import fi.vilpponen.mhr.core.BalanceException;
import fi.vilpponen.mhr.core.BalanceManager;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * {@code /mhr reload} and {@code /mhr balance}, for tuning numbers without restarting the game.
 *
 * <p>The point is the playtesting loop: change diamond from 100 to 60 in the config override, run
 * the command, and the next thing that asks the price gets 60. Nothing here restarts the world.
 *
 * <p>It registers its own {@code mhr} root rather than editing {@link UnlockCommand}. Brigadier
 * merges two literals of the same name, so both sets of subcommands end up under one command.
 */
public final class BalanceCommand {
	private BalanceCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("mhr")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("reload").executes(BalanceCommand::reload))
				.then(Commands.literal("balance").executes(BalanceCommand::show)));
	}

	private static int reload(CommandContext<CommandSourceStack> context) {
		Balance balance;
		try {
			balance = BalanceManager.reload();
		} catch (BalanceException e) {
			HardcoreRoguelite.LOGGER.error("Balance reload failed, keeping the balance already loaded", e);
			context.getSource().sendFailure(Component.literal(
					"Balance reload failed, nothing changed: " + e.getMessage()).withStyle(ChatFormatting.RED));
			return 0;
		}

		context.getSource().sendSuccess(() -> Component.literal(summary(balance)), true);
		context.getSource().sendSuccess(() -> Component.literal(
				"Prices and rewards apply from now on. A run already in progress keeps the world border "
						+ "it started with — that one follows the new size on the next run.")
				.withStyle(ChatFormatting.GRAY), false);
		return 1;
	}

	private static int show(CommandContext<CommandSourceStack> context) {
		Balance balance = BalanceManager.get();
		StringBuilder lines = new StringBuilder(summary(balance));
		lines.append("\n  override: ").append(BalanceManager.overrideFile());
		lines.append("\n  mob damage: ").append(balance.mobDamageMultiplier()).append("x");
		balance.worldBorder().forEach((id, tier) -> lines.append("\n  border ").append(id).append(": ")
				.append(tier.isUnbounded() ? "unbounded" : String.valueOf(tier.size().getAsDouble()))
				.append(" for ").append(tier.price()));
		balance.unlocks().forEach((id, unlock) ->
				lines.append("\n  ").append(id).append(": ").append(unlock.price()));
		String message = lines.toString();
		context.getSource().sendSuccess(() -> Component.literal(message), false);
		return 1;
	}

	private static String summary(Balance balance) {
		return "Balance: " + balance.unlocks().size() + " unlocks, "
				+ balance.worldBorder().size() + " border tiers, "
				+ balance.advancementRewards().size() + " paying advancements, mob damage x"
				+ balance.mobDamageMultiplier();
	}
}
