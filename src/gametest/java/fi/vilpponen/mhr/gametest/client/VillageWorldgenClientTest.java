package fi.vilpponen.mhr.gametest.client;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.StructureTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The villages unlock, proved without anybody joining the game: land generated while the unlock is
 * missing has no village in it, and the same land generated once the unlock is bought does.
 *
 * <p>Villages have nothing to place directly — there is no {@code place feature} for a structure —
 * so there is no fast server-GameTest half. The whole thing needs real
 * terrain, which in this harness means a dedicated server built on an *ordinary* overworld rather
 * than the flat world the harness makes by default.
 *
 * <p><b>Two worlds, not two places in one world.</b> {@code locate} writes down what it has already
 * looked at: a chunk that was searched while villages were locked keeps answering "nothing here"
 * afterwards, so unlocking and searching again in the same world would answer "no village" whatever
 * the mod did. The last scenario here demonstrates exactly that trap. So each half gets a world of
 * its own, built from scratch — and because the harness pins the seed, the two worlds are the same
 * terrain, which is what makes "there was a village at this spot, and now there is not" a sentence
 * about the unlock rather than about two different pieces of land.
 *
 * <p>The unlocked half runs first, for the same reason: only a world that has villages can say where
 * this seed puts one, and that spot is what the locked half then goes and looks at.
 *
 * <p>See {@code docs/dev-environment.md} for how to run this.
 */
