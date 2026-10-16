package dev.dexer.syncarenas;

import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Immutable snapshot of {@code config.yml}.
 *
 * @param restoreBudgetNanos    wall clock budget of a single region thread restore slice
 * @param blocksPerBudgetCheck  block writes between two clock reads
 * @param restoreChunksAtOnce   chunks restored concurrently
 * @param skipUnchangedBlocks   compare before writing
 * @param holdChunkTickets      keep restoring chunks loaded
 * @param captureChunksAtOnce   chunks snapshotted concurrently while creating an arena
 * @param compressionLevel      deflate level for section payloads
 * @param autosaveMinutes       dirty set autosave interval, 0 to disable
 * @param trackFluidFlow        treat fluid spread as a block change
 * @param trackPistons          treat piston moves as a block change
 * @param maxArenaBlocks        refuse larger arenas, 0 for no limit
 * @param wandMaterial          item handed out by {@code /arena wand}
 */
public record PluginSettings(
    long restoreBudgetNanos,
    int blocksPerBudgetCheck,
    int restoreChunksAtOnce,
    boolean skipUnchangedBlocks,
    boolean holdChunkTickets,
    int captureChunksAtOnce,
    int compressionLevel,
    int autosaveMinutes,
    boolean trackFluidFlow,
    boolean trackPistons,
    long maxArenaBlocks,
    Material wandMaterial
) {

    public static PluginSettings load(final FileConfiguration config, final Logger logger) {
        final long budgetMicros = clamp(config.getLong("restore.region-budget-micros", 400L), 25L, 25_000L);
        final int blocksPerCheck = (int) clamp(config.getInt("restore.blocks-per-budget-check", 256), 16L, 65_536L);
        final int restoreChunks = (int) clamp(config.getInt("restore.max-concurrent-chunks", 8), 1L, 256L);
        final int captureChunks = (int) clamp(config.getInt("capture.max-concurrent-chunks", 4), 1L, 64L);
        final int compression = (int) clamp(config.getInt("capture.compression-level", 4), 0L, 9L);
        final int autosave = (int) clamp(config.getInt("tracking.autosave-minutes", 5), 0L, 1440L);
        final long maxBlocks = Math.max(0L, config.getLong("limits.max-arena-blocks", 0L));

        final String wandName = config.getString("wand.material", "GOLDEN_AXE");
        Material wand = wandName == null ? null : Material.matchMaterial(wandName);
        if (wand == null || !wand.isItem()) {
            logger.warning("wand.material '" + wandName + "' is not a valid item, using GOLDEN_AXE.");
            wand = Material.GOLDEN_AXE;
        }

        return new PluginSettings(
            budgetMicros * 1_000L,
            blocksPerCheck,
            restoreChunks,
            config.getBoolean("restore.skip-unchanged-blocks", true),
            config.getBoolean("restore.hold-chunk-tickets", true),
            captureChunks,
            compression,
            autosave,
            config.getBoolean("tracking.track-fluid-flow", true),
            config.getBoolean("tracking.track-pistons", true),
            maxBlocks,
            wand);
    }

    private static long clamp(final long value, final long min, final long max) {
        return Math.max(min, Math.min(max, value));
    }
}
