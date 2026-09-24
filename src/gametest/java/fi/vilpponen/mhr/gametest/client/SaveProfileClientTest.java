package fi.vilpponen.mhr.gametest.client;

import fi.vilpponen.mhr.UnlockState;
import fi.vilpponen.mhr.progression.Progress;
import fi.vilpponen.mhr.progression.Wallet;
import fi.vilpponen.mhr.shop.client.SyncedShop;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One Minecraft save is one roguelite profile.
 *
 * <p>Two real singleplayer saves, created from the client the way a player creates them, each
 * given different currency and unlocks, then each reopened. What is being proved is the ownership
 * rule itself: a new save starts with nothing, a save that was played keeps what it had, and no save
 * ever reads or writes another's progression.
 *
 * <p>A profile left in the installation's config directory by an older build is put there first,
 * with currency and an unlock in it. It must be neither read nor copied into either save: it cannot
 * say which save it belonged to, so it belongs to none.
 *
 * <p>Singleplayer rather than the dedicated server the other client tests use, because a dedicated
 * server is one save for its whole life, and this is about there being two.
 */
public class SaveProfileClientTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("mhr-gametest");

	private static final String COAL = "world.ore.coal";
	private static final String VILLAGE = "world.village";
	private static final String SNAPSHOT = "hardcore-roguelite-progress.json";

	/** Different in each save, and different from the installation-wide leftover. */
	private static final int CURRENCY_A = 35;
	private static final int CURRENCY_B = 4;
	private static final int LEFTOVER_CURRENCY = 999;

	private final List<String> failures = new ArrayList<>();

	@Override
	public void runTest(ClientGameTestContext context) {
		Path leftover = FabricLoader.getInstance().getConfigDir().resolve(SNAPSHOT);
		String leftoverContents = "{\"currency\": " + LEFTOVER_CURRENCY + ", \"unlocks\": {\"" + COAL
				+ "\": 1, \"" + VILLAGE + "\": 1}}";
		write(leftover, leftoverContents);

		try {
			TestWorldSave saveA;
			TestWorldSave saveB;

			try (TestSingleplayerContext a = context.worldBuilder().create()) {
				saveA = a.getWorldSave();
				scenario(context, "a-new-save-starts-with-nothing",
						() -> startsWithNothing(a.getServer(), saveA, "save A"));
				scenario(context, "save-a-buys-its-own-things",
						() -> establish(context, a.getServer(), CURRENCY_A, COAL, "save-a-first-played"));
			}

			try (TestSingleplayerContext b = context.worldBuilder().create()) {
				saveB = b.getWorldSave();
				check(!normal(saveA.getSaveDirectory()).equals(normal(saveB.getSaveDirectory())),
						"setup: the second save needs a directory of its own, and both are "
								+ saveA.getSaveDirectory());
				scenario(context, "a-second-new-save-does-not-see-the-first",
						() -> startsWithNothing(b.getServer(), saveB, "save B, created after save A"));
				scenario(context, "save-b-buys-different-things",
						() -> establish(context, b.getServer(), CURRENCY_B, VILLAGE, "save-b-first-played"));
			}

			scenario(context, "reopening-save-a-brings-back-only-its-own-profile", () -> {
				try (TestSingleplayerContext a = saveA.open()) {
					comesBackAs(context, a.getServer(), saveA, CURRENCY_A, COAL, VILLAGE,
							"save-a-reopened");
				}
			});
			scenario(context, "reopening-save-b-brings-back-only-its-own-profile", () -> {
				try (TestSingleplayerContext b = saveB.open()) {
					comesBackAs(context, b.getServer(), saveB, CURRENCY_B, VILLAGE, COAL,
							"save-b-reopened");
				}
			});

			scenario(context, "the-installation-keeps-no-progression", () -> {
				check(leftoverContents.equals(read(leftover)),
						"the leftover in the installation's config directory must be left exactly as"
								+ " it was: nothing reads it and nothing writes it, and it now says "
								+ read(leftover));
				check(Files.isRegularFile(saveA.getSaveDirectory().resolve(SNAPSHOT))
								&& Files.isRegularFile(saveB.getSaveDirectory().resolve(SNAPSHOT)),
						"each save must hold its own snapshot at its root");
			});
		} finally {
			delete(leftover);
		}

		if (!failures.isEmpty()) {
			throw new AssertionError(failures.size() + " save-profile scenario(s) failed:\n  "
					+ String.join("\n  ", failures));
		}
		LOGGER.info("All save-profile client scenarios passed.");
	}

	// --- the scenarios ---------------------------------------------------------------------

	/**
	 * Nothing owned, nothing to spend, and the file that would hold it is this save's. Deliberately
	 * not reset first: a new save has to <em>be</em> empty, not be made so.
	 */
	private static void startsWithNothing(TestServerContext server, TestWorldSave save, String which) {
		Path file = server.computeOnServer(unused -> Progress.file());
		check(normal(file).equals(normal(save.getSaveDirectory().resolve(SNAPSHOT))),
				which + "'s progression must live at the root of that save, and it is at " + file);

		int balance = server.computeOnServer(unused -> Wallet.get().balance());
		List<String> owned = server.computeOnServer(unused -> List.copyOf(UnlockState.get().ownedIds()));
		check(balance == 0, which + " must start with no currency, and it has " + balance
				+ (balance == LEFTOVER_CURRENCY ? " — the installation-wide leftover" : ""));
		check(owned.isEmpty(), which + " must start with nothing owned, and it owns " + owned);
	}

	/** Give the save a purse and an unlock through the dev commands, and let the HUD show it. */
	private static void establish(ClientGameTestContext context, TestServerContext server, int currency,
			String unlock, String screenshot) {
		server.runCommand("mhr currency set " + currency);
		server.runCommand("mhr unlock " + unlock);

		int balance = server.computeOnServer(unused -> Wallet.get().balance());
		boolean owns = server.computeOnServer(unused -> UnlockState.get().isOwned(unlock));
		check(balance == currency && owns, "setup: the save should now hold " + currency + " and "
				+ unlock + ", and it holds " + balance + (owns ? " and owns it" : " without it"));

		waitForHud(context, currency);
		context.takeScreenshot(screenshot);
	}

	/**
	 * What reopening a save has to give back: its own currency and unlock, read from its own file,
	 * and nothing of the other save's.
	 */
	private static void comesBackAs(ClientGameTestContext context, TestServerContext server,
			TestWorldSave save, int currency, String owned, String other, String screenshot) {
		Path file = server.computeOnServer(unused -> Progress.file());
		check(normal(file).equals(normal(save.getSaveDirectory().resolve(SNAPSHOT))),
				"a reopened save must answer from its own file, and it answers from " + file);

		int balance = server.computeOnServer(unused -> Wallet.reloadFromFile().balance());
		boolean ownsIt = server.computeOnServer(unused -> UnlockState.get().isOwned(owned));
		boolean ownsTheOther = server.computeOnServer(unused -> UnlockState.get().isOwned(other));
		check(balance == currency, "the save must come back with its own " + currency
				+ " currency, and it has " + balance);
		check(ownsIt, "the save must come back still owning " + owned);
		check(!ownsTheOther, "the save must not own " + other + ", which only the other save bought");

		waitForHud(context, currency);
		context.takeScreenshot(screenshot);
	}

	// --- plumbing --------------------------------------------------------------------------

	/** The HUD draws what the server last sent, so this is the player seeing the right purse. */
	private static void waitForHud(ClientGameTestContext context, int currency) {
		context.waitFor(client -> SyncedShop.isKnown() && SyncedShop.currency() == currency);
		context.waitTicks(2);
	}

	private void scenario(ClientGameTestContext context, String name, Runnable body) {
		LOGGER.info("=== scenario {} ===", name);
		try {
			body.run();
			LOGGER.info("=== scenario {}: PASS ===", name);
		} catch (Throwable failure) {
			failures.add(name + ": " + failure.getMessage());
			LOGGER.error("=== scenario {}: FAIL === {}", name, failure.getMessage(), failure);
			try {
				context.takeScreenshot("failed-" + name);
			} catch (Throwable ignored) {
				LOGGER.warn("Could not screenshot the failure of {}", name);
			}
		}
	}

	private static Path normal(Path path) {
		return path.toAbsolutePath().normalize();
	}

	private static void write(Path file, String contents) {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, contents, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not write " + file, e);
		}
	}

	private static String read(Path file) {
		try {
			return Files.readString(file, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not read " + file, e);
		}
	}

	private static void delete(Path file) {
		try {
			Files.deleteIfExists(file);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not remove " + file, e);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