public class VillageWorldgenClientTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("mhr-gametest");

	/**
	 * Where both worlds start searching. Far enough from spawn that nothing here was generated when
	 * the server started up, so every chunk this test looks at is made after the unlock state is
	 * set.
	 */
	private static final BlockPos SEARCH_FROM = new BlockPos(8000, 64, 8000);

	/**
	 * How far the structure search may spread, in chunks. Villages sit about 34 chunks apart, so
	 * this covers a good many candidate spots without taking all day.
	 */
	private static final int SEARCH_RADIUS_IN_CHUNKS = 48;

	/** How much land to generate around the village spot, in chunks. A village fits inside this. */
	private static final int PATCH_RADIUS_IN_CHUNKS = 2;

	/** The slice of the world worth counting ground in. */
	private static final int SCAN_FROM_Y = 40;
	private static final int SCAN_TO_Y = 160;

	private final List<String> failures = new ArrayList<>();

	/** Where the unlocked world put a village. The locked world goes and looks at the same spot. */
	private BlockPos village;

	/** The unlocked world's seed, so the locked world can prove it is the same terrain. */
	private long unlockedSeed;

	@Override
	public void runTest(ClientGameTestContext context) {
		unlockedWorld(context);
		lockedWorld(context);

		if (!failures.isEmpty()) {
			throw new AssertionError(failures.size() + " village-worldgen scenario(s) failed:\n  "
					+ String.join("\n  ", failures));
		}
		LOGGER.info("All village-worldgen scenarios passed.");
	}

	/** The first world: villages bought, so this seed's village is there to be found. */
	private void unlockedWorld(ClientGameTestContext context) {
		try (TestDedicatedServerContext server = normalWorld(context)) {
			try (TestDedicatedServerConnection connection = server.connect()) {
				settle(server, connection);
				server.runCommand("mhr unlock world.village");
				unlockedSeed = seedOf(server);
				LOGGER.info("Unlocked world, seed {}", unlockedSeed);

				scenario(context, "a-fresh-world-has-a-village-to-find",
						() -> aFreshWorldHasAVillageToFind(server));
				scenario(context, "the-village-really-generated",
						() -> theVillageReallyGenerated(context, server, connection));
			}
		}
	}

	/** The second world: same seed, same terrain, villages locked. */
	private void lockedWorld(ClientGameTestContext context) {
		try (TestDedicatedServerContext server = normalWorld(context)) {
			try (TestDedicatedServerConnection connection = server.connect()) {
				settle(server, connection);
				server.runCommand("mhr lock world.village");
				LOGGER.info("Locked world, seed {}", seedOf(server));

				scenario(context, "a-fresh-world-has-no-village-to-find",
						() -> aFreshWorldHasNoVillageToFind(server));
				scenario(context, "the-village-that-would-be-there-is-gone",
						() -> theVillageThatWouldBeThereIsGone(context, server, connection));
				scenario(context, "unrelated-structures-still-generate",
						() -> unrelatedStructuresStillGenerate(server));
				// Last, because it buys the unlock.
				scenario(context, "a-world-already-searched-cannot-prove-the-unlock",
						() -> aWorldAlreadySearchedCannotProveTheUnlock(server));
			}
		}
	}

	// --- the scenarios ---------------------------------------------------------------------

	/** With the unlock bought, land nobody has been to has a vanilla village in it. */
	private void aFreshWorldHasAVillageToFind(TestDedicatedServerContext server) {
		village = locate(server, StructureTags.VILLAGE);
		LOGGER.info("Village found at {}", village);

		check(village != null, "with world.village unlocked there must be a vanilla village within "
				+ SEARCH_RADIUS_IN_CHUNKS + " chunks of " + SEARCH_FROM + ", and locate found none");
	}

	/**
	 * Found is not the same as built. Generate the land around the village and read the chunks'
	 * own record of what was started there, which is what actually puts the buildings down.
	 */
	private void theVillageReallyGenerated(ClientGameTestContext context,
			TestDedicatedServerContext server, TestDedicatedServerConnection connection) {
		requireVillageSpot();

		generate(server, village);
		List<String> starts = villageStartsAround(server, village);
		LOGGER.info("Unlocked patch at {}: village starts {}", village, starts);

		check(!starts.isEmpty(), "the land around " + village + " was generated with world.village"
				+ " unlocked, so a village must have been started in it, and none was");

		look(context, server, connection, village, "fresh-village-unlocked");
	}

	/**
	 * The locked half of the same seed. Nothing anywhere near, over the whole area the unlocked
	 * world found its village in.
	 */
	private void aFreshWorldHasNoVillageToFind(TestDedicatedServerContext server) {
		long seed = seedOf(server);
		check(seed == unlockedSeed, "the two worlds must be the same terrain for the comparison to"
				+ " mean anything, but the unlocked one had seed " + unlockedSeed + " and this one"
				+ " has " + seed);

		BlockPos found = locate(server, StructureTags.VILLAGE);
		LOGGER.info("Locked world, village search from {}: {}", SEARCH_FROM, found);

		check(found == null, "a world generated while world.village is locked must have no village"
				+ " within " + SEARCH_RADIUS_IN_CHUNKS + " chunks of " + SEARCH_FROM + ", but locate"
				+ " found one at " + found);
	}

	/**
	 * The same spot, in the same terrain, where the unlocked world put a village: no village
	 * started there.
	 *
	 * <p>The ground count is the control. The unlock withholds villages, not land, so a patch that
	 * failed to generate at all would have no village in it either and would prove nothing.
	 */
	private void theVillageThatWouldBeThereIsGone(ClientGameTestContext context,
			TestDedicatedServerContext server, TestDedicatedServerConnection connection) {
		requireVillageSpot();

		generate(server, village);
		List<String> starts = villageStartsAround(server, village);
		int ground = count(server, village, BlockTags.DIRT);
		LOGGER.info("Locked patch at {}: village starts {}, {} ground blocks", village, starts, ground);

		check(ground > 0, "the patch at " + village + " did not generate at all: no ground in it, so"
				+ " finding no village there would prove nothing");
		check(starts.isEmpty(), "the same seed put a village at " + village + ", and with"
				+ " world.village locked it must not be built there — but the chunks started "
				+ starts);

		look(context, server, connection, village, "fresh-village-locked");
	}

	/**
	 * The control: only villages are withheld. A mixin that quietly refused every structure would
	 * pass everything above and be caught only here.
	 *
	 * <p>Two of them, and unrelated on purpose — a pillager outpost is a surface structure placed
	 * the same way a village is, and a mineshaft is an underground one placed a different way.
	 */
	private void unrelatedStructuresStillGenerate(TestDedicatedServerContext server) {
		BlockPos outpost = locate(server, outpostSet(server));
		BlockPos mineshaft = locate(server, StructureTags.MINESHAFT);
		LOGGER.info("Locked world: outpost {}, mineshaft {}", outpost, mineshaft);

		check(outpost != null, "locking world.village must leave other structures alone, but no"
				+ " pillager outpost generated within " + SEARCH_RADIUS_IN_CHUNKS + " chunks of "
				+ SEARCH_FROM);
		check(mineshaft != null, "locking world.village must leave other structures alone, but no"
				+ " mineshaft generated within " + SEARCH_RADIUS_IN_CHUNKS + " chunks of "
				+ SEARCH_FROM);
	}

	/**
	 * Why this test builds two worlds instead of unlocking halfway through one.
	 *
	 * <p>The search above has already been over this land, and every chunk it looked at now carries
	 * "no village starts here" in its own save data. Buying the unlock does not go back and change
	 * chunks that already exist, so the same search answers "nothing" a second time — which looks
	 * exactly like the unlock not working, and would be a false failure. A run that wants to see the
	 * unlock has to generate land that does not exist yet, which is what the first world above is.
	 */
	private void aWorldAlreadySearchedCannotProveTheUnlock(TestDedicatedServerContext server) {
		server.runCommand("mhr unlock world.village");

		BlockPos found = locate(server, StructureTags.VILLAGE);
		LOGGER.info("Locked world after unlocking, searching the same land again: {}", found);

		check(found == null, "land that was already searched must keep its answer after an unlock —"
				+ " that is why each half of this test gets a world of its own. It answered " + found
				+ " this time, so either vanilla stopped remembering searched chunks or the two"
				+ " worlds here are no longer needed; read this scenario before changing it");
	}

	// --- asking the server -------------------------------------------------------------------

	/** The nearest structure of a kind to {@link #SEARCH_FROM}, or null if there is none. */
	private static BlockPos locate(TestDedicatedServerContext server, TagKey<Structure> kind) {
		return server.computeOnServer(minecraftServer -> minecraftServer.overworld()
				.findNearestMapStructure(kind, SEARCH_FROM, SEARCH_RADIUS_IN_CHUNKS, false));
	}

	/** The same, for a single structure rather than a tag. */
	private static BlockPos locate(TestDedicatedServerContext server, HolderSet<Structure> kind) {
		return server.computeOnServer(minecraftServer -> minecraftServer.overworld()
				.findNearestMapStructure(kind, SEARCH_FROM, SEARCH_RADIUS_IN_CHUNKS, false));
	}

	/** Just the pillager outpost. It has no tag of its own, so it is named directly. */
	private static HolderSet<Structure> outpostSet(TestDedicatedServerContext server) {
		return server.computeOnServer(minecraftServer -> HolderSet.direct(minecraftServer
				.registryAccess()
				.lookupOrThrow(Registries.STRUCTURE)
				.getOrThrow(BuiltinStructures.PILLAGER_OUTPOST)));
	}

	private static long seedOf(TestDedicatedServerContext server) {
		return server.computeOnServer(minecraftServer -> minecraftServer.overworld().getSeed());
	}

	/**
	 * Every village the generated chunks around a point say they started, named.
	 *
	 * <p>This reads the chunks' own structure starts rather than asking {@code locate} again, so it
	 * answers what was built and not what could have been placed. Only valid starts count: refusing
	 * a village is recorded as an invalid one.
	 */
	private static List<String> villageStartsAround(TestDedicatedServerContext server, BlockPos middle) {
		int centreChunkX = middle.getX() >> 4;
		int centreChunkZ = middle.getZ() >> 4;
		return server.computeOnServer(minecraftServer -> {
			Registry<Structure> structures = minecraftServer.registryAccess()
					.lookupOrThrow(Registries.STRUCTURE);
			Set<Structure> villages = new HashSet<>();
			for (Holder<Structure> village : structures.getOrThrow(StructureTags.VILLAGE)) {
				villages.add(village.value());
			}

			ServerLevel level = minecraftServer.overworld();
			List<String> started = new ArrayList<>();
			for (int x = -PATCH_RADIUS_IN_CHUNKS; x <= PATCH_RADIUS_IN_CHUNKS; x++) {
				for (int z = -PATCH_RADIUS_IN_CHUNKS; z <= PATCH_RADIUS_IN_CHUNKS; z++) {
					LevelChunk chunk = requireChunk(level, centreChunkX + x, centreChunkZ + z);
					for (var start : chunk.getAllStarts().entrySet()) {
						if (villages.contains(start.getKey()) && start.getValue().isValid()) {
							started.add(name(structures, start.getKey(), start.getValue()));
						}
					}
				}
			}
			return started;
		});
	}

	private static String name(Registry<Structure> structures, Structure structure, StructureStart start) {
		return structures.getKey(structure) + " at " + start.getChunkPos();
	}

	/** Counts the blocks in the generated patch that carry a tag. */
	private static int count(TestDedicatedServerContext server, BlockPos middle, TagKey<Block> tag) {
		int centreChunkX = middle.getX() >> 4;
		int centreChunkZ = middle.getZ() >> 4;
		return server.computeOnServer(minecraftServer -> {
			ServerLevel level = minecraftServer.overworld();
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
			int found = 0;
			for (int chunkX = -PATCH_RADIUS_IN_CHUNKS; chunkX <= PATCH_RADIUS_IN_CHUNKS; chunkX++) {
				for (int chunkZ = -PATCH_RADIUS_IN_CHUNKS; chunkZ <= PATCH_RADIUS_IN_CHUNKS; chunkZ++) {
					LevelChunk chunk = requireChunk(level, centreChunkX + chunkX, centreChunkZ + chunkZ);
					for (int x = 0; x < 16; x++) {
						for (int z = 0; z < 16; z++) {
							for (int y = SCAN_FROM_Y; y <= SCAN_TO_Y; y++) {
								pos.set(((centreChunkX + chunkX) << 4) + x, y,
										((centreChunkZ + chunkZ) << 4) + z);
								if (chunk.getBlockState(pos).is(tag)) {
									found++;
								}
							}
						}
					}
				}
			}
			return found;
		});
	}

	private static LevelChunk requireChunk(ServerLevel level, int chunkX, int chunkZ) {
		LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
		if (chunk == null) {
			throw new AssertionError("Chunk " + chunkX + "," + chunkZ
					+ " went away before it could be read");
		}
		return chunk;
	}

	// --- the world -------------------------------------------------------------------------

	/** A dedicated server on ordinary terrain, with the settings both halves share. */
	private static TestDedicatedServerContext normalWorld(ClientGameTestContext context) {
		return context.worldBuilder()
				.adjustSettings(VillageWorldgenClientTest::normalTerrainWithStructures)
				.createServer();
	}

	/**
	 * Turns the harness's test world into an ordinary one that generates structures.
	 *
	 * <p>Both halves matter. The harness makes a superflat world, which has no villages to withhold;
	 * and it turns structure generation off, which would leave every world here villageless whatever
	 * the mod did — the locked half would pass for the wrong reason and the unlocked half could
	 * never pass at all.
	 *
	 * <p>Everything else it fixes for repeatability — the seed above all, which is what makes the
	 * two worlds the same terrain — is left alone.
	 */
	private static void normalTerrainWithStructures(WorldCreationUiState state) {
		state.setWorldType(new WorldCreationUiState.WorldTypeEntry(
				state.getSettings().worldgenLoadContext()
						.lookupOrThrow(Registries.WORLD_PRESET)
						.getOrThrow(WorldPresets.NORMAL)));
		state.setGenerateStructures(true);
	}

	/** Gets a new server ready to be asked questions: no border in the way, and daylight. */
	private static void settle(TestDedicatedServerContext server, TestDedicatedServerConnection connection) {
		connection.waitForChunksRender();
		// A run starts on the tiny border tier, and land outside the border is not the question
		// being asked here.
		server.runCommand("mhr border infinite");
		server.runCommand("time set noon");
		server.runCommand("weather clear");
	}

	/**
	 * Generates the patch around a point and waits for every chunk of it to be there.
	 *
	 * <p>Force-loading rather than walking in: this land has never existed, and a chunk the server
	 * has to make on the spot is the worldgen path the unlock acts on.
	 */
	private static void generate(TestDedicatedServerContext server, BlockPos middle) {
		int centreChunkX = middle.getX() >> 4;
		int centreChunkZ = middle.getZ() >> 4;
		server.runCommand("forceload add "
				+ ((centreChunkX - PATCH_RADIUS_IN_CHUNKS) << 4) + " "
				+ ((centreChunkZ - PATCH_RADIUS_IN_CHUNKS) << 4) + " "
				+ ((((centreChunkX + PATCH_RADIUS_IN_CHUNKS) << 4) + 15)) + " "
				+ ((((centreChunkZ + PATCH_RADIUS_IN_CHUNKS) << 4) + 15)));
		server.waitFor(minecraftServer -> {
			ServerLevel level = minecraftServer.overworld();
			for (int x = -PATCH_RADIUS_IN_CHUNKS; x <= PATCH_RADIUS_IN_CHUNKS; x++) {
				for (int z = -PATCH_RADIUS_IN_CHUNKS; z <= PATCH_RADIUS_IN_CHUNKS; z++) {
					if (level.getChunkSource().getChunkNow(centreChunkX + x, centreChunkZ + z) == null) {
						return false;
					}
				}
			}
			return true;
		});
	}

	/**
	 * Puts the player where they can see the patch and photographs it.
	 *
	 * <p>The structure starts above are the proof. This is so a human can see what they describe,
	 * and so a failure has a picture attached to it.
	 */
	private static void look(ClientGameTestContext context, TestDedicatedServerContext server,
			TestDedicatedServerConnection connection, BlockPos middle, String name) {
		int surface = server.computeOnServer(minecraftServer -> minecraftServer.overworld()
				.getHeight(Heightmap.Types.MOTION_BLOCKING, middle.getX(), middle.getZ()));
		// Spectator, or the player falls out of the sky and the picture is of whatever they landed
		// in. A spectator stays exactly where they are put.
		server.runCommand("gamemode spectator Player0");
		server.runCommand("tp Player0 " + middle.getX() + " " + (surface + 30) + " "
				+ (middle.getZ() - 40) + " 0 25");
		connection.waitForChunksRender();
		context.waitTicks(20);
		context.takeScreenshot(name);
	}

	// --- plumbing --------------------------------------------------------------------------

	/** The locked half has nothing to look at if the unlocked half never found a village. */
	private void requireVillageSpot() {
		if (village == null) {
			throw new AssertionError("the unlocked world found no village, so there is no spot to"
					+ " compare — fix a-fresh-world-has-a-village-to-find first");
		}
	}

	/**
	 * Runs one scenario. A failure is recorded and photographed rather than ending the run, so one
	 * command shows every criterion that is red instead of only the first.
	 */
	private void scenario(ClientGameTestContext context, String name, Runnable body) {
		LOGGER.info("=== scenario {} ===", name);
		try {
			body.run();
			LOGGER.info("=== scenario {}: PASS ===", name);
		} catch (Throwable failure) {
			failures.add(name + ": " + failure.getMessage());
			LOGGER.error("=== scenario {}: FAIL === {}", name, failure.getMessage(), failure);
			try {
				context.takeScreenshot("failed-" + name);
			} catch (Throwable ignored) {
				LOGGER.warn("Could not screenshot the failure of {}", name);
			}
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
