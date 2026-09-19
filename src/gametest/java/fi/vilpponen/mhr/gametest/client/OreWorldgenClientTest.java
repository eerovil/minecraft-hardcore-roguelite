package fi.vilpponen.mhr.gametest.client;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldBuilder;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The ore unlocks against real terrain, generated for real.
 *
 * <p>{@code OreFeatureTest} runs the ore features by hand in a box of stone, which is fast and says
 * nothing about the world. This says the other half: with a fixed seed and a fixed patch of land
 * that no player has ever been to, what actually comes out of the ground. It is the only way to
 * reach the deep iron and copper veins, which are not a feature at all — they are a material rule
 * applied while a chunk's blocks are chosen, so no {@code place feature} can trigger one.
 *
 * <p>Three worlds are generated from the same seed and scanned at the same coordinates: one with
 * every ore locked, one with two of them unlocked, one with all of them unlocked. Same seed and
 * same coordinates is what makes the three counts comparable, and the surface blocks — which no ore
 * feature and no vein can touch — are counted too, so a run that quietly generated *different*
 * terrain fails rather than reads as a working feature.
 *
 * <p>It is a client game test for one reason only: the client harness's world builder is the thing
 * that can start a dedicated server on a seed we choose. No client ever connects, and nothing here
 * looks at a screen.
 */
