package fi.vilpponen.mhr.gametest;

import fi.vilpponen.mhr.border.BorderTier;
import fi.vilpponen.mhr.core.BalanceManager;
import fi.vilpponen.mhr.gametest.mixin.GameTestHelperAccessor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec3;
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
 * <p>Whether a portal actually comes out inside the border it was promised is here too, at the end:
 * that one needs a real traveller and real ticks rather than arithmetic, so a pig walks into a real
 * obsidian portal and the sequence waits for it to arrive. The only thing left to a client is what
 * a connected player is shown, in
 * {@link fi.vilpponen.mhr.gametest.client.WorldBorderClientTest}.
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

	/**
	 * Where in the test area the nether portal is built, in the area's own coordinates: a four-by-five
	 * obsidian frame standing in the z = 1 plane, with the doorway at x 1..2, y 2..4.
	 */
	private static final BlockPos PORTAL_DOORWAY = new BlockPos(1, 2, 1);

	/** Where the end portal goes, well clear of the nether one. */
	private static final BlockPos END_PORTAL = new BlockPos(6, 1, 6);

	/**
	 * How far west of the portal the run's spawn is put, in blocks.
	 *
	 * <p>Not on the portal, on purpose: the promise is that a portal built <em>anywhere</em> inside
	 * the allowed area is safe, and a portal standing exactly on the center would be safe even if
	 * only the center had ever been thought about. It has to stay inside the tiny border, which is
	 * 128 blocks across.
	 */
	private static final int SPAWN_OFFSET = 50;

	/**
	 * How far from the portal's own coordinates, scaled, the arrival may be.
	 *
	 * <p>Vanilla builds the far-side portal near the scaled position but not exactly on it, so some
	 * slack is needed. It has to stay small, because the failure this catches is a border in the
	 * wrong place: vanilla clamps a destination into the border it is given, so a nether border
	 * left on the raw overworld coordinates does not strand anybody — it quietly lands the traveller
	 * hundreds of blocks from where the portal maths says, which an "is it inside the border" check
	 * cannot see on its own.
	 */
	private static final double ARRIVAL_SLACK = 32.0;

	private static final String NETHER_JOURNEY =
			"a-real-nether-portal-lands-the-traveller-inside-the-nether-border";
	private static final String END_JOURNEY =
			"a-real-end-transition-lands-the-traveller-inside-the-end-border";

	/** The run spawn as the world had it, put back when the journeys are over. */
	private LevelData.RespawnData spawnBeforeTheJourneys;

	/** The pigs, by uuid: a traveller is a new object in the dimension it arrives in. */
	private UUID netherTraveller;
	private UUID endTraveller;

	/**
	 * The whole suite. The arithmetic scenarios run first and take no time at all; the two real
	 * journeys follow on a sequence, because a portal transition happens over ticks and cannot be
	 * asked for synchronously.
	 */
	@GameTest(maxTicks = 800)
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
		scenario(failures, "a-balance-that-turns-the-ladder-upside-down-is-refused",
				() -> aBalanceThatTurnsTheLadderUpsideDownIsRefused(server));

		// The journeys move state the whole server shares — the run's spawn and the border in every
		// dimension — and they hold it across ticks while a traveller is on its way. A traveller
		// that never arrives is a failure this test has to be able to report, and a test that fails
		// or times out never reaches the end of its own sequence, so putting the world back cannot
		// be a step there. It goes on the framework's own end-of-test hook instead, registered
		// before anything is moved, and that hook runs whichever way the test ends.
		putTheWorldBackWhateverHappens(helper);

		helper.startSequence()
				.thenExecute(() -> aTravellerStandsInARealNetherPortal(helper))
				.thenWaitUntil(() -> waitForArrival(helper, netherTraveller, Level.NETHER, NETHER_JOURNEY))
				.thenExecute(() -> verdict(failures, NETHER_JOURNEY,
						() -> theNetherArrivalIsInsideTheNetherBorder(helper)))
				.thenExecute(() -> aTravellerStandsInARealEndPortal(helper))
				.thenWaitUntil(() -> waitForArrival(helper, endTraveller, Level.END, END_JOURNEY))
				.thenExecute(() -> verdict(failures, END_JOURNEY,
						() -> theEndArrivalIsInsideTheEndBorder(helper)))
				.thenExecute(() -> report(helper, failures))
				.thenSucceed();
	}

	/**
	 * Hangs the teardown off the end of the test itself, rather than off the end of its sequence.
	 *
	 * <p>{@code testPassed} and {@code testFailed} are the two ways a GameTest can end, and a
	 * timeout is a failure, so between them they cover a traveller that never arrives just as well
	 * as one that does. The listener wants the {@link GameTestInfo} a {@link GameTestHelper} keeps
	 * private, which is the whole reason {@link GameTestHelperAccessor} exists.
	 */
	private void putTheWorldBackWhateverHappens(GameTestHelper helper) {
		((GameTestHelperAccessor) helper).mhr$testInfo().addListener(new GameTestListener() {
			@Override
			public void testStructureLoaded(GameTestInfo info) {
			}

			@Override
			public void testPassed(GameTestInfo info, GameTestRunner runner) {
				putTheWorldBack(helper);
			}

			@Override
			public void testFailed(GameTestInfo info, GameTestRunner runner) {
				putTheWorldBack(helper);
			}

			@Override
			public void testAddedForRerun(GameTestInfo before, GameTestInfo after, GameTestRunner runner) {
			}
		});
	}

	private static void report(GameTestHelper helper, List<String> failures) {
		// Through the helper rather than a bare AssertionError: GameTest turns anything else into
		// "Unknown internal error" in the line it prints at the end, which is the line somebody
		// reads first, and the scenario names would only be findable further up the log.
		helper.assertTrue(failures.isEmpty(), failures.size() + " world-border scenario(s) failed: "
				+ String.join(" | ", failures));
		LOGGER.info("All world-border server scenarios passed.");
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

	// --- the journeys ----------------------------------------------------------------------------

	/**
	 * Builds a real nether portal inside the border and stands a pig in it.
	 *
	 * <p>A pig rather than a player because this server has no players, and a real portal rather
	 * than a teleport because the thing being checked is exactly what vanilla decides when somebody
	 * walks through one. The frame is the four-by-five every player builds, and the doorway is
	 * walled off on both sides so the traveller stays in it rather than wandering off while the
	 * portal counts down.
	 */
	private void aTravellerStandsInARealNetherPortal(GameTestHelper helper) {
		beginScenario(NETHER_JOURNEY);
		MinecraftServer server = helper.getLevel().getServer();
		spawnBeforeTheJourneys = server.getWorldData().overworldData().getRespawnData();
		buildTheNetherPortal(helper);

		BlockPos doorway = helper.absolutePos(PORTAL_DOORWAY);
		runCommand(server, "setworldspawn " + (doorway.getX() - SPAWN_OFFSET) + " " + doorway.getY()
				+ " " + doorway.getZ());
		selectTier(server, BorderTier.TINY);
		check(border(server, Level.OVERWORLD).isWithinBounds(doorway),
				"this scenario is meant to send a traveller through a portal that is inside the"
						+ " overworld border, and the portal at " + doorway + " is not");

		netherTraveller = helper.spawn(EntityTypes.PIG, PORTAL_DOORWAY).getUUID();
		LOGGER.info("A pig is standing in a portal at {}, with the run spawn {} blocks west",
				doorway, SPAWN_OFFSET);
	}

	/**
	 * Where the traveller came out, and the two things that have to be true about it.
	 *
	 * <p>Both are needed. Vanilla clamps a portal destination into whatever border it is given, so
	 * a nether border in the wrong place still answers "inside" — while quietly putting the
	 * traveller a long way from the portal they walked into.
	 */
	private void theNetherArrivalIsInsideTheNetherBorder(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		Entity traveller = traveller(helper, netherTraveller, NETHER_JOURNEY);
		Vec3 arrival = traveller.position();
		double scale = level(server, Level.NETHER).dimensionType().coordinateScale();
		BlockPos doorway = helper.absolutePos(PORTAL_DOORWAY);
		double expectedX = (doorway.getX() + 0.5) / scale;
		double expectedZ = (doorway.getZ() + 0.5) / scale;
		double drift = Math.hypot(arrival.x() - expectedX, arrival.z() - expectedZ);
		LOGGER.info("Nether arrival at {}, {} — the 1:{} mapping of the portal is {}, {} ({} blocks away)",
				(long) arrival.x(), (long) arrival.z(), (long) scale,
				(long) expectedX, (long) expectedZ, (long) drift);

		check(border(server, Level.NETHER).isWithinBounds(arrival),
				"a traveller who went through a portal inside the overworld border must arrive inside"
						+ " the nether border, and " + (long) arrival.x() + ", " + (long) arrival.z()
						+ " is outside it");
		check(drift <= ARRIVAL_SLACK,
				"the arrival must be where the 1:" + (long) scale + " portal mapping puts the portal,"
						+ " but it came out " + (long) drift + " blocks away from " + (long) expectedX
						+ ", " + (long) expectedZ + " — which is what a nether border in the wrong"
						+ " place looks like, because vanilla drags the destination inside it");
	}

	/**
	 * The same again for the end, which takes everybody to the same place whatever portal they left
	 * from — the obsidian platform a hundred blocks east of the island, further out than the tiny
	 * tier is wide.
	 */
	private void aTravellerStandsInARealEndPortal(GameTestHelper helper) {
		beginScenario(END_JOURNEY);
		// Into the portal block rather than onto it: an end portal is a shallow slab with a
		// collision shape, so anything dropped from above stands on its lid forever.
		helper.setBlock(END_PORTAL.below(), Blocks.OBSIDIAN);
		helper.setBlock(END_PORTAL, Blocks.END_PORTAL);
		endTraveller = helper.spawn(EntityTypes.PIG, END_PORTAL).getUUID();
	}

	private void theEndArrivalIsInsideTheEndBorder(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		Entity traveller = traveller(helper, endTraveller, END_JOURNEY);
		Vec3 arrival = traveller.position();
		LOGGER.info("End arrival at {}, {}", (long) arrival.x(), (long) arrival.z());

		check(border(server, Level.END).isWithinBounds(arrival),
				"a real end transition must land inside the end border, and " + (long) arrival.x()
						+ ", " + (long) arrival.z() + " is outside it");
		check(border(server, Level.END).isWithinBounds(ServerLevel.END_SPAWN_POINT),
				"the obsidian arrival platform at " + ServerLevel.END_SPAWN_POINT + " must be inside"
						+ " the end border whatever tier the run is on");
	}

	/** The frame every player builds: four wide, five tall, a two-by-three doorway inside it. */
	private static void buildTheNetherPortal(GameTestHelper helper) {
		for (int x = 0; x <= 3; x++) {
			helper.setBlock(new BlockPos(x, 1, 1), Blocks.OBSIDIAN);
			helper.setBlock(new BlockPos(x, 5, 1), Blocks.OBSIDIAN);
		}
		for (int y = 2; y <= 4; y++) {
			helper.setBlock(new BlockPos(0, y, 1), Blocks.OBSIDIAN);
			helper.setBlock(new BlockPos(3, y, 1), Blocks.OBSIDIAN);
		}
		for (int x = 1; x <= 2; x++) {
			for (int y = 2; y <= 4; y++) {
				// The walls either side of the doorway are this test's own, not a player's: they
				// keep the traveller in the portal for the three hundred ticks it takes.
				helper.setBlock(new BlockPos(x, y, 0), Blocks.OBSIDIAN);
				helper.setBlock(new BlockPos(x, y, 2), Blocks.OBSIDIAN);
				helper.setBlock(new BlockPos(x, y, 1), Blocks.NETHER_PORTAL.defaultBlockState()
						.setValue(NetherPortalBlock.AXIS, Direction.Axis.X));
			}
		}
	}

	/**
	 * Keeps the sequence waiting until the traveller is in the dimension it set off for.
	 *
	 * <p>Thrown through the helper rather than as a plain {@code AssertionError}, because the
	 * sequence only treats a {@code GameTestAssertException} as "not yet" — anything else ends the
	 * test on the first tick. The message doubles as the timeout's, so it says where the traveller
	 * is stuck.
	 */
	private void waitForArrival(GameTestHelper helper, UUID uuid, ResourceKey<Level> dimension,
			String scenario) {
		Entity traveller = helper.getLevel().getEntityInAnyDimension(uuid);
		if (traveller == null) {
			throw helper.assertionException(Component.literal(scenario
					+ ": the traveller is gone, so it can never arrive in " + dimension.identifier()));
		}
		if (!traveller.level().dimension().equals(dimension)) {
			throw helper.assertionException(Component.literal(scenario + ": the traveller is still in "
					+ traveller.level().dimension().identifier() + " at "
					+ (long) traveller.position().x() + ", " + (long) traveller.position().z()
					+ " rather than " + dimension.identifier()));
		}
	}

	private Entity traveller(GameTestHelper helper, UUID uuid, String scenario) {
		Entity traveller = helper.getLevel().getEntityInAnyDimension(uuid);
		if (traveller == null) {
			throw new AssertionError(scenario + ": the traveller went away before it could be asked"
					+ " where it ended up");
		}
		return traveller;
	}

	/**
	 * Puts back everything the journeys moved: the run's spawn, a border wide enough not to fence
	 * anybody in, and the two pigs, which are in other dimensions by now.
	 *
	 * <p>Safe to run at any point, including before the journeys have started and more than once: a
	 * teardown that has to run exactly once to be correct is a teardown waiting to be skipped.
	 */
	private void putTheWorldBack(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		if (spawnBeforeTheJourneys != null) {
			server.getWorldData().overworldData().setSpawn(spawnBeforeTheJourneys);
			spawnBeforeTheJourneys = null;
		}
		runCommand(server, "mhr border " + BorderTier.INFINITE.id());
		for (UUID uuid : new UUID[] {netherTraveller, endTraveller}) {
			Entity traveller = uuid == null ? null : helper.getLevel().getEntityInAnyDimension(uuid);
			if (traveller != null) {
				traveller.discard();
			}
		}
		netherTraveller = null;
		endTraveller = null;
	}

	/**
	 * The tiers are a ladder and two different things read it: a run takes the largest tier owned by
	 * walking the constants, and the shop stops selling a tier once a bigger one is owned by
	 * comparing sizes. Those agree only while the data does.
	 *
	 * <p>So a balance that makes a lower tier bigger than the one above it is refused outright,
	 * which is what stops the disagreement from ever reaching the shop — there is no state in which
	 * a tier could be charged for while leaving the world on a tier the ladder calls bigger.
	 */
	private void aBalanceThatTurnsTheLadderUpsideDownIsRefused(MinecraftServer server) {
		Path override = BalanceManager.overrideFile();
		double medium = BorderTier.MEDIUM.balance(BalanceManager.get()).size().orElseThrow();
		double large = BorderTier.LARGE.balance(BalanceManager.get()).size().orElseThrow();
		check(medium < large, "setup: the shipped ladder should go up, and medium is " + (long) medium
				+ " against large's " + (long) large);

		try {
			// Medium bigger than Large: the shop would compare sizes and call Medium the bigger of
			// the two, while a run walking the constants would still finish on Large.
			write(override, "{\"worldBorder\": {\"medium\": {\"size\": 4096}, \"large\": {\"size\": 2048}}}");
			runCommand(server, "mhr reload");

			check(BorderTier.MEDIUM.balance(BalanceManager.get()).size().orElseThrow() == medium,
					"an upside-down ladder must be refused and the balance left alone, and medium is now "
							+ (long) BorderTier.MEDIUM.balance(BalanceManager.get()).size().orElseThrow());
			check(BorderTier.LARGE.balance(BalanceManager.get()).size().orElseThrow() == large,
					"large likewise, and it is now "
							+ (long) BorderTier.LARGE.balance(BalanceManager.get()).size().orElseThrow());

			// A ladder that goes up is still accepted, or the check above would pass with every
			// reload refused.
			write(override, "{\"worldBorder\": {\"medium\": {\"size\": " + RETUNED_MEDIUM_SIZE + "}}}");
			runCommand(server, "mhr reload");
			check(BorderTier.MEDIUM.balance(BalanceManager.get()).size().orElseThrow() == RETUNED_MEDIUM_SIZE,
					"a ladder that goes up must still be accepted, and medium is "
							+ (long) BorderTier.MEDIUM.balance(BalanceManager.get()).size().orElseThrow());
		} finally {
			delete(override);
			runCommand(server, "mhr reload");
		}
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
		beginScenario(name);
		verdict(failures, name, body);
	}

	/** The start of a scenario whose verdict comes ticks later, once a traveller has arrived. */
	private static void beginScenario(String name) {
		LOGGER.info("=== scenario {} ===", name);
	}

	/** The end of one: a failure is recorded rather than thrown, so the rest still runs. */
	private static void verdict(List<String> failures, String name, Runnable body) {
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
