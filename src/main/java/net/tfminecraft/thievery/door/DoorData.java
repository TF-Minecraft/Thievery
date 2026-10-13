package net.tfminecraft.thievery.door;

import org.bukkit.Location;

import net.tfminecraft.thievery.owner.LockOwner;

public class DoorData {
    private Location location;
    private String key;
    private double strength;
    private LockOwner owner;
    private Long unlockExpiryMs;
    private boolean unreadable;

    public DoorData(Location location, String key, double strength, LockOwner owner) {
        this.location = location;
        this.key = key;
        this.strength = strength;
        this.owner = owner;
    }

    /** A record that could not be read: still locked, owner unresolved; the key survives when it can be read. */
    public static DoorData unreadable(Location location, String key) {
        DoorData data = new DoorData(location, key, 0.0, LockOwner.unreadable());
        data.unreadable = true;
        return data;
    }

    public Location getLocation() {
        return location;
    }

    public String getKey() {
        return key;
    }

    public double getStrength() {
        return strength;
    }

    public LockOwner getOwner() {
        return owner;
    }

    /** Staff only: {@code /thievery locks setowner}. */
    public void setOwner(LockOwner owner) {
        this.owner = owner;
        this.unreadable = false;
    }

    public boolean isUnreadable() {
        return unreadable;
    }

    public Long getUnlockExpiryMs() {
        return unlockExpiryMs;
    }

    public void setUnlockExpiryMs(Long unlockExpiryMs) {
        this.unlockExpiryMs = unlockExpiryMs;
    }
}
