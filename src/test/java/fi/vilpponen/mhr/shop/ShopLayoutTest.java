package fi.vilpponen.mhr.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fi.vilpponen.mhr.progression.Offer;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The shop's arrangement, which is the part of the screen that can be checked without a game.
 *
 * <p>The criteria under test are the issue's own: vanilla restoration comes first, Vanilla+ comes
 * after it, every catalogue entry is somewhere, and nothing is in two places. They are checked
 * against the shipped {@code default-balance.json} rather than a made-up catalogue, so the day
 * somebody adds an unlock and forgets the shop, this is what says so.
 */
class ShopLayoutTest {
	private static final String VANILLA = "vanilla";
	private static final String VANILLA_PLUS = "vanilla_plus";

	/** Every id the shipped catalogue sells, in file order, as the shop would receive them. */
	private static List<Offer> shippedOffers() {
		JsonObject balance = readJson("/default-balance.json");
		List<Offer> offers = new ArrayList<>();
		for (Map.Entry<String, JsonElement> unlock : balance.getAsJsonObject("unlocks").entrySet()) {
			offers.add(new Offer(unlock.getKey(), unlock.getValue().getAsJsonObject().get("price").getAsInt(), 0, 1));
		}
		for (Map.Entry<String, JsonElement> tier : balance.getAsJsonObject("worldBorder").entrySet()) {
			offers.add(new Offer("world.border." + tier.getKey(),
					tier.getValue().getAsJsonObject().get("price").getAsInt(), 0, 1));
		}
		return offers;
	}

	private static JsonObject readJson(String resource) {
		try (InputStream stream = ShopLayoutTest.class.getResourceAsStream(resource);
				Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
			return JsonParser.parseReader(reader).getAsJsonObject();
		} catch (Exception e) {
			throw new AssertionError("Could not read " + resource, e);
		}
	}

	private static List<String> idsIn(List<ShopLayout.Section> sections) {
		List<String> ids = new ArrayList<>();
		for (ShopLayout.Section section : sections) {
			for (ShopLayout.Row row : section.rows()) {
				for (Offer offer : row.offers()) {
					ids.add(offer.id());
				}
			}
		}
		return ids;
	}

	private static ShopLayout.Section sectionOf(List<ShopLayout.Section> sections, String tier) {
		for (ShopLayout.Section section : sections) {
			if (section.tierId().equals(tier)) {
				return section;
			}
		}
		throw new AssertionError("The shop has no '" + tier + "' section at all. Sections: " + sections);
	}

	private static List<String> rowIds(ShopLayout.Section section, String groupId) {
		for (ShopLayout.Row row : section.rows()) {
			if (row.groupId().equals(groupId)) {
				List<String> ids = new ArrayList<>();
				for (Offer offer : row.offers()) {
					ids.add(offer.id());
				}
				return ids;
			}
		}
		throw new AssertionError("No '" + groupId + "' row in the " + section.tierId() + " section");
	}

	@Test
	void everyCatalogueEntryIsSomewhereAndOnlySomewhere() {
		List<Offer> offers = shippedOffers();
		List<String> placed = idsIn(ShopLayout.arrange(offers));

		Set<String> expected = new LinkedHashSet<>();
		for (Offer offer : offers) {
			expected.add(offer.id());
		}

		assertEquals(expected.size(), placed.size(),
				"every catalogue entry must be drawn exactly once, and these are drawn: " + placed);
		assertEquals(expected, new LinkedHashSet<>(placed),
				"the shop must show exactly what the catalogue sells, no more and no less");
	}

	@Test
	void vanillaRestorationComesBeforeVanillaPlus() {
		List<ShopLayout.Section> sections = ShopLayout.arrange(shippedOffers());

		assertEquals(VANILLA, sections.get(0).tierId(),
				"restoring vanilla is the first thing the player should see");
		assertEquals(VANILLA_PLUS, sections.get(1).tierId(),
				"going past vanilla belongs after restoring it");
		assertEquals(2, sections.size(), "the shipped catalogue has exactly these two tiers");
	}

	@Test
	void theWorldAndThePlayerAreBothVanillaRestoration() {
		ShopLayout.Section vanilla = sectionOf(ShopLayout.arrange(shippedOffers()), VANILLA);

		assertEquals(List.of("world.trees", "world.village"), rowIds(vanilla, "world"));
		assertEquals(List.of("world.ore.coal", "world.ore.copper", "world.ore.iron", "world.ore.redstone",
				"world.ore.lapis", "world.ore.gold", "world.ore.diamond"), rowIds(vanilla, "ores"));
		assertEquals(List.of("world.animal.cow", "world.animal.pig", "world.animal.sheep",
				"world.animal.chicken", "world.animal.horse", "world.animal.wolf"), rowIds(vanilla, "animals"));
		assertEquals(List.of("player.slot.helmet", "player.slot.boots", "player.slot.leggings",
				"player.slot.chestplate", "player.slot.offhand"), rowIds(vanilla, "slots"));
		assertEquals(List.of("world.border.tiny", "world.border.medium", "world.border.large",
				"world.border.infinite"), rowIds(vanilla, "border"));
	}

