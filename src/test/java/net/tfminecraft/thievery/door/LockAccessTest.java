package net.tfminecraft.thievery.door;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.UUID;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.tfminecraft.thievery.owner.LockGuildBridge;
import net.tfminecraft.thievery.owner.LockGuilds;
import net.tfminecraft.thievery.owner.LockOwner;
import net.tfminecraft.thievery.testsupport.FactionFixture;

class LockAccessTest {
    private final UUID account = UUID.randomUUID();
    private final UUID kira = UUID.randomUUID();
    private final UUID alt = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();
    private final LockOwner kiraLock = LockOwner.character(kira);
    private Player player;
    private FactionFixture identity;

    @BeforeEach
    void setUp() {
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(account);
        identity = new FactionFixture();
        identity.plays(player, kira);
    }

    @AfterEach
    void tearDown() {
        identity.close();
    }

    @Test
    void publicAndUnownedLocksAreOpenToEveryoneEvenWithoutACharacter() {
        identity.plays(player, null);
        assertTrue(LockAccess.canAccess(player, kiraLock, LockState.PUBLIC));
        assertTrue(LockAccess.canAccess(player, null, LockState.PRIVATE));
        assertTrue(LockAccess.canAccess(player, LockOwner.server(), LockState.PUBLIC));
    }

    @Test
    void onlyTheOwningCharactersCurrentSessionOwnsAPrivateLock() {
        assertTrue(LockAccess.canAccess(player, kiraLock, LockState.PRIVATE));
        assertTrue(LockAccess.isOwner(player, kiraLock));
        // The same account on another character is a stranger.
        identity.plays(player, alt);
        assertFalse(LockAccess.canAccess(player, kiraLock, LockState.PRIVATE));
        assertFalse(LockAccess.isOwner(player, kiraLock));
        // No current session owns nothing.
        identity.plays(player, null);
        assertFalse(LockAccess.isOwner(player, kiraLock));
        assertFalse(LockAccess.isOwner(player, null));
    }

    @Test
    void serverLegacyUnresolvedAndUnreadableOwnersDenyEveryoneOnNonPublicLocks() {
        for (LockOwner owner : new LockOwner[] { LockOwner.server(), LockOwner.legacy(account),
                LockOwner.unresolved(account), LockOwner.unreadable() }) {
            identity.guilds.member(account, "g1", "f1").member(kira, "g1", "f1");
            for (LockState state : new LockState[] { LockState.PRIVATE, LockState.GUILD, LockState.FACTION }) {
                assertFalse(LockAccess.canAccess(player, owner, state), owner + " " + state);
            }
            assertFalse(LockAccess.isOwner(player, owner));
            assertFalse(LockAccess.sharesLockGuild(player, owner));
        }
    }

    @Test
    void guildLocksCompareIncarnationsOfTheOwnersLockGuildAndTheOpenersCharacter() {
        identity.guilds.member(stranger, "inc-1", "realm").member(kira, "inc-1", "realm");
        LockOwner strangerLock = LockOwner.character(stranger);
        assertTrue(LockAccess.canAccess(player, strangerLock, LockState.GUILD));
        assertTrue(LockAccess.sharesLockGuild(player, strangerLock));
        // A recreated guild has a new incarnation, so it inherits nothing.
        identity.guilds.member(kira, "inc-2", "realm");
        assertFalse(LockAccess.canAccess(player, strangerLock, LockState.GUILD));
        assertFalse(LockAccess.sharesLockGuild(player, strangerLock));
        assertTrue(LockAccess.canAccess(player, strangerLock, LockState.FACTION));
        // The alt's guild is what counts when the alt is played.
        identity.guilds.member(alt, "inc-1", "realm");
        identity.plays(player, alt);
        assertTrue(LockAccess.canAccess(player, strangerLock, LockState.GUILD));
    }

    @Test
    void factionLocksNeedBothGuildsInTheSameFaction() {
        LockOwner strangerLock = LockOwner.character(stranger);
        identity.guilds.member(stranger, "a", "realm-1").member(kira, "b", "realm-2");
        assertFalse(LockAccess.canAccess(player, strangerLock, LockState.FACTION));
        identity.guilds.member(kira, "b", "realm-1");
        assertTrue(LockAccess.canAccess(player, strangerLock, LockState.FACTION));
        identity.guilds.member(stranger, "a", null);
        assertFalse(LockAccess.canAccess(player, strangerLock, LockState.FACTION));
        identity.guilds.member(stranger, "a", "realm-1").member(kira, "b", null);
        assertFalse(LockAccess.canAccess(player, strangerLock, LockState.FACTION));
    }

    @Test
    void aDeadOwnersGuildLockFollowsTheGuildTheyDiedIn() {
        // lockGuild answers for the dead from the death-guild index; Thievery only compares incarnations.
        identity.guilds.member(stranger, "died-in", "realm").member(kira, "died-in", "realm");
        assertTrue(LockAccess.canAccess(player, LockOwner.character(stranger), LockState.GUILD));
        identity.guilds.leave(stranger);
        assertFalse(LockAccess.canAccess(player, LockOwner.character(stranger), LockState.GUILD));
    }

    @Test
    void missingGuildsUnavailableLookupsAndNoSessionDeny() {
        LockOwner strangerLock = LockOwner.character(stranger);
        assertFalse(LockAccess.canAccess(player, strangerLock, LockState.GUILD));
        identity.guilds.member(stranger, "g", "f");
        assertFalse(LockAccess.canAccess(player, strangerLock, LockState.GUILD));
        identity.guilds.member(kira, "g", "f");
        identity.guilds.ready = false;
        assertFalse(LockAccess.canAccess(player, strangerLock, LockState.GUILD));
        assertFalse(LockAccess.sharesLockGuild(player, strangerLock));
        identity.guilds.ready = true;
        identity.plays(player, null);
        assertFalse(LockAccess.canAccess(player, strangerLock, LockState.GUILD));
        // Degraded: SimpleFactions has no lockGuild.
        identity.plays(player, kira);
        LockGuildBridge.set(LockGuilds.UNAVAILABLE);
        assertFalse(LockAccess.canAccess(player, strangerLock, LockState.FACTION));
        assertTrue(LockAccess.canAccess(player, kiraLock, LockState.FACTION));
    }

    @Test
    void sharingAGuildNeedsBothSidesInOne() {
        LockOwner strangerLock = LockOwner.character(stranger);
        assertFalse(LockAccess.sharesLockGuild(player, null));
        assertFalse(LockAccess.sharesLockGuild(player, strangerLock));
        identity.guilds.member(stranger, "g", null);
        assertFalse(LockAccess.sharesLockGuild(player, strangerLock));
    }
}
