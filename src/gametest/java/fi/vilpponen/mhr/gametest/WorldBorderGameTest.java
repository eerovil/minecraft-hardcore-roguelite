package fi.vilpponen.mhr.gametest;

import fi.vilpponen.mhr.border.BorderTier;
import fi.vilpponen.mhr.core.BalanceManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.storage.LevelData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The world-border tiers, checked on a dedicated server with no client in sight.
 *
 * <p>These are the fast checks: where the border ends up in each dimension, and where its size
 * comes from. Every one of them picks the tier the way a player would — {@code /mhr border medium}
 * — and then reads the border vanilla itself would enforce, so nothing here is asking the mod to
 * repeat its own arithmetic back.
 *
 * <p>Everything here is one test method on purpose, the same way {@code OreFeatureTest} is. There
 * is one world border per dimension, one run spawn and one balance override file for the whole
 * server, and GameTest runs the tests of a batch side by side in the same world — so as separate
 * methods these scenarios would take each other's border away mid-assertion, or delete the override
 * another one was still reading. Inside one method they are strictly sequential, and each sets up
 * the spawn and the tier it needs, so the order they run in does not matter either.
 *
 * <p>What is deliberately not here is whether a portal actually comes out inside the border it was
 * promised. That takes real ticks and a real traveller, so it lives in
 * {@link fi.vilpponen.mhr.gametest.client.WorldBorderPortalClientTest}.
 *
 * <p>Every scenario moves the run's spawn somewhere awkward and puts it back afterwards, because a
 * border centered on the origin would pass whether the centering works or not. It also leaves the
 * border on {@code infinite}, which is the one state that cannot fence in whatever runs next. See
 * {@code docs/dev-environment.md} for how to run these.
 */
