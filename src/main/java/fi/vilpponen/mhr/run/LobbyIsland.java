package fi.vilpponen.mhr.run;

import fi.vilpponen.mhr.HardcoreRoguelite;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * What the lobby is actually made of: a skyblock island, the block that opens the shop, and the
 * edge you step off to start the next run.
 *
 * <p>{@link Lobby} owns the dimension and the business of getting a player into it. This owns the
 * room itself. The dimension generates as open void — see {@code data/hardcore_roguelite/dimension/
 * lobby.json} — so everything the player can see here is put there by {@link #ensure}.
 *
 * <p>Three rules hold the place together:
 *
 * <ul>
 *   <li><b>The island is built before anybody can arrive.</b> {@code Lobby.require} builds it on
 *       every way in, so a player is never dropped into a lobby with no floor. Building twice costs
 *       two block reads.
 *   <li><b>The island cannot be dug away.</b> A player in the lobby has an empty inventory, but
 *       grass comes up by hand in under a second, and the lobby is the one world that is never
 *       deleted — a hole in it would be there for good. An operator in creative is still allowed to
 *       rearrange the room.
 *   <li><b>Falling off starts the next run.</b> That is the design's "press start": there is no
 *       other way down and nothing else down there. It is watched from the end of the server tick
 *       rather than from the lobby's own level tick, because starting a run replaces the server's
 *       other three levels and doing that while Minecraft is iterating them is a concurrent
 *       modification of the map it is walking.
 * </ul>
 */
public final class LobbyIsland {
	/**
	 * The island, top layer first, each one a square centred under the spawn and a block narrower
	 * than the one above it. Five layers from a half-width of four gives a 9x9 lawn tapering to a
	 * point — the shape a skyblock island has.
	 */
	private static final Block[] LAYERS = {
		Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.DIRT, Blocks.STONE, Blocks.STONE,
	};

	/** Half-width of the top layer. Each layer below is one narrower. */
	private static final int TOP_RADIUS = LAYERS.length - 1;

	/** The middle of the top layer: the block the player spawns standing on. */
	private static final BlockPos TOP_CENTRE = Lobby.SPAWN.below();

	/**
	 * The shop, as a block you walk up to.
	 *
	 * <p>Three paces in front of the spawn, because an arriving player faces yaw 0 and that is
	 * south. Emerald is what vanilla has always meant by trading, which is the whole of why this
	 * block and not another: a good unlock is obvious, and so is a good shop.
	 */
	public static final BlockPos SHOP_BLOCK = new BlockPos(
			Lobby.SPAWN.getX(), Lobby.SPAWN.getY(), Lobby.SPAWN.getZ() + 3);

	public static final Block SHOP_BLOCK_TYPE = Blocks.EMERALD_BLOCK;

	/**
	 * Fall below this and the next run starts.
	 *
	 * <p>Well clear of both ends. Sixty-five blocks under the island is far enough that nobody gets
	 * there by stepping back from the edge and catching themselves, and it is still a hundred and
	 * twenty-eight blocks above the height where the void starts doing damage — so a start that is
	 * refused leaves plenty of room to put the player back.
	 */
	public static final int VOID_Y = 0;

	private LobbyIsland() {
	}

	public static void register() {
		// The island is wanted even before the first player arrives: something has to be standing
		// there for a screenshot, an operator, or a save that is loaded and looked at.
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			ServerLevel lobby = Lobby.level(server);
			if (lobby != null) {
				ensure(lobby);
			}
		});

		// END_SERVER_TICK, not the lobby's own level tick. See the class comment.
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			ServerLevel lobby = Lobby.level(server);
			if (lobby != null) {
				watchForJumpers(server, lobby);
			}
		});

		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) ->
				!Lobby.isLobby(level) || player.getAbilities().instabuild);
	}

	/** Is this the block that opens the shop? Asked of the position, so there is only ever one. */
	public static boolean isShopBlock(Level level, BlockPos pos) {
		return Lobby.isLobby(level) && pos.equals(SHOP_BLOCK);
	}

	/**
	 * Put the island there if it is not.
	 *
	 * <p>Cheap to ask and safe to repeat: the two blocks that say whether the room is furnished are
	 * read, and nothing is written unless one of them is wrong. That is deliberately not a repair
	 * of every block — an operator who rearranges the lobby in creative keeps their changes, and
	 * nobody else can change it at all.
	 */
	public static void ensure(ServerLevel lobby) {
		if (lobby.getBlockState(TOP_CENTRE).is(LAYERS[0])
				&& lobby.getBlockState(SHOP_BLOCK).is(SHOP_BLOCK_TYPE)) {
			return;
		}

		HardcoreRoguelite.LOGGER.info("Building the lobby island at {}", TOP_CENTRE);
		for (int layer = 0; layer < LAYERS.length; layer++) {
			int radius = TOP_RADIUS - layer;
			int y = TOP_CENTRE.getY() - layer;
			for (int x = -radius; x <= radius; x++) {
				for (int z = -radius; z <= radius; z++) {
					lobby.setBlockAndUpdate(
							new BlockPos(TOP_CENTRE.getX() + x, y, TOP_CENTRE.getZ() + z),
							LAYERS[layer].defaultBlockState());
				}
			}
		}
		lobby.setBlockAndUpdate(SHOP_BLOCK, SHOP_BLOCK_TYPE.defaultBlockState());
	}

	/**
	 * Anybody who has fallen off the island starts the next run.
	 *
	 * <p><b>A run start is one event, not one per jumper.</b> Two players stepping off together is
	 * the ordinary case, and {@code startRun} takes everybody who is connected into the run it
	 * builds — so the first successful start is the end of this tick's work and the loop stops
	 * there. Carrying on would be worse than redundant: getting a player into a run is a
	 * <em>respawn</em>, which destroys the {@link ServerPlayer} this loop is holding and builds a
	 * new one, so a second jumper handled after the first start would be handled as an object that
	 * no longer exists. The ids are collected first and nothing but an id survives the start.
	 *
	 * <p>Nothing has to be done about the fall itself. {@code startRun} runs to completion on this
	 * thread — worlds deleted, rebuilt, players moved — so the jumper is in the new run before the
	 * next tick could drop them another block.
	 */
	private static void watchForJumpers(MinecraftServer server, ServerLevel lobby) {
		List<UUID> fallen = null;
		for (ServerPlayer player : lobby.players()) {
			if (hasFallenOff(player)) {
				if (fallen == null) {
					fallen = new ArrayList<>();
				}
				fallen.add(player.getUUID());
			}
		}
		if (fallen == null) {
			return;
		}

		String refused = startTheNextRun();
		if (refused == null) {
			// The run started. Everybody who was falling is standing in it, as somebody else's
			// object; there is nobody left here to do anything to.
			return;
		}
		for (UUID id : fallen) {
			putBack(server, id, refused);
		}
	}

	private static boolean hasFallenOff(ServerPlayer player) {
		return player.getY() <= VOID_Y && player.isAlive() && !player.isSpectator();
	}

	/** @return null if a run started, or why it did not. */
	private static String startTheNextRun() {
		RunLifecycle lifecycle = RunLifecycle.get();
		if (lifecycle.record().phase() != RunPhase.LOBBY) {
			// Somebody is in the lobby while the loop is somewhere else — a run winding up, a save
			// that has stopped. There is no run to start for them.
			return "the save is not between runs: " + lifecycle.describe();
		}

		try {
			lifecycle.startRun(OptionalLong.empty());
			return null;
		} catch (IllegalStateException refused) {
			return refused.getMessage();
		}
	}

	/**
	 * Catch a jumper the loop could not start a run for, so the fall ends on the island rather
	 * than in void damage.
	 *
	 * <p>Looked up by id rather than kept from the scan, and this is the whole reason the ids are
	 * what get collected. A start can fail <em>after</em> it has already moved people: the failure
	 * path takes them back out of the half-built run, and both crossings are respawns, so the
	 * object that was standing here two calls ago has been destroyed and replaced — possibly
	 * twice. Moving that object moves nobody and leaves a dead entity where a live one should be.
	 *
	 * <p>Three things are asked of whoever comes back, and all three are reasons to do nothing:
	 * they have logged out, the failure path has already put them somewhere else, or they are back
	 * on the island and have been told by the lifecycle itself.
	 *
	 * <p>Deliberately not conditional on the phase. Nothing here writes to the record — it is a
	 * teleport inside the one dimension that is never deleted — and a save that has stopped is the
	 * last place to leave somebody falling.
	 */
	private static void putBack(MinecraftServer server, UUID id, String why) {
		ServerPlayer player = server.getPlayerList().getPlayer(id);
		if (player == null || !Lobby.isLobby(player.level()) || !hasFallenOff(player)) {
			return;
		}

		HardcoreRoguelite.LOGGER.warn("{} jumped into the lobby's void and no run could start: {}",
				player.getGameProfile().name(), why);
		player.setDeltaMovement(0.0, 0.0, 0.0);
		player.resetFallDistance();
		// Within the lobby, so this is the teleport branch and not the respawn one: the object
		// below is still the one that was just moved.
		Lobby.send(player);
		player.sendSystemMessage(Component.translatable("mhr.lobby.no_run_started", why));
	}
}
