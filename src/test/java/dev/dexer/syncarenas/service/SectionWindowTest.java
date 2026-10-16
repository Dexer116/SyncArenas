package dev.dexer.syncarenas.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.dexer.syncarenas.arena.ArenaBounds;
import dev.dexer.syncarenas.format.SectionPos;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SectionWindowTest {

    /** The order the section payload is stored in: y major, then z, then x. */
    private static List<int[]> expectedOrder(final ArenaBounds bounds, final long key) {
        final int baseX = SectionPos.sectionX(key) << 4;
        final int baseY = SectionPos.sectionY(key) << 4;
        final int baseZ = SectionPos.sectionZ(key) << 4;
        final List<int[]> expected = new ArrayList<>();
        for (int y = Math.max(bounds.minY(), baseY); y <= Math.min(bounds.maxY(), baseY + 15); y++) {
            for (int z = Math.max(bounds.minZ(), baseZ); z <= Math.min(bounds.maxZ(), baseZ + 15); z++) {
                for (int x = Math.max(bounds.minX(), baseX); x <= Math.min(bounds.maxX(), baseX + 15); x++) {
                    expected.add(new int[]{x, y, z});
                }
            }
        }
        return expected;
    }

    private static void assertWalkMatches(final ArenaBounds bounds, final long key, final int[] sliceSizes) {
        final SectionWindow window = SectionWindow.create(key, null, bounds);
        assertNotNull(window);
        final List<int[]> expected = expectedOrder(bounds, key);
        assertEquals(expected.size(), window.blockCount(), "window size");

        final List<int[]> visited = new ArrayList<>();
        int slice = 0;
        while (!window.done()) {
            // Every slice re-seeks, exactly like a restore resuming on the next tick.
            window.seek();
            final int budget = sliceSizes[slice++ % sliceSizes.length];
            for (int i = 0; i < budget && !window.done(); i++) {
                visited.add(new int[]{window.x(), window.y(), window.z()});
                window.advance();
            }
        }

        assertEquals(expected.size(), visited.size(), "visited block count");
        for (int i = 0; i < expected.size(); i++) {
            final int[] want = expected.get(i);
            final int[] got = visited.get(i);
            assertEquals(want[0], got[0], "x at " + i);
            assertEquals(want[1], got[1], "y at " + i);
            assertEquals(want[2], got[2], "z at " + i);
            assertTrue(bounds.contains(got[0], got[1], got[2]), "visited a block outside the arena");
            assertEquals(key, SectionPos.keyOfBlock(got[0], got[1], got[2]), "visited another section");
        }
    }

    @Test
    void fullSectionsAreWalkedInPayloadOrder() {
        final ArenaBounds bounds = ArenaBounds.of(-64, 48, -64, 63, 111, 63);
        for (final long key : bounds.sectionKeys()) {
            assertWalkMatches(bounds, key, new int[]{4096});
        }
    }

    @Test
    void partialSectionsAreClampedToTheArena() {
        // Edges of the box cut sections on every axis, including negative coordinates.
        final ArenaBounds bounds = ArenaBounds.of(-21, -5, 13, 34, 39, 46);
        for (final long key : bounds.sectionKeys()) {
            assertWalkMatches(bounds, key, new int[]{4096});
        }
    }

    @Test
    void resumingMidSectionVisitsEveryBlockExactlyOnce() {
        final ArenaBounds bounds = ArenaBounds.of(-21, -5, 13, 34, 39, 46);
        final Random random = new Random(99L);
        final long[] keys = bounds.sectionKeys();
        for (int i = 0; i < keys.length; i++) {
            final int[] slices = new int[8];
            for (int s = 0; s < slices.length; s++) {
                slices[s] = 1 + random.nextInt(37);
            }
            assertWalkMatches(bounds, keys[i], slices);
        }
    }

    @Test
    void singleBlockArenasWork() {
        final ArenaBounds bounds = ArenaBounds.of(7, 70, -3, 7, 70, -3);
        final long[] keys = bounds.sectionKeys();
        assertEquals(1, keys.length);
        final SectionWindow window = SectionWindow.create(keys[0], null, bounds);
        assertNotNull(window);
        assertEquals(1, window.blockCount());
        assertEquals(7, window.x());
        assertEquals(70, window.y());
        assertEquals(-3, window.z());
        window.advance();
        assertTrue(window.done());
        assertWalkMatches(bounds, keys[0], new int[]{1});
    }

    @Test
    void sectionsOutsideTheArenaAreSkipped() {
        final ArenaBounds bounds = ArenaBounds.of(0, 0, 0, 15, 15, 15);
        assertNull(SectionWindow.create(SectionPos.key(5, 0, 5), null, bounds));
        assertNull(SectionWindow.create(SectionPos.key(0, 3, 0), null, bounds));
        assertNotNull(SectionWindow.create(SectionPos.key(0, 0, 0), null, bounds));
    }

    @Test
    void cursorReflectsProgress() {
        final ArenaBounds bounds = ArenaBounds.of(0, 0, 0, 15, 15, 15);
        final SectionWindow window = SectionWindow.create(SectionPos.key(0, 0, 0), null, bounds);
        assertNotNull(window);
        assertEquals(0, window.cursor());
        for (int i = 0; i < 300; i++) {
            window.advance();
        }
        assertEquals(300, window.cursor());
        window.seek();
        // 300 = 1 * 256 + 2 * 16 + 12
        assertEquals(12, window.x());
        assertEquals(1, window.y());
        assertEquals(2, window.z());
    }
}
