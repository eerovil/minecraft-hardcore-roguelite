package fi.vilpponen.mhr.gametest;

import fi.vilpponen.mhr.Unlock;
import fi.vilpponen.mhr.UnlockState;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.features.TreeFeatures;
import net.minecraft.data.worldgen.features.VegetationFeatures;
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
 * The trees unlock, checked on a dedicated server with no client in sight.
 *
 * <p>These are the fast checks. Each one lays a patch of dirt in its own test area and then asks
 * the vanilla tree feature to place itself on it, which is the same call worldgen, bonemeal and a
 * sapling all end up making — so refusing it is the whole of the feature. The slow half, that real
 * terrain generated from scratch comes out treeless, lives in
 * {@link fi.vilpponen.mhr.gametest.client.TreeWorldgenClientTest} because only a normal overworld
 * can answer it.
 *
 * <p>Every scenario sets the unlock itself rather than inheriting whatever the last one left, so
 * they can run in any order. See {@code docs/dev-environment.md} for how to run them.
 */
public final class TreeUnlockGameTest {
	/** The dirt layer each scenario builds, and the square the feature is asked to grow from. */
	private static final BlockPos GROUND = new BlockPos(3, 0, 3);
	private static final BlockPos SEED = new BlockPos(3, 1, 3);

	/** The patch that gets cleared and then searched, relative to the test area. */
	private static final BlockPos PATCH_FROM = new BlockPos(0, 1, 0);
	private static final BlockPos PATCH_TO = new BlockPos(6, 24, 6);

	// --- the tree feature itself -------------------------------------------------------------

	/**
	 * A locked world turns the vanilla oak feature away. This is the check the dev docs used to ask
	 * a human to make by hand with {@code place feature minecraft:oak}.
	 */
	@GameTest(skyAccess = true)
	public void lockedWorldRefusesATreeFeature(GameTestHelper helper) {
		setTrees(false);
		prepare(helper);

		boolean placed = place(helper, TreeFeatures.OAK);

		helper.assertFalse(placed, "a locked world must refuse to place the vanilla oak feature");
		helper.assertValueEqual(logsInPatch(helper), 0,
				"logs left behind after a refused oak");
		helper.succeed();
	}

	/** Buy the unlock and the very same call places a tree. */
	@GameTest(skyAccess = true)
	public void unlockedWorldPlacesATreeFeature(GameTestHelper helper) {
		setTrees(true);
		prepare(helper);

		boolean placed = place(helper, TreeFeatures.OAK);

		helper.assertTrue(placed, "an unlocked world must place the vanilla oak feature");
		helper.assertTrue(logsInPatch(helper) > 0,
				"an oak that reported success must have left logs behind, and there are none");
		helper.succeed();
	}

	/**
	 * Fallen trees are suppressed too. They are a free pile of logs lying on the ground, so leaving
	 * them in would hand the player the one thing the unlock is meant to withhold.
	 */
	@GameTest(skyAccess = true)
	public void lockedWorldRefusesAFallenTree(GameTestHelper helper) {
		setTrees(false);
		prepare(helper);

		boolean placed = place(helper, TreeFeatures.FALLEN_OAK_TREE);

		helper.assertFalse(placed, "a locked world must refuse to place a fallen oak either");
		helper.assertValueEqual(logsInPatch(helper), 0,
				"logs left behind after a refused fallen oak");
		helper.succeed();
	}

	@GameTest(skyAccess = true)
	public void unlockedWorldPlacesAFallenTree(GameTestHelper helper) {
		setTrees(true);
		prepare(helper);

		boolean placed = place(helper, TreeFeatures.FALLEN_OAK_TREE);

		helper.assertTrue(placed, "an unlocked world must place a fallen oak");
		helper.assertTrue(logsInPatch(helper) > 0,
				"a fallen oak that reported success must have left logs behind, and there are none");
		helper.succeed();
	}

	// --- saplings --------------------------------------------------------------------------

	/**
	 * A sapling grown on purpose — which is what bonemeal and the random tick both come down to —
	 * gets nowhere while the unlock is missing, and the sapling is handed back rather than eaten.
	 */
	@GameTest(skyAccess = true)
	public void saplingWillNotGrowWhileLocked(GameTestHelper helper) {
		setTrees(false);
		prepare(helper);
		BlockPos sapling = helper.absolutePos(SEED);
		helper.getLevel().setBlock(sapling, Blocks.OAK_SAPLING.defaultBlockState(), 3);

		advanceSapling(helper, sapling);

		helper.assertTrue(helper.getLevel().getBlockState(sapling).is(Blocks.OAK_SAPLING),
				"a sapling that could not grow must still be standing, and it is "
						+ helper.getLevel().getBlockState(sapling));
		helper.assertValueEqual(logsInPatch(helper), 0,
				"logs grown from a sapling while trees are locked");
		helper.succeed();
	}

	/** The same sapling, the same pushes, once the unlock is owned. */
	@GameTest(skyAccess = true)
	public void saplingGrowsOnceUnlocked(GameTestHelper helper) {
		setTrees(true);
		prepare(helper);
		BlockPos sapling = helper.absolutePos(SEED);
		helper.getLevel().setBlock(sapling, Blocks.OAK_SAPLING.defaultBlockState(), 3);

		advanceSapling(helper, sapling);

		helper.assertFalse(helper.getLevel().getBlockState(sapling).is(Blocks.OAK_SAPLING),
				"an unlocked sapling must turn into a tree rather than stay a sapling");
		helper.assertTrue(logsInPatch(helper) > 0,
				"an unlocked sapling must have grown logs, and there are none");
		helper.succeed();
	}

	// --- the control -----------------------------------------------------------------------

	/**
	 * What the unlock withholds is trees, not worldgen. A flower patch still places while trees are
	 * locked, so a mixin that quietly stopped every feature would be caught here rather than read
	 * as the feature working.
	 */
	@GameTest(skyAccess = true)
	public void unrelatedVegetationStillPlacesWhileLocked(GameTestHelper helper) {
		setTrees(false);
		prepare(helper);

		boolean placed = place(helper, VegetationFeatures.FLOWER_DEFAULT);

		helper.assertTrue(placed,
				"locking trees must leave the rest of worldgen alone, but a flower patch was refused");
		helper.assertTrue(blocksInPatch(helper, BlockTags.FLOWERS) > 0,
				"the flower patch reported success but left no flowers");
		helper.succeed();
	}

	// --- plumbing --------------------------------------------------------------------------

	private static void setTrees(boolean owned) {
		UnlockState.get().set(Unlock.TREES, owned);
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
