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
	void aMissingPathIsAnError() {
		// There is nowhere for a fallback to come from. A value the code reads but the data does not
		// have is a mistake in one of the two, and the one place a balance number may be written
		// down is default-balance.json.
		Balance balance = of("{\"vanillaPlus\": {\"speed\": {\"stepPercent\": 10}}}");

		assertTrue(assertThrows(BalanceException.class, () -> balance.number("vanillaPlus.hunger.drainPercent"))
				.getMessage().contains("vanillaPlus.hunger.drainPercent"));
		assertThrows(BalanceException.class, () -> balance.number("nothingLikeThis"));
		assertThrows(BalanceException.class, () -> balance.integer("vanillaPlus.speed.somethingElse"));
	}

	@Test
	void presentPathIsRead() {
		Balance balance = of("{\"vanillaPlus\": {\"speed\": {\"stepPercent\": 10}}}");

		assertEquals(10, balance.number("vanillaPlus.speed.stepPercent"));
		assertEquals(10, balance.integer("vanillaPlus.speed.stepPercent"));
	}

	@Test
	void pathThroughANonObjectIsAnError() {
		// The file says speed is 12; the code expects it to hold stepPercent. One of the two is
		// wrong, and the message has to say which prefix is the wrong shape rather than just
		// reporting the leaf as missing.
		Balance balance = of("{\"vanillaPlus\": {\"speed\": 12}}");

		BalanceException thrown = assertThrows(BalanceException.class,
				() -> balance.number("vanillaPlus.speed.stepPercent"));

		assertTrue(thrown.getMessage().contains("vanillaPlus.speed"), thrown.getMessage());
		assertTrue(thrown.getMessage().contains("should be an object"), thrown.getMessage());
	}

	@Test
	void anObjectReplacedByAScalarIsAnError() {
		// An override doing {"vanillaPlus": 3} to a bundled object is refused before the merge now
		// (see BalanceOverrideKeysTest), but the read stays strict too: bundled data this shape is
		// a mistake in the mod, and it is still not allowed to read as a plausible number.
		Balance balance = of("{\"vanillaPlus\": 3}");

		assertThrows(BalanceException.class, () -> balance.number("vanillaPlus.speed.stepPercent"));
		assertThrows(BalanceException.class, () -> balance.integer("vanillaPlus.speed.stepPercent"));
	}

	@Test
	void pathThroughAnArrayIsAnError() {
		Balance balance = of("{\"vanillaPlus\": {\"speed\": [10, 20]}}");

		assertThrows(BalanceException.class, () -> balance.number("vanillaPlus.speed.stepPercent"));
	}

	@Test
	void aValueThatIsNotANumberIsAnError() {
		Balance balance = of("{\"vanillaPlus\": {\"speed\": {\"stepPercent\": \"fast\"}}}");

		BalanceException thrown = assertThrows(BalanceException.class,
				() -> balance.number("vanillaPlus.speed.stepPercent"));

		assertTrue(thrown.getMessage().contains("should be a number"), thrown.getMessage());
	}

	@Test
	void numbersThatCannotBeRepresentedAreErrors() {
		Balance tooBig = of("{\"tuning\": {\"value\": 1e20}}");
		Balance overflowing = of("{\"tuning\": {\"value\": 1e400}}");
		Balance fractional = of("{\"tuning\": {\"value\": 2.5}}");

		assertThrows(BalanceException.class, () -> tooBig.integer("tuning.value"));
		assertThrows(BalanceException.class, () -> overflowing.number("tuning.value"));
		assertThrows(BalanceException.class, () -> fractional.integer("tuning.value"));
		assertEquals(Integer.MAX_VALUE, of("{\"tuning\": {\"value\": 2147483647}}").integer("tuning.value"));
	}

	@Test
	void unlockIdsWithDotsAreNotReachableByPath() {
		// Documented: unlock keys contain dots, so the path lookup cannot see into them. Reaching for
		// one says the value is missing, which is the truth, and unlockPrice is the way in.
		Balance balance = of("{\"unlocks\": {\"world.trees\": {\"price\": 3}}}");

		assertTrue(assertThrows(BalanceException.class, () -> balance.number("unlocks.world.trees.price"))
				.getMessage().contains("missing the value 'unlocks.world.trees.price'"));
	}
}
