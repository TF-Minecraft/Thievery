package net.tfminecraft.thievery.owner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

import org.bukkit.Location;
import org.bukkit.World;

import net.tfminecraft.thievery.door.LockState;

/**
 * The lock files each character owns, so the will screen lists them without reading the stores. The boot lock pass
 * fills it as the files stand after the pass; the data managers' saves and deletes and the guard's queued deletions
 * keep it current. It holds only owners marked as characters, the records an estate moves. Furniture locks live in
 * the furniture and are not held here.
 */
public final class OwnedLocks {

    private static final Pattern BLOCK = Pattern.compile("-?\\d{1,9}_-?\\d{1,9}_-?\\d{1,9}\\.json");
    private static final Pattern ENTITY = Pattern.compile("\\p{XDigit}{8}(-\\p{XDigit}{4}){3}-\\p{XDigit}{12}\\.json");

    /**
     * A held record, named by its file ({@code 1_64_-2.json} or {@code <entity>.json}). {@code state} is null for
     * doors. {@code world}, {@code kind} and {@code partner} (the file of a double chest's other half) are null until
     * the block is seen.
     */
    public record Lock(Path path, LockStores.Store store, UUID owner, LockState state, String world, String kind,
            String partner) {

        public String name() {
            return path.getFileName().toString();
        }
    }

    private static final Map<Path, Lock> LOCKS = new HashMap<>();
    private static final Map<UUID, Set<Path>> BY_OWNER = new HashMap<>();

    private OwnedLocks() {}

    public static synchronized void clear() {
        LOCKS.clear();
        BY_OWNER.clear();
    }

    /**
     * Records what a file now holds: kept when its stored owner is marked as a character, dropped otherwise.
     * {@code owner} is null for an unreadable record; {@code world} is where it was written, or null. A record whose
     * owner and state are unchanged keeps what was seen of its block, unless it was written in another world.
     */
    public static synchronized void put(LockStores.Store store, Path path, OwnerCodec.Stored owner, LockState state,
            String world) {
        Path key = key(path);
        Lock previous = LOCKS.get(key);
        Pattern names = store == LockStores.Store.ENTITIES ? ENTITY : BLOCK;
        if (owner == null || owner.marker() != LockOwner.Kind.CHARACTER
                || !names.matcher(key.getFileName().toString()).matches()) {
            remove(key);
            return;
        }
        if (previous != null && previous.store() == store && previous.owner().equals(owner.id())
                && previous.state() == state && (world == null || world.equals(previous.world()))) {
            return;
        }
        remove(key);
        LOCKS.put(key, new Lock(key, store, owner.id(), state, world, null, null));
        BY_OWNER.computeIfAbsent(owner.id(), id -> new TreeSet<>()).add(key);
    }

    /**
     * Indexes a file as it stands on disk after a save or delete, so a write or deletion that took effect before its
     * directory sync failed is still reflected, and a no-op save repairs a stale entry. A missing or unreadable file
     * is dropped, as it holds no character's lock.
     */
    public static void refresh(LockStores.Store store, Path path, String world) {
        try {
            LockMigration.Decoded record = LockMigration.decode(store, Files.readAllBytes(path));
            put(store, path, record.owner(), record.state(), world);
        } catch (IOException | IllegalArgumentException e) {
            remove(path);
        }
    }

    /** Drops a file the guard tried to delete, once it is gone, whether or not its directory sync failed. */
    public static void forgetIfGone(Path path) {
        if (!Files.exists(path)) {
            remove(path);
        }
    }

    public static synchronized void remove(Path path) {
        Lock lock = LOCKS.remove(key(path));
        if (lock != null) {
            Set<Path> owned = BY_OWNER.get(lock.owner());
            owned.remove(lock.path());
            if (owned.isEmpty()) {
                BY_OWNER.remove(lock.owner());
            }
        }
    }

    /** The character's records, in file order. */
    public static synchronized List<Lock> of(UUID owner) {
        List<Lock> locks = new ArrayList<>();
        for (Path path : BY_OWNER.getOrDefault(owner, Set.of())) {
            locks.add(LOCKS.get(path));
        }
        return locks;
    }

    /** Remembers where a block was seen, unless the record has changed since; returns the record as now held. */
    public static synchronized Lock seen(Lock lock, String world, String kind, String partner) {
        Lock seen = new Lock(lock.path(), lock.store(), lock.owner(), lock.state(), world, kind, partner);
        LOCKS.replace(lock.path(), lock, seen);
        return seen;
    }

    /** The name of a location's world, or null when it has none. */
    public static String world(Location location) {
        World world = location.getWorld();
        return world == null ? null : world.getName();
    }

    // Managers name files relative to the server directory and the lock pass through LockStores; both meet here.
    private static Path key(Path path) {
        return path.toAbsolutePath().normalize();
    }
}
