package net.tfminecraft.thievery.owner;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.tfminecraft.thievery.door.ContainerDataManager;
import net.tfminecraft.thievery.estate.EstateBridge;
import net.tfminecraft.thievery.estate.LockEstates;

/**
 * The lock gates in {@code onEnable}, run after the listeners are registered in guard mode. Any failed gate keeps
 * the guard on for the rest of the process; nothing retries it later.
 */
public final class LockStartup {

    private LockStartup() {}

    /** Returns whether locks reached FULL mode. */
    public static boolean start(LockStores stores, Logger log) {
        LockGuard.enter("starting up");
        LockQuarantine.reset();
        if (!EstateBridge.probe()) {
            log.warning("TLibs is older than 2.6.0, so locks take no part in estates: a dead owner keeps them.");
        }
        if (!LockGuildBridge.probe()) {
            log.warning("SimpleFactions' lockGuild is missing, so guild and faction locks deny everyone except staff."
                    + " Update SimpleFactions to 4.1.0 or later.");
        }
        if (!OwnerIdentity.heirApiPresent()) {
            return stay(log, "TLibs is older than 2.4.0", "Locks stay frozen: TLibs 2.4.0 or later is required for"
                    + " character lock owners. Update TLibs and restart.");
        }
        LockMigration migration = new LockMigration(stores, log, EstateBridge.get());
        LockMigration.Outcome outcome;
        try {
            if (migration.storeNewerThanJar()) {
                return stay(log, "the lock stores were written by a newer Thievery", "Locks stay frozen: the lock"
                        + " stores were written by a newer Thievery. Install that version or restore the stores.");
            }
            if (!OwnerIdentity.heirsReady()) {
                return stay(log, "restart required", "Locks stay frozen until a restart: character heirs were not"
                        + " ready when Thievery enabled. Restart the server once RPCharacters is loading normally.");
            }
            outcome = migration.run();
        } catch (Exception | LinkageError e) {
            // A LinkageError means a mismatched TLibs or SimpleFactions jar; hold the guard rather than fail open.
            log.log(Level.SEVERE, "Locks stay frozen until a restart: the lock migration failed. The next restart"
                    + " replays it.", e);
            LockGuard.enter("lock migration failed; restart required");
            return false;
        }
        LockGuard.lift(ContainerDataManager::clearOwnerCache);
        report(outcome.estates(), log);
        return true;
    }

    // Reports each decided estate after the pass; a failure here never touches the lock mode.
    private static void report(List<LockEstates.Report> reports, Logger log) {
        try {
            for (LockEstates.Report report : reports) {
                EstateBridge.get().report(report);
                log.info("Estate " + report.estate().deathId() + " (revision " + report.estate().revision()
                        + "): locks " + report.kind() + ", " + report.moved().size() + " moved in all, "
                        + (report.leftProperty() + report.leftGuild()) + " still with the testator.");
            }
        } catch (RuntimeException | LinkageError e) {
            log.log(Level.WARNING, "Could not report estate locks; the estate service asks again.", e);
        }
    }

    private static boolean stay(Logger log, String reason, String message) {
        log.severe(message);
        LockGuard.enter(reason);
        return false;
    }
}
