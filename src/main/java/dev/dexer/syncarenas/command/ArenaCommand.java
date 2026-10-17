package dev.dexer.syncarenas.command;

import dev.dexer.syncarenas.SyncArenas;
import dev.dexer.syncarenas.arena.Arena;
import dev.dexer.syncarenas.arena.ArenaBounds;
import dev.dexer.syncarenas.listener.WandListener;
import dev.dexer.syncarenas.service.RestoreService;
import dev.dexer.syncarenas.service.SelectionService;
import dev.dexer.syncarenas.util.Msg;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/** {@code /arena} - create, wand, reset, list, delete. */
public final class ArenaCommand implements TabExecutor {

    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final List<String> SUBCOMMANDS = List.of("create", "wand", "reset", "interval", "list", "delete");

    private final SyncArenas plugin;

    public ArenaCommand(final SyncArenas plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label,
                             final String[] args) {
        if (!sender.hasPermission("syncarenas.admin")) {
            Msg.send(this.plugin, sender, "§cYou may not manage arenas.");
            return true;
        }
        if (args.length == 0) {
            this.usage(sender, label);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> this.create(sender, label, args);
            case "wand" -> this.wand(sender);
            case "reset" -> this.reset(sender, label, args);
            case "interval" -> this.interval(sender, label, args);
            case "list" -> this.list(sender);
            case "delete", "remove" -> this.delete(sender, label, args);
            default -> this.usage(sender, label);
        }
        return true;
    }

    private void usage(final CommandSender sender, final String label) {
        Msg.send(this.plugin, sender, "§bSyncArenas §7commands:");
        Msg.send(this.plugin, sender, "§f/" + label + " create <name> §7- snapshot your selection");
        Msg.send(this.plugin, sender, "§f/" + label + " wand §7- get the selection wand");
        Msg.send(this.plugin, sender, "§f/" + label + " reset <name> [full] §7- restore broken sections");
        Msg.send(this.plugin, sender, "\u00a7f/" + label + " interval <name> <seconds|off> \u00a77- auto reset");
        Msg.send(this.plugin, sender, "§f/" + label + " list §7- list arenas");
        Msg.send(this.plugin, sender, "§f/" + label + " delete <name> §7- drop an arena");
    }

    // --- create ---------------------------------------------------------------------------------

    private void create(final CommandSender sender, final String label, final String[] args) {
        if (args.length < 2) {
            Msg.send(this.plugin, sender, "§cUsage: §f/" + label + " create <name>");
            return;
        }
        if (!(sender instanceof final Player player)) {
            Msg.send(this.plugin, sender, "§cOnly a player can create an arena: it uses your wand selection.");
            return;
        }
        final String name = args[1];
        if (!VALID_NAME.matcher(name).matches()) {
            Msg.send(this.plugin, sender, "§cUse 1 to 32 characters out of A-Z, a-z, 0-9, _ and -.");
            return;
        }
        if (this.plugin.arenas().exists(name)) {
            Msg.send(this.plugin, sender, "§cAn arena called §f" + name + " §calready exists.");
            return;
        }
        final SelectionService.Selection selection = this.plugin.selections().get(player);
        if (selection == null || !selection.complete()) {
            Msg.send(this.plugin, sender, "§cSelect both corners first: §f/" + label + " wand");
            return;
        }
        final World world = selection.world();
        final ArenaBounds raw = ArenaBounds.of(
            selection.first()[0], selection.first()[1], selection.first()[2],
            selection.second()[0], selection.second()[1], selection.second()[2]);
        final ArenaBounds bounds = raw.clampY(world.getMinHeight(), world.getMaxHeight() - 1);
        final long maxBlocks = this.plugin.settings().maxArenaBlocks();
        if (maxBlocks > 0L && bounds.blockCount() > maxBlocks) {
            Msg.send(this.plugin, sender, "§cThat selection holds " + Msg.count(bounds.blockCount())
                + " blocks, the configured limit is " + Msg.count(maxBlocks) + ".");
            return;
        }

        final Arena arena = new Arena(name, world.getName(), world.getUID(), bounds,
            System.currentTimeMillis());
        arena.beginJob(Arena.State.CAPTURING);
        Msg.send(this.plugin, sender, "§7Capturing §f" + Msg.count(bounds.blockCount())
            + " §7blocks in §f" + Msg.count(bounds.sectionCount())
            + " §7sections across §f" + Msg.count(bounds.chunkCount()) + " §7chunks...");

        this.plugin.captures().capture(arena, world, result -> {
            arena.endJob();
            if (!result.success()) {
                Msg.send(this.plugin, sender, "§cCapture failed: §f"
                    + (result.error() == null ? "unknown error" : result.error()));
                return;
            }
            arena.snapshotSections(result.sections());
            arena.snapshotBytes(result.bytes());
            this.plugin.arenas().save(arena);
            // Registering last means a failed capture leaves nothing behind.
            this.plugin.arenas().register(arena);
            Msg.send(this.plugin, sender, "§aCreated §f" + name + "§a: §f"
                + Msg.count(result.sections()) + " §7sections, §f" + Msg.bytes(result.bytes())
                + " §7on disk, took §f" + Msg.millis(result.wallNanos())
                + " §7(region threads: §f" + Msg.millis(result.regionNanos()) + "§7)");
        });
    }

