package net.tfminecraft.thievery.door;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.bukkit.Bukkit;
import org.bukkit.Location;

import net.tfminecraft.thievery.owner.DurableFiles;
import net.tfminecraft.thievery.owner.LockGuard;
import net.tfminecraft.thievery.owner.LockOwner;
import net.tfminecraft.thievery.owner.LockQuarantine;
import net.tfminecraft.thievery.owner.LockRecords;
import net.tfminecraft.thievery.owner.LockStores;
import net.tfminecraft.thievery.owner.OwnedLocks;
import net.tfminecraft.thievery.owner.OwnerCodec;

public class ContainerDataManager {

    /** Owner and state cached for hot paths such as hopper transfers. */
    public record CachedLock(LockOwner owner, LockState state) {}

    // Shared across instances; container files are keyed by block coordinates only.
    private static final Map<BlockKey, CachedLock> OWNER_CACHE = new ConcurrentHashMap<>();

    private final File dataFolder;

    public ContainerDataManager() {
        this.dataFolder = new File("plugins/Thievery/data");
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
    }

    private record BlockKey(int x, int y, int z) {
        static BlockKey of(Location location) {
            return new BlockKey(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        }
    }

    /** Clears the owner cache; the guard does this before it lifts. */
    public static void clearOwnerCache() {
        OWNER_CACHE.clear();
    }

    /** Cached owner lookup for hot paths such as hopper transfers. */
    public LockOwner getOwner(Location location) {
        return getLock(location).owner();
    }

    /** Cached owner and state; unreadable records come back as locked with an unresolved owner. */
    public CachedLock getLock(Location location) {
        BlockKey key = BlockKey.of(location);
        CachedLock cached = OWNER_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        ContainerData data = loadContainerData(location);
        CachedLock lock = new CachedLock(data.getOwner(), data.getLockState());
        if (!data.isProvisional()) {
            OWNER_CACHE.put(key, lock);
        }
        return lock;
    }

    public boolean deleteContainerData(Location location) {
        File file = getFileForLocation(location);
        if (LockGuard.guarded()) {
            LockGuard.queueDeletion(file.toPath());
            return false;
        }
        OWNER_CACHE.remove(BlockKey.of(location));
        try {
            boolean deleted = DurableFiles.delete(file.toPath());
            OwnedLocks.remove(file.toPath());
            return deleted;
        } catch (IOException e) {
            Bukkit.getLogger().warning("Failed to delete container data for " + location + ": " + e.getMessage());
            return false;
        }
    }

    public ContainerData loadContainerData(Location location) {
        File file = getFileForLocation(location);
        if (!file.exists()) {
            return new ContainerData(location); // fresh
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file.toPath());
        } catch (IOException e) {
            return unreadable(location, file, e, new byte[0]);
        }
        LockRecords.Container record;
        try {
            record = LockRecords.decodeContainer(bytes);
        } catch (IllegalArgumentException e) {
            return unreadable(location, file, e, bytes);
        }
        OwnerCodec.Resolved owner = OwnerCodec.resolve(record.owner());
        ContainerData data = new ContainerData(location, owner.owner());
        data.setProvisional(!owner.definitive());
        data.setAccessMap(record.accessMap());
        data.setLockState(record.state());
        return data;
    }

    // Locked with an unresolved owner; thief cooldowns are kept when they can be read, so staff fixes keep them.
    private static ContainerData unreadable(Location location, File file, Exception e, byte[] bytes) {
        Bukkit.getLogger().warning("Unreadable container lock " + file + ": " + e.getMessage());
        LockQuarantine.noteUnreadable(file.toPath());
        ContainerData data = ContainerData.unreadable(location);
        data.setAccessMap(LockRecords.salvageAccessMap(bytes));
        return data;
    }

    /**
     * Writes the record when it has changed. Owner and state changes are forced to disk; cooldown-only changes are
     * not. Nothing is written in guard mode or for an unreadable record.
     */
    public void saveContainerData(ContainerData data) {
        if (LockGuard.guarded() || data.isUnreadable()) {
            return;
        }
        File file = getFileForLocation(data.getLocation());
        LockRecords.Container next = new LockRecords.Container(OwnerCodec.store(data.getOwner()),
                data.getLockState(), data.getAccessMap());
        try {
            LockRecords.Container previous = readExisting(file.toPath());
            if (next.equals(previous)) {
                return;
            }
            boolean ownership = previous == null || !Objects.equals(previous.owner(), next.owner())
                    || previous.state() != next.state();
            DurableFiles.write(file.toPath(), LockRecords.encodeContainer(next), ownership);
            OwnedLocks.put(LockStores.Store.CONTAINERS, file.toPath(), next.owner(), next.state(),
                    OwnedLocks.world(data.getLocation()));
            if (data.isProvisional()) {
                OWNER_CACHE.remove(BlockKey.of(data.getLocation()));
            } else {
                OWNER_CACHE.put(BlockKey.of(data.getLocation()), new CachedLock(data.getOwner(), data.getLockState()));
            }
        } catch (IOException e) {
            OWNER_CACHE.remove(BlockKey.of(data.getLocation()));
            Bukkit.getLogger().warning("Failed to save container data for " + data.getLocation() + ": " + e.getMessage());
        }
    }

    // The current record, or null when the file is missing or unreadable.
    private static LockRecords.Container readExisting(Path path) throws IOException {
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return LockRecords.decodeContainer(Files.readAllBytes(path));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public File getFileForLocation(Location loc) {
        String chunkFolder = (loc.getBlockX() >> 4) + "_" + (loc.getBlockZ() >> 4);
        String fileName = loc.getBlockX() + "_" + loc.getBlockY() + "_" + loc.getBlockZ() + ".json";
        return new File(new File(dataFolder, chunkFolder), fileName);
    }

    public void removePlayerFromAllAccessMaps(UUID playerId) {
        if (playerId == null) {
            return;
        }
        forEachContainerFile(file -> updateAccessMap(file, map -> map.remove(playerId) != null));
    }

    public void clearAllAccessMaps() {
        forEachContainerFile(file -> updateAccessMap(file, map -> {
            if (map.isEmpty()) {
                return false;
            }
            map.clear();
            return true;
        }));
    }

    private void forEachContainerFile(Consumer<File> action) {
        if (LockGuard.guarded() || !dataFolder.exists()) {
            return;
        }
        File[] chunkDirs = dataFolder.listFiles(File::isDirectory);
        if (chunkDirs == null) {
            return;
        }
        for (File chunkDir : chunkDirs) {
            File[] files = chunkDir.listFiles((dir, name) -> name.endsWith(".json"));
            if (files == null) {
                continue;
            }
            for (File file : files) {
                action.accept(file);
            }
        }
    }

    // Cooldown-only rewrites keep the owner exactly as stored and are not forced to disk.
    private void updateAccessMap(File file, Predicate<Map<UUID, String>> mutation) {
        try {
            LockRecords.Container record = LockRecords.decodeContainer(Files.readAllBytes(file.toPath()));
            Map<UUID, String> accessMap = new HashMap<>(record.accessMap());
            if (!mutation.test(accessMap)) {
                return;
            }
            DurableFiles.write(file.toPath(), LockRecords.encodeContainer(
                    new LockRecords.Container(record.owner(), record.state(), accessMap)), false);
        } catch (IOException | IllegalArgumentException e) {
            Bukkit.getLogger().warning("Failed to update container cooldown data for " + file + ": " + e.getMessage());
        }
    }
}
