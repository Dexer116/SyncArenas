package dev.dexer.syncarenas.service;

import dev.dexer.syncarenas.PluginSettings;
import dev.dexer.syncarenas.SyncArenas;
import dev.dexer.syncarenas.arena.Arena;
import dev.dexer.syncarenas.arena.ArenaBounds;
import dev.dexer.syncarenas.format.SectionCodec;
import dev.dexer.syncarenas.format.SectionPos;
import dev.dexer.syncarenas.format.SnapshotWriter;
import dev.dexer.syncarenas.util.Chunks;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

/**
 * Captures the pristine state of an arena into its {@code .sa} snapshot.
 *
 * <p>The only work that happens on a region thread is {@code Chunk#getChunkSnapshot}, which is a
 * cheap copy of the chunk's paletted containers. Palette building, bit packing, compression and
 * the disk writes all run on the async scheduler.
 */
public final class CaptureService {

    /** Outcome of a capture. */
    public record Result(boolean success, String error, int sections, long bytes,
                         long regionNanos, long wallNanos) {
    }

    private final SyncArenas plugin;

    public CaptureService(final SyncArenas plugin) {
        this.plugin = plugin;
    }

    /** Starts a capture; {@code done} is called on the async scheduler when it finishes. */
    public void capture(final Arena arena, final World world, final Consumer<Result> done) {
        Bukkit.getAsyncScheduler().runNow(this.plugin, task -> {
            final Path path = this.plugin.arenas().store().snapshotPath(arena.name());
            try {
                Files.createDirectories(path.getParent());
                new Job(this.plugin, arena, world, path, done).start();
            } catch (final IOException ex) {
                this.plugin.getLogger().log(Level.SEVERE, "Could not start the capture of " + arena.name(), ex);
                done.accept(new Result(false, ex.getMessage(), 0, 0L, 0L, 0L));
            }
        });
    }

    /** One capture run. */
    private static final class Job {

        private final SyncArenas plugin;
        private final PluginSettings settings;
        private final Arena arena;
        private final World world;
        private final Path path;
        private final Consumer<Result> done;

        private final long[] chunkKeys;
        private final AtomicInteger nextChunk = new AtomicInteger();
        private final AtomicInteger activeSlots = new AtomicInteger();
        private final AtomicInteger finishedChunks = new AtomicInteger();
        private final AtomicInteger sections = new AtomicInteger();
        private final AtomicLong regionNanos = new AtomicLong();
        private final AtomicBoolean aborted = new AtomicBoolean();
        private final AtomicBoolean completed = new AtomicBoolean();
        private final AtomicReference<String> error = new AtomicReference<>();
        private final long startedAt = System.nanoTime();

        private SnapshotWriter writer;

        Job(final SyncArenas plugin, final Arena arena, final World world, final Path path,
            final Consumer<Result> done) {
            this.plugin = plugin;
            this.settings = plugin.settings();
            this.arena = arena;
            this.world = world;
            this.path = path;
            this.done = done;
            this.chunkKeys = arena.bounds().chunkKeys();
        }

        void start() throws IOException {
            this.writer = new SnapshotWriter(this.path, this.world.getName(), this.arena.bounds(),
                this.arena.bounds().sectionCount());
            if (this.chunkKeys.length == 0) {
                this.complete();
                return;
            }
            this.pump();
        }

        private void pump() {
            final int limit = this.settings.captureChunksAtOnce();
            while (true) {
                if (this.aborted.get()) {
                    this.skipRemaining();
                    return;
                }
                final int slots = this.activeSlots.get();
                if (slots >= limit) {
                    return;
                }
                if (!this.activeSlots.compareAndSet(slots, slots + 1)) {
                    continue;
                }
                final int index = this.nextChunk.getAndIncrement();
                if (index >= this.chunkKeys.length) {
                    this.activeSlots.decrementAndGet();
                    return;
                }
                this.startChunk(this.chunkKeys[index]);
            }
        }

        private void startChunk(final long chunkKey) {
            final int chunkX = SectionPos.chunkX(chunkKey);
            final int chunkZ = SectionPos.chunkZ(chunkKey);
            try {
                this.snapshotChunk(chunkX, chunkZ);
            } catch (final RuntimeException ex) {
                // The schedulers refuse work once the plugin is disabled.
                this.fail(ex.getMessage() == null ? ex.toString() : ex.getMessage());
                this.chunkFinished();
            }
        }

