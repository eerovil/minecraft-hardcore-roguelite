package fi.vilpponen.mhr.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/**
 * The one key that makes a starter item a starter item.
 *
 * <p>A starter item is not a second kind of unlock with a catalogue of its own — it is an ordinary
 * entry under {@code unlocks} that also says what it hands over. What is worth pinning down is
 * that the extra key is accepted where it belongs, that it stays opaque to this layer, and that
 * relaxing the key check for it did not relax it for anything else.
 */
class StarterItemBalanceTest {
	private static Balance bind(String unlocks) {
		JsonObject source = JsonParser.parseString("""
				{
					"currency": { "advancements": {} },
					"unlocks": %s,
					"worldBorder": {},
					"difficulty": { "mobDamageMultiplier": 1.0 },
					"vanillaPlus": { "craftEnchant": { "maxUnlockLevel": 4, "strengthPerLevel": 0.25 } }
				}""".formatted(unlocks)).getAsJsonObject();
		return BalanceManager.bindAndCheck(source);
	}

	/** The bundled unlocks, with an override applied over the whole file the way a config does. */
	private static Balance override(String unlocks, String overrideJson) {
		JsonObject defaults = JsonParser.parseString("""
				{
					"currency": { "advancements": {} },
					"unlocks": %s,
					"worldBorder": {},
					"difficulty": { "mobDamageMultiplier": 1.0 },
					"vanillaPlus": { "craftEnchant": { "maxUnlockLevel": 4, "strengthPerLevel": 0.25 } }
				}""".formatted(unlocks)).getAsJsonObject();
		JsonObject over = JsonParser.parseString(overrideJson).getAsJsonObject();
		return BalanceManager.bindAndCheck(BalanceManager.applyOverride(defaults, over, "the-override.json"));
	}

	@Test
	void anUnlockCarriesBothItsPriceAndItsItem() {
		Balance balance = bind("""
				{ "starter.bread": { "price": 3, "item": { "id": "minecraft:bread", "count": 16 } } }""");

		Balance.UnlockBalance bread = balance.unlock("starter.bread").orElseThrow();

		assertEquals(3, bread.price());
		assertEquals("minecraft:bread", bread.item().orElseThrow().get("id").getAsString());
		assertEquals(16, bread.item().orElseThrow().get("count").getAsInt());
	}

	@Test
	void anUnlockWithoutAnItemIsNotAStarterItem() {
		// The ordinary case, and the reason item is optional rather than a second section: an
		// unlock whose effect is code has nothing to put in a chest.
		Balance balance = bind("{ \"world.trees\": { \"price\": 3 } }");

		assertFalse(balance.unlock("world.trees").orElseThrow().item().isPresent());
	}

	@Test
	void theItemIsPassedThroughUntouched() {
		// Balance deliberately does not understand item components — they go to the game's own
		// codec. That is what lets an enchanted pickaxe be sold without a line of code per item.
		Balance balance = bind("""
				{
					"starter.pickaxe": {
						"price": 100,
						"item": {
							"id": "minecraft:diamond_pickaxe",
							"count": 1,
							"components": { "minecraft:enchantments": { "minecraft:efficiency": 3 } }
						}
					}
				}""");

		JsonObject item = balance.unlock("starter.pickaxe").orElseThrow().item().orElseThrow();

		assertEquals(3, item.getAsJsonObject("components")
				.getAsJsonObject("minecraft:enchantments").get("minecraft:efficiency").getAsInt());
	}

	@Test
	void theSnapshotCannotBeEditedThroughTheItemItHandsOut() {
		// Balance is shared across threads and swapped whole on reload. Handing out the live
		// JsonObject would let one caller retune everyone else's chest.
		Balance balance = bind("""
				{ "starter.bread": { "price": 3, "item": { "id": "minecraft:bread", "count": 16 } } }""");

		balance.unlock("starter.bread").orElseThrow().item().orElseThrow().addProperty("count", 64);

		assertEquals(16, balance.unlock("starter.bread").orElseThrow()
				.item().orElseThrow().get("count").getAsInt());
	}

	@Test
	void anythingOtherThanPriceAndItemIsStillRefused() {
		// Adding one allowed key must not turn the entry into a free-for-all, or a misspelt price
		// becomes an unlock that quietly costs nothing.
		BalanceException thrown = assertThrows(BalanceException.class,
				() -> bind("{ \"starter.bread\": { \"price\": 3, \"itme\": {} } }"));

		assertTrue(thrown.getMessage().contains("itme"), thrown.getMessage());
	}

	@Test
	void aCountOverAStackIsFine() {
		// The one place this is not vanilla's format. 128 bread is a shopping list, not a broken
		// stack: the chest splits it. So there is no upper bound to check.
		Balance balance = bind("""
				{ "starter.bread": { "price": 3, "item": { "id": "minecraft:bread", "count": 128 } } }""");

		assertEquals(128, balance.unlock("starter.bread").orElseThrow()
				.item().orElseThrow().get("count").getAsInt());
	}

