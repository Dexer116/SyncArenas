package dev.dexer.syncarenas.format;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.block.data.BlockData;

/**
 * Encodes and decodes the payload of a single section inside a {@code .sa} file.
 *
 * <p>Layout (before the per-section deflate pass):
 * <pre>
 *   u16  palette size
 *   utf  palette entry 0 .. n     (BlockData#getAsString())
 *   u8   bits per entry           (0 when the section is uniform)
 *   i32  packed length in longs   (absent when bits per entry is 0)
 *   i64  packed data              (absent when bits per entry is 0)
 * </pre>
 *
 * <p>Indices are packed exactly like a vanilla paletted container: entries never straddle a
 * {@code long} boundary, which makes random access a shift and a mask.
 */
public final class SectionCodec {

    private SectionCodec() {
    }

    /**
     * @param palette distinct states, palette index order
     * @param indices {@value SectionPos#SECTION_VOLUME} palette indices, {@code y << 8 | z << 4 | x}
     */
    public static byte[] encode(final List<BlockData> palette, final int[] indices) throws IOException {
        if (palette.isEmpty() || palette.size() > 0xFFFF) {
            throw new IOException("illegal palette size " + palette.size());
        }
        final int bits = BitStorage.bitsFor(palette.size());
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream(1024);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeShort(palette.size());
            for (final BlockData data : palette) {
                out.writeUTF(data.getAsString());
            }
            out.writeByte(bits);
            if (bits > 0) {
                final long[] packed = BitStorage.pack(indices, bits);
                out.writeInt(packed.length);
                for (final long word : packed) {
                    out.writeLong(word);
                }
            }
        }
        return bytes.toByteArray();
    }

    /**
     * Decodes a payload. Called from the async scheduler; {@link Bukkit#createBlockData(String)}
     * only reads the immutable block registry, so this never touches region-owned state.
     */
    public static SectionSnapshot decode(final byte[] payload) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            final int paletteSize = in.readUnsignedShort();
            if (paletteSize == 0) {
                throw new IOException("empty palette");
            }
            final BlockData[] palette = new BlockData[paletteSize];
            final boolean[] propertyLess = new boolean[paletteSize];
            for (int i = 0; i < paletteSize; i++) {
                final String encoded = in.readUTF();
                try {
                    palette[i] = Bukkit.createBlockData(encoded);
                } catch (final IllegalArgumentException ex) {
                    throw new IOException("unknown block state '" + encoded + "'", ex);
                }
                propertyLess[i] = encoded.indexOf('[') < 0;
            }
            final int bits = in.readUnsignedByte();
            long[] packed = EMPTY;
            if (bits > 0) {
                if (bits > 16) {
                    throw new IOException("illegal bits per entry " + bits);
                }
                final int length = in.readInt();
                if (length < 0 || length > SectionPos.SECTION_VOLUME) {
                    throw new IOException("illegal packed length " + length);
                }
                packed = new long[length];
                for (int i = 0; i < length; i++) {
                    packed[i] = in.readLong();
                }
            }
            return new SectionSnapshot(palette, propertyLess, packed, bits);
        }
    }

    private static final long[] EMPTY = new long[0];
}