public final class WorldBorderGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("mhr-gametest");

	/**
	 * Where these scenarios move the run's spawn to. Far from the origin, not on a chunk corner and
	 * negative in one axis on purpose: those are the three ways the centering arithmetic can be
	 * wrong and still look right at 0, 0.
	 */
	private static final BlockPos RUN_SPAWN = new BlockPos(1234, 70, -5678);

	/** What the override in the reload scenario retunes {@code medium} to, in blocks. */
	private static final int RETUNED_MEDIUM_SIZE = 777;

	/** Where the end's own floor sits in the balance file — the same path the feature reads it by. */
	private static final String END_MINIMUM_SIZE = "endBorder.minimumSize";

	@GameTest
	public void theBorderTiersLandWhereTheBalanceFileSays(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		List<String> failures = new ArrayList<>();

		scenario(failures, "every-tier-takes-its-size-from-balance",
				() -> everyTierTakesItsSizeFromBalance(server));
		scenario(failures, "infinite-removes-the-practical-limit",
				() -> infiniteRemovesThePracticalLimit(server));
		scenario(failures, "the-border-centers-on-the-run-spawn",
				() -> theBorderCentersOnTheRunSpawn(server));
		scenario(failures, "the-nether-border-follows-the-coordinate-scale",
				() -> theNetherBorderFollowsTheCoordinateScale(server));
		scenario(failures, "the-end-sits-on-the-origin-and-holds-the-arrival-platform",
				() -> theEndSitsOnTheOriginAndHoldsTheArrivalPlatform(server));
		scenario(failures, "the-end-is-widened-only-when-the-tier-is-narrower-than-its-floor",
				() -> theEndIsWidenedOnlyWhenTheTierIsNarrowerThanItsFloor(server));
		scenario(failures, "a-reloaded-override-resizes-the-tier-on-its-next-application",
				() -> aReloadedOverrideResizesTheTierOnItsNextApplication(server));

		// Through the helper rather than a bare AssertionError: GameTest turns anything else into
		// "Unknown internal error" in the line it prints at the end, which is the line somebody
		// reads first, and the scenario names would only be findable further up the log.
		helper.assertTrue(failures.isEmpty(), failures.size() + " world-border scenario(s) failed: "
				+ String.join(" | ", failures));
		LOGGER.info("All world-border server scenarios passed.");
		helper.succeed();
	}

	// --- the tiers -----------------------------------------------------------------------------

	/**
	 * Each finite tier is exactly as wide as the balance file says. The number is read out of the
	 * balance in effect rather than written down here, so retuning a tier does not break its test —
	 * which is the whole point of the sizes being data.
	 */
	private void everyTierTakesItsSizeFromBalance(MinecraftServer server) {
		withRunSpawn(server, () -> {
			for (BorderTier tier : List.of(BorderTier.TINY, BorderTier.MEDIUM, BorderTier.LARGE)) {
				double expected = tier.balance(BalanceManager.get()).size().orElseThrow(
						() -> new AssertionError("Tier " + tier.id() + " should have a size in the balance file"));
				selectTier(server, tier);

				check(border(server, Level.OVERWORLD).getSize() == expected,
						"the " + tier.id() + " tier must be " + (long) expected + " blocks across, as"
								+ " worldBorder." + tier.id() + ".size says, and it is "
								+ (long) border(server, Level.OVERWORLD).getSize());
				check(border(server, Level.NETHER).getSize() == expected,
						"the nether must be as wide as the " + tier.id() + " tier, not wider or narrower,"
								+ " and it is " + (long) border(server, Level.NETHER).getSize());
			}
		});
	}

	/**
	 * The unbounded tier stops being a restriction at all. It still has a number, because vanilla's
	 * border always does — so the number to use is vanilla's own maximum, the one an ordinary world
	 * starts with, rather than something large the mod invented.
	 */
	private void infiniteRemovesThePracticalLimit(MinecraftServer server) {
		withRunSpawn(server, () -> {
			double large = BorderTier.LARGE.balance(BalanceManager.get()).size().orElseThrow();
			selectTier(server, BorderTier.INFINITE);
			WorldBorder border = border(server, Level.OVERWORLD);

			check(border.getSize() == WorldBorder.MAX_SIZE,
					"the infinite tier must use vanilla's own maximum border size, and it is "
							+ (long) border.getSize());
			check(border.getSize() > large,
					"the infinite tier must be wider than the largest finite one, and it is not");
			check(border.isWithinBounds(new BlockPos(2_000_000, 64, -2_000_000)),
					"a point two million blocks out must be inside the infinite tier's border");
		});
	}

	// --- where the border sits ------------------------------------------------------------------

	/**
	 * The overworld border is centered on the run's spawn, not on the origin. A run that begins a
	 * thousand blocks out otherwise starts with the border already behind the player.
	 */
	private void theBorderCentersOnTheRunSpawn(MinecraftServer server) {
		withRunSpawn(server, () -> {
			selectTier(server, BorderTier.TINY);
			WorldBorder border = border(server, Level.OVERWORLD);

			check(border.getCenterX() == RUN_SPAWN.getX() + 0.5,
					"the overworld border must be centered on the run spawn's x, " + RUN_SPAWN.getX()
							+ ", and it is centered on " + (long) border.getCenterX());
			check(border.getCenterZ() == RUN_SPAWN.getZ() + 0.5,
					"the overworld border must be centered on the run spawn's z, " + RUN_SPAWN.getZ()
							+ ", and it is centered on " + (long) border.getCenterZ());
			check(border.isWithinBounds(RUN_SPAWN),
					"the run spawn at " + RUN_SPAWN + " must be inside the border the run begins with");
			check(!border.isWithinBounds(BlockPos.ZERO),
					"a tiny border centered a thousand blocks out must not still cover the origin,"
							+ " which would mean it never moved");
		});
	}

	/**
	 * The nether border sits where the nether portal maths puts the spawn, which is the overworld
	 * center divided by the dimension's own coordinate scale.
	 *
	 * <p>The centers matching is the cheap half. The half that matters is the last check: every
	 * corner of the overworld border has to map inside the nether one, because that is what makes
	 * a portal built anywhere in the allowed area safe rather than only a portal built on spawn.
	 */
	private void theNetherBorderFollowsTheCoordinateScale(MinecraftServer server) {
		withRunSpawn(server, () -> {
			selectTier(server, BorderTier.TINY);
			ServerLevel nether = level(server, Level.NETHER);
			double scale = nether.dimensionType().coordinateScale();
			WorldBorder overworld = border(server, Level.OVERWORLD);
			WorldBorder border = nether.getWorldBorder();

			check(scale > 1.0,
					"the nether is meant to be a scaled dimension, and this one has a scale of " + scale);
			check(border.getCenterX() == (RUN_SPAWN.getX() + 0.5) / scale,
					"the nether border must be centered on the run spawn through the 1:" + (long) scale
							+ " portal mapping, which is " + (long) ((RUN_SPAWN.getX() + 0.5) / scale)
							+ ", and it is centered on " + (long) border.getCenterX());
			check(border.getCenterZ() == (RUN_SPAWN.getZ() + 0.5) / scale,
					"the nether border must be centered on the run spawn through the 1:" + (long) scale
							+ " portal mapping, which is " + (long) ((RUN_SPAWN.getZ() + 0.5) / scale)
							+ ", and it is centered on " + (long) border.getCenterZ());

			for (double x : List.of(overworld.getMinX(), overworld.getMaxX())) {
				for (double z : List.of(overworld.getMinZ(), overworld.getMaxZ())) {
					check(border.isWithinBounds(x / scale, z / scale),
							"a portal at the overworld border's corner " + (long) x + ", " + (long) z
									+ " would come out at " + (long) (x / scale) + ", " + (long) (z / scale)
									+ ", which is outside the nether border");
				}
			}
		});
	}

	/**
	 * The end ignores where the run began: every arrival lands on the obsidian platform east of the
	 * island, so the border goes on the origin and never shrinks below what it takes to hold both.
	 *
	 * <p>The width expected of each tier is the rule the feature follows — the wider of the tier and
	 * {@code endBorder.minimumSize} — rather than which side of the floor today's numbers happen to
	 * fall. Both of those are balance data, so a legitimate retune that lifts a tier above the floor
	 * must not turn this red. Which side of the floor the two branches are on is the next scenario's
	 * question, and that one builds itself a fixture instead of hoping.
	 */
	private void theEndSitsOnTheOriginAndHoldsTheArrivalPlatform(MinecraftServer server) {
		withRunSpawn(server, () -> {
			double minimum = BalanceManager.get().number(END_MINIMUM_SIZE);

			for (BorderTier tier : List.of(BorderTier.TINY, BorderTier.MEDIUM, BorderTier.LARGE)) {
				double tierSize = tier.balance(BalanceManager.get()).size().orElseThrow();
				selectTier(server, tier);
				WorldBorder border = border(server, Level.END);

				check(border.getCenterX() == 0.5 && border.getCenterZ() == 0.5,
						"the end border must sit on the origin however far out the run spawn is, and it"
								+ " is centered on " + (long) border.getCenterX() + ", "
								+ (long) border.getCenterZ());
				check(border.getSize() == Math.max(tierSize, minimum),
						"the end on the " + tier.id() + " tier must be the wider of that tier and "
								+ END_MINIMUM_SIZE + ", which is " + (long) Math.max(tierSize, minimum)
								+ " blocks, and it is " + (long) border.getSize());
				check(border.isWithinBounds(ServerLevel.END_SPAWN_POINT),
						"the obsidian arrival platform at " + ServerLevel.END_SPAWN_POINT
								+ " must be inside the end border, or arriving is a death sentence");
				check(border.isWithinBounds(BlockPos.ZERO),
						"the main island at the origin must be inside the end border");
			}
		});
	}

	/**
	 * The floor itself, on a fixture rather than on whatever the bundled numbers are today.
	 *
	 * <p>A tier is retuned to a quarter of {@code endBorder.minimumSize} and another to four times
	 * it, so one tier is certainly below the floor and one certainly above it whatever the catalogue
	 * says. The overworld is checked alongside: the widening is the end's alone, and a floor that
	 * leaked into the other dimensions would hand the player a world the tier never sold them.
	 */
	private void theEndIsWidenedOnlyWhenTheTierIsNarrowerThanItsFloor(MinecraftServer server) {
		Path override = BalanceManager.overrideFile();
		double minimum = BalanceManager.get().number(END_MINIMUM_SIZE);
		long narrow = Math.max(1, Math.round(minimum / 4));
		long wide = Math.round(minimum * 4);

		withRunSpawn(server, () -> {
			try {
				write(override, "{\"worldBorder\": {\"" + BorderTier.TINY.id() + "\": {\"size\": " + narrow
						+ "}, \"" + BorderTier.LARGE.id() + "\": {\"size\": " + wide + "}}}");
				runCommand(server, "mhr reload");

				selectTier(server, BorderTier.TINY);
				check(border(server, Level.END).getSize() == minimum,
						"a tier narrower than " + END_MINIMUM_SIZE + " must be widened to it in the end,"
								+ " to " + (long) minimum + " blocks, and the end is "
								+ (long) border(server, Level.END).getSize());
				check(border(server, Level.OVERWORLD).getSize() == narrow,
						"the end's floor must not widen the overworld, which the tier sold as " + narrow
								+ " blocks across, and it is "
								+ (long) border(server, Level.OVERWORLD).getSize());

				selectTier(server, BorderTier.LARGE);
				check(border(server, Level.END).getSize() == wide,
						"a tier wider than " + END_MINIMUM_SIZE + " must keep its own size in the end,"
								+ " which is " + wide + " blocks, and the end is "
								+ (long) border(server, Level.END).getSize());
			} finally {
				delete(override);
				runCommand(server, "mhr reload");
			}
		});
	}

	// --- balance ---------------------------------------------------------------------------------

	/**
	 * Retuning a tier is editing the balance override and reloading it, not a rebuild — but it is
	 * the <em>next</em> border application that picks the new number up, not the running world.
	 *
	 * <p>That is deliberate, and {@code mhr reload} says so in as many words: a run already in
	 * progress keeps the border it started with, because resizing a world under the player would
	 * put them outside a wall they never crossed. So this scenario pins both halves — the reload
	 * changing nothing on the spot, and the same tier applied again coming out at the new size.
	 */
	private void aReloadedOverrideResizesTheTierOnItsNextApplication(MinecraftServer server) {
		Path override = BalanceManager.overrideFile();
		double before = BorderTier.MEDIUM.balance(BalanceManager.get()).size().orElseThrow();
		check(before != RETUNED_MEDIUM_SIZE,
				"the override in this scenario has to change the size, and " + RETUNED_MEDIUM_SIZE
						+ " is already what medium is");

		withRunSpawn(server, () -> {
			try {
				selectTier(server, BorderTier.MEDIUM);
				write(override, "{\"worldBorder\": {\"medium\": {\"size\": " + RETUNED_MEDIUM_SIZE + "}}}");
				runCommand(server, "mhr reload");

				// Nothing moves under the player. The balance in effect has changed and the world
				// has not, which is what the reload command promises whoever ran it.
				check(border(server, Level.OVERWORLD).getSize() == before,
						"a reload must leave the border a run is already inside at the size it had, "
								+ (long) before + " blocks, and it is now "
								+ (long) border(server, Level.OVERWORLD).getSize());
				check(border(server, Level.NETHER).getSize() == before,
						"a reload must leave the nether border alone too, not only the overworld, and it"
								+ " is now " + (long) border(server, Level.NETHER).getSize());
				check(BorderTier.MEDIUM.balance(BalanceManager.get()).size().orElseThrow()
								== RETUNED_MEDIUM_SIZE,
						"the reload itself must have taken, or the check above proves nothing");

				// The next time the tier is applied — the next run, or a tier change — it is on the
				// new numbers, with no rebuild anywhere.
				selectTier(server, BorderTier.MEDIUM);
				check(border(server, Level.OVERWORLD).getSize() == RETUNED_MEDIUM_SIZE,
						"applying the medium tier after the reload must use the retuned size, "
								+ RETUNED_MEDIUM_SIZE + " blocks, and it came out at "
								+ (long) border(server, Level.OVERWORLD).getSize());
				check(border(server, Level.NETHER).getSize() == RETUNED_MEDIUM_SIZE,
						"the retuned size must reach every dimension, not only the overworld, and the"
								+ " nether is " + (long) border(server, Level.NETHER).getSize());

				// And the override going away puts the bundled number back, so nothing that runs
				// after this scenario inherits a world a test retuned.
				delete(override);
				runCommand(server, "mhr reload");
				selectTier(server, BorderTier.MEDIUM);
				check(border(server, Level.OVERWORLD).getSize() == before,
						"removing the override must put the bundled medium size back, " + (long) before
								+ " blocks, and it is " + (long) border(server, Level.OVERWORLD).getSize());
			} finally {
				delete(override);
				runCommand(server, "mhr reload");
			}
		});
	}

	// --- plumbing ----------------------------------------------------------------------------------

	/**
	 * Runs a scenario with the run's spawn somewhere awkward, and puts the world back afterwards.
	 *
	 * <p>The spawn is moved with the vanilla command a run would use, and restored to the exact
	 * value it had — including the angle, which the command cannot say. The border is left on
	 * {@code infinite} on the way out: this world is shared with every other gametest, and a
	 * wide-open border is the one state that cannot make somebody else's scenario fail.
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

	/**
	 * Runs one scenario. A failure is recorded rather than ending the run, so one command shows
	 * every criterion that is red instead of only the first.
	 */
	private static void scenario(List<String> failures, String name, Runnable body) {
		LOGGER.info("=== scenario {} ===", name);
		try {
			body.run();
			LOGGER.info("=== scenario {}: PASS ===", name);
		} catch (Throwable failure) {
			failures.add(name + ": " + failure.getMessage());
			LOGGER.error("=== scenario {}: FAIL === {}", name, failure.getMessage(), failure);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
