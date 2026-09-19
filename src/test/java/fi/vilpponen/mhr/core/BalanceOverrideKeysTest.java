package fi.vilpponen.mhr.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/**
 * An override may change what the bundled file has, and nothing else.
 *
 * <p>A misspelt key is the mistake this file is really about, and it is the same mistake at every
 * depth: merged unchecked it produces a section or an entry nothing reads, while the real one keeps
 * its bundled value. A balance change that looks applied, does nothing, and says nothing.
 */
class BalanceOverrideKeysTest {
	private static final JsonObject DEFAULTS = json("""
			{
				"currency": { "advancements": { "minecraft:story/mine_diamond": 30 } },
				"unlocks": { "world.trees": { "price": 3 }, "world.ore.diamond": { "price": 100 } },
				"worldBorder": { "medium": { "size": 512, "price": 3 } },
				"difficulty": { "mobDamageMultiplier": 1.5 },
				"vanillaPlus": { "speed": { "stepPercent": 10 } }
			}""");

	private static JsonObject json(String text) {
		return JsonParser.parseString(text).getAsJsonObject();
	}

	private static BalanceException reject(String override) {
		return assertThrows(BalanceException.class,
				() -> BalanceManager.checkOverrideKeys(DEFAULTS, json(override), "the-override.json"));
	}

	private static void accept(String override) {
		assertDoesNotThrow(() -> BalanceManager.checkOverrideKeys(DEFAULTS, json(override), "the-override.json"));
	}

	@Test
	void aMisspeltSectionIsRefused() {
		// The one a whole-section typo makes: difficulty keeps its bundled value and mobs go on
		// hitting exactly as hard as before, with nothing said.
		BalanceException thrown = reject("{\"dificulty\": {\"mobDamageMultiplier\": 2.0}}");

		assertTrue(thrown.getMessage().contains("sets 'dificulty'"), thrown.getMessage());
		assertTrue(thrown.getMessage().contains("Did you mean 'difficulty'?"), thrown.getMessage());
	}

	@Test
	void aMisspeltValueInsideARealSectionIsRefused() {
		BalanceException thrown = reject("{\"difficulty\": {\"mobDamageMultiplyer\": 2.0}}");

		assertTrue(thrown.getMessage().contains("difficulty.mobDamageMultiplyer"), thrown.getMessage());
		assertTrue(thrown.getMessage().contains("Did you mean 'mobDamageMultiplier'?"), thrown.getMessage());
	}

	@Test
	void aMisspeltUnlockIsRefused() {
		BalanceException thrown = reject("{\"unlocks\": {\"world.ore.diamod\": {\"price\": 60}}}");

		assertTrue(thrown.getMessage().contains("unlocks.world.ore.diamod"), thrown.getMessage());
		assertTrue(thrown.getMessage().contains("Did you mean 'world.ore.diamond'?"), thrown.getMessage());
	}

	@Test
	void aMisspeltAdvancementIsRefused() {
		BalanceException thrown = reject("{\"currency\": {\"advancements\": {\"minecraft:story/mine_diamonds\": 40}}}");

		assertTrue(thrown.getMessage().contains("currency.advancements.minecraft:story/mine_diamonds"),
				thrown.getMessage());
		assertTrue(thrown.getMessage().contains("minecraft:story/mine_diamond'?"), thrown.getMessage());
	}

	@Test
	void aMisspeltBorderTierIsRefused() {
		BalanceException thrown = reject("{\"worldBorder\": {\"mediun\": {\"size\": 600}}}");

		assertTrue(thrown.getMessage().contains("worldBorder.mediun"), thrown.getMessage());
		assertTrue(thrown.getMessage().contains("Did you mean 'medium'?"), thrown.getMessage());
	}

	@Test
	void aMisspeltPathInsideANestedSectionIsRefused() {
		// The by-path API's version of the same mistake: the intended path stays absent and
		// number(path, fallback) hands back the plausible fallback forever.
		BalanceException thrown = reject("{\"vanillaPlus\": {\"speed\": {\"stepPercnt\": 20}}}");

		assertTrue(thrown.getMessage().contains("vanillaPlus.speed.stepPercnt"), thrown.getMessage());
		assertTrue(thrown.getMessage().contains("Did you mean 'stepPercent'?"), thrown.getMessage());
	}

	@Test
	void aNewTopLevelSectionIsRefused() {
		BalanceException thrown = reject("{\"hunger\": {\"drainPercent\": 50}}");

		assertTrue(thrown.getMessage().contains("sets 'hunger'"), thrown.getMessage());
		assertTrue(thrown.getMessage().contains("default-balance.json"), thrown.getMessage());
	}

	@Test
	void anIdNothingResemblesIsRefusedWithSomewhereToPutIt() {
		BalanceException thrown = reject("{\"unlocks\": {\"world.weather.rain\": {\"price\": 5}}}");

		assertTrue(thrown.getMessage().contains("unlocks.world.weather.rain"), thrown.getMessage());
		assertTrue(thrown.getMessage().contains("default-balance.json"), thrown.getMessage());
	}

	@Test
	void changingSomethingThatExistsIsFine() {
		accept("{\"unlocks\": {\"world.ore.diamond\": {\"price\": 60}}}");
		accept("{\"currency\": {\"advancements\": {\"minecraft:story/mine_diamond\": 40}}}");
		accept("{\"worldBorder\": {\"medium\": {\"size\": 600}}}");
		accept("{\"difficulty\": {\"mobDamageMultiplier\": 2.0}}");
		accept("{\"vanillaPlus\": {\"speed\": {\"stepPercent\": 20}}}");
		accept("{}");
	}

	@Test
	void anObjectReplacedByAScalarIsRefused() {
		// The one bind would miss: nothing traverses vanillaPlus, so left to bind this override
		// survives the reload and only shows up later, when the feature reading the path is asked a
		// question. Refusing it here keeps the game on the balance it had.
		BalanceException thrown = reject("{\"vanillaPlus\": {\"speed\": 12}}");

		assertTrue(thrown.getMessage().contains("sets 'vanillaPlus.speed' to 12"), thrown.getMessage());
		assertTrue(thrown.getMessage().contains("has an object there"), thrown.getMessage());
	}

	@Test
	void aWrongShapedSectionIsRefused() {
		assertTrue(reject("{\"unlocks\": 3}").getMessage().contains("has an object there"));
		assertTrue(reject("{\"currency\": {\"advancements\": \"lots\"}}").getMessage()
				.contains("has an object there"));
		assertTrue(reject("{\"difficulty\": {\"mobDamageMultiplier\": \"hard\"}}").getMessage()
				.contains("has a number there"));
		assertTrue(reject("{\"unlocks\": {\"world.trees\": {\"price\": [3]}}}").getMessage()
				.contains("has a number there"));
	}
}
