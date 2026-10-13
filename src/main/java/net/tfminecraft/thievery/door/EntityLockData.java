package net.tfminecraft.thievery.door;

import java.util.UUID;

import org.bukkit.entity.Player;

import net.tfminecraft.thievery.owner.LockOwner;

public class EntityLockData {

    private final UUID entityId;
    private LockOwner owner;
    private LockState lockState = LockState.DEFAULT;
    private boolean unreadable;

    public EntityLockData(UUID entityId) {
        this.entityId = entityId;
    }

    public EntityLockData(UUID entityId, LockOwner owner) {
        this.entityId = entityId;
        this.owner = owner;
    }

    /** A record that could not be read: locked, owner unresolved. */
    public static EntityLockData unreadable(UUID entityId) {
        EntityLockData data = new EntityLockData(entityId, LockOwner.unreadable());
        data.lockState = LockState.PRIVATE;
        data.unreadable = true;
        return data;
    }

    public UUID getEntityId() {
        return entityId;
    }

    public LockOwner getOwner() {
        return owner;
    }

    public void setOwner(LockOwner owner) {
        this.owner = owner;
        this.unreadable = false;
    }

    public boolean isUnreadable() {
        return unreadable;
    }

    public boolean canAccess(Player player) {
        return LockAccess.canAccess(player, owner, lockState);
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
}
