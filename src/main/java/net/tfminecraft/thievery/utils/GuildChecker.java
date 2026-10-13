package net.tfminecraft.thievery.utils;

import net.tfminecraft.thievery.cache.Cache;
import net.tfminecraft.thievery.cache.LockpickTargetCache;
import net.tfminecraft.thievery.owner.LockGuildBridge;
import net.tfminecraft.thievery.owner.LockGuilds;
import net.tfminecraft.thievery.owner.LockOwner;
import net.tfminecraft.thievery.owner.OwnerIdentity;
import net.tfminecraft.thievery.player.TargetKeyResolver;

/** Whether a lock may be picked now. Its messages never name the owner. */
public final class GuildChecker {

    static final String UNPICKABLE = "This lock can't be picked.";
    static final String UNKNOWN = "There's no telling whether this lock's owners are about. Try again later.";
    static final String AWAY = "Cannot lockpick - this lock's owners are away.";

    private GuildChecker() {}

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
     * Unowned locks are always pickable; server, quarantined and unreadable locks never are. With
     * {@code require-owner-online}, a character's lock needs a member of its {@code lockGuild} online, or without a
     * guild the owning character in play, or an open lockpick window.
     */
    public static LockpickAccessResult checkLockpickAccess(LockOwner owner) {
        if (owner == null) return new LockpickAccessResult(LockpickAccessResult.Type.ALLOWED, null);
        if (!owner.isCharacter()) return new LockpickAccessResult(LockpickAccessResult.Type.DENY, UNPICKABLE);
        if (!Cache.requireOwnerOnline) {
            return new LockpickAccessResult(LockpickAccessResult.Type.ALLOWED, null);
        }
        LockGuilds guilds = LockGuildBridge.get();
        if (!guilds.ready()) return new LockpickAccessResult(LockpickAccessResult.Type.DENY, UNKNOWN);

        String targetKey = TargetKeyResolver.forLockOwner(owner);
        boolean online = guilds.lockGuild(owner.id()).isPresent()
                ? guilds.anyMemberOnline(owner.id())
                : OwnerIdentity.playing(owner.id());
        if (online) {
            LockpickTargetCache.refresh(targetKey);
            return new LockpickAccessResult(LockpickAccessResult.Type.ALLOWED, null);
        }

        if (LockpickTargetCache.isActive(targetKey)) {
            long remainingMs = LockpickTargetCache.getRemainingMs(targetKey);
            long minutes = remainingMs / 60000;
            long seconds = (remainingMs % 60000) / 1000;
            String msg = "This lock's owners are away - " + minutes + "m " + seconds + "s remaining on lockpick window.";
            return new LockpickAccessResult(LockpickAccessResult.Type.WARN, msg);
        }
        return new LockpickAccessResult(LockpickAccessResult.Type.DENY, AWAY);
    }
}
