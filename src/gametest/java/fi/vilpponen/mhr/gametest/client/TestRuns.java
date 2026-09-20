package fi.vilpponen.mhr.gametest.client;

import fi.vilpponen.mhr.mixin.MinecraftServerAccessor;
import fi.vilpponen.mhr.run.Lobby;
import fi.vilpponen.mhr.run.RunLifecycle;
import fi.vilpponen.mhr.run.RunPhase;
import fi.vilpponen.mhr.run.RunRecord;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Driving the run loop from a client test.
 *
 * <p>Runs are started and ended through {@code /mhr run}, the same commands a person would type,
 * rather than by calling {@link RunLifecycle} directly — the command is the only "start next run"
 * action the game has until the shop exists, so a test that bypassed it would not be exercising the
 * path a player uses. Reading the state, on the other hand, goes straight to the server, because
 * parsing chat is not evidence.
 *
 * <p>Every method here blocks until the server has finished the tick it asked for, so a scenario
 * never asserts against a half-applied transition.
 */
final class TestRuns {
	private static final org.slf4j.Logger LOGGER =
			org.slf4j.LoggerFactory.getLogger("mhr-gametest");

	private TestRuns() {
	}

	static RunRecord record(TestDedicatedServerContext server) {
		return server.computeOnServer(unused -> RunLifecycle.get().record());
	}

	static RunPhase phase(TestDedicatedServerContext server) {
		return record(server).phase();
	}

	/** Start a run the way the lobby's button will, and wait for it to be playable. */
	static void start(TestDedicatedServerContext server) {
		server.runCommand("mhr run start");
		settle(server);
	}

	/** End a run without anybody having to die for it. */
	static void end(TestDedicatedServerContext server) {
		server.runCommand("mhr run end");
		settle(server);
	}

	/** Where this run's overworld puts an arriving player. */
	static BlockPos runSpawn(TestDedicatedServerContext server) {
		return server.computeOnServer(minecraftServer -> minecraftServer.getRespawnData().pos());
	}

	/**
	 * Put an unmistakable block in a dimension, to be looked for again later.
	 *
	 * <p>This is how "the world was really replaced" is asked: a block that a generator would never
	 * produce, at a fixed coordinate, in a chunk the call itself loads. If it is still there after a
	 * new run starts, the old chunks were reused.
	 */
	static void mark(TestDedicatedServerContext server, ResourceKey<Level> dimension, BlockPos pos,
			BlockState block) {
		server.runOnServer(minecraftServer -> {
			ServerLevel level = minecraftServer.getLevel(dimension);
			if (level == null) {
				throw new AssertionError("No " + dimension.identifier() + " to mark");
			}
			level.setBlock(pos, block, 3);
		});
	}

	static boolean isMarked(TestDedicatedServerContext server, ResourceKey<Level> dimension,
			BlockPos pos, BlockState block) {
		return server.computeOnServer(minecraftServer -> {
			ServerLevel level = minecraftServer.getLevel(dimension);
			return level != null && level.getBlockState(pos) == block;
		});
	}

	/**
	 * Which dimension the server says this connection's player is standing in.
	 *
	 * <p>Looked up in the player list by id rather than taken from the connection. Moving a player
	 * between dimensions here replaces the {@code ServerPlayer} object, and a test holding the one
	 * from login would keep answering for a player the server destroyed — which reads as "they
	 * never moved" when in fact they did.
	 */
	static String playerDimension(
			TestDedicatedServerContext server, TestDedicatedServerConnection connection) {
		return server.computeOnServer(minecraftServer -> {
			ServerPlayer live = livePlayer(minecraftServer);
			return live == null
					? "nowhere (not connected)"
					: live.level().dimension().identifier().toString();
		});
	}

	/** How much health this connection's player has, asked of the player the server currently has. */
	static float playerHealth(
			TestDedicatedServerContext server, TestDedicatedServerConnection connection) {
		return server.computeOnServer(minecraftServer -> {
			ServerPlayer live = livePlayer(minecraftServer);
			return live == null ? 0.0F : live.getHealth();
		});
	}

