package fi.vilpponen.mhr.gametest.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The half of the animal unlocks that only real terrain can answer: land generated from scratch
 * comes out with no cows, pigs, sheep, chickens, horses or wolves standing in it, and comes back
 * populated once the species is bought.
 *
 * <p>{@link fi.vilpponen.mhr.gametest.server.AnimalSpawnGameTest} covers the fast, exact half on a
 * plain server — the two spawn seams, the jockey, the bystanders. It cannot cover this one, because
 * Fabric's server GameTests run on a superflat world with no biome populations to suppress. So this
 * test builds a dedicated server on a *normal* overworld — the only lever in the harness that
 * produces one — walks out to plains nobody has been to, force-loads them, and counts what is
 * standing there.
 *
 * <p>A different patch is used for every unlock state on purpose. Chunk-generation animals are
 * placed once, when the land is made, so scanning the same patch twice would answer "no cows" both
 * times and look exactly like the feature working.
 *
 * <p>See {@code docs/dev-environment.md} for how to run this.
 */
public class AnimalWorldgenClientTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("mhr-gametest");

	/** How far out from the middle of a patch to generate, in chunks. 7×7 chunks of plains. */
	private static final int RADIUS_IN_CHUNKS = 7;

	/** How far a biome search may wander, in blocks. */
	private static final int SEARCH_RADIUS = 6400;

	/**
	 * Where the camera stands relative to what it is photographing: back along both horizontal axes
	 * and up, so the shot looks down across the ground from about fourteen blocks — close enough
	 * that an animal is plainly an animal, far enough that a whole herd fits in frame.
	 */
	private static final int CAMERA_BACK = 9;
	private static final int CAMERA_UP = 7;

	/** How close two animals have to be to count as standing together. */
	private static final int CLUSTER_RADIUS = 24;

	/**
	 * Where each scenario goes looking for its land. Thousands of blocks apart and in different
	 * directions, so no patch is land an earlier scenario already made.
	 */
	private static final BlockPos LOCKED_PLAINS_FROM = new BlockPos(-7000, 64, -7000);
	private static final BlockPos UNLOCKED_PLAINS_FROM = new BlockPos(7000, 64, 7000);
	private static final BlockPos MIXED_PLAINS_FROM = new BlockPos(-7000, 64, 7000);
	private static final BlockPos TAIGA_FROM = new BlockPos(7000, 64, -7000);

	/** The six species, in the order they are reported. */
	private static final Map<String, EntityType<?>> SPECIES = species();

	private static final List<String> UNLOCK_IDS = List.of(
			"world.animal.cow", "world.animal.pig", "world.animal.sheep",
			"world.animal.chicken", "world.animal.horse", "world.animal.wolf");

	private final List<String> failures = new ArrayList<>();

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestDedicatedServerContext server = context.worldBuilder()
				.adjustSettings(AnimalWorldgenClientTest::useNormalTerrain)
				.createServer()) {
			try (TestDedicatedServerConnection connection = server.connect()) {
				connection.waitForChunksRender();

				// A run starts on the tiny border tier, and land outside the border never gets its
				// animals at all — every count would read zero whether a species is locked or not,
				// which looks exactly like the feature working and is not.
				server.runCommand("mhr border infinite");
				server.runCommand("time set noon");
				server.runCommand("weather clear");
				// The harness's test world is made with mob spawning off, so its own tests are not
				// disturbed by wandering mobs. This test is about nothing else, so turn it back on
				// before any land is made: without it every patch generates empty and every count
				// reads zero, which looks exactly like the feature working and is not.
				// The rule is `spawn_mobs` — 26.3 renamed every gamerule to snake_case, and the old
				// `doMobSpawning` spelling is quietly rejected as a bad argument.
				server.runCommand("gamerule spawn_mobs true");

				scenario(context, "fresh-plains-have-no-animals-while-every-species-is-locked",
						() -> freshPlainsAreEmptyWhileLocked(context, server, connection));
				scenario(context, "fresh-plains-fill-up-once-every-species-is-bought",
						() -> freshPlainsFillUpOnceUnlocked(context, server, connection));
				scenario(context, "a-mixed-lock-state-shows-up-in-fresh-land",
						() -> onlyTheBoughtSpeciesTurnsUp(context, server, connection));
				scenario(context, "locking-every-species-leaves-other-animals-alone",
						() -> unsoldAnimalsStillPopulateFreshLand(server));
			}
		}

		if (!failures.isEmpty()) {
			throw new AssertionError(failures.size() + " animal-worldgen scenario(s) failed:\n  "
					+ String.join("\n  ", failures));
		}
		LOGGER.info("All animal-worldgen scenarios passed.");
	}

	// --- the scenarios ---------------------------------------------------------------------

	/**
	 * Plains nobody has been to, made on the spot with all six species locked, come out with none
	 * of them in it.
	 *
	 * <p>The ground count is the control: the unlock withholds animals, not terrain, so a patch
	 * that never generated would make "no cows" mean nothing.
	 */
	private void freshPlainsAreEmptyWhileLocked(ClientGameTestContext context,
			TestDedicatedServerContext server, TestDedicatedServerConnection connection) {
		lockEverySpecies(server);

		BlockPos plains = findBiome(server, Biomes.PLAINS, LOCKED_PLAINS_FROM);
		generate(server, plains);
		Map<String, Integer> standing = countSpecies(server, plains);
		int ground = countGround(server, plains);
		LOGGER.info("Locked plains at {}: {}, {} ground blocks", plains, standing, ground);

		check(ground > 0, "the patch at " + plains + " did not generate at all: no ground in it,"
				+ " so counting zero animals there would prove nothing");
		for (Map.Entry<String, Integer> found : standing.entrySet()) {
			check(found.getValue() == 0, "fresh plains generated with " + found.getKey()
					+ " locked must have none in it, and the patch at " + plains + " has "
					+ found.getValue());
		}

		look(context, server, connection, plains, "fresh-plains-animals-locked");
	}

	/** Buy all six, walk to plains nobody has generated yet, and the animals are back. */
	private void freshPlainsFillUpOnceUnlocked(ClientGameTestContext context,
			TestDedicatedServerContext server, TestDedicatedServerConnection connection) {
		unlockEverySpecies(server);

		BlockPos plains = findBiome(server, Biomes.PLAINS, UNLOCKED_PLAINS_FROM);
		generate(server, plains);
		Map<String, Integer> standing = countSpecies(server, plains);
		int total = standing.values().stream().mapToInt(Integer::intValue).sum();
		LOGGER.info("Unlocked plains at {}: {}", plains, standing);

		check(total > 0, "fresh plains generated with every species bought must have animals in"
				+ " them, and the patch at " + plains + " is empty — which would make the locked"
				+ " scenario prove nothing");

		look(context, server, connection, plains, "fresh-plains-animals-unlocked");
	}

	/**
	 * The state a real save is almost always in: one species bought and the rest still missing.
	 * Cows walk into the new land and nothing else the feature owns does.
	 */
	private void onlyTheBoughtSpeciesTurnsUp(ClientGameTestContext context,
			TestDedicatedServerContext server, TestDedicatedServerConnection connection) {
		lockEverySpecies(server);
		server.runCommand("mhr unlock world.animal.cow");

		BlockPos plains = findBiome(server, Biomes.PLAINS, MIXED_PLAINS_FROM);
		generate(server, plains);
		Map<String, Integer> standing = countSpecies(server, plains);
		LOGGER.info("Cow-only plains at {}: {}", plains, standing);

		check(standing.get("cow") > 0, "with only the cow bought, fresh plains must still get their"
				+ " cows, and the patch at " + plains + " has none");
		for (Map.Entry<String, Integer> found : standing.entrySet()) {
			if (found.getKey().equals("cow")) {
				continue;
			}
			check(found.getValue() == 0, "buying the cow must not also bring back " + found.getKey()
					+ ", and the patch at " + plains + " has " + found.getValue() + " of them");
		}

		look(context, server, connection, plains, "fresh-plains-only-cows-unlocked");
	}

	/**
	 * The control, in real terrain: the animals the shop does not sell keep populating fresh land
	 * with all six locked.
	 *
	 * <p>Taiga, because it is a common biome whose list is mostly *not* ours — foxes and rabbits
	 * alongside the sold species — so a patch of it answers both halves of the question at once.
	 * Plains would not: nearly everything that generates there is something the shop sells, and
	 * "nothing unsold turned up" would mean nothing.
	 */
	private void unsoldAnimalsStillPopulateFreshLand(TestDedicatedServerContext server) {
		lockEverySpecies(server);

		BlockPos taiga = findBiome(server, Biomes.TAIGA, TAIGA_FROM);
		generate(server, taiga);
		Map<String, Integer> unsold = countUnsold(server, taiga);
		Map<String, Integer> standing = countSpecies(server, taiga);
		int total = unsold.values().stream().mapToInt(Integer::intValue).sum();
		LOGGER.info("Locked taiga at {}: unsold {}, ours {}", taiga, unsold, standing);

		check(total > 0, "the animals the shop does not sell must keep populating fresh land with"
				+ " every species locked, and the patch at " + taiga + " has none of them either —"
				+ " which would mean the lock is taking more than it was asked to");
		for (Map.Entry<String, Integer> found : standing.entrySet()) {
			check(found.getValue() == 0, "with every species locked, fresh taiga must have no "
					+ found.getKey() + " in it, and the patch at " + taiga + " has "
					+ found.getValue());
		}
	}

	// --- the world -------------------------------------------------------------------------

	/**
	 * Turns the harness's superflat test world into an ordinary one.
	 *
	 * <p>Everything else the harness fixes for repeatability — the seed, the frozen clock and
	 * weather — is left alone, so the same patches of land turn up on every run.
	 */
	private static void useNormalTerrain(WorldCreationUiState state) {
		state.setWorldType(new WorldCreationUiState.WorldTypeEntry(
				state.getSettings().worldgenLoadContext()
						.lookupOrThrow(Registries.WORLD_PRESET)
						.getOrThrow(WorldPresets.NORMAL)));
	}

	/** The middle of the nearest patch of a biome to a point, without generating anything. */
	private static BlockPos findBiome(TestDedicatedServerContext server,
			ResourceKey<Biome> biome, BlockPos from) {
		BlockPos found = server.computeOnServer(minecraftServer -> {
			var nearest = minecraftServer.overworld().findClosestBiome3d(
					held -> held.is(biome), from, SEARCH_RADIUS, 32, 64);
			return nearest == null ? null : nearest.getFirst();
		});
		if (found == null) {
			throw new AssertionError("No " + biome.identifier() + " within " + SEARCH_RADIUS
					+ " blocks of " + from + ", so there is nowhere to count animals");
		}
		return found;
	}

	/**
	 * Generates the patch around a point and waits for every chunk of it to be there.
	 *
	 * <p>Force-loading rather than walking in: the point is that this land has never existed
	 * before, and the animals a chunk is born with are placed exactly once, while it is being made.
	 */
	private static void generate(TestDedicatedServerContext server, BlockPos middle) {
		int centreChunkX = middle.getX() >> 4;
		int centreChunkZ = middle.getZ() >> 4;
		server.runCommand("forceload add "
				+ ((centreChunkX - RADIUS_IN_CHUNKS) << 4) + " "
				+ ((centreChunkZ - RADIUS_IN_CHUNKS) << 4) + " "
				+ ((((centreChunkX + RADIUS_IN_CHUNKS) << 4) + 15)) + " "
				+ ((((centreChunkZ + RADIUS_IN_CHUNKS) << 4) + 15)));
		server.waitFor(minecraftServer -> {
			ServerLevel level = minecraftServer.overworld();
			for (int x = -RADIUS_IN_CHUNKS; x <= RADIUS_IN_CHUNKS; x++) {
				for (int z = -RADIUS_IN_CHUNKS; z <= RADIUS_IN_CHUNKS; z++) {
					if (level.getChunkSource().getChunkNow(centreChunkX + x, centreChunkZ + z) == null) {
						return false;
					}
				}
			}
			return true;
		});
	}

	/** How many of each of the six species are standing in the generated patch. */
	private static Map<String, Integer> countSpecies(TestDedicatedServerContext server,
			BlockPos middle) {
		Map<String, Integer> standing = new LinkedHashMap<>();
		for (Map.Entry<String, EntityType<?>> species : SPECIES.entrySet()) {
			standing.put(species.getKey(), count(server, middle, species.getValue()));
		}
		return standing;
	}

	/**
	 * Every mob standing in the generated patch that is <em>not</em> one of the six, tallied by
	 * type. Counting by exclusion rather than naming a species keeps the control honest: whatever
	 * the biome happens to have produced, none of it is the mod's business.
	 */
	private static Map<String, Integer> countUnsold(TestDedicatedServerContext server,
			BlockPos middle) {
		return server.computeOnServer(minecraftServer -> {
			ServerLevel level = minecraftServer.overworld();
			Map<String, Integer> tally = new LinkedHashMap<>();
			for (Entity entity : level.getEntitiesOfClass(Mob.class, patch(middle))) {
				if (SPECIES.containsValue(entity.getType())) {
					continue;
				}
				String name = EntityType.getKey(entity.getType()).toString();
				tally.merge(name, 1, Integer::sum);
			}
			return tally;
		});
	}

	/** How many of one entity type are standing in the generated patch. */
	private static int count(TestDedicatedServerContext server, BlockPos middle,
			EntityType<?> type) {
		return server.computeOnServer(minecraftServer -> {
			ServerLevel level = minecraftServer.overworld();
			int found = 0;
			for (Entity entity : level.getEntitiesOfClass(Mob.class, patch(middle))) {
				if (entity.getType() == type) {
					found++;
				}
			}
			return found;
		});
	}

	/** The block-counting control: whether the patch generated any terrain at all. */
	private static int countGround(TestDedicatedServerContext server, BlockPos middle) {
		int centreChunkX = middle.getX() >> 4;
		int centreChunkZ = middle.getZ() >> 4;
		return server.computeOnServer(minecraftServer -> {
			ServerLevel level = minecraftServer.overworld();
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
			int found = 0;
			for (int chunkX = -RADIUS_IN_CHUNKS; chunkX <= RADIUS_IN_CHUNKS; chunkX++) {
				for (int chunkZ = -RADIUS_IN_CHUNKS; chunkZ <= RADIUS_IN_CHUNKS; chunkZ++) {
					LevelChunk chunk = level.getChunkSource()
							.getChunkNow(centreChunkX + chunkX, centreChunkZ + chunkZ);
					if (chunk == null) {
						throw new AssertionError("Chunk " + (centreChunkX + chunkX) + ","
								+ (centreChunkZ + chunkZ) + " went away before it could be counted");
					}
					for (int x = 0; x < 16; x++) {
						for (int z = 0; z < 16; z++) {
							// One block below the surface on purpose: the top of a meadow is a
							// grass block or the plant standing on it, and neither is in
							// BlockTags.DIRT. The dirt underneath is.
							int surface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE,
									((centreChunkX + chunkX) << 4) + x,
									((centreChunkZ + chunkZ) << 4) + z);
							pos.set(((centreChunkX + chunkX) << 4) + x, surface - 1,
									((centreChunkZ + chunkZ) << 4) + z);
							if (chunk.getBlockState(pos).is(BlockTags.DIRT)) {
								found++;
							}
						}
					}
				}
			}
			return found;
		});
	}

	/** The box the counts are taken over: the whole force-loaded patch, top to bottom. */
	private static AABB patch(BlockPos middle) {
		int centreChunkX = middle.getX() >> 4;
		int centreChunkZ = middle.getZ() >> 4;
		return new AABB(
				(centreChunkX - RADIUS_IN_CHUNKS) << 4, -64,
				(centreChunkZ - RADIUS_IN_CHUNKS) << 4,
				(((centreChunkX + RADIUS_IN_CHUNKS) << 4) + 16), 320,
				(((centreChunkZ + RADIUS_IN_CHUNKS) << 4) + 16));
	}

	/**
	 * Photographs the patch from the same camera every time, aimed at a spot chosen from the data
	 * rather than at a fixed offset from the biome's middle.
	 *
	 * <p>The counts are the proof; this is so a human can see what the counts are describing. That
	 * only works if the picture is of open ground, though, and the middle of a biome is as likely
	 * to be a cliff face or the far side of a lake as it is to be a field. So the camera is aimed
	 * at the thickest cluster of animals in the patch when there are any, and at the flattest open
	 * ground in it when there are not — which frames both halves of the pair identically.
	 */
	private static void look(ClientGameTestContext context, TestDedicatedServerContext server,
			TestDedicatedServerConnection connection, BlockPos middle, String name) {
		BlockPos cluster = thickestCluster(server, middle);
		BlockPos subject = cluster != null ? cluster : flattestOpenGround(server, middle);
		int cameraX = subject.getX() - CAMERA_BACK;
		int cameraZ = subject.getZ() - CAMERA_BACK;
		// Above whichever is higher, the subject or the ground the camera itself stands over. A
		// spectator inside a hillside photographs the inside of the hillside, which is how the
		// first version of this ended up with two pictures of dirt.
		int cameraY = server.computeOnServer(minecraftServer -> Math.max(
				subject.getY() + CAMERA_UP,
				minecraftServer.overworld()
						.getHeight(Heightmap.Types.MOTION_BLOCKING, cameraX, cameraZ) + CAMERA_UP));

		LOGGER.info("Photographing {} from {},{},{} aimed at {} ({})", name, cameraX, cameraY,
				cameraZ, subject, cluster != null ? "a herd" : "open ground");
		// Spectator, or the player simply falls out of the sky and the picture is of whatever they
		// landed in. A spectator stays exactly where they are put.
		server.runCommand("gamemode spectator Player0");
		server.runCommand("tp Player0 " + cameraX + " " + cameraY + " " + cameraZ
				+ " facing " + subject.getX() + " " + subject.getY() + " " + subject.getZ());
		connection.waitForChunksRender();
		// Long enough for the join toasts — the chat-signing warning and the social-interactions
		// hint — to expire, so the picture is of the world and not of two notification boxes.
		context.waitTicks(140);
		context.takeScreenshot(name);
	}

	/**
	 * The animal in the patch with the most company inside {@link #CLUSTER_RADIUS} blocks, or null
	 * if the patch has none of the six in it.
	 */
	private static BlockPos thickestCluster(TestDedicatedServerContext server, BlockPos middle) {
		return server.computeOnServer(minecraftServer -> {
			ServerLevel level = minecraftServer.overworld();
			List<Entity> ours = new ArrayList<>();
			for (Entity entity : level.getEntitiesOfClass(Mob.class, patch(middle))) {
				if (SPECIES.containsValue(entity.getType())) {
					ours.add(entity);
				}
			}
			Entity best = null;
			int bestCompany = -1;
			for (Entity candidate : ours) {
				int company = 0;
				for (Entity other : ours) {
					if (other.blockPosition().closerThan(candidate.blockPosition(), CLUSTER_RADIUS)) {
						company++;
					}
				}
				if (company > bestCompany) {
					bestCompany = company;
					best = candidate;
				}
			}
			return best == null ? null : best.blockPosition();
		});
	}

	/**
	 * The most level piece of dry land in the patch: the sampled column whose neighbours differ
	 * least in height. Open field photographs as an empty field; a hillside photographs as a wall of
	 * dirt, which says nothing about whether anything is standing on it.
	 */
	private static BlockPos flattestOpenGround(TestDedicatedServerContext server, BlockPos middle) {
		int centreChunkX = middle.getX() >> 4;
		int centreChunkZ = middle.getZ() >> 4;
		int from = (centreChunkX - RADIUS_IN_CHUNKS) << 4;
		int fromZ = (centreChunkZ - RADIUS_IN_CHUNKS) << 4;
		int span = ((RADIUS_IN_CHUNKS * 2) + 1) * 16;

		return server.computeOnServer(minecraftServer -> {
			ServerLevel level = minecraftServer.overworld();
			BlockPos best = null;
			int bestSpread = Integer.MAX_VALUE;
			for (int x = from + 16; x < from + span - 16; x += 8) {
				for (int z = fromZ + 16; z < fromZ + span - 16; z += 8) {
					int here = groundTop(level, x, z);
					if (!isField(level, x, here, z)) {
						continue;
					}
					int spread = 0;
					for (int dx = -12; dx <= 12; dx += 6) {
						for (int dz = -12; dz <= 12; dz += 6) {
							int near = groundTop(level, x + dx, z + dz);
							spread += Math.abs(near - here);
							// A lake is perfectly level and would otherwise win every time, so a
							// neighbour that is not dry land counts as rough ground.
							if (!isField(level, x + dx, near, z + dz)) {
								spread += 8;
							}
						}
					}
					if (spread < bestSpread) {
						bestSpread = spread;
						best = new BlockPos(x, here + 1, z);
					}
				}
			}
			if (best == null) {
				LOGGER.warn("No dry level ground found in the patch around {}; photographing its"
						+ " middle instead", middle);
				return middle;
			}
			return best;
		});
	}

	/**
	 * Whether this column's top block is field — plain grass, the thing an animal stands on.
	 *
	 * <p>A grass block, not {@code BlockTags.DIRT}: in 26.3 that tag is only dirt, coarse dirt and
	 * rooted dirt, so asking it about the top of a meadow answers no everywhere and every column in
	 * the patch gets skipped.
	 */
	private static boolean isField(ServerLevel level, int x, int y, int z) {
		return level.getBlockState(new BlockPos(x, y, z)).is(Blocks.GRASS_BLOCK);
	}

	/**
	 * The y of the topmost block of real ground in a column.
	 *
	 * <p>Not {@code WORLD_SURFACE}, which counts the tall grass and flowers standing on the ground
	 * as the ground — on plains that is most of the map, and a check for "is this dirt" run against
	 * it says no everywhere.
	 */
	private static int groundTop(ServerLevel level, int x, int z) {
		return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
	}

	// --- plumbing --------------------------------------------------------------------------

	private static Map<String, EntityType<?>> species() {
		Map<String, EntityType<?>> map = new LinkedHashMap<>();
		map.put("cow", EntityTypes.COW);
		map.put("pig", EntityTypes.PIG);
		map.put("sheep", EntityTypes.SHEEP);
		map.put("chicken", EntityTypes.CHICKEN);
		map.put("horse", EntityTypes.HORSE);
		map.put("wolf", EntityTypes.WOLF);
		return map;
	}

	private static void lockEverySpecies(TestDedicatedServerContext server) {
		for (String id : UNLOCK_IDS) {
			server.runCommand("mhr lock " + id);
		}
	}

	private static void unlockEverySpecies(TestDedicatedServerContext server) {
		for (String id : UNLOCK_IDS) {
			server.runCommand("mhr unlock " + id);
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
