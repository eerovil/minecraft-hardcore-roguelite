package fi.vilpponen.mhr.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import fi.vilpponen.mhr.progression.Wallet;
import fi.vilpponen.mhr.shop.ShopServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /mhr shop} to open the shop, and {@code /mhr currency} to look at or change the purse.
 *
 * <p>Opening the shop is a command rather than a key because the design's real entry point is the
 * death screen, and death handling is not built yet. When it is, it calls
 * {@link ShopServer#open(ServerPlayer)} — the same door this command uses.
 *
 * <p>{@code /mhr currency give} and {@code set} are the development stand-ins for earning.
 * <b>Nothing in gameplay pays out yet</b>, and deliberately so: how currency is earned is still
 * open in {@code docs/open-questions.md}, and picking a rule here would settle it by accident. The
 * balance file's {@code currency.advancements} table is the price list that decision will use, not
 * the decision itself.
 */
public final class ShopCommand {
	private ShopCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("mhr")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("shop")
						.executes(ShopCommand::openShop))
				.then(Commands.literal("currency")
						.executes(ShopCommand::showBalance)
						.then(Commands.literal("give")
								.then(Commands.argument("amount", IntegerArgumentType.integer(0))
										.executes(context -> change(context, true))))
						.then(Commands.literal("set")
								.then(Commands.argument("amount", IntegerArgumentType.integer(0))
										.executes(context -> change(context, false))))));
	}

	private static int openShop(CommandContext<CommandSourceStack> context) {
		ServerPlayer player = context.getSource().getPlayer();
		if (player == null) {
			context.getSource().sendFailure(Component.literal("Only a player can open the shop."));
			return 0;
		}
		ShopServer.open(player);
		return 1;
	}

	private static int showBalance(CommandContext<CommandSourceStack> context) {
		int balance = Wallet.get().balance();
		context.getSource().sendSuccess(() -> Component.literal("Currency: " + balance), false);
		return balance;
	}

	private static int change(CommandContext<CommandSourceStack> context, boolean add) {
		int amount = IntegerArgumentType.getInteger(context, "amount");
		Wallet wallet = Wallet.get();
		if (add) {
			wallet.earn(amount);
		} else {
			wallet.set(amount);
		}

		// Every open shop screen is showing the old total, and an affordable row that still looks
		// unaffordable is indistinguishable from the shop being broken.
		ShopServer.sendToAll(context.getSource().getServer());

		int balance = wallet.balance();
		context.getSource().sendSuccess(() -> Component.literal("Currency: " + balance), true);
		return balance;
	}
}
