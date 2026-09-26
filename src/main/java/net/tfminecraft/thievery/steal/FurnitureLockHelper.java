package net.tfminecraft.thievery.steal;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.bukkit.entity.Player;

import net.tfminecraft.interactiblefurniture.InteractibleFurniture;
import net.tfminecraft.interactiblefurniture.furniture.Furniture;
import net.tfminecraft.thievery.cache.Parameters;
import net.tfminecraft.thievery.door.LockAccess;
import net.tfminecraft.thievery.door.LockState;

public final class FurnitureLockHelper {

    public static final String OWNER_KEY = "thievery.owner";
    public static final String LOCK_STATE_KEY = "thievery.lockState";

    private FurnitureLockHelper() {}

    public static boolean isLockable(Furniture furniture) {
        return furniture != null && Parameters.isLockableFurnitureId(furniture.getId());
    }

    public static UUID getOwner(Furniture furniture) {
        if (furniture == null) {
            return null;
        }
        Object raw = furniture.getVariables().get(OWNER_KEY);
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw.toString());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public static void setOwner(Furniture furniture, UUID owner) {
        if (furniture == null) {
            return;
        }
        Map<String, Object> variables = furniture.getVariables();
        if (owner == null) {
            variables.remove(OWNER_KEY);
        } else {
            variables.put(OWNER_KEY, owner.toString());
        }
        persist(furniture);
    }

    public static LockState getLockState(Furniture furniture) {
        if (furniture == null) {
            return LockState.DEFAULT;
        }
        Object raw = furniture.getVariables().get(LOCK_STATE_KEY);
        if (raw == null) {
            return LockState.DEFAULT;
        }
        try {
            return LockState.valueOf(raw.toString().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return LockState.DEFAULT;
        }
    }

    public static void setLockState(Furniture furniture, LockState lockState) {
        if (furniture == null) {
            return;
        }
        LockState state = lockState == null ? LockState.DEFAULT : lockState;
        furniture.getVariables().put(LOCK_STATE_KEY, state.name());
        persist(furniture);
    }

    public static LockState rotateLockState(Furniture furniture) {
        LockState next = getLockState(furniture).next();
        setLockState(furniture, next);
        return next;
    }

    public static boolean owns(Furniture furniture, Player player) {
        UUID owner = getOwner(furniture);
        return owner != null && player != null && owner.equals(player.getUniqueId());
    }

    public static boolean canAccess(Furniture furniture, Player player) {
        return LockAccess.canAccess(player, getOwner(furniture), getLockState(furniture));
    }

    private static void persist(Furniture furniture) {
        InteractibleFurniture.getInstance().getFurnitureManager().persistFurniture(furniture);
    }
}
