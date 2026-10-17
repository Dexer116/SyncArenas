package dev.dexer.syncarenas.service;

import dev.dexer.syncarenas.SyncArenas;
import dev.dexer.syncarenas.arena.Arena;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.World;

/**
 * Automatic resets for arenas with an interval set. One async task wakes once a second and does a
 * few field reads per arena; the actual reset goes through {@link RestoreService} like a manual one,
 * so all the region thread budgeting still applies. Arenas with nothing dirty are skipped.
 */
public final class IntervalService {

    private final SyncArenas plugin;
    private ScheduledTask task;

    public IntervalService(final SyncArenas plugin) {
        this.plugin = plugin;
    }

    public void start() {
        this.task = Bukkit.getAsyncScheduler().runAtFixedRate(this.plugin, t -> this.tick(),
            1L, 1L, TimeUnit.SECONDS);
    }

    public void stop() {
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
        }
    }

    private void tick() {
        final long now = System.nanoTime();
        for (final Arena arena : this.plugin.arenas().all()) {
            final int seconds = arena.intervalSeconds();
            if (seconds <= 0 || now - arena.nextReset() < 0L) {
                continue;
            }
            if (arena.state() != Arena.State.IDLE) {
                continue; // busy, retry next second
            }
            arena.scheduleNext(now);
            if (arena.dirtyCount() == 0) {
                continue;
            }
            final World world = Bukkit.getWorld(arena.worldName());
            if (world == null) {
                continue;
            }
            this.plugin.restores().reset(arena, world, false, result -> {
                if (!result.success()) {
                    this.plugin.getLogger().log(Level.WARNING, "Interval reset of " + arena.name()
                        + " failed: " + result.error());
                }
            });
        }
    }
}