	/**
	 * The player the server currently has, asked of the server and nothing else.
	 *
	 * <p>Deliberately not routed through the connection. Moving a player between dimensions here
	 * replaces the {@code ServerPlayer} object, and anything holding the one from login keeps
	 * answering for a player the server has destroyed — which reads as "they never moved" when they
	 * did. These tests run one player, so the live one is simply the one in the list that is not
	 * removed.
	 */
	private static ServerPlayer livePlayer(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!player.isRemoved()) {
				return player;
			}
		}
		return null;
	}

	/**
	 * Everything a player is carrying that belongs to one run and must not outlive it.
	 *
	 * <p>Read back as one string so an assertion can say what was still there rather than which of
	 * three separate checks tripped.
	 */
	static String runLocalStateOf(TestDedicatedServerContext server) {
		return server.computeOnServer(minecraftServer -> {
			ServerPlayer live = livePlayer(minecraftServer);
			if (live == null) {
				return "nobody connected";
			}
			return "inventory " + live.getInventory().countItem(Items.DIAMOND) + " diamond(s),"
					+ " ender chest " + live.getEnderChestInventory().countItem(Items.EMERALD)
					+ " emerald(s), " + live.experienceLevel + " xp level(s)";
		});
	}

	/** Nothing carried, nothing stored, no experience. */
	static final String NOTHING_CARRIED =
			"inventory 0 diamond(s), ender chest 0 emerald(s), 0 xp level(s)";

	/** Everybody the server has and where they are, for a failure message worth reading. */
	static String describePlayers(TestDedicatedServerContext server) {
		return server.computeOnServer(minecraftServer -> {
			StringBuilder described = new StringBuilder();
			for (ServerPlayer player : minecraftServer.getPlayerList().getPlayers()) {
				if (!described.isEmpty()) {
					described.append("; ");
				}
				described.append(player.getGameProfile().name())
						.append(" #").append(player.getId())
						.append(" in ").append(player.level().dimension().identifier())
						.append(player.isRemoved() ? " (removed)" : "")
						.append(player.connection == null ? " (no connection)" : "");
			}
			return described.isEmpty() ? "nobody" : described.toString();
		});
	}

	static boolean playerIsInTheLobby(
			TestDedicatedServerContext server, TestDedicatedServerConnection connection) {
		return playerDimension(server, connection).equals(Lobby.LEVEL.identifier().toString());
	}

	/**
	 * Wait for the save to reach a phase.
	 *
	 * <p>A death does not finish the run in the tick it happens in — see
	 * {@code RunLifecycle.playerDied} — so a scenario that killed somebody has to wait for the
	 * transition rather than assume it.
	 */
	static void waitForPhase(ClientGameTestContext context, TestDedicatedServerContext server,
			RunPhase wanted) {
		for (int attempt = 0; attempt < 100; attempt++) {
			if (phase(server) == wanted) {
				return;
			}
			context.waitTicks(2);
		}
		throw new AssertionError("The save never reached " + wanted + "; it is in " + phase(server));
	}

	/**
	 * Does the lobby still hold a <em>live</em> registration for a player who has left it?
	 *
	 * <p>The lobby is the one level that outlives every run, so it is the one that can accumulate
	 * registrations for players who are no longer in it, and an arrival that collides with a live
	 * one is only half added and never sent any chunks — the player stands in a lobby their client
	 * draws as empty void.
	 *
	 * <p>"Live" is the operative word. Leaving through a respawn destroys the entity that was in
	 * the lobby and builds a fresh one for the destination, and Minecraft leaves the destroyed
	 * one's entry in the level's lookup until that lookup is next disturbed. A dead entry is inert:
	 * the next arrival is a different object, and {@code ServerLevel.addPlayer} discards the dead
	 * one before adding it. A live entry is the real fault, and is what this asks about.
	 *
	 * @return an empty string when nothing live is left behind
	 */
	static String liveLobbyRegistrationOf(
			TestDedicatedServerContext server, TestDedicatedServerConnection connection) {
		return server.computeOnServer(minecraftServer -> {
			ServerLevel lobby = minecraftServer.getLevel(Lobby.LEVEL);
			if (lobby == null) {
				return "there is no lobby at all";
			}
			ServerPlayer current = livePlayer(minecraftServer);
			if (current == null) {
				return "";
			}
			UUID id = current.getUUID();

			Entity held = lobby.getEntity(id);
			boolean heldAlive = held != null && !held.isRemoved();
			boolean listedAlive = lobby.players().stream()
					.anyMatch(p -> p.getUUID().equals(id) && !p.isRemoved());
			if (held != null || listedAlive) {
				LOGGER.info("Lobby still has an entry for {}: entity {}, alive {}, listed alive {}",
						id, held, heldAlive, listedAlive);
			}
			if (!heldAlive && !listedAlive) {
				return "";
			}
			return "entity lookup holds a live " + held + ", player list holds one: " + listedAlive;
		});
	}

	/**
	 * Wait until the server has nobody connected.
	 *
	 * <p>Closing a connection does not free the slot in the same breath, and this harness's server
	 * allows one player. Reconnecting too eagerly is answered with "The server is full!", which
	 * looks like a fault in whatever the scenario was actually about.
	 */
	static void waitForNobodyConnected(ClientGameTestContext context, TestDedicatedServerContext server) {
		// Generous on purpose: this pod’s server runs tens of ticks behind the client, so a budget
		// counted in client ticks buys far fewer server ticks than it looks like.
		for (int attempt = 0; attempt < 600; attempt++) {
			if (server.computeOnServer(minecraftServer ->
					minecraftServer.getPlayerList().getPlayers().isEmpty())) {
				return;
			}
			context.waitTicks(2);
		}
		throw new AssertionError("The server never let go of " + describePlayers(server));
	}

	/**
	 * Take the lobby dimension off the server, run something, and put it back.
	 *
	 * <p>The only way to ask what happens when the persistent lobby is not there. It is taken out of
	 * the server's level map rather than closed or deleted, so the very same level object goes back
	 * afterwards and the rest of the run carries on with it — nothing about the world on disk is
	 * touched.
	 */
	static void withNoLobby(TestDedicatedServerContext server, Runnable body) {
		ServerLevel lobby = server.computeOnServer(minecraftServer -> {
			Map<ResourceKey<Level>, ServerLevel> levels =
					((MinecraftServerAccessor) minecraftServer).mhr$levels();
			return levels.remove(Lobby.LEVEL);
		});
		if (lobby == null) {
			throw new AssertionError("there was no lobby to take away");
		}
		try {
			body.run();
		} finally {
			server.runOnServer(minecraftServer ->
					((MinecraftServerAccessor) minecraftServer).mhr$levels().put(Lobby.LEVEL, lobby));
		}
	}

	/** Wait for one full server tick, so a command issued just before it has certainly run. */
	static void settle(TestDedicatedServerContext server) {
		server.runOnServer(unused -> {
		});
	}
}