    // --- wand -----------------------------------------------------------------------------------

    private void wand(final CommandSender sender) {
        if (!(sender instanceof final Player player)) {
            Msg.send(this.plugin, sender, "§cOnly a player can hold a wand.");
            return;
        }
        final ItemStack wand = new ItemStack(this.plugin.settings().wandMaterial());
        final ItemMeta meta = wand.getItemMeta();
        if (meta == null) {
            Msg.send(this.plugin, sender, "§cThe configured wand item cannot carry item data.");
            return;
        }
        meta.displayName(Component.text("Arena Wand")
            .color(NamedTextColor.AQUA)
            .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
            Component.text("Left click: first corner")
                .color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false),
            Component.text("Right click: second corner")
                .color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(WandListener.wandKey(this.plugin),
            PersistentDataType.BYTE, (byte) 1);
        wand.setItemMeta(meta);
        player.getInventory().addItem(wand);
        Msg.send(this.plugin, sender, "§7Left click for the §bfirst §7corner, "
            + "right click for the §bsecond§7.");
    }

    // --- reset ----------------------------------------------------------------------------------

    private void reset(final CommandSender sender, final String label, final String[] args) {
        if (args.length < 2) {
            Msg.send(this.plugin, sender, "§cUsage: §f/" + label + " reset <name> [full]");
            return;
        }
        final Arena arena = this.plugin.arenas().get(args[1]);
        if (arena == null) {
            Msg.send(this.plugin, sender, "§cNo arena called §f" + args[1] + "§c.");
            return;
        }
        if (arena.state() != Arena.State.IDLE) {
            Msg.send(this.plugin, sender, "§cArena §f" + arena.name() + " §cis currently "
                + arena.state().name().toLowerCase(Locale.ROOT) + ".");
            return;
        }
        final World world = Bukkit.getWorld(arena.worldName());
        if (world == null) {
            Msg.send(this.plugin, sender, "§cWorld §f" + arena.worldName() + " §cis not loaded.");
            return;
        }
        final boolean full = args.length > 2 && args[2].equalsIgnoreCase("full");
        final int dirty = arena.dirtyCount();
        if (!full && dirty == 0) {
            Msg.send(this.plugin, sender, "§aArena §f" + arena.name()
                + " §ais already pristine; nothing to restore.");
            return;
        }
        Msg.send(this.plugin, sender, "§7Restoring §f"
            + (full ? Msg.count(arena.snapshotSections()) + " §7(full)" : Msg.count(dirty))
            + " §7section(s) of §f" + arena.name() + "§7...");

        final boolean started = this.plugin.restores().reset(arena, world, full,
            result -> this.report(sender, arena, result));
        if (!started) {
            Msg.send(this.plugin, sender, "§cCould not start the reset; the arena is busy.");
        }
    }

    private void report(final CommandSender sender, final Arena arena, final RestoreService.Result result) {
        if (!result.success()) {
            Msg.send(this.plugin, sender, "§cReset of §f" + arena.name() + " §cfailed: §f"
                + (result.error() == null ? "unknown error" : result.error())
                + " §7(unrestored sections stay queued)");
            return;
        }
        Msg.send(this.plugin, sender, "§aReset §f" + arena.name() + "§a: §f"
            + Msg.count(result.sections()) + " §7sections in §f" + Msg.count(result.chunks())
            + " §7chunks, §f" + Msg.count(result.blocksChanged()) + " §7blocks rewritten of "
            + "§f" + Msg.count(result.blocksScanned()) + " §7checked");
        Msg.send(this.plugin, sender, "§7Took §f" + Msg.millis(result.wallNanos())
            + " §7wall clock, §f" + Msg.millis(result.regionNanos())
            + " §7of region thread time in total.");
    }

    // --- interval -------------------------------------------------------------------------------

