package fi.vilpponen.mhr.gametest.client;

import com.mojang.blaze3d.platform.InputConstants;
import fi.vilpponen.mhr.gametest.mixin.ChatComponentAccessor;
import fi.vilpponen.mhr.run.Lobby;
import fi.vilpponen.mhr.run.LobbyIsland;
import fi.vilpponen.mhr.run.RunEvents;
import fi.vilpponen.mhr.run.RunPhase;
import fi.vilpponen.mhr.run.RunRecord;
import fi.vilpponen.mhr.shop.client.ShopScreen;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The lobby as a place: an island in the void, a block that opens the shop, and an edge that starts
 * the next run.
 *
 * <p>A client test because every claim in it is about a real player standing in a real dimension.
 * The island is not generated — the lobby's dimension file produces empty void — so "there is
 * something to stand on" is a claim about the mod, not about Minecraft, and the void jump cannot be
 * asked about at all without a player who can fall.
 *
 * <p>Each of the two things you can do here is asked with its own control, because both of them
 * would otherwise pass for the wrong reason:
 *
 * <ul>
 *   <li>the shop opens on the shop block, and <b>does not</b> open on the grass beside it — so it
 *       is that block and not any right-click that is the door;
 *   <li>walking off the island starts a run, and standing on it <b>does not</b> — so it is the fall
 *       and not merely being in the lobby that presses start.
 * </ul>
 *
 * <p>Two more scenarios ask what happens when the jump does <b>not</b> end in a run: one where the
 * lifecycle refuses before it has touched anything, and one where it fails after the jumper has
 * already been respawned into the half-built run. Both end in the same place — the player standing
 * on the island, unhurt, with the save saying something true — and the second is the one that
 * catches a watcher still holding the player object it started the tick with.
 *
 * <p>The player walks off the edge with the real movement key rather than being teleported into the
 * air. Being put in the void by a command would prove the watcher fires; it would not prove a
 * player can reach the void from where the game puts them, which is the whole of the feature.
 *
 * <p>See {@code docs/dev-environment.md} for how to run this.
 */
