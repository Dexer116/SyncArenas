package dev.dexer.syncarenas.arena;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Registry of arenas plus the lookup structure the block listeners use.
 *
 * <p>The listener path must stay allocation free and branch cheap, because on a 500 player server
 * it runs on every block change on every region thread. It is a flat array of per world entries,
 * each carrying the union box of its arenas: one reference comparison per loaded world, one box
 * test, and only then the per arena tests.
 */
public final class ArenaManager {

    /** Arenas of a single loaded world, with their union box as a pre filter. */
    public static final class WorldArenas {

        private final World world;
        private final int minX;
        private final int minY;
        private final int minZ;
        private final int maxX;
        private final int maxY;
        private final int maxZ;
        private final Arena[] arenas;

        WorldArenas(final World world, final Arena[] arenas) {
            this.world = world;
            this.arenas = arenas;
            int nx = Integer.MAX_VALUE;
            int ny = Integer.MAX_VALUE;
            int nz = Integer.MAX_VALUE;
            int xx = Integer.MIN_VALUE;
            int xy = Integer.MIN_VALUE;
            int xz = Integer.MIN_VALUE;
            for (final Arena arena : arenas) {
                final ArenaBounds bounds = arena.bounds();
                nx = Math.min(nx, bounds.minX());
                ny = Math.min(ny, bounds.minY());
                nz = Math.min(nz, bounds.minZ());
                xx = Math.max(xx, bounds.maxX());
                xy = Math.max(xy, bounds.maxY());
                xz = Math.max(xz, bounds.maxZ());
            }
            this.minX = nx;
            this.minY = ny;
            this.minZ = nz;
            this.maxX = xx;
            this.maxY = xy;
            this.maxZ = xz;
        }

        public World world() {
            return this.world;
        }

        /** Marks the section of every arena in this world that contains the block. */
        public void markBlock(final int x, final int y, final int z) {
            if (x < this.minX || x > this.maxX
                || y < this.minY || y > this.maxY
                || z < this.minZ || z > this.maxZ) {
                return;
            }
            for (final Arena arena : this.arenas) {
                arena.markBlock(x, y, z);
            }
        }
    }

    private static final WorldArenas[] EMPTY_INDEX = new WorldArenas[0];

    private final Logger logger;
    private final ArenaStore store;
    private final Map<String, Arena> arenas = new ConcurrentHashMap<>();
    private volatile WorldArenas[] index = EMPTY_INDEX;

    public ArenaManager(final Logger logger, final Path dataFolder) {
        this.logger = logger;
        this.store = new ArenaStore(dataFolder);
    }

    public ArenaStore store() {
        return this.store;
    }

    /** The lookup array used by the listeners. Never null, may be empty. */
    public WorldArenas[] index() {
        return this.index;
    }

    /**
     * Marks the section containing this block in every arena of that world. This is the hot path
     * the block listeners call: one reference comparison per loaded world that has arenas, one
     * union box test, then a packed {@code long} into a concurrent set. No allocation, no blocking.
     */
    public void markBlock(final World world, final int x, final int y, final int z) {
        final WorldArenas[] current = this.index;
        for (int i = 0; i < current.length; i++) {
            final WorldArenas entry = current[i];
            if (entry.world() == world) {
                entry.markBlock(x, y, z);
                return;
            }
        }
    }

    public void markBlock(final Block block) {
        this.markBlock(block.getWorld(), block.getX(), block.getY(), block.getZ());
    }

    public void loadAll() {
        try {
            this.store.createDirectories();
        } catch (final IOException ex) {
            this.logger.log(Level.SEVERE, "Could not create the SyncArenas data directories", ex);
            return;
        }
        final List<String> failures = new ArrayList<>();
        final List<Arena> loaded;
        try {
            loaded = this.store.loadAll(failures);
        } catch (final IOException ex) {
            this.logger.log(Level.SEVERE, "Could not read the arena directory", ex);
            return;
        }
        for (final Arena arena : loaded) {
            this.arenas.put(arena.key(), arena);
        }
        for (final String failure : failures) {
            this.logger.warning("Skipped a broken arena file -> " + failure);
        }
        this.rebuildIndex();
        this.logger.info("Loaded " + this.arenas.size() + " arena(s).");
    }

    public Arena get(final String name) {
        return this.arenas.get(name.toLowerCase(Locale.ROOT));
    }

    public boolean exists(final String name) {
        return this.arenas.containsKey(name.toLowerCase(Locale.ROOT));
    }

    public Collection<Arena> all() {
        return this.arenas.values();
    }

    public int size() {
        return this.arenas.size();
    }

    public void register(final Arena arena) {
        this.arenas.put(arena.key(), arena);
        this.rebuildIndex();
    }

    public void unregister(final Arena arena) {
        this.arenas.remove(arena.key());
        this.rebuildIndex();
    }

    /** Rebuilds the per world lookup array. Cheap and only run on arena or world changes. */
    public void rebuildIndex() {
        final Map<World, List<Arena>> byWorld = new HashMap<>();
        for (final Arena arena : this.arenas.values()) {
            final World world = Bukkit.getWorld(arena.worldName());
            if (world == null) {
                continue;
            }
            byWorld.computeIfAbsent(world, ignored -> new ArrayList<>(2)).add(arena);
        }
        final WorldArenas[] built = new WorldArenas[byWorld.size()];
        int i = 0;
        for (final Map.Entry<World, List<Arena>> entry : byWorld.entrySet()) {
            built[i++] = new WorldArenas(entry.getKey(), entry.getValue().toArray(new Arena[0]));
        }
        this.index = built;
    }

    /** Persists arenas whose dirty set changed since the last save. */
    public void saveUnsaved() {
        for (final Arena arena : this.arenas.values()) {
            if (arena.dirtyStateUnsaved()) {
                this.save(arena);
            }
        }
    }

    public void saveAll() {
        for (final Arena arena : this.arenas.values()) {
            this.save(arena);
        }
    }

    public void save(final Arena arena) {
        try {
            this.store.save(arena);
        } catch (final IOException ex) {
            this.logger.log(Level.SEVERE, "Could not save arena " + arena.name(), ex);
        }
    }
}
