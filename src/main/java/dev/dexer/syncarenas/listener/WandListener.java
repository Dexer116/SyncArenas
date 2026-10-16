package dev.dexer.syncarenas.listener;

import dev.dexer.syncarenas.SyncArenas;
import dev.dexer.syncarenas.arena.ArenaBounds;
import dev.dexer.syncarenas.service.SelectionService;
import dev.dexer.syncarenas.util.Msg;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/** Turns the selection wand's clicks into arena corners. */
public final class WandListener implements Listener {

    private final SyncArenas plugin;
    private final SelectionService selections;
    private final NamespacedKey wandKey;

    public WandListener(final SyncArenas plugin) {
        this.plugin = plugin;
        this.selections = plugin.selections();
        this.wandKey = wandKey(plugin);
    }

    public static NamespacedKey wandKey(final SyncArenas plugin) {
        return new NamespacedKey(plugin, "wand");
    }

    public static boolean isWand(final SyncArenas plugin, final ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return false;
        }
        final ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer()
            .has(wandKey(plugin), PersistentDataType.BYTE);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteract(final PlayerInteractEvent event) {
        final Action action = event.getAction();
        if (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        final Block block = event.getClickedBlock();
        if (block == null || !isWand(this.plugin, event.getItem())) {
            return;
        }
        event.setCancelled(true);

        final SelectionService.Selection selection = action == Action.LEFT_CLICK_BLOCK
            ? this.selections.setFirst(event.getPlayer(), block)
            : this.selections.setSecond(event.getPlayer(), block);

        final String corner = action == Action.LEFT_CLICK_BLOCK ? "First" : "Second";
        final StringBuilder message = new StringBuilder()
            .append("§b").append(corner).append(" corner §7set to §f")
            .append(block.getX()).append("§7, §f")
            .append(block.getY()).append("§7, §f")
            .append(block.getZ());
        if (selection.complete()) {
            final dev.dexer.syncarenas.arena.ArenaBounds bounds = dev.dexer.syncarenas.arena.ArenaBounds.of(
                selection.first()[0], selection.first()[1], selection.first()[2],
                selection.second()[0], selection.second()[1], selection.second()[2]);
            message.append("§7 (").append(bounds.sizeX()).append('x').append(bounds.sizeY())
                .append('x').append(bounds.sizeZ()).append(", ")
                .append(dev.dexer.syncarenas.util.Msg.count(bounds.blockCount())).append(" blocks)");
        }
        // The interact event already runs on the player's own thread.
        event.getPlayer().sendMessage(Msg.PREFIX + message);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        this.selections.clear(event.getPlayer().getUniqueId());
    }
}
