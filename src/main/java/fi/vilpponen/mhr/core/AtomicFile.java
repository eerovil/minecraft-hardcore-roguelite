package fi.vilpponen.mhr.core;

import fi.vilpponen.mhr.HardcoreRoguelite;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Writing a small file so that a crash leaves either the old contents or the new ones.
 *
 * <p>The ordinary way to write a file — open it, write, close — has a window in the middle where it
 * is truncated and half written. For a config file that is a nuisance; for the files holding what a
 * player has permanently bought it is the difference between a bad restart and a lost purchase. So
 * the bytes go to a temporary file next to the real one, are forced down to the disk, and only then
 * replace it in one move.
 *
 * <p>The directory is forced too. A rename is a change to the directory, and without that the
 * rename itself can still be in a cache when the power goes.
 *
 * <p><b>Nothing here falls back.</b> Permanent progression is built on the promise this class
 * makes, so a filesystem that cannot keep the promise has to say so rather than quietly do
 * something weaker: a plain replace instead of an atomic one leaves a window where the file is
 * neither the old contents nor the new, and a caller told "written" would have sold something on
 * the strength of it. Every failure is reported, because the caller is the only one that knows
 * whether it can carry on without the write.
 *
 * <p><b>The rename is the commit point, and a failure says which side of it it is on.</b> That
 * distinction is the whole of this class's contract, because the two sides call for opposite
 * answers and a caller that cannot tell them apart will get one of them wrong:
 *
 * <ul>
 *   <li>{@link NotWritten} — the target still holds exactly what it held. Nothing happened, and a
 *       caller may say so.</li>
 *   <li>{@link WrittenNotFlushed} — the target holds the new contents. The change <em>has</em>
 *       happened; what is in doubt is only whether it would survive the power going out. A caller
 *       that treats this as "nothing happened" is lying about a file it can go and read, and will
 *       later write its stale idea of the contents back over the real ones.</li>
 * </ul>
 */
public final class AtomicFile {
	/** The write did not happen. Whatever the target held, it still holds. */
	public static class NotWritten extends IOException {
		NotWritten(Path file, Throwable cause) {
			super("Could not write " + file + "; it still holds what it held", cause);
		}
	}

	/**
	 * The target holds the new contents, and only their survival of a power cut is in doubt.
	 *
	 * <p>Not a failure of the write. The rename has happened, so the file <em>is</em> the new one;
	 * what could not be done is flushing the directory entry that makes the rename itself durable.
	 */
	public static class WrittenNotFlushed extends IOException {
		WrittenNotFlushed(Path file, Throwable cause) {
			super("Wrote " + file + ", and could not flush the directory, so it might not survive a"
					+ " power cut. The file itself is the new one.", cause);
		}
	}

	/**
	 * Flushing a directory, so the test that has to fail after the rename can.
	 *
	 * <p>The one step this class cannot provoke a failure in through its public method: on any real
	 * filesystem a directory that opens will flush. Same reason {@link #writeFully} takes a channel
	 * interface.
	 */
	@FunctionalInterface
	public interface DirectoryFlush {
		void flush(Path directory) throws IOException;
	}

	/** Whether this run has already said that directories cannot be flushed. */
	private static volatile boolean directoriesCannotBeOpened;

	/** Nothing in the game ever replaces this. See {@link #useDirectoryFlush}. */
	private static volatile DirectoryFlush directoryFlush = AtomicFile::forceDirectory;

	private AtomicFile() {
	}

	/**
	 * Replace how directories are flushed. For the test that proves what a post-rename failure does;
	 * pass null to put the real one back.
	 */
	public static void useDirectoryFlush(DirectoryFlush flush) {
		directoryFlush = flush == null ? AtomicFile::forceDirectory : flush;
	}

