package net.tfminecraft.thievery.owner;

import java.util.UUID;
import java.util.function.Supplier;

import org.bukkit.Bukkit;

/**
 * Chooses the {@link LockGuilds} source once at enable. SimpleFactions types live only in
 * {@link SimpleFactionsLockGuilds}, which is loaded only when SimpleFactions is installed and offers
 * {@code SimpleFactionsApi.lockGuild}.
 */
public final class LockGuildBridge {

    private static LockGuilds current = LockGuilds.UNAVAILABLE;
    private static boolean degraded = true;

    private LockGuildBridge() {}

    public static LockGuilds get() {
        return current;
    }

    /** True when the capability is missing, so guild and faction locks deny everyone except staff. */
    public static boolean degraded() {
        return degraded;
    }

    /** Probes SimpleFactions and installs the matching source. Returns whether {@code lockGuild} is available. */
    public static boolean probe() {
        return probe("net.tfminecraft.simplefactions.api.SimpleFactionsApi");
    }

    static boolean probe(String apiClass) {
        boolean installed = Bukkit.getPluginManager().getPlugin("SimpleFactions") != null;
        return install(installed && probeLockGuild(apiClass), SimpleFactionsLockGuilds::new);
    }

    static boolean install(boolean available, Supplier<LockGuilds> source) {
        degraded = !available;
        current = available ? source.get() : LockGuilds.UNAVAILABLE;
        return available;
    }

    static boolean probeLockGuild(String className) {
        try {
            Class.forName(className).getMethod("lockGuild", UUID.class);
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            return false;
        }
    }

    /** For tests and plugin reloads. */
    public static void set(LockGuilds source) {
        current = source;
        degraded = source == LockGuilds.UNAVAILABLE;
    }
}
