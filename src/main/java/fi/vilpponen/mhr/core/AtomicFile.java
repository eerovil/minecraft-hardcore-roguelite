package fi.vilpponen.mhr.core;

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
 * Writing a small file so that a reader ever after sees either all of the old contents or all of
 * the new ones.
 *
 * <p>The ordinary way to write a file — open it, write, close — has a window in the middle where it
 * is truncated and half written. For a config file that is a nuisance; for the file holding what a
 * player has permanently bought it is the difference between a bad restart and a lost purchase. So
 * the bytes go to a temporary file next to the real one, are forced down to the disk, and only then
 * replace it in one atomic rename.
 *
 * <p><b>The rename is last, so every failure means nothing was written.</b> That is the whole
 * contract, and it is deliberately one sentence: a caller has exactly one thing to handle, and a
 * file that could not be replaced still holds what it held.
 *
 * <p><b>Nothing here falls back.</b> A filesystem that will not promise an atomic rename cannot
 * give a caller what this class says it gives, and quietly doing the weaker thing while reporting
 * success is how a purchase gets sold against a guarantee that was never made.
 * {@link AtomicMoveNotSupportedException} is an {@link IOException} and comes back like any other
 * failed write.
 *
 * <h2>What this does not promise</h2>
 *
 * <p>The file's own contents are flushed before the rename, so a reader never sees a half-written
 * file. <b>The directory entry is not flushed</b>, so on some filesystems a power cut in the moment
 * after the rename can lose the rename itself and leave the previous contents in place — an older
 * snapshot, never a broken one.
 *
 * <p>That is a deliberate limit rather than an oversight. Flushing a directory portably means
 * guessing whether a platform refusing to open one is a missing capability or a real failure, and
 * that guess was an entire branch of its own with no way to be sure it guessed right. Losing the
 * last purchase to a power cut costs a player one purchase; a storage protocol nobody can reason
 * about costs them the lot. If surviving hard power loss becomes a product requirement, it wants a
 * persistence library written to provide it, not another branch here.
 */
public final class AtomicFile {
	private AtomicFile() {
	}

	/**
	 * Replace a file's whole contents, or leave it exactly as it was.
	 *
	 * @throws IOException if it did not happen, in which case the file still holds what it held
	 */
	public static void write(Path file, String contents) throws IOException {
		Path directory = file.getParent();
		Path temporary = directory.resolve(file.getFileName() + ".tmp");

		try {
			Files.createDirectories(directory);
			try (FileChannel channel = FileChannel.open(temporary,
					StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
				writeFully(channel, StandardCharsets.UTF_8.encode(contents));
				channel.force(true);
			}
			// Last, and the only step that changes what the file is.
			Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException | RuntimeException e) {
			// The half-written temporary file is of no use to anyone, and leaving it behind would
			// only confuse the next person to look in the config directory.
			deleteQuietly(temporary);
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

	private static void deleteQuietly(Path file) {
		try {
			Files.deleteIfExists(file);
		} catch (IOException ignored) {
			// Nothing useful to do about it, and the real failure is the one being thrown.
		}
	}
}
