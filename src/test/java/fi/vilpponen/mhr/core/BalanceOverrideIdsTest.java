package fi.vilpponen.mhr.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/**
 * An override may change the catalogue; it may not invent it.
 *
 * <p>A misspelt id is the mistake this file is really about. Merged unchecked it produces an entry
 * nothing reads while the real one keeps its bundled value — a balance change that looks applied,
 * does nothing, and says nothing.
 */
class BalanceOverrideIdsTest {
	private static final JsonObject DEFAULTS = json("""
			{
				"currency": { "advancements": { "minecraft:story/mine_diamond": 30 } },
				"unlocks": { "world.trees": { "price": 3 }, "world.ore.diamond": { "price": 100 } },
				"worldBorder": { "medium": { "size": 512, "price": 3 } },
				"difficulty": { "mobDamageMultiplier": 1.5 }
			}""");

	private static JsonObject json(String text) {
		return JsonParser.parseString(text).getAsJsonObject();
	}

	private static BalanceException reject(String override) {
		return assertThrows(BalanceException.class,
				() -> BalanceManager.checkOverrideIds(DEFAULTS, json(override), "the-override.json"));
	}

	private static void accept(String override) {
		assertDoesNotThrow(() -> BalanceManager.checkOverrideIds(DEFAULTS, json(override), "the-override.json"));
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
	void anIdNothingResemblesIsRefusedWithSomewhereToPutIt() {
		BalanceException thrown = reject("{\"unlocks\": {\"world.weather.rain\": {\"price\": 5}}}");

		assertTrue(thrown.getMessage().contains("no such unlock"), thrown.getMessage());
		assertTrue(thrown.getMessage().contains("default-balance.json"), thrown.getMessage());
	}

	@Test
	void anOverrideOfSomethingThatExistsIsFine() {
		accept("{\"unlocks\": {\"world.ore.diamond\": {\"price\": 60}}}");
		accept("{\"currency\": {\"advancements\": {\"minecraft:story/mine_diamond\": 40}}}");
		accept("{\"worldBorder\": {\"medium\": {\"size\": 600}}}");
		accept("{\"difficulty\": {\"mobDamageMultiplier\": 2.0}}");
		accept("{}");
	}

	@Test
	void aNewTopLevelSectionIsStillFree() {
		// The vanilla+ extension point: tuning values for a system that has no typed accessor yet.
		accept("{\"vanillaPlus\": {\"speed\": {\"stepPercent\": 10}}}");
	}

	@Test
	void aWrongShapedSectionIsLeftForTheParserToReport() {
		// Not this check's job — bind says what shape a section should be, and says it once.
		accept("{\"unlocks\": 3}");
		accept("{\"currency\": {\"advancements\": \"lots\"}}");
	}
}
