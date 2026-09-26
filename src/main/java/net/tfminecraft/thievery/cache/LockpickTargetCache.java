package net.tfminecraft.thievery.cache;

import java.util.HashMap;
import java.util.Map;

/**
 * Tracks "targeted" guilds/players that are currently being lockpicked.
 * Once a lockpick session starts while the owner's guild has members online,
 * the target key is registered for 10 minutes. Subsequent attempts while
 * no members are online but the window is still active are allowed (with a warning).
 */
public final class LockpickTargetCache {

    private LockpickTargetCache() {}

    private static final long WINDOW_MS = 10 * 60 * 1000L; // 10 minutes

    private static final Map<String, Long> cache = new HashMap<>();

    /** Registers or refreshes the 10-minute window for the given target key. */
    public static void refresh(String targetKey) {
        cache.put(targetKey, System.currentTimeMillis() + WINDOW_MS);
    }

    /** Returns true if the target key has an active (non-expired) window. */
    public static boolean isActive(String targetKey) {
        Long expiry = cache.get(targetKey);
        if (expiry == null) return false;
        if (System.currentTimeMillis() >= expiry) {
            cache.remove(targetKey);
            return false;
        }
        return true;
    }

    /** Returns remaining milliseconds for the target key's window, or 0 if inactive. */
    public static long getRemainingMs(String targetKey) {
        Long expiry = cache.get(targetKey);
        if (expiry == null) return 0;
        return Math.max(0, expiry - System.currentTimeMillis());
    }
}
