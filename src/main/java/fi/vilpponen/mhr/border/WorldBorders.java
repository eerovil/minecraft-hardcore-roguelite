package fi.vilpponen.mhr.border;

import fi.vilpponen.mhr.HardcoreRoguelite;
import fi.vilpponen.mhr.core.Balance;
import fi.vilpponen.mhr.core.BalanceManager;
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
 *
 * <p>No size is written down here either: the tier sizes come from the balance file, read each time
 * the border is applied. A balance reload mid-run therefore does not resize a world under the
 * player — it is picked up the next time a border is set, which is the next run or the next tier
 * change. See {@code docs/balance.md}.
 */
public final class WorldBorders {
	/**
	 * Where the end's own floor size lives in the balance file. Centered on the origin the end has
	 * to hold both the main island and the obsidian arrival platform 100 blocks east of it, or
	 * arriving through an end portal would drop you outside the border. It is a balance number like
	 * any other, so it sits in data rather than in Java.
	 */
	private static final String END_MINIMUM_SIZE = "endBorder.minimumSize";

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
		Balance balance = BalanceManager.get();
		Balance.BorderBalance tierBalance = tier.balance(balance);
		// An unbounded tier still has a number, because vanilla's border always does: its own
		// maximum, which is also what a normal world starts with.
		double diameter = tierBalance.size().orElse(WorldBorder.MAX_SIZE);

		// The world's own spawn point, not MinecraftServer.getRespawnData(): that one is already
		// clamped inside the current border, so centering on it would drag the border around.
		BlockPos spawn = server.getWorldData().overworldData().getRespawnData().pos();

		for (ServerLevel level : server.getAllLevels()) {
			apply(level, balance, diameter, spawn);
		}

		String size = tierBalance.isUnbounded() ? "no practical limit" : (long) diameter + " blocks across";
		HardcoreRoguelite.LOGGER.info("World border tier {}: {}, overworld centered on {}, {}",
				tier.id(), size, spawn.getX(), spawn.getZ());
	}

	/**
	 * Each dimension is centered on wherever the run's spawn comes out in that dimension, not on
	 * the overworld's raw coordinates. Getting this wrong is what makes a portal a trap: build one
	 * well away from 0,0 and you arrive somewhere the border has never covered.
	 */
	private static void apply(ServerLevel level, Balance balance, double diameter, BlockPos overworldSpawn) {
		WorldBorder border = level.getWorldBorder();

		if (level.dimension().equals(Level.END)) {
			// The end ignores where you came from: every arrival lands on the obsidian platform at
			// ServerLevel.END_SPAWN_POINT, and the island and the return portal sit at the origin.
			// So it centers on the origin, and never gets smaller than it takes to hold both.
			border.setCenter(0.5, 0.5);
			border.setSize(Math.max(diameter, balance.number(END_MINIMUM_SIZE)));
			logLevel(level, border);
			return;
		}

		// A nether portal maps overworld coordinates through the dimension's own scale — 1:8 for
		// the nether — so the center has to travel the same way. Reading it off the dimension type
		// rather than hardcoding 8 means a custom scaled dimension lands in the right place too.
		double scale = level.dimensionType().coordinateScale();
		border.setCenter((overworldSpawn.getX() + 0.5) / scale, (overworldSpawn.getZ() + 0.5) / scale);
		border.setSize(diameter);
		logLevel(level, border);
	}

	private static void logLevel(ServerLevel level, WorldBorder border) {
		HardcoreRoguelite.LOGGER.info("  {}: {} wide, centered on {}, {}",
				level.dimension().identifier(), (long) border.getSize(),
				(long) border.getCenterX(), (long) border.getCenterZ());
	}
}
