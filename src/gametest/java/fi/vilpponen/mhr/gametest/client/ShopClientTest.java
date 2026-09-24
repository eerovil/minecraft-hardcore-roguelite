package fi.vilpponen.mhr.gametest.client;

import fi.vilpponen.mhr.border.BorderTier;
import fi.vilpponen.mhr.core.BalanceManager;
import fi.vilpponen.mhr.progression.Offer;
import fi.vilpponen.mhr.shop.ShopServer;
import fi.vilpponen.mhr.shop.ShopStatePayload;
import fi.vilpponen.mhr.shop.client.ShopClient;
import fi.vilpponen.mhr.shop.client.ShopScreen;
import fi.vilpponen.mhr.starter.StarterItems;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.List;
import fi.vilpponen.mhr.gametest.mixin.ChatComponentAccessor;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
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

	private static final String VILLAGE = "world.village";
	private static final String RETIRED_TREES = "world.trees";
	private static final String DIAMOND = "world.ore.diamond";
	private static final String ENCHANT = "player.craft.enchant";
	private static final String BREAD = "starter.bread";
	private static final String MEDIUM_BORDER = "world.border.medium";
	private static final String LARGE_BORDER = "world.border.large";

	private final List<String> failures = new ArrayList<>();

	/**
	 * The screen and the purse, driven through {@link TestShop}.
	 *
	 * <p>A field rather than a parameter threaded through twelve scenarios. It is built once the
	 * server and the connection exist, which is why it cannot be final.
	 */
	private TestShop shop;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestDedicatedServerContext server = context.worldBuilder().createServer()) {
			try (TestDedicatedServerConnection connection = server.connect()) {
				connection.waitForChunksRender();
				TestPlayer player = new TestPlayer(context, server, connection);
				shop = new TestShop(context, player);

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
				scenario(context, "a-retuned-starter-item-is-shown-as-what-it-will-grant",
						() -> aRetunedStarterItemIsShownAsWhatItWillGrant(context, player));
				scenario(context, "an-unrelated-purchase-does-not-resize-the-run",
						() -> anUnrelatedPurchaseDoesNotResizeTheRun(context, player));
				scenario(context, "a-balance-reload-reaches-an-open-shop",
						() -> aBalanceReloadReachesAnOpenShop(context, player));
				scenario(context, "a-square-that-is-not-drawn-cannot-be-bought",
						() -> aSquareThatIsNotDrawnCannotBeBought(context, player));
				scenario(context, "buying-a-border-tier-resizes-the-world",
						() -> buyingABorderTierResizesTheWorld(context, player));
				scenario(context, "a-smaller-border-tier-is-not-for-sale-once-a-bigger-one-is-owned",
						() -> aSmallerBorderTierIsNotForSaleOnceABiggerOneIsOwned(context, player));
				scenario(context, "a-client-that-cannot-be-sent-the-shop-is-told-why",
						() -> aClientThatCannotBeSentTheShopIsToldWhy(context, player));
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
		shop.resetProgression();
		shop.open();

		List<Offer> served = shop.offersOnServer();
		List<Offer> shown = shop.offersOnScreen();

		check(!served.isEmpty(), "setup: the catalogue should sell something");
		check(shown.equals(served),
				"the screen must have been told exactly what the server sells, and it was told " + shown.size()
						+ " offer(s) against the server's " + served.size());
		check(shop.currencyOnScreen() == 0,
				"a fresh profile has nothing to spend, and the screen says " + shop.currencyOnScreen());
		// Trees are vanilla from the first run, so the shop has no square for them.
		check(shown.stream().noneMatch(offer -> offer.id().equals(RETIRED_TREES))
						&& shop.squareOf(RETIRED_TREES) == null,
				"the shop must not offer " + RETIRED_TREES + " any more, and it does");

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
		List<String> reachable = new ArrayList<>(shop.visibleIds(served));
		shop.scrollToTheBottom();
		for (String id : shop.visibleIds(served)) {
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
		shop.resetProgression();
		shop.open();

		check(shop.isClickable(VILLAGE),
				"a world unlock must be visible without scrolling, and " + VILLAGE + " is not");
		double[] village = shop.squareOf(VILLAGE);
		double[] bread = shop.squareOf(BREAD);
		check(bread[1] > village[1],
				"Vanilla+ must not be drawn above vanilla restoration: " + VILLAGE + " is at y=" + village[1]
						+ " and " + BREAD + " is at y=" + bread[1]);

		shop.scrollToTheBottom();
		check(shop.isClickable(BREAD), "scrolling down must reach the Vanilla+ purchases, and " + BREAD
				+ " is still not drawn");
		context.takeScreenshot("shop-vanilla-plus-below");
	}

	/** A real click on a real square: the server grants it, charges once, and the screen updates. */
	private void clickingAnAffordableSquareBuysIt(ClientGameTestContext context, TestPlayer player) {
		shop.resetProgression();
		int price = shop.priceOf(VILLAGE);
		player.command("mhr currency set " + (price + 5));
		shop.open();

		check(!shop.ownedOnServer(VILLAGE), "setup: " + VILLAGE + " should start unowned");
		shop.clickSquare(VILLAGE);

		check(shop.ownedOnServer(VILLAGE),
				"clicking an affordable square must buy it, and the server still does not own " + VILLAGE);
		check(shop.balanceOnServer() == 5,
				"the click must have charged exactly " + price + ": the purse went from " + (price + 5)
						+ " to " + shop.balanceOnServer());
		check(shop.levelOnScreen(VILLAGE) == 1,
				"the screen must show the purchase at once, and it still shows level "
						+ shop.levelOnScreen(VILLAGE));
		check(shop.currencyOnScreen() == 5,
				"the screen must show the new total at once, and it shows " + shop.currencyOnScreen());

		context.takeScreenshot("shop-after-buying-village");
	}

	/** Out of reach means out of reach: the click is refused and nothing moves either way. */
	private void clickingAnUnaffordableSquareChangesNothing(ClientGameTestContext context, TestPlayer player) {
		shop.resetProgression();
		int price = shop.priceOf(DIAMOND);
		player.command("mhr currency set " + (price - 1));
		shop.open();

		shop.clickSquare(DIAMOND);

		check(!shop.ownedOnServer(DIAMOND),
				"a square the player cannot afford must not be granted, and the server now owns " + DIAMOND);
		check(shop.balanceOnServer() == price - 1,
				"a refused click must cost nothing, and the purse went from " + (price - 1) + " to "
						+ shop.balanceOnServer());
	}

	/**
	 * The four states the issue asks to be distinguishable, established for real and then read back
	 * off the screen's own copy: owned, part-upgraded, affordable and out of reach.
	 */
	private void ownedAndPartUpgradedStatesReachTheScreen(ClientGameTestContext context, TestPlayer player) {
		shop.resetProgression();
		int enchantPrice = shop.priceOf(ENCHANT);
		player.command("mhr currency set " + (enchantPrice * 2 + shop.priceOf(VILLAGE) + 30));
		shop.open();

		shop.clickSquare(VILLAGE);
		shop.clickSquare(ENCHANT);
		shop.clickSquare(ENCHANT);

		check(shop.levelOnScreen(VILLAGE) == 1, "an owned unlock must show as owned on the screen");
		check(shop.levelOnScreen(ENCHANT) == 2,
				"a repeatable unlock bought twice must show level 2, and the screen shows "
						+ shop.levelOnScreen(ENCHANT));
		check(shop.maxLevelOnScreen(ENCHANT) > 2,
				"setup: the crafted-tool enchant should still have levels left to buy");
		check(shop.currencyOnScreen() < shop.priceOf(DIAMOND),
				"setup: diamond ore should now be out of reach, so the screen has something to grey out");

		// Two evidence shots. The first is the top of the shop with purchases made and something
		// visibly out of reach; the second is the Vanilla+ row with the repeatable part-upgraded.
		// Hovering is where the wordier explanation lives, so both have a tooltip open.
		shop.scrollToTheTop();
		shop.hover(DIAMOND);
		context.takeScreenshot("shop-some-unlocks-owned");

		shop.hover(ENCHANT);
		context.takeScreenshot("shop-vanilla-plus-upgraded");
	}

	/**
	 * A border tier is a purchase like any other, and the world it buys is the world the player is
	 * standing in from that moment on.
	 */
	private void buyingABorderTierResizesTheWorld(ClientGameTestContext context, TestPlayer player) {
		shop.resetProgression();
		double tiny = borderSize(player);
		double wanted = context.computeOnClient(client -> BalanceManager.get()
				.border(BorderTier.MEDIUM.id()).orElseThrow().size().getAsDouble());
		check(Math.abs(tiny - wanted) > 1.0,
				"setup: a fresh profile should start on the tiny border, and the world is already " + tiny
						+ " across");

		player.command("mhr currency set " + shop.priceOf(MEDIUM_BORDER));
		shop.open();
		shop.clickSquare(MEDIUM_BORDER);

		check(shop.ownedOnServer(MEDIUM_BORDER), "the border tier should have been bought");
		double now = borderSize(player);
		check(Math.abs(now - wanted) < 1.0,
				"buying the medium tier must resize the world to " + wanted + ", and it is " + now + " across");

		// Put the world back, so nothing after this is fenced in by a purchase it did not make.
		shop.resetProgression();
	}

	/**
	 * A row scrolled half off the panel is not drawn, and must not be buyable either.
	 *
	 * <p>A scroll notch is smaller than a square, so a square can end up with part of itself still
	 * inside the panel while the whole of it is left undrawn. The blank strip that leaves behind used
	 * to be a live purchase target: nothing to see, and clicking it bought something.
	 */
	private void aSquareThatIsNotDrawnCannotBeBought(ClientGameTestContext context, TestPlayer player) {
		shop.resetProgression();
		int price = shop.priceOf(VILLAGE);
		player.command("mhr currency set " + (price + 9));
		shop.open();

		check(shop.isClickable(VILLAGE), "setup: " + VILLAGE + " should be drawn before anything scrolls");

		// Scroll a notch at a time until the square stops being drawn. The first notch that does it is
		// the dangerous one: a notch is smaller than a square, so at that moment most of the square is
		// still inside the panel with nothing drawn on it.
		int notches = 0;
		while (shop.isClickable(VILLAGE) && notches < 6) {
			shop.scroll(-1, 1);
			notches++;
		}
		check(!shop.isClickable(VILLAGE),
				"scrolling should have taken " + VILLAGE + " off the panel within " + notches
						+ " notches, and it is still drawn");

		// Click the whole of where the square would have been, top strip included.
		double[] centre = shop.squareOf(VILLAGE);
		check(centre != null, "setup: the shop should still know where " + VILLAGE + " is");
		for (int offset = -8; offset <= 8; offset += 4) {
			shop.clickAt(centre[0], centre[1] + offset * shop.guiScale());
		}

		check(!shop.ownedOnServer(VILLAGE),
				"clicking a square that is not drawn must buy nothing, and the server now owns " + VILLAGE);
		check(shop.balanceOnServer() == price + 9,
				"and it must cost nothing: the purse went from " + (price + 9) + " to " + shop.balanceOnServer());

		// The control: the same square, scrolled back into view, is bought by the same click. Without
		// this the scenario would pass just as well if nothing on the screen were clickable at all.
		shop.scrollToTheTop();
		check(shop.isClickable(VILLAGE), "back at the top the square should be drawn again");
		shop.clickSquare(VILLAGE);
		check(shop.ownedOnServer(VILLAGE),
				"the same click must buy it once the square is drawn, and the server still does not own " + VILLAGE);
		check(shop.balanceOnServer() == 9,
				"and then charge exactly once: the purse holds " + shop.balanceOnServer() + " rather than 9");
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
		shop.resetProgression();
		int original = shop.priceOf(VILLAGE);
		int retuned = original + 41;
		player.command("mhr currency set " + (retuned + 6));
		shop.open();

		check(shop.priceOnScreen(VILLAGE) == original,
				"setup: the screen should start showing the bundled price, and it shows "
						+ shop.priceOnScreen(VILLAGE));

		try {
			shop.writeOverride("{\"unlocks\": {\"" + VILLAGE + "\": {\"price\": " + retuned + "}}}");

			check(shop.priceOnScreen(VILLAGE) == retuned,
					"a reload must reach the open screen: it should now show " + retuned + " and it shows "
							+ shop.priceOnScreen(VILLAGE));

			// And the price it shows is the price it takes. Clicking without reopening anything.
			shop.clickSquare(VILLAGE);
			check(shop.ownedOnServer(VILLAGE), "the click should still buy it");
			check(shop.balanceOnServer() == 6,
					"the charge must match the price on the screen: the purse went from " + (retuned + 6)
							+ " to " + shop.balanceOnServer());
		} finally {
			shop.removeOverride();
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
		shop.resetProgression();
		int largePrice = shop.priceOf(LARGE_BORDER);
		int spare = shop.priceOf(MEDIUM_BORDER) + 7;
		player.command("mhr currency set " + (largePrice + spare));
		shop.open();

		shop.clickSquare(LARGE_BORDER);
		check(shop.ownedOnServer(LARGE_BORDER), "setup: the large tier should have been bought");
		double large = borderSize(player);
		check(shop.balanceOnServer() == spare,
				"setup: and should have cost exactly " + largePrice + ", leaving " + spare);

		// The screen has to stop offering it, not offer it and then have the click refused.
		check(shop.levelOnScreen(MEDIUM_BORDER) > 0,
				"a tier smaller than the one owned must show as owned on the screen, and it shows level "
						+ shop.levelOnScreen(MEDIUM_BORDER));

		shop.clickSquare(MEDIUM_BORDER);

		check(shop.balanceOnServer() == spare,
				"clicking it must cost nothing: the purse went from " + spare + " to "
						+ shop.balanceOnServer());
		check(!shop.ownedOnServer(MEDIUM_BORDER),
				"and must write nothing: what was bought is what is recorded");
		check(Math.abs(borderSize(player) - large) < 1.0,
				"and the world must still be the size the large tier made it: it is " + borderSize(player)
						+ " across rather than " + large);

		// Put the world back, so nothing after this is fenced in by a purchase it did not make.
		shop.resetProgression();
	}

	/**
	 * What the shop shows a starter item as, and what the chest will hold, are the same fact.
	 *
	 * <p>A starter item's whole effect is a stack in the balance catalogue, and the override file is
	 * allowed to replace it. The shop used to describe it from an icon file and a line in the
	 * language file, so retuning the count left the screen promising sixteen bread while
	 * {@code ownedStacks} built sixty-four.
	 */
	private void aRetunedStarterItemIsShownAsWhatItWillGrant(
			ClientGameTestContext context, TestPlayer player) {
		shop.resetProgression();
		shop.open();

		int shipped = grantedCount(player, BREAD);
		check(shipped > 1, "setup: the shipped bread should be more than one, and it is " + shipped);
		check(shop.rewardCount(BREAD) == shipped,
				"the screen should start by showing what the chest will hold, " + shipped
						+ ", and it shows " + shop.rewardCount(BREAD));

		try {
			shop.writeOverride("{\"unlocks\": {\"" + BREAD
					+ "\": {\"item\": {\"id\": \"minecraft:cooked_beef\", \"count\": 64}}}}");

			check(grantedCount(player, BREAD) == 64,
					"setup: the retuned catalogue should grant 64, and it grants " + grantedCount(player, BREAD));
			check(shop.rewardCount(BREAD) == 64,
					"the open screen must follow it: it should show 64 and it shows "
							+ shop.rewardCount(BREAD));
			check(shop.rewardIs(BREAD, "minecraft:cooked_beef"),
					"and must show the item the chest will actually hold, not the one it used to");
			check(shop.drawnIs(BREAD, "minecraft:cooked_beef"),
					"and the square must be drawn as that item: knowing the reward and drawing something"
							+ " else is the same lie in a different place");
		} finally {
			shop.removeOverride();
		}

		check(shop.rewardCount(BREAD) == shipped,
				"taking the override away must put the shipped reward back, and the screen shows "
						+ shop.rewardCount(BREAD));
		check(shop.drawnIs(BREAD, "minecraft:bread"),
				"and the square must be drawn as bread again");
	}

	/**
	 * A reload does not resize a run, and neither does anything that happens afterwards.
	 *
	 * <p>Every purchase asks the border to look at the unlocks again. It used to re-apply whatever
	 * it found, and applying a tier reads its size out of the balance in effect — so buying a village
	 * an hour after a reload would quietly hand the run the new size. {@code /mhr reload} promises
	 * the opposite.
	 */
	private void anUnrelatedPurchaseDoesNotResizeTheRun(ClientGameTestContext context, TestPlayer player) {
		shop.resetProgression();
		int mediumPrice = shop.priceOf(MEDIUM_BORDER);
		int largePrice = shop.priceOf(LARGE_BORDER);
		player.command("mhr currency set " + (mediumPrice + largePrice + shop.priceOf(VILLAGE) + 5));
		shop.open();

		shop.clickSquare(MEDIUM_BORDER);
		double started = borderSize(player);
		check(shop.ownedOnServer(MEDIUM_BORDER), "setup: the medium tier should have been bought");

		try {
			// A size a run in progress must not be given, because it is already inside the old one.
			shop.writeOverride("{\"worldBorder\": {\"medium\": {\"size\": 256}}}");
			check(Math.abs(borderSize(player) - started) < 1.0,
					"setup: the reload itself must leave the live border alone, and it is now "
							+ borderSize(player));

			shop.clickSquare(VILLAGE);

			check(shop.ownedOnServer(VILLAGE), "setup: the unrelated purchase should have gone through");
			check(Math.abs(borderSize(player) - started) < 1.0,
					"buying something that is not a border must not resize the run: it was " + started
							+ " across and is now " + borderSize(player));

			// The control: a tier that really is a change still changes it, so this is not "the
			// border never moves again".
			shop.clickSquare(LARGE_BORDER);
			check(borderSize(player) > started,
					"buying a bigger tier must still resize the run, and it is " + borderSize(player)
							+ " across against " + started);
		} finally {
			shop.removeOverride();
		}

		shop.resetProgression();
	}

	/**
	 * The dedicated server takes anyone. So a player can ask for the shop from a client that has no
	 * way of drawing one, and used to get nothing whatsoever back: the server checked whether it
	 * could send the screen and returned when it could not, which from the chair looks exactly like
	 * the shop being broken.
	 *
	 * <p>Played for real rather than faked: the client stops listening for the shop, which is what
	 * having no mod amounts to on the wire, and the server is asked to confirm it has heard so
	 * before the command is run. The channel is then taken back and the same command opens the shop
	 * again — the control, without which this scenario would pass just as well if {@code /mhr shop}
	 * had stopped working altogether.
	 */
	private void aClientThatCannotBeSentTheShopIsToldWhy(ClientGameTestContext context, TestPlayer player) {
		shop.resetProgression();
		closeAnyScreen(context);
		check(!wasToldOnTheClient(context, CLIENT_MOD_REQUIRED),
				"setup: nothing should have said this yet, or the check below would pass on an old message");

		dropTheShopChannel(context, player);
		try {
			check(!canBeSentTheShop(player),
					"setup: the server should have heard that this client no longer listens for the shop");

			player.command("execute as Player0 run mhr shop");
			context.waitTicks(20);
			player.settle();

			check(!shopIsOpen(context),
					"no shop may open for a client that cannot be sent one, and the screen is "
							+ screenName(context));
			waitToBeToldOnTheClient(context, CLIENT_MOD_REQUIRED);
			check(wasToldOnTheClient(context, CLIENT_MOD_REQUIRED),
					"the player must be told why the shop did not open. Chat said: " + clientMessages(context));

			// The evidence shot: what the player is left looking at. No screen, and a line in the
			// chat saying why, taken on the passing path rather than on failure.
			context.takeScreenshot("shop-client-mod-required");
		} finally {
			takeTheShopChannelBack(context, player);
		}

		check(canBeSentTheShop(player), "the control: the channel should be back");
		shop.open();
		check(shopIsOpen(context),
				"and the same command must open the shop again, so the refusal was about the client and"
						+ " nothing else");
		closeAnyScreen(context);
	}

	// --- asking the feature the purchase is about ---------------------------------------------

	/** How many the chest will actually hold, from the server's own catalogue. */
	private int grantedCount(TestPlayer player, String unlockId) {
		return player.onServerComputing(server -> {
			ItemStack stack = StarterItems.stackFor(unlockId, server.registryAccess());
			return stack.getCount();
		});
	}

	private double borderSize(TestPlayer player) {
		return player.onServerComputing(server -> server.overworld().getWorldBorder().getSize());
	}

	// --- playing a client without the mod -----------------------------------------------------

	/** What a client with no mod of ours is told. Spelled out here, as the player reads it. */
	private static final String CLIENT_MOD_REQUIRED =
			"Hardcore Roguelite client mod is required to open the shop.";

	/**
	 * Stop listening for the shop, and wait until the server knows.
	 *
	 * <p>A registered receiver is what puts the channel in the list this client sends the server, so
	 * taking it away is the same fact on the wire as never having had the mod. The announcement
	 * travels, so the server is asked until it agrees rather than after a fixed wait.
	 */
	private void dropTheShopChannel(ClientGameTestContext context, TestPlayer player) {
		context.runOnClient(client -> ClientPlayNetworking.unregisterGlobalReceiver(ShopStatePayload.TYPE.id()));
		waitUntilTheServerAgrees(context, player, false);
	}

	private void takeTheShopChannelBack(ClientGameTestContext context, TestPlayer player) {
		context.runOnClient(client -> ShopClient.registerReceiver());
		waitUntilTheServerAgrees(context, player, true);
	}

	private void waitUntilTheServerAgrees(ClientGameTestContext context, TestPlayer player, boolean listening) {
		for (int attempt = 0; attempt < 40; attempt++) {
			if (canBeSentTheShop(player) == listening) {
				return;
			}
			context.waitTicks(2);
		}
	}

	/** The server's own answer to "can this client be shown the shop" — the check the command uses. */
	private boolean canBeSentTheShop(TestPlayer player) {
		return player.onServerComputing(server ->
				ShopServer.canReceive(server.getPlayerList().getPlayers().get(0)));
	}

	private boolean shopIsOpen(ClientGameTestContext context) {
		return context.computeOnClient(client -> client.gui.screen() instanceof ShopScreen);
	}

	private String screenName(ClientGameTestContext context) {
		return context.computeOnClient(client -> String.valueOf(client.gui.screen()));
	}

	private void closeAnyScreen(ClientGameTestContext context) {
		context.setScreen(() -> null);
		context.waitTicks(2);
	}

	/** Give the client a while to be shown this. */
	private void waitToBeToldOnTheClient(ClientGameTestContext context, String fragment) {
		for (int attempt = 0; attempt < 60; attempt++) {
			if (wasToldOnTheClient(context, fragment)) {
				return;
			}
			context.waitTicks(2);
		}
	}

	/** Has the client been shown a message containing this? */
	private boolean wasToldOnTheClient(ClientGameTestContext context, String fragment) {
		return clientMessages(context).contains(fragment);
	}

	private String clientMessages(ClientGameTestContext context) {
		return context.computeOnClient(client -> {
			StringBuilder said = new StringBuilder();
			for (GuiMessage message : ((ChatComponentAccessor) client.gui.hud.getChat()).mhr$allMessages()) {
				said.append(message.content().getString()).append(" | ");
			}
			return said.toString();
		});
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
