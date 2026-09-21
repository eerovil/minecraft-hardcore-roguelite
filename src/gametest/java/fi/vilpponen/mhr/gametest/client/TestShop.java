package fi.vilpponen.mhr.gametest.client;

import com.mojang.blaze3d.platform.InputConstants;
import fi.vilpponen.mhr.UnlockEffects;
import fi.vilpponen.mhr.UnlockState;
import fi.vilpponen.mhr.core.BalanceManager;
import fi.vilpponen.mhr.progression.Catalogue;
import fi.vilpponen.mhr.progression.Offer;
import fi.vilpponen.mhr.progression.Wallet;
import fi.vilpponen.mhr.shop.Reward;
import fi.vilpponen.mhr.shop.ShopServer;
import fi.vilpponen.mhr.shop.client.ShopIcons;
import fi.vilpponen.mhr.shop.client.ShopScreen;
import fi.vilpponen.mhr.shop.client.SyncedShop;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * Driving the shop screen with a real mouse, and asking both sides what they think is on it.
 *
 * <p>Every purchase made through here is the cursor landing on a square and the left button going
 * down — nothing calls a purchase helper, because the point of a client test is that the squares are
 * where the player sees them and that clicking one is what charges the purse.
 *
 * <p>This lives apart from any one test because the input it sends carries two 26.3 traps that must
 * not be rediscovered per feature: the left mouse button is {@code MOUSE_BUTTON_LEFT} and not 0,
 * since input comes from SDL and SDL numbers buttons from one; and the screen only scrolls through
 * its own wheel handler, so a test that moved the panel any other way would be moving something the
 * player cannot. See {@code docs/dev-environment.md}.
 *
 * <p>The queries are deliberately split three ways, and which one a scenario wants depends on what
 * it is claiming. {@code ...OnScreen} is what this client has been told, which is what the player can
 * see. {@code ...OnServer} is the authority on what was actually bought and charged. {@code
 * isClickable} is the screen's own answer to "would a click here do anything", which is the same
 * answer the click itself uses — so a square that is scrolled out of sight cannot be clicked by a
 * test either.
 */
final class TestShop {
	private final ClientGameTestContext context;
	private final TestPlayer player;

	TestShop(ClientGameTestContext context, TestPlayer player) {
		this.context = context;
		this.player = player;
	}

	// --- establishing state -------------------------------------------------------------------

	/**
	 * Nothing owned, nothing to spend, no screen open — on the server, where it counts.
	 *
	 * <p>Permanent progression lives outside every world and is shared by every test in this client's
	 * process, so a scenario that depends on a fresh profile has to say so rather than hope it is the
	 * first to run.
	 */
	void resetProgression() {
		context.setScreen(() -> null);
		context.waitTicks(2);
		player.onServer(server -> {
			for (Offer offer : Catalogue.offers()) {
				UnlockState.get().setLevel(offer.id(), 0);
			}
			Wallet.get().set(0);
			UnlockEffects.applyAll(server);
			ShopServer.sendToAll(server);
		});
	}

	/** Opens the shop as the player would, and waits for the screen to actually be up. */
	void open() {
		// As the player, not as the console: the shop opens for whoever asked for it, and the
		// console is nobody.
		player.command("execute as Player0 run mhr shop");
		context.waitForScreen(ShopScreen.class);
		context.waitTicks(3);
		player.settle();
	}

	void close() {
		context.setScreen(() -> null);
		context.waitTicks(2);
		player.settle();
	}

	// --- real input ---------------------------------------------------------------------------

	/** Puts the real cursor on a square and presses the real left button. */
	void clickSquare(String unlockId) {
		hover(unlockId);
		press();
	}

	/** The same click, at a window position of the caller's own choosing. */
	void clickAt(double x, double y) {
		context.getInput().setCursorPos(x, y);
		context.waitTicks(2);
		press();
	}

	void hover(String unlockId) {
		bringIntoView(unlockId);
		if (!isClickable(unlockId)) {
			throw new AssertionError("cannot hover " + unlockId + ": it is not drawn anywhere");
		}
		double[] square = squareOf(unlockId);
		if (square == null) {
			throw new AssertionError("cannot hover " + unlockId + ": the shop has no such offer");
		}
		context.getInput().setCursorPos(square[0], square[1]);
		context.waitTicks(3);
	}

	private void press() {
		// MOUSE_BUTTON_LEFT rather than 0: 26.3 takes its input from SDL, which numbers from one.
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		player.settle();
		context.waitTicks(3);
		player.settle();
	}

	/**
	 * Scroll until a square is on screen, the way a player looking for it would.
	 *
	 * <p>The whole catalogue is one page, but one screen is not the whole page, so anything in the
	 * Vanilla+ section is below the fold on a 1280x720 client. A test that could click it anyway
	 * would be clicking something the player cannot.
	 */
	private void bringIntoView(String unlockId) {
		if (isClickable(unlockId)) {
			return;
		}
		scroll(1, 40);
		for (int i = 0; i < 40; i++) {
			if (isClickable(unlockId)) {
				return;
			}
			scroll(-1, 1);
		}
	}

