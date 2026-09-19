package fi.vilpponen.mhr.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import fi.vilpponen.mhr.Unlock;
import fi.vilpponen.mhr.UnlockState;
import fi.vilpponen.mhr.equipment.EquipmentLocks;
import fi.vilpponen.mhr.equipment.EquipmentSlots;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * A developer command standing in for the shop, which does not exist yet.
 *
 * <p>{@code /mhr list}, {@code /mhr unlock <id>}, {@code /mhr lock <id>}.
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
						.then(idArgument().executes(context -> set(context, true))))
				.then(Commands.literal("lock")
						.then(idArgument().executes(context -> set(context, false)))));
	}

	private static RequiredArgumentBuilder<CommandSourceStack, String> idArgument() {
		return Commands.argument("id", StringArgumentType.word())
				.suggests((context, builder) -> {
					for (Unlock unlock : Unlock.values()) {
						builder.suggest(unlock.id());
					}
					return builder.buildFuture();
				});
	}

	private static int list(CommandContext<CommandSourceStack> context) {
		UnlockState state = UnlockState.get();
		StringBuilder lines = new StringBuilder("Unlocks:");
		for (Unlock unlock : Unlock.values()) {
			lines.append("\n  ")
					.append(state.isOwned(unlock) ? "[owned] " : "[locked] ")
					.append(unlock.id());
		}
		String message = lines.toString();
		context.getSource().sendSuccess(() -> Component.literal(message), false);
		return Unlock.values().length;
	}

	private static int set(CommandContext<CommandSourceStack> context, boolean owned) {
		String id = StringArgumentType.getString(context, "id");
		Unlock unlock = Unlock.byId(id);
		if (unlock == null) {
			context.getSource().sendFailure(Component.literal("No such unlock: " + id));
			return 0;
		}

		boolean changed = UnlockState.get().set(unlock, owned);
		if (changed) {
			// Tell the clients, and re-apply the slot rule to anyone already wearing something.
			EquipmentSlots.onUnlocksChanged(context.getSource().getServer());
		}
		String verb = owned ? "Unlocked " : "Locked ";
		String note = changed ? "" : " (no change)";
		// Equipment slots take effect at once; the worldgen unlocks do not.
		String suffix = EquipmentLocks.isSlotUnlock(unlock)
				? "."
				: " — worldgen changes apply to new chunks only.";
		context.getSource().sendSuccess(() -> Component.literal(verb + unlock.id() + note + suffix), true);
		return changed ? 1 : 0;
	}
}
