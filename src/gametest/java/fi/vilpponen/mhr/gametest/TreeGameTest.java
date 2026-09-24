package fi.vilpponen.mhr.gametest;

import fi.vilpponen.mhr.UnlockState;
import fi.vilpponen.mhr.border.StartingWood;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.features.TreeFeatures;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;

/**
 * Trees, checked on a dedicated server with no client in sight.
 *
 * <p>Two things live here. Trees are vanilla from the first run, with nothing bought: the vanilla
 * tree feature places — the same call worldgen, bonemeal and a sapling all end up making — and a
 * sapling grows. And the fallback that keeps a bounded run from starting without wood, {@link
 * StartingWood}, asked about a patch each scenario builds for itself: it grows a tree where there
 * is no wood, and leaves alone a patch that already has some.
 *
 * <p>The slow half — real terrain, and a real run start — lives in {@link
 * fi.vilpponen.mhr.gametest.client.TreeWorldgenClientTest} and {@link
 * fi.vilpponen.mhr.gametest.client.StartingWoodClientTest}.
 *
 * <p>Every scenario makes sure the old {@code world.trees} unlock is not owned, rather than
 * inheriting whatever the last one left, so "nothing was bought" is something each one establishes.
 */
public final class TreeGameTest {
	/** The retired unlock that used to gate trees. Kept only to prove it no longer matters. */
	private static final String RETIRED_TREES = "world.trees";

	/** The dirt layer each scenario builds, and the square the feature is asked to grow from. */
	private static final BlockPos GROUND = new BlockPos(3, 0, 3);
	private static final BlockPos SEED = new BlockPos(3, 1, 3);

	/** The patch that gets cleared and then searched, relative to the test area. */
	private static final BlockPos PATCH_FROM = new BlockPos(0, 1, 0);
	private static final BlockPos PATCH_TO = new BlockPos(6, 24, 6);

	// --- trees are vanilla -----------------------------------------------------------------

	/** The vanilla oak feature places, with nothing bought. */
	@GameTest(skyAccess = true)
	public void aTreeFeaturePlacesWithNothingBought(GameTestHelper helper) {
		nothingBought();
		prepare(helper);

		boolean placed = place(helper, TreeFeatures.OAK);

		helper.assertTrue(placed, "the vanilla oak feature must place with nothing bought");
		helper.assertTrue(logsInPatch(helper) > 0,
				"an oak that reported success must have left logs behind, and there are none");
		helper.succeed();
	}

	/** Fallen trees are vanilla again too. */
	@GameTest(skyAccess = true)
	public void aFallenTreePlacesWithNothingBought(GameTestHelper helper) {
		nothingBought();
		prepare(helper);

		boolean placed = place(helper, TreeFeatures.FALLEN_OAK_TREE);

		helper.assertTrue(placed, "a fallen oak must place with nothing bought");
		helper.assertTrue(logsInPatch(helper) > 0,
				"a fallen oak that reported success must have left logs behind, and there are none");
		helper.succeed();
	}

	/**
	 * A sapling pushed along the way bonemeal and the random tick push it turns into a tree. This is
	 * what makes a tree renewable, so it is the part a woodless start most needs.
	 */
	@GameTest(skyAccess = true)
	public void aSaplingGrowsWithNothingBought(GameTestHelper helper) {
		nothingBought();
		prepare(helper);
		BlockPos sapling = helper.absolutePos(SEED);
		helper.getLevel().setBlock(sapling, Blocks.OAK_SAPLING.defaultBlockState(), 3);

		advanceSapling(helper, sapling);

		helper.assertFalse(helper.getLevel().getBlockState(sapling).is(Blocks.OAK_SAPLING),
				"a sapling must turn into a tree rather than stay a sapling");
		helper.assertTrue(logsInPatch(helper) > 0, "a sapling must have grown logs, and there are none");
		helper.succeed();
	}

