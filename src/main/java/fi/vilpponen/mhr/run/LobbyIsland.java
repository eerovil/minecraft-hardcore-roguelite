package fi.vilpponen.mhr.run;

import fi.vilpponen.mhr.HardcoreRoguelite;
import java.util.List;
import java.util.OptionalLong;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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
				watchForJumpers(lobby);
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
	 * <p>The player list is copied first because starting a run moves everyone out of the lobby,
	 * and that is a change to the very list this is walking.
	 *
	 * <p>Nothing has to be done about the fall itself. {@code startRun} runs to completion on this
	 * thread — worlds deleted, rebuilt, players moved — so the jumper is in the new run before the
	 * next tick could drop them another block.
	 */
	private static void watchForJumpers(ServerLevel lobby) {
		if (lobby.players().isEmpty()) {
			return;
		}
		for (ServerPlayer player : List.copyOf(lobby.players())) {
			if (player.getY() > VOID_Y || player.isSpectator() || !player.isAlive()) {
				continue;
			}
			jumped(player);
		}
	}

	private static void jumped(ServerPlayer player) {
		RunLifecycle lifecycle = RunLifecycle.get();
		if (lifecycle.record().phase() != RunPhase.LOBBY) {
			// Somebody is in the lobby while the loop is somewhere else — a run winding up, a save
			// that has stopped. There is no run to start for them, and leaving them to fall would
			// end in void damage, so they go back on the island.
			putBack(player, "the save is not between runs: " + lifecycle.describe());
			return;
		}

		try {
			lifecycle.startRun(OptionalLong.empty());
		} catch (IllegalStateException e) {
			putBack(player, e.getMessage());
		}
	}

	private static void putBack(ServerPlayer player, String why) {
		HardcoreRoguelite.LOGGER.warn("{} jumped into the lobby's void and no run could start: {}",
				player.getGameProfile().name(), why);
		player.setDeltaMovement(0.0, 0.0, 0.0);
		player.resetFallDistance();
		Lobby.send(player);
		player.sendSystemMessage(Component.translatable("mhr.lobby.no_run_started", why));
	}
}
