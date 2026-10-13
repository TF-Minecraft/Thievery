package net.tfminecraft.thievery.owner;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.event.Cancellable;

/**
 * Guard mode (rule 6). Listeners are registered before any gate and check this flag on every event. In GUARD
 * the lock stores are frozen: the data managers neither save nor delete, and deletions wait in a queue until
 * the migration has finished.
 */
public final class LockGuard {

    public enum Mode {
        GUARD,
        FULL
    }

    public static final String STARTING = "Locks are starting up. Try again shortly.";

    private static Mode mode = Mode.GUARD;
    private static String reason = "starting up";
    private static final List<Path> pendingDeletions = new ArrayList<>();

    private LockGuard() {}

    public static Mode mode() {
        return mode;
    }

    public static boolean guarded() {
        return mode == Mode.GUARD;
    }

    /** Why the guard is still on, for staff. */
    public static String reason() {
        return reason;
    }

    /** Puts the guard back on, as at enable. */
    public static void enter(String why) {
        mode = Mode.GUARD;
        reason = why;
    }

    /** Called from the managers' delete paths while guarded. */
    public static void queueDeletion(Path path) {
        pendingDeletions.add(path);
    }

    public static List<Path> pendingDeletions() {
        return List.copyOf(pendingDeletions);
    }

    /**
     * Lifts the guard on the main thread once the migration has completed: queued deletions are applied, the
     * owner cache is cleared, and only then does the mode become FULL.
     */
    public static void lift(Runnable clearOwnerCache) {
        for (Path path : List.copyOf(pendingDeletions)) {
            try {
                DurableFiles.delete(path);
            } catch (IOException e) {
                logger().warning("Could not delete queued lock file " + path + ": " + e.getMessage());
            }
        }
        pendingDeletions.clear();
        clearOwnerCache.run();
        reason = null;
        mode = Mode.FULL;
    }

    /** For tests: FULL without a migration. */
    public static void forceFull() {
        pendingDeletions.clear();
        reason = null;
        mode = Mode.FULL;
    }

    /**
     * Runs a listener body on an ownership path. An exception cancels the event (rule 5), so a bug never lets a
     * player through a lock.
     */
    public static void failClosed(Cancellable event, String where, Runnable body) {
        try {
            body.run();
        } catch (RuntimeException e) {
            event.setCancelled(true);
            logger().log(Level.SEVERE, "Thievery cancelled " + where + " after an error", e);
        }
    }

    private static Logger logger() {
        return Bukkit.getLogger();
    }
}
