package net.tfminecraft.thievery.testsupport;

import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import net.tfminecraft.thievery.estate.EstateBridge;
import net.tfminecraft.thievery.estate.LockEstates;
import net.tfminecraft.thievery.owner.LockGuard;
import net.tfminecraft.thievery.owner.LockGuildBridge;
import net.tfminecraft.thievery.owner.LockGuilds;
import net.tfminecraft.thievery.owner.LockQuarantine;

/**
 * Every test starts with locks in FULL mode, no guild source, no estates and an empty quarantine; guard tests opt
 * in.
 */
public final class LockModeExtension implements BeforeEachCallback {
    @Override
    public void beforeEach(ExtensionContext context) {
        LockGuard.forceFull();
        LockGuildBridge.set(LockGuilds.UNAVAILABLE);
        LockQuarantine.reset();
        EstateBridge.set(LockEstates.NONE);
    }
}
