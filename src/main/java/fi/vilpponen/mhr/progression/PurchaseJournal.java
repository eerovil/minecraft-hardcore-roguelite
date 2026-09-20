package fi.vilpponen.mhr.progression;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fi.vilpponen.mhr.HardcoreRoguelite;
import fi.vilpponen.mhr.UnlockState;
import fi.vilpponen.mhr.core.AtomicFile;
import fi.vilpponen.mhr.core.PersistenceException;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The single moment at which a purchase becomes real.
 *
 * <p>A purchase moves two things that live in two files: the currency and what is owned. Writing
 * them one after the other has a window in the middle, and a crash inside that window used to leave
 * either a free unlock or currency spent on nothing. Neither file can be made to answer for the
 * other, so a third, tiny file answers for both.
 *
 * <p>Before either of them is touched, this writes down the whole of the intended outcome —
 * atomically, so it is either wholly there or wholly absent. That write is the commit point. Once
 * it has landed the purchase has happened, whatever becomes of the next two writes; if the game
 * stops before both of them are on the disk, {@link #settle()} finishes the job the next time it
 * starts. Once both are on the disk the record is deleted, and the ordinary state of this file is
 * not to exist.
 *
 * <p>There is room for one record, so there may only ever be one purchase outstanding. A second
 * purchase committed over an unfinished first would replace the only note of the first, and the
 * first unlock would be lost while its price stayed spent. That is why {@link Purchase} settles
 * before it commits, and refuses outright if settling does not work: a record that is still there
 * is an obligation nothing is allowed to write over.
 *
 * <p>The record holds the levels and totals the purchase is aiming at, not the amounts it is
 * moving. That is what makes recovery safe to run twice, or on a purchase that had in fact already
 * finished: setting a number to what it already is does nothing.
 *
 * <p>A record that cannot be read is treated as absent. The write is atomic, so a half-written one
 * cannot exist; a corrupt one means the file is damaged in some other way, and acting on a purchase
 * we cannot read is worse than forgetting one that never committed.
 */
public final class PurchaseJournal {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE_NAME = "hardcore-roguelite-purchase.json";

	private static final String ID = "id";
	private static final String LEVEL = "level";
	private static final String BALANCE = "balance";

	/**
	 * What a purchase is aiming at: the level it should end up owning and the total it should have
	 * left.
	 *
	 * <p>Public, with {@link #commit}, because the crash-recovery test has to be able to leave
	 * behind exactly what a session that stopped mid-purchase leaves behind, and writing the file by
	 * hand in the test would only prove the test knows the format. {@link Purchase} is still the
	 * only thing in the mod that commits one.
	 */
	public record Record(String id, int level, int balance) {
	}

	private PurchaseJournal() {
	}

	/** Where the record lives. Public so a test can get in the way of it on purpose. */
	public static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
	}

	/**
	 * Commit a purchase.
	 *
	 * <p>Until this returns, nothing has happened and the caller may still refuse. After it returns,
	 * the purchase is the player's whether or not the game survives the next two writes.
	 *
	 * @throws IOException if the record did not reach the disk, in which case nothing is committed
	 */
	public static void commit(Record record) throws IOException {
		JsonObject json = new JsonObject();
		json.addProperty(ID, record.id());
		json.addProperty(LEVEL, record.level());
		json.addProperty(BALANCE, record.balance());
		AtomicFile.write(file(), GSON.toJson(json));
	}

	/** Both files now hold what the record asked for, so there is nothing left to finish. */
	static void done() {
		try {
			AtomicFile.delete(file());
		} catch (IOException e) {
			// Harmless in itself: the record now says exactly what both files already say, so the
			// worst a leftover does is make the next start redo work that is already done.
			HardcoreRoguelite.LOGGER.warn("Could not remove the finished purchase record {}", file(), e);
		}
	}

	/** Whether a purchase is waiting to be finished. */
	public static boolean isPending() {
		return read() != null;
	}

	/**
	 * Finish whatever is outstanding, if anything is.
	 *
	 * <p>Called when the mod starts, before anything reads what the player owns, and again before
	 * every purchase — a purchase may not commit while another one is unfinished, because there is
	 * only one record and committing would write over it.
	 *
	 * <p>Safe to call at any time and safe to call twice: the record says what the two numbers
	 * should be, so applying it again is applying the same numbers again.
	 *
	 * @return true when nothing is outstanding any more, false when the disk still will not take it
	 */
	public static boolean settle() {
		Record record = read();
		if (record == null) {
			return true;
		}

		HardcoreRoguelite.LOGGER.warn(
				"Finishing an unfinished purchase of '{}': level {}, {} left to spend",
				record.id(), record.level(), record.balance());
		try {
			// Both of these write unconditionally. What is outstanding is a write, not a value:
			// whichever half failed last time has memory that already agrees and a file that does
			// not, so anything that skipped the write when the value matched would report the
			// obligation as discharged and then delete the record.
			Wallet.get().set(record.balance());
			UnlockState.get().restoreLevel(record.id(), record.level());
		} catch (PersistenceException e) {
			// The record stays, so this is tried again — at the next purchase, or the next start.
			// Nothing is lost by giving up here, and something would be lost by carrying on.
			HardcoreRoguelite.LOGGER.error("Could not finish the purchase of '{}'; it will be retried",
					record.id(), e);
			return false;
		}
		// Both files now say what the record says, so the obligation is discharged whether or not
		// the record itself can be removed.
		done();
		return true;
	}

	private static Record read() {
		Path file = file();
		if (!Files.isRegularFile(file)) {
			return null;
		}
		try (Reader reader = Files.newBufferedReader(file)) {
			JsonElement root = JsonParser.parseReader(reader);
			if (root == null || !root.isJsonObject()) {
				return null;
			}
			JsonObject json = root.getAsJsonObject();
			return new Record(json.get(ID).getAsString(), json.get(LEVEL).getAsInt(), json.get(BALANCE).getAsInt());
		} catch (IOException | RuntimeException e) {
			HardcoreRoguelite.LOGGER.error("Could not read {}; ignoring it", file, e);
			return null;
		}
	}
}