	// --- a bounded start always has wood ---------------------------------------------------

	/**
	 * A patch with no wood in it gets a tree, inside the patch, standing on the ground. This is the
	 * regression the issue is about: a start that would otherwise have nothing to cut.
	 */
	@GameTest(skyAccess = true)
	public void aWoodlessPatchGetsATree(GameTestHelper helper) {
		nothingBought();
		prepare(helper);
		helper.assertValueEqual(logsInPatch(helper), 0, "setup: logs in the patch before the fallback");

		StartingWood.Result result = StartingWood.ensure(helper.getLevel(), area(helper, 0, 6),
				helper.absolutePos(SEED));

		helper.assertValueEqual(result.outcome(), StartingWood.Outcome.PLANTED,
				"what the fallback did about a patch with no wood");
		helper.assertTrue(helper.getLevel().getBlockState(result.log()).is(BlockTags.LOGS),
				"the fallback said it grew a tree at " + result.log() + ", and there is no log there");
		helper.assertTrue(isInside(helper, result.log(), 0, 6),
				"the fallback's tree must be inside the patch it was asked about, and it is at " + result.log());
		helper.assertTrue(logsInPatch(helper) > 0, "the patch still has no logs after the fallback");
		helper.succeed();
	}

	/**
	 * A patch that already has a log is left exactly as it was. The fallback is for starts with no
	 * wood, not a free tree on top of vanilla's.
	 */
	@GameTest(skyAccess = true)
	public void aPatchWithWoodIsLeftAlone(GameTestHelper helper) {
		nothingBought();
		prepare(helper);
		BlockPos log = helper.absolutePos(new BlockPos(1, 1, 5));
		helper.getLevel().setBlock(log, Blocks.BIRCH_LOG.defaultBlockState(), 3);

		StartingWood.Result result = StartingWood.ensure(helper.getLevel(), area(helper, 0, 6),
				helper.absolutePos(SEED));

		helper.assertValueEqual(result.outcome(), StartingWood.Outcome.FOUND,
				"what the fallback did about a patch that already had a log");
		helper.assertValueEqual(result.log(), log, "the log the fallback found");
		helper.assertValueEqual(logsInPatch(helper), 1, "logs in the patch after the fallback");
		helper.assertValueEqual(blocksInPatch(helper, BlockTags.LEAVES), 0,
				"leaves in the patch after the fallback, which should have grown nothing");
		helper.succeed();
	}

	/**
	 * Under water no vanilla oak will stand, so the fallback's last resort builds one: a trunk from
	 * the bottom up through the water, which is still wood a player can reach.
	 */
	@GameTest(skyAccess = true)
	public void aFloodedPatchStillGetsWood(GameTestHelper helper) {
		nothingBought();
		prepare(helper);
		ServerLevel level = helper.getLevel();
		// A pool two deep with a stone rim, so the water stays in this test's own patch.
		for (BlockPos pos : between(helper, new BlockPos(0, 1, 0), new BlockPos(6, 2, 6))) {
			level.setBlock(pos, Blocks.STONE.defaultBlockState(), 2);
		}
		for (BlockPos pos : between(helper, new BlockPos(1, 1, 1), new BlockPos(5, 2, 5))) {
			level.setBlock(pos, Blocks.WATER.defaultBlockState(), 2);
		}

		StartingWood.Result result = StartingWood.ensure(level, area(helper, 1, 5), helper.absolutePos(SEED));

		helper.assertValueEqual(result.outcome(), StartingWood.Outcome.PLANTED,
				"what the fallback did about a flooded patch with no wood");
		helper.assertTrue(isInside(helper, result.log(), 1, 5),
				"the fallback's tree must be inside the pool it was asked about, and it is at " + result.log());
		// The water is two deep over the dirt, so the first dry block of the trunk is three up.
		BlockPos aboveWater = result.log().atY(helper.absolutePos(new BlockPos(0, 3, 0)).getY());
		helper.assertTrue(level.getBlockState(aboveWater).is(BlockTags.LOGS),
				"the trunk must come up out of the water to be reachable, and above the surface at "
						+ aboveWater + " is " + level.getBlockState(aboveWater));
		helper.succeed();
	}

