package dev.dexer.syncarenas.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class BitStorageTest {

    @Test
    void bitsForCoversThePalette() {
        assertEquals(0, BitStorage.bitsFor(0));
        assertEquals(0, BitStorage.bitsFor(1));
        assertEquals(1, BitStorage.bitsFor(2));
        assertEquals(2, BitStorage.bitsFor(3));
        assertEquals(2, BitStorage.bitsFor(4));
        assertEquals(3, BitStorage.bitsFor(5));
        assertEquals(8, BitStorage.bitsFor(256));
        assertEquals(9, BitStorage.bitsFor(257));
        for (int size = 1; size <= 4096; size++) {
            final int bits = BitStorage.bitsFor(size);
            assertTrue(size <= 1 << bits, "palette of " + size + " does not fit in " + bits + " bits");
        }
    }

    @Test
    void packAndReadRoundTripForEveryWidth() {
        final Random random = new Random(20261016L);
        for (int bits = 1; bits <= 16; bits++) {
            final int[] indices = new int[SectionPos.SECTION_VOLUME];
            final int bound = 1 << bits;
            for (int i = 0; i < indices.length; i++) {
                indices[i] = random.nextInt(bound);
            }
            final long[] packed = BitStorage.pack(indices, bits);
            assertEquals(BitStorage.packedLength(indices.length, bits), packed.length);
            for (int i = 0; i < indices.length; i++) {
                assertEquals(indices[i], BitStorage.get(packed, bits, i),
                    "mismatch at " + i + " with " + bits + " bits");
            }
        }
    }

    @Test
    void uniformSectionsStoreNothing() {
        assertEquals(0, BitStorage.pack(new int[SectionPos.SECTION_VOLUME], 0).length);
        assertEquals(0, BitStorage.packedLength(SectionPos.SECTION_VOLUME, 0));
        assertEquals(0, BitStorage.get(new long[0], 0, 123));
    }

    @Test
    void sectionSnapshotReadsTheSameLayout() {
        final Random random = new Random(7L);
        for (int bits = 1; bits <= 12; bits++) {
            final int[] indices = new int[SectionPos.SECTION_VOLUME];
            for (int i = 0; i < indices.length; i++) {
                indices[i] = random.nextInt(1 << bits);
            }
            final long[] packed = BitStorage.pack(indices, bits);
            final SectionSnapshot snapshot = new SectionSnapshot(
                new org.bukkit.block.data.BlockData[1 << bits], new boolean[1 << bits], packed, bits);
            for (int i = 0; i < indices.length; i++) {
                assertEquals(indices[i], snapshot.paletteIndex(i));
            }
        }
    }

    @Test
    void uniformSnapshotAlwaysReadsZero() {
        final SectionSnapshot snapshot = new SectionSnapshot(
            new org.bukkit.block.data.BlockData[1], new boolean[1], new long[0], 0);
        assertTrue(snapshot.uniform());
        assertEquals(0, snapshot.paletteIndex(0));
        assertEquals(0, snapshot.paletteIndex(SectionPos.SECTION_VOLUME - 1));
    }
}
