package net.tfminecraft.thievery.owner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Logger;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Carries what {@link OwnedLocks} has seen of locked blocks across restarts, so the will screen names a chest as a
 * chest before its chunk loads again. The lock stores keep no block kind or world, so this sidecar is only a cache:
 * entries are keyed by store file name ({@code data/0_0/1_64_2.json}), applied only to records the boot pass indexed
 * in the same store, and never touch ownership. A missing file is a first start; an unreadable one is logged once and
 * ignored.
 *
 * <pre>{"version":1,"locks":{"data/0_0/1_64_2.json":{"world":"world","kind":"Chest","partner":"2_64_2.json"}}}</pre>
 */
public final class LockKindFile {

    public static final String FILE = "owned-lock-kinds.json";
    static final int VERSION = 1;

    private final LockStores stores;
    private final Path file;
    private final Logger log;
    // The index revision on disk, so an unchanged index is never rewritten.
    private long written = Long.MIN_VALUE;

    public LockKindFile(LockStores stores, Path file, Logger log) {
        this.stores = stores;
        this.file = file;
        this.log = log;
    }

    /** Applies the remembered sightings; call once the boot lock pass has filled the index. */
    public void load() {
        try {
            if (Files.exists(file)) {
                JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                if (root.get("version").getAsInt() != VERSION) {
                    throw new IllegalArgumentException("unknown version " + root.get("version"));
                }
                for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("locks").entrySet()) {
                    LockStores.StoreFile store = stores.resolve(entry.getKey());
                    JsonObject seen = entry.getValue().getAsJsonObject();
                    JsonElement partner = seen.get("partner");
                    OwnedLocks.remember(store.store(), store.path(), seen.get("world").getAsString(),
                            seen.get("kind").getAsString(), partner == null ? null : partner.getAsString());
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warning("Remembered lock kinds in " + file + " could not be read, so some locks read as containers"
                    + " or doors until their chunks load: " + e);
        }
        written = OwnedLocks.revision();
    }

    /** Writes the held sightings when they have changed since the last write; keys no longer held are dropped. */
    public void saveIfChanged() {
        long revision = OwnedLocks.revision();
        if (revision == written) {
            return;
        }
        JsonObject locks = new JsonObject();
        for (OwnedLocks.Lock lock : OwnedLocks.sighted()) {
            JsonObject seen = new JsonObject();
            seen.addProperty("world", lock.world());
            seen.addProperty("kind", lock.kind());
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
            written = revision;
        } catch (IOException e) {
            log.warning("Could not save remembered lock kinds to " + file + ": " + e.getMessage());
        }
    }
}
