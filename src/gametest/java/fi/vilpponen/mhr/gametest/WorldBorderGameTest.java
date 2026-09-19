package fi.vilpponen.mhr.gametest;

import fi.vilpponen.mhr.border.BorderTier;
import fi.vilpponen.mhr.core.BalanceManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.storage.LevelData;

/**
 * The world-border tiers, checked on a dedicated server with no client in sight.
 *
 * <p>These are the fast checks: where the border ends up in each dimension, and where its size
 * comes from. Every one of them picks the tier the way a player would — {@code /mhr border medium}
 * — and then reads the border vanilla itself would enforce, so nothing here is asking the mod to
 * repeat its own arithmetic back.
 *
 * <p>What is deliberately not here is whether a portal actually comes out inside the border it was
 * promised. That takes real ticks and a real traveller, so it lives in
 * {@link fi.vilpponen.mhr.gametest.client.WorldBorderPortalClientTest}.
 *
 * <p>Every scenario moves the run's spawn somewhere awkward and puts it back afterwards, because a
 * border centered on the origin would pass whether the centering works or not. It also leaves the
 * border on {@code infinite}, which is the one state that cannot fence in whatever scenario runs
 * next. See {@code docs/dev-environment.md} for how to run these.
 */
public final class WorldBorderGameTest {
	/**
	 * Where these scenarios move the run's spawn to. Far from the origin, not on a chunk corner and
	 * negative in one axis on purpose: those are the three ways the centering arithmetic can be
	 * wrong and still look right at 0, 0.
	 */
	private static final BlockPos RUN_SPAWN = new BlockPos(1234, 70, -5678);

	/** What the override in the reload scenario retunes {@code medium} to, in blocks. */
	private static final int RETUNED_MEDIUM_SIZE = 777;

	// --- the tiers -----------------------------------------------------------------------------

	/**
	 * Each finite tier is exactly as wide as the balance file says. The number is read out of the
	 * balance in effect rather than written down here, so retuning a tier does not break its test —
	 * which is the whole point of the sizes being data.
	 */
	@GameTest
	public void everyTierTakesItsSizeFromBalance(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		withRunSpawn(server, () -> {
			for (BorderTier tier : List.of(BorderTier.TINY, BorderTier.MEDIUM, BorderTier.LARGE)) {
				double expected = tier.balance(BalanceManager.get()).size().orElseThrow(
						() -> new AssertionError("Tier " + tier.id() + " should have a size in the balance file"));
				selectTier(server, tier);

				helper.assertValueEqual(border(server, Level.OVERWORLD).getSize(), expected,
						"the " + tier.id() + " tier must be as wide as worldBorder." + tier.id()
								+ ".size says");
				helper.assertValueEqual(border(server, Level.NETHER).getSize(), expected,
						"the nether must be as wide as the " + tier.id() + " tier, not wider or narrower");
			}
		});
		helper.succeed();
	}

	/**
	 * The unbounded tier stops being a restriction at all. It still has a number, because vanilla's
	 * border always does — so the number to use is vanilla's own maximum, the one an ordinary world
	 * starts with, rather than something large the mod invented.
	 */
	@GameTest
	public void infiniteRemovesThePracticalLimit(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		withRunSpawn(server, () -> {
			double large = BorderTier.LARGE.balance(BalanceManager.get()).size().orElseThrow();
			selectTier(server, BorderTier.INFINITE);

			helper.assertValueEqual(border(server, Level.OVERWORLD).getSize(), WorldBorder.MAX_SIZE,
					"the infinite tier must use vanilla's own maximum border size");
			helper.assertTrue(border(server, Level.OVERWORLD).getSize() > large,
					"the infinite tier must be wider than the largest finite one, and it is not");
			helper.assertTrue(border(server, Level.OVERWORLD).isWithinBounds(
					new BlockPos(2_000_000, 64, -2_000_000)),
					"a point two million blocks out must be inside the infinite tier's border");
		});
		helper.succeed();
	}

	// --- where the border sits ------------------------------------------------------------------

