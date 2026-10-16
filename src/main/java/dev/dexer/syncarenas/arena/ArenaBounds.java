package dev.dexer.syncarenas.arena;

import dev.dexer.syncarenas.format.SectionPos;

/** An inclusive axis aligned block box. */
public record ArenaBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public ArenaBounds {
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("bounds are not normalised");
        }
    }

    /** Builds normalised bounds from two arbitrary corners. */
    public static ArenaBounds of(final int x1, final int y1, final int z1,
                                 final int x2, final int y2, final int z2) {
        return new ArenaBounds(
            Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
            Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
    }

    public boolean contains(final int x, final int y, final int z) {
        return x >= this.minX && x <= this.maxX
            && y >= this.minY && y <= this.maxY
            && z >= this.minZ && z <= this.maxZ;
    }

    public int sizeX() {
        return this.maxX - this.minX + 1;
    }

    public int sizeY() {
        return this.maxY - this.minY + 1;
    }

    public int sizeZ() {
        return this.maxZ - this.minZ + 1;
    }

    public long blockCount() {
        return (long) this.sizeX() * this.sizeY() * this.sizeZ();
    }

    public int minChunkX() {
        return this.minX >> 4;
    }

    public int maxChunkX() {
        return this.maxX >> 4;
    }

    public int minChunkZ() {
        return this.minZ >> 4;
    }

    public int maxChunkZ() {
        return this.maxZ >> 4;
    }

    public int minSectionY() {
        return this.minY >> 4;
    }

    public int maxSectionY() {
        return this.maxY >> 4;
    }

    public int chunkCount() {
        return (this.maxChunkX() - this.minChunkX() + 1) * (this.maxChunkZ() - this.minChunkZ() + 1);
    }

    public int sectionsPerChunk() {
        return this.maxSectionY() - this.minSectionY() + 1;
    }

    /** Number of 16x16x16 sections this box touches. */
    public int sectionCount() {
        return this.chunkCount() * this.sectionsPerChunk();
    }

    /** Every section key this box touches, ordered chunk by chunk. */
    public long[] sectionKeys() {
        final long[] keys = new long[this.sectionCount()];
        int i = 0;
        for (int cx = this.minChunkX(); cx <= this.maxChunkX(); cx++) {
            for (int cz = this.minChunkZ(); cz <= this.maxChunkZ(); cz++) {
                for (int sy = this.minSectionY(); sy <= this.maxSectionY(); sy++) {
                    keys[i++] = SectionPos.key(cx, sy, cz);
                }
            }
        }
        return keys;
    }

    /** Every chunk key this box touches. */
    public long[] chunkKeys() {
        final long[] keys = new long[this.chunkCount()];
        int i = 0;
        for (int cx = this.minChunkX(); cx <= this.maxChunkX(); cx++) {
            for (int cz = this.minChunkZ(); cz <= this.maxChunkZ(); cz++) {
                keys[i++] = SectionPos.chunkKey(cx, cz);
            }
        }
        return keys;
    }

    /** Clamps the vertical extent into the given world height range. */
    public ArenaBounds clampY(final int worldMinY, final int worldMaxY) {
        final int lo = Math.max(this.minY, worldMinY);
        final int hi = Math.min(this.maxY, worldMaxY);
        if (lo == this.minY && hi == this.maxY) {
            return this;
        }
        return new ArenaBounds(this.minX, lo, this.minZ, this.maxX, hi, this.maxZ);
    }

    @Override
    public String toString() {
        return "(" + this.minX + ',' + this.minY + ',' + this.minZ + ") -> ("
            + this.maxX + ',' + this.maxY + ',' + this.maxZ + ')';
    }
}