public class LobbyIslandClientTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("mhr-gametest");

	/** How far off the island's edge the void has to be empty for it to be an island at all. */
	private static final int CLEARANCE = 12;

	/** What the player is told when their jump could not start a run. From the lang file. */
	private static final String REFUSAL = "No run could be started";

	/**
	 * Make the next run fail on the player's way into it, after they have already been moved.
	 *
	 * <p>This is the only way to reach the case the fix is about. Crossing into a run is a respawn,
	 * so by the time this throws the player object the jump watcher was holding has been destroyed
	 * and replaced — and the failure path then respawns them a second time on the way back out. A
	 * watcher that kept the object it scanned with would be acting on a corpse.
	 *
	 * <p>{@link RunEvents} is a static registry shared by every server this client process drives,
	 * so the listener is registered once and does nothing at all unless this test has armed it.
	 */
	private static final AtomicBoolean FAIL_PLAYER_ENTRY = new AtomicBoolean();

	private static boolean entryFaultRegistered;

	private final List<String> failures = new ArrayList<>();

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestDedicatedServerContext server = context.worldBuilder().createServer()) {
			try (TestDedicatedServerConnection connection = server.connect()) {
				TestRuns.settleClient(context, connection);
				TestRuns.waitForPlayerInTheLobby(context, server, connection);
				TestPlayer player = new TestPlayer(context, server, connection);
				registerEntryFault(server);

				// Said out loud rather than hoped for: a dig proves nothing about survival rules
				// if the digger turns out to be in creative.
				server.runCommand("gamemode survival Player0");
				TestRuns.settle(server);

				scenario(context, "the-lobby-is-an-island-in-the-void",
						() -> theLobbyIsAnIsland(context, server, connection));
				scenario(context, "the-shop-block-opens-the-shop",
						() -> theShopBlockOpensTheShop(context, server, player));
				scenario(context, "an-ordinary-lobby-block-does-not-open-the-shop",
						() -> plainGroundOpensNothing(context, server, player));
				scenario(context, "the-island-cannot-be-dug-away",
						() -> theIslandCannotBeDugAway(context, server, player));
				scenario(context, "standing-on-the-island-starts-no-run",
						() -> standingStartsNothing(context, server));
				scenario(context, "a-refused-run-start-puts-the-jumper-back",
						() -> aRefusedStartPutsTheJumperBack(context, server, connection));
				scenario(context, "a-failure-after-the-jumper-moved-still-leaves-them-safe",
						() -> aLateFailureLeavesTheJumperSafe(context, server, connection));
				scenario(context, "walking-off-the-island-starts-a-run",
						() -> walkingOffStartsARun(context, server, connection));
				scenario(context, "the-same-dig-works-inside-a-run",
						() -> theSameDigWorksInARun(context, server, connection, player));
			}
		}

		if (!failures.isEmpty()) {
			throw new AssertionError(failures.size() + " lobby-island scenario(s) failed:\n  "
					+ String.join("\n  ", failures));
		}
		LOGGER.info("All lobby-island client scenarios passed.");
	}

	// --- the scenarios ---------------------------------------------------------------------

	/**
	 * Something underfoot, nothing under that, and nothing where the island stops.
	 *
	 * <p>Asked of the client as well as the server. A lobby the client has not been sent draws as
	 * empty void, which is indistinguishable from the island having failed to build — and this is
	 * the one test where "the player can see void" is also the expected answer somewhere, so both
	 * sides are asked about both kinds of position.
	 */
	private void theLobbyIsAnIsland(ClientGameTestContext context,
			TestDedicatedServerContext server, TestDedicatedServerConnection connection) {
		check(TestRuns.playerIsInTheLobby(server, connection),
				"a save nobody has played must start in the lobby, and the player is in "
						+ TestRuns.playerDimension(server, connection));

		BlockPos underfoot = Lobby.SPAWN.below();
		String floor = blockOnServer(server, underfoot);
		check(floor.contains("grass"), "the island's top layer must be under the spawn, and "
				+ underfoot + " holds " + floor);

		String beneath = blockOnServer(server, underfoot.below(CLEARANCE));
		check(beneath.contains("air"), "the island must be an island — " + CLEARANCE
				+ " blocks under it should be open void, and it holds " + beneath);

		BlockPos offTheEdge = underfoot.offset(CLEARANCE, 0, 0);
		String beside = blockOnServer(server, offTheEdge);
		check(beside.contains("air"), "the island must end — " + offTheEdge
				+ " is well past its edge and it holds " + beside);

		String shop = blockOnServer(server, LobbyIsland.SHOP_BLOCK);
		check(shop.contains("emerald"), "the shop block must stand on the island at "
				+ LobbyIsland.SHOP_BLOCK + ", and it holds " + shop);

		// The client's own copy. Without this the island could be a server-side fiction the player
		// is standing inside but cannot see.
		String drawnFloor = blockOnClient(context, underfoot);
		String drawnShop = blockOnClient(context, LobbyIsland.SHOP_BLOCK);
		LOGGER.info("The lobby as the client has it: underfoot {}, shop block {}",
				drawnFloor, drawnShop);
		check(drawnFloor.contains("grass") && drawnShop.contains("emerald"),
				"the client must have been sent the island: it has " + drawnFloor + " underfoot and "
						+ drawnShop + " where the shop block is");

		framePicture(context, server);
		context.takeScreenshot("lobby-island");
		endPicture(server);
	}

	/** Stand on the shop block, look down, right-click: the shop screen comes up. */
	private void theShopBlockOpensTheShop(ClientGameTestContext context,
			TestDedicatedServerContext server, TestPlayer player) {
		standOnAndLookDown(server, player, LobbyIsland.SHOP_BLOCK);

		// MOUSE_BUTTON_RIGHT is the use key while no screen is open. SDL numbers the buttons from
		// one, which is the same trap TestShop and TestPlayer document.
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
		context.waitForScreen(ShopScreen.class);
		context.waitTicks(3);
		context.takeScreenshot("lobby-shop-block-opened-the-shop");

		context.setScreen(() -> null);
		context.waitTicks(2);
		player.settle();
	}

	/**
	 * The control for the one above. The same click, two blocks away, opens nothing.
	 *
	 * <p>Without it, a shop that opened on any right-click anywhere in the lobby — or on joining,
	 * or on a tick — would pass the scenario before this one unchanged.
	 */
	private void plainGroundOpensNothing(ClientGameTestContext context,
			TestDedicatedServerContext server, TestPlayer player) {
		standOnAndLookDown(server, player, Lobby.SPAWN.below());

		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
		context.waitTicks(20);
		player.settle();

		String screen = context.computeOnClient(client ->
				client.gui.screen() == null ? "none" : client.gui.screen().getClass().getName());
		check(screen.equals("none"), "right-clicking the island's ordinary ground must open"
				+ " nothing, and it opened " + screen);
	}

	/**
	 * The island is not diggable. A survival player in the lobby has nothing but their hands, and
	 * grass comes up by hand in under a second — so this is the difference between a lobby and a
	 * hole in one, and the lobby is the world that is never deleted.
	 *
	 * <p>A real dig with the real mouse. Its positive control is
	 * {@link #theSameDigWorksInARun}: the same hands, the same block, the same three seconds of
	 * holding the button, inside a run — because "the block is still there" is exactly what a dig
	 * that never happened also looks like.
	 */
	private void theIslandCannotBeDugAway(ClientGameTestContext context,
			TestDedicatedServerContext server, TestPlayer player) {
		BlockPos underfoot = Lobby.SPAWN.below();
		standOnAndLookDown(server, player, underfoot);
		digUnderFoot(context, player);

		String after = blockOnServer(server, underfoot);
		check(after.contains("grass"), "the lobby's floor must survive a survival player digging at"
				+ " it, and after three seconds " + underfoot + " holds " + after);
	}

	/**
	 * The control for the one above: the same dig, in a run, takes the block away.
	 *
	 * <p>Run last, because it needs a run to be running. Without it, a lobby dig that silently
	 * failed to register — a missed aim, an input the harness swallowed — would read as protection
	 * working.
	 */
	private void theSameDigWorksInARun(ClientGameTestContext context,
			TestDedicatedServerContext server, TestDedicatedServerConnection connection,
			TestPlayer player) {
		check(TestRuns.phase(server) == RunPhase.RUNNING,
				"this control has to run inside a run, and the save says "
						+ TestRuns.record(server).describe());

		BlockPos target = server.computeOnServer(minecraftServer ->
				minecraftServer.getPlayerList().getPlayers().getFirst().blockPosition().below());
		server.runCommand("execute in " + Level.OVERWORLD.identifier() + " run setblock "
				+ target.getX() + " " + target.getY() + " " + target.getZ()
				+ " minecraft:grass_block");
		server.runCommand("execute in " + Level.OVERWORLD.identifier() + " run tp Player0 "
				+ (target.getX() + 0.5) + " " + (target.getY() + 1) + " " + (target.getZ() + 0.5)
				+ " 0 90");
		player.settle();

		digUnderFoot(context, player);

		String after = blockOnRunOverworld(server, target);
		check(!after.contains("grass"), "the same dig with the same hands must take an ordinary"
				+ " block away inside a run, and " + target + " still holds " + after
				+ " — so the lobby scenario before this one proved nothing");
	}

	/** Three seconds on the left button, aimed straight down. Long enough to break grass twice. */
	private static void digUnderFoot(ClientGameTestContext context, TestPlayer player) {
		// MOUSE_BUTTON_LEFT, not 0: 26.3 takes its input from SDL, which numbers buttons from one.
		context.getInput().holdMouseFor(InputConstants.MOUSE_BUTTON_LEFT, 60);
		context.waitTicks(5);
		player.settle();
	}

	/**
	 * The control for the void jump: being in the lobby is not falling out of it.
	 *
	 * <p>A watcher that started a run for anybody standing in the lobby would pass the scenario
	 * after this one and make the game unplayable, and nothing else here would notice.
	 */
	private void standingStartsNothing(
			ClientGameTestContext context, TestDedicatedServerContext server) {
		sendBackToSpawn(server);
		context.waitTicks(60);

		RunRecord record = TestRuns.record(server);
		check(record.phase() == RunPhase.LOBBY,
				"standing on the island for three seconds must start nothing, and the save says "
						+ record.describe());
		check(record.runId() == 0, "and no run id may have been spent: " + record.describe());
	}

	/**
	 * A jump the lifecycle refuses outright leaves the jumper standing on the island, told why.
	 *
	 * <p>The refusal is a save that cannot describe the nether, which `startRun` checks before it
	 * destroys anything — so nothing has moved and the player the watcher scanned with is still the
	 * player it has to catch. Falling on is the wrong answer: there is nothing down there but void
	 * damage, and the save is unchanged, so there is nothing to recover from either.
	 */
	private void aRefusedStartPutsTheJumperBack(ClientGameTestContext context,
			TestDedicatedServerContext server, TestDedicatedServerConnection connection) {
		sendBackToSpawn(server);
		RunRecord before = TestRuns.record(server);

		TestRuns.withNoNetherStem(server, () ->
				walkOffTheEdge(context, () -> wasToldOnTheClient(context, REFUSAL)));

		check(wasToldOnTheClient(context, REFUSAL), "a jumper whose run cannot start must be told"
				+ " so, and nothing saying '" + REFUSAL + "' reached the client");

		RunRecord after = TestRuns.record(server);
		check(after.phase() == RunPhase.LOBBY && after.runId() == before.runId(),
				"a start refused before anything is destroyed must leave the save exactly as it"
						+ " was — it was " + before.describe() + " and it is now " + after.describe());

		check(TestRuns.playerIsInTheLobby(server, connection),
				"the jumper must still be in the lobby, and they are in "
						+ TestRuns.playerDimension(server, connection));
		check(isOnTheIsland(server), "the jumper must have been put back on the island rather than"
				+ " left falling, and they are at " + playerPosition(server));
		check(TestRuns.playerHealth(server, connection) == 20.0F,
				"and they must not have been hurt doing it: they have "
						+ TestRuns.playerHealth(server, connection));
	}

	/**
	 * A run start that fails <b>after</b> the jumper has already been moved into it.
	 *
	 * <p>This is the case the jump watcher is easiest to get wrong in. Crossing into a run is a
	 * respawn and so is the failure path's evacuation, so between the watcher noticing the fall and
	 * the refusal coming back, the player object it scanned with has been destroyed and replaced
	 * twice. A watcher that reused it would be teleporting a corpse: the real player stays wherever
	 * the failure left them, and a dead entity gets moved about in the one dimension that is never
	 * rebuilt.
	 *
	 * <p>So the claims are about the live player, asked of the server by id and of the client by
	 * what it can see. The lobby drawing as empty void is what a broken lobby registration looks
	 * like from the inside.
	 */
	private void aLateFailureLeavesTheJumperSafe(ClientGameTestContext context,
			TestDedicatedServerContext server, TestDedicatedServerConnection connection) {
		sendBackToSpawn(server);
		int before = TestRuns.record(server).runId();

		FAIL_PLAYER_ENTRY.set(true);
		try {
			walkOffTheEdge(context, () -> TestRuns.record(server).runId() != before);
		} finally {
			FAIL_PLAYER_ENTRY.set(false);
		}

		TestRuns.waitForPhase(context, server, RunPhase.LOBBY);
		TestRuns.settleClient(context, connection);

		RunRecord after = TestRuns.record(server);
		check(after.runId() != before, "the jump must actually have tried to start a run — the"
				+ " record still says " + after.describe() + ", so the entry fault never fired and"
				+ " this scenario proved nothing");
		check(after.phase() == RunPhase.LOBBY && after.completedRuns() == 0,
				"a run that could not be entered is a run that never started, and the record says "
						+ after.describe());

		check(TestRuns.playerIsInTheLobby(server, connection),
				"the jumper must end up back in the lobby, and they are in "
						+ TestRuns.playerDimension(server, connection)
						+ " — players: " + TestRuns.describePlayers(server));
		check(isOnTheIsland(server), "and on the island rather than still falling through the"
				+ " void, and they are at " + playerPosition(server));
		check(TestRuns.playerHealth(server, connection) == 20.0F,
				"and unhurt, and they have " + TestRuns.playerHealth(server, connection));

		// The client's own copy of the lobby. A watcher that had moved the destroyed entity would
		// leave a live-looking registration behind, and the symptom of that is a player standing in
		// a lobby their client draws as nothing at all.
		String drawn = blockOnClient(context, Lobby.SPAWN.below());
		check(drawn.contains("grass"), "the jumper must be able to see the island they are standing"
				+ " on, and their client has " + drawn + " under them");
	}

	/**
	 * Walk north off the edge, fall, and land in a run.
	 *
	 * <p>The walk is the real forward key. The fall needs nothing: {@code startRun} runs to
	 * completion inside the tick that notices the jumper, so the player is in the new run's
	 * overworld before they could drop another block.
	 */
	private void walkingOffStartsARun(ClientGameTestContext context,
			TestDedicatedServerContext server, TestDedicatedServerConnection connection) {
		sendBackToSpawn(server);
		check(TestRuns.phase(server) == RunPhase.LOBBY,
				"the jump must start from a save between runs, and it says "
						+ TestRuns.record(server).describe());
		int before = TestRuns.record(server).runId();

		walkOffTheEdge(context, () -> TestRuns.phase(server) != RunPhase.LOBBY);
		check(TestRuns.phase(server) != RunPhase.LOBBY,
				"walking off the island must start the next run, and after eight seconds of"
						+ " walking the save still says " + TestRuns.record(server).describe()
						+ ", with the player at " + playerPosition(server));

		TestRuns.waitForPhase(context, server, RunPhase.RUNNING);
		TestRuns.settleClient(context, connection);

		RunRecord record = TestRuns.record(server);
		check(record.runId() > before, "the jump must have started a run of its own, and the record"
				+ " still says " + record.describe());

		String where = TestRuns.playerDimension(server, connection);
		check(where.equals(Level.OVERWORLD.identifier().toString()),
				"the jumper must come out of the fall in the run's overworld, and they are in "
						+ where);
		TestRuns.waitForClientIn(context, Level.OVERWORLD.identifier().toString());

		float health = TestRuns.playerHealth(server, connection);
		check(health == 20.0F, "a jump into the void is how a run starts, not damage — the player"
				+ " must arrive on full health and they have " + health);

		context.takeScreenshot("lobby-void-jump-started-a-run");
	}

	// --- plumbing --------------------------------------------------------------------------

	/**
	 * Put the player on top of a block, looking straight down at it.
	 *
	 * <p>The same aim {@link TestPlayer#openCraftingTable} uses and for the same reason: straight
	 * down at the block being stood on is the one aim nothing can get in front of, so a right-click
	 * either lands on that block or the test fails waiting — it can never quietly hit something
	 * else.
	 */
	private static void standOnAndLookDown(
			TestDedicatedServerContext server, TestPlayer player, BlockPos block) {
		server.runCommand("execute in " + Lobby.LEVEL.identifier() + " run tp Player0 "
				+ (block.getX() + 0.5) + " " + (block.getY() + 1) + " " + (block.getZ() + 0.5)
				+ " 0 90");
		player.settle();
	}

	/**
	 * Hold the real forward key until something has come of it, or give up after eight seconds.
	 *
	 * <p>Held in bursts rather than in one long press so the walk stops the moment the fall has
	 * been dealt with, instead of pressing forward into whatever the player has arrived in. What
	 * counts as dealt with is the caller's, because the three jump scenarios are each waiting for a
	 * different thing to happen.
	 */
	private static void walkOffTheEdge(ClientGameTestContext context, BooleanSupplier settled) {
		for (int burst = 0; burst < 8 && !settled.getAsBoolean(); burst++) {
			context.getInput().holdKeyFor(options -> options.keyUp, 20);
		}
	}

	/** Is the player standing on the island rather than somewhere under it? */
	private static boolean isOnTheIsland(TestDedicatedServerContext server) {
		return server.computeOnServer(minecraftServer -> {
			if (minecraftServer.getPlayerList().getPlayers().isEmpty()) {
				return false;
			}
			return minecraftServer.getPlayerList().getPlayers().getFirst().getY()
					>= (double) LobbyIsland.VOID_Y;
		});
	}

	/** Has the client been shown a message containing this? */
	private static boolean wasToldOnTheClient(ClientGameTestContext context, String fragment) {
		return context.computeOnClient(client -> {
			StringBuilder said = new StringBuilder();
			for (GuiMessage message : ((ChatComponentAccessor) client.gui.hud.getChat()).mhr$allMessages()) {
				said.append(message.content().getString()).append(" | ");
			}
			return said.toString();
		}).contains(fragment);
	}

	/**
	 * Arm-able failure on the way into a run, registered once for this client process.
	 *
	 * <p>{@link RunEvents} is static and every dedicated server this process drives shares it, so
	 * registering per server would stack listeners and registering unconditionally would fail
	 * other tests' runs. It is therefore registered once and inert until {@link #FAIL_PLAYER_ENTRY}
	 * is set, which only happens inside the one scenario that wants it.
	 */
	private static void registerEntryFault(TestDedicatedServerContext server) {
		server.runOnServer(unused -> {
			if (entryFaultRegistered) {
				return;
			}
			entryFaultRegistered = true;
			RunEvents.PLAYER_ENTERED_RUN.register((minecraftServer, player, run) -> {
				if (FAIL_PLAYER_ENTRY.get()) {
					throw new IllegalStateException("deliberate player-entry failure");
				}
			});
		});
	}

	/** Back to the middle of the island, facing north — away from the shop block. */
	private static void sendBackToSpawn(TestDedicatedServerContext server) {
		server.runCommand("execute in " + Lobby.LEVEL.identifier() + " run tp Player0 "
				+ (Lobby.SPAWN.getX() + 0.5) + " " + Lobby.SPAWN.getY() + " "
				+ (Lobby.SPAWN.getZ() + 0.5) + " 180 0");
		TestRuns.settle(server);
	}

	private static String playerPosition(TestDedicatedServerContext server) {
		return server.computeOnServer(minecraftServer -> {
			if (minecraftServer.getPlayerList().getPlayers().isEmpty()) {
				return "nobody connected";
			}
			return minecraftServer.getPlayerList().getPlayers().getFirst().blockPosition().toString();
		});
	}

	private static String blockOnServer(TestDedicatedServerContext server, BlockPos pos) {
		return server.computeOnServer(minecraftServer ->
				minecraftServer.getLevel(Lobby.LEVEL).getBlockState(pos).getBlock().toString());
	}

	private static String blockOnRunOverworld(TestDedicatedServerContext server, BlockPos pos) {
		return server.computeOnServer(minecraftServer ->
				minecraftServer.overworld().getBlockState(pos).getBlock().toString());
	}

	private static String blockOnClient(ClientGameTestContext context, BlockPos pos) {
		return context.computeOnClient(client ->
				client.level.getBlockState(pos).getBlock().toString());
	}

	/**
	 * Stand off the island and look back at it, so the picture shows what the scenario asserts.
	 *
	 * <p>From the spawn the camera is inside the room and the picture is a lawn and a sky, which
	 * is what any flat world looks like. The thing worth photographing is the island having an
	 * edge and nothing under it, and that can only be seen from outside it.
	 *
	 * <p>Spectator for the length of the photograph, because the viewpoint is over the void and a
	 * survival player left there would fall and start a run. {@code watchForJumpers} skips
	 * spectators for exactly this reason.
	 */
	private static void framePicture(ClientGameTestContext context, TestDedicatedServerContext server) {
		server.runCommand("time set noon");
		server.runCommand("gamemode spectator Player0");
		server.runCommand("execute in " + Lobby.LEVEL.identifier() + " run tp Player0 "
				+ (Lobby.SPAWN.getX() + 0.5) + " " + (Lobby.SPAWN.getY() + 7) + " "
				+ (Lobby.SPAWN.getZ() + 15.5) + " 180 22");
		TestRuns.settle(server);
		context.waitTicks(20);
	}

	/** Put the photographer back where the next scenario expects to find them. */
	private static void endPicture(TestDedicatedServerContext server) {
		server.runCommand("gamemode survival Player0");
		sendBackToSpawn(server);
	}

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
