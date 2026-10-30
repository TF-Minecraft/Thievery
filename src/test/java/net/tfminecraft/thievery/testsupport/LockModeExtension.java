package net.tfminecraft.thievery.testsupport;

import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import net.tfminecraft.thievery.estate.EstateBridge;
import net.tfminecraft.thievery.estate.LockEstates;
import net.tfminecraft.thievery.owner.HeldLockDisk;
import net.tfminecraft.thievery.owner.LockGuard;
import net.tfminecraft.thievery.owner.LockGuildBridge;
import net.tfminecraft.thievery.owner.LockGuilds;
import net.tfminecraft.thievery.owner.LockQuarantine;
import net.tfminecraft.thievery.owner.OwnedLocks;

/**
 * Every test starts with locks in FULL mode, no guild source, no estates, an empty quarantine, an empty owner index
 * and lock writes on the calling thread; guard tests and writer-thread tests opt in.
 */
public final class LockModeExtension implements BeforeEachCallback {
    @Override
    public void beforeEach(ExtensionContext context) {
        HeldLockDisk.uninstall(null);
        LockGuard.forceFull();
        LockGuildBridge.set(LockGuilds.UNAVAILABLE);
        LockQuarantine.reset();
        OwnedLocks.clear();
        EstateBridge.set(LockEstates.NONE);
    }
}
