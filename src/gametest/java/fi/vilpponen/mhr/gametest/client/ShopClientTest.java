package fi.vilpponen.mhr.gametest.client;

import com.mojang.blaze3d.platform.InputConstants;
import fi.vilpponen.mhr.UnlockEffects;
import fi.vilpponen.mhr.UnlockState;
import fi.vilpponen.mhr.border.BorderTier;
import fi.vilpponen.mhr.core.BalanceManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import fi.vilpponen.mhr.progression.Catalogue;
import fi.vilpponen.mhr.progression.Offer;
import fi.vilpponen.mhr.progression.Wallet;
import fi.vilpponen.mhr.shop.ShopServer;
import fi.vilpponen.mhr.shop.client.ShopScreen;
import fi.vilpponen.mhr.shop.client.SyncedShop;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The shop as a player meets it: a real screen, a real mouse, a real dedicated server.
 *
 * <p>What these scenarios prove that the server test cannot is that the screen and the server agree
 * — that the squares are where the player sees them, that clicking one is what charges the purse,
 * and that the screen shows the purchase the moment the server allows it. Nothing here calls a
 * purchase helper: every buy is the cursor landing on a square and the left button going down.
 *
 * <p>The screenshots are deliberate evidence rather than debris. They are the states the issue asks
 * to see — near-fresh progression, part-way through, and the vanilla-restoration heading sitting
 * above the Vanilla+ one — and they are taken on the passing path, not only on failure.
 *
 * <p>The world border is bought here too, because owning a tier resizes the world and this is the
 * only place with a dedicated server of its own to resize.
 *
 * <p>See {@code docs/dev-environment.md} for how to run this.
 */
