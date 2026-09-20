package fi.vilpponen.mhr.gametest.client;

import fi.vilpponen.mhr.run.Lobby;
import fi.vilpponen.mhr.run.RunLifecycle;
import fi.vilpponen.mhr.run.RunPhase;
import fi.vilpponen.mhr.run.RunRecord;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
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

	/** Which dimension the server says this connection's player is standing in. */
	static String playerDimension(
			TestDedicatedServerContext server, TestDedicatedServerConnection connection) {
		return server.computeOnServer(unused ->
				connection.getServerPlayer().level().dimension().identifier().toString());
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
			ServerPlayer current = connection.getServerPlayer();
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

	/** Wait for one full server tick, so a command issued just before it has certainly run. */
	static void settle(TestDedicatedServerContext server) {
		server.runOnServer(unused -> {
		});
	}
}
