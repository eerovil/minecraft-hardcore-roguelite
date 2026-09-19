package fi.vilpponen.mhr.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The crafted-tool enchant's numbers are checked while the balance loads, not when somebody crafts.
 *
 * <p>That is the difference between a bad tuning edit being refused — at startup it stops the mod,
 * on reload the running game keeps the balance it had — and it becoming the balance in effect and
 * throwing halfway through a crafting grid. A whole-number level and a strength above zero both
 * have the same JSON shape as a valid one, so nothing earlier in the load catches them.
 */
class CraftEnchantBindTest {
	private static JsonObject bundled() {
		try (InputStream in = CraftEnchantBindTest.class.getResourceAsStream(BalanceManager.DEFAULT_RESOURCE);
				Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
			return JsonParser.parseReader(reader).getAsJsonObject();
		} catch (Exception e) {
			throw new IllegalStateException("Could not read the bundled balance", e);
		}
	}

	/** The bundled file with one crafted-enchant value replaced, the way an override would. */
	private static JsonObject withCraftEnchant(String key, Number value) {
		JsonObject merged = bundled();
		merged.getAsJsonObject("vanillaPlus").getAsJsonObject("craftEnchant").addProperty(key, value);
		return merged;
	}

	@Test
	void theBundledNumbersBind() {
		Balance.CraftEnchantBalance craftEnchant = BalanceManager.bind(bundled()).craftEnchant();

		assertTrue(craftEnchant.maxUnlockLevel() > 1);
		assertTrue(craftEnchant.strengthPerLevel() > 0);
	}

	@Test
	void aFractionalMaxLevelIsRefused() {
		BalanceException refused = assertThrows(BalanceException.class,
				() -> BalanceManager.bind(withCraftEnchant("maxUnlockLevel", 1.5)));

		assertTrue(refused.getMessage().contains("vanillaPlus.craftEnchant.maxUnlockLevel"), refused.getMessage());
	}

	@Test
	void anUnlockThatCannotBeBoughtAtAllIsRefused() {
		assertThrows(BalanceException.class, () -> BalanceManager.bind(withCraftEnchant("maxUnlockLevel", 0)));
		assertThrows(BalanceException.class, () -> BalanceManager.bind(withCraftEnchant("maxUnlockLevel", -1)));
	}

	@Test
	void aLevelWorthNothingIsRefused() {
		assertThrows(BalanceException.class, () -> BalanceManager.bind(withCraftEnchant("strengthPerLevel", 0)));
		assertThrows(BalanceException.class, () -> BalanceManager.bind(withCraftEnchant("strengthPerLevel", -0.25)));
	}

	@Test
	void anUnknownKeyInTheSectionIsRefused() {
		JsonObject merged = bundled();
		merged.getAsJsonObject("vanillaPlus").getAsJsonObject("craftEnchant").addProperty("strengthPerLevl", 0.5);

		BalanceException refused = assertThrows(BalanceException.class, () -> BalanceManager.bind(merged));

		assertTrue(refused.getMessage().contains("strengthPerLevl"), refused.getMessage());
	}

	@Test
	void aMissingSectionIsRefused() {
		JsonObject merged = bundled();
		merged.remove("vanillaPlus");

		assertEquals(true, assertThrows(BalanceException.class, () -> BalanceManager.bind(merged))
				.getMessage().contains("vanillaPlus"));
	}
}
