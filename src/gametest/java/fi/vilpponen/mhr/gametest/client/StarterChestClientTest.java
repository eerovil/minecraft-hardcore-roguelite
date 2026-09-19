package fi.vilpponen.mhr.gametest.client;

import fi.vilpponen.mhr.UnlockState;
import fi.vilpponen.mhr.starter.RunStart;
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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The half of the starter chest that only a real client can answer: joining a fresh run is what
 * puts the chest there, once, and joining again does not do it twice.
 *
 * <p>{@link fi.vilpponen.mhr.gametest.server.StarterChestGameTest} covers what goes in the chest
 * and how many chests that takes, by calling the delivery itself. It cannot cover this half,
 * because the trigger is a player arriving in the world and Fabric's server GameTests have no
 * player to arrive. So this one builds a real dedicated server, buys the starter items before
 * anybody is connected — which is when a player would have bought them, between runs — and then
 * connects a real client and looks at what is standing next to them.
 *
 * <p>Three servers are built rather than one, because each one is a *run*: the harness deletes the
 * world before it starts a server, and the flag that says "this run has had its chest" lives in the
 * world's own save while the purchases live outside it. That is exactly the difference between
 * rejoining and starting over, and no shortcut can imitate it.
 *
 * <p>Which is also why the purchases are made once, before the first run, and never again. The
 * second run buys nothing: it asserts the three are still owned and then expects its chest, so a
 * change that reset the unlocks along with the world would fail here rather than quietly re-buying
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
		// Run one: the purchases are made while nobody is connected, and then somebody arrives.
		try (TestDedicatedServerContext server = context.worldBuilder().createServer()) {
			own(server, PURCHASES);
			boolean grantedBeforeAnyoneJoined = alreadyGranted(server);

			try (TestDedicatedServerConnection connection = server.connect()) {
				connection.waitForChunksRender();

				scenario(context, "first-join-of-a-run-places-the-starter-chest",
						() -> firstJoinPlacesTheChest(context, server, connection,
								grantedBeforeAnyoneJoined));
				scenario(context, "the-chest-opens-and-shows-what-was-bought",
						() -> theChestOpens(context, server, connection));
			}

			scenario(context, "a-reconnect-does-not-place-a-second-chest",
					() -> reconnectGivesNoSecondChest(context, server));
		}

		// Run two: a world that has never been played, and deliberately nothing bought here. The
		// purchases have to be the ones run one was given, or the scenario proves nothing.
		try (TestDedicatedServerContext server = context.worldBuilder().createServer()) {
			scenario(context, "a-genuinely-new-run-gets-its-starter-chest-again",
					() -> aNewRunGrantsAgain(context, server));
		}

		// Run three: the control. Nothing bought, so there must be nothing to find.
		try (TestDedicatedServerContext server = context.worldBuilder().createServer()) {
			own(server, List.of());
			scenario(context, "nothing-bought-means-no-chest-on-join",
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
	 * Joining a fresh run puts the chest next to the player, holding what they bought.
	 *
	 * <p>Nothing in this scenario asks the mod to place anything. The player connects the way a
	 * player connects, and the assertions are about blocks that are either standing there
	 * afterwards or are not.
	 */
	private void firstJoinPlacesTheChest(ClientGameTestContext context,
			TestDedicatedServerContext server, TestDedicatedServerConnection connection,
			boolean grantedBeforeAnyoneJoined) {
		check(!grantedBeforeAnyoneJoined,
				"a world nobody has joined yet must not already be marked as having had its chest");

		BlockPos player = playerPosition(server, connection);
		List<BlockPos> chests = chestsNear(server, player);
		LOGGER.info("Player at {}, chests near: {}", player, chests);

		check(chests.size() == 1, "joining a fresh run with three starter items bought must leave"
				+ " exactly one chest next to the player, and there are " + chests.size()
				+ " at " + chests);
		chest = chests.getFirst();
		check(chest.closerThan(player, 6.0), "the chest must be next to the player at " + player
				+ ", and it is at " + chest);
		check(alreadyGranted(server),
				"after the chest is given the run must be marked as having had it, and it is not");

		checkContents(server, chest, EXPECTED);
		check(efficiencyOf(server, chest) == 3,
				"the enchanted pickaxe must arrive through the join with its enchantment, and the"
						+ " one in the chest has Efficiency " + efficiencyOf(server, chest));

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
	 * second chest whether or not the flag says so.
	 */
	private void reconnectGivesNoSecondChest(
			ClientGameTestContext context, TestDedicatedServerContext server) {
		check(chest != null, "no chest was found on the first join, so there is nothing to compare"
				+ " a reconnect against");

		try (TestDedicatedServerConnection connection = server.connect()) {
			connection.waitForChunksDownload();
			BlockPos player = playerPosition(server, connection);
			List<BlockPos> chests = chestsNear(server, player);
			LOGGER.info("After reconnect: player at {}, chests near: {}", player, chests);

			check(chests.size() == 1, "coming back to the same run must not hand out another chest,"
					+ " and there are now " + chests.size() + " at " + chests);
			check(chests.getFirst().equals(chest),
					"the chest after a reconnect should be the one from the first join at " + chest
							+ ", and it is at " + chests.getFirst());
			checkContents(server, chest, EXPECTED);
		}
	}

	/**
	 * A new world is a new run, and the purchases are permanent, so the chest comes back.
	 *
	 * <p>Nothing is bought in this run on purpose. The purchases are the ones run one was given,
	 * still owned across a world that was deleted and a server that was replaced, which is the
	 * whole point of keeping unlocks out of the save — and re-buying them here would make the
	 * scenario pass just as happily if they had been lost.
	 */
	private void aNewRunGrantsAgain(
			ClientGameTestContext context, TestDedicatedServerContext server) {
		check(!alreadyGranted(server),
				"a world the harness has just made must not be carrying the last run's flag");
		for (String id : PURCHASES) {
			check(owns(server, id), "the purchases are permanent and must outlive the run that used"
					+ " them, and " + id + " is not owned any more in the new run");
		}

		try (TestDedicatedServerConnection connection = server.connect()) {
			connection.waitForChunksRender();
			BlockPos player = playerPosition(server, connection);
			List<BlockPos> chests = chestsNear(server, player);
			LOGGER.info("New run: player at {}, chests near: {}", player, chests);

			check(chests.size() == 1, "a new run must get its starter chest again, and there are "
					+ chests.size() + " chests near the player at " + player);
			checkContents(server, chests.getFirst(), EXPECTED);
			check(efficiencyOf(server, chests.getFirst()) == 3,
					"the enchanted pickaxe must come back enchanted in a new run too");
		}
	}

	/**
	 * The control. With nothing bought there is no chest at all, which is what stops every
	 * scenario above from passing for the wrong reason — a mod that put a chest down on every join
	 * would look identical until this one is asked.
	 */
	private void nothingBoughtMeansNoChest(
			ClientGameTestContext context, TestDedicatedServerContext server) {
		try (TestDedicatedServerConnection connection = server.connect()) {
			connection.waitForChunksRender();
			BlockPos player = playerPosition(server, connection);
			List<BlockPos> chests = chestsNear(server, player);
			LOGGER.info("Nothing bought: player at {}, chests near: {}", player, chests);

			check(chests.isEmpty(), "with nothing bought, joining must leave no chest at all, and"
					+ " there are " + chests.size() + " at " + chests);
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

	private static boolean alreadyGranted(TestDedicatedServerContext server) {
		return server.computeOnServer(minecraftServer ->
				RunStart.alreadyGranted(minecraftServer.overworld()));
	}

	/** Every chest block standing near a point, both halves of a double one counted separately. */
	private static List<BlockPos> chestsNear(TestDedicatedServerContext server, BlockPos middle) {
		return server.computeOnServer(minecraftServer -> {
			ServerLevel level = minecraftServer.overworld();
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