	@Test
	void aCountThatIsNotAWholeNumberOfAtLeastOneIsRefused() {
		// Rounding these into something workable is the failure this layer exists to prevent: the
		// player gets a different amount from the one the file names, and nothing says so.
		for (String count : new String[] {"0", "-4", "16.5"}) {
			BalanceException thrown = assertThrows(BalanceException.class,
					() -> bind("{ \"starter.bread\": { \"price\": 3, \"item\": { \"count\": " + count + " } } }"),
					"count " + count + " should have been refused");

			assertTrue(thrown.getMessage().contains("unlocks.starter.bread.item.count"), thrown.getMessage());
		}
	}

	@Test
	void anItemWithoutACountIsFine() {
		// One of whatever it is, the same as leaving count out of a /give.
		Balance balance = bind("""
				{ "starter.pickaxe": { "price": 100, "item": { "id": "minecraft:stone_pickaxe" } } }""");

		assertFalse(balance.unlock("starter.pickaxe").orElseThrow().item().orElseThrow().has("count"));
	}

	@Test
	void anOverrideGivesAnItemComponentsItDidNotHave() {
		// The reason item is not walked into. The bundled bread has no components key at all, so a
		// recursive key check called this a typo and refused it — while the whole point of putting
		// the stack in balance data was that the shop could sell a named or enchanted version.
		Balance balance = override(
				"{ \"starter.bread\": { \"price\": 3, \"item\": { \"id\": \"minecraft:bread\", \"count\": 16 } } }",
				"""
				{ "unlocks": { "starter.bread": { "item": {
					"id": "minecraft:bread", "count": 16,
					"components": { "minecraft:custom_name": "Packed Lunch" } } } } }""");

		JsonObject item = balance.unlock("starter.bread").orElseThrow().item().orElseThrow();

		assertEquals("Packed Lunch",
				item.getAsJsonObject("components").get("minecraft:custom_name").getAsString());
	}

	@Test
	void anOverrideReplacesTheItemWholeSoAComponentCanBeRemoved() {
		// A deep merge could add a component and change one, but never take one away: the old
		// components object was still underneath. An override says what the item now is.
		Balance balance = override(
				"""
				{ "starter.pickaxe": { "price": 100, "item": {
					"id": "minecraft:diamond_pickaxe", "count": 1,
					"components": { "minecraft:enchantments": { "minecraft:efficiency": 3 } } } } }""",
				"""
				{ "unlocks": { "starter.pickaxe": { "item": {
					"id": "minecraft:diamond_pickaxe", "count": 1 } } } }""");

		JsonObject item = balance.unlock("starter.pickaxe").orElseThrow().item().orElseThrow();

		assertFalse(item.has("components"), item.toString());
	}

	@Test
	void anOverrideStillCannotInventAnUnlockOrMisspellAPrice() {
		// Only the stack is opaque. Relaxing it must not relax the catalogue around it, or a
		// misspelt price becomes an unlock that quietly costs nothing.
		String defaults = "{ \"starter.bread\": { \"price\": 3, \"item\": { \"id\": \"minecraft:bread\" } } }";

		assertThrows(BalanceException.class, () -> override(defaults,
				"{ \"unlocks\": { \"starter.bread\": { \"pirce\": 5 } } }"));
		assertThrows(BalanceException.class, () -> override(defaults,
				"{ \"unlocks\": { \"starter.braed\": { \"price\": 5 } } }"));
	}

	@Test
	void aCandidateThatFailsACheckNeverBecomesTheBalance() {
		// What stands in for the registries here. The real check decodes every item with the
		// game's own codec, which needs a running server; the mechanism it hangs off is this one,
		// and what matters about it is that the throw happens while binding the candidate — before
		// any caller can swap it in.
		BalanceManager.Check refuseBread = candidate -> {
			if (candidate.unlock("starter.bread").isPresent()) {
				throw new BalanceException("'unlocks.starter.bread.item' is not an item the game has");
			}
		};
		BalanceManager.addCheck(refuseBread);
		try {
			BalanceException thrown = assertThrows(BalanceException.class, () -> bind(
					"{ \"starter.bread\": { \"price\": 3, \"item\": { \"id\": \"minecraft:braed\" } } }"));
			assertTrue(thrown.getMessage().contains("starter.bread"), thrown.getMessage());

			assertDoesNotThrow(() -> bind("{ \"world.trees\": { \"price\": 3 } }"));
		} finally {
			BalanceManager.removeCheck(refuseBread);
		}
	}

	@Test
	void anItemThatIsNotAnObjectIsRefused() {
		// Caught at startup rather than when the chest is built, which is a run later.
		BalanceException thrown = assertThrows(BalanceException.class,
				() -> bind("{ \"starter.bread\": { \"price\": 3, \"item\": \"minecraft:bread\" } }"));

		assertTrue(thrown.getMessage().contains("unlocks.starter.bread.item"), thrown.getMessage());
	}
}
