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
 */
public final class AtomicFile {
	/** Whether this run has already said that directories cannot be flushed. */
	private static volatile boolean directoriesCannotBeOpened;

	private AtomicFile() {
	}

	/**
	 * Replace a file's whole contents, or leave it exactly as it was.
	 *
	 * @throws IOException if the new contents did not reach the disk, in which case the file still
	 *     holds whatever it held before
	 */
	public static void write(Path file, String contents) throws IOException {
		Path directory = file.getParent();
		Files.createDirectories(directory);
		Path temporary = directory.resolve(file.getFileName() + ".tmp");

		try {
			try (FileChannel channel = FileChannel.open(temporary,
					StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
				writeFully(channel, StandardCharsets.UTF_8.encode(contents));
				channel.force(true);
			}
			move(temporary, file);
			forceDirectory(directory);
		} catch (IOException e) {
			// The half-written temporary file is of no use to anyone, and leaving it behind would
			// only confuse the next person to look in the config directory.
			try {
				Files.deleteIfExists(temporary);
			} catch (IOException ignored) {
				// Nothing useful to do about it, and the real failure is the one being thrown.
			}
			throw e;
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
