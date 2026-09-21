package fi.vilpponen.mhr.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The shipped price list for advancements, read as an economy rather than as JSON.
 *
 * <p>Now that finishing an advancement is the only way currency is earned, this table is the whole
 * income side of the design's "about five reasonable runs restores vanilla". The parsing of it is
 * {@code BalanceTest}'s business; what is checked here is that the numbers in it still describe the
 * economy the design asks for, because every one of these can be broken by a tuning edit that
 * parses perfectly.
 *
 * <p>The bands are deliberately loose. This is not a second copy of the tuning — it is the set of
 * statements that make the tuning an economy at all, and a retune that trips one of them is a
 * design change rather than a balance edit.
 */
class AdvancementPayoutBalanceTest {
	private static final String MINE_STONE = "minecraft:story/mine_stone";
	private static final String SMELT_IRON = "minecraft:story/smelt_iron";
	private static final String MINE_DIAMOND = "minecraft:story/mine_diamond";
	private static final String KILL_DRAGON = "minecraft:end/kill_dragon";

	/** What a first run that gets as far as iron tends to finish, in the shipped table. */
	private static final String[] A_FIRST_RUN = {
		MINE_STONE,
		"minecraft:story/upgrade_tools",
		SMELT_IRON,
		"minecraft:story/iron_tools",
		"minecraft:story/obtain_armor",
	};

	private static Balance bundled() {
		try (InputStream in =
						AdvancementPayoutBalanceTest.class.getResourceAsStream(BalanceManager.DEFAULT_RESOURCE);
				Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
			JsonObject source = JsonParser.parseReader(reader).getAsJsonObject();
			return BalanceManager.bindAndCheck(source);
		} catch (Exception e) {
			throw new IllegalStateException("Could not read the bundled balance", e);
		}
	}

	@Test
	void everyListedAdvancementPaysSomething() {
		for (Map.Entry<String, Integer> reward : bundled().advancementRewards().entrySet()) {
			assertTrue(reward.getValue() > 0,
					reward.getKey() + " is in the price list and pays nothing, which is what"
							+ " leaving it out already means");
		}
	}

	@Test
	void aRunPaysAllTheWayThrough() {
		Balance balance = bundled();

		assertTrue(balance.advancementReward(MINE_STONE) > 0, "the first minutes of a run must pay");
		assertTrue(balance.advancementReward("minecraft:nether/root") > 0, "the nether must pay");
		assertTrue(balance.advancementReward(KILL_DRAGON) > 0, "and so must finishing the game");
	}

	@Test
	void gettingFurtherPaysBetter() {
		Balance balance = bundled();

		assertTrue(balance.advancementReward(MINE_STONE) < balance.advancementReward(SMELT_IRON),
				"iron must be worth more than stone");
		assertTrue(balance.advancementReward(SMELT_IRON) < balance.advancementReward(MINE_DIAMOND),
				"diamond must be worth more than iron");
		assertTrue(balance.advancementReward(MINE_DIAMOND) < balance.advancementReward(KILL_DRAGON),
				"and the dragon must be worth more than anything on the way to it");
	}

	@Test
	void oneEarlyRunCanAffordSomethingFromTheShop() {
		Balance balance = bundled();

		int earned = 0;
		for (String advancement : A_FIRST_RUN) {
			earned += balance.advancementReward(advancement);
		}
		int cheapest = balance.unlocks().values().stream()
				.mapToInt(Balance.UnlockBalance::price)
				.min()
				.orElseThrow();

		assertTrue(earned >= cheapest, "a first run that reaches iron earns " + earned
				+ " and the cheapest thing in the shop costs " + cheapest + ", so it would buy"
				+ " nothing at all and the first death would be for nothing");
	}

	@Test
	void restoringVanillaTakesMoreThanOneGoodRun() {
		Balance balance = bundled();

		int earned = 0;
		for (String advancement : A_FIRST_RUN) {
			earned += balance.advancementReward(advancement);
		}
		int vanillaAgain = balance.unlocks().entrySet().stream()
				.filter(unlock -> unlock.getKey().startsWith("world.")
						|| unlock.getKey().startsWith("player.slot."))
				.mapToInt(unlock -> unlock.getValue().price())
				.sum();

		assertTrue(vanillaAgain > earned * 2, "restoring vanilla costs " + vanillaAgain
				+ " and one early run earns " + earned + ", which is not the several runs the"
				+ " design asks for");
	}

	@Test
	void theTableIsAdvancementIdsAndNotUnlockIds() {
		for (String id : bundled().advancementRewards().keySet()) {
			assertTrue(id.contains(":"), id + " is not a namespaced advancement id");
			assertEquals("minecraft", id.substring(0, id.indexOf(':')),
					id + " pays for an advancement no vanilla game has");
		}
	}
}
