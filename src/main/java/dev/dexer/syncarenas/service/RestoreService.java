package dev.dexer.syncarenas.service;

import dev.dexer.syncarenas.PluginSettings;
import dev.dexer.syncarenas.SyncArenas;
import dev.dexer.syncarenas.arena.Arena;
import dev.dexer.syncarenas.format.SectionPos;
import dev.dexer.syncarenas.format.SectionSnapshot;
import dev.dexer.syncarenas.format.SnapshotFile;
import dev.dexer.syncarenas.util.Chunks;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

/**
 * Restores arenas from their snapshots, section by section.
 *
 * <p>Shape of a reset:
 * <ol>
 *   <li>async: open the snapshot, take the arena's dirty section set, group it by chunk;</li>
 *   <li>async, per chunk: read and inflate only those sections;</li>
 *   <li>region thread owning that chunk: write the blocks in slices bounded by
 *       {@code restore.region-budget-micros}, resuming on the next tick of that region until the
 *       chunk is done.</li>
 * </ol>
 *
 * <p>Because step 3 is a region task per chunk, a reset of a large arena is spread over every
 * region thread that owns a piece of it instead of running on one thread, and no single tick ever
 * pays more than the configured budget per region.
 */
public final class RestoreService {

    /** Outcome of a reset. */
    public record Result(boolean success, String error, boolean full, int sections, int chunks,
                         long blocksChanged, long blocksScanned, long regionNanos, long wallNanos) {
    }

    private final SyncArenas plugin;
    private final Set<Job> active = ConcurrentHashMap.newKeySet();
    private volatile boolean shuttingDown;

    public RestoreService(final SyncArenas plugin) {
        this.plugin = plugin;
    }

    public int activeJobs() {
        return this.active.size();
    }

    /**
     * Starts a reset. The arena must be idle; the caller is expected to have checked
     * {@link Arena#state()} and this method takes the arena's job slot itself.
     *
     * @param full restore every section instead of only the dirty ones
     * @return {@code false} when the arena is busy or the plugin is shutting down
     */
    public boolean reset(final Arena arena, final World world, final boolean full,
                         final Consumer<Result> done) {
        if (this.shuttingDown) {
            return false;
        }
        if (!arena.beginJob(Arena.State.RESETTING)) {
            return false;
        }
        final Path path = this.plugin.arenas().store().snapshotPath(arena.name());
        Bukkit.getAsyncScheduler().runNow(this.plugin, task -> {
            final Job job = new Job(this.plugin, this, arena, world, full, path, done);
            this.active.add(job);
            job.start();
        });
        return true;
    }

    /** Stops handing out new work and gives every unfinished section back to its arena. */
    public void shutdown() {
        this.shuttingDown = true;
        for (final Job job : this.active) {
            job.abort("server shutting down");
            job.requeueOutstanding();
        }
    }

    boolean shuttingDown() {
        return this.shuttingDown;
    }

    void jobFinished(final Job job) {
        this.active.remove(job);
    }

    // --- internals ------------------------------------------------------------------------------

    /** One chunk's worth of dirty sections. */
    private record ChunkWork(int chunkX, int chunkZ, long[] sections) {
    }

    /** One reset run. */
    private static final class Job {

        private final SyncArenas plugin;
        private final RestoreService service;
        private final PluginSettings settings;
        private final Arena arena;
        private final World world;
        private final boolean full;
        private final Path path;
        private final Consumer<Result> done;

        private final Set<Long> outstanding = ConcurrentHashMap.newKeySet();
        private final AtomicInteger nextWork = new AtomicInteger();
        private final AtomicInteger activeSlots = new AtomicInteger();
        private final AtomicInteger finishedWorks = new AtomicInteger();
        private final AtomicInteger restoredSections = new AtomicInteger();
        private final AtomicLong blocksChanged = new AtomicLong();
        private final AtomicLong blocksScanned = new AtomicLong();
        private final AtomicLong regionNanos = new AtomicLong();
        private final AtomicBoolean aborted = new AtomicBoolean();
        private final AtomicBoolean completed = new AtomicBoolean();
        private final AtomicReference<String> error = new AtomicReference<>();
        private final long startedAt = System.nanoTime();

