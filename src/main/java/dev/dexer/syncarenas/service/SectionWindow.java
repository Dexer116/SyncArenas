package dev.dexer.syncarenas.service;

import dev.dexer.syncarenas.arena.ArenaBounds;
import dev.dexer.syncarenas.format.SectionPos;
import dev.dexer.syncarenas.format.SectionSnapshot;

/**
 * The part of one decoded section that lies inside an arena, plus a resumable cursor over it.
 *
 * <p>A restore slice can stop anywhere, so the walk has to be able to pick up at an arbitrary
 * cursor. {@link #seek()} does that once per slice - the only integer division in the whole
 * restore path - and {@link #advance()} then carries the coordinates along, y major, then z, then
 * x, matching the order the section payload is stored in.
 *
 * <p>Owned by the region thread that restores the chunk, so none of this is synchronised.
 */
final class SectionWindow {

    final long key;
    final SectionSnapshot data;
    final int x0;
    final int y0;
    final int z0;
    final int sizeX;
    final int sizeY;
    final int sizeZ;

    /** Blocks already restored. */
    private int cursor;
    private int x;
    private int y;
    private int z;

    private SectionWindow(final long key, final SectionSnapshot data, final int x0, final int y0,
                          final int z0, final int sizeX, final int sizeY, final int sizeZ) {
        this.key = key;
        this.data = data;
        this.x0 = x0;
        this.y0 = y0;
        this.z0 = z0;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.seek();
    }

    /**
     * Intersects a section with the arena box.
     *
     * @return {@code null} when the section does not overlap the arena at all
     */
    static SectionWindow create(final long key, final SectionSnapshot data, final ArenaBounds bounds) {
        final int baseX = SectionPos.sectionX(key) << 4;
        final int baseY = SectionPos.sectionY(key) << 4;
        final int baseZ = SectionPos.sectionZ(key) << 4;
        final int minX = Math.max(bounds.minX(), baseX);
        final int maxX = Math.min(bounds.maxX(), baseX + 15);
        final int minY = Math.max(bounds.minY(), baseY);
        final int maxY = Math.min(bounds.maxY(), baseY + 15);
        final int minZ = Math.max(bounds.minZ(), baseZ);
        final int maxZ = Math.min(bounds.maxZ(), baseZ + 15);
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            return null;
        }
        return new SectionWindow(key, data, minX, minY, minZ,
            maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1);
    }

    int blockCount() {
        return this.sizeX * this.sizeY * this.sizeZ;
    }

    int cursor() {
        return this.cursor;
    }

    boolean done() {
        return this.cursor >= this.blockCount();
    }

    /** Recomputes the current coordinates from the cursor. Called once per restore slice. */
    void seek() {
        final int plane = this.sizeX * this.sizeZ;
        final int layer = this.cursor / plane;
        final int rest = this.cursor - layer * plane;
        final int offsetZ = rest / this.sizeX;
        this.x = this.x0 + rest - offsetZ * this.sizeX;
        this.y = this.y0 + layer;
        this.z = this.z0 + offsetZ;
    }

    int x() {
        return this.x;
    }

    int y() {
        return this.y;
    }

    int z() {
        return this.z;
    }

    /** Steps to the next block of the window. */
    void advance() {
        this.cursor++;
        if (++this.x - this.x0 == this.sizeX) {
            this.x = this.x0;
            if (++this.z - this.z0 == this.sizeZ) {
                this.z = this.z0;
                this.y++;
            }
        }
    }
}
