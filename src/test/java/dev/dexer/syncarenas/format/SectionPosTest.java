package dev.dexer.syncarenas.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SectionPosTest {

    @Test
    void sectionKeysRoundTrip() {
        final int[] coordinates = {-1_875_000, -131_073, -4097, -17, -1, 0, 1, 17, 4097, 131_073, 1_875_000};
        final int[] sectionYs = {-512, -5, -4, -1, 0, 1, 19, 20, 511};
        for (final int x : coordinates) {
            for (final int z : coordinates) {
                for (final int y : sectionYs) {
                    final long key = SectionPos.key(x, y, z);
                    assertEquals(x, SectionPos.sectionX(key), "x");
                    assertEquals(y, SectionPos.sectionY(key), "y");
                    assertEquals(z, SectionPos.sectionZ(key), "z");
                }
            }
        }
    }

    @Test
    void sectionKeysAreDistinct() {
        final Set<Long> seen = new HashSet<>();
        for (int x = -40; x <= 40; x++) {
            for (int z = -40; z <= 40; z++) {
                for (int y = -4; y <= 20; y++) {
                    assertEquals(true, seen.add(SectionPos.key(x, y, z)),
                        "duplicate key for " + x + ", " + y + ", " + z);
                }
            }
        }
    }

    @Test
    void blockCoordinatesMapIntoTheirSection() {
        assertEquals(SectionPos.key(0, 0, 0), SectionPos.keyOfBlock(15, 15, 15));
        assertEquals(SectionPos.key(-1, -1, -1), SectionPos.keyOfBlock(-1, -1, -1));
        assertEquals(SectionPos.key(-1, -4, 2), SectionPos.keyOfBlock(-16, -64, 32));
        assertNotEquals(SectionPos.keyOfBlock(15, 15, 15), SectionPos.keyOfBlock(16, 15, 15));
    }

    @Test
    void chunkKeysMatchTheSectionColumn() {
        for (int x = -300; x <= 300; x += 7) {
            for (int z = -300; z <= 300; z += 11) {
                final long chunkKey = SectionPos.chunkKey(x, z);
                assertEquals(x, SectionPos.chunkX(chunkKey));
                assertEquals(z, SectionPos.chunkZ(chunkKey));
                assertEquals(chunkKey, SectionPos.chunkOf(SectionPos.key(x, 3, z)));
                assertEquals(chunkKey, SectionPos.chunkOf(SectionPos.key(x, -4, z)));
            }
        }
    }

    @Test
    void blockIndexIsABijection() {
        final boolean[] seen = new boolean[SectionPos.SECTION_VOLUME];
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    final int index = SectionPos.blockIndex(x, y, z);
                    assertEquals(false, seen[index], "duplicate index " + index);
                    seen[index] = true;
                }
            }
        }
    }
}
