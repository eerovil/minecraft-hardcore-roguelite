package fi.vilpponen.mhr.shop;

import fi.vilpponen.mhr.UnlockEffects;
import fi.vilpponen.mhr.progression.Catalogue;
import fi.vilpponen.mhr.progression.Purchase;
import fi.vilpponen.mhr.progression.Wallet;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The server half of the shop: what is for sale, who is told about it, and who decides a purchase.
 *
 * <p>The client is told the catalogue when it joins, whenever anything about progression changes,
 * and when it asks for the screen. It is never asked what something costs or whether it is
 * affordable — a click sends an id and the server does the rest through
 * {@link Purchase}, which is the one place currency turns into ownership.
 *
 * <p>Nothing here knows what the screen looks like. That is the client's, in
 * {@code fi.vilpponen.mhr.shop.client}.
 */
public final class ShopServer {
	private ShopServer() {
	}

	/** Called from the mod initializer, on both sides: the payload types have to be known to both. */
	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(ShopStatePayload.TYPE, ShopStatePayload.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ShopBuyPayload.TYPE, ShopBuyPayload.STREAM_CODEC);

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> sendTo(handler.player, false));
		ServerPlayNetworking.registerGlobalReceiver(ShopBuyPayload.TYPE,
				(payload, context) -> buy(context.server(), context.player(), payload.id()));

		// A dev command can change an unlock behind the shop's back, and an open screen showing the
		// old level is worse than no screen at all.
		UnlockEffects.onChange(ShopServer::sendToAll);
	}

	/** Put the screen in front of one player. {@code /mhr shop} is the only caller today. */
	public static void open(ServerPlayer player) {
		sendTo(player, true);
	}

	public static void sendTo(ServerPlayer player, boolean open) {
		if (ServerPlayNetworking.canSend(player, ShopStatePayload.TYPE)) {
			ServerPlayNetworking.send(player,
					new ShopStatePayload(open, Wallet.get().balance(), Catalogue.offers()));
		}
	}

	/** After anything changes, so an open shop screen never shows a stale price or level. */
	public static void sendToAll(MinecraftServer server) {
		if (server == null) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			sendTo(player, false);
		}
	}

	/**
	 * One click, decided here and nowhere else.
	 *
	 * <p>The buyer is told what happened either way, and everyone's screen is refreshed afterwards
	 * — including on a refusal, because the commonest reason a click is refused is that the client's
	 * copy of the currency was out of date.
	 */
	private static void buy(MinecraftServer server, ServerPlayer player, String id) {
		Purchase.Result result = Purchase.buy(id);
		if (result.bought()) {
			UnlockEffects.applyAll(server);
		}
		player.sendSystemMessage(describe(result));
		sendToAll(server);
	}

	private static Component describe(Purchase.Result result) {
		return switch (result.outcome()) {
			case BOUGHT -> Component.translatable("mhr.shop.bought",
					Component.translatable(ShopText.nameKey(result.id())), result.price(), result.balance());
			case NOT_FOR_SALE -> Component.translatable("mhr.shop.refused.not_for_sale", result.id());
			case ALREADY_MAXED -> Component.translatable("mhr.shop.refused.maxed",
					Component.translatable(ShopText.nameKey(result.id())));
			case TOO_EXPENSIVE -> Component.translatable("mhr.shop.refused.too_expensive",
					Component.translatable(ShopText.nameKey(result.id())), result.price(), result.balance());
		};
	}
}
