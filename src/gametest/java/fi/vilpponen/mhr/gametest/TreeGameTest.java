package fi.vilpponen.mhr.gametest;

import fi.vilpponen.mhr.UnlockState;
import fi.vilpponen.mhr.border.StartingWood;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.features.TreeFeatures;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
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
 * sapling grows. And the rule {@link StartingWood} uses to judge a start, asked about a patch each
 * scenario builds for itself: three reachable logs make a start viable, two do not.
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

	// --- what counts as a viable start ----------------------------------------------------

	/**
	 * Two logs are not a start: eight planks cannot make a crafting table and a wooden pickaxe with
	 * the sticks for it. Counting them changes nothing about the patch — the check only looks.
	 */
	@GameTest(skyAccess = true)
	public void twoLogsAreNotAViableStart(GameTestHelper helper) {
		nothingBought();
		prepare(helper);
		placeLogs(helper, 2);

		int counted = StartingWood.countLogs(helper.getLevel(), area(helper, 0, 6), helper.absolutePos(SEED),
				StartingWood.VIABLE_LOGS).size();

		helper.assertValueEqual(counted, 2, "logs counted in a patch holding two");
		helper.assertTrue(counted < StartingWood.VIABLE_LOGS, "two logs must not count as a viable start");
		helper.assertValueEqual(logsInPatch(helper), 2, "logs in the patch after counting, which only looks");
		helper.assertValueEqual(blocksInPatch(helper, BlockTags.LEAVES), 0,
				"leaves in the patch after counting, which must never grow anything");
		helper.succeed();
	}

	/** Three logs are. The control for the scenario above: the same patch with one more log. */
	@GameTest(skyAccess = true)
	public void threeLogsAreAViableStart(GameTestHelper helper) {
		nothingBought();
		prepare(helper);
		placeLogs(helper, 3);

		int counted = StartingWood.countLogs(helper.getLevel(), area(helper, 0, 6), helper.absolutePos(SEED),
				StartingWood.VIABLE_LOGS).size();

		helper.assertValueEqual(counted, StartingWood.VIABLE_LOGS, "logs counted in a patch holding three");
		helper.succeed();
	}

	// --- choosing a natural start ----------------------------------------------------------

	/**
	 * The search does not give up early. Nine candidates in a row turn out to have no trees, and the
	 * tenth does: it is the one chosen, and every one before it was tried, in order. A cap on how many
	 * may fail would leave a run without wood while a viable start was still in reach.
	 */
	@GameTest
	public void aLaterCandidateIsTriedAfterManyFail(GameTestHelper helper) {
		java.util.List<BlockPos> candidates = new java.util.ArrayList<>();
		for (int i = 0; i < 12; i++) {
			candidates.add(new BlockPos(i * 96, 64, 0));
		}
		java.util.List<BlockPos> tried = new java.util.ArrayList<>();

		BlockPos chosen = StartingWood.firstViable(candidates, candidate -> {
			tried.add(candidate);
			return tried.size() == 10 ? candidate : null;
		}, () -> false);

		helper.assertValueEqual(chosen, candidates.get(9), "the candidate chosen after nine that failed");
		helper.assertValueEqual(tried, candidates.subList(0, 10), "the candidates tried, in order");
		helper.succeed();
	}

	/**
	 * A candidate right next to one that failed is still tried, and can be the one chosen. Each
	 * candidate looks only at its own cell, so a neighbour's land is land nobody has looked at —
	 * skipping it as "too close" would miss trees that are really there.
	 */
	@GameTest
	public void aCandidateNextToAFailedOneIsStillTried(GameTestHelper helper) {
		BlockPos failed = new BlockPos(0, 64, 0);
		BlockPos neighbour = new BlockPos(64, 64, 64);
		java.util.List<BlockPos> tried = new java.util.ArrayList<>();

		BlockPos chosen = StartingWood.firstViable(java.util.List.of(failed, neighbour), candidate -> {
			tried.add(candidate);
			return candidate.equals(neighbour) ? candidate : null;
		}, () -> false);

		helper.assertValueEqual(chosen, neighbour, "the candidate chosen after its neighbour failed");
		helper.assertValueEqual(tried, java.util.List.of(failed, neighbour), "the candidates tried, in order");
		helper.succeed();
	}

	/**
	 * A biome that grows trees is a candidate even when it is not a forest, taiga, jungle or
	 * savanna. Cherry grove is the plain case: its trees are real, and those four tags do not name
	 * it, so a rule built from them would never count its logs. Each biome is checked against the
	 * tags first, so the scenario keeps meaning something if Minecraft ever retags one.
	 */
	@GameTest
	public void aTreeBiomeOutsideTheForestTagsIsACandidate(GameTestHelper helper) {
		for (ResourceKey<Biome> key : java.util.List.of(Biomes.CHERRY_GROVE, Biomes.MANGROVE_SWAMP,
				Biomes.MEADOW, Biomes.PLAINS)) {
			Holder<Biome> biome = biome(helper, key);
			helper.assertFalse(inForestTags(biome), key.identifier() + " must be outside the forest, taiga, jungle"
					+ " and savanna tags for this scenario to test anything");
			helper.assertTrue(StartingWood.growsTrees(biome.value()),
					key.identifier() + " grows trees, so a start may move to it");
		}
		helper.succeed();
	}

	/**
	 * Every forest, taiga, jungle and savanna is still a candidate — the new rule loses none of the
	 * old — and a biome with no trees at all is not, so the rule is not simply "every biome".
	 */
	@GameTest
	public void onlyBiomesThatGrowTreesAreCandidates(GameTestHelper helper) {
		var biomes = helper.getLevel().registryAccess().lookupOrThrow(Registries.BIOME);
		int forested = 0;
		for (Holder<Biome> biome : biomes.listElements().toList()) {
			if (inForestTags(biome)) {
				forested++;
				helper.assertTrue(StartingWood.growsTrees(biome.value()),
						biome.getRegisteredName() + " is a forest, taiga, jungle or savanna, so it must be a candidate");
			}
		}
		helper.assertTrue(forested > 0, "the forest, taiga, jungle and savanna tags must name some biome");
		for (ResourceKey<Biome> key : java.util.List.of(Biomes.DESERT, Biomes.BEACH, Biomes.STONY_SHORE,
				Biomes.THE_VOID)) {
			helper.assertFalse(StartingWood.growsTrees(biome(helper, key).value()),
					key.identifier() + " grows no trees, so it must not be a candidate");
		}
		helper.succeed();
	}

	private static Holder<Biome> biome(GameTestHelper helper, ResourceKey<Biome> key) {
		return helper.getLevel().registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(key);
	}

	private static boolean inForestTags(Holder<Biome> biome) {
		return biome.is(BiomeTags.IS_FOREST) || biome.is(BiomeTags.IS_TAIGA)
				|| biome.is(BiomeTags.IS_JUNGLE) || biome.is(BiomeTags.IS_SAVANNA);
	}

	/** Every candidate is tried before the search gives up. */
	@GameTest
	public void everyCandidateIsTriedBeforeGivingUp(GameTestHelper helper) {
		java.util.List<BlockPos> candidates = new java.util.ArrayList<>();
		for (int i = 0; i < 20; i++) {
			candidates.add(new BlockPos(i * 32, 64, 0));
			candidates.add(new BlockPos(i * 32, 64, 32));
		}
		java.util.List<BlockPos> tried = new java.util.ArrayList<>();

		BlockPos chosen = StartingWood.firstViable(candidates, candidate -> {
			tried.add(candidate);
			return null;
		}, () -> false);

		helper.assertTrue(chosen == null, "nothing may be chosen when no candidate has trees, and " + chosen + " was");
		helper.assertValueEqual(tried, candidates, "the candidates tried before giving up");
		helper.succeed();
	}

	/**
	 * The search stops once it has spent its budget, even with candidates left and none viable. The
	 * budget is what keeps a start at sea from generating the whole ocean; see {@link
	 * fi.vilpponen.mhr.gametest.client.StartingWoodClientTest} for the same limit on real land.
	 */
	@GameTest
	public void theSearchStopsWhenItsBudgetIsSpent(GameTestHelper helper) {
		java.util.List<BlockPos> candidates = new java.util.ArrayList<>();
		for (int i = 0; i < 10; i++) {
			candidates.add(new BlockPos(i * 32, 64, 0));
		}
		java.util.List<BlockPos> tried = new java.util.ArrayList<>();

		BlockPos chosen = StartingWood.firstViable(candidates, candidate -> {
			tried.add(candidate);
			return null;
		}, () -> tried.size() >= 3);

		helper.assertTrue(chosen == null, "nothing may be chosen once the budget is spent, and " + chosen + " was");
		helper.assertValueEqual(tried, candidates.subList(0, 3), "the candidates tried before the budget ran out");
		helper.succeed();
	}

	/** Lays {@code count} logs on the dirt, in a row along one edge of the patch. */
	private static void placeLogs(GameTestHelper helper, int count) {
		for (int i = 0; i < count; i++) {
			helper.getLevel().setBlock(helper.absolutePos(new BlockPos(1 + i * 2, 1, 5)),
					Blocks.BIRCH_LOG.defaultBlockState(), 3);
		}
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
