package dev.dexer.syncarenas.format;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.dexer.syncarenas.arena.ArenaBounds;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SnapshotFileTest {

    @TempDir
    Path directory;

    @Test
    void sectionsSurviveARoundTrip() throws IOException {
        final ArenaBounds bounds = ArenaBounds.of(-20, 55, 12, 37, 92, 61);
        final long[] keys = bounds.sectionKeys();
        final Path path = this.directory.resolve("round-trip.sa");
        final Random random = new Random(42L);
        final Map<Long, byte[]> payloads = new HashMap<>();

        try (SnapshotWriter writer = new SnapshotWriter(path, "arena_world", bounds, keys.length)) {
            for (final long key : keys) {
                final byte[] payload = new byte[1 + random.nextInt(4096)];
                random.nextBytes(payload);
                payloads.put(key, payload);
                writer.writeSection(key, SnapshotWriter.compress(payload, 4), payload.length);
            }
            writer.finish();
            assertTrue(writer.byteSize() > 0L);
        }

        try (SnapshotFile file = SnapshotFile.open(path)) {
            assertEquals("arena_world", file.worldName());
            assertEquals(bounds, file.bounds());
            assertEquals(keys.length, file.sectionCount());
            for (final long key : keys) {
                assertTrue(file.contains(key));
                assertArrayEquals(payloads.get(key), file.readPayload(key), "payload of section " + key);
            }
            assertFalse(file.contains(SectionPos.key(9999, 0, 9999)));
            assertThrows(IOException.class, () -> file.readPayload(SectionPos.key(9999, 0, 9999)));
        }
    }

    @Test
    void highlyCompressiblePayloadsStaySmall() throws IOException {
        // A uniform section is what most of a big arena looks like, so it has to be cheap.
        final byte[] payload = new byte[32 * 1024];
        final byte[] compressed = SnapshotWriter.compress(payload, 4);
        assertTrue(compressed.length < 256, "32 KiB of zeroes compressed to " + compressed.length + " bytes");
        final Path path = this.directory.resolve("uniform.sa");
        final ArenaBounds bounds = ArenaBounds.of(0, 0, 0, 15, 15, 15);
        try (SnapshotWriter writer = new SnapshotWriter(path, "w", bounds, 1)) {
            writer.writeSection(SectionPos.key(0, 0, 0), compressed, payload.length);
            writer.finish();
        }
        try (SnapshotFile file = SnapshotFile.open(path)) {
            assertArrayEquals(payload, file.readPayload(SectionPos.key(0, 0, 0)));
        }
    }

    @Test
    void anIncompleteSnapshotIsRefused() throws IOException {
        final Path path = this.directory.resolve("incomplete.sa");
        final ArenaBounds bounds = ArenaBounds.of(0, 0, 0, 31, 15, 15);
        try (SnapshotWriter writer = new SnapshotWriter(path, "w", bounds, 2)) {
            writer.writeSection(SectionPos.key(0, 0, 0), SnapshotWriter.compress(new byte[8], 1), 8);
            assertThrows(IOException.class, writer::finish);
        }
    }

    @Test
    void duplicateSectionsAreRefused() throws IOException {
        final Path path = this.directory.resolve("duplicate.sa");
        final ArenaBounds bounds = ArenaBounds.of(0, 0, 0, 15, 15, 15);
        try (SnapshotWriter writer = new SnapshotWriter(path, "w", bounds, 1)) {
            writer.writeSection(SectionPos.key(0, 0, 0), SnapshotWriter.compress(new byte[8], 1), 8);
            assertThrows(IOException.class, () -> writer.writeSection(
                SectionPos.key(0, 0, 0), SnapshotWriter.compress(new byte[8], 1), 8));
        }
    }

    @Test
    void foreignFilesAreRefused() throws IOException {
        final Path path = this.directory.resolve("not-a-snapshot.sa");
        Files.write(path, new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12});
        final IOException error = assertThrows(IOException.class, () -> SnapshotFile.open(path));
        assertTrue(error.getMessage().contains("not a SyncArenas snapshot"), error.getMessage());
    }
}