        private SnapshotFile snapshot;
        private ChunkWork[] works = new ChunkWork[0];
        private Set<Long> originallyDirty = Set.of();

        Job(final SyncArenas plugin, final RestoreService service, final Arena arena, final World world,
            final boolean full, final Path path, final Consumer<Result> done) {
            this.plugin = plugin;
            this.service = service;
            this.settings = plugin.settings();
            this.arena = arena;
            this.world = world;
            this.full = full;
            this.path = path;
            this.done = done;
        }

        /** Async: open the snapshot and plan the work. */
        void start() {
            if (this.stopping()) {
                // Bail before touching the dirty set, so nothing can be drained and then dropped.
                this.abort("plugin is shutting down");
                this.complete();
                return;
            }
            final Set<Long> wanted;
            try {
                this.snapshot = SnapshotFile.open(this.path);
                final Set<Long> drained = this.arena.drainDirty();
                this.originallyDirty = drained;
                if (this.full) {
                    wanted = new HashSet<>(this.snapshot.sectionKeys());
                } else {
                    wanted = new HashSet<>(drained.size());
                    for (final Long key : drained) {
                        if (this.snapshot.contains(key)) {
                            wanted.add(key);
                        }
                    }
                }
            } catch (final IOException ex) {
                this.plugin.getLogger().log(Level.SEVERE,
                    "Could not open the snapshot of arena " + this.arena.name(), ex);
                this.abort(ex.getMessage() == null ? ex.toString() : ex.getMessage());
                this.complete();
                return;
            }
            if (wanted.isEmpty()) {
                this.complete();
                return;
            }
            this.outstanding.addAll(wanted);
            this.works = group(wanted);
            this.pump();
        }

        private static ChunkWork[] group(final Set<Long> sections) {
            final Map<Long, List<Long>> byChunk = new HashMap<>();
            for (final Long section : sections) {
                byChunk.computeIfAbsent(SectionPos.chunkOf(section), ignored -> new ArrayList<>(4))
                    .add(section);
            }
            final ChunkWork[] works = new ChunkWork[byChunk.size()];
            int i = 0;
            for (final Map.Entry<Long, List<Long>> entry : byChunk.entrySet()) {
                final List<Long> list = entry.getValue();
                final long[] keys = new long[list.size()];
                for (int k = 0; k < keys.length; k++) {
                    keys[k] = list.get(k);
                }
                // Restoring a chunk bottom up keeps the light engine's work local.
                Arrays.sort(keys);
                works[i++] = new ChunkWork(SectionPos.chunkX(entry.getKey()),
                    SectionPos.chunkZ(entry.getKey()), keys);
            }
            return works;
        }

