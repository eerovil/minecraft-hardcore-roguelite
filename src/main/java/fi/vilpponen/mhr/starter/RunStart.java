package fi.vilpponen.mhr.starter;

import fi.vilpponen.mhr.HardcoreRoguelite;
import fi.vilpponen.mhr.run.RunEvents;
import java.util.List;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * When the starter chest appears: once, at the start of a run.
 *
 * <p>"Once per run" needs no flag of its own any more. A run now has an explicit beginning —
 * {@link RunEvents#RUN_STARTED}, fired by the lifecycle after the run's worlds are built and before
 * anybody is standing in them — and that beginning happens exactly once by construction. Logging
 * out and back in is not a run start, a second player arriving is not a run start, and neither is
 * loading the save again; so none of them produces a second chest, and none of them needs to be
 * reasoned about here.
 *
 * <p>The chest goes at the run's overworld spawn rather than next to a player, because at run start
 * there is no player in the world yet — they arrive immediately afterwards, at that same spot.
 *
 * @see fi.vilpponen.mhr.run.RunLifecycle
 */
public final class RunStart {
	private RunStart() {
	}

	public static void register() {
		// One check, registered once. What it validates against is a world's own registries, so
		// the lifecycle hands it the current server's and takes them back when that world closes —
		// otherwise a second world in the same client would be judged by the first one's data as
		// well as its own. See StarterItems.installCheck.
		StarterItems.installCheck();
		ServerLifecycleEvents.SERVER_STARTED.register(StarterItems::useRegistriesOf);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> StarterItems.forgetRegistries());

		RunEvents.RUN_STARTED.register((server, overworld, run) -> onRunStarted(server, overworld));
	}

	private static void onRunStarted(MinecraftServer server, ServerLevel overworld) {
		BlockPos spawn = server.getRespawnData().pos();
		StarterChest.Placement placement = grant(overworld, spawn, Direction.NORTH);
		if (placement == null) {
			return;
		}

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			player.sendSystemMessage(
					Component.literal("This run's starter chest is " + describe(placement) + "."));
			warnAboutOverflow(placement, player::sendSystemMessage);
		}
	}

	/**
	 * Place the chest holding everything the player has bought. Used by the run-start hook, and by
	 * the dev command so the chest can be looked at without starting a run.
	 *
	 * @return null when no starter items are owned, so nothing was placed and no block was touched.
	 */
	public static StarterChest.Placement grant(ServerLevel level, BlockPos near, Direction facing) {
		List<ItemStack> stacks = StarterItems.ownedStacks(level.registryAccess());
		if (stacks.isEmpty()) {
			return null;
		}

		StarterChest.Placement placement = StarterChest.place(level, near, facing, stacks);
		if (placement != null) {
			HardcoreRoguelite.LOGGER.info("Starter chest {}", describe(placement));
		}
		return placement;
	}

	public static String describe(StarterChest.Placement placement) {
		BlockPos pos = placement.pos();
		return (placement.doubleChest() ? "(double) at " : "at ")
				+ pos.getX() + " " + pos.getY() + " " + pos.getZ();
	}

	/** Say out loud what did not fit, wherever the caller can be heard. */
	public static void warnAboutOverflow(StarterChest.Placement placement, Consumer<Component> say) {
		if (placement.omitted().isEmpty()) {
			return;
		}
		say.accept(Component.literal(
				"Too many starter items for one double chest — these were left out: "
						+ StarterChest.describe(placement.omitted())
						+ ". That is too much in the balance catalogue, not a lost purchase; see the server log."));
	}
}
