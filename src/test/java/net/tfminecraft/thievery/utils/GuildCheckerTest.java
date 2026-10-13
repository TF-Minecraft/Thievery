package net.tfminecraft.thievery.utils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import net.tfminecraft.thievery.cache.Cache;
import net.tfminecraft.thievery.cache.LockpickTargetCache;
import net.tfminecraft.thievery.owner.LockGuildBridge;
import net.tfminecraft.thievery.owner.LockGuilds;
import net.tfminecraft.thievery.owner.LockOwner;
import net.tfminecraft.thievery.owner.OwnerIdentity;
import net.tfminecraft.thievery.testsupport.FactionFixture;
import net.tfminecraft.thievery.utils.GuildChecker.LockpickAccessResult.Type;

class GuildCheckerTest {
    FactionFixture identity;
    MockedStatic<LockpickTargetCache> window;
    boolean saved;
    final UUID owner = UUID.randomUUID();
    final LockOwner lock = LockOwner.character(owner);

    @BeforeEach void setUp() { saved = Cache.requireOwnerOnline; identity = new FactionFixture(); window = mockStatic(LockpickTargetCache.class); }
    @AfterEach void tearDown() { window.close(); identity.close(); Cache.requireOwnerOnline = saved; }

    @Test void unownedLocksArePickableAndServerQuarantinedAndUnreadableLocksNeverAre() {
        assertEquals(Type.ALLOWED, GuildChecker.checkLockpickAccess(null).type);
        for (LockOwner never : new LockOwner[] { LockOwner.server(), LockOwner.legacy(owner), LockOwner.unresolved(owner), LockOwner.unreadable() }) {
            var result = GuildChecker.checkLockpickAccess(never);
            assertEquals(Type.DENY, result.type);
            assertEquals(GuildChecker.UNPICKABLE, result.message);
        }
    }

    @Test void byDefaultOwnersNeedNotBeOnlineEvenWithoutFactions() {
        Cache.requireOwnerOnline = false;
        LockGuildBridge.set(LockGuilds.UNAVAILABLE);
        assertEquals(Type.ALLOWED, GuildChecker.checkLockpickAccess(lock).type);
    }

    @Test void requiringOwnersOnlineChecksTheLockGuildThenTheOwningCharacterAndNeverNamesThem() {
        Cache.requireOwnerOnline = true;
        identity.guilds.ready = false;
        assertEquals(GuildChecker.UNKNOWN, GuildChecker.checkLockpickAccess(lock).message);
        identity.guilds.ready = true;

        var away = GuildChecker.checkLockpickAccess(lock);
        assertEquals(Type.DENY, away.type);
        assertEquals(GuildChecker.AWAY, away.message);
        identity.identity().when(() -> OwnerIdentity.playing(owner)).thenReturn(true);
        assertEquals(Type.ALLOWED, GuildChecker.checkLockpickAccess(lock).type);
        window.verify(() -> LockpickTargetCache.refresh("player:" + owner));

        // In a guild, the guild's members count and the owner alone does not.
        identity.guild(owner, "night-watch", "realm");
        assertEquals(Type.DENY, GuildChecker.checkLockpickAccess(lock).type);
        identity.guilds.online("night-watch", true);
        assertEquals(Type.ALLOWED, GuildChecker.checkLockpickAccess(lock).type);
        window.verify(() -> LockpickTargetCache.refresh("guild:night-watch"));

        identity.guilds.online("night-watch", false);
        window.when(() -> LockpickTargetCache.isActive("guild:night-watch")).thenReturn(true);
        window.when(() -> LockpickTargetCache.getRemainingMs("guild:night-watch")).thenReturn(125_000L);
        var warn = GuildChecker.checkLockpickAccess(lock);
        assertEquals(Type.WARN, warn.type);
        assertTrue(warn.message.contains("2m 5s"));
        assertFalse(warn.message.contains("night-watch"));
    }

    @Test void aDeadOwnerWithoutALockGuildIsUnpickableWhenOwnersMustBeOnline() {
        Cache.requireOwnerOnline = true;
        // Nobody can play a dead character, and with no lock guild nobody else counts.
        assertEquals(Type.DENY, GuildChecker.checkLockpickAccess(lock).type);
        Cache.requireOwnerOnline = false;
        assertEquals(Type.ALLOWED, GuildChecker.checkLockpickAccess(lock).type);
    }
}
