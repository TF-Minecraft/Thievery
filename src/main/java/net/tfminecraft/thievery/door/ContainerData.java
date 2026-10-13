package net.tfminecraft.thievery.door;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import net.tfminecraft.thievery.owner.LockOwner;

public class ContainerData {

    private Location location;
    private LockOwner owner;
    private LockState lockState = LockState.DEFAULT;
    private Map<UUID, String> accessMap = new HashMap<>();
    // Unreadable records load locked and are never written back; only staff can replace them.
    private boolean unreadable;
    // A classification made while identity was unavailable; it must not be cached or acted on as final.
    private boolean provisional;

    public ContainerData(Location location) {
        this.location = location;
    }

    public ContainerData(Location location, LockOwner owner) {
        this.location = location;
        this.owner = owner;
    }

    /** A record that could not be read: locked, owner unresolved. */
    public static ContainerData unreadable(Location location) {
        ContainerData data = new ContainerData(location, LockOwner.unreadable());
        data.lockState = LockState.PRIVATE;
        data.unreadable = true;
        return data;
    }

    public Location getLocation() {
        return location;
    }

    public LockOwner getOwner() {
        return owner;
    }

    public boolean isOwned() {
        return owner != null;
    }

    public boolean canAccess(Player p) {
        return LockAccess.canAccess(p, owner, lockState);
    }

    public void setOwner(LockOwner owner) {
        this.owner = owner;
        this.unreadable = false;
        this.provisional = false;
    }

    public boolean isUnreadable() {
        return unreadable;
    }

    public boolean isProvisional() {
        return provisional;
    }

    void setProvisional(boolean provisional) {
        this.provisional = provisional;
    }

    public LockState getLockState() {
        return lockState;
    }

    public void setLockState(LockState lockState) {
        this.lockState = lockState == null ? LockState.DEFAULT : lockState;
    }

    public LockState rotateLockState() {
        lockState = lockState.next();
        return lockState;
    }

    public void updateAccess(UUID playerUUID, String date) {
        accessMap.put(playerUUID, date);
    }

    public String getLastAccess(UUID playerUUID) {
        return accessMap.get(playerUUID);
    }

    public Map<UUID, String> getAccessMap() {
        return accessMap;
    }

    public void setAccessMap(Map<UUID, String> accessMap) {
        this.accessMap = accessMap;
    }
}
