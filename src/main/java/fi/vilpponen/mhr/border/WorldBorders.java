package fi.vilpponen.mhr.border;

import fi.vilpponen.mhr.HardcoreRoguelite;
import fi.vilpponen.mhr.UnlockEffects;
import fi.vilpponen.mhr.UnlockState;
import fi.vilpponen.mhr.core.Balance;
import fi.vilpponen.mhr.core.BalanceException;
import fi.vilpponen.mhr.core.BalanceManager;
import fi.vilpponen.mhr.mixin.MinecraftServerAccessor;
import fi.vilpponen.mhr.run.RunEvents;
import fi.vilpponen.mhr.run.RunLifecycle;
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

		// A new run is three new dimensions, each with a border of its own that has never been set.
		// Listening here rather than being called by the lifecycle is what keeps world management
		// from having to know that borders exist.
		RunEvents.RUN_STARTED.register((server, overworld, run) -> {
			apply(server);
			// Straight after the border, because what counts as the starting area is what the
			// border has just fenced in.
			// May move the spawn to trees nearby, and the border with it.
			StartingWood.ensure(server, overworld);
		});

		// A border tier bought in the shop is the size of the world from that moment on, the same
		// way the dev command's has always been.
		UnlockEffects.onChange(server -> selectOwnedTier());

		BalanceManager.addCheck(WorldBorders::checkTheTiersGoUp);
		// The balance in effect was loaded before that could be registered, so nothing has looked at
		// its tiers yet. A bundled or override file with the ladder the wrong way up stops the game
		// here, rather than waiting for somebody to reload.
		checkTheTiersGoUp(BalanceManager.get());
	}

	/**
	 * The tiers are a ladder, so their sizes have to go up it.
	 *
	 * <p>Two things read that ladder and they read it differently. A run takes the largest tier
	 * owned by walking {@link BorderTier} in order. The shop stops selling a tier once a bigger one
	 * is owned, by comparing the sizes in the balance file. Those are the same ordering only while
	 * the data says they are: make Medium bigger than Large and the two disagree about which is
	 * bigger, and a tier that cannot change the world goes back on sale.
	 *
	 * <p>The answer is to require the data to agree rather than teach two readers to cope with data
	 * that does not. Sizes may repeat — two tiers the same size satisfy each other, and neither
	 * changes anything the other did not — but they may never go down, and nothing bounded may
	 * follow something unbounded.
	 *
	 * @throws BalanceException naming the tier, so the message says which line to fix
	 */
	private static void checkTheTiersGoUp(Balance candidate) {
		double previous = 0;
		BorderTier below = null;
		boolean unbounded = false;

		for (BorderTier tier : BorderTier.values()) {
			Balance.BorderBalance balance = tier.balance(candidate);
			if (unbounded) {
				throw new BalanceException("Border tier '" + tier.id() + "' has a size, and '" + below.id()
						+ "' before it has none. The tiers have to get bigger going up, and nothing is"
						+ " bigger than unbounded.");
			}
			if (balance.isUnbounded()) {
				unbounded = true;
				below = tier;
				continue;
			}

			double size = balance.size().getAsDouble();
			if (below != null && size < previous) {
				throw new BalanceException("Border tier '" + tier.id() + "' is " + (long) size
						+ " blocks across and '" + below.id() + "' below it is " + (long) previous
						+ ". The tiers have to get bigger going up, never smaller.");
			}
			previous = size;
			below = tier;
		}
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
		if (owned == selectedTier) {
			// Nothing about the border has changed, so nothing is put on the world. This is asked
			// after *every* purchase, and applying a tier reads its size out of the balance in
			// effect — so re-applying the tier already in force would hand a run the size a reload
			// set while it was being played, which is the one thing {@code /mhr reload} promises it
			// will not do. A run keeps the border it started with until the tier itself changes.
			return;
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
			// The lobby is not part of a run and is not somewhere a border means anything: it is
			// one small platform, and a border around it would only be something to walk into.
			if (RunLifecycle.isRunLevel(level)) {
				apply(level, balance, diameter, spawn);
			}
		}

		// The spawn vanilla hands out is the world's own pulled inside the border, and it is only
		// worked out again once a tick. Everything that runs straight after a run start — the starter
		// chest, the players arriving — asks for it before that tick comes, so bring it up to date
		// now or they get the last border's answer.
		((MinecraftServerAccessor) server).mhr$updateEffectiveRespawnData();

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