	// --- plumbing --------------------------------------------------------------------------

	private static void nothingBought() {
		UnlockState.get().set(RETIRED_TREES, false);
	}

	/** Empties the test area above the floor and lays a patch of dirt to grow from. */
	private static void prepare(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		for (BlockPos pos : patch(helper)) {
			level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
		}
		for (BlockPos pos : between(helper, GROUND.offset(-3, 0, -3), GROUND.offset(3, 0, 3))) {
			level.setBlock(pos, Blocks.DIRT.defaultBlockState(), 2);
		}
	}

	/** The square of columns from {@code from} to {@code to} on both axes, relative to the test area. */
	private static StartingWood.Area area(GameTestHelper helper, int from, int to) {
		BlockPos a = helper.absolutePos(new BlockPos(from, 0, from));
		BlockPos b = helper.absolutePos(new BlockPos(to, 0, to));
		return new StartingWood.Area(Math.min(a.getX(), b.getX()), Math.min(a.getZ(), b.getZ()),
				Math.max(a.getX(), b.getX()), Math.max(a.getZ(), b.getZ()));
	}

	private static boolean isInside(GameTestHelper helper, BlockPos pos, int from, int to) {
		StartingWood.Area area = area(helper, from, to);
		return pos.getX() >= area.minX() && pos.getX() <= area.maxX()
				&& pos.getZ() >= area.minZ() && pos.getZ() <= area.maxZ();
	}

	/** Places a vanilla feature exactly the way worldgen would, and says whether it took. */
	private static boolean place(GameTestHelper helper, ResourceKey<Feature> key) {
		ServerLevel level = helper.getLevel();
		Feature feature = level.registryAccess().lookupOrThrow(Registries.FEATURE).getValueOrThrow(key);
		return feature.place(level, level.getChunkSource().getGenerator(), level.getRandom(),
				helper.absolutePos(SEED));
	}

	/**
	 * Pushes a sapling along the way bonemeal does. The first push only advances its stage, so this
	 * repeats until it either grows or is plainly not going to.
	 */
	private static void advanceSapling(GameTestHelper helper, BlockPos pos) {
		ServerLevel level = helper.getLevel();
		SaplingBlock sapling = (SaplingBlock) Blocks.OAK_SAPLING;
		for (int push = 0; push < 8; push++) {
			BlockState state = level.getBlockState(pos);
			if (!state.is(Blocks.OAK_SAPLING)) {
				return;
			}
			sapling.advanceTree(level, pos, state, level.getRandom());
		}
	}

	private static int logsInPatch(GameTestHelper helper) {
		return blocksInPatch(helper, BlockTags.LOGS);
	}

	private static int blocksInPatch(GameTestHelper helper, TagKey<Block> tag) {
		ServerLevel level = helper.getLevel();
		int count = 0;
		for (BlockPos pos : patch(helper)) {
			if (level.getBlockState(pos).is(tag)) {
				count++;
			}
		}
		return count;
	}

	private static Iterable<BlockPos> patch(GameTestHelper helper) {
		return between(helper, PATCH_FROM, PATCH_TO);
	}

	/**
	 * Every absolute position between two relative corners.
	 *
	 * <p>A test area can be placed rotated, which swaps the corners round, so the absolute corners
	 * are sorted rather than assumed to be in order.
	 */
	private static Iterable<BlockPos> between(GameTestHelper helper, BlockPos from, BlockPos to) {
		BlockPos a = helper.absolutePos(from);
		BlockPos b = helper.absolutePos(to);
		return BlockPos.betweenClosed(
				Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
				Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
	}
}