        private void pump() {
            final int limit = this.settings.restoreChunksAtOnce();
            while (true) {
                if (this.stopping()) {
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
                final int index = this.nextWork.getAndIncrement();
                if (index >= this.works.length) {
                    this.activeSlots.decrementAndGet();
                    return;
                }
                this.startWork(this.works[index]);
            }
        }

        /** Async: read and inflate the sections of one chunk, then hand them to its region. */
        private void startWork(final ChunkWork work) {
            try {
                this.readAndRestore(work);
            } catch (final RuntimeException ex) {
                // The schedulers refuse work once the plugin is disabled.
                this.abort(ex.getMessage() == null ? ex.toString() : ex.getMessage());
                this.workFinished();
            }
        }

        private void readAndRestore(final ChunkWork work) {
            Bukkit.getAsyncScheduler().runNow(this.plugin, task -> {
                final List<SectionWindow> pending = new ArrayList<>(work.sections().length);
                try {
                    for (final long key : work.sections()) {
                        final SectionSnapshot data = this.snapshot.read(key);
                        final SectionWindow window = SectionWindow.create(key, data, this.arena.bounds());
                        if (window == null) {
                            // The section does not overlap the arena, nothing to write.
                            this.sectionDone(key);
                        } else {
                            pending.add(window);
                        }
                    }
                } catch (final IOException | RuntimeException ex) {
                    this.plugin.getLogger().log(Level.SEVERE, "Could not read sections of arena "
                        + this.arena.name() + " in chunk " + work.chunkX() + ", " + work.chunkZ(), ex);
                    this.abort(ex.getMessage() == null ? ex.toString() : ex.getMessage());
                    this.workFinished();
                    return;
                }
                if (pending.isEmpty()) {
                    this.workFinished();
                    return;
                }
                new ChunkRestore(this, work.chunkX(), work.chunkZ(), pending).schedule();
            });
        }

        private void skipRemaining() {
            while (true) {
                final int index = this.nextWork.getAndIncrement();
                if (index >= this.works.length) {
                    return;
                }
                if (this.finishedWorks.incrementAndGet() == this.works.length) {
                    this.complete();
                    return;
                }
            }
        }

        private void workFinished() {
            this.activeSlots.decrementAndGet();
            if (this.finishedWorks.incrementAndGet() == this.works.length) {
                this.complete();
            } else {
                this.pump();
            }
        }

        private void sectionDone(final long key) {
            if (this.outstanding.remove(key)) {
                this.restoredSections.incrementAndGet();
            }
        }

        private boolean stopping() {
            return this.aborted.get() || this.service.shuttingDown() || !this.plugin.isEnabled();
        }

        void abort(final String message) {
            this.error.compareAndSet(null, message);
            this.aborted.set(true);
        }

        /**
         * Hands every section that was not restored back to the arena's dirty set. A full reset
         * only gives back the sections that were actually dirty: the rest were already pristine.
         */
        void requeueOutstanding() {
            if (this.outstanding.isEmpty()) {
                return;
            }
            final List<Long> requeue = new ArrayList<>(this.outstanding.size());
            for (final Long key : this.outstanding) {
                if (!this.full || this.originallyDirty.contains(key)) {
                    requeue.add(key);
                }
            }
            this.arena.requeueDirty(requeue);
        }

        /**
         * May be reached from a region thread (the last chunk slice) so the closing I/O is pushed
         * onto the async scheduler.
         */
        private void complete() {
            if (!this.completed.compareAndSet(false, true)) {
                return;
            }
            final long wall = System.nanoTime() - this.startedAt;
            this.requeueOutstanding();
            final String failure = this.error.get();
            if (failure == null) {
                this.arena.lastReset(System.currentTimeMillis());
            }
            this.arena.endJob();
            this.service.jobFinished(this);
            final Result result = new Result(failure == null, failure, this.full,
                this.restoredSections.get(), this.works.length, this.blocksChanged.get(),
                this.blocksScanned.get(), this.regionNanos.get(), wall);
            if (this.plugin.isEnabled()) {
                Bukkit.getAsyncScheduler().runNow(this.plugin, task -> this.finishOffThread(result));
            } else {
                this.finishOffThread(result);
            }
        }

        private void finishOffThread(final Result result) {
            if (this.snapshot != null) {
                try {
                    this.snapshot.close();
                } catch (final IOException ex) {
                    this.plugin.getLogger().log(Level.WARNING, "Could not close snapshot file", ex);
                }
            }
            this.plugin.arenas().save(this.arena);
            this.done.accept(result);
        }
    }

    /**
     * The region thread side: writes blocks for one chunk in budgeted slices.
     *
     * <p>Only ever touched by the region thread that owns the chunk, so the cursor fields need no
     * synchronisation.
     */
    private static final class ChunkRestore {

        private final Job job;
        private final int chunkX;
        private final int chunkZ;
        private final List<SectionWindow> sections;
        private int index;
        private boolean holdsTicket;
        private final AtomicBoolean reported = new AtomicBoolean();

        ChunkRestore(final Job job, final int chunkX, final int chunkZ, final List<SectionWindow> sections) {
            this.job = job;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.sections = sections;
        }

