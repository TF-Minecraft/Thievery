package net.tfminecraft.thievery.owner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 *
 * <p>What is seen of a block (its world, kind and double-chest partner) is kept per record and by chunk, so a chunk
 * load can look at its locks; {@link LockKindFile} carries it across restarts. It never affects ownership.
 */
public final class OwnedLocks {

    private static final Pattern BLOCK = Pattern.compile("-?\\d{1,9}_-?\\d{1,9}_-?\\d{1,9}\\.json");
    private static final Pattern ENTITY = Pattern.compile("\\p{XDigit}{8}(-\\p{XDigit}{4}){3}-\\p{XDigit}{12}\\.json");

    /**
     * A held record, named by its file ({@code 1_64_-2.json} or {@code <entity>.json}). {@code state} is null for
     * doors. {@code world}, {@code kind} and {@code partner} (the file of a double chest's other half) are null until
     * the block is seen. A lock file has no world: {@code bound} says the world came from a save or delete at the
     * block, so only that world can hold it; otherwise {@code world} is only where the block was last seen to fit.
     */
    public record Lock(Path path, LockStores.Store store, UUID owner, LockState state, String world, boolean bound,
            String kind, String partner) {

        public String name() {
            return path.getFileName().toString();
        }
    }

    private static final Map<Path, Lock> LOCKS = new HashMap<>();
    private static final Map<UUID, Set<Path>> BY_OWNER = new HashMap<>();
    private static final Map<Long, Set<Path>> BY_CHUNK = new HashMap<>();
    // Bumped whenever what has been seen of the held blocks changes, so the kind file is written only then.
    private static long revision;

    private OwnedLocks() {}

    public static synchronized void clear() {
        LOCKS.clear();
        BY_OWNER.clear();
        BY_CHUNK.clear();
        revision++;
    }

    /**
     * Records what a file now holds: kept when its stored owner is marked as a character, dropped otherwise.
     * {@code owner} is null for an unreadable record; {@code world} is where it was written, or null. The same block
     * keeps what was seen of it through owner and state changes, unless the record was written in another world.
     */
    public static synchronized void put(LockStores.Store store, Path path, OwnerCodec.Stored owner, LockState state,
            String world) {
        Path key = key(path);
        Lock previous = LOCKS.get(key);
        Pattern names = store == LockStores.Store.ENTITIES ? ENTITY : BLOCK;
        if (owner == null || owner.marker() != LockOwner.Kind.CHARACTER
                || !names.matcher(key.getFileName().toString()).matches()) {
            replace(key, null);
            return;
        }
        boolean sameBlock = previous != null && previous.store() == store
                && (world == null || world.equals(previous.world()));
        replace(key, sameBlock
                ? new Lock(key, store, owner.id(), state, previous.world(), previous.bound() || world != null,
                        previous.kind(), previous.partner())
                : new Lock(key, store, owner.id(), state, world, world != null, null, null));
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
        replace(key(path), null);
    }

    // The one place the maps change; next is null to drop the record.
    private static void replace(Path key, Lock next) {
        Lock previous = next == null ? LOCKS.remove(key) : LOCKS.put(key, next);
        if (previous != null) {
            unlink(BY_OWNER, previous.owner(), key);
            if (previous.store() != LockStores.Store.ENTITIES) {
                unlink(BY_CHUNK, chunk(previous.name()), key);
            }
        }
        if (next != null) {
            BY_OWNER.computeIfAbsent(next.owner(), id -> new TreeSet<>()).add(key);
            if (next.store() != LockStores.Store.ENTITIES) {
                BY_CHUNK.computeIfAbsent(chunk(next.name()), id -> new TreeSet<>()).add(key);
            }
        }
        if (!Objects.equals(sighting(previous), sighting(next))) {
            revision++;
        }
        // Only a removed record, or one whose seen block is gone or replaced, invalidates its neighbours' references;
        // restoring or rewriting a record whose kind was never seen leaves them alone.
        if (previous != null && previous.store() == LockStores.Store.CONTAINERS
                && (next == null || previous.kind() != null && next.kind() == null)) {
            forgetPartner(previous);
        }
    }

    // A half that named a removed or replaced record as its partner is single again, so a lock later placed there is
    // never taken for its other half.
    private static void forgetPartner(Lock gone) {
        String[] at = gone.name().substring(0, gone.name().length() - ".json".length()).split("_");
        int x = Integer.parseInt(at[0]);
        int z = Integer.parseInt(at[2]);
        Path store = gone.path().getParent().getParent();
        for (int[] side : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            int nx = x + side[0];
            int nz = z + side[1];
            Path key = store.resolve((nx >> 4) + "_" + (nz >> 4)).resolve(nx + "_" + at[1] + "_" + nz + ".json");
            Lock half = LOCKS.get(key);
            if (half != null && gone.name().equals(half.partner())) {
                replace(key, new Lock(key, half.store(), half.owner(), half.state(), half.world(), half.bound(),
                        half.kind(), null));
            }
        }
    }

    private static <K> void unlink(Map<K, Set<Path>> index, K group, Path key) {
        Set<Path> paths = index.get(group);
        paths.remove(key);
        if (paths.isEmpty()) {
            index.remove(group);
        }
    }

    // What the kind file keeps of a record, or null when its block has not been seen and its world is not bound.
    private static List<Object> sighting(Lock lock) {
        return lock == null || !known(lock) ? null
                : Arrays.asList(lock.store(), lock.world(), lock.bound(), lock.kind(), lock.partner());
    }

    private static boolean known(Lock lock) {
        return lock.kind() != null || lock.bound();
    }

    // A block file's chunk, from its x_y_z name; its folder is named the same way.
    private static long chunk(String name) {
        String[] parts = name.substring(0, name.length() - ".json".length()).split("_");
        return chunkKey(Integer.parseInt(parts[0]) >> 4, Integer.parseInt(parts[2]) >> 4);
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xffffffffL);
    }

    /** The block records in a chunk, in file order; a chunk without locks costs one map lookup. */
    public static synchronized List<Lock> inChunk(int chunkX, int chunkZ) {
        Set<Path> paths = BY_CHUNK.get(chunkKey(chunkX, chunkZ));
        if (paths == null) {
            return List.of();
        }
        List<Lock> locks = new ArrayList<>();
        for (Path path : paths) {
            locks.add(LOCKS.get(path));
        }
        return locks;
    }

    /**
     * Every record whose block has been seen or whose world is bound, unordered; a cheap copy of immutable records.
     */
    public static synchronized List<Lock> sighted() {
        List<Lock> locks = new ArrayList<>();
        for (Lock lock : LOCKS.values()) {
            if (known(lock)) {
                locks.add(lock);
            }
        }
        return locks;
    }

    /** A counter that changes whenever {@link #sighted()} would. */
    public static synchronized long revision() {
        return revision;
    }

    /** Applies a remembered sighting to a record the index still holds in the same store; others are ignored. */
    public static synchronized void remember(LockStores.Store store, Path path, String world, boolean bound,
            String kind, String partner) {
        Lock lock = LOCKS.get(key(path));
        if (lock != null && lock.store() == store) {
            replace(lock.path(), new Lock(lock.path(), store, lock.owner(), lock.state(), world, bound, kind, partner));
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
        Lock seen = new Lock(lock.path(), lock.store(), lock.owner(), lock.state(), world, lock.bound(), kind,
                partner);
        if (lock.equals(LOCKS.get(lock.path()))) {
            replace(lock.path(), seen);
        }
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