	/**
	 * Replace a file's whole contents, or leave it exactly as it was.
	 *
	 * <p>The body is in two halves on purpose, with the rename between them, because that is where
	 * the commit point is and the caller has to be able to see which half failed.
	 *
	 * @throws NotWritten if it did not happen, in which case the file still holds what it held
	 * @throws WrittenNotFlushed if it did happen but may not survive a power cut
	 */
	public static void write(Path file, String contents) throws NotWritten, WrittenNotFlushed {
		Path directory = file.getParent();
		Path temporary = directory == null ? null : directory.resolve(file.getFileName() + ".tmp");

		// --- before the commit point: anything that goes wrong here changed nothing ---
		try {
			Files.createDirectories(directory);
			try (FileChannel channel = FileChannel.open(temporary,
					StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
				writeFully(channel, StandardCharsets.UTF_8.encode(contents));
				channel.force(true);
			}
			move(temporary, file);
		} catch (IOException | RuntimeException e) {
			// The half-written temporary file is of no use to anyone, and leaving it behind would
			// only confuse the next person to look in the config directory.
			deleteQuietly(temporary);
			throw new NotWritten(file, e);
		}

		// --- after it: the file is the new one, whatever happens next ---
		try {
			directoryFlush.flush(directory);
		} catch (IOException | RuntimeException e) {
			throw new WrittenNotFlushed(file, e);
		}
	}

	private static void deleteQuietly(Path file) {
		if (file == null) {
			return;
		}
		try {
			Files.deleteIfExists(file);
		} catch (IOException ignored) {
			// Nothing useful to do about it, and the real failure is the one being thrown.
		}
	}

	/**
	 * Empty the buffer into the channel.
	 *
	 * <p>{@link FileChannel#write(ByteBuffer)} says how many bytes it took, and is allowed to take
	 * fewer than it was offered. One call and no loop is the bug that turns a long-enough file into
	 * a shorter one — which would then be forced to the disk and renamed over the real one, so the
	 * truncation would be the permanent state rather than a failure.
	 *
	 * <p>A channel that takes nothing at all cannot make progress, and looping on it would hang the
	 * game rather than report anything, so that is an error.
	 *
	 * <p>Takes the interface rather than {@link FileChannel} so that a test can hand it a channel
	 * that takes one byte at a time. A real file rarely writes short, which is exactly why the
	 * missing loop survived review: it cannot be provoked through the public method.
	 */
	static void writeFully(WritableByteChannel channel, ByteBuffer buffer) throws IOException {
		while (buffer.hasRemaining()) {
			if (channel.write(buffer) <= 0) {
				throw new IOException("Wrote nothing with " + buffer.remaining() + " byte(s) left to write");
			}
		}
	}

	/**
	 * Put the finished file in place in one step.
	 *
	 * <p>There is deliberately no fallback to a plain replace. A filesystem that will not promise an
	 * atomic rename cannot give the caller what this class says it gives, and quietly doing the
	 * weaker thing while reporting success is how a purchase gets sold against a guarantee that was
	 * never made. {@link AtomicMoveNotSupportedException} is an {@link IOException}, so it reaches
	 * the caller as any other failed write does.
	 */
	private static void move(Path from, Path to) throws IOException {
		Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
	}

	/**
	 * Force the directory entry, so the rename itself cannot still be sitting in a cache.
	 *
	 * <p>Two different things used to be swallowed here, and only one of them is harmless.
	 *
	 * <p><b>Not being able to open the directory at all</b> is a property of the platform — Windows
	 * refuses, and there is no portable way to ask in advance. The rename is already durable there,
	 * so this is a capability and not a failure; it is said once, for the log, and the write stands.
	 *
	 * <p><b>Failing to flush a directory we did open</b> is a real I/O failure on the very step that
	 * makes the rename survive a power cut. Treating that as "nothing to see here" is exactly the
	 * fail-open this class is not allowed to have, so it is reported and the write is not.
	 */
	private static void forceDirectory(Path directory) throws IOException {
		FileChannel channel;
		try {
			channel = FileChannel.open(directory, StandardOpenOption.READ);
		} catch (IOException | UnsupportedOperationException e) {
			warnAboutDirectories(directory, e);
			return;
		}
		try (FileChannel open = channel) {
			open.force(true);
		}
	}

	/**
	 * Say once per run that directories cannot be flushed here.
	 *
	 * <p>Once, because it would otherwise be said on every purchase, and a line repeated that often
	 * is a line nobody reads.
	 */
	private static void warnAboutDirectories(Path directory, Exception reason) {
		if (directoriesCannotBeOpened) {
			return;
		}
		directoriesCannotBeOpened = true;
		HardcoreRoguelite.LOGGER.info(
				"This platform will not open {} as a channel, so directory entries are not flushed here."
						+ " The rename itself is still atomic. ({})",
				directory, reason.toString());
	}
}
