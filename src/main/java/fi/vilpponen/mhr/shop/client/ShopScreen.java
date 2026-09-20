package fi.vilpponen.mhr.shop.client;

import com.mojang.blaze3d.platform.InputConstants;
import fi.vilpponen.mhr.progression.Offer;
import fi.vilpponen.mhr.shop.Reward;
import fi.vilpponen.mhr.shop.ShopBuyPayload;
import fi.vilpponen.mhr.shop.ShopLayout;
import fi.vilpponen.mhr.shop.ShopText;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

/**
 * The shop: everything permanent the player can buy, on one screen.
 *
 * <p>The arrangement is the design's own hierarchy and comes from {@code shop-layout.json} rather
 * than from anything written here — vanilla restoration first and largest, Vanilla+ below it. There
 * is no category navigation and nothing is nested: the whole catalogue is on one scrolling page, so
 * "what can I work towards?" is answered by looking rather than by clicking through menus.
 *
 * <p>Each offer is one small square: the icon says what it is, the number under it says what it
 * costs, and the colour says whether that is within reach. Everything wordier than that — what an
 * unlock actually does, what level a repeatable one is at, why a click would be refused — is in the
 * hover tooltip, so the persistent layout stays readable at a glance.
 *
 * <p>Nothing here decides a purchase. A click sends an id and the server answers; every price and
 * level drawn came from the server in {@code ShopStatePayload}, and the screen redraws itself from
 * the reply. See {@code fi.vilpponen.mhr.shop.ShopServer}.
 */
public final class ShopScreen extends Screen {
	// The icon square, and the caption line under it. A cell is deliberately barely wider than a
	// vanilla inventory slot: fitting a whole catalogue on one page is the point of the screen.
	private static final int ICON_BOX = 20;
	private static final int CELL_WIDTH = 22;
	private static final int CELL_HEIGHT = ICON_BOX + 10;

	/** Room for the row heading to the left of the icons, so a row costs one line and not two. */
	private static final int ROW_LABEL_WIDTH = 78;

	private static final int PANEL_MAX_WIDTH = 360;
	private static final int HEADER_HEIGHT = 30;
	private static final int FOOTER_HEIGHT = 30;
	private static final int SCROLL_STEP = 12;

	private static final int PANEL_BACKGROUND = 0x70000000;
	private static final int BAND_BACKGROUND = 0xF0100A0A;
	private static final int SEPARATOR = 0x50FFFFFF;
	private static final int OWNED_BACKGROUND = 0xFF1E4620;
	private static final int AFFORDABLE_BACKGROUND = 0xFF3A3A3A;
	private static final int UNAFFORDABLE_BACKGROUND = 0xFF2A2020;
	private static final int CELL_OUTLINE = 0xFF000000;
	private static final int OWNED_OUTLINE = 0xFF4C8C4A;
	private static final int HOVER_OUTLINE = 0xFFFFFFFF;
	private static final int SCROLLBAR = 0xFF808080;

	private static final int TITLE_COLOR = 0xFFFFFFFF;
	private static final int CURRENCY_COLOR = 0xFFFFD75F;
	private static final int TIER_COLOR = 0xFFFFFFFF;
	private static final int ROW_COLOR = 0xFFA0A0A0;
	private static final int PRICE_COLOR = 0xFFFFFFFF;
	private static final int UNAFFORDABLE_COLOR = 0xFFFF6B6B;
	private static final int OWNED_COLOR = 0xFF7FE07F;
	private static final int PARTIAL_COLOR = 0xFFFFE066;

	/** One offer's square, at the position it has before scrolling is taken into account. */
	private record Cell(Offer offer, int x, int y) {
	}

	/** A heading. {@code rule} draws the line under a section heading. */
	private record Heading(Component text, int x, int y, int color, boolean rule) {
	}

	private final List<Cell> cells = new ArrayList<>();
	private final List<Heading> headings = new ArrayList<>();

	/**
	 * The offer list the current layout was built from, compared by identity.
	 *
	 * <p>Every packet from the server produces a new list, so this is how the screen notices that a
	 * purchase went through and rebuilds itself without the server having to say so.
	 */
	private List<Offer> laidOutFrom;

	private int panelLeft;
	private int panelWidth;
	private int contentTop;
	private int contentBottom;
	private int contentHeight;
	private int scroll;

	public ShopScreen() {
		super(Component.translatable("mhr.shop.title"));
	}

