package fi.vilpponen.mhr.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The file writer the permanent progression files go through.
 *
 * <p>Its whole job is that a file is either wholly what it was or wholly what it is being asked to
 * be, so the things worth testing are the two ways that can fail: a write that only takes part of
 * what it was given, and a write that cannot happen at all.
 */
class AtomicFileTest {
	/** A channel that takes only so much per call, the way a real one is allowed to. */
	private record Stingy(ByteArrayOutputStream written, int perCall) implements WritableByteChannel {
		@Override
		public int write(ByteBuffer source) {
			int taken = Math.min(perCall, source.remaining());
			byte[] bytes = new byte[taken];
			source.get(bytes);
			written.writeBytes(bytes);
			return taken;
		}

		@Override
		public boolean isOpen() {
			return true;
		}

		@Override
		public void close() {
		}
	}

	/** A channel that never takes anything, which is a channel that cannot finish. */
	private record Deaf() implements WritableByteChannel {
		@Override
		public int write(ByteBuffer source) {
			return 0;
		}

		@Override
		public boolean isOpen() {
			return true;
		}

		@Override
		public void close() {
		}
	}

	@Test
	void aChannelThatTakesOneByteAtATimeStillGetsTheWholeThing() {
		// The failure this guards against: one write call, the rest of the buffer dropped, and the
		// truncation forced to the disk and renamed over the real file as though it were the answer.
		byte[] wanted = "{\"world.trees\": 1, \"player.craft.enchant\": 4}".getBytes(StandardCharsets.UTF_8);
		Stingy channel = new Stingy(new ByteArrayOutputStream(), 1);

		assertDoesNotThrowIo(() -> AtomicFile.writeFully(channel, ByteBuffer.wrap(wanted)));

		assertArrayEquals(wanted, channel.written().toByteArray(),
				"every byte offered has to end up in the channel, however few it takes per call");
	}

	@Test
	void anAwkwardChunkSizeIsStillWrittenWhole() {
		byte[] wanted = new byte[10_000];
		for (int i = 0; i < wanted.length; i++) {
			wanted[i] = (byte) (i % 251);
		}
		Stingy channel = new Stingy(new ByteArrayOutputStream(), 997);

		assertDoesNotThrowIo(() -> AtomicFile.writeFully(channel, ByteBuffer.wrap(wanted)));

		assertArrayEquals(wanted, channel.written().toByteArray());
	}

	@Test
	void aChannelThatTakesNothingIsAnErrorRatherThanAHang() {
		IOException thrown = assertThrows(IOException.class,
				() -> AtomicFile.writeFully(new Deaf(), ByteBuffer.wrap(new byte[] {1, 2, 3})));
		assertTrue(thrown.getMessage().contains("3"), "the message should say how much was left: " + thrown);
	}

	@Test
	void aFileComesBackExactlyAsItWentIn(@TempDir Path directory) throws IOException {
		Path file = directory.resolve("unlocks.json");
		String contents = "{\n\t\"world.trees\": 1,\n\t\"ääkkösiä\": 2\n}\n";

		AtomicFile.write(file, contents);

		assertEquals(contents, Files.readString(file, StandardCharsets.UTF_8));
	}

	@Test
	void writingAgainReplacesTheWholeFileAndLeavesNoTemporaryBehind(@TempDir Path directory) throws IOException {
		Path file = directory.resolve("currency.json");
		AtomicFile.write(file, "{\"balance\": 1234567}");

		AtomicFile.write(file, "{\"balance\": 1}");

		assertEquals("{\"balance\": 1}", Files.readString(file, StandardCharsets.UTF_8),
				"the shorter contents must replace the longer ones entirely");
		try (var entries = Files.list(directory)) {
			assertEquals(1, entries.count(), "the temporary file must not be left lying about");
		}
	}

	@Test
	void aWriteThatCannotHappenLeavesThePreviousContentsAlone(@TempDir Path directory) throws IOException {
		Path file = directory.resolve("unlocks.json");
		AtomicFile.write(file, "{\"world.trees\": 1}");

		// A non-empty directory where the temporary file has to go: the write cannot even start.
		Path temporary = directory.resolve("unlocks.json.tmp");
		Files.createDirectory(temporary);
		Files.writeString(temporary.resolve("in-the-way"), "");

		assertThrows(IOException.class, () -> AtomicFile.write(file, "{\"world.trees\": 0}"));

		assertEquals("{\"world.trees\": 1}", Files.readString(file, StandardCharsets.UTF_8),
				"a write that did not happen must not have changed anything");
	}

	private static void assertDoesNotThrowIo(IoAction action) {
		try {
			action.run();
		} catch (IOException e) {
			throw new AssertionError("The write should have finished", e);
		}
	}

	private interface IoAction {
		void run() throws IOException;
	}
}
