package fi.vilpponen.mhr.gametest.server;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.feature.Feature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The per-ore worldgen suppression, checked against the real vanilla ore features.
 *
 * <p>No client and no terrain generation: a box of stone is built inside the test region, the
 * vanilla feature that places an ore is run at the middle of it, and the box is counted afterwards.
 * That is the same thing {@code place feature minecraft:ore_iron} does from the console, which is
 * what this used to be checked with by hand — see {@code docs/dev-environment.md}.
 *
 * <p>Everything here is one test method on purpose. The unlock state is one file shared by the
 * whole server, and GameTest runs the tests of a batch side by side in the same world, so two
 * methods flipping unlocks would step on each other. Inside one method the scenarios are strictly
 * sequential, and each one starts by locking all seven ores, so the order they run in does not
 * matter either.
 *
 * <p>What this cannot reach is the deep iron and copper veins: they are not a feature at all but a
 * material rule applied while a chunk's blocks are chosen. Those are covered by the fresh-world
 * scan in {@code OreWorldgenClientTest}.
 */
public class OreFeatureTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("mhr-gametest");

	/** The test structure is 8x8x8, so this is the middle of it. */
	private static final BlockPos CENTER = new BlockPos(4, 4, 4);
	private static final int SIZE = 8;

	/**
	 * Placements are run on fixed seeds, counted out from this one, so the same unlock state gives
	 * the same veins twice: a test that is red once in twenty runs is worse than no test.
	 *
	 * <p>There are several of them because a feature is allowed to place nothing on a given seed —
	 * emerald scatters three attempts and can miss with all three. So "it places" means at least one
	 * of {@link #ATTEMPTS} seeds placed, and "it does not place" means none of them did, which is a
	 * stricter thing to ask than one seed ever was.
	 */
	private static final long PLACEMENT_SEED = 0x5EEDL;
	private static final int ATTEMPTS = 8;

	/**
	 * One ore: the unlock that sells it, a vanilla feature that places it, and the blocks that
	 * feature lands as.
	 *
	 * <p>The blocks are spelled out here rather than read from {@code Ore}, so that the test still
	 * knows what an ore looks like if that table is edited.
	 */
	private record OreCase(String name, String unlock, String feature, List<Block> blocks) {}

	private static final List<OreCase> ORES = List.of(
			new OreCase("coal", "world.ore.coal", "ore_coal",
					List.of(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE)),
			new OreCase("iron", "world.ore.iron", "ore_iron",
					List.of(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE)),
			new OreCase("copper", "world.ore.copper", "ore_copper_large",
					List.of(Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE)),
			new OreCase("gold", "world.ore.gold", "ore_gold",
					List.of(Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE)),
			new OreCase("redstone", "world.ore.redstone", "ore_redstone",
					List.of(Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE)),
			new OreCase("lapis", "world.ore.lapis", "ore_lapis",
					List.of(Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE)),
			new OreCase("diamond", "world.ore.diamond", "ore_diamond_large",
					List.of(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE)));

	@GameTest(maxTicks = 600)
	public void oreUnlocksDecideWhichOreFeaturesPlace(GameTestHelper helper) {
		List<String> failures = new ArrayList<>();

		for (OreCase ore : ORES) {
			scenario(failures, "locked-" + ore.name() + "-places-nothing",
					() -> lockedOrePlacesNothing(helper, ore));
			scenario(failures, "unlocked-" + ore.name() + "-places-while-the-others-stay-locked",
					() -> unlockedOrePlacesAlone(helper, ore));
		}

		scenario(failures, "locked-gold-covers-nether-gold-ore",
				() -> netherGoldFollowsTheGoldUnlock(helper));
		scenario(failures, "features-we-do-not-sell-are-untouched",
				() -> unrelatedFeaturesStillPlace(helper));

		if (!failures.isEmpty()) {
			throw new AssertionError(failures.size() + " ore-feature scenario(s) failed:\n  "
					+ String.join("\n  ", failures));
		}
		LOGGER.info("All ore-feature server scenarios passed.");
		helper.succeed();
	}

	// --- the scenarios ---------------------------------------------------------------------

	/** A locked ore's feature refuses to place, and leaves the stone alone. */
	private void lockedOrePlacesNothing(GameTestHelper helper, OreCase ore) {
		lockEveryOre(helper);
		fill(helper, Blocks.STONE);

		boolean placed = place(helper, ore.feature());

		check(!placed, "a locked " + ore.name() + " must make " + ore.feature()
				+ " refuse to place, but it reported success");
		check(count(helper, ore.blocks()) == 0, "a locked " + ore.name()
				+ " must leave the stone alone, but the box holds " + count(helper, ore.blocks())
				+ " block(s) of it");
	}

	/**
	 * Unlocking one ore restores exactly that ore. Every other ore is tried in the same breath, so
	 * a mixed state is what is actually being asserted here, not two separate all-or-nothing ones.
	 */
	private void unlockedOrePlacesAlone(GameTestHelper helper, OreCase ore) {
		lockEveryOre(helper);
		command(helper, "mhr unlock " + ore.unlock());
		fill(helper, Blocks.STONE);

		boolean placed = place(helper, ore.feature());

		check(placed, "an unlocked " + ore.name() + " must let " + ore.feature()
				+ " place, but it reported failure");
		int landed = count(helper, ore.blocks());
		check(landed > 0, "an unlocked " + ore.name() + " must put its vein in the stone, but the box"
				+ " holds none of " + ore.blocks());

		for (OreCase other : ORES) {
			if (other.name().equals(ore.name())) {
				continue;
			}
			fill(helper, Blocks.STONE);
			boolean otherPlaced = place(helper, other.feature());
			check(!otherPlaced, "unlocking " + ore.name() + " must not unlock " + other.name()
					+ ", but " + other.feature() + " placed");
			check(count(helper, other.blocks()) == 0, "unlocking " + ore.name() + " must leave "
					+ other.name() + " out of the world, but the box holds "
					+ count(helper, other.blocks()) + " block(s) of it");
		}
	}

	/**
	 * Nether gold ore is gold: the same unlock decides it, and the blackstone blobs beside it in the
	 * nether are not ours and keep generating either way.
	 */
	private void netherGoldFollowsTheGoldUnlock(GameTestHelper helper) {
		lockEveryOre(helper);
		fill(helper, Blocks.NETHERRACK);

		check(!place(helper, "ore_nether_gold"),
				"a locked gold unlock must stop nether gold ore as well");
		check(count(helper, List.of(Blocks.NETHER_GOLD_ORE)) == 0,
				"a locked gold unlock must leave the netherrack alone, but nether gold ore landed");

		check(place(helper, "ore_blackstone"),
				"blackstone is not an ore we sell and must place while every ore is locked");
		check(count(helper, List.of(Blocks.BLACKSTONE)) > 0,
				"the blackstone blob must actually land in the netherrack");

		command(helper, "mhr unlock world.ore.gold");
		fill(helper, Blocks.NETHERRACK);

		check(place(helper, "ore_nether_gold"),
				"an unlocked gold must let nether gold ore place again");
		check(count(helper, List.of(Blocks.NETHER_GOLD_ORE)) > 0,
				"an unlocked gold must put nether gold ore in the netherrack");
	}

	/**
	 * The control. Emerald, andesite and diorite come out of the same two feature classes the mod
	 * intercepts, and none of them is sold, so all three must place with every ore locked.
	 */
	private void unrelatedFeaturesStillPlace(GameTestHelper helper) {
		lockEveryOre(helper);

		fill(helper, Blocks.STONE);
		check(place(helper, "ore_emerald"), "emerald is not sold and must still place");
		check(count(helper, List.of(Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE)) > 0,
				"the emerald vein must actually land in the stone");

		fill(helper, Blocks.STONE);
		check(place(helper, "ore_andesite"), "andesite is not sold and must still place");
		check(count(helper, List.of(Blocks.ANDESITE)) > 0,
				"the andesite blob must actually land in the stone");

		fill(helper, Blocks.STONE);
		check(place(helper, "ore_diorite"), "diorite is not sold and must still place");
		check(count(helper, List.of(Blocks.DIORITE)) > 0,
				"the diorite blob must actually land in the stone");
	}

	// --- the box ---------------------------------------------------------------------------

	/** Rebuilds the test region as a solid block of one material. */
	private static void fill(GameTestHelper helper, Block block) {
		for (int x = 0; x < SIZE; x++) {
			for (int y = 0; y < SIZE; y++) {
				for (int z = 0; z < SIZE; z++) {
					helper.setBlock(x, y, z, block);
				}
			}
		}
	}

	/** How many of these blocks are in the box. */
	private static int count(GameTestHelper helper, List<Block> blocks) {
		int found = 0;
		for (int x = 0; x < SIZE; x++) {
			for (int y = 0; y < SIZE; y++) {
				for (int z = 0; z < SIZE; z++) {
					if (blocks.contains(helper.getBlockState(new BlockPos(x, y, z)).getBlock())) {
						found++;
					}
				}
			}
		}
		return found;
	}

	/**
	 * Runs a vanilla feature at the middle of the box, the way worldgen would, once per fixed seed.
	 *
	 * @return true if any of the attempts reported a placement.
	 */
	private static boolean place(GameTestHelper helper, String featureId) {
		ServerLevel level = helper.getLevel();
		Feature feature = level.registryAccess()
				.lookupOrThrow(Registries.FEATURE)
				.getOrThrow(ResourceKey.create(Registries.FEATURE,
						Identifier.withDefaultNamespace(featureId)))
				.value();
		boolean placed = false;
		for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
			placed |= feature.place(level, level.getChunkSource().getGenerator(),
					RandomSource.create(PLACEMENT_SEED + attempt), helper.absolutePos(CENTER));
		}
		return placed;
	}

	// --- plumbing --------------------------------------------------------------------------

	private static void lockEveryOre(GameTestHelper helper) {
		for (OreCase ore : ORES) {
			command(helper, "mhr lock " + ore.unlock());
		}
	}

	/** The dev command, through the real command dispatcher, as the console would run it. */
	private static void command(GameTestHelper helper, String command) {
		MinecraftServer server = helper.getLevel().getServer();
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
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
