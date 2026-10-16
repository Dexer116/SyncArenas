package dev.dexer.syncarenas.util;

import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * Thread correct chat output.
 *
 * <p>Under region threading a player may only be touched from the thread that owns them, and most
 * of this plugin's work finishes on the async scheduler, so every message is routed back through
 * the right scheduler unless the current thread already owns the receiver.
 */
public final class Msg {

    public static final String PREFIX = "§8[§bSyncArenas§8] §7";

    private Msg() {
    }

    public static void send(final Plugin plugin, final CommandSender target, final String message) {
        final String text = PREFIX + message;
        if (target instanceof final Entity entity) {
            if (Bukkit.isOwnedByCurrentRegion(entity)) {
                target.sendMessage(text);
            } else {
                entity.getScheduler().run(plugin, task -> target.sendMessage(text), null);
            }
            return;
        }
        if (Bukkit.isGlobalTickThread()) {
            target.sendMessage(text);
        } else {
            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> target.sendMessage(text));
        }
    }

    /** Formats a duration in milliseconds with one decimal, e.g. {@code 12.4ms}. */
    public static String millis(final long nanos) {
        return String.format(Locale.ROOT, "%.1fms", nanos / 1_000_000.0D);
    }

    /** Groups a number with thin separators, e.g. {@code 1,204,331}. */
    public static String count(final long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    /** Formats a byte size, e.g. {@code 4.2 MiB}. */
    public static String bytes(final long value) {
        if (value < 1024L) {
            return value + " B";
        }
        if (value < 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f KiB", value / 1024.0D);
        }
        return String.format(Locale.ROOT, "%.1f MiB", value / (1024.0D * 1024.0D));
    }
}
