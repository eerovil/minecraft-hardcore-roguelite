package fi.vilpponen.mhr.shop;

import fi.vilpponen.mhr.run.LobbyIsland;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;

/**
 * The shop as a thing in the world: right-click the block on the lobby island and the screen opens.
 *
 * <p>This is the door the design wants and {@code /mhr shop} is now the operator's spare key.
 * Both end up in {@link ShopServer#open(ServerPlayer)}, which is still the only place that decides
 * whether a client can be shown a shop at all.
 *
 * <p>It is the block's <b>position</b> that opens the shop, not its type — {@link
 * LobbyIsland#isShopBlock}. There is exactly one shop in the game and it is on the island; an
 * emerald block anywhere else is an emerald block.
 *
 * <p>The client side deliberately passes. Its only job is to send the interaction to the server,
 * which vanilla already does, and the server is where the screen is decided from state the client
 * does not own.
 */
public final class ShopBlock {
	private ShopBlock() {
	}

	public static void register() {
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (level.isClientSide() || !LobbyIsland.isShopBlock(level, hit.getBlockPos())) {
				return InteractionResult.PASS;
			}
			if (player instanceof ServerPlayer serverPlayer) {
				ShopServer.open(serverPlayer);
			}
			// Handled either way: nothing else may act on a click that landed on the shop, and a
			// client whose mod is missing has already been told why it is getting no screen.
			return InteractionResult.SUCCESS_SERVER;
		});
	}
}
