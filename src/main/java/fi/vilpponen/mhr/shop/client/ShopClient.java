package fi.vilpponen.mhr.shop.client;

import fi.vilpponen.mhr.shop.ShopStatePayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * Keeps this client's copy of the shop up to date, and opens the screen when the server says so.
 *
 * <p>The same packet does both jobs. A shop that is already open has nothing to do but redraw
 * itself from the new numbers, which it does on its own; a packet that asks for the screen puts it
 * in front of the player. Nothing here decides anything — the server does.
 */
public final class ShopClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(ShopStatePayload.TYPE, (payload, context) -> {
			SyncedShop.accept(payload.currency(), payload.offers(), payload.rewards());
			if (payload.open()) {
				context.client().setScreenAndShow(new ShopScreen());
			}
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> SyncedShop.forget());
	}
}