	void scrollToTheBottom() {
		scroll(-1, 40);
	}

	void scrollToTheTop() {
		scroll(1, 40);
	}

	/** Turns the wheel, which is the screen's own handler and the only way it scrolls. */
	void scroll(int direction, int notches) {
		for (int i = 0; i < notches; i++) {
			context.runOnClient(client -> {
				if (client.gui.screen() instanceof ShopScreen shop) {
					shop.mouseScrolled(0, 0, 0, direction);
				}
			});
		}
		context.waitTicks(2);
	}

	// --- what the screen has ------------------------------------------------------------------

	/** Whether the screen would act on a click there — the same answer the click itself uses. */
	boolean isClickable(String unlockId) {
		return context.computeOnClient(client -> shopScreen(client).isClickable(unlockId));
	}

	double guiScale() {
		return context.computeOnClient(client -> client.getWindow().getGuiScale());
	}

	/** Where a square is in window pixels, whether or not it is drawn there. */
	double[] squareOf(String unlockId) {
		return context.computeOnClient(client -> {
			double[] centre = shopScreen(client).centreOf(unlockId);
			if (centre == null) {
				return null;
			}
			double scale = client.getWindow().getGuiScale();
			return new double[] {centre[0] * scale, centre[1] * scale};
		});
	}

	/** The ids whose squares are on screen right now. */
	List<String> visibleIds(List<Offer> candidates) {
		List<String> visible = new ArrayList<>();
		for (Offer offer : candidates) {
			if (isClickable(offer.id())) {
				visible.add(offer.id());
			}
		}
		return visible;
	}

	/** Every offer this client has been told about. */
	List<Offer> offersOnScreen() {
		return context.computeOnClient(client -> List.copyOf(SyncedShop.offers()));
	}

	int currencyOnScreen() {
		return context.computeOnClient(client -> SyncedShop.currency());
	}

	int levelOnScreen(String unlockId) {
		return fieldOnScreen(unlockId, Offer::level);
	}

	int maxLevelOnScreen(String unlockId) {
		return fieldOnScreen(unlockId, Offer::maxLevel);
	}

	/** The price the screen is showing right now — what the player is being told it costs. */
	int priceOnScreen(String unlockId) {
		return fieldOnScreen(unlockId, Offer::price);
	}

	private int fieldOnScreen(String unlockId, ToIntFunction<Offer> field) {
		return context.computeOnClient(client -> {
			for (Offer offer : SyncedShop.offers()) {
				if (offer.id().equals(unlockId)) {
					return field.applyAsInt(offer);
				}
			}
			return -1;
		});
	}

	/** How many the screen says the chest will hold. */
	int rewardCount(String unlockId) {
		return context.computeOnClient(client -> SyncedShop.reward(unlockId).count());
	}

	/** Whether the screen is drawing the item the chest will actually hold. */
	boolean rewardIs(String unlockId, String itemId) {
		return context.computeOnClient(client -> {
			Reward reward = SyncedShop.reward(unlockId);
			return reward.isSomething() && reward.item().getItem().builtInRegistryHolder()
					.key().identifier().toString().equals(itemId);
		});
	}

	/** What the square is actually drawn as — the last step between the reward and the player. */
	boolean drawnIs(String unlockId, String itemId) {
		return context.computeOnClient(client -> ShopIcons.stackFor(unlockId).getItem()
				.builtInRegistryHolder().key().identifier().toString().equals(itemId));
	}

	private static ShopScreen shopScreen(net.minecraft.client.Minecraft client) {
		if (client.gui.screen() instanceof ShopScreen shop) {
			return shop;
		}
		throw new AssertionError("The shop screen should be open, and the screen is " + client.gui.screen());
	}

	// --- what the server has ------------------------------------------------------------------

	List<Offer> offersOnServer() {
		return player.onServerComputing(server -> List.copyOf(Catalogue.offers()));
	}

	boolean ownedOnServer(String unlockId) {
		return player.onServerComputing(server -> UnlockState.get().isOwned(unlockId));
	}

	int balanceOnServer() {
		return player.onServerComputing(server -> Wallet.get().balance());
	}

	int priceOf(String unlockId) {
		return player.onServerComputing(server -> Catalogue.offer(unlockId)
				.orElseThrow(() -> new AssertionError("The catalogue does not sell " + unlockId))
				.price());
	}

	// --- retuning the catalogue ---------------------------------------------------------------

	/** Retune the catalogue the way a balance edit would, and reload it as a player would. */
	void writeOverride(String json) {
		Path file = player.onServerComputing(server -> BalanceManager.overrideFile());
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, json, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not write the balance override " + file, e);
		}
		player.command("mhr reload");
	}

	void removeOverride() {
		Path file = player.onServerComputing(server -> BalanceManager.overrideFile());
		try {
			Files.deleteIfExists(file);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not remove the balance override " + file, e);
		}
		player.command("mhr reload");
	}
}
