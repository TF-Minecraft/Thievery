package net.tfminecraft.thievery.door;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.bukkit.Bukkit;

import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.owner.DurableFiles;
import net.tfminecraft.thievery.owner.LockGuard;
import net.tfminecraft.thievery.owner.LockQuarantine;
import net.tfminecraft.thievery.owner.LockRecords;
import net.tfminecraft.thievery.owner.LockStores;
import net.tfminecraft.thievery.owner.OwnedLocks;
import net.tfminecraft.thievery.owner.OwnerCodec;

public class EntityLockDataManager {

    private final File dataFolder;

    public EntityLockDataManager() {
        this.dataFolder = new File(Thievery.getInstance().getDataFolder(), "entities");
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
    }

    public EntityLockData load(UUID entityId) {
        if (entityId == null) {
            return new EntityLockData(null);
        }
        File file = getFile(entityId);
        if (!file.exists()) {
            return new EntityLockData(entityId);
        }
        try {
            LockRecords.Entity record = LockRecords.decodeEntity(Files.readAllBytes(file.toPath()));
            EntityLockData data = new EntityLockData(entityId, OwnerCodec.resolve(record.owner()).owner());
            data.setLockState(record.state());
            return data;
        } catch (IOException | IllegalArgumentException e) {
            Bukkit.getLogger().warning("Unreadable entity lock " + file + ": " + e.getMessage());
            LockQuarantine.noteUnreadable(file.toPath());
            return EntityLockData.unreadable(entityId);
        }
    }

    /** Writes the record when it has changed, forced to disk. Frozen in guard mode. */
    public void save(EntityLockData data) {
        if (data == null || data.getEntityId() == null || LockGuard.guarded() || data.isUnreadable()) {
            return;
        }
        Path path = getFile(data.getEntityId()).toPath();
        LockRecords.Entity next = new LockRecords.Entity(OwnerCodec.store(data.getOwner()), data.getLockState());
        try {
            if (Files.exists(path) && next.equals(decodeOrNull(Files.readAllBytes(path)))) {
                return;
            }
            DurableFiles.write(path, LockRecords.encodeEntity(next), true);
        } catch (IOException e) {
            Bukkit.getLogger().warning("Failed to save entity lock data for " + data.getEntityId() + ": " + e.getMessage());
        } finally {
            OwnedLocks.refresh(LockStores.Store.ENTITIES, path, null);
        }
    }

    /** Removes the record; in guard mode the deletion waits until the migration has finished. */
    public boolean delete(UUID entityId) {
        if (entityId == null) {
            return false;
        }
        Path path = getFile(entityId).toPath();
        if (LockGuard.guarded()) {
            LockGuard.queueDeletion(path);
            return false;
        }
        try {
            return DurableFiles.delete(path);
        } catch (IOException e) {
            Bukkit.getLogger().warning("Failed to delete entity lock data for " + entityId + ": " + e.getMessage());
            return false;
        } finally {
            OwnedLocks.refresh(LockStores.Store.ENTITIES, path, null);
        }
    }

    private static LockRecords.Entity decodeOrNull(byte[] bytes) {
        try {
            return LockRecords.decodeEntity(bytes);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public File getFile(UUID entityId) {
        return new File(dataFolder, entityId.toString() + ".json");
    }
}
