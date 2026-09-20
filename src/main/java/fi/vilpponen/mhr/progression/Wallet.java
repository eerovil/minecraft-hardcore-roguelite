package fi.vilpponen.mhr.progression;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fi.vilpponen.mhr.HardcoreRoguelite;
import fi.vilpponen.mhr.core.AtomicFile;
import fi.vilpponen.mhr.core.PersistenceException;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/**
 * How much currency the player has to spend in the shop.
 *
 * <p>Permanent, like the unlocks it buys, and stored the same way and for the same reason: in the
 * Fabric config directory rather than in a world, because a run is disposable and progression is
 * not. See {@code docs/codebase/progression.md}.
 *
 * <p>It is a separate file from the unlocks rather than a key inside them. The unlock file is a
 * plain map of id to level and has a migration history; wedging a number that is not an unlock into
 * it would mean every reader has to know which keys are not unlocks. Two small files with one shape
 * each is cheaper than one file with two.
 *
 * <p><b>What this deliberately does not do is earn anything.</b> How currency is paid out — what it
 * is paid for, whether a run that ends badly pays, whether the total is visible during a run — is
 * still the biggest open design question in {@code docs/open-questions.md}, and the balance file's
 * {@code currency.advancements} table is a price list waiting for the rule rather than the rule
 * itself. Until that is decided, the only things that move this number are the shop spending it and
 * the dev command granting it.
 */
public final class Wallet {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE_NAME = "hardcore-roguelite-currency.json";
	private static final String BALANCE_KEY = "balance";

	private static volatile Wallet instance;

	private final Path file;
	private int balance;

	private Wallet(Path file) {
		this.file = file;
	}

	public static Wallet get() {
		Wallet local = instance;
		if (local == null) {
			synchronized (Wallet.class) {
				local = instance;
				if (local == null) {
					local = new Wallet(FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME));
					local.load();
					instance = local;
				}
			}
		}
		return local;
	}

	/**
	 * Throw away what is loaded and read the file again.
	 *
	 * <p>For the same reason {@code UnlockState.reloadFromFile} exists: in the automated tests the
	 * dedicated server runs inside the client's process, so a test asking whether a purchase really
	 * reached the disk has no process boundary to cross and has to ask for one.
	 */
	public static Wallet reloadFromFile() {
		synchronized (Wallet.class) {
			instance = null;
			return get();
		}
	}

	public synchronized int balance() {
		return balance;
	}

	public synchronized boolean canAfford(int price) {
		return price <= balance;
	}

	/** Put currency in. Negative amounts are not an earning rule, they are a bug, so they throw. */
	public synchronized void earn(int amount) {
		if (amount < 0) {
			throw new IllegalArgumentException("Cannot earn a negative amount: " + amount);
		}
		set(balance + amount);
	}

	/**
	 * Set the total outright.
	 *
	 * <p>There is deliberately no "spend" beside this. A purchase decides the total it wants while
	 * holding this object's monitor and then says so, because the total it writes has to be the same
	 * number it wrote into the journal — see {@link PurchaseJournal}. Two ways to move currency is
	 * one more than the one the design allows.
	 *
	 * <p>The new total is in memory before it is on the disk, so a running game stays consistent
	 * even when the write fails; what the failure costs is the restart, which is what the journal
	 * is for.
	 *
	 * @throws PersistenceException if the new total did not reach the disk
	 */
	public synchronized void set(int amount) {
		balance = Math.max(0, amount);
		save();
	}

	private synchronized void load() {
		if (!Files.isRegularFile(file)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(file)) {
			JsonElement root = JsonParser.parseReader(reader);
			if (root == null || !root.isJsonObject()) {
				return;
			}
			JsonElement value = root.getAsJsonObject().get(BALANCE_KEY);
			balance = value == null ? 0 : Math.max(0, value.getAsInt());
		} catch (IOException | RuntimeException e) {
			// Deliberately not fatal, and deliberately not a guess either: a currency file we cannot
			// read is a problem, but refusing to start the game over it would cost the player their
			// unlocks as well. Nothing is written back until something legitimately changes the
			// total, so a file that is merely unreadable today is still there to be looked at.
			HardcoreRoguelite.LOGGER.error("Could not read {}, starting this session with nothing to spend", file, e);
		}
	}

	/**
	 * @throws PersistenceException if the total did not reach the disk. It used to be logged and
	 *     swallowed, which meant a failed write and a successful one were the same answer, and a
	 *     purchase could be reported as bought on the strength of neither.
	 */
	private synchronized void save() {
		JsonObject root = new JsonObject();
		root.addProperty(BALANCE_KEY, balance);
		try {
			AtomicFile.write(file, GSON.toJson(root));
		} catch (IOException e) {
			throw new PersistenceException("Could not write " + file, e);
		}
	}
}
