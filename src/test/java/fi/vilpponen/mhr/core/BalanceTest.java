package fi.vilpponen.mhr.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The by-path escape hatch, which is the part of {@link Balance} nothing reachable from a command
 * exercises yet.
 *
 * <p>The distinction under test is the one this whole layer rests on: a value that is missing falls
 * back, a value that is wrong is reported. Getting that backwards would let a typo in a config file
 * play as a perfectly ordinary run.
 */
class BalanceTest {
	private static Balance of(String json) {
		JsonObject source = JsonParser.parseString(json).getAsJsonObject();
		return new Balance(Map.of(), Map.of(), Map.of(), 1.0, source);
	}

	@Test
	void missingPathFallsBack() {
		Balance balance = of("{\"vanillaPlus\": {\"speed\": {\"stepPercent\": 10}}}");

		assertEquals(25, balance.number("vanillaPlus.hunger.drainPercent", 25));
		assertEquals(25, balance.number("nothingLikeThis", 25));
		assertEquals(25, balance.number("vanillaPlus.speed.somethingElse", 25));
	}

	@Test
	void presentPathIsRead() {
		Balance balance = of("{\"vanillaPlus\": {\"speed\": {\"stepPercent\": 10}}}");

		assertEquals(10, balance.number("vanillaPlus.speed.stepPercent", 25));
		assertEquals(10, balance.integer("vanillaPlus.speed.stepPercent", 25));
	}

	@Test
	void pathThroughANonObjectIsAnError() {
		// The file says speed is 12; the code expects it to hold stepPercent. One of the two is
		// wrong, and quietly handing back the fallback would hide that.
		Balance balance = of("{\"vanillaPlus\": {\"speed\": 12}}");

		BalanceException thrown = assertThrows(BalanceException.class,
				() -> balance.number("vanillaPlus.speed.stepPercent", 10));

		assertTrue(thrown.getMessage().contains("vanillaPlus.speed"), thrown.getMessage());
		assertTrue(thrown.getMessage().contains("should be an object"), thrown.getMessage());
	}

	@Test
	void anObjectReplacedByAScalarIsAnError() {
		// What an override doing {"vanillaPlus": 3} to a bundled object leaves behind after the
		// merge: the merge itself succeeds, so the error has to surface on the way out.
		Balance balance = of("{\"vanillaPlus\": 3}");

		assertThrows(BalanceException.class, () -> balance.number("vanillaPlus.speed.stepPercent", 10));
		assertThrows(BalanceException.class, () -> balance.integer("vanillaPlus.speed.stepPercent", 10));
	}

	@Test
	void pathThroughAnArrayIsAnError() {
		Balance balance = of("{\"vanillaPlus\": {\"speed\": [10, 20]}}");

		assertThrows(BalanceException.class, () -> balance.number("vanillaPlus.speed.stepPercent", 10));
	}

	@Test
	void aValueThatIsNotANumberIsAnError() {
		Balance balance = of("{\"vanillaPlus\": {\"speed\": {\"stepPercent\": \"fast\"}}}");

		BalanceException thrown = assertThrows(BalanceException.class,
				() -> balance.number("vanillaPlus.speed.stepPercent", 10));

		assertTrue(thrown.getMessage().contains("should be a number"), thrown.getMessage());
	}

	@Test
	void numbersThatCannotBeRepresentedAreErrors() {
		Balance tooBig = of("{\"tuning\": {\"value\": 1e20}}");
		Balance overflowing = of("{\"tuning\": {\"value\": 1e400}}");
		Balance fractional = of("{\"tuning\": {\"value\": 2.5}}");

		assertThrows(BalanceException.class, () -> tooBig.integer("tuning.value", 0));
		assertThrows(BalanceException.class, () -> overflowing.number("tuning.value", 0));
		assertThrows(BalanceException.class, () -> fractional.integer("tuning.value", 0));
		assertEquals(Integer.MAX_VALUE, of("{\"tuning\": {\"value\": 2147483647}}").integer("tuning.value", 0));
	}

	@Test
	void unlockIdsWithDotsAreNotReachableByPath() {
		// Documented: unlock keys contain dots, so the path lookup cannot see into them. The point
		// of the test is that this is a plain miss rather than a wrong-shape error.
		Balance balance = of("{\"unlocks\": {\"world.trees\": {\"price\": 3}}}");

		assertEquals(-1, balance.number("unlocks.world.trees.price", -1));
	}
}
