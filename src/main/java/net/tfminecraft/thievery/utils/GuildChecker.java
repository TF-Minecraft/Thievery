package net.tfminecraft.thievery.utils;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import net.tfminecraft.simplefactions.guild.Guild;
import net.tfminecraft.simplefactions.managers.FactionManager;
import net.tfminecraft.thievery.cache.Cache;
import net.tfminecraft.thievery.cache.LockpickTargetCache;
import net.tfminecraft.thievery.player.TargetKeyResolver;

public class GuildChecker {

    public static class LockpickAccessResult {
        public enum Type { ALLOWED, WARN, DENY }
        public final Type type;
        public final String message;
        private LockpickAccessResult(Type type, String message) {
            this.type = type;
            this.message = message;
        }
    }

    /**
     * Checks whether lockpicking is allowed based on the owner's guild/online status
     * and the targeted-lockpick cache window.
     */
    public static LockpickAccessResult checkLockpickAccess(UUID ownerUUID) {
        if (ownerUUID == null) return new LockpickAccessResult(LockpickAccessResult.Type.ALLOWED, null);
        if (!Cache.requireOwnerOnline) {
            return new LockpickAccessResult(LockpickAccessResult.Type.ALLOWED, null);
        }
        OfflinePlayer owner = Bukkit.getOfflinePlayer(ownerUUID);
        String ownerName = owner.getName();
        if (ownerName == null) return new LockpickAccessResult(LockpickAccessResult.Type.ALLOWED, null);

        Guild guild = FactionManager.getGuildByMember(ownerName);
        String targetKey = TargetKeyResolver.resolve(ownerUUID);
        String subjectName;
        boolean membersOnline;

        if (guild != null) {
            subjectName = guild.getName();
            membersOnline = false;
            for (String m : guild.getMembers()) {
                if (Bukkit.getPlayerExact(m) != null) { membersOnline = true; break; }
            }
        } else {
            subjectName = ownerName;
            membersOnline = owner.isOnline();
        }

        if (membersOnline) {
            LockpickTargetCache.refresh(targetKey);
            return new LockpickAccessResult(LockpickAccessResult.Type.ALLOWED, null);
        }

        if (LockpickTargetCache.isActive(targetKey)) {
            long remainingMs = LockpickTargetCache.getRemainingMs(targetKey);
            long minutes = remainingMs / 60000;
            long seconds = (remainingMs % 60000) / 1000;
            String msg = subjectName + " has no members online - " + minutes + "m " + seconds + "s remaining on lockpick window.";
            return new LockpickAccessResult(LockpickAccessResult.Type.WARN, msg);
        }

        String denyMsg = guild != null
                ? "Cannot lockpick - " + subjectName + " has no members online."
                : "Cannot lockpick - " + subjectName + " is not online.";
        return new LockpickAccessResult(LockpickAccessResult.Type.DENY, denyMsg);
    }
}
