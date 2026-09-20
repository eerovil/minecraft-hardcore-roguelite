package fi.vilpponen.mhr.run;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.OptionalLong;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * The loop's controls, standing in for the lobby's shop screen until that exists.
 *
 * <p>{@code /mhr run} says where the save is, {@code /mhr run start [seed]} is the "start next run"
 * action, and {@code /mhr run end} finishes a run without anybody having to die for it.
 *
 * <p>The seed argument is here for tests and for looking at the same world twice. Ordinary play
 * never names one — a run that could be replayed would not be a roguelite.
 *
 * <p>Registered on its own rather than alongside the other commands: brigadier merges two
 * registrations of the same {@code /mhr} root, so the run feature stays in one package.
 */
public final class RunCommand {
	private RunCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("mhr")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("run")
						.executes(RunCommand::status)
						.then(Commands.literal("start")
								.executes(context -> start(context, OptionalLong.empty()))
								.then(Commands.argument("seed", LongArgumentType.longArg())
										.executes(context -> start(context, OptionalLong.of(
												LongArgumentType.getLong(context, "seed"))))))
						.then(Commands.literal("end")
								.executes(RunCommand::end))));
	}

	private static int status(CommandContext<CommandSourceStack> context) {
		String described = RunLifecycle.get().describe();
		context.getSource().sendSuccess(() -> Component.literal("Run lifecycle: " + described), false);
		return 1;
	}

	private static int start(CommandContext<CommandSourceStack> context, OptionalLong seed) {
		try {
			RunLifecycle.get().startRun(seed);
		} catch (IllegalStateException e) {
			context.getSource().sendFailure(Component.literal("Cannot start a run: " + e.getMessage()));
			return 0;
		}
		RunRecord record = RunLifecycle.get().record();
		context.getSource().sendSuccess(
				() -> Component.literal("Run " + record.runId() + " started on seed " + record.seed()), true);
		return 1;
	}

	private static int end(CommandContext<CommandSourceStack> context) {
		try {
			RunLifecycle.get().endRun("ended by " + context.getSource().getTextName());
		} catch (IllegalStateException e) {
			context.getSource().sendFailure(Component.literal("Cannot end a run: " + e.getMessage()));
			return 0;
		}
		context.getSource().sendSuccess(() -> Component.literal("Back in the lobby."), true);
		return 1;
	}
}
