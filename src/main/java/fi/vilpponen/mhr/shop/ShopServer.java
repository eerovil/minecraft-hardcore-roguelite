package fi.vilpponen.mhr.shop;

import fi.vilpponen.mhr.UnlockEffects;
import fi.vilpponen.mhr.progression.Catalogue;
import fi.vilpponen.mhr.progression.Purchase;
import fi.vilpponen.mhr.progression.Offer;
import fi.vilpponen.mhr.progression.Wallet;
import fi.vilpponen.mhr.starter.StarterItems;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
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

	/**
	 * Put the screen in front of one player. Two callers: the lobby's shop block, which is how a
	 * player opens it, and {@code /mhr shop}, which is the operator's and the tests' spare key.
	 *
	 * <p>The dedicated server is joinable without the client mod, so a player can ask for a screen
	 * their client has no way of drawing. That gets said out loud rather than dropped: asking for
	 * the shop and getting nothing at all is indistinguishable from the shop being broken.
	 *
	 * @return false if the player was told the shop cannot be opened instead of being shown it.
	 */
	public static boolean open(ServerPlayer player) {
		if (!canReceive(player)) {
			player.sendSystemMessage(ShopText.clientModRequired());
			return false;
		}
		sendTo(player, true);
		return true;
	}

	/** Whether this player's client has told us it can receive the shop, i.e. whether it has the mod. */
	public static boolean canReceive(ServerPlayer player) {
		return ServerPlayNetworking.canSend(player, ShopStatePayload.TYPE);
	}

	/**
	 * Tell one client what the shop holds now, if it is listening.
	 *
	 * <p>Silent when it is not, deliberately: this is the background refresh, sent on joining and
	 * after every change, and a client without the mod has not asked for any of it. The player
	 * asking for the screen themselves is {@link #open(ServerPlayer)}, which does answer.
	 */
	public static void sendTo(ServerPlayer player, boolean open) {
		if (!canReceive(player)) {
			return;
		}
		List<Offer> offers = Catalogue.offers();
		ServerPlayNetworking.send(player, new ShopStatePayload(
				open, Wallet.get().balance(), offers, rewardsFor(offers, player.level().registryAccess())));
	}

	/**
	 * What each offer will actually hand over, for the ones that hand over an item.
	 *
	 * <p>Read from the balance in effect every time, like the price beside it, so a retuned starter
	 * item is described by the shop the moment {@code /mhr reload} takes — and described as the
	 * thing the chest is going to hold, rather than as whatever an icon file once said.
	 */
	private static Map<String, Reward> rewardsFor(List<Offer> offers, HolderLookup.Provider registries) {
		Map<String, Reward> rewards = new LinkedHashMap<>();
		for (Offer offer : offers) {
			ItemStack stack = StarterItems.stackFor(offer.id(), registries);
			if (!stack.isEmpty()) {
				rewards.put(offer.id(), new Reward(stack, stack.getCount()));
			}
		}
		return rewards;
	}

	/**
	 * What to call an offer in chat.
	 *
	 * <p>A starter item is named after the thing it gives, from the balance in effect, for the same
	 * reason the screen draws it from there: any other name is a second copy of something an
	 * override may change.
	 */
	private static Component nameOf(String id, ServerPlayer player) {
		ItemStack stack = StarterItems.stackFor(id, player.level().registryAccess());
		if (stack.isEmpty()) {
			return Component.translatable(ShopText.nameKey(id));
		}
		return stack.getCount() > 1
				? Component.translatable("mhr.shop.reward.name", stack.getCount(), stack.getHoverName())
				: stack.getHoverName();
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
		player.sendSystemMessage(describe(result, player));
		sendToAll(server);
	}

	private static Component describe(Purchase.Result result, ServerPlayer player) {
		Component name = nameOf(result.id(), player);
		return switch (result.outcome()) {
			case BOUGHT -> Component.translatable("mhr.shop.bought", name, result.price(), result.balance());
			case NOT_FOR_SALE -> Component.translatable("mhr.shop.refused.not_for_sale", result.id());
			case ALREADY_MAXED -> Component.translatable("mhr.shop.refused.maxed", name);
			case TOO_EXPENSIVE -> Component.translatable("mhr.shop.refused.too_expensive",
					name, result.price(), result.balance());
			case NOT_SAVED -> Component.translatable("mhr.shop.refused.not_saved", name);
		};
	}
}