	/**
	 * The overworld border is centered on the run's spawn, not on the origin. A run that begins a
	 * thousand blocks out otherwise starts with the border already behind the player.
	 */
	@GameTest
	public void theBorderCentersOnTheRunSpawn(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		withRunSpawn(server, () -> {
			selectTier(server, BorderTier.TINY);
			WorldBorder border = border(server, Level.OVERWORLD);

			helper.assertValueEqual(border.getCenterX(), RUN_SPAWN.getX() + 0.5,
					"the overworld border must be centered on the run spawn's x");
			helper.assertValueEqual(border.getCenterZ(), RUN_SPAWN.getZ() + 0.5,
					"the overworld border must be centered on the run spawn's z");
			helper.assertTrue(border.isWithinBounds(RUN_SPAWN),
					"the run spawn at " + RUN_SPAWN + " must be inside the border the run begins with");
			helper.assertFalse(border.isWithinBounds(BlockPos.ZERO),
					"a tiny border centered a thousand blocks out must not still cover the origin,"
							+ " which would mean it never moved");
		});
		helper.succeed();
	}

	/**
	 * The nether border sits where the nether portal maths puts the spawn, which is the overworld
	 * center divided by the dimension's own coordinate scale.
	 *
	 * <p>The centers matching is the cheap half. The half that matters is the last check: every
	 * corner of the overworld border has to map inside the nether one, because that is what makes
	 * a portal built anywhere in the allowed area safe rather than only a portal built on spawn.
	 */
	@GameTest
	public void theNetherBorderFollowsTheCoordinateScale(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		withRunSpawn(server, () -> {
			selectTier(server, BorderTier.TINY);
			ServerLevel nether = level(server, Level.NETHER);
			double scale = nether.dimensionType().coordinateScale();
			WorldBorder overworld = border(server, Level.OVERWORLD);
			WorldBorder border = nether.getWorldBorder();

			helper.assertTrue(scale > 1.0,
					"the nether is meant to be a scaled dimension, and this one has a scale of " + scale);
			helper.assertValueEqual(border.getCenterX(), (RUN_SPAWN.getX() + 0.5) / scale,
					"the nether border must be centered on the run spawn through the 1:" + (long) scale
							+ " portal mapping");
			helper.assertValueEqual(border.getCenterZ(), (RUN_SPAWN.getZ() + 0.5) / scale,
					"the nether border must be centered on the run spawn through the 1:" + (long) scale
							+ " portal mapping");

			for (double x : List.of(overworld.getMinX(), overworld.getMaxX())) {
				for (double z : List.of(overworld.getMinZ(), overworld.getMaxZ())) {
					helper.assertTrue(border.isWithinBounds(x / scale, z / scale),
							"a portal at the overworld border's corner " + (long) x + ", " + (long) z
									+ " would come out at " + (long) (x / scale) + ", " + (long) (z / scale)
									+ ", which is outside the nether border");
				}
			}
		});
		helper.succeed();
	}

	/**
	 * The end ignores where the run began: every arrival lands on the obsidian platform east of the
	 * island, so the border goes on the origin and never shrinks below what it takes to hold both.
	 */
	@GameTest
	public void theEndSitsOnTheOriginAndHoldsTheArrivalPlatform(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		withRunSpawn(server, () -> {
			double minimum = BalanceManager.get().number("endBorder.minimumSize");
			double large = BorderTier.LARGE.balance(BalanceManager.get()).size().orElseThrow();

			selectTier(server, BorderTier.TINY);
			WorldBorder border = border(server, Level.END);

			helper.assertValueEqual(border.getCenterX(), 0.5,
					"the end border must sit on the origin however far out the run spawn is");
			helper.assertValueEqual(border.getCenterZ(), 0.5,
					"the end border must sit on the origin however far out the run spawn is");
			helper.assertValueEqual(border.getSize(), minimum,
					"a tier narrower than endBorder.minimumSize must be widened to it in the end");
			helper.assertTrue(border.isWithinBounds(ServerLevel.END_SPAWN_POINT),
					"the obsidian arrival platform at " + ServerLevel.END_SPAWN_POINT
							+ " must be inside the end border, or arriving is a death sentence");
			helper.assertTrue(border.isWithinBounds(BlockPos.ZERO),
					"the main island at the origin must be inside the end border");

			// A tier wider than the floor is not clamped down to it.
			selectTier(server, BorderTier.LARGE);
			helper.assertValueEqual(border(server, Level.END).getSize(), large,
					"a tier wider than endBorder.minimumSize must keep its own size in the end");
		});
		helper.succeed();
	}

