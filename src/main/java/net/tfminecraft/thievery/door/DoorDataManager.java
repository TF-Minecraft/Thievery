package net.tfminecraft.thievery.door;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import org.bukkit.Bukkit;
import org.bukkit.Location;

import net.tfminecraft.thievery.owner.DurableFiles;
import net.tfminecraft.thievery.owner.LockGuard;
import net.tfminecraft.thievery.owner.LockQuarantine;
import net.tfminecraft.thievery.owner.LockRecords;
import net.tfminecraft.thievery.owner.OwnerCodec;

public class DoorDataManager {

    private final File dataFolder;

    public DoorDataManager() {
        this.dataFolder = new File("plugins/Thievery/door-data");
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
    }

    /** The door's lock, null when it is unlocked. An unreadable record stays locked. */
    public DoorData loadDoorData(Location location) {
        File file = getFileForLocation(location);
        if (!file.exists()) return null;

        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file.toPath());
        } catch (IOException e) {
            Bukkit.getLogger().warning("Unreadable door lock " + file + ": " + e.getMessage());
            LockQuarantine.noteUnreadable(file.toPath());
            return DoorData.unreadable(location, null);
        }
        try {
            LockRecords.Door record = LockRecords.decodeDoor(bytes);
            DoorData data = new DoorData(location, record.key(), record.strength(),
                    OwnerCodec.resolve(record.owner()).owner());
            data.setUnlockExpiryMs(record.unlockExpiryMs());
            return data;
        } catch (IllegalArgumentException e) {
            Bukkit.getLogger().warning("Unreadable door lock " + file + ": " + e.getMessage());
            LockQuarantine.noteUnreadable(file.toPath());
            return DoorData.unreadable(location, LockRecords.salvageDoorKey(bytes));
        }
    }

    /** Writes the record when it has changed; owner changes are forced to disk. Frozen in guard mode. */
    public void saveDoorData(DoorData data) {
        if (LockGuard.guarded() || data.isUnreadable()) {
            return;
        }
        Path path = getFileForLocation(data.getLocation()).toPath();
        LockRecords.Door next = new LockRecords.Door(data.getKey(), data.getStrength(),
                OwnerCodec.store(data.getOwner()), data.getUnlockExpiryMs());
        try {
            LockRecords.Door previous = readExisting(path);
            if (next.equals(previous)) {
                return;
            }
            boolean ownership = previous == null || !Objects.equals(previous.owner(), next.owner())
                    || !Objects.equals(previous.key(), next.key());
            DurableFiles.write(path, LockRecords.encodeDoor(next), ownership);
        } catch (IOException e) {
            Bukkit.getLogger().warning("Failed to save door data for " + data.getLocation() + ": " + e.getMessage());
        }
    }

    public boolean deleteDoorData(Location location) {
        Path path = getFileForLocation(location).toPath();
        if (LockGuard.guarded()) {
            LockGuard.queueDeletion(path);
            return false;
        }
        try {
            return DurableFiles.delete(path);
        } catch (IOException e) {
            Bukkit.getLogger().warning("Failed to delete door data for " + location + ": " + e.getMessage());
            return false;
        }
    }

    private static LockRecords.Door readExisting(Path path) throws IOException {
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return LockRecords.decodeDoor(Files.readAllBytes(path));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private File getFileForLocation(Location loc) {
        String chunkFolder = (loc.getBlockX() >> 4) + "_" + (loc.getBlockZ() >> 4);
        String fileName = loc.getBlockX() + "_" + loc.getBlockY() + "_" + loc.getBlockZ() + ".json";
        return new File(new File(dataFolder, chunkFolder), fileName);
    }
}
