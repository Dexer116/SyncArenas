package dev.dexer.syncarenas;

import dev.dexer.syncarenas.arena.ArenaManager;
import dev.dexer.syncarenas.command.ArenaCommand;
import dev.dexer.syncarenas.listener.BlockTrackingListener;
import dev.dexer.syncarenas.listener.FluidFlowTrackingListener;
import dev.dexer.syncarenas.listener.PistonTrackingListener;
import dev.dexer.syncarenas.listener.WandListener;
import dev.dexer.syncarenas.service.CaptureService;
import dev.dexer.syncarenas.service.RestoreService;
import dev.dexer.syncarenas.service.SelectionService;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.concurrent.TimeUnit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Section granular arena regeneration for Folia and CanvasMC.
 *
 * <p>Design in one paragraph: an arena's pristine state is captured once into a seekable
 * {@code .sa} file with one compressed entry per 16x16x16 section. Block events flip a bit for the
 * section they happened in, nothing more. A reset reads and inflates only the flagged sections on
 * the async scheduler, then writes their blocks from the region thread that owns each chunk, in
 * slices bounded by a configurable microsecond budget. Nothing in the plugin ever blocks a region
 * thread on I/O, and no tick pays for more than its budget.
 *
 * <p>These schedulers exist on plain Paper too, where they run on the server thread, so the plugin
 * behaves the same there - it simply has one region to spread the work over instead of many.
 */
public final class SyncArenas extends JavaPlugin {

    private PluginSettings settings;
    private ArenaManager arenas;
    private SelectionService selections;
    private CaptureService captures;
    private RestoreService restores;
    private ScheduledTask autosaveTask;

    @Override
    public void onEnable() {
        this.saveDefaultConfig();
        this.settings = PluginSettings.load(this.getConfig(), this.getLogger());
        this.arenas = new ArenaManager(this.getLogger(), this.getDataFolder().toPath());
        this.selections = new SelectionService();
        this.captures = new CaptureService(this);
        this.restores = new RestoreService(this);

        this.arenas.loadAll();

        final PluginManager events = this.getServer().getPluginManager();
        events.registerEvents(new BlockTrackingListener(this), this);
        events.registerEvents(new WandListener(this), this);
        // The two high frequency sources are only hooked when they are actually wanted, so turning
        // them off in the config means no handler runs for them at all.
        if (this.settings.trackFluidFlow()) {
            events.registerEvents(new FluidFlowTrackingListener(this), this);
        }
        if (this.settings.trackPistons()) {
            events.registerEvents(new PistonTrackingListener(this), this);
        }

        final PluginCommand command = this.getCommand("arena");
        if (command == null) {
            this.getLogger().severe("The /arena command is missing from plugin.yml; the jar is broken.");
        } else {
            final ArenaCommand executor = new ArenaCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        final int autosave = this.settings.autosaveMinutes();
        if (autosave > 0) {
            this.autosaveTask = this.getServer().getAsyncScheduler().runAtFixedRate(this,
                task -> this.arenas.saveUnsaved(), autosave, autosave, TimeUnit.MINUTES);
        }

        this.getLogger().info("Ready: " + this.settings.restoreBudgetNanos() / 1000L
            + "us region budget, up to " + this.settings.restoreChunksAtOnce()
            + " chunks restored at once; fluid flow tracking "
            + (this.settings.trackFluidFlow() ? "on" : "off") + ", piston tracking "
            + (this.settings.trackPistons() ? "on" : "off") + ".");
    }

    @Override
    public void onDisable() {
        if (this.autosaveTask != null) {
            this.autosaveTask.cancel();
            this.autosaveTask = null;
        }
        if (this.restores != null) {
            // Gives every section that did not make it back to its arena's dirty set first.
            this.restores.shutdown();
        }
        if (this.arenas != null) {
            this.arenas.saveAll();
        }
    }

    public PluginSettings settings() {
        return this.settings;
    }

    public ArenaManager arenas() {
        return this.arenas;
    }

    public SelectionService selections() {
        return this.selections;
    }

    public CaptureService captures() {
        return this.captures;
    }

    public RestoreService restores() {
        return this.restores;
    }
}