	// --- balance ---------------------------------------------------------------------------------

	/**
	 * Retuning a tier is editing the balance override and reloading it, not a rebuild. The border
	 * is read out of the balance each time it is applied, so the next tier change is on the new
	 * numbers.
	 */
	@GameTest
	public void aBalanceOverrideAndAReloadResizeATier(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		Path override = BalanceManager.overrideFile();
		double before = BorderTier.MEDIUM.balance(BalanceManager.get()).size().orElseThrow();
		helper.assertFalse(before == RETUNED_MEDIUM_SIZE,
				"the override in this scenario has to change the size, and " + RETUNED_MEDIUM_SIZE
						+ " is already what medium is");

		withRunSpawn(server, () -> {
			try {
				write(override, "{\"worldBorder\": {\"medium\": {\"size\": " + RETUNED_MEDIUM_SIZE + "}}}");
				runCommand(server, "mhr reload");

				selectTier(server, BorderTier.MEDIUM);
				helper.assertValueEqual(border(server, Level.OVERWORLD).getSize(),
						(double) RETUNED_MEDIUM_SIZE,
						"a reloaded balance override must resize the medium tier without a rebuild");
				helper.assertValueEqual(border(server, Level.NETHER).getSize(),
						(double) RETUNED_MEDIUM_SIZE,
						"the retuned size must reach every dimension, not only the overworld");

				// And the override going away puts the bundled number back, so nothing that runs
				// after this scenario inherits a world a test retuned.
				delete(override);
				runCommand(server, "mhr reload");
				selectTier(server, BorderTier.MEDIUM);
				helper.assertValueEqual(border(server, Level.OVERWORLD).getSize(), before,
						"removing the override must put the bundled medium size back");
			} finally {
				delete(override);
				runCommand(server, "mhr reload");
			}
		});
		helper.succeed();
	}

	// --- plumbing ----------------------------------------------------------------------------------

	/**
	 * Runs a scenario with the run's spawn somewhere awkward, and puts the world back afterwards.
	 *
	 * <p>The spawn is moved with the vanilla command a run would use, and restored to the exact
	 * value it had — including the angle, which the command cannot say. The border is left on
	 * {@code infinite} on the way out: these scenarios share a world with every other gametest, and
	 * a wide-open border is the one state that cannot make somebody else's scenario fail.
	 */
	private static void withRunSpawn(MinecraftServer server, Runnable body) {
		LevelData.RespawnData original = server.getWorldData().overworldData().getRespawnData();
		runCommand(server, "setworldspawn " + RUN_SPAWN.getX() + " " + RUN_SPAWN.getY() + " "
				+ RUN_SPAWN.getZ());
		try {
			body.run();
		} finally {
			server.getWorldData().overworldData().setSpawn(original);
			runCommand(server, "mhr border " + BorderTier.INFINITE.id());
		}
	}

	/** Picks a tier the way a player does, through the command, rather than by calling the mod. */
	private static void selectTier(MinecraftServer server, BorderTier tier) {
		runCommand(server, "mhr border " + tier.id());
	}

	private static void runCommand(MinecraftServer server, String command) {
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
	}

	private static WorldBorder border(MinecraftServer server, ResourceKey<Level> dimension) {
		return level(server, dimension).getWorldBorder();
	}

	private static ServerLevel level(MinecraftServer server, ResourceKey<Level> dimension) {
		ServerLevel level = server.getLevel(dimension);
		if (level == null) {
			throw new AssertionError("This server has no " + dimension.identifier()
					+ ", so the border there cannot be checked");
		}
		return level;
	}

	private static void write(Path file, String contents) {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, contents, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not write the balance override " + file, e);
		}
	}

	private static void delete(Path file) {
		try {
			Files.deleteIfExists(file);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not remove the balance override " + file, e);
		}
	}
}
