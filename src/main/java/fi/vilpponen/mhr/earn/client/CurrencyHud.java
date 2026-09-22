package fi.vilpponen.mhr.earn.client;

import fi.vilpponen.mhr.HardcoreRoguelite;
import fi.vilpponen.mhr.shop.client.SyncedShop;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/**
 * The purse, drawn in the corner of the screen the whole time.
 *
 * <p>Currency is now earned during a run rather than granted between them, so the number moves
 * while the player is playing — and a number that moves while nobody is looking at it may as well
 * not have moved. The shop is the only other place it appears, and the shop is a screen you open
 * after you have already decided to spend.
 *
 * <p>Top left, above everything vanilla draws in that corner, and only once the server has said
 * what the balance is: a client connected to a server without the mod is told nothing and draws
 * nothing, rather than claiming the player has none.
 *
 * <p>The number itself comes from {@link SyncedShop}, which is this client's one copy of what the
 * server last said about progression. Deliberately one copy and not two — a HUD with a purse of its
 * own would be a second thing to keep in step, and the two disagreeing by a packet is exactly the
 * kind of bug nobody reports.
 */
public final class CurrencyHud implements ClientModInitializer {
	private static final Identifier ID =
			Identifier.fromNamespaceAndPath(HardcoreRoguelite.MOD_ID, "currency");

	/** Top left, clear of the hotbar and of everything vanilla puts in that corner. */
	private static final int LEFT = 6;
	private static final int TOP = 6;

	private static final int PADDING = 4;
	private static final int COIN = 8;
	private static final int GAP = 4;

	private static final int BACKDROP = 0x90101010;
	private static final int EDGE = 0xFF3A3A3A;
	private static final int COIN_FACE = 0xFFFFD24A;
	private static final int COIN_SHADE = 0xFFB07C16;
	private static final int TEXT = 0xFFFFE484;

	@Override
	public void onInitializeClient() {
		HudElementRegistry.attachElementAfter(VanillaHudElements.MISC_OVERLAYS, ID, CurrencyHud::draw);
	}

	private static void draw(GuiGraphicsExtractor extractor, DeltaTracker tracker) {
		if (!SyncedShop.isKnown()) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) {
			return;
		}

		Font font = client.font;
		String amount = Integer.toString(SyncedShop.currency());
		int textWidth = font.width(amount);
		int width = PADDING + COIN + GAP + textWidth + PADDING;
		int height = PADDING + font.lineHeight + PADDING;

		extractor.fill(LEFT, TOP, LEFT + width, TOP + height, BACKDROP);
		extractor.fill(LEFT, TOP, LEFT + width, TOP + 1, EDGE);
		extractor.fill(LEFT, TOP + height - 1, LEFT + width, TOP + height, EDGE);
		extractor.fill(LEFT, TOP, LEFT + 1, TOP + height, EDGE);
		extractor.fill(LEFT + width - 1, TOP, LEFT + width, TOP + height, EDGE);

		// A coin: a gold disc suggested by three rows, with a darker rim along the bottom.
		int coinX = LEFT + PADDING;
		int coinY = TOP + PADDING + (font.lineHeight - COIN) / 2;
		extractor.fill(coinX + 2, coinY, coinX + COIN - 2, coinY + 1, COIN_SHADE);
		extractor.fill(coinX + 1, coinY + 1, coinX + COIN - 1, coinY + COIN - 2, COIN_FACE);
		extractor.fill(coinX + 2, coinY + COIN - 2, coinX + COIN - 2, coinY + COIN - 1, COIN_SHADE);

		extractor.text(font, amount, coinX + COIN + GAP, TOP + PADDING, TEXT);
	}
}
