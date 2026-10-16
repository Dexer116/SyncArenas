package dev.dexer.syncarenas.service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/** Per player wand selections. */
public final class SelectionService {

    /** A (possibly incomplete) selection. Corner arrays are {@code {x, y, z}} or {@code null}. */
    public record Selection(World world, int[] first, int[] second) {

        public boolean complete() {
            return this.first != null && this.second != null;
        }
    }

    private final Map<UUID, Selection> selections = new ConcurrentHashMap<>();

    public Selection get(final Player player) {
        return this.selections.get(player.getUniqueId());
    }

    public void clear(final UUID playerId) {
        this.selections.remove(playerId);
    }

    /** Sets the first corner, dropping the second one when the player switched worlds. */
    public Selection setFirst(final Player player, final Block block) {
        return this.selections.compute(player.getUniqueId(), (id, current) -> {
            final int[] corner = {block.getX(), block.getY(), block.getZ()};
            if (current == null || !current.world().equals(block.getWorld())) {
                return new Selection(block.getWorld(), corner, null);
            }
            return new Selection(current.world(), corner, current.second());
        });
    }

    /** Sets the second corner, dropping the first one when the player switched worlds. */
    public Selection setSecond(final Player player, final Block block) {
        return this.selections.compute(player.getUniqueId(), (id, current) -> {
            final int[] corner = {block.getX(), block.getY(), block.getZ()};
            if (current == null || !current.world().equals(block.getWorld())) {
                return new Selection(block.getWorld(), null, corner);
            }
            return new Selection(current.world(), current.first(), corner);
        });
    }
}