        void schedule() {
            try {
                Chunks.withChunk(this.job.plugin, this.job.world, this.chunkX, this.chunkZ, this::run, () -> {
                    this.job.plugin.getLogger().warning("Chunk " + this.chunkX + ", " + this.chunkZ
                        + " of arena " + this.job.arena.name()
                        + " could not be loaded; its sections stay queued for the next reset.");
                    this.report();
                });
            } catch (final RuntimeException ex) {
                // The schedulers refuse work once the plugin is disabled; finish the job cleanly
                // so its sections go back into the dirty set.
                this.job.abort(ex.getMessage() == null ? ex.toString() : ex.getMessage());
                this.report();
            }
        }

        private void run(final Chunk chunk) {
            try {
                this.restoreSlice(chunk);
            } catch (final RuntimeException ex) {
                this.job.plugin.getLogger().log(Level.SEVERE, "Restore of chunk " + this.chunkX + ", "
                    + this.chunkZ + " in arena " + this.job.arena.name() + " failed", ex);
                this.job.abort(ex.getMessage() == null ? ex.toString() : ex.getMessage());
                this.releaseTicket(chunk);
                this.report();
            }
        }

        /** Reports this chunk as finished exactly once. */
        private void report() {
            if (this.reported.compareAndSet(false, true)) {
                this.job.workFinished();
            }
        }

        private void restoreSlice(final Chunk chunk) {
            if (this.job.stopping()) {
                this.releaseTicket(chunk);
                this.report();
                return;
            }
            if (!this.holdsTicket && this.job.settings.holdChunkTickets()) {
                chunk.addPluginChunkTicket(this.job.plugin);
                this.holdsTicket = true;
            }

            final long begin = System.nanoTime();
            final long deadline = begin + this.job.settings.restoreBudgetNanos();
            final boolean skipUnchanged = this.job.settings.skipUnchangedBlocks();
            final int blocksPerCheck = this.job.settings.blocksPerBudgetCheck();
            long changed = 0L;
            long scanned = 0L;
            int sinceCheck = 0;
            boolean outOfTime = false;

            while (this.index < this.sections.size() && !outOfTime) {
                final SectionWindow window = this.sections.get(this.index);
                final SectionSnapshot data = window.data;
                window.seek();
                while (!window.done()) {
                    final int x = window.x();
                    final int y = window.y();
                    final int z = window.z();

                    final int paletteIndex = data.paletteIndex(
                        SectionPos.blockIndex(x & 15, y & 15, z & 15));
                    final BlockData target = data.state(paletteIndex);
                    final Block block = chunk.getBlock(x & 15, y, z & 15);
                    scanned++;
                    if (skipUnchanged
                        && block.getType() == target.getMaterial()
                        && (data.propertyLess(paletteIndex) || target.equals(block.getBlockData()))) {
                        // Already pristine: no write, so no light update and no block change packet.
                    } else {
                        block.setBlockData(target, false);
                        changed++;
                    }
                    window.advance();

                    if (++sinceCheck >= blocksPerCheck) {
                        sinceCheck = 0;
                        if (System.nanoTime() >= deadline) {
                            outOfTime = !window.done();
                            break;
                        }
                    }
                }
                if (window.done()) {
                    this.job.sectionDone(window.key);
                    this.index++;
                    if (this.index < this.sections.size() && System.nanoTime() >= deadline) {
                        outOfTime = true;
                    }
                }
            }

            this.job.regionNanos.addAndGet(System.nanoTime() - begin);
            if (changed > 0L) {
                this.job.blocksChanged.addAndGet(changed);
            }
            if (scanned > 0L) {
                this.job.blocksScanned.addAndGet(scanned);
            }

            if (this.index >= this.sections.size()) {
                this.releaseTicket(chunk);
                this.report();
                return;
            }
            Chunks.withChunkLater(this.job.plugin, this.job.world, this.chunkX, this.chunkZ, this::run, () -> {
                this.job.plugin.getLogger().warning("Chunk " + this.chunkX + ", " + this.chunkZ
                    + " of arena " + this.job.arena.name() + " unloaded mid restore; "
                    + "its remaining sections stay queued for the next reset.");
                this.report();
            }, 1L);
        }

        private void releaseTicket(final Chunk chunk) {
            if (this.holdsTicket) {
                this.holdsTicket = false;
                chunk.removePluginChunkTicket(this.job.plugin);
            }
        }
    }
}
