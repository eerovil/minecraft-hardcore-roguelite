package fi.vilpponen.mhr.starter;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import fi.vilpponen.mhr.HardcoreRoguelite;
import java.util.List;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * When the starter chest appears: once, at the start of a run.
 *
 * <p>A run is a world, so "once per run" is a flag in the world's own save rather than in the
 * cross-run unlock file. Delete the world and the next one gets its own chest; log out and back
 * in, or bring a second player, and nothing happens twice.
 *
 * <p>It is placed when a player first joins rather than when the world is created, because the
 * chest is supposed to be next to the player and there is no player until then.
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

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> onJoin(handler.player));
	}

	/** @return true if this run has already had its starter chest. */
	public static boolean alreadyGranted(ServerLevel anyLevel) {
		return granted(anyLevel).starterChest;
	}

	/**
	 * The flag, always read from the overworld.
	 *
	 * <p>Saved data is per dimension, and "once per run" has to mean once per world — otherwise a
	 * player who first appears in the Nether would be owed a second chest.
	 */
	private static Granted granted(ServerLevel anyLevel) {
		return anyLevel.getServer().overworld().getDataStorage().computeIfAbsent(Granted.TYPE);
	}

	private static void onJoin(ServerPlayer player) {
		ServerLevel level = player.level();
		Granted granted = granted(level);
		if (granted.starterChest) {
			return;
		}

		// Claim it before placing: a failure to place must not turn into a chest every login.
		granted.starterChest = true;
		granted.setDirty();

		StarterChest.Placement placement =
				grant(level, player.blockPosition(), player.getDirection().getOpposite());
		if (placement != null) {
			player.sendSystemMessage(Component.literal("This run's starter chest is " + describe(placement) + "."));
			warnAboutOverflow(placement, player::sendSystemMessage);
		}
	}

	/**
	 * Place the chest holding everything the player has bought. Used by the join hook, and by the
	 * dev command so the chest can be looked at without making a new world.
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

	/** The one bit of per-world state this feature has: whether this run already got its chest. */
	public static final class Granted extends SavedData {
		static final Codec<Granted> CODEC = RecordCodecBuilder.create(instance -> instance.group(
						Codec.BOOL.fieldOf("starter_chest").forGetter(data -> data.starterChest))
				.apply(instance, Granted::new));

		static final SavedDataType<Granted> TYPE = new SavedDataType<>(
				Identifier.fromNamespaceAndPath(HardcoreRoguelite.MOD_ID, "run_start"),
				Granted::new,
				CODEC,
				DataFixTypes.LEVEL);

		private boolean starterChest;

		private Granted() {
		}

		private Granted(boolean starterChest) {
			this.starterChest = starterChest;
		}
	}
}
