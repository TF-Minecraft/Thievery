package net.tfminecraft.thievery.door;

import java.util.Locale;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import net.tfminecraft.coreprotect.CoreProtectAPI;
import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.cache.Cache;

// Records lock state changes as CoreProtect interactions so staff can see who changed a lock and
// when. The new state is part of the user name, following the "<player>_lockpick" convention.
public final class LockStateLog {

    private LockStateLog() {}

    public static void record(Player player, Location location, LockState lockState, boolean staffOverride) {
        if (!Cache.coreProtect || player == null || location == null || lockState == null) {
            return;
        }
        CoreProtectAPI coreProtect = Thievery.getCoreProtect();
        if (coreProtect == null) {
            return;
        }
        coreProtect.logInteraction(user(player.getName(), lockState, staffOverride), location);
    }

    static String user(String playerName, LockState lockState, boolean staffOverride) {
        return playerName + (staffOverride ? "_staff" : "") + "_lock_" + lockState.name().toLowerCase(Locale.ROOT);
    }
}
