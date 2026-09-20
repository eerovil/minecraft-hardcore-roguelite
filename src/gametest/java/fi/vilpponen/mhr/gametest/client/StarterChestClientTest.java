package fi.vilpponen.mhr.gametest.client;

import fi.vilpponen.mhr.UnlockState;
import fi.vilpponen.mhr.run.Lobby;
import fi.vilpponen.mhr.starter.StarterItems;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The half of the starter chest that only a real client can answer: a run beginning is what puts
 * the chest there, once, and nothing else does.
 *
 * <p>{@link fi.vilpponen.mhr.gametest.server.StarterChestGameTest} covers what goes in the chest
 * and how many chests that takes, by calling the delivery itself. It cannot cover this half,
 * because the trigger is a run starting around a real player and Fabric's server GameTests have
 * neither.
 *
 * <p>Three runs inside one dedicated server, because a run is no longer a world the harness has to
 * build: it is the loop starting again, and the worlds under it are replaced while the server keeps
 * going. That is exactly the boundary the "once per run" rule is about, and the run before each one
 * is deliberately left behind rather than reset by hand.
 *
 * <p>The purchases are made once, before the first run, and never again. The second run buys
 * nothing: it drops the loaded unlock state so the file has to answer for it, asserts the three are
 * still owned, and only then expects its chest. So a change that lost the unlocks along with the
 * world, or that stopped writing them to disk at all, fails here rather than quietly re-buying
 * them. The third run is the only one that touches the catalogue again, to clear it for the
 * nothing-bought control.
 *
 * <p>See {@code docs/dev-environment.md} for how to run this.
 */
