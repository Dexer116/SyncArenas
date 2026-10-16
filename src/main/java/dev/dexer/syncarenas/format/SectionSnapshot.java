package dev.dexer.syncarenas.format;

import org.bukkit.block.data.BlockData;

/**
 * A decoded 16x16x16 section: a palette plus a bit-packed index per block.
 *
 * <p>Instances are immutable after decoding and are therefore safe to hand from the async
 * scheduler to a region thread.
 */
public final class SectionSnapshot {

    private final BlockData[] palette;
    /** For each palette entry: {@code true} when the state has no properties at all. */
    private final boolean[] propertyLess;
    private final long[] packed;
    private final int bitsPerEntry;
    private final int entriesPerLong;
    private final long mask;

    SectionSnapshot(final BlockData[] palette, final boolean[] propertyLess,
                    final long[] packed, final int bitsPerEntry) {
        this.palette = palette;
        this.propertyLess = propertyLess;
        this.packed = packed;
        this.bitsPerEntry = bitsPerEntry;
        this.entriesPerLong = bitsPerEntry == 0 ? 0 : 64 / bitsPerEntry;
        this.mask = bitsPerEntry == 0 ? 0L : (1L << bitsPerEntry) - 1L;
    }

    /** {@code true} when every block in this section is the same state. */
    public boolean uniform() {
        return this.bitsPerEntry == 0;
    }

    public int paletteSize() {
        return this.palette.length;
    }

    /** Palette index of the block at the given section-local index. */
    public int paletteIndex(final int blockIndex) {
        if (this.bitsPerEntry == 0) {
            return 0;
        }
        final int longIndex = blockIndex / this.entriesPerLong;
        final int shift = (blockIndex % this.entriesPerLong) * this.bitsPerEntry;
        return (int) ((this.packed[longIndex] >>> shift) & this.mask);
    }

    public BlockData state(final int paletteIndex) {
        return this.palette[paletteIndex];
    }

    /**
     * Whether the palette entry carries no block properties. Such a state is fully identified by
     * its {@link org.bukkit.Material}, which lets the restore loop compare it against the world
     * with {@code Block#getType()} alone - no {@code BlockData} wrapper is allocated.
     */
    public boolean propertyLess(final int paletteIndex) {
        return this.propertyLess[paletteIndex];
    }
}
