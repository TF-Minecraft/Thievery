package net.tfminecraft.thievery.estate;

import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.plugin.Plugin;

/**
 * Chooses the {@link LockEstates} source once at enable (design E5, 6a rule 11). TLibs estate types live only in
 * {@link TLibsLockEstates}, which is loaded only when TLibs offers the estate API; on an older TLibs locks simply
 * take no part in estates.
 */
public final class EstateBridge {

    private static LockEstates current = LockEstates.NONE;

    private EstateBridge() {}

    public static LockEstates get() {
        return current;
    }

    /** Probes TLibs and installs the matching source. Returns whether the estate API is available. */
    public static boolean probe() {
        return probe("net.tfminecraft.tlibs.character.estate.Estates", TLibsLockEstates::new);
    }

    static boolean probe(String apiClass, Supplier<LockEstates> source) {
        try {
            Class.forName(apiClass);
            Class.forName("net.tfminecraft.tlibs.character.estate.EstateParticipant");
            current = source.get();
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            current = LockEstates.NONE;
            return false;
        }
    }

    /** Registers the participant; a failure is logged and leaves Thievery out of estates. */
    public static void register(Plugin plugin, Logger log) {
        try {
            current.register(plugin);
        } catch (RuntimeException | LinkageError e) {
            log.log(Level.WARNING, "Locks could not join estates; they move only after a fix and a restart.", e);
        }
    }

    /** For tests. */
    public static void set(LockEstates source) {
        current = source;
    }
}
