package dev.dexer.syncarenas.format;

/**
 * Palette index packing, laid out like a vanilla paletted container: a fixed number of entries per
 * {@code long} and never an entry straddling a word boundary, so reading one is a shift and a mask.
 */
public final class BitStorage {

    private BitStorage() {
    }

    /** Bits needed to address {@code paletteSize} entries; 0 when a single entry covers everything. */
    public static int bitsFor(final int paletteSize) {
        if (paletteSize <= 1) {
            return 0;
        }
        return 32 - Integer.numberOfLeadingZeros(paletteSize - 1);
    }

    /** Number of longs needed for {@code count} entries of {@code bits} each. */
    public static int packedLength(final int count, final int bits) {
        if (bits == 0) {
            return 0;
        }
        final int entriesPerLong = 64 / bits;
        return (count + entriesPerLong - 1) / entriesPerLong;
    }

    public static long[] pack(final int[] indices, final int bits) {
        if (bits == 0) {
            return new long[0];
        }
        final int entriesPerLong = 64 / bits;
        final long[] packed = new long[packedLength(indices.length, bits)];
        final long mask = (1L << bits) - 1L;
        for (int i = 0; i < indices.length; i++) {
            packed[i / entriesPerLong] |= (indices[i] & mask) << (i % entriesPerLong) * bits;
        }
        return packed;
    }

    public static int get(final long[] packed, final int bits, final int index) {
        if (bits == 0) {
            return 0;
        }
        final int entriesPerLong = 64 / bits;
        final long mask = (1L << bits) - 1L;
        return (int) ((packed[index / entriesPerLong] >>> (index % entriesPerLong) * bits) & mask);
    }
}
