package dev.dexer.syncarenas.listener;

import dev.dexer.syncarenas.SyncArenas;
import dev.dexer.syncarenas.arena.ArenaManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFromToEvent;

/**
 * Marks sections that water or lava flowed into.
 *
 * <p>Fluid flow is one of the most frequent block changes in the game, which is why it lives in its
 * own listener: with {@code tracking.track-fluid-flow} off, this is never registered, so the plugin
 * costs nothing on the event instead of returning early from a handler.
 */
public final class FluidFlowTrackingListener implements Listener {

    private final ArenaManager arenas;

    public FluidFlowTrackingListener(final SyncArenas plugin) {
        this.arenas = plugin.arenas();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFromTo(final BlockFromToEvent event) {
        this.arenas.markBlock(event.getToBlock());
    }
}
