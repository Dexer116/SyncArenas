package dev.dexer.syncarenas.listener;

import dev.dexer.syncarenas.SyncArenas;
import dev.dexer.syncarenas.arena.ArenaManager;
import java.util.List;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPistonEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;

/**
 * Marks sections that a piston moved blocks in or out of: the piston itself, every block it moves,
 * and the position each of those blocks ends up in.
 *
 * <p>Separate listener so that {@code tracking.track-pistons: false} registers no handler at all.
 */
public final class PistonTrackingListener implements Listener {

    private final ArenaManager arenas;

    public PistonTrackingListener(final SyncArenas plugin) {
        this.arenas = plugin.arenas();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExtend(final BlockPistonExtendEvent event) {
        this.mark(event, event.getBlocks());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRetract(final BlockPistonRetractEvent event) {
        this.mark(event, event.getBlocks());
    }

    private void mark(final BlockPistonEvent event, final List<Block> moved) {
        this.arenas.markBlock(event.getBlock());
        for (int i = 0; i < moved.size(); i++) {
            final Block block = moved.get(i);
            this.arenas.markBlock(block);
            this.arenas.markBlock(block.getRelative(event.getDirection()));
        }
    }
}
