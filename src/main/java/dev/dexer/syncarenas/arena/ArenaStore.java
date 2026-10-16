package dev.dexer.syncarenas.arena;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reads and writes the per arena {@code .json} metadata files, plus the paths of the {@code .sa}
 * snapshots. All of this is file I/O and is only ever called from the async scheduler (or from
 * {@code onEnable}/{@code onDisable}).
 */
public final class ArenaStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path arenaDir;
    private final Path snapshotDir;

    public ArenaStore(final Path dataFolder) {
        this.arenaDir = dataFolder.resolve("arenas");
        this.snapshotDir = dataFolder.resolve("snapshots");
    }

    public void createDirectories() throws IOException {
        Files.createDirectories(this.arenaDir);
        Files.createDirectories(this.snapshotDir);
    }

    public Path metadataPath(final String name) {
        return this.arenaDir.resolve(name + ".json");
    }

    public Path snapshotPath(final String name) {
        return this.snapshotDir.resolve(name + ".sa");
    }

    /** Loads every arena definition; unreadable files are reported and skipped. */
    public List<Arena> loadAll(final List<String> failures) throws IOException {
        final List<Arena> loaded = new ArrayList<>();
        if (!Files.isDirectory(this.arenaDir)) {
            return loaded;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(this.arenaDir, "*.json")) {
            for (final Path path : stream) {
                try {
                    loaded.add(this.read(path));
                } catch (final Exception ex) {
                    failures.add(path.getFileName() + ": " + ex.getMessage());
                }
            }
        }
        return loaded;
    }

    private Arena read(final Path path) throws IOException {
        final JsonObject root;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            final JsonElement element = JsonParser.parseReader(reader);
            if (!element.isJsonObject()) {
                throw new IOException("not a json object");
            }
            root = element.getAsJsonObject();
        }
        final String name = root.get("name").getAsString();
        final String worldName = root.get("world").getAsString();
        UUID worldId = null;
        if (root.has("worldId") && !root.get("worldId").isJsonNull()) {
            try {
                worldId = UUID.fromString(root.get("worldId").getAsString());
            } catch (final IllegalArgumentException ignored) {
                worldId = null;
            }
        }
        final JsonObject min = root.getAsJsonObject("min");
        final JsonObject max = root.getAsJsonObject("max");
        final ArenaBounds bounds = new ArenaBounds(
            min.get("x").getAsInt(), min.get("y").getAsInt(), min.get("z").getAsInt(),
            max.get("x").getAsInt(), max.get("y").getAsInt(), max.get("z").getAsInt());
        final long createdAt = root.has("createdAt") ? root.get("createdAt").getAsLong() : 0L;

        final Arena arena = new Arena(name, worldName, worldId, bounds, createdAt);
        if (root.has("snapshotSections")) {
            arena.snapshotSections(root.get("snapshotSections").getAsInt());
        }
        if (root.has("snapshotBytes")) {
            arena.snapshotBytes(root.get("snapshotBytes").getAsLong());
        }
        if (root.has("lastReset")) {
            arena.lastReset(root.get("lastReset").getAsLong());
        }
        if (root.has("dirtySections")) {
            final JsonArray dirty = root.getAsJsonArray("dirtySections");
            for (final JsonElement entry : dirty) {
                arena.markSection(entry.getAsLong());
            }
        }
        arena.dirtyStateSaved();
        return arena;
    }

    /** Writes the arena definition atomically. */
    public void save(final Arena arena) throws IOException {
        Files.createDirectories(this.arenaDir);
        final JsonObject root = new JsonObject();
        root.addProperty("name", arena.name());
        root.addProperty("world", arena.worldName());
        root.addProperty("worldId", arena.worldId() == null ? null : arena.worldId().toString());
        root.add("min", position(arena.bounds().minX(), arena.bounds().minY(), arena.bounds().minZ()));
        root.add("max", position(arena.bounds().maxX(), arena.bounds().maxY(), arena.bounds().maxZ()));
        root.addProperty("snapshot", this.snapshotPath(arena.name()).getFileName().toString());
        root.addProperty("snapshotSections", arena.snapshotSections());
        root.addProperty("snapshotBytes", arena.snapshotBytes());
        root.addProperty("blocks", arena.bounds().blockCount());
        root.addProperty("createdAt", arena.createdAt());
        root.addProperty("lastReset", arena.lastReset());
        final JsonArray dirty = new JsonArray();
        for (final Long section : arena.dirtyView()) {
            dirty.add(section);
        }
        root.add("dirtySections", dirty);

        final Path target = this.metadataPath(arena.name());
        final Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        }
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final IOException ex) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        arena.dirtyStateSaved();
    }

    /** Removes both the metadata and the snapshot of an arena. */
    public void delete(final String name) throws IOException {
        Files.deleteIfExists(this.metadataPath(name));
        Files.deleteIfExists(this.snapshotPath(name));
    }

    private static JsonObject position(final int x, final int y, final int z) {
        final JsonObject object = new JsonObject();
        object.addProperty("x", x);
        object.addProperty("y", y);
        object.addProperty("z", z);
        return object;
    }
}
