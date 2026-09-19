package fi.vilpponen.mhr.enchant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The level curve, which is the part of the unlock a player actually feels, checked against the
 * numbers the mod actually ships.
 *
 * <p>The values are read out of {@code default-balance.json} rather than written down again here, so
 * this fails if the data and the code stop agreeing — a curve that no longer reaches the top, or a
 * level nothing can be bought at. Reading the file directly rather than through
 * {@code BalanceManager} keeps the test out of the Fabric runtime, the same way {@code BalanceTest}
 * does.
 *
 * <p>Three enchantment ceilings stand in for the whole registry: Silk Touch stops at 1, Fortune at
 * 3, Efficiency at 5.
 */
class CraftEnchantBalanceTest {
	private static final int[] CEILINGS = {1, 3, 5};

	private static final JsonObject SHIPPED = shipped();
	private static final int MAX_UNLOCK_LEVEL = SHIPPED.get("maxUnlockLevel").getAsInt();
	private static final double STRENGTH_PER_LEVEL = SHIPPED.get("strengthPerLevel").getAsDouble();

	private static JsonObject shipped() {
		try (InputStream in = CraftEnchantBalanceTest.class.getResourceAsStream("/default-balance.json");
				Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
			return JsonParser.parseReader(reader).getAsJsonObject()
					.getAsJsonObject("vanillaPlus")
					.getAsJsonObject("craftEnchant");
		} catch (Exception e) {
			throw new IllegalStateException("Could not read the bundled balance", e);
		}
	}

	private static int level(int unlockLevel, int maxEnchantmentLevel) {
		return CraftEnchantBalance.enchantmentLevel(unlockLevel, maxEnchantmentLevel,
				MAX_UNLOCK_LEVEL, STRENGTH_PER_LEVEL);
	}

	@Test
	void theShippedCurveIsBuyableMoreThanOnce() {
		assertTrue(MAX_UNLOCK_LEVEL > 1, "the unlock is meant to be repeatable, not on or off");
	}

	@Test
	void theTopUnlockLevelGivesTheEnchantmentsOwnMaximum() {
		for (int max : CEILINGS) {
			assertEquals(max, level(MAX_UNLOCK_LEVEL, max));
		}
	}

	@Test
	void buyingTheUnlockAgainNeverMakesItWeaker() {
		for (int max : CEILINGS) {
			for (int unlockLevel = 2; unlockLevel <= MAX_UNLOCK_LEVEL; unlockLevel++) {
				int previous = level(unlockLevel - 1, max);
				int current = level(unlockLevel, max);
				assertTrue(current >= previous,
						"max " + max + ": level " + unlockLevel + " gave " + current + ", worse than " + previous);
			}
		}
	}

	@Test
	void theTopUnlockLevelIsStrongerThanTheFirstWhereverTheresRoom() {
		assertTrue(level(MAX_UNLOCK_LEVEL, 5) > level(1, 5));
		assertTrue(level(MAX_UNLOCK_LEVEL, 3) > level(1, 3));
	}

	@Test
	void aRolledEnchantmentIsAlwaysWorthSomething() {
		for (int max : CEILINGS) {
			for (int unlockLevel = 1; unlockLevel <= MAX_UNLOCK_LEVEL; unlockLevel++) {
				int level = level(unlockLevel, max);
				assertTrue(level >= 1 && level <= max, "max " + max + " level " + unlockLevel + " gave " + level);
			}
		}
	}

	@Test
	void anUnlockLevelOutsideTheRangeIsClamped() {
		assertEquals(level(MAX_UNLOCK_LEVEL, 5), level(MAX_UNLOCK_LEVEL + 10, 5));
		assertEquals(1, level(-1, 5));
	}
}