public class ShopClientTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("mhr-gametest");

	private static final String TREES = "world.trees";
	private static final String DIAMOND = "world.ore.diamond";
	private static final String ENCHANT = "player.craft.enchant";
	private static final String BREAD = "starter.bread";
	private static final String MEDIUM_BORDER = "world.border.medium";
	private static final String LARGE_BORDER = "world.border.large";

	private final List<String> failures = new ArrayList<>();

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestDedicatedServerContext server = context.worldBuilder().createServer()) {
			try (TestDedicatedServerConnection connection = server.connect()) {
				connection.waitForChunksRender();
				TestPlayer player = new TestPlayer(context, server, connection);

				scenario(context, "the-shop-opens-with-the-whole-catalogue-on-it",
						() -> theShopOpensWithTheWholeCatalogueOnIt(context, player));
				scenario(context, "vanilla-restoration-is-drawn-above-vanilla-plus",
						() -> vanillaRestorationIsDrawnAboveVanillaPlus(context, player));
				scenario(context, "clicking-an-affordable-square-buys-it",
						() -> clickingAnAffordableSquareBuysIt(context, player));
				scenario(context, "clicking-an-unaffordable-square-changes-nothing",
						() -> clickingAnUnaffordableSquareChangesNothing(context, player));
				scenario(context, "owned-and-part-upgraded-states-reach-the-screen",
						() -> ownedAndPartUpgradedStatesReachTheScreen(context, player));
				scenario(context, "a-balance-reload-reaches-an-open-shop",
						() -> aBalanceReloadReachesAnOpenShop(context, player));
				scenario(context, "a-square-that-is-not-drawn-cannot-be-bought",
						() -> aSquareThatIsNotDrawnCannotBeBought(context, player));
				scenario(context, "buying-a-border-tier-resizes-the-world",
						() -> buyingABorderTierResizesTheWorld(context, player));
				scenario(context, "a-smaller-border-tier-is-not-for-sale-once-a-bigger-one-is-owned",
						() -> aSmallerBorderTierIsNotForSaleOnceABiggerOneIsOwned(context, player));
			}
		}

		if (!failures.isEmpty()) {
			throw new AssertionError(failures.size() + " shop client scenario(s) failed:\n  "
					+ String.join("\n  ", failures));
		}
		LOGGER.info("All shop client scenarios passed.");
	}

	// --- the scenarios ---------------------------------------------------------------------

	/**
	 * Everything the catalogue sells is on the one screen, with nothing to click through to find
	 * it, and the prices on it are the server's.
	 */
	private void theShopOpensWithTheWholeCatalogueOnIt(ClientGameTestContext context, TestPlayer player) {
		reset(player);
		openShop(context, player);

		List<Offer> served = serverOffers(player);
		List<Offer> shown = context.computeOnClient(client -> List.copyOf(SyncedShop.offers()));

		check(!served.isEmpty(), "setup: the catalogue should sell something");
		check(shown.equals(served),
				"the screen must have been told exactly what the server sells, and it was told " + shown.size()
						+ " offer(s) against the server's " + served.size());
		check(currencyOnScreen(context) == 0,
				"a fresh profile has nothing to spend, and the screen says " + currencyOnScreen(context));

		// Vanilla's join toasts sit over the title for a few seconds. Letting them expire once here
		// keeps them out of every evidence shot, including the later scenarios'.
		context.waitTicks(140);
		context.getInput().setCursorPos(2, 2);
		context.waitTicks(2);

		// Taken before anything scrolls, because this is the evidence shot of a fresh profile.
		context.takeScreenshot("shop-fresh-progression");

		// Everything is reachable by scrolling one page, with nothing to click through to find it.
		// Two looks are enough to prove that: what is on screen at the top, and what is on screen at
		// the bottom, have to cover the catalogue between them.
		List<String> reachable = new ArrayList<>(visibleIds(context, served));
		scrollToTheBottom(context);
		for (String id : visibleIds(context, served)) {
			if (!reachable.contains(id)) {
				reachable.add(id);
			}
		}
		for (Offer offer : served) {
			check(reachable.contains(offer.id()),
					"every offer must be on the one scrolling page, and " + offer.id()
							+ " never appears. Reachable: " + reachable);
		}

	}

	/**
	 * The hierarchy the design asks for, measured on the screen rather than in the layout code:
	 * restoring vanilla is what you see first, and Vanilla+ is below it.
	 */
	private void vanillaRestorationIsDrawnAboveVanillaPlus(ClientGameTestContext context, TestPlayer player) {
		reset(player);
		openShop(context, player);

		check(isClickable(context, TREES),
				"a world unlock must be visible without scrolling, and " + TREES + " is not");
		double[] trees = squareOf(context, TREES);
		double[] bread = squareOf(context, BREAD);
		check(bread[1] > trees[1],
				"Vanilla+ must not be drawn above vanilla restoration: " + TREES + " is at y=" + trees[1]
						+ " and " + BREAD + " is at y=" + bread[1]);

		scrollToTheBottom(context);
		check(isClickable(context, BREAD), "scrolling down must reach the Vanilla+ purchases, and " + BREAD
				+ " is still not drawn");
		context.takeScreenshot("shop-vanilla-plus-below");
	}

	/** A real click on a real square: the server grants it, charges once, and the screen updates. */
	private void clickingAnAffordableSquareBuysIt(ClientGameTestContext context, TestPlayer player) {
		reset(player);
		int price = priceOf(player, TREES);
		player.command("mhr currency set " + (price + 5));
		openShop(context, player);

		check(!ownedOnServer(player, TREES), "setup: " + TREES + " should start unowned");
		clickSquare(context, player, TREES);

		check(ownedOnServer(player, TREES),
				"clicking an affordable square must buy it, and the server still does not own " + TREES);
		check(balanceOnServer(player) == 5,
				"the click must have charged exactly " + price + ": the purse went from " + (price + 5)
						+ " to " + balanceOnServer(player));
		check(levelOnScreen(context, TREES) == 1,
				"the screen must show the purchase at once, and it still shows level "
						+ levelOnScreen(context, TREES));
		check(currencyOnScreen(context) == 5,
				"the screen must show the new total at once, and it shows " + currencyOnScreen(context));

		context.takeScreenshot("shop-after-buying-trees");
	}

	/** Out of reach means out of reach: the click is refused and nothing moves either way. */
	private void clickingAnUnaffordableSquareChangesNothing(ClientGameTestContext context, TestPlayer player) {
		reset(player);
		int price = priceOf(player, DIAMOND);
		player.command("mhr currency set " + (price - 1));
		openShop(context, player);

		clickSquare(context, player, DIAMOND);

		check(!ownedOnServer(player, DIAMOND),
				"a square the player cannot afford must not be granted, and the server now owns " + DIAMOND);
		check(balanceOnServer(player) == price - 1,
				"a refused click must cost nothing, and the purse went from " + (price - 1) + " to "
						+ balanceOnServer(player));
	}

	/**
	 * The four states the issue asks to be distinguishable, established for real and then read back
	 * off the screen's own copy: owned, part-upgraded, affordable and out of reach.
	 */
	private void ownedAndPartUpgradedStatesReachTheScreen(ClientGameTestContext context, TestPlayer player) {
		reset(player);
		int enchantPrice = priceOf(player, ENCHANT);
		player.command("mhr currency set " + (enchantPrice * 2 + priceOf(player, TREES) + 30));
		openShop(context, player);

		clickSquare(context, player, TREES);
		clickSquare(context, player, ENCHANT);
		clickSquare(context, player, ENCHANT);

		check(levelOnScreen(context, TREES) == 1, "an owned unlock must show as owned on the screen");
		check(levelOnScreen(context, ENCHANT) == 2,
				"a repeatable unlock bought twice must show level 2, and the screen shows "
						+ levelOnScreen(context, ENCHANT));
		check(maxLevelOnScreen(context, ENCHANT) > 2,
				"setup: the crafted-tool enchant should still have levels left to buy");
		check(currencyOnScreen(context) < priceOf(player, DIAMOND),
				"setup: diamond ore should now be out of reach, so the screen has something to grey out");

		// Two evidence shots. The first is the top of the shop with purchases made and something
		// visibly out of reach; the second is the Vanilla+ row with the repeatable part-upgraded.
		// Hovering is where the wordier explanation lives, so both have a tooltip open.
		scrollToTheTop(context);
		hover(context, DIAMOND);
		context.takeScreenshot("shop-some-unlocks-owned");

		hover(context, ENCHANT);
		context.takeScreenshot("shop-vanilla-plus-upgraded");
	}

	/**
	 * A border tier is a purchase like any other, and the world it buys is the world the player is
	 * standing in from that moment on.
	 */
	private void buyingABorderTierResizesTheWorld(ClientGameTestContext context, TestPlayer player) {
		reset(player);
		double tiny = borderSize(player);
		double wanted = context.computeOnClient(client -> BalanceManager.get()
				.border(BorderTier.MEDIUM.id()).orElseThrow().size().getAsDouble());
		check(Math.abs(tiny - wanted) > 1.0,
				"setup: a fresh profile should start on the tiny border, and the world is already " + tiny
						+ " across");

		player.command("mhr currency set " + priceOf(player, MEDIUM_BORDER));
		openShop(context, player);
		clickSquare(context, player, MEDIUM_BORDER);

		check(ownedOnServer(player, MEDIUM_BORDER), "the border tier should have been bought");
		double now = borderSize(player);
		check(Math.abs(now - wanted) < 1.0,
				"buying the medium tier must resize the world to " + wanted + ", and it is " + now + " across");

		// Put the world back, so nothing after this is fenced in by a purchase it did not make.
		reset(player);
	}

	/**
	 * A row scrolled half off the panel is not drawn, and must not be buyable either.
	 *
	 * <p>A scroll notch is smaller than a square, so a square can end up with part of itself still
	 * inside the panel while the whole of it is left undrawn. The blank strip that leaves behind used
	 * to be a live purchase target: nothing to see, and clicking it bought something.
	 */
	private void aSquareThatIsNotDrawnCannotBeBought(ClientGameTestContext context, TestPlayer player) {
		reset(player);
		int price = priceOf(player, TREES);
		player.command("mhr currency set " + (price + 9));
		openShop(context, player);

		check(isClickable(context, TREES), "setup: " + TREES + " should be drawn before anything scrolls");

		// Scroll a notch at a time until the square stops being drawn. The first notch that does it is
		// the dangerous one: a notch is smaller than a square, so at that moment most of the square is
		// still inside the panel with nothing drawn on it.
		int notches = 0;
		while (isClickable(context, TREES) && notches < 6) {
			scroll(context, -1, 1);
			notches++;
		}
		check(!isClickable(context, TREES),
				"scrolling should have taken " + TREES + " off the panel within " + notches
						+ " notches, and it is still drawn");

		// Click the whole of where the square would have been, top strip included.
		double[] centre = squareOf(context, TREES);
		check(centre != null, "setup: the shop should still know where " + TREES + " is");
		for (int offset = -8; offset <= 8; offset += 4) {
			clickAt(context, player, centre[0], centre[1] + offset * guiScale(context));
		}

		check(!ownedOnServer(player, TREES),
				"clicking a square that is not drawn must buy nothing, and the server now owns " + TREES);
		check(balanceOnServer(player) == price + 9,
				"and it must cost nothing: the purse went from " + (price + 9) + " to " + balanceOnServer(player));

		// The control: the same square, scrolled back into view, is bought by the same click. Without
		// this the scenario would pass just as well if nothing on the screen were clickable at all.
		scrollToTheTop(context);
		check(isClickable(context, TREES), "back at the top the square should be drawn again");
		clickSquare(context, player, TREES);
		check(ownedOnServer(player, TREES),
				"the same click must buy it once the square is drawn, and the server still does not own " + TREES);
		check(balanceOnServer(player) == 9,
				"and then charge exactly once: the purse holds " + balanceOnServer(player) + " rather than 9");
	}

	/**
	 * A price change reaches a shop that is already open, and the price it then shows is the price
	 * it charges.
	 *
	 * <p>The screen only ever knows what it was last sent. Before this, {@code /mhr reload} changed
	 * what the server charged without telling anyone, so an open shop went on advertising the old
	 * price — and the first the player heard of it was being charged something else.
	 */
	private void aBalanceReloadReachesAnOpenShop(ClientGameTestContext context, TestPlayer player) {
		reset(player);
		int original = priceOf(player, TREES);
		int retuned = original + 41;
		player.command("mhr currency set " + (retuned + 6));
		openShop(context, player);

		check(priceOnScreen(context, TREES) == original,
				"setup: the screen should start showing the bundled price, and it shows "
						+ priceOnScreen(context, TREES));

		try {
			writeOverride(player, "{\"unlocks\": {\"" + TREES + "\": {\"price\": " + retuned + "}}}");

			check(priceOnScreen(context, TREES) == retuned,
					"a reload must reach the open screen: it should now show " + retuned + " and it shows "
							+ priceOnScreen(context, TREES));

			// And the price it shows is the price it takes. Clicking without reopening anything.
			clickSquare(context, player, TREES);
			check(ownedOnServer(player, TREES), "the click should still buy it");
			check(balanceOnServer(player) == 6,
					"the charge must match the price on the screen: the purse went from " + (retuned + 6)
							+ " to " + balanceOnServer(player));
		} finally {
			removeOverride(player);
		}
	}

	/**
	 * The tiers are steps, and the run gets the largest one owned. So once Large is bought, clicking
	 * Medium cannot change the world — and it must not be able to take the price for trying.
	 *
	 * <p>The half that needs a real world: the border is actually applied here, so what is checked
	 * at the end is the size of the world the player is standing in.
	 */
	private void aSmallerBorderTierIsNotForSaleOnceABiggerOneIsOwned(
			ClientGameTestContext context, TestPlayer player) {
		reset(player);
		int largePrice = priceOf(player, LARGE_BORDER);
		int spare = priceOf(player, MEDIUM_BORDER) + 7;
		player.command("mhr currency set " + (largePrice + spare));
		openShop(context, player);

		clickSquare(context, player, LARGE_BORDER);
		check(ownedOnServer(player, LARGE_BORDER), "setup: the large tier should have been bought");
		double large = borderSize(player);
		check(balanceOnServer(player) == spare,
				"setup: and should have cost exactly " + largePrice + ", leaving " + spare);

		// The screen has to stop offering it, not offer it and then have the click refused.
		check(levelOnScreen(context, MEDIUM_BORDER) > 0,
				"a tier smaller than the one owned must show as owned on the screen, and it shows level "
						+ levelOnScreen(context, MEDIUM_BORDER));

		clickSquare(context, player, MEDIUM_BORDER);

		check(balanceOnServer(player) == spare,
				"clicking it must cost nothing: the purse went from " + spare + " to "
						+ balanceOnServer(player));
		check(!ownedOnServer(player, MEDIUM_BORDER),
				"and must write nothing: what was bought is what is recorded");
		check(Math.abs(borderSize(player) - large) < 1.0,
				"and the world must still be the size the large tier made it: it is " + borderSize(player)
						+ " across rather than " + large);

		// Put the world back, so nothing after this is fenced in by a purchase it did not make.
		reset(player);
	}

	// --- talking to the shop ------------------------------------------------------------------

	/** Nothing owned, nothing to spend, no screen open — on the server, where it counts. */
	private void reset(TestPlayer player) {
		player.onServer(server -> {
			for (Offer offer : Catalogue.offers()) {
				UnlockState.get().setLevel(offer.id(), 0);
			}
			Wallet.get().set(0);
			UnlockEffects.applyAll(server);
			ShopServer.sendToAll(server);
		});
	}

	private void openShop(ClientGameTestContext context, TestPlayer player) {
		// As the player, not as the console: the shop opens for whoever asked for it, and the
		// console is nobody.
		player.command("execute as Player0 run mhr shop");
		context.waitForScreen(ShopScreen.class);
		context.waitTicks(3);
		player.settle();
	}

	/** Whether the screen would act on a click there — the same answer the click itself uses. */
	private boolean isClickable(ClientGameTestContext context, String unlockId) {
		return context.computeOnClient(client -> shopScreen(client).isClickable(unlockId));
	}

	private double guiScale(ClientGameTestContext context) {
		return context.computeOnClient(client -> client.getWindow().getGuiScale());
	}

	/** Where a square is in window pixels, whether or not it is drawn there. */
	private double[] squareOf(ClientGameTestContext context, String unlockId) {
		return context.computeOnClient(client -> {
			double[] centre = shopScreen(client).centreOf(unlockId);
			if (centre == null) {
				return null;
			}
			double scale = client.getWindow().getGuiScale();
			return new double[] {centre[0] * scale, centre[1] * scale};
		});
	}

	private static ShopScreen shopScreen(net.minecraft.client.Minecraft client) {
		if (client.gui.screen() instanceof ShopScreen shop) {
			return shop;
		}
		throw new AssertionError("The shop screen should be open, and the screen is " + client.gui.screen());
	}

	/** The ids whose squares are on screen right now. */
	private List<String> visibleIds(ClientGameTestContext context, List<Offer> candidates) {
		List<String> visible = new ArrayList<>();
		for (Offer offer : candidates) {
			if (isClickable(context, offer.id())) {
				visible.add(offer.id());
			}
		}
		return visible;
	}

	private void hover(ClientGameTestContext context, String unlockId) {
		bringIntoView(context, unlockId);
		check(isClickable(context, unlockId), "cannot hover " + unlockId + ": it is not drawn anywhere");
		double[] square = squareOf(context, unlockId);
		check(square != null, "cannot hover " + unlockId + ": the shop has no such offer");
		context.getInput().setCursorPos(square[0], square[1]);
		context.waitTicks(3);
	}

	/** Puts the real cursor on a square and presses the real left button. */
	private void clickSquare(ClientGameTestContext context, TestPlayer player, String unlockId) {
		hover(context, unlockId);
		press(context, player);
	}

	/** The same click, at a window position of the test's own choosing. */
	private void clickAt(ClientGameTestContext context, TestPlayer player, double x, double y) {
		context.getInput().setCursorPos(x, y);
		context.waitTicks(2);
		press(context, player);
	}

	private void press(ClientGameTestContext context, TestPlayer player) {
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
	private void bringIntoView(ClientGameTestContext context, String unlockId) {
		if (isClickable(context, unlockId)) {
			return;
		}
		scroll(context, 1, 40);
		for (int i = 0; i < 40; i++) {
			if (isClickable(context, unlockId)) {
				return;
			}
			scroll(context, -1, 1);
		}
	}

	private void scrollToTheBottom(ClientGameTestContext context) {
		scroll(context, -1, 40);
	}

	private void scrollToTheTop(ClientGameTestContext context) {
		scroll(context, 1, 40);
	}

	/** Turns the wheel, which is the screen's own handler and the only way it scrolls. */
	private void scroll(ClientGameTestContext context, int direction, int notches) {
		for (int i = 0; i < notches; i++) {
			context.runOnClient(client -> {
				if (client.gui.screen() instanceof ShopScreen shop) {
					shop.mouseScrolled(0, 0, 0, direction);
				}
			});
		}
		context.waitTicks(2);
	}

	// --- asking the two sides -----------------------------------------------------------------

	private int levelOnScreen(ClientGameTestContext context, String unlockId) {
		return context.computeOnClient(client -> {
			for (Offer offer : SyncedShop.offers()) {
				if (offer.id().equals(unlockId)) {
					return offer.level();
				}
			}
			return -1;
		});
	}

	private int maxLevelOnScreen(ClientGameTestContext context, String unlockId) {
		return context.computeOnClient(client -> {
			for (Offer offer : SyncedShop.offers()) {
				if (offer.id().equals(unlockId)) {
					return offer.maxLevel();
				}
			}
			return -1;
		});
	}

	/** The price the screen is showing right now — what the player is being told it costs. */
	private int priceOnScreen(ClientGameTestContext context, String unlockId) {
		return context.computeOnClient(client -> {
			for (Offer offer : SyncedShop.offers()) {
				if (offer.id().equals(unlockId)) {
					return offer.price();
				}
			}
			return -1;
		});
	}

	/** Retune the catalogue the way a balance edit would, and reload it as a player would. */
	private void writeOverride(TestPlayer player, String json) {
		Path file = player.onServerComputing(server -> BalanceManager.overrideFile());
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, json, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not write the balance override " + file, e);
		}
		player.command("mhr reload");
	}

	private void removeOverride(TestPlayer player) {
		Path file = player.onServerComputing(server -> BalanceManager.overrideFile());
		try {
			Files.deleteIfExists(file);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not remove the balance override " + file, e);
		}
		player.command("mhr reload");
	}

	private int currencyOnScreen(ClientGameTestContext context) {
		return context.computeOnClient(client -> SyncedShop.currency());
	}

	private List<Offer> serverOffers(TestPlayer player) {
		return player.onServerComputing(server -> List.copyOf(Catalogue.offers()));
	}

	private boolean ownedOnServer(TestPlayer player, String unlockId) {
		return player.onServerComputing(server -> UnlockState.get().isOwned(unlockId));
	}

	private int balanceOnServer(TestPlayer player) {
		return player.onServerComputing(server -> Wallet.get().balance());
	}

	private int priceOf(TestPlayer player, String unlockId) {
		return player.onServerComputing(server -> Catalogue.offer(unlockId)
				.orElseThrow(() -> new AssertionError("The catalogue does not sell " + unlockId))
				.price());
	}

	private double borderSize(TestPlayer player) {
		return player.onServerComputing(server -> server.overworld().getWorldBorder().getSize());
	}

	// --- plumbing --------------------------------------------------------------------------

	private void scenario(ClientGameTestContext context, String name, Runnable body) {
		LOGGER.info("=== scenario {} ===", name);
		try {
			body.run();
			LOGGER.info("=== scenario {}: PASS ===", name);
		} catch (Throwable failure) {
			failures.add(name + ": " + failure.getMessage());
			LOGGER.error("=== scenario {}: FAIL === {}", name, failure.getMessage(), failure);
			try {
				context.takeScreenshot("failed-" + name);
			} catch (Throwable ignored) {
				LOGGER.warn("Could not screenshot the failure of {}", name);
			}
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
