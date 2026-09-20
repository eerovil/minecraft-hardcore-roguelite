package fi.vilpponen.mhr.core;

import java.io.IOException;
import java.nio.channels.FileChannel;
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
 * <p>Nothing here retries or falls back quietly. A write that did not happen is reported, because
 * the caller is the only one that knows whether it can carry on without it.
 */
public final class AtomicFile {
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
				channel.write(StandardCharsets.UTF_8.encode(contents));
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

	/** Remove a file, and make sure the removal itself has reached the disk. */
	public static void delete(Path file) throws IOException {
		if (!Files.deleteIfExists(file)) {
			return;
		}
		forceDirectory(file.getParent());
	}

	private static void move(Path from, Path to) throws IOException {
		try {
			Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			// Some filesystems will not promise it. A plain replace is still better than writing
			// over the real file in place, and there is nothing further to try.
			Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/**
	 * Force the directory entry, where the platform allows it.
	 *
	 * <p>Windows refuses to open a directory as a channel at all, and there is no portable way to
	 * ask; the rename is already atomic there. So a refusal is not a failure of the write.
	 */
	private static void forceDirectory(Path directory) {
		try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
			channel.force(true);
		} catch (IOException | UnsupportedOperationException ignored) {
			// See above: not every platform lets a directory be opened, and the move has happened.
		}
	}
}
