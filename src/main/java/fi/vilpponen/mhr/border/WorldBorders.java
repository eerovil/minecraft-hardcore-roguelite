package fi.vilpponen.mhr.border;

import fi.vilpponen.mhr.HardcoreRoguelite;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.border.WorldBorder;

/**
 * The run's world border, and the one place that decides how big it is.
 *
 * <p>This is the whole public surface of the border feature: pick a tier with
 * {@link #select(BorderTier)}, read it back with {@link #selectedTier()}. The border is then set up
 * when a server starts, and again immediately whenever the tier changes.
 *
 * <p>The tier lives in memory and starts at {@link BorderTier#DEFAULT} every time the game starts.
 * Remembering it between runs is the job of the permanent unlock state, which this feature
 * deliberately does not touch — when that arrives it only has to call {@link #select(BorderTier)}.
 *
 * <p>Nothing about world generation is involved. The border is placed on the finished world, so
 * changing the tier never means changing worldgen code.
 */
public final class WorldBorders {
	private static volatile BorderTier selectedTier = BorderTier.DEFAULT;
	private static volatile MinecraftServer runningServer;

	private WorldBorders() {
	}

	/** Called once from the mod initializer. */
	public static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			runningServer = server;
			apply(server);
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> runningServer = null);
	}

	public static BorderTier selectedTier() {
		return selectedTier;
	}

	/**
	 * Choose the tier future runs start with. Takes effect on the running world straight away too,
	 * so the same call works from a shop, from a dev command, or before a run begins.
	 */
	public static void select(BorderTier tier) {
		selectedTier = tier;
		MinecraftServer server = runningServer;
		if (server != null) {
			server.execute(() -> apply(server));
		}
	}

	/** Put the selected tier's border on every dimension of a running world. */
	public static void apply(MinecraftServer server) {
		BorderTier tier = selectedTier;
		// The world's own spawn point, not MinecraftServer.getRespawnData(): that one is already
		// clamped inside the current border, so centering on it would drag the border around.
		BlockPos center = server.getWorldData().overworldData().getRespawnData().pos();

		for (ServerLevel level : server.getAllLevels()) {
			// Every dimension gets the same numbers, which is how vanilla's border behaves: the
			// nether border sits at the same coordinates as the overworld one rather than being
			// converted through the 1:8 scale.
			WorldBorder border = level.getWorldBorder();
			border.setCenter(center.getX() + 0.5, center.getZ() + 0.5);
			border.setSize(tier.diameter());
		}

		String size = tier.isInfinite() ? "no practical limit" : (long) tier.diameter() + " blocks across";
		HardcoreRoguelite.LOGGER.info("World border tier {}: {}, centered on {}, {}",
				tier.id(), size, center.getX(), center.getZ());
	}
}
