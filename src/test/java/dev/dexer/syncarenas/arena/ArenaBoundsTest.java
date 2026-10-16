package dev.dexer.syncarenas.arena;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.dexer.syncarenas.format.SectionPos;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ArenaBoundsTest {

    @Test
    void cornersAreNormalised() {
        final ArenaBounds bounds = ArenaBounds.of(10, 80, -5, -10, 60, 5);
        assertEquals(-10, bounds.minX());
        assertEquals(60, bounds.minY());
        assertEquals(-5, bounds.minZ());
        assertEquals(10, bounds.maxX());
        assertEquals(80, bounds.maxY());
        assertEquals(5, bounds.maxZ());
        assertThrows(IllegalArgumentException.class, () -> new ArenaBounds(1, 0, 0, 0, 0, 0));
    }

    @Test
    void sizesAreInclusive() {
        final ArenaBounds bounds = ArenaBounds.of(0, 0, 0, 15, 15, 15);
        assertEquals(16, bounds.sizeX());
        assertEquals(16, bounds.sizeY());
        assertEquals(16, bounds.sizeZ());
        assertEquals(4096L, bounds.blockCount());
        assertEquals(1, bounds.chunkCount());
        assertEquals(1, bounds.sectionCount());
    }

    @Test
    void containsIsInclusiveOnEveryFace() {
        final ArenaBounds bounds = ArenaBounds.of(-4, 60, -4, 4, 70, 4);
        assertTrue(bounds.contains(-4, 60, -4));
        assertTrue(bounds.contains(4, 70, 4));
        assertTrue(bounds.contains(0, 65, 0));
        assertFalse(bounds.contains(-5, 65, 0));
        assertFalse(bounds.contains(0, 59, 0));
        assertFalse(bounds.contains(0, 65, 5));
    }

    @Test
    void sectionAndChunkCountsMatchTheKeys() {
        final ArenaBounds bounds = ArenaBounds.of(-33, -7, 17, 48, 71, 90);
        // -33 >> 4 == -3 and 48 >> 4 == 3, so the box spans 7 chunks on x and 5 on z.
        assertEquals(-3, bounds.minChunkX());
        assertEquals(3, bounds.maxChunkX());
        assertEquals(7, bounds.maxChunkX() - bounds.minChunkX() + 1);
        assertEquals(1, bounds.minChunkZ());
        assertEquals(5, bounds.maxChunkZ());
        assertEquals(5, bounds.maxChunkZ() - bounds.minChunkZ() + 1);
        assertEquals(-1, bounds.minSectionY());
        assertEquals(4, bounds.maxSectionY());
        assertEquals(6, bounds.sectionsPerChunk());
        assertEquals(35, bounds.chunkCount());
        assertEquals(210, bounds.sectionCount());

        final long[] sections = bounds.sectionKeys();
        assertEquals(bounds.sectionCount(), sections.length);
        final Set<Long> unique = new HashSet<>();
        for (final long key : sections) {
            assertTrue(unique.add(key), "duplicate section key");
            assertTrue(SectionPos.sectionX(key) >= bounds.minChunkX());
            assertTrue(SectionPos.sectionX(key) <= bounds.maxChunkX());
            assertTrue(SectionPos.sectionY(key) >= bounds.minSectionY());
            assertTrue(SectionPos.sectionY(key) <= bounds.maxSectionY());
            assertTrue(SectionPos.sectionZ(key) >= bounds.minChunkZ());
            assertTrue(SectionPos.sectionZ(key) <= bounds.maxChunkZ());
        }

        final long[] chunks = bounds.chunkKeys();
        assertEquals(bounds.chunkCount(), chunks.length);
        final Set<Long> uniqueChunks = new HashSet<>();
        for (final long key : chunks) {
            assertTrue(uniqueChunks.add(key), "duplicate chunk key");
        }
    }

    @Test
    void everyBlockOfTheBoxFallsInAStoredSection() {
        final ArenaBounds bounds = ArenaBounds.of(-20, -3, 7, 35, 44, 28);
        final Set<Long> stored = new HashSet<>();
        for (final long key : bounds.sectionKeys()) {
            stored.add(key);
        }
        for (int x = bounds.minX(); x <= bounds.maxX(); x += 3) {
            for (int y = bounds.minY(); y <= bounds.maxY(); y += 3) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z += 3) {
                    assertTrue(stored.contains(SectionPos.keyOfBlock(x, y, z)),
                        "no section stored for " + x + ", " + y + ", " + z);
                }
            }
        }
    }

    @Test
    void clampYStaysInsideTheWorld() {
        final ArenaBounds bounds = ArenaBounds.of(0, -120, 0, 10, 400, 10);
        final ArenaBounds clamped = bounds.clampY(-64, 319);
        assertEquals(-64, clamped.minY());
        assertEquals(319, clamped.maxY());
        assertEquals(0, clamped.minX());
        assertEquals(10, clamped.maxZ());

        final ArenaBounds inside = ArenaBounds.of(0, 0, 0, 1, 1, 1);
        assertSame(inside, inside.clampY(-64, 319));
    }
}
