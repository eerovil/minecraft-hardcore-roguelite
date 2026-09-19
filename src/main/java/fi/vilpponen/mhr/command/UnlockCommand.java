package fi.vilpponen.mhr.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import fi.vilpponen.mhr.Unlock;
import fi.vilpponen.mhr.UnlockState;
import fi.vilpponen.mhr.animal.AnimalSpecies;
import fi.vilpponen.mhr.equipment.EquipmentLocks;
import fi.vilpponen.mhr.equipment.EquipmentSlots;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * A developer command standing in for the shop, which does not exist yet.
 *
 * <p>{@code /mhr list}, {@code /mhr unlock <id> [level]}, {@code /mhr lock <id>}.
 *
 * <p>Without a level, {@code unlock} buys the next one, which is the shop's own behaviour for the
 * repeatable unlocks and plain ownership for the rest.
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
						.then(idArgument().executes(context -> apply(context, 0)))));
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
			int level = state.level(unlock);
			lines.append("\n  ")
					.append(level > 0 ? "[owned] " : "[locked] ")
					.append(unlock.id());
			if (unlock.isRepeatable()) {
				lines.append(" (level ").append(level).append('/').append(unlock.maxLevel()).append(')');
			}
		}
		String message = lines.toString();
		context.getSource().sendSuccess(() -> Component.literal(message), false);
		return Unlock.values().length;
	}

	/** {@code /mhr unlock <id>} — buy the next level, which for a plain unlock means owning it. */
	private static int buyNextLevel(CommandContext<CommandSourceStack> context) {
		Unlock unlock = unlock(context);
		if (unlock == null) {
			return 0;
		}
		return apply(context, UnlockState.get().level(unlock) + 1);
	}

	private static int setGivenLevel(CommandContext<CommandSourceStack> context) {
		return apply(context, IntegerArgumentType.getInteger(context, "level"));
	}

	private static Unlock unlock(CommandContext<CommandSourceStack> context) {
		String id = StringArgumentType.getString(context, "id");
		Unlock unlock = Unlock.byId(id);
		if (unlock == null) {
			context.getSource().sendFailure(Component.literal("No such unlock: " + id));
		}
		return unlock;
	}

	private static int apply(CommandContext<CommandSourceStack> context, int level) {
		Unlock unlock = unlock(context);
		if (unlock == null) {
			return 0;
		}

		boolean changed = UnlockState.get().setLevel(unlock, level);
		if (changed) {
			// Tell the clients, and re-apply the slot rule to anyone already wearing something.
			EquipmentSlots.onUnlocksChanged(context.getSource().getServer());
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
}
