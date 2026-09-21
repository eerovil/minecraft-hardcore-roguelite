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
 * <p>This is the spare key, not the door. Normal play opens the shop by right-clicking the block
 * on the lobby island — see {@code fi.vilpponen.mhr.shop.ShopBlock}. Both end in
 * {@link ShopServer#open(ServerPlayer)}, and the command stays for operators and for tests, which
 * need a shop they can open from anywhere.
 *
 * <p>{@code /mhr currency give} and {@code set} put a number in the purse by hand. Gameplay pays
 * out on its own now — finishing an advancement inside a run does, through
 * {@link fi.vilpponen.mhr.earn.AdvancementPayouts} — so this is for tests and playtests that want
 * to start from a particular amount rather than earn their way to it.
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
		// A client without the mod cannot be shown a shop, and has already been told so by the
		// door it knocked on. Nothing to add here but the failing exit code.
		return ShopServer.open(player) ? 1 : 0;
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
