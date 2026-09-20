package fi.vilpponen.mhr.gametest.client;

import fi.vilpponen.mhr.run.Lobby;
import fi.vilpponen.mhr.run.RunLifecycle;
import fi.vilpponen.mhr.run.RunPhase;
import fi.vilpponen.mhr.run.RunRecord;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
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

	/** Wait for one full server tick, so a command issued just before it has certainly run. */
	static void settle(TestDedicatedServerContext server) {
		server.runOnServer(unused -> {
		});
	}
}
