package net.tfminecraft.thievery.player;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.UUID;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.tfminecraft.thievery.owner.LockOwner;
import net.tfminecraft.thievery.testsupport.FactionFixture;

class TargetKeyResolverTest {
    FactionFixture identity;
    @BeforeEach void setUp() { identity = new FactionFixture(); }
    @AfterEach void tearDown() { identity.close(); }

    @Test void lockOwnersKeyByGuildIncarnationThenCharacterAndNeverCollapseToNone() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        assertEquals("player:" + a, TargetKeyResolver.forLockOwner(LockOwner.character(a)));
        assertNotEquals(TargetKeyResolver.forLockOwner(LockOwner.character(a)), TargetKeyResolver.forLockOwner(LockOwner.character(b)));
        identity.guild(a, "inc-a", "realm");
        assertEquals("guild:inc-a", TargetKeyResolver.forLockOwner(LockOwner.character(a)));
        identity.guilds.ready = false;
        assertEquals("player:" + a, TargetKeyResolver.forLockOwner(LockOwner.character(a)));
        assertEquals("unowned", TargetKeyResolver.forLockOwner(null));
        assertEquals("server", TargetKeyResolver.forLockOwner(LockOwner.server()));
        assertEquals("unresolved:" + a, TargetKeyResolver.forLockOwner(LockOwner.legacy(a)));
        assertEquals("unresolved:" + b, TargetKeyResolver.forLockOwner(LockOwner.unresolved(b)));
        assertEquals("unresolved:unreadable", TargetKeyResolver.forLockOwner(LockOwner.unreadable()));
        for (LockOwner owner : new LockOwner[] { null, LockOwner.server(), LockOwner.character(a), LockOwner.legacy(a), LockOwner.unreadable() }) {
            assertNotEquals("none", TargetKeyResolver.forLockOwner(owner));
        }
    }

    @Test void victimsKeyByTheirCurrentCharacterNotTheirAccount() {
        Player victim = mock(Player.class);
        UUID account = UUID.randomUUID(), kira = UUID.randomUUID(), alt = UUID.randomUUID();
        when(victim.getUniqueId()).thenReturn(account);
        identity.plays(victim, kira);
        assertEquals("player:" + kira, TargetKeyResolver.forVictim(victim));
        identity.plays(victim, alt);
        assertEquals("player:" + alt, TargetKeyResolver.forVictim(victim));
        identity.guild(alt, "alt-guild", null);
        assertEquals("guild:alt-guild", TargetKeyResolver.forVictim(victim));
        identity.plays(victim, null);
        assertEquals("unresolved:" + account, TargetKeyResolver.forVictim(victim));
    }
}
