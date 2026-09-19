package fi.vilpponen.mhr.border;

import fi.vilpponen.mhr.HardcoreRoguelite;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
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
	/**
	 * The smallest the end is ever allowed to be, whatever the tier says. Centered on the origin
	 * it has to hold both the main island and the obsidian arrival platform 100 blocks east of it,
	 * or arriving through an end portal would drop you outside the border. A balance value like the
	 * tier diameters, so it is a constant here.
	 */
	private static final double END_MIN_DIAMETER = 512.0;

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
		BlockPos spawn = server.getWorldData().overworldData().getRespawnData().pos();

		for (ServerLevel level : server.getAllLevels()) {
			apply(level, tier, spawn);
		}

		String size = tier.isInfinite() ? "no practical limit" : (long) tier.diameter() + " blocks across";
		HardcoreRoguelite.LOGGER.info("World border tier {}: {}, overworld centered on {}, {}",
				tier.id(), size, spawn.getX(), spawn.getZ());
	}

	/**
	 * Each dimension is centered on wherever the run's spawn comes out in that dimension, not on
	 * the overworld's raw coordinates. Getting this wrong is what makes a portal a trap: build one
	 * well away from 0,0 and you arrive somewhere the border has never covered.
	 */
	private static void apply(ServerLevel level, BorderTier tier, BlockPos overworldSpawn) {
		WorldBorder border = level.getWorldBorder();

		if (level.dimension().equals(Level.END)) {
			// The end ignores where you came from: every arrival lands on the obsidian platform at
			// ServerLevel.END_SPAWN_POINT, and the island and the return portal sit at the origin.
			// So it centers on the origin, and never gets smaller than it takes to hold both.
			border.setCenter(0.5, 0.5);
			border.setSize(Math.max(tier.diameter(), END_MIN_DIAMETER));
			logLevel(level, border);
			return;
		}

		// A nether portal maps overworld coordinates through the dimension's own scale — 1:8 for
		// the nether — so the center has to travel the same way. Reading it off the dimension type
		// rather than hardcoding 8 means a custom scaled dimension lands in the right place too.
		double scale = level.dimensionType().coordinateScale();
		border.setCenter((overworldSpawn.getX() + 0.5) / scale, (overworldSpawn.getZ() + 0.5) / scale);
		border.setSize(tier.diameter());
		logLevel(level, border);
	}

	private static void logLevel(ServerLevel level, WorldBorder border) {
		HardcoreRoguelite.LOGGER.info("  {}: {} wide, centered on {}, {}",
				level.dimension().identifier(), (long) border.getSize(),
				(long) border.getCenterX(), (long) border.getCenterZ());
	}
}