        private void snapshotChunk(final int chunkX, final int chunkZ) {
            Chunks.withChunk(this.plugin, this.world, chunkX, chunkZ, chunk -> {
                final ChunkSnapshot snapshot;
                try {
                    final long begin = System.nanoTime();
                    snapshot = chunk.getChunkSnapshot(false, false, false);
                    this.regionNanos.addAndGet(System.nanoTime() - begin);
                } catch (final RuntimeException ex) {
                    this.plugin.getLogger().log(Level.SEVERE,
                        "Could not snapshot chunk " + chunkX + ", " + chunkZ, ex);
                    this.fail("chunk " + chunkX + ", " + chunkZ + " could not be snapshotted");
                    this.chunkFinished();
                    return;
                }
                try {
                    Bukkit.getAsyncScheduler().runNow(this.plugin,
                        task -> this.encodeChunk(chunkX, chunkZ, snapshot));
                } catch (final RuntimeException ex) {
                    this.fail(ex.getMessage() == null ? ex.toString() : ex.getMessage());
                    this.chunkFinished();
                }
            }, () -> {
                this.fail("chunk " + chunkX + ", " + chunkZ + " could not be loaded");
                this.chunkFinished();
            });
        }

        /** Counts every not yet started chunk as finished so an aborted job still completes. */
        private void skipRemaining() {
            while (true) {
                final int index = this.nextChunk.getAndIncrement();
                if (index >= this.chunkKeys.length) {
                    return;
                }
                if (this.finishedChunks.incrementAndGet() == this.chunkKeys.length) {
                    this.complete();
                    return;
                }
            }
        }

        /** Async: turn one chunk snapshot into section payloads and append them to the file. */
        private void encodeChunk(final int chunkX, final int chunkZ, final ChunkSnapshot snapshot) {
            try {
                final ArenaBounds bounds = this.arena.bounds();
                final int[] indices = new int[SectionPos.SECTION_VOLUME];
                final Map<BlockData, Integer> paletteIndex = new HashMap<>();
                final List<BlockData> palette = new ArrayList<>();
                for (int sectionY = bounds.minSectionY(); sectionY <= bounds.maxSectionY(); sectionY++) {
                    paletteIndex.clear();
                    palette.clear();
                    final int baseY = sectionY << 4;
                    for (int localY = 0; localY < 16; localY++) {
                        for (int localZ = 0; localZ < 16; localZ++) {
                            for (int localX = 0; localX < 16; localX++) {
                                final BlockData data = snapshot.getBlockData(localX, baseY + localY, localZ);
                                Integer index = paletteIndex.get(data);
                                if (index == null) {
                                    index = palette.size();
                                    paletteIndex.put(data, index);
                                    palette.add(data);
                                }
                                indices[SectionPos.blockIndex(localX, localY, localZ)] = index;
                            }
                        }
                    }
                    final byte[] payload = SectionCodec.encode(palette, indices);
                    final byte[] compressed = SnapshotWriter.compress(payload, this.settings.compressionLevel());
                    this.writer.writeSection(SectionPos.key(chunkX, sectionY, chunkZ), compressed, payload.length);
                    this.sections.incrementAndGet();
                }
            } catch (final IOException | RuntimeException ex) {
                this.plugin.getLogger().log(Level.SEVERE,
                    "Failed to encode chunk " + chunkX + ", " + chunkZ + " of arena " + this.arena.name(), ex);
                this.fail(ex.getMessage() == null ? ex.toString() : ex.getMessage());
            }
            this.chunkFinished();
        }

        private void chunkFinished() {
            this.activeSlots.decrementAndGet();
            if (this.finishedChunks.incrementAndGet() == this.chunkKeys.length) {
                this.complete();
            } else {
                this.pump();
            }
        }

        private void fail(final String message) {
            this.error.compareAndSet(null, message);
            this.aborted.set(true);
        }

        /** May be reached from a region thread, so the closing I/O is pushed onto the async scheduler. */
        private void complete() {
            if (!this.completed.compareAndSet(false, true)) {
                return;
            }
            if (this.plugin.isEnabled()) {
                Bukkit.getAsyncScheduler().runNow(this.plugin, task -> this.finishOffThread());
            } else {
                this.finishOffThread();
            }
        }

        private void finishOffThread() {
            final long wall = System.nanoTime() - this.startedAt;
            long bytes = 0L;
            String failure = this.error.get();
            try {
                if (failure == null) {
                    this.writer.finish();
                    bytes = this.writer.byteSize();
                }
            } catch (final IOException ex) {
                failure = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            } finally {
                try {
                    this.writer.close();
                } catch (final IOException ex) {
                    this.plugin.getLogger().log(Level.WARNING, "Could not close snapshot writer", ex);
                }
            }
            if (failure != null) {
                try {
                    Files.deleteIfExists(this.path);
                } catch (final IOException ex) {
                    this.plugin.getLogger().log(Level.WARNING, "Could not remove the partial snapshot "
                        + this.path, ex);
                }
            }
            this.done.accept(new Result(failure == null, failure, this.sections.get(), bytes,
                this.regionNanos.get(), wall));
        }
    }
}
