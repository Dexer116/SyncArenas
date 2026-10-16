package dev.dexer.syncarenas.util;

import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * Region thread aware chunk access.
 *
 * <p>Every entry point hops onto a region task for the target chunk first, so the chunk system is
 * only ever queried from a thread that owns the chunk. That holds on Folia and CanvasMC as well as
 * on plain Paper, where these schedulers run on the server thread.
 */
public final class Chunks {

    private Chunks() {
    }

    /**
     * Runs {@code work} on the thread that owns the chunk, loading the chunk first if needed.
     * May be called from any thread.
     *
     * @param onFailure run when the chunk could not be made available at all
     */
    public static void withChunk(final Plugin plugin, final World world, final int chunkX, final int chunkZ,
                                 final Consumer<Chunk> work, final Runnable onFailure) {
        Bukkit.getRegionScheduler().execute(plugin, world, chunkX, chunkZ,
            () -> resolve(plugin, world, chunkX, chunkZ, work, onFailure));
    }

    /** Like {@link #withChunk} but only after {@code delayTicks} ticks of that region. */
    public static void withChunkLater(final Plugin plugin, final World world, final int chunkX, final int chunkZ,
                                      final Consumer<Chunk> work, final Runnable onFailure,
                                      final long delayTicks) {
        Bukkit.getRegionScheduler().runDelayed(plugin, world, chunkX, chunkZ,
            task -> resolve(plugin, world, chunkX, chunkZ, work, onFailure), Math.max(1L, delayTicks));
    }

    /** Runs on the owning region thread; the chunk is loaded if it is not already. */
    private static void resolve(final Plugin plugin, final World world, final int chunkX, final int chunkZ,
                                final Consumer<Chunk> work, final Runnable onFailure) {
        if (world.isChunkLoaded(chunkX, chunkZ)) {
            work.accept(world.getChunkAt(chunkX, chunkZ));
            return;
        }
        world.getChunkAtAsync(chunkX, chunkZ, true, false, loaded -> {
            if (loaded == null) {
                onFailure.run();
                return;
            }
            // The load callback already runs on the owning thread, but hop once more so this is
            // correct no matter which thread the implementation picks.
            Bukkit.getRegionScheduler().execute(plugin, world, chunkX, chunkZ,
                () -> work.accept(world.getChunkAt(chunkX, chunkZ)));
        });
    }
}
