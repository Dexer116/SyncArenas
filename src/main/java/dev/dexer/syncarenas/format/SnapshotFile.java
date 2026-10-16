package dev.dexer.syncarenas.format;

import dev.dexer.syncarenas.arena.ArenaBounds;
import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Read side of the {@code .sa} format.
 *
 * <p>Only the header and the section index are held in memory. Sections are inflated on demand
 * through positional channel reads, which are thread safe, so several async tasks can pull
 * different sections out of the same file in parallel.
 */
public final class SnapshotFile implements Closeable {

    /** Index entry: offset, compressed length, uncompressed length. */
    private record Entry(long offset, int compressed, int uncompressed) {
    }

    private final FileChannel channel;
    private final String worldName;
    private final ArenaBounds bounds;
    private final Map<Long, Entry> index;

    private SnapshotFile(final FileChannel channel, final String worldName, final ArenaBounds bounds,
                         final Map<Long, Entry> index) {
        this.channel = channel;
        this.worldName = worldName;
        this.bounds = bounds;
        this.index = index;
    }

    public static SnapshotFile open(final Path path) throws IOException {
        final FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
        boolean ok = false;
        try {
            // Buffered: the index of a large arena is tens of thousands of entries and
            // DataInputStream would otherwise read it four bytes at a time. Read ahead past the
            // index is harmless because every section read below is positional.
            final DataInputStream in = new DataInputStream(
                new BufferedInputStream(Channels.newInputStream(channel), 64 * 1024));
            final int magic = in.readInt();
            if (magic != SnapshotWriter.MAGIC) {
                throw new IOException("not a SyncArenas snapshot (magic 0x" + Integer.toHexString(magic) + ")");
            }
            final int version = in.readInt();
            if (version != SnapshotWriter.VERSION) {
                throw new IOException("unsupported snapshot version " + version);
            }
            in.readInt();
            final String worldName = in.readUTF();
            final ArenaBounds bounds = new ArenaBounds(
                in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt());
            final int sectionCount = in.readInt();
            if (sectionCount < 0) {
                throw new IOException("illegal section count " + sectionCount);
            }
            final Map<Long, Entry> index = new HashMap<>(Math.max(16, sectionCount * 2));
            for (int i = 0; i < sectionCount; i++) {
                final long key = in.readLong();
                final long offset = in.readLong();
                final int compressed = in.readInt();
                final int uncompressed = in.readInt();
                index.put(key, new Entry(offset, compressed, uncompressed));
            }
            final SnapshotFile file = new SnapshotFile(channel, worldName, bounds,
                Collections.unmodifiableMap(index));
            ok = true;
            return file;
        } finally {
            if (!ok) {
                channel.close();
            }
        }
    }

    public String worldName() {
        return this.worldName;
    }

    public ArenaBounds bounds() {
        return this.bounds;
    }

    public int sectionCount() {
        return this.index.size();
    }

    public Set<Long> sectionKeys() {
        return this.index.keySet();
    }

    public boolean contains(final long sectionKey) {
        return this.index.containsKey(sectionKey);
    }

    /** Reads, inflates and decodes one section. Thread safe. */
    public SectionSnapshot read(final long sectionKey) throws IOException {
        return SectionCodec.decode(this.readPayload(sectionKey));
    }

    /** Reads and inflates one section payload without decoding it. Thread safe. */
    byte[] readPayload(final long sectionKey) throws IOException {
        final Entry entry = this.index.get(sectionKey);
        if (entry == null) {
            throw new IOException("section " + sectionKey + " is not part of this snapshot");
        }
        final ByteBuffer buffer = ByteBuffer.allocate(entry.compressed());
        long position = entry.offset();
        while (buffer.hasRemaining()) {
            final int read = this.channel.read(buffer, position);
            if (read < 0) {
                throw new IOException("truncated snapshot at offset " + position);
            }
            position += read;
        }
        return inflate(buffer.array(), entry.uncompressed());
    }

    private static byte[] inflate(final byte[] compressed, final int uncompressedLength) throws IOException {
        final Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            final byte[] out = new byte[uncompressedLength];
            int offset = 0;
            while (offset < uncompressedLength) {
                final int written = inflater.inflate(out, offset, uncompressedLength - offset);
                if (written == 0) {
                    if (inflater.finished() || inflater.needsInput() || inflater.needsDictionary()) {
                        throw new IOException("truncated section payload");
                    }
                } else {
                    offset += written;
                }
            }
            return out;
        } catch (final DataFormatException ex) {
            throw new IOException("corrupt section payload", ex);
        } finally {
            inflater.end();
        }
    }

    @Override
    public void close() throws IOException {
        this.channel.close();
    }
}
