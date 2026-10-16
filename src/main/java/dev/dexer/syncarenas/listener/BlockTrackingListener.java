package dev.dexer.syncarenas.listener;

import dev.dexer.syncarenas.SyncArenas;
import dev.dexer.syncarenas.arena.ArenaManager;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.CauldronLevelChangeEvent;
import org.bukkit.event.block.FluidLevelChangeEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.block.MoistureChangeEvent;
import org.bukkit.event.block.SpongeAbsorbEvent;
import org.bukkit.event.block.TNTPrimeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

/**
 * Flags the sections an arena needs restoring.
 *
 * <p>These handlers are the only part of the plugin that runs on every block change of every
 * region thread, so they stay as cheap as physically possible: one reference comparison per loaded
 * world with arenas, one box test against the union of that world's arenas, and only then a
 * {@code long} added to a concurrent set. Nothing blocks, nothing allocates per event, and no
 * event is ever cancelled or modified here.
 *
 * <p>Everything runs at {@link EventPriority#MONITOR} with {@code ignoreCancelled = true}, so a
 * change another plugin vetoes is never counted.
 *
 * <p>The two opt-out sources, fluid flow and piston movement, live in
 * {@link FluidFlowTrackingListener} and {@link PistonTrackingListener} so that turning them off in
 * the config registers no handler for them at all.
 */
public final class BlockTrackingListener implements Listener {

    private final SyncArenas plugin;
    private final ArenaManager arenas;

    public BlockTrackingListener(final SyncArenas plugin) {
        this.plugin = plugin;
        this.arenas = plugin.arenas();
    }

    private void mark(final Block block) {
        this.arenas.markBlock(block.getWorld(), block.getX(), block.getY(), block.getZ());
    }

    private void markBlocks(final List<Block> blocks) {
        for (int i = 0; i < blocks.size(); i++) {
            this.mark(blocks.get(i));
        }
    }

    private void markStates(final List<BlockState> states) {
        for (int i = 0; i < states.size(); i++) {
            final BlockState state = states.get(i);
            this.arenas.markBlock(state.getWorld(), state.getX(), state.getY(), state.getZ());
        }
    }

    // --- player driven changes ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(final BlockBreakEvent event) {
        this.mark(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(final BlockPlaceEvent event) {
        this.mark(event.getBlock());
        // Beds, doors and other multi block placements share this handler list.
        if (event instanceof final BlockMultiPlaceEvent multi) {
            this.markStates(multi.getReplacedBlockStates());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEmpty(final PlayerBucketEmptyEvent event) {
        this.mark(event.getBlockClicked());
        this.mark(event.getBlockClicked().getRelative(event.getBlockFace()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketFill(final PlayerBucketFillEvent event) {
        this.mark(event.getBlockClicked());
    }

    // --- explosions -----------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(final BlockExplodeEvent event) {
        this.mark(event.getBlock());
        this.markBlocks(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(final EntityExplodeEvent event) {
        this.markBlocks(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTntPrime(final TNTPrimeEvent event) {
        this.mark(event.getBlock());
    }

    // --- world driven changes -------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(final BlockBurnEvent event) {
        this.mark(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIgnite(final BlockIgniteEvent event) {
        this.mark(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFade(final BlockFadeEvent event) {
        this.mark(event.getBlock());
    }

    /** Also covers {@code EntityBlockFormEvent}, which shares this handler list. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(final BlockFormEvent event) {
        this.mark(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpread(final BlockSpreadEvent event) {
        this.mark(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGrow(final BlockGrowEvent event) {
        this.mark(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeavesDecay(final LeavesDecayEvent event) {
        this.mark(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStructureGrow(final StructureGrowEvent event) {
        this.markStates(event.getBlocks());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpongeAbsorb(final SpongeAbsorbEvent event) {
        this.mark(event.getBlock());
        this.markStates(event.getBlocks());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCauldronLevelChange(final CauldronLevelChangeEvent event) {
        this.mark(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFluidLevelChange(final FluidLevelChangeEvent event) {
        this.mark(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMoistureChange(final MoistureChangeEvent event) {
        this.mark(event.getBlock());
    }

    /** Endermen, falling blocks, ravagers, wither heads and friends. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(final EntityChangeBlockEvent event) {
        this.mark(event.getBlock());
    }

    // --- index maintenance ----------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(final WorldLoadEvent event) {
        this.arenas.rebuildIndex();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldUnload(final WorldUnloadEvent event) {
        this.arenas.rebuildIndex();
        if (this.plugin.isEnabled()) {
            // Keep the dirty sets of that world's arenas on disk; never block the tick on I/O.
            Bukkit.getAsyncScheduler().runNow(this.plugin, task -> this.arenas.saveUnsaved());
        }
    }
}
