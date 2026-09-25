package net.tfminecraft.thievery.door;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;

import com.google.gson.Gson;

import net.tfminecraft.thievery.door.ContainerData;
import net.tfminecraft.thievery.door.LockState;

public class ContainerDataManager {

    // Shared across instances; container files are keyed by block coordinates only.
    private static final Map<BlockKey, Optional<UUID>> OWNER_CACHE = new ConcurrentHashMap<>();

    private final File dataFolder;
    private final Gson gson = new Gson();

    private static class ContainerDataJson {
        UUID owner;
        LockState lockState = LockState.DEFAULT;
        Map<UUID, String> accessMap = new HashMap<>();
    }

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

    /** Cached owner lookup for hot paths such as hopper transfers. */
    public UUID getOwner(Location location) {
        BlockKey key = BlockKey.of(location);
        Optional<UUID> cached = OWNER_CACHE.get(key);
        if (cached != null) {
            return cached.orElse(null);
        }
        File file = getFileForLocation(location);
        if (!file.exists()) {
            OWNER_CACHE.putIfAbsent(key, Optional.empty());
            return null;
        }
        try (Reader reader = new FileReader(file)) {
            ContainerDataJson json = gson.fromJson(reader, ContainerDataJson.class);
            UUID owner = json == null ? null : json.owner;
            OWNER_CACHE.putIfAbsent(key, Optional.ofNullable(owner));
            return owner;
        } catch (IOException e) {
            // Leave uncached so the next lookup retries the read.
            Bukkit.getLogger().warning("Failed to load container owner for " + location + ": " + e.getMessage());
            return null;
        }
    }

    public boolean deleteContainerData(Location location) {
        OWNER_CACHE.remove(BlockKey.of(location));
        File file = getFileForLocation(location);
        return file.exists() && file.delete();
    }

    public ContainerData loadContainerData(Location location) {
        File file = getFileForLocation(location);
        if (!file.exists()) {
            return new ContainerData(location); // fresh
        }

        try (Reader reader = new FileReader(file)) {
            ContainerDataJson json = gson.fromJson(reader, ContainerDataJson.class);

            ContainerData data = new ContainerData(location);
            data.setOwner(json.owner);
            data.setAccessMap(json.accessMap);
            data.setLockState(json.lockState);
            return data;
        } catch (IOException e) {
            Bukkit.getLogger().warning("Failed to load container data for " + location + ": " + e.getMessage());
            return new ContainerData(location);
        }
    }

    public void saveContainerData(ContainerData data) {
        File file = getFileForLocation(data.getLocation());
        File parent = file.getParentFile();
        if (!parent.exists()) parent.mkdirs();

        try (Writer writer = new FileWriter(file)) {
            ContainerDataJson json = new ContainerDataJson();
            json.owner = data.getOwner();
            json.lockState = data.getLockState();
            json.accessMap = data.getAccessMap();
            gson.toJson(json, writer);
            OWNER_CACHE.put(BlockKey.of(data.getLocation()), Optional.ofNullable(data.getOwner()));
        } catch (IOException e) {
            OWNER_CACHE.remove(BlockKey.of(data.getLocation()));
            Bukkit.getLogger().warning("Failed to save container data for " + data.getLocation() + ": " + e.getMessage());
        }
    }

    private File getFileForLocation(Location loc) {
        Chunk chunk = loc.getChunk();
        String chunkFolder = chunk.getX() + "_" + chunk.getZ();
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

    private interface AccessMapMutation {
        boolean apply(java.util.Map<UUID, String> accessMap);
    }

    private void forEachContainerFile(java.util.function.Consumer<File> action) {
        if (!dataFolder.exists()) {
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

    private void updateAccessMap(File file, AccessMapMutation mutation) {
        try (Reader reader = new FileReader(file)) {
            ContainerDataJson json = gson.fromJson(reader, ContainerDataJson.class);
            if (json == null) {
                return;
            }
            if (json.accessMap == null) {
                json.accessMap = new HashMap<>();
            }
            if (!mutation.apply(json.accessMap)) {
                return;
            }
            try (Writer writer = new FileWriter(file)) {
                gson.toJson(json, writer);
            }
        } catch (IOException e) {
            Bukkit.getLogger().warning("Failed to update container cooldown data for " + file + ": " + e.getMessage());
        }
    }
}
