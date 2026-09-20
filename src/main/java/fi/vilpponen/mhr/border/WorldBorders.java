package fi.vilpponen.mhr.border;

import fi.vilpponen.mhr.HardcoreRoguelite;
import fi.vilpponen.mhr.UnlockEffects;
import fi.vilpponen.mhr.UnlockState;
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
 * <p>The tier is not remembered in memory between sessions, and does not need to be: it is decided
 * by what the player has permanently bought. {@link #selectOwnedTier()} reads that, and is called
 * when a server starts and again whenever the owned unlocks change. {@link #select(BorderTier)}
 * stays for the dev command, which is allowed to ignore the shop.
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

	/**
	 * True once {@code /mhr border} has picked a tier by hand for this world.
	 *
	 * <p>The dev command is allowed to ignore what has been bought — that is what it is for, and
	 * tests lean on it heavily to generate ordinary terrain a long way from spawn. Without this
	 * flag, buying anything at all afterwards would quietly put the bought-for border back, because
	 * every purchase asks the border to look at the unlocks again. Cleared when a server starts, so
	 * a hand-picked tier never outlives the world it was picked for.
	 */
	private static volatile boolean pickedByHand;

	private WorldBorders() {
	}

	/** Called once from the mod initializer. */
	public static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			runningServer = server;
			pickedByHand = false;
			selectOwnedTier();
			apply(server);
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> runningServer = null);

		// A border tier bought in the shop is the size of the world from that moment on, the same
		// way the dev command's has always been.
		UnlockEffects.onChange(server -> selectOwnedTier());
	}

	/**
	 * Take the tier from what the player has permanently bought: the largest one owned, or
	 * {@link BorderTier#DEFAULT} while none is.
	 *
	 * <p>This is what wires the border to progression. The tiers are steps rather than choices —
	 * owning Large means the world is Large, whether or not Medium was ever bought — so the answer
	 * is the furthest one along the enum, not the most recent purchase.
	 */
	public static void selectOwnedTier() {
		if (pickedByHand) {
			return;
		}
		UnlockState state = UnlockState.get();
		BorderTier owned = BorderTier.DEFAULT;
		for (BorderTier tier : BorderTier.values()) {
			if (state.isOwned(tier.unlockId())) {
				owned = tier;
			}
		}
		set(owned);
	}

	public static BorderTier selectedTier() {
		return selectedTier;
	}

	/**
	 * Pick a tier by hand, ignoring what has been bought until this world is over.
	 *
	 * <p>{@code /mhr border} and nothing else. It takes effect on the running world straight away,
	 * and from then on a purchase no longer decides the size of this world — see
	 * {@link #pickedByHand}.
	 */
	public static void select(BorderTier tier) {
		pickedByHand = true;
		set(tier);
	}

	/** Put a tier in place, whoever chose it. */
	private static void set(BorderTier tier) {
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
