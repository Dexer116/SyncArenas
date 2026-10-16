package dev.dexer.syncarenas.format;

import dev.dexer.syncarenas.arena.ArenaBounds;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;

/**
 * Writes the custom {@code .sa} arena snapshot format.
 *
 * <pre>
 *   i32  magic "SYNA"
 *   i32  format version
 *   i32  flags (reserved)
 *   utf  world name
 *   i32  minX, minY, minZ, maxX, maxY, maxZ
 *   i32  section count
 *   ---- index, one 24 byte entry per section, sorted by key ----
 *   i64  section key
 *   i64  absolute file offset of the payload
 *   i32  compressed length
 *   i32  uncompressed length
 *   ---- payloads, individually deflated ----
 * </pre>
 *
 * <p>Every section is compressed on its own and reachable through the index, so a reset can read
 * and inflate <em>only</em> the sections it is about to restore instead of paging in the whole
 * arena.
 *
 * <p>Section payloads may be handed in from several async tasks at once; the actual file writes
 * are serialised on this object.
 */
public final class SnapshotWriter implements Closeable {

    static final int MAGIC = 0x53594E41;
    static final int VERSION = 1;
    static final int INDEX_ENTRY_BYTES = 24;

    private final RandomAccessFile file;
    private final Map<Long, long[]> entries = new HashMap<>();
    private final int sectionCount;
    private final long indexOffset;
    private long dataEnd;
    private boolean finished;

    public SnapshotWriter(final Path path, final String worldName, final ArenaBounds bounds,
                          final int sectionCount) throws IOException {
        this.sectionCount = sectionCount;
        this.file = new RandomAccessFile(path.toFile(), "rw");
        boolean ok = false;
        try {
            this.file.setLength(0L);
            this.file.writeInt(MAGIC);
            this.file.writeInt(VERSION);
            this.file.writeInt(0);
            this.file.writeUTF(worldName);
            this.file.writeInt(bounds.minX());
            this.file.writeInt(bounds.minY());
            this.file.writeInt(bounds.minZ());
            this.file.writeInt(bounds.maxX());
            this.file.writeInt(bounds.maxY());
            this.file.writeInt(bounds.maxZ());
            this.file.writeInt(sectionCount);
            this.indexOffset = this.file.getFilePointer();
            this.dataEnd = this.indexOffset + (long) sectionCount * INDEX_ENTRY_BYTES;
            this.file.setLength(this.dataEnd);
            ok = true;
        } finally {
            if (!ok) {
                this.file.close();
            }
        }
    }

    /** Compresses a payload. Safe to call from any thread; does no I/O. */
    public static byte[] compress(final byte[] payload, final int level) {
        final Deflater deflater = new Deflater(level);
        try {
            deflater.setInput(payload);
            deflater.finish();
            final byte[] buffer = new byte[Math.max(64, payload.length)];
            final List<byte[]> chunks = new ArrayList<>(1);
            int total = 0;
            while (!deflater.finished()) {
                final int written = deflater.deflate(buffer, 0, buffer.length);
                if (written == 0) {
                    break;
                }
                final byte[] copy = new byte[written];
                System.arraycopy(buffer, 0, copy, 0, written);
                chunks.add(copy);
                total += written;
            }
            if (chunks.size() == 1) {
                return chunks.get(0);
            }
            final byte[] result = new byte[total];
            int offset = 0;
            for (final byte[] chunk : chunks) {
                System.arraycopy(chunk, 0, result, offset, chunk.length);
                offset += chunk.length;
            }
            return result;
        } finally {
            deflater.end();
        }
    }

    /** Appends one already compressed section payload. */
    public synchronized void writeSection(final long key, final byte[] compressed,
                                          final int uncompressedLength) throws IOException {
        if (this.finished) {
            throw new IOException("writer already finished");
        }
        if (this.entries.containsKey(key)) {
            throw new IOException("duplicate section " + key);
        }
        this.file.seek(this.dataEnd);
        this.file.write(compressed);
        this.entries.put(key, new long[]{this.dataEnd, compressed.length, uncompressedLength});
        this.dataEnd += compressed.length;
    }

    /** Writes the index and flushes. */
    public synchronized void finish() throws IOException {
        if (this.finished) {
            return;
        }
        if (this.entries.size() != this.sectionCount) {
            throw new IOException("expected " + this.sectionCount + " sections but wrote "
                + this.entries.size());
        }
        final long[] keys = new long[this.entries.size()];
        int i = 0;
        for (final Long key : this.entries.keySet()) {
            keys[i++] = key;
        }
        Arrays.sort(keys);
        // Built in memory and written in one go: an arena can have tens of thousands of sections,
        // and RandomAccessFile would otherwise issue a syscall per field.
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream(keys.length * INDEX_ENTRY_BYTES);
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            for (final long key : keys) {
                final long[] entry = this.entries.get(key);
                out.writeLong(key);
                out.writeLong(entry[0]);
                out.writeInt((int) entry[1]);
                out.writeInt((int) entry[2]);
            }
        }
        this.file.seek(this.indexOffset);
        this.file.write(buffer.toByteArray());
        this.file.getChannel().force(false);
        this.finished = true;
    }

    public synchronized long byteSize() throws IOException {
        return this.file.length();
    }

    @Override
    public synchronized void close() throws IOException {
        this.file.close();
    }
}