    private void interval(final CommandSender sender, final String label, final String[] args) {
        if (args.length < 3) {
            Msg.send(this.plugin, sender, "\u00a7cUsage: \u00a7f/" + label + " interval <name> <seconds|off>");
            return;
        }
        final Arena arena = this.plugin.arenas().get(args[1]);
        if (arena == null) {
            Msg.send(this.plugin, sender, "\u00a7cNo arena called \u00a7f" + args[1] + "\u00a7c.");
            return;
        }
        int seconds = 0;
        if (!args[2].equalsIgnoreCase("off")) {
            try {
                seconds = Integer.parseInt(args[2]);
            } catch (final NumberFormatException ex) {
                seconds = -1;
            }
            if (seconds < 0) {
                Msg.send(this.plugin, sender, "\u00a7cSeconds must be a whole number, or \u00a7foff\u00a7c.");
                return;
            }
        }
        arena.intervalSeconds(seconds);
        Bukkit.getAsyncScheduler().runNow(this.plugin, task -> this.plugin.arenas().save(arena));
        Msg.send(this.plugin, sender, seconds == 0
            ? "\u00a77Automatic reset of \u00a7f" + arena.name() + " \u00a77is \u00a7coff\u00a77."
            : "\u00a7f" + arena.name() + " \u00a77now resets every \u00a7f" + seconds + "s\u00a77.");
    }

    // --- list -----------------------------------------------------------------------------------

    private void list(final CommandSender sender) {
        final List<Arena> arenas = new ArrayList<>(this.plugin.arenas().all());
        if (arenas.isEmpty()) {
            Msg.send(this.plugin, sender, "§7No arenas yet. Create one with §f/arena create <name>§7.");
            return;
        }
        arenas.sort((left, right) -> left.name().compareToIgnoreCase(right.name()));
        Msg.send(this.plugin, sender, "§bArenas §7(" + arenas.size() + "):");
        for (final Arena arena : arenas) {
            final ArenaBounds bounds = arena.bounds();
            Msg.send(this.plugin, sender, "§f" + arena.name() + " §8| §7"
                + arena.worldName() + " §8| §7" + bounds.sizeX() + "x" + bounds.sizeY()
                + "x" + bounds.sizeZ() + " §8| §7" + Msg.count(arena.snapshotSections())
                + " sections §8| §e" + Msg.count(arena.dirtyCount()) + " dirty"
                + (arena.intervalSeconds() > 0 ? " \u00a78| \u00a7b" + arena.intervalSeconds() + "s" : "")
                + (arena.state() == Arena.State.IDLE ? "" : " §8| §c"
                    + arena.state().name().toLowerCase(Locale.ROOT)));
        }
    }

    // --- delete ---------------------------------------------------------------------------------

    private void delete(final CommandSender sender, final String label, final String[] args) {
        if (args.length < 2) {
            Msg.send(this.plugin, sender, "§cUsage: §f/" + label + " delete <name>");
            return;
        }
        final Arena arena = this.plugin.arenas().get(args[1]);
        if (arena == null) {
            Msg.send(this.plugin, sender, "§cNo arena called §f" + args[1] + "§c.");
            return;
        }
        // Claiming the job slot and never releasing it keeps resets off a deleted arena.
        if (!arena.beginJob(Arena.State.RESETTING)) {
            Msg.send(this.plugin, sender, "§cArena §f" + arena.name()
                + " §cis busy; try again in a moment.");
            return;
        }
        this.plugin.arenas().unregister(arena);
        Bukkit.getAsyncScheduler().runNow(this.plugin, task -> {
            try {
                this.plugin.arenas().store().delete(arena.name());
                Msg.send(this.plugin, sender, "§aDeleted arena §f" + arena.name() + "§a.");
            } catch (final IOException ex) {
                this.plugin.getLogger().log(Level.WARNING,
                    "Could not delete the files of arena " + arena.name(), ex);
                Msg.send(this.plugin, sender, "§cArena §f" + arena.name()
                    + " §cwas unregistered but its files could not be removed: §f" + ex.getMessage());
            }
        });
    }

    // --- completion -----------------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command, final String label,
                                      final String[] args) {
        if (!sender.hasPermission("syncarenas.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            return prefixed(SUBCOMMANDS, args[0]);
        }
        final String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && (sub.equals("reset") || sub.equals("interval") || sub.equals("delete") || sub.equals("remove"))) {
            final List<String> names = new ArrayList<>(this.plugin.arenas().size());
            for (final Arena arena : this.plugin.arenas().all()) {
                names.add(arena.name());
            }
            names.sort(String::compareToIgnoreCase);
            return prefixed(names, args[1]);
        }
        if (args.length == 3 && sub.equals("interval")) {
            return prefixed(List.of("off", "30", "60", "300"), args[2]);
        }
        if (args.length == 3 && sub.equals("reset")) {
            return prefixed(List.of("full"), args[2]);
        }
        return List.of();
    }

    private static List<String> prefixed(final List<String> options, final String prefix) {
        final String lower = prefix.toLowerCase(Locale.ROOT);
        final List<String> matches = new ArrayList<>(options.size());
        for (final String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                matches.add(option);
            }
        }
        return matches;
    }
}
