package dev.dexer.syncarenas.arena;

import dev.dexer.syncarenas.format.SectionPos;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One arena: its box, the snapshot that backs it and the set of sections that have been touched
 * since the last reset.
 *
 * <p>The dirty set is the whole point of the plugin: block events only ever flip a bit for the
 * 16x16x16 section they happened in, and a reset then restores nothing but those sections. All
 * mutable state here is safe to touch from several region threads at once.
 */
public final class Arena {

    /** What the arena is currently busy with. */
    public enum State {
        IDLE,
        CAPTURING,
        RESETTING
    }

    private final String name;
    private final String worldName;
    private final UUID worldId;
    private final ArenaBounds bounds;
    private final long createdAt;

    private final Set<Long> dirtySections = ConcurrentHashMap.newKeySet();
    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);

    private volatile int snapshotSections;
    private volatile long snapshotBytes;
    private volatile long lastReset;
    private volatile boolean dirtyStateUnsaved;
    private volatile int intervalSeconds;
    private volatile long nextReset;

    public Arena(final String name, final String worldName, final UUID worldId,
                 final ArenaBounds bounds, final long createdAt) {
        this.name = name;
        this.worldName = worldName;
        this.worldId = worldId;
        this.bounds = bounds;
        this.createdAt = createdAt;
    }

    public String name() {
        return this.name;
    }

    /** Lower cased name, used as the registry key. */
    public String key() {
        return this.name.toLowerCase(Locale.ROOT);
    }

    public String worldName() {
        return this.worldName;
    }

    public UUID worldId() {
        return this.worldId;
    }

    public ArenaBounds bounds() {
        return this.bounds;
    }

    public long createdAt() {
        return this.createdAt;
    }

    public int snapshotSections() {
        return this.snapshotSections;
    }

    public void snapshotSections(final int sections) {
        this.snapshotSections = sections;
    }

    public long snapshotBytes() {
        return this.snapshotBytes;
    }

    public void snapshotBytes(final long bytes) {
        this.snapshotBytes = bytes;
    }

    public long lastReset() {
        return this.lastReset;
    }

    public void lastReset(final long millis) {
        this.lastReset = millis;
    }

    /** Seconds between automatic resets, 0 when off. */
    public int intervalSeconds() {
        return this.intervalSeconds;
    }

    public void intervalSeconds(final int seconds) {
        this.intervalSeconds = Math.max(0, seconds);
        this.scheduleNext(System.nanoTime());
    }

    /** {@link System#nanoTime()} deadline of the next automatic reset. */
    public long nextReset() {
        return this.nextReset;
    }

    public void scheduleNext(final long nowNanos) {
        this.nextReset = nowNanos + this.intervalSeconds * 1_000_000_000L;
    }

    public State state() {
        return this.state.get();
    }

    /** Moves the arena from {@link State#IDLE} into {@code target}. */
    public boolean beginJob(final State target) {
        return this.state.compareAndSet(State.IDLE, target);
    }

    public void endJob() {
        this.state.set(State.IDLE);
    }

    // --- dirty tracking -------------------------------------------------------------------------

    /**
     * Marks the section containing the given block, when the block is inside the arena.
     * This is the hot path; it allocates nothing but the {@link Long} box of a new key.
     */
    public void markBlock(final int x, final int y, final int z) {
        if (!this.bounds.contains(x, y, z)) {
            return;
        }
        this.markSection(SectionPos.keyOfBlock(x, y, z));
    }

    public void markSection(final long sectionKey) {
        if (this.dirtySections.add(sectionKey)) {
            this.dirtyStateUnsaved = true;
        }
    }

    public int dirtyCount() {
        return this.dirtySections.size();
    }

    /** Takes the current dirty set and clears it. */
    public Set<Long> drainDirty() {
        final Set<Long> taken = ConcurrentHashMap.newKeySet(Math.max(16, this.dirtySections.size()));
        taken.addAll(this.dirtySections);
        this.dirtySections.removeAll(taken);
        if (!taken.isEmpty()) {
            this.dirtyStateUnsaved = true;
        }
        return taken;
    }

    /** Puts sections back after a failed restore so a later reset picks them up again. */
    public void requeueDirty(final Collection<Long> sections) {
        if (sections.isEmpty()) {
            return;
        }
        this.dirtySections.addAll(sections);
        this.dirtyStateUnsaved = true;
    }

    public Set<Long> dirtyView() {
        return Collections.unmodifiableSet(this.dirtySections);
    }

    public boolean dirtyStateUnsaved() {
        return this.dirtyStateUnsaved;
    }

    public void dirtyStateSaved() {
        this.dirtyStateUnsaved = false;
    }

    @Override
    public String toString() {
        return "Arena[" + this.name + " in " + this.worldName + ' ' + this.bounds + ']';
    }
}