public class StarterChestClientTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("mhr-gametest");

	/** How far from the player to go looking for chests. The chest lands within three. */
	private static final int SEARCH_RADIUS = 10;

	/** What this player bought between runs. Two stackables and the enchanted pickaxe. */
	private static final List<String> PURCHASES =
			List.of("starter.bread", "starter.torches", "starter.efficient_pickaxe");

	/** What those three have to turn into, in the chest, on the other side of the join. */
	private static final Map<String, Integer> EXPECTED = Map.of(
			"minecraft:bread", 16,
			"minecraft:torch", 32,
			"minecraft:diamond_pickaxe", 1);

	private final List<String> failures = new ArrayList<>();

	/** Where the first run's chest turned up, so the scenarios after it can go and look at it. */
	private BlockPos chest;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestDedicatedServerContext server = context.worldBuilder().createServer()) {
			own(server, PURCHASES);

			try (TestDedicatedServerConnection connection = server.connect()) {
				connection.waitForChunksRender();

				scenario(context, "the-lobby-has-no-starter-chest-before-a-run",
						() -> theLobbyHasNoChest(server, connection));

				TestRuns.start(server);
				connection.waitForChunksRender();

				scenario(context, "starting-a-run-places-the-starter-chest",
						() -> runStartPlacesTheChest(context, server, connection));
				scenario(context, "the-chest-opens-and-shows-what-was-bought",
						() -> theChestOpens(context, server, connection));
			}

			scenario(context, "a-reconnect-does-not-place-a-second-chest",
					() -> reconnectGivesNoSecondChest(context, server));

			// Run two. Nothing is bought here on purpose: these are run one's purchases, and they
			// have to come back off the disk rather than out of the process.
			forgetWhatIsInMemory(server);
			scenario(context, "a-second-run-gets-its-starter-chest-again",
					() -> aSecondRunGrantsAgain(context, server));

			// Run three: the control. Nothing bought, so there must be nothing to find.
			own(server, List.of());
			scenario(context, "nothing-bought-means-no-chest-when-a-run-starts",
					() -> nothingBoughtMeansNoChest(context, server));
		}

		if (!failures.isEmpty()) {
			throw new AssertionError(failures.size() + " starter-chest scenario(s) failed:\n  "
					+ String.join("\n  ", failures));
		}
		LOGGER.info("All starter-chest client scenarios passed.");
	}

	// --- the scenarios ---------------------------------------------------------------------

	/**
	 * Between runs there is no chest, because between runs there is no run to have started.
	 *
	 * <p>This is what stops the scenario after it from passing for the wrong reason: a mod that
	 * handed a chest to anybody who logged in would look identical one scenario later.
	 */
	private void theLobbyHasNoChest(
			TestDedicatedServerContext server, TestDedicatedServerConnection connection) {
		check(TestRuns.playerIsInTheLobby(server, connection),
				"with no run in progress the player belongs in the lobby, and they are in "
						+ TestRuns.playerDimension(server, connection));

		BlockPos player = playerPosition(server, connection);
		List<BlockPos> chests = chestsNear(server, Lobby.LEVEL, player);
		check(chests.isEmpty(), "joining between runs must not put a chest anywhere, and there are "
				+ chests.size() + " at " + chests);
	}

	/**
	 * Starting a run puts the chest at the run's spawn, holding what was bought.
	 *
	 * <p>Nothing in this scenario asks the mod to place anything. A run is started the way the
	 * lobby will start one, and the assertions are about blocks that are either standing there
	 * afterwards or are not.
	 */
	private void runStartPlacesTheChest(ClientGameTestContext context,
			TestDedicatedServerContext server, TestDedicatedServerConnection connection) {
		BlockPos spawn = TestRuns.runSpawn(server);
		List<BlockPos> chests = chestsNear(server, Level.OVERWORLD, spawn);
		LOGGER.info("Run spawn {}, chests near: {}", spawn, chests);

		check(chests.size() == 1, "starting a run with three starter items bought must leave"
				+ " exactly one chest at the run's spawn, and there are " + chests.size()
				+ " at " + chests);
		chest = chests.getFirst();
		check(chest.closerThan(spawn, 6.0), "the chest must be at the run spawn " + spawn
				+ ", and it is at " + chest);

		checkContents(server, chest, EXPECTED);
		check(efficiencyOf(server, chest) == 3,
				"the enchanted pickaxe must arrive through the run start with its enchantment, and"
						+ " the one in the chest has Efficiency " + efficiencyOf(server, chest));

		look(context, server, connection, chest);
		context.takeScreenshot("starter-chest-at-the-run-start");
	}

	/**
	 * The player opens it, the way a player would: aim at the chest, press use.
	 *
	 * <p>The contents were already read out of the block entity above. What this adds is that the
	 * chest is a chest a player can actually get at, and that the client is shown the same items —
	 * a chest placed inside a wall, or filled after the menu was built, would pass the check above
	 * and fail here.
	 */
	private void theChestOpens(ClientGameTestContext context, TestDedicatedServerContext server,
			TestDedicatedServerConnection connection) {
		check(chest != null, "no chest was found by the scenario before this one, so there is"
				+ " nothing to open");

		look(context, server, connection, chest);
		context.getInput().pressKey(options -> options.keyUse);
		context.waitForScreen(ContainerScreen.class);
		context.waitTicks(3);
		context.takeScreenshot("starter-chest-open");

		Map<String, Integer> onScreen = context.computeOnClient(client -> {
			ContainerScreen screen = (ContainerScreen) client.gui.screen();
			Map<String, Integer> seen = new LinkedHashMap<>();
			for (int slot = 0; slot < screen.getMenu().getContainer().getContainerSize(); slot++) {
				ItemStack stack = screen.getMenu().getSlot(slot).getItem();
				if (!stack.isEmpty()) {
					seen.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
							stack.getCount(), Integer::sum);
				}
			}
			return seen;
		});
		check(onScreen.equals(EXPECTED), "the open chest must show the player exactly what they"
				+ " bought: expected " + EXPECTED + " and the screen shows " + onScreen);

		context.getInput().pressKey(options -> options.keyInventory);
		context.waitFor(client -> client.gui.screen() == null);
	}

	/**
	 * Logging out and back in is not a new run, so it does not come with a second chest.
	 *
	 * <p>Counted as blocks in the world rather than as a flag, because a second grant would be a
	 * second chest whether or not any flag says so.
	 */
	private void reconnectGivesNoSecondChest(
			ClientGameTestContext context, TestDedicatedServerContext server) {
		check(chest != null, "no chest was found at the run start, so there is nothing to compare"
				+ " a reconnect against");

		try (TestDedicatedServerConnection connection = server.connect()) {
			connection.waitForChunksDownload();
			BlockPos spawn = TestRuns.runSpawn(server);
			List<BlockPos> chests = chestsNear(server, Level.OVERWORLD, spawn);
			LOGGER.info("After reconnect: spawn {}, chests near: {}", spawn, chests);

			check(chests.size() == 1, "coming back to the same run must not hand out another chest,"
					+ " and there are now " + chests.size() + " at " + chests);
			check(chests.getFirst().equals(chest),
					"the chest after a reconnect should be the one from the run start at " + chest
							+ ", and it is at " + chests.getFirst());
			checkContents(server, chest, EXPECTED);
		}
	}

	/**
	 * A new run is a new world, and the purchases are permanent, so the chest comes back.
	 *
	 * <p>Nothing is bought in this run on purpose. The purchases are the ones run one was given,
	 * still owned across a world that was deleted and a trip through the unlock file, which is the
	 * whole point of keeping unlocks out of the save — and re-buying them here would make the
	 * scenario pass just as happily if they had been lost.
	 */
	private void aSecondRunGrantsAgain(
			ClientGameTestContext context, TestDedicatedServerContext server) {
		for (String id : PURCHASES) {
			check(owns(server, id), "the purchases are permanent and must outlive the run that used"
					+ " them, and " + id + " is not owned any more");
		}

		TestRuns.end(server);
		TestRuns.start(server);

		try (TestDedicatedServerConnection connection = server.connect()) {
			connection.waitForChunksRender();
			BlockPos spawn = TestRuns.runSpawn(server);
			List<BlockPos> chests = chestsNear(server, Level.OVERWORLD, spawn);
			LOGGER.info("New run: spawn {}, chests near: {}", spawn, chests);

			check(chests.size() == 1, "a new run must get its starter chest again, and there are "
					+ chests.size() + " chests at the run spawn " + spawn);
			checkContents(server, chests.getFirst(), EXPECTED);
			check(efficiencyOf(server, chests.getFirst()) == 3,
					"the enchanted pickaxe must come back enchanted in a new run too");
		}
	}

	/**
	 * The control. With nothing bought there is no chest at all, which is what stops every
	 * scenario above from passing for the wrong reason — a mod that put a chest down at every run
	 * start would look identical until this one is asked.
	 */
	private void nothingBoughtMeansNoChest(
			ClientGameTestContext context, TestDedicatedServerContext server) {
		TestRuns.end(server);
		TestRuns.start(server);

		try (TestDedicatedServerConnection connection = server.connect()) {
			connection.waitForChunksRender();
			BlockPos spawn = TestRuns.runSpawn(server);
			List<BlockPos> chests = chestsNear(server, Level.OVERWORLD, spawn);
			LOGGER.info("Nothing bought: spawn {}, chests near: {}", spawn, chests);

			check(chests.isEmpty(), "with nothing bought, starting a run must leave no chest at all,"
					+ " and there are " + chests.size() + " at " + chests);
			context.takeScreenshot("starter-chest-nothing-bought");
		}
	}

	// --- looking at the world ----------------------------------------------------------------

	/**
	 * Stands the player two blocks from the chest and points them at it.
	 *
	 * <p>Two blocks and not three: the same position is used to photograph the chest and to reach
	 * it, and vanilla's reach is shorter than the three blocks the chest may be placed away.
	 */
	private static void look(ClientGameTestContext context, TestDedicatedServerContext server,
			TestDedicatedServerConnection connection, BlockPos chest) {
		server.runCommand("time set noon");
		server.runCommand("weather clear");
		server.runCommand("tp Player0 " + (chest.getX() + 0.5) + " " + chest.getY() + " "
				+ (chest.getZ() + 2.5));
		connection.waitForChunksRender();
		context.waitTicks(5);
		context.getInput().lookAt(chest);
		context.waitTicks(5);
	}

	// --- asking the server -------------------------------------------------------------------

	private static BlockPos playerPosition(
			TestDedicatedServerContext server, TestDedicatedServerConnection connection) {
		return server.computeOnServer(unused -> connection.getServerPlayer().blockPosition());
	}

	/** Every chest block standing near a point, both halves of a double one counted separately. */
	private static List<BlockPos> chestsNear(TestDedicatedServerContext server,
			ResourceKey<Level> dimension, BlockPos middle) {
		return server.computeOnServer(minecraftServer -> {
			ServerLevel level = minecraftServer.getLevel(dimension);
			List<BlockPos> found = new ArrayList<>();
			for (BlockPos pos : BlockPos.betweenClosed(
					middle.offset(-SEARCH_RADIUS, -SEARCH_RADIUS, -SEARCH_RADIUS),
					middle.offset(SEARCH_RADIUS, SEARCH_RADIUS, SEARCH_RADIUS))) {
				if (level.getBlockState(pos).is(Blocks.CHEST)) {
					found.add(pos.immutable());
				}
			}
			return found;
		});
	}

	/** What is in the chest at this position, item by item, both halves if it is a double one. */
	private static Map<String, Integer> contentsOf(
			TestDedicatedServerContext server, BlockPos pos) {
		return server.computeOnServer(minecraftServer -> {
			ServerLevel level = minecraftServer.overworld();
			Map<String, Integer> contents = new LinkedHashMap<>();
			for (BlockPos half : halves(level, pos)) {
				if (!(level.getBlockEntity(half) instanceof ChestBlockEntity chest)) {
					throw new AssertionError("No chest to read at " + half + ", the block there is "
							+ level.getBlockState(half));
				}
				for (int slot = 0; slot < chest.getContainerSize(); slot++) {
					ItemStack stack = chest.getItem(slot);
					if (!stack.isEmpty()) {
						contents.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
								stack.getCount(), Integer::sum);
					}
				}
			}
			return contents;
		});
	}

	/** The Efficiency level on the one diamond pickaxe in the chest, or 0 if there is none. */
	private static int efficiencyOf(TestDedicatedServerContext server, BlockPos pos) {
		return server.computeOnServer(minecraftServer -> {
			ServerLevel level = minecraftServer.overworld();
			for (BlockPos half : halves(level, pos)) {
				if (!(level.getBlockEntity(half) instanceof ChestBlockEntity chest)) {
					continue;
				}
				for (int slot = 0; slot < chest.getContainerSize(); slot++) {
					ItemEnchantments enchantments = chest.getItem(slot)
							.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
					for (var held : enchantments.keySet()) {
						if (held.is(Enchantments.EFFICIENCY)) {
							return enchantments.getLevel(held);
						}
					}
				}
			}
			return 0;
		});
	}

	private static List<BlockPos> halves(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (!state.is(Blocks.CHEST) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
			return List.of(pos);
		}
		return List.of(pos, ChestBlock.getConnectedBlockPos(pos, state));
	}

	private static void checkContents(
			TestDedicatedServerContext server, BlockPos pos, Map<String, Integer> expected) {
		Map<String, Integer> actual = contentsOf(server, pos);
		check(actual.equals(expected), "the chest at " + pos + " should hold " + expected
				+ " and it holds " + actual);
	}

	// --- the catalogue -----------------------------------------------------------------------

	/**
	 * Own exactly these starter items and no others.
	 *
	 * <p>Run before anything connects, which is both what a scenario needs — the chest is given on
	 * join, so a purchase made afterwards is too late — and what a player does, since between runs
	 * is when the shop is open.
	 */
	/**
	 * Drop the loaded unlocks so the next question about them is answered by the file.
	 *
	 * <p>This is the process boundary a real player crosses between runs and this harness does not.
	 * Its dedicated server runs inside the client's own process, so the unlock state that run one
	 * bought from is still the very same object in run two — and a purchase that was never written
	 * to {@code hardcore-roguelite-unlocks.json}, or a file that cannot be read back, would be
	 * invisible here. Called before anybody joins, so the grant that follows reads what the disk
	 * says and nothing else.
	 */
	private static void forgetWhatIsInMemory(TestDedicatedServerContext server) {
		server.runOnServer(unused -> UnlockState.reloadFromFile());
	}

	/** Does the player still own this, as the server sees it right now? */
	private static boolean owns(TestDedicatedServerContext server, String id) {
		return server.computeOnServer(unused -> UnlockState.get().isOwned(id));
	}

	private static void own(TestDedicatedServerContext server, List<String> ids) {
		for (String id : server.computeOnServer(unused -> StarterItems.ids())) {
			server.runCommand("mhr lock " + id);
		}
		for (String id : ids) {
			server.runCommand("mhr unlock " + id);
		}
	}

	// --- plumbing --------------------------------------------------------------------------

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