public class OreWorldgenClientTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("mhr-gametest");

	/** Any fixed seed would do. This one is fixed, which is the whole point. */
	private static final String SEED = "hardcore-roguelite-ore-scan";

	/**
	 * The patch that gets scanned: 12x12 chunks, far enough from spawn that the server has not
	 * generated any of it before the unlocks are set.
	 */
	private static final int SCAN_CHUNK_X = 120;
	private static final int SCAN_CHUNK_Z = 120;
	private static final int SCAN_CHUNKS = 12;

	/** One ore, and every block it can be in the ground as — raw blocks included. */
	private record OreCase(String name, String unlock, List<Block> blocks) {}

	private static final List<OreCase> ORES = List.of(
			new OreCase("coal", "world.ore.coal",
					List.of(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE)),
			new OreCase("iron", "world.ore.iron",
					List.of(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE, Blocks.RAW_IRON_BLOCK)),
			new OreCase("copper", "world.ore.copper",
					List.of(Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE, Blocks.RAW_COPPER_BLOCK)),
			new OreCase("gold", "world.ore.gold",
					List.of(Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE)),
			new OreCase("redstone", "world.ore.redstone",
					List.of(Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE)),
			new OreCase("lapis", "world.ore.lapis",
					List.of(Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE)),
			new OreCase("diamond", "world.ore.diamond",
					List.of(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE)));

	/**
	 * Ore features nobody sells: they must be in the ground whatever the unlocks say.
	 *
	 * <p>Only their presence is asserted, never their exact count. An ore vein may replace andesite
	 * or an emerald placed before it — {@code stone_ore_replaceables} is every stone variant — so
	 * the same patch really does hold a little less andesite once seven ores are digging into it.
	 */
	private static final List<Block> UNRELATED_FEATURES = List.of(
			Blocks.ANDESITE, Blocks.DIORITE, Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE);

	/**
	 * The one block that says two scans are the same world.
	 *
	 * <p>Bedrock is laid down by terrain generation from the seed alone. No feature replaces it, no
	 * vein reaches it and nothing ticks it, so two scans of the same seed hold exactly as much of
	 * it. Everything else drifts: grass spreads, sand falls, water flows, and every stone variant
	 * is something an ore is allowed to replace.
	 */
	private static final List<Block> BEDROCK = List.of(Blocks.BEDROCK);

	private static final Set<Block> COUNTED = counted();

	private final List<String> failures = new ArrayList<>();

	@Override
	public void runTest(ClientGameTestContext context) {
		Scan locked = scan(context, "every-ore-locked", Set.of());
		Scan mixed = scan(context, "iron-and-diamond-unlocked",
				Set.of("world.ore.iron", "world.ore.diamond"));
		Scan unlocked = scan(context, "every-ore-unlocked", allOreUnlocks());

		// The harness insists a client game test ends on the title screen. Every other test gets
		// there by disconnecting; nothing here ever connects, so the client is still sitting on the
		// world-creation screen the world builder used to make the last world.
		context.setScreen(TitleScreen::new);

		scenario("the-three-scans-generated-the-same-world", () -> sameWorld(locked, mixed, unlocked));

		for (OreCase ore : ORES) {
			scenario("locked-" + ore.name() + "-is-missing-from-fresh-terrain",
					() -> check(locked.count(ore.blocks()) == 0,
							"a locked " + ore.name() + " must not be in fresh terrain, but the scan"
									+ " found " + locked.describe(ore.blocks())));
			scenario("unlocked-" + ore.name() + "-is-back-in-fresh-terrain",
					() -> check(unlocked.count(ore.blocks()) > 0,
							"an unlocked " + ore.name() + " must be in fresh terrain, but the scan"
									+ " found none of " + ore.blocks()));
		}

		scenario("mixed-state-restores-only-what-was-bought", () -> mixedState(mixed, unlocked));
		scenario("large-iron-and-copper-veins-follow-their-unlock",
				() -> largeVeins(locked, mixed, unlocked));
		scenario("features-we-do-not-sell-are-untouched", () -> unrelatedFeatures(locked, unlocked));

		if (!failures.isEmpty()) {
			throw new AssertionError(failures.size() + " ore-worldgen scenario(s) failed:\n  "
					+ String.join("\n  ", failures));
		}
		LOGGER.info("All ore-worldgen scenarios passed.");
	}

	// --- the scenarios ---------------------------------------------------------------------

	/**
	 * The control that makes the rest comparable: all three worlds hold exactly the same bedrock, so
	 * the same seed really did give the same terrain and the ore counts below differ because of the
	 * unlocks and nothing else.
	 */
	private void sameWorld(Scan locked, Scan mixed, Scan unlocked) {
		long bedrock = locked.count(BEDROCK);
		check(bedrock > 0, "the scan should have found terrain at all, but there is no bedrock in "
				+ SCAN_CHUNKS + "x" + SCAN_CHUNKS + " chunks");
		check(mixed.count(BEDROCK) == bedrock && unlocked.count(BEDROCK) == bedrock,
				"the same seed must give the same terrain, but the bedrock came out as "
						+ locked.describe(BEDROCK) + " / " + mixed.describe(BEDROCK) + " / "
						+ unlocked.describe(BEDROCK));
	}

	/** Two ores bought, five not: exactly the two are in the ground. */
	private void mixedState(Scan mixed, Scan unlocked) {
		for (OreCase ore : ORES) {
			boolean bought = ore.name().equals("iron") || ore.name().equals("diamond");
			if (bought) {
				check(mixed.count(ore.blocks()) > 0, "buying " + ore.name()
						+ " must put it in the ground even while five other ores are locked");
			} else {
				check(mixed.count(ore.blocks()) == 0, "buying iron and diamond must not bring "
						+ ore.name() + " back, but the scan found " + mixed.describe(ore.blocks()));
			}
		}
	}

	/**
	 * The deep veins. Raw iron and raw copper blocks only ever come from a vein, so counting them
	 * counts veins and nothing else — and a vein is the half of the feature no {@code place feature}
	 * can reach.
	 */
	private void largeVeins(Scan locked, Scan mixed, Scan unlocked) {
		List<Block> rawIron = List.of(Blocks.RAW_IRON_BLOCK);
		List<Block> rawCopper = List.of(Blocks.RAW_COPPER_BLOCK);

		check(unlocked.count(rawIron) > 0, "the scanned patch must contain an iron vein with the ore"
				+ " unlocked, or this scenario proves nothing — no raw iron block in "
				+ SCAN_CHUNKS + "x" + SCAN_CHUNKS + " chunks");
		check(unlocked.count(rawCopper) > 0, "the scanned patch must contain a copper vein with the"
				+ " ore unlocked, or this scenario proves nothing — no raw copper block in "
				+ SCAN_CHUNKS + "x" + SCAN_CHUNKS + " chunks");

		check(locked.count(rawIron) == 0,
				"a locked iron must take the whole vein with it, but the scan found "
						+ locked.describe(rawIron));
		check(locked.count(rawCopper) == 0,
				"a locked copper must take the whole vein with it, but the scan found "
						+ locked.describe(rawCopper));

		// A vein is decided while the chunk's blocks are chosen, long before any ore feature runs,
		// and a block belongs to at most one vein. So buying iron alone has to give back exactly the
		// veins a fully unlocked world has — not merely some.
		check(mixed.count(rawIron) == unlocked.count(rawIron),
				"buying iron must bring back the same veins vanilla would have put there, raw blocks"
						+ " and all, but the scan found " + mixed.describe(rawIron) + " against "
						+ unlocked.describe(rawIron));
		check(mixed.count(rawCopper) == 0, "leaving copper locked must leave its veins out even"
				+ " while iron's are back, but the scan found " + mixed.describe(rawCopper));
	}

	/** Emerald, andesite and diorite are not sold, so locking every ore must not touch them. */
	private void unrelatedFeatures(Scan locked, Scan unlocked) {
		check(unlocked.count(UNRELATED_FEATURES) > 0,
				"the scanned patch should contain some andesite, diorite or emerald to compare");
		check(locked.count(UNRELATED_FEATURES) > 0,
				"locking every ore must leave the features we do not sell in the ground, but the"
						+ " scan found none of " + UNRELATED_FEATURES);
	}

	// --- generating and counting a world ----------------------------------------------------

	/**
	 * Starts a dedicated server on the fixed seed, sets exactly these unlocks, generates the scan
	 * patch and counts it.
	 *
	 * <p>The unlocks are set before a single chunk of the patch exists, which is what makes the
	 * scan honest: worldgen decides an ore when its chunk is generated, so land that was already
	 * there would answer for whatever the unlocks were then.
	 */
	private Scan scan(ClientGameTestContext context, String name, Set<String> unlocks) {
		LOGGER.info("=== generating world {} with unlocks {} ===", name, unlocks);
		Map<Block, Long> counts = new TreeMap<>(
				(left, right) -> id(left).compareTo(id(right)));

		Properties properties = new Properties();
		properties.setProperty("level-seed", SEED);
		properties.setProperty("level-name", "ore-scan");
		// Generating a hundred chunks in one go is exactly the kind of long tick the watchdog
		// exists to kill, and here it is the test doing its job rather than the server hanging.
		properties.setProperty("max-tick-time", "-1");
		// A port of its own. Nothing ever connects to these three servers, and taking the harness's
		// usual one would mean racing whichever server the run before this had not quite let go of.
		properties.setProperty("server-port", "25577");
		properties.setProperty("view-distance", "4");
		properties.setProperty("simulation-distance", "4");

		// The harness's own "consistent settings" are a superflat world, which has no ores in it at
		// all and would make every count below a confident zero. So the world type is chosen here
		// instead, and with it goes the seed: an ordinary world, generated the ordinary way, the
		// same one every time.
		TestWorldBuilder builder = context.worldBuilder()
				.setUseConsistentSettings(false)
				.adjustSettings(settings -> {
					settings.setWorldType(new WorldCreationUiState.WorldTypeEntry(
							settings.getSettings().worldgenLoadContext()
									.lookupOrThrow(Registries.WORLD_PRESET)
									.getOrThrow(WorldPresets.NORMAL)));
					settings.setSeed(SEED);
					settings.setGenerateStructures(true);
				});

		try (TestDedicatedServerContext server = builder.createServer(properties)) {
			for (OreCase ore : ORES) {
				server.runCommand((unlocks.contains(ore.unlock()) ? "mhr unlock " : "mhr lock ")
						+ ore.unlock());
			}

			for (int x = SCAN_CHUNK_X; x < SCAN_CHUNK_X + SCAN_CHUNKS; x++) {
				final int chunkX = x;
				// A row at a time, so the server gets a tick in between and the harness keeps its
				// grip on it.
				server.runOnServer(minecraftServer -> {
					ServerLevel level = minecraftServer.overworld();
					for (int z = SCAN_CHUNK_Z; z < SCAN_CHUNK_Z + SCAN_CHUNKS; z++) {
						count(level.getChunk(chunkX, z, ChunkStatus.FULL, true), counts);
					}
				});
				context.waitTicks(1);
			}
		}

		Scan scan = new Scan(name, counts);
		LOGGER.info("=== scan {} ===\n{}", name, scan.table());
		return scan;
	}

	/** Adds one generated chunk to the running totals, a section's palette at a time. */
	private static void count(ChunkAccess chunk, Map<Block, Long> counts) {
		for (LevelChunkSection section : chunk.getSections()) {
			if (section.hasOnlyAir()) {
				continue;
			}
			section.getStates().count((state, howMany) -> {
				Block block = state.getBlock();
				if (COUNTED.contains(block)) {
					counts.merge(block, (long) howMany, Long::sum);
				}
			});
		}
	}

	/** What one world's scan found. */
	private record Scan(String name, Map<Block, Long> counts) {
		long count(List<Block> blocks) {
			long total = 0;
			for (Block block : blocks) {
				total += counts.getOrDefault(block, 0L);
			}
			return total;
		}

		/** The same numbers, spelled out per block: a count that is wrong has to be readable. */
		String describe(List<Block> blocks) {
			StringBuilder description = new StringBuilder();
			for (Block block : blocks) {
				long found = counts.getOrDefault(block, 0L);
				if (found > 0) {
					description.append(description.isEmpty() ? "" : ", ")
							.append(id(block)).append(" x").append(found);
				}
			}
			return description.isEmpty() ? "nothing" : description.toString();
		}

		String table() {
			StringBuilder table = new StringBuilder();
			for (Map.Entry<Block, Long> entry : counts.entrySet()) {
				table.append("    ").append(id(entry.getKey())).append(' ')
						.append(entry.getValue()).append('\n');
			}
			return table.isEmpty() ? "    (nothing counted)" : table.toString();
		}
	}

	// --- plumbing --------------------------------------------------------------------------

	private static Set<String> allOreUnlocks() {
		Set<String> unlocks = new LinkedHashSet<>();
		for (OreCase ore : ORES) {
			unlocks.add(ore.unlock());
		}
		return unlocks;
	}

	private static Set<Block> counted() {
		Set<Block> blocks = new HashSet<>();
		for (OreCase ore : ORES) {
			blocks.addAll(ore.blocks());
		}
		blocks.addAll(UNRELATED_FEATURES);
		blocks.addAll(BEDROCK);
		return Set.copyOf(blocks);
	}

	private static String id(Block block) {
		return BuiltInRegistries.BLOCK.getKey(block).toString();
	}

	private void scenario(String name, Runnable body) {
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
