package net.tfminecraft.thievery.door;

import java.util.Locale;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import net.tfminecraft.coreprotect.CoreProtectAPI;
import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.cache.Cache;

// Records lock state changes in CoreProtect so staff can see who changed a lock, to what and when.
public final class LockStateLog {

    // CoreProtectAPI.logLockChange was added in API version 14.
    static final int LOCK_CHANGE_API_VERSION = 14;

    private LockStateLog() {}

    public static void record(Player player, Location location, LockState lockState, boolean staffOverride) {
        if (!Cache.coreProtect || player == null || location == null || lockState == null) {
            return;
        }
        CoreProtectAPI coreProtect = Thievery.getCoreProtect();
        if (coreProtect == null || coreProtect.APIVersion() < LOCK_CHANGE_API_VERSION) {
            return;
        }
        coreProtect.logLockChange(player.getName(), location, displayName(lockState), staffOverride);
    }

    static String displayName(LockState lockState) {
        String value = lockState.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
