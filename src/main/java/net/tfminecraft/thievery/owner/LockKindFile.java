package net.tfminecraft.thievery.owner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Carries what {@link OwnedLocks} has seen of locked blocks across restarts, so the will screen names a chest as a
 * chest before its chunk loads again. The lock stores keep no block kind or world, so this sidecar is only a cache:
 * entries are keyed by store file name ({@code data/0_0/1_64_2.json}), applied only to records the boot pass indexed
 * in the same store, and never touch ownership. {@code bound} marks a world that came from a save at the block, kept
 * even before the block's kind is known; any other world is a sighting that later looks may correct. A missing file
 * is a first start; an unreadable one is logged once and ignored.
 *
 * <pre>{"version":1,"locks":{"data/0_0/1_64_2.json":{"world":"world","kind":"Chest","partner":"2_64_2.json"}}}</pre>
 *
 * <p>The main thread only copies the held records; sorting, serialising and writing run on {@code background}, one
 * write at a time, and a write older than the last one written is dropped.
 */
public final class LockKindFile {

    public static final String FILE = "owned-lock-kinds.json";
    static final int VERSION = 1;

    private record Snapshot(long revision, List<OwnedLocks.Lock> locks) {}

    private final LockStores stores;
    private final Path file;
    private final Logger log;
    private final Executor background;
    // The index revision last handed to a write; reset when a write fails so the next save tries again.
    private volatile long queued = Long.MIN_VALUE;
    // The index revision on disk, guarded by this.
    private long written = Long.MIN_VALUE;

    public LockKindFile(LockStores stores, Path file, Logger log, Executor background) {
        this.stores = stores;
        this.file = file;
        this.log = log;
        this.background = background;
    }

    /** Applies the remembered sightings; call once the boot lock pass has filled the index. */
    public synchronized void load() {
        try {
            if (Files.exists(file)) {
                JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                if (root.get("version").getAsInt() != VERSION) {
                    throw new IllegalArgumentException("unknown version " + root.get("version"));
                }
                for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("locks").entrySet()) {
                    LockStores.StoreFile store = stores.resolve(entry.getKey());
                    JsonObject seen = entry.getValue().getAsJsonObject();
                    JsonElement bound = seen.get("bound");
                    OwnedLocks.remember(store.store(), store.path(), seen.get("world").getAsString(),
                            bound != null && bound.getAsBoolean(), text(seen, "kind"), text(seen, "partner"));
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warning("Remembered lock kinds in " + file + " could not be read, so some locks read as containers"
                    + " or doors until their chunks load: " + e);
        }
        written = OwnedLocks.revision();
        queued = written;
    }

    /** On the main thread: hands a copy of the held sightings to a background write when they have changed. */
    public void saveIfChanged() {
        Snapshot snapshot = snapshot();
        if (snapshot != null) {
            background.execute(() -> write(snapshot));
        }
    }

    /**
     * On disable: waits for a background write still running, then writes the index unless exactly it is on disk. A
     * snapshot queued but not yet run counts as unwritten, as the scheduler drops it at disable.
     */
    public synchronized void saveNow() {
        long revision = OwnedLocks.revision();
        if (revision != written) {
            queued = revision;
            write(new Snapshot(revision, OwnedLocks.sighted()));
        }
    }

    private static String text(JsonObject json, String field) {
        JsonElement value = json.get(field);
        return value == null ? null : value.getAsString();
    }

    private Snapshot snapshot() {
        long revision = OwnedLocks.revision();
        if (revision == queued) {
            return null;
        }
        queued = revision;
        return new Snapshot(revision, OwnedLocks.sighted());
    }

    // Keys no longer held are dropped, as only held records are copied.
    private synchronized void write(Snapshot snapshot) {
        if (snapshot.revision() <= written) {
            return;
        }
        JsonObject locks = new JsonObject();
        for (OwnedLocks.Lock lock : snapshot.locks().stream().sorted(Comparator.comparing(OwnedLocks.Lock::path))
                .toList()) {
            JsonObject seen = new JsonObject();
            seen.addProperty("world", lock.world());
            if (lock.bound()) {
                seen.addProperty("bound", true);
            }
            if (lock.kind() != null) {
                seen.addProperty("kind", lock.kind());
            }
            if (lock.partner() != null) {
                seen.addProperty("partner", lock.partner());
            }
            locks.add(stores.name(lock.store(), lock.path()), seen);
        }
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        root.add("locks", locks);
        try {
            // A cache: losing the last minute in a crash only costs a look when the chunk loads, so no fsync.
            DurableFiles.write(file, new GsonBuilder().setPrettyPrinting().create().toJson(root)
                    .getBytes(StandardCharsets.UTF_8), false);
            written = snapshot.revision();
        } catch (IOException e) {
            queued = Long.MIN_VALUE;
            log.warning("Could not save remembered lock kinds to " + file + ": " + e.getMessage());
        }
    }
}