	@Test
	void starterGearAndTheCraftEnchantAreVanillaPlus() {
		ShopLayout.Section beyond = sectionOf(ShopLayout.arrange(shippedOffers()), VANILLA_PLUS);

		assertEquals(List.of("starter.bread", "starter.logs", "starter.torches", "starter.stone_pickaxe",
				"starter.iron_sword", "starter.efficient_pickaxe"), rowIds(beyond, "gear"));
		assertEquals(List.of("player.craft.enchant"), rowIds(beyond, "upgrades"));
	}

	@Test
	void anUnlockNobodyHasPlacedYetStillShowsUp() {
		// The point of the catch-all row: a catalogue entry added without touching the layout file is
		// drawn somewhere rather than missing from a shop that otherwise looks complete.
		List<Offer> offers = new ArrayList<>(shippedOffers());
		offers.add(new Offer("something.nobody.planned", 7, 0, 1));

		List<ShopLayout.Section> sections = ShopLayout.arrange(offers);
		assertTrue(idsIn(sections).contains("something.nobody.planned"),
				"an unplaced catalogue entry must still be drawn");
		assertEquals(List.of("something.nobody.planned"), rowIds(sectionOf(sections, VANILLA_PLUS), "other"));
	}

	@Test
	void emptyRowsAndEmptySectionsAreNotDrawn() {
		List<ShopLayout.Section> sections = ShopLayout.arrange(List.of(new Offer("world.trees", 3, 1, 1)));

		assertEquals(1, sections.size(), "a catalogue with nothing beyond vanilla has no Vanilla+ heading");
		assertEquals(VANILLA, sections.get(0).tierId());
		assertEquals(1, sections.get(0).rows().size(), "and only the one row that has anything in it");
	}

	@Test
	void everyEntryWhoseEffectIsCodeHasAnIconAndAName() {
		JsonObject lang = readJson("/assets/hardcore_roguelite/lang/en_us.json");

		for (Offer offer : shippedOffers()) {
			if (handsOverAnItem(offer.id())) {
				continue;
			}
			assertTrue(ShopLayout.hasIcon(offer.id()),
					"shop-layout.json has no icon for '" + offer.id() + "', so it would draw as the default");
			assertTrue(lang.has(ShopText.nameKey(offer.id())),
					"en_us.json has no name for '" + offer.id() + "', so the shop would show a raw key");
			assertTrue(lang.has(ShopText.descriptionKey(offer.id())),
					"en_us.json has no description for '" + offer.id() + "', so hovering would explain nothing");
		}
	}

	/**
	 * The other half of the same rule, and the one that stops the duplication coming back.
	 *
	 * <p>What a starter item gives is a stack in the balance catalogue, which an override may
	 * replace. The shop is told it by the server and draws it from there. An icon or a name written
	 * down here as well would be a second copy of a number somebody is allowed to change, and the
	 * two drifted the moment anyone retuned a count.
	 */
	@Test
	void anEntryThatHandsOverAnItemDescribesItselfFromTheCatalogue() {
		JsonObject lang = readJson("/assets/hardcore_roguelite/lang/en_us.json");
		int checked = 0;

		for (Offer offer : shippedOffers()) {
			if (!handsOverAnItem(offer.id())) {
				continue;
			}
			checked++;
			assertFalse(ShopLayout.hasIcon(offer.id()),
					"shop-layout.json names an icon for '" + offer.id() + "', which the balance file already"
							+ " decides. The server sends the real stack; this copy can only go stale.");
			assertFalse(lang.has(ShopText.nameKey(offer.id())),
					"en_us.json names '" + offer.id() + "', which the item itself already does");
			assertFalse(lang.has(ShopText.descriptionKey(offer.id())),
					"en_us.json describes '" + offer.id() + "', counts and all, which is exactly what the"
							+ " override file is allowed to change");
		}
		assertTrue(checked > 0, "the shipped catalogue should have at least one entry that gives an item");
	}

	/** Whether the shipped catalogue says this unlock's whole effect is a stack. */
	private static boolean handsOverAnItem(String id) {
		JsonObject unlocks = readJson("/default-balance.json").getAsJsonObject("unlocks");
		return unlocks.has(id) && unlocks.getAsJsonObject(id).has("item");
	}

	@Test
	void everyGroupAndTierHasAHeading() {
		JsonObject lang = readJson("/assets/hardcore_roguelite/lang/en_us.json");
		Set<String> tiers = new LinkedHashSet<>();

		for (ShopLayout.Group group : ShopLayout.groups()) {
			assertTrue(lang.has(ShopText.groupKey(group.id())),
					"en_us.json has no heading for the '" + group.id() + "' row");
			tiers.add(group.tier());
		}
		for (String tier : tiers) {
			assertTrue(lang.has(ShopText.tierKey(tier)),
					"en_us.json has no heading for the '" + tier + "' section");
		}
		assertFalse(tiers.isEmpty(), "the layout file must declare at least one section");
	}
}
