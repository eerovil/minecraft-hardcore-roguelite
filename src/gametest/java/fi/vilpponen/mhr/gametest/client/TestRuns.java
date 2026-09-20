package fi.vilpponen.mhr.gametest.client;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import fi.vilpponen.mhr.gametest.mixin.MappedRegistryAccessor;
import fi.vilpponen.mhr.mixin.MinecraftServerAccessor;
import fi.vilpponen.mhr.run.Lobby;
import fi.vilpponen.mhr.run.RunAdmission;
import fi.vilpponen.mhr.run.RunLifecycle;
import fi.vilpponen.mhr.run.RunPhase;
import fi.vilpponen.mhr.run.RunRecord;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Items;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.WanderingTraderData;

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

	/**
	 * Run a command and get back what it actually returned.
	 *
	 * <p>{@code runCommand} throws away the result, and the result is the point when a command is
	 * allowed to fail politely: a run that could not be finished reports zero and says why, and a
	 * test that only looked at the record would never notice the operator being told the opposite.
	 *
	 * @return the command's own return value, or 0 if it reported a failure
	 */
	static int runCommandResult(TestDedicatedServerContext server, String command) {
		int result = server.computeOnServer(minecraftServer -> {
			try {
				return minecraftServer.getCommands().getDispatcher()
						.execute(command, minecraftServer.createCommandSourceStack());
			} catch (CommandSyntaxException unparseable) {
				throw new AssertionError("could not run /" + command, unparseable);
			}
		});
		settle(server);
		return result;
	}

	/**
	 * Which run this connection's player is marked as having been let into, or -1 if nobody is on.
	 *
	 * <p>This is what run membership actually is. Standing in {@code minecraft:overworld} only says
	 * the run's world is underfoot; the mark says the player crossed this run's start boundary.
	 */
	static int admittedRunOf(TestDedicatedServerContext server) {
		return server.computeOnServer(minecraftServer -> {
			ServerPlayer live = livePlayer(minecraftServer);
			return live == null ? -1 : RunAdmission.of(live);
		});
	}

	/** Hurt the live player down to this much health, so a later revive is visible. */
	static void woundLivePlayer(TestDedicatedServerContext server, float health) {
		server.runOnServer(minecraftServer -> livePlayer(minecraftServer).setHealth(health));
	}

	/**
	 * Take away the mark that says this player joined the run in progress.
	 *
	 * <p>What an entry that failed part-way leaves behind, without having to make one fail: the
	 * same player, in the same world, with the one difference that decides whether their death is
	 * the run ending or their own business. Zero is the value an unmarked player reads as.
	 */
	static void forgetAdmission(TestDedicatedServerContext server) {
		server.runOnServer(minecraftServer -> RunAdmission.admit(livePlayer(minecraftServer), 0));
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
	 * four separate checks tripped. The respawn point is in here with the rest because it is the
	 * same kind of thing: a bed from the last run points into the new one, since the runs share
	 * their dimension keys.
	 */
	static String runLocalStateOf(TestDedicatedServerContext server) {
		return server.computeOnServer(minecraftServer -> {
			ServerPlayer live = livePlayer(minecraftServer);
			if (live == null) {
				return "nobody connected";
			}
			return "inventory " + live.getInventory().countItem(Items.DIAMOND) + " diamond(s),"
					+ " ender chest " + live.getEnderChestInventory().countItem(Items.EMERALD)
					+ " emerald(s), " + live.experienceLevel + " xp level(s), respawn point "
					+ (live.getRespawnConfig() == null ? "unset" : "set")
					+ ", hunger " + live.getFoodData().getFoodLevel()
					+ "/" + live.getFoodData().getSaturationLevel();
		});
	}

	/** Nothing carried, nothing stored, no experience. */
	static final String NOTHING_CARRIED = "inventory 0 diamond(s), ender chest 0 emerald(s),"
			+ " 0 xp level(s), respawn point unset, hunger 20/5.0";

	/** Which status effects the player is carrying, named, so a failure says which are missing. */
	static String effectsOn(TestDedicatedServerContext server) {
		return server.computeOnServer(minecraftServer -> {
			ServerPlayer live = livePlayer(minecraftServer);
			if (live == null) {
				return "nobody connected";
			}
			StringBuilder carried = new StringBuilder();
			live.getActiveEffects().forEach(effect -> carried
					.append(effect.getEffect().getRegisteredName()).append(' '));
			return carried.isEmpty() ? "none" : carried.toString().trim();
		});
	}

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
	 * Wait until a joining player has actually been put in the lobby.
	 *
	 * <p>Any test that connects and then does something where the player is standing needs this.
	 * Chunks having rendered is not the same as the lifecycle having finished with the player: a
	 * join that has to change dimension waits for the login to complete first, because moving a
	 * half-logged-in player leaves them registered in two levels and receiving chunks from neither.
	 * So the move can land a tick or two after the client thinks it has arrived, and a test that
	 * put a crafting table down in between finds the player has since been teleported away from it.
	 */
	static void waitForPlayerInTheLobby(ClientGameTestContext context,
			TestDedicatedServerContext server, TestDedicatedServerConnection connection) {
		for (int attempt = 0; attempt < 100; attempt++) {
			if (playerIsInTheLobby(server, connection)) {
				return;
			}
			context.waitTicks(2);
		}
		throw new AssertionError("The player never reached the lobby; they are in "
				+ playerDimension(server, connection));
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

	/**
	 * Run {@code body} on a save that cannot describe one of the three run dimensions.
	 *
	 * <p>The nether, because it is the one a run needs and the lobby does not, so taking it away
	 * breaks exactly the thing under test and nothing else. Put straight back afterwards, whatever
	 * the body did — a save left short a dimension would fail every scenario after this one for a
	 * reason that had nothing to do with them.
	 */
	static void withNoNetherStem(TestDedicatedServerContext server, Runnable body) {
		ResourceKey<LevelStem> stemKey =
				ResourceKey.create(Registries.LEVEL_STEM, Level.NETHER.identifier());
		Object stem = server.computeOnServer(minecraftServer -> {
			Map<Object, Object> byKey = ((MappedRegistryAccessor) minecraftServer.registryAccess()
					.lookupOrThrow(Registries.LEVEL_STEM)).mhr$byKey();
			return byKey.remove(stemKey);
		});
		if (stem == null) {
			throw new AssertionError("there was no " + stemKey.identifier() + " stem to take away");
		}
		try {
			body.run();
		} finally {
			server.runOnServer(minecraftServer -> ((MappedRegistryAccessor) minecraftServer
					.registryAccess().lookupOrThrow(Registries.LEVEL_STEM)).mhr$byKey()
					.put(stemKey, stem));
		}
	}

	/**
	 * The run state Minecraft keeps on the server rather than in the levels.
	 *
	 * <p>Read as one string for the same reason as the player's: an assertion should be able to say
	 * which part of a fresh run was not fresh.
	 */
	static String serverRunStateOf(TestDedicatedServerContext server) {
		return server.computeOnServer(minecraftServer -> {
			ServerLevel overworld = minecraftServer.overworld();
			long ticks = minecraftServer.clockManager()
					.getInstance(minecraftServer.registryAccess().getOrThrow(WorldClocks.OVERWORLD))
					.totalTicks();
			return "raining " + overworld.isRaining() + ", thundering " + overworld.isThundering()
					+ ", day " + (ticks / 24000L);
		});
	}

	/** Nothing has happened yet: clear skies, first morning. */
	static final String FRESH_WORLD = "raining false, thundering false, day 0";

	/** The named loot-table random sequence this test uses to see whether RNG state carries over. */
	static final Identifier TEST_SEQUENCE =
			Identifier.fromNamespaceAndPath("hardcore_roguelite", "gametest_sequence");

	/**
	 * Draw some randomness from a named sequence, so the server has a sequence to carry over.
	 *
	 * <p>This is the same call a loot table makes: {@code LootContext} asks the server for the
	 * sequence its table names, and every number taken from it advances the stored state. One run's
	 * worth of opening chests is exactly this, many times over.
	 */
	static void useARandomSequence(TestDedicatedServerContext server) {
		server.runOnServer(minecraftServer -> {
			for (int i = 0; i < 16; i++) {
				minecraftServer.getRandomSequence(TEST_SEQUENCE).nextInt();
			}
		});
	}

	/** How many named random sequences the server is carrying, and whether ours is among them. */
	static String randomSequencesOn(TestDedicatedServerContext server) {
		return server.computeOnServer(minecraftServer -> {
			int[] total = {0};
			boolean[] ours = {false};
			minecraftServer.getRandomSequences().forAllSequences((id, sequence) -> {
				total[0]++;
				if (id.equals(TEST_SEQUENCE)) {
					ours[0] = true;
				}
			});
			return total[0] + " sequence(s), ours " + (ours[0] ? "present" : "gone");
		});
	}

	/**
	 * Is the server-global state this mod resets actually going to reach the disk?
	 *
	 * <p>Resetting a {@code SavedData} in memory and never marking it dirty is a reset that lasts
	 * until the next restart and no longer — the save loop writes only dirty entries. Asked of both
	 * the pieces put back at a run boundary, because they get there by different routes: the
	 * storage marks what it is handed dirty, and the sequences have to be marked by hand.
	 *
	 * <p>Asked at the moment of the reset rather than afterwards. The save loop clears the dirty
	 * flag as it writes, so a later answer would depend on whether an autosave had happened to run
	 * in between — which is not a thing this test gets to be about.
	 */
	static String persistableOn(MinecraftServer server) {
		boolean trader = server.getDataStorage().get(WanderingTraderData.TYPE).isDirty();
		boolean sequences = server.getRandomSequences().isDirty();
		return "trader " + (trader ? "persistable" : "NOT persistable")
				+ ", sequences " + (sequences ? "persistable" : "NOT persistable");
	}

	/** Both halves of the server's own run state will survive a restart. */
	static final String PERSISTABLE = "trader persistable, sequences persistable";

	/**
	 * Clear the dirty flags, exactly as saving to disk does.
	 *
	 * <p>Without this the persistability check answers for the wrong reason. Drawing from a random
	 * sequence marks it dirty by itself, so a run that used one leaves the flag set and a reset that
	 * never marked anything would look fine. Starting from "everything is written" makes the next
	 * answer about the reset and nothing else.
	 */
	static void pretendEverythingIsSaved(TestDedicatedServerContext server) {
		server.runOnServer(minecraftServer -> {
			minecraftServer.getDataStorage().get(WanderingTraderData.TYPE).setDirty(false);
			minecraftServer.getRandomSequences().setDirty(false);
		});
	}

	/** Wait for one full server tick, so a command issued just before it has certainly run. */
	static void settle(TestDedicatedServerContext server) {
		server.runOnServer(unused -> {
		});
	}
}
