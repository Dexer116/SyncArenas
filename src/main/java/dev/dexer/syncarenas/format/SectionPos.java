package dev.dexer.syncarenas.format;

/**
 * Packing helpers for 16x16x16 section coordinates and chunk coordinates.
 *
 * <p>A section key is a single {@code long} so that the hot path (marking a section dirty from a
 * block event) never allocates: {@code 22 bits section x | 22 bits section z | 20 bits section y}.
 */
public final class SectionPos {

    /** Blocks per section edge. */
    public static final int SECTION_SIZE = 16;
    /** Blocks in a full section. */
    public static final int SECTION_VOLUME = SECTION_SIZE * SECTION_SIZE * SECTION_SIZE;

    private static final int SY_OFFSET = 1 << 19;
    private static final long XZ_MASK = 0x3FFFFFL;
    private static final long Y_MASK = 0xFFFFFL;

    private SectionPos() {
    }

    /** Packs section coordinates (block coordinates shifted right by 4) into a key. */
    public static long key(final int sectionX, final int sectionY, final int sectionZ) {
        return ((sectionX & XZ_MASK) << 42)
            | ((sectionZ & XZ_MASK) << 20)
            | ((sectionY + SY_OFFSET) & Y_MASK);
    }

    /** Packs the section key that contains the given block position. */
    public static long keyOfBlock(final int blockX, final int blockY, final int blockZ) {
        return key(blockX >> 4, blockY >> 4, blockZ >> 4);
    }

    public static int sectionX(final long key) {
        return signExtend22((int) ((key >>> 42) & XZ_MASK));
    }

    public static int sectionZ(final long key) {
        return signExtend22((int) ((key >>> 20) & XZ_MASK));
    }

    public static int sectionY(final long key) {
        return (int) (key & Y_MASK) - SY_OFFSET;
    }

    /** Chunk key of the chunk column a section key belongs to. */
    public static long chunkOf(final long key) {
        return chunkKey(sectionX(key), sectionZ(key));
    }

    /** Packs chunk coordinates into a key (same layout as {@code Chunk#getChunkKey()}). */
    public static long chunkKey(final int chunkX, final int chunkZ) {
        return (chunkX & 0xFFFFFFFFL) | ((chunkZ & 0xFFFFFFFFL) << 32);
    }

    public static int chunkX(final long chunkKey) {
        return (int) chunkKey;
    }

    public static int chunkZ(final long chunkKey) {
        return (int) (chunkKey >> 32);
    }

    /** Index of a block inside a section payload: {@code y << 8 | z << 4 | x}, all local. */
    public static int blockIndex(final int localX, final int localY, final int localZ) {
        return (localY << 8) | (localZ << 4) | localX;
    }

    private static int signExtend22(final int value) {
        return (value << 10) >> 10;
    }
}