	@Override
	protected void init() {
		panelWidth = Math.min(width - 20, PANEL_MAX_WIDTH);
		panelLeft = (width - panelWidth) / 2;
		contentTop = HEADER_HEIGHT;
		contentBottom = Math.max(contentTop + CELL_HEIGHT, height - FOOTER_HEIGHT);

		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
				.bounds((width - 100) / 2, height - 25, 100, 20)
				.build());

		rebuild();
	}

	// --- layout ------------------------------------------------------------------------------

	private void ensureLayout() {
		if (laidOutFrom != SyncedShop.offers()) {
			rebuild();
		}
	}

	/**
	 * Work out where everything goes, once, from the layout file and the server's offers.
	 *
	 * <p>Positions are relative to the top of the content, not to the screen, so scrolling is one
	 * subtraction at draw time and a click can be resolved against the same numbers.
	 */
	private void rebuild() {
		List<Offer> offers = SyncedShop.offers();
		laidOutFrom = offers;
		cells.clear();
		headings.clear();

		int columns = Math.max(1, (panelWidth - ROW_LABEL_WIDTH - 8) / CELL_WIDTH);
		int y = 0;
		for (ShopLayout.Section section : ShopLayout.arrange(offers)) {
			headings.add(new Heading(Component.translatable(ShopText.tierKey(section.tierId()))
					.withStyle(ChatFormatting.BOLD), 2, y, TIER_COLOR, true));
			y += 14;

			for (ShopLayout.Row row : section.rows()) {
				headings.add(new Heading(
						Component.translatable(ShopText.groupKey(row.groupId())), 4, y + 6, ROW_COLOR, false));
				int column = 0;
				int rowTop = y;
				for (Offer offer : row.offers()) {
					cells.add(new Cell(offer, ROW_LABEL_WIDTH + column * CELL_WIDTH, rowTop));
					column++;
					if (column == columns) {
						column = 0;
						rowTop += CELL_HEIGHT;
					}
				}
				y = rowTop + (column > 0 ? CELL_HEIGHT : 0) + 3;
			}
			y += 7;
		}
		contentHeight = y;
		scroll = Math.clamp(scroll, 0, maxScroll());
	}

	private int maxScroll() {
		return Math.max(0, contentHeight - (contentBottom - contentTop));
	}

	/** Where a square's top-left corner is on screen right now, scrolling included. */
	private int screenTop(Cell cell) {
		return cell.y() + contentTop - scroll;
	}

	/**
	 * Whether a square is wholly inside the panel, and therefore drawn.
	 *
	 * <p>The one predicate three things share: what is drawn, what a click can land on, and what a
	 * test is allowed to click. They have to be the same, or a row scrolled half off the panel is
	 * left undrawn while the strip of it still inside the panel goes on buying things — a purchase
	 * target with nothing visible on it.
	 *
	 * <p>Whole rather than partly, because an item model is a deferred element and a clipped icon
	 * comes back on top of the title bar. Leaving the row out entirely is what stops that, and this
	 * is what makes the click agree.
	 */
	private boolean isDrawn(Cell cell) {
		int y = screenTop(cell);
		return y >= contentTop && y + ICON_BOX <= contentBottom;
	}

	/** The cell under the mouse, or null. Only a drawn cell can be under it. */
	private Cell cellAt(double mouseX, double mouseY) {
		for (Cell cell : cells) {
			int x = panelLeft + cell.x();
			int y = screenTop(cell);
			if (isDrawn(cell)
					&& mouseX >= x && mouseX < x + ICON_BOX
					&& mouseY >= y && mouseY < y + ICON_BOX) {
				return cell;
			}
		}
		return null;
	}

	private Cell cellFor(String unlockId) {
		for (Cell cell : cells) {
			if (cell.offer().id().equals(unlockId)) {
				return cell;
			}
		}
		return null;
	}

	/**
	 * The middle of one offer's square in screen coordinates, whether or not it is drawn there, or
	 * null when the shop has no such offer at all.
	 *
	 * <p>Nothing in the game calls this. It exists so the client GameTest can put the real mouse on
	 * a real square and click it, rather than calling a purchase helper and proving only that the
	 * helper works — see {@code docs/codebase/gametest.md}. It deliberately answers for a square
	 * that is scrolled half off the panel too, because one of the tests is that clicking exactly
	 * there does nothing; {@link #isClickable} is what says which case you are in.
	 */
	public double[] centreOf(String unlockId) {
		ensureLayout();
		Cell cell = cellFor(unlockId);
		if (cell == null) {
			return null;
		}
		return new double[] {
				panelLeft + cell.x() + ICON_BOX / 2.0,
				screenTop(cell) + ICON_BOX / 2.0,
		};
	}

	/**
	 * Whether a square is drawn where {@link #centreOf} says it is, and so whether clicking there
	 * buys anything. Also test-facing, and the same answer the click itself uses.
	 */
	public boolean isClickable(String unlockId) {
		ensureLayout();
		Cell cell = cellFor(unlockId);
		return cell != null && isDrawn(cell);
	}

	// --- drawing -----------------------------------------------------------------------------

	@Override
	public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
		ensureLayout();

		extractor.fill(panelLeft, contentTop, panelLeft + panelWidth, contentBottom, PANEL_BACKGROUND);

		int offset = contentTop - scroll;
		Cell hovered = cellAt(mouseX, mouseY);

		extractor.enableScissor(panelLeft, contentTop, panelLeft + panelWidth, contentBottom);
		for (Heading heading : headings) {
			int y = heading.y() + offset;
			if (y + 10 < contentTop || y > contentBottom) {
				continue;
			}
			extractor.text(font, heading.text(), panelLeft + heading.x(), y, heading.color());
			if (heading.rule()) {
				extractor.fill(panelLeft + 2, y + 10, panelLeft + panelWidth - 2, y + 11, SEPARATOR);
			}
		}
		for (Cell cell : cells) {
			if (!isDrawn(cell)) {
				continue;
			}
			drawCell(extractor, cell, panelLeft + cell.x(), screenTop(cell), cell == hovered);
		}
		drawScrollbar(extractor);
		extractor.disableScissor();

		// The bands are drawn above the content, so the panel has a solid edge whatever is scrolled
		// against it.
		extractor.nextStratum();

		extractor.fill(0, 0, width, contentTop, BAND_BACKGROUND);
		extractor.fill(0, contentBottom, width, height, BAND_BACKGROUND);
		extractor.fill(0, contentTop - 1, width, contentTop, SEPARATOR);
		extractor.fill(0, contentBottom, width, contentBottom + 1, SEPARATOR);

		extractor.centeredText(font, title, width / 2, 8, TITLE_COLOR);
		extractor.centeredText(font,
				Component.translatable("mhr.shop.currency", SyncedShop.currency()), width / 2, 19, CURRENCY_COLOR);

		super.extractRenderState(extractor, mouseX, mouseY, partialTick);

		if (hovered != null) {
			extractor.nextStratum();
			extractor.setComponentTooltipForNextFrame(font, tooltipFor(hovered.offer()), mouseX, mouseY);
		}
	}

	private void drawCell(GuiGraphicsExtractor extractor, Cell cell, int x, int y, boolean hovered) {
		Offer offer = cell.offer();
		boolean affordable = SyncedShop.currency() >= offer.price();

		int background = offer.isMaxed()
				? OWNED_BACKGROUND
				: (affordable ? AFFORDABLE_BACKGROUND : UNAFFORDABLE_BACKGROUND);
		extractor.fill(x, y, x + ICON_BOX, y + ICON_BOX, background);
		int outline = hovered ? HOVER_OUTLINE : (offer.isMaxed() ? OWNED_OUTLINE : CELL_OUTLINE);
		extractor.outline(x, y, ICON_BOX, ICON_BOX, outline);

		// fakeItem rather than item: these are pictures of things, not things. It also keeps them in
		// this stratum instead of the deferred pass, which is what stops an icon being drawn on top
		// of the tooltip explaining it.
		extractor.fakeItem(ShopIcons.stackFor(offer.id()), x + 2, y + 2);

		// The corner a Minecraft player already reads a number out of. An offer that hands over a
		// stack says how many; a repeatable one says how far it has been taken. Nothing has both.
		Reward reward = SyncedShop.reward(offer.id());
		if (reward.count() > 1) {
			extractor.text(font, Component.literal(String.valueOf(reward.count())),
					x + ICON_BOX - 2 - font.width(String.valueOf(reward.count())), y + ICON_BOX - 9,
					PRICE_COLOR);
		} else if (offer.isRepeatable() && offer.isOwned()) {
			extractor.text(font, Component.literal(String.valueOf(offer.level())),
					x + ICON_BOX - 6, y + ICON_BOX - 9, offer.isMaxed() ? OWNED_COLOR : PARTIAL_COLOR);
		}

		// The caption is the price, and it is struck through once there is nothing left to pay.
		Component price = Component.literal(String.valueOf(offer.price()));
		int color = PRICE_COLOR;
		if (offer.isMaxed()) {
			price = price.copy().withStyle(ChatFormatting.STRIKETHROUGH);
			color = OWNED_COLOR;
		} else if (!affordable) {
			color = UNAFFORDABLE_COLOR;
		}
		extractor.centeredText(font, price, x + ICON_BOX / 2, y + ICON_BOX + 1, color);
	}

	private void drawScrollbar(GuiGraphicsExtractor extractor) {
		int range = maxScroll();
		if (range == 0) {
			return;
		}
		int track = contentBottom - contentTop;
		int thumb = Math.max(16, track * track / contentHeight);
		int top = contentTop + (track - thumb) * scroll / range;
		int x = panelLeft + panelWidth - 3;
		extractor.fill(x, top, x + 2, top + thumb, SCROLLBAR);
	}

	/**
	 * What hovering says. Everything the layout deliberately has no room for lives here: what the
	 * unlock does, what state it is in, and why a click would be refused.
	 */
	private List<Component> tooltipFor(Offer offer) {
		List<Component> lines = new ArrayList<>();
		lines.add(nameOf(offer).copy().withStyle(ChatFormatting.WHITE));

		Component description = descriptionOf(offer);
		if (!description.getString().isEmpty()) {
			lines.add(description.copy().withStyle(ChatFormatting.GRAY));
		}
		lines.add(Component.empty());

		if (offer.isRepeatable()) {
			lines.add(Component.translatable("mhr.shop.level", offer.level(), offer.maxLevel())
					.withStyle(offer.isMaxed() ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
		}

		if (offer.isMaxed()) {
			lines.add(Component.translatable(offer.isRepeatable() ? "mhr.shop.state.maxed" : "mhr.shop.state.owned")
					.withStyle(ChatFormatting.GREEN));
		} else {
			int missing = offer.price() - SyncedShop.currency();
			lines.add(Component.translatable(
							offer.isRepeatable() ? "mhr.shop.price.next" : "mhr.shop.price", offer.price())
					.withStyle(missing > 0 ? ChatFormatting.RED : ChatFormatting.WHITE));
			lines.add(missing > 0
					? Component.translatable("mhr.shop.state.short", missing).withStyle(ChatFormatting.RED)
					: Component.translatable("mhr.shop.state.buy").withStyle(ChatFormatting.GRAY));
		}
		return lines;
	}

	/**
	 * What to call an offer.
	 *
	 * <p>One that hands over an item is named after the item, with how many, out of what the server
	 * said it will give. Writing that name down anywhere else is a second copy of something the
	 * balance file is allowed to change, and the two drifted apart as soon as anybody retuned a
	 * starter item.
	 */
	private static Component nameOf(Offer offer) {
		Reward reward = SyncedShop.reward(offer.id());
		if (!reward.isSomething()) {
			return Component.translatable(ShopText.nameKey(offer.id()));
		}
		return reward.count() > 1
				? Component.translatable("mhr.shop.reward.name", reward.count(), reward.item().getHoverName())
				: reward.item().getHoverName();
	}

	/** Likewise: an item says what it is for itself, and the line below says where it turns up. */
	private static Component descriptionOf(Offer offer) {
		return SyncedShop.reward(offer.id()).isSomething()
				? Component.translatable("mhr.shop.reward.desc")
				: Component.translatableWithFallback(ShopText.descriptionKey(offer.id()), "");
	}

	// --- input -------------------------------------------------------------------------------

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		ensureLayout();
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			Cell cell = cellAt(event.x(), event.y());
			if (cell != null) {
				click(cell.offer());
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	/**
	 * A click asks; it does not decide.
	 *
	 * <p>Something already at its maximum is the one case the client answers itself, because there
	 * is no question to ask and a line of chat per click would be noise. An unaffordable click is
	 * still sent: the server's refusal names the shortfall, and the client's idea of the balance is
	 * the thing most likely to be out of date.
	 */
	private void click(Offer offer) {
		if (offer.isMaxed()) {
			return;
		}
		ClientPlayNetworking.send(new ShopBuyPayload(offer.id()));
		if (minecraft != null) {
			minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
		ensureLayout();
		int before = scroll;
		scroll = Math.clamp(scroll - (int) Math.signum(deltaY) * SCROLL_STEP, 0, maxScroll());
		return scroll != before || super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
	}

	@Override
	public void resize(int newWidth, int newHeight) {
		super.resize(newWidth, newHeight);
		rebuild();
	}
}
